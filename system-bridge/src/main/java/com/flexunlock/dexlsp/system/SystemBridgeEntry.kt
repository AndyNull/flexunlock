package com.flexunlock.dexlsp.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.flexunlock.dexlsp.system.runtime.CoverAppDisplayProfiles
import com.flexunlock.dexlsp.system.runtime.CoverAppLaunchProfilePolicy
import com.flexunlock.dexlsp.system.runtime.CoverDisplayConfigurationController
import com.flexunlock.dexlsp.system.runtime.CoverDisplayPolicy
import com.flexunlock.dexlsp.system.runtime.CoverDexToastPolicy
import com.flexunlock.dexlsp.system.runtime.DisplayChannelModePolicy
import com.flexunlock.dexlsp.system.runtime.ExternalDisplayDexPolicy
import com.flexunlock.dexlsp.system.runtime.CoverGoodLockPolicy
import com.flexunlock.dexlsp.system.runtime.CoverKeyguardLaunchPolicy
import com.flexunlock.dexlsp.system.runtime.CoverLaunchAllowlist
import com.flexunlock.dexlsp.system.runtime.CoverLockTransitionPolicy
import com.flexunlock.dexlsp.system.runtime.CoverKeyguardSurfacePolicy
import com.flexunlock.dexlsp.system.runtime.CoverManagerLaunchPolicy
import com.flexunlock.dexlsp.system.runtime.CoverRecentsPolicy
import com.flexunlock.dexlsp.system.runtime.CoverRotationLockPolicy
import com.flexunlock.dexlsp.system.runtime.CoverSessionCoordinator
import com.flexunlock.dexlsp.system.runtime.CoverSizeCompatPolicy
import com.flexunlock.dexlsp.system.runtime.CoverTimeoutPolicy
import com.flexunlock.dexlsp.system.runtime.CoverWakeBrightnessGatePolicy
import com.flexunlock.dexlsp.system.runtime.CoverWallpaperTokenPolicy
import com.flexunlock.dexlsp.system.runtime.FullDexImePolicy
import com.flexunlock.dexlsp.system.runtime.FullQsAppLaunchPolicy
import com.flexunlock.dexlsp.system.runtime.NativeSecondaryHomeRouter
import com.flexunlock.dexlsp.system.runtime.RuntimeFacts
import com.flexunlock.dexlsp.system.session.FoldState
import java.util.concurrent.atomic.AtomicBoolean
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val DEVICE_STATE_CLOSED = 0
private const val DEVICE_STATE_TENT = 1
private const val DEVICE_STATE_HALF_OPENED = 2
private const val DEVICE_STATE_OPENED = 3

internal fun foldStateOf(identifier: Int): FoldState = when (identifier) {
    DEVICE_STATE_CLOSED,
    DEVICE_STATE_TENT -> FoldState.CLOSED
    DEVICE_STATE_HALF_OPENED,
    DEVICE_STATE_OPENED -> FoldState.OPENED
    else -> FoldState.UNKNOWN
}

class SystemBridgeEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") return
        if (!installed.compareAndSet(false, true)) {
            log("duplicate system_server entry ignored")
            return
        }
        CoverDisplayConfigurationController.install(lpparam.classLoader)
        ExternalDisplayDexPolicy.install(lpparam)
        DisplayChannelModePolicy.install(lpparam)
        FullQsAppLaunchPolicy.install(lpparam)
        CoverDisplayPolicy.install(lpparam)
        CoverRotationLockPolicy.install(lpparam.classLoader)
        CoverTimeoutPolicy.install(lpparam.classLoader)
        CoverDexToastPolicy.install(lpparam)
        CoverGoodLockPolicy.install(lpparam)
        CoverManagerLaunchPolicy.install(lpparam)
        CoverAppLaunchProfilePolicy.install(lpparam)
        CoverLockTransitionPolicy.install(lpparam)
        CoverKeyguardSurfacePolicy.install(lpparam)
        CoverWakeBrightnessGatePolicy.install(lpparam.classLoader)
        CoverKeyguardLaunchPolicy.install(lpparam)
        CoverRecentsPolicy.install(lpparam)
        FullDexImePolicy.install(lpparam)
        CoverSizeCompatPolicy.install(lpparam)
        CoverWallpaperTokenPolicy.install(lpparam)
        com.flexunlock.dexlsp.CoverDisplayResolver.addListener { previous, current ->
            CoverDisplayConfigurationController.onResolverChanged(previous, current)
            ExternalDisplayDexPolicy.onTargetChanged(previous, current)
            CoverTimeoutPolicy.onCoverDisplayResolutionChanged(previous, current)
            CoverDisplayPolicy.onCoverDisplayResolutionChanged(previous, current)
            NativeSecondaryHomeRouter.onCoverDisplayResolutionChanged(previous, current)
            CoverSessionCoordinator.current()?.onCoverDisplayResolutionChanged(previous, current)
        }
        installDeviceStateObservation(lpparam)
        installClosedDexCompatibility(lpparam)
        NativeSecondaryHomeRouter.install(lpparam)
        installBootPhaseObservation(lpparam)
        installSystemServerBootstrap(lpparam)
        log("CLOSED native secondary Home bridge installed")
    }

    private fun installSystemServerBootstrap(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val systemServer = XposedHelpers.findClass(
                "com.android.server.SystemServer",
                lpparam.classLoader
            )
            val methods = systemServer.declaredMethods.filter { it.name == "startOtherServices" }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = XposedHelpers.getObjectField(
                            param.thisObject,
                            "mSystemContext"
                        ) as? Context ?: return
                        initializeCoordinator(context)
                    }
                })
            }
            log("system_server bootstrap installed methods=${methods.size}")
        }.onFailure { error ->
            log("system_server bootstrap unavailable: ${error.message}")
        }
    }

    private fun installBootPhaseObservation(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val systemServiceManager = XposedHelpers.findClass(
                "com.android.server.SystemServiceManager",
                lpparam.classLoader
            )
            val methods = systemServiceManager.declaredMethods.filter { method ->
                method.name == "startBootPhase" &&
                    method.parameterTypes.any { it == Int::class.javaPrimitiveType }
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val phase = param.args
                            .filterIsInstance<Number>()
                            .firstOrNull()
                            ?.toInt()
                            ?: return
                        if (phase >= PHASE_BOOT_COMPLETED) {
                            CoverSessionCoordinator.current()?.onBootCompletedPhase()
                        }
                    }
                })
            }
            log("boot phase observation installed methods=${methods.size}")
        }.onFailure { error ->
            log("boot phase observation unavailable: ${error.message}")
        }
    }

    private fun initializeCoordinator(context: Context) {
        grantDiagnosticPermissions(context)
        com.flexunlock.dexlsp.CoverDisplayResolver.initialize(context)
        val queriedState = currentFoldState()
        val initialState = RuntimeFacts.foldState.takeUnless { it == FoldState.UNKNOWN }
            ?: queriedState
        RuntimeFacts.updateFoldState(initialState)
        val handler = Handler(Looper.getMainLooper())
        CoverLaunchAllowlist.initialize(context, handler)
        CoverAppDisplayProfiles.initialize(context, handler)
        CoverAppLaunchProfilePolicy.bind(context)
        CoverDisplayConfigurationController.initialize(context, handler)
        ExternalDisplayDexPolicy.bind(context, handler)
        DisplayChannelModePolicy.bind(context)
        FullQsAppLaunchPolicy.bind(context)
        CoverDisplayPolicy.bind(context, handler)
        CoverRotationLockPolicy.bind(context)
        CoverTimeoutPolicy.restoreNativeCoverTimeout(context, ::log)
        CoverRecentsPolicy.bind(context)
        NativeSecondaryHomeRouter.bind(context)
        CoverSessionCoordinator.initialize(context, initialState)
        CoverDisplayPolicy.onFoldState(initialState)
    }

    private fun grantDiagnosticPermissions(context: Context) {
        listOf("android.permission.READ_LOGS", "android.permission.DUMP").forEach { permission ->
            runCatching {
                XposedHelpers.callMethod(
                    context.packageManager,
                    "grantRuntimePermission",
                    "com.flexunlock.dexlsp",
                    permission,
                    Process.myUserHandle()
                )
            }.onFailure { error ->
                log("diagnostic permission unavailable permission=$permission: ${error.message}")
            }
        }
    }

    private fun installDeviceStateObservation(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val service = XposedHelpers.findClass(
                "com.android.server.devicestate.DeviceStateManagerService",
                lpparam.classLoader
            )
            val baseStateMethods = service.declaredMethods.filter { method ->
                method.name == "setBaseState" &&
                    method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
            }
            baseStateMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val identifier = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        publishFoldState(identifier)
                    }
                })
            }

            val committedStateMethods = service.declaredMethods.filter { method ->
                method.name == "notifyDeviceStateInfoChangedAsync" && method.parameterCount == 0
            }
            committedStateMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val committed = runCatching {
                            val optional = XposedHelpers.getObjectField(
                                param.thisObject,
                                "mCommittedState"
                            ) as? java.util.Optional<*>
                            optional?.orElse(null)
                        }.getOrNull() ?: return
                        val identifier = runCatching {
                            XposedHelpers.callMethod(committed, "getIdentifier") as? Number
                        }.getOrNull()?.toInt() ?: return
                        publishFoldState(identifier)
                    }
                })
            }
            log(
                "device-state observation installed " +
                    "baseMethods=${baseStateMethods.size} committedMethods=${committedStateMethods.size}"
            )
        }.onFailure { error ->
            log("device-state observation unavailable: ${error.message}")
        }
    }

    private fun currentFoldState(): FoldState {
        val identifier = runCatching {
            val managerClass = Class.forName("android.hardware.devicestate.DeviceStateManagerGlobal")
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val state = managerClass.getMethod("getDeviceState").invoke(manager)
            when (state) {
                is Number -> state.toInt()
                null -> null
                else -> state.javaClass.methods
                    .firstOrNull { it.name == "getIdentifier" && it.parameterCount == 0 }
                    ?.invoke(state)
                    ?.let { it as? Number }
                    ?.toInt()
            }
        }.getOrNull()
        return identifier?.let(::foldStateOf) ?: FoldState.UNKNOWN
    }

    private fun publishFoldState(identifier: Int) {
        val state = foldStateOf(identifier)
        if (!RuntimeFacts.updateFoldState(state)) return
        DisplayChannelModePolicy.onFoldState(state)
        CoverDisplayPolicy.onFoldState(state)
        CoverSessionCoordinator.current()?.onFoldState(state)
        log("device state=$identifier fold=$state")
    }

    private fun installClosedDexCompatibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val dexObserver = XposedHelpers.findClass(
                "android.util.sysfwutil.DexObserver",
                lpparam.classLoader
            )
            listOf("isDexModeOn", "isSemiDexModeOn").forEach { methodName ->
                XposedHelpers.findAndHookMethod(
                    dexObserver,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (RuntimeFacts.isTargetSessionEligible()) param.result = true
                        }
                    }
                )
            }
            dexObserver.declaredMethods
                .filter { it.name == "checkDexStatebySysfs" }
                .forEach { method ->
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!RuntimeFacts.isTargetSessionEligible()) return
                            runCatching { XposedHelpers.setBooleanField(param.thisObject, "mDexMode", true) }
                            runCatching { XposedHelpers.setBooleanField(param.thisObject, "mSemiDexMode", true) }
                        }
                    })
                }
            log("target-display DexObserver compatibility installed")
        }.onFailure { error ->
            log("DexObserver compatibility unavailable: ${error.message}")
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }

    private companion object {
        val installed = AtomicBoolean(false)
        const val TAG = "FlexUnlock-SystemBridge"
        const val PHASE_BOOT_COMPLETED = 1000
    }
}
