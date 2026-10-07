package com.flexunlock.dexlsp

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.flexunlock.dexlsp.config.AppDisplayProfile
import com.flexunlock.dexlsp.config.AppDisplayProfileConfig
import com.flexunlock.dexlsp.config.AppWindowMode
import com.flexunlock.dexlsp.config.CoverLaunchConfig

internal class CoverLaunchAllowlistPanel(
    context: Context,
    private val palette: ModuleUiPalette,
    private val onSaved: () -> Unit
) : FrameLayout(context) {
    private fun getString(id: Int, vararg args: Any): String =
        if (args.isEmpty()) context.getString(id) else context.getString(id, *args)
    private val selectedPackages = linkedSetOf<String>()
    private val displayProfiles = linkedMapOf<String, AppDisplayProfile>()
    private var packageCatalog: List<SelectablePackage> = emptyList()
    private var searchQuery = ""
    private var showSystemApps = true
    private var showUserApps = true
    private var showSelectedOnly = false
    private val adapter = CoverLaunchAllowlistAdapter(
        context,
        palette,
        ::updateSelection,
        ::showDisplayProfileDialog
    )
    private val summary = TextView(context)
    private val resultSummary = TextView(context)
    private val packageList = ListView(context)
    private var refreshIcon: ImageView? = null
    private var refreshing = false

    init {
        setBackgroundColor(palette.background)
        selectedPackages += CoverLaunchConfig.read(context)
        displayProfiles.putAll(AppDisplayProfileConfig.read(context))
        buildView()
        val cached = CoverLaunchPackageCatalog.peek()
        if (cached != null) {
            packageCatalog = cached
            renderPackages()
        } else {
            resultSummary.visibility = View.VISIBLE
            resultSummary.text = getString(R.string.ui_235)
            reloadPackages(force = false, fromUser = false)
        }
    }

    private fun buildView() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(4))
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = getString(R.string.ui_002)
                    textSize = 19f
                    setTextColor(palette.text)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply {
                    text = getString(R.string.ui_236)
                    textSize = 10f
                    isAllCaps = false
                    minHeight = 0
                    minimumHeight = 0
                    minWidth = 0
                    minimumWidth = 0
                    stateListAnimator = null
                    elevation = 0f
                    backgroundTintList = null
                    setTextColor(palette.text)
                    background = roundedBackground(palette.surface, palette.outline, 10)
                    setPadding(dp(7), 0, dp(7), 0)
                    setOnClickListener { showGlobalDisplayProfileDialog() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)).apply {
                    marginEnd = dp(6)
                })
                addView(FrameLayout(context).apply {
                    background = roundedBackground(palette.surface, palette.outline, 10)
                    isClickable = true
                    isFocusable = true
                    contentDescription = getString(R.string.ui_237)
                    addView(ImageView(context).apply {
                        refreshIcon = this
                        setImageResource(R.drawable.ic_refresh)
                        imageTintList = ColorStateList.valueOf(palette.text)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply {
                        setMargins(dp(6), dp(6), dp(6), dp(6))
                    })
                    setOnClickListener { reloadPackages(force = true, fromUser = true) }
                }, LinearLayout.LayoutParams(dp(30), dp(30)))
            }
            addView(header, matchWrap())
            summary.apply {
                textSize = 11f
                setTextColor(palette.secondaryText)
                setPadding(0, dp(2), 0, dp(7))
            }
            addView(summary, matchWrap())
            val controls = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val search = EditText(context).apply {
                    hint = getString(R.string.ui_238)
                    setSingleLine(true)
                    textSize = 12f
                    setTextColor(palette.text)
                    setHintTextColor(palette.secondaryText)
                    background = roundedBackground(palette.surface, palette.outline, 12)
                    setPadding(dp(12), 0, dp(12), 0)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            searchQuery = s?.toString()?.trim().orEmpty()
                            renderPackages()
                        }
                        override fun afterTextChanged(s: Editable?) = Unit
                    })
                }
                addView(search, LinearLayout.LayoutParams(0, dp(38), 1f))
                addView(Button(context).apply {
                    text = getString(R.string.ui_239)
                    textSize = 11f
                    isAllCaps = false
                    minHeight = 0
                    minimumHeight = 0
                    stateListAnimator = null
                    elevation = 0f
                    backgroundTintList = null
                    setTextColor(palette.text)
                    background = roundedBackground(palette.surface, palette.outline, 12)
                    setPadding(dp(7), 0, dp(7), 0)
                    setOnClickListener { showFilterDialog() }
                }, LinearLayout.LayoutParams(dp(56), dp(38)).apply {
                    marginStart = dp(6)
                })
            }
            addView(controls, matchWrap())
            resultSummary.apply {
                textSize = 11f
                setTextColor(palette.secondaryText)
                setPadding(dp(2), dp(4), dp(2), dp(2))
            }
            addView(resultSummary, matchWrap())
            packageList.apply {
                divider = null
                dividerHeight = 0
                clipToPadding = false
                adapter = this@CoverLaunchAllowlistPanel.adapter
                setPadding(0, 0, 0, dp(6))
            }
            addView(packageList, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(actionButton(getString(R.string.ui_240), false) {
                    selectedPackages.clear()
                    renderPackages()
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(actionButton(getString(R.string.ui_241), true) {
                    save()
                }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    marginStart = dp(7)
                })
            }
            addView(actions, matchWrap())
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        renderPackages()
    }

    private fun reloadPackages(force: Boolean, fromUser: Boolean) {
        if (fromUser && refreshing) return
        if (fromUser) {
            refreshing = true
            resultSummary.visibility = View.VISIBLE
            resultSummary.text = getString(R.string.ui_242)
            refreshIcon?.isEnabled = true
            refreshIcon?.animate()?.cancel()
            refreshIcon?.rotation = 0f
            refreshIcon?.animate()?.rotationBy(360f)?.setDuration(480)?.start()
        }
        CoverLaunchPackageCatalog.load(
            context = context,
            force = force,
            selectedPackages = selectedPackages.toSet()
        ) { result ->
            post {
                refreshing = false
                result.onSuccess {
                    packageCatalog = it
                    renderPackages()
                    if (fromUser) {
                        Toast.makeText(context, getString(R.string.ui_243), Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    resultSummary.visibility = View.VISIBLE
                    resultSummary.text = getString(R.string.ui_244)
                }
            }
        }
    }

    private fun renderPackages() {
        val query = searchQuery.lowercase()
        val visible = packageCatalog.asSequence()
            .filter { item ->
                (if (item.isSystem) showSystemApps else showUserApps) &&
                    (!showSelectedOnly || item.packageName in selectedPackages) &&
                    (query.isEmpty() || query in item.label.lowercase() || query in item.packageName.lowercase())
            }
            .map { item -> item.copy(
                selected = item.packageName in selectedPackages,
                profile = displayProfiles[item.packageName],
                showPackageName = query.isNotEmpty()
            ) }
            .toList()
            .let(CoverLaunchAllowlistPresentation::sort)
        val firstVisible = packageList.firstVisiblePosition
        val topOffset = packageList.getChildAt(0)?.top ?: 0
        adapter.submit(visible)
        if (visible.isNotEmpty() && firstVisible >= 0) {
            packageList.setSelectionFromTop(
                firstVisible.coerceAtMost(visible.lastIndex),
                topOffset
            )
        }
        resultSummary.text = if (showSelectedOnly) getString(R.string.ui_245, visible.size) else ""
        resultSummary.visibility = if (resultSummary.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        summary.text = getString(R.string.ui_246, selectedPackages.size)
    }

    private fun updateSelection(packageName: String, selected: Boolean) {
        if (selected) selectedPackages += packageName else selectedPackages -= packageName
        if (!selected) displayProfiles.remove(packageName)
        renderPackages()
    }

    private fun applyBulkSelection(updatedPackages: Set<String>) {
        val removed = selectedPackages - updatedPackages
        removed.forEach(displayProfiles::remove)
        selectedPackages.clear()
        selectedPackages.addAll(updatedPackages)
        renderPackages()
    }

    private fun setAllInAppTypeScope(selected: Boolean) {
        applyBulkSelection(
            CoverLaunchAllowlistPresentation.setAllInScope(
                packages = packageCatalog,
                selectedPackages = selectedPackages,
                showSystemApps = showSystemApps,
                showUserApps = showUserApps,
                selected = selected
            )
        )
    }

    private fun invertAppTypeScope() {
        applyBulkSelection(
            CoverLaunchAllowlistPresentation.invertInScope(
                packages = packageCatalog,
                selectedPackages = selectedPackages,
                showSystemApps = showSystemApps,
                showUserApps = showUserApps
            )
        )
    }

    private fun appTypeScopeFullySelected(): Boolean =
        CoverLaunchAllowlistPresentation.isScopeFullySelected(
            packages = packageCatalog,
            selectedPackages = selectedPackages,
            showSystemApps = showSystemApps,
            showUserApps = showUserApps
        )

    private fun showFilterDialog() {
        fun filterToggle(label: String, checked: Boolean) = CheckBox(context).apply {
            text = label
            isChecked = checked
            textSize = 11f
            setTextColor(palette.text)
            buttonTintList = ColorStateList.valueOf(palette.primary)
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(4), 0, dp(4), 0)
        }

        fun sectionLabel(label: String) = TextView(context).apply {
            text = label
            textSize = 10f
            setTextColor(palette.secondaryText)
            setPadding(dp(3), 0, dp(3), dp(2))
        }

        fun toggleGroup(vararg toggles: CheckBox) = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(palette.background, palette.outline, 11)
            setPadding(dp(5), dp(2), dp(5), dp(2))
            toggles.forEach { toggle ->
                addView(toggle, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(29)
                ))
            }
        }

        val systemApps = filterToggle(getString(R.string.ui_247), showSystemApps)
        val userApps = filterToggle(getString(R.string.ui_248), showUserApps)
        val selectedOnly = filterToggle(getString(R.string.ui_249), showSelectedOnly)
        val selectAll = filterToggle(getString(R.string.ui_250), appTypeScopeFullySelected())
        val invert = filterToggle(getString(R.string.ui_251), false)
        var syncingChecks = false
        var invertSelected = false

        fun syncBulkChecks(resetInvert: Boolean = false) {
            if (resetInvert) invertSelected = false
            syncingChecks = true
            selectAll.isChecked = !invertSelected && appTypeScopeFullySelected()
            invert.isChecked = invertSelected
            syncingChecks = false
        }

        systemApps.setOnCheckedChangeListener { _, checked ->
            if (syncingChecks) return@setOnCheckedChangeListener
            showSystemApps = checked
            renderPackages()
            syncBulkChecks(resetInvert = true)
        }
        userApps.setOnCheckedChangeListener { _, checked ->
            if (syncingChecks) return@setOnCheckedChangeListener
            showUserApps = checked
            renderPackages()
            syncBulkChecks(resetInvert = true)
        }
        selectedOnly.setOnCheckedChangeListener { _, checked ->
            if (syncingChecks) return@setOnCheckedChangeListener
            showSelectedOnly = checked
            renderPackages()
        }
        selectAll.setOnCheckedChangeListener { _, checked ->
            if (syncingChecks) return@setOnCheckedChangeListener
            invertSelected = false
            syncingChecks = true
            invert.isChecked = false
            syncingChecks = false
            setAllInAppTypeScope(checked)
            syncBulkChecks()
        }
        invert.setOnCheckedChangeListener { _, checked ->
            if (syncingChecks) return@setOnCheckedChangeListener
            invertSelected = checked
            if (checked) {
                syncingChecks = true
                selectAll.isChecked = false
                syncingChecks = false
                invertAppTypeScope()
            }
            syncBulkChecks()
        }

        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(palette.surface, palette.outline, 15)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            addView(TextView(context).apply {
                text = getString(R.string.ui_252)
                textSize = 15f
                setTextColor(palette.text)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(2), 0, dp(2), dp(5))
            }, matchWrap())
            addView(sectionLabel(getString(R.string.ui_253)), matchWrap())
            addView(toggleGroup(systemApps, userApps, selectedOnly), matchWrap())
            addView(sectionLabel(getString(R.string.ui_254)).apply {
                setPadding(dp(3), dp(7), dp(3), dp(2))
            }, matchWrap())
            addView(toggleGroup(selectAll, invert), matchWrap())
            addView(actionButton(getString(R.string.ui_255), true) { dialog.dismiss() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply {
                    topMargin = dp(8)
                })
        }
        val cardWidthDp = minOf(context.resources.configuration.screenWidthDp - 24, 286)
        val overlay = FrameLayout(context).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(
                card,
                FrameLayout.LayoutParams(dp(cardWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )
        }
        dialog.setContentView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.CENTER
                dimAmount = 0.38f
            }
        }
        dialog.show()
    }

    private fun showDisplayProfileDialog(item: SelectablePackage) {
        showDisplayProfileDialog(
            packageName = item.packageName,
            label = item.label,
            isGlobal = false
        )
    }

    private fun showGlobalDisplayProfileDialog() {
        showDisplayProfileDialog(
            packageName = AppDisplayProfileConfig.GLOBAL_PROFILE_PACKAGE_NAME,
            label = getString(R.string.ui_256),
            isGlobal = true
        )
    }

    private fun showDisplayProfileDialog(
        packageName: String,
        label: String,
        isGlobal: Boolean
    ) {
        val current = displayProfiles[packageName]
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        panel.addView(TextView(context).apply {
            text = if (isGlobal) {
                getString(R.string.ui_257)
            } else {
                getString(R.string.ui_258)
            }
            setTextColor(palette.secondaryText)
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        })
        val mode = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            background = roundedBackground(palette.surface, palette.outline, 12)
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        val fullscreen = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.ui_259)
            buttonTintList = ColorStateList.valueOf(palette.primary)
            setTextColor(palette.text)
            minHeight = 0
            minimumHeight = 0
        }
        val popup = RadioButton(context).apply {
            id = View.generateViewId()
            text = getString(R.string.ui_260)
            buttonTintList = ColorStateList.valueOf(palette.primary)
            setTextColor(palette.text)
            minHeight = 0
            minimumHeight = 0
        }
        mode.addView(fullscreen, LinearLayout.LayoutParams(0, dp(36), 1f))
        mode.addView(popup, LinearLayout.LayoutParams(0, dp(36), 1f))
        mode.check(if (current?.windowMode == AppWindowMode.POPUP) popup.id else fullscreen.id)
        panel.addView(mode)

        fun control(title: String, initial: Int, min: Int, max: Int): Triple<TextView, TextView, SeekBar> {
            val titleView = TextView(context).apply {
                text = title
                setTextColor(palette.text)
                textSize = 11f
                setPadding(dp(2), dp(4), dp(2), 0)
            }
            val value = TextView(context).apply {
                setTextColor(palette.secondaryText)
                textSize = 12f
                setPadding(dp(2), 0, dp(2), 0)
            }
            panel.addView(titleView)
            panel.addView(value)
            val bar = SeekBar(context).apply {
                this.max = (max - min) / AppDisplayProfileConfig.PERCENT_STEP
                progress = (initial.coerceIn(min, max) - min) / AppDisplayProfileConfig.PERCENT_STEP
                progressTintList = ColorStateList.valueOf(palette.primary)
                thumbTintList = ColorStateList.valueOf(palette.primary)
                splitTrack = false
            }
            panel.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(24)))
            return Triple(titleView, value, bar)
        }
        val (fullscreenTitle, fullscreenValue, fullscreenBar) = control(
            getString(R.string.ui_261),
            current?.fullscreenPercent ?: 100,
            AppDisplayProfileConfig.FULLSCREEN_MIN_PERCENT,
            AppDisplayProfileConfig.FULLSCREEN_MAX_PERCENT
        )
        val (widthTitle, widthValue, widthBar) = control(
            getString(R.string.ui_262),
            current?.popupWidthPercent ?: 82,
            AppDisplayProfileConfig.POPUP_MIN_PERCENT,
            AppDisplayProfileConfig.POPUP_MAX_PERCENT
        )
        val (heightTitle, heightValue, heightBar) = control(
            getString(R.string.ui_263),
            current?.popupHeightPercent ?: 76,
            AppDisplayProfileConfig.POPUP_MIN_PERCENT,
            AppDisplayProfileConfig.POPUP_MAX_PERCENT
        )
        fun percent(bar: SeekBar, min: Int) = min + bar.progress * AppDisplayProfileConfig.PERCENT_STEP
        fun refresh() {
            fullscreenValue.text = getString(R.string.ui_264, percent(fullscreenBar, AppDisplayProfileConfig.FULLSCREEN_MIN_PERCENT))
            widthValue.text = getString(R.string.ui_265, percent(widthBar, 50))
            heightValue.text = getString(R.string.ui_266, percent(heightBar, 50))
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) = refresh()
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        }
        fullscreenBar.setOnSeekBarChangeListener(listener)
        widthBar.setOnSeekBarChangeListener(listener)
        heightBar.setOnSeekBarChangeListener(listener)
        fun updateVisibility() {
            val isPopup = mode.checkedRadioButtonId == popup.id
            fullscreenValue.visibility = if (isPopup) View.GONE else View.VISIBLE
            fullscreenBar.visibility = if (isPopup) View.GONE else View.VISIBLE
            fullscreenTitle.visibility = if (isPopup) View.GONE else View.VISIBLE
            widthTitle.visibility = if (isPopup) View.VISIBLE else View.GONE
            widthValue.visibility = if (isPopup) View.VISIBLE else View.GONE
            widthBar.visibility = if (isPopup) View.VISIBLE else View.GONE
            heightTitle.visibility = if (isPopup) View.VISIBLE else View.GONE
            heightValue.visibility = if (isPopup) View.VISIBLE else View.GONE
            heightBar.visibility = if (isPopup) View.VISIBLE else View.GONE
        }
        mode.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        refresh()
        updateVisibility()
        val dialog = Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(palette.surface, palette.outline, 16)
            setPadding(dp(14), dp(12), dp(14), dp(10))
            addView(TextView(context).apply {
                text = getString(R.string.ui_267, label)
                textSize = 16f
                setTextColor(palette.text)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(2), 0, dp(2), dp(6))
            }, matchWrap())
            addView(panel, matchWrap())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(4), 0, 0)
                addView(actionButton(getString(R.string.ui_215), false) { dialog.dismiss() },
                    LinearLayout.LayoutParams(0, dp(36), 1f))
                addView(actionButton(getString(R.string.ui_241), true) {
                    val profile = if (mode.checkedRadioButtonId == popup.id) {
                        AppDisplayProfile(
                            packageName,
                            windowMode = AppWindowMode.POPUP,
                            popupWidthPercent = percent(
                                widthBar,
                                AppDisplayProfileConfig.POPUP_MIN_PERCENT
                            ),
                            popupHeightPercent = percent(
                                heightBar,
                                AppDisplayProfileConfig.POPUP_MIN_PERCENT
                            )
                        )
                    } else {
                        AppDisplayProfile(
                            packageName,
                            fullscreenPercent = percent(
                                fullscreenBar,
                                AppDisplayProfileConfig.FULLSCREEN_MIN_PERCENT
                            )
                        )
                    }
                    applyDisplayProfile(profile)
                    dialog.dismiss()
                }, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginStart = dp(8)
                })
            }, matchWrap())
        }
        val cardWidthDp = minOf(context.resources.configuration.screenWidthDp - 24, 300)
        val overlay = FrameLayout(context).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(
                ScrollView(context).apply {
                    isFillViewport = false
                    clipToPadding = false
                    addView(card, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ))
                },
                FrameLayout.LayoutParams(dp(cardWidthDp), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )
        }
        dialog.setContentView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.CENTER
                dimAmount = 0.38f
            }
        }
        dialog.show()
    }

    private fun applyDisplayProfile(profile: AppDisplayProfile) {
        val isGlobal = profile.packageName == AppDisplayProfileConfig.GLOBAL_PROFILE_PACKAGE_NAME
        if (profile.isDefault && !isGlobal) displayProfiles.remove(profile.packageName)
        else displayProfiles[profile.packageName] = profile
        if (!isGlobal) selectedPackages += profile.packageName
        CoverLaunchConfig.requestUpdate(context, selectedPackages)
        AppDisplayProfileConfig.requestUpdate(context, displayProfiles.values)
        renderPackages()
        Toast.makeText(
            context,
            if (isGlobal) getString(R.string.ui_268) else getString(R.string.ui_269),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun save() {
        val normalized = CoverLaunchConfig.normalize(selectedPackages)
        val profiles = displayProfiles.values.filter {
            it.packageName in normalized ||
                it.packageName == AppDisplayProfileConfig.GLOBAL_PROFILE_PACKAGE_NAME
        }
        CoverLaunchConfig.requestUpdate(context, normalized)
        AppDisplayProfileConfig.requestUpdate(context, profiles)
        Toast.makeText(context, getString(R.string.ui_270), Toast.LENGTH_SHORT).show()
        onSaved()
    }

    private fun actionButton(label: String, emphasized: Boolean, action: () -> Unit): Button = Button(context).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = 0
        minimumHeight = 0
        stateListAnimator = null
        elevation = 0f
        backgroundTintList = null
        setTextColor(if (emphasized) Color.WHITE else palette.text)
        background = roundedBackground(if (emphasized) palette.primary else palette.surface,
            if (emphasized) Color.TRANSPARENT else palette.outline, 14)
        setOnClickListener { action() }
    }

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
