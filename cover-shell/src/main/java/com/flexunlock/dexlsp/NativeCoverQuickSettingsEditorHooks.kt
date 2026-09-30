package com.flexunlock.dexlsp

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import java.util.Collections
import java.util.WeakHashMap

internal object NativeCoverQuickSettingsEditorHooks {
    private const val SCOPE = "CoverQuickSettingsEditor"
    private const val CONTROLLER_CLASS =
        "com.android.systemui.subscreen.SubScreenQuickPanelWindowController"

    @Volatile
    private var qsControllerInstance: Any? = null
    private const val CUSTOMIZER_ACTIVITY =
        "com.android.systemui.qp.customize.SubscreenCustomizerActivity"

    private const val CUSTOMIZER_CONTROLLER =
        "com.android.systemui.qp.customize.SubscreenCustomizerController"
    private const val SUBSCREEN_TILE_LAYOUT =
        "com.android.systemui.qp.SubscreenTileLayout"
    private const val SUBSCREEN_PAGED_TILE_LAYOUT =
        "com.android.systemui.qp.SubscreenPagedTileLayout"
    private const val SUBSCREEN_PARENT_LAYOUT =
        "com.android.systemui.qp.SubscreenParentLayout"
    private const val SUBROOM_QS_TILE_BASE_VIEW =
        "com.android.systemui.qp.SubRoomQsTileBaseView"
    private const val EXTRA_REVEAL_CENTER_X = "flexunlock_reveal_center_x"
    private const val EXTRA_REVEAL_CENTER_Y = "flexunlock_reveal_center_y"
    private const val SUBSCREEN_HOST_CALLBACK =
        "com.android.systemui.qp.SubscreenParentLayout\$mQSHostCallback\$1"
    private const val NATIVE_TILE_ROWS = 3
    private const val CONFIGURED_TILE_ROWS = 4
    // 编辑页磁贴缩小系数(磁贴过大导致网格边缘被裁剪;主面板不生效)
    private const val EDITOR_TILE_SHRINK_5COL = 0.855f
    private const val EDITOR_TILE_SHRINK_4COL = 0.9f
    // 编辑页磁贴内部图标相对 cell 的比例
    private const val EDITOR_TILE_ICON_RATIO = 0.66f
    private const val EDITOR_TILE_MARK = "flexunlockEditorTile"
    private const val MAIN_TILE_LABEL_MARK = "flexunlockMainTileLabel"

    @Volatile
    private var gridReceiverRegistered = false

    private data class EditorEntry(
        val root: ViewGroup,
        val button: TextView,
        val layoutListener: View.OnLayoutChangeListener,
        val attachListener: View.OnAttachStateChangeListener,
        var panelVisible: Boolean = false,
        var primaryPageVisible: Boolean = true,
        var lastLayoutSnapshot: String? = null
    )

    private data class PendingCustomizerLaunch(
        val source: View,
        val intent: Intent,
        val options: Bundle,
        val displayId: Int
    )

    private data class CandidateTile(
        val spec: String,
        val label: String
    )

    /** 编辑页长按拖动手势的触摸状态 */
    private class DragTouchState {
        var downX = 0f
        var downY = 0f
        var startRawX = 0f
        var startRawY = 0f
        var triggered = false
        var dragView: View? = null
        var tileLayout: View? = null
        var record: Any? = null
    }

    private data class NativeTileMetrics(
        val contentBottom: Int,
        val rowGap: Int,
        val lastRowInset: Int,
        val contentLeft: Int,
        val contentWidth: Int
    )

    private data class NativeTileMetricsCache(
        val signature: String,
        val metrics: NativeTileMetrics
    )

    private data class EditorGridSnapshot(
        val bitmap: android.graphics.Bitmap,
        val target: ViewGroup
    )

    private data class ActiveGridOverlay(
        val bitmap: android.graphics.Bitmap,
        val target: ViewGroup,
        val overlay: android.widget.ImageView,
        val detachListener: View.OnAttachStateChangeListener
    )

    private data class EditorDragBinding(
        val icon: View,
        val tileView: View,
        val runnable: Runnable,
        val state: DragTouchState
    )

    private data class ClipBaseline(
        val clipChildren: Boolean,
        val clipToPadding: Boolean
    )

    private data class QsScrollState(
        var offset: Float = 0f,
        var downY: Float = 0f,
        var startOffset: Float = 0f,
        var contentBottom: Int = 0,
        var viewportBottom: Int = 0,
        var header: View? = null,
        var headerBaseTranslationY: Float = 0f,
        var tileBaseTranslationY: Float = 0f,
        var mediaBaseTranslationY: Float = 0f,
        var brightnessBaseTranslationY: Float = 0f,
        var nativeLayoutCaptured: Boolean = false,
        var nativeTileTranslationY: Float = 0f,
        var nativeMediaTranslationX: Float = 0f,
        var nativeMediaTranslationY: Float = 0f,
        var nativeMediaClipBounds: Rect? = null,
        var nativeMediaWidth: Int? = null,
        var nativeBrightnessTranslationY: Float = 0f,
        var nativeBrightnessTranslationX: Float = 0f,
        var nativeBrightnessClipBounds: Rect? = null,
        var nativeBrightnessHeight: Int? = null,
        var brightnessVisualLeft: Int = 0,
        var brightnessVisualTop: Int = 0,
        var headerPreDrawObserver: ViewTreeObserver? = null,
        var headerPreDrawListener: ViewTreeObserver.OnPreDrawListener? = null,
        var mediaFollowObserver: ViewTreeObserver? = null,
        var mediaFollowListener: ViewTreeObserver.OnPreDrawListener? = null,
        var mediaLayoutView: View? = null,
        var mediaLayoutListener: View.OnLayoutChangeListener? = null,
        val clipBaselines: MutableMap<ViewGroup, ClipBaseline> = mutableMapOf(),
        var dragging: Boolean = false,
        var gestureStartedAtContentBottom: Boolean = false,
        var collapseRequested: Boolean = false,
        var collapseDispatched: Boolean = false,
        var userScrolled: Boolean = false,
        var capturedRotation: Int = -1,
        var islandLiftY: Float = 0f,
        var lastExpansionFraction: Float = 0f
    )

