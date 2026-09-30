package com.flexunlock.dexlsp

import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.roundToInt

internal object CoverImeHooks {
    private const val TAG = "FlexUnlock-CoverIme"

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        CoverDisplayResolver.installProcess()
        runCatching {
            val serviceClass = XposedHelpers.findClass(
                "android.inputmethodservice.InputMethodService",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                serviceClass,
                "onCreateInputView",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        applyDensity(param.thisObject)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.result as? View)?.let { view ->
                            applyDensity(view.resources, view.context)
                            view.requestLayout()
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                serviceClass,
                "onStartInputView",
                android.view.inputmethod.EditorInfo::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        applyDensity(param.thisObject)
                        runCatching {
                            (XposedHelpers.callMethod(param.thisObject, "getInputView") as? View)
                                ?.requestLayout()
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                serviceClass,
                "onConfigurationChanged",
                Configuration::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyDensity(param.thisObject)
                        runCatching {
                            (XposedHelpers.callMethod(param.thisObject, "getInputView") as? View)
                                ?.requestLayout()
                        }
                    }
                }
            )
            log("IME process density hook installed")
        }.onFailure { log("IME process density hook unavailable: ${it.message}") }
    }

    private fun applyDensity(service: Any) {
        val resources = XposedHelpers.callMethod(service, "getResources") as? android.content.res.Resources
            ?: return
        val context = service as? android.content.Context ?: return
        applyDensity(resources, context)
    }

    private fun applyDensity(
        resources: android.content.res.Resources,
        context: android.content.Context
    ) {
        val display = CoverDisplayResolver.current() ?: return
        if (display.type != 1) return
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val displayDensity = displayManager
            ?.getDisplay(display.id)
            ?.let { target ->
                DisplayMetrics().also(target::getRealMetrics).densityDpi
            }
            ?: resources.displayMetrics.densityDpi
        val nativeDensity = CoverDisplayConfig.readOverride(context)?.densityDpi
            ?: CoverDisplayConfig.readStatus(context)?.candidates
                ?.firstOrNull { it.displayId == display.id }
                ?.densityDpi
            ?: displayDensity
        val percent = if (CoverDisplayConfig.readImeCompact(context)) {
            CoverDisplayConfig.readImeCompactPercent(context)
        } else {
            100
        }
        val targetDensity = (nativeDensity * percent / 100f).roundToInt()
        if (targetDensity <= 0 || resources.configuration.densityDpi == targetDensity) return
        val configuration = Configuration(resources.configuration).apply {
            densityDpi = targetDensity
        }
        val metrics = DisplayMetrics().apply {
            setTo(resources.displayMetrics)
            densityDpi = targetDensity
            val densityScale = targetDensity / DisplayMetrics.DENSITY_DEFAULT.toFloat()
            density = densityScale
            scaledDensity = densityScale
        }
        resources.updateConfiguration(configuration, metrics)
        log("IME resources density applied displayId=${display.id} density=$targetDensity")
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
