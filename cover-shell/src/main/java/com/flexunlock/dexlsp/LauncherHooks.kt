package com.flexunlock.dexlsp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.DisplayCutout
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.PopupWindow
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.AndroidAppHelper
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

internal data class EdgeHandleInputSpan(val top: Int, val bottom: Int)

internal fun edgeHandleInputSpan(height: Int, stableFullQs: Boolean): EdgeHandleInputSpan {
    if (!stableFullQs) return EdgeHandleInputSpan(0, height)
    val scaledHeight = (height / 4).coerceAtLeast(1)
    val top = (height - scaledHeight) / 2
    return EdgeHandleInputSpan(top, top + scaledHeight)
}

internal fun shouldToggleCoverRecentsDirectly(
    stableFullQs: Boolean,
    regionType: String?
): Boolean = stableFullQs && regionType == "RECENT"

internal fun shouldFinishManagedExternalRecents(
    recentsActivity: Boolean,
    externalDisplay: Boolean
): Boolean = recentsActivity && externalDisplay

internal fun isScrcpyDisplayUniqueId(uniqueId: String?): Boolean =
    uniqueId?.startsWith("virtual:com.android.shell,2000,scrcpy,", true) == true

internal fun shouldLaunchScrcpyHome(
    action: Int,
    flags: Int,
    keyCode: Int,
    uniqueId: String?
): Boolean = action == KeyEvent.ACTION_UP &&
    flags == 0 &&
    keyCode == KeyEvent.KEYCODE_HOME &&
    isScrcpyDisplayUniqueId(uniqueId)

internal fun shouldRouteLauncherAppToDisplay(
    displayId: Int?,
    isSelectableDisplay: Boolean,
    isScrcpy: Boolean
): Boolean = displayId != null && displayId > 0 && (isSelectableDisplay || isScrcpy)

internal object LauncherHooks {
    private const val SCOPE = "Launcher"
    private const val COVER_DRAWER_ICON_LABEL_GAP_PX = 3
    private const val FULL_QS_DRAWER_ICON_LABEL_GAP_PX = -12
    private const val FULL_QS_DRAWER_ICON_HORIZONTAL_OFFSET_PX = 4
    private const val COVER_DRAWER_ICON_EDGE_SAFETY_PX = 6
    private const val COVER_HOME_ICON_EDGE_SAFETY_PX = 2
    private const val COVER_DRAWER_LABEL_TEXT_SIZE_SP = 11.5f
    private const val COVER_DRAWER_COLUMNS = 6
    private const val COVER_DRAWER_ROWS = 4
    private const val COVER_DRAWER_PORTRAIT_TOP_PADDING_REDUCTION_PX = 0
    // Measured on Flip5 cover at 0°: land more/search circles are 102px with 45px between.
    private const val COVER_DRAWER_SEARCH_BUTTON_GAP_PX = 45
    private const val COVER_QS_NATIVE_PULL_ZONE_PX = 240f
    private const val HOTSEAT_HORIZONTAL_SAFE_INSET_PX = 16
    private const val FULLSCREEN_CORNER_RADIUS_PX = 0.0f
    private const val HOTSEAT_TOP_OFFSET_PX = 8
    private const val DRAWER_HORIZONTAL_PADDING_PX = 12
    private const val DRAWER_PORTRAIT_HORIZONTAL_PADDING_PX = 24
    private const val DRAWER_TOP_PADDING_PX = 22
    private const val DRAWER_BOTTOM_PADDING_PX = 72
    private const val FULL_DEX_DRAWER_MIN_COLUMNS = 4
    private const val FULL_DEX_DRAWER_MAX_COLUMNS = 8
    private const val APP_LAUNCH_TRANSITION = "APP_LAUNCH"
    private const val COVER_APP_FULLSCREEN_SNAP_EDGE_RATIO = 0.0125f
    private const val COVER_HEADER_SAFE_HEIGHT_RATIO = 0.08f
    private const val BYPASSABLE_WINDOW_EVENT_FLAG = 0x20000000
    private const val EXTRA_SWIPE_INPUT_RECEIVER_DISCRIMINATOR = 3
    private const val COVER_RECENTS_ENTER_DURATION_MS = 180L
    private const val SCRCPY_APP_CLOSE_DURATION_MS = 240L
    private const val COVER_RECENTS_NATIVE_BACKDROP_DURATION_MS = 300L
    private const val COVER_RECENTS_SPRING_FAST_FINISH_SETTLE_MS = 34L
    private const val QUICK_PANEL_COLLAPSE_RECENTS_COOLDOWN_MS = 350L
    private const val CACHED_SHELL_TRANSITION_REPLAY_MS = 2_000L
    private const val COVER_WORK_TAB_RETRY_DELAY_MS = 250L
    private const val COVER_WORK_TAB_RETRY_ATTEMPTS = 12
    private const val RECENTS_STAGE_RETRY_DELAY_MS = 64L
    private const val RECENTS_STAGE_RETRY_ATTEMPTS = 5
    private const val RECENTS_NATIVE_KEY_WAIT_MS = 80L
    private const val RECENTS_ACTIVITY_CLASS = "com.android.quickstep.RecentsActivity"
    private const val LAUNCHER_ACTIVITY_CLASS =
        "com.sec.android.app.launcher.Launcher"
    private const val SECONDARY_LAUNCHER_CLASS =
        "com.honeyspace.dexservice.SecondaryLauncher"
    private const val HOME_SCREEN_NORMAL_CLASS = "com.honeyspace.sdk.HomeScreen\$Normal"
    private const val HONEYSPACE_COMPONENT_ENTRY_POINT_CLASS =
        "com.honeyspace.common.di.HoneySpaceComponentEntryPoint"
    private const val HONEY_GENERATED_COMPONENT_MANAGER_ENTRY_POINT_CLASS =
        "com.honeyspace.common.di.HoneyGeneratedComponentManagerEntryPoint"
    private const val HONEYSPACE_CONTEXT_EXTENSION_CLASS =
        "com.honeyspace.common.context.ContextExtensionKt"
    private const val HILT_ENTRY_POINTS_CLASS = "dagger.hilt.EntryPoints"
    private const val FULL_DEX_INFO_CLASS = "com.honeyspace.ui.common.dex.CombinedDexInfoImpl"
    private const val FULL_DEX_TASKBAR_CLASS =
        "com.honeyspace.ui.common.taskbar.TaskbarControllerImpl"
    private val FULL_DEX_RUNE_METHODS = setOf(
        "getHOME_SUPPORT_TASKBAR",
        "getSUPPORT_DESKTOP_MODE",
        "getSUPPORT_DESKTOP_WINDOWING",
        "getSUPPORT_DEX_POPUP_TOP_LAYER"
    )
    private val FULL_DEX_BOOLEAN_METHODS = setOf(
        "isDexSpace",
        "isDexSpaceInHomeOnly",
        "isInternalDex",
        "isExtendedMode",
        "getSupportDexStandAlone",
        "getSupportDexHotseatSync"
    )
    private val FULL_DEX_STATE_FLOW_METHODS = setOf(
        "isDockedTaskbar",
        "isExternalDexConnected"
    )

    private data class DrawerIconStyle(
        val iconSizePx: Int,
        val labelGapPx: Int,
        val labelTextSizeSp: Float
    )

    private data class FullDexDrawerBaseline(
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val clipToPadding: Boolean
    )

    private data class FullDexWorkTabBaseline(val visibility: Int, val alpha: Float)

    private data class DrawerSourceItem(
        val item: Any,
        val sourceIndex: Int,
        val primary: Boolean,
        val page: Int,
        val rank: Int
    )

    private data class CoverDisplayGeometry(
        val rotation: Int,
        val width: Int,
        val height: Int,
        val navigationBarHeight: Int,
        val cutout: DisplayCutout?
    )

    private data class WorkspacePresentationSignal(
        val visible: Boolean,
        val screen: String,
        val state: String,
        val transition: Boolean,
        val folder: Boolean
    )

    private data class WorkspaceSnapshot(
        val screen: String,
        val state: String,
        val changeState: String,
        val homeState: String,
        val homeChangeState: String,
        val homePresented: Boolean,
        val appsPresented: Boolean,
        val recentsPresented: Boolean,
        val finderPresented: Boolean,
        val stackEmpty: Boolean,
        val transition: Boolean,
        val animation: Boolean,
        val folder: Boolean,
        val folderMode: Boolean,
        val edit: Boolean,
        val menu: Boolean,
        val appsNormal: Boolean,
        val activityResumed: Boolean,
        val activityFocused: Boolean
    ) {
        val visible: Boolean
            get() = screen == "HOME" &&
                (state == HOME_SCREEN_NORMAL_CLASS || state == "null") &&
                changeState == HOME_SCREEN_NORMAL_CLASS &&
                !appsPresented &&
                !finderPresented &&
                !transition &&
                !animation &&
                !folder &&
                !folderMode &&
                !edit
    }

    private val normalizedDrawers: MutableSet<View> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val scheduledDrawerSpacing: MutableSet<View> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val coverDrawerViewModels: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val scheduledCoverWorkTabLayouts: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val coverDrawerMenuLayoutOwners: MutableSet<Activity> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val hotseatLayoutListeners: MutableSet<View> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val hotseatOriginalTopMargins: MutableMap<View, Int> = Collections.synchronizedMap(
        WeakHashMap()
    )
    private val hotseatOriginalTranslationsX: MutableMap<View, Float> = Collections.synchronizedMap(
        WeakHashMap()
    )
    private val nativeGestureModeSources: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val nativeGestureRegions: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val nativeGestureRegionManagers: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    @Volatile
    private var nativeGestureBottomPosition: Any? = null
    private val nativeGestureDisplaySyncLock = Any()
    @Volatile
    private var nativeGestureDisplayListener: DisplayManager.DisplayListener? = null
    private val nativeGestureHandlers: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val coverAnimationSessions: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val activeCoverTransitionSessions: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val coverRecentsPolicyInstances: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val coverRecentsWallpaperStartDepth = ThreadLocal.withInitial { 0 }
    private val coverRecentsWallpaperAnimators: MutableMap<Any, Any> =
        Collections.synchronizedMap(WeakHashMap())
    private val coverRecentsEnterAnimatorSets: MutableSet<AnimatorSet> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val coverRecentsOverlayRoots: MutableMap<Any, ViewGroup> =
        Collections.synchronizedMap(WeakHashMap())
    private val coverRecentsBackdropViews: MutableMap<Any, List<View>> =
        Collections.synchronizedMap(WeakHashMap())
    private val coverRecentsFastFinishRunnables: MutableMap<Any, Runnable> =
        Collections.synchronizedMap(WeakHashMap())
    private val coverScreenManagers: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val coverWorkspacePopups: MutableSet<PopupWindow> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val coverQuickOptionUtils: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val quickOptionUtilHookedClasses: MutableSet<Class<*>> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val workspaceSignalHookedClasses: MutableSet<Class<*>> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val workspaceScreenOwners: MutableMap<Any, Any> = Collections.synchronizedMap(
        WeakHashMap()
    )
    private val workspaceScreenNames: MutableMap<Any, String> = Collections.synchronizedMap(
        WeakHashMap()
    )
    private val workspaceScreenPresented: MutableMap<Any, Boolean> =
        Collections.synchronizedMap(WeakHashMap())
    private val workspaceScreenHookedClasses: MutableSet<Class<*>> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val workspaceScreenHookedMethodSignatures: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())
    private val workspaceTransitionAnimators: MutableSet<ValueAnimator> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val workspaceTransitionTargets: MutableMap<Any, String> =
        Collections.synchronizedMap(WeakHashMap())
    private val loggedCoverAppLaunchPlayers: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val loggedCoverTaskLaunchPlayers: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val snappedCoverAppLaunchPlayers: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val completedCoverAppLaunchPlayers: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val coverReturnHeaderGeometryStates: MutableMap<Any, Boolean> =
        Collections.synchronizedMap(WeakHashMap())

    @Volatile
    private var lastCoverWorkspaceSnapshot: WorkspaceSnapshot? = null

    @Volatile
    private var lastPublishedCoverWorkspaceSignal: WorkspacePresentationSignal? = null

    @Volatile
    private var coverWorkspaceEpoch = SystemClock.elapsedRealtimeNanos()

    @Volatile
    private var coverWorkspaceSequence = 0L

    @Volatile
    private var resumedSecondaryLauncher = WeakReference<Activity>(null)

    @Volatile
    private var secondaryLauncherResumed = false

    @Volatile
    private var secondaryLauncherFocused = false

    @Volatile
    private var coverWorkspaceRequestReceiverRegistered = false

    @Volatile
    private var fullDexChangeReceiverRegistered = false

    @Volatile
    private var latestShellTransitionActive: Boolean? = null

    @Volatile
    private var latestShellTransitionReason: String? = null

    @Volatile
    private var latestShellTransitionElapsed = 0L

    @Volatile
    private var coverScreenManagerUtilClass: Class<*>? = null

    @Volatile
    private var nativeSamsungTouchRegionClass: Class<*>? = null

    @Volatile
    private var lastCoverDisplayCutout: DisplayCutout? = null

    private val coverIconSizeReceiverInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var configuredCoverHomeIconSizePx = DEFAULT_COVER_HOME_ICON_SIZE_PX

    @Volatile
    private var configuredCoverDrawerIconSizePx = DEFAULT_COVER_DRAWER_ICON_SIZE_PX

    @Volatile
    private var coverQuickPanelBlocksRecents = false

    @Volatile
    private var coverQuickPanelRecentsBlockedUntil = 0L

    @Volatile
    private var resumedCoverRecentsActivity = WeakReference<Activity>(null)

    @Volatile
    private var resumedFullQsActivity = WeakReference<Activity>(null)

    @Volatile
    private var coverRecentsLifecycleResumed = false

    @Volatile
    private var publishedCoverRecentsTransitionActive: Boolean? = null

    @Volatile
    private var fullDexRecentsCardMode = true

    @Volatile
    private var coverGestureTransitionActive = false
    private var coverRecentsDispatchCommitted = false
