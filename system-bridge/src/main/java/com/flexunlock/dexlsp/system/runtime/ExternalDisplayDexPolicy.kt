package com.flexunlock.dexlsp.system.runtime

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.Display
import com.flexunlock.dexlsp.CoverDisplayResolver
import com.flexunlock.dexlsp.CoverDisplaySnapshot
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal fun externalDexDisplayFlags(flags: Int): Int =
    flags or 0x2 or 0x20 or 0x40 or 0x80 or 0x100 or 0x200 or 0x20000 or 0x4000000 or 0x8000000

internal object ExternalDisplayDexPolicy {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val LAUNCHER_PACKAGE = "com.sec.android.app.launcher"

    @Volatile private var classLoader: ClassLoader? = null
    @Volatile private var context: Context? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var logicalDisplayMapper: Any? = null
    private val displayWakeLocks = mutableMapOf<Int, Any>()
    private val wallpaperAttachedDisplays = mutableSetOf<Int>()
    private var displayListenerRegistered = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        classLoader = lpparam.classLoader
        hookDisplayInfo(lpparam.classLoader)
        hookLogicalDisplays(lpparam.classLoader)
        hookExternalKeyguardAccess(lpparam.classLoader)
        hookExternalDisplayPower(lpparam.classLoader)
    }

    fun bind(systemContext: Context, mainHandler: Handler) {
        context = systemContext
        handler = mainHandler
        registerDisplayListener(systemContext, mainHandler)
        syncDisplayWakeLocks()
        prepareTarget(CoverDisplayResolver.current(), "bind")
    }

    fun onTargetChanged(previous: CoverDisplaySnapshot?, current: CoverDisplaySnapshot?) {
        if (previous?.id != current?.id && isExternal(previous)) {
            setHomeSupported(previous!!.id, false)
        }
        prepareTarget(current, "resolver-change")
    }

    private fun hookDisplayInfo(loader: ClassLoader) = runCatching {
        val service = XposedHelpers.findClass(
            "com.android.server.display.DisplayManagerService",
            loader
        )
        val methods = service.declaredMethods.filter { method ->
            method.name == "getDisplayInfoInternal" &&
                method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType
        }
        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                    val info = param.result ?: return
                    if (!isManagedTarget(displayId) && !isExternalDisplayInfo(info)) return
                    injectDisplayInfo(info)
                }
            })
        }
        log("external display info policy installed methods=${methods.size}")
    }.onFailure { log("external display info policy unavailable: ${it.message}") }

    private fun hookLogicalDisplays(loader: ClassLoader) = runCatching {
        val mapper = XposedHelpers.findClass(
            "com.android.server.display.LogicalDisplayMapper",
            loader
        )
        XposedBridge.hookAllMethods(
            mapper,
            "updateLogicalDisplaysLocked",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    logicalDisplayMapper = param.thisObject
                    applyLogicalTargets(param.thisObject)
                }
            }
        )
        log("external logical display policy installed")
    }.onFailure { log("external logical display policy unavailable: ${it.message}") }

    private fun hookExternalKeyguardAccess(loader: ClassLoader) {
        runCatching {
            val displayContent = XposedHelpers.findClass(
                "com.android.server.wm.DisplayContent",
                loader
            )
            XposedBridge.hookAllMethods(
                displayContent,
                "isKeyguardAlwaysUnlocked",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mDisplayInfo")
                        }.getOrNull()
                        if (info != null && isExternalDisplayInfo(info)) param.result = true
                    }
                }
            )
            val keyguardController = XposedHelpers.findClass(
                "com.android.server.wm.KeyguardController",
                loader
            )
            XposedBridge.hookAllMethods(
                keyguardController,
                "isKeyguardLocked",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (isExternalDisplayId(displayId)) param.result = false
                    }
                }
            )
            XposedBridge.hookAllMethods(
                keyguardController,
                "isKeyguardOrAodShowing",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (isExternalDisplayId(displayId)) param.result = false
                    }
                }
            )
            XposedBridge.hookAllMethods(
                keyguardController,
                "isKeyguardShowing",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (isExternalDisplayId(displayId)) param.result = false
                    }
                }
            )
            XposedBridge.hookAllMethods(
                keyguardController,
                "isKeyguardOccluded",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (isExternalDisplayId(displayId)) param.result = false
                    }
                }
            )
            XposedBridge.hookAllMethods(
                keyguardController,
                "updateKeyguardSleepToken",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val display = param.args.firstOrNull() ?: return
                        val info = runCatching {
                            XposedHelpers.getObjectField(display, "mDisplayInfo")
                        }.getOrNull() ?: return
                        if (!isExternalDisplayInfo(info)) return
                        runCatching {
                            XposedHelpers.callMethod(display, "removeSleepToken", "keyguard")
                        }
                        param.result = null
                    }
                }
            )
            log("external display Keyguard isolation installed")
        }.onFailure { log("external display Keyguard isolation unavailable: ${it.message}") }
    }

    private fun hookExternalDisplayPower(loader: ClassLoader) {
        runCatching {
            val powerManagerService = XposedHelpers.findClass(
                "com.android.server.power.PowerManagerService",
                loader
            )
            XposedBridge.hookAllMethods(
                powerManagerService,
                "goToSleepWithDisplayId",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (!isScrcpyDisplayId(displayId)) return
                        param.result = null
                        log("scrcpy display sleep request blocked display=$displayId")
                    }
                }
            )
            log("scrcpy display power isolation installed")
        }.onFailure { log("scrcpy display power isolation unavailable: ${it.message}") }
    }

    private fun prepareTarget(target: CoverDisplaySnapshot?, reason: String) {
        if (!isExternal(target) || CoverDisplayResolver.configuredIdentity() == null) return
        handler?.post {
            applyLogicalTargets(logicalDisplayMapper)
            setHomeSupported(target!!.id, true)
            attachWallpaperOnce(target.id)
            acquireDisplayWakeLock(target.id)
            NativeSecondaryHomeRouter.startCoverHome(SystemClock.uptimeMillis())
            log("external DeX target prepared display=${target.id} reason=$reason")
        }
        handler?.postDelayed({
            if (isManagedTarget(target!!.id)) {
                setHomeSupported(target.id, true)
                attachWallpaperOnce(target.id)
                acquireDisplayWakeLock(target.id)
                NativeSecondaryHomeRouter.startCoverHome(SystemClock.uptimeMillis())
            }
        }, 1_500L)
    }

    private fun applyLogicalTargets(mapper: Any?) {
        val selectedId = managedTargetId()
        val displays = mapper?.let {
            runCatching {
                XposedHelpers.getObjectField(it, "mLogicalDisplays") as? SparseArray<*>
            }.getOrNull()
        } ?: return
        for (index in 0 until displays.size()) {
            val logical = displays.valueAt(index) ?: continue
            val info = runCatching {
                XposedHelpers.callMethod(logical, "getDisplayInfoLocked")
            }.getOrNull() ?: continue
            val displayId = intField(info, "displayId") ?: continue
            if (displayId != selectedId && !isExternalDisplayInfo(info)) continue
            runCatching {
                val group = XposedHelpers.getObjectField(logical, "mDisplayGroupName") as? String
                if (group.isNullOrEmpty()) {
                    XposedHelpers.setObjectField(
                        logical,
                        "mDisplayGroupName",
                        "flexunlock-dex-$displayId"
                    )
                }
            }
            runCatching { XposedHelpers.setBooleanField(logical, "mCanHostTasks", true) }
            runCatching {
                XposedHelpers.getObjectField(logical, "mBaseDisplayInfo")
            }.getOrNull()?.let(::injectDisplayInfo)
            injectDisplayInfo(info)
            return
        }
    }

    private fun injectDisplayInfo(info: Any) {
        runCatching {
            val flags = intField(info, "flags") ?: return@runCatching
            XposedHelpers.setIntField(info, "flags", externalDexDisplayFlags(flags))
            if (intField(info, "type") != 5) XposedHelpers.setIntField(info, "type", 2)
            XposedHelpers.setBooleanField(info, "canHostTasks", true)
        }.onFailure { log("external display info injection failed: ${it.message}") }
    }

    private fun isExternalDisplayInfo(info: Any): Boolean {
        val type = intField(info, "type")
        if (type == 2 || type == 6) return true
        val uniqueId = runCatching {
            XposedHelpers.getObjectField(info, "uniqueId") as? String
        }.getOrNull()
        return type == 5 && uniqueId?.startsWith(
            "virtual:com.android.shell,2000,scrcpy,",
            ignoreCase = true
        ) == true
    }

    private fun isExternalDisplayId(displayId: Int): Boolean {
        val display = context?.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId) ?: return false
        val type = runCatching {
            (XposedHelpers.callMethod(display, "getType") as Number).toInt()
        }.getOrNull()
        if (type == 2 || type == 6) return true
        val uniqueId = runCatching {
            XposedHelpers.callMethod(display, "getUniqueId") as? String
        }.getOrNull()
        return type == 5 && uniqueId?.startsWith(
            "virtual:com.android.shell,2000,scrcpy,",
            ignoreCase = true
        ) == true
    }

    private fun isScrcpyDisplayId(displayId: Int): Boolean {
        val display = context?.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId) ?: return false
        val type = runCatching {
            (XposedHelpers.callMethod(display, "getType") as Number).toInt()
        }.getOrNull()
        if (type != 5) return false
        val uniqueId = runCatching {
            XposedHelpers.callMethod(display, "getUniqueId") as? String
        }.getOrNull()
        return uniqueId?.startsWith(
            "virtual:com.android.shell,2000,scrcpy,",
            ignoreCase = true
        ) == true
    }

    private fun setHomeSupported(displayId: Int, supported: Boolean) = runCatching {
        val loader = classLoader ?: return@runCatching
        val localServices = XposedHelpers.findClass("com.android.server.LocalServices", loader)
        val wmInternal = XposedHelpers.findClass(
            "com.android.server.wm.WindowManagerInternal",
            loader
        )
        val service = XposedHelpers.callStaticMethod(localServices, "getService", wmInternal)
        XposedHelpers.callMethod(
            service,
            "setHomeSupportedOnDisplay",
            LAUNCHER_PACKAGE,
            displayId,
            supported
        )
    }.onFailure { log("external Home support failed display=$displayId: ${it.message}") }

    private fun attachWallpaper(displayId: Int) = runCatching {
        val loader = classLoader ?: return@runCatching
        val localServices = XposedHelpers.findClass("com.android.server.LocalServices", loader)
        val wallpaperLocal = XposedHelpers.findClass(
            "com.android.server.wallpaper.WallpaperManagerService\$LocalService",
            loader
        )
        val service = XposedHelpers.callStaticMethod(localServices, "getService", wallpaperLocal)
        XposedHelpers.callMethod(service, "onDisplayAddSystemDecorations", displayId)
    }.onFailure { log("external wallpaper attach failed display=$displayId: ${it.message}") }

    private fun attachWallpaperOnce(displayId: Int) {
        if (!wallpaperAttachedDisplays.add(displayId)) return
        attachWallpaper(displayId)
    }

    private fun acquireDisplayWakeLock(displayId: Int) {
        if (displayWakeLocks.containsKey(displayId)) return
        runCatching {
            val powerManager = context?.getSystemService(PowerManager::class.java) ?: return
            val lock = XposedHelpers.callMethod(
                powerManager,
                "newWakeLock",
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "FlexUnlock:ExternalDisplay",
                displayId
            ) ?: return
            XposedHelpers.callMethod(lock, "setReferenceCounted", false)
            XposedHelpers.callMethod(lock, "acquire")
            displayWakeLocks[displayId] = lock
            wakeScrcpyDisplay(displayId)
            log("external display wake lock acquired display=$displayId")
        }.onFailure { log("external display wake lock failed display=$displayId: ${it.message}") }
    }

    private fun wakeScrcpyDisplay(displayId: Int) {
        if (!isScrcpyDisplayId(displayId)) return
        runCatching {
            val powerManager = context?.getSystemService(PowerManager::class.java) ?: return
            XposedHelpers.callMethod(
                powerManager,
                "wakeUp",
                SystemClock.uptimeMillis(),
                2,
                "FlexUnlock:ExternalDisplay",
                displayId
            )
            log("scrcpy display group awakened display=$displayId")
        }.onFailure { log("scrcpy display wake failed display=$displayId: ${it.message}") }
    }

    private fun releaseDisplayWakeLock(displayId: Int) {
        val lock = displayWakeLocks.remove(displayId) ?: return
        runCatching { XposedHelpers.callMethod(lock, "release") }
            .onSuccess { log("external display wake lock released display=$displayId") }
            .onFailure { log("external display wake lock release failed: ${it.message}") }
    }

    private fun registerDisplayListener(systemContext: Context, mainHandler: Handler) {
        if (displayListenerRegistered) return
        val manager = systemContext.getSystemService(DisplayManager::class.java) ?: return
        manager.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                NativeSecondaryHomeRouter.onExternalDisplayAdded(displayId)
                syncDisplayWakeLocks()
            }
            override fun onDisplayChanged(displayId: Int) {
                NativeSecondaryHomeRouter.onExternalDisplayAdded(displayId)
                syncDisplayWakeLocks()
            }
            override fun onDisplayRemoved(displayId: Int) {
                setHomeSupported(displayId, false)
                wallpaperAttachedDisplays.remove(displayId)
                NativeSecondaryHomeRouter.onExternalDisplayRemoved(displayId)
                syncDisplayWakeLocks()
            }
        }, mainHandler)
        displayListenerRegistered = true
    }

    private fun syncDisplayWakeLocks() {
        val manager = context?.getSystemService(DisplayManager::class.java) ?: return
        val connected = manager.displays
            .filter(::isExternalDesktop)
            .mapTo(mutableSetOf()) { it.displayId }
        displayWakeLocks.keys.toList()
            .filterNot(connected::contains)
            .forEach(::releaseDisplayWakeLock)
        connected.forEach(::acquireDisplayWakeLock)
    }

    private fun isExternalDesktop(display: Display): Boolean {
        val type = runCatching {
            (XposedHelpers.callMethod(display, "getType") as Number).toInt()
        }.getOrNull()
        if (type == 2 || type == 6) return true
        val uniqueId = runCatching {
            XposedHelpers.callMethod(display, "getUniqueId") as? String
        }.getOrNull()
        return type == 5 && uniqueId?.startsWith(
            "virtual:com.android.shell,2000,scrcpy,",
            ignoreCase = true
        ) == true
    }

    private fun managedTargetId(): Int? = CoverDisplayResolver.current()
        ?.takeIf(::isExternal)
        ?.takeIf { CoverDisplayResolver.configuredIdentity() != null }
        ?.id

    private fun isManagedTarget(displayId: Int): Boolean = managedTargetId() == displayId

    private fun isExternal(snapshot: CoverDisplaySnapshot?): Boolean =
        snapshot?.type == 2 || snapshot?.type == 5 || snapshot?.type == 6

    private fun intField(target: Any, name: String): Int? = runCatching {
        XposedHelpers.getIntField(target, name)
    }.getOrNull()

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
