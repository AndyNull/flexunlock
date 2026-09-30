package com.flexunlock.dexlsp

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class CoverHookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE -> installWithDisplayResolver() {
                LauncherHooks.install(lpparam)
            }
            CoverRuntime.SYSTEM_UI_PACKAGE -> installWithDisplayResolver() {
                SystemUiHooks.install(lpparam)
            }
            "com.samsung.android.dialer" -> installWithDisplayResolver() {
                SamsungDialerHooks.install(lpparam)
            }
            "com.android.settings" -> installWithDisplayResolver() {
                SettingsHooks.install(lpparam)
            }
            CoverRuntime.ONE_HAND_OPERATION_PACKAGE -> installWithDisplayResolver() {
                OneHandOperationHooks.install(lpparam)
            }
            "com.sec.android.app.camera" -> installWithDisplayResolver() {
                SamsungCameraHooks.install(lpparam)
            }
            "com.tencent.wetype",
            "com.samsung.android.honeyboard" -> CoverImeHooks.install(lpparam)
            "android" -> if (lpparam.processName == "android") {
                com.flexunlock.dexlsp.system.SystemBridgeEntry().handleLoadPackage(lpparam)
            }
            CoverRuntime.GOOD_LOCK_PACKAGE,
            CoverRuntime.LOCKSTAR_PACKAGE,
            CoverRuntime.DRESSROOM_PACKAGE -> installWithDisplayResolver() {
                GoodLockHooks.install(lpparam)
            }
        }
    }

    private inline fun installWithDisplayResolver(
        installHooks: () -> Unit
    ) {
        CoverDisplayResolver.installProcess()
        installHooks()
    }
}
