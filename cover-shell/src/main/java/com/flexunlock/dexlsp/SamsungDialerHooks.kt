package com.flexunlock.dexlsp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal object SamsungDialerHooks {
    private const val SCOPE = "SamsungDialer"
    private const val DIALTACTS_ACTIVITY =
        "com.samsung.android.dialer.DialtactsActivity"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onPostResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.javaClass.name != DIALTACTS_ACTIVITY) return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity))) return
                        bindDialButtonLayout(activity)
                    }
                }
            )
            CoverRuntime.log(SCOPE, "display-1 dial button compatibility hook installed")
        }.onFailure {
            CoverRuntime.log(SCOPE, "dial button hook unavailable: ${it.message}")
        }
    }

    private fun bindDialButtonLayout(activity: Activity) {
        val decor = activity.window.decorView
        if (XposedHelpers.getAdditionalInstanceField(decor, "flexunlockDialButtonBound") == true) {
            normalizeDialButton(activity)
            return
        }
        XposedHelpers.setAdditionalInstanceField(decor, "flexunlockDialButtonBound", true)
        decor.viewTreeObserver.addOnGlobalLayoutListener(
            ViewTreeObserver.OnGlobalLayoutListener { normalizeDialButton(activity) }
        )
        normalizeDialButton(activity)
    }

    private fun normalizeDialButton(activity: Activity) {
        val resources = activity.resources
        val containerId = resources.getIdentifier(
            "dial_button_container",
            "id",
            activity.packageName
        )
        val buttonId = resources.getIdentifier("dialButton", "id", activity.packageName)
        if (containerId == 0 || buttonId == 0) return
        val container = activity.findViewById<View>(containerId) ?: return
        val button = activity.findViewById<View>(buttonId) ?: return
        val parent = container.parent as? ViewGroup ?: return
        if (!container.isLaidOut || button.height <= 0 || parent.width <= 0) return

        val targetWidth = maxOf(container.width, button.height)
        var changed = false
        if (container.layoutParams.width != targetWidth) {
            container.layoutParams = container.layoutParams.apply { width = targetWidth }
            changed = true
        }
        parent.clipChildren = false
        parent.clipToPadding = false
        (container as? ViewGroup)?.let {
            it.clipChildren = false
            it.clipToPadding = false
        }
        val targetTranslationX = minOf(0, parent.width - container.left - targetWidth).toFloat()
        if (container.translationX != targetTranslationX) {
            container.translationX = targetTranslationX
            changed = true
        }
        if (changed) {
            val snapshot = "parent=${parent.width} left=${container.left} " +
                "width=$targetWidth shift=$targetTranslationX button=${button.width}x${button.height}"
            if (XposedHelpers.getAdditionalInstanceField(container, "flexunlockDialButtonSnapshot") != snapshot) {
                XposedHelpers.setAdditionalInstanceField(
                    container,
                    "flexunlockDialButtonSnapshot",
                    snapshot
                )
                CoverRuntime.log(SCOPE, "display-1 dial button normalized $snapshot")
            }
        }
    }
}