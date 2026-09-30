package com.flexunlock.dexlsp.system.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Keeps display 1 dark until the safety layer has survived a physical screen-on. */
internal object CoverWakeBrightnessGatePolicy {
    private enum class GateState {
        IDLE,
        HOLDING,
        BRIGHTNESS_OPENING
    }

    private const val DEFAULT_DISPLAY_GROUP = 0
    private const val WAKE_REASON_TAP = 15
    private const val WAKEFULNESS_AWAKE = 1
    private const val DOUBLE_TAP_TO_WAKE = "double_tap_to_wake"
    private const val PRESENT_GUARD_MILLIS = 50L
    private const val POWER_GROUP_CLASS = "com.android.server.power.PowerGroup"
    private const val POWER_MANAGER_SERVICE_CLASS =
        "com.android.server.power.PowerManagerService"
    private const val NOTIFIER_CLASS = "com.android.server.power.Notifier"
    private const val DISPLAY_POWER_STATE_CLASS =
        "com.android.server.display.DisplayPowerState"
    private const val DISPLAY_POWER_CONTROLLER_CLASS =
        "com.android.server.display.DisplayPowerController"
    private const val ACTUAL_STATE_CALLBACK_CLASS =
        "com.android.server.display.DisplayPowerController\$\$ExternalSyntheticLambda16"

    private val handler = Handler(Looper.getMainLooper())
    private val stateLock = Any()

    private var state = GateState.IDLE
    private var generation = 0L
    private var actualDisplayOn = false
    private var darkBacklightApplied = false
    private var safetyLayerArmed = false
    private var screenOnAllowed = false
    private var keyguardSurfaceReady = false
    private var displayPowerState: Any? = null
    private var displayPowerController: Any? = null
    private var loggedSuppressedGeneration = -1L
    private var loggedScreenOnBlockedGeneration = -1L

    fun install(classLoader: ClassLoader) {
        installDoubleTapWakeSettingGate(classLoader)
        installDisplayPowerStateBinding(classLoader)
        installColorFadeLevelGate(classLoader)
        installPhysicalScreenOnGate(classLoader)
        installActualDisplayStateObservation(classLoader)
        installPowerGroupWakeGate(classLoader)
        installNotifierWakeFallback(classLoader)
    }

