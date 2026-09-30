package com.flexunlock.dexlsp

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import com.flexunlock.dexlsp.config.CoverDisplayConfig

internal fun coverStatusDimensionPx(
    baselineDp: Float,
    density: Float,
    ratio: Float
): Int = maxOf(1, (baselineDp * density * ratio).toInt())

internal fun coverStatusTextSizePx(
    baselineSp: Float,
    scaledDensity: Float,
    ratio: Float
): Float = baselineSp * scaledDensity * ratio

internal fun isCoverHomeActivity(className: String?, stableFullQs: Boolean): Boolean =
    className == "com.honeyspace.dexservice.SecondaryLauncher" ||
        (stableFullQs && className == "com.sec.android.app.launcher.activities.LauncherActivity")

internal fun isFullCoverMainLauncher(
    className: String?,
    stableFullQs: Boolean,
    homeActivityType: Boolean = false
): Boolean = stableFullQs && (
    homeActivityType ||
        className == "com.sec.android.app.launcher.activities.LauncherActivity"
    )

internal fun fullDexBlocksCoverQuickPanel(
    fullDexEnabled: Boolean,
    coverDisplay: Boolean
): Boolean = fullDexEnabled && coverDisplay

internal object NativeCoverStatusBarHooks {
    private const val SCOPE = "SystemUI"
    private const val ROTATION_SETTLE_RECOVERY_DELAY_MS = 450L
    private const val CONTROLLER_CLASS =
        "com.android.systemui.subscreen.SubScreenQuickPanelWindowController"
    private const val EVENT_HANDLER_CLASS =
        "com.android.systemui.subscreen.SubScreenQSEventHandler"
    private const val PLUGIN_AOD_MANAGER_CLASS =
        "com.android.systemui.doze.PluginAODManager"
    private const val FOLD_LISTENER_CLASS =
        "com.android.systemui.subscreen.SubScreenQuickPanelWindowController\$1"
    private const val COVER_BLUR_CONTROLLER_CLASS =
        "com.android.systemui.blur.SecCoverBlurController"
    private const val TASK_STACK_LISTENER_IMPL_CLASS =
        "com.android.systemui.shared.system.TaskStackChangeListeners\$Impl"
    private const val SECONDARY_LAUNCHER_CLASS =
        "com.honeyspace.dexservice.SecondaryLauncher"
    private const val RECENTS_ACTIVITY_CLASS = "com.android.quickstep.RecentsActivity"
    private const val COMMAND_QUEUE_CLASS = "com.android.systemui.statusbar.CommandQueue"
    private const val APPEARANCE_LIGHT_STATUS_BARS = 0x8
    private const val PRIVATE_FLAG_TRUSTED_OVERLAY = 0x20000000
    private const val COLLAPSED_HEADER_TOP_MARGIN_DP = 2
    private const val COVER_STATUS_ICON_SIZE_RATIO = 0.76f
    private const val COVER_STATUS_TEXT_SIZE_RATIO = 0.86f
    private const val TOP_SURFACE_CACHE_MS = 80L
    private const val ROTATION_SURFACE_HOLD_MS = 900L
    private const val STATUS_SYNC_POSTED_MARK = "flexunlockStatusSyncPosted"
    private const val BATCH_SYNC_POSTED_MARK = "flexunlockBatchStatusSyncPosted"
    private const val WINDOW_UPDATE_POSTED_MARK = "flexunlockStatusWindowUpdatePosted"
    private const val ROTATION_LAYOUT_POSTED_MARK = "flexunlockRotationLayoutPosted"
    private const val ROTATION_RECOVERY_GENERATION_MARK =
        "flexunlockRotationRecoveryGeneration"
    private const val ROTATION_GEOMETRY_MARK = "flexunlockRotationGeometry"

    private enum class CoverTopSurface {
        SECONDARY_LAUNCHER,
        MAIN_LAUNCHER,
        RECENTS,
        TRANSITION,
        OTHER
    }

    private enum class HeaderPresentationState {
        DISABLED,
        KEYGUARD,
        HOME,
        SOURCE_PANEL,
        FULL_SHADE,
        HIDDEN
    }

    private data class HeaderPresentationInput(
        val coverEligible: Boolean,
        val hideCoverHomeStatusBar: Boolean,
        val keyguardPriorityActive: Boolean,
        val launcherWorkspaceVisible: Boolean,
        val recentsTransitionActive: Boolean,
        val homeShellTransitionActive: Boolean,
        val returningAppTransitionActive: Boolean,
        val returningAppHeaderClear: Boolean,
        val topSurface: CoverTopSurface,
        val panelExpanded: Boolean,
        val fullShadeOnCover: Boolean
    )

    private data class HeaderRenderKey(
        val state: HeaderPresentationState,
        val rootIdentity: Int
    )

    private data class TopSurfaceSnapshot(
        val surface: CoverTopSurface,
        val capturedAt: Long
    )

    private enum class TransitionAppearancePhase {
        IDLE,
        ACTIVE,
        AWAITING_NATIVE_DARK_OWNER
    }

    private data class WindowBaseline(
        val height: Int,
        val screenOrientation: Int,
        val flags: Int,
        val privateFlags: Int,
        val format: Int,
        val layoutInDisplayCutoutMode: Int
    )

    private data class HeaderBaseline(
        val topMargin: Int,
        val bottomMargin: Int
    )

    private data class StatusScaleKey(
        val compact: Boolean,
        val densityDpi: Int,
        val widthPixels: Int,
        val heightPixels: Int,
        val fontScaleBits: Int
    )

    private data class StatusIconBaseline(
        val widthDp: Float?,
        val heightDp: Float?
    )

    private data class CoverHeaderGeometry(
        val rotation: Int,
        val edgeLength: Int,
        val safeTop: Int,
        val translationY: Float,
        val translationX: Float,
        val cutoutBounds: List<Rect>
    )

    private data class AncestorClipBaseline(
        val view: WeakReference<ViewGroup>,
        val clipChildren: Boolean,
        val clipToPadding: Boolean
    )

    private data class HomeContentTransform(
        val content: WeakReference<View>,
        val translationY: Float,
        val ancestorClips: List<AncestorClipBaseline>
    )

    private val baselines = Collections.synchronizedMap(WeakHashMap<Any, WindowBaseline>())
    private val headerBaselines = Collections.synchronizedMap(WeakHashMap<View, HeaderBaseline>())
    private val blurControllers = Collections.synchronizedMap(WeakHashMap<View, Any>())
    private val transparentCollapsedPanels: MutableSet<View> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val controllers: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val controllersByRoot = Collections.synchronizedMap(WeakHashMap<View, Any>())
    private val loggedControllers: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val collapseRequestedControllers: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val presentationSnapshots = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val renderedPresentations = Collections.synchronizedMap(
        WeakHashMap<Any, HeaderRenderKey>()
    )
    private val panelExpansionStates = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val homeExpandedContentCandidates = Collections.synchronizedMap(
        WeakHashMap<View, List<WeakReference<View>>>()
    )
    private val homeTouchableInsetsListeners =
        Collections.synchronizedMap(WeakHashMap<View, Any>())
    private val homeTouchableHeights =
        Collections.synchronizedMap(WeakHashMap<View, Int>())
    private val rootGeometryLayoutListeners =
        Collections.synchronizedMap(WeakHashMap<View, View.OnLayoutChangeListener>())
    private val headerGeometrySnapshots =
        Collections.synchronizedMap(WeakHashMap<View, CoverHeaderGeometry>())
    private val homeContentTransforms =
        Collections.synchronizedMap(WeakHashMap<View, HomeContentTransform>())
    private val homeContentPreDrawListeners =
        Collections.synchronizedMap(WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>())
    private val statusIconSizeBaselines =
        Collections.synchronizedMap(WeakHashMap<View, StatusIconBaseline>())
    private val statusTextSizeBaselines =
        Collections.synchronizedMap(WeakHashMap<TextView, Float>())
    private val statusScaleStates =
        Collections.synchronizedMap(WeakHashMap<View, StatusScaleKey>())
    private val collapsedHomeContentVisibilities =
        Collections.synchronizedMap(WeakHashMap<View, Int>())

    private val expandedContentResourceNames = listOf(
        "subscreen_quickpanel_blur_view",
        "subscreen_tile_layout",
        "subscreen_media_player_root_view",
        "subroom_brightness_settings"
    )

    @Volatile
    private var launcherWorkspaceVisible = false

    @Volatile
    private var launcherWorkspaceHomeSurface = false

    @Volatile
    private var recentsTransitionActive = false

    @Volatile
    private var coverHomeShellTransitionActive = false

    @Volatile
    private var returningAppTransitionActive = false

    @Volatile
    private var returningAppTop = Float.POSITIVE_INFINITY

    @Volatile
    private var returningAppGeometryObserved = false

    @Volatile
    private var externalLaunchSuppressedUntil = 0L

    @Volatile
    private var lastCoverTopSurface = CoverTopSurface.OTHER

    @Volatile
    private var topSurfaceSnapshot = TopSurfaceSnapshot(
        CoverTopSurface.OTHER,
        Long.MIN_VALUE
    )

    @Volatile
    private var topSurfaceRotationHoldUntil = 0L

    @Volatile
    private var transitionAppearancePhase = TransitionAppearancePhase.IDLE

    @Volatile
    private var launcherWorkspaceEpoch = Long.MIN_VALUE

    @Volatile
    private var launcherWorkspaceSequence = Long.MIN_VALUE

    @Volatile
    private var workspaceReceiverRegistered = false

    private val displayRotationListeners =
        Collections.synchronizedMap(WeakHashMap<Context, DisplayManager.DisplayListener>())

    @Volatile
    private var displayRotationSyncLogged = false

    @Volatile
    private var lastPublishedQuickPanelRecentsBlock: Boolean? = null

    @Volatile
    private var lastCoverSystemBarAttributes: Array<Any?>? = null

    @Volatile
    private var coverSystemBarCommandQueue: Any? = null

    private val systemBarRedispatchDepth = ThreadLocal.withInitial { 0 }

    @Volatile
    private var systemBarOverrideLogged = false

    @Volatile
    private var trustedQuickPanelAdmissionLogged = false

    @Volatile
    private var fullDexQuickPanelBlocked = false

    fun install(classLoader: ClassLoader) {
        runCatching {
            installNativeCoverStatusBar(classLoader)
        }.onFailure {
            unavailable("native cover status bar", "install failed: ${it.message}")
        }
    }

    private fun installNativeCoverStatusBar(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(CONTROLLER_CLASS, classLoader)
            ?: return unavailable("native cover status bar", "controller missing")

        installQuickPanelWindowAdmission(classLoader)
        installFullDexQuickPanelGate(classLoader)
        installControllerTracking(controllerClass)
        installConfigurationSync()
        installCoverSystemBarAppearanceSync(classLoader)
        installCoverShellTransitionCompletion(classLoader)
        installCoverBlurTracking(classLoader)
        installTaskStackSync(classLoader)
        installPanelExpansionSync(controllerClass)
        installInitializationSync(classLoader)
        installFoldLifecycleSync(classLoader, controllerClass)
    }

