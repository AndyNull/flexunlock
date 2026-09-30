package com.flexunlock.dexlsp.system.runtime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceControl
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Keeps display 1 opaque while native SubHome is becoming visible. */
internal object CoverKeyguardSurfacePolicy {
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val SUB_HOME_ACTIVITY = "com.android.systemui.subscreen.SubHomeActivity"
    private const val WINDOW_MANAGER_SERVICE = "com.android.server.wm.WindowManagerService"
    private const val POLL_INTERVAL_MILLIS = 24L
    private const val REQUIRED_READY_SAMPLES = 2
    private const val MAX_SAFETY_LAYER_HOLD_MILLIS = 1_500L
    private const val GATE_ARM_TIMEOUT_MILLIS = 280L
    private const val COLD_GATE_ARM_TIMEOUT_MILLIS = 450L
    private const val SAFETY_LAYER_NAME = "FlexUnlock display-1 Keyguard safety"
    private const val SAFETY_LAYER_Z = 1_000_000
    private const val WINDOW_DRAW_STATE_HAS_DRAWN = 4
    private const val SUB_LAUNCHER_WINDOW_TITLE = "SubLauncherWindow"

    private val handler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private val readinessProbeLock = Any()
    private var windowManagerService: Any? = null
    private var safetyLayer: SurfaceControl? = null
    private var safetyLayerShown = false
    private var pollQueued = false
    private var readinessMethod: Method? = null
    private var readinessCallbackType: Class<*>? = null
    private var readinessCallback: Any? = null
    private var readinessProbeResult: Any? = null
    private var readinessSubHomeSeen = false
    private var consecutiveReadySamples = 0
    private var lastReadinessDiagnostic: String? = null
    private var watchdogGeneration = 0L
    private var sleepHideGeneration = 0L
    private var firstFrameGateArmed = false
    private var gateDeadlineMs = 0L
    private var pendingGateDeadlineMs = 0L
    private var firstFrameGateGeneration = Long.MIN_VALUE
    private var coldWakeProtectionConsumed = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val serviceClass = XposedHelpers.findClassIfExists(
            WINDOW_MANAGER_SERVICE,
            lpparam.classLoader
        ) ?: return log("Keyguard safety layer unavailable: WindowManagerService missing")

