package com.flexunlock.dexlsp.system.runtime

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Keeps the Keyguard boundary at ActivityTaskManager, before a cover App can
 * create or resume a task. This is intentionally independent from App UI.
 */
object CoverKeyguardLaunchPolicy {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val ACTIVITY_STARTER_CLASS = "com.android.server.wm.ActivityStarter"
    private const val ACTIVITY_RECORD_CLASS = "com.android.server.wm.ActivityRecord"
    private const val TASK_CLASS = "com.android.server.wm.Task"
    private const val COVER_LAUNCHER_TASK_UTILS =
        "com.android.server.wm.CoverLauncherTaskUtils"
    private const val DEFAULT_DISPLAY_ID = 0
    private const val INVALID_DISPLAY_ID = -1
    private const val START_ABORTED = 102
    private const val FIRST_APPLICATION_UID = 10000
    private const val SAMSUNG_LAUNCHER_PACKAGE = "com.sec.android.app.launcher"
    private const val SECONDARY_LAUNCHER_ACTIVITY =
        "com.honeyspace.dexservice.SecondaryLauncher"

    private val systemPackages = setOf(
        "android",
        "com.android.keyguard",
        "com.android.systemui"
    )

    private val trustedKeyguardWidgetCallers = setOf(
        "com.android.keyguard",
        "com.android.systemui",
        "com.samsung.android.app.aodservice",
        "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.multistar"
    )

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        installCoverLauncherWidgetLaunchObservation(lpparam)
        val starterClass = XposedHelpers.findClassIfExists(
            ACTIVITY_STARTER_CLASS,
            lpparam.classLoader
        ) ?: return log("Keyguard App launch policy unavailable: ActivityStarter missing")