    private fun installFullDexQuickPanelGate(classLoader: ClassLoader) {
        val eventHandlerClass = XposedHelpers.findClassIfExists(EVENT_HANDLER_CLASS, classLoader)
            ?: return unavailable("full DeX QuickPanel gate", "event handler missing")
        runCatching {
            val methods = eventHandlerClass.declaredMethods.filter {
                it.name == "needToBlockTouchEvent"
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (fullDexQuickPanelBlocked) param.result = true
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 full DeX QuickPanel gate installed methods=${methods.size}"
            )
        }.onFailure { unavailable("full DeX QuickPanel gate", it.message) }
    }

    private fun installQuickPanelWindowAdmission(classLoader: ClassLoader) {
        val windowManagerGlobalClass = XposedHelpers.findClassIfExists(
            "android.view.WindowManagerGlobal",
            classLoader
        ) ?: return unavailable("QuickPanel trusted admission", "WindowManagerGlobal missing")

        runCatching {
            XposedBridge.hookAllMethods(
                windowManagerGlobalClass,
                "addView",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val root = param.args.firstOrNull() as? View ?: return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(root.context))) {
                            return
                        }
                        val layoutParams = param.args.getOrNull(1)
                            as? WindowManager.LayoutParams ?: return
                        if (layoutParams.title?.toString() != "SubScreenQuickPanel") return

                        setTrustedOverlay(layoutParams, trusted = true)
                        layoutParams.format = PixelFormat.TRANSLUCENT
                        layoutParams.layoutInDisplayCutoutMode =
                            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        if (!trustedQuickPanelAdmissionLogged) {
                            trustedQuickPanelAdmissionLogged = true
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 SubScreenQuickPanel admitted as trusted translucent overlay " +
                                    "before WindowState creation"
                            )
                        }
                    }
                }
            )
        }.onFailure { unavailable("QuickPanel trusted admission", it.message) }
    }

    private fun installControllerTracking(controllerClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllConstructors(controllerClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    controllers.add(param.thisObject)
                    val context = field(param.thisObject, "mContext") as? Context
                    if (context != null) {
                        fullDexQuickPanelBlocked = fullDexBlocksCoverQuickPanel(
                            CoverDisplayConfig.readFullDex(context),
                            CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))
                        )
                        val receiverContext = context.applicationContext ?: context
                        registerWorkspaceReceiver(receiverContext)
                        installCoverHomeStatusBarConfigReceiver(receiverContext) {
                            syncAllControllers()
                        }
                        installCoverIconSizeConfigReceiver(receiverContext) { _, _ -> Unit }
                        registerDisplayRotationSync(context)
                    }
                    syncAfterLayout(param.thisObject)
                }
            })
        }.onFailure { unavailable("controller tracking", it.message) }
    }

    private fun installConfigurationSync() {
        runCatching {
            XposedBridge.hookAllMethods(
                View::class.java,
                "dispatchConfigurationChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val root = param.thisObject as? View ?: return
                        val controller = controllersByRoot[root] ?: return
                        val configuration = param.args.firstOrNull() as? Configuration ?: return
                        val geometry = listOf(
                            root.display?.rotation ?: Surface.ROTATION_0,
                            configuration.screenWidthDp,
                            configuration.screenHeightDp,
                            configuration.densityDpi
                        )
                        if (XposedHelpers.getAdditionalInstanceField(
                                root,
                                ROTATION_GEOMETRY_MARK
                            ) == geometry
                        ) return
                        XposedHelpers.setAdditionalInstanceField(root, ROTATION_GEOMETRY_MARK, geometry)
                        retainTopSurfaceDuringRotation()
                        root.post {
                            root.requestApplyInsets()
                            syncAfterLayout(controller)
                            root.postOnAnimation { syncAfterLayout(controller) }
                            root.postDelayed(
                                { syncAfterLayout(controller) },
                                TOP_SURFACE_CACHE_MS + 20L
                            )
                            scheduleRotationRecovery(root, controller)
                        }
                    }
                }
            )
            CoverRuntime.log(SCOPE, "display-1 status bar configuration sync installed")
        }.onFailure { unavailable("status bar configuration sync", it.message) }
    }

    private fun installCoverSystemBarAppearanceSync(classLoader: ClassLoader) {
        val commandQueueClass = XposedHelpers.findClassIfExists(COMMAND_QUEUE_CLASS, classLoader)
            ?: return unavailable("cover system-bar appearance", "CommandQueue missing")

        runCatching {
            val methods = commandQueueClass.declaredMethods.filter { method ->
                method.name == "onSystemBarAttributesChanged" && method.parameterCount == 8
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        val displayId = (args.firstOrNull() as? Number)?.toInt() ?: return
                        if (!CoverRuntime.isCoverDisplay(displayId)) return

                        coverSystemBarCommandQueue = param.thisObject
                        if ((systemBarRedispatchDepth.get() ?: 0) == 0) {
                            lastCoverSystemBarAttributes = args.copyOf()
                        }
                        if (!shouldOverrideCoverSystemBarAppearance()) return
                        if (releaseAppearanceOverrideOnResolvedNativeOwner(args)) return
                        applyCoverSystemBarAppearance(args)
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 native transition system-bar appearance installed methods=${methods.size}"
            )
        }.onFailure { unavailable("cover system-bar appearance", it.message) }
    }

    private fun installCoverShellTransitionCompletion(classLoader: ClassLoader) {
        val transitionsClass = XposedHelpers.findClassIfExists(
            "com.android.wm.shell.transition.Transitions",
            classLoader
        ) ?: return unavailable("cover Shell transition completion", "Transitions missing")

        runCatching {
            val playMethods = transitionsClass.declaredMethods.filter { method ->
                method.name == "playTransition" && method.parameterCount == 1
            }
            playMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activeTransition = param.args.firstOrNull() ?: return
                        val info = field(activeTransition, "mInfo") ?: return
                        if (!isCoverHomeShellTransition(info)) return
                        val context = field(param.thisObject, "mContext") as? Context ?: return
                        publishCoverShellTransition(
                            context,
                            active = true,
                            reason = "shell-home-transition-started"
                        )
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 native Shell Home transition started"
                        )
                    }
                })
            }
            val methods = transitionsClass.declaredMethods.filter { method ->
                method.name == "onFinish" && method.parameterCount == 2
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val token = param.args.firstOrNull() ?: return
                        val knownTransitions = field(param.thisObject, "mKnownTransitions")
                            as? Map<*, *> ?: return
                        val activeTransition = knownTransitions[token] ?: return
                        val info = field(activeTransition, "mInfo") ?: return
                        if (!isCoverHomeShellTransition(info)) return
                        param.setObjectExtra(CoverRuntime.COVER_SHELL_TRANSITION_ACTION, true)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (
                            param.getObjectExtra(CoverRuntime.COVER_SHELL_TRANSITION_ACTION) != true
                        ) return
                        val context = field(param.thisObject, "mContext") as? Context ?: return
                        publishCoverShellTransition(
                            context,
                            active = false,
                            reason = "shell-home-transition-finished"
                        )
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 native Shell Home transition finished"
                        )
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 native Shell transition lifecycle installed " +
                    "playMethods=${playMethods.size} finishMethods=${methods.size}"
            )
        }.onFailure { unavailable("cover Shell transition completion", it.message) }
    }

    private fun publishCoverShellTransition(
        context: Context,
        active: Boolean,
        reason: String
    ) {
        coverHomeShellTransitionActive = active
        if (!active && returningAppTransitionActive) {
            CoverRuntime.log(
                SCOPE,
                "display-1 return App geometry released at Shell finish " +
                    "observed=$returningAppGeometryObserved top=$returningAppTop"
            )
            returningAppTransitionActive = false
            returningAppTop = Float.POSITIVE_INFINITY
            returningAppGeometryObserved = false
        }
        syncAllControllers()
        context.sendBroadcast(
            Intent(CoverRuntime.COVER_SHELL_TRANSITION_ACTION).apply {
                setPackage(CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(CoverRuntime.EXTRA_COVER_SHELL_TRANSITION_ACTIVE, active)
                putExtra(CoverRuntime.EXTRA_COVER_SHELL_TRANSITION_REASON, reason)
            }
        )
    }

    private fun isCoverHomeShellTransition(info: Any): Boolean {
        val changes = runCatching {
            XposedHelpers.callMethod(info, "getChanges") as? List<*>
        }.getOrNull() ?: return false
        return changes.any { change ->
            change ?: return@any false
            val mode = runCatching {
                (XposedHelpers.callMethod(change, "getMode") as? Number)?.toInt()
            }.getOrNull() ?: return@any false
            if (mode != 1 && mode != 3) return@any false
            val taskInfo = runCatching {
                XposedHelpers.callMethod(change, "getTaskInfo")
            }.getOrNull() ?: return@any false
            val displayId = runCatching {
                XposedHelpers.getIntField(taskInfo, "displayId")
            }.getOrNull() ?: return@any false
            if (!CoverRuntime.isCoverDisplay(displayId)) return@any false
            val topActivity = field(taskInfo, "topActivity")
            val className = topActivity?.let { component ->
                runCatching {
                    XposedHelpers.callMethod(component, "getClassName")?.toString()
                }.getOrNull()
            }
            val activityType = runCatching {
                XposedHelpers.getIntField(taskInfo, "topActivityType")
            }.getOrNull()
            activityType == 2 || className == SECONDARY_LAUNCHER_CLASS
        }
    }

    private fun shouldOverrideCoverSystemBarAppearance(): Boolean =
        transitionAppearancePhase != TransitionAppearancePhase.IDLE &&
            CoverRuntime.isCoverUiSessionEligible()

    private fun releaseAppearanceOverrideOnResolvedNativeOwner(args: Array<Any?>): Boolean {
        if (transitionAppearancePhase != TransitionAppearancePhase.AWAITING_NATIVE_DARK_OWNER) {
            return false
        }
        val owner = args.getOrNull(6)?.toString() ?: return false
        val trustedDarkOwner = (
            owner == CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE ||
                owner == CoverRuntime.SYSTEM_UI_PACKAGE
            ) && !hasLightStatusBarAppearance(args)
        val restoredAppOwner = owner != CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE &&
            owner != CoverRuntime.SYSTEM_UI_PACKAGE &&
            owner == coverTopPackage()
        if (!trustedDarkOwner && !restoredAppOwner) return false

        transitionAppearancePhase = TransitionAppearancePhase.IDLE
        systemBarOverrideLogged = false
        CoverRuntime.log(
            SCOPE,
            "display-1 transition appearance ownership released to resolved native owner " +
                "owner=$owner appearance=${args.getOrNull(1)} " +
                "destination=${if (trustedDarkOwner) "dark-system-surface" else "restored-app"}"
        )
        return true
    }

    private fun coverTopPackage(): String? {
        val context = controllers.toList().firstNotNullOfOrNull { controller ->
            field(controller, "mContext") as? Context
        } ?: return null
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            activityManager.getRunningTasks(32).firstOrNull { task ->
                CoverRuntime.isCoverDisplay(XposedHelpers.getIntField(task, "displayId"))
            }?.topActivity?.packageName
        }.getOrNull()
    }

    private fun hasLightStatusBarAppearance(args: Array<Any?>): Boolean {
        val appearance = (args.getOrNull(1) as? Number)?.toInt() ?: 0
        if ((appearance and APPEARANCE_LIGHT_STATUS_BARS) != 0) return true
        val regions = args.getOrNull(2) as? Array<*> ?: return false
        return regions.any { region ->
            region != null && runCatching {
                val regionAppearance =
                    (XposedHelpers.callMethod(region, "getAppearance") as? Number)?.toInt() ?: 0
                (regionAppearance and APPEARANCE_LIGHT_STATUS_BARS) != 0
            }.getOrDefault(false)
        }
    }

    private fun applyCoverSystemBarAppearance(args: Array<Any?>) {
        val nativeAppearance = (args.getOrNull(1) as? Number)?.toInt() ?: return
        val nativeOwner = args.getOrNull(6)?.toString()
        args[1] = nativeAppearance and APPEARANCE_LIGHT_STATUS_BARS.inv()
        args[2] = withoutLightStatusBarRegions(args.getOrNull(2))
        args[6] = CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE
        if (!systemBarOverrideLogged) {
            systemBarOverrideLogged = true
            CoverRuntime.log(
                SCOPE,
                "display-1 transition released stale light-status appearance " +
                    "phase=$transitionAppearancePhase owner=$nativeOwner " +
                    "native=$nativeAppearance effective=${args[1]}"
            )
        }
    }

    private fun withoutLightStatusBarRegions(value: Any?): Any? {
        val regions = value as? Array<*> ?: return value
        if (regions.isEmpty()) return value
        val result = regions.copyOf()
        regions.forEachIndexed { index, region ->
            region ?: return@forEachIndexed
            val appearance = runCatching {
                (XposedHelpers.callMethod(region, "getAppearance") as? Number)?.toInt()
            }.getOrNull() ?: return@forEachIndexed
            if ((appearance and APPEARANCE_LIGHT_STATUS_BARS) == 0) return@forEachIndexed
            val bounds = runCatching {
                XposedHelpers.callMethod(region, "getBounds")
            }.getOrNull() ?: return@forEachIndexed
            java.lang.reflect.Array.set(
                result,
                index,
                XposedHelpers.newInstance(
                    region.javaClass,
                    appearance and APPEARANCE_LIGHT_STATUS_BARS.inv(),
                    bounds
                )
            )
        }
        return result
    }

    private fun redispatchCoverSystemBarAppearance() {
        val commandQueue = coverSystemBarCommandQueue ?: return
        val snapshot = lastCoverSystemBarAttributes?.copyOf() ?: return
        val depth = systemBarRedispatchDepth.get() ?: 0
        systemBarRedispatchDepth.set(depth + 1)
        try {
            XposedHelpers.callMethod(
                commandQueue,
                "onSystemBarAttributesChanged",
                *snapshot
            )
        } finally {
            if (depth == 0) {
                systemBarRedispatchDepth.remove()
            } else {
                systemBarRedispatchDepth.set(depth)
            }
        }
    }

    private fun installCoverBlurTracking(classLoader: ClassLoader) {
        val blurControllerClass = XposedHelpers.findClassIfExists(
            COVER_BLUR_CONTROLLER_CLASS,
            classLoader
        ) ?: return unavailable("cover blur tracking", "SecCoverBlurController missing")

        runCatching {
            XposedBridge.hookAllConstructors(blurControllerClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val root = field(param.thisObject, "mRootView") as? View ?: return
                    if (CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(root.context))) {
                        blurControllers[root] = param.thisObject
                    }
                }
            })
            XposedBridge.hookAllMethods(
                blurControllerClass,
                "applyBlur",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val root = field(param.thisObject, "mRootView") as? View ?: return
                        if (!transparentCollapsedPanels.contains(root)) return
                        clearNativeBlur(root)
                        param.result = null
                    }
                }
            )
        }.onFailure { unavailable("cover blur tracking", it.message) }
    }

    private fun installTaskStackSync(classLoader: ClassLoader) {
        val listenerImplClass = XposedHelpers.findClassIfExists(
            TASK_STACK_LISTENER_IMPL_CLASS,
            classLoader
        ) ?: return unavailable("task stack status bar sync", "listener impl missing")

        runCatching {
            XposedBridge.hookAllMethods(
                listenerImplClass,
                "handleMessage",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        syncAllControllers()
                    }
                }
            )
            CoverRuntime.log(SCOPE, "display-1 native status bar task-stack gate installed")
        }.onFailure { unavailable("task stack status bar sync", it.message) }
    }

    private fun registerWorkspaceReceiver(context: Context) {
        synchronized(this) {
            if (workspaceReceiverRegistered) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (!CoverRuntime.isTrustedCoverSender(receiverContext, this)) {
                        CoverRuntime.log(SCOPE, "ignored untrusted cover status broadcast")
                        return
                    }
                    when (intent?.action) {
                        CoverRuntime.COVER_RECENTS_TRANSITION_ACTION -> {
                            val wasActive = recentsTransitionActive
                            recentsTransitionActive = intent.getBooleanExtra(
                                CoverRuntime.EXTRA_COVER_RECENTS_TRANSITION_ACTIVE,
                                false
                            )
                            val reason = intent.getStringExtra(
                                CoverRuntime.EXTRA_COVER_RECENTS_TRANSITION_REASON
                            )
                            transitionAppearancePhase = when {
                                recentsTransitionActive -> TransitionAppearancePhase.ACTIVE
                                wasActive && reason == "animation-close" ->
                                    TransitionAppearancePhase.AWAITING_NATIVE_DARK_OWNER
                                else -> TransitionAppearancePhase.IDLE
                            }
                            CoverRuntime.log(
                                SCOPE,
                                "accepted display-1 Recents transition " +
                                    "active=$recentsTransitionActive reason=$reason " +
                                    "appearancePhase=$transitionAppearancePhase"
                            )
                            if (wasActive != recentsTransitionActive) {
                                systemBarOverrideLogged = false
                                if (
                                    recentsTransitionActive ||
                                    transitionAppearancePhase == TransitionAppearancePhase.IDLE
                                ) {
                                    redispatchCoverSystemBarAppearance()
                                }
                            }
                            syncAllControllers()
                            return
                        }
                        CoverRuntime.COVER_APP_TRANSITION_GEOMETRY_ACTION -> {
                            returningAppTransitionActive = intent.getBooleanExtra(
                                CoverRuntime.EXTRA_COVER_APP_TRANSITION_ACTIVE,
                                false
                            )
                            returningAppTop = intent.getFloatExtra(
                                CoverRuntime.EXTRA_COVER_APP_TRANSITION_TOP,
                                Float.POSITIVE_INFINITY
                            )
                            returningAppGeometryObserved = returningAppTransitionActive
                            val reason = intent.getStringExtra(
                                CoverRuntime.EXTRA_COVER_APP_TRANSITION_REASON
                            )
                            CoverRuntime.log(
                                SCOPE,
                                "accepted display-1 return App geometry " +
                                    "active=$returningAppTransitionActive top=$returningAppTop " +
                                    "reason=$reason"
                            )
                            syncAllControllers()
                            return
                        }
                        CoverDisplayConfig.ACTION_FULL_DEX_CHANGED -> {
                            fullDexQuickPanelBlocked = fullDexBlocksCoverQuickPanel(
                                CoverDisplayConfig.readFullDex(context),
                                CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))
                            )
                            if (fullDexQuickPanelBlocked) {
                                controllers.toList().forEach { controller ->
                                    collapseNativePanelIfNeeded(
                                        controller,
                                        booleanField(controller, "mPanelExpanded") == true
                                    )
                                }
                            }
                            syncAllControllers()
                            return
                        }
                        CoverRuntime.COVER_WORKSPACE_STATE_ACTION -> Unit
                        else -> return
                    }
                    val epoch = intent.getLongExtra(
                        CoverRuntime.EXTRA_COVER_WORKSPACE_EPOCH,
                        Long.MIN_VALUE
                    )
                    val sequence = intent.getLongExtra(
                        CoverRuntime.EXTRA_COVER_WORKSPACE_SEQUENCE,
                        Long.MIN_VALUE
                    )
                    if (
                        epoch < launcherWorkspaceEpoch ||
                        (epoch == launcherWorkspaceEpoch && sequence <= launcherWorkspaceSequence)
                    ) {
                        CoverRuntime.log(
                            SCOPE,
                            "ignored stale display-1 workspace epoch=$epoch sequence=$sequence " +
                                "accepted=$launcherWorkspaceEpoch/$launcherWorkspaceSequence"
                        )
                        return
                    }
                    launcherWorkspaceEpoch = epoch
                    launcherWorkspaceSequence = sequence
                    launcherWorkspaceVisible = intent.getBooleanExtra(
                        CoverRuntime.EXTRA_COVER_WORKSPACE_VISIBLE,
                        false
                    )
                    val screen = intent.getStringExtra(
                        CoverRuntime.EXTRA_COVER_WORKSPACE_SCREEN
                    )
                    val state = intent.getStringExtra(
                        CoverRuntime.EXTRA_COVER_WORKSPACE_STATE
                    )?.substringAfterLast('.')
                    val transition = intent.getBooleanExtra(
                        CoverRuntime.EXTRA_COVER_WORKSPACE_TRANSITION,
                        false
                    )
                    val folder = intent.getBooleanExtra(
                        CoverRuntime.EXTRA_COVER_WORKSPACE_FOLDER,
                        false
                    )
                    launcherWorkspaceHomeSurface =
                        screen == "HOME" &&
                            state?.let { it.startsWith("HomeScreen") && it.endsWith("Normal") } == true &&
                            !transition &&
                            !folder
                    val reason = intent.getStringExtra(
                        CoverRuntime.EXTRA_COVER_WORKSPACE_REASON
                    )
                    if (
                        reason == "shell-home-transition-finished" &&
                        launcherWorkspaceVisible &&
                        launcherWorkspaceHomeSurface
                    ) {
                        externalLaunchSuppressedUntil = 0L
                    }
                    CoverRuntime.log(
                        SCOPE,
                        "accepted display-1 workspace visible=$launcherWorkspaceVisible " +
                            "screen=$screen state=$state transition=$transition folder=$folder " +
                            "reason=$reason epoch=$epoch sequence=$sequence"
                    )
                    syncAllControllers()
                }
            }
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(CoverRuntime.COVER_WORKSPACE_STATE_ACTION)
                    addAction(CoverRuntime.COVER_RECENTS_TRANSITION_ACTION)
                    addAction(CoverRuntime.COVER_APP_TRANSITION_GEOMETRY_ACTION)
                    addAction(CoverDisplayConfig.ACTION_FULL_DEX_CHANGED)
                },
                CoverRuntime.COVER_BROADCAST_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED
            )
            workspaceReceiverRegistered = true
            context.sendBroadcast(
                Intent(CoverRuntime.COVER_WORKSPACE_STATE_REQUEST_ACTION).apply {
                    setPackage(CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                }
            )
        }
    }

    private fun registerDisplayRotationSync(context: Context) {
        val applicationContext = context.applicationContext
        synchronized(displayRotationListeners) {
            if (displayRotationListeners.containsKey(applicationContext)) return
            val displayManager = applicationContext.getSystemService(DisplayManager::class.java)
                ?: return unavailable("display rotation sync", "DisplayManager missing")
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit

                override fun onDisplayChanged(displayId: Int) {
                    if (!CoverRuntime.isCoverDisplay(displayId)) return
                    retainTopSurfaceDuringRotation()
                    val display = runCatching {
                        displayManager.getDisplay(displayId)
                    }.getOrNull()
                    val displayConfiguration = display?.let { currentDisplay ->
                        runCatching {
                            Configuration(
                                applicationContext
                                    .createDisplayContext(currentDisplay)
                                    .resources
                                    .configuration
                            )
                        }.getOrNull()
                    }
                    syncAllControllers()
                    controllers.toList().forEach { controller ->
                        val root = field(controller, "mSubScreenQsWindowView") as? View
                            ?: return@forEach
                        root.post {
                            if (displayConfiguration != null) {
                                runCatching {
                                    root.dispatchConfigurationChanged(
                                        Configuration(displayConfiguration)
                                    )
                                }.onFailure {
                                    CoverRuntime.log(
                                        SCOPE,
                                        "display-1 QS configuration dispatch failed: ${it.message}"
                                    )
                                }
                            }
                            root.requestApplyInsets()
                            requestLayoutAfterTraversal(root)
                            root.invalidate()
                            root.postOnAnimation { syncAfterLayout(controller) }
                            scheduleRotationRecovery(root, controller)
                        }
                    }
                    if (!displayRotationSyncLogged) {
                        displayRotationSyncLogged = true
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 status bar rotation listener registered"
                        )
                    }
                }
            }
            runCatching {
                displayManager.registerDisplayListener(listener, null)
                displayRotationListeners[applicationContext] = listener
            }.onFailure {
                unavailable("display rotation sync", it.message)
            }
        }
    }

    internal fun isStableHomePresentation(context: Context): Boolean =
        CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context)) &&
            reduceHeaderPresentation(
                coverEligible = CoverRuntime.isCoverUiSessionEligible(),
                hideCoverHomeStatusBar = isCoverHomeStatusBarHidden(context) ||
                    CoverDisplayConfig.readFullDex(context),
                topSurface = lastCoverTopSurface,
                panelExpanded = false,
                fullShadeOnCover = false
            ) == HeaderPresentationState.HOME

    internal fun isCoverHomeSurface(context: Context): Boolean =
        CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context)) &&
            CoverRuntime.isCoverUiSessionEligible() &&
            !NativeCoverKeyguardHooks.isPriorityActive() &&
            !recentsTransitionActive &&
            !coverHomeShellTransitionActive &&
            lastCoverTopSurface == CoverTopSurface.SECONDARY_LAUNCHER

    internal fun syncPresentation() {
        syncAllControllers()
    }

    internal fun suppressHomeDuringExternalLaunch(timeoutMillis: Long = 2_000L) {
        externalLaunchSuppressedUntil = maxOf(
            externalLaunchSuppressedUntil,
            SystemClock.elapsedRealtime() + timeoutMillis
        )
        syncAllControllers()
        controllers.toList().forEach { controller ->
            val root = field(controller, "mSubScreenQsWindowView") as? View ?: return@forEach
            root.postDelayed(::syncAllControllers, timeoutMillis)
        }
    }

    internal fun requestAuthoritativeWorkspaceState(context: Context) {
        context.sendBroadcast(
            Intent(CoverRuntime.COVER_WORKSPACE_STATE_REQUEST_ACTION).apply {
                setPackage(CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    private fun syncAllControllers() {
        controllers.toList().forEach { controller ->
            val root = field(controller, "mSubScreenQsWindowView") as? View ?: return@forEach
            if (XposedHelpers.getAdditionalInstanceField(root, BATCH_SYNC_POSTED_MARK) == true) {
                return@forEach
            }
            XposedHelpers.setAdditionalInstanceField(root, BATCH_SYNC_POSTED_MARK, true)
            root.postOnAnimation {
                XposedHelpers.removeAdditionalInstanceField(root, BATCH_SYNC_POSTED_MARK)
                if (root.isAttachedToWindow) syncAfterLayout(controller)
            }
        }
    }

    private fun installPanelExpansionSync(controllerClass: Class<*>) {
        runCatching {
            XposedBridge.hookAllMethods(
                controllerClass,
                "updatePanelExpansion",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val controller = param.thisObject ?: return
                        val expanded = booleanField(controller, "mPanelExpanded") ?: return
                        val previous = panelExpansionStates.put(controller, expanded)
                        if (previous == expanded) return
                        if (previous == true && !expanded) {
                            val context = field(controller, "mContext") as? Context
                            if (context != null) requestAuthoritativeWorkspaceState(context)
                        }
                        syncAfterLayout(controller)
                    }
                }
            )
        }.onFailure { unavailable("panel expansion sync", it.message) }
    }

    private fun installInitializationSync(classLoader: ClassLoader) {
        val pluginClass = XposedHelpers.findClassIfExists(PLUGIN_AOD_MANAGER_CLASS, classLoader)
            ?: return unavailable("initialization sync", "PluginAODManager missing")

        runCatching {
            XposedBridge.hookAllConstructors(pluginClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.args.firstOrNull { it?.javaClass?.name == CONTROLLER_CLASS }
                        ?.let(::syncAfterLayout)
                }
            })
        }.onFailure { unavailable("initialization sync", it.message) }
    }

    private fun installFoldLifecycleSync(
        classLoader: ClassLoader,
        controllerClass: Class<*>
    ) {
        val listenerClass = XposedHelpers.findClassIfExists(FOLD_LISTENER_CLASS, classLoader)
            ?: return unavailable("fold lifecycle sync", "fold listener missing")

        runCatching {
            XposedBridge.hookAllMethods(
                listenerClass,
                "onFolderStateChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val controller = surroundingController(param.thisObject, controllerClass)
                            ?: return
                        val context = field(controller, "mContext") as? Context
                        if (context != null) requestAuthoritativeWorkspaceState(context)
                        syncAfterLayout(controller)
                    }
                }
            )
            CoverRuntime.log(SCOPE, "native display-1 cover status bar lifecycle installed")
        }.onFailure { unavailable("fold lifecycle sync", it.message) }
    }

    private fun syncAfterLayout(controller: Any) {
        val root = field(controller, "mSubScreenQsWindowView") as? View ?: return
        controllersByRoot[root] = controller
        installRootGeometrySync(root, controller)
        val onViewThread = Looper.myLooper() == root.handler?.looper
        if (root.isLaidOut && onViewThread && !isInLayoutHierarchy(root)) {
            sync(controller, root)
        } else {
            if (XposedHelpers.getAdditionalInstanceField(root, STATUS_SYNC_POSTED_MARK) == true) {
                return
            }
            XposedHelpers.setAdditionalInstanceField(root, STATUS_SYNC_POSTED_MARK, true)
            root.postOnAnimation {
                XposedHelpers.removeAdditionalInstanceField(root, STATUS_SYNC_POSTED_MARK)
                if (root.isAttachedToWindow) syncAfterLayout(controller)
            }
        }
    }

    private fun retainTopSurfaceDuringRotation() {
        val surface = topSurfaceSnapshot.surface.takeUnless {
            it == CoverTopSurface.OTHER || it == CoverTopSurface.TRANSITION
        } ?: lastCoverTopSurface.takeUnless {
            it == CoverTopSurface.OTHER || it == CoverTopSurface.TRANSITION
        } ?: return
        val now = SystemClock.elapsedRealtime()
        topSurfaceSnapshot = TopSurfaceSnapshot(surface, now)
        topSurfaceRotationHoldUntil = now + ROTATION_SURFACE_HOLD_MS
    }

    private fun scheduleRotationRecovery(root: View, controller: Any) {
        val generation = (
            XposedHelpers.getAdditionalInstanceField(root, ROTATION_RECOVERY_GENERATION_MARK)
                as? Number
            )?.toLong()?.plus(1L) ?: 1L
        XposedHelpers.setAdditionalInstanceField(
            root,
            ROTATION_RECOVERY_GENERATION_MARK,
            generation
        )
        root.postDelayed({
            val current = (
                XposedHelpers.getAdditionalInstanceField(
                    root,
                    ROTATION_RECOVERY_GENERATION_MARK
                ) as? Number
                )?.toLong()
            if (current != generation || !root.isAttachedToWindow) return@postDelayed
            XposedHelpers.removeAdditionalInstanceField(root, ROTATION_RECOVERY_GENERATION_MARK)
            root.requestApplyInsets()
            syncAfterLayout(controller)
            redispatchCoverSystemBarAppearance()
            CoverRuntime.log(SCOPE, "display-1 rotation settle status-bar recovery")
        }, ROTATION_SETTLE_RECOVERY_DELAY_MS)
    }

    private fun requestLayoutAfterTraversal(root: View) {
        if (XposedHelpers.getAdditionalInstanceField(root, ROTATION_LAYOUT_POSTED_MARK) == true) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(root, ROTATION_LAYOUT_POSTED_MARK, true)
        fun apply() {
            if (!root.isAttachedToWindow) {
                XposedHelpers.removeAdditionalInstanceField(root, ROTATION_LAYOUT_POSTED_MARK)
                return
            }
            if (isInLayoutHierarchy(root)) {
                root.postOnAnimation(::apply)
                return
            }
            XposedHelpers.removeAdditionalInstanceField(root, ROTATION_LAYOUT_POSTED_MARK)
            root.requestLayout()
        }
        root.postOnAnimation(::apply)
    }

    private fun installRootGeometrySync(root: View, controller: Any) {
        if (rootGeometryLayoutListeners.containsKey(root)) return
        val rootReference = WeakReference(root)
        val controllerReference = WeakReference(controller)
        val listener = View.OnLayoutChangeListener {
                _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val sizeChanged = right - left != oldRight - oldLeft ||
                bottom - top != oldBottom - oldTop
            if (sizeChanged) {
                val attachedRoot = rootReference.get() ?: return@OnLayoutChangeListener
                val attachedController = controllerReference.get()
                    ?: return@OnLayoutChangeListener
                attachedRoot.requestApplyInsets()
                attachedRoot.postOnAnimation { syncAfterLayout(attachedController) }
            }
        }
        rootGeometryLayoutListeners[root] = listener
        root.addOnLayoutChangeListener(listener)
    }

    private fun isInLayoutHierarchy(view: View): Boolean {
        var current: View? = view
        repeat(32) {
            if (current?.isInLayout == true) return true
            current = current?.parent as? View
        }
        return false
    }

    private fun statusScaleKey(root: View, compact: Boolean): StatusScaleKey {
        val resources = root.resources
        val metrics = resources.displayMetrics
        val configuration = resources.configuration
        return StatusScaleKey(
            compact,
            configuration.densityDpi,
            metrics.widthPixels,
            metrics.heightPixels,
            configuration.fontScale.toBits()
        )
    }

    private fun applyCoverStatusIconScale(root: View, compact: Boolean) {
        val metrics = root.resources.displayMetrics
        val density = metrics.density
        val scaledDensity = metrics.scaledDensity
        if (compact) {
            val iconCandidates = mutableListOf<View>()
            collectStatusIconViews(root, iconCandidates)
            iconCandidates.forEach { view ->
                val params = view.layoutParams ?: return@forEach
                val baseline = synchronized(statusIconSizeBaselines) {
                    statusIconSizeBaselines.getOrPut(view) {
                        StatusIconBaseline(
                            params.width.takeIf { it > 0 }?.div(density),
                            params.height.takeIf { it > 0 }?.div(density)
                        )
                    }
                }
                val targetWidth = baseline.widthDp?.let {
                    coverStatusDimensionPx(it, density, COVER_STATUS_ICON_SIZE_RATIO)
                }
                val targetHeight = baseline.heightDp?.let {
                    coverStatusDimensionPx(it, density, COVER_STATUS_ICON_SIZE_RATIO)
                }
                var changed = false
                if (targetWidth != null && params.width != targetWidth) {
                    params.width = targetWidth
                    changed = true
                }
                if (targetHeight != null && params.height != targetHeight) {
                    params.height = targetHeight
                    changed = true
                }
                if (changed) view.layoutParams = params
            }

            val textCandidates = mutableListOf<TextView>()
            collectStatusTextViews(root, textCandidates)
            textCandidates.forEach { view ->
                val baselineSp = synchronized(statusTextSizeBaselines) {
                    statusTextSizeBaselines.getOrPut(view) { view.textSize / scaledDensity }
                }
                val target = coverStatusTextSizePx(
                    baselineSp,
                    scaledDensity,
                    COVER_STATUS_TEXT_SIZE_RATIO
                )
                if (kotlin.math.abs(view.textSize - target) > 0.5f) {
                    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
                }
            }
            return
        }

        // Keep the captured native values until they have been applied. Removing
        // the entries before restoring them leaves the scaled dimensions behind
        // when the cover session ends.
        val iconBaselines = synchronized(statusIconSizeBaselines) {
            statusIconSizeBaselines.toMap()
        }
        iconBaselines.forEach { (view, baseline) ->
            val params = view.layoutParams ?: return@forEach
            val targetWidth = baseline.widthDp?.let {
                coverStatusDimensionPx(it, density, 1f)
            }
            val targetHeight = baseline.heightDp?.let {
                coverStatusDimensionPx(it, density, 1f)
            }
            var changed = false
            if (targetWidth != null && params.width != targetWidth) {
                params.width = targetWidth
                changed = true
            }
            if (targetHeight != null && params.height != targetHeight) {
                params.height = targetHeight
                changed = true
            }
            if (changed) view.layoutParams = params
        }
        synchronized(statusIconSizeBaselines) {
            statusIconSizeBaselines.clear()
        }

        val textBaselines = synchronized(statusTextSizeBaselines) {
            statusTextSizeBaselines.toMap()
        }
        textBaselines.forEach { (view, baselineSp) ->
            val target = coverStatusTextSizePx(baselineSp, scaledDensity, 1f)
            if (kotlin.math.abs(view.textSize - target) > 0.5f) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, target)
            }
        }
        synchronized(statusTextSizeBaselines) {
            statusTextSizeBaselines.clear()
        }
    }

    private fun collectStatusTextViews(view: View, result: MutableList<TextView>) {
        val resourceName = view.id.takeIf { it != View.NO_ID }?.let {
            runCatching { view.resources.getResourceEntryName(it) }.getOrNull()
        }.orEmpty()
        if (
            view is TextView &&
            (view.javaClass.simpleName.contains("Clock") ||
                resourceName.contains("clock") ||
                resourceName.contains("time"))
        ) {
            result += view
        }
        (view as? ViewGroup)?.let { group ->
            for (index in 0 until group.childCount) {
                collectStatusTextViews(group.getChildAt(index), result)
            }
        }
    }

    private fun collectStatusIconViews(view: View, result: MutableList<View>) {
        val name = view.javaClass.simpleName
        val resourceName = view.id.takeIf { it != View.NO_ID }?.let {
            runCatching { view.resources.getResourceEntryName(it) }.getOrNull()
        }.orEmpty()
        if (
            name.contains("StatusBarIconView") ||
            name.contains("BatteryMeterView") ||
            name.contains("SignalClusterView") ||
            resourceName.contains("status_icon") ||
            resourceName.contains("battery") ||
            resourceName.contains("signal")
        ) {
            result += view
            return
        }
        (view as? ViewGroup)?.let { group ->
            for (index in 0 until group.childCount) {
                collectStatusIconViews(group.getChildAt(index), result)
            }
        }
    }

    private fun sync(controller: Any, root: View) {
        val context = field(controller, "mContext") as? Context ?: return
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return
        val coverEligible = CoverRuntime.isCoverUiSessionEligible()
        val scaleKey = statusScaleKey(root, coverEligible)
        if (statusScaleStates.put(root, scaleKey) != scaleKey) {
            applyCoverStatusIconScale(root, coverEligible)
        }

        val layoutParams = field(controller, "mLp") as? WindowManager.LayoutParams ?: return
        val windowManager = field(controller, "mWindowManager") as? WindowManager ?: return
        val baseline = synchronized(baselines) {
            baselines.getOrPut(controller) {
                WindowBaseline(
                    layoutParams.height,
                    layoutParams.screenOrientation,
                    layoutParams.flags,
                    privateFlagsOf(layoutParams),
                    layoutParams.format,
                    layoutParams.layoutInDisplayCutoutMode
                )
            }
        }
        val panelExpanded = booleanField(controller, "mPanelExpanded") == true
        val expandedFraction = (field(controller, "mExpandedFraction") as? Number)
            ?.toFloat()
            ?: 0f
        val panelActive = panelExpanded || expandedFraction > 0.001f
        panelExpansionStates[controller] = panelExpanded
        if (!coverEligible) {
            recentsTransitionActive = false
            coverHomeShellTransitionActive = false
            returningAppTransitionActive = false
            returningAppTop = Float.POSITIVE_INFINITY
            returningAppGeometryObserved = false
            transitionAppearancePhase = TransitionAppearancePhase.IDLE
            launcherWorkspaceHomeSurface = false
        }
        val topSurface = coverTopSurface(context)
        val previousTopSurface = lastCoverTopSurface
        if (
            topSurface == CoverTopSurface.SECONDARY_LAUNCHER &&
            previousTopSurface != CoverTopSurface.SECONDARY_LAUNCHER &&
            launcherWorkspaceVisible &&
            !recentsTransitionActive &&
            !panelExpanded
        ) {
            returningAppTransitionActive = true
            returningAppTop = 0.0f
            returningAppGeometryObserved = false
            CoverRuntime.log(
                SCOPE,
                "display-1 return App pending from top-surface edge " +
                    "$previousTopSurface->$topSurface"
            )
        }
        lastCoverTopSurface = topSurface
        val fullShadeOnCover = false
        val nativeHeader = (field(controller, "mQSPanel") as? View)?.let(::findNativeHeader)
        val headerBoundary = nativeHeader?.let(::nativeHeaderHeight)?.coerceAtLeast(0) ?: 0
        val returningAppHeaderClear = !returningAppTransitionActive ||
            (headerBoundary > 0 && returningAppTop >= headerBoundary)
        val presentation = reduceHeaderPresentation(
            coverEligible = coverEligible,
            hideCoverHomeStatusBar = isCoverHomeStatusBarHidden(context) ||
                CoverDisplayConfig.readFullDex(context),
            topSurface = topSurface,
            panelExpanded = panelActive,
            fullShadeOnCover = fullShadeOnCover,
            returningAppHeaderClear = returningAppHeaderClear
        )
        publishQuickPanelRecentsBlock(
            context,
            coverEligible &&
                (panelActive || presentation == HeaderPresentationState.SOURCE_PANEL)
        )
        if (
            presentation == HeaderPresentationState.DISABLED ||
            presentation == HeaderPresentationState.KEYGUARD ||
            presentation == HeaderPresentationState.HOME ||
            presentation == HeaderPresentationState.SOURCE_PANEL
        ) {
            collapseRequestedControllers.remove(controller)
        }

        val renderKey = HeaderRenderKey(presentation, System.identityHashCode(root))
        if (renderedPresentations.put(controller, renderKey) != renderKey) {
            renderPresentation(
                presentation,
                controller,
                root,
                context,
                windowManager,
                layoutParams,
                baseline,
                panelExpanded
            )
        } else {
            when (presentation) {
                HeaderPresentationState.HOME -> reconcileNativeHomeHeader(
                    controller,
                    root,
                    context,
                    windowManager,
                    layoutParams
                )
                HeaderPresentationState.HIDDEN -> reconcileHiddenTransitionSurface(
                    controller,
                    root
                )
                HeaderPresentationState.SOURCE_PANEL -> {
                    (field(controller, "mQSPanel") as? View)
                        ?.let(::findNativeHeader)
                        ?.let { header ->
                            val geometry = applyCollapsedHeaderGeometry(header, root, context)
                            ensureExpandedHeaderVisible(root, header, geometry)
                        }
                }
                else -> Unit
            }
        }
        logPresentationState(
            controller,
            root,
            layoutParams,
            presentation,
            panelExpanded,
            fullShadeOnCover,
            topSurface
        )
    }

    private fun publishQuickPanelRecentsBlock(context: Context, blocked: Boolean) {
        if (lastPublishedQuickPanelRecentsBlock == blocked) return
        lastPublishedQuickPanelRecentsBlock = blocked
        context.sendBroadcast(
            Intent(CoverRuntime.COVER_QUICK_PANEL_STATE_ACTION).apply {
                setPackage(CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(CoverRuntime.EXTRA_COVER_QUICK_PANEL_BLOCKS_RECENTS, blocked)
            }
        )
        CoverRuntime.log(
            SCOPE,
            "display-1 QuickPanel Recents input blocked=$blocked"
        )
    }

    private fun reduceHeaderPresentation(
        coverEligible: Boolean,
        hideCoverHomeStatusBar: Boolean,
        topSurface: CoverTopSurface,
        panelExpanded: Boolean,
        fullShadeOnCover: Boolean,
        returningAppHeaderClear: Boolean = !returningAppTransitionActive
    ): HeaderPresentationState {
        val input = HeaderPresentationInput(
            coverEligible = coverEligible,
            hideCoverHomeStatusBar = hideCoverHomeStatusBar,
            keyguardPriorityActive = NativeCoverKeyguardHooks.isPriorityActive(),
            launcherWorkspaceVisible = launcherWorkspaceVisible,
            recentsTransitionActive = recentsTransitionActive,
            homeShellTransitionActive = coverHomeShellTransitionActive,
            returningAppTransitionActive = returningAppTransitionActive,
            returningAppHeaderClear = returningAppHeaderClear,
            topSurface = topSurface,
            panelExpanded = panelExpanded,
            fullShadeOnCover = fullShadeOnCover
        )
        val homeGeometrySafe =
            !input.returningAppTransitionActive || input.returningAppHeaderClear
        val stableHomeSurface = when (input.topSurface) {
            CoverTopSurface.MAIN_LAUNCHER -> true
            CoverTopSurface.SECONDARY_LAUNCHER ->
                input.launcherWorkspaceVisible && launcherWorkspaceHomeSurface
            else -> false
        }
        val stableHome =
            homeGeometrySafe &&
                stableHomeSurface &&
                !input.recentsTransitionActive &&
                !input.homeShellTransitionActive &&
                SystemClock.elapsedRealtime() >= externalLaunchSuppressedUntil
        return when {
            !input.coverEligible -> HeaderPresentationState.DISABLED
            input.keyguardPriorityActive -> HeaderPresentationState.KEYGUARD
            input.fullShadeOnCover -> HeaderPresentationState.FULL_SHADE
            input.panelExpanded -> HeaderPresentationState.SOURCE_PANEL
            !stableHome || input.hideCoverHomeStatusBar -> HeaderPresentationState.HIDDEN
            else -> HeaderPresentationState.HOME
        }
    }

    private fun renderPresentation(
        presentation: HeaderPresentationState,
        controller: Any,
        root: View,
        context: Context,
        windowManager: WindowManager,
        layoutParams: WindowManager.LayoutParams,
        baseline: WindowBaseline,
        panelExpanded: Boolean
    ) {
        when (presentation) {
            HeaderPresentationState.DISABLED -> restoreCollapsedDefault(
                controller,
                root,
                windowManager,
                layoutParams,
                baseline,
                panelExpanded,
                keepBlurSuppressed = false
            )
            HeaderPresentationState.KEYGUARD -> restoreCollapsedDefault(
                controller,
                root,
                windowManager,
                layoutParams,
                baseline,
                panelExpanded = false,
                keepBlurSuppressed = false
            )
            HeaderPresentationState.FULL_SHADE -> restoreCollapsedDefault(
                controller,
                root,
                windowManager,
                layoutParams,
                baseline,
                panelExpanded = false,
                keepBlurSuppressed = true
            )
            HeaderPresentationState.SOURCE_PANEL -> restoreExpandedPanel(
                controller,
                root,
                windowManager,
                layoutParams,
                baseline
            )
            HeaderPresentationState.HIDDEN -> {
                collapseNativePanelIfNeeded(controller, panelExpanded)
                restoreCollapsedDefault(
                    controller,
                    root,
                    windowManager,
                    layoutParams,
                    baseline,
                    panelExpanded = false,
                    keepBlurSuppressed = true,
                    keepFullHeight = true
                )
                reconcileHiddenTransitionSurface(controller, root)
            }
            HeaderPresentationState.HOME -> pinNativeHeader(
                controller,
                root,
                context,
                windowManager,
                layoutParams,
                touchable = true
            )
        }
    }

    private fun logPresentationState(
        controller: Any,
        root: View,
        layoutParams: WindowManager.LayoutParams,
        presentation: HeaderPresentationState,
        panelExpanded: Boolean,
        fullShadeOnCover: Boolean,
        topSurface: CoverTopSurface
    ) {
        val panel = field(controller, "mQSPanel") as? View
        val header = panel?.let(::findNativeHeader)
        val snapshot = buildString {
            append("state=").append(presentation)
            append(" keyguardPriority=").append(
                NativeCoverKeyguardHooks.isPriorityActive()
            )
            append(" workspace=").append(launcherWorkspaceVisible)
            append(" recents=").append(recentsTransitionActive)
            append(" shellHome=").append(coverHomeShellTransitionActive)
            append(" returnApp=").append(returningAppTransitionActive)
            append('@').append(returningAppTop)
            append(" surface=").append(topSurface)
            append(" expanded=").append(panelExpanded)
            append(" fullShade=").append(fullShadeOnCover)
            append(" rootAlpha=").append(root.alpha)
            append(" rootVisibility=").append(root.visibility)
            append(" headerVisibility=").append(header?.visibility)
            append(" backgrounds=")
                .append(root.background != null).append('/')
                .append(panel?.background != null).append('/')
                .append(header?.background != null)
            append(" notTouchable=").append(
                layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
            )
            append(" size=").append(root.width).append('x').append(layoutParams.height)
            append(" format=").append(layoutParams.format)
            append(" rootId=").append(System.identityHashCode(root))
            append(" tokenId=").append(root.windowToken?.let(System::identityHashCode))
        }
        if (presentationSnapshots.put(controller, snapshot) != snapshot) {
            CoverRuntime.log(SCOPE, "display-1 Header presentation $snapshot")
        }
    }

    private fun reconcileNativeHomeHeader(
        controller: Any,
        root: View,
        context: Context,
        windowManager: WindowManager,
        layoutParams: WindowManager.LayoutParams
    ) {
        val panel = field(controller, "mQSPanel") as? View ?: return
        val header = findNativeHeader(panel) ?: return
        val headerGeometry = applyCollapsedHeaderGeometry(header, root, context)
        val headerHeight = nativeHeaderHeight(header)
        if (headerHeight <= 0) return

        transparentCollapsedPanels.add(panel)
        clearNativeSurfaceEffects(root, panel, header)
        suppressExpandedContentOnHome(root)
        val rotateContentToPhysicalTop =
            headerGeometry.rotation == Surface.ROTATION_180
        root.translationY = 0f
        if (rotateContentToPhysicalTop) {
            applyHomeContentTranslation(root, header, headerGeometry.translationY)
        } else {
            resetWindowContentTranslation(controller, root)
            header.translationY = headerGeometry.translationY
        }
        root.alpha = 1f
        root.visibility = View.VISIBLE
        panel.translationX = 0f
        panel.translationY = 0f
        panel.alpha = 1f
        panel.visibility = View.VISIBLE
        header.alpha = 1f
        header.visibility = View.VISIBLE

        installHomeTouchableRegion(root, headerHeight)
        val baseFlags = layoutParams.flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        val targetFlags = baseFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        val trustedOverlayChanged = setTrustedOverlay(layoutParams, trusted = true)
        if (
            layoutParams.height != WindowManager.LayoutParams.MATCH_PARENT ||
            layoutParams.screenOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED ||
            layoutParams.flags != targetFlags ||
            layoutParams.format != PixelFormat.TRANSLUCENT ||
            layoutParams.layoutInDisplayCutoutMode !=
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS ||
            trustedOverlayChanged
        ) {
            layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
            layoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            layoutParams.flags = targetFlags
            layoutParams.format = PixelFormat.TRANSLUCENT
            layoutParams.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            updateAttachedWindow(root, windowManager, layoutParams)
        }
    }

    private fun reconcileHiddenTransitionSurface(controller: Any, root: View) {
        val panel = field(controller, "mQSPanel") as? View ?: return
        val header = findNativeHeader(panel)
        transparentCollapsedPanels.add(panel)
        clearNativeSurfaceEffects(
            *listOfNotNull(root, panel, header).toTypedArray()
        )
        root.translationY = 0f
        resetWindowContentTranslation(controller, root)
        root.alpha = 1f
        root.visibility = View.INVISIBLE
        panel.translationX = 0f
        panel.translationY = 0f
        panel.alpha = 1f
        panel.visibility = View.INVISIBLE
        header?.apply {
            translationY = 0f
            alpha = 1f
            visibility = View.INVISIBLE
        }
    }

    private fun pinNativeHeader(
        controller: Any,
        root: View,
        context: Context,
        windowManager: WindowManager,
        layoutParams: WindowManager.LayoutParams,
        touchable: Boolean
    ) {
        val panel = field(controller, "mQSPanel") as? View ?: return
        val headerId = context.resources.getIdentifier(
            "sub_screen_quick_panel_header",
            "id",
            context.packageName
        )
        if (headerId == 0) return
        val header = panel.findViewById<View>(headerId) ?: return
        val headerGeometry = applyCollapsedHeaderGeometry(header, root, context)
        val headerHeight = nativeHeaderHeight(header)
        if (headerHeight <= 0) return

        transparentCollapsedPanels.add(panel)
        clearNativeSurfaceEffects(root, panel, header)
        suppressExpandedContentOnHome(root)
        val rotateContentToPhysicalTop =
            headerGeometry.rotation == Surface.ROTATION_180
        root.translationY = 0f
        if (rotateContentToPhysicalTop) {
            applyHomeContentTranslation(root, header, headerGeometry.translationY)
        } else {
            resetWindowContentTranslation(controller, root)
            header.translationY = headerGeometry.translationY
        }
        root.visibility = View.VISIBLE
        panel.translationX = 0f
        panel.translationY = 0f
        panel.alpha = 1f
        panel.visibility = View.VISIBLE
        header.alpha = 1f
        header.visibility = View.VISIBLE
        installHomeTouchableRegion(root, headerHeight)
        layoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        layoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        layoutParams.format = PixelFormat.TRANSLUCENT
        layoutParams.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        val baseFlags = layoutParams.flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        layoutParams.flags = if (touchable) {
            baseFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        setTrustedOverlay(layoutParams, trusted = true)
        updateAttachedWindow(root, windowManager, layoutParams)
        root.alpha = 1f

        if (loggedControllers.add(controller)) {
            CoverRuntime.log(
                SCOPE,
                "native display-1 cover status bar pinned height=$headerHeight " +
                    "source=SubScreenQuickPanelHeader"
            )
        }
    }

    private fun restoreExpandedPanel(
        controller: Any,
        root: View,
        windowManager: WindowManager,
        layoutParams: WindowManager.LayoutParams,
        baseline: WindowBaseline
    ) {
        removeHomeTouchableRegion(root)
        root.translationY = 0f
        resetWindowContentTranslation(controller, root)
        restoreExpandedBackdrop(root)
        restoreExpandedContent(root)
        layoutParams.height = baseline.height
        layoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        layoutParams.flags = baseline.flags
        setPrivateFlags(layoutParams, baseline.privateFlags or PRIVATE_FLAG_TRUSTED_OVERLAY)
        layoutParams.format = baseline.format
        layoutParams.layoutInDisplayCutoutMode = baseline.layoutInDisplayCutoutMode
        updateAttachedWindow(root, windowManager, layoutParams)
        restoreNativePanelAppearance(controller, preservePanelAnimationState = true)
        val nativePanel = field(controller, "mQSPanel") as? View
        nativePanel?.let { panel ->
            findNativeHeader(panel)?.let { header ->
                val geometry = applyCollapsedHeaderGeometry(header, root, root.context)
                ensureExpandedHeaderVisible(root, header, geometry)
            }
        }
        val nativePanelAlpha = nativePanel?.alpha
        val nativePanelTranslationY = nativePanel?.translationY
        val expandedFraction = (field(controller, "mExpandedFraction") as? Number)?.toFloat()
        CoverRuntime.log(
            SCOPE,
            "display-1 SOURCE_PANEL preserved native panel " +
                "alpha=$nativePanelAlpha translationY=$nativePanelTranslationY " +
                "fraction=$expandedFraction"
        )
        root.alpha = 1f
        root.visibility = View.VISIBLE
    }

    private fun restoreCollapsedDefault(
        controller: Any,
        root: View,
        windowManager: WindowManager,
        layoutParams: WindowManager.LayoutParams,
        baseline: WindowBaseline,
        panelExpanded: Boolean,
        keepBlurSuppressed: Boolean,
        keepFullHeight: Boolean = false
    ) {
        removeHomeTouchableRegion(root)
        root.translationY = 0f
        resetWindowContentTranslation(controller, root)
        if (!panelExpanded && !keepBlurSuppressed) root.visibility = View.GONE
        if (keepBlurSuppressed) {
            root.alpha = 0f
            val panel = field(controller, "mQSPanel") as? View
            if (panel != null) {
                transparentCollapsedPanels.add(panel)
                val header = findNativeHeader(panel)
                clearNativeSurfaceEffects(
                    *listOfNotNull(root, panel, header).toTypedArray()
                )
                header?.visibility = View.INVISIBLE
                panel.translationX = 0f
                panel.translationY = 0f
                panel.alpha = 1f
                panel.visibility = View.VISIBLE
            }
            root.visibility = View.VISIBLE
            layoutParams.height = if (keepFullHeight) {
                WindowManager.LayoutParams.MATCH_PARENT
            } else {
                collapsedHeaderHeight(controller) ?: baseline.height
            }
            layoutParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            layoutParams.format = PixelFormat.TRANSLUCENT
            layoutParams.layoutInDisplayCutoutMode = baseline.layoutInDisplayCutoutMode
            layoutParams.flags = layoutParams.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            restoreExpandedContent(root)
            restoreNativePanelAppearance(controller)
            layoutParams.height = baseline.height
            layoutParams.screenOrientation = baseline.screenOrientation
            layoutParams.flags = baseline.flags
            setPrivateFlags(layoutParams, baseline.privateFlags)
            layoutParams.format = baseline.format
            layoutParams.layoutInDisplayCutoutMode = baseline.layoutInDisplayCutoutMode
        }
        updateAttachedWindow(root, windowManager, layoutParams)
    }

    private fun privateFlagsOf(layoutParams: WindowManager.LayoutParams): Int =
        runCatching { XposedHelpers.getIntField(layoutParams, "privateFlags") }
            .getOrDefault(0)

    private fun setPrivateFlags(
        layoutParams: WindowManager.LayoutParams,
        privateFlags: Int
    ): Boolean {
        val current = privateFlagsOf(layoutParams)
        if (current == privateFlags) return false
        return runCatching {
            XposedHelpers.setIntField(layoutParams, "privateFlags", privateFlags)
            true
        }.onFailure {
            unavailable("trusted overlay private flags", it.message)
        }.getOrDefault(false)
    }

    private fun setTrustedOverlay(
        layoutParams: WindowManager.LayoutParams,
        trusted: Boolean
    ): Boolean {
        val current = privateFlagsOf(layoutParams)
        val target = if (trusted) {
            current or PRIVATE_FLAG_TRUSTED_OVERLAY
        } else {
            current and PRIVATE_FLAG_TRUSTED_OVERLAY.inv()
        }
        return setPrivateFlags(layoutParams, target)
    }

    private fun applyCollapsedHeaderLayout(header: View, context: Context) {
        val params = header.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        synchronized(headerBaselines) {
            headerBaselines.getOrPut(header) {
                HeaderBaseline(params.topMargin, params.bottomMargin)
            }
        }
        val compactTopMargin = (
            COLLAPSED_HEADER_TOP_MARGIN_DP * context.resources.displayMetrics.density
        ).toInt()
        if (params.topMargin == compactTopMargin) return
        params.topMargin = compactTopMargin
        header.layoutParams = params
    }

    private fun resolvedCoverRotation(root: View): Int {
        val snapshot = CoverRuntime.coverDisplaySnapshot()
        val displayId = CoverRuntime.displayIdOf(root.context)
        return if (snapshot != null && snapshot.id == displayId) {
            snapshot.rotation
        } else {
            root.display?.rotation ?: Surface.ROTATION_0
        }
    }

    private fun applyCollapsedHeaderGeometry(
        header: View,
        root: View,
        context: Context
    ): CoverHeaderGeometry {
        applyCollapsedHeaderLayout(header, context)
        val edgeLength = root.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val cutout = root.rootWindowInsets?.displayCutout
        val rotation = resolvedCoverRotation(root)
        val rootHeight = root.height.takeIf { it > 0 }
            ?: context.resources.displayMetrics.heightPixels
        val cutoutBounds = cutout?.boundingRects
            ?.filterNot(Rect::isEmpty)
            ?.map(::Rect)
            .orEmpty()
        val safeTop = when (rotation) {
            // During a 180-degree relayout Samsung briefly reports the old
            // bottom-edge cutout. Treating it as top-safe inset moves the
            // header down until the final configuration arrives.
            Surface.ROTATION_180 -> cutoutBounds
                .filter { it.top <= 1 }
                .maxOfOrNull(Rect::bottom) ?: 0
            else -> cutout?.safeInsetTop ?: 0
        }
        val headerHeightPx = nativeHeaderHeight(header).coerceAtLeast(1)
        val mappedCutouts = cutoutBounds.map {
            CoverCutoutRect(it.left, it.top, it.right, it.bottom)
        }
        val band = coverTopStatusBand(
            rotation = rotation,
            windowWidthPx = edgeLength,
            windowHeightPx = rootHeight,
            cutoutRects = mappedCutouts,
            headerHeightPx = headerHeightPx
        )
        val headerTopMargin =
            (header.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val headerTopInRoot = layoutTopWithinRoot(header, root) - headerTopMargin
        val translationY = -safeTop.toFloat()
        val geometry = CoverHeaderGeometry(
            rotation = rotation,
            edgeLength = band.widthPx,
            safeTop = safeTop,
            translationY = translationY,
            translationX = band.leftPx.toFloat(),
            cutoutBounds = cutoutBounds
        )
        val params = header.layoutParams
        if (params != null && params.width != geometry.edgeLength) {
            params.width = geometry.edgeLength
            header.layoutParams = params
        }
        header.translationX = geometry.translationX
        if (headerGeometrySnapshots.put(header, geometry) != geometry) {
            CoverRuntime.log(
                SCOPE,
                "display-1 status bar geometry rotation=${geometry.rotation} " +
                    "edge=${geometry.edgeLength} left=${geometry.translationX} " +
                    "safeTop=${geometry.safeTop} " +
                    "headerTop=$headerTopInRoot translationY=${geometry.translationY} " +
                    "cutout=${geometry.cutoutBounds}"
            )
        }
        return geometry
    }

    private fun suppressExpandedContentOnHome(root: View) {
        val cached = homeExpandedContentCandidates[root]
            ?.mapNotNull(WeakReference<View>::get)
            ?.takeIf { views ->
                views.isNotEmpty() && views.all { it.isAttachedToWindow && it.isDescendantOf(root) }
            }
        val candidates = cached?.toMutableSet() ?: expandedContentResourceNames.mapNotNull {
                resourceName ->
            val id = root.resources.getIdentifier(
                resourceName,
                "id",
                CoverRuntime.SYSTEM_UI_PACKAGE
            )
            id.takeIf { it != 0 }?.let { root.findViewById<View>(it) }
        }.toMutableSet().also { resolved ->
            collectViews(root) { view ->
                view.contentDescription?.toString() == "编辑快捷设置磁贴"
            }.forEach(resolved::add)
            if (resolved.isNotEmpty()) {
                homeExpandedContentCandidates[root] = resolved.map(::WeakReference)
            }
        }

        var hidden = 0
        candidates.forEach { view ->
            if (view.visibility == View.GONE) return@forEach
            collapsedHomeContentVisibilities.putIfAbsent(view, view.visibility)
            view.visibility = View.GONE
            hidden++
        }
        if (hidden > 0) {
            CoverRuntime.log(
                SCOPE,
                "display-1 HOME expanded content suppressed views=$hidden"
            )
        }
    }

    private fun restoreExpandedBackdrop(root: View) {
        val id = root.resources.getIdentifier(
            "subscreen_quickpanel_blur_view",
            "id",
            CoverRuntime.SYSTEM_UI_PACKAGE
        )
        if (id == 0) return
        val backdrop = root.findViewById<View>(id) ?: return
        val visibility = collapsedHomeContentVisibilities.remove(backdrop) ?: View.VISIBLE
        backdrop.visibility = visibility
    }

    private fun restoreExpandedContent(root: View) {
        val restored = collapsedHomeContentVisibilities.entries
            .filter { (view, _) -> view === root || view.isDescendantOf(root) }
            .map { it.key to it.value }
        restored.forEach { (view, visibility) ->
            collapsedHomeContentVisibilities.remove(view)
            view.visibility = visibility
        }
        if (restored.isNotEmpty()) {
            CoverRuntime.log(
                SCOPE,
                "display-1 expanded content restored views=${restored.size}"
            )
        }
    }

    private fun collectViews(root: View, predicate: (View) -> Boolean): List<View> {
        val result = mutableListOf<View>()
        fun visit(view: View) {
            if (predicate(view)) result += view
            val group = view as? ViewGroup ?: return
            for (index in 0 until group.childCount) visit(group.getChildAt(index))
        }
        visit(root)
        return result
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var candidate = parent
        while (candidate is View) {
            if (candidate === ancestor) return true
            candidate = candidate.parent
        }
        return false
    }

    /**
     * Returns the native SecCoverBlurController root view that lives inside
     * [panel], if any. The SemBlurInfo gaussian layer is attached to that view,
     * so callers that translate [panel] can counter-translate the host to keep
     * the blur screen-anchored.
     */
    internal fun nativeBlurHostWithin(panel: View): View? =
        synchronized(blurControllers) {
            blurControllers.keys.firstOrNull { it !== panel && it.isDescendantOf(panel) }
        }

    internal fun restoreCoverPanelBlur(panel: View) {
        val entry = synchronized(blurControllers) {
            blurControllers.entries.firstOrNull { (root, _) ->
                root === panel || panel.isDescendantOf(root) || root.isDescendantOf(panel)
            }
        }
        val blurRoot = entry?.key ?: panel
        val blurController = entry?.value
        transparentCollapsedPanels.remove(blurRoot)
        if (blurController == null) {
            CoverRuntime.log(
                SCOPE,
                "display-1 native cover blur handoff skipped controller=false " +
                    "panel=${panel.javaClass.name}"
            )
            return
        }
        runCatching { XposedHelpers.callMethod(blurController, "applyBlur") }
            .onSuccess {
                CoverRuntime.log(
                    SCOPE,
                    "display-1 native cover blur handoff restored root=${blurRoot.javaClass.name}"
                )
            }
            .onFailure { unavailable("native cover blur handoff", it.message) }
    }

    private fun restoreNativePanelAppearance(
        controller: Any,
        preservePanelAnimationState: Boolean = false
    ) {
        val panel = field(controller, "mQSPanel") as? View ?: return
        val nativePanelAlpha = panel.alpha
        val nativePanelTranslationY = panel.translationY
        val headerId = panel.resources.getIdentifier(
            "sub_screen_quick_panel_header",
            "id",
            panel.context.packageName
        )
        val header: View? = if (headerId != 0) {
            panel.findViewById<View>(headerId)
        } else {
            null
        }
        header?.let {
            restoreHeaderLayout(it)
            it.translationY = 0f
            it.alpha = 1f
            it.visibility = View.VISIBLE
        }
        panel.translationY = if (preservePanelAnimationState) nativePanelTranslationY else 0f
        panel.alpha = if (preservePanelAnimationState) nativePanelAlpha else 1f
        panel.visibility = View.VISIBLE
        transparentCollapsedPanels.remove(panel)
        restoreCoverPanelBlur(panel)
    }

    private fun restoreHeaderLayout(header: View) {
        val baseline = headerBaselines[header] ?: return
        val params = header.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (
            params.topMargin == baseline.topMargin &&
            params.bottomMargin == baseline.bottomMargin
        ) return
        params.topMargin = baseline.topMargin
        params.bottomMargin = baseline.bottomMargin
        header.layoutParams = params
    }

    private fun clearNativeSurfaceEffects(vararg views: View) {
        views.forEach(::clearNativeBlur)
    }

    private fun clearNativeBlur(view: View) {
        view.background = null
        runCatching {
            val method = view.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "semSetBlurInfo" && candidate.parameterCount == 1
            } ?: return@runCatching
            method.invoke(view, null as Any?)
        }.onFailure { unavailable("native cover blur clear", it.message) }
    }

    private fun coverTopSurface(context: Context): CoverTopSurface {
        val now = SystemClock.elapsedRealtime()
        val cached = topSurfaceSnapshot
        val resolved = runCatching {
            val activityManager = context.getSystemService(ActivityManager::class.java)
                ?: return@runCatching CoverTopSurface.OTHER
            val coverTasks = activityManager.getRunningTasks(32).filter { task ->
                CoverRuntime.isCoverDisplay(XposedHelpers.getIntField(task, "displayId"))
            }
            val task = coverTasks.firstOrNull { task ->
                runCatching { XposedHelpers.getBooleanField(task, "isFocused") }
                    .getOrDefault(false)
            } ?: coverTasks.firstOrNull { task ->
                runCatching { XposedHelpers.getBooleanField(task, "isVisible") }
                    .getOrDefault(false)
            } ?: coverTasks.firstOrNull()
                ?: return@runCatching CoverTopSurface.TRANSITION
            val component = task.topActivity ?: return@runCatching CoverTopSurface.TRANSITION
            if (component.packageName != CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE) {
                return@runCatching CoverTopSurface.OTHER
            }
            when {
                component.className == RECENTS_ACTIVITY_CLASS -> CoverTopSurface.RECENTS
                component.className == "com.sec.android.app.launcher.activities.LauncherActivity" ||
                    isFullCoverMainLauncher(
                        component.className,
                        CoverQsModeConfig.readTransaction(context).isStableFull,
                        runCatching {
                            (XposedHelpers.callMethod(task, "getActivityType") as Number).toInt() == 2
                        }.getOrDefault(false)
                    ) -> CoverTopSurface.MAIN_LAUNCHER
                component.className == SECONDARY_LAUNCHER_CLASS ->
                    CoverTopSurface.SECONDARY_LAUNCHER
                else -> CoverTopSurface.OTHER
            }
        }.getOrDefault(CoverTopSurface.OTHER)
        if (
            resolved == CoverTopSurface.TRANSITION &&
            cached.capturedAt != Long.MIN_VALUE &&
            cached.surface != CoverTopSurface.OTHER &&
            now <= topSurfaceRotationHoldUntil
        ) {
            return cached.surface
        }
        if (resolved != CoverTopSurface.TRANSITION) {
            topSurfaceSnapshot = TopSurfaceSnapshot(resolved, now)
        }
        return resolved
    }

    private fun collapseNativePanelIfNeeded(controller: Any, panelExpanded: Boolean) {
        if (!panelExpanded) {
            collapseRequestedControllers.remove(controller)
            return
        }
        val animationRunning = field(controller, "mPanelHeightAnimator") != null
        if (!collapseRequestedControllers.add(controller)) return
        runCatching {
            XposedHelpers.callMethod(controller, "collapsePanel")
            CoverRuntime.log(
                SCOPE,
                "display-1 native collapse requested workspace=false " +
                    "expanded=$panelExpanded animation=$animationRunning"
            )
        }.onFailure {
            collapseRequestedControllers.remove(controller)
            unavailable("native cover panel collapse", it.message)
        }
    }

    private fun collapsedHeaderHeight(controller: Any): Int? {
        val panel = field(controller, "mQSPanel") as? View ?: return null
        val header = findNativeHeader(panel) ?: return null
        applyCollapsedHeaderLayout(header, panel.context)
        return nativeHeaderHeight(header).takeIf { it > 0 }
    }

    private fun findNativeHeader(panel: View): View? {
        val headerId = panel.resources.getIdentifier(
            "sub_screen_quick_panel_header",
            "id",
            panel.context.packageName
        )
        return headerId.takeIf { it != 0 }?.let(panel::findViewById)
    }

    private fun ensureExpandedHeaderVisible(
        root: View,
        header: View,
        geometry: CoverHeaderGeometry
    ) {
        val previousTranslationY = header.translationY
        applyHomeContentTranslation(
            root,
            header,
            if (geometry.rotation == Surface.ROTATION_180) geometry.translationY else 0f
        )
        var visibilityChanged = false
        var visualChanged = previousTranslationY != header.translationY
        if (header.alpha != 1f) {
            header.alpha = 1f
            visualChanged = true
        }
        if (header.visibility != View.VISIBLE) {
            header.visibility = View.VISIBLE
            visibilityChanged = true
            visualChanged = true
        }
        collectViews(header) { it.visibility != View.GONE }.forEach { view ->
            if (view.alpha != 1f) {
                view.alpha = 1f
                visualChanged = true
            }
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
                visibilityChanged = true
                visualChanged = true
            }
        }
        if (visualChanged) {
            header.invalidate()
        }
    }

    private fun layoutTopWithinRoot(view: View, root: View): Int {
        var top = view.top
        var ancestor = view.parent
        while (ancestor is View && ancestor !== root) {
            top += ancestor.top
            ancestor = ancestor.parent
        }
        return top
    }

    private fun applyHomeContentTranslation(
        root: View,
        content: View,
        translationY: Float
    ) {
        val previous = homeContentTransforms[root]
        val ancestorClips = if (previous?.content?.get() === content) {
            previous.ancestorClips
        } else {
            previous?.let(::restoreHomeContentTransform)
            collectAncestorClipBaselines(content, root)
        }
        ancestorClips.forEach { baseline ->
            baseline.view.get()?.let { ancestor ->
                ancestor.clipChildren = false
                ancestor.clipToPadding = false
            }
        }
        val transform = HomeContentTransform(
            WeakReference(content),
            translationY,
            ancestorClips
        )
        homeContentTransforms[root] = transform
        if (kotlin.math.abs(content.translationY - translationY) > 0.5f) {
            content.translationY = translationY
        }
        installHomeContentPreDrawSync(root)
    }

    private fun collectAncestorClipBaselines(
        content: View,
        root: View
    ): List<AncestorClipBaseline> {
        val baselines = mutableListOf<AncestorClipBaseline>()
        var ancestor = content.parent as? ViewGroup
        while (ancestor != null) {
            baselines += AncestorClipBaseline(
                WeakReference(ancestor),
                ancestor.clipChildren,
                ancestor.clipToPadding
            )
            if (ancestor === root) break
            ancestor = ancestor.parent as? ViewGroup
        }
        return baselines
    }

    private fun installHomeContentPreDrawSync(root: View) {
        if (homeContentPreDrawListeners.containsKey(root)) return
        val observer = root.viewTreeObserver.takeIf { it.isAlive } ?: return
        val rootReference = WeakReference(root)
        val listener = ViewTreeObserver.OnPreDrawListener {
            val attachedRoot = rootReference.get() ?: return@OnPreDrawListener true
            val transform = homeContentTransforms[attachedRoot]
                ?: return@OnPreDrawListener true
            val content = transform.content.get() ?: return@OnPreDrawListener true
            if (kotlin.math.abs(content.translationY - transform.translationY) > 0.5f) {
                content.translationY = transform.translationY
            }
            transform.ancestorClips.forEach { baseline ->
                baseline.view.get()?.let { ancestor ->
                    ancestor.clipChildren = false
                    ancestor.clipToPadding = false
                }
            }
            true
        }
        observer.addOnPreDrawListener(listener)
        homeContentPreDrawListeners[root] = listener
    }

    private fun resetWindowContentTranslation(controller: Any, root: View) {
        homeContentTransforms.remove(root)?.let(::restoreHomeContentTransform)
        val listener = homeContentPreDrawListeners.remove(root)
        val observer = root.viewTreeObserver
        if (listener != null && observer.isAlive) {
            observer.removeOnPreDrawListener(listener)
        }
        val panel = field(controller, "mQSPanel") as? View ?: return
        findNativeHeader(panel)?.translationY = 0f
    }

    private fun restoreHomeContentTransform(transform: HomeContentTransform) {
        transform.content.get()?.translationY = 0f
        transform.ancestorClips.forEach { baseline ->
            baseline.view.get()?.let { ancestor ->
                ancestor.clipChildren = baseline.clipChildren
                ancestor.clipToPadding = baseline.clipToPadding
            }
        }
    }

    private fun nativeHeaderHeight(header: View): Int {
        val params = header.layoutParams
        val measured = header.measuredHeight.takeIf { it > 0 }
            ?: params.height.takeIf { it > 0 }
            ?: return 0
        val topMargin = (params as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val bottomMargin = (params as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        return topMargin + measured + bottomMargin
    }

    private fun installHomeTouchableRegion(root: View, headerHeight: Int) {
        homeTouchableHeights[root] = headerHeight
        if (homeTouchableInsetsListeners.containsKey(root)) return
        val observer = root.viewTreeObserver.takeIf { it.isAlive } ?: return
        val listenerClass = XposedHelpers.findClassIfExists(
            "android.view.ViewTreeObserver\$OnComputeInternalInsetsListener",
            root.javaClass.classLoader
        ) ?: return unavailable("home touchable region", "listener class missing")
        val listener = Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "CoverHomeTouchableInsetsListener@${System.identityHashCode(proxy)}"
                "onComputeInternalInsets" -> {
                    val info = args?.firstOrNull()
                    if (info != null) {
                        XposedHelpers.callMethod(info, "setTouchableInsets", 3)
                        val width = root.width.coerceAtLeast(0)
                        val height = (homeTouchableHeights[root] ?: 0).coerceAtLeast(0)
                        val rootHeight = root.height.coerceAtLeast(0)
                        val top = 0
                        val bottom = if (rootHeight > 0) {
                            height.coerceAtMost(rootHeight)
                        } else {
                            height
                        }
                        (field(info, "touchableRegion") as? Region)?.set(
                            Rect(0, top, width, bottom)
                        )
                    }
                    null
                }
                else -> null
            }
        }
        runCatching {
            XposedHelpers.callMethod(observer, "addOnComputeInternalInsetsListener", listener)
            homeTouchableInsetsListeners[root] = listener
        }.onFailure { unavailable("home touchable region install", it.message) }
    }

    private fun removeHomeTouchableRegion(root: View) {
        homeTouchableHeights.remove(root)
        val listener = homeTouchableInsetsListeners.remove(root) ?: return
        val observer = root.viewTreeObserver.takeIf { it.isAlive } ?: return
        runCatching {
            XposedHelpers.callMethod(observer, "removeOnComputeInternalInsetsListener", listener)
        }.onFailure { unavailable("home touchable region remove", it.message) }
    }

    private fun updateAttachedWindow(
        root: View,
        windowManager: WindowManager,
        layoutParams: WindowManager.LayoutParams
    ) {
        if (!root.isAttachedToWindow) return
        if (isInLayoutHierarchy(root)) {
            if (XposedHelpers.getAdditionalInstanceField(root, WINDOW_UPDATE_POSTED_MARK) == true) {
                return
            }
            XposedHelpers.setAdditionalInstanceField(root, WINDOW_UPDATE_POSTED_MARK, true)
            root.postOnAnimation {
                XposedHelpers.removeAdditionalInstanceField(root, WINDOW_UPDATE_POSTED_MARK)
                updateAttachedWindow(root, windowManager, layoutParams)
            }
            return
        }
        runCatching { windowManager.updateViewLayout(root, layoutParams) }
            .onFailure { unavailable("native status bar window update", it.message) }
    }

    private fun surroundingController(instance: Any, controllerClass: Class<*>): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.forEach { candidate ->
                val value = candidate.readObject(instance) ?: return@forEach
                if (controllerClass.isInstance(value)) return value
            }
            type = type.superclass
        }
        return null
    }

    private fun Field.readObject(instance: Any): Any? = runCatching {
        isAccessible = true
        get(instance)
    }.getOrNull()

    private fun field(instance: Any, name: String): Any? = runCatching {
        XposedHelpers.getObjectField(instance, name)
    }.getOrNull()

    private fun booleanField(instance: Any, name: String): Boolean? = runCatching {
        XposedHelpers.getBooleanField(instance, name)
    }.getOrNull()

    private fun unavailable(feature: String, reason: String?) {
        CoverRuntime.log(SCOPE, "$feature unavailable: ${reason ?: "unknown"}")
    }
}
