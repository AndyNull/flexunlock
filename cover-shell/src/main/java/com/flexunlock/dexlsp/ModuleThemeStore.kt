package com.flexunlock.dexlsp

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowInsetsController
import android.view.WindowManager

internal data class ModuleUiPalette(
    val background: Int,
    val sidebar: Int,
    val surface: Int,
    val primary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val text: Int,
    val secondaryText: Int,
    val outline: Int
)

internal enum class ModuleTheme(val storedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark")
}

internal object ModuleThemeStore {
    private const val PREFERENCES = "module_ui"
    private const val KEY_THEME = "theme"

    fun selected(context: Context): ModuleTheme {
        val value = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_THEME, ModuleTheme.SYSTEM.storedValue)
        return ModuleTheme.entries.firstOrNull { it.storedValue == value } ?: ModuleTheme.SYSTEM
    }

    fun save(context: Context, theme: ModuleTheme) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.storedValue)
            .apply()
    }

    fun isDark(context: Context): Boolean = when (selected(context)) {
        ModuleTheme.DARK -> true
        ModuleTheme.LIGHT -> false
        ModuleTheme.SYSTEM ->
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    fun palette(context: Context): ModuleUiPalette = if (isDark(context)) {
        ModuleUiPalette(
            background = Color.rgb(13, 16, 22),
            sidebar = Color.rgb(20, 24, 32),
            surface = Color.rgb(28, 33, 43),
            primary = Color.rgb(74, 104, 224),
            primaryContainer = Color.rgb(42, 56, 103),
            onPrimaryContainer = Color.rgb(223, 229, 255),
            text = Color.rgb(244, 246, 250),
            secondaryText = Color.rgb(178, 185, 199),
            outline = Color.rgb(57, 65, 80)
        )
    } else {
        ModuleUiPalette(
            background = Color.rgb(244, 246, 251),
            sidebar = Color.rgb(232, 237, 248),
            surface = Color.WHITE,
            primary = Color.rgb(47, 78, 196),
            primaryContainer = Color.rgb(221, 228, 255),
            onPrimaryContainer = Color.rgb(25, 48, 130),
            text = Color.rgb(24, 29, 39),
            secondaryText = Color.rgb(83, 92, 110),
            outline = Color.rgb(208, 215, 229)
        )
    }

    fun configureEdgeToEdge(activity: Activity, backgroundColor: Int) {
        val darkTheme = isDark(activity)
        activity.window.setBackgroundDrawable(ColorDrawable(backgroundColor))
        activity.window.decorView.setBackgroundColor(backgroundColor)
        activity.window.setDecorFitsSystemWindows(false)
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        activity.window.isNavigationBarContrastEnforced = false
        activity.window.insetsController?.setSystemBarsAppearance(
            if (darkTheme) 0 else {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            },
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
        activity.window.attributes = activity.window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }
}