        runCatching {
            val methods = starterClass.declaredMethods.filter { method ->
                method.name == "isAllowedToStart" &&
                    method.parameterCount == 3 &&
                    method.parameterTypes[0].name == ACTIVITY_RECORD_CLASS &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes[2].name == TASK_CLASS
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (
                            !RuntimeFacts.isClosed() ||
                            !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()
                        ) return
                        val activityRecord = param.args.firstOrNull() ?: return
                        val displayId = displayIdOf(param.thisObject, activityRecord)
                            ?: return
                        if (!com.flexunlock.dexlsp.CoverDisplayResolver.matches(displayId)) return
                        val accessRestricted = CoverLockTransitionPolicy.isAccessRestricted() ||
                            isKeyguardLocked(param.thisObject, displayId)
                        if (!accessRestricted) return
                        if (isTrustedKeyguardWidgetLaunch(param.thisObject, activityRecord)) {
                            val component = componentOf(activityRecord) as ComponentName
                            NativeSecondaryHomeRouter.markTrustedKeyguardAppLaunch(component)
                            log(
                                "allowing trusted lockscreen widget launch behind Keyguard " +
                                    "component=$component " +
                                    "callingPackage=${callingPackageOf(param.thisObject)}"
                            )
                            return
                        }
                        if (!isBlockedApplication(activityRecord)) return

                        param.result = START_ABORTED
                        log(
                            "blocked restricted display-1 App/Home launch " +
                                "pending=${CoverLockTransitionPolicy.isLockPending()} " +
                                "component=${componentOf(activityRecord)} " +
                                "callingPackage=${callingPackageOf(param.thisObject)}"
                        )
                    }
                })
            }
            log("locked display-1 App launch policy installed methods=${methods.size}")
        }.onFailure { error ->
            log("Keyguard App launch policy unavailable: ${error.message}")
        }
    }

    private fun installCoverLauncherWidgetLaunchObservation(
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        val utilsClass = XposedHelpers.findClassIfExists(
            COVER_LAUNCHER_TASK_UTILS,
            lpparam.classLoader
        ) ?: return log("CoverLauncher widget launch observation unavailable")
        val methods = utilsClass.declaredMethods.filter { method ->
            method.name == "startActivityFromSubDisplay" &&
                method.parameterTypes.lastOrNull() == Intent::class.java
        }
        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!RuntimeFacts.isClosed() ||
                        !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() ||
                        !CoverLockTransitionPolicy.isAccessRestricted()
                    ) return
                    val intent = param.args.lastOrNull() as? Intent ?: return
                    val component = intent.component ?: return
                    NativeSecondaryHomeRouter.markTrustedKeyguardAppLaunch(component)
                }
            })
        }
        log("CoverLauncher widget launch observation installed methods=${methods.size}")
    }

    private fun displayIdOf(starter: Any, activityRecord: Any): Int? {
        val preferredTaskDisplayArea = field(starter, "mPreferredTaskDisplayArea")
        val displayContent = preferredTaskDisplayArea?.let { field(it, "mDisplayContent") }
        val selectedDisplayId = displayContent?.let { intField(it, "mDisplayId") }
        if (selectedDisplayId != null) return selectedDisplayId

        // 字段或方法形状漂移时无法证明目标属于外屏。保持系统原行为，
        // 不得把未知 display 伪装成外屏后拦截主屏 Activity。
        val fallback = runCatching {
            (XposedHelpers.callMethod(activityRecord, "getDisplayId") as? Number)?.toInt()
        }.getOrNull()
        if (fallback != null && fallback != INVALID_DISPLAY_ID) return fallback

        log("cover launch display resolution unavailable; policy skipped")
        return null
    }

    private fun isKeyguardLocked(starter: Any, displayId: Int): Boolean {
        val service = field(starter, "mService") ?: return true
        val keyguardController = field(service, "mKeyguardController") ?: return true
        return runCatching {
            val selectedDisplayLocked = XposedHelpers.callMethod(
                keyguardController,
                "isKeyguardLocked",
                displayId
            ) as Boolean
            if (selectedDisplayLocked || displayId == DEFAULT_DISPLAY_ID) {
                selectedDisplayLocked
            } else {
                XposedHelpers.callMethod(
                    keyguardController,
                    "isKeyguardLocked",
                    DEFAULT_DISPLAY_ID
                ) as Boolean
            }
        }.getOrElse { error ->
            log("locked display-1 Keyguard lookup failed; blocking launch: ${error.message}")
            true
        }
    }

    private fun isTrustedKeyguardWidgetLaunch(
        starter: Any,
        activityRecord: Any
    ): Boolean {
        val component = componentOf(activityRecord) as? ComponentName ?: return false
        if (isActivityTypeHome(activityRecord)) return false
        val callingPackage = callingPackageOf(starter)
        return callingPackage in trustedKeyguardWidgetCallers ||
            NativeSecondaryHomeRouter.isTrustedKeyguardAppLaunch(component)
    }

    private fun isActivityTypeHome(activityRecord: Any): Boolean = runCatching {
        XposedHelpers.callMethod(activityRecord, "isActivityTypeHome") as Boolean
    }.getOrDefault(false)

    private fun isBlockedApplication(activityRecord: Any): Boolean {
        val component = componentOf(activityRecord) as? ComponentName
        if (
            component?.packageName == SAMSUNG_LAUNCHER_PACKAGE &&
            component.className == SECONDARY_LAUNCHER_ACTIVITY
        ) {
            return true
        }

        val isHome = isActivityTypeHome(activityRecord)
        // 仅放行受信任的系统 Home(SubHomeActivity 等已在 systemPackages 分支放行);
        // 不再无条件放行任意第三方 HOME,避免锁屏时第三方 Home 前台化盖住 Keyguard 壳。
        if (isHome && component?.packageName in systemPackages) return false

        if (component?.packageName in systemPackages) return false

        val activityInfo = field(activityRecord, "info") ?: return true
        val applicationInfo = field(activityInfo, "applicationInfo") as? ApplicationInfo
            ?: return true
        if (applicationInfo.uid < FIRST_APPLICATION_UID) return false
        return !isSignedWithPlatformKey(applicationInfo)
    }

    private fun isSignedWithPlatformKey(applicationInfo: ApplicationInfo): Boolean =
        runCatching {
            ApplicationInfo::class.java
                .getMethod("isSignedWithPlatformKey")
                .invoke(applicationInfo) as Boolean
        }.getOrElse { error ->
            log("platform signature lookup failed; treating App as external: ${error.message}")
            false
        }

    private fun componentOf(activityRecord: Any): Any? = field(activityRecord, "mActivityComponent")

    private fun callingPackageOf(starter: Any): String? {
        val request = field(starter, "mRequest") ?: return null
        return field(request, "callingPackage") as? String
    }

    private fun field(instance: Any, name: String): Any? = runCatching {
        XposedHelpers.getObjectField(instance, name)
    }.getOrNull()

    private fun intField(instance: Any, name: String): Int? = runCatching {
        XposedHelpers.getIntField(instance, name)
    }.getOrNull()

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
