package com.flexunlock.dexlsp.system.runtime

import android.app.ActivityOptions
import android.content.Intent
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object CoverManagerLaunchPolicy {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val ACTIVITY_STARTER_CLASS = "com.android.server.wm.ActivityStarter"
    private const val LSPOSED_MANAGER_CATEGORY = "org.lsposed.manager.LAUNCH_MANAGER"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val starterClass = XposedHelpers.findClassIfExists(
            ACTIVITY_STARTER_CLASS,
            lpparam.classLoader
        ) ?: return log("cover manager launch policy unavailable: ActivityStarter missing")

        runCatching {
            val methods = starterClass.declaredMethods.filter { method ->
                method.name == "setInitialState" &&
                    method.parameterTypes.firstOrNull()?.name ==
                    "com.android.server.wm.ActivityRecord" &&
                    method.parameterTypes.getOrNull(1) == ActivityOptions::class.java
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!RuntimeFacts.isTargetSessionEligible()) return
                        val activityRecord = param.args.firstOrNull() ?: return
                        val intent = field(activityRecord, "intent") as? Intent ?: return
                        if (!intent.categories.orEmpty().contains(LSPOSED_MANAGER_CATEGORY)) return
                        val coverDisplayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId()
                            ?: return

                        val options = (param.args.getOrNull(1) as? ActivityOptions)
                            ?: ActivityOptions.makeBasic().also { param.args[1] = it }
                        options.launchDisplayId = coverDisplayId
                        log(
                            "LSPosed manager launch routed to display-1 " +
                                "component=${intent.component} data=${intent.data}"
                        )
                    }
                })
            }
            log("CLOSED display-1 manager launch policy installed methods=${methods.size}")
        }.onFailure { error ->
            log("cover manager launch policy unavailable: ${error.message}")
        }
    }

    private fun field(instance: Any, name: String): Any? = runCatching {
        XposedHelpers.getObjectField(instance, name)
    }.getOrNull()

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
