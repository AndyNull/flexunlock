package com.flexunlock.dexlsp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.flexunlock.dexlsp.config.CoverDisplayCandidateStatus
import com.flexunlock.dexlsp.config.CoverCameraMode
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import com.flexunlock.dexlsp.config.CoverDisplayMode
import com.flexunlock.dexlsp.config.CoverDisplayOverride
import com.flexunlock.dexlsp.config.CoverDisplayStatus
import com.flexunlock.dexlsp.config.displayModesMatch
import com.flexunlock.dexlsp.config.selectModeForRefresh
import com.flexunlock.dexlsp.config.selectModeForResolution

internal fun compactActionColumnCount(availableWidthDp: Int, itemCount: Int): Int = when {
    itemCount <= 0 -> 0
    itemCount == 1 -> 1
    availableWidthDp >= 480 -> minOf(itemCount, 3)
    else -> minOf(itemCount, 2)
}

internal enum class CoverOutputMode { ORIGINAL, FULL_QS, FULL_DEX }

internal fun resolveCoverOutputMode(fullDex: Boolean, qsMode: CoverQsMode): CoverOutputMode = when {
    fullDex -> CoverOutputMode.FULL_DEX
    qsMode == CoverQsMode.FULL -> CoverOutputMode.FULL_QS
    else -> CoverOutputMode.ORIGINAL
}

class CoverDexControlActivity : Activity() {
    private data class ActionOption(
        val label: String,
        val emphasized: Boolean = true,
        val action: () -> Unit
    )

    private enum class Page(val title: String, val shortTitle: String) {
        HOME("控制中心", "主页"),
        APPS("应用管理与缩放", "应用"),
        TILES("磁贴设置", "磁贴"),
        SETTINGS("外屏控制", "控制"),
        THEME("外观主题", "主题"),
        CHARITY("公益项目", "公益"),
        ABOUT("关于 FlexUnlock", "关于")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var displayStatusRunnable: Runnable? = null
    private var settingConfirmationRunnable: Runnable? = null
    private lateinit var palette: ModuleUiPalette
    private lateinit var shellRoot: ViewGroup
    private lateinit var sidebarSlot: FrameLayout
    private lateinit var sidebar: View
    private lateinit var contentHost: FrameLayout
    private lateinit var pageContainer: LinearLayout
    private var pageScrollView: ScrollView? = null
    private val pageScrollPositions = mutableMapOf<Page, Int>()
    private var appPanel: CoverLaunchAllowlistPanel? = null
    private var sidebarCollapsed = false
    private val navigationButtons = linkedMapOf<Page, View>()
    private var currentPage = Page.HOME
    private var lastInsets: WindowInsets? = null
    private var homeStatusBody: TextView? = null
    private var pendingHomeStatusBarHidden: Boolean? = null
    private var restoringPageScroll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = ModuleThemeStore.palette(this)
        ModuleThemeStore.configureEdgeToEdge(this, palette.background)
        CoverLaunchPackageCatalog.ensureLoaded(applicationContext)
        sidebarCollapsed = savedInstanceState?.getBoolean(STATE_SIDEBAR_COLLAPSED, false) ?: false
        setContentView(buildShell())
        val initialPage = savedInstanceState
            ?.getString(STATE_PAGE)
            ?.let { stored -> Page.entries.firstOrNull { it.name == stored } }
            ?: Page.HOME
        savedInstanceState?.getInt(STATE_SCROLL, 0)?.takeIf { it > 0 }?.let { scrollY ->
            pageScrollPositions[initialPage] = scrollY
        }
        if (initialPage == Page.SETTINGS) {
            openSettingsPage()
        } else if (initialPage == Page.APPS) {
            showApplicationPage()
        } else {
            showPage(initialPage)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildShell()
    }

    override fun onDestroy() {
        displayStatusRunnable?.let { mainHandler.removeCallbacks(it) }
        displayStatusRunnable = null
        settingConfirmationRunnable?.let { mainHandler.removeCallbacks(it) }
        settingConfirmationRunnable = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pageScrollView?.let { pageScrollPositions[currentPage] = it.scrollY }
        outState.putString(STATE_PAGE, currentPage.name)
        outState.putBoolean(STATE_SIDEBAR_COLLAPSED, sidebarCollapsed)
        outState.putInt(STATE_SCROLL, pageScrollPositions[currentPage] ?: 0)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!::contentHost.isInitialized || currentPage != Page.HOME || appPanel != null) return
        if (homeStatusBody != null) {
            CoverDisplayConfig.requestStatus(this)
            updateHomeStatus()
            return
        }
        openHomePage()
    }

    private fun isCoverRotation270(): Boolean {
        val rotation = window.decorView.display?.rotation
            ?: display?.rotation
            ?: return false
        return rotation == Surface.ROTATION_270
    }

    private fun buildShell(): View =
        if (isCoverRotation270()) buildRotation270Shell() else buildDefaultShell()

