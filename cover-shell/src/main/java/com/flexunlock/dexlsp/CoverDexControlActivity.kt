package com.flexunlock.dexlsp

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

    private enum class Page(val title: Int, val shortTitle: Int) {
        HOME(R.string.ui_000, R.string.ui_001),
        APPS(R.string.ui_002, R.string.ui_003),
        TILES(R.string.ui_004, R.string.ui_005),
        SETTINGS(R.string.ui_006, R.string.ui_007),
        THEME(R.string.ui_008, R.string.ui_009),
        CHARITY(R.string.ui_010, R.string.ui_011),
        ABOUT(R.string.ui_012, R.string.ui_013)
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(ModuleLanguageStore.configuration(newBase))
    }

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
        contentDescription = getString(page.shortTitle)
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
        contentDescription = if (sidebarCollapsed) getString(R.string.ui_014) else getString(R.string.ui_015)
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
            contentDescription = getString(page.shortTitle)
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
        text = getString(page.shortTitle)
        textSize = 12f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(4), dp(4), dp(4), dp(4))
        gravity = Gravity.CENTER
        setTextColor(palette.secondaryText)
        contentDescription = getString(page.shortTitle)
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
        pageContainer.addView(label(getString(page.title), 20f, palette.text).apply {
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
            getString(R.string.ui_016),
            status?.let(::displayStatusSummary)
                ?: getString(R.string.ui_017)
        )
        addAction(getString(R.string.ui_018)) { openSettingsPage() }
        addSectionTitle(getString(R.string.ui_019))
        addCard(
            getString(R.string.ui_020),
            getString(R.string.ui_021)
        )
        addAction(getString(R.string.ui_022)) { showApplicationPage() }
        addCard(getString(R.string.language), getString(R.string.language_name))
        val selected = ModuleLanguageStore.selected(this)
        addActionGrid(listOf(
            ModuleLanguage.CHINESE to "简体中文",
            ModuleLanguage.ENGLISH to "English"
        ).map { (language, name) ->
            ActionOption(name, language == selected) {
                if (language != selected) {
                    ModuleLanguageStore.save(this, language)
                    DebugLogCaptureService.refreshLanguage(this)
                    recreate()
                }
            }
        })
    }

    private fun showApplicationPage() {
        pageScrollView?.let { pageScrollPositions[currentPage] = it.scrollY }
        pageScrollView = null
        currentPage = Page.APPS
        updateNavigationSelection()
        if (appPanel == null) {
            contentHost.removeAllViews()
            appPanel = CoverLaunchAllowlistPanel(this, palette) {
                Toast.makeText(this, getString(R.string.ui_023), Toast.LENGTH_SHORT).show()
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
                        getString(R.string.ui_024, grid.label)
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
                            getString(R.string.ui_025, grid.label),
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
        addSectionTitle(getString(R.string.ui_026))
        addActionGrid(
            listOf(
                ActionOption(getString(R.string.ui_027)) {
                    sendCoverAction(ACTION_ENABLE_COVER_DEX)
                },
                ActionOption(getString(R.string.ui_028), emphasized = false) {
                    sendCoverAction(ACTION_DISABLE_COVER_DEX)
                }
            )
        )
        renderCoverLockscreenTimeoutControl()
        renderSystemUpdateControl()
        addSectionTitle(getString(R.string.ui_029))
        val hideCoverHomeStatusBar = pendingHomeStatusBarHidden
            ?: isCoverHomeStatusBarHidden(this)
        addCard(
            getString(R.string.ui_030),
            if (hideCoverHomeStatusBar) {
                getString(R.string.ui_031)
            } else {
                getString(R.string.ui_032)
            }
        )
        addAction(
            label = if (hideCoverHomeStatusBar) {
                getString(R.string.ui_033)
            } else {
                getString(R.string.ui_034)
            },
            emphasized = hideCoverHomeStatusBar
        ) {
            setCoverHomeStatusBarHidden(!hideCoverHomeStatusBar)
        }
        addSectionTitle(getString(R.string.ui_035))
        addCard(
            getString(R.string.ui_036),
            getString(R.string.ui_037)
        )
        addIconSizeControl(
            title = getString(R.string.ui_038),
            surface = COVER_ICON_SURFACE_HOME
        )
        addIconSizeControl(
            title = getString(R.string.ui_039),
            surface = COVER_ICON_SURFACE_DRAWER,
            topMargin = 12
        )
    }

    private fun renderCoverLockscreenTimeoutControl() {
        addSectionTitle(getString(R.string.ui_040))
        val current = CoverDisplayConfig.readLockscreenTimeoutMillis(this)
        val options = listOf(
            30_000L to getString(R.string.ui_041),
            60_000L to getString(R.string.ui_042),
            120_000L to getString(R.string.ui_043),
            1_980_000L to getString(R.string.ui_044),
            300_000L to getString(R.string.ui_045),
            600_000L to getString(R.string.ui_046),
            1_800_000L to getString(R.string.ui_047)
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
            successMessage = getString(R.string.ui_048, label),
            failureMessage = getString(R.string.ui_049),
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun renderSystemUpdateControl() {
        addSectionTitle(getString(R.string.ui_050))
        val blocked = CoverDisplayConfig.readSystemUpdateBlocked(this)
        addCard(
            getString(R.string.ui_051),
            getString(R.string.ui_052)
        )
        addAction(
            label = if (blocked) getString(R.string.ui_053) else getString(R.string.ui_054),
            emphasized = blocked
        ) {
            requestSystemUpdateBlocked(!blocked)
        }
    }

    private fun renderImeKeyboardControl() {
        val compact = CoverDisplayConfig.readImeCompact(this)
        val percent = CoverDisplayConfig.readImeCompactPercent(this)
        addSectionTitle(getString(R.string.ui_055))
        addCard(
            if (compact) getString(R.string.ui_056, percent) else getString(R.string.ui_057),
            if (compact) getString(R.string.ui_058) else getString(R.string.ui_059)
        )
        addAction(
            label = if (compact) getString(R.string.ui_060) else getString(R.string.ui_061),
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
            successMessage = if (compact) getString(R.string.ui_062) else getString(R.string.ui_063),
            failureMessage = getString(R.string.ui_064),
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun requestImeCompactPercent(percent: Int) {
        val compact = CoverDisplayConfig.readImeCompact(this)
        CoverDisplayConfig.requestImeCompact(this, compact, percent)
        awaitSettingConfirmation(
            isApplied = { CoverDisplayConfig.readImeCompactPercent(this) == percent },
            successMessage = getString(R.string.ui_065, percent),
            failureMessage = getString(R.string.ui_066),
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
                            label(getString(R.string.ui_067), 15f, palette.text),
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
            successMessage = if (blocked) getString(R.string.ui_068) else getString(R.string.ui_069),
            failureMessage = getString(R.string.ui_070),
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun renderOutputModeControl() {
        addSectionTitle(getString(R.string.ui_071))
        val transaction = CoverQsModeConfig.readTransaction(this)
        val selected = resolveCoverOutputMode(
            CoverDisplayConfig.readFullDex(this),
            transaction.applied
        )
        val state = when (transaction.state) {
            CoverQsTransition.ORIGINAL -> if (selected == CoverOutputMode.FULL_DEX) {
                getString(R.string.ui_072)
            } else {
                getString(R.string.ui_073)
            }
            CoverQsTransition.FULL -> getString(R.string.ui_074)
            CoverQsTransition.ENABLING -> getString(R.string.ui_075)
            CoverQsTransition.DISABLING -> getString(R.string.ui_076)
            CoverQsTransition.RECOVERING -> getString(R.string.ui_077)
        }
        addCard(getString(R.string.ui_078), state)
        addActionGrid(
            listOf(
                CoverOutputMode.ORIGINAL to getString(R.string.ui_079),
                CoverOutputMode.FULL_QS to getString(R.string.ui_080),
                CoverOutputMode.FULL_DEX to getString(R.string.ui_081)
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
        addSectionTitle(getString(R.string.ui_082))
        addAction(
            label = if (enabled) getString(R.string.ui_083) else getString(R.string.ui_084),
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
            successMessage = if (enabled) getString(R.string.ui_085) else getString(R.string.ui_086),
            failureMessage = getString(R.string.ui_087),
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun renderCameraModeControl() {
        addSectionTitle(getString(R.string.ui_088))
        val mode = CoverDisplayConfig.readCameraMode(this)
        addCard(
            getString(R.string.ui_089),
            if (mode == CoverCameraMode.INNER) {
                getString(R.string.ui_090)
            } else {
                getString(R.string.ui_091)
            }
        )
        addActionGrid(
            listOf(
                CoverCameraMode.INNER to getString(R.string.ui_092),
                CoverCameraMode.ORIGINAL to getString(R.string.ui_093)
            ).map { (candidate, label) ->
                ActionOption(
                    label = if (candidate == mode) getString(R.string.ui_094, label) else label,
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
            successMessage = getString(R.string.ui_097, if (mode == CoverCameraMode.INNER) getString(R.string.ui_095) else getString(R.string.ui_096)),
            failureMessage = getString(R.string.ui_098),
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
            successMessage = getString(R.string.ui_101, if (mode == CoverQsMode.ORIGINAL) getString(R.string.ui_099) else getString(R.string.ui_100)),
            failureMessage = getString(R.string.ui_102),
            attempts = 60,
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun CoverQsTransition.isStable(): Boolean =
        this == CoverQsTransition.ORIGINAL || this == CoverQsTransition.FULL

    private fun renderDisplayMetricsControl() {
        addSectionTitle(getString(R.string.ui_103))
        val status = CoverDisplayConfig.readStatus(this)
        val snapshot = (status?.resolution as? CoverDisplayResolution.Resolved)?.snapshot
        if (status == null || snapshot == null) {
            addCard(getString(R.string.ui_104), getString(R.string.ui_105))
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
            getString(R.string.ui_106),
            "display ${snapshot.id} · ${snapshot.width}×${snapshot.height} · $currentDensity dpi"
        )
        val width = metricsField((configured?.width ?: snapshot.width).toString(), getString(R.string.ui_107))
        val height = metricsField((configured?.height ?: snapshot.height).toString(), getString(R.string.ui_108))
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
            getString(R.string.ui_109),
            getString(R.string.ui_110, nativeWidth, nativeHeight, nativeDensity),
            getString(R.string.ui_111, nativeWidth, nativeHeight, presets[2]!!.densityDpi)
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
                        presetLabels += getString(R.string.ui_112, value.width, value.height, value.densityDpi)
                    }
                }
            }
        }
        if (!externalTarget || presets.none { it?.let { preset ->
                preset.width == fullHdWidth && preset.height == fullHdHeight
            } == true
        }) {
            presets += CoverDisplayOverride(fullHdWidth, fullHdHeight, fullHdDensity)
            presetLabels += getString(R.string.ui_113, fullHdWidth, fullHdHeight, fullHdDensity)
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
            ActionOption(getString(R.string.apply)) {
                val value = CoverDisplayOverride(
                    width.text.toString().toIntOrNull() ?: 0,
                    height.text.toString().toIntOrNull() ?: 0,
                    dpi.text.toString().toIntOrNull() ?: 0
                )
                if (value.width !in 480..7680 || value.height !in 320..4320 || value.densityDpi !in 120..960) {
                    Toast.makeText(this, getString(R.string.ui_114), Toast.LENGTH_SHORT).show()
                } else if (!displayMetricsAspectMatches(value, nativeWidth, nativeHeight)) {
                    Toast.makeText(
                        this,
                        if (fullQs) {
                            getString(R.string.ui_115)
                        } else {
                            getString(R.string.ui_116)
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    requestDisplayMetrics(value, getString(R.string.ui_117))
                }
            }
        )
        if (configured != null) {
            metricActions += ActionOption(getString(R.string.ui_118), emphasized = false) {
                requestDisplayMetrics(null, getString(R.string.ui_119))
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
            failureMessage = getString(R.string.ui_120),
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

        addSectionTitle(getString(R.string.ui_121))
        addActionGrid((listOf<CoverDisplayMode?>(null) + resolutions).map { resolution ->
            val selected = if (resolution == null) {
                preferred == null
            } else {
                preferred?.width == resolution.width && preferred.height == resolution.height
            }
            ActionOption(
                label = if (resolution == null) {
                    if (selected) getString(R.string.ui_122) else getString(R.string.ui_123)
                } else {
                    buildString {
                        append("${resolution.width}×${resolution.height}")
                        if (selected) append(getString(R.string.ui_124))
                    }
                },
                emphasized = selected
            ) {
                if (!selected) {
                    requestRefreshMode(
                        resolution?.let { selectModeForResolution(supported, it, preferred) },
                        resolution?.let { getString(R.string.ui_125, it.width, it.height) }
                            ?: getString(R.string.ui_126)
                    )
                }
            }
        })

        addSectionTitle(getString(R.string.ui_127))
        addActionGrid((listOf<CoverDisplayMode?>(null) + refreshModes).map { refreshMode ->
            val selected = if (refreshMode == null) {
                preferred == null
            } else {
                preferred?.let { kotlin.math.abs(it.refreshRate - refreshMode.refreshRate) < 0.01f } == true
            }
            ActionOption(
                label = if (refreshMode == null) {
                    if (selected) getString(R.string.ui_122) else getString(R.string.ui_123)
                } else {
                    buildString {
                        append("${formatRefreshRate(refreshMode.refreshRate)} Hz")
                        if (selected) append(getString(R.string.ui_124))
                    }
                },
                emphasized = selected
            ) {
                if (!selected) {
                    requestRefreshMode(
                        refreshMode?.let { selectModeForRefresh(supported, it, preferred) },
                        refreshMode?.let { getString(R.string.ui_128, formatRefreshRate(it.refreshRate)) }
                            ?: getString(R.string.ui_129)
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
                getString(R.string.ui_130, it.width, it.height, formatRefreshRate(it.refreshRate))
            } ?: getString(R.string.ui_131),
            failureMessage = getString(R.string.ui_132),
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
            successMessage = if (enabled) getString(R.string.ui_133) else getString(R.string.ui_134),
            failureMessage = getString(R.string.ui_135),
            onFinished = {
                refreshCurrentPage(Page.SETTINGS)
            }
        )
    }

    private fun renderCoverDisplaySelection() {
        addSectionTitle(getString(R.string.ui_136))
        val status = CoverDisplayConfig.readStatus(this)
        if (status == null) {
            addCard(
                getString(R.string.ui_137),
                getString(R.string.ui_138)
            )
            addAction(getString(R.string.ui_139), emphasized = false) {
                requestDisplayStatusRefresh()
            }
            return
        }

        val manual = status.manualIdentity
        val mode = if (manual == null) getString(R.string.ui_140) else getString(R.string.ui_141)
        addCard(getString(R.string.ui_142, mode), displayStatusSummary(status))
        addSectionTitle(getString(R.string.ui_143))
        if (status.candidates.isEmpty()) {
            addCard(
                getString(R.string.ui_144),
                getString(R.string.ui_145)
            )
        } else {
            val manualToken = manual?.let(CoverDisplayConfig::encodeIdentity)
            addActionGrid(status.candidates.map { candidate ->
                val isManualCandidate =
                    manualToken == CoverDisplayConfig.encodeIdentity(candidate.identity)
                ActionOption(
                    label = buildString {
                        append(displayCandidateLabel(candidate))
                        if (isManualCandidate) append(getString(R.string.ui_146))
                    },
                    emphasized = isManualCandidate
                ) {
                    if (!isManualCandidate) {
                        requestDisplayMode(
                            candidate.identity,
                            getString(R.string.ui_147, displayCandidateLabel(candidate))
                        )
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.ui_148),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
        }
        addActionGrid(
            buildList {
                if (manual != null) {
                    add(ActionOption(getString(R.string.ui_149), emphasized = false) {
                        requestDisplayMode(null, getString(R.string.ui_140))
                    })
                }
                add(ActionOption(getString(R.string.ui_150), emphasized = false) {
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
        Toast.makeText(this, getString(R.string.ui_151, label), Toast.LENGTH_SHORT).show()
        awaitSettingConfirmation(
            isApplied = {
                val status = CoverDisplayConfig.readStatus(this) ?: return@awaitSettingConfirmation false
                status.revision != previousRevision &&
                    CoverDisplayConfig.encodeIdentity(status.manualIdentity) == expectedIdentity
            },
            successMessage = getString(R.string.ui_152, label),
            failureMessage = getString(R.string.ui_153, label),
            onFinished = { refreshCurrentPage(Page.SETTINGS) }
        )
    }

    private fun requestDisplayStatusRefresh() {
        val previousRevision = CoverDisplayConfig.readStatus(this)?.revision ?: 0L
        CoverDisplayConfig.requestStatus(this)
        Toast.makeText(this, getString(R.string.ui_154), Toast.LENGTH_SHORT).show()
        awaitSettingConfirmation(
            isApplied = {
                val revision = CoverDisplayConfig.readStatus(this)?.revision ?: return@awaitSettingConfirmation false
                revision != previousRevision
            },
            successMessage = getString(R.string.ui_155),
            failureMessage = getString(R.string.ui_156),
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
            ?: getString(R.string.ui_017)
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
        val mode = if (status.manualIdentity == null) getString(R.string.ui_157) else getString(R.string.ui_158)
        val result = when (val resolution = status.resolution) {
            is CoverDisplayResolution.Resolved -> {
                val snapshot = resolution.snapshot
                val displayName = snapshot.name?.takeIf(String::isNotBlank) ?: getString(R.string.ui_159)
                val source = when (resolution.source) {
                    CoverDisplaySelectionSource.AUTO -> getString(R.string.ui_160)
                    CoverDisplaySelectionSource.MANUAL -> getString(R.string.ui_161)
                    CoverDisplaySelectionSource.AUTO_FALLBACK -> getString(R.string.ui_162)
                }
                "$displayName · ${snapshot.width}×${snapshot.height} · $source"
            }
            is CoverDisplayResolution.Ambiguous -> getString(R.string.ui_163)
            is CoverDisplayResolution.Unavailable -> when (resolution.reason) {
                "default-display-missing", "default-display-size-missing" ->
                    getString(R.string.ui_164)
                "manual-missing-auto-unavailable", "cover-display-missing" ->
                    getString(R.string.ui_165)
                else -> getString(R.string.ui_166)
            }
        }
        return getString(R.string.ui_167, mode, result)
    }

    private fun displayCandidateLabel(candidate: CoverDisplayCandidateStatus): String {
        val name = candidate.name?.takeIf(String::isNotBlank) ?: getString(R.string.ui_168)
        return "$name · ${candidate.width}×${candidate.height}"
    }

    private fun renderTheme() {
        val selected = ModuleThemeStore.selected(this)
        addCard(getString(R.string.ui_169), getString(R.string.ui_170))
        addActionGrid(
            listOf(
                ModuleTheme.SYSTEM to getString(R.string.ui_171),
                ModuleTheme.LIGHT to getString(R.string.ui_172),
                ModuleTheme.DARK to getString(R.string.ui_173)
            ).map { (theme, title) ->
                ActionOption(
                    label = if (selected == theme) getString(R.string.ui_174, title) else title,
                    emphasized = selected == theme
                ) {
                    if (selected != theme) {
                        ModuleThemeStore.save(this, theme)
                        recreate()
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.ui_175, title),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun renderCharity() {
        addCard(
            getString(R.string.ui_176),
            getString(R.string.ui_177)
        )
        addActionGrid(
            listOf(
                ActionOption(getString(R.string.ui_178)) { openUrl(CHARITY_PROJECT_PAGE) },
                ActionOption(getString(R.string.ui_179), emphasized = false) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(
                        ClipData.newPlainText(getString(R.string.ui_180), CHARITY_PROJECT_PAGE)
                    )
                    Toast.makeText(this, getString(R.string.ui_181), Toast.LENGTH_SHORT).show()
                }
            )
        )
    }

    private fun renderAbout() {
        val installedVersion = currentVersion()
        pageContainer.addView(
            label(
                getString(R.string.ui_182),
                14f,
                palette.secondaryText
            ).apply { setPadding(dp(2), 0, dp(2), dp(12)) },
            matchWrap()
        )
        addInfoRow(getString(R.string.ui_183), installedVersion)
        addInfoRow(getString(R.string.ui_184), "AndyNull")
        addSectionTitle(getString(R.string.ui_185))
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
        val updateButton = actionButton(getString(R.string.ui_186), true) { button ->
            button.isEnabled = false
            button.text = getString(R.string.ui_187)
            updateStatus.visibility = View.VISIBLE
            updateStatus.setTextColor(palette.onPrimaryContainer)
            updateStatus.background = roundedBackground(
                palette.primaryContainer,
                Color.TRANSPARENT,
                14
            )
            updateStatus.text = getString(R.string.ui_188)
            releaseNotes.visibility = View.GONE
            Thread {
                val result = GitHubReleaseChecker.check(this, installedVersion)
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    button.isEnabled = true
                    result.onSuccess { checkResult ->
                        when (checkResult) {
                            is ReleaseCheckResult.UpdateAvailable -> {
                                val release = checkResult.release
                                updateStatus.setTextColor(palette.onPrimaryContainer)
                                updateStatus.text = getString(R.string.ui_189, release.tag, release.name)
                                button.text = getString(R.string.ui_190)
                                button.setOnClickListener {
                                    openUrl(GitHubReleaseChecker.RELEASES_PAGE)
                                }
                                releaseNotes.text = buildString {
                                    append("${release.name} · ${release.tag}\n")
                                    append(getString(R.string.ui_191, release.publishedAt))
                                    append(release.notes.ifBlank { getString(R.string.ui_192) })
                                }
                                releaseNotes.visibility = View.VISIBLE
                            }

                            ReleaseCheckResult.UpToDate -> {
                                updateStatus.setTextColor(palette.onPrimaryContainer)
                                updateStatus.text = getString(R.string.ui_193)
                                button.text = getString(R.string.ui_194)
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
                            getString(R.string.ui_195, GitHubReleaseChecker.failureMessage(this, error))
                        button.text = getString(R.string.ui_196)
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
        addSectionTitle(getString(R.string.ui_197))
        val communityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                communityIconButton(R.drawable.ic_qq, getString(R.string.ui_198)) {
                    openUrl(QQ_GROUP_PAGE)
                },
                LinearLayout.LayoutParams(dp(42), dp(42))
            )
            addView(
                communityIconButton(R.drawable.ic_github, getString(R.string.ui_199)) {
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
        addSectionTitle(getString(R.string.ui_200))
        addCard(
            if (active) getString(R.string.ui_201, getString(mode.label)) else getString(R.string.ui_202),
            getString(R.string.ui_203, limitMb, stats.count) +
                "${stats.totalBytes / 1024 / 1024} MB"
        )
        addActionGrid(DebugLogMode.entries.map { candidate ->
            ActionOption(
                label = getString(candidate.label),
                emphasized = candidate == mode
            ) {
                if (active) {
                    Toast.makeText(this, getString(R.string.ui_204), Toast.LENGTH_SHORT).show()
                } else if (candidate != mode) {
                    DebugLogCaptureConfig.writeMode(this, candidate)
                    refreshCurrentPage(Page.ABOUT)
                }
            }
        })
        renderDebugLogLimit(limitMb, active)
        addActionGrid(
            listOf(
                ActionOption(if (active) getString(R.string.ui_205) else getString(R.string.ui_206), active) {
                    if (active) DebugLogCaptureService.stop(this)
                    else DebugLogCaptureService.start(this)
                    mainHandler.postDelayed({ refreshCurrentPage(Page.ABOUT) }, 300L)
                },
                ActionOption(getString(R.string.ui_207), emphasized = false) {
                    shareDebugLog(stats.latestUri, active)
                },
                ActionOption(getString(R.string.ui_208), emphasized = false) {
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
                    actionButton(getString(R.string.ui_209), false) {
                        val value = field.text.toString().toIntOrNull()
                        if (active) {
                            Toast.makeText(this@CoverDexControlActivity, getString(R.string.ui_204), Toast.LENGTH_SHORT).show()
                        } else if (value == null || value !in 10..500) {
                            Toast.makeText(this@CoverDexControlActivity, getString(R.string.ui_210), Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, getString(R.string.ui_204), Toast.LENGTH_SHORT).show()
            return
        }
        if (uri == null) {
            Toast.makeText(this, getString(R.string.ui_211), Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.ui_212)))
    }

    private fun confirmClearDebugLogs(active: Boolean) {
        if (active) {
            Toast.makeText(this, getString(R.string.ui_204), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ui_213))
            .setMessage(getString(R.string.ui_214))
            .setNegativeButton(getString(R.string.ui_215), null)
            .setPositiveButton(getString(R.string.ui_208)) { _, _ ->
                val deleted = DebugLogCaptureService.clearLogs(this)
                Toast.makeText(this, getString(R.string.ui_216, deleted), Toast.LENGTH_SHORT).show()
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
            getString(R.string.ui_217, grid.label),
            Toast.LENGTH_SHORT
        ).show()
        awaitSettingConfirmation(
            isApplied = { CoverQsGridConfig.read(this) == grid },
            successMessage = getString(R.string.ui_218, grid.label),
            failureMessage = getString(R.string.ui_219),
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
        val surfaceLabel = if (surface == COVER_ICON_SURFACE_HOME) getString(R.string.ui_220) else getString(R.string.ui_221)
        Toast.makeText(
            this,
            getString(R.string.ui_222, surfaceLabel),
            Toast.LENGTH_SHORT
        ).show()
        awaitSettingConfirmation(
            isApplied = { coverIconSizePx(this, surface) == sizePx },
            successMessage = getString(R.string.ui_223, surfaceLabel, sizePx),
            failureMessage = getString(R.string.ui_224, surfaceLabel)
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
            if (hidden) getString(R.string.ui_225) else getString(R.string.ui_226),
            Toast.LENGTH_SHORT
        ).show()
        awaitSettingConfirmation(
            isApplied = { isCoverHomeStatusBarHidden(this) == hidden },
            successMessage = if (hidden) {
                getString(R.string.ui_227)
            } else {
                getString(R.string.ui_228)
            },
            failureMessage = getString(R.string.ui_229),
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
                ACTION_ENABLE_COVER_DEX -> getString(R.string.ui_230)
                ACTION_RESTART_COVER_DEX -> getString(R.string.ui_231)
                else -> getString(R.string.ui_232)
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun currentVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: getString(R.string.ui_233)
    }.getOrDefault(getString(R.string.ui_233))

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, getString(R.string.ui_234), Toast.LENGTH_SHORT).show()
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
