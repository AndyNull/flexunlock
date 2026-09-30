package com.flexunlock.dexlsp.system.runtime

import android.content.Context
import com.flexunlock.dexlsp.CoverQsModeConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal object FullQsAppLaunchPolicy {
    @Volatile private var context: Context? = null

    fun bind(systemContext: Context) {
        context = systemContext
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val starterClass = XposedHelpers.findClass(
            "com.android.server.wm.ActivityStarter",
            lpparam.classLoader
        )
        val methods = starterClass.declaredMethods.filter {
            it.name == "computeLaunchParams" && it.parameterCount == 3
        }
        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { routeToDefaultDisplay(param) }.onFailure { error ->
                        log("full QS app route failed: ${error.message}")
                    }
                }
            })
        }
        log("full QS app launch policy installed methods=${methods.size}")
    }

    private fun routeToDefaultDisplay(param: XC_MethodHook.MethodHookParam) {
        val systemContext = context ?: return
        if (
            !RuntimeFacts.isClosed() ||
            !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()
        ) return
        if (!CoverQsModeConfig.readTransaction(systemContext).isStableFull) return
        val record = param.args.firstOrNull() ?: return
        if (XposedHelpers.callMethod(record, "isActivityTypeHome") == true) return
        val packageName = XposedHelpers.getObjectField(record, "packageName") as? String ?: return
        if (packageName == SYSTEM_UI_PACKAGE) return

        val starter = param.thisObject
        val root = XposedHelpers.getObjectField(starter, "mRootWindowContainer")
        val defaultDisplay = XposedHelpers.getObjectField(root, "mDefaultDisplay")
        val taskDisplayArea = XposedHelpers.callMethod(defaultDisplay, "getDefaultTaskDisplayArea")
        XposedHelpers.setObjectField(starter, "mPreferredTaskDisplayArea", taskDisplayArea)
        val launchParams = XposedHelpers.getObjectField(starter, "mLaunchParams")
        XposedHelpers.setObjectField(launchParams, "mPreferredTaskDisplayArea", taskDisplayArea)
    }

    private fun log(message: String) {
        XposedBridge.log("FlexUnlock-SystemBridge: $message")
    }

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
}