    private val editorEntries: MutableMap<View, EditorEntry> =
        Collections.synchronizedMap(WeakHashMap())
    private val customizerControllers: MutableMap<Activity, Any> =
        Collections.synchronizedMap(WeakHashMap())
    private val pendingCustomizerLaunches: MutableMap<Any, PendingCustomizerLaunch> =
        Collections.synchronizedMap(WeakHashMap())
    private val qsScrollStates: MutableMap<ViewGroup, QsScrollState> =
        Collections.synchronizedMap(WeakHashMap())
    private val translatedBrightnessGestures: MutableMap<ViewGroup, Boolean> =
        Collections.synchronizedMap(WeakHashMap())
    private val pendingBrightnessMoves: MutableMap<ViewGroup, MotionEvent> =
        Collections.synchronizedMap(WeakHashMap())
    private val scheduledBrightnessMoves: MutableSet<ViewGroup> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val pendingAddedTileSpecs: MutableMap<Activity, String> =
        Collections.synchronizedMap(WeakHashMap())
    private val editorGridSnapshots: MutableMap<Any, EditorGridSnapshot> =
        Collections.synchronizedMap(WeakHashMap())
    private val activeGridOverlays: MutableMap<Any, ActiveGridOverlay> =
        Collections.synchronizedMap(WeakHashMap())
    private val editorDragBindings: MutableMap<Any, List<EditorDragBinding>> =
        Collections.synchronizedMap(WeakHashMap())
    private val candidateTileCache: MutableMap<Activity, List<CandidateTile>> =
        Collections.synchronizedMap(WeakHashMap())
    private val configuredTileLayouts: MutableSet<View> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val configuredTilePagers: MutableSet<View> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val migratedLegacyTileHosts: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )

    fun install(classLoader: ClassLoader) {
        runCatching {
            installQuickSettingsCore(classLoader)
        }.onFailure { unavailable("quick settings install failed: ${it.message}") }
    }

    private fun installQuickSettingsCore(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(CONTROLLER_CLASS, classLoader)
            ?: return unavailable("controller missing")

        runCatching {
            XposedBridge.hookAllMethods(
                controllerClass,
                "updatePanelExpansion",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        clearQuickPanelWindowTimeout(param.thisObject)
                        migrateLegacyTileSpecs(param.thisObject)
                        attachEditor(param.thisObject)
                        completeCustomizerLaunchIfCollapsed(param.thisObject)
                        val panel = field(param.thisObject, "mQSPanel") as? ViewGroup
                        if (
                            panel != null &&
                            CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(panel.context))
                        ) {
                            enforceMediaAboveBrightness(panel)
                        }
                    }
                }
            )
            CoverRuntime.log(SCOPE, "native display-1 QS customizer entry installed")
        }.onFailure { unavailable(it.message) }

        installNativeMediaPositionOverride(classLoader)
        installTranslatedBrightnessTouchForwarding(classLoader)
        installConfiguredCoverTileGrid(classLoader)
        installRotatedCoverQsNativePull(classLoader)
        installCustomizerEnhancements(classLoader)
    }

    private fun clearQuickPanelWindowTimeout(controller: Any) {
        val params = field(controller, "mLp") as? WindowManager.LayoutParams ?: return
        val timeoutField = runCatching {
            WindowManager.LayoutParams::class.java.getField("userActivityTimeout")
        }.getOrNull() ?: return
        if (runCatching { timeoutField.getLong(params) }.getOrNull() == -1L) return
        runCatching { timeoutField.setLong(params, -1L) }
            .onFailure {
                unavailable("quick-panel timeout field update failed: ${it.message}")
                return
            }
        val windowManager = field(controller, "mWindowManager") as? WindowManager
        val root = field(controller, "mSubScreenQsWindowView") as? View
        if (windowManager != null && root?.isAttachedToWindow == true) {
            runCatching { windowManager.updateViewLayout(root, params) }
                .onFailure { unavailable("quick-panel timeout update failed: ${it.message}") }
        }
        CoverRuntime.log(
            SCOPE,
            "display-1 QuickPanel native 10-second timeout cleared; system timeout restored"
        )
    }

    private fun installGridConfigurationReceiver(context: Context) {
        if (gridReceiverRegistered) return
        val receiverContext = context.applicationContext ?: context
        runCatching {
            receiverContext.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action != CoverQsGridConfig.ACTION_CHANGED) return
                        val grid = CoverQsGridConfig.read(context)
                        synchronized(configuredTilePagers) {
                            configuredTilePagers.toList()
                        }.forEach { pager ->
                            XposedHelpers.setBooleanField(pager, "mDistributeTiles", true)
                            pager.requestLayout()
                        }
                        synchronized(configuredTileLayouts) {
                            configuredTileLayouts.toList()
                        }.forEach(View::requestLayout)
                        CoverRuntime.log(
                            SCOPE,
                            "display-1 QS grid refresh requested grid=${grid.label} " +
                                "capacity=${grid.capacity}"
                        )
                    }
                },
                IntentFilter(CoverQsGridConfig.ACTION_CHANGED),
                Context.RECEIVER_EXPORTED
            )
            gridReceiverRegistered = true
            CoverRuntime.log(SCOPE, "display-1 QS grid configuration receiver installed")
        }.onFailure { unavailable("QS grid receiver failed: ${it.message}") }
    }

    private fun installNativeMediaPositionOverride(classLoader: ClassLoader) {
        val parentClass = XposedHelpers.findClassIfExists(SUBSCREEN_PARENT_LAYOUT, classLoader)
            ?: return unavailable("SubscreenParentLayout missing")
        runCatching {
            XposedBridge.hookAllMethods(
                parentClass,
                "onConfigurationChanged",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? ViewGroup)?.post {
                            enforceMediaAboveBrightness(param.thisObject as ViewGroup)
                        }
                    }
                }
            )
            XposedHelpers.findClassIfExists(SUBSCREEN_HOST_CALLBACK, classLoader)?.let { callbackClass ->
                XposedBridge.hookAllMethods(
                    callbackClass,
                    "onTilesChanged",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val panel = field(param.thisObject, "this\$0") as? ViewGroup ?: return
                            panel.post { enforceMediaAboveBrightness(panel) }
                        }
                    }
                )
            }
            CoverRuntime.log(SCOPE, "native media grid positioning override installed")
        }.onFailure { unavailable("native media position override failed: ${it.message}") }
    }

    private fun installTranslatedBrightnessTouchForwarding(classLoader: ClassLoader) {
        if (XposedHelpers.findClassIfExists(SUBSCREEN_PARENT_LAYOUT, classLoader) == null) {
            return unavailable("SubscreenParentLayout missing for brightness touch forwarding")
        }
        val windowClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.subscreen.SubScreenQuickPanelWindowView",
            classLoader
        ) ?: return unavailable("SubScreenQuickPanelWindowView missing for brightness touch forwarding")
        runCatching {
            XposedHelpers.findAndHookMethod(
                windowClass,
                "onInterceptTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook(PRIORITY_HIGHEST) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val root = param.thisObject as? ViewGroup ?: return
                        val event = param.args.firstOrNull() as? MotionEvent ?: return
                        val panel = brightnessPanelForRoot(root) ?: return
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            translatedBrightnessGestures[panel] =
                                isTranslatedBrightnessHit(panel, event)
                        }
                        if (translatedBrightnessGestures[panel] == true) {
                            param.result = true
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                windowClass,
                "onTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook(PRIORITY_HIGHEST) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val root = param.thisObject as? ViewGroup ?: return
                        val event = param.args.firstOrNull() as? MotionEvent ?: return
                        val panel = brightnessPanelForRoot(root) ?: return
                        if (translatedBrightnessGestures[panel] != true) return
                        forwardTranslatedBrightnessEvent(panel, event)
                        param.result = true
                    }
                }
            )
            CoverRuntime.log(SCOPE, "translated brightness touch forwarding installed")
        }.onFailure { unavailable("brightness touch forwarding hook failed: ${it.message}") }
    }

    private fun brightnessPanelForRoot(root: ViewGroup): ViewGroup? {
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(root.context))) return null
        return synchronized(editorEntries) {
            editorEntries.entries.firstOrNull { (_, entry) ->
                entry.root === root && entry.panelVisible
            }?.key as? ViewGroup
        }
    }

    private fun isTranslatedBrightnessHit(panel: ViewGroup, event: MotionEvent): Boolean {
        // Edit button takes priority: its visual area overlaps the translated
        // brightness slider at the bottom, so taps on the button must NOT be
        // forwarded to the slider (otherwise the editor never opens).
        val editButton = editorEntries[panel]?.button
        if (editButton != null && editButton.isAttachedToWindow && editButton.visibility == View.VISIBLE) {
            val buttonLoc = IntArray(2)
            runCatching { editButton.getLocationOnScreen(buttonLoc) }
            if (
                event.rawY >= buttonLoc[1] && event.rawY <= buttonLoc[1] + editButton.height &&
                event.rawX >= buttonLoc[0] && event.rawX <= buttonLoc[0] + editButton.width
            ) {
                return false
            }
        }
        val brightness = panel.findDescendantByName("subroom_brightness_settings") ?: return false
        val state = qsScrollStates[panel] ?: return false
        val stableWidth = brightness.measuredWidth.takeIf { it > 0 }
            ?: brightness.width.takeIf { it > 0 }
            ?: return false
        val stableHeight = state.nativeBrightnessHeight?.takeIf { it > 0 }
            ?: brightness.coverDrawnHeight().takeIf { it > 0 }
            ?: return false
        val local = brightnessLocalPoint(brightness, event)
        return local[0] >= 0f && local[0] < stableWidth &&
            local[1] >= 0f && local[1] < stableHeight
    }

    fun isQsBrightnessGesture(panel: View, event: MotionEvent): Boolean {
        val group = panel as? ViewGroup ?: return false
        return translatedBrightnessGestures[group] == true ||
            isTranslatedBrightnessHit(group, event)
    }

    private fun forwardTranslatedBrightnessEvent(panel: ViewGroup, event: MotionEvent) {
        val brightness = panel.findDescendantByName("subroom_brightness_settings") ?: return
        val local = brightnessLocalPoint(brightness, event)
        val localX = local[0]
        val localY = local[1]
        val translated = MotionEvent.obtain(event)
        translated.setLocation(localX, localY)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_MOVE) {
            pendingBrightnessMoves.put(panel, translated)?.recycle()
            if (scheduledBrightnessMoves.add(panel)) {
                brightness.postOnAnimation {
                    scheduledBrightnessMoves.remove(panel)
                    pendingBrightnessMoves.remove(panel)?.let { move ->
                        brightness.dispatchTouchEvent(move)
                        move.recycle()
                    }
                }
            }
            return
        }
        pendingBrightnessMoves.remove(panel)?.let { move ->
            brightness.dispatchTouchEvent(move)
            move.recycle()
        }
        brightness.dispatchTouchEvent(translated)
        translated.recycle()

        if (action == MotionEvent.ACTION_DOWN) {
            CoverRuntime.log(
                SCOPE,
                "brightness touch forwarding started local=${localX.toInt()},${localY.toInt()}"
            )
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            translatedBrightnessGestures.remove(panel)
            CoverRuntime.log(SCOPE, "brightness touch forwarding finished action=$action")
        }
    }

    private fun coverQsExpandedFraction(): Float = runCatching {
        val controller = qsControllerInstance ?: return@runCatching 0f
        (XposedHelpers.getObjectField(controller, "mExpandedFraction") as? Number)?.toFloat()
            ?: 0f
    }.getOrDefault(0f)

    private fun maybeRestoreCollapsedQsLayout(panel: ViewGroup) {
        if (coverQsExpandedFraction() > 0.001f) return
        qsScrollStates[panel]?.let { state ->
            if (state.nativeLayoutCaptured || state.offset != 0f || state.userScrolled) {
                restoreNativeQsLayout(panel, state)
            }
        }
    }

    private fun installRotatedCoverQsNativePull(classLoader: ClassLoader) {
        val handlerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.subscreen.SubScreenQSEventHandler",
            classLoader
        ) ?: return unavailable("rotated cover QS pull: SubScreenQSEventHandler missing")
        runCatching {
            XposedBridge.hookAllMethods(
                handlerClass,
                "handleTouchEvent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val handler = param.thisObject ?: return
                        val context = qsHandlerContext(handler) ?: return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return
                        val rotation = coverQsRotation(context)
                        if (rotation == 0) return
                        val interactive = context.getSystemService(android.os.PowerManager::class.java)
                            ?.isInteractive != false
                        val displayState = context.display?.state ?: 0
                        val dozing = displayState == android.view.Display.STATE_DOZE ||
                            displayState == android.view.Display.STATE_DOZE_SUSPEND
                        if (!interactive || dozing) return
                        XposedHelpers.setBooleanField(handler, "mIsRotation0", true)
                        param.setObjectExtra("flexunlockQsForceRot0", true)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.getObjectExtra("flexunlockQsForceRot0") != true) return
                        val handler = param.thisObject ?: return
                        val context = qsHandlerContext(handler) ?: return
                        val rotation = coverQsRotation(context)
                        runCatching {
                            XposedHelpers.setBooleanField(handler, "mIsRotation0", rotation == 0)
                        }
                        runCatching {
                            XposedHelpers.setBooleanField(handler, "mIsRotation180", rotation == 2)
                        }
                    }
                }
            )
            CoverRuntime.log(SCOPE, "rotated cover native QS pull touch installed")
        }.onFailure { unavailable("rotated cover QS pull: ${it.message}") }
        runCatching {
            XposedBridge.hookAllMethods(
                handlerClass,
                "handleDownEvent",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val handler = param.thisObject ?: return
                        val context = qsHandlerContext(handler) ?: return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return
                        val rotation = coverQsRotation(context)
                        if (rotation == 0) return
                        val interactive = context.getSystemService(android.os.PowerManager::class.java)
                            ?.isInteractive != false
                        val displayState = context.display?.state ?: 0
                        val dozing = displayState == android.view.Display.STATE_DOZE ||
                            displayState == android.view.Display.STATE_DOZE_SUSPEND
                        if (!interactive || dozing) return
                        val y = when {
                            (param.args?.size ?: 0) >= 2 ->
                                (param.args?.getOrNull(1) as? Number)?.toFloat()
                            else -> (param.args?.getOrNull(0) as? Number)?.toFloat()
                        } ?: return
                        val height = context.resources.displayMetrics.heightPixels
                        if (coverQsEdgePullFromTop(rotation, y, 220f, height)) {
                            runCatching {
                                XposedHelpers.setBooleanField(handler, "mIsInDraggingArea", true)
                            }
                        }
                    }
                }
            )
        }.onFailure { unavailable("rotated cover QS down: ${it.message}") }
    }

    private fun qsHandlerContext(handler: Any): android.content.Context? = runCatching {
        val supplier = runCatching {
            XposedHelpers.getObjectField(handler, "mContextSupplier")
        }.getOrNull()
        if (supplier != null) {
            XposedHelpers.callMethod(supplier, "get") as? android.content.Context
        } else {
            XposedHelpers.getObjectField(handler, "mContext") as? android.content.Context
        }
    }.getOrNull()

    private fun coverQsRotation(context: Context): Int {
        val snapshot = CoverRuntime.coverDisplaySnapshot()
        val displayId = CoverRuntime.displayIdOf(context)
        return if (snapshot != null && snapshot.id == displayId) {
            snapshot.rotation
        } else {
            context.display?.rotation ?: 0
        }
    }

    private fun installConfiguredCoverTileGrid(classLoader: ClassLoader) {
        runCatching {
            val uiModeInteractor = XposedHelpers.findClassIfExists(
                "com.android.systemui.util.SecQsUiDisplayModeInteractor",
                classLoader
            )
            if (uiModeInteractor != null) {
                XposedBridge.hookAllMethods(
                    uiModeInteractor,
                    "updateUiDisplayMode",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            runCatching {
                                val result = param.result ?: return
                                if (result.toString() != "COVER") return
                                val narrow = result.javaClass.enumConstants
                                    ?.firstOrNull { it.toString() == "NARROW" } ?: return
                                param.result = narrow
                                CoverRuntime.log(
                                    SCOPE,
                                    "display-1 QS uiDisplayMode COVER mapped to NARROW"
                                )
                            }
                        }
                    }
                )
                CoverRuntime.log(
                    SCOPE,
                    "display-1 QS uiDisplayMode COVER->NARROW mapper installed"
                )
            }
        }.onFailure { error ->
            CoverRuntime.log(SCOPE, "uiDisplayMode mapper failed: ${error.message}")
        }
        runCatching {
            val controllerClass = XposedHelpers.findClassIfExists(
                CONTROLLER_CLASS,
                classLoader
            )
            if (controllerClass != null) {
                XposedBridge.hookAllConstructors(
                    controllerClass,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            qsControllerInstance = param.thisObject
                        }
                    }
                )
                CoverRuntime.log(
                    SCOPE,
                    "display-1 QS controller lifecycle captured"
                )
            }
        }.onFailure { error ->
            CoverRuntime.log(SCOPE, "top-down QS gesture bridge failed: ${error.message}")
        }
        runCatching {
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val registerConfigurationReceivers = Runnable {
                runCatching {
                    val activityThreadClass = Class.forName(
                        "android.app.ActivityThread",
                        false,
                        classLoader
                    )
                    val app = XposedHelpers.callStaticMethod(
                        activityThreadClass,
                        "currentApplication"
                    ) as? android.content.Context
                    if (app != null) {
                        val appContext = app.applicationContext ?: app
                        installOneUiCompatReceiver(appContext)
                        installCoverHomeStatusBarConfigReceiver(appContext) {
                            NativeCoverStatusBarHooks.syncPresentation()
                        }
                        installCoverIconSizeConfigReceiver(appContext) { _, _ -> Unit }
                    }
                }
            }
            handler.post(registerConfigurationReceivers)
            handler.postDelayed(registerConfigurationReceivers, 2000L)
            handler.postDelayed(registerConfigurationReceivers, 8000L)
        }.onFailure { }
        val tileLayoutClass = XposedHelpers.findClassIfExists(
            SUBSCREEN_TILE_LAYOUT,
            classLoader
        ) ?: return unavailable("SubscreenTileLayout missing")
        val pagedLayoutClass = XposedHelpers.findClassIfExists(
            SUBSCREEN_PAGED_TILE_LAYOUT,
            classLoader
        ) ?: return unavailable("SubscreenPagedTileLayout missing")
        runCatching {
            XposedBridge.hookAllMethods(
                tileLayoutClass,
                "updateResources",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val layout = param.thisObject as? View ?: return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(layout.context))) return
                        configuredTileLayouts.add(layout)
                        installGridConfigurationReceiver(layout.context)
                        applyGridGeometry(layout)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                tileLayoutClass,
                "onMeasure",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val layout = param.thisObject as? View ?: return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(layout.context))) return
                        configuredTileLayouts.add(layout)
                        // 锁屏与解锁完全同一方案:不缩小磁贴,4 行 + media +
                        // 亮度,超出屏幕部分通过 QS 滚动访问(enforce 统一处理)。
                        applyGridGeometry(
                            layout,
                            View.MeasureSpec.getSize(param.args[0] as Int)
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val layout = param.thisObject as? View ?: return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(layout.context))) return
                        val grid = CoverQsGridConfig.read(layout.context)
                        val rotation = coverQsRotation(layout)
                        val mainLandscape = editorGridHeight(layout) == null &&
                            (rotation == 1 || rotation == 3)
                        val availableWidth = if (mainLandscape) {
                            mainQsVisibleWidth(layout, layout.measuredWidth)
                        } else {
                            (layout.measuredWidth - layout.paddingStart - layout.paddingEnd)
                                .coerceAtLeast(1)
                        }
                        val cellWidth = XposedHelpers.getIntField(param.thisObject, "mCellWidth")
                        val gap = if (grid.columns > 1) {
                            ((availableWidth - cellWidth * grid.columns) / (grid.columns - 1))
                                .coerceAtLeast(0)
                        } else {
                            0
                        }
                        XposedHelpers.setIntField(param.thisObject, "mCellMarginHorizontal", gap)

                        val editorHeight = editorGridHeight(layout)
                        if (editorHeight == null) {
                            val compactInset = dp(layout, 4)
                            val effectiveGap = coverQsEffectiveGapPx(
                                rotation,
                                gap,
                                compactInset
                            )
                            XposedHelpers.setIntField(
                                param.thisObject,
                                "mCellMarginHorizontal",
                                effectiveGap
                            )
                            if (mainLandscape) {
                                val compactVerticalGap = (
                                    XposedHelpers.getIntField(
                                        param.thisObject,
                                        "mTileVerticalMargin"
                                    ) - compactInset
                                    ).coerceAtLeast(0)
                                XposedHelpers.setIntField(
                                    param.thisObject,
                                    "mTileVerticalMargin",
                                    compactVerticalGap
                                )
                            }
                            applyMainQsTileVisualBounds(layout, cellWidth)
                            if (mainLandscape) {
                                val snapshot =
                                    "$rotation:$availableWidth:${grid.columns}:$cellWidth:$effectiveGap"
                                if (XposedHelpers.getAdditionalInstanceField(
                                        layout,
                                        "flexunlockMainGridSnapshot"
                                    ) != snapshot
                                ) {
                                    XposedHelpers.setAdditionalInstanceField(
                                        layout,
                                        "flexunlockMainGridSnapshot",
                                        snapshot
                                    )
                                    CoverRuntime.log(
                                        SCOPE,
                                            "landscape QS grid fitted rotation=$rotation " +
                                                "visibleWidth=$availableWidth columns=${grid.columns} " +
                                            "cellWidth=$cellWidth gap=$effectiveGap"
                                    )
                                }
                            }
                            return
                        }
                        // 编辑页:显式固定水平 gap(缩小间距使 grid 宽度能容纳于
                        // pager 可视区,避免磁贴放大后最右列被裁剪)
                        val editorGap = dp(layout, if (grid.columns >= 5) 2 else 4)
                        XposedHelpers.setIntField(
                            param.thisObject,
                            "mCellMarginHorizontal",
                            editorGap
                        )
                        val verticalGap = dp(layout, 4)
                        val cellHeight = (
                            editorHeight - verticalGap * (CONFIGURED_TILE_ROWS - 1)
                            ) / CONFIGURED_TILE_ROWS
                        if (cellHeight <= 0) return
                        XposedHelpers.setIntField(param.thisObject, "mCellHeight", cellHeight)
                        XposedHelpers.setIntField(param.thisObject, "mTileVerticalMargin", verticalGap)

                        // 网格居中由 SubscreenTileLayout.onLayout 的 after hook 负责
                        // (layout 阶段 left 已定,translationX 校正才准确)

                        // 磁贴内部图标缩小(三星每帧强制恢复 52dp≈104px,必须每次覆盖)
                        // 并取消黄色矩形点击背景(mIcon.background = ripple,三星每帧 setColor 黄色)
                        val iconSize = (cellWidth * EDITOR_TILE_ICON_RATIO).toInt()
                            .coerceAtLeast(44)
                        val records = field(param.thisObject, "mRecords") as? Iterable<*>
                        records?.forEach { record ->
                            val tileView = record?.let { item ->
                                field(item, "tileView") as? View
                            } ?: return@forEach
                            runCatching {
                                val icon = XposedHelpers.getObjectField(
                                    tileView,
                                    "mIcon"
                                ) as? View
                                val bg = XposedHelpers.getObjectField(
                                    tileView,
                                    "mBg"
                                ) as? View
                                val frame = XposedHelpers.getObjectField(
                                    tileView,
                                    "mIconFrame"
                                ) as? View
                                icon?.layoutParams = FrameLayout.LayoutParams(
                                    iconSize,
                                    iconSize,
                                    Gravity.CENTER
                                )
                                bg?.layoutParams = FrameLayout.LayoutParams(
                                    iconSize,
                                    iconSize,
                                    Gravity.CENTER
                                )
                                // frame 占满 cell,内部图标居中(frame 内为 CENTER)
                                frame?.layoutParams = LinearLayout.LayoutParams(
                                    cellWidth,
                                    cellHeight
                                )
                                // 部分磁贴(如 v2rayNG)图标 intrinsic 尺寸偏大,
                                // 强制 FIT_CENTER 缩放到 iconSize 内
                                if (icon is android.widget.ImageView &&
                                    icon.scaleType !=
                                    android.widget.ImageView.ScaleType.FIT_CENTER
                                ) {
                                    icon.scaleType =
                                        android.widget.ImageView.ScaleType.FIT_CENTER
                                }
                                // mIcon 是 QSIconViewImpl(ViewGroup),内部还有一个
                                // ImageView(字段名 mIcon)负责实际绘制 drawable;
                                // 三星 updateIcon 会给它 104px 的图尺寸,外层容器缩小后
                                // 内层仍按 104px 绘制 → 图标溢出(如 v2rayNG)。
                                // 必须同步缩小内层 ImageView。
                                runCatching {
                                    val innerIcon = XposedHelpers.getObjectField(
                                        icon ?: return@runCatching,
                                        "mIcon"
                                    ) as? android.widget.ImageView
                                    innerIcon?.layoutParams = FrameLayout.LayoutParams(
                                        iconSize,
                                        iconSize,
                                        Gravity.CENTER
                                    )
                                    innerIcon?.scaleType =
                                        android.widget.ImageView.ScaleType.FIT_CENTER
                                }
                                icon?.setBackgroundColor(Color.TRANSPARENT)
                                XposedHelpers.setAdditionalInstanceField(
                                    tileView,
                                    EDITOR_TILE_MARK,
                                    true
                                )
                            }
                            tileView.measure(
                                View.MeasureSpec.makeMeasureSpec(
                                    cellWidth,
                                    View.MeasureSpec.EXACTLY
                                ),
                                View.MeasureSpec.makeMeasureSpec(
                                    cellHeight,
                                    View.MeasureSpec.EXACTLY
                                )
                            )
                        }
                        runCatching {
                            XposedHelpers.callMethod(
                                layout,
                                "setMeasuredDimension",
                                layout.measuredWidth,
                                editorHeight
                            )
                        }
                        val editorMeasureSnapshot =
                            "$editorHeight:$cellWidth:$cellHeight:$editorGap:$iconSize"
                        if (XposedHelpers.getAdditionalInstanceField(
                                layout,
                                "flexunlockEditorMeasureSnapshot"
                            ) != editorMeasureSnapshot
                        ) {
                            XposedHelpers.setAdditionalInstanceField(
                                layout,
                                "flexunlockEditorMeasureSnapshot",
                                editorMeasureSnapshot
                            )
                            CoverRuntime.log(
                                SCOPE,
                                "customizer tile page fitted height=$editorHeight " +
                                    "cell=${cellWidth}x$cellHeight gap=$editorGap " +
                                    "icon=$iconSize"
                            )
                        }
                    }
                }
            )
            // 编辑页网格居中(双平移):
            // 1. 编辑页 pager 在屏幕中自带 19px 左偏移且宽仅 585px(右侧留白 144px),
            //    先把 pager 整体屏幕居中,使磁贴网格有对称空间;
            // 2. 三星 layoutTileRecords 定位忽略 padding(用 (gap+cell)*col),
            //    再把 tile layout 在 pager 内居中(translationX)。
            // 磁贴尺寸在 onMeasure 中已缩小,grid 宽 < pager 宽 → 无裁剪。
            XposedBridge.hookAllMethods(
                tileLayoutClass,
                "onLayout",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val layout = param.thisObject as? View ?: return
                        if (!CoverRuntime.isCoverDisplay(
                                CoverRuntime.displayIdOf(layout.context)
                            )
                        ) return
                        if (editorGridHeight(layout) != null) {
                            applyEditorPageRows(layout)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val layout = param.thisObject as? View ?: return
                        if (!CoverRuntime.isCoverDisplay(
                                CoverRuntime.displayIdOf(layout.context)
                            )
                        ) return
                        val editorHeight = editorGridHeight(layout)
                        if (editorHeight == null) {
                            if (isPrimaryMainPagerPage(layout)) {
                                disableAncestorClipping(layout)
                                applyMainQsTileEdgeFrame(layout)
                                applyMainQsTileVisualBounds(
                                    layout,
                                    XposedHelpers.getIntField(layout, "mCellWidth")
                                )
                            }
                            return
                        }
                        if (editorHeight <= 0) return
                        val grid = CoverQsGridConfig.read(layout.context)
                        val cellWidth = XposedHelpers.getIntField(layout, "mCellWidth")
                        val editorGap = XposedHelpers.getIntField(
                            layout,
                            "mCellMarginHorizontal"
                        )
                        val gridWidth = cellWidth * grid.columns +
                            editorGap * (grid.columns - 1)
                        val screenWidth = layout.resources.displayMetrics.widthPixels
                        // pager(SubscreenPagedTileLayout)整体屏幕居中
                        val pager = layout.parent as? View
                        if (pager != null) {
                            val pagerLeft = pager.left
                            val desiredPagerTx = (
                                (screenWidth - pager.measuredWidth) / 2 - pagerLeft
                                ).toFloat()
                            if (pager.translationX != desiredPagerTx) {
                                pager.translationX = desiredPagerTx
                            }
                        }
                        disableAncestorClipping(layout)
                        val containerWidth = pager?.measuredWidth
                            ?: layout.measuredWidth
                        val targetInside = ((containerWidth - gridWidth) / 2)
                            .coerceAtLeast(8)
                        val firstTile = (field(layout, "mRecords") as? Iterable<*>)
                            ?.firstOrNull()?.let { item ->
                                field(item, "tileView") as? View
                            }
                        val tileL = firstTile?.left ?: 0
                        // 用实测屏幕坐标增量修正:getLocationOnScreen 已含 pager
                        // 偏移与 translationX,直接对齐到屏幕目标位置,多次调用收敛
                        val loc = IntArray(2)
                        runCatching { layout.getLocationOnScreen(loc) }
                        val currentCol0 = loc[0] + tileL
                        val targetScreenLeft = ((screenWidth - gridWidth) / 2)
                            .coerceAtLeast(8)
                        val shift = (targetScreenLeft - currentCol0).toFloat()
                        if (shift != 0f) {
                            layout.translationX = layout.translationX + shift
                        }
                    }
                }
            )
            // 取消编辑页磁贴的黄色矩形点击背景(三星 handleStateChanged 每次刷新重建
            // selectableItemBackgroundBorderless 矩形 ripple,需在刷新后覆盖为透明;
            // 仅作用于被标记为编辑页磁贴的 view,主面板不受影响)
            runCatching {
                val tileCommonClass = XposedHelpers.findClassIfExists(
                    "com.android.systemui.qs.SecQSCommonTileView",
                    classLoader
                )
                if (tileCommonClass != null) {
                    XposedBridge.hookAllMethods(
                        tileCommonClass,
                        "handleStateChanged",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    val view = param.thisObject as? View ?: return
                                    val ctx = view.context ?: return
                                    val display = ctx.display ?: return
                                    if (display.rotation == 0) return
                                    // Cover display is rotated; the QS panel is
                                    // portrait-only, so force the desktop portrait.
                                    if (qsForcePortrait) return
                                    ctx.sendBroadcast(
                                        android.content.Intent(
                                            ACTION_QS_FORCE_PORTRAIT
                                        )
                                    )
                                    CoverRuntime.log(
                                        SCOPE,
                                        "display-1 QS open on rotated display, forcing portrait"
                                    )
                                }
                            }
                        }
                    )
                }
                val subscreenUtilClass = XposedHelpers.findClassIfExists(
                    "com.android.systemui.qp.util.SubscreenUtil",
                    classLoader
                )
                if (subscreenUtilClass != null) {
                    XposedBridge.hookAllMethods(
                        subscreenUtilClass,
                        "closeSubscreenPanel",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    if (!qsForcePortrait) return
                                    qsForcePortrait = false
                                    val ctx = param.thisObject as? android.content.Context
                                        ?: return
                                    ctx.sendBroadcast(
                                        android.content.Intent(
                                            ACTION_QS_RESTORE_ORIENTATION
                                        )
                                    )
                                    CoverRuntime.log(
                                        SCOPE,
                                        "display-1 QS closed, restoring orientation"
                                    )
                                }
                            }
                        }
                    )
                }
            }.onFailure { error ->
                CoverRuntime.log(SCOPE, "QS portrait bridge failed: ${error.message}")
            }
            val subRoomQsTileBaseViewClass = XposedHelpers.findClassIfExists(
                SUBROOM_QS_TILE_BASE_VIEW,
                classLoader
            )
            if (subRoomQsTileBaseViewClass != null) {
                XposedBridge.hookAllMethods(
                    subRoomQsTileBaseViewClass,
                    "handleStateChanged",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val tileView = param.thisObject as? View ?: return
                            if (XposedHelpers.getAdditionalInstanceField(
                                    tileView,
                                    EDITOR_TILE_MARK
                                ) != true
                            ) return
                            if (!CoverRuntime.isCoverDisplay(
                                    CoverRuntime.displayIdOf(tileView.context)
                                )
                            ) return
                            runCatching {
                                (XposedHelpers.getObjectField(
                                    tileView,
                                    "mIcon"
                                ) as? View)?.setBackgroundColor(Color.TRANSPARENT)
                            }
                        }
                    }
                )
            }
            runCatching {
                val customTileClass = XposedHelpers.findClassIfExists(
                    "com.android.systemui.qs.external.CustomTile",
                    classLoader
                )
                if (customTileClass != null) {
                    XposedBridge.hookAllMethods(
                        customTileClass,
                        "handleClick",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                runCatching {
                                    val mTile = XposedHelpers.getObjectField(
                                        param.thisObject,
                                        "mTile"
                                    )
                                    val state = XposedHelpers.getIntField(mTile, "state")
                                    if (state == 0) {
                                        XposedHelpers.setIntField(mTile, "state", 1)
                                        param.setObjectExtra(
                                            "flexunlock_restore_tile_state",
                                            true
                                        )
                                        CoverRuntime.log(
                                            SCOPE,
                                            "custom tile state=0 forced clickable"
                                        )
                                    }
                                }
                            }

                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (param.getObjectExtra(
                                        "flexunlock_restore_tile_state"
                                    ) == true
                                ) {
                                    runCatching {
                                        val mTile = XposedHelpers.getObjectField(
                                            param.thisObject,
                                            "mTile"
                                        )
                                        XposedHelpers.setIntField(mTile, "state", 0)
                                    }
                                }
                            }
                        }
                    )
                    CoverRuntime.log(
                        SCOPE,
                        "custom tile handleClick state bypass installed"
                    )
                }
            }.onFailure { error ->
                CoverRuntime.log(SCOPE, "custom tile handleClick hook failed: ${error.message}")
            }
            XposedBridge.hookAllMethods(
                pagedLayoutClass,
                "onMeasure",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val pager = param.thisObject as? View ?: return
                        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(pager.context))) {
                            return
                        }
                        // Editor pager shares this class with the main QS panel.
                        // Do not cap, redistribute, or bind tile.click() here —
                        // that re-enters Samsung setTiles and crashes SystemUI.
                        // Still pin columns/rows so leftover tiles are not left
                        // at (0,0) overlapping the first row (Samsung only
                        // recomputes mRows when height is UNSPECIFIED).
                        if (isCustomizerPager(pager)) {
                            applyCustomizerPagerMetrics(pager)
                            pinSamsungPagerMeasureCache(
                                pager,
                                param.args[0] as? Int ?: 0
                            )
                            return
                        }
                        configuredTilePagers.add(pager)
                        restoreCappedPagerTiles(param.thisObject)
                        val tiles = mutableObjectListField(param.thisObject, "mTiles") ?: return
                        val grid = CoverQsGridConfig.read(pager.context)
                        capPagerTiles(param.thisObject, tiles, grid.capacity)
                        val pages = field(param.thisObject, "mPages") as? Collection<*> ?: return
                        val effectiveRows = grid.rows
                        pages.forEach { page ->
                            page ?: return@forEach
                            XposedHelpers.setIntField(page, "mColumns", grid.columns)
                            XposedHelpers.setIntField(page, "mMaxAllowedRows", effectiveRows)
                            XposedHelpers.setIntField(page, "mRows", effectiveRows)
                        }
                        val measuredPageHeight = XposedHelpers.getIntField(
                            param.thisObject,
                            "mPageHeight"
                        )
                        val firstPage = pages.firstOrNull()
                        val measuredCellHeight = firstPage?.let {
                            (field(it, "mCellHeight") as? Number)?.toInt()
                        }?.takeIf { it > 0 }
                        val measuredVerticalGap = firstPage?.let {
                            (field(it, "mTileVerticalMargin") as? Number)?.toInt()
                        }?.takeIf { it >= 0 }
                        val fallbackRowExtent = (
                            measuredPageHeight.takeIf { it > 0 } ?: dp(pager, 205)
                            ) / NATIVE_TILE_ROWS
                        val rowExtent = measuredCellHeight?.let { cellHeight ->
                            cellHeight + (measuredVerticalGap ?: 0)
                        }?.takeIf { it > 0 } ?: fallbackRowExtent.coerceAtLeast(1)
                        val customizerGridHeight = (
                            XposedHelpers.getAdditionalInstanceField(
                                param.thisObject,
                                "flexunlockCustomizerGridHeight"
                            ) as? Int
                            )?.takeIf { it > 0 }
                        val targetHeight = customizerGridHeight ?: measuredCellHeight?.let { cellHeight ->
                            cellHeight * effectiveRows +
                                (measuredVerticalGap ?: 0) * (effectiveRows - 1)
                        }?.takeIf { it > 0 }
                            ?: rowExtent * effectiveRows
                        XposedHelpers.setIntField(param.thisObject, "mPageHeight", targetHeight)
                        val widthMeasureSpec = param.args[0] as? Int ?: 0
                        val pinMeasureCache = coverQsShouldPinPagerMeasureCache(
                            rotation = coverQsRotation(pager),
                            columns = grid.columns,
                            pageCount = pages.size,
                            pageHeightPx = targetHeight,
                            widthMeasureSpecSizePx = View.MeasureSpec.getSize(widthMeasureSpec)
                        )
                        if (pinMeasureCache) {
                            // On rotated five-column layouts Samsung otherwise
                            // resets page 0 to four columns and exposes a transient
                            // 16+2 split before the recovery hook can merge it.
                            enforceConfiguredPagerDistribution(param.thisObject)
                            pinSamsungPagerMeasureCache(pager, widthMeasureSpec)
                        } else {
                            XposedHelpers.setBooleanField(
                                param.thisObject,
                                "mDistributeTiles",
                                true
                            )
                        }
                        if (pager.layoutParams.height != targetHeight) {
                            pager.layoutParams = pager.layoutParams.apply { height = targetHeight }
                        }
                        val measureSnapshot =
                            "${grid.label}:${tiles.size}:$targetHeight:" +
                                "$measuredCellHeight:$measuredVerticalGap"
                        if (XposedHelpers.getAdditionalInstanceField(
                                pager,
                                "flexunlockPagerMeasureSnapshot"
                            ) != measureSnapshot
                        ) {
                            XposedHelpers.setAdditionalInstanceField(
                                pager,
                                "flexunlockPagerMeasureSnapshot",
                                measureSnapshot
                            )
                            CoverRuntime.log(
                                SCOPE,
                                "display-1 QS grid=${grid.label} visibleTiles=${tiles.size} " +
                                    "contentHeight=$targetHeight cell=$measuredCellHeight " +
                                    "verticalGap=$measuredVerticalGap"
                            )
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pager = param.thisObject as? View ?: return
                        if (isCustomizerPager(pager)) return
                        enforceConfiguredPagerDistribution(pager)
                        bindMainPanelTileClicks(pager)
                        restoreCappedPagerTiles(pager)
                    }
                }
            )
            XposedBridge.hookAllMethods(
                pagedLayoutClass,
                "addTile",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pager = param.thisObject as? View ?: return
                        if (!isCustomizerPager(pager)) return
                        placeEditorTileOnFirstPage(pager, param.args.getOrNull(0))
                    }
                }
            )
            XposedBridge.hookAllMethods(
                pagedLayoutClass,
                "removeTile",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pager = param.thisObject as? View ?: return
                        if (!isCustomizerPager(pager)) return
                        detachEditorTile(pager, param.args.getOrNull(0))
                    }
                }
            )
            // 编辑页网格只有一页内容:禁用水平滑动(避免误触发翻页动画,
            // 也避免与长按拖动排序的触摸手势冲突)。主面板 pager 不受影响。
            val editorPagerTouchBlocker = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pager = param.thisObject as? View ?: return
                    val editorPagerId = pager.resources.getIdentifier(
                        "subscreen_customize_qs_paged",
                        "id",
                        CoverRuntime.SYSTEM_UI_PACKAGE
                    )
                    if (editorPagerId != 0 && pager.id == editorPagerId) {
                        param.result = false
                    }
                }
            }
            XposedBridge.hookAllMethods(
                pagedLayoutClass,
                "onInterceptTouchEvent",
                editorPagerTouchBlocker
            )
            XposedBridge.hookAllMethods(
                pagedLayoutClass,
                "onTouchEvent",
                editorPagerTouchBlocker
            )
            CoverRuntime.log(SCOPE, "configured display-1 QS grid policy installed")
        }.onFailure { unavailable("configured tile grid policy failed: ${it.message}") }
    }

    /**
     * Samsung’s main-panel QS never delivers clicks for custom tiles
     * (v2rayNG, Dolby, ...): tapping them just collapses the panel with no
     * TileLifecycleManager.onClick. Bind our own click on the tile icon that
     * calls tile.click() directly so edited-in tiles actually work.
     */
    private fun bindMainPanelTileClicks(pager: View) {
        if (isCustomizerPager(pager)) return
        val pages = field(pager, "mPages") as? Collection<*> ?: return
        var bound = 0
        var customCount = 0
        var recordCount = 0
        pages.forEach { page ->
            page ?: return@forEach
            val records = field(page, "mRecords") as? Collection<*> ?: return@forEach
            records.forEach { record ->
                record ?: return@forEach
                recordCount++
                val tile = field(record, "tile") ?: return@forEach
                if (!tile.javaClass.name.contains("CustomTile")) return@forEach
                customCount++
                val tileView = field(record, "tileView") as? View ?: return@forEach
                val icon = runCatching {
                    XposedHelpers.getObjectField(tileView, "mIcon") as? View
                }.getOrNull()
                if (icon == null) {
                    CoverRuntime.log(
                        SCOPE,
                        "custom tile icon missing tileView=${tileView.javaClass.name} " +
                            "tile=${tile.javaClass.name}"
                    )
                    return@forEach
                }
                val clickTile: () -> Unit = {
                    runCatching {
                        XposedHelpers.callMethod(tile, "click")
                        CoverRuntime.log(
                            SCOPE,
                            "custom tile click forwarded spec=" +
                                runCatching {
                                    XposedHelpers.callMethod(tile, "getTileSpec")
                                }.getOrNull()
                        )
                    }.onFailure {
                        CoverRuntime.log(
                            SCOPE,
                            "custom tile click failed: $it"
                        )
                    }
                }
                // 每次 onMeasure 后强制重新绑定:三星 handleStateChanged 会重设
                // icon 的 OnClickListener,不能依赖一次性标记。
                tileView.setOnClickListener { clickTile() }
                bound++
                icon.setOnClickListener { clickTile() }
            }
        }
        val bindingSnapshot = "$recordCount:$customCount:$bound"
        if (XposedHelpers.getAdditionalInstanceField(
                pager,
                "flexunlockCustomTileBindingSnapshot"
            ) != bindingSnapshot
        ) {
            XposedHelpers.setAdditionalInstanceField(
                pager,
                "flexunlockCustomTileBindingSnapshot",
                bindingSnapshot
            )
            if (recordCount > 0) {
                CoverRuntime.log(
                    SCOPE,
                    "main-panel tile records=$recordCount custom=$customCount bound=$bound"
                )
            }
        }
    }

    private fun enforceConfiguredPagerDistribution(pager: Any) {
        val view = pager as? View ?: return
        val grid = CoverQsGridConfig.read(view.context)
        val tiles = mutableObjectListField(pager, "mTiles") ?: return
        val pages = mutableObjectListField(pager, "mPages") ?: return
        val firstPage = pages.firstOrNull() ?: return
        val records = field(firstPage, "mRecords") as? Collection<*>
        // 锁屏与解锁一致:按实际行数(grid.rows)填充磁贴,不再限制 3 行
        // (用户要求锁屏显示实际磁贴数量)。media/亮度条通过滚动访问。
        val rows = grid.rows
        val capacity = grid.columns * rows
        val alreadyConfigured = pages.size == 1 &&
            records?.size == minOf(tiles.size, capacity) &&
            XposedHelpers.getIntField(firstPage, "mColumns") == grid.columns &&
            XposedHelpers.getIntField(firstPage, "mRows") == rows
        if (alreadyConfigured) return

        pages.forEach { page ->
            page ?: return@forEach
            runCatching { XposedHelpers.callMethod(page, "removeAllViews") }
        }
        pages.drop(1).forEach { page ->
            val pageView = page as? View ?: return@forEach
            (pageView.parent as? ViewGroup)?.removeView(pageView)
        }
        while (pages.size > 1) pages.removeAt(pages.lastIndex)

        XposedHelpers.setIntField(firstPage, "mColumns", grid.columns)
        XposedHelpers.setIntField(firstPage, "mRows", rows)
        XposedHelpers.setIntField(firstPage, "mMaxAllowedRows", rows)
        tiles.take(capacity).forEach { record ->
            record ?: return@forEach
            XposedHelpers.callMethod(firstPage, "addTile", record)
        }
        XposedHelpers.setBooleanField(pager, "mDistributeTiles", false)
        field(pager, "mAdapter")?.let { adapter ->
            runCatching { XposedHelpers.callMethod(adapter, "notifyDataSetChanged") }
        }
        field(pager, "mPageIndicator")?.let { indicator ->
            runCatching { XposedHelpers.callMethod(indicator, "setNumPages", 1) }
        }
        (firstPage as? View)?.requestLayout()
        view.requestLayout()
        CoverRuntime.log(
            SCOPE,
            "display-1 QS pager redistributed as one ${grid.label} page records=${tiles.size}"
        )
    }

    private fun applyGridGeometry(layout: View, measuredWidth: Int = layout.width) {
        val grid = CoverQsGridConfig.read(layout.context)
        val nativeCellWidth = (
            XposedHelpers.getAdditionalInstanceField(layout, "flexunlockNativeCellWidth") as? Int
            )?.takeIf { it > 0 } ?: XposedHelpers.getIntField(layout, "mCellWidth")
            .takeIf { it > 0 }
            ?.also {
                XposedHelpers.setAdditionalInstanceField(
                    layout,
                    "flexunlockNativeCellWidth",
                    it
                )
            } ?: return
        applyQsTileEdgePadding(layout)
        val rotation = coverQsRotation(layout)
        val mainLandscape = editorGridHeight(layout) == null &&
            (rotation == 1 || rotation == 3)
        val availableWidth = if (mainLandscape) {
            mainQsVisibleWidth(layout, measuredWidth)
        } else {
            (measuredWidth - layout.paddingStart - layout.paddingEnd)
                .takeIf { it > 0 }
                ?: layout.resources.displayMetrics.widthPixels
        }
        val minimumGap = dp(layout, if (grid.columns == 5) 8 else 12)
        val maximumFittingCellWidth = (
            availableWidth - minimumGap * (grid.columns - 1)
            ) / grid.columns
        val editorShrink = if (editorGridHeight(layout) != null) {
            if (grid.columns >= 5) EDITOR_TILE_SHRINK_5COL else EDITOR_TILE_SHRINK_4COL
        } else {
            1f
        }
        val optimizedCellWidth = when {
            editorShrink < 1f -> {
                (nativeCellWidth * editorShrink).toInt().coerceAtLeast(56)
            }
            mainLandscape -> {
                coverQsGridGeometry(
                    visibleWidthPx = availableWidth,
                    columns = grid.columns,
                    nativeCellWidthPx = nativeCellWidth,
                    minimumGapPx = minimumGap
                ).cellWidthPx
            }
            else -> minOf(nativeCellWidth, maximumFittingCellWidth.coerceAtLeast(1))
        }
        XposedHelpers.setIntField(layout, "mColumns", grid.columns)
        XposedHelpers.setIntField(layout, "mRows", CONFIGURED_TILE_ROWS)
        XposedHelpers.setIntField(layout, "mMaxAllowedRows", CONFIGURED_TILE_ROWS)
        XposedHelpers.setIntField(layout, "mCellWidth", optimizedCellWidth)
        if (editorGridHeight(layout) == null) {
            val nativeCellHeight = (
                XposedHelpers.getAdditionalInstanceField(
                    layout,
                    "flexunlockNativeCellHeight"
                ) as? Int
                ) ?: XposedHelpers.getIntField(layout, "mCellHeight").also {
                XposedHelpers.setAdditionalInstanceField(
                    layout,
                    "flexunlockNativeCellHeight",
                    it
                )
            }
            val nativeVerticalGap = (
                XposedHelpers.getAdditionalInstanceField(
                    layout,
                    "flexunlockNativeVerticalGap"
                ) as? Int
                ) ?: XposedHelpers.getIntField(layout, "mTileVerticalMargin").also {
                XposedHelpers.setAdditionalInstanceField(
                    layout,
                    "flexunlockNativeVerticalGap",
                    it
                )
            }
            XposedHelpers.setIntField(
                layout,
                "mCellHeight",
                coverScaleDimensionForWidth(
                    nativeCellHeight,
                    nativeCellWidth,
                    optimizedCellWidth
                ).coerceAtLeast(1)
            )
            XposedHelpers.setIntField(
                layout,
                "mTileVerticalMargin",
                coverScaleDimensionForWidth(nativeVerticalGap, nativeCellWidth, optimizedCellWidth)
            )
        }
    }

    private fun coverQsRotation(view: View): Int {
        val snapshot = CoverRuntime.coverDisplaySnapshot()
        val displayId = CoverRuntime.displayIdOf(view.context)
        return if (snapshot != null && snapshot.id == displayId) {
            snapshot.rotation
        } else {
            view.display?.rotation ?: 0
        }
    }

    private fun mainQsVisibleWidth(layout: View, measuredWidth: Int): Int {
        val host = layout.rootView ?: layout
        val hostWidth = host.width.takeIf { it > 0 }
            ?: layout.resources.displayMetrics.widthPixels
        val rotation = coverQsRotation(layout)
        val inset = coverQsTileEdgeInset(rotation, hostWidth, qsCutoutRects(layout))
        val cutoutInsets = layout.rootWindowInsets?.getInsets(WindowInsets.Type.displayCutout())
        val leftSafe = maxOf(inset.leftPx, cutoutInsets?.left ?: 0)
        val rightSafe = maxOf(inset.rightPx, cutoutInsets?.right ?: 0)
        val baselineTranslation = (
            XposedHelpers.getAdditionalInstanceField(
                layout,
                "flexunlockTileBaseTranslationX"
            ) as? Float
            ) ?: layout.translationX.also { baseline ->
            XposedHelpers.setAdditionalInstanceField(
                layout,
                "flexunlockTileBaseTranslationX",
                baseline
            )
        }
        val location = IntArray(2)
        runCatching { layout.getLocationOnScreen(location) }
        val appliedCorrection = (layout.translationX - baselineTranslation).toInt()
        val uncorrectedLeft = location[0] - appliedCorrection
        val correctedLeft = maxOf(uncorrectedLeft, leftSafe)
        val visibleToRightEdge = hostWidth - rightSafe - correctedLeft
        return minOf(measuredWidth, visibleToRightEdge).coerceAtLeast(1)
    }

    private fun applyMainQsTileVisualBounds(layout: View, cellWidth: Int) {
        if (editorGridHeight(layout) != null) return
        val backgroundSize = (cellWidth * 0.78f).toInt().coerceAtLeast(dp(layout, 36))
        val records = field(layout, "mRecords") as? Iterable<*> ?: return
        records.forEach { record ->
            val tileView = record?.let { field(it, "tileView") as? View }
                ?: return@forEach
            val background = runCatching {
                XposedHelpers.getObjectField(tileView, "mBg") as? View
            }.getOrNull() ?: return@forEach
            val params = background.layoutParams ?: return@forEach
            if (params.width != backgroundSize || params.height != backgroundSize) {
                params.width = backgroundSize
                params.height = backgroundSize
                background.layoutParams = params
            }
            val iconFrame = runCatching {
                XposedHelpers.getObjectField(tileView, "mIconFrame") as? View
            }.getOrNull()
            iconFrame?.apply {
                scaleX = 0.78f
                scaleY = 0.78f
                pivotX = width / 2f
                pivotY = height / 2f
                translationY = -dp(this, 7).toFloat()
            }
            val group = tileView as? ViewGroup ?: return@forEach
            val existing = XposedHelpers.getAdditionalInstanceField(
                tileView,
                MAIN_TILE_LABEL_MARK
            ) as? TextView
            val label = existing ?: TextView(tileView.context).apply {
                gravity = Gravity.CENTER
                textSize = 9f
                setTextColor(Color.WHITE)
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                group.overlay.add(this)
                XposedHelpers.setAdditionalInstanceField(
                    tileView,
                    MAIN_TILE_LABEL_MARK,
                    this
                )
            }
            val tile = field(record, "tile")
            label.text = (runCatching {
                XposedHelpers.callMethod(tile, "getTileLabel") as? CharSequence
            }.getOrNull()?.toString()).orEmpty()
            val labelHeight = dp(tileView, 22)
            val labelWidth = tileView.measuredWidth.coerceAtLeast(1)
            val tileHeight = tileView.measuredHeight.coerceAtLeast(labelHeight)
            label.measure(
                View.MeasureSpec.makeMeasureSpec(labelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(labelHeight, View.MeasureSpec.EXACTLY)
            )
            label.layout(
                0,
                tileHeight - labelHeight,
                labelWidth,
                tileHeight
            )
            label.translationY = dp(tileView, 7).toFloat()
        }
    }

    private fun brightnessLocalPoint(view: View, event: MotionEvent): FloatArray {
        val global = Matrix()
        val inverse = Matrix()
        view.transformMatrixToGlobal(global)
        global.invert(inverse)
        return floatArrayOf(event.rawX, event.rawY).also(inverse::mapPoints)
    }

    private fun qsCutoutRects(view: View): List<CoverCutoutRect> {
        val cutout = view.rootWindowInsets?.displayCutout
            ?: view.rootView?.rootWindowInsets?.displayCutout
            ?: runCatching { view.display?.cutout }.getOrNull()
        return cutout?.boundingRects.orEmpty().map { rect ->
            CoverCutoutRect(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    private fun applyQsTileEdgePadding(layout: View) {
        val original = (XposedHelpers.getAdditionalInstanceField(
            layout,
            "flexunlockTilePad"
        ) as? IntArray) ?: intArrayOf(
            layout.paddingLeft,
            layout.paddingTop,
            layout.paddingRight,
            layout.paddingBottom
        ).also {
            XposedHelpers.setAdditionalInstanceField(layout, "flexunlockTilePad", it)
        }
        val rotation = coverQsRotation(layout)
        val mainLandscape = editorGridHeight(layout) == null &&
            (rotation == 1 || rotation == 3)
        val host = layout.rootView ?: layout
        val width = host.width.takeIf { it > 0 }
            ?: layout.resources.displayMetrics.widthPixels
        val inset = coverQsTileEdgeInset(rotation, width, qsCutoutRects(layout))
        val cutoutInsets = layout.rootWindowInsets?.getInsets(WindowInsets.Type.displayCutout())
        val padLeft = if (mainLandscape) {
            original[0]
        } else {
            original[0] + maxOf(inset.leftPx, cutoutInsets?.left ?: 0)
        }
        val padRight = if (mainLandscape) {
            original[2]
        } else {
            original[2] + maxOf(inset.rightPx, cutoutInsets?.right ?: 0)
        }
        if (
            layout.paddingLeft != padLeft ||
            layout.paddingTop != original[1] ||
            layout.paddingRight != padRight ||
            layout.paddingBottom != original[3]
        ) {
            layout.setPadding(padLeft, original[1], padRight, original[3])
        }
    }

    private fun disableAncestorClipping(view: View) {
        var current: View? = view
        var depth = 0
        while (current is ViewGroup && depth++ < 16) {
            current.clipChildren = false
            current.clipToPadding = false
            current = current.parent as? View
        }
    }

    private fun isPrimaryMainPagerPage(layout: View): Boolean {
        var node: Any? = layout
        repeat(3) {
            node = (node as? View)?.parent ?: return true
            val pager = node ?: return true
            if (pager.javaClass.name.contains("SubscreenPagedTileLayout")) {
                val pages = field(pager, "mPages") as? List<*> ?: return true
                val pageIndex = pages.indexOfFirst { page -> page === layout }
                return coverQsPageOwnsVisibleFrame(pageIndex)
            }
        }
        return true
    }

    private fun applyMainQsTileEdgeFrame(layout: View) {
        applyQsTileEdgePadding(layout)
        val baselineTranslation = (
            XposedHelpers.getAdditionalInstanceField(
                layout,
                "flexunlockTileBaseTranslationX"
            ) as? Float
            ) ?: layout.translationX.also { baseline ->
            XposedHelpers.setAdditionalInstanceField(
                layout,
                "flexunlockTileBaseTranslationX",
                baseline
            )
        }
        val rotation = coverQsRotation(layout)
        if (rotation != 1 && rotation != 3) {
            if (layout.translationX != baselineTranslation) {
                layout.translationX = baselineTranslation
            }
            return
        }
        val host = layout.rootView ?: layout
        val screenWidth = host.width.takeIf { it > 0 }
            ?: layout.resources.displayMetrics.widthPixels
        val inset = coverQsTileEdgeInset(rotation, screenWidth, qsCutoutRects(layout))
        val cutoutInsets = layout.rootWindowInsets?.getInsets(WindowInsets.Type.displayCutout())
        val leftSafe = maxOf(inset.leftPx, cutoutInsets?.left ?: 0)
        val rightSafe = maxOf(inset.rightPx, cutoutInsets?.right ?: 0)
        val grid = CoverQsGridConfig.read(layout.context)
        val cellWidth = XposedHelpers.getIntField(layout, "mCellWidth")
        val gap = XposedHelpers.getIntField(layout, "mCellMarginHorizontal")
        val gridWidth = cellWidth * grid.columns + gap * (grid.columns - 1)
        val targetScreenLeft = coverQsCenteredGridLeft(
            screenWidthPx = screenWidth,
            leftSafePx = leftSafe,
            rightSafePx = rightSafe,
            gridWidthPx = gridWidth
        )
        val firstTile = (field(layout, "mRecords") as? Iterable<*>)
            ?.firstOrNull()?.let { field(it, "tileView") as? View }
        val location = IntArray(2)
        if (firstTile != null) {
            runCatching { firstTile.getLocationOnScreen(location) }
        } else {
            runCatching { layout.getLocationOnScreen(location) }
        }
        val previousCorrection = layout.translationX - baselineTranslation
        val pagerOffset = (
            XposedHelpers.getAdditionalInstanceField(
                host,
                COVER_NOTIFICATION_PAGER_OFFSET_MARK
            ) as? Number
            )?.toFloat() ?: 0f
        val uncorrectedFirstLeft = coverQsUnpagedLeftPx(
            location[0].toFloat(),
            previousCorrection,
            pagerOffset
        )
        val desiredTranslation = baselineTranslation +
            targetScreenLeft - uncorrectedFirstLeft
        if (layout.translationX != desiredTranslation) {
            layout.translationX = desiredTranslation
        }
        val snapshot = "$rotation:$screenWidth:$leftSafe:$rightSafe:$gridWidth:" +
            "$targetScreenLeft:$uncorrectedFirstLeft"
        if (XposedHelpers.getAdditionalInstanceField(
                layout,
                "flexunlockMainFrameSnapshot"
            ) != snapshot
        ) {
            XposedHelpers.setAdditionalInstanceField(
                layout,
                "flexunlockMainFrameSnapshot",
                snapshot
            )
            CoverRuntime.log(
                SCOPE,
                "landscape QS frame centered rotation=$rotation " +
                    "safe=$leftSafe/$rightSafe grid=$gridWidth " +
                    "targetLeft=$targetScreenLeft firstLeft=$uncorrectedFirstLeft " +
                    "translation=${layout.translationX}"
            )
        }
    }

    /**
     * SubscreenPagedTileLayout is used by both the live QS panel and the
     * editor. Identify the editor so main-panel measure/click hooks never
     * run there: those hooks call tile.click() (opens Settings) and
     * removeAllViews/addTile during setTiles, which ANRs/crashes SystemUI.
     */
    private fun isCustomizerPager(pager: View): Boolean {
        if (
            (XposedHelpers.getAdditionalInstanceField(
                pager,
                "flexunlockCustomizerGridHeight"
            ) as? Int)?.let { it > 0 } == true
        ) {
            return true
        }
        val customizerPagerId = runCatching {
            pager.resources.getIdentifier(
                "subscreen_customize_qs_paged",
                "id",
                CoverRuntime.SYSTEM_UI_PACKAGE
            )
        }.getOrDefault(0)
        if (customizerPagerId != 0 && pager.id == customizerPagerId) return true
        var context: Context? = pager.context
        var depth = 0
        while (context != null && depth++ < 6) {
            if (context.javaClass.name == CUSTOMIZER_ACTIVITY) return true
            context = (context as? android.content.ContextWrapper)?.baseContext
        }
        return false
    }

    private fun applyCustomizerPagerHeight(pager: View) {
        val customizerGridHeight = (
            XposedHelpers.getAdditionalInstanceField(
                pager,
                "flexunlockCustomizerGridHeight"
            ) as? Int
            )?.takeIf { it > 0 } ?: return
        if (XposedHelpers.getIntField(pager, "mPageHeight") != customizerGridHeight) {
            XposedHelpers.setIntField(pager, "mPageHeight", customizerGridHeight)
        }
        if (pager.layoutParams.height != customizerGridHeight) {
            pager.layoutParams = pager.layoutParams.apply { height = customizerGridHeight }
        }
    }

    private fun applyEditorPageRows(page: Any) {
        val grid = CoverQsGridConfig.read(
            (page as? View)?.context ?: return
        )
        runCatching {
            XposedHelpers.setIntField(page, "mColumns", grid.columns)
            XposedHelpers.setIntField(page, "mMaxAllowedRows", CONFIGURED_TILE_ROWS)
            XposedHelpers.setIntField(page, "mRows", CONFIGURED_TILE_ROWS)
        }
    }

    private fun applyCustomizerPagerMetrics(pager: View) {
        applyCustomizerPagerHeight(pager)
        val pages = field(pager, "mPages") as? Collection<*> ?: return
        pages.forEach { page ->
            page ?: return@forEach
            applyEditorPageRows(page)
        }
    }

    /**
     * Samsung SubscreenPagedTileLayout.onMeasure assigns mColumns=4 whenever
     * its cache is invalidated, briefly creating a second page for 18 tiles.
     * Pin the cache only after page records and configured geometry are ready,
     * so both the main panel and editor retain their single-page layout.
     */
    private fun pinSamsungPagerMeasureCache(pager: View, widthMeasureSpec: Int) {
        runCatching { XposedHelpers.setBooleanField(pager, "mDistributeTiles", false) }
        val pageHeight = runCatching {
            XposedHelpers.getIntField(pager, "mPageHeight")
        }.getOrNull() ?: return
        runCatching { XposedHelpers.setIntField(pager, "mLastMaxHeight", pageHeight) }
        if (widthMeasureSpec != 0) {
            runCatching {
                XposedHelpers.setIntField(
                    pager,
                    "mLastMaxWidth",
                    View.MeasureSpec.getSize(widthMeasureSpec)
                )
            }
        }
    }

    private fun placeEditorTileOnFirstPage(pager: View, record: Any?) {
        record ?: return
        runCatching { XposedHelpers.setBooleanField(pager, "mDistributeTiles", false) }
        val page = ensureEditorFirstPage(pager) ?: return
        val records = field(page, "mRecords") as? Collection<*>
        if (records?.contains(record) == true) return
        val tileView = field(record, "tileView") as? View
        if (tileView?.parent == page) return
        (tileView?.parent as? ViewGroup)?.removeView(tileView)
        runCatching { XposedHelpers.callMethod(page, "addTile", record) }
    }

    private fun detachEditorTile(pager: View, record: Any?) {
        record ?: return
        runCatching { XposedHelpers.setBooleanField(pager, "mDistributeTiles", false) }
        val pages = field(pager, "mPages") as? Collection<*> ?: return
        val tileView = field(record, "tileView") as? View
        pages.forEach { page ->
            page ?: return@forEach
            mutableObjectListField(page, "mRecords")?.remove(record)
            if (tileView != null) (page as? ViewGroup)?.removeView(tileView)
        }
    }

    private fun ensureEditorFirstPage(pager: View): ViewGroup? {
        val pages = mutableObjectListField(pager, "mPages") ?: return null
        pages.firstOrNull()?.let { existing ->
            applyEditorPageRows(existing)
            return existing as? ViewGroup
        }
        val layoutId = pager.resources.getIdentifier(
            "qs_subscreen_paged_page",
            "layout",
            CoverRuntime.SYSTEM_UI_PACKAGE
        )
        if (layoutId == 0) return null
        val page = LayoutInflater.from(pager.context).inflate(
            layoutId,
            pager as ViewGroup,
            false
        )
        applyEditorPageRows(page)
        pages.add(page)
        field(pager, "mAdapter")?.let { adapter ->
            runCatching { XposedHelpers.callMethod(pager, "setAdapter", adapter) }
            runCatching { XposedHelpers.callMethod(adapter, "notifyDataSetChanged") }
        }
        return page as? ViewGroup
    }

    private fun editorGridHeight(layout: View): Int? {
        (XposedHelpers.getAdditionalInstanceField(
            layout,
            "flexunlockCustomizerGridHeight"
        ) as? Int)?.takeIf { it > 0 }?.let { return it }
        val customizerPagerId = layout.resources.getIdentifier(
            "subscreen_customize_qs_paged",
            "id",
            CoverRuntime.SYSTEM_UI_PACKAGE
        )
        if (customizerPagerId == 0) return null
        val pager = generateSequence(layout.parent as? View) { it.parent as? View }
            .firstOrNull { it.id == customizerPagerId }
        // 主面板的 tile layout parent 链中没有 customizer pager → 不是编辑页
        if (pager == null && layout.id != customizerPagerId) return null
        // 编辑页:优先缓存
        (XposedHelpers.getAdditionalInstanceField(
            pager,
            "flexunlockCustomizerGridHeight"
        ) as? Int)?.takeIf { it > 0 }?.let { return it }
        // 缓存未就绪(applyFit 在布局稳定后才写入):用三星原生行高估算,
        // 保证首帧就应用优化而不是闪现旧布局
        val nativeCellHeight = runCatching {
            XposedHelpers.getIntField(layout, "mCellHeight")
        }.getOrNull()?.takeIf { it > 0 }
        val nativeGap = runCatching {
            XposedHelpers.getIntField(layout, "mTileVerticalMargin")
        }.getOrNull()?.takeIf { it >= 0 } ?: dp(layout, 4)
        val cell = nativeCellHeight ?: dp(layout, 102)
        return cell * CONFIGURED_TILE_ROWS + nativeGap * (CONFIGURED_TILE_ROWS - 1)
    }

    private fun capPagerTiles(pager: Any, tiles: MutableList<Any?>, capacity: Int) {
        if (tiles.size <= capacity) return
        val overflow = tiles.subList(capacity, tiles.size).toList()
        tiles.subList(capacity, tiles.size).clear()
        XposedHelpers.setAdditionalInstanceField(pager, "flexunlockOverflowTiles", overflow)
    }

    private fun restoreCappedPagerTiles(pager: Any) {
        val overflow = XposedHelpers.removeAdditionalInstanceField(
            pager,
            "flexunlockOverflowTiles"
        ) as? List<*> ?: return
        val tiles = mutableObjectListField(pager, "mTiles") ?: return
        tiles.addAll(overflow)
    }

    private fun installCustomizerEnhancements(classLoader: ClassLoader) {
        val activityClass = XposedHelpers.findClassIfExists(CUSTOMIZER_ACTIVITY, classLoader)
            ?: return unavailable("customizer activity missing")
        val customizerControllerClass =
            XposedHelpers.findClassIfExists(CUSTOMIZER_CONTROLLER, classLoader)
                ?: return unavailable("customizer controller missing")

        XposedBridge.hookAllConstructors(
            customizerControllerClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = field(param.thisObject, "mView") as? View ?: return
                    val activity = view.context as? Activity ?: return
                    customizerControllers[activity] = param.thisObject
                }
            }
        )
        XposedBridge.hookAllMethods(
            customizerControllerClass,
            "setTiles",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    disposeEditorDragBindings(param.thisObject)
                    // 重建前快照当前网格:setTiles 会 removeAllTileViews 全量
                    // 重建,期间会绘制一帧空网格(表现为删除/新增磁贴时网格
                    // 闪烁);用快照覆盖过渡掩盖这帧空网格
                    captureCustomizerGridSnapshot(param.thisObject)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = field(param.thisObject, "mView") as? View ?: return
                    val activity = view.context as? Activity ?: return
                    if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity))) return
                    val records = field(param.thisObject, "mSubscreenRecords") as? List<*>
                    CoverRuntime.log(
                        SCOPE,
                        "customizer setTiles after records=${records?.size ?: -1}"
                    )
                    bindEditorDragSort(param.thisObject)
                    revealCustomizerGridSnapshot(param.thisObject)
                    scheduleCustomizerEnhancement(activity)
                }
            }
        )
        XposedBridge.hookAllMethods(
            activityClass,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity))) return
                    runCatching { activity.overridePendingTransition(0, 0) }
                    runCatching { activity.window.setWindowAnimations(0) }
                    if (NativeCoverKeyguardHooks.blockLockedActivity(activity)) return
                    runCatching {
                        activity.setRequestedOrientation(
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        )
                    }
                    scheduleCustomizerEnhancement(activity, animateEntry = true)
                }
            }
        )
        XposedBridge.hookAllMethods(
            activityClass,
            "onDestroy",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    disposeCustomizer(activity)
                }
            }
        )
        CoverRuntime.log(SCOPE, "native display-1 QS customizer add/remove enhancement installed")
    }

    /**
     * setTiles 全量重建前,把编辑页网格(pager 区域)截成位图快照,
     * 用于重建期间覆盖显示,掩盖 removeAllTileViews 造成的空网格帧。
     */
    private fun captureCustomizerGridSnapshot(controller: Any) {
        val view = field(controller, "mView") as? View ?: return
        if (view.width <= 0) return
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(view.context))) return
        val pagerId = view.resources.getIdentifier(
            "subscreen_customize_qs_paged",
            "id",
            CoverRuntime.SYSTEM_UI_PACKAGE
        )
        val target = if (pagerId != 0) {
            view.findViewById<ViewGroup>(pagerId)
        } else {
            null
        } ?: return
        if (target.width <= 0 || target.height <= 0 || target.childCount == 0) return
        disposeActiveGridOverlay(controller)
        val bitmap = runCatching {
            android.graphics.Bitmap.createBitmap(
                target.width,
                target.height,
                android.graphics.Bitmap.Config.ARGB_8888
            )
        }.getOrNull() ?: return
        if (runCatching { target.draw(android.graphics.Canvas(bitmap)) }.isFailure) {
            bitmap.recycle()
            return
        }
        editorGridSnapshots.remove(controller)?.bitmap?.let { stale ->
            if (!stale.isRecycled) stale.recycle()
        }
        editorGridSnapshots[controller] = EditorGridSnapshot(bitmap, target)
    }

    /** 重建完成后,用快照覆盖在新网格上并淡出,视觉上无空帧闪烁 */
    private fun revealCustomizerGridSnapshot(controller: Any) {
        val snapshot = editorGridSnapshots.remove(controller) ?: return
        val bitmap = snapshot.bitmap
        val target = snapshot.target
        if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        val overlay = android.widget.ImageView(target.context).apply {
            setImageBitmap(bitmap)
            alpha = 1f
        }
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                disposeActiveGridOverlay(controller, overlay)
            }
        }
        overlay.addOnAttachStateChangeListener(detachListener)
        runCatching {
            target.addView(
                overlay,
                ViewGroup.LayoutParams(target.width, target.height)
            )
        }.onFailure {
            overlay.removeOnAttachStateChangeListener(detachListener)
            overlay.setImageDrawable(null)
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        activeGridOverlays[controller] = ActiveGridOverlay(
            bitmap,
            target,
            overlay,
            detachListener
        )
        overlay.post {
            if (activeGridOverlays[controller]?.overlay !== overlay ||
                !overlay.isAttachedToWindow
            ) {
                disposeActiveGridOverlay(controller, overlay)
                return@post
            }
            overlay.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction { disposeActiveGridOverlay(controller, overlay) }
                .start()
        }
    }

    private fun disposeActiveGridOverlay(controller: Any, expected: View? = null) {
        val active = activeGridOverlays[controller] ?: return
        if (expected != null && active.overlay !== expected) return
        activeGridOverlays.remove(controller)
        active.overlay.animate().cancel()
        active.overlay.removeOnAttachStateChangeListener(active.detachListener)
        active.overlay.setImageDrawable(null)
        runCatching {
            if (active.overlay.parent === active.target) {
                active.target.removeView(active.overlay)
            }
        }
        if (!active.bitmap.isRecycled) active.bitmap.recycle()
    }

    /**
     * 编辑页长按拖动排序(自实现,不依赖三星原生拖动链):
     * 三星原生的 mLongClickListener → message 100 → 放大动画 → startDrag 链
     * 在本 ROM 有 NPE(SubscreenCustomizer$1.handleMessage:461,原生从未启用
     * 所以一直隐藏),直接激活会导致 SystemUI 崩溃。这里用 OnTouchListener
     * 自检测长按,拖动过程磁贴跟随手指,松手时计算目标格、重排 records 并
     * changeTilesByUser 持久化;短按(<600ms)不消费事件,轻触删除保持可用。
     */
    private fun bindEditorDragSort(controller: Any) {
        disposeEditorDragBindings(controller)
        val records = field(controller, "mSubscreenRecords") as? List<*> ?: return
        val bindings = mutableListOf<EditorDragBinding>()
        val tileLayoutClass = XposedHelpers.findClassIfExists(
            SUBSCREEN_TILE_LAYOUT,
            controller.javaClass.classLoader
        ) ?: return
        var bound = 0
        records.forEach { record ->
            val tileView = record?.let { item -> field(item, "tileView") as? View }
                ?: return@forEach
            val icon = runCatching {
                XposedHelpers.getObjectField(tileView, "mIcon") as? View
            }.getOrNull() ?: return@forEach
            // mIconFrame 上三星设置的 { return true } 长按监听会在 500ms 后
            // 消费长按事件,导致系统取消整个触摸流(后续 MOVE/UP 变 CANCEL,
            // 长按拖动无法完成)。移除它,触摸流保持完整。
            runCatching {
                (XposedHelpers.getObjectField(tileView, "mIconFrame") as? View)
                    ?.setOnLongClickListener(null)
            }
            // Samsung addTile also puts a swallow { return true } long-click on
            // getIcon() itself; without clearing it the system cancels the touch
            // stream at ~500ms and our 600ms long-press drag never fires.
            runCatching {
                icon.setOnLongClickListener(null)
            }
            val slop = android.view.ViewConfiguration.get(icon.context)
                .scaledTouchSlop
            val state = DragTouchState()
            val dragRunnable = Runnable {
                state.triggered = true
                state.dragView = tileView
                state.record = record
                var walk: View? = tileView.parent as? View
                var guard = 0
                while (walk != null && guard++ < 8) {
                    if (tileLayoutClass.isInstance(walk)) {
                        state.tileLayout = walk
                        break
                    }
                    walk = walk.parent as? View
                }
                tileView.animate()
                    .scaleX(1.14f).scaleY(1.14f).alpha(0.65f)
                    .setDuration(90).start()
                CoverRuntime.log(SCOPE, "editor drag: long-press triggered")
            }
            icon.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        state.downX = event.x
                        state.downY = event.y
                        state.startRawX = event.rawX
                        state.startRawY = event.rawY
                        state.triggered = false
                        icon.removeCallbacks(dragRunnable)
                        icon.postDelayed(dragRunnable, 600L)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (state.triggered) {
                            state.dragView?.translationX = event.rawX - state.startRawX
                            state.dragView?.translationY = event.rawY - state.startRawY
                            true
                        } else {
                            val dx = event.x - state.downX
                            val dy = event.y - state.downY
                            if (dx * dx + dy * dy > slop * slop) {
                                icon.removeCallbacks(dragRunnable)
                            }
                            false
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        icon.removeCallbacks(dragRunnable)
                        if (state.triggered) {
                            finishEditorDrag(controller, state, event.rawX, event.rawY)
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        icon.removeCallbacks(dragRunnable)
                        // 系统在长按后移动时会以 CANCEL 结束触摸流(事件坐标即
                        // 手指当前位置);此时同样完成拖动落位,保证真机可用
                        if (state.triggered) {
                            finishEditorDrag(controller, state, event.rawX, event.rawY)
                        }
                        false
                    }
                    else -> state.triggered
                }
            }
            bindings += EditorDragBinding(icon, tileView, dragRunnable, state)
            bound++
        }
        editorDragBindings[controller] = bindings
        CoverRuntime.log(SCOPE, "editor drag: bound $bound tile long-presses")
    }

    private fun disposeEditorDragBindings(controller: Any) {
        editorDragBindings.remove(controller).orEmpty().forEach { binding ->
            binding.icon.removeCallbacks(binding.runnable)
            binding.icon.setOnTouchListener(null)
            binding.tileView.animate().cancel()
            binding.tileView.scaleX = 1f
            binding.tileView.scaleY = 1f
            binding.tileView.alpha = 1f
            binding.tileView.translationX = 0f
            binding.tileView.translationY = 0f
            binding.state.dragView = null
            binding.state.tileLayout = null
            binding.state.record = null
            binding.state.triggered = false
        }
    }

    private fun resetEditorDragVisual(state: DragTouchState) {
        state.dragView?.animate()
            ?.scaleX(1f)?.scaleY(1f)?.alpha(1f)
            ?.translationX(0f)?.translationY(0f)
            ?.setDuration(80)?.start()
        state.dragView = null
        state.tileLayout = null
        state.record = null
        state.triggered = false
    }

    /**
     * 松手:计算手指位置对应的目标磁贴 index,把被拖磁贴移到该位置,
     * 重新生成 spec 顺序并通过 changeTilesByUser 持久化(触发 setTiles 重建)。
     */
    private fun finishEditorDrag(
        controller: Any,
        state: DragTouchState,
        rawX: Float,
        rawY: Float
    ) {
        val tileLayout = state.tileLayout as? View
        val draggedRecord = state.record
        if (tileLayout == null || draggedRecord == null) {
            CoverRuntime.log(
                SCOPE,
                "editor drag: finish skipped tileLayout=${tileLayout != null} record=${draggedRecord != null}"
            )
            resetEditorDragVisual(state)
            return
        }
        val records = field(tileLayout, "mRecords") as? List<*> ?: run {
            CoverRuntime.log(SCOPE, "editor drag: finish skipped records null")
            resetEditorDragVisual(state)
            return
        }
        val from = records.indexOfFirst { it === draggedRecord }
        if (from < 0) {
            CoverRuntime.log(SCOPE, "editor drag: finish skipped from<0")
            resetEditorDragVisual(state)
            return
        }
        // 目标:手指位置最近的磁贴(排除被拖磁贴)
        var best = from
        var bestDist = Float.MAX_VALUE
        records.forEachIndexed { index, r ->
            if (r === draggedRecord || r == null) return@forEachIndexed
            val v = runCatching { field(r, "tileView") as? View }.getOrNull()
                ?: return@forEachIndexed
            val loc = IntArray(2)
            runCatching { v.getLocationOnScreen(loc) }
            val cx = loc[0] + v.width / 2f
            val cy = loc[1] + v.height / 2f
            val d = (cx - rawX) * (cx - rawX) + (cy - rawY) * (cy - rawY)
            if (d < bestDist) {
                bestDist = d
                best = index
            }
        }
        // 若目标在被拖磁贴之后,移除后插入位置需减一
        val insertAt = if (best > from) best - 1 else best
        val list = records as? MutableList<*> ?: run {
            resetEditorDragVisual(state)
            return
        }
        val item = list.removeAt(from)
        (list as MutableList<Any?>).add(insertAt.coerceIn(0, list.size), item)
        // 持久化:按新顺序 changeTilesByUser
        val specs = list.mapNotNull {
            runCatching { XposedHelpers.getObjectField(it, "mTilespec") as? String }
                .getOrNull()
        }
        resetEditorDragVisual(state)
        if (specs.isEmpty()) {
            CoverRuntime.log(SCOPE, "editor drag: finish skipped specs empty")
            return
        }
        val host = runCatching {
            XposedHelpers.getObjectField(controller, "mHost")
        }.getOrNull() ?: return
        val hostView = field(controller, "mView") as? View
        val oldSpecs = ArrayList<String>()
        val newSpecs = ArrayList(specs)
        val persist = Runnable {
            runCatching {
                XposedHelpers.callMethod(host, "changeTilesByUser", oldSpecs, newSpecs)
            }.onFailure {
                CoverRuntime.log(SCOPE, "editor drag: reorder persist failed: $it")
            }
        }
        if (hostView?.isAttachedToWindow == true) {
            hostView.post(persist)
        } else {
            persist.run()
        }
        CoverRuntime.log(SCOPE, "editor drag: reordered $from -> $insertAt")
    }

    private fun attachEditor(controller: Any) {
        val panel = field(controller, "mQSPanel") as? ViewGroup
            ?: return unavailable("mQSPanel is not a ViewGroup")
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(panel.context))) return
        val root = field(controller, "mSubScreenQsWindowView") as? ViewGroup
            ?: return unavailable("mSubScreenQsWindowView is not a ViewGroup")

        // 顶部状态栏跟随 QS 滚动(三星每次 draw 都会把 header 拉回固定顶部,
        // 需要 preDraw 强制它跟随滚动偏移)。
        val headerId = panel.resources.getIdentifier(
            "sub_screen_quick_panel_header",
            "id",
            panel.context.packageName
        )
        if (headerId != 0) {
            val header = panel.findViewById<View>(headerId)
            // 顶部状态栏图标避免顶部被裁剪:补 3px 顶部内边距(用户反馈
            // 蓝牙/飞行模式等图标最上面有裁剪效果)。
            runCatching {
                val minPad = dp(panel, 3)
                if (header.paddingTop < minPad) {
                    header.setPadding(
                        header.paddingLeft,
                        minPad,
                        header.paddingRight,
                        header.paddingBottom
                    )
                }
            }
        bindQsHeader(panel, header)
        }
        bindQsExpansionLifecycle(panel)

        editorEntries[panel]?.let { entry ->
            updateEditorEntry(controller, panel, entry)
            enforceMediaAboveBrightness(panel)
            return
        }

        val button = TextView(panel.context).apply {
            text = "编辑"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "编辑快捷设置磁贴"
            setPadding(dp(this, 10), 0, dp(this, 10), 0)
            elevation = dp(this, 3).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(panel, 14).toFloat()
                setColor(Color.argb(236, 46, 50, 59))
                setStroke(dp(panel, 1), Color.argb(92, 255, 255, 255))
            }
            setOnClickListener { openCustomizer(this, controller) }
        }
        root.addView(
            button,
            FrameLayout.LayoutParams(
                dp(button, 58),
                dp(button, 28),
                Gravity.BOTTOM or Gravity.START
            )
        )
        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            editorEntries[panel]?.let { updateEditorEntry(controller, panel, it) }
            val fraction = coverQsExpandedFraction()
            if (fraction > 0.001f) enforceMediaAboveBrightness(panel)
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                pendingCustomizerLaunches.remove(controller)
                disposeQsPanel(panel)
                editorEntries.remove(panel)?.let { entry ->
                    entry.root.removeView(entry.button)
                    panel.removeOnLayoutChangeListener(entry.layoutListener)
                    panel.removeOnAttachStateChangeListener(this)
                }
            }
        }
        val entry = EditorEntry(root, button, layoutListener, attachListener)
        editorEntries[panel] = entry
        panel.addOnLayoutChangeListener(layoutListener)
        panel.addOnAttachStateChangeListener(attachListener)
        updateEditorEntry(controller, panel, entry)
        enforceMediaAboveBrightness(panel)
        CoverRuntime.log(
            SCOPE,
            "native display-1 QS customizer entry attached as Window-root sibling " +
                "panel=${panel.javaClass.name} root=${root.javaClass.name}"
        )
    }

    private fun updateEditorEntry(controller: Any, panel: View, entry: EditorEntry) {
        val expandedFraction = (field(controller, "mExpandedFraction") as? Number)?.toFloat() ?: 0f
        entry.panelVisible = panel.isAttachedToWindow &&
            panel.isShown &&
            entry.root.isShown &&
            expandedFraction >= 0.98f &&
            !NativeCoverKeyguardHooks.isShowing()
        applyEditorVisibility(entry)
        entry.button.animate().cancel()
        entry.button.alpha = 1f
        entry.button.translationX = 0f
        entry.button.translationY = 0f
        if (!entry.panelVisible || entry.root.width <= 0 || entry.root.height <= 0) return

        val cutoutRects = if (CoverDisplayConfig.readHalfMode(entry.root.context)) {
            emptyList()
        } else {
            entry.root.rootWindowInsets?.displayCutout?.boundingRects.orEmpty().map { rect ->
                CoverCutoutRect(rect.left, rect.top, rect.right, rect.bottom)
            }
        }
        val params = (entry.button.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                dp(entry.button, 28),
                dp(entry.button, 28)
            )
        val buttonWidth = dp(entry.button, 58)
        val buttonHeight = dp(entry.button, 28)
        val layout = coverQsEditLiveBottomLayout(
            windowWidthPx = entry.root.width,
            windowHeightPx = entry.root.height,
            cutoutRects = cutoutRects,
            buttonWidthPx = buttonWidth,
            buttonHeightPx = buttonHeight,
            edgePaddingPx = dp(entry.root, 8)
        )
        params.gravity = Gravity.BOTTOM or Gravity.START
        params.width = layout.widthPx
        params.height = layout.heightPx
        params.marginStart = layout.leftMarginPx
        params.marginEnd = 0
        params.leftMargin = 0
        params.topMargin = 0
        params.bottomMargin = layout.bottomMarginPx
        entry.button.translationY = 0f
        entry.button.layoutParams = params
        val rotation = entry.root.display?.rotation ?: 0
        val snapshot = "visible=${entry.button.visibility} primary=${entry.primaryPageVisible} " +
            "size=${params.width}x${params.height} start=${params.marginStart} " +
            "bottom=${params.bottomMargin} rotation=$rotation " +
            "root=${entry.root.width}x${entry.root.height}"
        if (entry.lastLayoutSnapshot != snapshot) {
            entry.lastLayoutSnapshot = snapshot
            CoverRuntime.log(SCOPE, "QS edit entry layout $snapshot")
        }
    }

    fun bindQsHeader(panel: View, header: View?) {
        val group = panel as? ViewGroup ?: return
        val state = qsScrollStates.getOrPut(group) { QsScrollState() }
        if (state.header !== header) {
            removePreDrawListener(state.headerPreDrawObserver, state.headerPreDrawListener)
            state.headerPreDrawObserver = null
            state.headerPreDrawListener = null
            state.header?.translationY = qsHeaderBaseTranslationY(group, state)
            state.header = header
            state.headerBaseTranslationY = header?.translationY ?: 0f
            // Samsung relayouts the header back to its fixed top position on
            // every draw; force it to follow the scroll offset so the top bar
            // scrolls away with the content instead of staying pinned.
            if (header != null) {
                val observer = header.viewTreeObserver
                val listener = ViewTreeObserver.OnPreDrawListener {
                    val s = qsScrollStates[group] ?: return@OnPreDrawListener true
                    if (s.offset != 0f || s.islandLiftY != 0f) {
                        header.translationY =
                            qsHeaderBaseTranslationY(group, s) + s.offset + s.islandLiftY
                    }
                    true
                }
                observer.addOnPreDrawListener(listener)
                state.headerPreDrawObserver = observer
                state.headerPreDrawListener = listener
            }
        }
        if (state.nativeLayoutCaptured) applyQsScrollOffset(group, state)
    }

    fun setPrimaryPageVisible(root: ViewGroup, visible: Boolean) {
        synchronized(editorEntries) {
            editorEntries.values.firstOrNull { it.root === root }?.let { entry ->
                entry.primaryPageVisible = visible
                applyEditorVisibility(entry)
            }
        }
    }

    private fun applyEditorVisibility(entry: EditorEntry) {
        entry.button.visibility = if (entry.panelVisible && entry.primaryPageVisible) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private data class LockscreenQsLayout(
        val mediaTop: Int?,
        val brightnessTop: Int,
        val contentBottom: Int
    )

    /**
     * 锁屏下拉 QS 的布局目标:
     * - media 顶部 = 磁贴内容底(实际行数,3 行紧凑无空行)+ 行距(rowGap),
     *   与磁贴行距保持一致
     * - 亮度条 = media 下方(media 可见)或屏幕底部完整可见(media 隐藏)
     * - contentBottom 用于滚动(内容超高时 QS 可滚动而非收起)
     */
    private fun computeLockscreenLayout(panel: ViewGroup): LockscreenQsLayout? {
        val tileContainer = panel.findDescendantByName("subscreen_tile_layout")
            ?: return null
        val tilePager = panel.findDescendantByName("subscreen_qs_pager")
            ?: return null
        val brightness = panel.findDescendantByName("subroom_brightness_settings")
            ?: return null
        val media = field(panel, "mMediaPanelView") as? View
            ?: panel.findDescendantByName("subscreen_media_player_root_view")
        if (tileContainer.height <= 0 || brightness.height <= 0) return null
        val tileMetrics = nativeTileMetrics(tilePager) ?: return null
        val gap = tileMetrics.rowGap
        val panelLoc = IntArray(2)
        panel.getLocationInWindow(panelLoc)
        val tileLoc = IntArray(2)
        tileContainer.getLocationInWindow(tileLoc)
        // 磁贴内容底部(相对 panel):用 contentBottom(实际行数)而非容器高度,
        // 避免第 4 行空白占位
        val tileBottom = tileLoc[1] - panelLoc[1] + tileMetrics.contentBottom
        val mediaVisible = media != null && media.visibility == View.VISIBLE
        val screenH = panel.resources.displayMetrics.heightPixels
        val bottomGap = dp(panel, 12)
        val mediaTop = if (mediaVisible) tileBottom + gap else null
        val brightnessTop = if (mediaVisible && media != null) {
            (mediaTop!! + media.height + gap)
                .coerceAtMost(screenH - brightness.height - bottomGap)
        } else {
            (screenH - brightness.height - bottomGap)
        }
        val contentBottom = brightnessTop + brightness.height + gap
        return LockscreenQsLayout(mediaTop, brightnessTop, contentBottom)
    }

    private fun applyLockscreenQsLayout(panel: ViewGroup) {
        val layout = computeLockscreenLayout(panel) ?: return
        val state = qsScrollStates.getOrPut(panel) { QsScrollState() }
        val insetsBottom = panel.rootWindowInsets?.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )?.bottom ?: 0
        // 只更新滚动数据:内容超高时 QS 可滚动而非滑动收起。
        // 注意:不做 media/brightness 的持续 translationY 强制 —— 那会与
        // 滚动 offset 冲突,导致锁屏 QS 磁贴"乱飘"。
        state.contentBottom = layout.contentBottom
        state.viewportBottom = panel.height - insetsBottom
    }

    /**
     * Lock-screen QS: force tile grid to 3 rows once lock state is known.
     * onMeasure’s isShowing() check can lag (fires before KeyguardController
     * updates mShowing), leaving 4 rows and pushing media/brightness off the
     * panel. Called from the lockscreen branch of enforceMediaAboveBrightness.
     */
    private fun forceLockscreenTileRows(panel: ViewGroup) {
        // 锁屏与解锁一致:按实际磁贴数量显示(4 行),不再用 3 行。
        val pager = panel.findDescendantByName("subscreen_qs_pager") ?: return
        val pages = field(pager, "mPages") as? Collection<*> ?: return
        val grid = CoverQsGridConfig.read(pager.context)
        val refreshed = XposedHelpers.getAdditionalInstanceField(
            panel,
            "flexunlockLockscreenRowsRefreshed"
        ) as? Boolean
        if (refreshed == true) return
        XposedHelpers.setAdditionalInstanceField(panel, "flexunlockLockscreenRowsRefreshed", true)
        CoverRuntime.log(SCOPE, "lockscreen force rows to 4: pages=${pages.size}")
        pages.forEach { page ->
            runCatching {
                XposedHelpers.setIntField(page, "mColumns", grid.columns)
                XposedHelpers.setIntField(page, "mMaxAllowedRows", 4)
                XposedHelpers.setIntField(page, "mRows", 4)
            }
        }
        val firstPage = pages.firstOrNull() ?: return
        val cellHeight = (field(firstPage, "mCellHeight") as? Number)?.toInt() ?: return
        val verticalGap = (field(firstPage, "mTileVerticalMargin") as? Number)?.toInt() ?: 0
        val pageHeight = cellHeight * 4 + verticalGap * 3
        runCatching { XposedHelpers.setIntField(pager, "mPageHeight", pageHeight) }
        runCatching {
            val lp = pager.layoutParams
            if (lp.height != pageHeight) pager.layoutParams = lp.apply { height = pageHeight }
        }
        pager.requestLayout()
    }

    private fun enforceMediaAboveBrightness(panel: ViewGroup) {
        // lock/unlock switch: layout baseline differs (lock 3 rows vs unlock 4),
        // drop stale scroll state (offset/base/capture) so unlocked tiles are not
        // left pinned to the lock-screen baseline/scroll position.
        val lockscreen = NativeCoverKeyguardHooks.isShowing()
        val prevLock = XposedHelpers.getAdditionalInstanceField(
            panel,
            "flexunlockLastLockState"
        ) as? Boolean
        if (prevLock != null && prevLock != lockscreen) {
            qsScrollStates[panel]?.let { restoreNativeQsLayout(panel, it) }
            CoverRuntime.log(SCOPE, "qs scroll state reset on lock-state change")
        }
        XposedHelpers.setAdditionalInstanceField(
            panel,
            "flexunlockLastLockState",
            lockscreen
        )
        val state = qsScrollStates.getOrPut(panel) { QsScrollState() }
        val expandedFraction = coverQsExpandedFraction()
        val previousFraction = state.lastExpansionFraction
        val collapsing = expandedFraction + 0.001f < previousFraction
        state.lastExpansionFraction = expandedFraction

        // The native panel already animates its whole surface during collapse.
        // Re-running the full placement scan here competes with that animation
        // and causes frame drops; restore our translations only at the endpoint.
        if (collapsing && expandedFraction > 0.001f) return
        if (expandedFraction <= 0.001f) {
            maybeRestoreCollapsedQsLayout(panel)
            return
        }
        // 锁屏状态下下拉的 QS:只更新滚动数据(内容超高时可滚动,而不是
        // 滑动收起),布局交给三星原生(锁屏 3 行后 media/亮度条空间充足)。
        if (lockscreen) {
            runCatching {
                forceLockscreenTileRows(panel)
                applyLockscreenQsLayout(panel)
                // 锁屏:磁贴已缩小(cell 89),media/亮度移到磁贴下方
                // (enforce 计算位置),避免三星原生把 media 放顶部。
                enforceUnlockedQsLayout(panel)
            }
            return
        }
        enforceUnlockedQsLayout(panel)
    }

    /**
     * Place media above brightness below the tile grid (shared by lock and
     * unlock QS). Lock-screen native layout puts the media bar at the TOP;
     * reuse this positioning so the media bar sits under the tiles and the
     * brightness slider under it. Positions are stored as base translations
     * so the scroll system (base + offset) stays consistent.
     */
    private fun enforceUnlockedQsLayout(panel: ViewGroup) {
        val media = field(panel, "mMediaPanelView") as? View ?: panel.findDescendantByName(
            "subscreen_media_player_root_view"
        ) ?: return
        bindMediaLayoutChanges(panel, media)

        val tileContainer = panel.findDescendantByName("subscreen_tile_layout") ?: return
        val tilePager = panel.findDescendantByName("subscreen_qs_pager") ?: return
        val brightness = panel.findDescendantByName("subroom_brightness_settings") ?: return
        val chrome = CoverQsSpecialConfig.read(panel.context)
        if (chrome.mediaHidden) media.visibility = View.GONE
        brightness.visibility = if (chrome.brightnessHidden) View.GONE else View.VISIBLE
        if (!chrome.brightnessHidden && brightness.coverVisualWidth() <= 32) {
            brightness.requestLayout()
        }
        maybeRestoreCollapsedQsLayout(panel)
        bindQsExpansionLifecycle(panel)
        val state = qsScrollStates.getOrPut(panel) { QsScrollState() }
        val expandedFraction = coverQsExpandedFraction()
        val chromeRevealAlpha = coverQsChromeRevealAlpha(expandedFraction)
        if (!chrome.mediaHidden) media.alpha = chromeRevealAlpha
        if (!chrome.brightnessHidden) brightness.alpha = chromeRevealAlpha
        // Apply the measured placement during the drag. Waiting for two pre-draw
        // frames makes Samsung's native top-positioned brightness bar flash in
        // the first part of a slow pull.
        if (expandedFraction <= 0.001f) return
        if (
            !media.isAttachedToWindow ||
            !tileContainer.isAttachedToWindow ||
            !tilePager.isAttachedToWindow ||
            !brightness.isAttachedToWindow ||
            panel.height <= 0 ||
            tileContainer.height <= 0 ||
            (!chrome.brightnessHidden && brightness.coverVisualWidth() <= 32)
        ) return

        val rotation = panel.display?.rotation ?: 0
        if (state.capturedRotation != rotation) {
            tileContainer.translationY = 0f
            media.translationY = 0f
            brightness.translationY = 0f
            state.nativeLayoutCaptured = false
            state.offset = 0f
            state.islandLiftY = 0f
            state.capturedRotation = rotation
        }

        if (!state.nativeLayoutCaptured) {
            state.nativeLayoutCaptured = true
            state.nativeTileTranslationY = tileContainer.translationY
            state.nativeMediaTranslationX = media.translationX
            state.nativeMediaTranslationY = media.translationY
            state.nativeMediaClipBounds = media.clipBounds?.let(::Rect)
            state.nativeMediaWidth = media.layoutParams.width
            state.nativeBrightnessTranslationY = brightness.translationY
            state.nativeBrightnessTranslationX = brightness.translationX
            state.nativeBrightnessClipBounds = brightness.clipBounds?.let(::Rect)
            state.nativeBrightnessHeight = brightness.coverDrawnHeight()
        }

        val tileMetrics = nativeTileMetrics(tilePager) ?: return
        val gap = tileMetrics.rowGap
        val pairGap = coverQsSpecialPairGapPx(gap, tileMetrics.lastRowInset)
        val host = qsChromeHost(panel, media, brightness)
        val cutoutRects = (panel.rootWindowInsets?.displayCutout?.boundingRects ?: emptyList())
            .map { CoverCutoutRect(it.left, it.top, it.right, it.bottom) }
        state.islandLiftY = -coverQsIslandLiftPx(
            rotation = rotation,
            windowHeightPx = host.height.takeIf { it > 0 }
                ?: panel.resources.displayMetrics.heightPixels,
            cutoutRects = cutoutRects
        ).toFloat()
        val pagerTop = offsetTopInAncestor(tilePager, host)
        val liveTileBottom = pagerTop + tileMetrics.contentBottom
        val tileBottomAtNativeTranslation =
            liveTileBottom - (tileContainer.translationY - state.nativeTileTranslationY).toInt()
        val mediaTopAtNativeTranslation =
            offsetTopInAncestor(media, host) -
                (media.translationY - state.nativeMediaTranslationY).toInt()
        val brightnessTopAtNativeTranslation =
            offsetTopInAncestor(brightness, host) -
                (brightness.translationY - state.nativeBrightnessTranslationY).toInt()
        val insets = panel.rootWindowInsets?.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        val bottomSafe = maxOf(
            insets?.bottom ?: 0,
            if (rotation == 2) dp(panel, 20) else dp(panel, 12),
            dp(panel, 36)
        )
        val mediaUnavailable = chrome.mediaHidden ||
            media.visibility != View.VISIBLE ||
            media.coverVisualHeight() <= 32
        val mediaDrawnHeight = media.coverDrawnHeight().coerceAtLeast(1)
        val brightnessDrawnHeight = brightness.coverDrawnHeight().coerceAtLeast(1)
        val placement = coverQsSpecialPlacement(
            tileBottomPx = tileBottomAtNativeTranslation,
            gapPx = gap,
            mediaHeightPx = mediaDrawnHeight,
            brightnessHeightPx = brightnessDrawnHeight,
            panelHeightPx = panel.height,
            bottomSafePx = bottomSafe,
            mediaHidden = mediaUnavailable,
            brightnessHidden = chrome.brightnessHidden,
            brightnessFirst = chrome.brightnessFirst,
            pairGapPx = pairGap
        )
        val desiredMediaTop = placement.mediaTopPx ?: (tileBottomAtNativeTranslation + gap)
        val desiredBrightnessTop = placement.brightnessTopPx
            ?: (desiredMediaTop + mediaDrawnHeight + pairGap)

        state.tileBaseTranslationY = state.nativeTileTranslationY
        state.mediaBaseTranslationY =
            state.nativeMediaTranslationY + desiredMediaTop - mediaTopAtNativeTranslation
        state.brightnessBaseTranslationY =
            state.nativeBrightnessTranslationY +
                desiredBrightnessTop - brightnessTopAtNativeTranslation
        state.brightnessVisualLeft = offsetLeftInAncestor(brightness, host)
        state.brightnessVisualTop = desiredBrightnessTop
        state.contentBottom = coverQsContentBottomPx(
            tileBottomPx = tileBottomAtNativeTranslation,
            gapPx = gap,
            mediaTopPx = placement.mediaTopPx.takeUnless { mediaUnavailable },
            mediaHeightPx = mediaDrawnHeight,
            brightnessTopPx = placement.brightnessTopPx.takeUnless { chrome.brightnessHidden },
            brightnessHeightPx = brightnessDrawnHeight
        )
        state.viewportBottom = panel.height - bottomSafe
        state.offset = coverQsOpenOffset(
            dragging = state.dragging,
            userScrolled = state.userScrolled,
            currentOffset = state.offset,
            minOffset = minQsScrollOffset(state)
        )
        val brightnessParent = brightness.parent as? View
        val layoutSnapshot = "tileBottom=$tileBottomAtNativeTranslation gap=$gap pair=$pairGap " +
            "media=$mediaTopAtNativeTranslation->$desiredMediaTop h=$mediaDrawnHeight " +
            "brightness=$brightnessTopAtNativeTranslation->$desiredBrightnessTop " +
            "h=$brightnessDrawnHeight layout=${brightness.left},${brightness.top}," +
            "${brightness.right},${brightness.bottom} measured=${brightness.measuredWidth}x" +
            "${brightness.measuredHeight} parent=${brightnessParent?.javaClass?.name}" +
            "[${brightnessParent?.left},${brightnessParent?.top}," +
            "${brightnessParent?.right},${brightnessParent?.bottom}] " +
            "content=${state.contentBottom} viewport=${state.viewportBottom} " +
            "offset=${state.offset} userScrolled=${state.userScrolled} " +
            "rotation=$rotation host=${host.javaClass.simpleName}"
        if (XposedHelpers.getAdditionalInstanceField(panel, "flexunlockQsLayoutSnapshot") != layoutSnapshot) {
            XposedHelpers.setAdditionalInstanceField(
                panel,
                "flexunlockQsLayoutSnapshot",
                layoutSnapshot
            )
            CoverRuntime.log(SCOPE, "QS unified spacing $layoutSnapshot")
        }

        state.header?.let { allowTranslatedContent(panel, it, state) }
        allowTranslatedContent(panel, tileContainer, state)
        if (!chrome.brightnessHidden) {
            val targetLeft = offsetLeftInAncestor(tilePager, host) + tileMetrics.contentLeft
            val currentLeft = offsetLeftInAncestor(brightness, host)
            val deltaX = targetLeft - currentLeft
            if (kotlin.math.abs(deltaX) > 1) brightness.translationX += deltaX
            allowTranslatedContent(panel, brightness, state)
        }
        if (!chrome.mediaHidden && !mediaUnavailable) {
            applyMediaMatchBrightness(
                media = media,
                brightness = brightness.takeIf { !chrome.brightnessHidden && it.coverVisualWidth() > 32 },
                tileMetrics = tileMetrics,
                pagerLeftInPanel = offsetLeftInAncestor(tilePager, host),
                currentLeftInPanel = offsetLeftInAncestor(media, host),
                brightnessLeftInPanel = offsetLeftInAncestor(brightness, host)
            )
            allowTranslatedContent(panel, media, state)
        }
        applyQsScrollOffset(panel, state)
        separateOverlappingChrome(
            host = host,
            state = state,
            media = media,
            brightness = brightness,
            brightnessFirst = chrome.brightnessFirst,
            mediaUnavailable = mediaUnavailable,
            pairGap = pairGap
        )
        applyQsChromeVerticalReveal(panel, state, media, brightness)
        if (!chrome.mediaHidden || !chrome.brightnessHidden) {
            bindQsExpansionLifecycle(panel)
        }
    }

    /**
     * Reset translated chrome only after the panel is fully collapsed. During
     * an active drag the native panel translation remains the single source of
     * truth for all four rotations.
     */
    private fun bindQsExpansionLifecycle(panel: ViewGroup) {
        val state = qsScrollStates.getOrPut(panel) { QsScrollState() }
        if (state.mediaFollowListener != null) return
        val observer = panel.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (qsScrollStates[panel] == null) return@OnPreDrawListener true
            val fraction = coverQsExpandedFraction()
            if (fraction <= 0.001f) {
                maybeRestoreCollapsedQsLayout(panel)
            }
            true
        }
        observer.addOnPreDrawListener(listener)
        state.mediaFollowObserver = observer
        state.mediaFollowListener = listener
    }

    private fun nativeTileMetrics(tileLayout: View): NativeTileMetrics? {
        val nativePage = ((field(tileLayout, "mPages") as? Collection<*>)
            ?.firstOrNull() as? View)
            ?.takeIf { field(it, "mRecords") is Collection<*> }
            ?: tileLayout
        val records = field(nativePage, "mRecords") as? Collection<*>
        val tileViews = records.orEmpty()
            .mapNotNull { record -> record?.let { field(it, "tileView") as? View } }
            .filter { it.isLaidOut && it.height > 0 }
        if (tileViews.isEmpty()) {
            logTileMetrics(tileLayout, "unavailable page=${nativePage.javaClass.name} records=${records?.size ?: -1}")
            return null
        }
        val signature = buildString {
            append(tileLayout.display?.rotation ?: -1)
            append('|').append(tileLayout.width).append('x').append(tileLayout.height)
            append('|').append(nativePage.width).append('x').append(nativePage.height)
            append('|').append(tileViews.size).append('/').append(records?.size ?: -1)
        }
        val cached = XposedHelpers.getAdditionalInstanceField(
            tileLayout,
            "flexunlockNativeTileMetricsCache"
        ) as? NativeTileMetricsCache
        if (
            cached?.signature == signature &&
            !tileLayout.isLayoutRequested &&
            !nativePage.isLayoutRequested
        ) {
            return cached.metrics
        }

        val layoutLocation = IntArray(2).also(tileLayout::getLocationInWindow)
        val bounds = tileViews.map { tile ->
            val location = IntArray(2).also(tile::getLocationInWindow)
            Rect(
                location[0] - layoutLocation[0],
                location[1] - layoutLocation[1],
                location[0] - layoutLocation[0] + tile.width,
                location[1] - layoutLocation[1] + tile.height
            )
        }
        val rowTops = bounds.map(Rect::top).distinct().sorted()
        val measuredGap = if (rowTops.size >= 2) {
            val firstRowBottom = bounds
                .filter { it.top == rowTops[0] }
                .maxOfOrNull(Rect::bottom)
            firstRowBottom?.let { rowTops[1] - it }?.takeIf { it > 0 }
        } else {
            null
        }
        val fieldGap = (field(nativePage, "mTileVerticalMargin") as? Number)
            ?.toInt()
            ?.takeIf { it > 0 }
        val rowGap = measuredGap ?: fieldGap
        if (rowGap == null) {
            logTileMetrics(
                tileLayout,
                "unavailable page=${nativePage.javaClass.name} rows=$rowTops fieldGap=$fieldGap"
            )
            return null
        }
        val lastRowBottom = bounds.maxOf(Rect::bottom)
        val lastRowHeight = bounds
            .filter { it.bottom == lastRowBottom }
            .maxOf { it.bottom - it.top }
        val lastRowContentBottom = tileViews.zip(bounds)
            .filter { it.second.bottom == lastRowBottom }
            .mapNotNull { (tile, _) -> visualInnerBottom(tile, layoutLocation[1]) }
            .maxOrNull()
            ?: lastRowBottom
        val metrics = NativeTileMetrics(
            contentBottom = lastRowBottom,
            rowGap = rowGap,
            lastRowInset = coverQsLastRowInsetPx(
                lastRowHeightPx = lastRowHeight,
                lastRowContentBottomPx = lastRowContentBottom,
                lastRowViewBottomPx = lastRowBottom
            ),
            contentLeft = bounds.minOf(Rect::left),
            contentWidth = bounds.maxOf(Rect::right) - bounds.minOf(Rect::left)
        )
        XposedHelpers.setAdditionalInstanceField(
            tileLayout,
            "flexunlockNativeTileMetricsCache",
            NativeTileMetricsCache(signature, metrics)
        )
        logTileMetrics(
            tileLayout,
            "ready page=${nativePage.javaClass.name} records=${tileViews.size} " +
                "rows=$rowTops metrics=$metrics"
        )
        return metrics
    }

    private fun logTileMetrics(tileLayout: View, snapshot: String) {
        if (XposedHelpers.getAdditionalInstanceField(tileLayout, "flexunlockTileMetrics") == snapshot) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(tileLayout, "flexunlockTileMetrics", snapshot)
        CoverRuntime.log(SCOPE, "QS tile metrics $snapshot")
    }

    private fun bindMediaLayoutChanges(panel: ViewGroup, media: View) {
        val state = qsScrollStates.getOrPut(panel) { QsScrollState() }
        if (state.mediaLayoutView === media && state.mediaLayoutListener != null) return
        state.mediaLayoutView?.let { oldView ->
            state.mediaLayoutListener?.let(oldView::removeOnLayoutChangeListener)
        }
        val listener = View.OnLayoutChangeListener {
                _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (
                right - left != oldRight - oldLeft ||
                bottom - top != oldBottom - oldTop
            ) {
                panel.post {
                    if (panel.isAttachedToWindow) enforceMediaAboveBrightness(panel)
                }
            }
        }
        media.addOnLayoutChangeListener(listener)
        state.mediaLayoutView = media
        state.mediaLayoutListener = listener
    }

    private fun allowTranslatedContent(
        panel: ViewGroup,
        view: View,
        state: QsScrollState
    ) {
        fun disableClipping(group: ViewGroup) {
            state.clipBaselines.putIfAbsent(
                group,
                ClipBaseline(group.clipChildren, group.clipToPadding)
            )
            group.clipChildren = false
            group.clipToPadding = false
        }

        disableClipping(panel)
        var parent = view.parent as? ViewGroup
        while (parent != null) {
            disableClipping(parent)
            parent = parent.parent as? ViewGroup
        }
    }

    fun isQsBottomGestureRegion(panel: View, event: MotionEvent): Boolean {
        val group = panel as? ViewGroup ?: return true
        val state = qsScrollStates[group] ?: return true
        if (minQsScrollOffset(state) < 0f) return false
        val location = IntArray(2)
        group.getLocationInWindow(location)
        return event.rawY - location[1] >= state.viewportBottom
    }

    fun beginQsVerticalGesture(panel: View, event: MotionEvent) {
        val state = (panel as? ViewGroup)?.let(qsScrollStates::get) ?: return
        state.downY = event.rawY
        state.startOffset = state.offset
        state.dragging = false
        state.gestureStartedAtContentBottom =
            state.offset <= minQsScrollOffset(state) + 0.5f
        state.collapseRequested = false
        state.collapseDispatched = false
    }

    fun updateQsVerticalGesture(panel: View, event: MotionEvent): Boolean {
        val group = panel as? ViewGroup ?: return false
        val state = qsScrollStates[group] ?: return false
        if (minQsScrollOffset(state) >= 0f) return false
        val desiredOffset = state.startOffset + event.rawY - state.downY
        val minimumOffset = minQsScrollOffset(state)
        state.dragging = true
        state.userScrolled = true
        state.offset = desiredOffset.coerceIn(minimumOffset, 0f)
        val collapseSlop = android.view.ViewConfiguration.get(group.context).scaledTouchSlop
        state.collapseRequested =
            state.gestureStartedAtContentBottom &&
                !state.collapseDispatched &&
                desiredOffset < minimumOffset - collapseSlop
        applyQsScrollOffset(group, state)
        return true
    }

    fun finishQsVerticalGesture(panel: View): Boolean {
        val state = (panel as? ViewGroup)?.let(qsScrollStates::get) ?: return false
        val consumed = state.dragging
        state.dragging = false
        return consumed
    }

    fun consumeQsCollapseRequest(panel: View): Boolean {
        val state = (panel as? ViewGroup)?.let(qsScrollStates::get) ?: return false
        if (!state.collapseRequested || state.collapseDispatched) return false
        state.collapseRequested = false
        state.collapseDispatched = true
        return true
    }

    fun resetQsVerticalScroll(panel: View) {
        val group = panel as? ViewGroup ?: return
        val state = qsScrollStates[group] ?: return
        restoreNativeQsLayout(group, state)
    }

    private fun restoreNativeQsLayout(panel: ViewGroup, state: QsScrollState) {
        val hadOverrides = state.nativeLayoutCaptured ||
            state.clipBaselines.isNotEmpty() ||
            state.offset != 0f
        state.offset = 0f
        state.dragging = false
        state.userScrolled = false
        state.gestureStartedAtContentBottom = false
        state.collapseRequested = false
        state.collapseDispatched = false
        state.header?.translationY = qsHeaderBaseTranslationY(panel, state)
        state.islandLiftY = 0f

        if (state.nativeLayoutCaptured) {
            panel.findDescendantByName("subscreen_tile_layout")?.translationY =
                state.nativeTileTranslationY
            val media = field(panel, "mMediaPanelView") as? View ?: panel.findDescendantByName(
                "subscreen_media_player_root_view"
            )
            media?.let { view ->
                view.translationX = state.nativeMediaTranslationX
                view.translationY = state.nativeMediaTranslationY
                view.clipBounds = state.nativeMediaClipBounds?.let(::Rect)
                view.scaleX = 1f
                view.pivotX = view.width / 2f
                state.nativeMediaWidth?.let { width ->
                    if (view.layoutParams.width != width) {
                        view.layoutParams = view.layoutParams.apply { this.width = width }
                    }
                    view.minimumWidth = 0
                }
            }
            panel.findDescendantByName("subroom_brightness_settings")?.let { view ->
                view.translationX = state.nativeBrightnessTranslationX
                view.translationY = state.nativeBrightnessTranslationY
                view.clipBounds = state.nativeBrightnessClipBounds?.let(::Rect)
            }
        }

        state.clipBaselines.forEach { (group, baseline) ->
            group.clipChildren = baseline.clipChildren
            group.clipToPadding = baseline.clipToPadding
        }
        state.clipBaselines.clear()
        state.nativeLayoutCaptured = false
        state.lastExpansionFraction = 0f
        state.nativeMediaClipBounds = null
        state.nativeMediaWidth = null
        state.nativeBrightnessClipBounds = null
        state.nativeBrightnessHeight = null
        state.brightnessVisualLeft = 0
        state.brightnessVisualTop = 0
        state.tileBaseTranslationY = state.nativeTileTranslationY
        state.mediaBaseTranslationY = state.nativeMediaTranslationY
        state.brightnessBaseTranslationY = state.nativeBrightnessTranslationY
        state.contentBottom = 0
        state.viewportBottom = 0
        XposedHelpers.removeAdditionalInstanceField(panel, "flexunlockQsLayoutSnapshot")
        if (hadOverrides) {
            CoverRuntime.log(SCOPE, "display-1 QS native layout and clipping restored")
        }
    }

    private fun removePreDrawListener(
        observer: ViewTreeObserver?,
        listener: ViewTreeObserver.OnPreDrawListener?
    ) {
        if (observer != null && listener != null && observer.isAlive) {
            observer.removeOnPreDrawListener(listener)
        }
    }

    private fun disposeQsPanel(panel: ViewGroup) {
        val state = qsScrollStates.remove(panel)
        if (state != null) {
            restoreNativeQsLayout(panel, state)
            removePreDrawListener(state.headerPreDrawObserver, state.headerPreDrawListener)
            removePreDrawListener(state.mediaFollowObserver, state.mediaFollowListener)
            state.mediaLayoutView?.let { media ->
                state.mediaLayoutListener?.let(media::removeOnLayoutChangeListener)
            }
            state.headerPreDrawObserver = null
            state.headerPreDrawListener = null
            state.mediaFollowObserver = null
            state.mediaFollowListener = null
            state.mediaLayoutView = null
            state.mediaLayoutListener = null
        }
        translatedBrightnessGestures.remove(panel)
        pendingBrightnessMoves.remove(panel)?.recycle()
        scheduledBrightnessMoves.remove(panel)
        XposedHelpers.removeAdditionalInstanceField(panel, "flexunlockMediaFollowMetrics")
        XposedHelpers.removeAdditionalInstanceField(panel, "flexunlockTileMetrics")
        XposedHelpers.removeAdditionalInstanceField(panel, "flexunlockLastLockState")
    }

    private fun View.coverVisualWidth(): Int {
        return maxOf(width, measuredWidth, kotlin.math.abs(right - left)).takeIf { it > 32 } ?: 0
    }

    private fun View.coverVisualHeight(): Int {
        return maxOf(height, measuredHeight, kotlin.math.abs(bottom - top)).takeIf { it > 24 } ?: 0
    }

    private fun View.coverDrawnHeight(): Int {
        val layoutH = coverVisualHeight()
        val bottoms = ArrayList<Int>(8)
        bottoms.add(layoutH)
        fun walk(view: View, topInRoot: Int, depth: Int) {
            if (depth > 6 || view.visibility != View.VISIBLE) return
            val height = maxOf(
                view.height,
                view.measuredHeight,
                kotlin.math.abs(view.bottom - view.top)
            )
            val margin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            bottoms.add(topInRoot + height + margin)
            view.background?.intrinsicHeight?.takeIf { it > height }?.let { drawn ->
                bottoms.add(topInRoot + drawn)
            }
            runCatching {
                val thumb = XposedHelpers.getObjectField(view, "mThumb")
                    as? android.graphics.drawable.Drawable
                val thumbH = thumb?.bounds?.height()?.takeIf { it > 0 }
                    ?: thumb?.intrinsicHeight
                    ?: 0
                if (thumbH > height) bottoms.add(topInRoot + thumbH)
            }
            val group = view as? ViewGroup ?: return
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                val childTop = topInRoot + child.top + child.translationY.toInt()
                walk(child, childTop, depth + 1)
            }
        }
        walk(this, 0, 0)
        return coverQsDrawnHeightPx(layoutH, bottoms)
    }

    private fun applyMediaMatchBrightness(
        media: View,
        brightness: View?,
        tileMetrics: NativeTileMetrics,
        pagerLeftInPanel: Int,
        currentLeftInPanel: Int,
        brightnessLeftInPanel: Int? = null
    ) {
        val targetWidth = brightness?.coverVisualWidth()?.takeIf { it > 32 }
            ?: tileMetrics.contentWidth.takeIf { it > 32 }
            ?: return
        val targetLeft = brightnessLeftInPanel?.takeIf { brightness != null }
            ?: (pagerLeftInPanel + tileMetrics.contentLeft)
        val deltaX = targetLeft - currentLeftInPanel
        if (kotlin.math.abs(deltaX) > 1) {
            media.translationX += deltaX
        }
        media.scaleX = 1f
        media.pivotX = 0f
        forceViewWidth(media, targetWidth)
    }

    private fun forceViewWidth(view: View, targetWidth: Int, depth: Int = 0) {
        if (depth > 4) return
        val left = view.left
        val top = view.top
        val height = view.coverVisualHeight()
        if (height > 24 && view.right != left + targetWidth) {
            view.layout(left, top, left + targetWidth, top + height)
        }
        val group = view as? ViewGroup ?: return
        for (index in 0 until group.childCount) {
            val child = group.getChildAt(index)
            val matchParent = child.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT
            if (matchParent || depth < 2 || child.width >= view.width - 24) {
                forceViewWidth(child, targetWidth, depth + 1)
            }
        }
    }

    private fun visualInnerBottom(tile: View, layoutLocationY: Int): Int? {
        val group = tile as? ViewGroup ?: return null
        var maxBottom: Int? = null
        fun walk(view: View, depth: Int) {
            if (depth > 4 || view.visibility != View.VISIBLE) return
            if (view !== tile) {
                val location = IntArray(2)
                view.getLocationInWindow(location)
                val height = maxOf(
                    view.height,
                    view.measuredHeight,
                    kotlin.math.abs(view.bottom - view.top)
                )
                if (height in 16 until tile.height - 4) {
                    val bottom = location[1] - layoutLocationY + height
                    maxBottom = maxOf(maxBottom ?: bottom, bottom)
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    walk(view.getChildAt(index), depth + 1)
                }
            }
        }
        walk(group, 0)
        return maxBottom
    }

    private fun viewIsUnder(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun qsChromeHost(panel: View, media: View, brightness: View): View {
        if (viewIsUnder(media, panel) && viewIsUnder(brightness, panel)) return panel
        return panel.rootView ?: panel
    }

    private fun separateOverlappingChrome(
        host: View,
        state: QsScrollState,
        media: View,
        brightness: View,
        brightnessFirst: Boolean,
        mediaUnavailable: Boolean,
        pairGap: Int
    ) {
        if (mediaUnavailable || brightness.visibility != View.VISIBLE) return
        val first = if (brightnessFirst) brightness else media
        val second = if (brightnessFirst) media else brightness
        val push = coverQsOverlapPushPx(
            firstTopPx = offsetTopInAncestor(first, host),
            firstHeightPx = first.coverDrawnHeight(),
            secondTopPx = offsetTopInAncestor(second, host),
            gapPx = pairGap
        )
        if (push <= 2) return
        second.translationY += push
        if (second === media) {
            state.mediaBaseTranslationY += push
        } else {
            state.brightnessBaseTranslationY += push
        }
    }

    private fun offsetTopInAncestor(view: View, ancestor: View): Int {
        var y = 0f
        var current: View? = view
        while (current != null && current !== ancestor) {
            y += current.top + current.translationY
            current = current.parent as? View
        }
        if (current === ancestor) return y.toInt()
        val loc = IntArray(2)
        val anc = IntArray(2)
        view.getLocationInWindow(loc)
        ancestor.getLocationInWindow(anc)
        return loc[1] - anc[1]
    }

    private fun offsetLeftInAncestor(view: View, ancestor: View): Int {
        var x = 0f
        var current: View? = view
        while (current != null && current !== ancestor) {
            x += current.left + current.translationX
            current = current.parent as? View
        }
        if (current === ancestor) return x.toInt()
        val loc = IntArray(2)
        val anc = IntArray(2)
        view.getLocationInWindow(loc)
        ancestor.getLocationInWindow(anc)
        return loc[0] - anc[0]
    }

    private fun alignViewTopInPanel(view: View, panel: View, desiredTopInPanel: Int) {
        val delta = desiredTopInPanel - offsetTopInAncestor(view, panel)
        if (kotlin.math.abs(delta) > 2) {
            view.translationY += delta
        }
    }

    private fun viewBottomInPanel(view: View, panel: View): Int =
        offsetTopInAncestor(view, panel) + view.coverDrawnHeight()

    private fun applyQsScrollOffset(panel: ViewGroup, state: QsScrollState) {
        val tileLayout = panel.findDescendantByName("subscreen_tile_layout") ?: return
        val media = field(panel, "mMediaPanelView") as? View ?: panel.findDescendantByName(
            "subscreen_media_player_root_view"
        ) ?: return
        val brightness = panel.findDescendantByName("subroom_brightness_settings") ?: return
        state.header?.translationY = qsHeaderBaseTranslationY(panel, state) + state.offset +
            state.islandLiftY
        tileLayout.translationY = state.tileBaseTranslationY + state.offset + state.islandLiftY
        if (media.visibility == View.VISIBLE) {
            media.translationY = state.mediaBaseTranslationY + state.offset + state.islandLiftY
        }
        if (brightness.visibility == View.VISIBLE) {
            brightness.translationY = state.brightnessBaseTranslationY + state.offset +
                state.islandLiftY
        }
        applyQsChromeVerticalReveal(panel, state, media, brightness)
    }

    private fun qsHeaderBaseTranslationY(panel: View, state: QsScrollState): Float {
        if (panel.display?.rotation != android.view.Surface.ROTATION_180) {
            return state.headerBaseTranslationY
        }
        return -(panel.rootWindowInsets?.displayCutout?.safeInsetTop ?: 0).toFloat()
    }

    private fun applyQsChromeVerticalReveal(
        panel: ViewGroup,
        state: QsScrollState,
        media: View,
        brightness: View
    ) {
        val host = qsChromeHost(panel, media, brightness)
        val panelTop = offsetTopInAncestor(panel, host)
        val panelHeight = panel.height.takeIf { it > 0 }
        val viewportHeight = when {
            state.viewportBottom > 0 && panelHeight != null ->
                minOf(state.viewportBottom, panelHeight)
            state.viewportBottom > 0 -> state.viewportBottom
            panelHeight != null -> panelHeight
            host.height > 0 -> host.height
            else -> return
        }

        fun clipToPanelViewport(view: View) {
            if (view.visibility != View.VISIBLE) return
            val width = view.coverVisualWidth().takeIf { it > 0 } ?: return
            val height = view.coverDrawnHeight().takeIf { it > 0 } ?: return
            val bounds = coverQsVerticalClipBounds(
                viewTopPx = offsetTopInAncestor(view, host) - panelTop,
                viewHeightPx = height,
                viewportHeightPx = viewportHeight
            )
            view.clipBounds = Rect(0, bounds.topPx, width, bounds.bottomPx)
        }

        clipToPanelViewport(media)
        clipToPanelViewport(brightness)
    }

    private fun minQsScrollOffset(state: QsScrollState): Float =
        minOf(0, state.viewportBottom - state.contentBottom).toFloat()

    private fun View.findDescendantByName(name: String): View? {
        val id = resources.getIdentifier(name, "id", CoverRuntime.SYSTEM_UI_PACKAGE)
        return if (id != 0) findViewById(id) else null
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun scheduleCustomizerEnhancement(
        activity: Activity,
        animateEntry: Boolean = false
    ) {
        val decor = activity.window.decorView
        if (animateEntry) {
            XposedHelpers.setAdditionalInstanceField(
                decor,
                "flexunlockCustomizerAnimateEntry",
                true
            )
        }
        if (
            XposedHelpers.getAdditionalInstanceField(
                decor,
                "flexunlockCustomizerEnhancePosted"
            ) == true
        ) return
        XposedHelpers.setAdditionalInstanceField(
            decor,
            "flexunlockCustomizerEnhancePosted",
            true
        )
        val enhancement = Runnable {
            XposedHelpers.removeAdditionalInstanceField(
                decor,
                "flexunlockCustomizerEnhancePosted"
            )
            XposedHelpers.removeAdditionalInstanceField(
                decor,
                "flexunlockCustomizerEnhanceRunnable"
            )
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            if (NativeCoverKeyguardHooks.blockLockedActivity(activity)) return@Runnable
            runCatching { enhanceCustomizer(activity) }
                .onFailure { unavailable("customizer enhancement failed: ${it.message}") }
            val shouldAnimate = XposedHelpers.removeAdditionalInstanceField(
                decor,
                "flexunlockCustomizerAnimateEntry"
            ) == true
            if (shouldAnimate) applyCustomizerOriginAnimation(activity)
        }
        XposedHelpers.setAdditionalInstanceField(
            decor,
            "flexunlockCustomizerEnhanceRunnable",
            enhancement
        )
        if (decor.isLaidOut && decor.width > 0) {
            enhancement.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).postAtFrontOfQueue(enhancement)
        }
    }

    private fun disposeCustomizer(activity: Activity) {
        val decor = activity.window.decorView
        (XposedHelpers.removeAdditionalInstanceField(
            decor,
            "flexunlockCustomizerEnhanceRunnable"
        ) as? Runnable)?.let(decor::removeCallbacks)
        (XposedHelpers.removeAdditionalInstanceField(
            decor,
            "flexunlockCustomizerOriginRunnable"
        ) as? Runnable)?.let(decor::removeCallbacks)
        decor.animate().cancel()
        decor.alpha = 1f
        decor.scaleX = 1f
        decor.scaleY = 1f
        decor.setOnApplyWindowInsetsListener(null)
        XposedHelpers.removeAdditionalInstanceField(decor, "flexunlockCustomizerEnhancePosted")
        XposedHelpers.removeAdditionalInstanceField(decor, "flexunlockCustomizerAnimateEntry")
        pendingAddedTileSpecs.remove(activity)
        candidateTileCache.remove(activity)
        customizerControllers.remove(activity)?.let { controller ->
            disposeEditorDragBindings(controller)
            disposeActiveGridOverlay(controller)
            editorGridSnapshots.remove(controller)?.bitmap?.let { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
        val panel = activity.findViewById<ViewGroup>(android.R.id.content)
            ?.findViewWithTag<ViewGroup>("flexunlock_qs_candidates")
        val listener = panel?.let {
            XposedHelpers.removeAdditionalInstanceField(
                it,
                "flexunlockViewportFitListener"
            ) as? View.OnLayoutChangeListener
        }
        if (panel != null && listener != null) panel.removeOnLayoutChangeListener(listener)
        val viewport = activity.findViewById<ViewGroup>(android.R.id.content)
            ?.findViewWithTag<ScrollView>("flexunlock_active_tiles_scroll")
        if (viewport != null) {
            (XposedHelpers.removeAdditionalInstanceField(
                viewport,
                "flexunlockViewportFitRunnable"
            ) as? Runnable)?.let(viewport::removeCallbacks)
        }
    }

    private fun applyCustomizerOriginAnimation(activity: Activity) {
        val centerX = activity.intent.getIntExtra(EXTRA_REVEAL_CENTER_X, -1)
        val centerY = activity.intent.getIntExtra(EXTRA_REVEAL_CENTER_Y, -1)
        if (centerX < 0 || centerY < 0) return
        val decor = activity.window.decorView
        val animation = Runnable {
            XposedHelpers.removeAdditionalInstanceField(
                decor,
                "flexunlockCustomizerOriginRunnable"
            )
            if (activity.isFinishing || activity.isDestroyed ||
                decor.width <= 0 || decor.height <= 0
            ) return@Runnable
            decor.animate().cancel()
            decor.pivotX = decor.width / 2f
            decor.pivotY = decor.height / 2f
            decor.scaleX = 1f
            decor.scaleY = 1f
            decor.alpha = 0.9f
            decor.animate()
                .alpha(1f)
                .setDuration(70L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        XposedHelpers.setAdditionalInstanceField(
            decor,
            "flexunlockCustomizerOriginRunnable",
            animation
        )
        decor.post(animation)
    }

    private fun enhanceCustomizer(activity: Activity) {
        val controller = customizerControllers[activity]
            ?: return unavailable("customizer controller instance missing")
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: return unavailable("customizer content missing")
        // 禁用编辑页磁贴区(pager 及其 page)的布局动画:三星 setTiles 全量
        // 重建磁贴时若有布局动画会表现为网格闪烁
        val editorPagerId = activity.resources.getIdentifier(
            "subscreen_customize_qs_paged",
            "id",
            CoverRuntime.SYSTEM_UI_PACKAGE
        )
        if (editorPagerId != 0) {
            activity.findViewById<ViewGroup>(editorPagerId)?.let { pager ->
                pager.layoutTransition = null
                for (index in 0 until pager.childCount) {
                    (pager.getChildAt(index) as? ViewGroup)?.layoutTransition = null
                }
            }
        }
        val marker = "flexunlock_qs_candidates"
        val existingPanel = content.findViewWithTag<ViewGroup>(marker)

        bindActiveTileRemoval(activity, controller)
        val active = subTileSpecs(controller)
        val grid = CoverQsGridConfig.read(activity)
        val canAdd = active.size < grid.capacity
        val candidates = if (canAdd) {
            val catalog = candidateTileCache[activity]
                ?: availableCandidateTiles(activity, controller).also {
                    candidateTileCache[activity] = it
                }
            catalog.filterNot { it.spec in active }
        } else {
            emptyList()
        }
        if (existingPanel != null) {
            // 面板已存在(setTiles 重建磁贴触发):只刷新候选按钮与提示文字,
            // 不重建面板 —— removeView+addView 重建会导致底部候选区闪烁
            refreshCandidateButtons(
                activity, controller, existingPanel, candidates, canAdd, grid
            )
            ensureSpecialChrome(activity, content)
            configureCustomizerBounds(activity, controller, content, existingPanel)
            expandActiveTileGrid(activity, controller, existingPanel)
            return
        }
        val candidateGrid = GridLayout(activity).apply {
            orientation = GridLayout.HORIZONTAL
            rowCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            // 右侧留白:滚动到末尾时最后磁贴能完整显示,避免被右边缘半裁
            setPadding(0, 0, dp(this, 64), 0)
            candidates.forEachIndexed { index, candidate ->
                val button = candidateButton(activity, candidate.label) {
                    val current = subTileSpecs(controller)
                    if (current.size >= CoverQsGridConfig.read(activity).capacity) {
                        enhanceCustomizer(activity)
                        return@candidateButton
                    }
                    pendingAddedTileSpecs[activity] = candidate.spec
                    changeSubTiles(controller, current + candidate.spec)
                }
                addView(
                    button,
                    GridLayout.LayoutParams(
                        GridLayout.spec(index % 2),
                        GridLayout.spec(index / 2)
                    ).apply {
                        width = ViewGroup.LayoutParams.WRAP_CONTENT
                        height = dp(button, 18)
                        marginEnd = dp(button, 4)
                        bottomMargin = dp(button, 2)
                    }
                )
            }
        }
        val candidateScroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                candidateGrid,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val strip = LinearLayout(activity).apply {
            tag = "flexunlock_qs_candidate_strip"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(this, 4), dp(this, 1), dp(this, 4), dp(this, 1))
            addView(
                activity.editorText(
                    if (canAdd) "添加" else "已达 ${grid.capacity} 个磁贴上限",
                    10f
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(activity.window.decorView, 6) }
            )
            addView(
                candidateScroller,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
        }
        val candidatePanel = FrameLayout(activity).apply {
            tag = marker
            clipToOutline = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(content, 14).toFloat()
                setColor(Color.argb(236, 22, 24, 30))
                setStroke(dp(content, 1), Color.argb(72, 255, 255, 255))
            }
            elevation = dp(this, 3).toFloat()
            addView(
                LinearLayout(activity).apply {
                    tag = "flexunlock_qs_column"
                    orientation = LinearLayout.VERTICAL
                    addView(
                        strip,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1f
                        )
                    )
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        XposedHelpers.setAdditionalInstanceField(
            candidatePanel,
            "flexunlockCandidateSpecs",
            candidates.map(CandidateTile::spec)
        )
        val candidatePanelParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(content, 68),
            Gravity.BOTTOM or Gravity.START
        ).apply {
            marginStart = dp(content, 8)
            marginEnd = dp(content, 8)
        }
        content.addView(candidatePanel, candidatePanelParams)
        ensureSpecialChrome(activity, content)
        configureCustomizerBounds(activity, controller, content, candidatePanel)
        expandActiveTileGrid(activity, controller, candidatePanel)
        CoverRuntime.log(
            SCOPE,
            "customizer enhanced active=${active.size} candidates=${candidates.size}"
        )
    }

    /**
     * 候选区面板已存在时只刷新按钮与提示文字,不重建面板容器
     * (重建面板 = removeView + addView 会在删除/新增磁贴时造成底部闪烁)。
     */
    private fun refreshCandidateButtons(
        activity: Activity,
        controller: Any,
        panel: ViewGroup,
        candidates: List<CandidateTile>,
        canAdd: Boolean,
        grid: CoverQsGrid
    ) {
        val strip = panel.findViewWithTag<LinearLayout>("flexunlock_qs_candidate_strip")
            ?: (panel.getChildAt(0) as? LinearLayout)?.takeIf { it.orientation == LinearLayout.HORIZONTAL }
            ?: ((panel.getChildAt(0) as? LinearLayout)?.getChildAt(1) as? LinearLayout)
            ?: return
        (strip.getChildAt(0) as? TextView)?.text =
            if (canAdd) "添加" else "已达 ${grid.capacity} 个磁贴上限"
        val scroller = strip.getChildAt(1) as? HorizontalScrollView ?: return
        val candidateSpecs = candidates.map(CandidateTile::spec)
        if (
            XposedHelpers.getAdditionalInstanceField(panel, "flexunlockCandidateSpecs") ==
            candidateSpecs
        ) {
            return
        }
        XposedHelpers.setAdditionalInstanceField(
            panel,
            "flexunlockCandidateSpecs",
            candidateSpecs
        )
        scroller.removeAllViews()
        val candidateGrid = GridLayout(activity).apply {
            orientation = GridLayout.HORIZONTAL
            rowCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            // 右侧留白:滚动到末尾时最后磁贴能完整显示,避免被右边缘半裁
            setPadding(0, 0, dp(this, 64), 0)
            candidates.forEachIndexed { index, candidate ->
                val button = candidateButton(activity, candidate.label) {
                    val current = subTileSpecs(controller)
                    if (current.size >= CoverQsGridConfig.read(activity).capacity) {
                        enhanceCustomizer(activity)
                        return@candidateButton
                    }
                    pendingAddedTileSpecs[activity] = candidate.spec
                    changeSubTiles(controller, current + candidate.spec)
                }
                addView(
                    button,
                    GridLayout.LayoutParams(
                        GridLayout.spec(index % 2),
                        GridLayout.spec(index / 2)
                    ).apply {
                        width = ViewGroup.LayoutParams.WRAP_CONTENT
                        height = dp(button, 18)
                        marginEnd = dp(button, 4)
                        bottomMargin = dp(button, 2)
                    }
                )
            }
        }
        scroller.addView(
            candidateGrid,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun ensureSpecialChrome(activity: Activity, content: ViewGroup) {
        val panel = content.findViewWithTag<ViewGroup>("flexunlock_qs_candidates") ?: return
        val column = panel.findViewWithTag<LinearLayout>("flexunlock_qs_column")
            ?: (panel.getChildAt(0) as? LinearLayout)?.takeIf {
                it.orientation == LinearLayout.VERTICAL
            } ?: return
        (content.findViewWithTag<View>("flexunlock_qs_special"))?.let { leftover ->
            if (leftover.parent === content) content.removeView(leftover)
        }
        val chrome = CoverQsSpecialConfig.read(activity)
        val barHeight = dp(content, 20)
        val bar = column.findViewWithTag<LinearLayout>("flexunlock_qs_special")
            ?: LinearLayout(activity).apply {
                tag = "flexunlock_qs_special"
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(this, 4), 0, dp(this, 4), 0)
                column.addView(
                    this,
                    0,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        barHeight
                    )
                )
            }
        bar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            barHeight
        )
        bar.removeAllViews()
        fun chip(label: String, action: () -> Unit): TextView =
            activity.editorText(label, 9f).apply {
                gravity = Gravity.CENTER
                setPadding(dp(this, 3), dp(this, 1), dp(this, 3), dp(this, 1))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(content, 8).toFloat()
                    setColor(Color.argb(40, 255, 255, 255))
                }
                setOnClickListener { action() }
            }
        val persistAndRefresh = { next: CoverQsSpecialChrome ->
            CoverQsSpecialConfig.write(activity, next)
            ensureSpecialChrome(activity, content)
            qsScrollStates.keys.toList().forEach { qsPanel ->
                if (qsPanel.isAttachedToWindow) enforceUnlockedQsLayout(qsPanel)
            }
        }
        bar.addView(
            chip(if (chrome.mediaHidden) "音乐·隐" else "音乐") {
                persistAndRefresh(chrome.copy(mediaHidden = !chrome.mediaHidden))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        bar.addView(
            chip(if (chrome.brightnessHidden) "亮度·隐" else "亮度") {
                persistAndRefresh(chrome.copy(brightnessHidden = !chrome.brightnessHidden))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(bar, 3)
            }
        )
        bar.addView(
            chip(if (chrome.brightnessFirst) "亮度在上" else "音乐在上") {
                persistAndRefresh(chrome.copy(brightnessFirst = !chrome.brightnessFirst))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dp(bar, 3)
            }
        )
    }

    private fun configureCustomizerBounds(
        activity: Activity,
        controller: Any,
        content: ViewGroup,
        candidateStrip: View
    ) {
        activity.window.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        val baseView = field(controller, "mView") as? View
        val originalPadding = baseView?.let { view ->
            (XposedHelpers.getAdditionalInstanceField(
                view,
                "flexunlockCustomizerOriginalPadding"
            ) as? IntArray) ?: intArrayOf(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                view.paddingBottom
            ).also {
                XposedHelpers.setAdditionalInstanceField(
                    view,
                    "flexunlockCustomizerOriginalPadding",
                    it
                )
            }
        }
        val applyInsets = { insets: WindowInsets? ->
            val cutout = insets?.displayCutout
            val bottomCutout = cutout?.boundingRects.orEmpty()
                .filter { rect -> rect.bottom >= content.height - dp(content, 96) }
                .maxByOrNull { it.width() }
            val candidateHeight = dp(content, 68)
            (candidateStrip.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                val contentWidth = content.width.takeIf { it > 0 }
                    ?: content.resources.displayMetrics.widthPixels
                params.width = (contentWidth - dp(content, 16)).coerceAtLeast(dp(content, 220))
                params.height = candidateHeight
                params.gravity = Gravity.BOTTOM or Gravity.START
                params.marginStart = dp(content, 8)
                params.marginEnd = dp(content, 8)
                params.bottomMargin = bottomCutout?.let { rect ->
                    val contentHeight = content.height.takeIf { it > 0 }
                        ?: content.resources.displayMetrics.heightPixels
                    (contentHeight - rect.top).coerceAtLeast(0) + dp(content, 8)
                } ?: dp(content, 8)
                candidateStrip.layoutParams = params
            }
            if (baseView != null && originalPadding != null) {
                baseView.setPadding(
                    originalPadding[0],
                    originalPadding[1],
                    originalPadding[2],
                    originalPadding[3]
                )
                baseView.layoutParams = baseView.layoutParams.apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
                baseView.clipToOutline = false
            }
            content.clipChildren = false
            content.clipToPadding = false
        }
        val decor = activity.window.decorView
        decor.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            insets
        }
        applyInsets(decor.rootWindowInsets)
        decor.requestApplyInsets()
    }

    private fun expandActiveTileGrid(
        activity: Activity,
        controller: Any,
        candidatePanel: View
    ) {
        val baseView = field(controller, "mView") as? ViewGroup ?: return
        val tileLayoutId = activity.resources.getIdentifier(
            "subscreen_customize_tile_layout",
            "id",
            CoverRuntime.SYSTEM_UI_PACKAGE
        )
        val hintId = activity.resources.getIdentifier(
            "subscreen_customize_text_container",
            "id",
            CoverRuntime.SYSTEM_UI_PACKAGE
        )
        val pagerId = activity.resources.getIdentifier(
            "subscreen_customize_qs_paged",
            "id",
            CoverRuntime.SYSTEM_UI_PACKAGE
        )
        val tileLayout = if (tileLayoutId != 0) {
            baseView.findViewById<View>(tileLayoutId)
        } else {
            null
        } ?: return
        if (hintId != 0) {
            baseView.findViewById<View>(hintId)?.visibility = View.GONE
        }

        val viewportHeight = dp(tileLayout, 205)
        val scrollTag = "flexunlock_active_tiles_scroll"
        val existingScroll = (tileLayout.parent as? ScrollView)?.takeIf { it.tag == scrollTag }
        val activeScroll = existingScroll ?: run {
                val parent = tileLayout.parent as? ViewGroup ?: return
                val index = parent.indexOfChild(tileLayout)
                val originalParams = tileLayout.layoutParams
                parent.removeView(tileLayout)
                ScrollView(activity).apply {
                    tag = scrollTag
                    isFillViewport = false
                    isVerticalScrollBarEnabled = true
                    scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
                    isScrollbarFadingEnabled = false
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    clipChildren = true
                    clipToPadding = true
                    setVerticalScrollbarPosition(View.SCROLLBAR_POSITION_RIGHT)
                    addView(
                        tileLayout,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                    parent.addView(
                        this,
                        index,
                        originalParams.apply { height = viewportHeight }
                    )
                }
            }

        activeScroll.translationY = 0f
        activeScroll.clipChildren = true
        activeScroll.clipToPadding = true
        // 磁贴网格只有一屏内容,隐藏垂直滚动条,避免添加/删除磁贴时
        // 网格重排闪烁并闪现滑动条
        activeScroll.isVerticalScrollBarEnabled = false
        activeScroll.isHorizontalScrollBarEnabled = false
        activeScroll.isScrollbarFadingEnabled = true
        activeScroll.scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        activeScroll.setPadding(0, 0, 0, 0)
        activeScroll.layoutParams = activeScroll.layoutParams.apply {
            height = viewportHeight
        }
        tileLayout.layoutParams = tileLayout.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        if (pagerId != 0) {
            tileLayout.findViewById<View>(pagerId)?.requestLayout()
        }

        centerCustomizerViewport(activeScroll)
        fitActiveViewportAboveCandidates(activeScroll, candidatePanel)
        scrollToPendingTileAfterLayout(activity, controller, activeScroll)
        baseView.clipChildren = false
        baseView.clipToPadding = false
    }

    private fun fitActiveViewportAboveCandidates(
        viewport: ScrollView,
        candidatePanel: View
    ) {
        fun applyFit() {
            candidatePanel.post {
                if (!viewport.isAttachedToWindow || !candidatePanel.isAttachedToWindow) {
                    return@post
                }
                val viewportLocation = IntArray(2).also(viewport::getLocationOnScreen)
                val candidateLocation = IntArray(2).also(candidatePanel::getLocationOnScreen)
                val screenHeight = viewport.resources.displayMetrics.heightPixels
                val gap = dp(viewport, 6)
                val layoutReady =
                    viewport.width > 0 && viewport.height > 0 &&
                        candidatePanel.width > 0 && candidatePanel.height > 0 &&
                        candidateLocation[1] > 0 &&
                        candidateLocation[1] + candidatePanel.height <= screenHeight &&
                        viewportLocation[1] > 0 &&
                        viewportLocation[1] + gap < candidateLocation[1]
                if (!layoutReady) {
                    val retryCount = (
                        XposedHelpers.getAdditionalInstanceField(
                            viewport,
                            "flexunlockCustomizerViewportFitRetry"
                        ) as? Int
                        ) ?: 0
                    if (retryCount < 4) {
                        XposedHelpers.setAdditionalInstanceField(
                            viewport,
                            "flexunlockCustomizerViewportFitRetry",
                            retryCount + 1
                        )
                        (XposedHelpers.removeAdditionalInstanceField(
                            viewport,
                            "flexunlockViewportFitRunnable"
                        ) as? Runnable)?.let(viewport::removeCallbacks)
                        val retry = Runnable {
                            XposedHelpers.removeAdditionalInstanceField(
                                viewport,
                                "flexunlockViewportFitRunnable"
                            )
                            if (viewport.isAttachedToWindow && candidatePanel.isAttachedToWindow) {
                                applyFit()
                            }
                        }
                        XposedHelpers.setAdditionalInstanceField(
                            viewport,
                            "flexunlockViewportFitRunnable",
                            retry
                        )
                        viewport.postDelayed(retry, 320L)
                    }
                    return@post
                }
                (XposedHelpers.removeAdditionalInstanceField(
                    viewport,
                    "flexunlockViewportFitRunnable"
                ) as? Runnable)?.let(viewport::removeCallbacks)
                XposedHelpers.removeAdditionalInstanceField(
                    viewport,
                    "flexunlockCustomizerViewportFitRetry"
                )
                val closeButtonId = viewport.resources.getIdentifier(
                    "subroom_back_button",
                    "id",
                    CoverRuntime.SYSTEM_UI_PACKAGE
                )
                val closeTop = closeButtonId.takeIf { it != 0 }
                    ?.let { viewport.rootView.findViewById<View>(it) }
                    ?.takeIf { it.isLaidOut }
                    ?.let { IntArray(2).also(it::getLocationOnScreen)[1] }
                val anchorTop = closeTop ?: (
                    XposedHelpers.getAdditionalInstanceField(
                        viewport,
                        "flexunlockCustomizerViewportAnchorTop"
                    ) as? Int
                    ) ?: viewportLocation[1]
                XposedHelpers.setAdditionalInstanceField(
                    viewport,
                    "flexunlockCustomizerViewportAnchorTop",
                    anchorTop
                )
                if (candidateLocation[1] <= anchorTop + gap) return@post
                val topCorrection = anchorTop - viewportLocation[1]
                if (topCorrection != 0) {
                    viewport.translationY += topCorrection
                }
                val targetHeight = candidateLocation[1] - anchorTop - gap
                val pagerId = viewport.resources.getIdentifier(
                    "subscreen_customize_qs_paged",
                    "id",
                    CoverRuntime.SYSTEM_UI_PACKAGE
                )
                val pager = pagerId.takeIf { it != 0 }
                    ?.let { viewport.findViewById<View>(it) }
                    ?: viewport.getChildAt(0)
                XposedHelpers.setAdditionalInstanceField(
                    pager,
                    "flexunlockCustomizerGridHeight",
                    targetHeight
                )
                runCatching { XposedHelpers.setIntField(pager, "mPageHeight", targetHeight) }
                (field(pager, "mPages") as? Iterable<*>)?.firstOrNull()?.let { firstPage ->
                    XposedHelpers.setAdditionalInstanceField(
                        firstPage,
                        "flexunlockCustomizerGridHeight",
                        targetHeight
                    )
                    (firstPage as? View)?.requestLayout()
                }
                pager.layoutParams = pager.layoutParams.apply { height = targetHeight }
                pager.requestLayout()
                viewport.isVerticalScrollBarEnabled = false
                viewport.overScrollMode = View.OVER_SCROLL_NEVER
                if (viewport.layoutParams.height != targetHeight) {
                    viewport.layoutParams = viewport.layoutParams.apply { height = targetHeight }
                    viewport.requestLayout()
                    viewport.post { applyFit() }
                }
                val snapshot = "anchorTop=$anchorTop " +
                    "bottom=${anchorTop + targetHeight} " +
                    "candidateTop=${candidateLocation[1]} gap=$gap " +
                    "contentHeight=${viewport.getChildAt(0)?.measuredHeight ?: 0}"
                if (
                    XposedHelpers.getAdditionalInstanceField(
                        viewport,
                        "flexunlockCustomizerViewportFit"
                    ) != snapshot
                ) {
                    XposedHelpers.setAdditionalInstanceField(
                        viewport,
                        "flexunlockCustomizerViewportFit",
                        snapshot
                    )
                    CoverRuntime.log(SCOPE, "customizer active viewport fitted $snapshot")
                }
            }
        }
        if (
            XposedHelpers.getAdditionalInstanceField(
                candidatePanel,
                "flexunlockViewportFitListener"
            ) == null
        ) {
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyFit() }
            XposedHelpers.setAdditionalInstanceField(
                candidatePanel,
                "flexunlockViewportFitListener",
                listener
            )
            candidatePanel.addOnLayoutChangeListener(listener)
        }
        applyFit()
    }

    private fun centerCustomizerViewport(viewport: ScrollView) {
        viewport.post {
            val parent = viewport.parent as? ViewGroup ?: return@post
            val availableWidth = parent.width - parent.paddingLeft - parent.paddingRight
            val nativeViewportWidth = (
                XposedHelpers.getAdditionalInstanceField(
                    viewport,
                    "flexunlockCustomizerNativeViewportWidth"
                ) as? Int
                )?.takeIf { it > 0 } ?: viewport.width.takeIf { it > 0 }?.also {
                XposedHelpers.setAdditionalInstanceField(
                    viewport,
                    "flexunlockCustomizerNativeViewportWidth",
                    it
                )
            } ?: return@post
            if (availableWidth <= 0) return@post
            val sideMargin = dp(viewport, 9)
            val closeButtonId = viewport.resources.getIdentifier(
                "subroom_back_button",
                "id",
                CoverRuntime.SYSTEM_UI_PACKAGE
            )
            val closeLeft = closeButtonId.takeIf { it != 0 }
                ?.let { viewport.rootView.findViewById<View>(it) }
                ?.takeIf { it.isLaidOut }
                ?.left
            val unobstructedWidth = closeLeft?.minus(sideMargin * 2)
                ?.takeIf { it > 0 } ?: availableWidth
            val targetWidth = nativeViewportWidth
                .coerceAtMost(availableWidth - sideMargin * 2)
                .coerceAtMost(unobstructedWidth)
            val params = viewport.layoutParams
            when (params) {
                is LinearLayout.LayoutParams -> {
                    params.width = targetWidth
                    params.weight = 0f
                    params.gravity = Gravity.START
                    params.marginStart = sideMargin
                    params.marginEnd = 0
                }
                is FrameLayout.LayoutParams -> {
                    params.width = targetWidth
                    params.gravity = Gravity.START
                    params.marginStart = sideMargin
                    params.marginEnd = 0
                }
                else -> params.width = targetWidth
            }
            viewport.layoutParams = params
            viewport.requestLayout()
            CoverRuntime.log(
                SCOPE,
                "customizer active viewport aligned start=$sideMargin width=$targetWidth " +
                    "closeLeft=$closeLeft parent=${parent.width}"
            )
        }
    }

    private fun scrollToPendingTileAfterLayout(
        activity: Activity,
        controller: Any,
        viewport: ScrollView
    ) {
        val pendingSpec = pendingAddedTileSpecs[activity] ?: return
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (pendingAddedTileSpecs[activity] != pendingSpec) {
                    viewport.viewTreeObserver.takeIf { it.isAlive }
                        ?.removeOnGlobalLayoutListener(this)
                    return
                }
                if (!viewport.isAttachedToWindow) {
                    viewport.viewTreeObserver.takeIf { it.isAlive }
                        ?.removeOnGlobalLayoutListener(this)
                    return
                }
                val record = (field(controller, "mSubscreenRecords") as? Iterable<*>)
                    ?.firstOrNull { candidate ->
                        val tile = candidate?.let { field(it, "tile") } ?: return@firstOrNull false
                        runCatching {
                            XposedHelpers.callMethod(tile, "getTileSpec")?.toString() == pendingSpec
                        }.getOrDefault(false)
                    } ?: return
                val tileView = field(record, "tileView") as? View ?: return
                if (!tileView.isLaidOut || tileView.height <= 0 || viewport.height <= 0) return
                val tileBounds = Rect()
                viewport.offsetDescendantRectToMyCoords(
                    tileView,
                    tileBounds.apply { set(0, 0, tileView.width, tileView.height) }
                )
                val absoluteTop = viewport.scrollY + tileBounds.top
                val absoluteBottom = viewport.scrollY + tileBounds.bottom
                val targetScrollY = when {
                    absoluteTop < viewport.scrollY -> absoluteTop
                    absoluteBottom > viewport.scrollY + viewport.height ->
                        absoluteBottom - viewport.height
                    else -> viewport.scrollY
                }.coerceIn(0, maxOf(0, viewport.getChildAt(0).height - viewport.height))
                viewport.smoothScrollTo(0, targetScrollY)
                pendingAddedTileSpecs.remove(activity)
                viewport.viewTreeObserver.takeIf { it.isAlive }
                    ?.removeOnGlobalLayoutListener(this)
                CoverRuntime.log(
                    SCOPE,
                    "customizer positioned added tile spec=$pendingSpec scrollY=$targetScrollY " +
                        "bounds=$tileBounds viewportHeight=${viewport.height}"
                )
            }
        }
        viewport.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun bindActiveTileRemoval(activity: Activity, controller: Any) {
        val records = field(controller, "mSubscreenRecords") as? Iterable<*> ?: return
        records.forEach { record ->
            record ?: return@forEach
            val tile = field(record, "tile") ?: return@forEach
            val spec = runCatching { XposedHelpers.callMethod(tile, "getTileSpec")?.toString() }
                .getOrNull() ?: return@forEach
            val tileView = field(record, "tileView") as? View ?: return@forEach
            val removeTile = {
                val active = subTileSpecs(controller)
                if (active.size > 1) {
                    changeSubTiles(controller, active.filterNot { it == spec })
                }
            }
            bindRemovalClick(tileView, removeTile)
            runCatching {
                (XposedHelpers.getObjectField(tileView, "mIcon") as? View)
                    ?.setOnClickListener {
                        runCatching(removeTile).onFailure { error ->
                            CoverRuntime.log(
                                SCOPE,
                                "customizer tile remove failed: ${error.message}"
                            )
                        }
                    }
            }
            val originalDescription = (
                XposedHelpers.getAdditionalInstanceField(
                    tileView,
                    "flexunlockOriginalCustomizerDescription"
                ) as? String
                ) ?: (tileView.contentDescription?.toString() ?: tileLabel(spec)).also {
                XposedHelpers.setAdditionalInstanceField(
                    tileView,
                    "flexunlockOriginalCustomizerDescription",
                    it
                )
            }
            tileView.contentDescription = "$originalDescription，轻触删除，长按排序"
        }
    }

    private fun bindRemovalClick(view: View, removeTile: () -> Unit) {
        view.setOnClickListener {
            runCatching(removeTile).onFailure {
                CoverRuntime.log(SCOPE, "customizer tile remove failed: ${it.message}")
            }
        }
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            val child = view.getChildAt(index)
            if (child.isClickable || child is ViewGroup) {
                bindRemovalClick(child, removeTile)
            }
        }
    }

    private fun changeSubTiles(controller: Any, specs: List<String>) {
        val host = field(controller, "mHost")
            ?: return unavailable("customizer mHost missing")
        val previousRaw = hostStringList(host, "getSpecs")
        val previous = normalizeCoverTileSpecs(previousRaw)
        val normalized = normalizeCoverTileSpecs(specs)
        if (normalized.isEmpty() || (normalized == previous && previousRaw == previous)) return
        val hostView = field(controller, "mView") as? View
        val capacity = hostView?.context?.let(CoverQsGridConfig::read)?.capacity
            ?: CoverQsGrid.FOUR_BY_FOUR.capacity
        if (normalized.size > capacity && normalized.size > previous.size) {
            CoverRuntime.log(
                SCOPE,
                "subscreen tile add rejected size=${normalized.size} capacity=$capacity"
            )
            return
        }
        // Apply after the click/measure stack unwinds. Calling
        // changeTilesByUser during onClick+onMeasure re-enters setTiles
        // while SubscreenPagedTileLayout is still laying out.
        val oldSpecs = ArrayList(previousRaw)
        val newSpecs = ArrayList(normalized)
        val apply = Runnable {
            runCatching {
                XposedHelpers.callMethod(host, "changeTilesByUser", oldSpecs, newSpecs)
                CoverRuntime.log(
                    SCOPE,
                    "subscreen host transaction host=${host.javaClass.name} " +
                        "old=$oldSpecs new=$newSpecs"
                )
            }.onFailure { unavailable("host tile transaction failed: ${it.message}") }
        }
        if (hostView?.isAttachedToWindow == true) {
            hostView.post(apply)
        } else {
            apply.run()
        }
    }

    private fun subTileSpecs(controller: Any): List<String> {
        val host = field(controller, "mHost") ?: return emptyList()
        return normalizeCoverTileSpecs(hostStringList(host, "getSpecs"))
    }

    private fun migrateLegacyTileSpecs(controller: Any) {
        val context = listOf("mView", "mQSPanel", "mSubScreenQsWindowView")
            .firstNotNullOfOrNull { field(controller, it) as? View }
            ?.context
        if (context != null) {
            val persisted = tileSpecs(
                Settings.Secure.getString(context.contentResolver, "sysui_sub_qs_tiles")
            )
            val normalizedPersisted = normalizeCoverTileSpecs(persisted)
            if (persisted != normalizedPersisted) {
                Settings.Secure.putString(
                    context.contentResolver,
                    "sysui_sub_qs_tiles",
                    normalizedPersisted.joinToString(",")
                )
                CoverRuntime.log(SCOPE, "legacy subscreen tile specs migrated=$normalizedPersisted")
            }
        }
        val host = field(controller, "mHost") ?: return
        val current = hostStringList(host, "getSpecs")
        val normalized = normalizeCoverTileSpecs(current)
        if (current.isEmpty() || current == normalized || !migratedLegacyTileHosts.add(host)) return
        changeSubTiles(controller, normalized)
    }

    private fun availableCandidateTiles(
        activity: Activity,
        controller: Any
    ): List<CandidateTile> {
        val host = field(controller, "mHost") ?: return emptyList()
        val declared = (
            hostStringList(host, "getDefaultTileList") +
                mainTileSpecs(activity) +
                listOf("RotationLock")
            ).let(::normalizeCoverTileSpecs)
        return declared.mapNotNull { spec -> inspectCandidateTile(activity, host, spec) }
    }

    private fun hostStringList(host: Any, method: String): List<String> = runCatching {
        (XposedHelpers.callMethod(host, method) as? Iterable<*>)
            ?.mapNotNull { it?.toString() }
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            .orEmpty()
    }.onFailure { unavailable("host $method unavailable: ${it.message}") }
        .getOrDefault(emptyList())

    private fun inspectCandidateTile(activity: Activity, host: Any, spec: String): CandidateTile? {
        val tile = runCatching { XposedHelpers.callMethod(host, "createTile", spec) }
            .onFailure { unavailable("candidate $spec create failed: ${it.message}") }
            .getOrNull() ?: return null
        return try {
            val available = runCatching {
                XposedHelpers.callMethod(tile, "isAvailable") as? Boolean
            }.getOrNull() != false
            if (!available) return null
            val nativeLabel = runCatching {
                XposedHelpers.callMethod(tile, "getTileLabel")?.toString()
            }.getOrNull()?.trim().orEmpty()
            val label = nativeLabel.takeUnless(::isInternalTileLabel)
                ?: customTileServiceLabel(activity, spec)
                ?: tileLabel(spec)
            CandidateTile(spec, label)
        } finally {
            runCatching { XposedHelpers.callMethod(tile, "destroy") }
        }
    }

    private fun isInternalTileLabel(label: String): Boolean =
        label.isBlank() ||
            label.startsWith('.') ||
            (label.contains('.') && (
                label.endsWith("Tile") ||
                    label.endsWith("TileService") ||
                    label.endsWith("QSTileService")
                ))

    private fun customTileServiceLabel(activity: Activity, spec: String): String? {
        if (!spec.startsWith("custom(") || !spec.endsWith(')')) return null
        val component = ComponentName.unflattenFromString(spec.removePrefix("custom(").dropLast(1))
            ?: return null
        return runCatching {
            val serviceInfo = activity.packageManager.getServiceInfo(component, 0)
            serviceInfo.loadLabel(activity.packageManager)?.toString()?.trim()
        }.getOrNull()?.takeIf(String::isNotEmpty)
    }

    private fun mainTileSpecs(activity: Activity): List<String> {
        val secureSpecs = tileSpecs(
            Settings.Secure.getString(activity.contentResolver, "sysui_qs_tiles")
        )
        val resourceSpecs = listOf(
            "quick_settings_tiles_stock",
            "quick_settings_tiles_default",
            "quick_settings_tiles_retail_mode"
        ).flatMap { name ->
            val id = activity.resources.getIdentifier(
                name,
                "string",
                CoverRuntime.SYSTEM_UI_PACKAGE
            )
            if (id == 0) emptyList() else tileSpecs(
                runCatching { activity.resources.getString(id) }.getOrNull()
            )
        }
        return normalizeCoverTileSpecs(secureSpecs + resourceSpecs)
    }

    private fun tileSpecs(value: String?): List<String> =
        value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).distinct()

    private fun candidateButton(activity: Activity, label: String, onClick: () -> Unit): TextView =
        TextView(activity).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 10f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minHeight = 0
            setPadding(dp(this, 6), dp(this, 2), dp(this, 6), dp(this, 2))
            background = pillBackground()
            setOnClickListener { onClick() }
        }.also { view ->
            view.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(view, 7) }
        }

    private fun Activity.editorText(value: String, size: Float): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(205, 210, 220))
    }

    private fun tileLabel(spec: String): String = when (spec) {
        "RotationLock" -> "自动旋转"
        "Wifi" -> "Wi-Fi"
        "SoundMode" -> "声音"
        "Bluetooth" -> "蓝牙"
        "AirplaneMode" -> "飞行模式"
        "Flashlight" -> "手电筒"
        "MobileData" -> "移动数据"
        "Dnd" -> "勿扰"
        "Hotspot" -> "热点"
        "Location" -> "位置"
        "BatteryMode", "PowerSaving", "battery" -> "省电"
        else -> spec.substringAfterLast('/').substringBefore(')')
    }

    private fun pillBackground() = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 32f
        setColor(Color.rgb(55, 59, 70))
        setStroke(1, Color.argb(90, 255, 255, 255))
    }

    private fun field(instance: Any, name: String): Any? = runCatching {
        XposedHelpers.getObjectField(instance, name)
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun mutableObjectListField(instance: Any, name: String): MutableList<Any?>? =
        field(instance, name) as? MutableList<Any?>

    private fun openCustomizer(source: View, controller: Any) {
        if (NativeCoverKeyguardHooks.isShowing()) {
            NativeCoverKeyguardHooks.requestCoverBouncer("QS editor tap")
            return
        }
        if (pendingCustomizerLaunches.containsKey(controller)) return
        val coverDisplayId = CoverDisplayResolver.currentId()
            ?: return unavailable("customizer launch skipped: cover display unresolved")
        if (!CoverDisplayResolver.matches(CoverRuntime.displayIdOf(source.context))) {
            return unavailable("customizer launch skipped: source is not on cover display")
        }
        val sourceLocation = IntArray(2).also(source::getLocationOnScreen)
        val revealCenterX = sourceLocation[0] + source.width / 2
        val revealCenterY = sourceLocation[1] + source.height / 2
        val options = ActivityOptions.makeCustomAnimation(source.context, 0, 0).apply {
            launchDisplayId = coverDisplayId
        }
        val intent = Intent().apply {
            component = ComponentName(CoverRuntime.SYSTEM_UI_PACKAGE, CUSTOMIZER_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_REVEAL_CENTER_X, revealCenterX)
            putExtra(EXTRA_REVEAL_CENTER_Y, revealCenterY)
        }
        pendingCustomizerLaunches[controller] = PendingCustomizerLaunch(
            source,
            intent,
            options.toBundle(),
            coverDisplayId
        )
        runCatching {
            XposedHelpers.callMethod(controller, "collapsePanel")
            completeCustomizerLaunchIfCollapsed(controller)
            CoverRuntime.log(SCOPE, "QS collapse requested before native customizer launch")
        }.onFailure {
            pendingCustomizerLaunches.remove(controller)
            unavailable("QS collapse before customizer failed: ${it.message}")
        }
    }

    private fun completeCustomizerLaunchIfCollapsed(controller: Any) {
        val pending = pendingCustomizerLaunches[controller] ?: return
        val expandedFraction = (field(controller, "mExpandedFraction") as? Number)?.toFloat()
            ?: return
        if (expandedFraction > 0.001f) return
        pendingCustomizerLaunches.remove(controller)
        if (
            !pending.source.isAttachedToWindow ||
            NativeCoverKeyguardHooks.isShowing() ||
            CoverDisplayResolver.currentId() != pending.displayId ||
            CoverRuntime.displayIdOf(pending.source.context) != pending.displayId
        ) {
            unavailable("customizer launch cancelled after QS collapse")
            return
        }
        runCatching {
            pending.source.context.startActivity(pending.intent, pending.options)
            CoverRuntime.log(
                SCOPE,
                "native display-1 QS customizer launched after authoritative collapse"
            )
        }.onFailure { unavailable("customizer launch failed: ${it.message}") }
    }

    private fun dp(view: View, value: Int): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()

    private fun TextView.dp(value: Int): Int = dp(this, value)

    private fun unavailable(reason: String?) {
        CoverRuntime.log(SCOPE, "unavailable: ${reason ?: "unknown"}")
    }
}
