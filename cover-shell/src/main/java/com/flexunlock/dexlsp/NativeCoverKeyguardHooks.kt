package com.flexunlock.dexlsp

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

internal fun shouldPreserveCoverWidgetAuthentication(
    visible: Boolean,
    secure: Boolean,
    currentPage: Int?,
    defaultPage: Int?
): Boolean = visible && secure && currentPage != null &&
    defaultPage != null && currentPage != defaultPage

internal object NativeCoverKeyguardHooks {
    private const val SCOPE = "CoverKeyguard"
    private const val KEYGUARD_STATE_CONTROLLER =
        "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"
    private const val KEYGUARD_LIFECYCLE_HANDLER =
        "com.android.systemui.keyguard.KeyguardLifecyclesDispatcher\$KeyguardLifecycleHandler"
    private const val STARTED_WAKING_UP = 4
    private const val STARTED_GOING_TO_SLEEP = 6
    private const val SUBSCREEN_MANAGER =
        "com.android.systemui.subscreen.SubScreenManager"
    private const val SUB_HOME_ACTIVITY_CLASS =
        "com.android.systemui.subscreen.SubHomeActivity"
    private const val SUBLAUNCHER_GESTURE_LISTENER = "aod.Sv0"
    private const val SUBLAUNCHER_WINDOW_MANAGER = "aod.Yx1"
    private const val SUBLAUNCHER_BOUNCER_CONTROLLER =
        "com.samsung.android.app.sublauncher.presentation.plugin.controller.SubLauncherBouncerViewController"
    private const val SUB_HOME_ACTIVITY = "com.android.systemui.subscreen.SubHomeActivity"
    private const val SUBLAUNCHER_MAIN_GESTURE_DISCRIMINATOR = 4
    private const val CLOCK_BLUR_MANAGER = "aod.bs"
    private const val SUB_LAUNCHER_STATE_MANAGER =
        "com.samsung.android.app.sublauncher.presentation.plugin.support.SubLauncherStateManager"
    private const val SUB_LAUNCHER_CLOCK_COLOR_MANAGER =
        "com.samsung.android.app.sublauncher.presentation.plugin.controller.SubLauncherClockColorManager"
    private const val ABS_CLOCK_VIEW =
        "com.samsung.android.uniform.widget.clock.AbsClockView"
    private const val CLOCK_HOLDER_SCRIM_ALPHA = "aod.Al1"
    private const val SEC_LIGHT_REVEAL_SCRIM_HELPER =
        "com.android.systemui.statusbar.SecLightRevealScrimHelper"
    private const val POWER_BUTTON_REVEAL =
        "com.android.systemui.statusbar.PowerButtonReveal"
    private const val LIGHT_REVEAL_SCRIM =
        "com.android.systemui.statusbar.LightRevealScrim"

    private val hookedPluginGestureClasses = mutableSetOf<Class<*>>()
    private val hookedPluginBouncerClasses = mutableSetOf<Class<*>>()
    private val hookedClockBlurManagerClasses = mutableSetOf<Class<*>>()
    private val hookedSubLauncherStateManagerClasses = mutableSetOf<Class<*>>()
    private val hookedClockColorManagerClasses = mutableSetOf<Class<*>>()
    private val hookedAbsClockViewClasses = mutableSetOf<Class<*>>()
    private val hookedClockHolderScrimClasses = mutableSetOf<Class<*>>()
    private val hookedClockRenderColorMethods = mutableSetOf<Method>()
    private val hookedClockRenderColorClasses = mutableSetOf<Class<*>>()
    private val loggedClockRenderColorMethods = mutableSetOf<Method>()
    private val pluginHooksScheduled = AtomicBoolean(false)

    @Volatile
    private var showing = false

    @Volatile
    private var transitioningToSleep = false

    @Volatile
    private var secure = false

    @Volatile
    private var subScreenManager: Any? = null

    @Volatile
    private var subLauncherStateManager: Any? = null

    @Volatile
    private var subLauncherWindowManager: Any? = null

    @Volatile
    private var stableLockedClockColor: Int? = null

    @Volatile
    private var secLightRevealScrimHelper: Any? = null

    @Volatile
    private var loggedCoverPowerRevealReplacement = false

