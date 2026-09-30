package com.flexunlock.dexlsp

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

internal object NativeCoverNotificationPopupHooks {
    private const val SCOPE = "CoverNotificationPopup"
    private const val BIND_LISTENER_CLASS =
        "com.android.systemui.statusbar.notification.SubscreenNotificationController\$1"

    fun install(classLoader: ClassLoader) {
        val listenerClass = XposedHelpers.findClassIfExists(BIND_LISTENER_CLASS, classLoader)
            ?: return unavailable("native bind listener missing")

        runCatching {
            XposedBridge.hookAllMethods(
                listenerClass,
                "onViewBound",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        completeApprovedPopup(param.thisObject, param.args.firstOrNull() ?: return)
                    }
                }
            )
            CoverRuntime.log(SCOPE, "native display-1 notification popup completion installed")
        }.onFailure { unavailable(it.message) }
    }

    private fun completeApprovedPopup(listener: Any, entry: Any) {
        if (!CoverRuntime.isCoverUiSessionEligible()) return
        val controller = field(listener, "this\$0") ?: return
        if (booleanField(controller, "panelExpanded") == true) return
        val model = field(controller, "mDeviceModel") ?: return
        val key = field(entry, "mKey")?.toString() ?: return
        val approvedKeys = field(model, "showPopupEntryKeySet") as? Set<*> ?: return
        if (key !in approvedKeys) return

        val currentEntry = field(model, "currentPopupViewEntry")
        val currentKey = currentEntry?.let { field(it, "mKey")?.toString() }
        val alreadyShowing = booleanField(model, "popupViewShowing") == true && currentKey == key
        if (alreadyShowing) return

        runCatching {
            XposedHelpers.callMethod(model, "makeSubScreenNotification", entry)
            XposedHelpers.callMethod(model, "showSubscreenNotification")
            CoverRuntime.log(SCOPE, "native approved top popup completed key=$key")
        }.onFailure { unavailable("popup completion failed: ${it.message}") }
    }

    private fun field(instance: Any, name: String): Any? = runCatching {
        XposedHelpers.getObjectField(instance, name)
    }.getOrNull()

    private fun booleanField(instance: Any, name: String): Boolean? = runCatching {
        XposedHelpers.getBooleanField(instance, name)
    }.getOrNull()

    private fun unavailable(reason: String?) {
        CoverRuntime.log(SCOPE, "unavailable: ${reason ?: "unknown"}")
    }
}