private var nativeGestureDownRawX = 0f
private var nativeGestureDownRawY = 0f
private var coverRecentsStageManager: Any? = null

    private val suppressedCrossDisplayGestureHandlers: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val edgeHandleGestureExclusionBaselines =
        Collections.synchronizedMap(WeakHashMap<View, List<Rect>>())
    private val nativeCoverInputReceivers: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val fullDexTaskbarFlows: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val fullDexTaskbarControllers: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val fullDexOverlayLayoutHosts: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val fullDexVerticalLayoutHosts: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val fullDexDrawerBaselines: MutableMap<ViewGroup, FullDexDrawerBaseline> =
        Collections.synchronizedMap(WeakHashMap())
    private val fullDexWorkTabBaselines: MutableMap<View, FullDexWorkTabBaseline> =
        Collections.synchronizedMap(WeakHashMap())
    private val nativeCoverMonitorBindings: MutableMap<Any, Any> = Collections.synchronizedMap(
        WeakHashMap()
    )
    private val suppressedExtraMonitorBindings: MutableSet<Any> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    @Volatile
    private var lastNativeGestureDownTime = Long.MIN_VALUE

    @Volatile
    private var lastNativeGestureEventTime = Long.MIN_VALUE

    @Volatile
    private var lastNativeGestureAction = Int.MIN_VALUE

    @Volatile
    private var activeNativeGestureHandler: Any? = null

    @Volatile
    private var activeNativeGestureDownTime = Long.MIN_VALUE

    @Volatile
    private var activeNativeGestureRegionType: String? = null

    @Volatile
    private var recentsStageGestureToken = Long.MIN_VALUE

    @Volatile
    private var recentsStageLaunchPending = false

    private val nativeGestureDispatchLock = Any()
    private val nativeCoverGestureDispatchDepth = ThreadLocal.withInitial { 0 }
    private val coverRecentsRotationDepth = ThreadLocal.withInitial { 0 }
    private val scrcpyStatusPanelKeyguardBypass = ThreadLocal<Boolean>()
    @Volatile
    private var scrcpyWallpaperBypassUntil = 0L
    @Volatile
    private var scrcpyWallpaperBypassDisplayId = Display.INVALID_DISPLAY
    private val coverRecentsTaskFilterDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }
    private fun coverRecentsFilterDepth(): Int = coverRecentsTaskFilterDepth.get() ?: 0

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            installLauncherCore(lpparam)
        }.onFailure {
            unavailable("launcher hooks", "install failed: ${it.message}")
        }
    }

    private fun installLauncherCore(lpparam: XC_LoadPackage.LoadPackageParam) {
        installEarlyWorkspaceReceiver()
        installSecondaryLauncherOrientationPolicy(lpparam.classLoader)
        installSecondaryLauncherWorkspaceLifecycle(lpparam.classLoader)
        installSecondaryLauncherStatusBarPolicy(lpparam.classLoader)
        installFullDexDesktopBridge(lpparam.classLoader)
        installScrcpyTaskbarVisibilityBridge(lpparam.classLoader)
        installScrcpyNavigationButtonsBridge(lpparam.classLoader)
        installScrcpyStatusPanelBridge(lpparam.classLoader)
        installScrcpyWallpaperAnimationBridge(lpparam.classLoader)
        installScrcpyAppCloseTiming(lpparam.classLoader)
        installFullDexVerticalWorkTab(lpparam.classLoader)
        installFullDexOverlayAppsLayout(lpparam.classLoader)
        installCoverEditDisableHook(lpparam.classLoader)
        installFullDexOverlayIconLayout(lpparam.classLoader)
        installCoverWorkspaceSignal(lpparam.classLoader)
        installNativeSecondaryDisplayGate(lpparam.classLoader)
        installNativeSamsungGestureMode(lpparam.classLoader)
        installNativeSamsungTouchRegion(lpparam.classLoader)
        installCoverDeadZoneHole(lpparam.classLoader)
        installNativeRecentsTaskPolicy(lpparam.classLoader)
        installNativeRecentsRotationPolicy(lpparam.classLoader)
        installNativeRecentsWallpaperComposition(lpparam.classLoader)
        installNativeRecentsWallpaperLifecycle(lpparam.classLoader)
        installNativeRecentsOverlayBackdrop(lpparam.classLoader)
        installNativeRecentsEnterTiming(lpparam.classLoader)
        installNativeRecentsActivityFirstFrame(lpparam.classLoader)
        installNativeRecentsResumeSignal(lpparam.classLoader)
        installNativeKeyPressDisplayRoute(lpparam.classLoader)
        installNativeExtraDisplayRegionKeyRoute(lpparam.classLoader)
        installFullQsActivityLifecycle()
        installManagedExternalRecentsBackRoute(lpparam.classLoader)
        installFullQsKeyInjectionRoute(lpparam.classLoader)
        installFullQsRecentsPolicy(lpparam.classLoader)
        installNativeGestureMonitorOwnership(lpparam.classLoader)
        installNativeInputReceiverOwnership(lpparam.classLoader)
        installNativeGestureInputRoute(lpparam.classLoader)
        installNativeHomeRemoteTransitionParity(lpparam.classLoader)
        installNativeAppLaunchDisplayRoute(lpparam.classLoader)
        installNativeAppLaunchShape(lpparam.classLoader)
        installHomeIconSize(lpparam.classLoader)
        installHotseatPosition(lpparam.classLoader)
        installDrawerGridPolicy(lpparam.classLoader)
        installDrawerCellHeight(lpparam.classLoader)
        installDrawerSpacing(lpparam.classLoader)
        installCoverDrawerCutoutInsets(lpparam.classLoader)
        installCoverDrawerCompactSearch(lpparam.classLoader)
        installCoverDrawerPortraitWorkTab(lpparam.classLoader)
        installFullQsEdgeHandle(lpparam.classLoader)
    }

    private fun installScrcpyTaskbarVisibilityBridge(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.common.taskbar.TaskbarVisibilityController",
            classLoader
        ) ?: return unavailable("scrcpy taskbar visibility", "TaskbarVisibilityController missing")
        XposedBridge.hookAllMethods(controllerClass, "getNeedToHide", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isScrcpyDexTaskbarController(param.thisObject)) param.result = false
            }
        })
        XposedBridge.hookAllMethods(controllerClass, "updateTaskbarEvent", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isScrcpyDexTaskbarController(param.thisObject)) return
                val flags = (param.args.firstOrNull() as? Number)?.toLong() ?: return
                param.args[0] = flags and 576L.inv()
            }
        })
        CoverRuntime.log(SCOPE, "scrcpy taskbar visibility bridge installed")
    }

    private fun isScrcpyDexTaskbarController(instance: Any): Boolean {
        val tag = runCatching { XposedHelpers.callMethod(instance, "getTAG") as? String }
            .getOrNull()
            ?: getFieldOrNull(instance, "TAG") as? String
        if (tag?.endsWith("@Dex") != true) return false
        val combinedDexInfo = getFieldOrNull(instance, "combinedDexInfo")
        val primaryDisplayId = combinedDexInfo?.let {
            runCatching { XposedHelpers.callMethod(it, "getPrimaryDisplayId") as? Int }.getOrNull()
        }
        return runCatching {
            launcherContext()?.getSystemService(DisplayManager::class.java)?.displays?.any { display ->
                if (primaryDisplayId != null && display.displayId != primaryDisplayId) return@any false
                val uniqueId = XposedHelpers.callMethod(display, "getUniqueId") as? String
                isScrcpyDisplayUniqueId(uniqueId)
            } == true
        }.getOrDefault(false)
    }

    private fun installScrcpyNavigationButtonsBridge(classLoader: ClassLoader) {
        val layoutClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.honeypots.taskbar.presentation.NavigationBarButtonsLayout",
            classLoader
        ) ?: return unavailable("scrcpy navigation buttons", "NavigationBarButtonsLayout missing")
        XposedBridge.hookAllMethods(layoutClass, "e", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val layout = param.thisObject as? ViewGroup ?: return
                val display = layout.context.display ?: return
                val uniqueId = runCatching { XposedHelpers.callMethod(display, "getUniqueId") as? String }
                    .getOrNull()
                if (!isScrcpyDisplayUniqueId(uniqueId)) return
                val buttonWidth = layout.height.takeIf { it > 0 } ?: 56
                for (index in 0 until layout.childCount) {
                    val child = layout.getChildAt(index)
                    val buttonType = runCatching {
                        XposedHelpers.callMethod(child, "getButtonType") as? Int
                    }.getOrNull() ?: continue
                    if (buttonType == 32) {
                        child.visibility = View.GONE
                        continue
                    }
                    child.visibility = View.VISIBLE
                    val params = child.layoutParams ?: continue
                    if (params.width <= 0) {
                        params.width = buttonWidth
                        params.height = ViewGroup.LayoutParams.MATCH_PARENT
                        child.layoutParams = params
                    }
                }
            }
        })
        val buttonClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.honeypots.taskbar.presentation.NavigationBarKeyButtonView",
            classLoader
        ) ?: return unavailable("scrcpy home button", "NavigationBarKeyButtonView missing")
        XposedBridge.hookAllMethods(buttonClass, "b", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.args.size != 4) return
                val view = param.thisObject as? View ?: return
                val display = view.context.display ?: return
                val uniqueId = runCatching {
                    XposedHelpers.callMethod(display, "getUniqueId") as? String
                }.getOrNull()
                if (!shouldLaunchScrcpyHome(
                        (param.args[0] as Number).toInt(),
                        (param.args[1] as Number).toInt(),
                        (param.args[2] as Number).toInt(),
                        uniqueId
                    )) return
                launchSecondaryHome(view.context, display.displayId)
            }
        })
        CoverRuntime.log(SCOPE, "scrcpy navigation buttons bridge installed")
    }

    private fun launchSecondaryHome(context: Context, displayId: Int) {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(context.packageName, SECONDARY_LAUNCHER_CLASS)
            addCategory(Intent.CATEGORY_SECONDARY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(displayId) }
        context.startActivity(intent, options.toBundle())
        CoverRuntime.log(SCOPE, "display-$displayId scrcpy Home routed to SecondaryLauncher")
    }

    private fun installScrcpyStatusPanelBridge(classLoader: ClassLoader) {
        val managerClass = XposedHelpers.findClassIfExists("Q4.f", classLoader)
            ?: return unavailable("scrcpy status panel", "component manager missing")
        val keyguardClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.common.utils.KeyguardManagerHelper",
            classLoader
        ) ?: return unavailable("scrcpy status panel", "KeyguardManagerHelper missing")
        XposedBridge.hookAllMethods(managerClass, "b", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isScrcpyStatusComponent(param.thisObject, param.args.firstOrNull())) return
                param.setObjectExtra("scrcpyKeyguardBypass", scrcpyStatusPanelKeyguardBypass.get())
                scrcpyStatusPanelKeyguardBypass.set(true)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val previous = param.getObjectExtra("scrcpyKeyguardBypass") as? Boolean
                if (previous == null) scrcpyStatusPanelKeyguardBypass.remove()
                else scrcpyStatusPanelKeyguardBypass.set(previous)
            }
        })
        XposedBridge.hookAllMethods(keyguardClass, "isKeyguardLocked", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (scrcpyStatusPanelKeyguardBypass.get() == true) param.result = false
            }
        })
        CoverRuntime.log(SCOPE, "scrcpy status panel keyguard bridge installed")
    }

    private fun isScrcpyStatusComponent(manager: Any, componentName: Any?): Boolean {
        val components = getFieldOrNull(manager, "i") as? Map<*, *> ?: return false
        val provider = components[componentName] ?: return false
        val component = runCatching { XposedHelpers.callMethod(provider, "get") }.getOrNull()
            ?: return false
        val context = getFieldOrNull(component, "windowContext") as? Context
            ?: runCatching { XposedHelpers.callMethod(component, "g") as? Context }.getOrNull()
        return isScrcpyDisplay(context)
    }

    private fun installScrcpyWallpaperAnimationBridge(classLoader: ClassLoader) {
        val wrapperClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.shared.launcher.WindowManagerWrapper",
            classLoader
        ) ?: return unavailable("scrcpy wallpaper animation", "WindowManagerWrapper missing")
        val animatorClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.transition.anim.WallpaperAnimator",
            classLoader
        ) ?: return unavailable("scrcpy wallpaper animation", "WallpaperAnimator missing")
        val wrapper = XposedHelpers.callStaticMethod(wrapperClass, "getInstance")
        XposedBridge.hookAllMethods(animatorClass, "createWallpaperSurface", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args.size != 2) return
                val focusedDisplayId = runCatching {
                    (XposedHelpers.callMethod(wrapper, "getFocusedDisplayId") as Number).toInt()
                }.getOrDefault(0)
                val uniqueId = displayUniqueId(launcherContext(), focusedDisplayId)
                val pendingDisplayId = scrcpyWallpaperBypassDisplayId
                val pending = SystemClock.uptimeMillis() <= scrcpyWallpaperBypassUntil &&
                    pendingDisplayId != Display.INVALID_DISPLAY
                if (!isScrcpyDisplayUniqueId(uniqueId) && !pending) return
                val callback = param.args[1] ?: return
                val invokeCallback = Runnable { XposedHelpers.callMethod(callback, "invoke") }
                if (Looper.myLooper() == Looper.getMainLooper()) invokeCallback.run()
                else Handler(Looper.getMainLooper()).post(invokeCallback)
                param.result = null
                CoverRuntime.log(
                    SCOPE,
                    "display-${if (pending) pendingDisplayId else focusedDisplayId} " +
                        "scrcpy remote wallpaper animation bypassed focus=$focusedDisplayId"
                )
            }
        })
        CoverRuntime.log(SCOPE, "scrcpy wallpaper animation bridge installed")
    }

    private fun installScrcpyAppCloseTiming(classLoader: ClassLoader) {
        val delegateClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.transition.delegate.AppCloseAnimationDelegate",
            classLoader
        ) ?: return unavailable("scrcpy app close timing", "AppCloseAnimationDelegate missing")
        XposedBridge.hookAllMethods(delegateClass, "getCloseAnimator", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val animator = param.result as? Animator ?: return
                val rootView = getFieldOrNull(param.thisObject, "rootView") as? View ?: return
                if (!isScrcpyDisplay(rootView.context)) return
                scrcpyWallpaperBypassDisplayId =
                    rootView.context.display?.displayId ?: Display.INVALID_DISPLAY
                scrcpyWallpaperBypassUntil = SystemClock.uptimeMillis() + 800L
                val nativeDuration = when (animator) {
                    is AnimatorSet -> animator.totalDuration
                    else -> animator.startDelay + animator.duration
                }
                val changed = scaleAnimatorTreeToDuration(
                    animator,
                    SCRCPY_APP_CLOSE_DURATION_MS
                )
                CoverRuntime.log(
                    SCOPE,
                    "display-${rootView.context.display?.displayId} scrcpy app close " +
                        "duration=$nativeDuration->$SCRCPY_APP_CLOSE_DURATION_MS ms changed=$changed"
                )
            }
        })
        CoverRuntime.log(SCOPE, "scrcpy app close timing installed")
    }

    private fun displayUniqueId(context: Context?, displayId: Int): String? = runCatching {
        val display = context?.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
            ?: return@runCatching null
        XposedHelpers.callMethod(display, "getUniqueId") as? String
    }.getOrNull()

    private fun installFullQsEdgeHandle(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists("qb.e", classLoader)
            ?: return unavailable("full QS edge handle", "EdgeWindowController missing")
        XposedHelpers.findAndHookMethod(
            controllerClass,
            "i",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val controller = param.thisObject
                        val window = getFieldOrNull(param.thisObject, "h") as? Window ?: return
                        val context = window.decorView.context
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return
                        val stableFullQs = CoverQsModeConfig.readTransaction(context).isStableFull
                        val height = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        window.decorView.post {
                            scaleCollapsedEdgeHandle(window, if (stableFullQs) 0.25f else 1f)
                            applyEdgeHandleInputSpan(controller, window, height, stableFullQs)
                        }
                    }
                }
            )
        XposedBridge.hookAllMethods(controllerClass, "d", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val window = param.args.firstOrNull { it is Window } as? Window ?: return
                val height = window.attributes.height.takeIf { it > 0 }
                    ?: window.decorView.height.takeIf { it > 0 }
                    ?: return
                applyEdgeHandleInputSpan(param.thisObject, window, height, false)
                scaleCollapsedEdgeHandle(window, 1f)
            }
        })
        CoverRuntime.log(SCOPE, "full QS edge handle scale installed")
    }

    private fun scaleCollapsedEdgeHandle(window: Window, scale: Float) {
        val decor = window.decorView
        val trigger = findViewByEntryName(decor, "trigger")
            ?: findViewByClassName(decor, "EdgeTrigger")
            ?: return
        if (trigger.scaleY == scale) return
        trigger.pivotY = trigger.height / 2f
        trigger.scaleY = scale
        CoverRuntime.log(SCOPE, "full QS edge handle view scale=$scale")
    }

    private fun applyEdgeHandleInputSpan(
        controller: Any,
        window: Window,
        height: Int,
        stableFullQs: Boolean
    ) {
        val span = edgeHandleInputSpan(height, stableFullQs)
        val inputRect = getFieldOrNull(controller, "i") as? Rect ?: return
        inputRect.top = span.top
        inputRect.bottom = span.bottom
        runCatching {
            XposedHelpers.callMethod(controller, "j", Rect(inputRect))
        }.onFailure {
            unavailable("edge handle dynamic input region", it.message)
        }
        val decor = window.decorView
        val width = decor.width.takeIf { it > 0 } ?: window.attributes.width.coerceAtLeast(1)
        if (stableFullQs) {
            edgeHandleGestureExclusionBaselines.putIfAbsent(
                decor,
                decor.systemGestureExclusionRects.map(::Rect)
            )
            decor.systemGestureExclusionRects = listOf(Rect(0, span.top, width, span.bottom))
        } else {
            edgeHandleGestureExclusionBaselines.remove(decor)?.let { baseline ->
                decor.systemGestureExclusionRects = baseline.map(::Rect)
            }
        }
    }

    private fun findViewByClassName(root: View, simpleName: String): View? {
        if (root.javaClass.simpleName == simpleName) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findViewByClassName(group.getChildAt(index), simpleName)?.let { return it }
        }
        return null
    }

    private fun installFullDexDesktopBridge(classLoader: ClassLoader) {
        XposedHelpers.findClassIfExists("g3.f", classLoader)?.declaredMethods
            ?.filter { method ->
                method.name == "c" &&
                    method.returnType == String::class.java &&
                    method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }
            ?.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (
                            fullDexActive() &&
                            CoverRuntime.isCoverDisplay(displayId)
                        ) {
                            param.result = "Dex"
                        }
                    }
                })
            }

        XposedHelpers.findClassIfExists("com.honeyspace.common.Rune\$Companion", classLoader)
            ?.declaredMethods
            ?.filter { method ->
                method.name in FULL_DEX_RUNE_METHODS &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterCount == 0
            }
            ?.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (fullDexActive()) param.result = true
                    }
                })
            }

        XposedHelpers.findClassIfExists(FULL_DEX_INFO_CLASS, classLoader)?.let { dexInfoClass ->
            methodsInHierarchy(dexInfoClass).forEach { method ->
                when {
                    method.parameterCount != 0 -> Unit
                    method.name in FULL_DEX_BOOLEAN_METHODS &&
                        method.returnType == Boolean::class.javaPrimitiveType -> {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (fullDexActive(param.thisObject)) param.result = true
                            }
                        })
                    }
                    method.name == "getPrimaryDisplayId" &&
                        method.returnType == Int::class.javaPrimitiveType -> {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!fullDexActive(param.thisObject)) return
                                CoverDisplayResolver.currentId()?.let { param.result = it }
                            }
                        })
                    }
                    method.name == "getPrimaryDisplay" && method.returnType.name == "android.view.Display" -> {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!fullDexActive(param.thisObject)) return
                                val context = launcherContext() ?: return
                                val displayId = CoverDisplayResolver.currentId() ?: return
                                param.result = context.getSystemService(DisplayManager::class.java)
                                    ?.getDisplay(displayId)
                            }
                        })
                    }
                    method.name in FULL_DEX_STATE_FLOW_METHODS &&
                        method.returnType.name == "kotlinx.coroutines.flow.StateFlow" -> {
                        method.isAccessible = true
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (!fullDexActive(param.thisObject)) return
                                val flow = param.result ?: return
                                setFlowValueIfChanged(flow, true)
                            }
                        })
                    }
                }
            }
        }

        XposedHelpers.findClassIfExists(FULL_DEX_TASKBAR_CLASS, classLoader)?.let { taskbarClass ->
            XposedBridge.hookAllConstructors(taskbarClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    fullDexTaskbarControllers += param.thisObject
                }
            })
            methodsInHierarchy(taskbarClass)
                .filter { it.name == "getTaskbarStyleInfo" }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!fullDexTaskbarActive(param.thisObject)) return
                            getFieldOrNull(param.thisObject, "_taskbarAvailable")?.let { flow ->
                                fullDexTaskbarFlows += flow
                                setFlowValueIfChanged(flow, true)
                            }
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!fullDexTaskbarActive(param.thisObject)) return
                            val current = param.result ?: return
                            if (runCatching {
                                    XposedHelpers.callMethod(current, "isTaskbar") as? Boolean
                                }.getOrNull() == true
                            ) return
                            val constructor = current.javaClass.declaredConstructors.firstOrNull {
                                it.parameterTypes.size == 4 &&
                                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                                    it.parameterTypes[2] == Boolean::class.javaPrimitiveType &&
                                    it.parameterTypes[3] == Boolean::class.javaPrimitiveType
                            } ?: return
                            val style = runCatching {
                                XposedHelpers.callMethod(current, "getStyle")
                            }.getOrNull() ?: return
                            val defaultHome = runCatching {
                                XposedHelpers.callMethod(current, "isDefaultHome") as? Boolean
                            }.getOrNull() ?: true
                            val fitDisplay = runCatching {
                                XposedHelpers.callMethod(current, "isFitToActiveDisplay") as? Boolean
                            }.getOrNull() ?: false
                            constructor.isAccessible = true
                            param.result = constructor.newInstance(true, style, defaultHome, fitDisplay)
                        }
                    })
                }
        }

        XposedHelpers.findClassIfExists("kotlinx.coroutines.flow.StateFlowImpl", classLoader)
            ?.declaredMethods
            ?.filter { it.name == "setValue" && it.parameterCount == 1 }
            ?.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (
                            param.args.firstOrNull() == false &&
                            param.thisObject in fullDexTaskbarFlows &&
                            fullDexActive()
                        ) {
                            param.args[0] = true
                        }
                    }
                })
            }
        CoverRuntime.log(SCOPE, "optional full cover DeX bridge installed")
    }

    private fun fullDexActive(instance: Any? = null): Boolean {
        val context = launcherContext()
        val enabled = context?.let(com.flexunlock.dexlsp.config.CoverDisplayConfig::readFullDex) == true
        return (instance == null || isFullDexLauncherInstance(instance)) && enabled
    }

    private fun fullDexTaskbarActive(instance: Any? = null): Boolean =
        fullDexActive() &&
            isCoverSecondaryLauncherActive() &&
            (instance == null || isDexTaskbarController(instance))

    private fun isDexTaskbarController(instance: Any): Boolean {
        val tag = runCatching { XposedHelpers.callMethod(instance, "getTAG") as? String }
            .getOrNull()
            ?: getFieldOrNull(instance, "TAG") as? String
        return tag?.endsWith("@Dex") == true
    }

    private fun refreshFullDexTaskbarState(enabled: Boolean): Int {
        val controllers = synchronized(fullDexTaskbarControllers) {
            fullDexTaskbarControllers.toList()
        }
        return controllers.count { controller ->
            val windowContext = getFieldOrNull(controller, "windowContext") as? Context
                ?: return@count false
            if (
                !isDexTaskbarController(controller) ||
                !CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(windowContext))
            ) return@count false
            XposedHelpers.callMethod(controller, "updateTaskbarAvailable", enabled)
            true
        }
    }

    private fun setFlowValueIfChanged(flow: Any, value: Boolean) {
        val current = runCatching { XposedHelpers.callMethod(flow, "getValue") }.getOrNull()
        if (current == value) return
        runCatching { XposedHelpers.callMethod(flow, "setValue", value) }
    }

    private fun compactCoverUiActive(): Boolean =
        coverCompactLauncherUiEnabled(fullDexActive())

    private fun launcherContext(): Context? =
        resumedSecondaryLauncher.get()?.applicationContext ?: AndroidAppHelper.currentApplication()

    private fun isDexHoneySpaceInstance(instance: Any): Boolean {
        val tag = runCatching { XposedHelpers.callMethod(instance, "getTAG") as? String }.getOrNull()
            ?: getFieldOrNull(instance, "TAG") as? String
        if (tag?.endsWith("@Dex") == true) return true
        if (tag?.endsWith("@OneUI") == true) {
            return isCoverSecondaryLauncherActive()
        }
        val spaceInfo = getFieldOrNull(instance, "spaceInfo")
        if (spaceInfo != null) {
            val isDex = runCatching {
                XposedHelpers.callMethod(spaceInfo, "isDexSpace") as? Boolean
            }.getOrNull() == true
            val name = runCatching { XposedHelpers.callMethod(spaceInfo, "getName") as? String }
                .getOrNull()
            if (isDex || name == "Dex") return true
        }
        val combined = getFieldOrNull(instance, "combinedDexInfo") ?: return false
        return combined !== instance && isDexHoneySpaceInstance(combined)
    }

    private fun isFullDexLauncherInstance(instance: Any): Boolean =
        isDexHoneySpaceInstance(instance) ||
            isCoverSecondaryLauncherActive()

    private fun isCoverSecondaryLauncherActive(): Boolean =
        resumedSecondaryLauncher.get()?.let { activity ->
            isCoverSecondaryLauncher(activity) && !activity.isFinishing && !activity.isDestroyed
        } == true

    private fun isRecentsForeground(): Boolean = runCatching {
        val context = launcherContext() ?: return@runCatching false
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return@runCatching false
        activityManager.getRunningTasks(8).any { task ->
            task.topActivity?.className == RECENTS_ACTIVITY_CLASS
        }
    }.getOrDefault(false)

    private fun installFullDexOverlayAppsLayout(classLoader: ClassLoader) {
        val containerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.honeypots.overlayapps.presentation.OverlayAppsContainer",
            classLoader
        ) ?: return unavailable("full DeX app drawer layout", "OverlayAppsContainer missing")
        runCatching {
            XposedBridge.hookAllMethods(
                containerClass,
                "setChild",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val root = param.thisObject as? ViewGroup ?: return
                        if (!fullDexCoverEnabled(root.context)) return
                        val apply = { applyFullDexOverlayAppsLayout(root) }
                        if (fullDexOverlayLayoutHosts.add(root)) {
                            root.post(apply)
                        }
                        apply()
                    }
                }
            )
            XposedBridge.hookAllMethods(
                containerClass,
                "setSearchBarContainer",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val root = param.thisObject as? ViewGroup ?: return
                        if (!fullDexCoverEnabled(root.context)) return
                        root.post { applyFullDexOverlayAppsLayout(root) }
                    }
                }
            )
            val verticalContainerClass = XposedHelpers.findClassIfExists(
                "com.honeyspace.ui.honeypots.verticalapplist.presentation.VerticalApplistContainer",
                classLoader
            )
            if (verticalContainerClass != null) {
                XposedBridge.hookAllMethods(
                    verticalContainerClass,
                    "setViewModel",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val container = param.thisObject as? ViewGroup ?: return
                            if (!fullDexCoverEnabled(container.context)) return
                            bindFullDexVerticalContainerLayout(container)
                            container.post {
                                (container.rootView as? ViewGroup)?.let(::applyFullDexOverlayAppsLayout)
                                applyFullDexVerticalContainerLayout(container)
                            }
                        }
                    }
                )
            }
            CoverRuntime.log(SCOPE, "display-1 full DeX app drawer layout installed")
        }.onFailure { unavailable("full DeX app drawer layout", it.message) }
    }

    private fun installCoverEditDisableHook(classLoader: ClassLoader) {
        val editDisableClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.common.util.EditDisableToast",
            classLoader
        ) ?: return unavailable("cover screen edit mode", "EditDisableToast missing")
        XposedBridge.hookAllMethods(
            editDisableClass,
            "isEditDisable",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = param.args.firstOrNull() as? Context ?: return
                    if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return
                    val rotation = context.display?.rotation ?: return
                    val rotated = rotation == android.view.Surface.ROTATION_90 ||
                        rotation == android.view.Surface.ROTATION_270 ||
                        context.resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE
                    if (rotated) {
                        param.result = false
                    }
                }
            }
        )
        CoverRuntime.log(SCOPE, "cover screen edit mode enabled for rotated drawers")
    }

    private fun installFullDexOverlayIconLayout(classLoader: ClassLoader) {
        val iconViewClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.common.iconview.IconViewImpl",
            classLoader
        ) ?: return unavailable("full DeX app drawer icons", "IconViewImpl missing")
        runCatching {
            iconViewClass.declaredMethods
                .filter { it.name == "setIconIntoPosition" && it.parameterTypes.firstOrNull() == Drawable::class.java }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            if (!isFullDexOverlayDrawerItem(view)) return
                            val drawable = param.args.firstOrNull() as? Drawable ?: return
                            resizeHomeIcon(view, drawable)
                        }
                    })
                }
            iconViewClass.declaredMethods
                .filter { it.name == "onLayout" && it.parameterCount == 5 }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as? View ?: return
                            if (!isFullDexOverlayDrawerItem(view)) return
                            val drawable = runCatching {
                                XposedHelpers.callMethod(view, "getIcon") as? Drawable
                            }.getOrNull() ?: return
                            resizeHomeIcon(view, drawable)
                        }
                    })
                }
            CoverRuntime.log(SCOPE, "display-1 full DeX app drawer icons installed")
        }.onFailure { unavailable("full DeX app drawer icons", it.message) }
    }

    private fun installFullDexVerticalWorkTab(classLoader: ClassLoader) {
        val viewModelClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.honeypots.verticalapplist.viewmodel.VerticalApplistViewModel",
            classLoader
        ) ?: return unavailable("full DeX app drawer work tab", "VerticalApplistViewModel missing")
        runCatching {
            viewModelClass.declaredMethods
                .filter { it.name == "O" && it.parameterCount == 1 }
                .forEach { method ->
                    method.isAccessible = true
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val viewModel = param.thisObject
                            val context = getFieldOrNull(viewModel, "g") as? Context ?: return
                            if (!fullDexCoverEnabled(context)) return
                            val stateFlow = getFieldOrNull(viewModel, "r0") ?: return
                            runCatching { XposedHelpers.callMethod(stateFlow, "setValue", true) }
                            val height = runCatching {
                                (XposedHelpers.callMethod(viewModel, "t") as Number).toInt()
                            }.getOrDefault(0)
                            if (height > 0) {
                                getFieldOrNull(viewModel, "f14270l0")?.let {
                                    runCatching { XposedHelpers.callMethod(it, "setValue", height) }
                                }
                            }
                        }
                    })
                }
            CoverRuntime.log(SCOPE, "display-1 full DeX app drawer work tab installed")
        }.onFailure { unavailable("full DeX app drawer work tab", it.message) }
    }

    private fun fullDexSettingEnabled(context: Context?): Boolean =
        context != null &&
            com.flexunlock.dexlsp.config.CoverDisplayConfig.readFullDex(context)

    private fun fullDexCoverEnabled(context: Context?): Boolean =
        fullDexSettingEnabled(context) &&
            CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context)) &&
            !isScrcpyDisplay(context)

    private fun isScrcpyDisplay(context: Context?): Boolean = runCatching {
        val display = context?.display ?: return@runCatching false
        (XposedHelpers.callMethod(display, "getUniqueId") as? String)
            ?.startsWith("virtual:com.android.shell,2000,scrcpy,", true) == true
    }.getOrDefault(false)

    private fun applyFullDexOverlayAppsLayout(root: ViewGroup) {
        val overlayList = findViewByEntryName(root, "overlay_apps_list") ?: return
        val container = findViewByEntryName(root, "vertical_applist_container") as? ViewGroup
            ?: return
        bindFullDexVerticalContainerLayout(container)
        val viewport = root.rootView
        if (root.isAttachedToWindow && root.display?.rotation == android.view.Surface.ROTATION_0 &&
            viewport.width > 0 && viewport.height > 0
        ) {
            val navigationInset = root.rootWindowInsets?.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars()
            )?.bottom ?: 0
            val width = viewport.width
            val height = (viewport.height - navigationInset).coerceAtLeast(1)
            for (view in listOf(overlayList, container)) {
                val params = view.layoutParams ?: continue
                if (params.width != width || params.height != height) {
                    params.width = width
                    params.height = height
                    view.layoutParams = params
                }
            }
            if (overlayList.isShown) {
                overlayList.x = 0f
                overlayList.y = 0f
            }
        }
        if (overlayList.height > 0) applyFullDexVerticalContainerLayout(container)
    }

    private fun bindFullDexVerticalContainerLayout(container: ViewGroup) {
        if (!fullDexVerticalLayoutHosts.add(container)) return
        container.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (!fullDexCoverEnabled(view.context)) return@addOnLayoutChangeListener
            layoutFullDexVerticalContainer(view as ViewGroup)
        }
        container.postDelayed({
            (container.rootView as? ViewGroup)?.let(::applyFullDexOverlayAppsLayout)
            layoutFullDexVerticalContainer(container)
            findViewByEntryName(container, "vertical_applist_recycler_view")?.let { list ->
                runCatching { XposedHelpers.callMethod(list, "scrollToPosition", 0) }
            }
        }, 160L)
        container.postDelayed({
            (container.rootView as? ViewGroup)?.let(::applyFullDexOverlayAppsLayout)
            layoutFullDexVerticalContainer(container)
        }, 320L)
        container.postDelayed({
            layoutFullDexVerticalContainer(container)
            val list = findViewByEntryName(container, "vertical_applist_recycler_view")
            val tab = findViewByEntryName(container, "vertical_apps_change_page_mode_button")
            val workspace = tab?.let { findViewByEntryName(it, "workspace_tab_container") }
            val children = (workspace as? ViewGroup)?.let { group ->
                (0 until group.childCount).joinToString { index ->
                    group.getChildAt(index).let { child ->
                        "${child.javaClass.simpleName}:${child.left},${child.top}," +
                            "${child.right},${child.bottom}/y=${child.y}/ty=${child.translationY}/" +
                            "sx=${child.scaleX}/sy=${child.scaleY}/a=${child.alpha}"
                    }
                }
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 full DeX app drawer settled " +
                    "list=${list?.top},${list?.bottom} y=${list?.y} ty=${list?.translationY} " +
                    "paddingTop=${list?.paddingTop} " +
                    "tab=${tab?.top},${tab?.bottom} y=${tab?.y} visibility=${tab?.visibility} " +
                    "workspace=${workspace?.top},${workspace?.bottom} y=${workspace?.y} " +
                    "ty=${workspace?.translationY} alpha=${workspace?.alpha} children=[$children]"
            )
        }, 640L)
        CoverRuntime.log(SCOPE, "display-1 full DeX app drawer final layout bound")
    }

    private fun applyFullDexVerticalContainerLayout(container: ViewGroup) {
        val list = findViewByEntryName(container, "vertical_applist_recycler_view") as? ViewGroup
            ?: return
        if (!list.javaClass.name.endsWith("VerticalApplistRecyclerView")) return
        val workTab = findViewByEntryName(container, "vertical_apps_change_page_mode_button")
            ?: return
        if (container.display?.rotation == android.view.Surface.ROTATION_0) {
            val availableWidth = container.width.coerceAtLeast(1)
            val searchTop = findViewByEntryName(container.rootView, "overlay_apps_search_bar_container")
                ?.top ?: container.height
            val availableHeight = (searchTop - list.top).coerceAtLeast(1)
            list.layoutParams?.let { params ->
                if (params.width != availableWidth || params.height != availableHeight) {
                    params.width = availableWidth
                    params.height = availableHeight
                    list.layoutParams = params
                }
            }
        }
        fullDexWorkTabBaselines.getOrPut(workTab) {
            FullDexWorkTabBaseline(workTab.visibility, workTab.alpha)
        }
        workTab.visibility = View.VISIBLE
        workTab.alpha = 1f
        workTab.elevation = maxOf(workTab.elevation, 100f)
        workTab.translationZ = maxOf(workTab.translationZ, 100f)
        workTab.translationY = 0f
        workTab.y = 0f
        showFullDexWorkTab(workTab)

        val density = list.resources.displayMetrics.density
        val tabHeight = maxOf(
            workTab.measuredHeight,
            workTab.layoutParams?.height ?: 0,
            (44f * density).toInt()
        )
        val baseline = fullDexDrawerBaselines.getOrPut(list) {
            FullDexDrawerBaseline(
                list.paddingLeft,
                list.paddingTop,
                list.paddingRight,
                list.paddingBottom,
                list.clipToPadding
            )
        }
        val safeInset = maxOf(
            (8f * density).toInt(),
            list.rootWindowInsets?.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )?.left ?: 0
        )
        val workspace = findViewByEntryName(workTab, "workspace_tab_container")
        val targetTop = workspace?.let {
            val listLocation = IntArray(2).also(list::getLocationOnScreen)
            val workspaceLocation = IntArray(2).also(it::getLocationOnScreen)
            (workspaceLocation[1] + it.height + COVER_DRAWER_TAB_APP_GAP_PX - listLocation[1])
                .coerceAtLeast(0)
        }?.coerceAtLeast(baseline.paddingTop) ?: maxOf(baseline.paddingTop, tabHeight)
        val targetLeft = maxOf(baseline.paddingLeft, safeInset)
        val targetRight = maxOf(baseline.paddingRight, safeInset)
        val targetBottom = coverDrawerBottomPaddingPx(
            list.display?.rotation ?: 0,
            baseline.paddingBottom,
            list.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        )
        val layoutChanged = list.paddingLeft != targetLeft ||
            list.paddingTop != targetTop ||
            list.paddingRight != targetRight ||
            list.paddingBottom != targetBottom ||
            list.clipToPadding
        if (layoutChanged) {
            list.setPadding(targetLeft, targetTop, targetRight, targetBottom)
            list.clipToPadding = false
        }
        list.translationY = 0f
        val manager = runCatching { XposedHelpers.callMethod(list, "getLayoutManager") }.getOrNull()
        val contentWidth = (list.width - targetLeft - targetRight).coerceAtLeast(1)
        val minCellWidth = (56f * density).toInt().coerceAtLeast(1)
        val columns = (contentWidth / minCellWidth).coerceIn(
            FULL_DEX_DRAWER_MIN_COLUMNS,
            FULL_DEX_DRAWER_MAX_COLUMNS
        )
        runCatching { XposedHelpers.callMethod(manager, "setSpanCount", columns) }
        if (layoutChanged) list.requestLayout()
        list.invalidate()
        if (layoutChanged) {
            CoverRuntime.log(
                SCOPE,
                "display-1 full DeX app drawer geometry " +
                    "grid=$columns columns top=$targetTop horizontal=$targetLeft/$targetRight"
            )
        }
    }

    private fun layoutFullDexVerticalContainer(container: ViewGroup) {
        val list = findViewByEntryName(container, "vertical_applist_recycler_view") as? ViewGroup
            ?: return
        val workTab = findViewByEntryName(container, "vertical_apps_change_page_mode_button")
            ?: return
        workTab.visibility = View.VISIBLE
        workTab.alpha = 1f
        showFullDexWorkTab(workTab)
        workTab.bringToFront()
        applyFullDexVerticalContainerLayout(container)
    }

    private fun refreshFullDexDrawerState(enabled: Boolean) {
        if (enabled) {
            synchronized(fullDexVerticalLayoutHosts) {
                fullDexVerticalLayoutHosts.toList()
            }.filterIsInstance<ViewGroup>().forEach { container ->
                container.post { applyFullDexVerticalContainerLayout(container) }
            }
            return
        }
        synchronized(fullDexDrawerBaselines) {
            fullDexDrawerBaselines.toList()
        }.forEach { (list, baseline) ->
            list.setPadding(
                baseline.paddingLeft,
                baseline.paddingTop,
                baseline.paddingRight,
                baseline.paddingBottom
            )
            list.clipToPadding = baseline.clipToPadding
            list.translationY = 0f
            list.requestLayout()
        }
        synchronized(fullDexWorkTabBaselines) {
            fullDexWorkTabBaselines.toList()
        }.forEach { (tab, baseline) ->
            tab.visibility = baseline.visibility
            tab.alpha = baseline.alpha
        }
    }

    private fun showFullDexWorkTab(workTab: View) {
        findViewByEntryName(workTab, "backgroundContainer")?.let { background ->
            background.visibility = View.VISIBLE
            background.alpha = 1f
        }
        findViewByEntryName(workTab, "workspace_tab_container")?.let { workspace ->
            showViewTree(workspace)
        }
        workTab.invalidate()
    }

    private fun showViewTree(view: View) {
        view.visibility = View.VISIBLE
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) showViewTree(group.getChildAt(index))
    }

    private fun findViewByEntryName(root: View, entryName: String): View? {
        val currentName = runCatching {
            if (root.id == View.NO_ID) "" else root.resources.getResourceEntryName(root.id)
        }.getOrDefault("")
        if (currentName == entryName) return root
        val group = root as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findViewByEntryName(group.getChildAt(index), entryName)?.let { return it }
        }
        return null
    }

    private fun findAncestorByEntryName(view: View, entryName: String): View? {
        var current = view.parent as? View
        while (current != null) {
            val name = runCatching {
                if (current.id == View.NO_ID) "" else current.resources.getResourceEntryName(current.id)
            }.getOrDefault("")
            if (name == entryName) return current
            current = current.parent as? View
        }
        return null
    }

    private fun methodsInHierarchy(clazz: Class<*>): List<java.lang.reflect.Method> = buildList {
        val signatures = hashSetOf<String>()
        var current: Class<*>? = clazz
        while (current != null) {
            current.declaredMethods.forEach { method ->
                val signature = method.name + method.parameterTypes.joinToString { it.name }
                if (signatures.add(signature)) add(method)
            }
            current = current.superclass
        }
    }

    private fun installEarlyWorkspaceReceiver() {
        AndroidAppHelper.currentApplication()?.let { application ->
            registerCoverWorkspaceStateRequestReceiver(application)
            registerFullDexChangeReceiver(application)
            registerCoverIconSizeReceiver(application)
        }
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args.firstOrNull() as? Context ?: return
                        registerCoverWorkspaceStateRequestReceiver(
                            context.applicationContext ?: context
                        )
                        registerFullDexChangeReceiver(context.applicationContext ?: context)
                        registerCoverIconSizeReceiver(context.applicationContext ?: context)
                    }
                }
            )
            CoverRuntime.log(SCOPE, "HoneySpace transition receiver preinstalled at Application.attach")
        }.onFailure {
            unavailable("early HoneySpace transition receiver", it.message)
        }
    }

    private fun installCoverDrawerPortraitWorkTab(classLoader: ClassLoader) {
        // Cover drawer in portrait uses AppScreen=Grid where Samsung never calls
        // ApplistViewModel.w0() (showWorkTab), so the personal/work tabs never render.
        // In landscape AppScreen=Normal and the tabs appear. Force the WorkTab state
        // (O0) + tab height whenever the drawer ViewModel is accessed, so the drawer
        // shows the native personal/work tabs in portrait too.
        val potClass = XposedHelpers.findClassIfExists("p4.Y", classLoader)
            ?: return unavailable("cover portrait work tab", "ApplistPot missing")
        val viewModelClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.honeypots.applist.viewmodel.ApplistViewModel",
            classLoader
        ) ?: return unavailable("cover portrait work tab", "ApplistViewModel missing")

        runCatching {
            val viewModelAccessMethods = potClass.declaredMethods.filter { method ->
                method.name == "e" &&
                    method.parameterCount == 0 &&
                    viewModelClass.isAssignableFrom(method.returnType)
            }
            viewModelAccessMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!compactCoverUiActive()) return
                        try {
                            val viewModel = param.result?.takeIf(viewModelClass::isInstance)
                                ?: return
                            val stateFlow = XposedHelpers.getObjectField(viewModel, "O0")
                            val current =
                                XposedHelpers.callMethod(stateFlow, "getValue") as? Boolean
                            if (current != true) {
                                XposedHelpers.callMethod(stateFlow, "setValue", true)
                                CoverRuntime.log(
                                    SCOPE,
                                    "cover drawer work tab state forced for portrait grid"
                                )
                            }
                            val tabManager = XposedHelpers.callMethod(viewModel, "J")
                            runCatching {
                                XposedHelpers.callMethod(tabManager, "h", false)
                            }
                            scheduleCoverWorkTabLayout(param.thisObject)
                        } catch (t: Throwable) {
                            CoverRuntime.log(SCOPE, "cover work tab hook: ${t.message}")
                        }
                    }
                })
            }
            CoverRuntime.log(SCOPE, "cover drawer portrait work-tab hook installed")
        }.onFailure { unavailable("cover portrait work tab", it.message) }
    }

    private fun installCoverDrawerCompactSearch(classLoader: ClassLoader) {
        val landDetectorClass = XposedHelpers.findClassIfExists("v4.f", classLoader)
            ?: return unavailable("cover compact search", "v4.f missing")
        runCatching {
            val detectMethods = landDetectorClass.declaredMethods.filter { method ->
                method.name == "b" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == Context::class.java &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }
            detectMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args.firstOrNull() as? Context ?: return
                        if (!usesCoverPortraitCompactSearch(context)) return
                        param.result = true
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "cover drawer compact search installed detectMethods=${detectMethods.size}"
            )
        }.onFailure { unavailable("cover compact search", it.message) }
    }

    private fun usesCoverPortraitCompactSearch(context: Context): Boolean =
        compactCoverUiActive() &&
            CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context)) &&
            context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    private fun scheduleCoverWorkTabLayout(owner: Any) {
        if (!scheduledCoverWorkTabLayouts.add(owner)) return
        val handler = Handler(Looper.getMainLooper())

        fun applyWhenReady(remainingAttempts: Int) {
            handler.postDelayed(
                {
                    val tabLayout = runCatching {
                        val manager = XposedHelpers.callMethod(owner, "i")
                        XposedHelpers.callMethod(manager, "b") as? View
                    }.getOrNull()
                    if (tabLayout != null) {
                        applyFullQsWorkTabInset(tabLayout)
                        CoverRuntime.log(
                            SCOPE,
                            "cover tabLayout vis=${tabLayout.visibility} " +
                                "h=${tabLayout.height} parent=" +
                                "${tabLayout.parent?.javaClass?.simpleName}"
                        )
                        if (tabLayout.visibility != View.VISIBLE) {
                            tabLayout.visibility = View.VISIBLE
                            tabLayout.requestLayout()
                            CoverRuntime.log(SCOPE, "cover drawer tab layout forced visible")
                        }
                        return@postDelayed
                    }
                    if (remainingAttempts > 1) {
                        applyWhenReady(remainingAttempts - 1)
                    } else {
                        unavailable(
                            "cover portrait work tab layout",
                            "layout unavailable after initialization window"
                        )
                    }
                },
                if (remainingAttempts == COVER_WORK_TAB_RETRY_ATTEMPTS) {
                    0L
                } else {
                    COVER_WORK_TAB_RETRY_DELAY_MS
                }
            )
        }

        applyWhenReady(COVER_WORK_TAB_RETRY_ATTEMPTS)
    }

    private fun applyFullQsWorkTabInset(tabLayout: View) {
        val host = findAncestorByEntryName(tabLayout, "apps_change_page_mode_button")
            ?: tabLayout
        val stableFull = CoverQsModeConfig.readTransaction(host.context).isStableFull
        if (
            XposedHelpers.getAdditionalInstanceField(host, "flexunlock_full_qs_work_tab_inset") != true
        ) {
            XposedHelpers.setAdditionalInstanceField(
                host,
                "flexunlock_full_qs_work_tab_inset",
                true
            )
            host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                applyFullQsWorkTabInset(tabLayout)
            }
        }
        if (!stableFull) {
            val hadFullTarget = XposedHelpers.getAdditionalInstanceField(
                host,
                "flexunlock_full_qs_work_tab_inset_target"
            ) != null
            XposedHelpers.removeAdditionalInstanceField(
                host,
                "flexunlock_full_qs_work_tab_inset_target"
            )
            XposedHelpers.removeAdditionalInstanceField(
                host,
                "flexunlock_full_qs_work_tab_inset_rotation"
            )
            if (host.translationY != 0f) host.translationY = 0f
            if (hadFullTarget) {
                findViewByClassName(host.rootView, "ApplistFastRecyclerView")?.let(
                    ::scheduleCoverDrawerSpacing
                )
            }
            return
        }

        val rotation = host.display?.rotation ?: android.view.Surface.ROTATION_0
        val target = run {
            val insets = host.rootWindowInsets ?: host.rootView.rootWindowInsets
            val statusInset = insets
                ?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                ?.top ?: 0
            val cutoutInset = insets
                ?.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
                ?.top ?: 0
            val statusBarHeightId = host.resources.getIdentifier(
                "status_bar_height",
                "dimen",
                "android"
            )
            val displayCutoutInset = host.display?.cutout?.let {
                maxOf(it.safeInsetTop, it.safeInsetBottom)
            } ?: 0
            val statusBarHeight = maxOf(
                statusInset,
                cutoutInset,
                displayCutoutInset,
                if (statusBarHeightId != 0) {
                    host.resources.getDimensionPixelSize(statusBarHeightId)
                } else {
                    0
                }
            )
            // Avoid writing a zero target while Launcher is still attaching.
            if (statusBarHeight <= 0) return
            val nativeTop = tabLayout.top
            coverDrawerWorkTabTranslationPx(statusBarHeight, nativeTop).also { value ->
                XposedHelpers.setAdditionalInstanceField(
                    host,
                    "flexunlock_full_qs_work_tab_inset_rotation",
                    rotation
                )
                XposedHelpers.setAdditionalInstanceField(
                    host,
                    "flexunlock_full_qs_work_tab_inset_target",
                    value
                )
            }
        }
        if (host.translationY != target.toFloat()) {
            host.translationY = target.toFloat()
            CoverRuntime.log(SCOPE, "cover drawer work tab top inset=$target")
            findViewByClassName(host.rootView, "ApplistFastRecyclerView")?.let(
                ::scheduleCoverDrawerSpacing
            )
        }
    }

    private fun installSecondaryLauncherOrientationPolicy(classLoader: ClassLoader) {
        if (XposedHelpers.findClassIfExists(SECONDARY_LAUNCHER_CLASS, classLoader) == null) {
            return unavailable("SecondaryLauncher orientation policy", "class missing")
        }

        runCatching {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "setRequestedOrientation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!isCoverRotatableHome(activity)) return
                        // Respect the saved user angle when locked; otherwise follow the sensor.
                        val locked = runCatching {
                            android.provider.Settings.System.getInt(
                                activity.contentResolver,
                                android.provider.Settings.System.ACCELEROMETER_ROTATION,
                                1
                            ) == 0
                        }.getOrDefault(false)
                        param.args[0] = coverHomeOrientation(locked)
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 SecondaryLauncher orientation=" +
                                (if (locked) "locked" else "free")
                        )
                    }
                }
            )
            // Re-apply the current lock state whenever the cover desktop is
            // resumed, and watch the system rotation-lock setting so toggling
            // it from the QS tile takes effect immediately.
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!isCoverRotatableHome(activity)) return
                        if (XposedHelpers.getAdditionalInstanceField(
                                activity,
                                "flexunlock_rotation_observer"
                            ) != true
                        ) {
                            XposedHelpers.setAdditionalInstanceField(
                                activity,
                                "flexunlock_rotation_observer",
                                true
                            )
                            val handler = android.os.Handler(
                                android.os.Looper.getMainLooper()
                            )
                            val observer =
                                object : android.database.ContentObserver(handler) {
                                    override fun onChange(selfChange: Boolean) {
                                        val locked = runCatching {
                                            android.provider.Settings.System.getInt(
                                                activity.contentResolver,
                                                android.provider.Settings.System
                                                    .ACCELEROMETER_ROTATION,
                                                1
                                            ) == 0
                                        }.getOrDefault(false)
                                        if (activity.isDestroyed) return
                                        val target = coverHomeOrientation(locked)
                                        if (activity.requestedOrientation != target) {
                                            activity.requestedOrientation = target
                                        }
                                        CoverRuntime.log(
                                            SCOPE,
                                            "display-1 SecondaryLauncher rotation-lock change applied=" +
                                                (if (locked) "locked" else "free")
                                        )
                                    }
                                }
                            activity.contentResolver.registerContentObserver(
                                android.provider.Settings.System.getUriFor(
                                    android.provider.Settings.System.ACCELEROMETER_ROTATION
                                ),
                                false,
                                observer
                            )
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 SecondaryLauncher rotation-lock observer installed"
                            )
                        }
                        val locked = runCatching {
                            android.provider.Settings.System.getInt(
                                activity.contentResolver,
                                android.provider.Settings.System.ACCELEROMETER_ROTATION,
                                1
                            ) == 0
                        }.getOrDefault(false)
                        val target = coverHomeOrientation(locked)
                        runCatching {
                            if (activity.requestedOrientation != target) {
                                activity.requestedOrientation = target
                            }
                        }
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 SecondaryLauncher resume applied orientation=" +
                                (if (locked) "locked" else "free")
                        )
                    }
                }
            )
            CoverRuntime.log(SCOPE, "display-1 SecondaryLauncher orientation policy installed")
        }.onFailure { unavailable("SecondaryLauncher orientation policy", it.message) }
    }

    private fun installSecondaryLauncherWorkspaceLifecycle(classLoader: ClassLoader) {
        if (
            XposedHelpers.findClassIfExists(SECONDARY_LAUNCHER_CLASS, classLoader) == null
        ) return unavailable("SecondaryLauncher workspace lifecycle", "class missing")

        runCatching {
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (isScrcpySecondaryLauncher(activity)) {
                            bindNativeHomeTransitionRegistration(
                                activity,
                                "scrcpy-secondary-launcher-resume"
                            )
                            return
                        }
                        if (!isCoverSecondaryLauncher(activity)) return
                        resumedSecondaryLauncher = WeakReference(activity)
                        secondaryLauncherResumed = true
                        secondaryLauncherFocused = activity.hasWindowFocus()
                        if (fullDexActive()) {
                            applyFullDexStatusBar(activity, true)
                            activity.window.decorView.post {
                                if (activity.isFinishing || activity.isDestroyed) return@post
                                refreshFullDexTaskbarState(true)
                                refreshFullDexDrawerState(true)
                                activity.window.decorView.requestLayout()
                            }
                            // Native DeX owns the workspace, drawer and taskbar state.
                            // Do not reconcile it through the compact cover launcher path.
                            return
                        }
                        applyFullDexStatusBar(activity, false)
                        makeCoverStatusBarTransparent(activity)
                        clearCoverScreenPresentation("RECENTS")
                        activity.window.decorView.post {
                            if (activity.isFinishing || activity.isDestroyed) return@post
                            registerCoverIconSizeReceiver(
                                activity.applicationContext ?: activity
                            )
                            bindCoverDrawerMenuLayout(activity)
                            applyCoverDrawerMenuLayout(activity)
                            captureCoverQuickOptionUtil(activity.classLoader)
                            captureCoverScreenManager(activity)
                            bindNativeHomeTransitionRegistration(
                                activity,
                                "secondary-launcher-resume"
                            )
                            publishAllCoverWorkspaceStates("launcher-resume")
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onWindowFocusChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!isCoverSecondaryLauncher(activity)) return
                        if (resumedSecondaryLauncher.get() !== activity) return
                        if (fullDexActive()) {
                            applyFullDexStatusBar(activity, true)
                            if (param.args.firstOrNull() == true) {
                                refreshFullDexTaskbarState(true)
                            }
                            return
                        }
                        secondaryLauncherFocused = param.args.firstOrNull() == true
                        if (secondaryLauncherFocused) {
                            makeCoverStatusBarTransparent(activity)
                        }
                        if (secondaryLauncherFocused && !coverRecentsDispatchCommitted) {
                            completeCoverGestureTransition("launcher-focus-restored")
                        }
                        if (
                            secondaryLauncherFocused &&
                            latestShellTransitionActive == false &&
                            SystemClock.elapsedRealtime() - latestShellTransitionElapsed <=
                            CACHED_SHELL_TRANSITION_REPLAY_MS
                        ) {
                            coverScreenManagers.firstOrNull()?.let { manager ->
                                reconcileCoverWorkspaceToHome(
                                    manager,
                                    latestShellTransitionReason ?: "cached-shell-transition-finished"
                                )
                            }
                        }
                        applyCoverDrawerMenuLayout(activity)
                        publishAllCoverWorkspaceStates("launcher-focus")
                    }
                }
            )
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onConfigurationChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!isCoverSecondaryLauncher(activity)) return
                        val fullDex = fullDexSettingEnabled(activity)
                        applyFullDexStatusBar(activity, fullDex)
                        if (!fullDex) {
                            activity.window.decorView.postDelayed({
                                if (!activity.isFinishing && !activity.isDestroyed) {
                                    applyFullDexStatusBar(activity, false)
                                    makeCoverStatusBarTransparent(activity)
                                }
                            }, 250L)
                        }
                    }
                }
            )
            listOf("onPause", "onDestroy").forEach { methodName ->
                XposedBridge.hookAllMethods(
                    Activity::class.java,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            if (methodName == "onDestroy" && isScrcpySecondaryLauncher(activity)) {
                                unbindNativeHomeTransitionRegistration(
                                    activity,
                                    "scrcpy-secondary-launcher-destroy"
                                )
                                return
                            }
                            if (resumedSecondaryLauncher.get() !== activity) return
                            if (fullDexActive()) return
                            if (methodName == "onDestroy") {
                                unbindNativeHomeTransitionRegistration(
                                    activity,
                                    "secondary-launcher-destroy"
                                )
                            }
                            secondaryLauncherResumed = false
                            secondaryLauncherFocused = false
                            synchronized(workspaceTransitionTargets) {
                                workspaceTransitionTargets.clear()
                            }
                            publishAllCoverWorkspaceStates("launcher-$methodName")
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            if (
                                methodName == "onDestroy" &&
                                resumedSecondaryLauncher.get() === activity
                            ) {
                                resumedSecondaryLauncher.clear()
                            }
                        }
                    }
                )
            }
            CoverRuntime.log(SCOPE, "display-1 SecondaryLauncher lifecycle gate installed")
        }.onFailure { unavailable("SecondaryLauncher workspace lifecycle", it.message) }

        runCatching {
            listOf("showAsDropDown", "showAtLocation").forEach { methodName ->
                XposedBridge.hookAllMethods(
                    PopupWindow::class.java,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val popup = param.thisObject as? PopupWindow ?: return
                            val content = popup.contentView ?: return
                            if (!CoverRuntime.isCoverDisplay(
                                    CoverRuntime.displayIdOf(content.context)
                                )
                            ) return
                            coverWorkspacePopups.add(popup)
                            publishAllCoverWorkspaceStates("menu-show")
                        }
                    }
                )
            }
            XposedBridge.hookAllMethods(
                PopupWindow::class.java,
                "dismiss",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val popup = param.thisObject as? PopupWindow ?: return
                        if (!coverWorkspacePopups.remove(popup)) return
                        publishAllCoverWorkspaceStates("menu-dismiss")
                    }
                }
            )
            CoverRuntime.log(SCOPE, "display-1 Launcher PopupWindow menu gate installed")
        }.onFailure { unavailable("Launcher PopupWindow menu gate", it.message) }

        captureCoverQuickOptionUtil(classLoader)
    }

    private fun installSecondaryLauncherStatusBarPolicy(classLoader: ClassLoader) {
        val systemUiControl = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.common.util.SystemUIControlUtils",
            classLoader
        ) ?: return unavailable("SecondaryLauncher status bar policy", "class missing")

        XposedBridge.hookAllMethods(
            systemUiControl,
            "updateSystemUI",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val window = param.args.firstOrNull() as? Window ?: return
                    val activity = listOfNotNull(
                        resumedSecondaryLauncher.get(),
                        resumedFullQsActivity.get()
                    ).firstOrNull { it.window === window } ?: return
                    if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity))) return
                    val supportedHost = isCoverSecondaryLauncher(activity) ||
                        (
                            activity.javaClass.name == LAUNCHER_ACTIVITY_CLASS &&
                                CoverQsModeConfig.readTransaction(activity).isStableFull
                            )
                    if (!supportedHost) return
                    if (activity.isFinishing || activity.isDestroyed || fullDexSettingEnabled(activity)) return
                    applyFullDexStatusBar(activity, false)
                }
            }
        )
        CoverRuntime.log(SCOPE, "display-1 SecondaryLauncher status bar policy installed")
    }

    private fun bindCoverDrawerMenuLayout(activity: Activity) {
        if (!coverDrawerMenuLayoutOwners.add(activity)) return
        activity.window.decorView.addOnLayoutChangeListener {
                _, _, _, _, _, _, _, _, _ ->
            applyCoverDrawerMenuLayout(activity)
        }
    }

    private fun applyCoverDrawerMenuLayout(activity: Activity) {
        if (!compactCoverUiActive() || !isCoverDrawerHost(activity)) return
        val resources = activity.resources
        val packageName = activity.packageName
        val root = activity.window.decorView
        val portraitMenuId = resources.getIdentifier(
            "more_icon_button",
            "id",
            packageName
        )
        val landscapeMenuId = resources.getIdentifier(
            "more_icon_button_land",
            "id",
            packageName
        )
        if (portraitMenuId == 0 || landscapeMenuId == 0) return
        val portraitMenu = root.findViewById<View>(portraitMenuId) ?: return
        val landscapeMenu = root.findViewById<View>(landscapeMenuId) ?: return
        val compactSearch = usesCoverPortraitCompactSearch(activity)
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        portraitMenu.visibility = if (landscape || compactSearch) View.GONE else View.VISIBLE
        landscapeMenu.visibility = if (landscape || compactSearch) View.VISIBLE else View.GONE
        if (landscape || compactSearch) {
            applyCoverDrawerSearchButtonSpacing(root, packageName)
        }
        if (compactSearch) {
            root.resources.getIdentifier("app_search_wrapper", "id", packageName)
                .takeIf { it != 0 }
                ?.let { root.findViewById<View>(it)?.visibility = View.GONE }
        }
    }

    private fun applyCoverDrawerSearchButtonSpacing(root: View, packageName: String) {
        val more = findCoverViewById(root, packageName, "more_icon_button_land") ?: return
        val search = findCoverViewById(root, packageName, "search_icon_button_land") ?: return
        val moreLayout = findCoverViewById(root, packageName, "more_icon_button_land_layout")
            ?: more
        val searchLayout = findCoverViewById(root, packageName, "search_icon_button_land_layout")
            ?: search
        val siblings = coverDrawerSearchLayoutSiblings(moreLayout, searchLayout) ?: return
        val top = if (siblings.first.top <= siblings.second.top) {
            siblings.first
        } else {
            siblings.second
        }
        val bottom = if (top === siblings.first) siblings.second else siblings.first
        listOf(top, bottom).forEach { view ->
            if (
                XposedHelpers.getAdditionalInstanceField(
                    view,
                    "flexunlock_drawer_search_spacing"
                ) != true
            ) {
                XposedHelpers.setAdditionalInstanceField(
                    view,
                    "flexunlock_drawer_search_spacing",
                    true
                )
                view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    root.post { applyCoverDrawerSearchButtonSpacing(root, packageName) }
                }
            }
        }
        if (top.height <= 0 || bottom.height <= 0) return
        val currentGap = bottom.top - top.bottom
        val params = bottom.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val targetMargin = coverDrawerSearchButtonTopMarginPx(
            currentGapPx = currentGap,
            targetGapPx = COVER_DRAWER_SEARCH_BUTTON_GAP_PX,
            currentTopMarginPx = params.topMargin
        )
        if (params.topMargin == targetMargin) return
        params.topMargin = targetMargin
        bottom.layoutParams = params
        CoverRuntime.log(
            SCOPE,
            "cover drawer search gap current=$currentGap target=$COVER_DRAWER_SEARCH_BUTTON_GAP_PX " +
                "margin=$targetMargin"
        )
    }

    private fun findCoverViewById(root: View, packageName: String, name: String): View? {
        val id = root.resources.getIdentifier(name, "id", packageName)
        return id.takeIf { it != 0 }?.let { root.findViewById(it) }
    }

    private fun coverDrawerSearchLayoutSiblings(first: View, second: View): Pair<View, View>? {
        val secondAncestors = generateSequence(second) { it.parent as? View }.toList()
        generateSequence(first) { it.parent as? View }.forEach { candidate ->
            val parent = candidate.parent as? View ?: return@forEach
            val other = secondAncestors.firstOrNull { it.parent === parent } ?: return@forEach
            if (candidate !== other) return candidate to other
        }
        return if (first.parent === second.parent && first !== second) first to second else null
    }

    private fun captureCoverQuickOptionUtil(classLoader: ClassLoader) {
        val activity = resumedSecondaryLauncher.get() ?: return
        val coverDisplayId = CoverDisplayResolver.currentId() ?: return
        if (
            activity.javaClass.name != SECONDARY_LAUNCHER_CLASS ||
            CoverRuntime.displayIdOf(activity) != coverDisplayId
        ) return
        runCatching {
            val entryPointClass = XposedHelpers.findClass(
                HONEYSPACE_COMPONENT_ENTRY_POINT_CLASS,
                classLoader
            )
            val componentManagerEntryPointClass = XposedHelpers.findClass(
                HONEY_GENERATED_COMPONENT_MANAGER_ENTRY_POINT_CLASS,
                classLoader
            )
            val contextExtensionClass = XposedHelpers.findClass(
                HONEYSPACE_CONTEXT_EXTENSION_CLASS,
                classLoader
            )
            val entryPointsClass = XposedHelpers.findClass(
                HILT_ENTRY_POINTS_CLASS,
                classLoader
            )
            val homeAppContext = XposedHelpers.callStaticMethod(
                contextExtensionClass,
                "getHomeAppContext",
                activity
            )
            val componentManagerEntryPoint = XposedHelpers.callStaticMethod(
                entryPointsClass,
                "get",
                homeAppContext,
                componentManagerEntryPointClass
            )
            val componentManager = XposedHelpers.callMethod(
                componentManagerEntryPoint,
                "getHoneySpaceComponent"
            )
            val displayComponent = XposedHelpers.callMethod(
                componentManager,
                "generatedComponent",
                coverDisplayId
            )
            val entryPoint = XposedHelpers.callStaticMethod(
                entryPointsClass,
                "get",
                displayComponent,
                entryPointClass
            )
            val quickOptionUtil = XposedHelpers.callMethod(entryPoint, "getQuickOptionUtil") ?: return
            coverQuickOptionUtils.add(quickOptionUtil)
            installQuickOptionUtilHooks(quickOptionUtil.javaClass)
        }.onFailure { unavailable("Launcher QuickOption menu gate", it.message) }
    }

    private fun installQuickOptionUtilHooks(utilClass: Class<*>) {
        if (!quickOptionUtilHookedClasses.add(utilClass)) return
        listOf("showForIcon", "close", "closeDockedTaskBarQuickOption").forEach { methodName ->
            XposedBridge.hookAllMethods(
                utilClass,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        coverQuickOptionUtils.add(param.thisObject)
                        publishAllCoverWorkspaceStates("quick-option-$methodName")
                    }
                }
            )
        }
        CoverRuntime.log(
            SCOPE,
            "display-1 QuickOption menu gate installed class=${utilClass.name}"
        )
    }

    private fun isCoverQuickOptionVisible(): Boolean = coverQuickOptionUtils.any { util ->
        runCatching {
            XposedHelpers.callMethod(util, "isShowQuickOption") == true ||
                XposedHelpers.callMethod(util, "isQuickOptionWindowOpen") == true
        }.getOrDefault(false)
    }

    private fun isCoverSecondaryLauncher(activity: Activity): Boolean =
        activity.javaClass.name == SECONDARY_LAUNCHER_CLASS &&
            CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity))

    private fun isScrcpySecondaryLauncher(activity: Activity): Boolean =
        activity.javaClass.name == SECONDARY_LAUNCHER_CLASS && isScrcpyDisplay(activity)

    private fun isManagedSecondaryLauncher(activity: Activity): Boolean =
        isCoverSecondaryLauncher(activity) || isScrcpySecondaryLauncher(activity)

    private fun isCoverRotatableHome(activity: Activity): Boolean =
        CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity)) &&
            (
                activity.javaClass.name == SECONDARY_LAUNCHER_CLASS ||
                    (
                        activity.javaClass.name == LAUNCHER_ACTIVITY_CLASS &&
                            CoverQsModeConfig.readTransaction(activity).isStableFull
                        )
                )

    private fun coverHomeOrientation(locked: Boolean): Int =
        if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
        else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

    private fun isCoverDrawerHost(activity: Activity): Boolean =
        CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity)) &&
            (
                activity.javaClass.name == SECONDARY_LAUNCHER_CLASS ||
                    (
                        activity.javaClass.name == LAUNCHER_ACTIVITY_CLASS &&
                            CoverQsModeConfig.readTransaction(activity).isStableFull
                        )
                )

    private fun isKeyguardLocked(): Boolean {
        val context = AndroidAppHelper.currentApplication() ?: return true
        return context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
    }

    private fun makeCoverStatusBarTransparent(activity: Activity) {
        activity.window?.let { window ->
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
            window.setDecorFitsSystemWindows(false)
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            window.attributes = attributes
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun registerCoverIconSizeReceiver(context: Context) {
        if (!coverIconSizeReceiverInstalled.compareAndSet(false, true)) return
        configuredCoverHomeIconSizePx = coverIconSizePx(context, COVER_ICON_SURFACE_HOME)
        configuredCoverDrawerIconSizePx = coverIconSizePx(context, COVER_ICON_SURFACE_DRAWER)
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action != ACTION_SET_COVER_ICON_SIZE) return
                        val surface = intent.getStringExtra(EXTRA_COVER_ICON_SURFACE) ?: return
                        val sizePx = intent.getIntExtra(EXTRA_COVER_ICON_SIZE_PX, Int.MIN_VALUE)
                        if (sizePx !in MIN_COVER_ICON_SIZE_PX..MAX_COVER_ICON_SIZE_PX) return
                        when (surface) {
                            COVER_ICON_SURFACE_HOME -> configuredCoverHomeIconSizePx = sizePx
                            COVER_ICON_SURFACE_DRAWER -> configuredCoverDrawerIconSizePx = sizePx
                            else -> return
                        }
                        refreshVisibleCoverIcons()
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 $surface icon size refreshed=${sizePx}px"
                        )
                    }
                },
                IntentFilter(ACTION_SET_COVER_ICON_SIZE),
                MODULE_CONTROL_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED
            )
        }.onFailure {
            coverIconSizeReceiverInstalled.set(false)
            unavailable("cover icon-size refresh receiver", it.message)
        }
    }

    private fun refreshVisibleCoverIcons() {
        val root = resumedSecondaryLauncher.get()?.window?.decorView ?: return
        root.post {
            fun refresh(view: View) {
                val drawable = runCatching {
                    XposedHelpers.callMethod(view, "getIcon") as? Drawable
                }.getOrNull() ?: (view as? TextView)
                    ?.compoundDrawablesRelative
                    ?.getOrNull(1)
                if (drawable != null && isCoverLauncherIcon(view)) {
                    resizeHomeIcon(view, drawable)
                    view.requestLayout()
                }
                val group = view as? ViewGroup ?: return
                for (index in 0 until group.childCount) {
                    refresh(group.getChildAt(index))
                }
            }
            refresh(root)
            root.requestLayout()
            root.invalidate()
        }
    }

    private fun installCoverWorkspaceSignal(classLoader: ClassLoader) {
        val screenManagerUtil = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.common.ScreenManagerUtil",
            classLoader
        ) ?: return unavailable("cover workspace signal", "ScreenManagerUtil missing")
        coverScreenManagerUtilClass = screenManagerUtil

        runCatching {
            XposedBridge.hookAllMethods(
                screenManagerUtil,
                "getScreenManager",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args.firstOrNull() as? Context ?: return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return
                        if (fullDexActive()) return
                        val manager = param.result ?: return
                        trackCoverScreenManager(context, manager, "manager-captured")
                    }
                }
            )
            CoverRuntime.log(SCOPE, "display-1 HoneyScreen workspace signal installed")
        }.onFailure { unavailable("cover workspace signal", it.message) }
    }

    private fun captureCoverScreenManager(activity: Activity) {
        if (coverScreenManagers.isNotEmpty()) return
        val utilClass = coverScreenManagerUtilClass ?: return
        runCatching {
            val method = utilClass.declaredMethods.firstOrNull { candidate ->
                candidate.name == "getScreenManager" &&
                    candidate.parameterCount == 1 &&
                    candidate.parameterTypes[0].isAssignableFrom(activity.javaClass)
            } ?: error("compatible getScreenManager(Context) missing")
            method.isAccessible = true
            val receiver = if (java.lang.reflect.Modifier.isStatic(method.modifiers)) {
                null
            } else {
                XposedHelpers.getStaticObjectField(utilClass, "INSTANCE")
            }
            val manager = method.invoke(receiver, activity)
                ?: error("getScreenManager returned null")
            trackCoverScreenManager(activity, manager, "launcher-resume-recapture")
        }.onFailure {
            unavailable("cover workspace manager recapture", it.message)
        }
    }

    private fun trackCoverScreenManager(context: Context, manager: Any, reason: String) {
        val newlyTracked = coverScreenManagers.add(manager)
        installCoverScreenManagerStateHooks(manager.javaClass)
        trackCoverScreens(manager)
        registerCoverWorkspaceStateRequestReceiver(context.applicationContext ?: context)
        publishCoverWorkspaceState(
            manager,
            reason = reason,
            force = newlyTracked
        )
        val cachedReason = latestShellTransitionReason
        if (
            newlyTracked &&
            latestShellTransitionActive == false &&
            cachedReason != null &&
            SystemClock.elapsedRealtime() - latestShellTransitionElapsed <=
            CACHED_SHELL_TRANSITION_REPLAY_MS
        ) {
            reconcileCoverWorkspaceToHome(manager, cachedReason)
            publishCoverWorkspaceState(manager, cachedReason, force = true)
            CoverRuntime.log(SCOPE, "replayed cached shell transition reason=$cachedReason")
        }
    }

    private fun installCoverScreenManagerStateHooks(managerClass: Class<*>) {
        if (!workspaceSignalHookedClasses.add(managerClass)) return
        val stateMethods = setOf(
            "gotoScreen",
            "gotoScreenWithAnimation",
            "resetState",
            "clearStateTransition",
            "endOnGoingAnimation",
            "setOnStateTransition",
            "registerScreen",
            "unRegisterScreen"
        )
        val hooked = mutableSetOf<String>()
        var type: Class<*>? = managerClass
        while (type != null && type != Any::class.java) {
            type.declaredMethods
                .filter { it.name in stateMethods && !it.isSynthetic }
                .forEach { method ->
                    val signature = method.toGenericString()
                    if (!hooked.add(signature)) return@forEach
                    method.isAccessible = true
                    val methodName = method.name
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!coverScreenManagers.contains(param.thisObject)) return
                            if (fullDexActive()) return
                            val startsTransition = methodName == "gotoScreen" ||
                                methodName == "gotoScreenWithAnimation" ||
                                (methodName == "setOnStateTransition" &&
                                    param.args.firstOrNull() == true)
                            if (!startsTransition) return

                            val requestedTarget = if (
                                methodName == "gotoScreen" ||
                                methodName == "gotoScreenWithAnimation"
                            ) {
                                coverScreenNameOfState(param.args.firstOrNull())
                            } else {
                                workspaceTransitionTargets[param.thisObject]
                            }
                            val target = requestedTarget?.takeIf { candidate ->
                                candidate != "HOME" ||
                                    workspaceTransitionTargets[param.thisObject] == "HOME" ||
                                    coverGestureTransitionActive
                            }
                            if (target != null) {
                                workspaceTransitionTargets[param.thisObject] = target
                            } else if (requestedTarget == "HOME") {
                                workspaceTransitionTargets.remove(param.thisObject)
                            }
                            publishCoverWorkspaceState(
                                param.thisObject,
                                reason = "$methodName-start${target?.let { "-to-$it" }.orEmpty()}",
                                force = false
                            )
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!coverScreenManagers.contains(param.thisObject)) return
                            if (fullDexActive()) return
                            trackCoverScreens(param.thisObject)
                            attachCoverTransitionAnimator(param.thisObject, methodName)
                            val target = workspaceTransitionTargets[param.thisObject]
                            publishCoverWorkspaceState(
                                param.thisObject,
                                reason = "$methodName-end${target?.let { "-to-$it" }.orEmpty()}"
                            )
                        }
                    })
                }
            type = type.superclass
        }
        if (hooked.isEmpty()) {
            unavailable(
                "HoneyScreen state hooks",
                "class=${managerClass.name} methods=0"
            )
            return
        }
        CoverRuntime.log(
            SCOPE,
            "display-1 HoneyScreen state hooks installed class=${managerClass.name} methods=${hooked.size}"
        )
    }

    private fun trackCoverScreens(manager: Any) {
        val classLoader = manager.javaClass.classLoader ?: return
        val screenNames = runCatching {
            val nameClass = classLoader.loadClass("com.honeyspace.sdk.HoneyScreen\$Name")
            nameClass.enumConstants?.toList().orEmpty()
        }.getOrDefault(emptyList())
        screenNames.forEach { name ->
            val screen = runCatching {
                XposedHelpers.callMethod(manager, "getScreen", name)
            }.getOrNull() ?: return@forEach
            workspaceScreenOwners[screen] = manager
            workspaceScreenNames[screen] = name.toString()
            refreshCoverScreenPresentation(screen)
            installCoverScreenStateHooks(screen.javaClass)
        }
    }

    private fun installCoverScreenStateHooks(screenClass: Class<*>) {
        if (!workspaceScreenHookedClasses.add(screenClass)) return
        val stateMethods = setOf(
            "changeState",
            "show",
            "preShown",
            "preHide",
            "hide",
            "cancelState",
            "setCurrentHoneyState",
            "doOnStateChangeEnd",
            "onShown",
            "onCancelScreenAnimation"
        )
        val hooked = mutableSetOf<String>()
        var type: Class<*>? = screenClass
        while (type != null && type != Any::class.java) {
            type.declaredMethods
                .filter { it.name in stateMethods && !it.isSynthetic }
                .forEach { method ->
                    val signature = method.toGenericString()
                    if (!hooked.add(signature)) return@forEach
                    if (!workspaceScreenHookedMethodSignatures.add(signature)) return@forEach
                    method.isAccessible = true
                    val methodName = method.name
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val manager = workspaceScreenOwners[param.thisObject] ?: return
                            if (fullDexActive()) return
                            if (
                                methodName != "changeState" &&
                                methodName != "show" &&
                                methodName != "preShown" &&
                                methodName != "preHide" &&
                                methodName != "hide" &&
                                methodName != "cancelState" &&
                                methodName != "onCancelScreenAnimation"
                            ) return

                            if (isWorkspaceTransitionCancelCallback(methodName)) {
                                workspaceTransitionTargets.remove(manager)
                                cancelCoverGestureTransition("screen-$methodName-start")
                                refreshAllCoverScreenPresentations(manager)
                                publishCoverWorkspaceState(
                                    manager,
                                    reason = "screen-$methodName-start-cancelled",
                                    force = true
                                )
                                return
                            }

                            val target = workspaceTransitionTargets[manager]
                            publishCoverWorkspaceState(
                                manager,
                                reason = "screen-$methodName-start${target?.let { "-to-$it" }.orEmpty()}",
                                force = false
                            )
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            val manager = workspaceScreenOwners[param.thisObject] ?: return
                            if (fullDexActive()) return
                            refreshCoverScreenPresentation(param.thisObject, methodName)
                            attachCoverTransitionAnimator(manager, "screen-$methodName")
                            val target = workspaceTransitionTargets[manager]
                            publishCoverWorkspaceState(
                                manager,
                                reason = "screen-$methodName-end${target?.let { "-to-$it" }.orEmpty()}",
                                force = methodName == "doOnStateChangeEnd"
                            )
                        }
                    })
                }
            type = type.superclass
        }
        if (hooked.isEmpty()) {
            unavailable(
                "HoneyScreen callbacks",
                "class=${screenClass.name} methods=0"
            )
            return
        }
        CoverRuntime.log(
            SCOPE,
            "display-1 HoneyScreen callbacks installed class=${screenClass.name} methods=${hooked.size}"
        )
    }

    private fun clearCoverScreenPresentation(screenName: String) {
        val screens = synchronized(workspaceScreenOwners) {
            workspaceScreenOwners.keys.filter { screen ->
                workspaceScreenNames[screen] == screenName
            }
        }
        screens.forEach { workspaceScreenPresented[it] = false }
    }

    private fun refreshCoverScreenPresentation(screen: Any, event: String? = null) {
        val presented = when (event) {
            "show", "preShown", "onShown" -> true
            "hide", "preHide" -> false
            else -> inferCoverScreenPresentation(screen) ?: workspaceScreenPresented[screen]
        } ?: return
        workspaceScreenPresented[screen] = presented
    }

    private fun refreshAllCoverScreenPresentations(manager: Any) {
        val screens = synchronized(workspaceScreenOwners) {
            workspaceScreenOwners.entries
                .filter { it.value === manager }
                .map { it.key }
        }
        screens.forEach { refreshCoverScreenPresentation(it) }
    }

    private fun inferCoverScreenPresentation(screen: Any): Boolean? = runCatching {
        val view = (XposedHelpers.callMethod(screen, "getView") as? View)
            ?: (XposedHelpers.callMethod(screen, "getRootView") as? View)
            ?: return@runCatching null
        view.visibility == View.VISIBLE &&
            view.alpha > 0.01f &&
            view.isShown
    }.getOrNull()

    private fun isCoverScreenPresented(screen: Any?): Boolean =
        screen != null && workspaceScreenPresented[screen] == true

    private fun attachCoverTransitionAnimator(manager: Any, reason: String) {
        val holder = getFieldOrNull(manager, "h") ?: return
        val animator = getFieldOrNull(holder, "d") as? ValueAnimator ?: return
        if (!workspaceTransitionAnimators.add(animator)) return
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                workspaceTransitionTargets.remove(manager)
                cancelCoverGestureTransition("$reason-animator-cancel")
                refreshAllCoverScreenPresentations(manager)
                publishCoverWorkspaceState(
                    manager,
                    reason = "$reason-animator-cancel"
                )
            }

            override fun onAnimationEnd(animation: Animator) {
                if (workspaceTransitionTargets[manager] == "HOME") {
                    workspaceTransitionTargets.remove(manager)
                    completeCoverGestureTransition("$reason-animator-end-home")
                    refreshAllCoverScreenPresentations(manager)
                }
                publishCoverWorkspaceState(
                    manager,
                    reason = "$reason-animator-end"
                )
            }
        })
        CoverRuntime.log(SCOPE, "display-1 workspace transition animator observed reason=$reason")
    }

    private fun registerFullDexChangeReceiver(context: Context) {
        synchronized(this) {
            if (fullDexChangeReceiverRegistered) return
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        val action = intent?.action ?: return
                        if (action !=
                            com.flexunlock.dexlsp.config.CoverDisplayConfig.ACTION_FULL_DEX_CHANGED
                        ) return
                        val enabled = intent.getBooleanExtra(
                            com.flexunlock.dexlsp.config.CoverDisplayConfig.EXTRA_FULL_DEX,
                            false
                        )
                        val currentContext = receiverContext ?: context
                        if (fullDexSettingEnabled(currentContext) != enabled) return
                        resumedCoverRecentsActivity.get()?.let { recents ->
                            if (!recents.isFinishing && !recents.isDestroyed) {
                                recents.finishAndRemoveTask()
                            }
                        }
                        val taskbars = refreshFullDexTaskbarState(enabled)
                        refreshFullDexDrawerState(enabled)
                        resumedSecondaryLauncher.get()?.let { launcher ->
                            applyFullDexStatusBar(launcher, enabled)
                        }
                        resumedSecondaryLauncher.get()?.window?.decorView?.let { view ->
                            view.post {
                                view.requestLayout()
                                view.invalidate()
                            }
                        }
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 full DeX state refreshed enabled=$enabled taskbars=$taskbars"
                        )
                    }
                },
                IntentFilter().apply {
                    addAction(
                        com.flexunlock.dexlsp.config.CoverDisplayConfig.ACTION_FULL_DEX_CHANGED
                    )
                },
                CoverRuntime.COVER_BROADCAST_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED
            )
            fullDexChangeReceiverRegistered = true
        }
    }

    private fun applyFullDexStatusBar(activity: Activity, enabled: Boolean) {
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity))) return
        val controller = activity.window.insetsController ?: return
        if (enabled) {
            controller.hide(WindowInsets.Type.statusBars())
        } else {
            controller.show(WindowInsets.Type.statusBars())
        }
    }

    private fun registerCoverWorkspaceStateRequestReceiver(context: Context) {
        synchronized(this) {
            if (coverWorkspaceRequestReceiverRegistered) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (!CoverRuntime.isTrustedCoverSender(context, this)) {
                        CoverRuntime.log(SCOPE, "ignored untrusted cover state broadcast")
                        return
                    }
                    when (intent?.action) {
                        CoverRuntime.COVER_QUICK_PANEL_STATE_ACTION -> {
                            val blocked = intent.getBooleanExtra(
                                CoverRuntime.EXTRA_COVER_QUICK_PANEL_BLOCKS_RECENTS,
                                false
                            )
                            coverQuickPanelBlocksRecents = blocked
                            coverQuickPanelRecentsBlockedUntil = if (blocked) {
                                Long.MAX_VALUE
                            } else {
                                SystemClock.elapsedRealtime() +
                                    QUICK_PANEL_COLLAPSE_RECENTS_COOLDOWN_MS
                            }
                            if (blocked) {
                                cancelCoverGestureTransition("quick-panel-owns-input")
                            }
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 QuickPanel Recents gate blocked=$blocked " +
                                    "cooldownMs=" +
                                    if (blocked) {
                                        "active"
                                    } else {
                                        QUICK_PANEL_COLLAPSE_RECENTS_COOLDOWN_MS
                                    }
                            )
                            return
                        }
                        CoverRuntime.COVER_SHELL_TRANSITION_ACTION -> {
                            val active = intent.getBooleanExtra(
                                CoverRuntime.EXTRA_COVER_SHELL_TRANSITION_ACTIVE,
                                false
                            )
                            val reason = intent.getStringExtra(
                                CoverRuntime.EXTRA_COVER_SHELL_TRANSITION_REASON
                            ) ?: if (active) {
                                "shell-transition-started"
                            } else {
                                "shell-transition-finished"
                            }
                            latestShellTransitionActive = active
                            latestShellTransitionReason = reason
                            latestShellTransitionElapsed = SystemClock.elapsedRealtime()
                            if (active) {
                                beginCoverGestureTransition(reason)
                                publishAllCoverWorkspaceStates(reason, force = true)
                                return
                            }
                            synchronized(workspaceTransitionTargets) {
                                workspaceTransitionTargets.entries.removeAll { it.value == "HOME" }
                            }
                            completeCoverGestureTransition(reason)
                            coverScreenManagers.toList().forEach { manager ->
                                refreshAllCoverScreenPresentations(manager)
                                reconcileCoverWorkspaceToHome(manager, reason)
                            }
                            publishAllCoverWorkspaceStates(reason, force = true)
                            return
                        }
                        CoverRuntime.COVER_WORKSPACE_STATE_REQUEST_ACTION -> Unit
                        else -> return
                    }
                    resumedSecondaryLauncher.get()?.let { activity ->
                        secondaryLauncherResumed = !activity.isFinishing && !activity.isDestroyed
                        secondaryLauncherFocused = activity.hasWindowFocus()
                    }
                    coverScreenManagers.firstOrNull()?.let { manager ->
                        reconcileCoverWorkspaceToHome(manager, "state-request")
                        publishCoverWorkspaceState(
                            manager,
                            reason = "state-request",
                            force = true
                        )
                    }
                }
            }
            context.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(CoverRuntime.COVER_WORKSPACE_STATE_REQUEST_ACTION)
                    addAction(CoverRuntime.COVER_SHELL_TRANSITION_ACTION)
                    addAction(CoverRuntime.COVER_QUICK_PANEL_STATE_ACTION)
                },
                CoverRuntime.COVER_BROADCAST_PERMISSION,
                null,
                Context.RECEIVER_EXPORTED
            )
            coverWorkspaceRequestReceiverRegistered = true
        }
    }

    private fun reconcileCoverWorkspaceToHome(
        manager: Any,
        reason: String,
        force: Boolean = false
    ) {
        if (fullDexActive()) return
        val snapshot = readCoverWorkspaceSnapshot(manager) ?: return
        if (snapshot.screen == "HOME") return
        // 用户稳定停留在抽屉(APPS)时,解锁恢复或返回导航应保留抽屉,不强制切回桌面。
        // 判定基于 HoneySpace 自身的当前屏与状态(而非 view 呈现标志):
        // Launcher 在后台时 APPS 屏 view 不可见,appsPresented 会为 false,
        // 但 ScreenManager 仍停留在 APPS,返回时应保留抽屉。
        val stableAppsSurface =
            snapshot.screen == "APPS" &&
                snapshot.appsNormal &&
                !snapshot.transition &&
                !snapshot.animation &&
                !snapshot.folder &&
                !snapshot.folderMode &&
                !snapshot.edit &&
                !snapshot.menu
        if (!force && stableAppsSurface) {
            CoverRuntime.log(
                SCOPE,
                "display-1 stable Apps surface retained after $reason"
            )
            return
        }
        val authoritativeHomeTransition = reason == "shell-home-transition-finished"
        val stableNativeHomeSurface =
            snapshot.homePresented &&
                !snapshot.appsPresented &&
                !snapshot.recentsPresented &&
                !snapshot.finderPresented &&
                !snapshot.transition &&
                !snapshot.animation &&
                !snapshot.folder &&
                !snapshot.folderMode &&
                !snapshot.edit &&
                !snapshot.menu &&
                snapshot.activityResumed &&
                snapshot.activityFocused
        if (!force && !authoritativeHomeTransition && !stableNativeHomeSurface) return
        val activity = resumedSecondaryLauncher.get() ?: return
        if (!isCoverSecondaryLauncher(activity) || !activity.hasWindowFocus()) return

        runCatching {
            val nameClass = manager.javaClass.classLoader
                ?.loadClass("com.honeyspace.sdk.HoneyScreen\$Name")
                ?: return@runCatching
            val homeName = nameClass.enumConstants
                ?.firstOrNull { it.toString() == "HOME" }
                ?: return@runCatching
            val homeScreen = XposedHelpers.callMethod(manager, "getScreen", homeName)
                ?: return@runCatching
            val homeState = XposedHelpers.callMethod(homeScreen, "getCurrentHoneyState")
                ?: return@runCatching
            XposedHelpers.callMethod(manager, "gotoScreen", homeState)
            refreshAllCoverScreenPresentations(manager)
            CoverRuntime.log(
                SCOPE,
                "display-1 workspace reconciled ${snapshot.screen}->HOME after $reason"
            )
        }.onFailure {
            unavailable("cover workspace Home reconciliation", it.message)
        }
    }

    private fun publishAllCoverWorkspaceStates(reason: String, force: Boolean = false) {
        coverScreenManagers.toList().forEach { manager ->
            publishCoverWorkspaceState(manager, reason = reason, force = force)
        }
    }

    private fun publishCoverWorkspaceState(
        manager: Any,
        reason: String,
        force: Boolean = false
    ) {
        if (fullDexActive()) return
        trackCoverScreens(manager)
        val snapshot = readCoverWorkspaceSnapshot(manager) ?: return
        val usableSnapshot = snapshot.state != "null" ||
            snapshot.homeState != "null" ||
            snapshot.homePresented ||
            snapshot.appsPresented ||
            snapshot.recentsPresented ||
            snapshot.finderPresented
        if (!usableSnapshot) {
            CoverRuntime.log(
                SCOPE,
                "display-1 ignored stale workspace manager reason=$reason " +
                    "screen=${snapshot.screen} stackEmpty=${snapshot.stackEmpty}"
            )
            return
        }
        val transitionTarget = workspaceTransitionTargets[manager]
        val nativeGestureOwnsPresentation = coverGestureTransitionActive
        val visible = snapshot.visible &&
            transitionTarget == null &&
            !nativeGestureOwnsPresentation
        if (snapshot.visible && (transitionTarget == "HOME" || nativeGestureOwnsPresentation)) {
            CoverRuntime.log(
                SCOPE,
                "display-1 stable Home presentation held until native transition owner releases " +
                    "gesture=$nativeGestureOwnsPresentation target=$transitionTarget"
            )
        }
        val effectiveState = when {
            snapshot.state != "null" -> snapshot.state
            visible && snapshot.screen == "HOME" -> snapshot.changeState
            else -> snapshot.state
        }
        val signal = WorkspacePresentationSignal(
            visible = visible,
            screen = snapshot.screen,
            state = effectiveState,
            transition = snapshot.transition || snapshot.animation,
            folder = snapshot.folder || snapshot.folderMode
        )
        if (!force && lastPublishedCoverWorkspaceSignal == signal) {
            lastCoverWorkspaceSnapshot = snapshot
            return
        }
        lastCoverWorkspaceSnapshot = snapshot
        lastPublishedCoverWorkspaceSignal = signal
        val context = AndroidAppHelper.currentApplication() ?: return
        val sequence = synchronized(this) {
            coverWorkspaceSequence += 1L
            coverWorkspaceSequence
        }
        context.sendBroadcast(
            Intent(CoverRuntime.COVER_WORKSPACE_STATE_ACTION).apply {
                setPackage(CoverRuntime.SYSTEM_UI_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(CoverRuntime.EXTRA_COVER_WORKSPACE_VISIBLE, signal.visible)
                putExtra(CoverRuntime.EXTRA_COVER_WORKSPACE_EPOCH, coverWorkspaceEpoch)
                putExtra(CoverRuntime.EXTRA_COVER_WORKSPACE_SEQUENCE, sequence)
                putExtra(CoverRuntime.EXTRA_COVER_WORKSPACE_SCREEN, signal.screen)
                putExtra(CoverRuntime.EXTRA_COVER_WORKSPACE_STATE, signal.state)
                putExtra(
                    CoverRuntime.EXTRA_COVER_WORKSPACE_TRANSITION,
                    signal.transition
                )
                putExtra(
                    CoverRuntime.EXTRA_COVER_WORKSPACE_FOLDER,
                    signal.folder
                )
                putExtra(CoverRuntime.EXTRA_COVER_WORKSPACE_REASON, reason)
            }
        )
        CoverRuntime.log(
            SCOPE,
            "display-1 workspace reason=$reason screen=${snapshot.screen} " +
                "state=${snapshot.state.substringAfterLast('.')} " +
                "change=${snapshot.changeState.substringAfterLast('.')} " +
                "home=${snapshot.homeState.substringAfterLast('.')}/" +
                "${snapshot.homeChangeState.substringAfterLast('.')} " +
                "presented=H${snapshot.homePresented}:A${snapshot.appsPresented}:" +
                "R${snapshot.recentsPresented}:F${snapshot.finderPresented} " +
                "stackEmpty=${snapshot.stackEmpty} " +
                "transition=${snapshot.transition}/${snapshot.animation} " +
                "folder=${snapshot.folder}/${snapshot.folderMode} " +
                "edit=${snapshot.edit} menu=${snapshot.menu} " +
                "resumed=${snapshot.activityResumed} focus=${snapshot.activityFocused} " +
                "visible=$visible epoch=$coverWorkspaceEpoch sequence=$sequence"
        )
    }

    private fun readCoverWorkspaceSnapshot(manager: Any): WorkspaceSnapshot? = runCatching {
        val currentScreenObject = XposedHelpers.callMethod(manager, "getCurrentHoneyScreen")
        val currentScreenName = currentScreenObject?.toString() ?: "UNKNOWN"
        val currentChangeState = XposedHelpers.callMethod(manager, "getCurrentHoneyScreenState")
        val screenNames = currentScreenObject?.javaClass?.enumConstants.orEmpty()
        fun screen(name: String): Any? {
            val screenName = screenNames.firstOrNull { it.toString() == name } ?: return null
            return XposedHelpers.callMethod(manager, "getScreen", screenName)
        }
        val currentScreen = currentScreenObject?.let {
            XposedHelpers.callMethod(manager, "getScreen", it)
        }
        val homeScreen = screen("HOME")
        val appsScreen = screen("APPS")
        val recentsScreen = screen("RECENTS")
        val finderScreen = screen("FINDER")
        val currentState = currentScreen?.let {
            XposedHelpers.callMethod(it, "getCurrentHoneyState")
        }
        val changeState = currentScreen?.let {
            XposedHelpers.callMethod(it, "getCurrentChangeState")
        }
        val homeState = homeScreen?.let {
            XposedHelpers.callMethod(it, "getCurrentHoneyState")
        }
        val homeChangeState = homeScreen?.let {
            XposedHelpers.callMethod(it, "getCurrentChangeState")
        }
        WorkspaceSnapshot(
            screen = currentScreenName,
            state = stateClassName(currentState),
            changeState = stateClassName(changeState ?: currentChangeState),
            homeState = stateClassName(homeState),
            homeChangeState = stateClassName(homeChangeState),
            homePresented = isCoverScreenPresented(homeScreen),
            appsPresented = isCoverScreenPresented(appsScreen),
            recentsPresented = isCoverScreenPresented(recentsScreen),
            finderPresented = isCoverScreenPresented(finderScreen),
            stackEmpty = XposedHelpers.callMethod(manager, "getScreenStackIsEmpty") == true,
            transition = XposedHelpers.callMethod(manager, "isOnStateTransition") == true,
            animation = XposedHelpers.callMethod(manager, "isOnGoingAnimationRunning") == true,
            folder = XposedHelpers.callMethod(manager, "isOpenFolderMode") == true,
            folderMode = XposedHelpers.callMethod(manager, "isFolderMode") == true,
            edit = XposedHelpers.callMethod(manager, "isEditHomescreen") == true,
            menu = coverWorkspacePopups.isNotEmpty() || isCoverQuickOptionVisible(),
            appsNormal = XposedHelpers.callMethod(manager, "isAppsNormalState") == true,
            activityResumed = secondaryLauncherResumed,
            activityFocused = secondaryLauncherFocused
        )
    }.onFailure {
        unavailable("cover workspace state read", it.message)
    }.getOrNull()

    private fun stateClassName(state: Any?): String = state?.javaClass?.name ?: "null"

    private fun coverScreenNameOfState(state: Any?): String? = when {
        state == null -> null
        state.javaClass.name.contains(".HomeScreen\$") -> "HOME"
        state.javaClass.name.contains(".AppScreen\$") -> "APPS"
        state.javaClass.name.contains(".RecentScreen\$") -> "RECENTS"
        state.javaClass.name.contains(".FinderScreen\$") -> "FINDER"
        else -> null
    }

    private fun installNativeSecondaryDisplayGate(classLoader: ClassLoader) {
        val displayHelper = findCompatClass(
            classLoader = classLoader,
            v85Names = listOf("l3.r"),
            v8Names = listOf(
                "com.honeyspace.common.utils.DisplayHelperImpl",
                "com.honeyspace.common.context.ContextExtensionKt",
                "l3.r"
            ),
            v7Names = listOf(
                "com.honeyspace.common.utils.DisplayHelperImpl",
                "com.honeyspace.common.context.ContextExtensionKt",
                "l3.r"
            )
        ) { candidate ->
            candidate.declaredMethods.any { method ->
                method.name == "isDeviceDisplay" &&
                    method.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType)
                    ) &&
                    method.returnType == Boolean::class.javaPrimitiveType
            }
        } ?: return unavailable(
            "SecondaryLauncher display gate",
            "isDeviceDisplay(Int) shape unavailable"
        )
        runCatching {
            XposedHelpers.findAndHookMethod(
                displayHelper,
                "isDeviceDisplay",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (
                            CoverRuntime.isCoverDisplay(displayId) &&
                            CoverRuntime.isCoverSessionEligible()
                        ) {
                            param.result = false
                        }
                    }
                }
            )
            CoverRuntime.log(SCOPE, "native SecondaryLauncher display-1 gate installed")
        }.onFailure { unavailable("SecondaryLauncher display gate", it.message) }
    }

    private fun installNativeSamsungGestureMode(classLoader: ClassLoader) {
        val navigationModeSource = findCompatClass(
            classLoader = classLoader,
            v85Names = listOf("P2.d"),
            v8Names = listOf(
                "com.honeyspace.gesture.region.SamsungTouchRegion",
                "P2.d"
            ),
            v7Names = listOf(
                "com.honeyspace.gesture.region.SamsungTouchRegion",
                "P2.d"
            )
        ) { candidate ->
            candidate.declaredMethods.any { method ->
                method.name == "b" &&
                    method.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType)
                    ) &&
                    method.returnType.name == "com.honeyspace.sdk.NaviMode"
            }
        } ?: return unavailable(
            "Samsung gesture mode",
            "navigation mode source shape unavailable"
        )
        val naviModeClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.sdk.NaviMode",
            classLoader
        ) ?: return unavailable("Samsung gesture mode", "NaviMode missing")
        val samsungGestureMode = runCatching {
            XposedHelpers.getStaticObjectField(naviModeClass, "S_GESTURE")
        }.getOrElse {
            return unavailable("Samsung gesture mode", "S_GESTURE missing")
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                navigationModeSource,
                "b",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayId = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        param.result = samsungGestureMode
                        if (nativeGestureModeSources.add(param.thisObject)) {
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 NavigationModeSource uses native S_GESTURE"
                            )
                        }
                    }
                }
            )
            CoverRuntime.log(SCOPE, "display-1 native Samsung gesture mode route installed")
        }.onFailure { unavailable("Samsung gesture mode", it.message) }
    }

    private fun installNativeSamsungTouchRegion(classLoader: ClassLoader) {
        val regionManagerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.region.RegionManagerImpl",
            classLoader
        ) ?: return unavailable("Samsung touch region", "RegionManagerImpl missing")
        val samsungTouchRegionClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.region.SamsungTouchRegion",
            classLoader
        ) ?: return unavailable("Samsung touch region", "SamsungTouchRegion missing")
        nativeSamsungTouchRegionClass = samsungTouchRegionClass
        val bottomPositionClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.region.RegionPosition\$BOTTOM",
            classLoader
        ) ?: return unavailable("Samsung touch region", "RegionPosition.BOTTOM missing")
        val bottomPosition = XposedHelpers.newInstance(bottomPositionClass)
        nativeGestureBottomPosition = bottomPosition

        runCatching {
            val positionMethods = regionManagerClass.declaredMethods.filter { method ->
                method.name == "getRegionPosition" && method.parameterCount == 0
            }
            positionMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayId = getIntFieldOrNull(param.thisObject, "displayId") ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        param.result = bottomPosition
                    }
                })
            }

            val changeMethods = regionManagerClass.declaredMethods.filter { method ->
                method.name == "changeTouchRegion" && method.parameterCount == 0
            }
            changeMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            useNativeSamsungTouchRegion(
                                param.thisObject,
                                samsungTouchRegionClass
                            )
                        }.onFailure { unavailable("Samsung touch region apply", it.message) }
                    }
                })
            }

            val updateMethods = samsungTouchRegionClass.declaredMethods.filter { method ->
                method.name == "updateRegion" && method.parameterCount == 1
            }
            updateMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val deviceState = param.args?.firstOrNull() ?: return
                        val regionContext = getFieldOrNull(param.thisObject, "context") as? Context
                        val regionDisplayId = CoverRuntime.displayIdOf(regionContext)
                        val stateDisplayId = runCatching {
                            (XposedHelpers.callMethod(deviceState, "getDisplayId") as? Number)
                                ?.toInt()
                        }.getOrNull()
                        val displayId = regionDisplayId ?: stateDisplayId ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        runCatching {
                            applyCurrentCoverGestureRegions(param.thisObject, deviceState)
                            disableCoverGestureOverlayTouches(param.thisObject)
                            val appliedRegion = XposedHelpers.callMethod(
                                param.thisObject,
                                "getTouchRegionRectF"
                            ) as? RectF
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 gesture thirds applied regionDisplay=$regionDisplayId " +
                                    "stateDisplay=$stateDisplayId touch=$appliedRegion"
                            )
                        }.onFailure {
                            unavailable("Samsung touch region thirds", it.message)
                        }
                    }
                })
            }

            CoverRuntime.log(
                SCOPE,
                "display-1 native Samsung touch region route installed " +
                    "positionMethods=${positionMethods.size} " +
                    "changeMethods=${changeMethods.size} updateMethods=${updateMethods.size}"
            )
        }.onFailure { unavailable("Samsung touch region", it.message) }
    }

    private fun installNativeRecentsTaskPolicy(classLoader: ClassLoader) {
        val repositoryClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.repository.task.TaskListRepository",
            classLoader
        ) ?: return unavailable("Recents task policy", "TaskListRepository missing")

        runCatching {
            val filterMethods = repositoryClass.declaredMethods.filter { method ->
                method.name == "filterVisibleTasks" && method.parameterCount == 2
            }
            if (filterMethods.isEmpty()) {
                return@runCatching unavailable(
                    "Recents task policy",
                    "filterVisibleTasks(Int) shape unavailable"
                )
            }
            filterMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val displayId = (param.args.getOrNull(1) as? Number)?.toInt() ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        installCoverVisibleTasksPolicy(param.thisObject)
                        coverRecentsTaskFilterDepth.set(coverRecentsFilterDepth() + 1)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayId = (param.args.getOrNull(1) as? Number)?.toInt() ?: return
                        if (!CoverRuntime.isCoverDisplay(displayId)) return
                        val depth = (coverRecentsFilterDepth() - 1).coerceAtLeast(0)
                        if (depth == 0) {
                            coverRecentsTaskFilterDepth.remove()
                        } else {
                            coverRecentsTaskFilterDepth.set(depth)
                        }
                        val visibleTasks = param.result as? List<*>
                        val sourceTasks = param.args.firstOrNull() as? List<*>
                        if (fullDexActive() && sourceTasks != null) {
                            val nativeTasks = sourceTasks.filterNotNull()
                            if (nativeTasks.isNotEmpty()) {
                                param.result = nativeTasks
                                CoverRuntime.log(
                                    SCOPE,
                                    "display-1 full DeX Recents restored native tasks=" +
                                        nativeTasks.size
                                )
                            }
                        }
                        val coverSourceTasks = sourceTasks?.count { task ->
                            task != null && CoverRuntime.isCoverDisplay(
                                runCatching {
                                    (XposedHelpers.callMethod(
                                        task,
                                        "getDisplayId"
                                    ) as? Number)?.toInt()
                                }.getOrNull()
                            )
                        } ?: -1
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 native Recents task policy produced " +
                                "sourceTasks=${sourceTasks?.size ?: -1} " +
                                "coverSourceTasks=$coverSourceTasks " +
                                "visibleTasks=${visibleTasks?.size ?: -1}"
                        )
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 native Recents task policy route installed " +
                    "filterMethods=${filterMethods.size}"
            )
        }.onFailure { unavailable("Recents task policy", it.message) }
    }

    private fun installNativeRecentsRotationPolicy(classLoader: ClassLoader) {
        val drawingBagClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.common.taskScene.scenedrawingbag.DynamicSceneDrawingBag",
            classLoader
        ) ?: return unavailable("Recents rotation policy", "DynamicSceneDrawingBag missing")

        runCatching {
            val contextMethods = drawingBagClass.declaredMethods.filter { method ->
                (method.name == "getRotateMatrix" || method.name == "getSceneStateInfo") &&
                    method.parameterTypes.any(Context::class.java::isAssignableFrom)
            }
            contextMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val context = param.args.firstOrNull { it is Context } as? Context ?: return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return
                        val depth = coverRecentsRotationDepth.get() ?: 0
                        param.setObjectExtra("coverRecentsRotationDepth", depth)
                        coverRecentsRotationDepth.set(depth + 1)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val depth = param.getObjectExtra("coverRecentsRotationDepth") as? Int
                            ?: return
                        if (depth == 0) {
                            coverRecentsRotationDepth.remove()
                        } else {
                            coverRecentsRotationDepth.set(depth)
                        }
                    }
                })
            }

            val deltaMethods = drawingBagClass.declaredMethods.filter { method ->
                method.name == "getDeltaRotation" &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            Boolean::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                    )
            }
            deltaMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if ((coverRecentsRotationDepth.get() ?: 0) <= 0) return
                        val activityRotation = (param.args.getOrNull(1) as? Number)?.toInt()
                            ?: return
                        val thumbnailRotation = (param.args.getOrNull(2) as? Number)?.toInt()
                            ?: return
                        val metadataDelta = (thumbnailRotation - activityRotation + 4) % 4
                        // Cover task snapshots are already composed in display orientation. Applying the
                        // metadata delta rotates an in-place Recents configuration change a second time.
                        val appliedDelta = 0
                        param.result = appliedDelta
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 Recents thumbnail rotation current=$activityRotation " +
                                "snapshot=$thumbnailRotation metadataDelta=$metadataDelta " +
                                "appliedDelta=$appliedDelta"
                        )
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 Recents live rotation policy installed " +
                    "contextMethods=${contextMethods.size} deltaMethods=${deltaMethods.size}"
            )
        }.onFailure { unavailable("Recents rotation policy", it.message) }
    }

    private fun installCoverVisibleTasksPolicy(repository: Any) {
        val policy = getFieldOrNull(repository, "visibleTasksPolicy") ?: return
        if (!coverRecentsPolicyInstances.add(policy)) return
        val infoMethods = policy.javaClass.declaredMethods.filter { method ->
            method.name == "isInvisibleTaskInfo" && method.parameterCount == 5
        }
        val itemMethods = policy.javaClass.declaredMethods.filter { method ->
            method.name == "isInvisibleTaskItem" && method.parameterCount == 1
        }
        if (infoMethods.isEmpty() || itemMethods.isEmpty()) {
            coverRecentsPolicyInstances.remove(policy)
            unavailable(
                "VisibleTasksPolicy cover-state inputs",
                "visibility shapes unavailable class=${policy.javaClass.name} " +
                    "infoMethods=${infoMethods.size} itemMethods=${itemMethods.size}"
            )
            return
        }
        infoMethods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (coverRecentsFilterDepth() <= 0) return
                    if (!CoverRuntime.isCoverSessionEligible()) return
                    val taskInfo = param.args.firstOrNull() ?: return
                    if (!taskInfo.javaClass.name.endsWith("VisibleTasksPolicy\$TaskInfo")) return

                    param.result = false
                }
            })
        }
        itemMethods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (coverRecentsFilterDepth() <= 0) return
                    if (!CoverRuntime.isCoverSessionEligible()) return
                    val taskItem = param.args.firstOrNull() ?: return
                    if (!taskItem.javaClass.name.endsWith("VisibleTasksPolicy\$TaskItem")) return

                    param.result = false
                }
            })
        }
        CoverRuntime.log(
            SCOPE,
            "display-1 VisibleTasksPolicy cover-state inputs installed " +
                "class=${policy.javaClass.name} " +
                "infoMethods=${infoMethods.size} itemMethods=${itemMethods.size}"
        )
    }

    private fun installNativeRecentsWallpaperComposition(classLoader: ClassLoader) {
        val startInfoClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager\$GestureTransitionStartInfo",
            classLoader
        ) ?: return unavailable("Recents wallpaper composition", "start info missing")

        runCatching {
            XposedBridge.hookAllMethods(
                startInfoClass,
                "getWallpaperNoNeeded",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val manager = getFieldOrNull(param.thisObject, "this\$0") ?: return
                        val displayId = getIntFieldOrNull(manager, "displayId") ?: return
                        if (!isClosedCoverDisplay(displayId) || param.result != true) return

                        param.result = false
                        if (
                            XposedHelpers.getAdditionalInstanceField(
                                param.thisObject,
                                "coverRecentsWallpaperCompositionLogged"
                            ) != true
                        ) {
                            XposedHelpers.setAdditionalInstanceField(
                                param.thisObject,
                                "coverRecentsWallpaperCompositionLogged",
                                true
                            )
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 Recents native wallpaper Surface retained for blur composition"
                            )
                        }
                    }
                }
            )
            CoverRuntime.log(
                SCOPE,
                "display-1 Recents wallpaper Surface composition policy installed"
            )
        }.onFailure { unavailable("Recents wallpaper composition", it.message) }
    }

    private fun installNativeRecentsWallpaperLifecycle(classLoader: ClassLoader) {
        val managerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager",
            classLoader
        ) ?: return unavailable("Recents wallpaper lifecycle", "manager missing")

        runCatching {
            val translucentMethods = managerClass.declaredMethods.filter { method ->
                method.name == "isCloseTargetTranslucent" && method.parameterCount == 0
            }
            translucentMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if ((coverRecentsWallpaperStartDepth.get() ?: 0) <= 0) return
                        param.result = false
                    }
                })
            }

            val startMethods = managerClass.declaredMethods.filter { method ->
                method.name == "startWallpaperAnimator" &&
                    method.parameterCount == 3 &&
                    method.parameterTypes.getOrNull(0) == Rect::class.java &&
                    method.parameterTypes.getOrNull(1) == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.getOrNull(2) == Boolean::class.javaPrimitiveType
            }
            startMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val displayId = getIntFieldOrNull(param.thisObject, "displayId") ?: return
                        if (!isClosedCoverDisplay(displayId)) return

                        val animator = getFieldOrNull(param.thisObject, "wallpaperAnimator") ?: return
                        coverRecentsWallpaperAnimators[param.thisObject] = animator
                        param.args[1] = true
                        coverRecentsWallpaperStartDepth.set(
                            (coverRecentsWallpaperStartDepth.get() ?: 0) + 1
                        )
                        param.setObjectExtra("coverRecentsWallpaperLifecycleActive", true)
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 Recents wallpaper closeStart admitted " +
                                "willPause=true manager=${System.identityHashCode(param.thisObject)} " +
                                "animator=${System.identityHashCode(animator)}"
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.getObjectExtra("coverRecentsWallpaperLifecycleActive") != true) {
                            return
                        }
                        val depth = ((coverRecentsWallpaperStartDepth.get() ?: 0) - 1)
                            .coerceAtLeast(0)
                        if (depth == 0) {
                            coverRecentsWallpaperStartDepth.remove()
                        } else {
                            coverRecentsWallpaperStartDepth.set(depth)
                        }
                        val animator = coverRecentsWallpaperAnimators[param.thisObject] ?: return
                        val willPause = runCatching {
                            XposedHelpers.getBooleanField(animator, "willPause")
                        }.getOrNull()
                        val surface = getFieldOrNull(animator, "wallpaperSurface") as? SurfaceControl
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 Recents wallpaper closeStart returned " +
                                "willPause=$willPause surfaceValid=${surface?.isValid} " +
                                "animator=${System.identityHashCode(animator)}"
                        )
                    }
                })
            }

            val finishMethods = managerClass.declaredMethods.filter { method ->
                method.name == "finishController" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes.all { it == Boolean::class.javaPrimitiveType }
            }
            finishMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val displayId = getIntFieldOrNull(param.thisObject, "displayId") ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        val animator = getFieldOrNull(param.thisObject, "wallpaperAnimator") ?: return
                        val expected = coverRecentsWallpaperAnimators[param.thisObject]
                        val surface = getFieldOrNull(animator, "wallpaperSurface") as? SurfaceControl
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 Recents finishController resumes wallpaper " +
                                "sameAnimator=${expected === animator} " +
                                "surfaceValid=${surface?.isValid} " +
                                "animator=${System.identityHashCode(animator)}"
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val expected = coverRecentsWallpaperAnimators.remove(param.thisObject) ?: return
                        val running = getFieldOrNull(expected, "runningAnim") as? ValueAnimator
                        val surface = getFieldOrNull(expected, "wallpaperSurface") as? SurfaceControl
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 Recents finishController wallpaper handoff complete " +
                                "paused=${running?.isPaused} running=${running?.isRunning} " +
                                "surfaceValid=${surface?.isValid} " +
                                "animator=${System.identityHashCode(expected)}"
                        )
                    }
                })
            }

            CoverRuntime.log(
                SCOPE,
                "display-1 Recents native wallpaper lifecycle installed " +
                    "translucentMethods=${translucentMethods.size} " +
                    "startMethods=${startMethods.size} finishMethods=${finishMethods.size}"
            )
        }.onFailure { unavailable("Recents wallpaper lifecycle", it.message) }
    }

    private fun installNativeRecentsOverlayBackdrop(classLoader: ClassLoader) {
        val overlayClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.overlaywindow.LeashOverlayWindowImpl",
            classLoader
        ) ?: return unavailable("Recents overlay backdrop", "LeashOverlayWindowImpl missing")

        runCatching {
            XposedBridge.hookAllMethods(
                overlayClass,
                "addGestureTaskOverlay",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayId = getIntFieldOrNull(param.thisObject, "displayId") ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        val root = getFieldOrNull(param.thisObject, "rootView") as? ViewGroup
                            ?: return

                        val backdrops = findRecentsOverlayBackdrops(root)
                        coverRecentsOverlayRoots[param.thisObject] = root
                        coverRecentsBackdropViews[param.thisObject] = backdrops
                        if (backdrops.none(::isRecentsBlurView)) {
                            return unavailable(
                                "Recents overlay backdrop",
                                "WallpaperBlurView missing from ${backdrops.map { it.javaClass.name }}"
                            )
                        }
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 Recents native backdrop cached once " +
                                "views=${backdrops.map { it.javaClass.simpleName }}"
                        )
                    }
                }
            )
            XposedBridge.hookAllMethods(
                overlayClass,
                "backgroundProgress",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val displayId = getIntFieldOrNull(param.thisObject, "displayId") ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        val nativeProgress = (param.args.firstOrNull() as? Number)?.toFloat()
                            ?: return
                        param.args[0] = (
                            nativeProgress *
                                COVER_RECENTS_NATIVE_BACKDROP_DURATION_MS.toFloat() /
                                COVER_RECENTS_ENTER_DURATION_MS.toFloat()
                            ).coerceIn(0f, 1f)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                overlayClass,
                "removeOverlayWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        releaseRecentsOverlayBackdrops(param.thisObject, "native-remove")
                    }
                }
            )
            CoverRuntime.log(
                SCOPE,
                "display-1 Recents native backdrop timeline installed " +
                    "${COVER_RECENTS_NATIVE_BACKDROP_DURATION_MS}ms->" +
                    "$COVER_RECENTS_ENTER_DURATION_MS ms"
            )
        }.onFailure { unavailable("Recents overlay backdrop", it.message) }
    }

    private fun scaleAnimatorTreeToDuration(animator: Animator, maxDurationMs: Long): Boolean {
        val totalDuration = when (animator) {
            is AnimatorSet -> animator.totalDuration
            else -> animator.startDelay + animator.duration
        }
        if (totalDuration <= maxDurationMs || totalDuration <= 0L) return false
        val scale = maxDurationMs.toDouble() / totalDuration.toDouble()

        fun scaleNode(node: Animator) {
            node.startDelay = (node.startDelay * scale).toLong()
            if (node is AnimatorSet) {
                node.childAnimations.forEach(::scaleNode)
            } else if (node.duration > 0L) {
                node.duration = (node.duration * scale).toLong().coerceAtLeast(1L)
            }
        }

        scaleNode(animator)
        return true
    }

    private fun installNativeRecentsEnterTiming(classLoader: ClassLoader) {
        val enteringAnimationHelperClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.presentation.RecentEnteringAnimationHelper",
            classLoader
        ) ?: return unavailable("Recents enter timing", "entering animation helper missing")
        val managerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager",
            classLoader
        ) ?: return unavailable("Recents enter timing", "manager missing")

        runCatching {
            XposedBridge.hookAllMethods(
                enteringAnimationHelperClass,
                "animateToRecent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val parentView = getFieldOrNull(param.thisObject, "parentView") as? View
                            ?: return
                        if (!CoverRuntime.isCoverDisplay(
                                CoverRuntime.displayIdOf(parentView.context)
                            )
                        ) return
                        val animatorSet = getFieldOrNull(
                            param.thisObject,
                            "recentsEnteringAnimatorSet"
                        ) as? AnimatorSet ?: return
                        coverRecentsEnterAnimatorSets.add(animatorSet)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                AnimatorSet::class.java,
                "start",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val animatorSet = param.thisObject as? AnimatorSet ?: return
                        if (!coverRecentsEnterAnimatorSets.remove(animatorSet)) return
                        val nativeTotal = animatorSet.totalDuration
                        val changed = scaleAnimatorTreeToDuration(
                            animatorSet,
                            COVER_RECENTS_ENTER_DURATION_MS
                        )
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 Recents assembled animator tree normalized " +
                                "total=$nativeTotal->$COVER_RECENTS_ENTER_DURATION_MS ms " +
                                "changed=$changed"
                        )
                    }
                }
            )
            XposedBridge.hookAllMethods(
                managerClass,
                "startRecentEntering",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val manager = param.thisObject
                        val displayId = getIntFieldOrNull(manager, "displayId") ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        val targetView = getFieldOrNull(manager, "targetView") as? View ?: return
                        coverRecentsFastFinishRunnables.remove(manager)?.let(
                            targetView::removeCallbacks
                        )

                        val finishDelayMs =
                            COVER_RECENTS_ENTER_DURATION_MS +
                                COVER_RECENTS_SPRING_FAST_FINISH_SETTLE_MS
                        val fastFinish = Runnable {
                            coverRecentsFastFinishRunnables.remove(manager)
                            val activeDisplayId = getIntFieldOrNull(manager, "displayId")
                                ?: return@Runnable
                            if (!isClosedCoverDisplay(activeDisplayId)) return@Runnable
                            val helper = getFieldOrNull(manager, "desktopModeHelper")
                                ?: return@Runnable
                            val playerFlow = getFieldOrNull(helper, "_playerMap")
                                ?: return@Runnable
                            val players = runCatching {
                                (XposedHelpers.callMethod(playerFlow, "getValue") as? Map<*, *>)
                                    ?.values
                                    ?.filterNotNull()
                                    .orEmpty()
                            }.getOrElse {
                                unavailable("Recents spring player lookup", it.message)
                                emptyList()
                            }
                            var finished = 0
                            players.forEach { player ->
                                runCatching {
                                    val fastSpringFinish = player.javaClass.methods.firstOrNull {
                                        it.name == "fastSpringFinish" && it.parameterCount == 1
                                    } ?: error("fastSpringFinish(Function0) not found")
                                    val callbackType = fastSpringFinish.parameterTypes.single()
                                    val targetUnit = runCatching {
                                        Class.forName(
                                            "kotlin.Unit",
                                            false,
                                            callbackType.classLoader
                                        ).getField("INSTANCE").get(null)
                                    }.getOrNull()
                                    val onFinished = java.lang.reflect.Proxy.newProxyInstance(
                                        callbackType.classLoader,
                                        arrayOf(callbackType)
                                    ) { proxy, method, args ->
                                        when (method.name) {
                                            "invoke" -> targetUnit
                                            "toString" -> "CoverRecentsFastFinishCallback"
                                            "hashCode" -> System.identityHashCode(proxy)
                                            "equals" -> proxy === args?.firstOrNull()
                                            else -> null
                                        }
                                    }
                                    fastSpringFinish.invoke(player, onFinished)
                                    finished += 1
                                }.onFailure {
                                    unavailable("Recents spring fast finish", it.message)
                                }
                            }
                            if (players.isNotEmpty()) {
                                CoverRuntime.log(
                                    SCOPE,
                                    "display-1 Recents native spring fast-finish " +
                                        "players=${players.size} finished=$finished " +
                                        "at=$finishDelayMs ms " +
                                        "settle=$COVER_RECENTS_SPRING_FAST_FINISH_SETTLE_MS ms"
                                )
                            }
                        }
                        coverRecentsFastFinishRunnables[manager] = fastFinish
                        targetView.postDelayed(fastFinish, finishDelayMs)
                    }
                }
            )
            CoverRuntime.log(SCOPE, "display-1 Recents enter timing policy installed")
        }.onFailure { unavailable("Recents enter timing", it.message) }
    }

    private fun isRecentsBlurView(view: View): Boolean {
        val methodNames = view.javaClass.methods.mapTo(mutableSetOf()) { it.name }
        return "getAppliedPreset" in methodNames && "setAppliedPreset" in methodNames
    }

    private fun findRecentsOverlayBackdrops(root: ViewGroup): List<View> {
        fun View.isBackdrop(): Boolean {
            val methodNames = javaClass.methods.mapTo(mutableSetOf()) { it.name }
            val scrim = "setAlphaProgress" in methodNames && "setEndColor" in methodNames
            return scrim || isRecentsBlurView(this)
        }

        fun search(group: ViewGroup, result: MutableList<View>) {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child.isBackdrop()) result += child
                if (child is ViewGroup) search(child, result)
            }
        }

        return buildList { search(root, this) }
    }

    private fun releaseRecentsOverlayBackdrops(owner: Any, reason: String) {
        val root = coverRecentsOverlayRoots.remove(owner)
        val backdrops = coverRecentsBackdropViews.remove(owner)
        if (root != null || backdrops != null) {
            CoverRuntime.log(
                SCOPE,
                "display-1 Recents backdrop references released by native lifecycle " +
                    "reason=$reason views=${backdrops?.size ?: 0}"
            )
        }
    }

    private fun clearRecentsOverlayBackdropReferences(reason: String) {
        val rootCount = synchronized(coverRecentsOverlayRoots) {
            coverRecentsOverlayRoots.size.also { coverRecentsOverlayRoots.clear() }
        }
        val backdropCount = synchronized(coverRecentsBackdropViews) {
            coverRecentsBackdropViews.size.also { coverRecentsBackdropViews.clear() }
        }
        if (rootCount > 0 || backdropCount > 0) {
            CoverRuntime.log(
                SCOPE,
                "display-1 Recents backdrop references cleared " +
                    "reason=$reason roots=$rootCount owners=$backdropCount"
            )
        }
    }

    private fun cancelCoverRecentsFastFinishes(reason: String) {
        val pending = synchronized(coverRecentsFastFinishRunnables) {
            coverRecentsFastFinishRunnables.entries
                .map { it.key to it.value }
                .also { coverRecentsFastFinishRunnables.clear() }
        }
        pending.forEach { (manager, runnable) ->
            (getFieldOrNull(manager, "targetView") as? View)?.removeCallbacks(runnable)
        }
        if (pending.isNotEmpty()) {
            CoverRuntime.log(
                SCOPE,
                "display-1 Recents pending spring fast-finishes cancelled " +
                    "reason=$reason count=${pending.size}"
            )
        }
    }

    private fun installNativeRecentsActivityFirstFrame(classLoader: ClassLoader) {
        val recentsActivityClass = XposedHelpers.findClassIfExists(
            RECENTS_ACTIVITY_CLASS,
            classLoader
        ) ?: return unavailable("Recents Activity first frame", "RecentsActivity missing")

        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "setRequestedOrientation",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.javaClass.name != RECENTS_ACTIVITY_CLASS) return
                        if (fullDexActive()) return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity))) return
                        val requested = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                        if (requested != ActivityInfo.SCREEN_ORIENTATION_USER) return

                        val locked = android.provider.Settings.System.getInt(
                            activity.contentResolver,
                            android.provider.Settings.System.ACCELEROMETER_ROTATION,
                            1
                        ) == 0
                        if (locked) return
                        param.args[0] = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 RecentsActivity orientation request USER -> FULL_SENSOR"
                        )
                    }
                }
            )
            XposedBridge.hookAllMethods(
                recentsActivityClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!CoverRuntime.isCoverDisplay(
                                CoverRuntime.displayIdOf(activity)
                            )
                        ) return
                        if (fullDexActive() || isExternalDexDisplay(activity)) return

                        val converted = requestCoverRecentsTranslucency(activity)
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 RecentsActivity received transparent Window background " +
                                "before native content creation converted=$converted"
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity))) return
                        if (fullDexActive() || isExternalDexDisplay(activity)) return
                        val converted = requestCoverRecentsTranslucency(activity)
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 RecentsActivity translucency retained after native " +
                                "content creation converted=$converted"
                        )
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                recentsActivityClass,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!shouldRenderDexRecents(activity)) return
                        scheduleFullDexRecentsCards(activity)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                recentsActivityClass,
                "onConfigurationChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!shouldRenderDexRecents(activity)) return
                        scheduleFullDexRecentsCards(activity)
                    }
                }
            )
            CoverRuntime.log(SCOPE, "display-1 Recents Activity first-frame policy installed")
        }.onFailure { unavailable("Recents Activity first frame", it.message) }
    }

    private fun scheduleFullDexRecentsCards(activity: Activity) {
        activity.window.decorView.post { renderFullDexRecentsCards(activity) }
        activity.window.decorView.postDelayed({
            if (
                activity.window.decorView.findViewWithTag<View>(
                    "flexunlock-full-dex-recents"
                ) == null
            ) {
                renderFullDexRecentsCards(activity)
            }
        }, 240L)
    }

    private fun setCoverRecentsWindowTransparent(activity: Activity) {
        activity.window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        activity.window.decorView.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun renderFullDexRecentsCards(activity: Activity) {
        val containerId = activity.resources.getIdentifier(
            "taskListContainer",
            "id",
            activity.packageName
        )
        if (containerId == 0) return
        val container = activity.window.decorView.findViewById<ViewGroup>(containerId) ?: return
        val activityManager = activity.getSystemService(ActivityManager::class.java) ?: return
        val displayId = CoverRuntime.displayIdOf(activity) ?: return
        val tasks = activityManager.getRecentTasks(20, ActivityManager.RECENT_WITH_EXCLUDED)
            .filter { task ->
                val packageName = task.baseIntent?.component?.packageName
                task.id > 0 &&
                    runCatching { XposedHelpers.getIntField(task, "displayId") }
                        .getOrNull() == displayId &&
                    !packageName.isNullOrBlank() &&
                    packageName != activity.packageName &&
                    packageName != "com.android.systemui"
            }
            .distinctBy { it.id }
            .take(8)
        (container.findViewWithTag<View>("flexunlock-full-dex-recents"))?.let { oldRoot ->
            container.removeView(oldRoot)
            recycleFullDexRecentsBitmaps(oldRoot)
        }

        val width = container.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val height = container.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        val cardWidth = (width * 42 / 100).coerceAtLeast(1)
        val actionHeight = (height * 10 / 100).coerceAtLeast(48)
        val previewHeight = (height * 44 / 100).coerceAtLeast(1)
        val root = LinearLayout(activity).apply {
            tag = "flexunlock-full-dex-recents"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC202124.toInt())
            isClickable = true
            isFocusable = true
            elevation = 100f
        }
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(width / 24, 0, width / 24, 0)
        }
        actions.addView(TextView(activity).apply {
            text = if (fullDexRecentsCardMode) "仅标题" else "卡片"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener {
                fullDexRecentsCardMode = !fullDexRecentsCardMode
                renderFullDexRecentsCards(activity)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, actionHeight).apply {
            marginEnd = width / 32
        })
        actions.addView(TextView(activity).apply {
            text = "清理全部"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isEnabled = tasks.isNotEmpty()
            alpha = if (tasks.isEmpty()) 0.4f else 1f
            setOnClickListener {
                removeFullDexRecentTasks(tasks.map { task -> task.id })
                renderFullDexRecentsCards(activity)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, actionHeight))
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            actionHeight
        ))
        val cards: ViewGroup
        val row: LinearLayout
        if (fullDexRecentsCardMode) {
            cards = HorizontalScrollView(activity).apply { isFillViewport = true }
            row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(width / 24, height / 36, width / 24, height / 14)
            }
        } else {
            cards = ScrollView(activity).apply { isFillViewport = true }
            row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP
                setPadding(width / 24, height / 36, width / 24, height / 14)
            }
        }
        tasks.forEach { task ->
            val packageName = task.baseIntent?.component?.packageName ?: return@forEach
            val applicationInfo = runCatching {
                activity.packageManager.getApplicationInfo(packageName, 0)
            }.getOrNull()
            val label = task.taskDescription?.label?.toString()?.takeIf { it.isNotBlank() }
                ?: applicationInfo?.let { activity.packageManager.getApplicationLabel(it).toString() }
                ?: packageName
            val taskView = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF35363A.toInt())
                isClickable = true
                setOnClickListener {
                    launchFullDexRecentTask(activity, task.id, displayId)
                }
            }
            if (fullDexRecentsCardMode) {
                taskView.addView(ImageView(activity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val snapshot = loadFullDexTaskSnapshot(task.id)
                    if (snapshot != null) {
                        tag = snapshot
                        setImageBitmap(snapshot)
                    } else if (applicationInfo != null) {
                        setImageDrawable(activity.packageManager.getApplicationIcon(applicationInfo))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        setPadding(cardWidth / 6, previewHeight / 6, cardWidth / 6, previewHeight / 6)
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, previewHeight))
            }
            taskView.addView(TextView(activity).apply {
                text = label
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(24, 18, 24, 18)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(taskView, LinearLayout.LayoutParams(
                if (fullDexRecentsCardMode) cardWidth else ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (fullDexRecentsCardMode) {
                    marginEnd = width / 40
                } else {
                    bottomMargin = height / 80
                }
            })
        }
        cards.addView(row, ViewGroup.LayoutParams(
            if (fullDexRecentsCardMode) ViewGroup.LayoutParams.WRAP_CONTENT
            else ViewGroup.LayoutParams.MATCH_PARENT,
            if (fullDexRecentsCardMode) ViewGroup.LayoutParams.MATCH_PARENT
            else ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(cards, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        container.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        root.bringToFront()
        CoverRuntime.log(
            SCOPE,
            "display-$displayId full DeX Recents rendered count=${tasks.size} " +
                "cards=$fullDexRecentsCardMode container=${container.javaClass.name}"
        )
    }

    private fun removeFullDexRecentTasks(taskIds: List<Int>) {
        val service = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityTaskManager", null),
            "getService"
        )
        taskIds.forEach { taskId -> XposedHelpers.callMethod(service, "removeTask", taskId) }
    }

    private fun launchFullDexRecentTask(activity: Activity, taskId: Int, displayId: Int) {
        runCatching {
            val options = ActivityOptions.makeBasic().apply { setLaunchDisplayId(displayId) }
            val service = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityTaskManager", null),
                "getService"
            )
            XposedHelpers.callMethod(service, "startActivityFromRecents", taskId, options.toBundle())
            activity.finish()
            CoverRuntime.log(SCOPE, "display-$displayId full DeX recent launched task=$taskId")
        }.onFailure {
            CoverRuntime.log(
                SCOPE,
                "display-$displayId full DeX recent launch failed task=$taskId: ${it.message}"
            )
        }
    }

    private fun recycleFullDexRecentsBitmaps(view: View) {
        (view as? ImageView)?.tag?.let { bitmap ->
            if (bitmap is Bitmap && !bitmap.isRecycled) bitmap.recycle()
        }
        if (view is ViewGroup) {
            repeat(view.childCount) { recycleFullDexRecentsBitmaps(view.getChildAt(it)) }
        }
    }

    private fun loadFullDexTaskSnapshot(taskId: Int): Bitmap? = runCatching {
        val activityTaskManager = XposedHelpers.findClass("android.app.ActivityTaskManager", null)
        val service = XposedHelpers.callStaticMethod(activityTaskManager, "getService")
        val snapshot = runCatching {
            XposedHelpers.callMethod(service, "getTaskSnapshot", taskId, false, false)
        }.getOrElse {
            XposedHelpers.callMethod(service, "getTaskSnapshot", taskId, false)
        } ?: return@runCatching null
        val buffer = XposedHelpers.callMethod(snapshot, "getHardwareBuffer") as? HardwareBuffer
            ?: return@runCatching null
        val colorSpace = XposedHelpers.callMethod(snapshot, "getColorSpace") as? ColorSpace
            ?: ColorSpace.get(ColorSpace.Named.SRGB)
        Bitmap.wrapHardwareBuffer(buffer, colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
    }.getOrNull()

    private fun requestCoverRecentsTranslucency(activity: Activity): Boolean {
        if (fullDexActive()) return false
        setCoverRecentsWindowTransparent(activity)
        if (
            XposedHelpers.getAdditionalInstanceField(
                activity,
                "coverRecentsTranslucencyRequested"
            ) == true
        ) return true

        if (
            XposedHelpers.getAdditionalInstanceField(
                activity,
                "coverRecentsWindowFlagsCaptured"
            ) != true
        ) {
            val originalFlags = activity.window.attributes.flags
            XposedHelpers.setAdditionalInstanceField(
                activity,
                "coverRecentsOriginallyShowedWallpaper",
                originalFlags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0
            )
            XposedHelpers.setAdditionalInstanceField(
                activity,
                "coverRecentsOriginallyDimmedBehind",
                originalFlags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
            )
            XposedHelpers.setAdditionalInstanceField(
                activity,
                "coverRecentsWindowFlagsCaptured",
                true
            )
        }
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val method = Activity::class.java.declaredMethods.firstOrNull { candidate ->
            candidate.name == "convertToTranslucent" && candidate.parameterCount == 2
        } ?: return false
        val converted = runCatching {
            method.isAccessible = true
            (method.invoke(activity, null, null) as? Boolean) != false
        }.onFailure {
            unavailable("Recents Activity translucency", it.message)
        }.getOrDefault(false)
        if (converted) {
            XposedHelpers.setAdditionalInstanceField(
                activity,
                "coverRecentsTranslucencyRequested",
                true
            )
        }
        return converted
    }

    private fun restoreCoverRecentsTranslucency(activity: Activity, reason: String) {
        val requested = XposedHelpers.removeAdditionalInstanceField(
            activity,
            "coverRecentsTranslucencyRequested"
        ) == true
        val capturedFlags = XposedHelpers.removeAdditionalInstanceField(
            activity,
            "coverRecentsWindowFlagsCaptured"
        ) == true
        val originallyShowedWallpaper = XposedHelpers.removeAdditionalInstanceField(
            activity,
            "coverRecentsOriginallyShowedWallpaper"
        ) == true
        val originallyDimmedBehind = XposedHelpers.removeAdditionalInstanceField(
            activity,
            "coverRecentsOriginallyDimmedBehind"
        ) == true
        if (!requested && !capturedFlags) return

        if (requested) {
            runCatching {
                val method = Activity::class.java.declaredMethods.firstOrNull { candidate ->
                    candidate.name == "convertFromTranslucent" && candidate.parameterCount == 0
                } ?: return@runCatching
                method.isAccessible = true
                method.invoke(activity)
            }.onFailure {
                unavailable("Recents Activity translucency restore", it.message)
            }
        }
        if (capturedFlags && !originallyShowedWallpaper) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }
        if (capturedFlags && originallyDimmedBehind) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        } else if (capturedFlags) {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        CoverRuntime.log(
            SCOPE,
            "display-1 RecentsActivity translucency restored after native exit " +
                "reason=$reason"
        )
    }

    private fun installNativeRecentsResumeSignal(classLoader: ClassLoader) {
        val animationSessionClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.session.AnimationSession",
            classLoader
        ) ?: return unavailable("Recents lifecycle signal", "AnimationSession missing")
        val recentsAnimationManagerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.recentsanimation.RecentsAnimationManager",
            classLoader
        ) ?: return unavailable(
            "Recents lifecycle signal",
            "RecentsAnimationManager missing"
        )
        val recentsActivityClass = XposedHelpers.findClassIfExists(
            RECENTS_ACTIVITY_CLASS,
            classLoader
        ) ?: return unavailable("Recents lifecycle signal", "RecentsActivity missing")

        runCatching {
            XposedBridge.hookAllConstructors(
                animationSessionClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayId = getIntFieldOrNull(param.thisObject, "displayId") ?: return
                        if (CoverRuntime.isCoverDisplay(displayId)) {
                            coverAnimationSessions.add(param.thisObject)
                        }
                    }
                }
            )

            XposedBridge.hookAllMethods(
                animationSessionClass,
                "doAction",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val displayId = getIntFieldOrNull(param.thisObject, "displayId") ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        val eventName = param.args.firstOrNull()?.javaClass?.name ?: return
                        if (
                            eventName.endsWith("ActionEvent\$StartRecentsToggle") ||
                            eventName.endsWith("ActionEvent\$GoToRecents")
                        ) {
                            beginCoverTransitionSession(
                                param.thisObject,
                                eventName.substringAfterLast('$')
                            )
                        }
                    }
                }
            )
            XposedBridge.hookAllMethods(
                animationSessionClass,
                "onClose",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val displayId = getIntFieldOrNull(param.thisObject, "displayId") ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        endCoverTransitionSession(param.thisObject, "animation-close")
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                recentsActivityClass,
                "onResume",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!isCoverRecentsActivity(activity)) return
                        requestCoverRecentsTranslucency(activity)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!isCoverRecentsActivity(activity)) return
                        resumedCoverRecentsActivity = WeakReference(activity)
                        coverRecentsLifecycleResumed = true
                        coverRecentsDispatchCommitted = false
                        coverGestureTransitionActive = false
                        publishCoverTransitionAggregate("recents-resume-took-ownership")
                        makeCoverStatusBarTransparent(activity)
                        val signalled = setCoverRecentsShowing(true)
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 RecentsActivity real onResume synchronized " +
                                "DexRecentShowing count=$signalled"
                        )
                    }
                }
            )
            listOf("onPause", "onDestroy").forEach { methodName ->
                XposedHelpers.findAndHookMethod(
                    recentsActivityClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            if (!CoverRuntime.isCoverDisplay(
                                    CoverRuntime.displayIdOf(activity)
                                )
                            ) return
                            if (methodName == "onDestroy") {
                                restoreCoverRecentsTranslucency(activity, methodName)
                                clearRecentsOverlayBackdropReferences("recents-$methodName")
                            }
                            cancelCoverRecentsFastFinishes("recents-$methodName")
                            if (resumedCoverRecentsActivity.get() === activity) {
                                coverRecentsLifecycleResumed = false
                                resumedCoverRecentsActivity.clear()
                                setCoverRecentsShowing(false)
                                clearCoverTransitions("recents-$methodName")
                                coverScreenManagers.toList().forEach { manager ->
                                    workspaceTransitionTargets.remove(manager)
                                    refreshAllCoverScreenPresentations(manager)
                                }
                                clearCoverScreenPresentation("RECENTS")
                                publishAllCoverWorkspaceStates("recents-$methodName", force = true)
                            }
                        }
                    }
                )
            }
            XposedHelpers.findAndHookMethod(
                recentsActivityClass,
                "onStop",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!CoverRuntime.isCoverDisplay(
                                CoverRuntime.displayIdOf(activity)
                            )
                        ) return
                        restoreCoverRecentsTranslucency(activity, "onStop")
                    }
                }
            )

            runCatching {
                XposedBridge.hookAllConstructors(
                    recentsAnimationManagerClass,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            coverRecentsStageManager = param.thisObject
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 Recents stage manager captured via constructor"
                            )
                        }
                    }
                )
            }.onFailure { }
            val stageMethods = recentsAnimationManagerClass.declaredMethods.filter { method ->
                method.name == "startRecentsActivityInternal" && method.parameterCount == 0
            }
            stageMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching {
                            coverRecentsStageManager = param.thisObject
                        }
                        val displayId = getIntFieldOrNull(param.thisObject, "displayId") ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        val activity = resumedCoverRecentsActivity.get() ?: return
                        if (!isCoverRecentsActivity(activity)) return

                        activity.window?.decorView?.post {
                            if (!isCoverRecentsLifecycleResumed()) return@post
                            val signalled = setCoverRecentsShowing(true)
                            if (signalled > 0) {
                                CoverRuntime.log(
                                    SCOPE,
                                    "display-1 real Recents top-resumed state synchronized " +
                                        "after native stage-6 count=$signalled"
                                )
                            }
                        }
                    }
                })
            }

            val pauseWaitMethods = animationSessionClass.declaredMethods.filter { method ->
                method.name == "waitForTaskToPauseCompletely" && method.parameterCount == 1
            }
            pauseWaitMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val displayId = getIntFieldOrNull(param.thisObject, "displayId") ?: return
                        if (!isClosedCoverDisplay(displayId)) return
                        if (!isCoverRecentsLifecycleResumed()) return

                        param.result = Unit
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 stale task-pause wait resolved by real " +
                                "Recents lifecycle-resumed state"
                        )
                    }
                })
            }

            CoverRuntime.log(
                SCOPE,
                "display-1 native Recents lifecycle synchronization installed " +
                    "stageMethods=${stageMethods.size}, pauseWaitMethods=${pauseWaitMethods.size}"
            )
        }.onFailure { unavailable("Recents lifecycle signal", it.message) }
    }

    private fun isCoverRecentsActivity(activity: Activity): Boolean =
        !fullDexActive() &&
            CoverRuntime.isCoverSessionEligible() &&
            CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity)) &&
            !activity.isFinishing &&
            !activity.isDestroyed

    private fun isCoverRecentsLifecycleResumed(): Boolean {
        val activity = resumedCoverRecentsActivity.get() ?: return false
        return coverRecentsLifecycleResumed && isCoverRecentsActivity(activity)
    }

    private fun beginCoverTransitionSession(session: Any, reason: String) {
        activeCoverTransitionSessions.add(session)
        coverRecentsDispatchCommitted = false
        coverGestureTransitionActive = false
        publishCoverTransitionAggregate("$reason-session-took-ownership")
    }

    private fun endCoverTransitionSession(session: Any, reason: String) {
        activeCoverTransitionSessions.remove(session)
        publishCoverTransitionAggregate(reason)
    }

    private fun beginCoverGestureTransition(reason: String) {
        coverGestureTransitionActive = true
        publishCoverTransitionAggregate(reason)
    }

    private fun cancelCoverGestureTransition(reason: String) {
        coverRecentsDispatchCommitted = false
        if (!coverGestureTransitionActive) return
        coverGestureTransitionActive = false
        publishCoverTransitionAggregate(reason)
    }

    private fun finishCoverGestureInput(reason: String, recentsDispatchCommitted: Boolean) {
        if (!coverGestureTransitionActive) return
        if (activeCoverTransitionSessions.isNotEmpty() || coverRecentsLifecycleResumed) {
            coverGestureTransitionActive = false
            publishCoverTransitionAggregate("$reason-native-session-owns-transition")
            return
        }
        if (recentsDispatchCommitted || coverRecentsDispatchCommitted) {
            CoverRuntime.log(
                SCOPE,
                "display-1 RECENT input committed; transition owner retained for native session"
            )
            return
        }
        val launcher = resumedSecondaryLauncher.get()
        val launcherOwnsHome = launcher != null &&
            isCoverSecondaryLauncher(launcher) &&
            secondaryLauncherResumed &&
            secondaryLauncherFocused &&
            launcher.hasWindowFocus()
        if (launcherOwnsHome) {
            completeCoverGestureTransition("$reason-launcher-owns-home")
            return
        }
        CoverRuntime.log(
            SCOPE,
            "display-1 gesture input ended; transition owner retained until " +
                "native session, authoritative Home, or Launcher focus"
        )
    }

    private fun completeCoverGestureTransition(reason: String) {
        coverRecentsDispatchCommitted = false
        if (!coverGestureTransitionActive) return
        coverGestureTransitionActive = false
        publishCoverTransitionAggregate(reason)
    }

    private fun clearCoverTransitions(reason: String) {
        activeCoverTransitionSessions.clear()
        coverRecentsDispatchCommitted = false
        coverGestureTransitionActive = false
        publishCoverTransitionAggregate(reason)
    }

    private fun publishCoverTransitionAggregate(reason: String) {
        publishCoverRecentsTransition(
            activeCoverTransitionSessions.isNotEmpty() ||
                coverGestureTransitionActive ||
                coverRecentsLifecycleResumed,
            reason
        )
    }

    private fun publishCoverRecentsTransition(active: Boolean, reason: String) {
        if (publishedCoverRecentsTransitionActive == active) return
        publishedCoverRecentsTransitionActive = active
        val context = AndroidAppHelper.currentApplication() ?: return
        context.sendBroadcast(
            Intent(CoverRuntime.COVER_RECENTS_TRANSITION_ACTION).apply {
                setPackage(CoverRuntime.SYSTEM_UI_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(CoverRuntime.EXTRA_COVER_RECENTS_TRANSITION_ACTIVE, active)
                putExtra(CoverRuntime.EXTRA_COVER_RECENTS_TRANSITION_REASON, reason)
            }
        )
        CoverRuntime.log(
            SCOPE,
            "display-1 native Recents transition active=$active reason=$reason"
        )
    }

    private fun setCoverRecentsShowing(showing: Boolean): Int {
        var signalled = 0
        coverAnimationSessions.toList().forEach { session ->
            val displayId = getIntFieldOrNull(session, "displayId") ?: return@forEach
            if (!isClosedCoverDisplay(displayId)) return@forEach
            val recentInteraction = getFieldOrNull(session, "recentInteraction")
                ?: return@forEach
            val recentsStateSource = getFieldOrNull(recentInteraction, "recentsStateSource")
                ?: return@forEach
            val state = getFieldOrNull(recentsStateSource, "_showing")
                ?: return@forEach
            runCatching {
                val previous = XposedHelpers.callMethod(state, "getValue") as? Boolean
                XposedHelpers.callMethod(state, "setValue", showing)
                if (previous != showing) signalled++
            }.onFailure {
                unavailable("DexRecentShowing lifecycle synchronization apply", it.message)
            }
        }
        return signalled
    }

    private fun installNativeKeyPressDisplayRoute(classLoader: ClassLoader) {
        val keyPressEventClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.entity.SettledEvent\$KeyPressEvent",
            classLoader
        ) ?: return unavailable("Samsung key-press display route", "KeyPressEvent missing")

        runCatching {
            val constructors = keyPressEventClass.declaredConstructors.filter { constructor ->
                constructor.parameterTypes.contentEquals(
                    arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                )
            }
            constructors.forEach { constructor ->
                constructor.isAccessible = true
                XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if ((nativeCoverGestureDispatchDepth.get() ?: 0) <= 0) return
                        if (!CoverRuntime.isCoverSessionEligible()) return
                        val args = param.args ?: return
                        val keyCode = (args.firstOrNull() as? Number)?.toInt() ?: return
                        val displayId = (args.getOrNull(1) as? Number)?.toInt() ?: return
                        if (displayId != 0) return
                        val coverDisplayId = CoverDisplayResolver.currentId() ?: return
                        args[1] = coverDisplayId
                        if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
                            synchronized(nativeGestureDispatchLock) {
                                coverRecentsDispatchCommitted = true
                            }
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 native KeyPressEvent observed keyCode=$keyCode " +
                                    "display=$displayId->$coverDisplayId"
                            )
                        }
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 native KeyPressEvent route installed constructors=${constructors.size}"
            )
        }.onFailure { unavailable("Samsung key-press display route", it.message) }
    }

    private fun installNativeExtraDisplayRegionKeyRoute(classLoader: ClassLoader) {
        val handlerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.extradisplaygesture.ExtraDisplayInputHandler",
            classLoader
        ) ?: return unavailable(
            "Samsung extra-display region key route",
            "ExtraDisplayInputHandler missing"
        )

        runCatching {
            val methods = handlerClass.declaredMethods.filter { method ->
                method.name == "getKeyCode" &&
                    method.parameterCount == 0 &&
                    method.returnType == Int::class.javaPrimitiveType
            }
            if (methods.isEmpty()) {
                return@runCatching unavailable(
                    "Samsung extra-display region key route",
                    "getKeyCode() shape unavailable"
                )
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isCoverSessionEligible()) return
                        if (
                            XposedHelpers.getAdditionalInstanceField(
                                param.thisObject,
                                "flexunlock_topdown_start"
                            ) != null
                        ) {
                            param.result = KeyEvent.KEYCODE_UNKNOWN
                            return
                        }
                        val context = getFieldOrNull(param.thisObject, "context") as? Context
                            ?: return
                        val geometry = currentCoverDisplayGeometry(context) ?: return
                        val fullQs = CoverQsModeConfig.readTransaction(context).isStableFull
                        if (!fullQs && geometry.rotation != android.view.Surface.ROTATION_180) return
                        val regionType = activeNativeGestureRegionType ?: return
                        if (regionType == "RECENT") {
                            val quickPanelOwnsInput = coverQuickPanelBlocksRecents ||
                                SystemClock.elapsedRealtime() < coverQuickPanelRecentsBlockedUntil
                            if (quickPanelOwnsInput) {
                                param.result = KeyEvent.KEYCODE_UNKNOWN
                                param.setObjectExtra("coverRotation180KeyRouted", true)
                                coverRecentsDispatchCommitted = false
                                cancelCoverGestureTransition("quick-panel-recents-suppressed")
                                CoverRuntime.log(
                                    SCOPE,
                                    "display-1 RECENT key suppressed while QuickPanel owns input"
                                )
                                return
                            }
                        }
                        val keyCode = when (regionType) {
                            "RECENT" -> KeyEvent.KEYCODE_APP_SWITCH
                            "HOME" -> KeyEvent.KEYCODE_HOME
                            "BACK" -> KeyEvent.KEYCODE_BACK
                            else -> return
                        }
                        param.result = keyCode
                        param.setObjectExtra("coverRotation180KeyRouted", true)
                        if (regionType == "RECENT") {
                            coverRecentsDispatchCommitted = true
                            if (!coverGestureTransitionActive) {
                                beginCoverGestureTransition("recent-key-committed")
                            }
                        }
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 ${if (fullQs) "full-QS" else "rotation-180"} " +
                                "$regionType key committed keyCode=$keyCode"
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (
                            XposedHelpers.getAdditionalInstanceField(
                                param.thisObject,
                                "flexunlock_topdown_start"
                            ) != null
                        ) {
                            param.result = KeyEvent.KEYCODE_UNKNOWN
                            return
                        }
                        if (param.getObjectExtra("coverRotation180KeyRouted") == true) return
                        if (!CoverRuntime.isCoverSessionEligible()) return
                        if (activeNativeGestureRegionType != "RECENT") return
                        val quickPanelOwnsInput = coverQuickPanelBlocksRecents ||
                            SystemClock.elapsedRealtime() < coverQuickPanelRecentsBlockedUntil
                        if (quickPanelOwnsInput) {
                            param.result = KeyEvent.KEYCODE_UNKNOWN
                            coverRecentsDispatchCommitted = false
                            cancelCoverGestureTransition("quick-panel-recents-suppressed")
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 RECENT key suppressed while QuickPanel owns input"
                            )
                            return
                        }
                        val original = (param.result as? Number)?.toInt() ?: return
                        if (
                            original != KeyEvent.KEYCODE_HOME &&
                            original != KeyEvent.KEYCODE_APP_SWITCH
                        ) return
                        if (original == KeyEvent.KEYCODE_HOME) {
                            param.result = KeyEvent.KEYCODE_APP_SWITCH
                        }
                        coverRecentsDispatchCommitted = true
                        if (!coverGestureTransitionActive) {
                            beginCoverGestureTransition("recent-key-committed")
                        }
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 native RECENT key committed keyCode=" +
                                "$original->${KeyEvent.KEYCODE_APP_SWITCH}"
                        )
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 extra-display region key route installed methods=${methods.size}"
            )
        }.onFailure {
            unavailable("Samsung extra-display region key route", it.message)
        }
    }

    private fun installFullQsKeyInjectionRoute(classLoader: ClassLoader) {
        val injectorClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.keyinject.KeyInjectorImpl",
            classLoader
        ) ?: return unavailable("full QS key injection", "KeyInjectorImpl missing")
        XposedBridge.hookAllMethods(injectorClass, "injectKey", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = launcherContext() ?: return
                val keyCode = (param.args.firstOrNull() as? Number)?.toInt() ?: return
                val resumedActivity = resumedFullQsActivity.get()
                if (
                    keyCode == KeyEvent.KEYCODE_BACK &&
                    resumedActivity != null &&
                    isManagedExternalRecents(resumedActivity)
                ) {
                    param.result = null
                    resumedActivity.runOnUiThread(resumedActivity::finish)
                    CoverRuntime.log(SCOPE, "external Recents BACK finished activity")
                    return
                }
                if (!CoverQsModeConfig.readTransaction(context).isStableFull) return
                if (keyCode == KeyEvent.KEYCODE_BACK && resumedActivity != null) {
                    param.result = null
                    if (resumedActivity.javaClass.name == RECENTS_ACTIVITY_CLASS) {
                        resumedActivity.runOnUiThread(resumedActivity::finish)
                        CoverRuntime.log(SCOPE, "full QS Recents BACK finished activity")
                    } else {
                        CoverRuntime.log(SCOPE, "full QS Home BACK suppressed")
                    }
                    return
                }
                val sourceDisplayId = (param.args.getOrNull(2) as? Number)?.toInt() ?: return
                val coverDisplayId = CoverDisplayResolver.interactionDisplayId(sourceDisplayId)
                    ?: return
                if (sourceDisplayId == coverDisplayId) return
                param.args[2] = coverDisplayId
                CoverRuntime.log(
                    SCOPE,
                    "full QS key injection display=$sourceDisplayId->$coverDisplayId " +
                        "keyCode=${param.args.firstOrNull()}"
                )
            }
        })
        CoverRuntime.log(SCOPE, "full QS key injection route installed")
    }

    private fun installFullQsActivityLifecycle() {
        XposedBridge.hookAllMethods(Activity::class.java, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                if (
                    activity.javaClass.name != LAUNCHER_ACTIVITY_CLASS &&
                    activity.javaClass.name != RECENTS_ACTIVITY_CLASS
                ) return
                val stableFullQs =
                    CoverQsModeConfig.readTransaction(activity).isStableFull &&
                        CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity))
                if (!stableFullQs && !isManagedExternalRecents(activity)) return
                resumedFullQsActivity = WeakReference(activity)
                if (activity.javaClass.name == LAUNCHER_ACTIVITY_CLASS && stableFullQs) {
                    val fullDex = fullDexSettingEnabled(activity)
                    applyFullDexStatusBar(activity, fullDex)
                    if (!fullDex) makeCoverStatusBarTransparent(activity)
                    bindCoverDrawerMenuLayout(activity)
                    activity.window.decorView.post { applyCoverDrawerMenuLayout(activity) }
                }
                CoverRuntime.log(
                    SCOPE,
                    "full QS resumed activity=${activity.javaClass.simpleName}"
                )
            }
        })
        listOf("onPause", "onDestroy").forEach { methodName ->
            XposedBridge.hookAllMethods(
                Activity::class.java,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (resumedFullQsActivity.get() === activity) {
                            resumedFullQsActivity.clear()
                        }
                    }
                }
            )
        }
        listOf("onWindowFocusChanged", "onConfigurationChanged").forEach { methodName ->
            XposedBridge.hookAllMethods(
                Activity::class.java,
                methodName,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (activity.javaClass.name != LAUNCHER_ACTIVITY_CLASS) return
                        if (!CoverQsModeConfig.readTransaction(activity).isStableFull) return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity))) return
                        if (methodName == "onWindowFocusChanged" && param.args.firstOrNull() != true) return
                        val fullDex = fullDexSettingEnabled(activity)
                        applyFullDexStatusBar(activity, fullDex)
                        if (!fullDex) makeCoverStatusBarTransparent(activity)
                    }
                }
            )
        }
        CoverRuntime.log(SCOPE, "full QS activity lifecycle installed")
    }

    private fun installManagedExternalRecentsBackRoute(classLoader: ClassLoader) {
        val recentsClass = XposedHelpers.findClassIfExists(RECENTS_ACTIVITY_CLASS, classLoader)
            ?: return unavailable("external Recents BACK", "RecentsActivity missing")
        val backMethods = listOfNotNull(
            runCatching { recentsClass.getMethod("onBackPressed") }.getOrNull()
        )
        backMethods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!isManagedExternalRecents(activity)) return
                    activity.finish()
                    param.result = null
                    CoverRuntime.log(SCOPE, "external Recents onBackPressed finished activity")
                }
            })
        }
        val keyMethods = listOfNotNull(
            runCatching {
                recentsClass.getMethod("dispatchKeyEvent", KeyEvent::class.java)
            }.getOrNull()
        )
        keyMethods.forEach { method ->
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!isManagedExternalRecents(activity)) return
                    val event = param.args.firstOrNull() as? KeyEvent ?: return
                    if (event.keyCode != KeyEvent.KEYCODE_BACK) return
                    if (event.action == KeyEvent.ACTION_UP) activity.finish()
                    param.result = true
                }
            })
        }
        CoverRuntime.log(
            SCOPE,
            "external Recents BACK route installed " +
                "backMethods=${backMethods.size} keyMethods=${keyMethods.size}"
        )
    }

    private fun isManagedExternalRecents(activity: Activity): Boolean =
        shouldFinishManagedExternalRecents(
            activity.javaClass.name == RECENTS_ACTIVITY_CLASS,
            isExternalDexDisplay(activity)
        )

    private fun shouldRenderDexRecents(activity: Activity): Boolean {
        val displayId = CoverRuntime.displayIdOf(activity)
        return (CoverRuntime.isCoverDisplay(displayId) && fullDexActive()) ||
            isExternalDexDisplay(activity)
    }

    private fun isExternalDexDisplay(activity: Activity): Boolean {
        val display = activity.display ?: return false
        val type = runCatching {
            (XposedHelpers.callMethod(display, "getType") as Number).toInt()
        }.getOrNull() ?: return false
        if (type == 2 || type == 3 || type == 6) return true
        val uniqueId = runCatching {
            XposedHelpers.callMethod(display, "getUniqueId") as? String
        }.getOrNull()
        return type == 5 && uniqueId?.startsWith(
            "virtual:com.android.shell,2000,scrcpy,",
            ignoreCase = true
        ) == true
    }

    private fun installFullQsRecentsPolicy(classLoader: ClassLoader) {
        val topTaskClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.usecase.TopTaskUseCase",
            classLoader
        ) ?: return unavailable("full QS Recents policy", "TopTaskUseCase missing")
        XposedBridge.hookAllMethods(topTaskClass, "isHomeTask", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result != true) return
                val context = launcherContext() ?: return
                if (!CoverQsModeConfig.readTransaction(context).isStableFull) return
                val recentsCaller = Throwable().stackTrace.any { frame ->
                    frame.className.contains("AnimationSession") &&
                        (
                            frame.methodName == "toggleRecentsView" ||
                                frame.className.contains("toggleRecentsView")
                            )
                }
                if (!recentsCaller) return
                param.result = false
                CoverRuntime.log(
                    SCOPE,
                    "full QS Recents bypassed homeIsOnTop early return"
                )
            }
        })
        CoverRuntime.log(SCOPE, "full QS Recents policy installed")
    }

    private fun installNativeGestureMonitorOwnership(classLoader: ClassLoader) {
        val handlerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.GestureInputHandler",
            classLoader
        ) ?: return unavailable("Samsung gesture monitor ownership", "GestureInputHandler missing")
        val monitorClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.shared.system.InputMonitorCompat",
            classLoader
        ) ?: return unavailable("Samsung gesture monitor ownership", "InputMonitorCompat missing")
        val receiverClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.shared.system.InputChannelCompat\$InputEventReceiver",
            classLoader
        ) ?: return unavailable("Samsung gesture monitor ownership", "InputEventReceiver missing")

        runCatching {
            XposedHelpers.findAndHookMethod(
                handlerClass,
                "setMonitor",
                monitorClass,
                receiverClass,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val handler = param.thisObject ?: return
                        val handlerDisplayId = getIntFieldOrNull(handler, "displayId") ?: return
                        if (!isClosedCoverDisplay(handlerDisplayId)) return

                        val monitor = param.args?.getOrNull(0) ?: return
                        val receiver = param.args?.getOrNull(1) ?: return
                        val owner = inputReceiverListener(receiver)
                        val discriminator = owner?.let { getIntFieldOrNull(it, "c") }
                        val source = owner?.let { getFieldOrNull(it, "e") }
                        val sourceDisplayId = source?.let { getIntFieldOrNull(it, "a") }

                        if (!CoverRuntime.isCoverDisplay(sourceDisplayId)) return

                        if (discriminator == EXTRA_SWIPE_INPUT_RECEIVER_DISCRIMINATOR) {
                            if (suppressedExtraMonitorBindings.add(receiver)) {
                                CoverRuntime.log(
                                    SCOPE,
                                    "preserved Samsung extra-swipe monitor as a fallback input source; " +
                                        "event-level deduplication selects one native dispatcher " +
                                        "owner=${owner?.javaClass?.name} discriminator=$discriminator"
                                )
                            }
                            return
                        }
                        if (!CoverRuntime.isCoverDisplay(sourceDisplayId)) return

                        val previousMonitor = nativeCoverMonitorBindings.put(handler, monitor)
                        if (previousMonitor !== monitor) {
                            CoverRuntime.log(
                                SCOPE,
                                "accepted display-1 primary gesture monitor " +
                                    "owner=${owner?.javaClass?.name} sourceDisplay=$sourceDisplayId " +
                                    "monitor=${monitorName(monitor)}"
                            )
                        }
                    }
                }
            )
            CoverRuntime.log(SCOPE, "display-1 gesture monitor ownership installed")
        }.onFailure { unavailable("Samsung gesture monitor ownership", it.message) }
    }

    private fun installNativeInputReceiverOwnership(classLoader: ClassLoader) {
        val listenerClass = findCompatClass(
            classLoader = classLoader,
            v85Names = listOf("ob.h"),
            v8Names = listOf("com.honeyspace.gesture.GestureInputHandler", "ob.h"),
            v7Names = listOf("ob.h")
        ) { candidate ->
            candidate.declaredFields.any { it.type == Int::class.javaPrimitiveType } &&
                candidate.declaredMethods.any { method ->
                    method.name == "onInputEvent" &&
                        method.parameterCount == 1 &&
                        method.parameterTypes[0].isAssignableFrom(MotionEvent::class.java)
                }
        } ?: return unavailable(
            "Samsung input receiver ownership",
            "single-event listener shape unavailable"
        )

        runCatching {
            val discriminatorFields = listenerClass.declaredFields.filter { field ->
                field.type == Int::class.javaPrimitiveType
            }.onEach { field -> field.isAccessible = true }
            val methods = listenerClass.declaredMethods.filter { method ->
                method.name == "onInputEvent" &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0].isAssignableFrom(MotionEvent::class.java)
            }
            if (methods.isEmpty() || discriminatorFields.isEmpty()) {
                return@runCatching unavailable(
                    "Samsung input receiver ownership",
                    "methods=${methods.size}, discriminatorFields=${discriminatorFields.size}"
                )
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isCoverSessionEligible()) return
                        if (param.args?.firstOrNull() !is MotionEvent) return

                        val receiver = param.thisObject ?: return
                        val discriminator = discriminatorFields.firstNotNullOfOrNull { field ->
                            runCatching { field.getInt(receiver) }.getOrNull()
                        } ?: return unavailable(
                            "Samsung input receiver ownership",
                            "discriminator unavailable"
                        )
                        val source = getFieldOrNull(receiver, "e")
                        val sourceDisplayId = source?.let { getIntFieldOrNull(it, "a") }
                        if (CoverRuntime.isCoverDisplay(sourceDisplayId) && isKeyguardLocked()) {
                            cancelCoverGestureTransition("keyguard-input")
                            return
                        }
                        if (!CoverRuntime.isCoverDisplay(sourceDisplayId)) return

                        if (discriminator == EXTRA_SWIPE_INPUT_RECEIVER_DISCRIMINATOR) {
                            if (nativeCoverInputReceivers.add(receiver)) {
                                CoverRuntime.log(
                                    SCOPE,
                                    "accepted extra-swipe display-1 fallback receiver " +
                                        "discriminator=$discriminator"
                                )
                            }
                            return
                        }
                        if (nativeCoverInputReceivers.add(receiver)) {
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 native input owner accepted " +
                                    "discriminator=$discriminator sourceDisplay=$sourceDisplayId"
                            )
                        }
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 native input ownership installed " +
                    "methods=${methods.size}, discriminatorFields=${discriminatorFields.size}"
            )
        }.onFailure { unavailable("Samsung input receiver ownership", it.message) }
    }

    private fun installNativeGestureInputRoute(classLoader: ClassLoader) {
        val handlerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.GestureInputHandler",
            classLoader
        ) ?: return unavailable("Samsung gesture input", "GestureInputHandler missing")

        runCatching {
            val methods = handlerClass.declaredMethods.filter { method ->
                method.name == "onInputEvent" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType &&
                    method.parameterTypes.getOrNull(1)?.isAssignableFrom(MotionEvent::class.java) == true
            }
            if (methods.isEmpty()) {
                return@runCatching unavailable(
                    "Samsung gesture input",
                    "onInputEvent(Int, InputEvent) shape unavailable"
                )
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        val handler = param.thisObject ?: return
                        val displayId = (args.firstOrNull() as? Number)?.toInt() ?: return
                        val event = args.getOrNull(1) as? MotionEvent ?: return
                        val handlerDisplayId = getIntFieldOrNull(handler, "displayId")
                        val eventDisplayId = runCatching {
                            (XposedHelpers.callMethod(event, "getDisplayId") as? Number)?.toInt()
                        }.getOrNull()
                        if (
                            CoverRuntime.isCoverDisplay(eventDisplayId) &&
                            isKeyguardLocked()
                        ) {
                            cancelCoverGestureTransition("keyguard-input")
                            return
                        }
                        if (
                            CoverRuntime.isCoverSessionEligible() &&
                            CoverRuntime.isCoverDisplay(eventDisplayId) &&
                            (
                                !CoverRuntime.isCoverDisplay(displayId) ||
                                    !CoverRuntime.isCoverDisplay(handlerDisplayId)
                            )
                        ) {
                            param.result = null
                            if (suppressedCrossDisplayGestureHandlers.add(handler)) {
                                CoverRuntime.log(
                                    SCOPE,
                                    "suppressed display-$displayId callback from " +
                                        "display-$handlerDisplayId handler for display-1 input"
                                )
                            }
                            return
                        }
                        if (!isClosedCoverDisplay(displayId)) return
                        if (handlerDisplayId != displayId) return
                        if ((event.flags and BYPASSABLE_WINDOW_EVENT_FLAG) != 0) return
                        val context = getFieldOrNull(handler, "context") as? Context
                        val fullQs = context?.let {
                            CoverQsModeConfig.readTransaction(it).isStableFull
                        } == true
                        if (fullQs) {
                            param.setObjectExtra("flexunlockFullQsGesture", true)
                        }
                        val regionType = if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            nativeGestureRegionType(handler, event)
                        } else {
                            null
                        }
                        val display = context?.display
                        val rotation = context?.let(::currentCoverDisplayGeometry)?.rotation
                            ?: display?.rotation
                            ?: 0
                        val coverHeight = display?.height
                            ?: context?.resources?.displayMetrics?.heightPixels
                            ?: 0
                        val qsEdgePullDown = event.actionMasked == MotionEvent.ACTION_DOWN &&
                            coverQsEdgePullFromTop(
                                rotation,
                                event.y,
                                COVER_QS_NATIVE_PULL_ZONE_PX,
                                coverHeight
                            )
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            if (qsEdgePullDown) {
                                XposedHelpers.setAdditionalInstanceField(
                                    handler,
                                    "flexunlockNativeQsPassThrough",
                                    true
                                )
                            } else {
                                XposedHelpers.removeAdditionalInstanceField(
                                    handler,
                                    "flexunlockNativeQsPassThrough"
                                )
                            }
                        }
                        val qsPassThrough = qsEdgePullDown ||
                            XposedHelpers.getAdditionalInstanceField(
                                handler,
                                "flexunlockNativeQsPassThrough"
                            ) == true
                        if (qsPassThrough) {
                            param.result = null
                            activeNativeGestureRegionType = null
                            if (
                                event.actionMasked == MotionEvent.ACTION_UP ||
                                event.actionMasked == MotionEvent.ACTION_CANCEL
                            ) {
                                XposedHelpers.removeAdditionalInstanceField(
                                    handler,
                                    "flexunlockNativeQsPassThrough"
                                )
                            }
                            return
                        }
                        if (
                            event.actionMasked == MotionEvent.ACTION_DOWN &&
                            regionType == null
                        ) {
                            param.result = null
                            return
                        }
                        if (!claimNativeGestureEvent(handler, event)) {
                            param.result = null
                            return
                        }
                        param.setObjectExtra("flexunlockNativeGestureAccepted", true)
                        val depth = nativeCoverGestureDispatchDepth.get() ?: 0
                        param.setObjectExtra("flexunlockNativeGestureDepth", depth)
                        nativeCoverGestureDispatchDepth.set(depth + 1)
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            synchronized(nativeGestureDispatchLock) {
                                recentsStageGestureToken = event.downTime
                                recentsStageLaunchPending = false
                                coverRecentsDispatchCommitted = false
                            }
                            nativeGestureDownRawX = event.rawX
                            nativeGestureDownRawY = event.rawY
                            val binding = currentGestureMonitorBinding(handler)
                            activeNativeGestureRegionType = regionType
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 native gesture begin x=${event.x.toInt()} " +
                                    "y=${event.y.toInt()} region=$regionType " +
                                    "monitor=${binding.monitorName} " +
                                    "owner=${binding.ownerClass} discriminator=${binding.discriminator}"
                            )
                        }
                        if (shouldToggleCoverRecentsDirectly(fullQs, activeNativeGestureRegionType)) {
                            param.setObjectExtra("flexunlockDirectRecentGesture", true)
                            param.result = null
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.getObjectExtra("flexunlockNativeGestureAccepted") != true) return
                        val depth = param.getObjectExtra("flexunlockNativeGestureDepth") as? Int ?: 0
                        if (depth == 0) {
                            nativeCoverGestureDispatchDepth.remove()
                        } else {
                            nativeCoverGestureDispatchDepth.set(depth)
                        }
                        val handler = param.thisObject ?: return
                        val event = param.args?.getOrNull(1) as? MotionEvent ?: return
                        when (event.actionMasked) {
                            MotionEvent.ACTION_UP -> {
                                CoverRuntime.log(
                                    SCOPE,
                                    "display-1 native gesture onInputEvent completed result=" +
                                        (param.result?.toString() ?: "void") +
                                        " launcherFocus=$secondaryLauncherFocused " +
                                        " sessions=${activeCoverTransitionSessions.size} " +
                                        "recentsResumed=$coverRecentsLifecycleResumed"
                                )
                                // Keep the native HOME/BACK zones untouched. Only
                                // provide the fallback Recents launch for the zone
                                // Samsung maps to Recents.
                                val dyUp = event.rawY - nativeGestureDownRawY
                                val dxUp = event.rawX - nativeGestureDownRawX
                                val verticalUp = dyUp < -150f &&
                                    kotlin.math.abs(dxUp) < kotlin.math.abs(dyUp)
                                if (
                                    verticalUp &&
                                    activeNativeGestureRegionType == "RECENT" &&
                                    !coverRecentsDispatchCommitted
                                ) {
                                    val gestureToken = event.downTime
                                    if (param.getObjectExtra("flexunlockDirectRecentGesture") == true) {
                                        synchronized(nativeGestureDispatchLock) {
                                            coverRecentsDispatchCommitted = true
                                        }
                                        if (!coverGestureTransitionActive) {
                                            beginCoverGestureTransition("recent-toggle-committed")
                                        }
                                        runCatching {
                                            XposedHelpers.callMethod(handler, "toggleRecents")
                                        }.onSuccess {
                                            CoverRuntime.log(
                                                SCOPE,
                                                "display-1 RECENT gesture toggled without Home transition"
                                            )
                                        }.onFailure { error ->
                                            synchronized(nativeGestureDispatchLock) {
                                                coverRecentsDispatchCommitted = false
                                            }
                                            scheduleCoverRecentsStageLaunch(handler, gestureToken)
                                            CoverRuntime.log(
                                                SCOPE,
                                                "display-1 RECENT toggle failed; stage fallback scheduled: " +
                                                    error.message
                                            )
                                        }
                                    } else {
                                        CoverRuntime.log(
                                            SCOPE,
                                            "display-1 vertical-up detected; waiting for native " +
                                                "Recents key before fallback"
                                        )
                                        Handler(Looper.getMainLooper()).postDelayed(
                                            {
                                                val shouldFallback = synchronized(nativeGestureDispatchLock) {
                                                    recentsStageGestureToken == gestureToken &&
                                                        !coverRecentsDispatchCommitted
                                                }
                                                if (shouldFallback) {
                                                    scheduleCoverRecentsStageLaunch(handler, gestureToken)
                                                }
                                            },
                                            RECENTS_NATIVE_KEY_WAIT_MS
                                        )
                                    }
                                } else if (
                                    verticalUp &&
                                    activeNativeGestureRegionType == "HOME" &&
                                    !fullDexActive()
                                ) {
                                    coverScreenManagers.toList().forEach { manager ->
                                        reconcileCoverWorkspaceToHome(
                                            manager,
                                            "home-gesture",
                                            force = true
                                        )
                                    }
                                }
                                finishCoverGestureInput(
                                    "gesture-up",
                                    recentsDispatchCommitted = activeNativeGestureRegionType == "RECENT"
                                )
                                releaseNativeGesture(handler, event)
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                synchronized(nativeGestureDispatchLock) {
                                    if (recentsStageGestureToken == event.downTime) {
                                        recentsStageGestureToken = Long.MIN_VALUE
                                        recentsStageLaunchPending = false
                                        coverRecentsDispatchCommitted = false
                                    }
                                }
                                cancelCoverGestureTransition("gesture-cancel")
                                releaseNativeGesture(handler, event)
                            }
                        }
                        if (nativeGestureHandlers.add(handler)) {
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 input uses native InputSession consumer chain"
                            )
                        }
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 native Samsung input route installed methods=${methods.size}"
            )
        }.onFailure { unavailable("Samsung gesture input", it.message) }
    }

    private fun claimNativeGestureEvent(handler: Any, event: MotionEvent): Boolean =
        synchronized(nativeGestureDispatchLock) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (activeNativeGestureDownTime != event.downTime) {
                    activeNativeGestureHandler = handler
                    activeNativeGestureDownTime = event.downTime
                    lastNativeGestureDownTime = Long.MIN_VALUE
                    lastNativeGestureEventTime = Long.MIN_VALUE
                    lastNativeGestureAction = Int.MIN_VALUE
                }
            }
            if (
                activeNativeGestureHandler !== handler ||
                activeNativeGestureDownTime != event.downTime
            ) {
                return@synchronized false
            }
            val duplicate = lastNativeGestureDownTime == event.downTime &&
                lastNativeGestureEventTime == event.eventTime &&
                lastNativeGestureAction == event.action
            if (!duplicate) {
                lastNativeGestureDownTime = event.downTime
                lastNativeGestureEventTime = event.eventTime
                lastNativeGestureAction = event.action
            }
            !duplicate
        }

    private fun releaseNativeGesture(handler: Any, event: MotionEvent) {
        synchronized(nativeGestureDispatchLock) {
            if (
                activeNativeGestureHandler === handler &&
                activeNativeGestureDownTime == event.downTime
            ) {
                activeNativeGestureHandler = null
                activeNativeGestureDownTime = Long.MIN_VALUE
                activeNativeGestureRegionType = null
            }
        }
    }

    private fun installNativeHomeRemoteTransitionParity(classLoader: ClassLoader) {
        val transitionManager = XposedHelpers.findClassIfExists(
            "com.honeyspace.transition.ShellTransitionManager",
            classLoader
        ) ?: return unavailable("home remote transition parity", "transition manager missing")
        val registrationCollectorClass = findCompatClass(
            classLoader = classLoader,
            v85Names = listOf("E2.g"),
            v8Names = listOf("L1.k", "E2.g"),
            v7Names = listOf("L1.k", "E2.g")
        ) { candidate ->
            candidate.declaredMethods.any { method ->
                method.name == "emit" &&
                    method.parameterCount in 1..2 &&
                    !java.lang.reflect.Modifier.isStatic(method.modifiers)
            }
        } ?: return unavailable(
            "home remote transition parity",
            "transition collector emit shape unavailable"
        )

        runCatching {
            val emitMethods = registrationCollectorClass.declaredMethods.filter { method ->
                method.name == "emit" &&
                    method.parameterCount in 1..2 &&
                    !java.lang.reflect.Modifier.isStatic(method.modifiers)
            }
            if (emitMethods.isEmpty()) {
                return@runCatching unavailable(
                    "home remote transition parity",
                    "collector emit methods unavailable"
                )
            }
            emitMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (shouldDeliverTransitionRegistrationEvent(param.thisObject, param.args)) {
                            return
                        }
                        param.result = kotlin.Unit
                    }
                })
            }
            val filterMethods = transitionManager.declaredMethods.filter { method ->
                method.name == "makeTransitionFilter" && method.parameterCount == 1
            }
            filterMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val type = param.args.firstOrNull()?.toString() ?: return
                        val registrationFlag = when (type) {
                            "APP_CLOSE" -> "coverAppCloseRegistrationActive"
                            "RECENTS_CLOSE_REGISTER" -> "coverRecentsCloseRegistrationActive"
                            else -> return
                        }
                        if (
                            XposedHelpers.getAdditionalInstanceField(
                                param.thisObject,
                                registrationFlag
                            ) != true
                        ) return
                        val filter = param.result ?: return
                        if (!retargetHomeTransitionFilterForCover(filter)) return
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 native $type Quickstep home filter retargeted " +
                                "before registration"
                        )
                    }
                })
            }

            val appCloseRegistrationMethods = transitionManager.declaredMethods.filter { method ->
                method.name == "setAppCloseRemoteTransition" &&
                    method.parameterTypes.contentEquals(arrayOf(Activity::class.java))
            }
            appCloseRegistrationMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.args.firstOrNull() as? Activity ?: return
                        if (!isManagedSecondaryLauncher(activity)) return
                        XposedHelpers.setAdditionalInstanceField(
                            param.thisObject,
                            "coverAppCloseRegistrationActive",
                            true
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val active = XposedHelpers.removeAdditionalInstanceField(
                            param.thisObject,
                            "coverAppCloseRegistrationActive"
                        ) == true
                        if (!active) return
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 QuickstepLaunchHome native runner registered through " +
                                "the original inner-screen lifecycle"
                        )
                    }
                })
            }
            val recentsCloseRegistrationMethods = transitionManager.declaredMethods.filter { method ->
                method.name == "setRecentCloseRemoteTransition" &&
                    method.parameterTypes.contentEquals(arrayOf(Activity::class.java))
            }
            recentsCloseRegistrationMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.args.firstOrNull() as? Activity ?: return
                        if (!isManagedSecondaryLauncher(activity)) return
                        XposedHelpers.setAdditionalInstanceField(
                            param.thisObject,
                            "coverRecentsCloseRegistrationActive",
                            true
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val active = XposedHelpers.removeAdditionalInstanceField(
                            param.thisObject,
                            "coverRecentsCloseRegistrationActive"
                        ) == true
                        if (!active) return
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 QuickstepLaunchHomeFromRecents native runner registered " +
                                "for SecondaryLauncher"
                        )
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 native QuickstepLaunchHome parity installed " +
                    "collectorIsolation=true " +
                    "appCloseMethods=${appCloseRegistrationMethods.size} " +
                    "recentsCloseMethods=${recentsCloseRegistrationMethods.size} " +
                    "filterMethods=${filterMethods.size}"
            )
        }.onFailure { unavailable("home remote transition parity", it.message) }
    }

    private fun shouldDeliverTransitionRegistrationEvent(
        collector: Any,
        args: Array<out Any?>
    ): Boolean {
        val registrationState = args.firstOrNull()
        val launcher = registrationState?.let {
            runCatching { XposedHelpers.callMethod(it, "getLauncher") as? Activity }.getOrNull()
        }
        val controller = runCatching {
            val registerCallback = XposedHelpers.getObjectField(collector, "f2306e")
            XposedHelpers.getObjectField(registerCallback, "f518e")
        }.getOrNull() ?: return true
        val spaceInfo = getFieldOrNull(controller, "spaceInfo") ?: return true
        val collectorDisplayId = runCatching {
            (XposedHelpers.callMethod(spaceInfo, "getDisplayId") as Number).toInt()
        }.getOrNull() ?: return true
        val activeDisplayId = XposedHelpers.getAdditionalInstanceField(
            collector,
            "transitionRegistrationDisplayId"
        ) as? Int

        if (launcher == null) {
            val deliver = activeDisplayId == collectorDisplayId
            if (deliver) {
                XposedHelpers.removeAdditionalInstanceField(
                    collector,
                    "transitionRegistrationDisplayId"
                )
            }
            return deliver
        }

        val launcherDisplayId = launcher.display?.displayId ?: return false
        if (launcherDisplayId != collectorDisplayId) {
            CoverRuntime.log(
                SCOPE,
                "transition registration event isolated " +
                    "launcherDisplay=$launcherDisplayId collectorDisplay=$collectorDisplayId"
            )
            return false
        }
        val defaultHome = runCatching {
            XposedHelpers.callMethod(registrationState, "getDefaultHome") == true
        }.getOrDefault(false)
        if (defaultHome) {
            XposedHelpers.setAdditionalInstanceField(
                collector,
                "transitionRegistrationDisplayId",
                collectorDisplayId
            )
        } else {
            XposedHelpers.removeAdditionalInstanceField(
                collector,
                "transitionRegistrationDisplayId"
            )
        }
        return true
    }

    private fun bindNativeHomeTransitionRegistration(activity: Activity, reason: String) {
        if (!isManagedSecondaryLauncher(activity)) return
        val manager = coverShellTransitionManager(activity) ?: return
        val displayId = CoverRuntime.displayIdOf(activity)
        val activityIdentity = System.identityHashCode(activity)
        if (
            XposedHelpers.getAdditionalInstanceField(
                manager,
                "coverTransitionRegistrationActivity"
            ) == activityIdentity
        ) return
        activity.window?.decorView?.post {
            if (!isManagedSecondaryLauncher(activity)) return@post
            if (coverShellTransitionManager(activity) !== manager) return@post
            runCatching {
                val hasExistingRegistration =
                    getFieldOrNull(manager, "remoteClosingInfo") != null ||
                        getFieldOrNull(manager, "recentsActivityClosingInfo") != null
                if (hasExistingRegistration) {
                    XposedHelpers.callMethod(manager, "cleanUp")
                    XposedHelpers.callMethod(manager, "unregisterRemoteTransitions")
                    XposedHelpers.callMethod(
                        manager,
                        "unregisterPredictiveBackAnimationController"
                    )
                }
                XposedHelpers.callMethod(manager, "registerRemoteTransitions", activity)
                XposedHelpers.callMethod(
                    manager,
                    "registerPredictiveBackAnimationController",
                    activity
                )
                XposedHelpers.setAdditionalInstanceField(
                    manager,
                    "coverTransitionRegistrationActivity",
                    activityIdentity
                )
                CoverRuntime.log(
                    SCOPE,
                    "display-$displayId native transition registration bound to exact manager " +
                        "reason=$reason"
                )
            }.onFailure {
                unavailable("cover transition registration bind", it.message)
            }
        }
    }

    private fun unbindNativeHomeTransitionRegistration(activity: Activity, reason: String) {
        if (!isManagedSecondaryLauncher(activity)) return
        val manager = coverShellTransitionManager(activity) ?: return
        val displayId = CoverRuntime.displayIdOf(activity)
        val activityIdentity = System.identityHashCode(activity)
        if (
            XposedHelpers.getAdditionalInstanceField(
                manager,
                "coverTransitionRegistrationActivity"
            ) != activityIdentity
        ) return
        runCatching {
            XposedHelpers.callMethod(manager, "cleanUp")
            XposedHelpers.callMethod(manager, "unregisterRemoteTransitions")
            XposedHelpers.callMethod(manager, "unregisterPredictiveBackAnimationController")
            XposedHelpers.removeAdditionalInstanceField(
                manager,
                "coverTransitionRegistrationActivity"
            )
            CoverRuntime.log(
                SCOPE,
                "display-$displayId native transition registration unbound from exact manager " +
                    "reason=$reason"
            )
        }.onFailure {
            unavailable("cover transition registration unbind", it.message)
        }
    }

    private fun coverShellTransitionManager(activity: Activity): Any? = runCatching {
        val displayId = CoverRuntime.displayIdOf(activity)
        if (!isManagedSecondaryLauncher(activity)) return@runCatching null
        val utility = getFieldOrNull(activity, "honeySpaceUtility")
            ?: XposedHelpers.callMethod(activity, "e")
        val controller = XposedHelpers.callMethod(
            utility,
            "getHoneySystemController",
            displayId
        ) ?: return@runCatching null
        getFieldOrNull(controller, "f")
    }.onFailure {
        unavailable("cover shell transition manager lookup", it.message)
    }.getOrNull()

    private fun retargetHomeTransitionFilterForCover(filter: Any): Boolean = runCatching {
        val requirements = XposedHelpers.getObjectField(filter, "mRequirements") as Array<*>
        val homeRequirement = requirements.firstOrNull { requirement ->
            requirement != null &&
                XposedHelpers.getIntField(requirement, "mActivityType") == 2 &&
                XposedHelpers.getIntField(requirement, "mOrder") == 1
        } ?: return@runCatching false
        XposedHelpers.setObjectField(
            homeRequirement,
            "mTopActivity",
            ComponentName(
                CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE,
                SECONDARY_LAUNCHER_CLASS
            )
        )
        true
    }.onFailure {
        unavailable("cover home transition filter retarget", it.message)
    }.getOrDefault(false)

    private fun installNativeAppLaunchDisplayRoute(classLoader: ClassLoader) {
        val transitionManager = XposedHelpers.findClassIfExists(
            "com.honeyspace.transition.ShellTransitionManager",
            classLoader
        ) ?: return unavailable("app launch route", "transition manager missing")
        runCatching {
            val methods = transitionManager.declaredMethods.filter { method ->
                method.name == "getLaunchOptions" && method.parameterCount == 4
            }
            if (methods.isEmpty()) {
                return@runCatching unavailable(
                    "app launch route",
                    "getLaunchOptions(4) shape unavailable"
                )
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = param.args.firstOrNull() ?: return
                        val targetView = targetViewOf(info) ?: return
                        val sourceDisplayId = CoverRuntime.displayIdOf(targetView.context)
                        val sourceIsScrcpy = isScrcpyDisplay(targetView.context)
                        val sourceIsSelectable = sourceDisplayId != null &&
                            CoverDisplayResolver.selectableCandidates().any {
                                it.id == sourceDisplayId
                            }
                        if (!shouldRouteLauncherAppToDisplay(
                                sourceDisplayId,
                                sourceIsSelectable,
                                sourceIsScrcpy
                            )
                        ) return
                        val transitionType = runCatching {
                            XposedHelpers.callMethod(info, "getType")?.toString()
                        }.getOrNull()
                        val options = param.result ?: return
                        if (transitionType == APP_LAUNCH_TRANSITION) {
                            sourceDisplayId?.let { coverDisplayId ->
                                runCatching {
                                    XposedHelpers.callMethod(
                                        options,
                                        "setLaunchDisplayId",
                                        coverDisplayId
                                    )
                                    val platformOptions = XposedHelpers.callMethod(
                                        options,
                                        "getOptions"
                                    ) ?: error("platform ActivityOptions missing")
                                    XposedHelpers.callMethod(
                                        platformOptions,
                                        "setDisableStartingWindow",
                                        true
                                    )
                                    CoverRuntime.log(
                                        SCOPE,
                                        "display-$coverDisplayId app launch uses source display " +
                                            "with starting window disabled"
                                    )
                                }.onFailure {
                                    unavailable(
                                        "app launch display/starting-window option",
                                        it.message
                                    )
                                }
                            }
                        }
                    }
                })
            }
            val viewBoundsMethods = transitionManager.declaredMethods.filter { method ->
                method.name == "getViewBounds" &&
                    method.parameterTypes.contentEquals(arrayOf(View::class.java)) &&
                    Rect::class.java.isAssignableFrom(method.returnType)
            }
            viewBoundsMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val targetView = param.args.firstOrNull() as? View ?: return
                        val sourceBounds = homeIconBoundsOnScreen(targetView) ?: return
                        param.result = sourceBounds
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 app launch route installed methods=${methods.size} " +
                    "sourceBoundsMethods=${viewBoundsMethods.size}"
            )
        }.onFailure { unavailable("app launch route", it.message) }
    }

    private fun installNativeAppLaunchShape(classLoader: ClassLoader) {
        val playerClass = findCompatClass(
            classLoader = classLoader,
            v85Names = listOf("com.honeyspace.transition.anim.floating.PlayerImpl"),
            v8Names = listOf(
                "com.honeyspace.transition.anim.floating.FloatingAnimator\$Player",
                "com.honeyspace.transition.anim.floating.PlayerImpl"
            ),
            v7Names = listOf(
                "com.honeyspace.transition.anim.floating.FloatingAnimator\$Player",
                "com.honeyspace.transition.anim.floating.PlayerImpl"
            )
        ) { candidate ->
            candidate.declaredMethods.any { method ->
                (method.name == "setup" && method.parameterCount == 1) ||
                    (method.name == "buildInputData" && method.parameterCount == 9) ||
                    (method.name == "start" && method.parameterCount == 2)
            }
        } ?: return unavailable("app launch shape", "compatible Player shape missing")

        runCatching {
            val taskLaunchDelegateClass = XposedHelpers.findClassIfExists(
                "com.honeyspace.transition.delegate.TaskLaunchAnimationDelegate",
                classLoader
            )
            val taskLaunchInfoMethods = taskLaunchDelegateClass?.declaredMethods.orEmpty().filter { method ->
                method.name == "setInfo" && method.parameterCount == 3
            }
            taskLaunchInfoMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isCoverSessionEligible()) return
                        val targetView = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "targetView") as? View
                        }.getOrNull() ?: return
                        if (!CoverRuntime.isCoverView(targetView)) return
                        val nativeTaskRadius = runCatching {
                            XposedHelpers.getFloatField(param.thisObject, "initialWindowRadius")
                        }.getOrElse { return }
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 task launch native task-card radius=" +
                                "$nativeTaskRadius preserved=true"
                        )
                    }
                })
            }
            val setupMethods = playerClass.declaredMethods.filter { method ->
                method.name == "setup" && method.parameterCount == 1
            }
            setupMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val setupData = param.args.firstOrNull() ?: return
                        if (!CoverRuntime.isCoverSessionEligible()) return
                        val screen = runCatching {
                            XposedHelpers.callMethod(setupData, "getScreen") as? ViewGroup
                        }.getOrNull() ?: return
                        if (!CoverRuntime.isCoverView(screen)) return
                        val forward = runCatching {
                            XposedHelpers.callMethod(setupData, "isForward") == true
                        }.getOrDefault(false)
                        if (forward) {
                            param.setObjectExtra(APP_LAUNCH_TRANSITION, true)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.getObjectExtra(APP_LAUNCH_TRANSITION) == true) {
                            runCatching {
                                XposedHelpers.setFloatField(
                                    param.thisObject,
                                    "windowRadius",
                                    FULLSCREEN_CORNER_RADIUS_PX
                                )
                            }.onFailure {
                                unavailable("display-1 player end radius", it.message)
                            }
                        }
                        val setupData = param.args.firstOrNull() ?: return
                        val forward = runCatching {
                            XposedHelpers.callMethod(setupData, "isForward") == true
                        }.getOrDefault(true)
                        if (forward) return
                        val originalView = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getOriginalView") as? View
                        }.getOrNull() ?: return
                        if (!isCoverHomeIcon(originalView)) return
                        publishCoverReturnAppGeometry(
                            param.thisObject,
                            active = true,
                            appTop = 0.0f,
                            reason = "floating-return-setup"
                        )
                    }
                })
            }

            val applyMethods = playerClass.declaredMethods.filter { method ->
                method.name == "apply" &&
                    method.parameterTypes.contentEquals(
                        arrayOf(Float::class.javaPrimitiveType)
                    )
            }
            applyMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        normalizeCoverAppLaunchEndpoint(param.thisObject ?: return)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val player = param.thisObject ?: return
                        if (!snappedCoverAppLaunchPlayers.contains(player)) return
                        val inputData = getFieldOrNull(player, "inputData") ?: return
                        val forward = runCatching {
                            XposedHelpers.callMethod(inputData, "isForward") == true
                        }.getOrDefault(false)
                        if (!forward || !completedCoverAppLaunchPlayers.add(player)) return
                        runCatching {
                            XposedHelpers.callMethod(player, "endAnimation")
                        }.onFailure {
                            completedCoverAppLaunchPlayers.remove(player)
                            unavailable("display-1 app launch terminal completion", it.message)
                        }
                    }
                })
            }

            val finishMethods = playerClass.declaredMethods.filter { method ->
                method.name == "finish" && method.parameterCount == 0
            }
            finishMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val player = param.thisObject ?: return
                        if (!coverReturnHeaderGeometryStates.containsKey(player)) return
                        publishCoverReturnAppGeometry(
                            player,
                            active = false,
                            appTop = Float.POSITIVE_INFINITY,
                            reason = "floating-return-finished"
                        )
                    }
                })
            }

            val methods = playerClass.declaredMethods.filter { method ->
                method.name == "buildInputData" && method.parameterCount == 9
            }
            if (methods.isEmpty()) {
                unavailable(
                    "app launch input geometry",
                    "buildInputData(9) shape unavailable"
                )
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.getOrNull(3) != true) return
                        if (!CoverRuntime.isCoverSessionEligible()) return
                        val container = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getContainerView") as? View
                        }.getOrNull() ?: runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getOriginalView") as? View
                        }.getOrNull() ?: return
                        if (!CoverRuntime.isCoverView(container)) return
                        val inputData = param.result ?: return
                        val startRect = runCatching {
                            XposedHelpers.callMethod(inputData, "getStartRect") as? RectF
                        }.getOrNull() ?: return
                        val nativeEndRect = runCatching {
                            XposedHelpers.callMethod(inputData, "getEndRect") as? RectF
                        }.getOrNull() ?: return
                        val startRadius = runCatching {
                            (XposedHelpers.callMethod(
                                inputData,
                                "getStartCornerRadius"
                            ) as? Number)?.toFloat()
                        }.getOrNull() ?: return
                        val nativeEndRadius = runCatching {
                            (XposedHelpers.callMethod(
                                inputData,
                                "getEndCornerRadius"
                            ) as? Number)?.toFloat()
                        }.getOrNull() ?: return
                        val cornerRadius = FULLSCREEN_CORNER_RADIUS_PX
                        runCatching {
                            XposedHelpers.callMethod(
                                inputData,
                                "setStartCornerRadius",
                                cornerRadius
                            )
                            XposedHelpers.callMethod(
                                inputData,
                                "setEndCornerRadius",
                                cornerRadius
                            )
                        }.getOrElse {
                            unavailable("display-1 app launch corner radius", it.message)
                            return
                        }

                        val playerScreenSize = runCatching {
                            XposedHelpers.callMethod(
                                param.thisObject,
                                "getScreenSize"
                            ) as? IntArray
                        }.getOrNull() ?: return
                        val displayWidth = playerScreenSize.getOrNull(0)
                            ?.takeIf { it > 0 }
                            ?: return
                        val displayHeight = playerScreenSize.getOrNull(1)
                            ?.takeIf { it > 0 }
                            ?: return
                        val fullScreenRect = RectF(
                            0.0f,
                            0.0f,
                            displayWidth.toFloat(),
                            displayHeight.toFloat()
                        )
                        val homeToWindowMatrix = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getHomeToWindowMatrix")
                        }.getOrNull()
                        runCatching {
                            XposedHelpers.callMethod(
                                homeToWindowMatrix,
                                "mapRect",
                                fullScreenRect
                            )
                        }
                        val windowRectPrepared = sameRect(nativeEndRect, fullScreenRect)
                        if (loggedCoverAppLaunchPlayers.add(param.thisObject)) {
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 icon launch uses one native Quickstep geometry " +
                                    "icon=$startRect radius=$startRadius=>$cornerRadius " +
                                    "window=$nativeEndRect radius=$nativeEndRadius=>$cornerRadius " +
                                    "container=${container.width}x${container.height} " +
                                    "screen=${displayWidth}x${displayHeight} " +
                                    "fullScreen=$fullScreenRect prepared=$windowRectPrepared"
                            )
                        }
                    }
                })
            }
            val taskStartMethods = playerClass.declaredMethods.filter { method ->
                method.name == "start" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes.getOrNull(1) == Boolean::class.javaPrimitiveType
            }
            taskStartMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.getOrNull(1) != true) return
                        if (!CoverRuntime.isCoverSessionEligible()) return
                        val taskView = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getOriginalView") as? View
                        }.getOrNull() ?: return
                        if (!CoverRuntime.isCoverView(taskView)) return
                        val inputData = param.args.firstOrNull() ?: return
                        val taskRect = runCatching {
                            XposedHelpers.callMethod(inputData, "getStartRect") as? RectF
                        }.getOrNull() ?: return
                        val windowRect = runCatching {
                            XposedHelpers.callMethod(inputData, "getEndRect") as? RectF
                        }.getOrNull() ?: return
                        val taskRadius = runCatching {
                            (XposedHelpers.callMethod(
                                inputData,
                                "getStartCornerRadius"
                            ) as? Number)?.toFloat()
                        }.getOrNull() ?: return
                        val nativeWindowRadius = runCatching {
                            (XposedHelpers.callMethod(
                                inputData,
                                "getEndCornerRadius"
                            ) as? Number)?.toFloat()
                        }.getOrNull() ?: return
                        if (
                            kotlin.math.abs(taskRadius - FULLSCREEN_CORNER_RADIUS_PX) > 0.01f ||
                            kotlin.math.abs(nativeWindowRadius - FULLSCREEN_CORNER_RADIUS_PX) > 0.01f
                        ) {
                            runCatching {
                                XposedHelpers.callMethod(
                                    inputData,
                                    "setStartCornerRadius",
                                    FULLSCREEN_CORNER_RADIUS_PX
                                )
                                XposedHelpers.callMethod(
                                    inputData,
                                    "setEndCornerRadius",
                                    FULLSCREEN_CORNER_RADIUS_PX
                                )
                            }.getOrElse {
                                unavailable("display-1 task launch input corner radius", it.message)
                                return
                            }
                        }
                        val screenSize = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getScreenSize") as? IntArray
                        }.getOrNull()
                        val prepared = screenSize?.let { size ->
                            val width = size.getOrNull(0) ?: return@let null
                            val height = size.getOrNull(1) ?: return@let null
                            sameRect(
                                windowRect,
                                RectF(0.0f, 0.0f, width.toFloat(), height.toFloat())
                            )
                        }
                        if (loggedCoverTaskLaunchPlayers.add(param.thisObject)) {
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 task launch uses one native Quickstep geometry " +
                                    "task=$taskRect radius=$taskRadius=>" +
                                    "$FULLSCREEN_CORNER_RADIUS_PX " +
                                    "window=$windowRect radius=$nativeWindowRadius=>" +
                                    "$FULLSCREEN_CORNER_RADIUS_PX prepared=$prepared " +
                                    "screen=${screenSize?.contentToString()}"
                            )
                        }
                    }
                })
            }
            val installedRoutes = listOfNotNull(
                taskLaunchInfoMethods.takeIf { it.isNotEmpty() }
                    ?.let { "taskInfoMethods=${it.size}" },
                taskStartMethods.takeIf { it.isNotEmpty() }
                    ?.let { "taskStartMethods=${it.size}" },
                setupMethods.takeIf { it.isNotEmpty() }
                    ?.let { "setupMethods=${it.size}" },
                applyMethods.takeIf { it.isNotEmpty() }
                    ?.let { "applyMethods=${it.size}" },
                finishMethods.takeIf { it.isNotEmpty() }
                    ?.let { "finishMethods=${it.size}" },
                methods.takeIf { it.isNotEmpty() }
                    ?.let { "inputMethods=${it.size}" }
            )
            if (installedRoutes.isEmpty()) {
                unavailable("app launch shape", "no compatible methods installed")
            } else {
                CoverRuntime.log(
                    SCOPE,
                    "display-1 app launch shape installed ${installedRoutes.joinToString(" ")}"
                )
            }
        }.onFailure { unavailable("app launch shape", it.message) }
    }

    private fun normalizeCoverAppLaunchEndpoint(player: Any) {
        if (snappedCoverAppLaunchPlayers.contains(player)) return
        val originalView = runCatching {
            XposedHelpers.callMethod(player, "getOriginalView") as? View
        }.getOrNull() ?: return
        if (!isCoverHomeIcon(originalView)) return

        val outputData = getFieldOrNull(player, "outputData") ?: return
        val screenSize = runCatching {
            XposedHelpers.callMethod(player, "getScreenSize") as? IntArray
        }.getOrNull() ?: return
        val displayWidth = screenSize.getOrNull(0)?.takeIf { it > 0 } ?: return
        val displayHeight = screenSize.getOrNull(1)?.takeIf { it > 0 } ?: return
        val currentRect = runCatching {
            XposedHelpers.callMethod(outputData, "getCurrentRectF") as? RectF
        }.getOrNull() ?: return
        val inputData = getFieldOrNull(player, "inputData")
        val forward = inputData?.let { input ->
            runCatching {
                XposedHelpers.callMethod(input, "isForward") == true
            }.getOrNull()
        } ?: return
        val windowAlpha = runCatching {
            (XposedHelpers.callMethod(outputData, "getWindowAlpha") as? Number)?.toFloat()
        }.getOrNull() ?: return
        val nativeBackgroundAlpha = runCatching {
            (XposedHelpers.callMethod(outputData, "getBgAlpha") as? Number)?.toFloat()
        }.getOrNull() ?: return
        val coupledBackgroundAlpha = minOf(
            nativeBackgroundAlpha,
            (1.0f - windowAlpha).coerceIn(0.0f, 1.0f)
        )
        if (nativeBackgroundAlpha > coupledBackgroundAlpha) {
            XposedHelpers.callMethod(
                outputData,
                "setBgAlpha",
                coupledBackgroundAlpha
            )
        }

        if (!forward) {
            publishCoverReturnAppGeometry(
                player,
                active = true,
                appTop = currentRect.top,
                reason = "floating-return-header-boundary"
            )
        }

        val edgeLimit = minOf(displayWidth, displayHeight) *
            COVER_APP_FULLSCREEN_SNAP_EDGE_RATIO
        val leftEdgeError = kotlin.math.abs(currentRect.left)
        val topEdgeError = kotlin.math.abs(currentRect.top)
        val rightEdgeError = kotlin.math.abs(currentRect.right - displayWidth)
        val bottomEdgeError = kotlin.math.abs(currentRect.bottom - displayHeight)
        val maxEdgeError = maxOf(
            leftEdgeError,
            topEdgeError,
            rightEdgeError,
            bottomEdgeError
        )
        val nearFullScreen = maxEdgeError <= edgeLimit
        val opaqueWindow = windowAlpha >= 0.999f
        val nativeRadius = runCatching {
            (XposedHelpers.callMethod(outputData, "getRadius") as? Number)?.toFloat()
        }.getOrNull() ?: return
        val remainingRatio = (maxEdgeError / edgeLimit).coerceIn(0.0f, 1.0f)
        val smoothRemainingRatio = remainingRatio * remainingRatio *
            (3.0f - 2.0f * remainingRatio)
        val terminalRadius = nativeRadius * smoothRemainingRatio
        val shouldNormalize = nearFullScreen && opaqueWindow
        if (!shouldNormalize) return

        val before = RectF(currentRect)
        runCatching {
            XposedHelpers.callMethod(outputData, "setX", 0.0f)
            XposedHelpers.callMethod(outputData, "setY", 0.0f)
            XposedHelpers.callMethod(outputData, "setWidth", displayWidth.toFloat())
            XposedHelpers.callMethod(outputData, "setHeight", displayHeight.toFloat())
            XposedHelpers.callMethod(outputData, "setScale", 1.0f)
            XposedHelpers.callMethod(outputData, "setContainerScale", 1.0f)
            XposedHelpers.callMethod(outputData, "setBgWidth", displayWidth)
            XposedHelpers.callMethod(outputData, "setBgHeight", displayHeight)
            XposedHelpers.callMethod(outputData, "setPositionProgress", 0.0f)
            XposedHelpers.callMethod(outputData, "setWindowAlpha", 1.0f)
            XposedHelpers.callMethod(outputData, "setFgAlpha", 0.0f)
            XposedHelpers.callMethod(outputData, "setBgAlpha", 0.0f)
            XposedHelpers.callMethod(
                outputData,
                "setRadius",
                terminalRadius
            )
            XposedHelpers.callMethod(outputData, "setOutlineOffset", 0)
            XposedHelpers.callMethod(outputData, "setShadowRadius", 0.0f)
            val cropRect = XposedHelpers.callMethod(outputData, "getCropRect") as Rect
            cropRect.set(0, 0, displayWidth, displayHeight)
            currentRect.set(0.0f, 0.0f, displayWidth.toFloat(), displayHeight.toFloat())
        }.onFailure {
            unavailable("display-1 app launch endpoint normalization", it.message)
            return
        }
        if (snappedCoverAppLaunchPlayers.add(player)) {
            CoverRuntime.log(
                SCOPE,
                "display-1 floating App endpoint snapped atomically " +
                    "forward=$forward from=$before to=$currentRect edgeLimit=$edgeLimit " +
                    "windowAlpha=$windowAlpha bgAlpha=$nativeBackgroundAlpha=>0.0 " +
                    "edgeError=$maxEdgeError radius=$nativeRadius=>$terminalRadius"
            )
        }
    }

    private fun publishCoverReturnAppGeometry(
        player: Any,
        active: Boolean,
        appTop: Float,
        reason: String
    ) {
        val originalView = runCatching {
            XposedHelpers.callMethod(player, "getOriginalView") as? View
        }.getOrNull()
        val headerBoundary = originalView?.let(::coverStatusBarHeight) ?: 0
        val headerClear = !active || appTop >= headerBoundary
        val previous = if (active) {
            coverReturnHeaderGeometryStates.put(player, headerClear)
        } else {
            coverReturnHeaderGeometryStates.remove(player)
        }
        if (active && previous == headerClear) return
        if (!active && previous == null) return

        val context = AndroidAppHelper.currentApplication() ?: return
        context.sendBroadcast(
            Intent(CoverRuntime.COVER_APP_TRANSITION_GEOMETRY_ACTION).apply {
                setPackage(CoverRuntime.SYSTEM_UI_PACKAGE)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(CoverRuntime.EXTRA_COVER_APP_TRANSITION_ACTIVE, active)
                putExtra(CoverRuntime.EXTRA_COVER_APP_TRANSITION_TOP, appTop)
                putExtra(CoverRuntime.EXTRA_COVER_APP_TRANSITION_REASON, reason)
            }
        )
        CoverRuntime.log(
            SCOPE,
            "display-1 return App header geometry active=$active top=$appTop " +
                "boundary=$headerBoundary clear=$headerClear reason=$reason"
        )
    }

    private fun coverStatusBarHeight(view: View): Int {
        val resourceId = view.resources.getIdentifier("status_bar_height", "dimen", "android")
        val nativeStatusBarHeight = if (resourceId != 0) {
            view.resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
        val displayHeight = view.display?.mode?.physicalHeight ?: view.resources.displayMetrics.heightPixels
        val coverHeaderHeight = (displayHeight * COVER_HEADER_SAFE_HEIGHT_RATIO).toInt()
        return maxOf(nativeStatusBarHeight, coverHeaderHeight)
    }

    private fun installHomeIconSize(classLoader: ClassLoader) {
        val iconViewClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.common.iconview.IconViewImpl",
            classLoader
        ) ?: return unavailable("Home icon size", "IconViewImpl missing")

        runCatching {
            val positionMethods = iconViewClass.declaredMethods.filter { method ->
                method.name == "setIconIntoPosition" &&
                    method.parameterTypes.firstOrNull() == Drawable::class.java
            }
            positionMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!compactCoverUiActive()) return
                        val view = param.thisObject as? View ?: return
                        val drawable = param.args.firstOrNull() as? Drawable ?: return
                        resizeHomeIcon(view, drawable)
                    }
                })
            }

            val iconPositionMethods = iconViewClass.declaredMethods.filter { method ->
                method.name == "getIconPosition" && method.parameterCount == 0
            }
            iconPositionMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!compactCoverUiActive()) return
                        val view = param.thisObject as? View ?: return
                        if (
                            CoverQsModeConfig.readTransaction(view.context).isStableFull &&
                            isCoverHomeIcon(view)
                        ) return
                        if (!isCoverLauncherIcon(view)) return
                        param.result = if (isCoverDrawerItem(view) && fullQsDrawerLayout(view)) {
                            centeredDrawerIconPosition(view)
                        } else {
                            homeIconPosition(view)
                        }
                    }
                })
            }

            val layoutMethods = iconViewClass.declaredMethods.filter { method ->
                method.name == "onLayout" && method.parameterCount == 5
            }
            layoutMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!compactCoverUiActive()) return
                        val view = param.thisObject as? View ?: return
                        val drawerIcon = isCoverDrawerIcon(view)
                        val drawerFolder = isCoverDrawerFolder(view)
                        val homeIcon = !drawerIcon && !drawerFolder && isCoverHomeIcon(view)
                        if (!drawerIcon && !drawerFolder && !homeIcon) return
                        if (drawerIcon || drawerFolder) {
                            applyDrawerContentTopPadding(view)
                        }
                        val drawable = runCatching {
                            XposedHelpers.callMethod(view, "getIcon") as? Drawable
                        }.getOrNull()
                            ?: (view as? TextView)?.compoundDrawablesRelative?.getOrNull(1)
                            ?: return
                        if (drawerIcon) {
                            val targetStyle = coverDrawerIconStyle(view)
                            val drawableMatches =
                                drawable.bounds.width() == targetStyle.iconSizePx &&
                                    drawable.bounds.height() == targetStyle.iconSizePx
                            val textView = view as? TextView
                            val expectedTextSizePx = textView?.let {
                                TypedValue.applyDimension(
                                    TypedValue.COMPLEX_UNIT_SP,
                                    targetStyle.labelTextSizeSp,
                                    it.resources.displayMetrics
                                )
                            }
                            val targetGravity = if (textView?.let(::fullQsDrawerLayout) == true) {
                                Gravity.CENTER
                            } else {
                                Gravity.TOP or Gravity.CENTER_HORIZONTAL
                            }
                            val textMatches = textView != null &&
                                expectedTextSizePx != null &&
                                textView.compoundDrawablePadding == targetStyle.labelGapPx &&
                                kotlin.math.abs(textView.textSize - expectedTextSizePx) < 0.01f &&
                                !textView.includeFontPadding &&
                                textView.gravity == targetGravity
                            if (!drawableMatches || !textMatches) resizeHomeIcon(view, drawable)
                            return
                        }
                        resizeHomeIcon(view, drawable)
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 icon drawable sizing installed " +
                    "homeDefault=$DEFAULT_COVER_HOME_ICON_SIZE_PX px " +
                    "drawerDefault=$DEFAULT_COVER_DRAWER_ICON_SIZE_PX px " +
                    "positionMethods=${positionMethods.size} " +
                    "iconPositionMethods=${iconPositionMethods.size} " +
                    "layoutMethods=${layoutMethods.size}"
            )
        }.onFailure { unavailable("Home icon size", it.message) }
    }

    private fun installHotseatPosition(classLoader: ClassLoader) {
        val containerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.honeypots.hotseat.presentation.HotseatContainer",
            classLoader
        ) ?: return unavailable("Hotseat position", "HotseatContainer missing")

        runCatching {
            val setupMethods = containerClass.declaredMethods.filter { method ->
                method.name == "setup" && method.parameterCount == 1
            }
            setupMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val container = param.thisObject as? ViewGroup ?: return
                        if (!CoverRuntime.isCoverView(container)) return
                        installHotseatLayoutListener(container)
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 Hotseat native layout installed " +
                    "topOffset=$HOTSEAT_TOP_OFFSET_PX px " +
                    "landscapeSafeInset=$HOTSEAT_HORIZONTAL_SAFE_INSET_PX px " +
                    "setupMethods=${setupMethods.size}"
            )
        }.onFailure { unavailable("Hotseat position", it.message) }
    }

    private fun installDrawerGridPolicy(classLoader: ClassLoader) {
        installDrawerGridControllerPolicy(classLoader)
    }

    private fun installDrawerGridControllerPolicy(classLoader: ClassLoader) {
        val gridControllerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.common.util.GridController",
            classLoader
        ) ?: return unavailable("drawer grid policy", "GridController missing")

        runCatching {
            val columnMethods = gridControllerClass.declaredMethods.filter { method ->
                method.name in setOf("getUiGridX", "getPortGridX") &&
                    method.parameterCount == 0 &&
                    method.returnType == Int::class.javaPrimitiveType
            }
            columnMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isCoverAppListGrid(param.thisObject)) return
                        param.result = COVER_DRAWER_COLUMNS
                    }
                })
            }
            val rowMethods = gridControllerClass.declaredMethods.filter { method ->
                method.name in setOf("getUiGridY", "getPortGridY") &&
                    method.parameterCount == 0 &&
                    method.returnType == Int::class.javaPrimitiveType
            }
            rowMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isCoverAppListGrid(param.thisObject)) return
                        param.result = COVER_DRAWER_ROWS
                    }
                })
            }
            val pointMethods = gridControllerClass.declaredMethods.filter { method ->
                method.name in setOf("getUiGrid", "getPortGrid") &&
                    method.parameterCount == 0 &&
                    method.returnType == Point::class.java
            }
            pointMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isCoverAppListGrid(param.thisObject)) return
                        param.result = Point(COVER_DRAWER_COLUMNS, COVER_DRAWER_ROWS)
                    }
                })
            }
            val capacityMethods = gridControllerClass.declaredMethods.filter { method ->
                method.name == "getMaxItemCountInPage" &&
                    method.parameterCount == 2 &&
                    method.returnType == Int::class.javaPrimitiveType
            }
            capacityMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isCoverAppListGrid(param.thisObject)) return
                        param.result = COVER_DRAWER_COLUMNS * COVER_DRAWER_ROWS
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 drawer grid policy installed " +
                    "grid=${COVER_DRAWER_COLUMNS}x$COVER_DRAWER_ROWS " +
                    "columnMethods=${columnMethods.size} rowMethods=${rowMethods.size} " +
                    "pointMethods=${pointMethods.size} capacityMethods=${capacityMethods.size}"
            )
        }.onFailure { unavailable("drawer grid policy", it.message) }
    }

    private fun installDrawerViewModelGridPolicy(classLoader: ClassLoader) {
        val appListPotClass = XposedHelpers.findClassIfExists("p4.Y", classLoader)
            ?: return unavailable("drawer ViewModel grid policy", "ApplistPot missing")
        val viewModelClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.honeypots.applist.viewmodel.ApplistViewModel",
            classLoader
        ) ?: return unavailable("drawer ViewModel grid policy", "ApplistViewModel missing")

        runCatching {
            val viewModelAccessMethods = appListPotClass.declaredMethods.filter { method ->
                method.name == "e" &&
                    method.parameterCount == 0 &&
                    viewModelClass.isAssignableFrom(method.returnType)
            }
            viewModelAccessMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!compactCoverUiActive()) return
                        val context = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getContext") as? Context
                        }.getOrNull()
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return
                        val viewModel = param.result?.takeIf(viewModelClass::isInstance) ?: return
                        if (!coverDrawerViewModels.add(viewModel)) return
                        forceCoverDrawerGrid(viewModel, "pot-view-model-access")
                        reflowCoverDrawerItems(viewModel, "pot-view-model-access")
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 drawer ViewModel captured before data binding"
                        )
                    }
                })
            }

            val createViewMethods = appListPotClass.declaredMethods.filter { method ->
                method.name == "createView" && method.parameterCount == 0
            }
            createViewMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!compactCoverUiActive()) return
                        val context = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getContext") as? Context
                        }.getOrNull()
                        CoverRuntime.log(
                            SCOPE,
                            "drawer pot createView before contextDisplay=" +
                                "${CoverRuntime.displayIdOf(context)}"
                        )
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return
                        markCoverDrawerViewModel(
                            param.thisObject,
                            viewModelClass,
                            "pot-context"
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!compactCoverUiActive()) return
                        val view = param.result as? View ?: return
                        val appListPot = param.thisObject
                        val captureViewModel = { reason: String ->
                            val displayId = CoverRuntime.displayIdOf(view.context)
                            CoverRuntime.log(
                                SCOPE,
                                "drawer pot createView $reason viewDisplay=$displayId"
                            )
                            if (CoverRuntime.isCoverView(view)) {
                                markCoverDrawerViewModel(
                                    appListPot,
                                    viewModelClass,
                                    reason
                                )
                            }
                        }
                        captureViewModel("pot-view-immediate")
                        view.post { captureViewModel("pot-view-attached") }
                    }
                })
            }

            val gridPairMethods = viewModelClass.declaredMethods.filter { method ->
                method.name == "D" && method.parameterCount == 0
            }
            gridPairMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!compactCoverUiActive()) return
                        if (!coverDrawerViewModels.contains(param.thisObject)) return
                        val originalPair = param.result ?: return
                        val replacementPair = runCatching {
                            XposedHelpers.newInstance(
                                originalPair.javaClass,
                                COVER_DRAWER_COLUMNS,
                                COVER_DRAWER_ROWS
                            )
                        }.getOrNull() ?: return
                        if (!method.returnType.isInstance(replacementPair)) return
                        param.result = replacementPair
                    }
                })
            }

            val gridStateMethods = emptyList<java.lang.reflect.Method>()

            val loadSuccessMethods = viewModelClass.declaredMethods.filter { method ->
                method.name == "b0" &&
                    method.parameterCount == 3 &&
                    method.parameterTypes[1].name.endsWith("Outcome\$Success")
            }
            loadSuccessMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!compactCoverUiActive()) return
                        val viewModel = param.thisObject
                        if (!coverDrawerViewModels.contains(viewModel)) return
                        forceCoverDrawerGrid(viewModel, "load-success-source")
                        reflowCoverDrawerSourceItems(viewModel, "load-success-source")
                    }
                })
            }

            val partialCompleteMethods = viewModelClass.declaredMethods.filter { method ->
                method.name == "X" &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType
                        )
                    )
            }
            partialCompleteMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!compactCoverUiActive()) return
                        val viewModel = param.thisObject
                        if (!coverDrawerViewModels.contains(viewModel)) return
                        forceCoverDrawerGrid(viewModel, "partial-complete")
                        val pageCount = reflowCoverDrawerSourceItems(
                            viewModel,
                            "partial-complete"
                        )
                        if (pageCount <= 0) return
                        listOf("K", "M").forEach { field ->
                            (getFieldOrNull(viewModel, field) as? MutableCollection<*>)?.clear()
                        }
                        val firstLoadingComplete = param.args[1] as? Boolean ?: false
                        repeat(pageCount) { page ->
                            XposedBridge.invokeOriginalMethod(
                                method,
                                viewModel,
                                arrayOf(page, firstLoadingComplete)
                            )
                        }
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 drawer loaded pages rebuilt " +
                                "pages=$pageCount capacity=" +
                                "${COVER_DRAWER_COLUMNS * COVER_DRAWER_ROWS}"
                        )
                    }
                })
            }

            val initialItemMethods = viewModelClass.declaredMethods.filter { method ->
                method.name == "f" &&
                    method.parameterCount == 2 &&
                    method.parameterTypes[1] == Boolean::class.javaPrimitiveType
            }
            initialItemMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!compactCoverUiActive()) return
                        val viewModel = param.thisObject
                        if (!coverDrawerViewModels.contains(viewModel)) return
                        val sourceItem = param.args.firstOrNull() ?: return
                        forceCoverDrawerGrid(viewModel, "initial-item")
                        val primary = runCatching {
                            XposedHelpers.callMethod(viewModel, "T", sourceItem) == true
                        }.getOrDefault(true)
                        val targetItems = getFieldOrNull(
                            viewModel,
                            if (primary) "K" else "M"
                        ) as? Collection<*> ?: return
                        val normalizedItem = runCatching {
                            XposedHelpers.callMethod(sourceItem, "a")
                        }.getOrNull() ?: sourceItem
                        val linearIndex = targetItems.size
                        XposedHelpers.callMethod(
                            normalizedItem,
                            "i",
                            linearIndex / (COVER_DRAWER_COLUMNS * COVER_DRAWER_ROWS)
                        )
                        XposedHelpers.callMethod(
                            normalizedItem,
                            "j",
                            linearIndex % (COVER_DRAWER_COLUMNS * COVER_DRAWER_ROWS)
                        )
                        param.args[0] = normalizedItem
                        if (linearIndex == 0 || linearIndex == 18) {
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 drawer item reflow " +
                                    "index=$linearIndex page=" +
                                    "${linearIndex / (COVER_DRAWER_COLUMNS * COVER_DRAWER_ROWS)} " +
                                    "rank=${linearIndex % (COVER_DRAWER_COLUMNS * COVER_DRAWER_ROWS)}"
                            )
                        }
                    }
                })
            }

            CoverRuntime.log(
                SCOPE,
                "display-1 drawer ViewModel grid policy installed " +
                    "access=${viewModelAccessMethods.size} create=${createViewMethods.size} " +
                    "pair=${gridPairMethods.size} state=${gridStateMethods.size} " +
                    "loadSuccess=${loadSuccessMethods.size} " +
                    "partialComplete=${partialCompleteMethods.size} " +
                    "item=${initialItemMethods.size}"
            )
        }.onFailure { unavailable("drawer ViewModel grid policy", it.message) }
    }

    private fun installDrawerCellHeight(classLoader: ClassLoader) {
        val appListCellLayoutClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.honeypots.applist.presentation.ApplistCellLayout",
            classLoader
        ) ?: return unavailable("drawer row spacing", "ApplistCellLayout missing")

        runCatching {
            val calculateMethods = appListCellLayoutClass.declaredMethods.filter { method ->
                method.name == "calculateCellSize" &&
                    method.parameterTypes.contentEquals(
                        arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    )
            }
            calculateMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyCoverDrawerCellSize(param.thisObject as? View ?: return)
                    }
                })
            }
            val layoutMethods = appListCellLayoutClass.declaredMethods.filter { method ->
                method.name == "onLayout" && method.parameterCount == 5
            }
            layoutMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyCoverDrawerCellSize(param.thisObject as? View ?: return)
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                    "display-1 drawer cell size installed " +
                    "grid=${COVER_DRAWER_COLUMNS}x$COVER_DRAWER_ROWS " +
                    "calculateMethods=${calculateMethods.size} " +
                    "layoutMethods=${layoutMethods.size}"
            )
        }.onFailure { unavailable("drawer row spacing", it.message) }
    }

    private fun installDrawerSpacing(classLoader: ClassLoader) {
        val recyclerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.honeypots.applist.presentation.ApplistFastRecyclerView",
            classLoader
        ) ?: return unavailable("drawer spacing", "ApplistFastRecyclerView missing")

        runCatching {
            val setupMethods = recyclerClass.declaredMethods.filter { method ->
                method.name == "setup" && method.parameterCount == 1
            }
            setupMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val recycler = param.thisObject as? View ?: return
                        installCoverDrawerGapListener(recycler)
                        if (CoverQsModeConfig.readTransaction(recycler.context).isStableFull) {
                            scheduleCoverDrawerSpacing(recycler)
                        } else {
                            applyDrawerSpacingOnce(recycler)
                        }
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 drawer presentation installed " +
                    "iconDefault=$DEFAULT_COVER_DRAWER_ICON_SIZE_PX " +
                    "gap=$COVER_DRAWER_ICON_LABEL_GAP_PX px " +
                    "top=$DRAWER_TOP_PADDING_PX bottom=$DRAWER_BOTTOM_PADDING_PX " +
                    "setupMethods=${setupMethods.size}; native paging geometry preserved"
            )
        }.onFailure { unavailable("drawer spacing", it.message) }
    }

    private val coverDrawerGapListeners: MutableSet<View> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )

    private fun installCoverDrawerCutoutInsets(classLoader: ClassLoader) {
        val containerClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.ui.honeypots.applist.presentation.ApplistContainer",
            classLoader
        ) ?: return unavailable("cover drawer cutout insets", "ApplistContainer missing")
        runCatching {
            XposedBridge.hookAllConstructors(
                containerClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val bind = { host: View ->
                            host.setOnApplyWindowInsetsListener { target, insets ->
                                consumeCoverDrawerTopCutout(target, insets)
                            }
                        }
                        bind(view)
                        view.addOnAttachStateChangeListener(
                            object : View.OnAttachStateChangeListener {
                                override fun onViewAttachedToWindow(v: View) {
                                    bind(v)
                                }

                                override fun onViewDetachedFromWindow(v: View) = Unit
                            }
                        )
                    }
                }
            )
            CoverRuntime.log(SCOPE, "cover drawer 180 cutout inset consumer installed")
        }.onFailure { unavailable("cover drawer cutout insets", it.message) }
    }

    private fun consumeCoverDrawerTopCutout(
        view: View,
        insets: android.view.WindowInsets
    ): android.view.WindowInsets {
        if (
            !compactCoverUiActive() ||
            !CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(view.context))
        ) {
            return insets
        }
        if (view.display?.rotation != android.view.Surface.ROTATION_180) {
            return insets
        }
        val cutout = insets.getInsets(android.view.WindowInsets.Type.displayCutout())
        if (cutout.top <= 0) return insets
        CoverRuntime.log(SCOPE, "cover drawer consumed rotation-180 top cutout=${cutout.top}")
        return insets.inset(0, cutout.top, 0, 0)
    }

    private fun installHotseatLayoutListener(container: ViewGroup) {
        if (!hotseatLayoutListeners.add(container)) return
        disableCoverLayoutAnimation(container)
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            disableCoverLayoutAnimation(container)
            applyHotseatPosition(container)
        }
        container.post { applyHotseatPosition(container) }
    }

    private fun disableCoverLayoutAnimation(container: ViewGroup) {
        if (!CoverQsModeConfig.readTransaction(container.context).isStableFull) return
        container.layoutTransition = null
        container.animate().cancel()
        container.clearAnimation()
        for (index in 0 until container.childCount) {
            container.getChildAt(index).animate().cancel()
            container.getChildAt(index).clearAnimation()
        }
    }

    private fun applyHotseatPosition(container: ViewGroup) {
        if (!compactCoverUiActive() || !CoverRuntime.isCoverView(container)) return
        val balloonId = container.resources.getIdentifier(
            "hotseat_balloon",
            "id",
            container.context.packageName
        )
        if (balloonId == 0) return
        val balloon = container.findViewById<View>(balloonId) ?: return
        val params = balloon.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val originalTopMargin = hotseatOriginalTopMargins.getOrPut(balloon) {
            params.topMargin
        }
        val targetTopMargin = originalTopMargin - HOTSEAT_TOP_OFFSET_PX
        if (params.topMargin != targetTopMargin) {
            params.topMargin = targetTopMargin
            balloon.layoutParams = params
        }

        val originalTranslationX = hotseatOriginalTranslationsX.getOrPut(balloon) {
            balloon.translationX
        }
        val verticalHotseat = balloon.height > balloon.width
        val directionTowardCenter = when {
            !verticalHotseat -> 0
            container.left + (container.width / 2) < (container.rootView.width / 2) -> 1
            else -> -1
        }
        val targetTranslationX = originalTranslationX +
            (directionTowardCenter * HOTSEAT_HORIZONTAL_SAFE_INSET_PX)
        if (balloon.translationX != targetTranslationX) {
            balloon.translationX = targetTranslationX
        }
    }

    private fun resizeHomeIcon(view: View, drawable: Drawable) {
        if (!isCoverLauncherIcon(view)) return
        val textView = view as? TextView
        val drawerItem = textView?.let(::isCoverDrawerItem) == true
        val centeredDrawerItem = drawerItem && textView?.let(::fullQsDrawerLayout) == true
        val homeItem = !drawerItem && isCoverHomeIcon(view)
        if (homeItem && CoverQsModeConfig.readTransaction(view.context).isStableFull) return
        val labeledHomeItem = homeItem && textView?.let(::hasVisibleHomeLabel) == true
        val iconSize = coverIconSizePx(view)
        val iconLeft = if (centeredDrawerItem) {
            coverDrawerScaledPx(view, FULL_QS_DRAWER_ICON_HORIZONTAL_OFFSET_PX)
        } else {
            0
        }
        val boundsMatch =
            drawable.bounds.width() == iconSize &&
                drawable.bounds.height() == iconSize &&
                drawable.bounds.left == iconLeft
        if (boundsMatch && !drawerItem && !labeledHomeItem) return
        if (boundsMatch && textView != null) {
            if (drawerItem) {
                val style = coverDrawerIconStyle(textView)
                val gravity = if (fullQsDrawerLayout(textView)) {
                    Gravity.CENTER
                } else {
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
                val expectedTextSizePx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    style.labelTextSizeSp,
                    textView.resources.displayMetrics
                )
                if (
                    textView.compoundDrawablePadding == style.labelGapPx &&
                    kotlin.math.abs(textView.textSize - expectedTextSizePx) < 0.01f &&
                    !textView.includeFontPadding &&
                    textView.gravity == gravity &&
                    textView.maxLines == 1
                ) return
            } else if (
                textView.compoundDrawablePadding == COVER_DRAWER_ICON_LABEL_GAP_PX &&
                !textView.includeFontPadding &&
                textView.maxLines == 1 &&
                textView.gravity == (Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            ) {
                return
            }
        }
        drawable.setBounds(iconLeft, 0, iconLeft + iconSize, iconSize)
        textView?.let { value ->
            if (drawerItem) {
                val drawables = value.compoundDrawablesRelative
                if (drawables.getOrNull(1) !== drawable) {
                    value.setCompoundDrawablesRelative(
                        drawables.getOrNull(0),
                        drawable,
                        drawables.getOrNull(2),
                        drawables.getOrNull(3)
                    )
                }
            }
            if (drawerItem) {
                val style = coverDrawerIconStyle(value)
                value.compoundDrawablePadding = style.labelGapPx
                value.setTextSize(style.labelTextSizeSp)
            } else if (labeledHomeItem) {
                value.compoundDrawablePadding = COVER_DRAWER_ICON_LABEL_GAP_PX
            }
            if (drawerItem || labeledHomeItem) {
                value.includeFontPadding = false
                value.maxLines = 1
                value.ellipsize = TextUtils.TruncateAt.END
                value.gravity = if (drawerItem && fullQsDrawerLayout(value)) {
                    Gravity.CENTER
                } else {
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                }
                value.textAlignment = View.TEXT_ALIGNMENT_CENTER
            }
        }
        view.invalidate()
    }

    private fun applyDrawerSpacingOnce(recycler: View) {
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(recycler.context))) return
        val stableFull = CoverQsModeConfig.readTransaction(recycler.context).isStableFull
        if (!stableFull) {
            if (!normalizedDrawers.add(recycler)) {
                applyCoverDrawerTabAppGap(recycler)
                return
            }
            recycler.setPadding(
                DRAWER_HORIZONTAL_PADDING_PX,
                DRAWER_TOP_PADDING_PX,
                DRAWER_HORIZONTAL_PADDING_PX,
                DRAWER_BOTTOM_PADDING_PX
            )
            applyCoverDrawerTabAppGap(recycler)
            return
        }
        val root = recycler.rootView
        val workTab = findCoverViewById(
            root,
            recycler.context.packageName,
            "apps_change_page_mode_button"
        )?.takeIf { it.visibility == View.VISIBLE && it.isShown }
        if (stableFull) {
            val insetReady = workTab != null &&
                XposedHelpers.getAdditionalInstanceField(
                    workTab,
                    "flexunlock_full_qs_work_tab_inset_target"
                ) is Number
            if (!insetReady) {
                return
            }
        }
        val rotation = recycler.display?.rotation ?: android.view.Surface.ROTATION_0
        val horizontalPadding = if (
            rotation == android.view.Surface.ROTATION_0 ||
                rotation == android.view.Surface.ROTATION_180
        ) {
            DRAWER_PORTRAIT_HORIZONTAL_PADDING_PX
        } else {
            DRAWER_HORIZONTAL_PADDING_PX
        }
        val workTabHostInset = (workTab?.let {
            XposedHelpers.getAdditionalInstanceField(
                it,
                "flexunlock_full_qs_work_tab_inset_target"
            )
        } as? Number)?.toInt() ?: 0
        val targetTop = if (
            rotation == android.view.Surface.ROTATION_0 ||
            rotation == android.view.Surface.ROTATION_180
        ) {
            coverDrawerInitialTopPaddingPx(
                stableFull = true,
                baseTopPaddingPx = DRAWER_TOP_PADDING_PX,
                workTabMeasuredHeightPx = workTab?.measuredHeight ?: 0,
                workTabLayoutHeightPx = workTab?.layoutParams?.height ?: 0
            )
        } else {
            val workspaceTab = findCoverViewById(
                root,
                recycler.context.packageName,
                "workspace_tab_layout"
            )?.takeIf { it.visibility == View.VISIBLE && it.height > 0 }
            workspaceTab?.let { tab ->
                val recyclerLocation = IntArray(2).also(recycler::getLocationOnScreen)
                val tabLocation = IntArray(2).also(tab::getLocationOnScreen)
                coverDrawerStableTopPaddingPx(
                    recyclerTopOnScreenPx = recyclerLocation[1],
                    recyclerTranslationYPx = recycler.translationY.roundToInt(),
                    tabBottomOnScreenPx = tabLocation[1] + tab.height
                )
            } ?: DRAWER_TOP_PADDING_PX
        }
        val geometry = "rotation=$rotation recycler=${recycler.width}x${recycler.height} " +
            "tab=${workTab?.width}x${workTab?.height} inset=$workTabHostInset " +
            "padding=${recycler.paddingTop}->$targetTop"
        if (XposedHelpers.getAdditionalInstanceField(recycler, "flexunlock_drawer_geometry") != geometry) {
            XposedHelpers.setAdditionalInstanceField(recycler, "flexunlock_drawer_geometry", geometry)
            CoverRuntime.log(SCOPE, "cover drawer geometry $geometry")
        }
        if (
            recycler.paddingTop != targetTop ||
            recycler.paddingLeft != horizontalPadding ||
            recycler.paddingRight != horizontalPadding
        ) {
            recycler.setPadding(
                horizontalPadding,
                targetTop,
                horizontalPadding,
                recycler.paddingBottom
            )
        }
        applyCoverDrawerTabAppGap(recycler)
    }

    private fun scheduleCoverRecentsStageLaunch(handler: Any, token: Long) {
        synchronized(nativeGestureDispatchLock) {
            if (recentsStageGestureToken != token || recentsStageLaunchPending) return
            recentsStageLaunchPending = true
            // Keep the native transition owner alive while the stage manager is
            // being constructed. The flag is cleared if all attempts fail.
            coverRecentsDispatchCommitted = true
        }
        launchCoverRecentsStageAttempt(handler, token, 0)
    }

    private fun launchCoverRecentsStageAttempt(handler: Any, token: Long, attempt: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            val valid = synchronized(nativeGestureDispatchLock) {
                recentsStageGestureToken == token && recentsStageLaunchPending
            }
            if (!valid) return@postDelayed
            if (
                fullDexActive() ||
                !CoverRuntime.isCoverSessionEligible() ||
                coverRecentsLifecycleResumed
            ) {
                synchronized(nativeGestureDispatchLock) {
                    recentsStageLaunchPending = false
                }
                return@postDelayed
            }

            val stage = coverRecentsStageManager
            val stageDisplayId = stage?.let { getIntFieldOrNull(it, "displayId") }
            val stageReady = stage != null &&
                (stageDisplayId == null || isClosedCoverDisplay(stageDisplayId))
            if (!stageReady) {
                if (attempt < RECENTS_STAGE_RETRY_ATTEMPTS - 1) {
                    launchCoverRecentsStageAttempt(handler, token, attempt + 1)
                } else {
                    finishCoverRecentsStageLaunch(token, false)
                }
                return@postDelayed
            }

            val launched = runCatching {
                XposedHelpers.callMethod(stage, "startRecentsActivityInternal")
                true
            }.onFailure { error ->
                CoverRuntime.log(
                    SCOPE,
                    "display-1 Recents stage launch attempt=${attempt + 1} failed: ${error.message}"
                )
            }.getOrDefault(false)
            if (launched) {
                finishCoverRecentsStageLaunch(token, true)
                CoverRuntime.log(SCOPE, "display-1 Recents stage launch invoked")
            } else if (attempt < RECENTS_STAGE_RETRY_ATTEMPTS - 1) {
                launchCoverRecentsStageAttempt(handler, token, attempt + 1)
            } else {
                finishCoverRecentsStageLaunch(token, false)
            }
        }, if (attempt == 0) 0L else RECENTS_STAGE_RETRY_DELAY_MS)
    }

    private fun finishCoverRecentsStageLaunch(token: Long, success: Boolean) {
        val current = synchronized(nativeGestureDispatchLock) {
            if (recentsStageGestureToken != token) return
            recentsStageLaunchPending = false
            if (!success) coverRecentsDispatchCommitted = false
            true
        }
        if (!current || success) return
        completeCoverGestureTransition("recents-stage-unavailable")
        CoverRuntime.log(SCOPE, "display-1 Recents stage manager unavailable after retries")
    }

    private fun installCoverDrawerGapListener(recycler: View) {
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(recycler.context))) return
        if (!coverDrawerGapListeners.add(recycler)) return
        disableRecyclerLayoutAnimation(recycler)
        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            disableRecyclerLayoutAnimation(recycler)
            applyCoverDrawerTabAppGap(recycler)
            if (!CoverQsModeConfig.readTransaction(recycler.context).isStableFull) return@addOnLayoutChangeListener
            val rotation = recycler.display?.rotation ?: return@addOnLayoutChangeListener
            if (
                XposedHelpers.getAdditionalInstanceField(
                    recycler,
                    "flexunlock_drawer_spacing_rotation"
                ) == rotation
            ) return@addOnLayoutChangeListener
            XposedHelpers.setAdditionalInstanceField(
                recycler,
                "flexunlock_drawer_spacing_rotation",
                rotation
            )
            scheduleCoverDrawerSpacing(recycler)
        }
        recycler.post {
            applyCoverDrawerTabAppGap(recycler)
            applyCoverDrawerSearchButtonSpacing(
                recycler.rootView,
                recycler.context.packageName
            )
        }
    }

    private fun disableRecyclerLayoutAnimation(recycler: View) {
        if (!CoverQsModeConfig.readTransaction(recycler.context).isStableFull) return
        recycler.animate().cancel()
        recycler.clearAnimation()
        runCatching { XposedHelpers.callMethod(recycler, "setItemAnimator", null) }
    }

    private fun scheduleCoverDrawerSpacing(recycler: View) {
        if (!scheduledDrawerSpacing.add(recycler)) return

        fun dispatch(attempt: Int) {
            recycler.postOnAnimation {
                if (!scheduledDrawerSpacing.contains(recycler)) return@postOnAnimation
                val stableFull = CoverQsModeConfig.readTransaction(recycler.context).isStableFull
                val root = recycler.rootView
                val packageName = recycler.context.packageName
                val workTab = findCoverViewById(
                    root,
                    packageName,
                    "apps_change_page_mode_button"
                )
                val cell = findCoverViewById(recycler, packageName, "cell_layout")
                val layoutReady = recycler.width > 0 && recycler.height > 0 &&
                    (!stableFull || (
                        workTab?.height ?: 0 > 0 &&
                            cell?.width ?: 0 > 0 &&
                            cell?.height ?: 0 > 0
                        ))
                if (!layoutReady && attempt < 8) {
                    dispatch(attempt + 1)
                    return@postOnAnimation
                }
                scheduledDrawerSpacing.remove(recycler)
                applyDrawerSpacingOnce(recycler)
            }
        }

        dispatch(0)
    }

    private fun applyCoverDrawerTabAppGap(recycler: View) {
        if (
            !compactCoverUiActive() ||
            !CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(recycler.context))
        ) return
        val rotation = recycler.display?.rotation ?: 0
        val stableFull = CoverQsModeConfig.readTransaction(recycler.context).isStableFull
        if (stableFull) {
            XposedHelpers.removeAdditionalInstanceField(recycler, "flexunlock_rotated_gap_geometry")
            if (recycler.translationY != 0f) recycler.translationY = 0f
            return
        }
        XposedHelpers.removeAdditionalInstanceField(recycler, "flexunlock_rotated_gap_geometry")
        if (recycler.translationY != 0f) recycler.translationY = 0f
        if (rotation != android.view.Surface.ROTATION_180) return
        val root = recycler.rootView ?: return
        val packageName = recycler.context.packageName
        val tab = findCoverViewById(root, packageName, "workspace_tab_layout") ?: return
        val cell = findCoverViewById(recycler, packageName, "cell_layout")
            ?: findCoverViewById(root, packageName, "cell_layout")
            ?: return
        if (tab.height <= 0 || cell.height <= 0) return
        val tabLoc = IntArray(2)
        val cellLoc = IntArray(2)
        tab.getLocationOnScreen(tabLoc)
        cell.getLocationOnScreen(cellLoc)
        val extra = coverDrawerRotation180RecyclerShiftPx(
            tabBottomOnScreenPx = tabLoc[1] + tab.height,
            cellTopOnScreenPx = cellLoc[1]
        )
        if (extra > 2) recycler.translationY = -extra.toFloat()
    }

    private fun markCoverDrawerViewModel(
        appListPot: Any,
        viewModelClass: Class<*>,
        reason: String
    ) {
        if (!compactCoverUiActive()) return
        val viewModel = runCatching {
            XposedHelpers.callMethod(appListPot, "e")
        }.getOrNull()?.takeIf(viewModelClass::isInstance) ?: run {
            var currentClass: Class<*>? = appListPot.javaClass
            var resolved: Any? = null
            while (currentClass != null && resolved == null) {
                resolved = currentClass.declaredFields.firstNotNullOfOrNull { field ->
                    if (java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                        return@firstNotNullOfOrNull null
                    }
                    runCatching {
                        field.isAccessible = true
                        field.get(appListPot)
                    }.getOrNull()?.takeIf(viewModelClass::isInstance)
                }
                currentClass = currentClass.superclass
            }
            resolved
        }
        if (viewModel == null) {
            unavailable("drawer ViewModel resolve", "reason=$reason")
            return
        }
        coverDrawerViewModels.add(viewModel)
        forceCoverDrawerGrid(viewModel, reason)
        reflowCoverDrawerItems(viewModel, reason)
    }

    private fun forceCoverDrawerGrid(viewModel: Any, reason: String) {
        if (!compactCoverUiActive()) return
        runCatching {
            val oldColumns = XposedHelpers.getIntField(viewModel, "l0")
            val oldRows = XposedHelpers.getIntField(viewModel, "m0")
            XposedHelpers.setIntField(viewModel, "l0", COVER_DRAWER_COLUMNS)
            XposedHelpers.setIntField(viewModel, "m0", COVER_DRAWER_ROWS)
            if (oldColumns != COVER_DRAWER_COLUMNS || oldRows != COVER_DRAWER_ROWS) {
                CoverRuntime.log(
                    SCOPE,
                    "display-1 drawer grid state " +
                        "${oldColumns}x${oldRows}->" +
                        "${COVER_DRAWER_COLUMNS}x$COVER_DRAWER_ROWS reason=$reason"
                )
            }
        }.onFailure {
            CoverRuntime.log(
                SCOPE,
                "display-1 drawer legacy grid fields unavailable; " +
                    "method policies remain active reason=$reason"
            )
        }
    }

    private fun reflowCoverDrawerSourceItems(viewModel: Any, reason: String): Int {
        if (!compactCoverUiActive()) return 0
        val sourceItems = getFieldOrNull(viewModel, "P") as? Collection<*> ?: run {
            unavailable("drawer source item reflow", "source collection unavailable reason=$reason")
            return 0
        }
        val positionedItems = sourceItems.mapIndexedNotNull { sourceIndex, item ->
            item ?: return@mapIndexedNotNull null
            val page = runCatching {
                (XposedHelpers.callMethod(item, "f") as Number).toInt()
            }.getOrDefault(Int.MAX_VALUE)
            val rank = runCatching {
                (XposedHelpers.callMethod(item, "g") as Number).toInt()
            }.getOrDefault(Int.MAX_VALUE)
            DrawerSourceItem(
                item = item,
                sourceIndex = sourceIndex,
                primary = runCatching {
                    XposedHelpers.callMethod(viewModel, "T", item) == true
                }.getOrDefault(true),
                page = page,
                rank = rank
            )
        }
        val positionOrder = compareBy<DrawerSourceItem> {
            if (it.page >= 0 && it.rank >= 0) 0 else 1
        }.thenBy {
            if (it.page >= 0) it.page else Int.MAX_VALUE
        }.thenBy {
            if (it.rank >= 0) it.rank else Int.MAX_VALUE
        }.thenBy(DrawerSourceItem::sourceIndex)
        val pageCapacity = COVER_DRAWER_COLUMNS * COVER_DRAWER_ROWS
        var itemCount = 0
        var primaryCount = 0
        var secondaryCount = 0
        listOf(true, false).forEach { primary ->
            positionedItems
                .asSequence()
                .filter { it.primary == primary }
                .sortedWith(positionOrder)
                .forEachIndexed { linearIndex, sourceItem ->
                    runCatching {
                        XposedHelpers.callMethod(
                            sourceItem.item,
                            "i",
                            linearIndex / pageCapacity
                        )
                        XposedHelpers.callMethod(
                            sourceItem.item,
                            "j",
                            linearIndex % pageCapacity
                        )
                    }.onSuccess {
                        itemCount += 1
                        if (primary) primaryCount += 1 else secondaryCount += 1
                    }
                }
        }
        val primaryPages = (primaryCount + pageCapacity - 1) / pageCapacity
        val secondaryPages = (secondaryCount + pageCapacity - 1) / pageCapacity
        val pageCount = maxOf(primaryPages, secondaryPages)
        CoverRuntime.log(
            SCOPE,
            "display-1 drawer source items reflowed " +
                "items=$itemCount primary=$primaryCount/$primaryPages " +
                "secondary=$secondaryCount/$secondaryPages " +
                "capacity=$pageCapacity pages=$pageCount reason=$reason"
        )
        return pageCount
    }

    private fun reflowCoverDrawerItems(viewModel: Any, reason: String) {
        if (!compactCoverUiActive()) return
        val pageCapacity = COVER_DRAWER_COLUMNS * COVER_DRAWER_ROWS
        val itemCollections = listOf("K", "M").mapNotNull { field ->
            val items = getFieldOrNull(viewModel, field) as? Collection<*>
                ?: return@mapNotNull null
            field to items
        }
        var itemCount = 0
        itemCollections.forEach { (_, items) ->
            if (!coverDrawerNeedsSlotReflow(items, pageCapacity)) return@forEach
            items.forEachIndexed { linearIndex, sourceItem ->
                sourceItem ?: return@forEachIndexed
                runCatching {
                    XposedHelpers.callMethod(sourceItem, "i", linearIndex / pageCapacity)
                    XposedHelpers.callMethod(sourceItem, "j", linearIndex % pageCapacity)
                }.onSuccess {
                    itemCount += 1
                }
            }
        }
        CoverRuntime.log(
            SCOPE,
            "display-1 drawer existing items reflowed " +
                "collections=${itemCollections.map { it.first }} " +
                "items=$itemCount capacity=$pageCapacity reason=$reason"
        )
    }

    private fun coverDrawerNeedsSlotReflow(items: Collection<*>, pageCapacity: Int): Boolean {
        val seen = HashSet<Long>(items.size)
        items.forEach { item ->
            item ?: return true
            val page = runCatching {
                (XposedHelpers.callMethod(item, "f") as Number).toInt()
            }.getOrDefault(-1)
            val rank = runCatching {
                (XposedHelpers.callMethod(item, "g") as Number).toInt()
            }.getOrDefault(-1)
            if (page < 0 || rank !in 0 until pageCapacity) return true
            val key = (page.toLong() shl 32) xor (rank.toLong() and 0xffffffffL)
            if (!seen.add(key)) return true
        }
        return false
    }

    private fun isCoverAppListGrid(controller: Any): Boolean {
        if (!compactCoverUiActive()) return false
        val context = getFieldOrNull(controller, "uiContext") as? Context ?: return false
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return false
        val honeyInfo = getFieldOrNull(controller, "honeyInfo") ?: return false
        val type = runCatching {
            XposedHelpers.callMethod(honeyInfo, "getType")?.toString()
        }.getOrNull()?.uppercase() ?: return false
        return type in setOf("APPLIST", "VERTICAL_APPLIST", "APP_SCREEN")
    }

    private fun isCoverHomeIcon(view: View): Boolean {
        if (!compactCoverUiActive() || !CoverRuntime.isCoverView(view)) return false
        var current: View? = view
        repeat(12) {
            val candidate = current ?: return false
            val resourceName = runCatching {
                if (candidate.id == View.NO_ID) "" else {
                    candidate.resources.getResourceEntryName(candidate.id)
                }
            }.getOrDefault("")
            val identity = "${candidate.javaClass.name} $resourceName"
                .lowercase()
                .filter(Char::isLetterOrDigit)
            if (identity.contains("taskbar")) return false
            if (
                identity.contains("hotseat") ||
                identity.contains("workspacecelllayout") ||
                identity.contains("workspacefastrecyclerview") ||
                identity.contains("workspacecontainer") ||
                identity.contains("workspacepotview")
            ) {
                return true
            }
            current = candidate.parent as? View
        }
        return false
    }

    private fun isCoverLauncherIcon(view: View): Boolean =
        isCoverHomeIcon(view) || isCoverDrawerItem(view)

    private fun hasVisibleHomeLabel(textView: TextView): Boolean =
        textView.text?.isNotBlank() == true &&
            textView.visibility == View.VISIBLE &&
            textView.alpha > 0f &&
            Color.alpha(textView.currentTextColor) > 0

    private fun coverIconSizePx(view: View): Int = if (isCoverHomeIcon(view)) {
        coverHomeIconSizePx(view)
    } else {
        coverDrawerIconSizePx(view)
    }

    private fun coverHomeIconSizePx(view: View): Int {
        val textView = view as? TextView ?: return configuredCoverHomeIconSizePx
        if (CoverQsModeConfig.readTransaction(view.context).isStableOriginal &&
            view.width > 0 && view.height > 0
        ) {
            return coverHomeIconSizeForCellPx(
                configuredCoverHomeIconSizePx,
                minOf(
                    view.width - view.paddingLeft - view.paddingRight,
                    view.height - view.paddingTop - view.paddingBottom
                )
            )
        }
        val labelPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = textView.textSize
            typeface = textView.typeface
        }
        val fontMetrics = labelPaint.fontMetricsInt
        val labelHeight = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(1)
        return safeCoverHomeIconSizePx(
            hasVisibleLabel = hasVisibleHomeLabel(textView),
            requestedSizePx = configuredCoverHomeIconSizePx,
            itemWidthPx = view.width,
            itemHeightPx = view.height,
            paddingLeftPx = view.paddingLeft,
            paddingTopPx = view.paddingTop,
            paddingRightPx = view.paddingRight,
            paddingBottomPx = view.paddingBottom,
            labelHeightPx = labelHeight,
            labelGapPx = COVER_DRAWER_ICON_LABEL_GAP_PX,
            edgeSafetyPx = COVER_HOME_ICON_EDGE_SAFETY_PX
        )
    }

    private fun coverDrawerIconSizePx(view: View): Int {
        val itemWidth = view.width
        val itemHeight = view.height
        if (itemWidth <= 4 || itemHeight <= 4) {
            return coverDrawerScaledPx(view, configuredCoverDrawerIconSizePx)
        }
        val labelTextSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            COVER_DRAWER_LABEL_TEXT_SIZE_SP,
            view.resources.displayMetrics
        )
        val labelPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = labelTextSizePx
            typeface = (view as? TextView)?.typeface
        }
        val fontMetrics = labelPaint.fontMetricsInt
        val labelHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(1)
        val target = safeCoverDrawerIconSizePx(
            isLandscape = fullDexActive() || fullQsDrawerLayout(view) ||
                view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            requestedSizePx = coverDrawerScaledPx(view, configuredCoverDrawerIconSizePx),
            itemWidthPx = itemWidth,
            itemHeightPx = itemHeight,
            paddingLeftPx = view.paddingLeft,
            paddingTopPx = view.paddingTop,
            paddingRightPx = view.paddingRight,
            paddingBottomPx = view.paddingBottom,
            labelHeightPx = labelHeight,
            labelGapPx = coverDrawerScaledPx(view, COVER_DRAWER_ICON_LABEL_GAP_PX),
            edgeSafetyPx = coverDrawerScaledPx(view, COVER_DRAWER_ICON_EDGE_SAFETY_PX)
        )
        return target
    }

    private fun coverDrawerIconStyle(view: View): DrawerIconStyle = DrawerIconStyle(
        iconSizePx = coverDrawerIconSizePx(view),
        labelGapPx = if (fullQsDrawerLayout(view)) {
            FULL_QS_DRAWER_ICON_LABEL_GAP_PX
        } else {
            coverDrawerScaledPx(view, COVER_DRAWER_ICON_LABEL_GAP_PX)
        },
        labelTextSizeSp = COVER_DRAWER_LABEL_TEXT_SIZE_SP
    )

    private fun fullQsDrawerLayout(view: View): Boolean =
        CoverQsModeConfig.readTransaction(view.context).isStableFull && isCoverDrawerItem(view)

    private fun fullQsRotatedDrawer(view: View): Boolean =
        compactCoverUiActive() &&
            CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(view.context)) &&
            CoverQsModeConfig.readTransaction(view.context).isStableFull &&
            (view.display?.rotation == android.view.Surface.ROTATION_90 ||
                view.display?.rotation == android.view.Surface.ROTATION_270)

    private fun coverDrawerScaledPx(view: View, valuePx: Int): Int {
        val metrics = view.resources.displayMetrics
        val mode = view.display?.mode ?: return valuePx
        val scale = coverDrawerLogicalScale(
            logicalWidthPx = metrics.widthPixels,
            logicalHeightPx = metrics.heightPixels,
            nativeWidthPx = mode.physicalWidth,
            nativeHeightPx = mode.physicalHeight,
            rotation = view.display?.rotation ?: 0
        )
        return (valuePx * scale).toInt().coerceAtLeast(1)
    }

    private fun useCompactDrawerRows(view: View): Boolean {
        if (!isCoverAppListCellLayout(view)) return false
        if (view.width <= 0 || view.height <= 0) return false
        val portrait = view.resources.configuration.orientation ==
            Configuration.ORIENTATION_PORTRAIT
        return portrait && view.height > view.width
    }

    private fun applyCoverDrawerCellSize(layout: View) {
        if (fullDexActive()) return
        if (!isCoverAppListCellLayout(layout)) return
        val metrics = layout.resources.displayMetrics
        if (
            !coverDrawerLayoutSettled(
                layout.width,
                layout.height,
                metrics.widthPixels,
                metrics.heightPixels
            )
        ) return
        val landscape = layout.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val rotation = layout.display?.rotation ?: 0
        val stableFull = CoverQsModeConfig.readTransaction(layout.context).isStableFull
        if (!stableFull && landscape) {
            val targetTop = if (rotation == android.view.Surface.ROTATION_180) 0 else layout.paddingTop
            if (
                (layout.paddingRight != 0 || layout.paddingTop != targetTop) &&
                !layout.isInLayout
            ) {
                layout.setPadding(
                    layout.paddingLeft,
                    targetTop,
                    0,
                    layout.paddingBottom
                )
            }
            return
        }
        val fullRotated = stableFull && (
            rotation == android.view.Surface.ROTATION_90 ||
                rotation == android.view.Surface.ROTATION_270
            )
        val currentWidth = runCatching {
            (XposedHelpers.callMethod(layout, "getCellWidth") as Number).toInt()
        }.getOrDefault(0)
        val horizontalPadding = if (fullRotated) {
            0
        } else if (stableFull && currentWidth > 0) {
            ((layout.width - currentWidth * COVER_DRAWER_COLUMNS) / 2).coerceAtLeast(0)
        } else {
            layout.paddingLeft
        }
        if (
            stableFull &&
            (layout.paddingLeft != horizontalPadding || layout.paddingRight != horizontalPadding)
        ) {
            layout.setPadding(
                horizontalPadding,
                layout.paddingTop,
                horizontalPadding,
                layout.paddingBottom
            )
        }
        val hostWidth = layout.width.takeIf { it > 0 } ?: (
            layout.resources.displayMetrics.widthPixels - (2 * DRAWER_HORIZONTAL_PADDING_PX)
            ).coerceAtLeast(COVER_DRAWER_COLUMNS)
        val cellWidth = if (stableFull) currentWidth else {
            coverDrawerCellWidthPx(
                layoutWidthPx = hostWidth,
                searchReserveLeftPx = 0,
                columnCount = COVER_DRAWER_COLUMNS
            )
        }
        val visibleBounds = Rect()
        val visibleHeight = if (
            stableFull &&
            layout.getGlobalVisibleRect(visibleBounds) &&
            coverDrawerLayoutSettled(
                visibleBounds.width(),
                visibleBounds.height(),
                metrics.widthPixels,
                metrics.heightPixels
            )
        ) {
            visibleBounds.height()
        } else {
            layout.height
        }
        val cellHeight = if (visibleHeight > 0) {
            coverDrawerCellHeightPx(visibleHeight, COVER_DRAWER_ROWS)
        } else {
            0
        }
        val currentHeight = runCatching {
            (XposedHelpers.callMethod(layout, "getCellHeight") as Number).toInt()
        }.getOrDefault(0)
        if (
            currentWidth == cellWidth &&
            (cellHeight <= 0 || currentHeight == cellHeight)
        ) return
        if (!stableFull) runCatching { XposedHelpers.callMethod(layout, "setCellWidth", cellWidth) }
        if (cellHeight > 0) {
            runCatching { XposedHelpers.callMethod(layout, "setCellHeight", cellHeight) }
        }
        if (!layout.isInLayout) layout.requestLayout()
        layout.invalidate()
    }

    private fun isCoverAppListCellLayout(view: View): Boolean {
        if (
            !compactCoverUiActive() ||
            !CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(view.context))
        ) return false
        if (!view.javaClass.name.endsWith("CellLayout")) return false
        val resourceName = runCatching {
            if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
        }.getOrDefault("")
        return resourceName == "cell_layout"
    }

    private fun isCoverDrawerIcon(view: View): Boolean =
        !view.javaClass.name.endsWith("FolderIconViewImpl") && isCoverDrawerItem(view)

    private fun isCoverDrawerFolder(view: View): Boolean =
        view.javaClass.name.endsWith("FolderIconViewImpl") && isCoverDrawerItem(view)

    private fun isFullDexOverlayDrawerItem(view: View): Boolean {
        if (!fullDexCoverEnabled(view.context)) return false
        var current: View? = view
        repeat(12) {
            val candidate = current ?: return false
            val resourceName = runCatching {
                if (candidate.id == View.NO_ID) "" else {
                    candidate.resources.getResourceEntryName(candidate.id)
                }
            }.getOrDefault("")
            val identity = "${candidate.javaClass.name} $resourceName"
                .lowercase()
                .filter(Char::isLetterOrDigit)
            if (identity.contains("taskbar")) return false
            if (
                identity.contains("verticalapplistrecyclerview") ||
                identity.contains("verticalapplistcontainer") ||
                identity.contains("overlayappscontainer")
            ) return true
            current = candidate.parent as? View
        }
        return false
    }

    private fun isCoverDrawerItem(view: View): Boolean {
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(view.context))) return false
        if (!compactCoverUiActive() && !isFullDexOverlayDrawerItem(view)) return false
        var current: View? = view
        repeat(12) {
            val candidate = current ?: return false
            val resourceName = runCatching {
                if (candidate.id == View.NO_ID) "" else {
                    candidate.resources.getResourceEntryName(candidate.id)
                }
            }.getOrDefault("")
            val identity = "${candidate.javaClass.name} $resourceName"
                .lowercase()
                .filter(Char::isLetterOrDigit)
            if (identity.contains("taskbar")) return false
            if (
                identity.contains("applistfastrecyclerview") ||
                identity.contains("applistcelllayout") ||
                identity.contains("applistcontainer") ||
                identity.contains("applistpotview") ||
                identity.contains("verticalapplistrecyclerview") ||
                identity.contains("verticalapplistcontainer") ||
                identity.contains("overlayappscontainer")
            ) {
                return true
            }
            current = candidate.parent as? View
        }
        return false
    }

    private fun applyDrawerContentTopPadding(view: View) {
        if (!usesCompactDrawerTopPadding(view)) return
        val targetTopPadding =
            (view.paddingTop - COVER_DRAWER_PORTRAIT_TOP_PADDING_REDUCTION_PX).coerceAtLeast(0)
        if (view.paddingTop == targetTopPadding) return
        view.setPadding(
            view.paddingLeft,
            targetTopPadding,
            view.paddingRight,
            view.paddingBottom
        )
    }

    private fun usesCompactDrawerTopPadding(view: View): Boolean {
        if (!isCoverDrawerItem(view)) return false
        var current: View? = view.parent as? View
        var depth = 0
        while (current != null && depth < 12) {
            val candidate = current
            if (isCoverAppListCellLayout(candidate)) {
                return useCompactDrawerRows(candidate)
            }
            current = candidate.parent as? View
            depth++
        }
        return false
    }

    private fun homeIconPosition(view: View): Rect {
        val iconSize = coverIconSizePx(view)
        val width = minOf(iconSize, view.width.coerceAtLeast(iconSize))
        val height = minOf(iconSize, view.height.coerceAtLeast(iconSize))
        val left = ((view.width - width) / 2).coerceAtLeast(0)
        val top = view.paddingTop.coerceAtLeast(0)
        return Rect(left, top, left + width, top + height)
    }

    private fun centeredDrawerIconPosition(view: View): Rect {
        val textView = view as? TextView ?: return homeIconPosition(view)
        val iconSize = coverDrawerIconSizePx(view)
        val fontMetrics = textView.paint.fontMetricsInt
        val labelHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(1)
        val top = coverDrawerCenteredIconTopPx(
            itemHeightPx = view.height,
            paddingTopPx = view.paddingTop,
            paddingBottomPx = view.paddingBottom,
            iconHeightPx = iconSize,
            labelHeightPx = labelHeight,
            labelGapPx = coverDrawerIconStyle(view).labelGapPx
        )
        val left = ((view.width - iconSize) / 2).coerceAtLeast(0)
        return Rect(left, top, left + iconSize, top + iconSize)
    }

    private fun iconPositionOf(view: View): Rect {
        val nativePosition = runCatching {
            XposedHelpers.callMethod(view, "getIconPosition") as? Rect
        }.getOrNull()
        if (nativePosition != null && nativePosition.width() > 0 && nativePosition.height() > 0) {
            return Rect(
                nativePosition.left.coerceAtLeast(0),
                nativePosition.top.coerceAtLeast(0),
                nativePosition.right.coerceAtMost(view.width.coerceAtLeast(1)),
                nativePosition.bottom.coerceAtMost(view.height.coerceAtLeast(1))
            ).takeIf { it.width() > 0 && it.height() > 0 } ?: nativePosition
        }

        val drawable = runCatching {
            XposedHelpers.callMethod(view, "getIcon") as? Drawable
        }.getOrNull() ?: (view as? TextView)?.compoundDrawablesRelative?.getOrNull(1)
        val iconSize = coverIconSizePx(view)
        val width = drawable?.bounds?.width()?.takeIf { it > 0 }
            ?: minOf(iconSize, view.width.coerceAtLeast(1))
        val height = drawable?.bounds?.height()?.takeIf { it > 0 }
            ?: minOf(iconSize, view.height.coerceAtLeast(1))
        return Rect(
            ((view.width - width) / 2).coerceAtLeast(0),
            view.paddingTop.coerceAtLeast(0),
            (((view.width - width) / 2).coerceAtLeast(0) + width)
                .coerceAtMost(view.width.coerceAtLeast(width)),
            (view.paddingTop.coerceAtLeast(0) + height)
                .coerceAtMost(view.height.coerceAtLeast(height))
        )
    }

    private fun homeIconBoundsOnScreen(targetView: View): Rect? {
        if (!CoverRuntime.isCoverView(targetView)) return null
        val iconView = findHomeIconView(targetView) ?: return null
        if (!isCoverHomeIcon(iconView)) return null
        val iconPosition = iconPositionOf(iconView)
        val location = IntArray(2)
        iconView.getLocationOnScreen(location)
        return Rect(
            location[0] + iconPosition.left,
            location[1] + iconPosition.top,
            location[0] + iconPosition.right,
            location[1] + iconPosition.bottom
        )
    }

    private fun findHomeIconView(view: View): View? {
        if (view.javaClass.name == "com.honeyspace.ui.common.iconview.IconViewImpl") {
            return view
        }
        val group = view as? ViewGroup ?: return null
        return (0 until group.childCount)
            .asSequence()
            .map(group::getChildAt)
            .mapNotNull(::findHomeIconView)
            .firstOrNull()
    }

    private fun sameRect(first: RectF, second: RectF): Boolean =
        kotlin.math.abs(first.left - second.left) < 0.5f &&
            kotlin.math.abs(first.top - second.top) < 0.5f &&
            kotlin.math.abs(first.right - second.right) < 0.5f &&
            kotlin.math.abs(first.bottom - second.bottom) < 0.5f

    private fun currentCoverDisplayGeometry(baseContext: Context): CoverDisplayGeometry? {
        val context = coverDisplayContext(baseContext) ?: return null
        val display = context.display ?: return null
        val windowManager = context.getSystemService(WindowManager::class.java) ?: return null
        val metrics = windowManager.maximumWindowMetrics
        val bounds = metrics.bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return null
        val navigationInsets = metrics.windowInsets.getInsetsIgnoringVisibility(
            android.view.WindowInsets.Type.navigationBars()
        )
        val navigationBarHeight = maxOf(
            navigationInsets.left,
            navigationInsets.top,
            navigationInsets.right,
            navigationInsets.bottom
        ).coerceAtLeast(1)
        val halfMode = CoverDisplayConfig.readHalfMode(context)
        val cutout = if (halfMode) null else metrics.windowInsets.displayCutout ?: display.cutout
        if (!halfMode && cutout != null) lastCoverDisplayCutout = cutout
        return CoverDisplayGeometry(
            rotation = display.rotation,
            width = bounds.width(),
            height = bounds.height(),
            navigationBarHeight = navigationBarHeight,
            cutout = if (halfMode) null else cutout ?: lastCoverDisplayCutout
        )
    }

    private fun applyCurrentCoverGestureRegions(region: Any, deviceState: Any) {
        val context = getFieldOrNull(region, "context") as? Context ?: return
        val geometry = currentCoverDisplayGeometry(context) ?: return
        val stateNavigationBarHeight = runCatching {
            (XposedHelpers.callMethod(deviceState, "getNavigationBarHeight") as? Number)?.toInt()
        }.getOrNull() ?: 0
        val navigationBarHeight = stateNavigationBarHeight
            .takeIf { it > 0 }
            ?.coerceAtMost(minOf(geometry.width, geometry.height))
            ?: geometry.navigationBarHeight
        val regions = (getFieldOrNull(region, "regions") as? List<*>)
            ?.mapNotNull { it as? RectF }
            ?.takeIf { it.size == 3 }
            ?: return
        val touchRegion = runCatching {
            XposedHelpers.callMethod(region, "getTouchRegionRectF") as? RectF
        }.getOrNull() ?: return
        val disableQuickSwitchRegion = getFieldOrNull(
            region,
            "_disableQuickSwitchRegion"
        ) as? RectF

        val fullQs = CoverQsModeConfig.readTransaction(context).isStableFull
        val halfMode = CoverDisplayConfig.readHalfMode(context)
        val width = geometry.width.toFloat()
        val height = geometry.height.toFloat()
        val zeroRotationCutoutLeft = geometry.cutout
            ?.takeIf { geometry.rotation == 0 }
            ?.boundingRectBottom
            ?.takeIf { !it.isEmpty && it.left in 1 until geometry.width }
            ?.left
        val gestureWidth = coverGestureSafeWidthPx(
            geometry.rotation,
            geometry.width,
            zeroRotationCutoutLeft,
            fullQs,
            halfMode
        )
        val firstX = gestureWidth / 3f
        val secondX = gestureWidth * 2f / 3f
        val top = coverGestureBottomTopPx(
            geometry.height,
            navigationBarHeight,
            nativeTopPx = 0f
        )
        val inputEdge = if (zeroRotationCutoutLeft != null) {
            "BOTTOM_LEFT_CUTOUT_SAFE"
        } else {
            "LOGICAL_BOTTOM"
        }

        regions[0].set(0f, top, firstX, height)
        regions[1].set(firstX, top, secondX, height)
        regions[2].set(secondX, top, gestureWidth, height)
        touchRegion.set(0f, top, gestureWidth, height)
        disableQuickSwitchRegion?.set(0f, 0f, width, top)
        nativeGestureBottomPosition?.let { bottomPosition ->
            XposedHelpers.callMethod(region, "setRegionPosition", bottomPosition)
        }

        XposedHelpers.callMethod(deviceState, "setRotation", geometry.rotation)
        XposedHelpers.callMethod(
            deviceState,
            "setDisplaySize",
            Point(geometry.width, geometry.height)
        )
        XposedHelpers.callMethod(deviceState, "setDisplayCutout", geometry.cutout)
        CoverRuntime.log(
            SCOPE,
            "display-1 gesture thirds rotation=${geometry.rotation} " +
                "logical=${geometry.width}x${geometry.height} inputEdge=$inputEdge " +
                "direction=BOTTOM " +
                "RECENT=${regions[0]} HOME=${regions[1]} BACK=${regions[2]} " +
                "touch=$touchRegion"
        )
    }

    private fun disableCoverGestureOverlayTouches(region: Any) {
        val overlayImpl = getFieldOrNull(region, "overlayWindow") ?: return
        val overlayView = getFieldOrNull(overlayImpl, "overlayWindow") as? View ?: return
        val layoutParams = overlayView.layoutParams as? WindowManager.LayoutParams ?: return
        val flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (layoutParams.flags == flags) return
        layoutParams.flags = flags
        runCatching {
            overlayView.context.getSystemService(WindowManager::class.java)
                ?.updateViewLayout(overlayView, layoutParams)
        }.onFailure {
            unavailable("Samsung overlay not-touchable", it.message)
        }
    }

    private fun installCoverDeadZoneHole(classLoader: ClassLoader) {
        val companionClass = XposedHelpers.findClassIfExists(
            "com.honeyspace.gesture.utils.DeadZoneHole\$Companion",
            classLoader
        ) ?: return unavailable("cover TSP hole", "DeadZoneHole missing")
        val invokeMethod = companionClass.declaredMethods.firstOrNull { method ->
            method.name == "invokeMethod" && method.parameterCount == 4
        } ?: return unavailable("cover TSP hole", "invokeMethod missing")
        invokeMethod.isAccessible = true
        runCatching {
            companionClass.declaredMethods.filter { method ->
                method.name == "setDeadZoneHole" && method.parameterCount == 2
            }.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args.firstOrNull() as? Context ?: return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return
                        val deviceState = param.args.getOrNull(1) ?: return
                        val rotation = runCatching {
                            (XposedHelpers.callMethod(deviceState, "getRotation") as Number).toInt()
                        }.getOrNull() ?: return
                        if (rotation != 0) return
                        val cutout = runCatching {
                            XposedHelpers.callMethod(deviceState, "getDisplayCutout")
                                as? android.view.DisplayCutout
                        }.getOrNull() ?: return
                        val island = cutout.boundingRectBottom.takeIf { !it.isEmpty }
                            ?: cutout.boundingRects.maxByOrNull { it.width() }
                            ?: return
                        invokeMethod.invoke(param.thisObject, context, 2, island.left, island.right)
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 TSP hole punched for 0° island " +
                                "x=${island.left}..${island.right}"
                        )
                    }
                })
            }
            CoverRuntime.log(SCOPE, "display-1 cover TSP hole installed")
        }.onFailure { unavailable("cover TSP hole", it.message) }
    }

    private fun useNativeSamsungTouchRegion(
        manager: Any,
        samsungTouchRegionClass: Class<*>
    ) {
        val displayId = getIntFieldOrNull(manager, "displayId") ?: return
        if (!isClosedCoverDisplay(displayId)) return
        val deviceState = getFieldOrNull(manager, "deviceState") ?: return
        val mode = runCatching {
            XposedHelpers.callMethod(deviceState, "getNaviMode")?.toString()
        }.getOrNull()
        val baseContext = getFieldOrNull(manager, "context") as? Context ?: return
        val fullQs = CoverQsModeConfig.readTransaction(baseContext).isStableFull
        // After the FULL channel swap, Samsung's DeviceState keeps the
        // firmware extra-display id (1), while the app-facing Context and
        // RegionManager use the resolved Android display id (usually 0).
        // The manager identity is therefore the authoritative cover check;
        // rejecting the state id leaves ExtraDisplayTouchRegion in place,
        // whose physical 748x720 region cannot consume logical 1079x1080
        // MotionEvents.
        if (mode != "S_GESTURE" && !fullQs) return

        nativeGestureRegionManagers.add(manager)
        ensureNativeGestureDisplayListener(baseContext)

        val coverContext = coverDisplayContext(baseContext) ?: return
        val systemGestureUseCase = getFieldOrNull(manager, "systemGestureUseCase") ?: return
        val currentOverlay = getFieldOrNull(manager, "overlayWindow")
        val currentOverlayContext = currentOverlay?.let { overlay ->
            runCatching { XposedHelpers.callMethod(overlay, "getContext") as? Context }.getOrNull()
        }
        val overlay = if (
            currentOverlay != null &&
            CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(currentOverlayContext))
        ) {
            currentOverlay
        } else {
            currentOverlay?.let { staleOverlay ->
                runCatching { XposedHelpers.callMethod(staleOverlay, "removeOverlayWindow") }
            }
            val overlayClass = XposedHelpers.findClassIfExists(
                "com.honeyspace.gesture.overlaywindow.SGestureOverlayWindowImpl",
                manager.javaClass.classLoader
            ) ?: return
            XposedHelpers.newInstance(overlayClass, coverContext, systemGestureUseCase).also {
                XposedHelpers.setObjectField(manager, "overlayWindow", it)
            }
        }

        val currentRegion = getFieldOrNull(manager, "touchRegion")
        val currentRegionContext = currentRegion?.let { region ->
            getFieldOrNull(region, "context") as? Context
        }
        if (
            samsungTouchRegionClass.isInstance(currentRegion) &&
            CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(currentRegionContext))
        ) return

        val nativeRegion = XposedHelpers.newInstance(
            samsungTouchRegionClass,
            displayId,
            coverContext,
            overlay,
            systemGestureUseCase,
            getFieldOrNull(manager, "gestureSettingsUseCase") ?: return,
            getFieldOrNull(manager, "navigationSizeSource") ?: return
        )
        XposedHelpers.setObjectField(manager, "touchRegion", nativeRegion)
        XposedHelpers.callMethod(nativeRegion, "updateRegion", deviceState)
        if (nativeGestureRegions.add(manager)) {
            CoverRuntime.log(
                SCOPE,
                "display-1 RegionManager uses display-1 SamsungTouchRegion and overlay"
            )
        }
    }

    private fun ensureNativeGestureDisplayListener(baseContext: Context) {
        if (nativeGestureDisplayListener != null) return
        synchronized(nativeGestureDisplaySyncLock) {
            if (nativeGestureDisplayListener != null) return
            val displayManager = baseContext.applicationContext
                .getSystemService(DisplayManager::class.java) ?: return
            val mainHandler = Handler(Looper.getMainLooper())
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit

                override fun onDisplayChanged(displayId: Int) {
                    if (!CoverRuntime.isCoverDisplay(displayId)) return
                    refreshNativeGestureRegions("display-changed")
                    mainHandler.postDelayed(
                        { refreshNativeGestureRegions("display-changed-frame") },
                        16L
                    )
                    mainHandler.postDelayed(
                        { refreshNativeGestureRegions("display-changed-settled") },
                        64L
                    )
                }
            }
            displayManager.registerDisplayListener(listener, mainHandler)
            nativeGestureDisplayListener = listener
            CoverRuntime.log(SCOPE, "display-1 gesture rotation listener registered")
        }
    }

    private fun refreshNativeGestureRegions(reason: String) {
        val managers = synchronized(nativeGestureRegionManagers) {
            nativeGestureRegionManagers.toList()
        }
        managers.forEach { manager ->
            runCatching {
                refreshNativeGestureRegion(manager, reason)
            }.onFailure {
                unavailable("Samsung touch region rotation sync", it.message)
            }
        }
    }

    private fun refreshNativeGestureRegion(manager: Any, reason: String) {
        val managerDisplayId = getIntFieldOrNull(manager, "displayId") ?: return
        if (!CoverRuntime.isCoverDisplay(managerDisplayId)) return
        val deviceState = getFieldOrNull(manager, "deviceState") ?: return
        val stateDisplayId = runCatching {
            (XposedHelpers.callMethod(deviceState, "getDisplayId") as? Number)?.toInt()
        }.getOrNull()

        val baseContext = getFieldOrNull(manager, "context") as? Context ?: return
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(baseContext))) return
        val geometry = currentCoverDisplayGeometry(baseContext) ?: return
        val displaySize = Point(geometry.width, geometry.height)
        val previousRotation = runCatching {
            (XposedHelpers.callMethod(deviceState, "getRotation") as? Number)?.toInt()
        }.getOrNull() ?: return
        val previousSize = runCatching {
            XposedHelpers.callMethod(deviceState, "getDisplaySize") as? Point
        }.getOrNull() ?: return
        val previousCutout = runCatching {
            XposedHelpers.callMethod(deviceState, "getDisplayCutout") as? DisplayCutout
        }.getOrNull()
        val configurationChanged = previousRotation != geometry.rotation ||
            previousSize != displaySize ||
            previousCutout != geometry.cutout

        XposedHelpers.callMethod(deviceState, "setRotation", geometry.rotation)
        XposedHelpers.callMethod(deviceState, "setDisplaySize", displaySize)
        XposedHelpers.callMethod(deviceState, "setDisplayCutout", geometry.cutout)
        if (configurationChanged) {
            XposedHelpers.callMethod(manager, "resetSwipeRegion", deviceState)
        }
        if (CoverQsModeConfig.readTransaction(baseContext).isStableFull) {
            nativeSamsungTouchRegionClass?.let { regionClass ->
                useNativeSamsungTouchRegion(manager, regionClass)
            }
        }
        val touchRegion = getFieldOrNull(manager, "touchRegion") ?: return
        applyCurrentCoverGestureRegions(touchRegion, deviceState)
        disableCoverGestureOverlayTouches(touchRegion)
        val appliedTouchRegion = runCatching {
            XposedHelpers.callMethod(touchRegion, "getTouchRegionRectF") as? RectF
        }.getOrNull()
        val managerRegion = runCatching {
            XposedHelpers.callMethod(manager, "getRegion")
        }.getOrNull()
        CoverRuntime.log(
            SCOPE,
            "display-1 gesture region rotation sync reason=$reason " +
                "rotation=$previousRotation->${geometry.rotation} " +
                "logical=${previousSize.x}x${previousSize.y}->" +
                "${geometry.width}x${geometry.height} changed=$configurationChanged " +
                "touch=$appliedTouchRegion region=$managerRegion"
        )
    }

    private fun coverDisplayContext(baseContext: Context): Context? =
        CoverDisplayResolver.currentId()?.let { displayId ->
            CoverRuntime.contextForDisplay(baseContext, displayId)
        }

    private fun nativeGestureRegionType(handler: Any, event: MotionEvent): String? {
        val manager = runCatching {
            XposedHelpers.callMethod(handler, "getRegionManager")
        }.getOrNull() ?: objectFieldByClassName(handler, ".RegionManagerImpl") ?: return null
        runCatching {
            refreshNativeGestureRegion(manager, "input-down")
        }.onFailure {
            unavailable("Samsung touch region input sync", it.message)
        }
        val context = getFieldOrNull(manager, "context") as? Context
            ?: getFieldOrNull(handler, "context") as? Context
        val fullQs = context?.let { CoverQsModeConfig.readTransaction(it).isStableFull } == true
        val halfMode = context?.let(CoverDisplayConfig::readHalfMode) == true
        if (context != null && (fullQs || halfMode)) {
            val geometry = currentCoverDisplayGeometry(context) ?: return null
            val bottomCutoutLeft = geometry.cutout
                ?.takeIf { geometry.rotation == 0 }
                ?.boundingRectBottom
                ?.takeIf { !it.isEmpty && it.left in 1 until geometry.width }
                ?.left
            val gestureWidth = coverGestureSafeWidthPx(
                geometry.rotation,
                geometry.width,
                bottomCutoutLeft,
                fullQs = fullQs,
                halfMode = halfMode
            )
            val configuredRegion = runCatching {
                val touchRegion = getFieldOrNull(manager, "touchRegion") ?: return@runCatching null
                XposedHelpers.callMethod(touchRegion, "getTouchRegionRectF") as? RectF
            }.getOrNull()
            val gestureTop = coverGestureConfiguredTopPx(
                geometry.height,
                geometry.navigationBarHeight,
                configuredRegion?.top,
                configuredRegion?.bottom
            )
            return coverGestureRegionType(
                event.x,
                event.y,
                geometry.height,
                gestureTop,
                gestureWidth
            )
        }
        return runCatching {
            XposedHelpers.callMethod(manager, "getRegionType", event.x, event.y)?.toString()
        }.getOrNull()
    }

    private fun objectFieldByClassName(instance: Any, suffix: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.forEach { candidate ->
                if (java.lang.reflect.Modifier.isStatic(candidate.modifiers)) return@forEach
                val value = runCatching {
                    candidate.isAccessible = true
                    candidate.get(instance)
                }.getOrNull() ?: return@forEach
                if (value.javaClass.name.endsWith(suffix)) return value
            }
            type = type.superclass
        }
        return null
    }

    private data class GestureMonitorBinding(
        val monitorName: String,
        val ownerClass: String,
        val discriminator: Int?
    )

    private fun currentGestureMonitorBinding(handler: Any): GestureMonitorBinding {
        val proxy = runCatching {
            XposedHelpers.callMethod(handler, "getInputMonitor")
        }.getOrNull()
        val monitor = proxy?.let { getFieldOrNull(it, "inputMonitor") }
        val receiver = proxy?.let { getFieldOrNull(it, "inputEventReceiver") }
        val owner = receiver?.let(::inputReceiverListener)
        return GestureMonitorBinding(
            monitorName = monitor?.let(::monitorName) ?: "null",
            ownerClass = owner?.javaClass?.name ?: "null",
            discriminator = owner?.let { getIntFieldOrNull(it, "c") }
        )
    }

    private fun inputReceiverListener(receiver: Any): Any? {
        val platformReceiver = getFieldOrNull(receiver, "mReceiver") ?: return null
        return getFieldOrNull(platformReceiver, "val\$listener")
    }

    private fun monitorName(monitor: Any): String =
        (getFieldOrNull(monitor, "mName") as? String) ?: monitor.javaClass.name

    private fun targetViewOf(info: Any): View? = runCatching {
        XposedHelpers.callMethod(info, "getTargetView")
    }.getOrNull() as? View

    private fun isClosedCoverDisplay(displayId: Int): Boolean =
        CoverRuntime.isCoverDisplay(displayId) &&
            CoverRuntime.isBuiltInCoverSessionEligible() &&
            !fullDexActive()

    private fun getIntFieldOrNull(instance: Any, field: String): Int? = runCatching {
        XposedHelpers.getIntField(instance, field)
    }.getOrNull()

    private fun getFieldOrNull(instance: Any, field: String): Any? = runCatching {
        XposedHelpers.getObjectField(instance, field)
    }.getOrNull()

    private fun unavailable(feature: String, reason: String?) {
        CoverRuntime.log(SCOPE, "$feature unavailable: ${reason ?: "unknown"}")
    }
}
