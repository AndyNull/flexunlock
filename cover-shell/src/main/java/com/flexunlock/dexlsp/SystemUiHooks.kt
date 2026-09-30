package com.flexunlock.dexlsp

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import android.view.View
import android.widget.ImageView
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun routeFullQsVolumeToDefaultDisplay(
    stableFullQs: Boolean,
    defaultDisplayIsCover: Boolean
): Boolean = stableFullQs && defaultDisplayIsCover

internal fun useCoverFlashlightMainPath(
    coverSessionEligible: Boolean,
    folderOpened: Boolean
): Boolean = coverSessionEligible && !folderOpened

internal fun useSystemUiGlobalActionsContent(
    coverSessionEligible: Boolean,
    defaultDisplayIsCover: Boolean
): Boolean = coverSessionEligible && defaultDisplayIsCover

internal fun dismissCoverGlobalActionsImmediately(
    coverSessionEligible: Boolean,
    interactive: Boolean
): Boolean = coverSessionEligible && !interactive

internal fun resolveFullQsWallpaperWhich(stableFull: Boolean, which: Int): Int =
    if (stableFull && which and 60 == 0 && which and 3 != 0) which or 4 else which

internal object SystemUiHooks {
    private const val SCOPE = "SystemUI"
    private const val SAMSUNG_GESTURE_MODE = 3
    private const val FLASHLIGHT_MAIN_PATH_SETTLE_MS = 250L
    private val fullQsVolumeRouted = AtomicBoolean(false)
    private val globalActionsRouted = AtomicBoolean(false)
    private val flashlightResetGeneration = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val configuredNavigationBars: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    private val initializedNavigationBars: MutableSet<Any> = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap())
    )
    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val classLoader = lpparam.classLoader
        installCoverScreenDecorationMetrics(classLoader)
        installCoverGlobalActionsContent(classLoader)
        installInitialNavigationMode(classLoader)
        installNavigationModeChanges(classLoader)
        installFullQsWallpaperMode()
        installFullQsFlashlight(classLoader)
        installFullQsVolumeDisplay(classLoader)
        NativeCoverStatusBarHooks.install(classLoader)
        NativeCoverNotificationPageHooks.install(classLoader)
        NativeCoverKeyguardHooks.install(classLoader)
        NativeCoverQuickSettingsEditorHooks.install(classLoader)
        NativeCoverNotificationPopupHooks.install(classLoader)
    }

    private fun installFullQsWallpaperMode() {
        val methods = WallpaperManager::class.java.declaredMethods.filter { method ->
            method.name == "getModeEnsuredWhich" &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        methods.forEach { method ->
            method.isAccessible = true
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = getFieldOrNull(param.thisObject, "mContext") as? Context ?: return
                    val which = (param.args[0] as Number).toInt()
                    val resolved = resolveFullQsWallpaperWhich(
                        CoverQsModeConfig.readTransaction(context).isStableFull,
                        which
                    )
                    if (resolved != which) param.result = resolved
                }
            })
        }
        CoverRuntime.log(SCOPE, "full QS wallpaper mode installed methods=${methods.size}")
    }

    private fun installCoverGlobalActionsContent(classLoader: ClassLoader) {
        val factoryClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.globalactions.presentation.view.ContentViewFactory",
            classLoader
        ) ?: return unavailable("cover global actions content", "ContentViewFactory missing")
        val contentClass = XposedHelpers.findClassIfExists(
            "com.samsung.android.globalactions.presentation.view.GlobalActionsContentView",
            classLoader
        ) ?: return unavailable("cover global actions content", "GlobalActionsContentView missing")

        XposedBridge.hookAllMethods(factoryClass, "createContentView", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val factory = param.thisObject ?: return
                val context = getFieldOrNull(factory, "mContext") as? Context ?: return
                if (!useSystemUiGlobalActionsContent(
                        CoverRuntime.isBuiltInCoverSessionEligible(),
                        CoverRuntime.isCoverDisplay(Display.DEFAULT_DISPLAY)
                    )
                ) return

                param.result = XposedHelpers.newInstance(
                    contentClass,
                    context,
                    getFieldOrNull(factory, "mParentView"),
                    getFieldOrNull(factory, "mFeatureFactory"),
                    getFieldOrNull(factory, "mConditionChecker"),
                    getFieldOrNull(factory, "mWindowManagerUtil"),
                    getFieldOrNull(factory, "mResourceFactory"),
                    getFieldOrNull(factory, "mLogWrapper"),
                    param.args[0],
                    false
                )
                if (globalActionsRouted.compareAndSet(false, true)) {
                    CoverRuntime.log(SCOPE, "native global actions content routed to display 0")
                }
            }
        })
        installImmediateGlobalActionsSleepDismiss(classLoader)
        CoverRuntime.log(SCOPE, "native global actions content route installed")
    }

    private fun installImmediateGlobalActionsSleepDismiss(classLoader: ClassLoader) {
        val dialogClass = XposedHelpers.findClassIfExists(
            "com.samsung.android.globalactions.presentation.view.SamsungGlobalActionsDialogBase",
            classLoader
        ) ?: return unavailable("global actions sleep dismiss", "dialog base missing")
        XposedBridge.hookAllMethods(dialogClass, "dismissWithAnimation", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = getFieldOrNull(param.thisObject, "mContext") as? Context ?: return
                val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive ?: return
                if (!dismissCoverGlobalActionsImmediately(
                        CoverRuntime.isBuiltInCoverSessionEligible(),
                        interactive
                    )
                ) return
                XposedHelpers.callMethod(param.thisObject, "dismiss")
                param.result = null
                CoverRuntime.log(SCOPE, "global actions dismissed immediately for cover sleep")
            }
        })
    }

    private fun installCoverScreenDecorationMetrics(classLoader: ClassLoader) {
        val providerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.decor.CoverRoundedCornerDecorProviderImpl",
            classLoader
        ) ?: return unavailable("cover screen decoration metrics", "cover provider missing")

        XposedBridge.hookAllMethods(
            providerClass,
            "onReloadResAndMeasure",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.args.firstOrNull() as? ImageView ?: return
                    val rotation = (param.args.getOrNull(2) as? Number)?.toInt() ?: 0
                    view.post { resizeCoverRoundedMask(view, rotation) }
                }
            }
        )
        XposedBridge.hookAllMethods(
            providerClass,
            "inflateView",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.result as? ImageView ?: return
                    val rotation = (param.args.getOrNull(2) as? Number)?.toInt() ?: 0
                    view.post { resizeCoverRoundedMask(view, rotation) }
                }
            }
        )
        CoverRuntime.log(SCOPE, "cover screen decoration metrics hook installed")
    }

    private fun resizeCoverRoundedMask(view: ImageView, rotation: Int) {
        installCoverMaskLayoutListener(view)
        val drawable = view.drawable ?: return
        val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: return
        val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: return
        val targetWidth = view.width.takeIf { it > 0 } ?: return
        val targetHeight = view.height.takeIf { it > 0 } ?: return
        val matrix = Matrix()
        when (rotation) {
            1 -> {
                matrix.postRotate(270f)
                matrix.postTranslate(0f, sourceWidth.toFloat())
            }
            2 -> {
                matrix.postRotate(180f)
                matrix.postTranslate(sourceWidth.toFloat(), sourceHeight.toFloat())
            }
            3 -> {
                matrix.postRotate(90f)
                matrix.postTranslate(sourceHeight.toFloat(), 0f)
            }
        }
        val rotatedWidth = if (rotation == 1 || rotation == 3) sourceHeight else sourceWidth
        val rotatedHeight = if (rotation == 1 || rotation == 3) sourceWidth else sourceHeight
        val halfMode = CoverDisplayConfig.readHalfMode(view.context)
        if (halfMode) {
            if (rotation == 2) matrix.postTranslate(0f, (targetHeight - rotatedHeight).toFloat())
            if (rotation == 3) matrix.postTranslate((targetWidth - rotatedWidth).toFloat(), 0f)
            val displaySize = Point(targetWidth, targetHeight).also { view.display?.getRealSize(it) }
            val activeWidth = displaySize.x.coerceIn(1, targetWidth)
            val activeHeight = displaySize.y.coerceIn(1, targetHeight)
            val activeLeft = if (rotation == 3) targetWidth - activeWidth else 0
            val activeTop = if (rotation == 2) targetHeight - activeHeight else 0
            val radius = view.rootWindowInsets?.getRoundedCorner(0)?.radius
                ?: (minOf(targetWidth, targetHeight) * 0.065f).toInt()
            view.background = HalfModeCornerMaskDrawable(
                radius.toFloat(),
                view.imageTintList?.defaultColor ?: Color.BLACK,
                activeLeft,
                activeTop,
                activeLeft + activeWidth,
                activeTop + activeHeight
            )
        } else {
            matrix.postScale(
                targetWidth.toFloat() / rotatedWidth,
                targetHeight.toFloat() / rotatedHeight
            )
            view.background = null
        }
        view.imageMatrix = matrix
        val geometry = "$targetWidth x $targetHeight@$rotation half=$halfMode"
        if (XposedHelpers.getAdditionalInstanceField(view, "flexunlockCoverMaskGeometry") != geometry) {
            XposedHelpers.setAdditionalInstanceField(view, "flexunlockCoverMaskGeometry", geometry)
            CoverRuntime.log(SCOPE, "cover rounded mask resized $geometry")
        }
    }

    private fun installCoverMaskLayoutListener(view: ImageView) {
        if (XposedHelpers.getAdditionalInstanceField(view, "flexunlockCoverMaskLayout") != null) return
        val listener = View.OnLayoutChangeListener { changed, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left == oldRight - oldLeft && bottom - top == oldBottom - oldTop) return@OnLayoutChangeListener
            changed.post {
                resizeCoverRoundedMask(
                    changed as ImageView,
                    changed.display?.rotation ?: 0
                )
            }
        }
        XposedHelpers.setAdditionalInstanceField(view, "flexunlockCoverMaskLayout", listener)
        view.addOnLayoutChangeListener(listener)
    }

    private class HalfModeCornerMaskDrawable(
        private val radius: Float,
        color: Int,
        private val activeLeft: Int,
        private val activeTop: Int,
        private val activeRight: Int,
        private val activeBottom: Int
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }

        override fun draw(canvas: Canvas) {
            val area = bounds
            path.reset()
            path.addRect(
                area.left.toFloat(),
                area.top.toFloat(),
                area.right.toFloat(),
                area.bottom.toFloat(),
                Path.Direction.CW
            )
            path.addRoundRect(
                activeLeft.toFloat(),
                activeTop.toFloat(),
                activeRight.toFloat(),
                activeBottom.toFloat(),
                radius,
                radius,
                Path.Direction.CW
            )
            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun installFullQsVolumeDisplay(classLoader: ClassLoader) {
        val wrapperClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.volume.util.DisplayManagerWrapper",
            classLoader
        ) ?: return unavailable("full QS volume display", "DisplayManagerWrapper missing")
        XposedBridge.hookAllMethods(wrapperClass, "getFrontSubDisplay", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val context = getFieldOrNull(param.thisObject, "context") as? Context ?: return
                if (!routeFullQsVolumeToDefaultDisplay(
                        CoverQsModeConfig.readTransaction(context).isStableFull,
                        CoverRuntime.isCoverDisplay(Display.DEFAULT_DISPLAY)
                    )
                ) return
                val display = context.getSystemService(DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
                param.result = display
                if (fullQsVolumeRouted.compareAndSet(false, true)) {
                    CoverRuntime.log(SCOPE, "full QS volume display routed to display 0")
                }
            }
        })
        CoverRuntime.log(SCOPE, "full QS volume display route installed")
    }

    private fun installFullQsFlashlight(classLoader: ClassLoader) {
        val tileClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.qs.tiles.FlashlightTile",
            classLoader
        ) ?: return unavailable("full QS flashlight", "FlashlightTile missing")
        XposedBridge.hookAllMethods(tileClass, "handleClick", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val tile = param.thisObject ?: return
                val lifecycle = getFieldOrNull(tile, "mDisplayLifecycle") ?: return
                if (!useCoverFlashlightMainPath(
                        CoverRuntime.isBuiltInCoverSessionEligible(),
                        XposedHelpers.getBooleanField(lifecycle, "mIsFolderOpened")
                    )
                ) return
                XposedHelpers.setBooleanField(lifecycle, "mIsFolderOpened", true)
                param.setObjectExtra("flexunlockCoverFlashlight", lifecycle)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val lifecycle = param.getObjectExtra("flexunlockCoverFlashlight") ?: return
                val generation = flashlightResetGeneration.incrementAndGet()
                mainHandler.postDelayed({
                    if (
                        flashlightResetGeneration.get() == generation &&
                        CoverRuntime.isBuiltInCoverSessionEligible()
                    ) {
                        XposedHelpers.setBooleanField(lifecycle, "mIsFolderOpened", false)
                    }
                }, FLASHLIGHT_MAIN_PATH_SETTLE_MS)
            }
        })
        CoverRuntime.log(SCOPE, "cover QS flashlight branch installed")
    }

    private fun installInitialNavigationMode(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.navigationbar.NavigationModeController",
            classLoader
        ) ?: return unavailable("initial navigation mode", "NavigationModeController missing")

        runCatching {
            val methods = controllerClass.declaredMethods.filter { method ->
                method.name == "addListener" && method.parameterCount == 1
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isCoverUiSessionEligible()) return
                        val listener = param.args.firstOrNull() ?: return
                        val navigationBar = navigationBarOf(listener) ?: return
                        if (!isCoverNavigationBar(navigationBar, requireInitializedId = false)) return

                        param.result = SAMSUNG_GESTURE_MODE
                        dispatchNativeModeAfterInit(listener, navigationBar)
                        logModeOnce(navigationBar, "initial")
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "display-1 initial native Samsung gesture mode installed methods=${methods.size}"
            )
        }.onFailure { unavailable("initial navigation mode", it.message) }
    }

    private fun installNavigationModeChanges(classLoader: ClassLoader) {
        val listenerClass = XposedHelpers.findClassIfExists(
            "com.android.systemui.navigationbar.views.NavigationBar\$12",
            classLoader
        ) ?: return unavailable("navigation mode changes", "NavigationBar\$12 missing")

        runCatching {
            XposedHelpers.findAndHookMethod(
                listenerClass,
                "onNavigationModeChanged",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!CoverRuntime.isCoverUiSessionEligible()) return
                        val navigationBar = navigationBarOf(param.thisObject) ?: return
                        if (!isCoverNavigationBar(navigationBar, requireInitializedId = true)) return

                        param.args[0] = SAMSUNG_GESTURE_MODE
                        logModeOnce(navigationBar, "change")
                    }
                }
            )
            CoverRuntime.log(SCOPE, "display-1 dynamic native Samsung gesture mode installed")
        }.onFailure { unavailable("navigation mode changes", it.message) }
    }

    private fun dispatchNativeModeAfterInit(listener: Any, navigationBar: Any) {
        if (!initializedNavigationBars.add(navigationBar)) return
        val view = getFieldOrNull(navigationBar, "mView") as? View ?: return
        view.post {
            if (!CoverRuntime.isCoverUiSessionEligible()) return@post
            if (!isCoverNavigationBar(navigationBar, requireInitializedId = true)) return@post
            runCatching {
                XposedHelpers.callMethod(
                    listener,
                    "onNavigationModeChanged",
                    SAMSUNG_GESTURE_MODE
                )
            }.onFailure {
                initializedNavigationBars.remove(navigationBar)
                unavailable("initial native mode dispatch", it.message)
            }
        }
    }

    private fun navigationBarOf(listener: Any): Any? {
        var type: Class<*>? = listener.javaClass
        while (type != null && type != Any::class.java) {
            type.declaredFields.forEach { field ->
                val value = field.readObject(listener) ?: return@forEach
                if (value.javaClass.name == NAVIGATION_BAR_CLASS) return value
            }
            type = type.superclass
        }
        return null
    }

    private fun isCoverNavigationBar(
        navigationBar: Any,
        requireInitializedId: Boolean
    ): Boolean {
        if (navigationBar.javaClass.name != NAVIGATION_BAR_CLASS) return false
        val context = getFieldOrNull(navigationBar, "mContext") as? Context ?: return false
        if (!CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context))) return false
        if (!requireInitializedId) return true
        return CoverRuntime.isCoverDisplay(getIntFieldOrNull(navigationBar, "mDisplayId"))
    }

    private fun logModeOnce(navigationBar: Any, phase: String) {
        if (!configuredNavigationBars.add(navigationBar)) return
        CoverRuntime.log(
            SCOPE,
            "NavigationBar1 uses native Samsung mode=$SAMSUNG_GESTURE_MODE phase=$phase"
        )
    }

    private fun Field.readObject(instance: Any): Any? = runCatching {
        isAccessible = true
        get(instance)
    }.getOrNull()

    private fun getFieldOrNull(instance: Any, field: String): Any? = runCatching {
        XposedHelpers.getObjectField(instance, field)
    }.getOrNull()

    private fun getIntFieldOrNull(instance: Any, field: String): Int? = runCatching {
        XposedHelpers.getIntField(instance, field)
    }.getOrNull()

    private fun unavailable(feature: String, reason: String?) {
        CoverRuntime.log(SCOPE, "$feature unavailable: ${reason ?: "unknown"}")
    }

    private const val NAVIGATION_BAR_CLASS =
        "com.android.systemui.navigationbar.views.NavigationBar"
}
