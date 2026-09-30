package com.flexunlock.dexlsp

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

enum class CoverQsGrid(
    val columns: Int,
    val rows: Int = 4
) {
    FOUR_BY_FOUR(4),
    FIVE_BY_FOUR(5);

    val capacity: Int
        get() = columns * rows

    val label: String
        get() = "${columns}×${rows}"
}

object CoverQsGridConfig {
    const val SETTINGS_KEY = "flexunlock_cover_qs_columns"
    const val ACTION_SET = "com.flexunlock.dexlsp.action.SET_COVER_QS_GRID"
    const val ACTION_CHANGED = "com.flexunlock.dexlsp.action.COVER_QS_GRID_CHANGED"
    const val EXTRA_COLUMNS = "cover_qs_columns"
    private const val CACHE_TTL_MS = 750L

    @Volatile private var cached: CoverQsGrid? = null
    @Volatile private var cachedAtMs = 0L

    fun read(context: Context): CoverQsGrid {
        val now = SystemClock.uptimeMillis()
        val hit = cached
        if (hit != null && now - cachedAtMs < CACHE_TTL_MS) return hit
        val value = fromColumns(
            Settings.System.getInt(
                context.contentResolver,
                SETTINGS_KEY,
                CoverQsGrid.FOUR_BY_FOUR.columns
            )
        )
        cached = value
        cachedAtMs = now
        return value
    }

    fun fromColumns(columns: Int): CoverQsGrid =
        CoverQsGrid.entries.firstOrNull { it.columns == columns }
            ?: CoverQsGrid.FOUR_BY_FOUR
}

data class CoverQsSpecialChrome(
    val mediaHidden: Boolean = false,
    val brightnessHidden: Boolean = false,
    val brightnessFirst: Boolean = false
)

object CoverQsSpecialConfig {
    const val MEDIA_HIDDEN_KEY = "flexunlock_cover_qs_media_hidden"
    const val BRIGHTNESS_HIDDEN_KEY = "flexunlock_cover_qs_brightness_hidden"
    const val BRIGHTNESS_FIRST_KEY = "flexunlock_cover_qs_brightness_first"
    private const val CACHE_TTL_MS = 750L

    @Volatile private var cached: CoverQsSpecialChrome? = null
    @Volatile private var cachedAtMs = 0L

    fun read(context: Context): CoverQsSpecialChrome {
        val now = SystemClock.uptimeMillis()
        val hit = cached
        if (hit != null && now - cachedAtMs < CACHE_TTL_MS) return hit
        val value = CoverQsSpecialChrome(
            mediaHidden = Settings.System.getInt(context.contentResolver, MEDIA_HIDDEN_KEY, 0) == 1,
            brightnessHidden = Settings.System.getInt(
                context.contentResolver,
                BRIGHTNESS_HIDDEN_KEY,
                0
            ) == 1,
            brightnessFirst = Settings.System.getInt(
                context.contentResolver,
                BRIGHTNESS_FIRST_KEY,
                0
            ) == 1
        )
        cached = value
        cachedAtMs = now
        return value
    }

    fun write(context: Context, chrome: CoverQsSpecialChrome) {
        Settings.System.putInt(
            context.contentResolver,
            MEDIA_HIDDEN_KEY,
            if (chrome.mediaHidden) 1 else 0
        )
        Settings.System.putInt(
            context.contentResolver,
            BRIGHTNESS_HIDDEN_KEY,
            if (chrome.brightnessHidden) 1 else 0
        )
        Settings.System.putInt(
            context.contentResolver,
            BRIGHTNESS_FIRST_KEY,
            if (chrome.brightnessFirst) 1 else 0
        )
        cached = chrome
        cachedAtMs = SystemClock.uptimeMillis()
    }
}