        runCatching {
            serviceClass.declaredMethods
                .filter { method ->
                    method.name == "main" &&
                        java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.returnType == serviceClass
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            param.result?.let(::bind)
                        }
                    })
                }
            log("Keyguard safety layer binding installed")
        }.onFailure { error ->
            log("Keyguard safety layer binding unavailable: ${error.message}")
        }
    }

    fun prepareHiddenForSleep(): Boolean {
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return false
        val existingLayer = synchronized(stateLock) {
            safetyLayer?.takeIf { it.isValid }
        }
        if (existingLayer != null) {
            // 息屏时立即隐藏 safety layer:若延迟隐藏,黑色不透明层会在
            // 电源键息屏瞬间闪现,造成"整页闪黑"的视觉闪烁。
            // 实例保留供快速亮屏 armForPendingWake 直接复用。
            return runCatching {
                val transaction = SurfaceControl.Transaction()
                XposedHelpers.callMethod(transaction, "hide", existingLayer)
                val synchronousCommit = applyTransaction(transaction)
                synchronized(stateLock) {
                    safetyLayerShown = false
                    consecutiveReadySamples = 0
                    lastReadinessDiagnostic = null
                    firstFrameGateArmed = false
                    gateDeadlineMs = 0L
                    pendingGateDeadlineMs = 0L
                    firstFrameGateGeneration = Long.MIN_VALUE
                    watchdogGeneration++
                    sleepHideGeneration = watchdogGeneration
                }
                log(
                    "display-1 Keyguard safety layer hidden for sleep " +
                        "sync=$synchronousCommit"
                )
                true
            }.onFailure { error ->
                log("display-1 Keyguard safety layer hide for sleep failed: ${error.message}")
            }.getOrDefault(false)
        }
        val service = windowManagerService
            ?: return logAndFalse("Keyguard safety layer prepare unavailable: WMS not bound")
        return runCatching {
            val displayContent = displayContentOf(service)
                ?: error("display-1 content missing")
            val layerStack = layerStackOf(displayContent)
                ?: error("display-1 layer stack missing")
            val layer = createSafetyLayer(layerStack, shown = false)
            synchronized(stateLock) {
                safetyLayer = layer
                safetyLayerShown = false
                consecutiveReadySamples = 0
                lastReadinessDiagnostic = null
                firstFrameGateArmed = false
                gateDeadlineMs = 0L
                pendingGateDeadlineMs = 0L
                firstFrameGateGeneration = Long.MIN_VALUE
                watchdogGeneration++
                sleepHideGeneration = watchdogGeneration
            }
            log("display-1 Keyguard safety layer prepared hidden layerStack=$layerStack")
            true
        }.onFailure { error ->
            log("display-1 Keyguard safety layer prepare failed: ${error.message}")
        }.getOrDefault(false)
    }

    fun armForPendingWake(): Boolean = armSafetyLayer(
        resetReadiness = true,
        queuePoll = true,
        reason = "pending-wake"
    )

    private fun armSafetyLayer(
        resetReadiness: Boolean,
        queuePoll: Boolean,
        reason: String
    ): Boolean {
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return false
        val retainedLayer = synchronized(stateLock) {
            safetyLayer?.takeIf { it.isValid }
        }
        if (retainedLayer != null) {
            val shouldShow = synchronized(stateLock) { !safetyLayerShown }
            if (shouldShow) {
                val transaction = SurfaceControl.Transaction()
                XposedHelpers.callMethod(transaction, "show", retainedLayer)
                val synchronousCommit = applyTransaction(transaction)
                synchronized(stateLock) { safetyLayerShown = true }
                log(
                    "display-1 Keyguard safety layer armed from hidden " +
                        "reason=$reason sync=$synchronousCommit"
                )
            } else {
                log("display-1 Keyguard safety layer retained reason=$reason")
            }
            synchronized(stateLock) {
                if (resetReadiness) {
                    consecutiveReadySamples = 0
                    lastReadinessDiagnostic = null
                    firstFrameGateArmed = false
                    gateDeadlineMs = gateDeadlineForArm()
                }
            }
            if (queuePoll) queueReadinessPoll()
            scheduleSafetyLayerWatchdog(reason)
            return true
        }
        val service = windowManagerService
            ?: return logAndFalse("Keyguard safety layer unavailable: WMS not bound")
        return runCatching {
            val displayContent = displayContentOf(service)
                ?: error("display-1 content missing")
            val layerStack = layerStackOf(displayContent)
                ?: error("display-1 layer stack missing")
            val layer = createSafetyLayer(layerStack, shown = true)
            synchronized(stateLock) {
                safetyLayer = layer
                safetyLayerShown = true
                consecutiveReadySamples = 0
                lastReadinessDiagnostic = null
                if (resetReadiness) {
                    firstFrameGateArmed = false
                    gateDeadlineMs = gateDeadlineForArm()
                }
            }
            log(
                "display-1 Keyguard safety layer armed " +
                    "layerStack=$layerStack reason=$reason"
            )
            if (queuePoll) queueReadinessPoll()
            scheduleSafetyLayerWatchdog(reason)
            true
        }.onFailure { error ->
            log("display-1 Keyguard safety layer arm failed reason=$reason: ${error.message}")
        }.getOrDefault(false)
    }

    private fun gateDeadlineForArm(): Long {
        val now = SystemClock.elapsedRealtime()
        val coldDeadline = if (!coldWakeProtectionConsumed) {
            coldWakeProtectionConsumed = true
            log(
                "display-1 cold first-wake protection armed " +
                    "timeoutMs=$COLD_GATE_ARM_TIMEOUT_MILLIS"
            )
            now + COLD_GATE_ARM_TIMEOUT_MILLIS
        } else {
            now + GATE_ARM_TIMEOUT_MILLIS
        }
        return maxOf(coldDeadline, pendingGateDeadlineMs)
    }

    fun isReady(): Boolean = runCatching {
        val service = windowManagerService ?: return false
        val displayContent = displayContentOf(service) ?: return false
        if (findReadySubHomeWindow(displayContent) != null) return true
        if (synchronized(readinessProbeLock) { readinessSubHomeSeen }) return false
        val ownerReady =
            NativeSecondaryHomeRouter.isCoverKeyguardShowing() &&
                NativeSecondaryHomeRouter.observedCoverHomeOwner() ==
                NativeSecondaryHomeRouter.CoverHomeOwner.SYSTEM_UI_SUB_HOME
        if (ownerReady) {
            recordReadinessDiagnostic(
                "SubHome top owner accepted; One UI window-shape probe unavailable"
            )
        }
        ownerReady
    }.getOrDefault(false)

    /**
     * SystemUI 侧首帧门控 armed 信号。safety layer 释放前需要等待该信号,
     * 避免锁屏页进入动画(横向滑动)在门控生效前可见。
     */
    fun onFirstFrameGatePending(generation: Long) {
        synchronized(stateLock) {
            if (generation < firstFrameGateGeneration) return
            firstFrameGateGeneration = generation
            pendingGateDeadlineMs =
                SystemClock.elapsedRealtime() + COLD_GATE_ARM_TIMEOUT_MILLIS
            if (!firstFrameGateArmed) {
                gateDeadlineMs = maxOf(gateDeadlineMs, pendingGateDeadlineMs)
            }
        }
        log("display-1 Keyguard cold first-frame gate pending generation=$generation")
    }

    fun onFirstFrameGateArmed(generation: Long) {
        synchronized(stateLock) {
            if (generation < firstFrameGateGeneration) return
            firstFrameGateGeneration = generation
            firstFrameGateArmed = true
            pendingGateDeadlineMs = 0L
        }
        log("display-1 Keyguard first-frame gate armed observed generation=$generation")
    }

    fun isFirstFrameGateArmed(): Boolean = synchronized(stateLock) {
        firstFrameGateArmed
    }

    fun resetFirstFrameGateArmed() {
        synchronized(stateLock) {
            firstFrameGateArmed = false
            gateDeadlineMs = 0L
            pendingGateDeadlineMs = 0L
            firstFrameGateGeneration = Long.MIN_VALUE
        }
    }

    fun hasSafetyLayer(): Boolean = synchronized(stateLock) {
        safetyLayer?.isValid == true
    }

    fun isReleaseReady(): Boolean = synchronized(stateLock) {
        safetyLayer?.isValid == true &&
            consecutiveReadySamples >= REQUIRED_READY_SAMPLES &&
            (firstFrameGateArmed || SystemClock.elapsedRealtime() >= gateDeadlineMs)
    }

    fun releaseWhenReady(): Boolean = releaseSafetyLayer(
        force = false,
        reason = "native-SubHome-draw"
    )

    fun forceRelease(reason: String): Boolean = releaseSafetyLayer(
        force = true,
        reason = reason
    )

    private fun releaseSafetyLayer(force: Boolean, reason: String): Boolean {
        val layer = synchronized(stateLock) {
            val current = safetyLayer ?: return false
            if (!force && consecutiveReadySamples < REQUIRED_READY_SAMPLES) {
                return false
            }
            if (
                !force &&
                !firstFrameGateArmed &&
                SystemClock.elapsedRealtime() < gateDeadlineMs
            ) {
                return false
            }
            safetyLayer = null
            safetyLayerShown = false
            consecutiveReadySamples = 0
            watchdogGeneration++
            current
        }
        return runCatching {
            val transaction = SurfaceControl.Transaction()
            XposedHelpers.callMethod(transaction, "remove", layer)
            transaction.apply()
            log("display-1 Keyguard safety layer released reason=$reason force=$force")
            true
        }.onFailure { error ->
            log("display-1 Keyguard safety layer release failed: ${error.message}")
        }.getOrDefault(false)
    }

    private fun scheduleSafetyLayerWatchdog(reason: String) {
        val generation = synchronized(stateLock) {
            ++watchdogGeneration
        }
        handler.postDelayed(
            {
                val active = synchronized(stateLock) {
                    generation == watchdogGeneration && safetyLayer?.isValid == true
                }
                if (active) {
                    CoverTimeoutPolicy.onCoverKeyguardSurfaceWatchdog(reason)
                }
            },
            MAX_SAFETY_LAYER_HOLD_MILLIS
        )
    }

    private fun queueReadinessPoll() {
        synchronized(stateLock) {
            if (pollQueued) return
            pollQueued = true
        }
        handler.post(readinessPoll)
    }

    private val readinessPoll = object : Runnable {
        override fun run() {
            synchronized(stateLock) { pollQueued = false }
            if (safetyLayer == null) return
            consecutiveReadySamples = if (isReady()) {
                consecutiveReadySamples + 1
            } else {
                0
            }
            if (consecutiveReadySamples >= REQUIRED_READY_SAMPLES) {
                CoverTimeoutPolicy.onCoverKeyguardSurfaceReady()
                if (!hasSafetyLayer()) return
            }
            handler.postDelayed(this, POLL_INTERVAL_MILLIS)
            synchronized(stateLock) { pollQueued = true }
        }
    }

    private fun bind(service: Any) {
        windowManagerService = service
        log("Keyguard safety layer bound to WindowManagerService")
    }

    private fun displayContentOf(service: Any): Any? {
        val displayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId() ?: return null
        val root = XposedHelpers.getObjectField(service, "mRoot")
        return XposedHelpers.callMethod(root, "getDisplayContent", displayId)
    }

    private fun layerStackOf(displayContent: Any): Int? {
        val display = runCatching {
            XposedHelpers.getObjectField(displayContent, "mDisplay")
        }.getOrNull()
        val direct = display?.let {
            runCatching {
                (XposedHelpers.callMethod(it, "getLayerStack") as? Number)?.toInt()
            }.getOrNull()
        }
        if (direct != null) return direct
        return runCatching {
            val displayInfo = XposedHelpers.getObjectField(displayContent, "mDisplayInfo")
            XposedHelpers.getIntField(displayInfo, "layerStack")
        }.getOrNull()
    }

    private fun findReadySubHomeWindow(displayContent: Any): Any? =
        synchronized(readinessProbeLock) {
            readinessProbeResult = null
            readinessSubHomeSeen = false
            val callbackType = readinessCallbackType ?: Class.forName(
                "com.android.internal.util.ToBooleanFunction",
                false,
                displayContent.javaClass.classLoader
            ).also { readinessCallbackType = it }
            val callback = readinessCallback ?: Proxy.newProxyInstance(
                callbackType.classLoader,
                arrayOf(callbackType)
            ) { _, _, args ->
                val window = args?.firstOrNull()
                if (
                    readinessProbeResult == null &&
                    window != null &&
                    isReadySubHomeWindow(window)
                ) {
                    readinessProbeResult = window
                }
                false
            }.also { readinessCallback = it }
            val method = readinessMethod ?: displayContent.javaClass.methods
                .firstOrNull { candidate ->
                    candidate.name == "forAllWindows" &&
                        candidate.parameterTypes.size == 2 &&
                        candidate.parameterTypes[0] == callbackType
                }
                ?.also { it.isAccessible = true }
                ?.also { readinessMethod = it }
                ?: return@synchronized null
            runCatching { method.invoke(displayContent, callback, true) }
                .onFailure { recordReadinessDiagnostic("forAllWindows failed: ${it.message}") }
            readinessProbeResult
        }

    private fun isReadySubHomeWindow(window: Any): Boolean {
        val activity = activityRecordOf(window) ?: return false
        val component = runCatching {
            XposedHelpers.getObjectField(activity, "mActivityComponent")
                as? android.content.ComponentName
        }.getOrNull() ?: return false
        if (
            component.packageName != SYSTEM_UI_PACKAGE ||
            component.className != SUB_HOME_ACTIVITY
        ) return false

        val attrs = runCatching {
            XposedHelpers.getObjectField(window, "mAttrs") as? WindowManager.LayoutParams
        }.getOrNull() ?: return false
        val windowTitle = attrs.title?.toString()
        val isSubLauncherPanel =
            attrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_PANEL &&
                windowTitle == SUB_LAUNCHER_WINDOW_TITLE
        val isSubHomeBaseWindow =
            attrs.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
        if (!isSubLauncherPanel && !isSubHomeBaseWindow) return false
        readinessSubHomeSeen = true

        val hasSurface = booleanMethod(window, "hasSurface")
            ?: getBooleanField(window, "mHasSurface")
            ?: false
        val visible = booleanMethod(window, "isVisible") ?: false
        val drawState = runCatching {
            val animator = XposedHelpers.getObjectField(window, "mWinAnimator")
            XposedHelpers.getIntField(animator, "mDrawState")
        }.getOrDefault(-1)
        val drawn = drawState == WINDOW_DRAW_STATE_HAS_DRAWN
        if (hasSurface && visible && drawn) return true

        recordReadinessDiagnostic(
            "SubHome window not ready surface=$hasSurface visible=$visible " +
                "drawState=$drawState"
        )
        return false
    }

    private fun activityRecordOf(window: Any): Any? {
        var current: Any? = window
        repeat(4) {
            val candidate = current ?: return@repeat
            val activity = runCatching {
                XposedHelpers.getObjectField(candidate, "mActivityRecord")
            }.getOrNull()
            if (activity != null) return activity
            current = runCatching {
                XposedHelpers.getObjectField(candidate, "mParentWindow")
            }.getOrNull()
        }
        return null
    }

    private fun createSafetyLayer(layerStack: Int, shown: Boolean): SurfaceControl {
        val builder = SurfaceControl.Builder()
        XposedHelpers.callMethod(builder, "setColorLayer")
        val layer = builder
            .setName(SAFETY_LAYER_NAME)
            .setOpaque(true)
            .build()
        val transaction = SurfaceControl.Transaction()
        XposedHelpers.callMethod(transaction, "setLayerStack", layer, layerStack)
        transaction
            .setLayer(layer, SAFETY_LAYER_Z)
            .setAlpha(layer, 1f)
        XposedHelpers.callMethod(transaction, "setColor", layer, floatArrayOf(0f, 0f, 0f))
        if (shown) {
            XposedHelpers.callMethod(transaction, "show", layer)
        }
        applyTransaction(transaction)
        return layer
    }

    private fun applyTransaction(transaction: SurfaceControl.Transaction): Boolean {
        val synchronous = runCatching {
            XposedHelpers.callMethod(transaction, "apply", true)
        }.isSuccess
        if (!synchronous) {
            transaction.apply()
        }
        return synchronous
    }

    private fun booleanMethod(instance: Any, name: String): Boolean? = runCatching {
        XposedHelpers.callMethod(instance, name) as? Boolean
    }.getOrNull()

    private fun getBooleanField(instance: Any, name: String): Boolean? = runCatching {
        XposedHelpers.getBooleanField(instance, name)
    }.getOrNull()

    private fun recordReadinessDiagnostic(message: String) {
        synchronized(stateLock) {
            if (lastReadinessDiagnostic == message) return
            lastReadinessDiagnostic = message
        }
        log("display-1 Keyguard readiness: $message")
    }

    private fun logAndFalse(message: String): Boolean {
        log(message)
        return false
    }

    private fun log(message: String) {
        android.util.Log.i("FlexUnlock-SystemBridge", message)
    }
}
