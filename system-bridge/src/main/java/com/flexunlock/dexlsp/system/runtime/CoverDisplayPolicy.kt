package com.flexunlock.dexlsp.system.runtime

import android.content.Context
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import android.util.Log
import com.flexunlock.dexlsp.CoverQsModeConfig
import com.flexunlock.dexlsp.system.session.FoldState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal fun coverDisplayRotationTarget(
    rotationLocked: Boolean,
    currentRotation: Int,
    userRotation: Int,
    sensorRotation: Int,
    resolvedRotation: Int
): Int = if (rotationLocked) {
    currentRotation.takeIf { it in 0..3 }
        ?: sensorRotation.takeIf { it in 0..3 }
        ?: resolvedRotation.takeIf { it in 0..3 }
        ?: userRotation
} else {
    sensorRotation.takeIf { it in 0..3 } ?: resolvedRotation
}

internal fun coverRotationLocked(stableFull: Boolean, systemRotationLocked: Boolean): Boolean =
    systemRotationLocked

internal fun coverClosedTargetRotation(
    rotationLocked: Boolean,
    currentMode: Int,
    currentRotation: Int,
    userRotation: Int,
    sensorRotation: Int = -1,
    proposedRotation: Int = -1,
    desiredRotation: Int = -1
): Int = when {
    !rotationLocked -> currentRotation
    currentMode != 1 -> listOf(
        proposedRotation,
        desiredRotation,
        sensorRotation,
        currentRotation,
        userRotation
    ).firstOrNull { it in 0..3 } ?: 0
    else -> userRotation
}

internal fun coverDisplayGeometryChanged(
    previous: com.flexunlock.dexlsp.CoverDisplaySnapshot?,
    current: com.flexunlock.dexlsp.CoverDisplaySnapshot?
): Boolean = previous == null || current == null ||
    previous.id != current.id ||
    previous.uniqueId != current.uniqueId ||
    previous.type != current.type ||
    minOf(previous.width, previous.height) != minOf(current.width, current.height) ||
    maxOf(previous.width, previous.height) != maxOf(current.width, current.height)

object CoverDisplayPolicy {
    private data class RotationSnapshot(
        val mode: Int,
        val rotation: Int
    )

    private const val TAG = "FlexUnlock-SystemBridge"
    private const val DISPLAY_ROTATION_CLASS = "com.android.server.wm.DisplayRotation"
    private const val USER_ROTATION_FREE = 0
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val LAUNCHER_PACKAGE = "com.sec.android.app.launcher"
    private const val STANDARD_ACTIVITY_TYPE = 1
    private const val HOME_ACTIVITY_TYPE = 2
    private const val CLOSED_REASON = "flexunlock-cover-closed"
    private const val OPENED_REASON = "flexunlock-cover-opened"

    private val stateLock = Any()

    @Volatile
    private var windowManagerService: Any? = null

    @Volatile
    private var context: Context? = null

    @Volatile
    private var rotationObserverInstalled = false

    @Volatile
    private var applyQueued = false

    @Volatile
    private var coverWakeRequestQueued = false

    @Volatile
    private var decorationsActive = false

    @Volatile
    private var orientationOverrideLogged = false

    @Volatile
    private var lastFirstFrameCorrection: String? = null

