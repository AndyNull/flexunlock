package com.flexunlock.dexlsp.system.runtime

import android.util.Log
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

object SystemHomeController {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val START_HOME_METHOD = "startHomeOnDisplay"
    private const val START_REASON = "flexunlock-cover-session"

    @Volatile
    private var rootWindowContainer: Any? = null

    @Volatile
    private var startHomeMethod: Method? = null

    fun bind(systemServer: Any) {
        runCatching {
            val activityManager = XposedHelpers.getObjectField(
                systemServer,
                "mActivityManagerService"
            )
            val activityTaskManager = XposedHelpers.getObjectField(
                activityManager,
                "mActivityTaskManager"
            )
            val root = XposedHelpers.getObjectField(
                activityTaskManager,
                "mRootWindowContainer"
            )
            val method = root.javaClass.declaredMethods.single { candidate ->
                candidate.name == START_HOME_METHOD &&
                    candidate.parameterCount == 5 &&
                    candidate.parameterTypes.firstOrNull() == String::class.java
            }.apply { isAccessible = true }

            rootWindowContainer = root
            startHomeMethod = method
            log("system home controller bound method=${method.name}")
        }.onFailure { error ->
            rootWindowContainer = null
            startHomeMethod = null
            log("system home controller unavailable: ${error.message}")
        }
    }

    fun startHomeOnDisplay(displayId: Int): Boolean {
        val root = rootWindowContainer ?: return false
        val method = startHomeMethod ?: return false
        return runCatching {
            method.invoke(
                root,
                START_REASON,
                currentUserId(),
                displayId,
                false,
                false
            ) as? Boolean ?: false
        }.onFailure { error ->
            log("startHomeOnDisplay failed: ${error.message}")
        }.getOrDefault(false)
    }

    private fun currentUserId(): Int {
        return runCatching {
            Class.forName("android.app.ActivityManager")
                .getMethod("getCurrentUser")
                .invoke(null) as Int
        }.getOrDefault(0)
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}