    private fun buildDefaultShell(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(palette.background)
        }
        shellRoot = root
        sidebarSlot = FrameLayout(this)
        sidebar = buildSidebar()
        sidebarSlot.addView(
            sidebar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.TOP }
        )
        root.addView(
            sidebarSlot,
            LinearLayout.LayoutParams(sidebarWidth(), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        contentHost = FrameLayout(this).apply {
            clipToPadding = false
        }
        root.addView(
            contentHost,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        )
        attachShellInsets(root)
        return root
    }

    private fun buildRotation270Shell(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(palette.background)
            clipChildren = true
        }
        shellRoot = root
        contentHost = FrameLayout(this).apply {
            clipToPadding = false
        }
        root.addView(
            contentHost,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        sidebarSlot = FrameLayout(this)
        sidebar = buildRotation270Sidebar()
        sidebarSlot.addView(
            sidebar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            sidebarSlot,
            FrameLayout.LayoutParams(66, 379, Gravity.TOP or Gravity.START)
        )
        attachShellInsets(root)
        return root
    }

    private fun attachShellInsets(root: ViewGroup) {
        root.setOnApplyWindowInsetsListener { _, insets ->
            lastInsets = insets
            applyShellInsets(insets)
            insets
        }
        sidebarSlot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            lastInsets?.let(::applyShellInsets)
        }
        root.requestApplyInsets()
    }

    private fun applyShellInsets(insets: WindowInsets) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        val cutoutRects = insets.displayCutout?.boundingRects.orEmpty().map { rect ->
            CoverCutoutRect(rect.left, rect.top, rect.right, rect.bottom)
        }
        if (isCoverRotation270()) {
            val band = applyRotation270Sidebar(cutoutRects)
            val leftPad = band.width
            if (
                contentHost.paddingLeft != leftPad ||
                contentHost.paddingTop != bars.top ||
                contentHost.paddingRight != bars.right ||
                contentHost.paddingBottom != 0
            ) {
                contentHost.setPadding(leftPad, bars.top, bars.right, 0)
            }
        } else {
            applySidebarPlacement(
                coverControlSidebarPlacement(
                    sidebarWidthPx = sidebarWidth(),
                    cutoutRects = cutoutRects,
                    systemBarLeftPx = bars.left,
                    systemBarTopPx = bars.top,
                    innerPadTopPx = dp(12),
                    innerPadHorizontalPx = if (sidebarCollapsed) dp(4) else dp(10)
                )
            )
            val slotParams = sidebarSlot.layoutParams
            val slotWidth = sidebarWidth()
            if (slotParams.width != slotWidth) {
                slotParams.width = slotWidth
                sidebarSlot.layoutParams = slotParams
            }
            if (
                contentHost.paddingTop != bars.top ||
                contentHost.paddingRight != bars.right ||
                contentHost.paddingBottom != 0 ||
                contentHost.paddingLeft != 0
            ) {
                contentHost.setPadding(0, bars.top, bars.right, 0)
            }
        }
    }

    private fun applyRotation270Sidebar(cutoutRects: List<CoverCutoutRect>): CoverCutoutRect {
        val windowWidth = shellRoot.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val windowHeight = shellRoot.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val live = coverLiveIslandBand(windowWidth, windowHeight, cutoutRects)
            ?: CoverCutoutRect(0, 0, 66, 379)
        val paint = coverRotation270SidebarPaintBand(live)
        val params = (sidebarSlot.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(paint.width, paint.height)
        if (
            params.width != paint.width ||
            params.height != paint.height ||
            params.leftMargin != paint.left ||
            params.topMargin != paint.top
        ) {
            params.width = paint.width
            params.height = paint.height
            params.leftMargin = paint.left
            params.topMargin = paint.top
            params.gravity = Gravity.TOP or Gravity.START
            sidebarSlot.layoutParams = params
        }
        if (
            sidebar.paddingLeft != 0 ||
            sidebar.paddingTop != 0 ||
            sidebar.paddingRight != 0 ||
            sidebar.paddingBottom != 0
        ) {
            sidebar.setPadding(0, 0, 0, 0)
        }
        return live
    }

    private fun applySidebarPlacement(placement: CoverControlSidebarPlacement) {
        val params = (sidebar.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        if (
            params.height != ViewGroup.LayoutParams.MATCH_PARENT ||
            params.topMargin != 0 ||
            params.gravity != Gravity.TOP
        ) {
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.topMargin = 0
            params.gravity = Gravity.TOP
            sidebar.layoutParams = params
        }
        if (
            sidebar.paddingLeft != placement.padLeftPx ||
            sidebar.paddingTop != placement.padTopPx ||
            sidebar.paddingRight != placement.padRightPx ||
            sidebar.paddingBottom != placement.padBottomPx
        ) {
            sidebar.setPadding(
                placement.padLeftPx,
                placement.padTopPx,
                placement.padRightPx,
                placement.padBottomPx
            )
        }
        val sidebarGroup = sidebar as? LinearLayout ?: return
        for (index in 0 until sidebarGroup.childCount) {
            val child = sidebarGroup.getChildAt(index) ?: continue
            when (child.tag) {
                SIDEBAR_BRAND_TAG -> {
                    val visible = if (sidebarCollapsed) View.GONE else View.VISIBLE
                    if (child.visibility != visible) child.visibility = visible
                }
                SIDEBAR_SPACER_TAG -> {
                    val spacerParams = child.layoutParams as? LinearLayout.LayoutParams ?: continue
                    if (spacerParams.weight != 1f || spacerParams.height != 0) {
                        spacerParams.weight = 1f
                        spacerParams.height = 0
                        child.layoutParams = spacerParams
                    }
                }
                SIDEBAR_ITEM_TAG -> {
                    val itemParams = child.layoutParams as? LinearLayout.LayoutParams ?: continue
                    val itemHeight = dp(sidebarItemHeightDp())
                    if (itemParams.height != itemHeight) {
                        itemParams.height = itemHeight
                        child.layoutParams = itemParams
                    }
                }
            }
        }
    }

    private fun buildRotation270Sidebar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        setBackgroundColor(palette.sidebar)
        setPadding(0, ROTATION_270_NAV_ITEM_INSET_PX, 0, ROTATION_270_NAV_ITEM_INSET_PX)
        clipToPadding = false
        Page.entries.forEach { page ->
            val button = islandNavButton(page)
            navigationButtons[page] = button
            addView(
                button,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ROTATION_270_NAV_ITEM_HEIGHT_PX
                ).apply {
                    marginStart = ROTATION_270_NAV_ITEM_INSET_PX
                    marginEnd = ROTATION_270_NAV_ITEM_INSET_PX
                    topMargin = ROTATION_270_NAV_ITEM_GAP_PX
                    bottomMargin = ROTATION_270_NAV_ITEM_GAP_PX
                }
            )
        }
        addView(
            View(context).apply {
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    private fun islandNavButton(page: Page): ImageButton = ImageButton(this).apply {
        stateListAnimator = null
        elevation = 0f
        minimumWidth = 0
        minimumHeight = 0
        backgroundTintList = null
        background = islandIconBackground(false)
        setImageResource(sidebarIcon(page))
        imageTintList = ColorStateList.valueOf(palette.secondaryText)
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = false
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(
                    0,
                    0,
                    view.width,
                    view.height,
                    ROTATION_270_NAV_CORNER_PX.toFloat()
                )
            }
        }
        setPadding(
            ROTATION_270_NAV_ICON_PAD_PX,
            ROTATION_270_NAV_ICON_PAD_PX,
            ROTATION_270_NAV_ICON_PAD_PX,
            ROTATION_270_NAV_ICON_PAD_PX
        )
        contentDescription = page.shortTitle
        setOnClickListener {
            animate().cancel()
            animate()
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(70)
                .withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                .start()
            when (page) {
                Page.HOME -> openHomePage()
                Page.APPS -> showApplicationPage()
                Page.SETTINGS -> openSettingsPage()
                else -> showPage(page)
            }
        }
    }

    private fun islandIconBackground(selected: Boolean): android.graphics.drawable.Drawable {
        val fill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ROTATION_270_NAV_CORNER_PX.toFloat()
            setColor(if (selected) palette.primaryContainer else Color.TRANSPARENT)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ROTATION_270_NAV_CORNER_PX.toFloat()
            setColor(Color.WHITE)
        }
        val ripple = android.content.res.ColorStateList.valueOf(
            Color.argb(56, 255, 255, 255)
        )
        return android.graphics.drawable.RippleDrawable(ripple, fill, mask)
    }

    private fun buildSidebar(
        iconOnly: Boolean = sidebarCollapsed,
        showToggle: Boolean = true
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP
        setBackgroundColor(palette.sidebar)
        val brand = label("FlexUnlock", 14f, palette.text).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(3))
            tag = SIDEBAR_BRAND_TAG
        }
        brand.visibility = if (iconOnly) View.GONE else View.VISIBLE
        addView(brand, matchWrap())
        Page.entries.forEach { page ->
            val button = navigationButton(page)
            navigationButtons[page] = button
            val item = if (iconOnly) {
                collapsedNavigationItem(page, button)
            } else {
                button
            }
            item.tag = SIDEBAR_ITEM_TAG
            addView(
                item,
                if (isCoverRotation270()) {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                } else {
                    sidebarItemParams()
                }
            )
        }
        if (showToggle) {
            addView(
                View(context).apply { tag = SIDEBAR_SPACER_TAG },
                LinearLayout.LayoutParams(1, 0, 1f)
            )
            addView(buildSidebarToggleSlot(), sidebarToggleSlotParams())
        }
    }

    private fun buildSidebarToggleSlot(): View = FrameLayout(this).apply {
        clipChildren = false
        isClickable = true
        isFocusable = true
        contentDescription = if (sidebarCollapsed) "展开侧边栏" else "收起侧边栏"
        setOnClickListener { setSidebarCollapsed(!sidebarCollapsed) }
        addView(
            ImageButton(context).apply {
                stateListAnimator = null
                elevation = 0f
                isClickable = false
                isFocusable = false
                backgroundTintList = null
                background = roundedBackground(Color.TRANSPARENT, Color.TRANSPARENT, 12)
                setImageResource(
                    if (sidebarCollapsed) {
                        R.drawable.ic_sidebar_expand
                    } else {
                        R.drawable.ic_sidebar_collapse
                    }
                )
                imageTintList = ColorStateList.valueOf(palette.secondaryText)
                scaleType = android.widget.ImageView.ScaleType.CENTER
                setPadding(0, 0, 0, 0)
            },
            FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)
        )
    }

    private fun collapsedNavigationItem(page: Page, button: Button): View =
        FrameLayout(this).apply {
            contentDescription = page.shortTitle
            button.text = ""
            button.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            button.compoundDrawableTintList = null
            addView(
                button,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                ImageView(context).apply {
                    isClickable = false
                    isFocusable = false
                    setImageResource(sidebarIcon(page))
                    imageTintList = ColorStateList.valueOf(palette.secondaryText)
                    scaleType = android.widget.ImageView.ScaleType.CENTER
                    button.tag = this
                },
                FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
            )
        }

    private fun navigationButton(page: Page): Button = Button(this).apply {
        useFlatSurface()
        text = page.shortTitle
        textSize = 12f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), dp(4), dp(4), dp(4))
        gravity = Gravity.CENTER
        setTextColor(palette.secondaryText)
        contentDescription = page.shortTitle
        if (sidebarCollapsed) {
            setCompoundDrawablesWithIntrinsicBounds(sidebarIcon(page), 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(palette.secondaryText)
        } else {
            setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            compoundDrawableTintList = null
        }
        compoundDrawablePadding = 0
        setOnClickListener {
            when (page) {
                Page.HOME -> openHomePage()
                Page.APPS -> showApplicationPage()
                Page.SETTINGS -> openSettingsPage()
                else -> showPage(page)
            }
        }
    }

    private fun setSidebarCollapsed(collapsed: Boolean) {
        if (isCoverRotation270()) return
        if (sidebarCollapsed == collapsed) return
        sidebarCollapsed = collapsed
        navigationButtons.clear()
        val replacement = buildSidebar()
        sidebarSlot.removeAllViews()
        sidebar = replacement
        sidebarSlot.addView(
            sidebar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.TOP }
        )
        sidebarSlot.layoutParams = LinearLayout.LayoutParams(
            sidebarWidth(),
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        lastInsets?.let(::applyShellInsets) ?: shellRoot.requestApplyInsets()
        updateNavigationSelection()
    }

    private fun sidebarIcon(page: Page): Int = when (page) {
        Page.HOME -> R.drawable.ic_sidebar_home
        Page.APPS -> R.drawable.ic_sidebar_apps
        Page.TILES -> R.drawable.ic_sidebar_tiles
        Page.SETTINGS -> R.drawable.ic_sidebar_settings
        Page.THEME -> if (ModuleThemeStore.isDark(this)) {
            R.drawable.ic_sidebar_theme
        } else {
            R.drawable.ic_sidebar_theme_sun
        }
        Page.CHARITY -> R.drawable.ic_sidebar_charity
        Page.ABOUT -> R.drawable.ic_sidebar_about
    }

    private fun showPage(page: Page) {
        if (page == Page.APPS) {
            showApplicationPage()
            return
        }
        pageScrollView?.let { pageScrollPositions[currentPage] = it.scrollY }
        currentPage = page
        val retainedPanel = appPanel
        contentHost.removeAllViews()
        contentHost.alpha = 0.88f
        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(16))
        }
        pageScrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            viewTreeObserver.addOnScrollChangedListener {
                if (!restoringPageScroll) {
                    pageScrollPositions[currentPage] = scrollY
                }
            }
            addView(
                pageContainer,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        contentHost.addView(
            pageScrollView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        updateNavigationSelection()
        pageContainer.addView(label(page.title, 20f, palette.text).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, matchWrap())
        when (page) {
            Page.HOME -> renderHome()
            Page.APPS -> Unit
            Page.TILES -> renderTiles()
            Page.SETTINGS -> renderSettings()
            Page.THEME -> renderTheme()
            Page.CHARITY -> renderCharity()
            Page.ABOUT -> renderAbout()
        }
        restorePageScroll(page)
        animateContentIn()
        if (retainedPanel?.parent == null) {
            appPanel = retainedPanel
        }
    }

    private fun restorePageScroll(page: Page) {
        val scrollY = pageScrollPositions[page] ?: return
        val scroll = pageScrollView ?: return
        restoringPageScroll = true
        val apply = Runnable {
            if (pageScrollView === scroll) scroll.scrollTo(0, scrollY)
            restoringPageScroll = false
        }
        if (scroll.viewTreeObserver.isAlive) {
            scroll.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (scroll.viewTreeObserver.isAlive) {
                            scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        }
                        apply.run()
                    }
                }
            )
        }
        scroll.post(apply)
    }

    private fun updateNavigationSelection() {
        navigationButtons.forEach { (candidate, view) ->
            val selected = candidate == currentPage
            val foreground = if (selected) palette.onPrimaryContainer else palette.secondaryText
            view.isSelected = selected
            when (view) {
                is ImageButton -> {
                    view.imageTintList = ColorStateList.valueOf(foreground)
                    view.backgroundTintList = null
                    view.background = islandIconBackground(selected)
                }
                is Button -> {
                    view.setTextColor(foreground)
                    view.compoundDrawableTintList = null
                    (view.tag as? ImageView)?.imageTintList = ColorStateList.valueOf(foreground)
                    view.backgroundTintList = null
                    view.background = roundedBackground(
                        if (selected) palette.primaryContainer else Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        14
                    )
                }
            }
        }
    }

    private fun renderHome() {
        val status = CoverDisplayConfig.readStatus(this)
        homeStatusBody = addCard(
            "外屏状态",
            status?.let(::displayStatusSummary)
                ?: "等待 system_server 返回外屏状态。"
        )
        addAction("外屏设置") { openSettingsPage() }
        addSectionTitle("应用显示")
        addCard(
            "逐 App 显示设置",
            "管理外屏应用，并为电话等应用设置全屏百分比或弹窗宽高百分比。"
        )
        addAction("应用缩放") { showApplicationPage() }
    }

    private fun showApplicationPage() {
        pageScrollView?.let { pageScrollPositions[currentPage] = it.scrollY }
        pageScrollView = null
        currentPage = Page.APPS
        updateNavigationSelection()
        if (appPanel == null) {
            contentHost.removeAllViews()
            appPanel = CoverLaunchAllowlistPanel(this, palette) {
                Toast.makeText(this, "已保存，重新打开 App 后生效", Toast.LENGTH_SHORT).show()
            }
        } else {
            (appPanel?.parent as? ViewGroup)?.removeView(appPanel)
            contentHost.removeAllViews()
        }
        contentHost.alpha = 0.88f
        contentHost.addView(
            appPanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        animateContentIn()
    }

    private fun rebuildShell() {
        pageScrollView?.let { pageScrollPositions[currentPage] = it.scrollY }
        val selectedPage = currentPage
        palette = ModuleThemeStore.palette(this)
        ModuleThemeStore.configureEdgeToEdge(this, palette.background)
        val retainedPanel = appPanel
        appPanel = null
        pageScrollView = null
        navigationButtons.clear()
        lastInsets = null
        setContentView(buildShell())
        appPanel = retainedPanel
        if (selectedPage == Page.APPS) showApplicationPage() else showPage(selectedPage)
    }

    private fun sidebarItemParams(topMarginDp: Int = 2): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(sidebarItemHeightDp())
        ).apply {
            topMargin = if (sidebarCollapsed) 0 else dp(topMarginDp)
        }

    private fun sidebarToggleSlotParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(40)
        )

    private fun sidebarItemHeightDp(): Int = when {
        sidebarCollapsed -> 38
        resources.configuration.screenHeightDp <= 320 -> 26
        resources.configuration.screenHeightDp <= 360 -> 30
        else -> 34
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun sidebarWidth(): Int = dp(
        when {
            sidebarCollapsed -> if (isLandscape()) 52 else 56
            isLandscape() -> 92
            else -> 104
        }
    )


    private fun renderTiles() {
        val selectedGrid = CoverQsGridConfig.read(this)
        addActionGrid(
            CoverQsGrid.entries.map { grid ->
                ActionOption(
                    label = if (grid == selectedGrid) {
                        "${grid.label} · 当前"
                    } else {
                        grid.label
                    },
                    emphasized = grid == selectedGrid
                ) {
                    if (grid != selectedGrid) {
                        selectQsGrid(grid)
                    } else {
                        Toast.makeText(
                            this,
                            "快捷设置已是 ${grid.label}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun renderSettings() {
        renderCoverDisplaySelection()
        renderDisplayMetricsControl()
        renderImeKeyboardControl()
        renderOutputModeControl()
        renderHalfModeControl()
        addSectionTitle("外屏会话")
        addActionGrid(
            listOf(
                ActionOption("启动 DeX") {
                    sendCoverAction(ACTION_ENABLE_COVER_DEX)
                },
                ActionOption("退出 DeX", emphasized = false) {
                    sendCoverAction(ACTION_DISABLE_COVER_DEX)
                }
            )
        )
        renderCoverLockscreenTimeoutControl()
        renderSystemUpdateControl()
        addSectionTitle("桌面显示")
        val hideCoverHomeStatusBar = pendingHomeStatusBarHidden
            ?: isCoverHomeStatusBarHidden(this)
        addCard(
            "顶部状态栏",
            if (hideCoverHomeStatusBar) {
                "仅外屏桌面：已隐藏"
            } else {
                "仅外屏桌面：正常显示"
            }
        )
        addAction(
            label = if (hideCoverHomeStatusBar) {
                "隐藏状态栏 · 已开启"
            } else {
                "隐藏状态栏 · 已关闭"
            },
            emphasized = hideCoverHomeStatusBar
        ) {
            setCoverHomeStatusBarHidden(!hideCoverHomeStatusBar)
        }
        addSectionTitle("图标大小")
        addCard(
            "桌面与应用抽屉",
            "分别调整图标目标上限；空间不足时会自动缩小，文件夹自动跟随。"
        )
        addIconSizeControl(
            title = "桌面图标",
            surface = COVER_ICON_SURFACE_HOME
        )
        addIconSizeControl(
            title = "应用抽屉图标",
            surface = COVER_ICON_SURFACE_DRAWER,
            topMargin = 12
        )
    }

    private fun renderCoverLockscreenTimeoutControl() {
        addSectionTitle("外屏锁屏页面自动息屏")
        val current = CoverDisplayConfig.readLockscreenTimeoutMillis(this)
        val options = listOf(
            30_000L to "30秒",
            60_000L to "1分钟",
            120_000L to "2分钟",
            1_980_000L to "33分钟",
            300_000L to "5分钟",
            600_000L to "10分钟",
            1_800_000L to "30分钟"
        )
        pageContainer.addView(Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(
                this@CoverDexControlActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options.map { it.second }
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also {
                        (it as TextView).setTextColor(palette.text)
                    }

                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View = super.getDropDownView(position, convertView, parent).also {
                    (it as TextView).apply {
                        setTextColor(palette.text)
                        setBackgroundColor(palette.surface)
                    }
                }
            }
            setSelection(options.indexOfFirst { it.first == current }.coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val (timeoutMillis, label) = options[position]
                    if (timeoutMillis != CoverDisplayConfig.readLockscreenTimeoutMillis(this@CoverDexControlActivity)) {
                        requestCoverLockscreenTimeout(timeoutMillis, label)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }, matchWrap())
    }

    private fun requestCoverLockscreenTimeout(timeoutMillis: Long, label: String) {
        CoverDisplayConfig.requestLockscreenTimeout(this, timeoutMillis)
        awaitSettingConfirmation(
            isApplied = {
                CoverDisplayConfig.readLockscreenTimeoutMillis(this) == timeoutMillis
            },
            successMessage = "外屏锁屏页面自动息屏已设为$label",
            failureMessage = "外屏锁屏页面自动息屏设置未生效，请确认模块已加载",
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun renderSystemUpdateControl() {
        addSectionTitle("系统更新")
        val blocked = CoverDisplayConfig.readSystemUpdateBlocked(this)
        addCard(
            "停止三星系统更新",
            "仅停止系统OTA检测与下载，不影响Galaxy Store或Play商店应用更新。"
        )
        addAction(
            label = if (blocked) "系统更新 · 已停止" else "系统更新 · 允许",
            emphasized = blocked
        ) {
            requestSystemUpdateBlocked(!blocked)
        }
    }

    private fun renderImeKeyboardControl() {
        val compact = CoverDisplayConfig.readImeCompact(this)
        val percent = CoverDisplayConfig.readImeCompactPercent(this)
        addSectionTitle("外屏输入法布局")
        addCard(
            if (compact) "紧凑键盘 · $percent%" else "铺满键盘",
            if (compact) "减少键盘占用高度，保留更多输入框区域。" else "恢复输入法原始铺满布局。"
        )
        addAction(
            label = if (compact) "切换为铺满键盘" else "切换为紧凑键盘",
            emphasized = compact
        ) {
            requestImeCompact(!compact)
        }
        addImeCompactPercentControl(percent)
    }

    private fun requestImeCompact(compact: Boolean) {
        CoverDisplayConfig.requestImeCompact(this, compact)
        awaitSettingConfirmation(
            isApplied = { CoverDisplayConfig.readImeCompact(this) == compact },
            successMessage = if (compact) "已切换为紧凑键盘" else "已切换为铺满键盘",
            failureMessage = "输入法布局切换未生效，请确认模块已加载",
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun requestImeCompactPercent(percent: Int) {
        val compact = CoverDisplayConfig.readImeCompact(this)
        CoverDisplayConfig.requestImeCompact(this, compact, percent)
        awaitSettingConfirmation(
            isApplied = { CoverDisplayConfig.readImeCompactPercent(this) == percent },
            successMessage = "紧凑键盘比例已设为 $percent%",
            failureMessage = "紧凑键盘比例设置未生效，请确认模块已加载",
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun addImeCompactPercentControl(currentPercent: Int) {
        var submittedPercent = currentPercent
        val valueLabel = label("$currentPercent%", 14f, palette.text)
        val slider = SeekBar(this).apply {
            min = CoverDisplayConfig.MIN_IME_COMPACT_PERCENT
            max = CoverDisplayConfig.MAX_IME_COMPACT_PERCENT
            progress = currentPercent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    valueLabel.text = "$progress%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    if (seekBar.progress == submittedPercent) return
                    submittedPercent = seekBar.progress
                    requestImeCompactPercent(seekBar.progress)
                }
            })
        }
        pageContainer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(8))
                background = roundedBackground(palette.surface, palette.outline, 14)
                addView(
                    LinearLayout(this@CoverDexControlActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            label("紧凑键盘比例", 15f, palette.text),
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        )
                        addView(valueLabel, LinearLayout.LayoutParams(-2, -2))
                    },
                    matchWrap()
                )
                addView(slider, matchWrap())
            },
            matchWrap().apply { topMargin = dp(8) }
        )
    }

    private fun requestSystemUpdateBlocked(blocked: Boolean) {
        CoverDisplayConfig.requestSystemUpdateBlocked(this, blocked)
        awaitSettingConfirmation(
            isApplied = { CoverDisplayConfig.readSystemUpdateBlocked(this) == blocked },
            successMessage = if (blocked) "三星系统更新已停止" else "三星系统更新已恢复",
            failureMessage = "系统更新状态切换失败，请确认模块已加载",
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun renderOutputModeControl() {
        addSectionTitle("外屏显示模式")
        val transaction = CoverQsModeConfig.readTransaction(this)
        val selected = resolveCoverOutputMode(
            CoverDisplayConfig.readFullDex(this),
            transaction.applied
        )
        val state = when (transaction.state) {
            CoverQsTransition.ORIGINAL -> if (selected == CoverOutputMode.FULL_DEX) {
                "完整 DeX 桌面"
            } else {
                "原始外屏下拉"
            }
            CoverQsTransition.FULL -> "完整内屏下拉"
            CoverQsTransition.ENABLING -> "正在切换到完整模式"
            CoverQsTransition.DISABLING -> "正在恢复原始模式"
            CoverQsTransition.RECOVERING -> "正在回滚未完成切换"
        }
        addCard("当前模式", state)
        addActionGrid(
            listOf(
                CoverOutputMode.ORIGINAL to "原始下拉",
                CoverOutputMode.FULL_QS to "完整下拉",
                CoverOutputMode.FULL_DEX to "完整 DeX"
            ).map { (mode, label) ->
                ActionOption(
                    label = label,
                    emphasized = mode == selected && transaction.state.isStable()
                ) {
                    if (transaction.state.isStable() && mode != selected) {
                        when (mode) {
                            CoverOutputMode.ORIGINAL -> if (selected == CoverOutputMode.FULL_DEX) {
                                requestFullDex(false)
                            } else {
                                requestQsMode(CoverQsMode.ORIGINAL)
                            }
                            CoverOutputMode.FULL_QS -> requestQsMode(CoverQsMode.FULL)
                            CoverOutputMode.FULL_DEX -> requestFullDex(true)
                        }
                    }
                }
            }
        )
        if (selected == CoverOutputMode.FULL_QS && transaction.isStableFull) {
            renderCameraModeControl()
        }
    }

    private fun renderHalfModeControl() {
        val enabled = CoverDisplayConfig.readHalfMode(this)
        addSectionTitle("隐藏异形区域")
        addAction(
            label = if (enabled) "隐藏异形区域 · 已开启" else "隐藏异形区域 · 已关闭",
            emphasized = enabled
        ) {
            requestHalfMode(!enabled)
        }
    }

    private fun requestHalfMode(enabled: Boolean) {
        val previousRevision = CoverDisplayConfig.readStatus(this)?.revision ?: 0L
        CoverDisplayConfig.requestHalfMode(this, enabled)
        awaitSettingConfirmation(
            isApplied = {
                val status = CoverDisplayConfig.readStatus(this) ?: return@awaitSettingConfirmation false
                status.revision != previousRevision && status.halfModeEnabled == enabled
            },
            successMessage = if (enabled) "异形区域已隐藏" else "异形区域已恢复",
            failureMessage = "异形区域切换失败，请确认模块已加载",
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun renderCameraModeControl() {
        addSectionTitle("完整模式相机")
        val mode = CoverDisplayConfig.readCameraMode(this)
        addCard(
            "相机布局",
            if (mode == CoverCameraMode.INNER) {
                "使用内屏相机布局，适配完整模式外屏画布"
            } else {
                "使用系统原始外屏相机布局"
            }
        )
        addActionGrid(
            listOf(
                CoverCameraMode.INNER to "内屏布局",
                CoverCameraMode.ORIGINAL to "外屏布局"
            ).map { (candidate, label) ->
                ActionOption(
                    label = if (candidate == mode) "$label · 当前" else label,
                    emphasized = candidate == mode
                ) {
                    if (candidate != CoverDisplayConfig.readCameraMode(this)) {
                        requestCameraMode(candidate)
                    }
                }
            }
        )
    }

    private fun requestCameraMode(mode: CoverCameraMode) {
        CoverDisplayConfig.requestCameraMode(this, mode)
        awaitSettingConfirmation(
            isApplied = { CoverDisplayConfig.readCameraMode(this) == mode },
            successMessage = "已切换为${if (mode == CoverCameraMode.INNER) "内屏相机布局" else "原始外屏相机"}",
            failureMessage = "相机布局切换未生效，请重启设备以加载新版模块",
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun requestQsMode(mode: CoverQsMode) {
        CoverQsModeConfig.request(this, mode)
        awaitSettingConfirmation(
            isApplied = {
                val current = CoverQsModeConfig.readTransaction(this)
                current.state.isStable() && current.applied == mode
            },
            successMessage = "快捷设置已切换为${if (mode == CoverQsMode.ORIGINAL) "原始" else "完整"}",
            failureMessage = "快捷设置模式未生效，系统已保持或恢复原始通道",
            attempts = 60,
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun CoverQsTransition.isStable(): Boolean =
        this == CoverQsTransition.ORIGINAL || this == CoverQsTransition.FULL

    private fun renderDisplayMetricsControl() {
        addSectionTitle("显示参数")
        val status = CoverDisplayConfig.readStatus(this)
        val snapshot = (status?.resolution as? CoverDisplayResolution.Resolved)?.snapshot
        if (status == null || snapshot == null) {
            addCard("外屏不可用", "识别到外屏后可修改该显示器的分辨率和 DPI。")
            return
        }
        val qsTransaction = CoverQsModeConfig.readTransaction(this)
        val externalTarget = snapshot.type == 2 || snapshot.type == 5 || snapshot.type == 6
        val fullQs = qsTransaction.isStableFull && !externalTarget
        val fullSnapshot = qsTransaction.snapshots.firstOrNull { it.displayId == 1 }
        val configured = if (fullQs) {
            CoverDisplayConfig.readFullQsOverride(this)
        } else {
            status.displayOverride
        }
        val nativeDensity = if (fullQs) {
            fullSnapshot?.initialDensity
        } else {
            status.candidates.firstOrNull { it.displayId == snapshot.id }
                ?.densityDpi
                ?.takeIf { it > 0 }
        } ?: resources.displayMetrics.densityDpi
        val currentDensity = configured?.densityDpi ?: nativeDensity
        addCard(
            "当前输出",
            "display ${snapshot.id} · ${snapshot.width}×${snapshot.height} · $currentDensity dpi"
        )
        val width = metricsField((configured?.width ?: snapshot.width).toString(), "宽")
        val height = metricsField((configured?.height ?: snapshot.height).toString(), "高")
        val dpi = metricsField(currentDensity.toString(), "DPI")
        val mode = getSystemService(DisplayManager::class.java)
            ?.getDisplay(snapshot.id)
            ?.mode
        val fullEdge = minOf(
            fullSnapshot?.initialWidth ?: snapshot.width,
            fullSnapshot?.initialHeight ?: snapshot.height
        )
        val nativeWidth = if (fullQs) fullEdge - 1 else mode?.physicalWidth ?: snapshot.width
        val nativeHeight = if (fullQs) fullEdge else mode?.physicalHeight ?: snapshot.height
        val fullHdHeight = 1080
        val fullHdWidth = if (fullQs) {
            fullHdHeight - 1
        } else {
            ((fullHdHeight.toLong() * nativeWidth / nativeHeight + 1) / 2 * 2).toInt()
        }
        val fullHdDensity = (nativeDensity * fullHdHeight.toFloat() / nativeHeight)
            .toInt().coerceIn(120, 960)
        val presets = mutableListOf<CoverDisplayOverride?>(
            null,
            CoverDisplayOverride(nativeWidth, nativeHeight, nativeDensity),
            CoverDisplayOverride(
                nativeWidth,
                nativeHeight,
                (nativeDensity * 1.18f).toInt().coerceIn(120, 960)
            )
        )
        val presetLabels = mutableListOf(
            "自定义",
            "原始 · ${nativeWidth}×${nativeHeight} · $nativeDensity DPI",
            "大字号 · ${nativeWidth}×${nativeHeight} · ${presets[2]!!.densityDpi} DPI"
        )
        if (externalTarget) {
            listOf(5f / 6f, 2f / 3f).forEach { scale ->
                val scaledWidth = (nativeWidth * scale).toInt() / 2 * 2
                val scaledHeight = (nativeHeight * scale).toInt() / 2 * 2
                if (scaledWidth >= 480 && scaledHeight >= 320) {
                    val value = CoverDisplayOverride(
                        scaledWidth,
                        scaledHeight,
                        (nativeDensity * scale).toInt().coerceIn(120, 960)
                    )
                    if (presets.none { it?.let { preset ->
                            preset.width == value.width && preset.height == value.height
                        } == true
                    }) {
                        presets += value
                        presetLabels += "外接 · ${value.width}×${value.height} · ${value.densityDpi} DPI"
                    }
                }
            }
        }
        if (!externalTarget || presets.none { it?.let { preset ->
                preset.width == fullHdWidth && preset.height == fullHdHeight
            } == true
        }) {
            presets += CoverDisplayOverride(fullHdWidth, fullHdHeight, fullHdDensity)
            presetLabels += "1080P 等比 · $fullHdWidth×$fullHdHeight · $fullHdDensity DPI"
        }
        pageContainer.addView(Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(
                this@CoverDexControlActivity,
                android.R.layout.simple_spinner_dropdown_item,
                presetLabels
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).also {
                        (it as TextView).setTextColor(palette.text)
                    }

                override fun getDropDownView(
                    position: Int,
                    convertView: View?,
                    parent: ViewGroup
                ): View = super.getDropDownView(position, convertView, parent).also {
                    (it as TextView).apply {
                        setTextColor(palette.text)
                        setBackgroundColor(palette.surface)
                    }
                }
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    presets[position]?.let { value ->
                        width.setText(value.width.toString())
                        height.setText(value.height.toString())
                        dpi.setText(value.densityDpi.toString())
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }, matchWrap().apply { topMargin = dp(8) })
        pageContainer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(width, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(height, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    marginStart = dp(8)
                })
                addView(dpi, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    marginStart = dp(8)
                })
            },
            matchWrap().apply { topMargin = dp(8) }
        )
        val metricActions = mutableListOf(
            ActionOption("应用") {
                val value = CoverDisplayOverride(
                    width.text.toString().toIntOrNull() ?: 0,
                    height.text.toString().toIntOrNull() ?: 0,
                    dpi.text.toString().toIntOrNull() ?: 0
                )
                if (value.width !in 480..7680 || value.height !in 320..4320 || value.densityDpi !in 120..960) {
                    Toast.makeText(this, "分辨率或 DPI 超出范围", Toast.LENGTH_SHORT).show()
                } else if (!displayMetricsAspectMatches(value, nativeWidth, nativeHeight)) {
                    Toast.makeText(
                        this,
                        if (fullQs) {
                            "完整模式分辨率必须保持近似正方形"
                        } else {
                            "分辨率必须保持外屏原生宽高比"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    requestDisplayMetrics(value, "已应用")
                }
            }
        )
        if (configured != null) {
            metricActions += ActionOption("恢复默认", emphasized = false) {
                requestDisplayMetrics(null, "已恢复默认")
            }
        }
        addActionGrid(metricActions, topMargin = 8)
        renderExternalDisplayModeControl(snapshot)
    }

    private fun metricsField(value: String, hintText: String): EditText = EditText(this).apply {
        setText(value)
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine(true)
        textSize = 13f
        setTextColor(palette.text)
        setHintTextColor(palette.secondaryText)
        background = roundedBackground(palette.surface, palette.outline, 12)
        setPadding(dp(10), 0, dp(10), 0)
    }

    private fun requestDisplayMetrics(value: CoverDisplayOverride?, successMessage: String) {
        val expected = CoverDisplayConfig.encodeOverride(value)
        CoverDisplayConfig.requestMetrics(this, value)
        awaitSettingConfirmation(
            isApplied = {
                val applied = if (CoverQsModeConfig.readTransaction(this).isStableFull) {
                    CoverDisplayConfig.readFullQsOverride(this)
                } else {
                    CoverDisplayConfig.readStatus(this)?.displayOverride
                }
                CoverDisplayConfig.encodeOverride(applied) == expected
            },
            successMessage = successMessage,
            failureMessage = "显示参数未生效，请确认模块已加载",
            onFinished = {
                refreshCurrentPage(Page.SETTINGS)
            }
        )
    }

    private fun renderExternalDisplayModeControl(snapshot: CoverDisplaySnapshot) {
        if (snapshot.type != 2 && snapshot.type != 5 && snapshot.type != 6) return
        val status = CoverDisplayConfig.readStatus(this) ?: return
        val preferred = status.preferredDisplayMode
        val supported = status.supportedDisplayModes
        val resolutions = supported
            .sortedWith(compareByDescending<CoverDisplayMode> { it.width.toLong() * it.height })
            .distinctBy { it.width to it.height }
        val refreshModes = supported
            .sortedByDescending { it.refreshRate }
            .distinctBy { it.refreshRate }

        addSectionTitle("外接分辨率")
        addActionGrid((listOf<CoverDisplayMode?>(null) + resolutions).map { resolution ->
            val selected = if (resolution == null) {
                preferred == null
            } else {
                preferred?.width == resolution.width && preferred.height == resolution.height
            }
            ActionOption(
                label = if (resolution == null) {
                    if (selected) "系统默认 · 当前" else "系统默认"
                } else {
                    buildString {
                        append("${resolution.width}×${resolution.height}")
                        if (selected) append(" · 当前")
                    }
                },
                emphasized = selected
            ) {
                if (!selected) {
                    requestRefreshMode(
                        resolution?.let { selectModeForResolution(supported, it, preferred) },
                        resolution?.let { "外接分辨率已设为 ${it.width}×${it.height}" }
                            ?: "外接分辨率已恢复系统默认"
                    )
                }
            }
        })

        addSectionTitle("外接刷新率")
        addActionGrid((listOf<CoverDisplayMode?>(null) + refreshModes).map { refreshMode ->
            val selected = if (refreshMode == null) {
                preferred == null
            } else {
                preferred?.let { kotlin.math.abs(it.refreshRate - refreshMode.refreshRate) < 0.01f } == true
            }
            ActionOption(
                label = if (refreshMode == null) {
                    if (selected) "系统默认 · 当前" else "系统默认"
                } else {
                    buildString {
                        append("${formatRefreshRate(refreshMode.refreshRate)} Hz")
                        if (selected) append(" · 当前")
                    }
                },
                emphasized = selected
            ) {
                if (!selected) {
                    requestRefreshMode(
                        refreshMode?.let { selectModeForRefresh(supported, it, preferred) },
                        refreshMode?.let { "外接刷新率已设为 ${formatRefreshRate(it.refreshRate)} Hz" }
                            ?: "外接刷新率已恢复系统默认"
                    )
                }
            }
        })
    }

    private fun formatRefreshRate(value: Float): String =
        if (kotlin.math.abs(value - value.toInt()) < 0.01f) value.toInt().toString()
        else "%.2f".format(value)

    private fun requestRefreshMode(mode: CoverDisplayMode?, successMessage: String? = null) {
        val previousRevision = CoverDisplayConfig.readStatus(this)?.revision ?: 0L
        CoverDisplayConfig.requestRefreshMode(this, mode)
        awaitSettingConfirmation(
            isApplied = {
                val status = CoverDisplayConfig.readStatus(this) ?: return@awaitSettingConfirmation false
                status.revision != previousRevision &&
                    displayModesMatch(status.preferredDisplayMode, mode)
            },
            successMessage = successMessage ?: mode?.let {
                "外接显示器已设为 ${it.width}×${it.height} · ${formatRefreshRate(it.refreshRate)} Hz"
            } ?: "外接显示器已恢复系统默认输出模式",
            failureMessage = "外接显示器输出模式未生效",
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun requestFullDex(enabled: Boolean) {
        val previousRevision = CoverDisplayConfig.readStatus(this)?.revision ?: 0L
        CoverDisplayConfig.requestFullDex(this, enabled)
        awaitSettingConfirmation(
            isApplied = {
                val status = CoverDisplayConfig.readStatus(this) ?: return@awaitSettingConfirmation false
                status.revision != previousRevision && status.fullDexEnabled == enabled
            },
            successMessage = if (enabled) "完整外屏 DeX 已开启" else "完整外屏 DeX 已关闭",
            failureMessage = "桌面模式未生效，请确认模块已加载",
            onFinished = {
                refreshCurrentPage(Page.SETTINGS)
            }
        )
    }

    private fun renderCoverDisplaySelection() {
        addSectionTitle("外屏选择")
        val status = CoverDisplayConfig.readStatus(this)
        if (status == null) {
            addCard(
                "正在读取外屏状态",
                "外屏由 system_server 统一识别；App 不会自行猜测显示器。"
            )
            addAction("刷新外屏状态", emphasized = false) {
                requestDisplayStatusRefresh()
            }
            return
        }

        val manual = status.manualIdentity
        val mode = if (manual == null) "自动识别" else "手动选择"
        addCard("当前模式 · $mode", displayStatusSummary(status))
        addSectionTitle("可信候选")
        if (status.candidates.isEmpty()) {
            addCard(
                "暂无可选外屏",
                "未发现可信的内置或有线显示器。"
            )
        } else {
            val manualToken = manual?.let(CoverDisplayConfig::encodeIdentity)
            addActionGrid(status.candidates.map { candidate ->
                val isManualCandidate =
                    manualToken == CoverDisplayConfig.encodeIdentity(candidate.identity)
                ActionOption(
                    label = buildString {
                        append(displayCandidateLabel(candidate))
                        if (isManualCandidate) append(" · 当前手动")
                    },
                    emphasized = isManualCandidate
                ) {
                    if (!isManualCandidate) {
                        requestDisplayMode(
                            candidate.identity,
                            "手动选择 ${displayCandidateLabel(candidate)}"
                        )
                    } else {
                        Toast.makeText(
                            this,
                            "该外屏已是当前手动选择",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
        }
        addActionGrid(
            buildList {
                if (manual != null) {
                    add(ActionOption("恢复自动识别", emphasized = false) {
                        requestDisplayMode(null, "自动识别")
                    })
                }
                add(ActionOption("刷新候选列表", emphasized = false) {
                    requestDisplayStatusRefresh()
                })
            },
            topMargin = 10
        )
    }

    private fun openHomePage() {
        showPage(Page.HOME)
        CoverDisplayConfig.requestStatus(this)
        displayStatusRunnable?.let(mainHandler::removeCallbacks)
        displayStatusRunnable = Runnable {
            displayStatusRunnable = null
            if (!isFinishing && !isDestroyed && currentPage == Page.HOME) {
                refreshCurrentPage(Page.HOME)
            }
        }.also { mainHandler.postDelayed(it, 250L) }
    }

    private fun openSettingsPage() {
        showPage(Page.SETTINGS)
        CoverDisplayConfig.requestStatus(this)
        scheduleDisplayStatusRefresh(delayMs = 250L, requestAgain = false)
    }

    private fun requestDisplayMode(
        identity: DisplayStableIdentity?,
        label: String
    ) {
        val previousRevision = CoverDisplayConfig.readStatus(this)?.revision ?: 0L
        val expectedIdentity = CoverDisplayConfig.encodeIdentity(identity)
        CoverDisplayConfig.requestMode(this, identity)
        Toast.makeText(this, "正在应用$label…", Toast.LENGTH_SHORT).show()
        awaitSettingConfirmation(
            isApplied = {
                val status = CoverDisplayConfig.readStatus(this) ?: return@awaitSettingConfirmation false
                status.revision != previousRevision &&
                    CoverDisplayConfig.encodeIdentity(status.manualIdentity) == expectedIdentity
            },
            successMessage = "${label}已生效",
            failureMessage = "${label}未生效，请确认模块已加载后重试",
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun requestDisplayStatusRefresh() {
        val previousRevision = CoverDisplayConfig.readStatus(this)?.revision ?: 0L
        CoverDisplayConfig.requestStatus(this)
        Toast.makeText(this, "正在刷新外屏状态…", Toast.LENGTH_SHORT).show()
        awaitSettingConfirmation(
            isApplied = {
                val revision = CoverDisplayConfig.readStatus(this)?.revision ?: return@awaitSettingConfirmation false
                revision != previousRevision
            },
            successMessage = "外屏状态已刷新",
            failureMessage = "未收到 system_server 响应，请确认模块已加载",
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun awaitSettingConfirmation(
        isApplied: () -> Boolean,
        successMessage: String,
        failureMessage: String,
        attempts: Int = 16,
        onFinished: () -> Unit = {}
    ) {
        settingConfirmationRunnable?.let(mainHandler::removeCallbacks)
        var attemptsRemaining = attempts
        val poll = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (runCatching(isApplied).getOrDefault(false)) {
                    settingConfirmationRunnable = null
                    Toast.makeText(
                        this@CoverDexControlActivity,
                        successMessage,
                        Toast.LENGTH_SHORT
                    ).show()
                    onFinished()
                    return
                }
                attemptsRemaining -= 1
                if (attemptsRemaining <= 0) {
                    settingConfirmationRunnable = null
                    Toast.makeText(
                        this@CoverDexControlActivity,
                        failureMessage,
                        Toast.LENGTH_LONG
                    ).show()
                    onFinished()
                    return
                }
                mainHandler.postDelayed(this, 150L)
            }
        }
        settingConfirmationRunnable = poll
        mainHandler.postDelayed(poll, 100L)
    }

    private fun refreshCurrentPage(page: Page) {
        if (currentPage != page) return
        when (page) {
            Page.HOME -> updateHomeStatus()
            Page.APPS -> Unit
            else -> showPage(page)
        }
    }

    private fun updateHomeStatus() {
        val status = CoverDisplayConfig.readStatus(this)
        homeStatusBody?.text = status?.let(::displayStatusSummary)
            ?: "等待 system_server 返回外屏状态。"
    }

    private fun scheduleDisplayStatusRefresh(
        delayMs: Long = 350L,
        requestAgain: Boolean = true
    ) {
        displayStatusRunnable?.let(mainHandler::removeCallbacks)
        if (requestAgain) CoverDisplayConfig.requestStatus(this)
        displayStatusRunnable = Runnable {
            displayStatusRunnable = null
            if (!isFinishing && !isDestroyed && currentPage == Page.SETTINGS) {
                refreshCurrentPage(Page.SETTINGS)
            }
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun displayStatusSummary(status: CoverDisplayStatus): String {
        val mode = if (status.manualIdentity == null) "自动" else "手动"
        val result = when (val resolution = status.resolution) {
            is CoverDisplayResolution.Resolved -> {
                val snapshot = resolution.snapshot
                val displayName = snapshot.name?.takeIf(String::isNotBlank) ?: "内置外屏"
                val source = when (resolution.source) {
                    CoverDisplaySelectionSource.AUTO -> "自动命中"
                    CoverDisplaySelectionSource.MANUAL -> "手动命中"
                    CoverDisplaySelectionSource.AUTO_FALLBACK -> "手动目标不可用，已回到自动"
                }
                "$displayName · ${snapshot.width}×${snapshot.height} · $source"
            }
            is CoverDisplayResolution.Ambiguous -> "存在多个同等可信候选，安全起见未猜测"
            is CoverDisplayResolution.Unavailable -> when (resolution.reason) {
                "default-display-missing", "default-display-size-missing" ->
                    "显示服务尚未就绪，保持原生行为"
                "manual-missing-auto-unavailable", "cover-display-missing" ->
                    "当前没有可信外屏，保持原生行为"
                else -> "外屏暂不可用，保持原生行为"
            }
        }
        return "模式：$mode\n解析结果：$result"
    }

    private fun displayCandidateLabel(candidate: CoverDisplayCandidateStatus): String {
        val name = candidate.name?.takeIf(String::isNotBlank) ?: "内置显示器"
        return "$name · ${candidate.width}×${candidate.height}"
    }

    private fun renderTheme() {
        val selected = ModuleThemeStore.selected(this)
        addCard("显示模式", "主题设置仅作用于模块 App，不修改系统或外屏应用主题。")
        addActionGrid(
            listOf(
                ModuleTheme.SYSTEM to "跟随系统",
                ModuleTheme.LIGHT to "浅色",
                ModuleTheme.DARK to "深色"
            ).map { (theme, title) ->
                ActionOption(
                    label = if (selected == theme) "$title（当前）" else title,
                    emphasized = selected == theme
                ) {
                    if (selected != theme) {
                        ModuleThemeStore.save(this, theme)
                        recreate()
                    } else {
                        Toast.makeText(
                            this,
                            "$title 已是当前主题",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun renderCharity() {
        addCard(
            "救助流浪猫 · 街猫项目",
            "通过街猫 App 云守护流浪猫，可用签到获得的免费爱心币投喂猫粮。从街猫入口正常充话费、点外卖或购物，也会返还爱心币，无需购买指定商品。"
        )
        addActionGrid(
            listOf(
                ActionOption("查看详情") { openUrl(CHARITY_PROJECT_PAGE) },
                ActionOption("复制链接", emphasized = false) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText("街猫公益项目", CHARITY_PROJECT_PAGE)
                    )
                    Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show()
                }
            )
        )
    }

    private fun renderAbout() {
        val installedVersion = currentVersion()
        pageContainer.addView(
            label(
                "在外屏运行原生桌面与 App，并提供旋转、磁贴和独立息屏控制。",
                14f,
                palette.secondaryText
            ).apply { setPadding(dp(2), 0, dp(2), dp(12)) },
            matchWrap()
        )
        addInfoRow("当前版本", installedVersion)
        addInfoRow("作者", "AndyNull")
        addSectionTitle("更新与发布")
        val updateStatus = label("", 13f, palette.onPrimaryContainer).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(
                palette.primaryContainer,
                Color.TRANSPARENT,
                14
            )
        }
        val releaseNotes = label("", 13f, palette.secondaryText).apply {
            visibility = View.GONE
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(palette.surface, palette.outline, 16)
        }
        val updateButton = actionButton("检查更新", true) { button ->
            button.isEnabled = false
            button.text = "正在检查…"
            updateStatus.visibility = View.VISIBLE
            updateStatus.setTextColor(palette.onPrimaryContainer)
            updateStatus.background = roundedBackground(
                palette.primaryContainer,
                Color.TRANSPARENT,
                14
            )
            updateStatus.text = "正在连接 GitHub Releases"
            releaseNotes.visibility = View.GONE
            Thread {
                val result = GitHubReleaseChecker.check(installedVersion)
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    button.isEnabled = true
                    result.onSuccess { checkResult ->
                        when (checkResult) {
                            is ReleaseCheckResult.UpdateAvailable -> {
                                val release = checkResult.release
                                updateStatus.setTextColor(palette.onPrimaryContainer)
                                updateStatus.text = "发现新版本 ${release.tag} · ${release.name}"
                                button.text = "前往 Releases"
                                button.setOnClickListener {
                                    openUrl(GitHubReleaseChecker.RELEASES_PAGE)
                                }
                                releaseNotes.text = buildString {
                                    append("${release.name} · ${release.tag}\n")
                                    append("发布时间：${release.publishedAt}\n\n")
                                    append(release.notes.ifBlank { "该 Release 未填写更新日志。" })
                                }
                                releaseNotes.visibility = View.VISIBLE
                            }

                            ReleaseCheckResult.UpToDate -> {
                                updateStatus.setTextColor(palette.onPrimaryContainer)
                                updateStatus.text = "当前已是最新版本"
                                button.text = "重新检查"
                                releaseNotes.visibility = View.GONE
                            }
                        }
                    }.onFailure { error ->
                        releaseNotes.visibility = View.GONE
                        updateStatus.setTextColor(palette.secondaryText)
                        updateStatus.background = roundedBackground(
                            palette.surface,
                            palette.outline,
                            14
                        )
                        updateStatus.text =
                            "检查失败：${GitHubReleaseChecker.failureMessage(error)}"
                        button.text = "重试检查"
                    }
                }
            }.apply {
                name = "FlexUnlockReleaseCheck"
                start()
            }
        }
        pageContainer.addView(
            updateStatus,
            matchWrap().apply { bottomMargin = dp(10) }
        )
        val releaseRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                updateButton,
                LinearLayout.LayoutParams(0, dp(42), 1f)
            )
            addView(
                actionButton("Releases", false) {
                    openUrl(GitHubReleaseChecker.RELEASES_PAGE)
                },
                LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) }
            )
        }
        pageContainer.addView(releaseRow, matchWrap())
        pageContainer.addView(
            releaseNotes,
            matchWrap().apply { topMargin = dp(12) }
        )
        renderFeedbackDiagnostics()
        addSectionTitle("交流 / 反馈")
        val communityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                communityIconButton(R.drawable.ic_qq, "QQ 交流群") {
                    openUrl(QQ_GROUP_PAGE)
                },
                LinearLayout.LayoutParams(dp(42), dp(42))
            )
            addView(
                communityIconButton(R.drawable.ic_github, "GitHub 项目主页") {
                    openUrl(PROJECT_PAGE)
                },
                LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) }
            )
        }
        pageContainer.addView(communityRow, matchWrap().apply { topMargin = dp(2) })
    }

    private fun renderFeedbackDiagnostics() {
        val active = DebugLogCaptureService.isCaptureActive()
        val mode = DebugLogCaptureConfig.readMode(this)
        val limitMb = DebugLogCaptureConfig.readLimitMb(this)
        val stats = DebugLogCaptureService.logStats(this)
        addSectionTitle("反馈与诊断")
        addCard(
            if (active) "${mode.label}日志抓取中" else "日志抓取已停止",
            "单文件上限 $limitMb MB · 已有 ${stats.count} 个日志 · " +
                "${stats.totalBytes / 1024 / 1024} MB"
        )
        addActionGrid(DebugLogMode.entries.map { candidate ->
            ActionOption(
                label = candidate.label,
                emphasized = candidate == mode
            ) {
                if (active) {
                    Toast.makeText(this, "请先停止当前日志抓取", Toast.LENGTH_SHORT).show()
                } else if (candidate != mode) {
                    DebugLogCaptureConfig.writeMode(this, candidate)
                    refreshCurrentPage(Page.ABOUT)
                }
            }
        })
        renderDebugLogLimit(limitMb, active)
        addActionGrid(
            listOf(
                ActionOption(if (active) "停止" else "开始", active) {
                    if (active) DebugLogCaptureService.stop(this)
                    else DebugLogCaptureService.start(this)
                    mainHandler.postDelayed({ refreshCurrentPage(Page.ABOUT) }, 300L)
                },
                ActionOption("分享", emphasized = false) {
                    shareDebugLog(stats.latestUri, active)
                },
                ActionOption("删除", emphasized = false) {
                    confirmClearDebugLogs(active)
                }
            ),
            topMargin = 8
        )
    }

    private fun renderDebugLogLimit(limitMb: Int, active: Boolean) {
        val field = metricsField(limitMb.toString(), "").apply {
            background = null
            setPadding(dp(10), 0, dp(4), 0)
        }
        val valueBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBackground(palette.surface, palette.outline, 12)
            addView(field, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(TextView(this@CoverDexControlActivity).apply {
                text = "MB"
                textSize = 13f
                setTextColor(palette.secondaryText)
                gravity = Gravity.CENTER
                setPadding(0, 0, dp(10), 0)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        pageContainer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(valueBox, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(
                    actionButton("设置上限", false) {
                        val value = field.text.toString().toIntOrNull()
                        if (active) {
                            Toast.makeText(this@CoverDexControlActivity, "请先停止当前日志抓取", Toast.LENGTH_SHORT).show()
                        } else if (value == null || value !in 10..500) {
                            Toast.makeText(this@CoverDexControlActivity, "文件上限必须为 10-500 MB", Toast.LENGTH_SHORT).show()
                        } else {
                            DebugLogCaptureConfig.writeLimitMb(this@CoverDexControlActivity, value)
                            refreshCurrentPage(Page.ABOUT)
                        }
                    },
                    LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) }
                )
            },
            matchWrap().apply { topMargin = dp(8) }
        )
    }

    private fun shareDebugLog(uri: Uri?, active: Boolean) {
        if (active) {
            Toast.makeText(this, "请先停止当前日志抓取", Toast.LENGTH_SHORT).show()
            return
        }
        if (uri == null) {
            Toast.makeText(this, "暂无可分享的日志", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "分享调试日志"))
    }

    private fun confirmClearDebugLogs(active: Boolean) {
        if (active) {
            Toast.makeText(this, "请先停止当前日志抓取", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空调试日志")
            .setMessage("删除 Download/FlexUnlockLogs 中的全部 FlexUnlock 日志？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val deleted = DebugLogCaptureService.clearLogs(this)
                Toast.makeText(this, "已删除 $deleted 个日志", Toast.LENGTH_SHORT).show()
                refreshCurrentPage(Page.ABOUT)
            }
            .show()
    }

    private fun communityIconButton(
        icon: Int,
        description: String,
        onClick: () -> Unit
    ): ImageButton = ImageButton(this).apply {
        stateListAnimator = null
        elevation = 0f
        backgroundTintList = null
        background = roundedRippleBackground(palette.surface, palette.outline, 12)
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(palette.text)
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setOnClickListener { onClick() }
    }

    private fun addIconSizeControl(
        title: String,
        surface: String,
        topMargin: Int = 0
    ) {
        val outerTopMargin = dp(topMargin)
        val currentSize = coverIconSizePx(this, surface)
        var lastSubmittedSize = currentSize
        val valueLabel = label("${currentSize}px", 14f, palette.text)
        val slider = SeekBar(this).apply {
            min = MIN_COVER_ICON_SIZE_PX
            max = MAX_COVER_ICON_SIZE_PX
            progress = currentSize
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    valueLabel.text = "${progress}px"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    if (seekBar.progress != lastSubmittedSize) {
                        lastSubmittedSize = seekBar.progress
                        setCoverIconSize(surface, seekBar.progress)
                    }
                }
            })
        }
        pageContainer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(8))
                background = roundedBackground(palette.surface, palette.outline, 14)
                addView(
                    LinearLayout(this@CoverDexControlActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            label(title, 15f, palette.text),
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        )
                        addView(valueLabel, LinearLayout.LayoutParams(-2, -2))
                    },
                    matchWrap()
                )
                addView(
                    slider,
                    matchWrap().apply {
                        setMargins(leftMargin, dp(4), rightMargin, bottomMargin)
                    }
                )
            },
            matchWrap().apply {
                setMargins(leftMargin, outerTopMargin, rightMargin, bottomMargin)
            }
        )
    }

    private fun addSectionTitle(title: String) {
        pageContainer.addView(label(title, 14f, palette.text).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), dp(12), 0, dp(6))
        }, matchWrap())
    }

    private fun addCard(title: String, body: String): TextView {
        val bodyView = label(body, 12f, palette.secondaryText).apply {
            setPadding(0, dp(4), 0, 0)
        }
        pageContainer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground(palette.surface, palette.outline, 14)
                addView(label(title, 15f, palette.text).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, matchWrap())
                addView(bodyView, matchWrap())
            },
            matchWrap().apply { bottomMargin = dp(9) }
        )
        return bodyView
    }

    private fun addInfoRow(name: String, value: String) {
        pageContainer.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(label(name, 14f, palette.secondaryText), LinearLayout.LayoutParams(0, -2, 1f))
                addView(label(value, 14f, palette.text), LinearLayout.LayoutParams(-2, -2))
            },
            matchWrap()
        )
    }

    private fun addAction(
        label: String,
        topMargin: Int = 0,
        emphasized: Boolean = true,
        action: () -> Unit
    ) {
        pageContainer.addView(
            actionButton(label, emphasized) { action() },
            matchWrap().apply { this.topMargin = dp(topMargin) }
        )
    }

    private fun addActionGrid(options: List<ActionOption>, topMargin: Int = 0) {
        val columns = compactActionColumnCount(availableActionWidthDp(), options.size)
        val gap = dp(8)
        val grid = GridLayout(this).apply {
            columnCount = columns
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        options.forEachIndexed { index, option ->
            val row = index / columns
            val column = index % columns
            grid.addView(
                actionButton(option.label, option.emphasized) { option.action() },
                GridLayout.LayoutParams(
                    GridLayout.spec(row),
                    GridLayout.spec(column, 1f)
                ).apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    setGravity(Gravity.FILL_HORIZONTAL)
                    if (row > 0) this.topMargin = gap
                    if (column > 0) marginStart = gap
                }
            )
        }
        pageContainer.addView(
            grid,
            matchWrap().apply { this.topMargin = dp(topMargin) }
        )
    }

    private fun availableActionWidthDp(): Int {
        val widthPx = pageContainer.width.takeIf { it > 0 }
            ?: contentHost.width.takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - sidebarSlot.layoutParams.width)
        return ((widthPx - dp(28)) / resources.displayMetrics.density).toInt()
    }

    private fun actionButton(
        text: String,
        emphasized: Boolean,
        action: (Button) -> Unit
    ): Button = Button(this).apply {
        useFlatSurface()
        this.text = text
        textSize = 14f
        isAllCaps = false
        minHeight = dp(42)
        minimumHeight = dp(42)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setTextColor(if (emphasized) Color.WHITE else palette.text)
        background = roundedRippleBackground(
            if (emphasized) palette.primary else palette.surface,
            if (emphasized) palette.primary else palette.outline,
            14
        )
        setOnClickListener { action(this) }
    }

    private fun selectQsGrid(grid: CoverQsGrid) {
        sendBroadcast(
            Intent(CoverQsGridConfig.ACTION_SET).apply {
                setPackage("android")
                putExtra(CoverQsGridConfig.EXTRA_COLUMNS, grid.columns)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
        Toast.makeText(
            this,
            "正在切换快捷设置为 ${grid.label}…",
            Toast.LENGTH_SHORT
        ).show()
        awaitSettingConfirmation(
            isApplied = { CoverQsGridConfig.read(this) == grid },
            successMessage = "快捷设置已切换为 ${grid.label}",
            failureMessage = "快捷设置未生效，请确认 system_server 模块已加载",
            onFinished = {
                if (currentPage == Page.TILES) showPage(Page.TILES)
            }
        )
    }

    private fun setCoverIconSize(surface: String, sizePx: Int) {
        val updateIntent = Intent(ACTION_SET_COVER_ICON_SIZE).apply {
            putExtra(EXTRA_COVER_ICON_SURFACE, surface)
            putExtra(EXTRA_COVER_ICON_SIZE_PX, sizePx)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        sendBroadcast(
            Intent(updateIntent).setPackage(CoverRuntime.SYSTEM_UI_PACKAGE)
        )
        sendBroadcast(
            Intent(updateIntent).setPackage(CoverRuntime.SAMSUNG_LAUNCHER_PACKAGE)
        )
        val surfaceLabel = if (surface == COVER_ICON_SURFACE_HOME) "桌面" else "应用抽屉"
        Toast.makeText(
            this,
            "正在设置$surfaceLabel 图标…",
            Toast.LENGTH_SHORT
        ).show()
        awaitSettingConfirmation(
            isApplied = { coverIconSizePx(this, surface) == sizePx },
            successMessage = "$surfaceLabel 图标已设为 ${sizePx}px",
            failureMessage = "$surfaceLabel 图标设置未生效，请确认 Launcher 模块已加载"
        )
    }

    private fun setCoverHomeStatusBarHidden(hidden: Boolean) {
        pendingHomeStatusBarHidden = hidden
        refreshCurrentPage(Page.SETTINGS)
        sendBroadcast(
            Intent(ACTION_SET_HIDE_COVER_HOME_STATUS_BAR).apply {
                setPackage(CoverRuntime.SYSTEM_UI_PACKAGE)
                putExtra(EXTRA_HIDE_COVER_HOME_STATUS_BAR, hidden)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
        Toast.makeText(
            this,
            if (hidden) "正在关闭桌面顶部状态栏…" else "正在恢复桌面顶部状态栏…",
            Toast.LENGTH_SHORT
        ).show()
        awaitSettingConfirmation(
            isApplied = { isCoverHomeStatusBarHidden(this) == hidden },
            successMessage = if (hidden) {
                "已关闭桌面顶部状态栏"
            } else {
                "已恢复桌面顶部状态栏"
            },
            failureMessage = "顶部状态栏设置未生效，请确认 SystemUI 模块已加载",
            onFinished = {
                pendingHomeStatusBarHidden = null
                refreshCurrentPage(Page.SETTINGS)
            }
        )
    }

    private fun sendCoverAction(action: String) {
        sendBroadcast(
            Intent(action).apply {
                setPackage("android")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
        Toast.makeText(
            this,
            when (action) {
                ACTION_ENABLE_COVER_DEX -> "已请求启动或恢复"
                ACTION_RESTART_COVER_DEX -> "正在重启外屏会话"
                else -> "已退出当前外屏会话"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun currentVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
    }.getOrDefault("未知")

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "未找到可打开链接的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun label(value: String, sizeSp: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        setLineSpacing(0f, 1.08f)
    }

    private fun animateContentIn() {
        contentHost.animate().cancel()
        contentHost.alpha = 1f
        contentHost.translationX = 0f
        contentHost.translationY = 0f
    }

    private fun Button.useFlatSurface() {
        stateListAnimator = null
        elevation = 0f
        translationZ = 0f
        backgroundTintList = null
        background = roundedBackground(Color.TRANSPARENT, Color.TRANSPARENT, 12)
    }

    private fun roundedRippleBackground(fill: Int, stroke: Int, radiusDp: Int): android.graphics.drawable.Drawable {
        val radius = dp(radiusDp).toFloat()
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.WHITE)
        }
        return android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(Color.argb(52, 255, 255, 255)),
            content,
            mask
        )
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun matchWrap(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val STATE_PAGE = "selected_page"
        const val STATE_SIDEBAR_COLLAPSED = "sidebar_collapsed"
        const val STATE_SCROLL = "page_scroll_y"
        const val SIDEBAR_BRAND_TAG = "flexunlock_sidebar_brand"
        const val SIDEBAR_SPACER_TAG = "flexunlock_sidebar_spacer"
        const val SIDEBAR_ITEM_TAG = "flexunlock_sidebar_item"
        const val CHARITY_PROJECT_PAGE = "https://cat.iiwl.cc/jiemao/"
        const val ROTATION_270_NAV_ITEM_HEIGHT_PX = 48
        const val ROTATION_270_NAV_ICON_PAD_PX = 10
        const val ROTATION_270_NAV_ITEM_INSET_PX = 7
        const val ROTATION_270_NAV_ITEM_GAP_PX = 2
        const val ROTATION_270_NAV_CORNER_PX = 12
        const val PROJECT_PAGE = "https://github.com/AndyNull/flexunlock"
        const val QQ_GROUP_PAGE = "https://qm.qq.com/q/Uo8taIrlKw"
        const val ACTION_ENABLE_COVER_DEX = "com.flexunlock.dexlsp.action.ENABLE_COVER_DEX"
        const val ACTION_DISABLE_COVER_DEX = "com.flexunlock.dexlsp.action.DISABLE_COVER_DEX"
        const val ACTION_RESTART_COVER_DEX = "com.flexunlock.dexlsp.action.RESTART_COVER_DEX"
    }
}