    private fun installDoubleTapWakeSettingGate(classLoader: ClassLoader) {
        val serviceClass = XposedHelpers.findClassIfExists(
            POWER_MANAGER_SERVICE_CLASS,
            classLoader
        ) ?: return log("double-tap wake setting gate unavailable: PowerManagerService missing")

        runCatching {
            val methods = serviceClass.declaredMethods.filter { method ->
                method.name == "wakePowerGroupLocked" && method.parameterCount >= 4
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val powerGroup = param.args.firstOrNull() ?: return
                        val groupId = runCatching {
                            XposedHelpers.getIntField(powerGroup, "mGroupId")
                        }.getOrDefault(-1)
                        val wakeReason = (param.args.getOrNull(2) as? Number)?.toInt() ?: return
                        if (
                            !shouldBlockCoverTapWake(
                                builtInTarget = com.flexunlock.dexlsp.CoverDisplayResolver
                                    .isBuiltInTarget(),
                                coverClosed = RuntimeFacts.isClosed(),
                                groupId = groupId,
                                wakeReason = wakeReason,
                                doubleTapToWakeEnabled = {
                                    isDoubleTapToWakeEnabled(param.thisObject)
                                }
                            )
                        ) return
                        param.result = null
                        log("display-1 tap wake blocked by system double-tap setting")
                    }
                })
            }
            log("display-1 double-tap wake setting gate installed methods=${methods.size}")
        }.onFailure { error ->
            log("double-tap wake setting gate unavailable: ${error.message}")
        }
    }

    private fun isDoubleTapToWakeEnabled(service: Any): Boolean {
        val context = runCatching {
            XposedHelpers.getObjectField(service, "mContext") as? Context
        }.getOrNull() ?: return true
        return runCatching {
            (XposedHelpers.callStaticMethod(
                Settings.Secure::class.java,
                "getIntForUser",
                context.contentResolver,
                DOUBLE_TAP_TO_WAKE,
                0,
                -2
            ) as Number).toInt() != 0
        }.getOrDefault(true)
    }

    fun onCoverDisplayResolutionChanged() {
        val (powerState, controller) = synchronized(stateLock) {
            state = GateState.IDLE
            generation++
            actualDisplayOn = false
            darkBacklightApplied = false
            safetyLayerArmed = false
            screenOnAllowed = true
            keyguardSurfaceReady = false
            val bound = displayPowerState to displayPowerController
            displayPowerState = null
            displayPowerController = null
            bound
        }
        setColorFadeLevel(powerState, 1f)
        requestPowerStateUpdate(controller)
        log("cover display resolution changed; wake brightness bindings cleared")
    }

    fun onKeyguardSurfaceReady(): Boolean {
        val ownsRelease = synchronized(stateLock) {
            if (state == GateState.IDLE) return false
            keyguardSurfaceReady = true
            true
        }
        if (ownsRelease) scheduleOpeningIfReady("keyguard-surface-ready")
        return ownsRelease
    }

    fun onSleepRequested(reason: String) {
        val shouldCancel = synchronized(stateLock) {
            state != GateState.IDLE
        }
        if (!shouldCancel) return
        forceCancel(reason = "sleep-requested:$reason", restoreBrightness = false)
        CoverKeyguardSurfacePolicy.prepareHiddenForSleep()
    }

    fun forceCancel(reason: String, restoreBrightness: Boolean) {
        val (powerState, controller) = synchronized(stateLock) {
            if (state == GateState.IDLE) return
            state = GateState.IDLE
            generation++
            actualDisplayOn = false
            darkBacklightApplied = false
            safetyLayerArmed = false
            screenOnAllowed = true
            keyguardSurfaceReady = false
            displayPowerState to displayPowerController
        }
        if (restoreBrightness) {
            setColorFadeLevel(powerState, 1f)
            requestPowerStateUpdate(controller)
        }
        log(
            "display-1 wake brightness gate cancelled reason=$reason " +
                "restoreBrightness=$restoreBrightness"
        )
    }

    private fun installPowerGroupWakeGate(classLoader: ClassLoader) {
        val powerGroupClass = XposedHelpers.findClassIfExists(
            POWER_GROUP_CLASS,
            classLoader
        ) ?: return log("wake brightness gate unavailable: PowerGroup missing")

        runCatching {
            val methods = powerGroupClass.declaredMethods.filter { method ->
                method.name == "setWakefulnessLocked" &&
                    method.parameterTypes.size >= 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val groupId = runCatching {
                            XposedHelpers.getIntField(param.thisObject, "mGroupId")
                        }.getOrDefault(-1)
                        val requestedWakefulness =
                            (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        val currentWakefulness = runCatching {
                            XposedHelpers.getIntField(param.thisObject, "mWakefulness")
                        }.getOrDefault(requestedWakefulness)
                        if (groupId != DEFAULT_DISPLAY_GROUP) {
                            return
                        }
                        val reason = (param.args.getOrNull(3) as? Number)?.toInt()
                        if (requestedWakefulness != WAKEFULNESS_AWAKE) {
                            onSleepRequested("power-group wakefulness=$requestedWakefulness reason=$reason")
                            return
                        }
                        if (currentWakefulness == WAKEFULNESS_AWAKE) return
                        beginEarlyWakeGate("power-group reason=$reason")
                    }
                })
            }
            log("display-1 PowerGroup wake brightness gate installed methods=${methods.size}")
        }.onFailure { error ->
            log("PowerGroup wake brightness gate unavailable: ${error.message}")
        }
    }

    private fun installNotifierWakeFallback(classLoader: ClassLoader) {
        val notifierClass = XposedHelpers.findClassIfExists(
            NOTIFIER_CLASS,
            classLoader
        ) ?: return log("wake brightness fallback unavailable: Notifier missing")

        runCatching {
            val methods = notifierClass.declaredMethods.filter { method ->
                method.name == "onGroupWakefulnessChangeStarted" &&
                    method.parameterTypes.size == 4
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val groupId = (param.args.getOrNull(0) as? Number)?.toInt() ?: return
                        val wakefulness = (param.args.getOrNull(1) as? Number)?.toInt() ?: return
                        val reason = (param.args.getOrNull(2) as? Number)?.toInt()
                        if (groupId != DEFAULT_DISPLAY_GROUP || wakefulness != WAKEFULNESS_AWAKE) {
                            return
                        }
                        beginEarlyWakeGate("notifier-fallback reason=$reason")
                    }
                })
            }
            log("display-1 Notifier wake brightness fallback installed methods=${methods.size}")
        }.onFailure { error ->
            log("Notifier wake brightness fallback unavailable: ${error.message}")
        }
    }

    private fun installDisplayPowerStateBinding(classLoader: ClassLoader) {
        val powerStateClass = XposedHelpers.findClassIfExists(
            DISPLAY_POWER_STATE_CLASS,
            classLoader
        ) ?: return log("wake brightness gate unavailable: DisplayPowerState missing")

        runCatching {
            XposedBridge.hookAllConstructors(powerStateClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (com.flexunlock.dexlsp.CoverRuntime.isCoverDisplay(displayIdOf(param.thisObject))) {
                        synchronized(stateLock) { displayPowerState = param.thisObject }
                        log("display-1 wake brightness gate bound to DisplayPowerState")
                    }
                }
            })
        }.onFailure { error ->
            log("DisplayPowerState binding unavailable: ${error.message}")
        }
    }

    private fun installColorFadeLevelGate(classLoader: ClassLoader) {
        val powerStateClass = XposedHelpers.findClassIfExists(
            DISPLAY_POWER_STATE_CLASS,
            classLoader
        ) ?: return

        runCatching {
            powerStateClass.declaredMethods
                .filter { method ->
                    method.name == "setColorFadeLevel" && method.parameterCount == 1
                }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!com.flexunlock.dexlsp.CoverRuntime.isCoverDisplay(displayIdOf(param.thisObject))) return
                            synchronized(stateLock) { displayPowerState = param.thisObject }
                            val requested = (param.args.firstOrNull() as? Number)?.toFloat()
                                ?: return
                            val currentGeneration = synchronized(stateLock) {
                                if (state == GateState.IDLE || requested <= 0f) return
                                generation
                            }
                            param.result = null
                            synchronized(stateLock) {
                                if (loggedSuppressedGeneration == currentGeneration) return
                                loggedSuppressedGeneration = currentGeneration
                            }
                            log(
                                "display-1 wake brightness suppressed colorFadeLevel=$requested " +
                                    "generation=$currentGeneration"
                            )
                        }
                    })
                }
        }.onFailure { error ->
            log("color fade brightness gate unavailable: ${error.message}")
        }
    }

    private fun installPhysicalScreenOnGate(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(
            DISPLAY_POWER_CONTROLLER_CLASS,
            classLoader
        ) ?: return log("wake brightness gate unavailable: DisplayPowerController missing")

        runCatching {
            val methods = controllerClass.declaredMethods.filter { method ->
                method.name == "setScreenState" &&
                    method.parameterTypes.size == 3 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val controller = param.thisObject ?: return
                        val displayId = runCatching {
                            XposedHelpers.getIntField(controller, "mDisplayId")
                        }.getOrDefault(-1)
                        if (!com.flexunlock.dexlsp.CoverRuntime.isCoverDisplay(displayId)) return
                        synchronized(stateLock) { displayPowerController = controller }

                        val requestedState = (param.args.firstOrNull() as? Number)?.toInt()
                            ?: return
                        if (requestedState != Display.STATE_ON) return
                        val blockedGeneration = synchronized(stateLock) {
                            if (state == GateState.IDLE || screenOnAllowed) return
                            generation
                        }
                        param.result = false
                        synchronized(stateLock) {
                            if (loggedScreenOnBlockedGeneration == blockedGeneration) return
                            loggedScreenOnBlockedGeneration = blockedGeneration
                        }
                        log(
                            "display-1 physical screen ON blocked generation=$blockedGeneration " +
                                "dark=$darkBacklightApplied armed=$safetyLayerArmed"
                        )
                    }
                })
            }
            log("display-1 physical screen ON gate installed methods=${methods.size}")
        }.onFailure { error ->
            log("physical screen ON gate unavailable: ${error.message}")
        }
    }

    private fun installActualDisplayStateObservation(classLoader: ClassLoader) {
        val callbackClass = XposedHelpers.findClassIfExists(
            ACTUAL_STATE_CALLBACK_CLASS,
            classLoader
        ) ?: return log("wake brightness gate unavailable: actual-state callback missing")

        runCatching {
            callbackClass.declaredMethods
                .filter { method -> method.name == "run" && method.parameterCount == 0 }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val controller = runCatching {
                                XposedHelpers.getObjectField(param.thisObject, "f\$0")
                            }.getOrNull() ?: return
                            val displayId = runCatching {
                                XposedHelpers.getIntField(controller, "mDisplayId")
                            }.getOrDefault(-1)
                            val actualState = runCatching {
                                XposedHelpers.getIntField(controller, "mActualDisplayState")
                            }.getOrDefault(Display.STATE_UNKNOWN)
                            if (!com.flexunlock.dexlsp.CoverDisplayResolver.matchesBuiltIn(displayId)) return
                            synchronized(stateLock) { displayPowerController = controller }
                            observeDarkBacklight(controller)
                            if (actualState != Display.STATE_ON) return
                            val observed = synchronized(stateLock) {
                                if (state != GateState.HOLDING) return
                                actualDisplayOn = true
                                true
                            }
                            if (observed) {
                                log("display-1 wake brightness gate observed physical ON")
                                scheduleOpeningIfReady("physical-display-on")
                            }
                        }
                    })
                }
            log("display-1 physical ON observation installed")
        }.onFailure { error ->
            log("physical ON observation unavailable: ${error.message}")
        }
    }

    private fun beginEarlyWakeGate(reason: String) {
        if (DisplayChannelModePolicy.isAppliedFull()) return
        if (!com.flexunlock.dexlsp.CoverDisplayResolver.isBuiltInTarget()) return
        if (
            !RuntimeFacts.isClosed() ||
            !CoverLockTransitionPolicy.isAccessRestricted()
        ) return
        val powerState = synchronized(stateLock) {
            if (state != GateState.IDLE) {
                log("display-1 wake brightness gate retained reason=$reason state=$state")
                return
            }
            generation++
            state = GateState.HOLDING
            actualDisplayOn = false
            darkBacklightApplied = false
            safetyLayerArmed = false
            screenOnAllowed = false
            keyguardSurfaceReady = false
            loggedSuppressedGeneration = -1L
            loggedScreenOnBlockedGeneration = -1L
            displayPowerState
        }
        log("display-1 wake brightness gate engaged reason=$reason")
        val armed = CoverKeyguardSurfacePolicy.armForPendingWake()
        synchronized(stateLock) {
            if (state == GateState.HOLDING) safetyLayerArmed = armed
        }
        if (!armed) {
            CoverTimeoutPolicy.failClosedForWakeGate("safety-layer-unavailable:$reason")
            return
        }
        setColorFadeLevel(powerState, 0f)
        observeDarkBacklightOf(powerState)
        maybeAllowPhysicalScreenOn("dark-backlight-and-safety-layer")
    }

    private fun observeDarkBacklight(controller: Any) {
        val powerState = runCatching {
            XposedHelpers.getObjectField(controller, "mPowerState")
        }.getOrNull()
        if (powerState != null) {
            synchronized(stateLock) { displayPowerState = powerState }
        }
        observeDarkBacklightOf(powerState)
    }

    private fun observeDarkBacklightOf(powerState: Any?) {
        if (powerState == null) return
        val actualBacklight = runCatching {
            val photonicModulator = XposedHelpers.getObjectField(
                powerState,
                "mPhotonicModulator"
            )
            XposedHelpers.getFloatField(photonicModulator, "mActualBacklight")
        }.getOrNull() ?: return
        val newlyDark = synchronized(stateLock) {
            if (
                state != GateState.HOLDING ||
                darkBacklightApplied ||
                actualBacklight > 0f ||
                actualBacklight.isNaN()
            ) return
            darkBacklightApplied = true
            true
        }
        if (newlyDark) {
            log("display-1 wake brightness gate confirmed dark backlight=$actualBacklight")
            maybeAllowPhysicalScreenOn("dark-backlight-confirmed")
        }
    }

    private fun maybeAllowPhysicalScreenOn(reason: String) {
        val (scheduledGeneration, controller) = synchronized(stateLock) {
            if (
                state != GateState.HOLDING ||
                screenOnAllowed ||
                !darkBacklightApplied ||
                !safetyLayerArmed
            ) return
            screenOnAllowed = true
            generation to displayPowerController
        }
        log(
            "display-1 physical screen ON released reason=$reason " +
                "generation=$scheduledGeneration"
        )
        requestPowerStateUpdate(controller, scheduledGeneration)
    }

    private fun requestPowerStateUpdate(controller: Any?, expectedGeneration: Long? = null) {
        if (controller == null) return
        val controllerHandler = runCatching {
            XposedHelpers.getObjectField(controller, "mHandler") as? Handler
        }.getOrNull() ?: return
        controllerHandler.post {
            if (expectedGeneration != null) {
                val current = synchronized(stateLock) {
                    generation == expectedGeneration && screenOnAllowed
                }
                if (!current) return@post
            }
            runCatching {
                XposedHelpers.callMethod(controller, "updatePowerState")
            }.onFailure { error ->
                log("display-1 power-state retry failed: ${error.message}")
            }
        }
    }

    private fun scheduleOpeningIfReady(reason: String) {
        val scheduledGeneration = synchronized(stateLock) {
            if (
                state != GateState.HOLDING ||
                !actualDisplayOn ||
                !keyguardSurfaceReady
            ) return
            state = GateState.BRIGHTNESS_OPENING
            generation
        }
        handler.postDelayed(
            {
                val powerState = synchronized(stateLock) {
                    if (
                        generation != scheduledGeneration ||
                        state != GateState.BRIGHTNESS_OPENING
                    ) return@postDelayed
                    state = GateState.IDLE
                    displayPowerState
                }
                setColorFadeLevel(powerState, 1f)
                log(
                    "display-1 wake brightness opened after protected present guard " +
                        "reason=$reason generation=$scheduledGeneration"
                )
                handler.postDelayed(
                    {
                        val sameGeneration = synchronized(stateLock) {
                            generation == scheduledGeneration && state == GateState.IDLE
                        }
                        if (
                            sameGeneration &&
                            RuntimeFacts.isClosed() &&
                            CoverLockTransitionPolicy.isAccessRestricted()
                        ) {
                            CoverKeyguardSurfacePolicy.releaseWhenReady()
                        }
                    },
                    PRESENT_GUARD_MILLIS
                )
            },
            PRESENT_GUARD_MILLIS
        )
    }

    private fun setColorFadeLevel(powerState: Any?, level: Float) {
        if (powerState == null) return
        runCatching {
            XposedHelpers.callMethod(powerState, "setColorFadeLevel", level)
        }.onFailure { error ->
            log("display-1 wake brightness update failed level=$level: ${error.message}")
        }
    }

    private fun displayIdOf(powerState: Any): Int = runCatching {
        XposedHelpers.getIntField(powerState, "mDisplayId")
    }.getOrDefault(-1)

    private fun log(message: String) {
        android.util.Log.i("FlexUnlock-SystemBridge", message)
    }
}

internal fun shouldBlockCoverTapWake(
    builtInTarget: Boolean,
    coverClosed: Boolean,
    groupId: Int,
    wakeReason: Int,
    doubleTapToWakeEnabled: () -> Boolean
): Boolean = builtInTarget &&
    coverClosed &&
    groupId == 0 &&
    wakeReason == 15 &&
    !doubleTapToWakeEnabled()
