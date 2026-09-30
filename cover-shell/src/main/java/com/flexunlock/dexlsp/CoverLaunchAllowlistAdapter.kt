package com.flexunlock.dexlsp

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.flexunlock.dexlsp.config.AppDisplayProfile
import com.flexunlock.dexlsp.config.AppWindowMode

internal data class SelectablePackage(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystem: Boolean,
    val selected: Boolean,
    val profile: AppDisplayProfile?,
    val showPackageName: Boolean = false
)

internal class CoverLaunchAllowlistAdapter(
    private val context: Context,
    private val palette: ModuleUiPalette,
    private val onToggle: (String, Boolean) -> Unit,
    private val onConfigure: (SelectablePackage) -> Unit
) : BaseAdapter() {
    private val items = mutableListOf<SelectablePackage>()

    fun submit(packages: List<SelectablePackage>) {
        items.clear()
        items.addAll(packages)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): SelectablePackage? = items.getOrNull(position)

    override fun hasStableIds(): Boolean = true

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.packageName?.hashCode()?.toLong() ?: 0L

    override fun getView(position: Int, recycled: View?, parent: ViewGroup?): View {
        val row = recycled as? PackageRow
            ?: PackageRow(context, palette, onToggle, onConfigure)
        row.bind(items[position])
        return row
    }

    private class PackageRow(
        context: Context,
        palette: ModuleUiPalette,
        private val onToggle: (String, Boolean) -> Unit,
        private val onConfigure: (SelectablePackage) -> Unit
    ) : LinearLayout(context) {
        private val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        private val label = TextView(context).apply {
            setTextColor(palette.text)
            textSize = 14f
            maxLines = 1
        }
        private val summary = TextView(context).apply {
            setTextColor(palette.secondaryText)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val configureButton = ImageButton(context).apply {
            stateListAnimator = null
            elevation = 0f
            contentDescription = "显示设置"
            setImageResource(R.drawable.ic_display_tune)
            imageTintList = ColorStateList.valueOf(palette.primary)
            scaleType = ImageView.ScaleType.CENTER
            minimumHeight = 0
            minimumWidth = 0
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), palette.outline)
            }
            backgroundTintList = null
            setPadding(0, 0, 0, 0)
        }
        private val checkBox = CheckBox(context).apply {
            buttonTintList = android.content.res.ColorStateList.valueOf(palette.primary)
        }
        private var boundItem: SelectablePackage? = null
        private var binding = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
            minimumHeight = dp(52)

            addView(icon, LayoutParams(dp(32), dp(32)))
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(10), 0, dp(6), 0)
                    addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    addView(summary, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(configureButton, LayoutParams(dp(44), dp(36)))
            addView(checkBox, LayoutParams(dp(40), dp(40)))

            setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
            configureButton.setOnClickListener {
                boundItem?.let(onConfigure)
            }
            checkBox.setOnCheckedChangeListener { _, checked ->
                val item = boundItem ?: return@setOnCheckedChangeListener
                if (!binding) onToggle(item.packageName, checked)
            }
        }

        fun bind(item: SelectablePackage) {
            boundItem = item
            icon.setImageDrawable(item.icon)
            label.text = item.label
            summary.text = buildString {
                append(profileSummary(item.profile))
                if (item.showPackageName) {
                    append(" · ")
                    append(item.packageName)
                }
            }
            binding = true
            checkBox.isChecked = item.selected
            binding = false
            configureButton.alpha = if (item.selected) 1f else 0.72f
            contentDescription =
                "${item.label}, ${if (item.selected) "已允许" else "未允许"}, ${profileSummary(item.profile)}"
        }

        private fun profileSummary(profile: AppDisplayProfile?): String = when {
            profile?.windowMode == AppWindowMode.POPUP ->
                "弹窗 · ${profile.popupWidthPercent}% × ${profile.popupHeightPercent}%"
            profile != null -> "全屏 · ${profile.fullscreenPercent}%"
            else -> "全屏 · 默认 100%"
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}