    private var originalSystemDecors: Boolean? = null
    private var rotationSnapshot: RotationSnapshot? = null
    private var rotationSessionActive = false
    private var policyDisplayId: Int? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        installWindowManagerBinding(lpparam.classLoader)
        installScrcpyOverlayVisibilityPolicy(lpparam.classLoader)
        installCoverActivityOrientationPolicy(lpparam.classLoader)
        installDisplayRotationFirstFramePolicy(lpparam.classLoader)
        installSystemDecorationPolicy(lpparam.classLoader)
        installDecorationLifecycleObservation(lpparam.classLoader)
    }

    private fun installScrcpyOverlayVisibilityPolicy(classLoader: ClassLoader) {
        val windowStateClass = XposedHelpers.findClassIfExists(
            "com.android.server.wm.WindowState",
            classLoader
        ) ?: return log("scrcpy overlay policy unavailable: WindowState missing")
        runCatching {
            XposedBridge.hookAllMethods(
                windowStateClass,
                "setForceHideNonSystemOverlayWindowIfNeeded",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val window = param.thisObject ?: return
                        if (!isScrcpyOverlayWindow(window)) return
                        XposedHelpers.setBooleanField(window, "mIsForceHiddenNonSystemOverlayWindow", false)
                        param.result = Unit
                    }
                }
            )
            XposedBridge.hookAllMethods(
                windowStateClass,
                "hide",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val window = param.thisObject ?: return
                        if (!isScrcpyOverlayWindow(window)) return
                        if (XposedHelpers.getBooleanField(window, "mWindowRemovalAllowed")) return
                        XposedHelpers.setBooleanField(window, "mIsForceHiddenNonSystemOverlayWindow", false)
                        param.result = false
                    }
                }
            )
            log("scrcpy overlay visibility policy installed")
        }.onFailure { error ->
            log("scrcpy overlay visibility policy unavailable: ${error.message}")
        }
    }

    private fun isScrcpyOverlayWindow(window: Any): Boolean {
        val attrs = getFieldOrNull(window, "mAttrs")
            as? android.view.WindowManager.LayoutParams ?: return false
        if (attrs.type != android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) return false
        if (attrs.packageName != LAUNCHER_PACKAGE) return false
        val title = attrs.title?.toString().orEmpty()
        if (!title.startsWith("OverlayAppsDialog")) return false
        val displayContent = getFieldOrNull(window, "mDisplayContent") ?: return false
        val displayInfo = getFieldOrNull(displayContent, "mDisplayInfo") ?: return false
        val uniqueId = getFieldOrNull(displayInfo, "uniqueId") as? String ?: return false
        return uniqueId.startsWith("virtual:com.android.shell,2000,scrcpy,", true)
    }

    fun bind(systemContext: Context, handler: Handler) {
        context = systemContext
        if (!rotationObserverInstalled) {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    scheduleApply()
                }
            }
            systemContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
                false,
                observer
            )
            systemContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.USER_ROTATION),
                false,
                observer
            )
            rotationObserverInstalled = true
        }
        scheduleApply()
    }

    fun onFoldState(state: FoldState) {
        if (state == FoldState.UNKNOWN) return
        scheduleApply()
    }

    fun onCoverDisplayStateChanged() {
        scheduleApply()
    }

    internal fun onCoverDisplayResolutionChanged(
        previous: com.flexunlock.dexlsp.CoverDisplaySnapshot?,
        current: com.flexunlock.dexlsp.CoverDisplaySnapshot?
    ) {
        if (!coverDisplayGeometryChanged(previous, current)) return
        coverWakeRequestQueued = false
        scheduleApply()
    }

    internal fun requestKeyguardForCoverTimeout(): Boolean {
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return false
        val service = windowManagerService ?: return false
        return runCatching {
            XposedHelpers.callMethod(service, "lockDeviceNow")
            true
        }.onFailure { error ->
            log("display-1 timeout keyguard request failed: ${error.message}")
        }.getOrDefault(false)
    }

    internal fun requestKeyguardForCoverWake(reason: String) {
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return
        val service = windowManagerService ?: return
        synchronized(stateLock) {
            if (coverWakeRequestQueued) return
            coverWakeRequestQueued = true
        }
        if (!CoverKeyguardSurfacePolicy.armForPendingWake()) {
            coverWakeRequestQueued = false
            log("display-1 pending Keyguard wake remains display-OFF: safety layer unavailable")
            return
        }
        CoverTimeoutPolicy.holdDisplayOffForPendingKeyguard(reason)
        val handler = getFieldOrNull(service, "mH") as? Handler
        if (handler == null) {
            coverWakeRequestQueued = false
            log("display-1 pending Keyguard request skipped: WMS handler missing")
            return
        }
        handler.post {
            try {
                if (!RuntimeFacts.isClosed() || !CoverLockTransitionPolicy.isLockPending()) {
                    return@post
                }
                runCatching {
                    XposedHelpers.callMethod(service, "lockDeviceNow")
                    log("display-1 pending Keyguard request resubmitted reason=$reason")
                }.onFailure { error ->
                    log("display-1 pending Keyguard request failed: ${error.message}")
                }
            } finally {
                coverWakeRequestQueued = false
            }
        }
    }

    private fun installWindowManagerBinding(classLoader: ClassLoader) {
        val windowManagerClass = XposedHelpers.findClassIfExists(
            "com.android.server.wm.WindowManagerService",
            classLoader
        ) ?: return log("display policy unavailable: WindowManagerService missing")

        runCatching {
            val methods = windowManagerClass.declaredMethods.filter { method ->
                method.name == "main" &&
                    java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.returnType == windowManagerClass
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        bindWindowManager(param.result ?: return)
                    }
                })
            }
            log("WindowManager binding installed methods=${methods.size}")
        }.onFailure { error ->
            log("WindowManager binding unavailable: ${error.message}")
        }
    }

    private fun installCoverActivityOrientationPolicy(classLoader: ClassLoader) {
        val activityRecordClass = XposedHelpers.findClassIfExists(
            "com.android.server.wm.ActivityRecord",
            classLoader
        ) ?: return log("activity orientation policy unavailable: ActivityRecord missing")

        runCatching {
            val methods = activityRecordClass.declaredMethods.filter { method ->
                method.name == "getOrientation" && method.parameterCount == 1
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!RuntimeFacts.isClosed()) return
                        val record = param.thisObject ?: return
                        val displayId = runCatching {
                            (XposedHelpers.callMethod(record, "getDisplayId") as? Number)?.toInt()
                        }.getOrNull()
                        if (!com.flexunlock.dexlsp.CoverDisplayResolver.matchesBuiltIn(displayId)) return
                        val activityType = runCatching {
                            (XposedHelpers.callMethod(record, "getActivityType") as? Number)?.toInt()
                        }.getOrNull()
                        if (
                            activityType != STANDARD_ACTIVITY_TYPE &&
                            !(isStableFull() && activityType == HOME_ACTIVITY_TYPE)
                        ) return
                        val packageName = getFieldOrNull(record, "packageName") as? String
                        if (packageName == SYSTEM_UI_PACKAGE && !isStableFull()) return

                        val requested = (param.result as? Number)?.toInt() ?: return
                        if (!requested.followsPhysicalRotation()) return
                        val target = if (isRotationLocked()) {
                            ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        }
                        param.result = target
                        if (!orientationOverrideLogged) {
                            orientationOverrideLogged = true
                            log(
                                "display-1 standard app orientation normalized " +
                                    "from=$requested to=$target package=$packageName"
                            )
                        }
                    }
                })
            }
            log("display-1 standard app orientation policy installed methods=${methods.size}")
        }.onFailure { error ->
            log("activity orientation policy unavailable: ${error.message}")
        }
    }

    private fun installDisplayRotationFirstFramePolicy(classLoader: ClassLoader) {
        val displayRotationClass = XposedHelpers.findClassIfExists(
            DISPLAY_ROTATION_CLASS,
            classLoader
        ) ?: return log("first-frame rotation policy unavailable: DisplayRotation missing")

        runCatching {
            val methods = displayRotationClass.declaredMethods.filter { method ->
                method.name == "rotationForOrientation" &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType
                        )
                    )
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!RuntimeFacts.isClosed()) return
                        val displayContent = getFieldOrNull(param.thisObject, "mDisplayContent")
                            ?: return
                        if (!com.flexunlock.dexlsp.CoverDisplayResolver.matchesBuiltIn(displayIdOf(displayContent))) return

                        val requested = (param.args.firstOrNull() as? Number)?.toInt()
                            ?: return
                        if (!requested.followsPhysicalRotation()) return

                        val sensorRotation = runCatching {
                            XposedHelpers.getIntField(param.thisObject, "mLastSensorRotation")
                        }.getOrDefault(-1)
                        val currentRotation = (param.args.getOrNull(1) as? Number)?.toInt()
                            ?: return
                        val resolvedRotation = (param.result as? Number)?.toInt() ?: return
                        val rotationLocked = coverRotationLocked(isStableFull(), isRotationLocked())
                        val userRotation = runCatching {
                            XposedHelpers.getIntField(param.thisObject, "mUserRotation")
                        }.getOrDefault(currentRotation)
                        val targetRotation = coverDisplayRotationTarget(
                            rotationLocked,
                            currentRotation,
                            userRotation,
                            sensorRotation,
                            resolvedRotation
                        )
                        if (resolvedRotation == targetRotation) return

                        // Samsung's cover branch can return the previous rotation while a
                        // sensor-following activity is being created. Correct the result
                        // before WMS builds the activity configuration for its first frame.
                        param.result = targetRotation
                        val correctionKey =
                            "$requested:$resolvedRotation->$targetRotation"
                        if (lastFirstFrameCorrection != correctionKey) {
                            lastFirstFrameCorrection = correctionKey
                            log(
                                "display-1 first-frame rotation corrected " +
                                    "orientation=$requested resolved=$resolvedRotation " +
                                    "sensor=$sensorRotation current=$currentRotation " +
                                    "user=$userRotation locked=$rotationLocked"
                            )
                        }
                    }
                })
            }
            log("display-1 first-frame rotation policy installed methods=${methods.size}")
        }.onFailure { error ->
            log("first-frame rotation policy unavailable: ${error.message}")
        }
    }

    private fun Int.followsPhysicalRotation(): Boolean =
        this == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ||
            this == ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR ||
            this == ActivityInfo.SCREEN_ORIENTATION_USER ||
            this == ActivityInfo.SCREEN_ORIENTATION_SENSOR ||
            this == ActivityInfo.SCREEN_ORIENTATION_NOSENSOR ||
            this == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ||
            this == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ||
            this == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE ||
            this == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT ||
            this == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE ||
            this == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT ||
            this == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE ||
            this == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT ||
            this == ActivityInfo.SCREEN_ORIENTATION_FULL_USER ||
            this == ActivityInfo.SCREEN_ORIENTATION_LOCKED

    private fun installSystemDecorationPolicy(classLoader: ClassLoader) {
        val settingsClass = XposedHelpers.findClassIfExists(
            "com.android.server.wm.DisplayWindowSettings",
            classLoader
        ) ?: return log("system decorations unavailable: DisplayWindowSettings missing")

        runCatching {
            val methods = settingsClass.declaredMethods.filter { method ->
                method.name == "shouldShowSystemDecorsLocked" && method.parameterCount == 1
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayContent = param.args.firstOrNull() ?: return
                        if (!com.flexunlock.dexlsp.CoverDisplayResolver.matchesBuiltIn(displayIdOf(displayContent))) return
                        val original = param.result as? Boolean ?: return
                        synchronized(stateLock) {
                            if (!RuntimeFacts.isClosed() || originalSystemDecors == null) {
                                originalSystemDecors = original
                            }
                        }
                        if (RuntimeFacts.isClosed()) param.result = true
                    }
                })
            }
            log("CLOSED display-1 system-decoration policy installed methods=${methods.size}")
        }.onFailure { error ->
            log("system-decoration policy unavailable: ${error.message}")
        }
        installFullscreenCutoutPolicy(classLoader)
    }

    private fun installFullscreenCutoutPolicy(classLoader: ClassLoader) {
        val policyClass = XposedHelpers.findClassIfExists(
            "com.android.server.wm.DisplayPolicy",
            classLoader
        ) ?: return
        runCatching {
            policyClass.declaredMethods
                .filter { method ->
                    method.name == "layoutWindowLw" &&
                        method.parameterTypes.firstOrNull()?.name ==
                        "com.android.server.wm.WindowState"
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val window = param.args.firstOrNull() ?: return
                            if (!com.flexunlock.dexlsp.CoverDisplayResolver.matchesBuiltIn(
                                    displayIdOf(window)
                                )
                            ) return
                            if (!RuntimeFacts.isClosed()) return
                            if (getFieldOrNull(window, "mActivityRecord") == null) return
                            val attrs = getFieldOrNull(window, "mAttrs")
                                as? android.view.WindowManager.LayoutParams ?: return
                            val activityRecord = getFieldOrNull(window, "mActivityRecord") ?: return
                            val packageName = getFieldOrNull(activityRecord, "packageName") as? String
                                ?: runCatching {
                                    (XposedHelpers.callMethod(
                                        activityRecord,
                                        "getPackageName"
                                    ) as? String)
                                }.getOrNull()
                            if (packageName == SYSTEM_UI_PACKAGE) return
                            attrs.flags = attrs.flags or
                                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                            val fullscreen = attrs.flags and
                                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN != 0
                            val hidden = runCatching {
                                !(XposedHelpers.callMethod(
                                    window,
                                    "isRequestedVisible",
                                    android.view.WindowInsets.Type.statusBars()
                                ) as Boolean)
                            }.getOrDefault(false)
                            if (fullscreen || hidden) {
                                attrs.layoutInDisplayCutoutMode =
                                    android.view.WindowManager.LayoutParams
                                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            }
                        }
                    })
                }
            log("cover fullscreen cutout policy installed")
        }.onFailure { error ->
            log("fullscreen cutout policy unavailable: ${error.message}")
        }
    }

    private fun installDecorationLifecycleObservation(classLoader: ClassLoader) {
        runCatching {
            val rootClass = XposedHelpers.findClass(
                "com.android.server.wm.RootWindowContainer",
                classLoader
            )
            rootClass.declaredMethods
                .filter { method ->
                    method.name == "startSystemDecorations" && method.parameterCount == 2
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val displayContent = param.args.firstOrNull() ?: return
                            if (!com.flexunlock.dexlsp.CoverDisplayResolver.matchesBuiltIn(displayIdOf(displayContent))) return
                            val supported = runCatching {
                                XposedHelpers.callMethod(
                                    displayContent,
                                    "isSystemDecorationsSupported"
                                ) as? Boolean
                            }.getOrNull() == true
                            if (supported && isActivityManagerReady(param.thisObject)) {
                                decorationsActive = true
                                log("display-1 native system-decoration add lifecycle observed")
                            }
                        }
                    })
                }

            val policyClass = XposedHelpers.findClass(
                "com.android.server.wm.DisplayPolicy",
                classLoader
            )
            policyClass.declaredMethods
                .filter { method ->
                    method.name == "notifyDisplayRemoveSystemDecorations" &&
                        method.parameterCount == 0
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val displayContent = getFieldOrNull(
                                param.thisObject,
                                "mDisplayContent"
                            ) ?: return
                            if (com.flexunlock.dexlsp.CoverDisplayResolver.matchesBuiltIn(displayIdOf(displayContent))) {
                                decorationsActive = false
                                log("display-1 native system-decoration remove lifecycle observed")
                            }
                        }
                    })
                }
            log("display-1 decoration lifecycle observation installed")
        }.onFailure { error ->
            log("decoration lifecycle observation unavailable: ${error.message}")
        }
    }

    private fun bindWindowManager(service: Any) {
        if (windowManagerService === service) return
        windowManagerService = service
        log("cover display policy bound to WindowManagerService")
        scheduleApply()
    }

    private fun scheduleApply() {
        val service = windowManagerService ?: return
        synchronized(stateLock) {
            if (applyQueued) return
            applyQueued = true
        }
        val handler = getFieldOrNull(service, "mH") as? Handler
        if (handler == null) {
            synchronized(stateLock) { applyQueued = false }
            log("cover display policy apply skipped: WMS handler missing")
            return
        }
        handler.post {
            synchronized(stateLock) { applyQueued = false }
            applyCurrentState(service)
        }
    }

    private fun applyCurrentState(service: Any) {
        if (windowManagerService !== service) return
        runCatching {
            val root = getFieldOrNull(service, "mRoot")
                ?: error("RootWindowContainer missing")
            val globalLock = getFieldOrNull(service, "mGlobalLock")
                ?: error("WindowManagerGlobalLock missing")
            val currentDisplayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId()
                ?.takeIf { com.flexunlock.dexlsp.CoverDisplayResolver.matchesBuiltIn(it) }

            withGlobalLock(service, globalLock) {
                val trackedDisplayId = policyDisplayId
                if (trackedDisplayId != null && trackedDisplayId != currentDisplayId) {
                    val trackedDisplayContent = XposedHelpers.callMethod(
                        root,
                        "getDisplayContent",
                        trackedDisplayId
                    )
                    if (trackedDisplayContent != null) {
                        restoreOpenedPolicy(trackedDisplayContent)
                    } else {
                        clearPolicySessionState()
                    }
                }

                if (currentDisplayId == null) return@withGlobalLock
                val displayContent = XposedHelpers.callMethod(
                    root,
                    "getDisplayContent",
                    currentDisplayId
                ) ?: return@withGlobalLock
                if (RuntimeFacts.isClosed()) {
                    applyClosedPolicy(root, displayContent)
                } else {
                    restoreOpenedPolicy(displayContent)
                }
            }
        }.onFailure { error ->
            log("cover display policy apply failed: ${error.message}")
        }
    }

    private fun applyClosedPolicy(root: Any, displayContent: Any) {
        policyDisplayId = displayIdOf(displayContent)
            ?: error("cover display id missing")
        val displayRotation = getFieldOrNull(displayContent, "mDisplayRotation")
            ?: error("DisplayRotation missing")
        val sessionStarted = !rotationSessionActive
        if (sessionStarted) {
            rotationSnapshot = RotationSnapshot(
                mode = XposedHelpers.getIntField(displayRotation, "mUserRotationMode"),
                rotation = XposedHelpers.getIntField(displayRotation, "mUserRotation")
            )
            val currentRotation = XposedHelpers.getIntField(displayRotation, "mRotation")
            XposedHelpers.callMethod(
                displayRotation,
                "setUserRotation",
                USER_ROTATION_FREE,
                currentRotation,
                CLOSED_REASON
            )
            rotationSessionActive = true
            log("display-1 user rotation unlocked baseline=$rotationSnapshot")
        }
        if (sessionStarted) {
            updateRotation(displayRotation)
            reconfigureDisplay(displayContent)
        }

        if (!decorationsActive && isActivityManagerReady(root)) {
            XposedHelpers.callMethod(
                root,
                "startSystemDecorations",
                displayContent,
                CLOSED_REASON
            )
            log("display-1 native system-decoration start requested")
        }
    }

    private fun restoreOpenedPolicy(displayContent: Any) {
        val displayRotation = getFieldOrNull(displayContent, "mDisplayRotation")
            ?: error("DisplayRotation missing")
        val snapshot = rotationSnapshot
        if (rotationSessionActive && snapshot != null) {
            XposedHelpers.callMethod(
                displayRotation,
                "setUserRotation",
                snapshot.mode,
                snapshot.rotation,
                OPENED_REASON
            )
            updateRotation(displayRotation)
            rotationSessionActive = false
            rotationSnapshot = null
            log("display-1 user rotation restored mode=${snapshot.mode} rotation=${snapshot.rotation}")
        }

        val shouldRetainDecorations = synchronized(stateLock) {
            originalSystemDecors == true
        }
        if (decorationsActive && !shouldRetainDecorations) {
            val displayPolicy = getFieldOrNull(displayContent, "mDisplayPolicy")
                ?: error("DisplayPolicy missing")
            XposedHelpers.callMethod(displayPolicy, "notifyDisplayRemoveSystemDecorations")
            decorationsActive = false
            log("display-1 native system decorations removed")
        }
        reconfigureDisplay(displayContent)
        clearPolicySessionState()
        log("display-1 CLOSED policy session cleared")
    }

    private fun clearPolicySessionState() {
        rotationSessionActive = false
        rotationSnapshot = null
        policyDisplayId = null
        decorationsActive = false
        synchronized(stateLock) {
            originalSystemDecors = null
        }
    }

    private inline fun withGlobalLock(service: Any, globalLock: Any, block: () -> Unit) {
        val boosted = runCatching {
            XposedHelpers.callStaticMethod(
                service.javaClass,
                "boostPriorityForLockedSection"
            )
        }.isSuccess
        try {
            synchronized(globalLock) {
                block()
            }
        } finally {
            if (boosted) {
                runCatching {
                    XposedHelpers.callStaticMethod(
                        service.javaClass,
                        "resetPriorityAfterLockedSection"
                    )
                }.onFailure { error ->
                    log("WMS priority reset failed: ${error.message}")
                }
            }
        }
    }

    private fun isActivityManagerReady(root: Any): Boolean {
        val service = getFieldOrNull(root, "mService") ?: return false
        val activityManager = getFieldOrNull(service, "mAmInternal") ?: return false
        return runCatching {
            XposedHelpers.callMethod(activityManager, "isBooted") as Boolean
        }.getOrDefault(false) || runCatching {
            XposedHelpers.callMethod(activityManager, "isBooting") as Boolean
        }.getOrDefault(false)
    }

    private fun updateRotation(displayRotation: Any) {
        XposedHelpers.callMethod(displayRotation, "updateOrientationListener")
        XposedHelpers.callMethod(
            displayRotation,
            "updateRotationAndSendNewConfigIfChanged"
        )
    }

    private fun isRotationLocked(): Boolean {
        val resolver = context?.contentResolver ?: return false
        return Settings.System.getInt(
            resolver,
            Settings.System.ACCELEROMETER_ROTATION,
            1
        ) == 0
    }

    private fun isStableFull(): Boolean =
        context?.let { CoverQsModeConfig.readTransaction(it).isStableFull } == true

    private fun reconfigureDisplay(displayContent: Any) {
        XposedHelpers.callMethod(displayContent, "reconfigureDisplayLocked")
    }

    private fun displayIdOf(displayContent: Any?): Int? {
        if (displayContent == null) return null
        return runCatching {
            XposedHelpers.getIntField(displayContent, "mDisplayId")
        }.getOrNull() ?: runCatching {
            (XposedHelpers.callMethod(displayContent, "getDisplayId") as Number).toInt()
        }.getOrNull()
    }

    private fun getFieldOrNull(instance: Any, field: String): Any? = runCatching {
        XposedHelpers.getObjectField(instance, field)
    }.getOrNull()

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
