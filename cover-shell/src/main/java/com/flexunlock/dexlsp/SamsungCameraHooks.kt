package com.flexunlock.dexlsp

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.flexunlock.dexlsp.config.CoverCameraMode
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlin.math.roundToInt

internal data class CameraControlRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

internal data class CameraControlOffsets(
    val modeX: Int,
    val modeY: Int,
    val zoomX: Int,
    val zoomY: Int
)

internal data class CameraSurfaceTransform(
    val positionX: Int,
    val positionY: Int,
    val scaleX: Float,
    val scaleY: Float
)

internal fun cameraSurfaceFlipTransform(
    left: Int,
    top: Int,
    scaleX: Float,
    scaleY: Float,
    destinationWidth: Int,
    destinationHeight: Int
): CameraSurfaceTransform? {
    if (
        destinationWidth <= 0 ||
        destinationHeight <= 0 ||
        scaleX == 0f ||
        scaleY == 0f
    ) return null
    return CameraSurfaceTransform(
        positionX = left + destinationWidth,
        positionY = top + destinationHeight,
        scaleX = -scaleX,
        scaleY = -scaleY
    )
}

internal fun cameraBufferTransform180(nativeTransform: Int): Int =
    if (nativeTransform in 0..7) nativeTransform xor 3 else nativeTransform

internal fun cameraPreviewTransform(nativeTransform: Int, rootRotation: Int, active: Boolean): Int {
    return if (cameraPreviewNeedsCompensation(rootRotation, active)) {
        cameraBufferTransform180(nativeTransform)
    } else {
        nativeTransform
    }
}

internal fun cameraPreviewNeedsCompensation(rootRotation: Int, active: Boolean): Boolean {
    return active
}

internal fun cameraQuickShotDisplayId(actualDisplayId: Int, originalCoverCamera: Boolean): Int =
    if (originalCoverCamera) 1 else actualDisplayId

internal fun cameraUnionRect(rects: List<CameraControlRect>): CameraControlRect? =
    rects.takeIf(List<CameraControlRect>::isNotEmpty)?.let {
        CameraControlRect(
            it.minOf(CameraControlRect::left),
            it.minOf(CameraControlRect::top),
            it.maxOf(CameraControlRect::right),
            it.maxOf(CameraControlRect::bottom)
        )
    }

internal fun cameraControlOffsets(
    bottom: CameraControlRect,
    mode: CameraControlRect,
    zoom: CameraControlRect?,
    windowWidth: Int,
    windowHeight: Int,
    gapPx: Int
): CameraControlOffsets {
    if (bottom.height > bottom.width) {
        val bottomAtEnd = bottom.centerX >= windowWidth / 2
        val modeX = if (bottomAtEnd) {
            minOf(0, bottom.left - gapPx - mode.right)
        } else {
            maxOf(0, bottom.right + gapPx - mode.left)
        }
        val zoomX = zoom?.let {
            if (bottomAtEnd) {
                minOf(0, mode.left + modeX - gapPx - it.right)
            } else {
                maxOf(0, mode.right + modeX + gapPx - it.left)
            }
        } ?: 0
        return CameraControlOffsets(modeX, 0, zoomX, 0)
    }

    val bottomAtEnd = bottom.centerY >= windowHeight / 2
    val modeY = if (bottomAtEnd) {
        minOf(0, bottom.top - gapPx - mode.bottom)
    } else {
        maxOf(0, bottom.bottom + gapPx - mode.top)
    }
    val zoomY = zoom?.let {
        if (bottomAtEnd) {
            minOf(0, mode.top + modeY - gapPx - it.bottom)
        } else {
            maxOf(0, mode.bottom + modeY + gapPx - it.top)
        }
    } ?: 0
    return CameraControlOffsets(0, modeY, 0, zoomY)
}

internal fun cameraLocalOffset(
    screenX: Int,
    screenY: Int,
    rootRotation: Int
): Pair<Float, Float> = when (((rootRotation % 360) + 360) % 360) {
    90 -> screenY.toFloat() to -screenX.toFloat()
    180 -> -screenX.toFloat() to -screenY.toFloat()
    270 -> -screenY.toFloat() to screenX.toFloat()
    else -> screenX.toFloat() to screenY.toFloat()
}

