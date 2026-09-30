package com.flexunlock.dexlsp.config

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** User-facing scale is relative to the cover display's native app density. */
data class AppDisplayProfile(
    val packageName: String,
    val fullscreenPercent: Int = 100,
    val windowMode: AppWindowMode = AppWindowMode.FULLSCREEN,
    val popupWidthPercent: Int = 82,
    val popupHeightPercent: Int = 76
) {
    val isDefault: Boolean
        get() = windowMode == AppWindowMode.FULLSCREEN && fullscreenPercent == 100
}

enum class AppWindowMode {
    FULLSCREEN,
    POPUP
}

object AppDisplayProfileConfig {
    const val ACTION_UPDATE = "com.flexunlock.dexlsp.action.UPDATE_APP_DISPLAY_PROFILES"
    const val EXTRA_ENCODED_PROFILES = "encoded_profiles"
    const val SETTINGS_KEY = "flexunlock_cover_app_display_profiles"
    const val SETTINGS_REVISION_KEY = "flexunlock_cover_app_display_profiles_revision"
    const val GLOBAL_PROFILE_PACKAGE_NAME = "flexunlock.global.default"

    const val FULLSCREEN_MIN_PERCENT = 35
    const val FULLSCREEN_MAX_PERCENT = 130
    const val POPUP_MIN_PERCENT = 50
    const val POPUP_MAX_PERCENT = 100
    const val PERCENT_STEP = 5

    private const val VERSION = "v2"
    private const val LEGACY_VERSION = "v1"
    private const val EMPTY_VALUE = "$VERSION\n-"
    private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    fun read(context: Context): Map<String, AppDisplayProfile> {
        val raw = Settings.Global.getString(context.contentResolver, SETTINGS_KEY)
        return decode(raw) ?: emptyMap()
    }

    fun readRevision(context: Context): Long = Settings.Global.getLong(
        context.contentResolver,
        SETTINGS_REVISION_KEY,
        0L
    )

    fun requestUpdate(context: Context, profiles: Collection<AppDisplayProfile>) {
        context.sendBroadcast(
            Intent(ACTION_UPDATE).apply {
                setPackage("android")
                putExtra(EXTRA_ENCODED_PROFILES, encode(profiles))
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun normalize(profiles: Collection<AppDisplayProfile>): Map<String, AppDisplayProfile> =
        profiles.asSequence()
            .map { it.copy(packageName = it.packageName.trim()) }
            .filter { PACKAGE_PATTERN.matches(it.packageName) }
            .mapNotNull(::canonicalize)
            .filterNot {
                it.isDefault &&
                    it.packageName != GLOBAL_PROFILE_PACKAGE_NAME
            }
            .associateBy(AppDisplayProfile::packageName)
            .toSortedMap()

    fun encode(profiles: Collection<AppDisplayProfile>): String {
        val normalized = normalize(profiles)
        if (normalized.isEmpty()) return EMPTY_VALUE
        return buildString {
            append(VERSION)
            normalized.values.forEach { profile ->
                append('\n')
                append(profile.packageName)
                append('|')
                append(profile.fullscreenPercent)
                append('|')
                append(profile.windowMode.name)
                append('|')
                append(profile.popupWidthPercent)
                append('|')
                append(profile.popupHeightPercent)
            }
        }
    }

    fun decode(raw: String?): Map<String, AppDisplayProfile>? {
        if (raw == null) return emptyMap()
        val lines = raw.lineSequence().toList()
        return when (lines.firstOrNull()) {
            VERSION -> decodeV2(lines)
            LEGACY_VERSION -> decodeV1(lines)
            else -> null
        }
    }

    private fun decodeV2(lines: List<String>): Map<String, AppDisplayProfile>? {
        if (lines.size == 2 && lines[1] == "-") return emptyMap()
        if (lines.size < 2) return null
        val decoded = lines.drop(1).map { line ->
            val parts = line.split('|')
            if (parts.size != 5 || !PACKAGE_PATTERN.matches(parts[0])) return null
            val profile = AppDisplayProfile(
                packageName = parts[0],
                fullscreenPercent = parts[1].toIntOrNull() ?: return null,
                windowMode = runCatching { AppWindowMode.valueOf(parts[2]) }.getOrNull()
                    ?: return null,
                popupWidthPercent = parts[3].toIntOrNull() ?: return null,
                popupHeightPercent = parts[4].toIntOrNull() ?: return null
            )
            if (!isValid(profile)) return null
            profile
        }
        if (decoded.map(AppDisplayProfile::packageName).distinct().size != decoded.size) return null
        return normalize(decoded)
    }

    private fun decodeV1(lines: List<String>): Map<String, AppDisplayProfile>? {
        if (lines.size == 2 && lines[1] == "-") return emptyMap()
        if (lines.size < 2) return null
        val decoded = lines.drop(1).map { line ->
            val parts = line.split('|')
            if (parts.size != 4 || !PACKAGE_PATTERN.matches(parts[0])) return null
            val mode = runCatching { AppWindowMode.valueOf(parts[2]) }.getOrNull() ?: return null
            val profile = when {
                mode == AppWindowMode.POPUP -> AppDisplayProfile(
                    packageName = parts[0],
                    windowMode = mode,
                    popupWidthPercent = when (parts[3]) {
                        "SMALL" -> 70
                        "MEDIUM" -> 82
                        "LARGE" -> 92
                        else -> return null
                    },
                    popupHeightPercent = when (parts[3]) {
                        "SMALL" -> 64
                        "MEDIUM" -> 76
                        "LARGE" -> 88
                        else -> return null
                    }
                )
                else -> AppDisplayProfile(
                    packageName = parts[0],
                    fullscreenPercent = when (parts[1]) {
                        "DEFAULT" -> 100
                        "BALANCED" -> 90
                        "MORE_CONTENT" -> 80
                        else -> return null
                    }
                )
            }
            profile
        }
        if (decoded.map(AppDisplayProfile::packageName).distinct().size != decoded.size) return null
        return normalize(decoded)
    }

    private fun canonicalize(profile: AppDisplayProfile): AppDisplayProfile? =
        profile.copy(
            fullscreenPercent = profile.fullscreenPercent,
            popupWidthPercent = profile.popupWidthPercent,
            popupHeightPercent = profile.popupHeightPercent
        ).takeIf(::isValid)

    private fun isValid(profile: AppDisplayProfile): Boolean =
        profile.fullscreenPercent in FULLSCREEN_MIN_PERCENT..FULLSCREEN_MAX_PERCENT &&
            profile.popupWidthPercent in POPUP_MIN_PERCENT..POPUP_MAX_PERCENT &&
            profile.popupHeightPercent in POPUP_MIN_PERCENT..POPUP_MAX_PERCENT
}
