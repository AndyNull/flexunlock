package com.flexunlock.dexlsp.system.runtime

import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

object CoverDexToastPolicy {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val NOTIFICATION_MANAGER_SERVICE =
        "com.android.server.notification.NotificationManagerService"
    private val suppressionLogged = AtomicBoolean(false)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val hooked = (1..40).sumOf { suffix ->
                val candidate = XposedHelpers.findClassIfExists(
                    "$NOTIFICATION_MANAGER_SERVICE\$$suffix",
                    lpparam.classLoader
                ) ?: return@sumOf 0
                candidate.declaredMethods
                    .filter { method ->
                        method.name == "enqueueTextToastForDex" ||
                            method.name == "enqueueToastForDex"
                    }
                    .onEach { method ->
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!RuntimeFacts.isTargetSessionEligible()) return
                                if (!isQuickPanelCompatibilityToast(param.args)) return
                                param.result = null
                                if (suppressionLogged.compareAndSet(false, true)) {
                                    log(
                                        "display-1 stale QuickPanel DeX compatibility Toast " +
                                            "suppressed method=${method.name}"
                                    )
                                }
                            }
                        })
                    }
                    .size
            }
            log("display-1 QuickPanel DeX Toast policy installed methods=$hooked")
        }.onFailure { error ->
            log("display-1 QuickPanel DeX Toast policy unavailable: ${error.message}")
        }
    }

    private fun isQuickPanelCompatibilityToast(args: Array<Any?>): Boolean {
        val payload = args
            .filterIsInstance<CharSequence>()
            .joinToString(separator = " ")
            .lowercase()
        if (payload.isBlank()) return false

        val identifiesQuickPanel = listOf(
            "quickpanel",
            "quick panel",
            "subscreenquickpanel"
        ).any(payload::contains)
        val identifiesCompatibilityWarning = listOf(
            "not optimized",
            "not optimised",
            "latest version",
            "may not work correctly",
            "未针对最新",
            "无法正常运行",
            "可能无法正常",
            "兼容"
        ).any(payload::contains)
        return identifiesQuickPanel && identifiesCompatibilityWarning
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