internal fun cameraBoundedOffset(
    rect: CameraControlRect,
    windowWidth: Int,
    windowHeight: Int,
    x: Int,
    y: Int
): Pair<Int, Int> {
    val minX = -rect.left
    val maxX = windowWidth - rect.right
    val minY = -rect.top
    val maxY = windowHeight - rect.bottom
    return (if (minX <= maxX) x.coerceIn(minX, maxX) else 0) to
        (if (minY <= maxY) y.coerceIn(minY, maxY) else 0)
}

internal object SamsungCameraHooks {
    private const val SCOPE = "SamsungCamera"
    private const val CAMERA_ACTIVITY = "com.sec.android.app.camera.Camera"
    private const val MAIN_LAYOUT = "com.sec.android.app.camera.MainLayout"
    private const val BOUND_FIELD = "flexunlockCameraLayoutBound"
    private const val PREVIEW_FLIP_FIELD = "flexunlockCameraPreviewFlip"
    private const val PREVIEW_TRANSFORM_LOG_FIELD = "flexunlockCameraPreviewTransformLog"
    private const val PREVIEW_MATRIX_LOG_FIELD = "flexunlockCameraPreviewMatrixLog"
    private const val QUICK_SHOT_ACTIVITY_FIELD = "flexunlockQuickShotActivity"
    private const val BASE_TRANSLATION_X_FIELD = "flexunlockCameraBaseTranslationX"
    private const val BASE_TRANSLATION_Y_FIELD = "flexunlockCameraBaseTranslationY"
    private const val TARGET_TRANSLATION_X_FIELD = "flexunlockCameraTargetTranslationX"
    private const val TARGET_TRANSLATION_Y_FIELD = "flexunlockCameraTargetTranslationY"
    private const val SNAPSHOT_FIELD = "flexunlockCameraLayoutSnapshot"
    private const val STATE_FIELD = "flexunlockCameraLayoutState"
    private const val GEOMETRY_FIELD = "flexunlockCameraGeometryState"
    private var quickShotDisplayHookInstalled = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        installPreviewSurfaceTransform(lpparam.classLoader)
        installControlTranslationGuards()
        val cameraClass = XposedHelpers.findClass(CAMERA_ACTIVITY, lpparam.classLoader)
        installOriginalCoverCameraBridge(cameraClass)
        XposedBridge.hookAllMethods(cameraClass, "onResume", cameraLayoutHook())
        XposedBridge.hookAllMethods(
            cameraClass,
            "onWindowFocusChanged",
            cameraLayoutHook(requireFocus = true)
        )
        CoverRuntime.log(SCOPE, "cover preview and control layout hooks installed")
    }

    private fun installOriginalCoverCameraBridge(cameraClass: Class<*>) {
        XposedBridge.hookAllMethods(cameraClass, "initializeQuickShot", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val manager = XposedHelpers.getObjectField(activity, "mQuickShotManager") ?: return
                val newlyBound = XposedHelpers.getAdditionalInstanceField(
                    manager,
                    QUICK_SHOT_ACTIVITY_FIELD
                ) !== activity
                XposedHelpers.setAdditionalInstanceField(manager, QUICK_SHOT_ACTIVITY_FIELD, activity)
                if (!quickShotDisplayHookInstalled) {
                    XposedBridge.hookAllMethods(
                        manager.javaClass,
                        "getDisplayId",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val owner = XposedHelpers.getAdditionalInstanceField(
                                    param.thisObject,
                                    QUICK_SHOT_ACTIVITY_FIELD
                                ) as? Activity ?: return
                                val actual = (param.result as? Number)?.toInt() ?: return
                                param.result = cameraQuickShotDisplayId(
                                    actual,
                                    isOriginalCoverCameraMode(owner)
                                )
                            }
                        }
                    )
                    quickShotDisplayHookInstalled = true
                }
                if (newlyBound && isOriginalCoverCameraMode(activity)) {
                    XposedHelpers.callMethod(manager, "start")
                }
            }
        })
    }

    private fun installControlTranslationGuards() {
        listOf(
            "setTranslationX" to TARGET_TRANSLATION_X_FIELD,
            "setTranslationY" to TARGET_TRANSLATION_Y_FIELD
        ).forEach { (methodName, fieldName) ->
            XposedBridge.hookAllMethods(View::class.java, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val target = additionalFloat(view, fieldName) ?: return
                    if (
                        CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(view.context)) &&
                        CoverQsModeConfig.readTransaction(view.context).isStableFull &&
                        CoverDisplayConfig.readCameraMode(view.context) == CoverCameraMode.INNER
                    ) {
                        param.args[0] = target
                    } else {
                        XposedHelpers.removeAdditionalInstanceField(view, fieldName)
                    }
                }
            })
        }
    }

    private fun cameraLayoutHook(requireFocus: Boolean = false) = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (requireFocus && param.args.firstOrNull() != true) return
            val activity = param.thisObject as? Activity ?: return
            activity.window.decorView.post { bindLayout(activity) }
        }
    }

    private fun installPreviewSurfaceTransform(classLoader: ClassLoader) {
        XposedBridge.hookAllMethods(
            SurfaceView::class.java,
            "onSetSurfacePositionAndScale",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val preview = param.thisObject as? SurfaceView ?: return
                    val active = XposedHelpers.getAdditionalInstanceField(
                        preview,
                        PREVIEW_FLIP_FIELD
                    ) as? Boolean ?: return
                    if (!cameraPreviewNeedsCompensation(0, active)) return
                    applyPreviewSurfaceMatrix(param, preview)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val preview = param.thisObject as? SurfaceView ?: return
                    val active = XposedHelpers.getAdditionalInstanceField(
                        preview,
                        PREVIEW_FLIP_FIELD
                    ) as? Boolean ?: return
                    updatePreviewTransformHint(preview, active)
                }
            }
        )
        val previewManagerClass = XposedHelpers.findClass(
            "com.sec.android.app.camera.preview.PreviewSurfaceManager",
            classLoader
        )
        val imageUtilsClass = XposedHelpers.findClass(
            "com.sec.android.app.camera.util.ImageUtils",
            classLoader
        )
        XposedBridge.hookAllMethods(
            previewManagerClass,
            "getCurrentPreviewSurface",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val preview = XposedHelpers.getObjectField(
                        param.thisObject,
                        "mSurfaceView"
                    ) as? SurfaceView ?: return
                    val active = CoverRuntime.isCoverDisplay(
                        CoverRuntime.displayIdOf(preview.context)
                    ) && CoverQsModeConfig.readTransaction(preview.context).isStableFull &&
                        CoverDisplayConfig.readCameraMode(preview.context) == CoverCameraMode.INNER
                    updatePreviewFlip(preview, active)
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            previewManagerClass,
            "handlePixelCopyResult",
            Int::class.javaPrimitiveType,
            Bitmap::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if ((param.args[0] as Number).toInt() != 0) return
                    val preview = XposedHelpers.getObjectField(
                        param.thisObject,
                        "mSurfaceView"
                    ) as? SurfaceView ?: return
                    val active = XposedHelpers.getAdditionalInstanceField(
                        preview,
                        PREVIEW_FLIP_FIELD
                    ) == true
                    if (!cameraPreviewNeedsCompensation(cameraRootRotation(preview), active)) return
                    param.args[1] = XposedHelpers.callStaticMethod(
                        imageUtilsClass,
                        "rotateAndMirror",
                        param.args[1],
                        180,
                        false
                    )
                }
            }
        )
    }

    private fun bindLayout(activity: Activity) {
        val decor = activity.window.decorView
        if (XposedHelpers.getAdditionalInstanceField(decor, BOUND_FIELD) == true) {
            normalizeLayoutSafely(activity)
            return
        }
        XposedHelpers.setAdditionalInstanceField(decor, BOUND_FIELD, true)
        CoverRuntime.log(
            SCOPE,
            "camera layout bound display=${CoverRuntime.displayIdOf(activity)} " +
                "full=${CoverQsModeConfig.readTransaction(activity).isStableFull}"
        )
        decor.viewTreeObserver.addOnGlobalLayoutListener(
            ViewTreeObserver.OnGlobalLayoutListener { normalizeLayoutSafely(activity) }
        )
        decor.post { normalizeLayoutSafely(activity) }
        listOf(100L, 300L, 800L).forEach { delay ->
            decor.postDelayed({ normalizeLayoutSafely(activity) }, delay)
        }
    }

    private fun applyPreviewSurfaceMatrix(
        param: XC_MethodHook.MethodHookParam,
        preview: SurfaceView
    ) {
        val args = param.args ?: return
        if (args.size < 6) return
        val left = (args[2] as? Number)?.toInt() ?: return
        val top = (args[3] as? Number)?.toInt() ?: return
        val scaleX = (args[4] as? Number)?.toFloat() ?: return
        val scaleY = (args[5] as? Number)?.toFloat() ?: return
        val screenRect = runCatching {
            XposedHelpers.getObjectField(preview, "mScreenRect") as? Rect
        }.getOrNull()
        val destinationWidth = screenRect?.width()?.takeIf { it > 0 } ?: preview.width
        val destinationHeight = screenRect?.height()?.takeIf { it > 0 } ?: preview.height
        val transform = cameraSurfaceFlipTransform(
            left,
            top,
            scaleX,
            scaleY,
            destinationWidth,
            destinationHeight
        ) ?: return
        args[2] = transform.positionX
        args[3] = transform.positionY
        args[4] = transform.scaleX
        args[5] = transform.scaleY
        val log = "left=$left top=$top width=$destinationWidth " +
            "height=$destinationHeight scale=$scaleX,$scaleY " +
            "target=${transform.positionX},${transform.positionY} " +
            "matrix=${transform.scaleX},${transform.scaleY}"
        if (XposedHelpers.getAdditionalInstanceField(preview, PREVIEW_MATRIX_LOG_FIELD) != log) {
            XposedHelpers.setAdditionalInstanceField(preview, PREVIEW_MATRIX_LOG_FIELD, log)
            CoverRuntime.log(SCOPE, "camera preview surface matrix 180 $log")
        }
    }

    private fun normalizeLayoutSafely(activity: Activity) {
        runCatching { normalizeLayout(activity) }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "camera layout failed ${error.javaClass.name}: ${error.message}"
            )
        }
    }

    private fun normalizeLayout(activity: Activity) {
        val resources = activity.resources
        val decor = activity.window.decorView
        val preview = activity.findViewById<View>(
            resources.getIdentifier("camera_preview", "id", activity.packageName)
        ) as? SurfaceView
        val mode = findView(activity, "shooting_mode_list_area")
        val zoom = findView(activity, "zoom_button_list_layout")
        val bottom = sequenceOf("center_button_container", "bottom_area")
            .mapNotNull { findView(activity, it) }
            .firstOrNull { it.interactiveCameraRect() != null }
        val active = isInnerCameraMode(activity)
        val state = "active=$active display=${CoverRuntime.displayIdOf(activity)} " +
            "preview=${preview?.isLaidOut} bottom=${bottom?.isLaidOut} " +
            "mode=${mode?.isLaidOut} zoom=${zoom?.isLaidOut}"
        if (XposedHelpers.getAdditionalInstanceField(decor, STATE_FIELD) != state) {
            XposedHelpers.setAdditionalInstanceField(decor, STATE_FIELD, state)
            CoverRuntime.log(SCOPE, "camera layout state $state")
        }
        preview ?: return
        updatePreviewFlip(preview, active)
        mode ?: return
        if (!active) {
            restoreModuleTranslation(mode)
            zoom?.let(::restoreModuleTranslation)
            return
        }
        bottom ?: return
        if (!preview.isLaidOut || !bottom.isLaidOut || !mode.isLaidOut) return
        val rootRotation = cameraRootRotation(preview)
        val modeBase = resetModuleTranslation(mode)
        val zoomBase = zoom?.let(::resetModuleTranslation)
        val bottomRect = bottom.interactiveCameraRect()
        val modeRect = mode.interactiveCameraRect()
        val zoomRect = zoom?.interactiveCameraRect()
        val geometryState = "bottom=$bottomRect mode=$modeRect zoom=$zoomRect"
        if (XposedHelpers.getAdditionalInstanceField(decor, GEOMETRY_FIELD) != geometryState) {
            XposedHelpers.setAdditionalInstanceField(decor, GEOMETRY_FIELD, geometryState)
            CoverRuntime.log(SCOPE, "camera geometry state $geometryState")
        }
        bottomRect ?: return
        modeRect ?: return
        val controlBand = cameraControlBand(bottomRect, modeRect, zoomRect)
        val offsets = cameraControlOffsets(
            controlBand,
            modeRect,
            zoomRect,
            decor.width,
            decor.height,
            (resources.displayMetrics.density * 8f).roundToInt()
        )
        applyScreenOffset(
            mode,
            modeBase,
            modeRect,
            rootRotation,
            offsets.modeX,
            offsets.modeY,
            decor.width,
            decor.height
        )
        if (zoom != null && zoomBase != null && zoomRect != null) {
            applyScreenOffset(
                zoom,
                zoomBase,
                zoomRect,
                rootRotation,
                offsets.zoomX,
                offsets.zoomY,
                decor.width,
                decor.height
            )
        }

        val snapshot = "display=${activity.display?.displayId} rootRotation=$rootRotation " +
            "previewFlip=true " +
            "bottom=$bottomRect mode=$modeRect offset=${offsets.modeX},${offsets.modeY} " +
            "zoom=$zoomRect offset=${offsets.zoomX},${offsets.zoomY}"
        if (XposedHelpers.getAdditionalInstanceField(decor, SNAPSHOT_FIELD) != snapshot) {
            XposedHelpers.setAdditionalInstanceField(decor, SNAPSHOT_FIELD, snapshot)
            CoverRuntime.log(SCOPE, "cover camera normalized $snapshot")
        }
    }

    private fun updatePreviewFlip(preview: SurfaceView, active: Boolean) {
        val previous = XposedHelpers.getAdditionalInstanceField(preview, PREVIEW_FLIP_FIELD) == true
        XposedHelpers.setAdditionalInstanceField(preview, PREVIEW_FLIP_FIELD, active)
        updatePreviewTransformHint(preview, active)
        if (previous == active) return
        preview.post {
            XposedHelpers.callMethod(preview, "requestUpdateSurfacePositionAndScale")
        }
    }

    private fun isInnerCameraMode(activity: Activity): Boolean =
        CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity)) &&
            CoverQsModeConfig.readTransaction(activity).isStableFull &&
            CoverDisplayConfig.readCameraMode(activity) == CoverCameraMode.INNER

    private fun isOriginalCoverCameraMode(activity: Activity): Boolean =
        CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity)) &&
            CoverQsModeConfig.readTransaction(activity).isStableFull &&
            CoverDisplayConfig.readCameraMode(activity) == CoverCameraMode.ORIGINAL

    private fun updatePreviewTransformHint(preview: SurfaceView, active: Boolean) {
        val blastSurface = runCatching {
            XposedHelpers.getObjectField(preview, "mBlastSurfaceControl")
        }.getOrNull() ?: return
        val nativeTransform = XposedHelpers.getIntField(preview, "mTransformHint")
        val rootRotation = cameraRootRotation(preview)
        val targetTransform = cameraPreviewTransform(nativeTransform, rootRotation, active)
        XposedHelpers.callMethod(blastSurface, "setTransformHint", targetTransform)
        val transformLog = "display=${preview.display?.rotation ?: -1} rootRotation=$rootRotation " +
            "native=$nativeTransform target=$targetTransform active=$active"
        if (XposedHelpers.getAdditionalInstanceField(
                preview,
                PREVIEW_TRANSFORM_LOG_FIELD
            ) != transformLog
        ) {
            XposedHelpers.setAdditionalInstanceField(
                preview,
                PREVIEW_TRANSFORM_LOG_FIELD,
                transformLog
            )
            CoverRuntime.log(SCOPE, "camera preview transform hint $transformLog")
        }
    }

    private fun cameraControlBand(
        bottom: CameraControlRect,
        mode: CameraControlRect,
        zoom: CameraControlRect?
    ): CameraControlRect = if (mode.height > mode.width || zoom?.let { it.height > it.width } == true) {
        CameraControlRect(
            bottom.left,
            minOf(bottom.top, mode.top, zoom?.top ?: Int.MAX_VALUE),
            bottom.right,
            maxOf(bottom.bottom, mode.bottom, zoom?.bottom ?: Int.MIN_VALUE)
        )
    } else {
        CameraControlRect(
            minOf(bottom.left, mode.left, zoom?.left ?: Int.MAX_VALUE),
            bottom.top,
            maxOf(bottom.right, mode.right, zoom?.right ?: Int.MIN_VALUE),
            bottom.bottom
        )
    }

    private fun findView(activity: Activity, name: String): View? {
        val id = activity.resources.getIdentifier(name, "id", activity.packageName)
        return if (id == 0) null else activity.findViewById(id)
    }

    private fun cameraRootRotation(view: View): Int {
        var current: View? = view
        while (current != null) {
            if (current.javaClass.name == MAIN_LAYOUT) return current.rotation.roundToInt()
            current = current.parent as? View
        }
        return 0
    }

    private fun View.interactiveCameraRect(): CameraControlRect? {
        val childRects = (this as? ViewGroup)?.let { group ->
            (0 until group.childCount).mapNotNull { index ->
                group.getChildAt(index)
                    .takeIf { it.visibility == View.VISIBLE && it.alpha > 0f }
                    ?.interactiveCameraRect()
            }
        }.orEmpty()
        if (childRects.isNotEmpty()) return cameraUnionRect(childRects)
        if (!isClickable && !isFocusable && contentDescription.isNullOrEmpty()) return null
        val bounds = Rect()
        createAccessibilityNodeInfo().getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            return CameraControlRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
        if (width <= 0 || height <= 0) return null
        val location = IntArray(2)
        getLocationOnScreen(location)
        return CameraControlRect(
            location[0],
            location[1],
            location[0] + width,
            location[1] + height
        )
    }

    private fun resetModuleTranslation(view: View): Pair<Float, Float> {
        XposedHelpers.removeAdditionalInstanceField(view, TARGET_TRANSLATION_X_FIELD)
        XposedHelpers.removeAdditionalInstanceField(view, TARGET_TRANSLATION_Y_FIELD)
        val baseX = additionalFloat(view, BASE_TRANSLATION_X_FIELD)
            ?: view.translationX.also {
                XposedHelpers.setAdditionalInstanceField(view, BASE_TRANSLATION_X_FIELD, it)
            }
        val baseY = additionalFloat(view, BASE_TRANSLATION_Y_FIELD)
            ?: view.translationY.also {
                XposedHelpers.setAdditionalInstanceField(view, BASE_TRANSLATION_Y_FIELD, it)
            }
        if (view.translationX != baseX) view.translationX = baseX
        if (view.translationY != baseY) view.translationY = baseY
        return baseX to baseY
    }

    private fun restoreModuleTranslation(view: View) {
        val (baseX, baseY) = resetModuleTranslation(view)
        if (view.translationX != baseX) view.translationX = baseX
        if (view.translationY != baseY) view.translationY = baseY
    }

    private fun applyScreenOffset(
        view: View,
        base: Pair<Float, Float>,
        rect: CameraControlRect,
        rootRotation: Int,
        x: Int,
        y: Int,
        windowWidth: Int,
        windowHeight: Int
    ) {
        val (boundedX, boundedY) = cameraBoundedOffset(
            rect,
            windowWidth,
            windowHeight,
            x,
            y
        )
        val (localX, localY) = cameraLocalOffset(boundedX, boundedY, rootRotation)
        val targetX = base.first + localX
        val targetY = base.second + localY
        XposedHelpers.setAdditionalInstanceField(view, TARGET_TRANSLATION_X_FIELD, targetX)
        XposedHelpers.setAdditionalInstanceField(view, TARGET_TRANSLATION_Y_FIELD, targetY)
        if (view.translationX != targetX) view.translationX = targetX
        if (view.translationY != targetY) view.translationY = targetY
    }

    private fun additionalFloat(view: View, field: String): Float? =
        (XposedHelpers.getAdditionalInstanceField(view, field) as? Number)?.toFloat()
}