    fun install(classLoader: ClassLoader) {
        // 整体容错:任一子桥接失败(如旧 OneUI 固件方法/字段不同)只跳过该
        // 部分,不影响其他功能与 SystemUI 稳定性。
        runCatching { installKeyguardStateBridge(classLoader) }
        runCatching { installWakefulnessPriorityBridge(classLoader) }
        runCatching { installSamsungSubscreenBridge(classLoader) }
        runCatching { installSamsungCoverLightRevealBridge(classLoader) }
    }

    fun isShowing(): Boolean = showing && CoverRuntime.isBuiltInCoverSessionEligible()

    fun isPriorityActive(): Boolean =
        (showing || transitioningToSleep) && CoverRuntime.isBuiltInCoverSessionEligible()

    fun blockLockedActivity(activity: Activity): Boolean {
        if (!isShowing()) return false
        requestCoverBouncer("blocked ${activity.javaClass.name}")
        activity.finishAndRemoveTask()
        return true
    }

    fun requestCoverBouncer(reason: String): Boolean {
        if (!CoverRuntime.isBuiltInCoverSessionEligible()) return false
        val manager = subScreenManager
        if (manager == null) {
            unavailable("SubScreenManager unavailable while $reason")
            return false
        }
        return runCatching {
            XposedHelpers.callMethod(manager, "requestCoverBouncer")
            CoverRuntime.log(
                SCOPE,
                "Samsung cover bouncer requested secure=$secure reason=$reason"
            )
            true
        }.onFailure {
            unavailable("Samsung cover bouncer failed while $reason: ${it.message}")
        }.getOrDefault(false)
    }

    private fun installKeyguardStateBridge(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(
            KEYGUARD_STATE_CONTROLLER,
            classLoader
        ) ?: return unavailable("KeyguardStateControllerImpl missing")

        runCatching {
            XposedBridge.hookAllConstructors(controllerClass, stateHook())
            listOf("notifyKeyguardState", "update").forEach { methodName ->
                XposedBridge.hookAllMethods(controllerClass, methodName, stateHook())
            }
            CoverRuntime.log(SCOPE, "system Keyguard state bridge installed")
        }.onFailure { unavailable(it.message) }
    }

