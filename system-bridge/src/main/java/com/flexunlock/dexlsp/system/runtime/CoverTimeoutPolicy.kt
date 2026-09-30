package com.flexunlock.dexlsp.system.runtime

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal fun coverTimeoutMillisForKeyguard(
    keyguardShowing: Boolean,
    systemTimeoutMillis: Long,
    lockscreenTimeoutMillis: Long = CoverDisplayConfig.DEFAULT_LOCKSCREEN_TIMEOUT_MILLIS
): Long = if (keyguardShowing) {
    lockscreenTimeoutMillis
} else {
    systemTimeoutMillis.coerceAtLeast(1_000L)
}

internal fun shouldScheduleCoverTimeoutForDisplayState(
    previousOn: Boolean?,
    currentOn: Boolean
): Boolean = currentOn && previousOn != true

internal fun isScreenKeepingWakeLock(flags: Int, tag: String? = null): Boolean =
    tag != "FlexUnlock:ExternalDisplay" && when (
    flags and 0xffff
) {
    PowerManager.SCREEN_DIM_WAKE_LOCK,
    PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
    PowerManager.FULL_WAKE_LOCK -> true
    else -> false
}

internal fun isWindowKeepingScreenOn(flags: Int): Boolean =
    flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

internal fun isCoverUserActivity(
    reportedDisplayId: Int,
    coverDisplayId: Int,
    defaultDisplayOn: Boolean,
    coverDisplayOn: Boolean,
    keyguardShowing: Boolean
): Boolean = reportedDisplayId == coverDisplayId ||
    (
        reportedDisplayId == Display.DEFAULT_DISPLAY &&
            !defaultDisplayOn &&
            coverDisplayOn &&
            keyguardShowing
        )

internal object CoverTimeoutPolicy {
    private const val SCREEN_OFF_TIMEOUT = Settings.System.SCREEN_OFF_TIMEOUT
    private const val LEGACY_COVER_SCREEN_TIMEOUT = "cover_screen_timeout"
    private const val LEGACY_COVER_TIMEOUT_SECONDS = "10"
    private const val DEFAULT_SCREEN_TIMEOUT_MILLIS = 30_000L
    private const val BACKGROUND_LOCK_AFTER_DISPLAY_OFF_MILLIS = 500L
    private const val DISPLAY_MANAGER_SERVICE =
        "com.android.server.display.DisplayManagerService"
    private const val POWER_MANAGER_SERVICE =
        "com.android.server.power.PowerManagerService"
    private const val WINDOW_MANAGER_SERVICE =
        "com.android.server.wm.WindowManagerService"

    private val stateLock = Any()
    private val displayOverrideToken = Binder()
    private val timeoutHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var context: Context? = null

    @Volatile
    private var displayManagerService: Any? = null

    @Volatile
    private var powerManagerService: Any? = null

    @Volatile
    private var windowManagerService: Any? = null

    @Volatile
    private var displayOverrideActive = false

    @Volatile
    private var displayOverrideDisplayId: Int? = null

    @Volatile
    private var pendingKeyguardWakeHold = false

    @Volatile
    private var pendingBackgroundLockAfterTimeout = false

    @Volatile
    private var coverHomeOwner = NativeSecondaryHomeRouter.CoverHomeOwner.NONE

    @Volatile
    private var loggedDisplayManagerBinding = false

    @Volatile
    private var loggedPowerManagerBinding = false

    @Volatile
    private var loggedWindowTimeoutNormalization = false

    @Volatile
    private var timeoutGeneration = 0L

    @Volatile
    private var lastObservedDisplayOn: Boolean? = null

    @Volatile
    private var lastScheduledLogElapsed = 0L

    private var observer: ContentObserver? = null
    private val timeoutRunnable = Runnable { onTimeout() }
    private val backgroundLockRunnable = Runnable {
        preparePendingBackgroundKeyguard("display-off-settled")
    }

    fun install(classLoader: ClassLoader) {
        installDisplayManagerBinding(classLoader)
        installPowerManagerActivityHooks(classLoader)
        installWindowManagerBinding(classLoader)
    }

