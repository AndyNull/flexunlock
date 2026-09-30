package com.flexunlock.dexlsp

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import com.flexunlock.dexlsp.config.CoverLaunchConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference

internal object GoodLockHooks {
    private const val SCOPE = "GoodLock"
    private const val LOCKSTAR_LAUNCH_ACTIVITY =
        "com.samsung.systemui.lockstar.presentation.settings.launch.LockStarLaunchActivity"
    private const val DRESSROOM_DEVICE_UTIL = "W4.d"
    private const val DRESSROOM_WALLPAPER_TARGET_UTIL = "W4.z"
    private const val DRESSROOM_WALLPAPER_TARGET_UTIL_V8 = "H3.x"
    private const val DRESSROOM_EDIT_BASE_ACTIVITY =
        "com.samsung.android.app.dressroom.presentation.preview.activity.EditBaseActivity"
    private const val DRESSROOM_EDIT_MAIN_ACTIVITY =
        "com.samsung.android.app.dressroom.presentation.preview.activity.EditMainActivity"
    private const val DRESSROOM_EDIT_COVER_ACTIVITY =
        "com.samsung.android.app.dressroom.presentation.preview.activity.EditCoverActivity"
    private const val DRESSROOM_EDIT_DEX_ACTIVITY =
        "com.samsung.android.app.dressroom.presentation.preview.activity.EditDexActivity"
    private const val DRESSROOM_SET_AS_WALLPAPER_ACTIVITY =
        "com.samsung.android.app.dressroom.presentation.SetAsWallPaperActivity"
    private const val DRESSROOM_WALLPAPER_LIBRARY_ACTIVITY =
        "com.samsung.android.app.dressroom.presentation.library.ui.WallpaperLibraryActivity"
    private const val DRESSROOM_RUNE_PROVIDER = "y5.h"
    private const val DRESSROOM_COVER_EDITOR_ACTION =
        "com.samsung.intent.action.SET_COVER_WALLPAPER_MULTI"
    private const val DRESSROOM_SHOW_LOCK_EDITOR_ACTION =
        "com.samsung.dressroom.intent.action.SHOW_LOCK_EDITOR"
    private const val WALLPAPER_WHICH = "which"
    private const val WALLPAPER_WHICH_SELECTED = "which_selected"
    private const val WALLPAPER_IS_EDIT_COVER = "is_edit_cover"
    private const val WALLPAPER_MODE_MASK = 60
    private const val WALLPAPER_TARGET_MASK = 3
    private const val WALLPAPER_TARGET_LOCK = 2
    private const val WALLPAPER_MODE_SUB_DISPLAY = 16
    private const val WALLPAPER_LOCK_SUB_DISPLAY = 18
    private const val COVER_EDITOR_RELOCATED =
        "com.flexunlock.dexlsp.extra.COVER_EDITOR_RELOCATED"
    private const val PREVIEW_WRAPPER_TAG = "flexunlock-cover-preview-wrapper"
    private val goodLockRerouteDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }
    private val dressRoomWallpaperLibraryDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }
    private var dressRoomLockEditor = WeakReference<Activity>(null)

    private fun goodLockDepth(): Int = goodLockRerouteDepth.get() ?: 0

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            CoverRuntime.GOOD_LOCK_PACKAGE -> installGoodLockLauncher()
            CoverRuntime.LOCKSTAR_PACKAGE -> installLockStar(lpparam.classLoader)
            CoverRuntime.DRESSROOM_PACKAGE -> installDressRoom(lpparam.classLoader)
        }
    }

    private fun installGoodLockLauncher() {
        fun displayIdForRoute(context: Context, intent: Intent): Int? {
            val depth = goodLockDepth()
            val displayId = CoverRuntime.displayIdOf(context)
            val eligible = CoverRuntime.isCoverSessionEligible()
            val targetPackage = intent.component?.packageName
                ?: intent.`package`
                ?: context.packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
            val fromGoodLock = context.packageName == CoverRuntime.GOOD_LOCK_PACKAGE
            CoverRuntime.log(
                SCOPE,
                "Good Lock route check pkg=${context.packageName} " +
                    "display=$displayId eligible=$eligible depth=$depth " +
                    "target=$targetPackage fromGoodLock=$fromGoodLock"
            )
            if (depth > 0 || !eligible) return null
            val coverDisplayId = CoverDisplayResolver.currentId() ?: return null
            if (displayId != coverDisplayId) return null
            // 用户在外屏 Good Lock 主界面点击插件:无论是否在白名单,
            // 插件都必须留在外屏,否则会被系统启动到内屏导致外屏"无反应"。
            val allowed = targetPackage in CoverLaunchConfig.read(context) || fromGoodLock
            return coverDisplayId.takeIf { allowed }
        }

        fun optionsForCover(existing: Bundle?, displayId: Int): Bundle =
            Bundle(existing ?: Bundle()).apply {
                putInt("android.activity.launchDisplayId", displayId)
            }

        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivity",
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val intent = param.args.firstOrNull() as? Intent ?: return
                        val coverDisplayId = displayIdForRoute(activity, intent) ?: return
                        goodLockRerouteDepth.set(goodLockDepth() + 1)
                        try {
                            activity.startActivity(intent, optionsForCover(null, coverDisplayId))
                            param.result = null
                            CoverRuntime.log(
                                SCOPE,
                                "Good Lock plugin launch routed to display 1 " +
                                    "target=${intent.component ?: intent.action}"
                            )
                        } finally {
                            goodLockRerouteDepth.set(
                                (goodLockDepth() - 1).coerceAtLeast(0)
                            )
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivity",
                Intent::class.java,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val intent = param.args.firstOrNull() as? Intent ?: return
                        val coverDisplayId = displayIdForRoute(activity, intent) ?: return
                        param.args[1] = optionsForCover(
                            param.args.getOrNull(1) as? Bundle,
                            coverDisplayId
                        )
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivityForResult",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val intent = param.args.firstOrNull() as? Intent ?: return
                        val coverDisplayId = displayIdForRoute(activity, intent) ?: return
                        val requestCode = (param.args.getOrNull(1) as? Number)?.toInt() ?: return
                        goodLockRerouteDepth.set(goodLockDepth() + 1)
                        try {
                            activity.startActivityForResult(
                                intent,
                                requestCode,
                                optionsForCover(null, coverDisplayId)
                            )
                            param.result = null
                            CoverRuntime.log(
                                SCOPE,
                                "Good Lock plugin result launch routed to display 1 " +
                                    "target=${intent.component ?: intent.action}"
                            )
                        } finally {
                            goodLockRerouteDepth.set(
                                (goodLockDepth() - 1).coerceAtLeast(0)
                            )
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivityForResult",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val intent = param.args.firstOrNull() as? Intent ?: return
                        val coverDisplayId = displayIdForRoute(activity, intent) ?: return
                        param.args[2] = optionsForCover(
                            param.args.getOrNull(2) as? Bundle,
                            coverDisplayId
                        )
                    }
                }
            )
            // ActivityResultLauncher / Context 通道兜底:Good Lock 部分插件
            // (如 OHO)不经过 Activity.startActivity,而是走 ContextImpl.startActivity,
            // 这里给这类启动补上当前已解析的 launchDisplayId,避免三星默认投递到内屏。
            runCatching {
                val contextImplClass = Class.forName("android.app.ContextImpl")
                fun routeContextLaunch(param: XC_MethodHook.MethodHookParam) {
                    val context = param.thisObject as? Context ?: return
                    if (context.packageName != CoverRuntime.GOOD_LOCK_PACKAGE) return
                    val intent = param.args.firstOrNull() as? Intent ?: return
                    val coverDisplayId = displayIdForRoute(context, intent) ?: return
                    val target = intent.component?.packageName
                        ?: intent.`package`
                        ?: intent.action
                    val optionsIndex = param.args.indexOfFirst { it is Bundle }
                    if (optionsIndex >= 0) {
                        param.args[optionsIndex] = optionsForCover(
                            param.args[optionsIndex] as? Bundle,
                            coverDisplayId
                        )
                    } else {
                        goodLockRerouteDepth.set(goodLockDepth() + 1)
                        val routed = try {
                            runCatching {
                                context.startActivity(
                                    intent,
                                    optionsForCover(null, coverDisplayId)
                                )
                            }.onFailure { error ->
                                CoverRuntime.log(
                                    SCOPE,
                                    "Good Lock context launch route failed: ${error.message}"
                                )
                            }.isSuccess
                        } finally {
                            goodLockRerouteDepth.set(
                                (goodLockDepth() - 1).coerceAtLeast(0)
                            )
                        }
                        if (!routed) return
                        param.result = null
                    }
                    CoverRuntime.log(
                        SCOPE,
                        "Good Lock context launch routed to display $coverDisplayId " +
                            "target=$target"
                    )
                }
                XposedHelpers.findAndHookMethod(
                    contextImplClass,
                    "startActivity",
                    Intent::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            routeContextLaunch(param)
                        }
                    }
                )
                XposedHelpers.findAndHookMethod(
                    contextImplClass,
                    "startActivity",
                    Intent::class.java,
                    Bundle::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            routeContextLaunch(param)
                        }
                    }
                )
            }.onFailure { error ->
                CoverRuntime.log(
                    SCOPE,
                    "Good Lock context launch route unavailable: ${error.message}"
                )
            }
            CoverRuntime.log(
                SCOPE,
                "Good Lock display-1 configured plugin launch route installed"
            )
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "Good Lock display-1 plugin launch route unavailable: ${error.message}"
            )
        }
    }

    private fun installLockStar(classLoader: ClassLoader) {
        val activityClass = XposedHelpers.findClassIfExists(
            LOCKSTAR_LAUNCH_ACTIVITY,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "LockStar launch activity unavailable")

        runCatching {
            val methods = activityClass.declaredMethods.filter { method ->
                method.name == "j" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.contentEquals(arrayOf(Context::class.java))
            }
            require(methods.size == 1) {
                "LockStar launch method ambiguous count=${methods.size}"
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (
                            !CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity)) ||
                            !CoverRuntime.isCoverSessionEligible()
                        ) {
                            return
                        }
                        val pkg = activity.packageName
                        val allowed = pkg in CoverLaunchConfig.read(activity) ||
                            pkg.startsWith("com.samsung.systemui.")
                        if (!allowed) return
                        param.result = false
                        CoverRuntime.log(
                            SCOPE,
                            "LockStar display-1 launch restriction bypassed"
                        )
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "LockStar display-1 launch compatibility installed methods=${methods.size}"
            )
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "LockStar display-1 launch compatibility unavailable: ${error.message}"
            )
        }
    }

    private fun installDressRoom(classLoader: ClassLoader) {
        installDressRoomWallpaperLibraryFoldGuard(classLoader)
        installDressRoomCoverEditorRoute(classLoader)
        installDressRoomModeClassifier(classLoader)
        installDressRoomWallpaperTargetMapper(classLoader)
        installDressRoomWallpaperTarget(classLoader)
    }

    private fun installDressRoomWallpaperLibraryFoldGuard(classLoader: ClassLoader) {
        val libraryClass = XposedHelpers.findClassIfExists(
            DRESSROOM_WALLPAPER_LIBRARY_ACTIVITY,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom wallpaper library unavailable")
        val runeProviderClass = XposedHelpers.findClassIfExists(
            DRESSROOM_RUNE_PROVIDER,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom rune provider unavailable")

        XposedHelpers.findAndHookMethod(
            libraryClass,
            "onCreate",
            Bundle::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (!CoverQsModeConfig.readTransaction(activity).isStableFull ||
                        !CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity))
                    ) return
                    dressRoomWallpaperLibraryDepth.set(
                        (dressRoomWallpaperLibraryDepth.get() ?: 0) + 1
                    )
                    param.setObjectExtra("flexunlockWallpaperLibraryFull", true)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.getObjectExtra("flexunlockWallpaperLibraryFull") != true) return
                    dressRoomWallpaperLibraryDepth.set(
                        ((dressRoomWallpaperLibraryDepth.get() ?: 0) - 1).coerceAtLeast(0)
                    )
                }
            }
        )
        XposedBridge.hookAllMethods(runeProviderClass, "Y", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if ((dressRoomWallpaperLibraryDepth.get() ?: 0) <= 0) return
                param.result = false
                CoverRuntime.log(SCOPE, "Dressroom full QS wallpaper library Flip guard bypassed")
            }
        })
        CoverRuntime.log(SCOPE, "Dressroom full QS wallpaper library guard installed")
    }

    private fun installDressRoomCoverEditorRoute(classLoader: ClassLoader) {
        val coverEditorClass = XposedHelpers.findClassIfExists(
            DRESSROOM_EDIT_COVER_ACTIVITY,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom cover editor unavailable")
        val mainEditorClass = XposedHelpers.findClassIfExists(
            DRESSROOM_EDIT_MAIN_ACTIVITY,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom main editor unavailable")
        val dexEditorClass = XposedHelpers.findClassIfExists(
            DRESSROOM_EDIT_DEX_ACTIVITY,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom Dex editor unavailable")

        fun reroute(activity: Activity, intent: Intent): Int? {
            val coverDisplayId = CoverDisplayResolver.currentId() ?: return null
            if (
                CoverRuntime.displayIdOf(activity) != coverDisplayId ||
                !CoverRuntime.isCoverSessionEligible()
            ) {
                return null
            }
            val component = intent.component
            if (component?.className == coverEditorClass.name) return null
            val isUnsupportedEditor = component?.className in setOf(
                DRESSROOM_EDIT_MAIN_ACTIVITY,
                DRESSROOM_EDIT_DEX_ACTIVITY
            )
            if (!isUnsupportedEditor && intent.action != DRESSROOM_COVER_EDITOR_ACTION) return null

            val originalWhich = intent.getIntExtra(WALLPAPER_WHICH, 0)
            val originalSelected = intent.getIntExtra(WALLPAPER_WHICH_SELECTED, 0)
            val target = (originalWhich and WALLPAPER_TARGET_MASK).takeIf { it != 0 }
                ?: (originalSelected and WALLPAPER_TARGET_MASK).takeIf { it != 0 }
                ?: if (intent.action == DRESSROOM_COVER_EDITOR_ACTION) 1 else return null
            if (CoverQsModeConfig.readTransaction(activity).isStableFull) return null
            val normalizedWhich = target or WALLPAPER_MODE_SUB_DISPLAY

            intent.component = ComponentName(activity.packageName, coverEditorClass.name)
            intent.putExtra(WALLPAPER_WHICH, normalizedWhich)
            intent.putExtra(WALLPAPER_WHICH_SELECTED, normalizedWhich)
            intent.putExtra(WALLPAPER_IS_EDIT_COVER, true)
            CoverRuntime.log(
                SCOPE,
                "Dressroom display-1 editor routed to EditCoverActivity " +
                    "which=$originalWhich selected=$originalSelected normalized=$normalizedWhich"
            )
            return coverDisplayId
        }

        fun relocateDirectCoverEditor(activity: Activity): Boolean {
            if (
                CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity)) ||
                !CoverRuntime.isCoverSessionEligible()
            ) {
                return false
            }
            val coverDisplayId = CoverDisplayResolver.currentId() ?: return false
            val source = activity.intent ?: return false
            if (source.getBooleanExtra(COVER_EDITOR_RELOCATED, false)) {
                CoverRuntime.log(
                    SCOPE,
                    "Dressroom cover editor relocation rejected by display manager"
                )
                return false
            }

            val relocated = Intent(source).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                putExtra(COVER_EDITOR_RELOCATED, true)
            }
            activity.startActivity(
                relocated,
                Bundle().apply {
                    putInt(
                        "android.activity.launchDisplayId",
                        coverDisplayId
                    )
                }
            )
            activity.finish()
            CoverRuntime.log(
                SCOPE,
                "Dressroom direct cover editor relocated from display 0 to display 1 " +
                    "which=${source.getIntExtra(WALLPAPER_WHICH, 0)} " +
                    "selected=${source.getIntExtra(WALLPAPER_WHICH_SELECTED, 0)}"
            )
            return true
        }

        fun fitCoverEditorMenu(
            activity: Activity,
            menuScroll: android.widget.HorizontalScrollView?,
            preview: View
        ) {
            if (menuScroll == null) return
            menuScroll.post {
                val scrollContent = menuScroll.getChildAt(0) as? ViewGroup ?: return@post
                val previewLocation = IntArray(2)
                val menuLocation = IntArray(2)
                preview.getLocationOnScreen(previewLocation)
                menuScroll.getLocationOnScreen(menuLocation)
                val previewBottom = previewLocation[1] + preview.height
                val menuTop = menuLocation[1]
                val gapPx = (14 * activity.resources.displayMetrics.density + 0.5f).toInt()
                val shift = (previewBottom + gapPx - menuTop).toFloat()
                menuScroll.translationY += shift
                // 按钮缩放 + 效果并入第一行
                val firstRow = scrollContent.getChildAt(0) as? ViewGroup
                val secondRow = scrollContent.getChildAt(1) as? ViewGroup
                val scale = 0.82f
                if (firstRow != null) {
                    firstRow.pivotX = 0f
                    firstRow.pivotY = 0f
                    firstRow.scaleX = scale
                    firstRow.scaleY = scale
                }
                if (secondRow != null) {
                    // firstRow 缩放后的右缘(View.width 不随 scale 变化,需手动乘 scale)
                    val firstRight = (firstRow?.left ?: 0) +
                        ((firstRow?.width ?: 0) * scale).toInt()
                    val secondLeft = secondRow.left
                    val gap = 10
                    val xOffset = (firstRight - secondLeft + gap).toFloat()
                    secondRow.pivotX = 0f
                    secondRow.pivotY = 0f
                    secondRow.scaleX = scale
                    secondRow.scaleY = scale
                    secondRow.translationX = xOffset
                    val firstTop = firstRow?.top ?: 0
                    secondRow.translationY = (firstTop - secondRow.top).toFloat()
                    CoverRuntime.log(
                        SCOPE,
                        "Dressroom display-1 editor menu secondRow merged " +
                            "xOffset=$xOffset secondTop=${secondRow.top}"
                    )
                } else {
                    CoverRuntime.log(
                        SCOPE,
                        "Dressroom display-1 editor menu secondRow missing " +
                            "children=${scrollContent.childCount}"
                    )
                }
                CoverRuntime.log(
                    SCOPE,
                    "Dressroom display-1 editor menu fitted " +
                        "shift=$shift scale=$scale " +
                        "previewBottom=$previewBottom menuTop=$menuTop"
                )
            }
        }

        fun fitCoverEditorPreview(activity: Activity) {
            val menuScrollId = activity.resources.getIdentifier(
                "menu_scroll_view",
                "id",
                activity.packageName
            )
            activity.findViewById<HorizontalScrollView>(menuScrollId)?.apply {
                setOnTouchListener(null)
                isHorizontalScrollBarEnabled = true
                isFillViewport = false
            }

            val menuScroll = activity.findViewById<android.widget.HorizontalScrollView>(
                menuScrollId
            )
            val previewRootId = activity.resources.getIdentifier(
                "preview_single_view_root",
                "id",
                activity.packageName
            )
            val previewRoot = activity.findViewById<ViewGroup>(previewRootId) ?: return
            previewRoot.post {
                val parent = previewRoot.parent as? ViewGroup ?: return@post
                if (parent.tag == PREVIEW_WRAPPER_TAG) return@post
                val metrics = activity.resources.displayMetrics
                val cover = CoverDisplayResolver.current()
                val coverWidth = cover?.width?.takeIf { it > 0 } ?: metrics.widthPixels
                val coverHeight = cover?.height?.takeIf { it > 0 } ?: metrics.heightPixels
                if (coverWidth <= 0 || coverHeight <= 0) return@post
                val screenRatio = coverWidth.toFloat() / coverHeight.toFloat()
                val availableWidth = (
                    parent.width - parent.paddingLeft - parent.paddingRight
                ).takeIf { it > 0 } ?: coverWidth
                val targetWidth = minOf(
                    (coverWidth * 0.44f).toInt(),
                    availableWidth
                ).coerceAtLeast(1)
                val targetHeight = (targetWidth / screenRatio).toInt().coerceAtLeast(1)

                val parentParams = parent.layoutParams
                if (parentParams.height != targetHeight) {
                    parentParams.height = targetHeight
                    parent.layoutParams = parentParams
                }
                parent.clipChildren = false
                parent.clipToPadding = false

                val wrapperParams = previewRoot.layoutParams.apply {
                    width = targetWidth
                    height = targetHeight
                }
                val wrapper = FrameLayout(activity).apply {
                    tag = PREVIEW_WRAPPER_TAG
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = wrapperParams
                }
                parent.removeView(previewRoot)
                parent.addView(wrapper)
                wrapper.addView(
                    previewRoot,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER
                    )
                )
                previewRoot.scaleX = 1f
                previewRoot.scaleY = 1f
                previewRoot.translationX = 0f
                previewRoot.translationY = 0f
                CoverRuntime.log(
                    SCOPE,
                    "Dressroom display-1 preview fitted without transform cropping " +
                        "target=${targetWidth}x$targetHeight ratio=$screenRatio " +
                        "cover=${coverWidth}x$coverHeight"
                )
                wrapper.post {
                    fitCoverEditorMenu(activity, menuScroll, wrapper)
                }
            }
        }

        fun onCreateMethod(type: Class<*>): java.lang.reflect.Method? {
            var current: Class<*>? = type
            while (current != null && Activity::class.java.isAssignableFrom(current)) {
                current.declaredMethods.firstOrNull { method ->
                    method.name == "onCreate" &&
                        method.returnType == java.lang.Void.TYPE &&
                        method.parameterTypes.contentEquals(arrayOf(Bundle::class.java))
                }?.let { return it }
                current = current.superclass
            }
            return null
        }

        runCatching {
            val lifecycleMethods = listOf(
                mainEditorClass,
                dexEditorClass,
                coverEditorClass
            ).mapNotNull(::onCreateMethod).distinct()
            require(lifecycleMethods.isNotEmpty()) {
                "Dressroom editor onCreate(Bundle) lifecycle unavailable"
            }
            lifecycleMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (
                            !mainEditorClass.isInstance(activity) &&
                            !dexEditorClass.isInstance(activity)
                        ) return
                        val intent = activity.intent ?: return
                        val coverDisplayId = reroute(activity, intent) ?: return
                        activity.startActivity(
                            intent,
                            Bundle().apply {
                                putInt("android.activity.launchDisplayId", coverDisplayId)
                            }
                        )
                        activity.finish()
                        param.result = null
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!coverEditorClass.isInstance(activity)) return
                        if (relocateDirectCoverEditor(activity)) return
                        if (
                            CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity)) &&
                            CoverRuntime.isCoverSessionEligible()
                        ) {
                            fitCoverEditorPreview(activity)
                        }
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "Dressroom editor lifecycle route installed methods=${lifecycleMethods.size} " +
                    "owners=${lifecycleMethods.joinToString { it.declaringClass.name }}"
            )
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "Dressroom editor lifecycle route unavailable: ${error.message}"
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivity",
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        reroute(param.thisObject as? Activity ?: return, param.args[0] as? Intent ?: return)
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivity",
                Intent::class.java,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        reroute(param.thisObject as? Activity ?: return, param.args[0] as? Intent ?: return)
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "startActivityForResult",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        reroute(param.thisObject as? Activity ?: return, param.args[0] as? Intent ?: return)
                    }
                }
            )
            CoverRuntime.log(SCOPE, "Dressroom display-1 cover editor route installed")
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "Dressroom cover editor route unavailable: ${error.message}"
            )
        }
    }

    private fun installDressRoomWallpaperTargetMapper(classLoader: ClassLoader) {
        fun mapperMethods(candidate: Class<*>) = candidate.declaredMethods.filter { method ->
            method.name == "c" &&
                method.returnType == Int::class.javaPrimitiveType &&
                method.parameterTypes.size == 4 &&
                Context::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                method.parameterTypes[2] == Int::class.javaPrimitiveType
        }
        val targetUtilClass = findCompatClass(
            classLoader = classLoader,
            v85Names = listOf(
                DRESSROOM_WALLPAPER_TARGET_UTIL,
                DRESSROOM_WALLPAPER_TARGET_UTIL_V8
            ),
            v8Names = listOf(
                DRESSROOM_WALLPAPER_TARGET_UTIL_V8,
                DRESSROOM_WALLPAPER_TARGET_UTIL
            )
        ) { candidate -> mapperMethods(candidate).size == 1 }
            ?: return CoverRuntime.log(
                SCOPE,
                "Dressroom wallpaper target utility unavailable: unique mapper shape missing"
            )

        runCatching {
            val methods = mapperMethods(targetUtilClass)
            require(methods.size == 1) {
                "wallpaper target mapper ambiguous count=${methods.size}"
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args.getOrNull(0) as? Context ?: return
                        val input = (param.args.getOrNull(2) as? Number)?.toInt() ?: return
                        val lockEditor = dressRoomLockEditor.get()
                        val coverContext = CoverRuntime.isCoverDisplay(
                            CoverRuntime.displayIdOf(context)
                        ) || lockEditor?.intent?.action == DRESSROOM_SHOW_LOCK_EDITOR_ACTION
                        if (
                            !coverContext ||
                            !CoverRuntime.isCoverSessionEligible() ||
                            input and WALLPAPER_MODE_MASK != WALLPAPER_MODE_SUB_DISPLAY
                        ) {
                            return
                        }
                        val mapped = (param.result as? Number)?.toInt()
                        param.result = input
                        if (mapped != input) {
                            CoverRuntime.log(
                                SCOPE,
                                "Dressroom display-1 wallpaper target mapping preserved " +
                                    "input=$input mapped=$mapped"
                            )
                        }
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "Dressroom display-1 wallpaper target mapper installed methods=${methods.size}"
            )
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "Dressroom display-1 wallpaper target mapper unavailable: ${error.message}"
            )
        }
    }

    private fun installDressRoomModeClassifier(classLoader: ClassLoader) {
        val deviceUtilClass = XposedHelpers.findClassIfExists(
            DRESSROOM_DEVICE_UTIL,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom device utility unavailable")
        val setAsWallpaperActivityClass = XposedHelpers.findClassIfExists(
            DRESSROOM_SET_AS_WALLPAPER_ACTIVITY,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom SetAsWallpaper activity unavailable")
        val editMainActivityClass = XposedHelpers.findClassIfExists(
            DRESSROOM_EDIT_MAIN_ACTIVITY,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom EditMain activity unavailable")
        val editCoverActivityClass = XposedHelpers.findClassIfExists(
            DRESSROOM_EDIT_COVER_ACTIVITY,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom EditCover activity unavailable")

        fun activityFrom(context: Context): Activity? {
            var current: Context? = context
            repeat(8) {
                val candidate = current ?: return null
                if (candidate is Activity) return candidate
                val base = (candidate as? ContextWrapper)?.baseContext ?: return null
                if (base === candidate) return null
                current = base
            }
            return null
        }

        runCatching {
            val methods = deviceUtilClass.declaredMethods.filter { method ->
                method.name == "U" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.contentEquals(arrayOf(Context::class.java))
            }
            require(methods.size == 1) {
                "Dressroom editor classifier ambiguous count=${methods.size}"
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val context = param.args?.firstOrNull() as? Context ?: return
                        if (
                            !CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(context)) ||
                            !CoverRuntime.isCoverSessionEligible()
                        ) {
                            return
                        }
                        val activity = activityFrom(context)
                        val fullWallpaperLibrary =
                            activity?.javaClass?.name == DRESSROOM_WALLPAPER_LIBRARY_ACTIVITY &&
                                CoverQsModeConfig.readTransaction(context).isStableFull
                        param.result = activity == null ||
                            (!setAsWallpaperActivityClass.isInstance(activity) &&
                                !editMainActivityClass.isInstance(activity) &&
                                !editCoverActivityClass.isInstance(activity) &&
                                !fullWallpaperLibrary)
                    }
                })
            }
            CoverRuntime.log(
                SCOPE,
                "Dressroom display-1 editor classification installed methods=${methods.size}"
            )
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "Dressroom display-1 editor classification unavailable: ${error.message}"
            )
        }
    }

    private fun installDressRoomWallpaperTarget(classLoader: ClassLoader) {
        val editBaseActivityClass = XposedHelpers.findClassIfExists(
            DRESSROOM_EDIT_BASE_ACTIVITY,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom EditBase activity unavailable")
        val editMainActivityClass = XposedHelpers.findClassIfExists(
            DRESSROOM_EDIT_MAIN_ACTIVITY,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom EditMain activity unavailable")
        val editDexActivityClass = XposedHelpers.findClassIfExists(
            DRESSROOM_EDIT_DEX_ACTIVITY,
            classLoader
        ) ?: return CoverRuntime.log(SCOPE, "Dressroom EditDex activity unavailable")

        fun normalizeTarget(activity: Activity, intent: Intent) {
            if (
                (!editMainActivityClass.isInstance(activity) &&
                    !editDexActivityClass.isInstance(activity)) ||
                !CoverRuntime.isCoverDisplay(CoverRuntime.displayIdOf(activity)) ||
                !CoverRuntime.isCoverSessionEligible()
            ) {
                return
            }
            val originalWhich = intent.getIntExtra(WALLPAPER_WHICH, 0)
            val originalSelected = intent.getIntExtra(WALLPAPER_WHICH_SELECTED, 0)
            val target = (originalWhich and WALLPAPER_TARGET_MASK).takeIf { it != 0 }
                ?: (originalSelected and WALLPAPER_TARGET_MASK).takeIf { it != 0 }
                ?: WALLPAPER_TARGET_LOCK.takeIf {
                    intent.action == DRESSROOM_SHOW_LOCK_EDITOR_ACTION
                }
                ?: return CoverRuntime.log(
                    SCOPE,
                    "Dressroom display-1 wallpaper target missing " +
                        "which=$originalWhich selected=$originalSelected"
                )
            val normalizedWhich = if (CoverQsModeConfig.readTransaction(activity).isStableFull) {
                target
            } else {
                target or WALLPAPER_MODE_SUB_DISPLAY
            }
            if (intent.action == DRESSROOM_SHOW_LOCK_EDITOR_ACTION) {
                dressRoomLockEditor = WeakReference(activity)
            }

            intent.putExtra(WALLPAPER_WHICH, normalizedWhich)
            intent.putExtra(WALLPAPER_WHICH_SELECTED, normalizedWhich)
            intent.putExtra(WALLPAPER_IS_EDIT_COVER, false)
            CoverRuntime.log(
                SCOPE,
                "Dressroom display-1 wallpaper target observed " +
                    "which=$originalWhich selected=$originalSelected " +
                    "normalized=$normalizedWhich editor=standard"
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                editBaseActivityClass,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        normalizeTarget(activity, activity.intent ?: return)
                    }
                }
            )
            CoverRuntime.log(SCOPE, "Dressroom display-1 wallpaper onCreate target hook installed")
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "Dressroom display-1 wallpaper onCreate target hook unavailable: ${error.message}"
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                editBaseActivityClass,
                "onNewIntent",
                Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val incoming = param.args.firstOrNull() as? Intent ?: return
                        normalizeTarget(activity, incoming)
                        activity.intent = incoming
                    }
                }
            )
            CoverRuntime.log(SCOPE, "Dressroom display-1 wallpaper onNewIntent target hook installed")
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "Dressroom display-1 wallpaper onNewIntent target hook unavailable: ${error.message}"
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "getIntent",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (dressRoomLockEditor.get() !== activity) return
                        val intent = param.result as? Intent ?: return
                        val target = if (CoverQsModeConfig.readTransaction(activity).isStableFull) {
                            WALLPAPER_TARGET_LOCK
                        } else {
                            WALLPAPER_LOCK_SUB_DISPLAY
                        }
                        if (
                            intent.getIntExtra(WALLPAPER_WHICH, 0) == target &&
                            intent.getIntExtra(WALLPAPER_WHICH_SELECTED, 0) == target
                        ) return
                        intent.putExtra(WALLPAPER_WHICH, target)
                        intent.putExtra(WALLPAPER_WHICH_SELECTED, target)
                        CoverRuntime.log(SCOPE, "Dressroom lock editor intent normalized=$target")
                    }
                }
            )
            CoverRuntime.log(SCOPE, "Dressroom lock editor intent target hook installed")
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "Dressroom lock editor intent target hook unavailable: ${error.message}"
            )
        }

        runCatching {
            val wallpaperComponentClass = XposedHelpers.findClass("X2.B", classLoader)
            val cachedIntentField = wallpaperComponentClass.declaredFields.single {
                it.type == Intent::class.java
            }.apply { isAccessible = true }
            val resultMethods = wallpaperComponentClass.declaredMethods.filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(
                        arrayOf(
                            Int::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType,
                            Intent::class.java
                        )
                    )
            }
            check(resultMethods.isNotEmpty())
            resultMethods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = dressRoomLockEditor.get() ?: return
                        val target = if (CoverQsModeConfig.readTransaction(activity).isStableFull) {
                            WALLPAPER_TARGET_LOCK
                        } else {
                            WALLPAPER_LOCK_SUB_DISPLAY
                        }
                        val incoming = param.args[2] as Intent
                        val cached = cachedIntentField.get(param.thisObject) as Intent
                        listOf(incoming, cached).forEach { intent ->
                            intent.putExtra(WALLPAPER_WHICH, target)
                            intent.putExtra(WALLPAPER_WHICH_SELECTED, target)
                        }
                        CoverRuntime.log(
                            SCOPE,
                            "Dressroom final lock wallpaper target normalized=$target"
                        )
                    }
                })
            }
            XposedBridge.hookAllMethods(editBaseActivityClass, "onDestroy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (dressRoomLockEditor.get() === param.thisObject) {
                        dressRoomLockEditor = WeakReference(null)
                    }
                }
            })
            CoverRuntime.log(
                SCOPE,
                "Dressroom display-1 final wallpaper result target hook installed"
            )
        }.onFailure { error ->
            CoverRuntime.log(
                SCOPE,
                "Dressroom display-1 final wallpaper result target hook unavailable: ${error.message}"
            )
        }
    }
}