    private fun installWakefulnessPriorityBridge(classLoader: ClassLoader) {
        val lifecycleHandlerClass = XposedHelpers.findClassIfExists(
            KEYGUARD_LIFECYCLE_HANDLER,
            classLoader
        ) ?: return unavailable("KeyguardLifecycleHandler missing")

        runCatching {
            XposedBridge.hookAllMethods(
                lifecycleHandlerClass,
                "handleMessage",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val message = param.args.firstOrNull() as? android.os.Message ?: return
                        when (message.what) {
                            STARTED_GOING_TO_SLEEP -> {
                                NativeCoverKeyguardTransitionHooks.onStartedGoingToSleep()
                                setTransitioningToSleep(true)
                            }

                            STARTED_WAKING_UP -> {
                                NativeCoverKeyguardTransitionHooks.onStartedWakingUp()
                                setTransitioningToSleep(false)
                                // Workaround: after boot the cover display
                                // occasionally stays dim until the user touches
                                // the brightness slider or re-locks. Re-assert
                                // the configured brightness shortly after wake.
                                runCatching {
                                    val app = XposedHelpers.callStaticMethod(
                                        Class.forName("android.app.ActivityThread"),
                                        "currentApplication"
                                    ) as? android.content.Context
                                }
                            }
                        }
                    }
                }
            )
            CoverRuntime.log(SCOPE, "early wakefulness Keyguard priority bridge installed")
        }.onFailure { unavailable("wakefulness Keyguard priority bridge failed: ${it.message}") }
    }

    private fun setTransitioningToSleep(next: Boolean) {
        if (transitioningToSleep == next) return
        if (next) captureStableLockedClockColor()
        val wasPriorityActive = isPriorityActive()
        transitioningToSleep = next
        val priorityActive = isPriorityActive()
        CoverRuntime.log(
            SCOPE,
            "early Keyguard priority active=$priorityActive " +
                "showing=$showing transitioningToSleep=$transitioningToSleep"
        )
        if (priorityActive != wasPriorityActive) {
            NativeCoverStatusBarHooks.syncPresentation()
        }
    }

    private fun captureStableLockedClockColor() {
        val manager = subLauncherStateManager ?: return
        val color = lockedClockColorFromState(manager) ?: return
        stableLockedClockColor = color
        CoverRuntime.log(
            SCOPE,
            "captured stable locked clock color=$stableLockedClockColor"
        )
    }

    private fun lockedClockColorFromState(manager: Any): Int? {
        val rawColor = runCatching {
            val state = XposedHelpers.getObjectField(manager, "h0")
            (XposedHelpers.callMethod(state, "getValue") as? Number)?.toInt()
        }.getOrNull() ?: return null
        if (rawColor == 0) return null
        return Color.rgb(
            Color.red(rawColor),
            Color.green(rawColor),
            Color.blue(rawColor)
        )
    }

    private fun installSamsungSubscreenBridge(classLoader: ClassLoader) {
        val managerClass = XposedHelpers.findClassIfExists(SUBSCREEN_MANAGER, classLoader)
            ?: return unavailable("SubScreenManager missing")

        runCatching {
            XposedBridge.hookAllConstructors(managerClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    subScreenManager = param.thisObject
                    CoverRuntime.log(SCOPE, "Samsung SubScreenManager bridge ready")
                }
            })
            XposedBridge.hookAllMethods(
                managerClass,
                "onPluginConnected",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        subScreenManager = param.thisObject
                        val plugin = param.args.firstOrNull()
                        val pluginContext = param.args.getOrNull(1) as? android.content.Context
                        val pluginClassLoader =
                            plugin?.javaClass?.classLoader ?: pluginContext?.classLoader
                        schedulePluginHooks(pluginClassLoader)
                    }
                }
            )
            XposedHelpers.findClassIfExists(SUB_HOME_ACTIVITY_CLASS, classLoader)?.let { activityClass ->
                XposedBridge.hookAllMethods(
                    activityClass,
                    "onDestroy",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (releaseSubLauncherWindows()) {
                                CoverRuntime.log(SCOPE, "SubLauncher windows released before Activity destroy")
                            }
                        }
                    }
                )
            }
        }.onFailure { unavailable("SubScreenManager bridge failed: ${it.message}") }
    }

    private fun schedulePluginHooks(pluginClassLoader: ClassLoader?) {
        if (pluginClassLoader == null) return
        if (!pluginHooksScheduled.compareAndSet(false, true)) return
        Thread(
            {
                runCatching {
                    installPluginUnlockGestureBridge(pluginClassLoader)
                    installPluginWidgetLaunchBridge(pluginClassLoader)
                    installPluginWindowLifecycleBridge(pluginClassLoader)
                    NativeCoverKeyguardTransitionHooks.installPlugin(pluginClassLoader)
                    AodServiceHooks.installPlugin(pluginClassLoader)
                    installClockColorStateAlphaBridge(pluginClassLoader)
                    installClockViewColorBridge(pluginClassLoader)
                    installClockDozeAmountBridge(pluginClassLoader)
                    installClockHolderScrimBridge(pluginClassLoader)
                    installClockBlurAlphaBridge(pluginClassLoader)
                }.onFailure { error ->
                    pluginHooksScheduled.set(false)
                    unavailable("plugin hooks async failed: ${error.message}")
                }
            },
            "flexunlock-keyguard-plugin"
        ).apply { isDaemon = true }.start()
    }

    private fun installSamsungCoverLightRevealBridge(classLoader: ClassLoader) {
        val helperClass = XposedHelpers.findClassIfExists(
            SEC_LIGHT_REVEAL_SCRIM_HELPER,
            classLoader
        ) ?: return unavailable("SecLightRevealScrimHelper missing")
        val powerButtonRevealClass = XposedHelpers.findClassIfExists(
            POWER_BUTTON_REVEAL,
            classLoader
        ) ?: return unavailable("PowerButtonReveal missing")
        val lightRevealScrimClass = XposedHelpers.findClassIfExists(
            LIGHT_REVEAL_SCRIM,
            classLoader
        ) ?: return unavailable("LightRevealScrim missing")

        runCatching {
            XposedBridge.hookAllConstructors(
                helperClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        secLightRevealScrimHelper = param.thisObject
                    }
                }
            )
            XposedBridge.hookAllMethods(
                helperClass,
                "disableLightRevealScreenOn",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        secLightRevealScrimHelper = param.thisObject
                    }
                }
            )
            XposedBridge.hookAllMethods(
                powerButtonRevealClass,
                "setRevealAmountOnScrim",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val coverReveal = foldedCoverReveal() ?: return
                        val revealAmount = (param.args.firstOrNull() as? Number)?.toFloat()
                            ?: return
                        val scrim = param.args.getOrNull(1) ?: return
                        XposedHelpers.callMethod(
                            coverReveal,
                            "setRevealAmountOnScrim",
                            revealAmount,
                            scrim
                        )
                        param.result = null
                        logCoverPowerRevealReplacement()
                    }
                }
            )
            XposedBridge.hookAllMethods(
                lightRevealScrimClass,
                "setRevealEffect",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val requestedEffect = param.args.firstOrNull() ?: return
                        if (requestedEffect.javaClass.name != POWER_BUTTON_REVEAL) return
                        val coverReveal = foldedCoverReveal() ?: return
                        param.args[0] = coverReveal
                        logCoverPowerRevealReplacement()
                    }
                }
            )
            XposedBridge.hookAllMethods(
                lightRevealScrimClass,
                "onDraw",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val currentEffect = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "revealEffect")
                        }.getOrNull() ?: return
                        if (currentEffect.javaClass.name != POWER_BUTTON_REVEAL) return
                        val coverReveal = foldedCoverReveal() ?: return
                        val revealAmount = runCatching {
                            XposedHelpers.getFloatField(param.thisObject, "revealAmount")
                        }.getOrNull() ?: return
                        XposedHelpers.setObjectField(
                            param.thisObject,
                            "revealEffect",
                            coverReveal
                        )
                        XposedHelpers.callMethod(
                            coverReveal,
                            "setRevealAmountOnScrim",
                            revealAmount,
                            param.thisObject
                        )
                        logCoverPowerRevealReplacement()
                    }
                }
            )
            CoverRuntime.log(SCOPE, "Samsung folded power-button reveal bridge installed")
        }.onFailure {
            unavailable("Samsung folded power-button reveal bridge failed: ${it.message}")
        }
    }

    private fun foldedCoverReveal(): Any? {
        val helper = secLightRevealScrimHelper ?: return null
        val folded = runCatching {
            XposedHelpers.getBooleanField(helper, "isFolded")
        }.getOrDefault(false)
        if (!folded) return null
        return runCatching {
            XposedHelpers.getObjectField(helper, "secCircleReveal")
        }.getOrNull()
    }

    private fun logCoverPowerRevealReplacement() {
        if (loggedCoverPowerRevealReplacement) return
        loggedCoverPowerRevealReplacement = true
        CoverRuntime.log(
            SCOPE,
            "folded power-button wake reveal delegated to Samsung cover circle"
        )
    }

    private fun installPluginUnlockGestureBridge(classLoader: ClassLoader?) {
        if (classLoader == null) return unavailable("SubLauncher plugin ClassLoader missing")
        val gestureClass = XposedHelpers.findClassIfExists(
            SUBLAUNCHER_GESTURE_LISTENER,
            classLoader
        ) ?: return unavailable("SubLauncher main gesture listener missing")
        synchronized(hookedPluginGestureClasses) {
            if (!hookedPluginGestureClasses.add(gestureClass)) return
        }

        runCatching {
            XposedBridge.hookAllMethods(
                gestureClass,
                "onFling",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!showing) return
                        if (
                            runCatching {
                                XposedHelpers.getIntField(param.thisObject, "a")
                            }.getOrNull() != SUBLAUNCHER_MAIN_GESTURE_DISCRIMINATOR
                        ) return
                        val velocityX = (param.args.getOrNull(2) as? Number)?.toFloat() ?: return
                        val velocityY = (param.args.getOrNull(3) as? Number)?.toFloat() ?: return
                        if (velocityY >= 0f) return

                        val detector = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "b")
                        }.getOrNull() ?: return
                        val direction = runCatching {
                            XposedHelpers.getObjectField(detector, "L")
                        }.getOrNull() ?: return
                        if (
                            runCatching {
                                XposedHelpers.callMethod(direction, "isVertical") == true
                            }.getOrDefault(false).not()
                        ) return
                        val presentState = runCatching {
                            XposedHelpers.getObjectField(detector, "b")
                        }.getOrNull() ?: return
                        val presentMode = runCatching {
                            val state = XposedHelpers.callMethod(presentState, "F2")
                            XposedHelpers.callMethod(state, "getValue")
                        }.getOrNull() ?: return
                        if (
                            runCatching {
                                XposedHelpers.callMethod(presentMode, "isDefault") == true
                            }.getOrDefault(false).not()
                        ) return

                        requestCoverBouncer(
                            "native default-page upward fling " +
                                "vx=${velocityX.toInt()} vy=${velocityY.toInt()}"
                        )
                    }
                }
            )
            CoverRuntime.log(
                SCOPE,
                "Samsung default-page upward-fling bouncer bridge installed " +
                    "class=${gestureClass.name}"
            )
        }.onFailure {
            unavailable("SubLauncher upward-fling bridge failed: ${it.message}")
        }
    }

    private fun installPluginWidgetLaunchBridge(classLoader: ClassLoader) {
        val bouncerClass = XposedHelpers.findClassIfExists(
            SUBLAUNCHER_BOUNCER_CONTROLLER,
            classLoader
        ) ?: return unavailable("SubLauncher bouncer controller missing")
        synchronized(hookedPluginBouncerClasses) {
            if (!hookedPluginBouncerClasses.add(bouncerClass)) return
        }

        XposedBridge.hookAllMethods(
            bouncerClass,
            "initBouncer",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val visible = param.args.firstOrNull() as? Boolean ?: return
                    if (!visible || !showing || !secure || !CoverRuntime.isBuiltInCoverSessionEligible()) {
                        return
                    }
                    val context = XposedHelpers.getObjectField(param.thisObject, "context") as Context
                    if (!CoverRuntime.isCoverDisplay(context.display?.displayId)) return
                    val presentState = XposedHelpers.getObjectField(param.thisObject, "presentState")
                    val currentPage = pageIndex(presentState, "P2")
                    val defaultPage = pageIndex(presentState, "W1")
                    if (!shouldPreserveCoverWidgetAuthentication(
                            visible,
                            secure,
                            currentPage,
                            defaultPage
                        )
                    ) return
                    context.sendBroadcast(
                        Intent(CoverRuntime.COVER_WIDGET_AUTH_ACTION),
                        CoverRuntime.COVER_BROADCAST_PERMISSION
                    )
                    CoverRuntime.log(
                        SCOPE,
                        "cover widget authentication armed currentPage=$currentPage " +
                            "defaultPage=$defaultPage"
                    )
                }
            }
        )

        XposedBridge.hookAllMethods(
            bouncerClass,
            "requestBouncerWithIntent",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!showing || secure || !CoverRuntime.isBuiltInCoverSessionEligible()) return
                    val intent = param.args.firstOrNull() as? Intent ?: return
                    val pendingIntent = param.args.getOrNull(1) as? PendingIntent ?: return
                    val context = XposedHelpers.getObjectField(param.thisObject, "context") as Context
                    val displayId = context.display?.displayId ?: return
                    if (!CoverRuntime.isCoverDisplay(displayId)) return

                    XposedHelpers.callMethod(
                        param.thisObject,
                        "sendPendingIntent",
                        intent,
                        pendingIntent
                    )
                    val top = context.getSystemService(ActivityManager::class.java)
                        .getRunningTasks(20)
                        .firstOrNull {
                            XposedHelpers.getIntField(it, "displayId") == displayId
                        }
                        ?.topActivity
                    val embeddedWidget =
                        top?.packageName == CoverRuntime.SYSTEM_UI_PACKAGE &&
                            top.className == SUB_HOME_ACTIVITY
                    if (!embeddedWidget) {
                        XposedHelpers.callMethod(
                            context.getSystemService(KeyguardManager::class.java),
                            "semDismissKeyguard"
                        )
                    }
                    param.result = null
                    CoverRuntime.log(
                        SCOPE,
                        "cover widget launch dispatched target=$top embedded=$embeddedWidget"
                    )
                }
            }
        )
        CoverRuntime.log(SCOPE, "cover widget nonsecure launch bridge installed")
    }

    private fun pageIndex(presentState: Any, methodName: String): Int? = runCatching {
        val stateFlow = XposedHelpers.callMethod(presentState, methodName)
        (XposedHelpers.callMethod(stateFlow, "getValue") as? Number)?.toInt()
    }.getOrNull()

    private fun installPluginWindowLifecycleBridge(classLoader: ClassLoader) {
        val managerClass = XposedHelpers.findClassIfExists(
            SUBLAUNCHER_WINDOW_MANAGER,
            classLoader
        ) ?: return unavailable("SubLauncher window manager missing")
        XposedBridge.hookAllConstructors(managerClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                subLauncherWindowManager = param.thisObject
            }
        })
        XposedBridge.hookAllMethods(managerClass, "i", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.args.firstOrNull() as? View ?: return
                if (view.isAttachedToWindow) return
                @Suppress("UNCHECKED_CAST")
                (XposedHelpers.getObjectField(param.thisObject, "d") as MutableCollection<View>)
                    .remove(view)
                param.result = null
            }
        })
        CoverRuntime.log(SCOPE, "SubLauncher window lifecycle bridge installed")
    }

    private fun releaseSubLauncherWindows(): Boolean {
        val manager = subLauncherWindowManager ?: return false
        @Suppress("UNCHECKED_CAST")
        val tracked = XposedHelpers.getObjectField(manager, "d") as MutableCollection<View>
        val windowManager = XposedHelpers.callMethod(
            XposedHelpers.getObjectField(manager, "c"),
            "getValue"
        ) as WindowManager
        tracked.toList().forEach { view ->
            if (view.isAttachedToWindow) windowManager.removeViewImmediate(view)
        }
        tracked.clear()
        return true
    }

    private fun installClockColorStateAlphaBridge(classLoader: ClassLoader?) {
        if (classLoader == null) return unavailable("SubLauncher state ClassLoader missing")
        val stateManagerClass = XposedHelpers.findClassIfExists(
            SUB_LAUNCHER_STATE_MANAGER,
            classLoader
        ) ?: return unavailable("SubLauncherStateManager missing")
        synchronized(hookedSubLauncherStateManagerClasses) {
            if (!hookedSubLauncherStateManagerClasses.add(stateManagerClass)) return
        }

        runCatching {
            XposedBridge.hookAllConstructors(
                stateManagerClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        subLauncherStateManager = param.thisObject
                    }
                }
            )
            XposedBridge.hookAllMethods(
                stateManagerClass,
                "n2",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        subLauncherStateManager = param.thisObject
                        val requested = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        val opaqueRequested = Color.rgb(
                            Color.red(requested),
                            Color.green(requested),
                            Color.blue(requested)
                        )
                        val target = if (isPriorityActive()) {
                            stableLockedClockColor
                                ?: lockedClockColorFromState(param.thisObject)
                                    ?.also { stableLockedClockColor = it }
                                ?: opaqueRequested
                        } else {
                            opaqueRequested
                        }
                        if (target != requested) param.args[0] = target
                    }
                }
            )
            CoverRuntime.log(
                SCOPE,
                "native cover clock color state opacity bridge installed " +
                    "class=${stateManagerClass.name}"
            )
        }.onFailure {
            unavailable("native cover clock color state opacity bridge failed: ${it.message}")
        }
    }

    private fun installClockViewColorBridge(classLoader: ClassLoader?) {
        if (classLoader == null) return unavailable("SubLauncher clock color ClassLoader missing")
        val colorManagerClass = XposedHelpers.findClassIfExists(
            SUB_LAUNCHER_CLOCK_COLOR_MANAGER,
            classLoader
        ) ?: return unavailable("SubLauncherClockColorManager missing")
        synchronized(hookedClockColorManagerClasses) {
            if (!hookedClockColorManagerClasses.add(colorManagerClass)) return
        }

        runCatching {
            XposedBridge.hookAllMethods(
                colorManagerClass,
                "setClockColor",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isPriorityActive()) return
                        if (synchronized(hookedClockRenderColorClasses) {
                                param.thisObject.javaClass in hookedClockRenderColorClasses
                            }
                        ) return
                        val stableColor = stableLockedClockColor ?: return
                        val clockView = param.args.firstOrNull() as? View ?: return
                        installClockRenderColorBridge(clockView)
                        val requested = runCatching {
                            (XposedHelpers.callMethod(clockView, "getDateColor") as? Number)
                                ?.toInt()
                        }.getOrNull()
                        runCatching {
                            XposedHelpers.callMethod(clockView, "setCustomColor", stableColor)
                        }.onSuccess {
                            CoverRuntime.log(
                                SCOPE,
                                "stabilized cover clock view color " +
                                    "requested=$requested target=$stableColor"
                            )
                        }.onFailure {
                            unavailable("cover clock view color stabilization failed: ${it.message}")
                        }
                    }
                }
            )
            CoverRuntime.log(
                SCOPE,
                "native cover clock view color bridge installed " +
                    "class=${colorManagerClass.name}"
            )
        }.onFailure {
            unavailable("native cover clock view color bridge failed: ${it.message}")
        }
    }

    private fun installClockDozeAmountBridge(classLoader: ClassLoader?) {
        if (classLoader == null) return unavailable("AbsClockView ClassLoader missing")
        val clockViewClass = XposedHelpers.findClassIfExists(ABS_CLOCK_VIEW, classLoader)
            ?: return unavailable("AbsClockView missing")
        synchronized(hookedAbsClockViewClasses) {
            if (!hookedAbsClockViewClasses.add(clockViewClass)) return
        }

        runCatching {
            XposedBridge.hookAllMethods(
                clockViewClass,
                "setDozeAmount",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isPriorityActive()) return
                        if (synchronized(hookedClockRenderColorClasses) {
                                param.thisObject.javaClass in hookedClockRenderColorClasses
                            }
                        ) return
                        val stableColor = stableLockedClockColor ?: return
                        runCatching {
                            XposedHelpers.callMethod(
                                param.thisObject,
                                "setCustomColor",
                                stableColor
                            )
                        }.onFailure {
                            unavailable("clock doze color stabilization failed: ${it.message}")
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                clockViewClass,
                "D",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isPriorityActive()) return
                        param.result = stableLockedClockColor ?: return
                    }
                }
            )
            CoverRuntime.log(
                SCOPE,
                "native cover clock doze color bridge installed class=${clockViewClass.name}"
            )
        }.onFailure {
            unavailable("native cover clock doze color bridge failed: ${it.message}")
        }
    }

    private fun installClockHolderScrimBridge(classLoader: ClassLoader?) {
        if (classLoader == null) return unavailable("clock holder scrim ClassLoader missing")
        val scrimClass = XposedHelpers.findClassIfExists(
            CLOCK_HOLDER_SCRIM_ALPHA,
            classLoader
        ) ?: return unavailable("clock holder scrim alpha producer missing")
        synchronized(hookedClockHolderScrimClasses) {
            if (!hookedClockHolderScrimClasses.add(scrimClass)) return
        }

        runCatching {
            XposedBridge.hookAllMethods(
                scrimClass,
                "invokeSuspend",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isPriorityActive()) return
                        if (stableLockedClockColor == null) return
                        param.result = 0
                    }
                }
            )
            CoverRuntime.log(
                SCOPE,
                "native cover clock holder scrim bridge installed class=${scrimClass.name}"
            )
        }.onFailure {
            unavailable("native cover clock holder scrim bridge failed: ${it.message}")
        }
    }

    private fun installClockRenderColorBridge(clockView: View) {
        val renderTarget = deepestClockRenderTarget(clockView)
        val renderMethod = runCatching {
            renderTarget.javaClass.getMethod("Z", Int::class.javaPrimitiveType)
        }.getOrElse {
            unavailable(
                "cover clock final color method missing " +
                    "class=${renderTarget.javaClass.name}: ${it.message}"
            )
            return
        }
        synchronized(hookedClockRenderColorMethods) {
            if (!hookedClockRenderColorMethods.add(renderMethod)) return
        }

        runCatching {
            XposedBridge.hookMethod(
                renderMethod,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPriorityActive()) return
                        val stableColor = stableLockedClockColor ?: return
                        val requested = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        val firstHit = synchronized(loggedClockRenderColorMethods) {
                            loggedClockRenderColorMethods.add(renderMethod)
                        }
                        if (firstHit) {
                            CoverRuntime.log(
                                SCOPE,
                                "cover clock final color bridge hit " +
                                    "method=${renderMethod.declaringClass.name}.${renderMethod.name} " +
                                    "requested=$requested target=$stableColor"
                            )
                        }
                        if (requested != stableColor) param.args[0] = stableColor
                    }
                }
            )
            synchronized(hookedClockRenderColorClasses) {
                hookedClockRenderColorClasses.add(renderMethod.declaringClass)
            }
            CoverRuntime.log(
                SCOPE,
                "native cover clock final color bridge installed " +
                    "method=${renderMethod.declaringClass.name}.${renderMethod.name}"
            )
        }.onFailure {
            synchronized(hookedClockRenderColorMethods) {
                hookedClockRenderColorMethods.remove(renderMethod)
            }
            synchronized(hookedClockRenderColorClasses) {
                hookedClockRenderColorClasses.remove(renderMethod.declaringClass)
            }
            unavailable("native cover clock final color bridge failed: ${it.message}")
        }
    }

    private fun deepestClockRenderTarget(clockView: View): View {
        val visited = mutableSetOf<View>()
        var current = clockView
        while (visited.add(current)) {
            val nested = runCatching {
                XposedHelpers.getObjectField(current, "k1") as? View
            }.getOrNull() ?: break
            current = nested
        }
        return current
    }

    private fun installClockBlurAlphaBridge(classLoader: ClassLoader?) {
        if (classLoader == null) return unavailable("SubLauncher clock ClassLoader missing")
        val managerClass = XposedHelpers.findClassIfExists(CLOCK_BLUR_MANAGER, classLoader)
            ?: return unavailable("SubLauncher ClockBlurManager missing")
        synchronized(hookedClockBlurManagerClasses) {
            if (!hookedClockBlurManagerClasses.add(managerClass)) return
        }

        runCatching {
            XposedBridge.hookAllMethods(
                managerClass,
                "b",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPriorityActive()) return
                        val requested = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        val opaqueRequested = Color.rgb(
                            Color.red(requested),
                            Color.green(requested),
                            Color.blue(requested)
                        )
                        val target = stableLockedClockColor ?: opaqueRequested
                        if (target != requested) param.args[0] = target
                    }
                }
            )
            XposedBridge.hookAllMethods(
                managerClass,
                "a",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPriorityActive()) return
                        val alpha = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (alpha == 255) return
                        param.args[0] = 255
                    }
                }
            )
            CoverRuntime.log(
                SCOPE,
                "native cover clock blur opacity bridge installed class=${managerClass.name}"
            )
        }.onFailure {
            unavailable("native cover clock blur opacity bridge failed: ${it.message}")
        }
    }

    private fun stateHook() = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            updateFromController(param.thisObject)
        }
    }

    private fun updateFromController(controller: Any) {
        val nextShowing = runCatching {
            XposedHelpers.getBooleanField(controller, "mShowing")
        }.getOrDefault(false)
        val nextSecure = runCatching {
            XposedHelpers.getBooleanField(controller, "mSecure")
        }.getOrDefault(false)
        if (nextShowing == showing && nextSecure == secure) return

        val wasPriorityActive = isPriorityActive()
        if (showing && !nextShowing) {
            NativeCoverKeyguardTransitionHooks.onKeyguardHidden()
        }
        showing = nextShowing
        secure = nextSecure
        val priorityActive = isPriorityActive()
        CoverRuntime.log(
            SCOPE,
            "system Keyguard state showing=$showing secure=$secure " +
                "priority=$priorityActive; native subscreen owns presentation"
        )
        if (priorityActive != wasPriorityActive) {
            NativeCoverStatusBarHooks.syncPresentation()
        }
    }

    private fun unavailable(reason: String?) {
        CoverRuntime.log(SCOPE, "unavailable: ${reason ?: "unknown"}")
    }
}