    fun restoreNativeCoverTimeout(
        systemContext: Context,
        log: (String) -> Unit
    ) {
        context = systemContext
        migrateLegacyCoverTimeout(systemContext, log)
        registerSettingsObserver(systemContext)
        log(
            "cover timeout uses the independent lockscreen setting while Keyguard is showing; " +
                "desktop screen-off timeout is retained after unlock"
        )
        schedule("system-context-ready")
    }

    fun onCoverDisplayStateChanged() {
        val systemContext = context ?: return
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) {
            cancel("non-built-in-target")
            return
        }
        val displayOn = isCoverDisplayOn(systemContext)
        val previousOn = lastObservedDisplayOn
        lastObservedDisplayOn = displayOn
        if (pendingBackgroundLockAfterTimeout && !displayOn) {
            preparePendingBackgroundKeyguard("display-state-off")
            cancel("timed-out-display-off")
            return
        }
        if (!displayOn) {
            if (previousOn != false) cancel("display-state-off")
            return
        }
        if (shouldScheduleCoverTimeoutForDisplayState(previousOn, displayOn)) {
            schedule("display-turned-on")
        }
    }

    fun onCoverDisplayResolutionChanged(
        previous: com.flexunlock.dexlsp.CoverDisplaySnapshot?,
        current: com.flexunlock.dexlsp.CoverDisplaySnapshot?
    ) {
        if (previous?.id == current?.id) {
            return
        }
        cancel("display-resolution-changed:${previous?.id}->${current?.id}")
        pendingKeyguardWakeHold = false
        pendingBackgroundLockAfterTimeout = false
        timeoutHandler.removeCallbacks(backgroundLockRunnable)
        CoverWakeBrightnessGatePolicy.onCoverDisplayResolutionChanged()
        CoverKeyguardSurfacePolicy.forceRelease("display-resolution-changed")
        displayOverrideDisplayId?.let { oldDisplayId ->
            applyDisplayStateOverrideTo(oldDisplayId, Display.STATE_UNKNOWN, 0)
        }
        displayOverrideActive = false
        displayOverrideDisplayId = null
        lastObservedDisplayOn = null
        if (com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) {
            schedule("display-resolution-ready")
        }
    }

    fun onKeyguardStateChanged(showing: Boolean) {
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return
        if (showing) {
            pendingBackgroundLockAfterTimeout = false
            timeoutHandler.removeCallbacks(backgroundLockRunnable)
        }
        when {
            showing -> {
                log(
                    "cover Keyguard shown; independent timeout=" +
                        "${CoverDisplayConfig.readLockscreenTimeoutMillis(context ?: return)}ms"
                )
                schedule("keyguard-shown")
            }
            CoverLockTransitionPolicy.isAccessRestricted() -> {
                log(
                    "cover transient Keyguard dismissal retained protection " +
                        "lockPending=${CoverLockTransitionPolicy.isLockPending()}"
                )
            }
            else -> {
                CoverWakeBrightnessGatePolicy.forceCancel(
                    reason = "keyguard-dismissed",
                    restoreBrightness = true
                )
                CoverKeyguardSurfacePolicy.forceRelease("keyguard-dismissed")
                schedule("keyguard-dismissed")
            }
        }
    }

    fun onCoverKeyguardSurfaceReady() {
        if (
            !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() ||
            !RuntimeFacts.isClosed() ||
            !CoverLockTransitionPolicy.isAccessRestricted()
        ) {
            return
        }
        if (!CoverKeyguardSurfacePolicy.isReleaseReady()) return
        if (CoverWakeBrightnessGatePolicy.onKeyguardSurfaceReady()) {
            pendingKeyguardWakeHold = false
            return
        }
        if (pendingKeyguardWakeHold) {
            if (releasePendingKeyguardWakeHold("native-keyguard-surface-ready")) {
                schedule("native-keyguard-surface-ready")
            }
            return
        }
        if (
            NativeSecondaryHomeRouter.isCoverKeyguardShowing() &&
            CoverKeyguardSurfacePolicy.releaseWhenReady()
        ) {
            schedule("native-keyguard-surface-ready-without-display-hold")
        }
    }

    fun onCoverKeyguardSurfaceWatchdog(reason: String) {
        if (!CoverKeyguardSurfacePolicy.hasSafetyLayer()) return
        pendingKeyguardWakeHold = false
        applyDisplayStateOverride(Display.STATE_UNKNOWN, 0)
        CoverWakeBrightnessGatePolicy.forceCancel(
            reason = "watchdog-fail-open:$reason",
            restoreBrightness = true
        )
        CoverKeyguardSurfacePolicy.forceRelease("watchdog-fail-open:$reason")
        schedule("keyguard-surface-watchdog-fail-open")
        log(
            "Keyguard safety watchdog returned to native rendering " +
                "reason=$reason restricted=${CoverLockTransitionPolicy.isAccessRestricted()} " +
                "keyguard=${NativeSecondaryHomeRouter.isCoverKeyguardShowing()}"
        )
    }

    fun failClosedForWakeGate(reason: String) {
        pendingKeyguardWakeHold = false
        applyDisplayStateOverride(Display.STATE_UNKNOWN, 0)
        CoverWakeBrightnessGatePolicy.forceCancel(
            reason = "wake-gate-fail-open:$reason",
            restoreBrightness = true
        )
        CoverKeyguardSurfacePolicy.forceRelease("wake-gate-fail-open:$reason")
        schedule("wake-gate-fail-open")
        log("wake brightness gate returned to native rendering reason=$reason")
    }

    fun onCoverHomeOwnerChanged(owner: NativeSecondaryHomeRouter.CoverHomeOwner) {
        coverHomeOwner = owner
    }

    fun holdDisplayOffForPendingKeyguard(reason: String) {
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return
        if (!RuntimeFacts.isClosed() || !CoverLockTransitionPolicy.isLockPending()) return
        coverHomeOwner = NativeSecondaryHomeRouter.currentCoverHomeOwner()
        pendingKeyguardWakeHold = true
        if (!CoverKeyguardSurfacePolicy.hasSafetyLayer()) {
            pendingKeyguardWakeHold = false
            clearDisplayOverride("keyguard-safety-layer-unavailable:$reason")
            log("Keyguard safety layer unavailable; native wake rendering retained reason=$reason")
            return
        }
        if (releasePendingKeyguardWakeHold("keyguard-ready-during-wake-hold")) {
            schedule("keyguard-ready-during-wake-hold")
        } else {
            cancel("pending-keyguard:$reason")
            log(
                "display-1 wake protected while awaiting native Keyguard surface " +
                    "safetyLayer=${CoverKeyguardSurfacePolicy.hasSafetyLayer()} reason=$reason"
            )
        }
    }

    private fun installDisplayManagerBinding(classLoader: ClassLoader) {
        val serviceClass = XposedHelpers.findClassIfExists(
            DISPLAY_MANAGER_SERVICE,
            classLoader
        ) ?: return log("display timeout unavailable: DisplayManagerService missing")

        runCatching {
            XposedBridge.hookAllConstructors(serviceClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    displayManagerService = param.thisObject
                    if (!loggedDisplayManagerBinding) {
                        loggedDisplayManagerBinding = true
                        log("display-1 timeout bound to DisplayManagerService")
                    }
                    schedule("display-manager-ready")
                }
            })
            log("display-1 per-display timeout binding installed")
        }.onFailure { error ->
            log("display timeout binding unavailable: ${error.message}")
        }
    }

    private fun installPowerManagerActivityHooks(classLoader: ClassLoader) {
        val serviceClass = XposedHelpers.findClassIfExists(
            POWER_MANAGER_SERVICE,
            classLoader
        ) ?: return log("display timeout unavailable: PowerManagerService missing")

        runCatching {
            XposedBridge.hookAllConstructors(serviceClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    powerManagerService = param.thisObject
                }
            })
            serviceClass.declaredMethods
                .filter { method ->
                    method.name == "userActivityInternal" && method.parameterCount == 5
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            powerManagerService = param.thisObject
                            val displayId = (param.args.firstOrNull() as? Number)?.toInt()
                                ?: return
                            val coverDisplayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId()
                                ?: return
                            val systemContext = context ?: return
                            if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() ||
                                !RuntimeFacts.isClosed() || !isCoverUserActivity(
                                    reportedDisplayId = displayId,
                                    coverDisplayId = coverDisplayId,
                                    defaultDisplayOn = isDisplayOn(
                                        systemContext,
                                        Display.DEFAULT_DISPLAY
                                    ),
                                    coverDisplayOn = isDisplayOn(systemContext, coverDisplayId),
                                    keyguardShowing =
                                        NativeSecondaryHomeRouter.isCoverKeyguardShowing()
                                )
                            ) return
                            clearDisplayOverride("user-activity display=$displayId")
                            schedule("user-activity display=$displayId cover=$coverDisplayId")
                        }
                    })
                }

            serviceClass.declaredMethods
                .filter { method ->
                    method.name == "wakePowerGroupLocked" && method.parameterCount >= 1
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            powerManagerService = param.thisObject
                            if (
                                !com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget() ||
                                !RuntimeFacts.isClosed()
                            ) return
                            clearDisplayOverride("power-group-wake")
                            schedule("power-group-wake")
                        }
                    })
                }
            if (!loggedPowerManagerBinding) {
                loggedPowerManagerBinding = true
                log("display-1 activity and wake observation installed")
            }
        }.onFailure { error ->
            log("display timeout activity observation unavailable: ${error.message}")
        }
    }

    private fun installWindowManagerBinding(classLoader: ClassLoader) {
        val serviceClass = XposedHelpers.findClassIfExists(
            WINDOW_MANAGER_SERVICE,
            classLoader
        ) ?: return log("display timeout unavailable: WindowManagerService missing")
        runCatching {
            serviceClass.declaredMethods
                .filter { method ->
                    method.name == "main" &&
                        java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                        method.returnType == serviceClass
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            windowManagerService = param.result
                        }
                    })
                }

            serviceClass.declaredMethods
                .filter { method ->
                    (method.name == "addWindow" || method.name == "relayoutWindow") &&
                        method.parameterTypes.any { type ->
                            type == WindowManager.LayoutParams::class.java
                        }
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args.filterIsInstance<WindowManager.LayoutParams>()
                                .forEach(::normalizeSystemUiWindowTimeout)
                        }
                    })
                }
            log("SystemUI window activity timeout normalization installed")
        }.onFailure { error ->
            log("display timeout WindowManager binding unavailable: ${error.message}")
        }
    }

    private fun normalizeSystemUiWindowTimeout(attrs: WindowManager.LayoutParams) {
        val packageName = runCatching {
            XposedHelpers.getObjectField(attrs, "packageName") as? String
        }.getOrNull()
        if (packageName != "com.android.systemui") return
        val title = attrs.title?.toString().orEmpty()
        val type = runCatching { XposedHelpers.getIntField(attrs, "type") }
            .getOrDefault(0)
        if (!title.contains("SubHomeActivity") &&
            !title.contains("SubScreenQuickPanel") &&
            !title.contains("NotificationShade") &&
            type != 2040
        ) return
        val userActivityTimeout = runCatching {
            XposedHelpers.getLongField(attrs, "userActivityTimeout")
        }.getOrDefault(-1L)
        val screenDimDuration = runCatching {
            XposedHelpers.getLongField(attrs, "screenDimDuration")
        }.getOrDefault(-1L)
        if (userActivityTimeout < 0L && screenDimDuration < 0L) return
        XposedHelpers.setLongField(attrs, "userActivityTimeout", -1L)
        XposedHelpers.setLongField(attrs, "screenDimDuration", -1L)
        if (!loggedWindowTimeoutNormalization) {
            loggedWindowTimeoutNormalization = true
            log("SystemUI cover window timeout cleared title=$title")
        }
    }

    private fun migrateLegacyCoverTimeout(
        systemContext: Context,
        log: (String) -> Unit
    ) {
        val resolver = systemContext.contentResolver
        val legacyValue = Settings.System.getString(resolver, LEGACY_COVER_SCREEN_TIMEOUT)
        if (legacyValue != LEGACY_COVER_TIMEOUT_SECONDS) return

        val removed = Settings.System.putString(
            resolver,
            LEGACY_COVER_SCREEN_TIMEOUT,
            null
        )
        log(
            if (removed) {
                "removed legacy cover_screen_timeout=10; system timeout now owns display OFF"
            } else {
                "failed to remove legacy cover_screen_timeout=10"
            }
        )
    }

    private fun registerSettingsObserver(systemContext: Context) {
        synchronized(stateLock) {
            if (observer != null) return
            val settingsObserver = object : ContentObserver(timeoutHandler) {
                override fun onChange(selfChange: Boolean) {
                    schedule("system-screen-timeout-setting-changed")
                }
            }
            systemContext.contentResolver.registerContentObserver(
                Settings.System.getUriFor(SCREEN_OFF_TIMEOUT),
                false,
                settingsObserver
            )
            systemContext.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(CoverDisplayConfig.SETTINGS_LOCKSCREEN_TIMEOUT_KEY),
                false,
                settingsObserver
            )
            observer = settingsObserver
        }
    }

    private fun scheduledTimeoutMillis(systemContext: Context): Long {
        val systemTimeoutMillis = Settings.System.getLong(
            systemContext.contentResolver,
            SCREEN_OFF_TIMEOUT,
            DEFAULT_SCREEN_TIMEOUT_MILLIS
        )
        return coverTimeoutMillisForKeyguard(
            keyguardShowing = NativeSecondaryHomeRouter.isCoverKeyguardShowing(),
            systemTimeoutMillis = systemTimeoutMillis,
            lockscreenTimeoutMillis = CoverDisplayConfig.readLockscreenTimeoutMillis(systemContext)
        )
    }

    private fun schedule(reason: String) {
        val systemContext = context ?: return
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return
        timeoutHandler.post {
            if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return@post
            if (!isCoverDisplayOn(systemContext)) {
                cancel("display-not-on:$reason")
                return@post
            }
            val timeoutMillis = scheduledTimeoutMillis(systemContext)
            synchronized(stateLock) {
                timeoutGeneration++
                timeoutHandler.removeCallbacks(timeoutRunnable)
                timeoutHandler.postDelayed(timeoutRunnable, timeoutMillis)
            }
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (nowElapsed - lastScheduledLogElapsed > 1_000L) {
                lastScheduledLogElapsed = nowElapsed
                log(
                    "cover timeout scheduled reason=$reason " +
                        "timeoutMs=$timeoutMillis"
                )
            }
        }
    }

    private fun cancel(reason: String) {
        synchronized(stateLock) {
            timeoutGeneration++
            timeoutHandler.removeCallbacks(timeoutRunnable)
        }
        log("display-1 timeout cancelled reason=$reason")
    }

    private fun onTimeout() {
        val systemContext = context ?: return
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return
        if (!RuntimeFacts.isClosed() || !isCoverDisplayOn(systemContext)) return
        if (isScreenKeptAwake()) {
            schedule("screen-wakelock-active")
            return
        }
        val timeout = scheduledTimeoutMillis(systemContext)
        pendingKeyguardWakeHold = false
        pendingBackgroundLockAfterTimeout = true
        applyDisplayStateOverride(Display.STATE_OFF, 0)
        if (!displayOverrideActive) {
            pendingBackgroundLockAfterTimeout = false
            log("cover timeout display-OFF request failed; native timeout retained")
            return
        }
        timeoutHandler.removeCallbacks(backgroundLockRunnable)
        timeoutHandler.postDelayed(
            backgroundLockRunnable,
            BACKGROUND_LOCK_AFTER_DISPLAY_OFF_MILLIS
        )
        log(
            "cover timeout expired timeoutMs=$timeout " +
                "requestedDisplayOff=true backgroundKeyguardPending=true"
        )
    }

    private fun isScreenKeptAwake(): Boolean =
        hasScreenKeepingWakeLock() || hasKeepScreenOnWindow()

    private fun hasScreenKeepingWakeLock(): Boolean {
        val service = powerManagerService ?: return false
        val lock = runCatching { XposedHelpers.getObjectField(service, "mLock") }
            .getOrNull() ?: service
        return synchronized(lock) {
            val wakeLocks = runCatching {
                XposedHelpers.getObjectField(service, "mWakeLocks") as? Iterable<*>
            }.getOrNull() ?: return@synchronized false
            wakeLocks.any { wakeLock ->
                wakeLock != null && runCatching {
                    isScreenKeepingWakeLock(
                        XposedHelpers.getIntField(wakeLock, "mFlags"),
                        XposedHelpers.getObjectField(wakeLock, "mTag") as? String
                    )
                }.getOrDefault(false)
            }
        }
    }

    private fun hasKeepScreenOnWindow(): Boolean = runCatching {
        val service = windowManagerService ?: return false
        val displayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId() ?: return false
        val lock = XposedHelpers.getObjectField(service, "mGlobalLock")
        synchronized(lock) {
            val root = XposedHelpers.getObjectField(service, "mRoot")
            val displayContent = XposedHelpers.callMethod(root, "getDisplayContent", displayId)
                ?: return@synchronized false
            val window = runCatching {
                XposedHelpers.getObjectField(displayContent, "mHoldScreenWindow")
            }.getOrNull() ?: return@synchronized false
            val attrs = XposedHelpers.getObjectField(window, "mAttrs") as WindowManager.LayoutParams
            isWindowKeepingScreenOn(attrs.flags)
        }
    }.getOrDefault(false)

    private fun preparePendingBackgroundKeyguard(reason: String): Boolean {
        if (!pendingBackgroundLockAfterTimeout) return false
        pendingBackgroundLockAfterTimeout = false
        timeoutHandler.removeCallbacks(backgroundLockRunnable)
        val keyguardPrepared =
            NativeSecondaryHomeRouter.isCoverKeyguardShowing() ||
                CoverDisplayPolicy.requestKeyguardForCoverTimeout()
        log(
            "cover timed-out display kept OFF; background Keyguard " +
                "prepared=$keyguardPrepared reason=$reason"
        )
        return true
    }

    private fun clearDisplayOverride(reason: String) {
        preparePendingBackgroundKeyguard("override-clear:$reason")
        if (!RuntimeFacts.isClosed()) {
            pendingKeyguardWakeHold = false
            CoverWakeBrightnessGatePolicy.forceCancel(
                reason = "cover-opened:$reason",
                restoreBrightness = true
            )
            CoverKeyguardSurfacePolicy.forceRelease("cover-opened:$reason")
        }
        if (pendingKeyguardWakeHold && !isPendingKeyguardSurfaceReady()) {
            log("display-1 override clear suppressed awaiting native Keyguard surface reason=$reason")
            return
        }
        if (!displayOverrideActive) {
            pendingKeyguardWakeHold = false
            CoverKeyguardSurfacePolicy.releaseWhenReady()
            return
        }
        applyDisplayStateOverride(Display.STATE_UNKNOWN, 0)
        pendingKeyguardWakeHold = false
        CoverKeyguardSurfacePolicy.releaseWhenReady()
        log("display-1 display-state override cleared reason=$reason")
    }

    private fun releasePendingKeyguardWakeHold(reason: String): Boolean {
        if (!pendingKeyguardWakeHold || !isPendingKeyguardSurfaceReady()) return false
        clearDisplayOverride(reason)
        return !pendingKeyguardWakeHold
    }

    private fun isPendingKeyguardSurfaceReady(): Boolean =
        !RuntimeFacts.isClosed() ||
            (
                NativeSecondaryHomeRouter.isCoverKeyguardShowing() &&
                    CoverKeyguardSurfacePolicy.isReleaseReady()
            )

    private fun applyDisplayStateOverride(state: Int, maxTimeoutMillis: Int) {
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return
        val displayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId() ?: return
        if (applyDisplayStateOverrideTo(displayId, state, maxTimeoutMillis)) {
            displayOverrideActive = state != Display.STATE_UNKNOWN
            displayOverrideDisplayId = displayId.takeIf { displayOverrideActive }
        }
    }

    private fun applyDisplayStateOverrideTo(
        displayId: Int,
        state: Int,
        maxTimeoutMillis: Int
    ): Boolean {
        val service = displayManagerService ?: return false
        return runCatching {
            XposedHelpers.callMethod(
                service,
                "setDisplayStateOverrideWithDisplayIdInternal",
                state,
                displayId,
                maxTimeoutMillis,
                displayOverrideToken
            )
            true
        }.onFailure { error ->
            log(
                "cover display-state override failed display=$displayId " +
                    "state=$state: ${error.message}"
            )
        }.getOrDefault(false)
    }

    private fun isCoverDisplayOn(systemContext: Context): Boolean {
        val displayId = com.flexunlock.dexlsp.CoverDisplayResolver.currentId() ?: return false
        return isDisplayOn(systemContext, displayId)
    }

    private fun isDisplayOn(systemContext: Context, displayId: Int): Boolean {
        val display = systemContext
            .getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId)
            ?: return false
        return display.state == Display.STATE_ON
    }

    private fun log(message: String) {
        android.util.Log.i("FlexUnlock-SystemBridge", message)
        XposedBridge.log("FlexUnlock-SystemBridge: $message")
    }
}
