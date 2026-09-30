package com.flexunlock.dexlsp.system.runtime

import android.content.Context
import android.provider.Settings
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Keeps the selected built-in cover display at its current angle when auto-rotate is off. */
internal object CoverRotationLockPolicy {
    private const val DISPLAY_ROTATION = "com.android.server.wm.DisplayRotation"
    private const val ACTIVITY_RECORD = "com.android.server.wm.ActivityRecord"
    private const val ACCELEROMETER_ROTATION = "accelerometer_rotation"

    @Volatile
    private var context: Context? = null

    @Volatile
    private var installed = false

    fun bind(systemContext: Context) {
        context = systemContext
    }

    fun install(classLoader: ClassLoader) {
        if (installed) return
        var anyInstalled = false
        XposedHelpers.findClassIfExists(DISPLAY_ROTATION, classLoader)?.let { rotationClass ->
            runCatching {
                XposedBridge.hookAllMethods(
                    rotationClass,
                    "updateRotationUnchecked",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isRotationLocked()) return
                            val displayContent = getFieldOrNull(param.thisObject, "mDisplayContent")
                                ?: return
                            val displayId = getIntFieldOrNull(displayContent, "mDisplayId")
                                ?: return
                            if (!com.flexunlock.dexlsp.CoverDisplayResolver.matchesBuiltIn(displayId)) return
                            param.result = false
                            log("display-$displayId rotation update suppressed while locked")
                        }
                    }
                )
                anyInstalled = true
                log("cover rotation lock update hook installed")
            }.onFailure { error ->
                log("cover rotation lock update hook unavailable: ${error.message}")
            }
        }

        XposedHelpers.findClassIfExists(ACTIVITY_RECORD, classLoader)?.let { activityClass ->
            runCatching {
                XposedBridge.hookAllMethods(
                    activityClass,
                    "setRequestedOrientation",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isRotationLocked()) return
                            val displayId = runCatching {
                                (XposedHelpers.callMethod(param.thisObject, "getDisplayId") as Number).toInt()
                            }.getOrNull() ?: return
                            if (!com.flexunlock.dexlsp.CoverDisplayResolver.matchesBuiltIn(displayId)) return
                            param.result = null
                        }
                    }
                )
                anyInstalled = true
                log("cover app orientation lock hook installed")
            }.onFailure { error ->
                log("cover app orientation lock hook unavailable: ${error.message}")
            }
        }
        installed = anyInstalled
    }

    private fun isRotationLocked(): Boolean {
        val resolver = context?.contentResolver ?: return false
        return Settings.System.getInt(resolver, ACCELEROMETER_ROTATION, 1) == 0
    }

    private fun getFieldOrNull(instance: Any, field: String): Any? = runCatching {
        XposedHelpers.getObjectField(instance, field)
    }.getOrNull()

    private fun getIntFieldOrNull(instance: Any, field: String): Int? = runCatching {
        XposedHelpers.getIntField(instance, field)
    }.getOrNull()

    private fun log(message: String) {
        Log.i("FlexUnlock-SystemBridge", message)
        XposedBridge.log("FlexUnlock-SystemBridge: $message")
    }
}
