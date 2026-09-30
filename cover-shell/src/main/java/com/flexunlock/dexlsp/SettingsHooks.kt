package com.flexunlock.dexlsp

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal object SettingsHooks {
    private const val LOCK_UTILS_CLASS =
        "com.samsung.android.settings.lockscreen.LockUtils"
    private const val SCREEN_TIMEOUT_ACTIVITY_CLASS =
        "com.samsung.android.settings.display.ScreenTimeoutActivity"
    private const val POWER_SAVING_TIMEOUT_GATE = "pms_settings_screen_time_out_enabled"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        installAodAvailabilityGuard(lpparam)
        installScreenTimeoutPowerSavingGuard(lpparam)
        installCoverDisplayEligibility(lpparam)
    }

    private fun installAodAvailabilityGuard(lpparam: XC_LoadPackage.LoadPackageParam) {
        val lockUtils = XposedHelpers.findClassIfExists(LOCK_UTILS_CLASS, lpparam.classLoader)
            ?: return CoverRuntime.log("Settings", "LockUtils unavailable")

        runCatching {
            XposedBridge.hookAllMethods(
                lockUtils,
                "isAODBlockonSmartView",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                }
            )
            CoverRuntime.log("Settings", "AOD SmartView availability guard installed")
        }.onFailure {
            CoverRuntime.log(
                "Settings",
                "AOD SmartView availability guard unavailable: ${it.message}"
            )
        }
    }

    private fun installScreenTimeoutPowerSavingGuard(
        lpparam: XC_LoadPackage.LoadPackageParam
    ) {
        val timeoutActivity = XposedHelpers.findClassIfExists(
            SCREEN_TIMEOUT_ACTIVITY_CLASS,
            lpparam.classLoader
        ) ?: return CoverRuntime.log("Settings", "ScreenTimeoutActivity unavailable")

        runCatching {
            XposedBridge.hookAllMethods(
                timeoutActivity,
                "updateRelatedGUIByPowerSaving",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = XposedHelpers.getObjectField(
                            param.thisObject,
                            "mContext"
                        ) as? Context ?: return
                        val gateEnabled = Settings.Global.getInt(
                            context.contentResolver,
                            POWER_SAVING_TIMEOUT_GATE,
                            0
                        ) != 0
                        val powerSaving = context.getSystemService(PowerManager::class.java)
                            ?.isPowerSaveMode == true
                        if (!gateEnabled || powerSaving) return

                        XposedHelpers.setBooleanField(
                            param.thisObject,
                            "mItemEnabledPowerSaving",
                            true
                        )
                        (XposedHelpers.getObjectField(
                            param.thisObject,
                            "mLayoutPowerSavingDescription"
                        ) as? View)?.visibility = View.GONE
                        XposedHelpers.getObjectField(
                            param.thisObject,
                            "mPowerSavingDescriptionInsetCategory"
                        )?.let { inset ->
                            XposedHelpers.callMethod(inset, "setVisible", false)
                        }
                        XposedHelpers.getObjectField(param.thisObject, "mAdapter")?.let { adapter ->
                            XposedHelpers.callMethod(adapter, "notifyDataSetChanged")
                        }
                        CoverRuntime.log(
                            "Settings",
                            "ignored stale screen-timeout power-saving gate while Battery Saver is off"
                        )
                    }
                }
            )
            CoverRuntime.log("Settings", "screen-timeout power-saving guard installed")
        }.onFailure {
            CoverRuntime.log(
                "Settings",
                "screen-timeout power-saving guard unavailable: ${it.message}"
            )
        }
    }

    /**
     * 合盖伪 DeX 时,设置把 displayId != 0 当成 DeX 副屏,子页会 finish 或提示去内屏。
     * 只改设置进程的资格检查,不伪造全局 [android.view.Display.getDisplayId]。
     */
    private fun installCoverDisplayEligibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        val utils = XposedHelpers.findClassIfExists(
            "com.android.settings.Utils",
            lpparam.classLoader
        )
        if (utils != null) {
            hookBooleanEligibility(
                utils,
                "isDefaultDisplay",
                true,
                "cover default-display eligibility"
            )
            hookBooleanEligibility(
                utils,
                "isDesktopWindowing",
                false,
                "cover Dex windowing eligibility"
            )
        } else {
            CoverRuntime.log("Settings", "Utils unavailable for cover display eligibility")
        }

        val secDisplayUtils = XposedHelpers.findClassIfExists(
            "com.samsung.android.settings.display.SecDisplayUtils",
            lpparam.classLoader
        )
        if (secDisplayUtils != null) {
            hookBooleanEligibility(
                secDisplayUtils,
                "isDesktopWindowingAndNonDefaultDisplay",
                false,
                "cover Dex secondary-display guard"
            )
        }
    }

    private fun hookBooleanEligibility(
        target: Class<*>,
        methodName: String,
        result: Boolean,
        label: String
    ) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                target,
                methodName,
                Context::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isCoverSettingsDisplay(param.args.firstOrNull() as? Context)) {
                            return
                        }
                        param.result = result
                    }
                }
            )
            CoverRuntime.log("Settings", "$label installed")
        }.onFailure {
            CoverRuntime.log("Settings", "$label unavailable: ${it.message}")
        }
    }

    private fun isCoverSettingsDisplay(context: Context?): Boolean {
        if (context == null || !CoverRuntime.isCoverSessionEligible()) return false
        return CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))
    }
}