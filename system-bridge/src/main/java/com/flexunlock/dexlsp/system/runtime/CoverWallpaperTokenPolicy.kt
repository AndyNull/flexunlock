package com.flexunlock.dexlsp.system.runtime

import android.util.Log
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

internal object CoverWallpaperTokenPolicy {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val WALLPAPER_CONTROLLER = "com.android.server.wm.WallpaperController"
    private const val LAUNCHER_PACKAGE = "com.sec.android.app.launcher"
    private const val SECONDARY_LAUNCHER = "com.honeyspace.dexservice.SecondaryLauncher"
    private const val RECENTS_ACTIVITY = "com.android.quickstep.RecentsActivity"
    private const val IMAGE_WALLPAPER_PACKAGE = "com.android.systemui"
    private const val IMAGE_WALLPAPER = "ImageWallpaper"
    private const val RESUME_WALLPAPER = "samsung.android.wallpaper.resume"

    private val replacementLogged = AtomicBoolean(false)
    @Volatile
    private var activeTarget: Any? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val controllerClass = XposedHelpers.findClass(
                WALLPAPER_CONTROLLER,
                lpparam.classLoader
            )
            val methods = controllerClass.declaredMethods.filter { method ->
                method.name == "getTokenForTarget" && method.parameterCount == 1
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        preferCoverImageWallpaper(param.thisObject ?: return, param.args[0] ?: return)
                    }
                })
            }
            log("cover wallpaper token policy installed methods=${methods.size}")
        }.onFailure { error ->
            log("cover wallpaper token policy unavailable: ${error.message}")
        }
    }

    private fun preferCoverImageWallpaper(controller: Any, target: Any) {
        val displayContent = objectField(controller, "mDisplayContent") ?: return
        val displayId = intField(displayContent, "mDisplayId")
        if (!isManagedWallpaperDisplay(displayContent, displayId)) return
        if (!isCoverWallpaperTarget(target) || isKeyguardLocked(displayContent)) {
            activeTarget = null
            return
        }

        val imageWallpaper = wallpaperChildren(controller).firstOrNull(::isImageWallpaper) ?: return
        val findResults = objectField(controller, "mFindResults") ?: return
        val topWallpaper = objectField(findResults, "mTopWallpaper") ?: return
        if (objectField(topWallpaper, "mTopShowWhenLockedWallpaper") !== imageWallpaper) {
            setWallpaperField(topWallpaper, "mTopShowWhenLockedWallpaper", imageWallpaper)
            setWallpaperField(topWallpaper, "mTopHideWhenLockedWallpaper", imageWallpaper)
            setWallpaperField(topWallpaper, "mTopWallpaper", imageWallpaper)
            if (replacementLogged.compareAndSet(false, true)) {
                log("display=$displayId launcher selected system ImageWallpaper token")
            }
        }
        notifyWallpaperVisibleOnTargetEntry(target, imageWallpaper, displayId)
    }

    private fun isManagedWallpaperDisplay(displayContent: Any, displayId: Int?): Boolean {
        if (com.flexunlock.dexlsp.CoverDisplayResolver.matches(displayId)) return true
        val displayInfo = objectField(displayContent, "mDisplayInfo") ?: return false
        val uniqueId = objectField(displayInfo, "uniqueId") as? String ?: return false
        return uniqueId.startsWith("virtual:com.android.shell,2000,scrcpy,", ignoreCase = true)
    }

    private fun setWallpaperField(target: Any, fieldName: String, value: Any) {
        runCatching { XposedHelpers.setObjectField(target, fieldName, value) }
    }

    private fun notifyWallpaperVisibleOnTargetEntry(target: Any, imageWallpaper: Any, displayId: Int?) {
        if (activeTarget === target) return
        activeTarget = target

        val client = objectField(imageWallpaper, "mClient") ?: return
        runCatching {
            XposedHelpers.callMethod(
                client,
                "dispatchWallpaperCommand",
                RESUME_WALLPAPER,
                0,
                0,
                0,
                null
            )
        }.onSuccess {
            log("display=$displayId ImageWallpaper resumed for launcher target")
        }.onFailure { error ->
            log("display=$displayId ImageWallpaper resume unavailable: ${error.message}")
        }
    }

    private fun isKeyguardLocked(displayContent: Any): Boolean =
        runCatching {
            XposedHelpers.callMethod(displayContent, "isKeyguardLocked") as? Boolean
        }.getOrNull() ?: true

    private fun isCoverWallpaperTarget(windowState: Any): Boolean =
        isSecondaryLauncher(windowState) || isRecentsActivity(windowState)

    private fun isSecondaryLauncher(windowState: Any): Boolean {
        val attrs = objectField(windowState, "mAttrs") as? WindowManager.LayoutParams ?: return false
        return attrs.packageName == LAUNCHER_PACKAGE &&
            attrs.title?.toString()?.contains(SECONDARY_LAUNCHER) == true
    }

    private fun isRecentsActivity(windowState: Any): Boolean {
        val attrs = objectField(windowState, "mAttrs") as? WindowManager.LayoutParams ?: return false
        return attrs.packageName == LAUNCHER_PACKAGE &&
            attrs.title?.toString()?.contains(RECENTS_ACTIVITY) == true
    }

    private fun wallpaperChildren(controller: Any): Sequence<Any> {
        val tokens = objectField(controller, "mWallpaperTokens") as? List<*> ?: return emptySequence()
        return tokens.asSequence().flatMap { token ->
            val children = token?.let { objectField(it, "mChildren") } as? List<*>
            children.orEmpty().asSequence().filterNotNull()
        }
    }

    private fun isImageWallpaper(windowState: Any): Boolean {
        val attrs = objectField(windowState, "mAttrs") as? WindowManager.LayoutParams ?: return false
        return attrs.packageName == IMAGE_WALLPAPER_PACKAGE &&
            attrs.title?.toString()?.contains(IMAGE_WALLPAPER) == true
    }

    private fun objectField(instance: Any, name: String): Any? =
        runCatching { XposedHelpers.getObjectField(instance, name) }.getOrNull()

    private fun intField(instance: Any, name: String): Int? =
        runCatching { XposedHelpers.getIntField(instance, name) }.getOrNull()

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
