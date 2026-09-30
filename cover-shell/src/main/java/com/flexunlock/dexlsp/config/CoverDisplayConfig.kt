package com.flexunlock.dexlsp.config

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.flexunlock.dexlsp.CoverDisplayResolution
import com.flexunlock.dexlsp.CoverDisplaySelectionSource
import com.flexunlock.dexlsp.CoverDisplaySnapshot
import com.flexunlock.dexlsp.DisplayStableIdentity
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class CoverDisplayCandidateStatus(
    val identity: DisplayStableIdentity,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val name: String?
)

internal data class CoverDisplayOverride(
    val width: Int,
    val height: Int,
    val densityDpi: Int
)

internal data class CoverDisplayMode(
    val width: Int,
    val height: Int,
    val refreshRate: Float
)

internal fun normalizeImeCompactPercent(value: Int): Int = value.coerceIn(
    CoverDisplayConfig.MIN_IME_COMPACT_PERCENT,
    CoverDisplayConfig.MAX_IME_COMPACT_PERCENT
)

internal fun displayModesMatch(first: CoverDisplayMode?, second: CoverDisplayMode?): Boolean =
    first == null && second == null ||
        first != null && second != null &&
        first.width == second.width &&
        first.height == second.height &&
        kotlin.math.abs(first.refreshRate - second.refreshRate) < 0.01f

internal fun selectModeForResolution(
    supported: List<CoverDisplayMode>,
    resolution: CoverDisplayMode,
    preferred: CoverDisplayMode?
): CoverDisplayMode? = supported
    .filter { it.width == resolution.width && it.height == resolution.height }
    .minWithOrNull(
        compareBy<CoverDisplayMode> {
            kotlin.math.abs(it.refreshRate - (preferred?.refreshRate ?: it.refreshRate))
        }.thenByDescending { it.refreshRate }
    )

internal fun selectModeForRefresh(
    supported: List<CoverDisplayMode>,
    refresh: CoverDisplayMode,
    preferred: CoverDisplayMode?
): CoverDisplayMode? = supported
    .filter { kotlin.math.abs(it.refreshRate - refresh.refreshRate) < 0.01f }
    .minWithOrNull(
        compareBy<CoverDisplayMode> {
            if (preferred == null) 0 else
                kotlin.math.abs(it.width.toLong() * it.height - preferred.width.toLong() * preferred.height)
        }.thenByDescending { it.width.toLong() * it.height }
    )

internal data class CoverDisplayStatus(
    val manualIdentity: DisplayStableIdentity?,
    val resolution: CoverDisplayResolution,
    val candidates: List<CoverDisplayCandidateStatus>,
    val displayOverride: CoverDisplayOverride? = null,
    val preferredDisplayMode: CoverDisplayMode? = null,
    val supportedDisplayModes: List<CoverDisplayMode> = emptyList(),
    val fullDexEnabled: Boolean = false,
    val halfModeEnabled: Boolean = false,
    val revision: Long = 0L
)

/** Camera presentation used while the FULL cover channel is active. */
internal enum class CoverCameraMode(val value: Int) {
    INNER(0),
    ORIGINAL(1);

    companion object {
        fun from(value: Int): CoverCameraMode =
            entries.firstOrNull { it.value == value } ?: INNER
    }
}

internal object CoverDisplayConfig {
    const val ACTION_SET = "com.flexunlock.dexlsp.action.SET_COVER_DISPLAY_IDENTITY"
    const val ACTION_REQUEST_STATUS = "com.flexunlock.dexlsp.action.REQUEST_COVER_DISPLAY_STATUS"
    const val ACTION_SET_METRICS = "com.flexunlock.dexlsp.action.SET_COVER_DISPLAY_METRICS"
    const val ACTION_SET_REFRESH_MODE = "com.flexunlock.dexlsp.action.SET_COVER_REFRESH_MODE"
    const val ACTION_SET_FULL_DEX = "com.flexunlock.dexlsp.action.SET_FULL_COVER_DEX"
    const val ACTION_SET_HALF_MODE = "com.flexunlock.dexlsp.action.SET_HALF_COVER_MODE"
    const val ACTION_FULL_DEX_CHANGED = "com.flexunlock.dexlsp.action.FULL_COVER_DEX_CHANGED"
    const val EXTRA_IDENTITY = "cover_display_identity"
    const val EXTRA_METRICS = "cover_display_metrics"
    const val EXTRA_REFRESH_MODE = "cover_refresh_mode"
    const val EXTRA_FULL_DEX = "full_cover_dex"
    const val EXTRA_HALF_MODE = "half_cover_mode"
    const val SETTINGS_IDENTITY_KEY = "flexunlock_cover_display_identity"
    const val SETTINGS_STATUS_KEY = "flexunlock_cover_display_status"
    const val SETTINGS_OVERRIDE_KEY = "flexunlock_cover_display_override"
    const val SETTINGS_OVERRIDE_DISPLAY_ID_KEY = "flexunlock_cover_display_override_id"
    const val SETTINGS_OVERRIDE_UNIQUE_ID_KEY = "flexunlock_cover_display_override_unique_id"
    const val SETTINGS_FULL_DEX_KEY = "flexunlock_full_cover_dex"
    const val SETTINGS_HALF_MODE_KEY = "flexunlock_half_cover_mode"
    const val ACTION_SET_CAMERA_MODE = "com.flexunlock.dexlsp.action.SET_COVER_CAMERA_MODE"
    const val ACTION_SET_LOCKSCREEN_TIMEOUT =
        "com.flexunlock.dexlsp.action.SET_COVER_LOCKSCREEN_TIMEOUT"
    const val ACTION_SET_SYSTEM_UPDATE_BLOCKED =
        "com.flexunlock.dexlsp.action.SET_SYSTEM_UPDATE_BLOCKED"
    const val ACTION_SET_IME_COMPACT =
        "com.flexunlock.dexlsp.action.SET_IME_COMPACT"
    const val EXTRA_CAMERA_ORIGINAL = "cover_camera_original"
    const val EXTRA_LOCKSCREEN_TIMEOUT_MILLIS = "cover_lockscreen_timeout_millis"
    const val EXTRA_SYSTEM_UPDATE_BLOCKED = "system_update_blocked"
    const val EXTRA_IME_COMPACT = "ime_compact"
    const val EXTRA_IME_COMPACT_PERCENT = "ime_compact_percent"
    const val SETTINGS_CAMERA_MODE_KEY = "flexunlock_cover_camera_mode"
    const val SETTINGS_LOCKSCREEN_TIMEOUT_KEY = "flexunlock_cover_lockscreen_timeout_ms"
    const val SETTINGS_SYSTEM_UPDATE_BLOCKED_KEY = "flexunlock_system_update_blocked"
    const val SETTINGS_IME_COMPACT_KEY = "flexunlock_ime_compact"
    const val SETTINGS_IME_COMPACT_PERCENT_KEY = "flexunlock_ime_compact_percent"
    const val SETTINGS_FULL_QS_OVERRIDE_KEY = "flexunlock_full_qs_display_override"
    const val SETTINGS_SYSTEM_UPDATE_ORIGINAL_STATES_KEY =
        "flexunlock_system_update_original_states"
    val SYSTEM_UPDATE_PACKAGES = listOf(
        "com.wssyncmldm",
        "com.sec.android.soagent"
    )
    const val DEFAULT_LOCKSCREEN_TIMEOUT_MILLIS = 30_000L
    const val MIN_IME_COMPACT_PERCENT = 35
    const val MAX_IME_COMPACT_PERCENT = 100
    const val DEFAULT_IME_COMPACT_PERCENT = 60
    val LOCKSCREEN_TIMEOUT_OPTIONS_MILLIS = listOf(
        30_000L,
        60_000L,
        120_000L,
        1_980_000L,
        300_000L,
        600_000L,
        1_800_000L
    )
    const val AUTO_VALUE = "auto"

    fun readIdentity(context: Context): DisplayStableIdentity? = decodeIdentity(
        Settings.Global.getString(context.contentResolver, SETTINGS_IDENTITY_KEY)
    )

    fun readStatus(context: Context): CoverDisplayStatus? = decodeStatus(
        Settings.Global.getString(context.contentResolver, SETTINGS_STATUS_KEY)
    )

    fun readOverride(context: Context): CoverDisplayOverride? = decodeOverride(
        Settings.Global.getString(context.contentResolver, SETTINGS_OVERRIDE_KEY)
    )

    fun requestMode(context: Context, identity: DisplayStableIdentity?) {
        context.sendBroadcast(
            Intent(ACTION_SET).apply {
                setPackage("android")
                putExtra(EXTRA_IDENTITY, encodeIdentity(identity))
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun requestStatus(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_REQUEST_STATUS).apply {
                setPackage("android")
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun requestMetrics(context: Context, value: CoverDisplayOverride?) {
        context.sendBroadcast(
            Intent(ACTION_SET_METRICS).apply {
                setPackage("android")
                putExtra(EXTRA_METRICS, encodeOverride(value))
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun requestFullDex(context: Context, enabled: Boolean) {
        context.sendBroadcast(
            Intent(ACTION_SET_FULL_DEX).apply {
                setPackage("android")
                putExtra(EXTRA_FULL_DEX, enabled)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun requestHalfMode(context: Context, enabled: Boolean) {
        context.sendBroadcast(
            Intent(ACTION_SET_HALF_MODE).apply {
                setPackage("android")
                putExtra(EXTRA_HALF_MODE, enabled)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun requestCameraMode(context: Context, mode: CoverCameraMode) {
        context.sendBroadcast(
            Intent(ACTION_SET_CAMERA_MODE).apply {
                setPackage("android")
                putExtra(EXTRA_CAMERA_ORIGINAL, mode == CoverCameraMode.ORIGINAL)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun requestRefreshMode(context: Context, value: CoverDisplayMode?) {
        context.sendBroadcast(
            Intent(ACTION_SET_REFRESH_MODE).apply {
                setPackage("android")
                putExtra(EXTRA_REFRESH_MODE, encodeDisplayMode(value))
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun requestLockscreenTimeout(context: Context, timeoutMillis: Long) {
        context.sendBroadcast(
            Intent(ACTION_SET_LOCKSCREEN_TIMEOUT).apply {
                setPackage("android")
                putExtra(EXTRA_LOCKSCREEN_TIMEOUT_MILLIS, timeoutMillis)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun requestSystemUpdateBlocked(context: Context, blocked: Boolean) {
        context.sendBroadcast(
            Intent(ACTION_SET_SYSTEM_UPDATE_BLOCKED).apply {
                setPackage("android")
                putExtra(EXTRA_SYSTEM_UPDATE_BLOCKED, blocked)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun requestImeCompact(
        context: Context,
        compact: Boolean,
        percent: Int = readImeCompactPercent(context)
    ) {
        context.sendBroadcast(
            Intent(ACTION_SET_IME_COMPACT).apply {
                setPackage("android")
                putExtra(EXTRA_IME_COMPACT, compact)
                putExtra(EXTRA_IME_COMPACT_PERCENT, normalizeImeCompactPercent(percent))
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun readImeCompact(context: Context): Boolean = Settings.Global.getInt(
        context.contentResolver,
        SETTINGS_IME_COMPACT_KEY,
        1
    ) == 1

    fun readImeCompactPercent(context: Context): Int = normalizeImeCompactPercent(
        Settings.Global.getInt(
            context.contentResolver,
            SETTINGS_IME_COMPACT_PERCENT_KEY,
            DEFAULT_IME_COMPACT_PERCENT
        )
    )

    fun readCameraMode(context: Context): CoverCameraMode = CoverCameraMode.from(
        Settings.Global.getInt(context.contentResolver, SETTINGS_CAMERA_MODE_KEY, 0)
    )

    fun readLockscreenTimeoutMillis(context: Context): Long =
        normalizeLockscreenTimeoutMillis(
            Settings.Global.getLong(
                context.contentResolver,
                SETTINGS_LOCKSCREEN_TIMEOUT_KEY,
                DEFAULT_LOCKSCREEN_TIMEOUT_MILLIS
            )
        )

    fun readSystemUpdateBlocked(context: Context): Boolean =
        Settings.Global.getInt(
            context.contentResolver,
            SETTINGS_SYSTEM_UPDATE_BLOCKED_KEY,
            0
        ) == 1

    fun readFullQsOverride(context: Context): CoverDisplayOverride? = decodeOverride(
        Settings.Global.getString(context.contentResolver, SETTINGS_FULL_QS_OVERRIDE_KEY)
    )

    fun readFullDex(context: Context): Boolean = resolveFullDexEnabled(
        Settings.Global.getInt(context.contentResolver, SETTINGS_FULL_DEX_KEY, -1),
        readStatus(context)?.fullDexEnabled == true
    )

    fun readHalfMode(context: Context): Boolean = Settings.Global.getInt(
        context.contentResolver,
        SETTINGS_HALF_MODE_KEY,
        0
    ) == 1

    fun encodeOverride(value: CoverDisplayOverride?): String = value?.let {
        "v1|${it.width}|${it.height}|${it.densityDpi}"
    } ?: AUTO_VALUE

    fun decodeOverride(raw: String?): CoverDisplayOverride? {
        if (raw.isNullOrBlank() || raw == AUTO_VALUE) return null
        val parts = raw.split('|')
        if (parts.size != 4 || parts[0] != "v1") return null
        return CoverDisplayOverride(
            width = parts[1].toIntOrNull() ?: return null,
            height = parts[2].toIntOrNull() ?: return null,
            densityDpi = parts[3].toIntOrNull() ?: return null
        )
    }

    fun encodeDisplayMode(value: CoverDisplayMode?): String = value?.let {
        "v1|${it.width}|${it.height}|${it.refreshRate}"
    } ?: AUTO_VALUE

    fun decodeDisplayMode(raw: String?): CoverDisplayMode? {
        if (raw.isNullOrBlank() || raw == AUTO_VALUE) return null
        val parts = raw.split('|')
        if (parts.size != 4 || parts[0] != "v1") return null
        return CoverDisplayMode(
            width = parts[1].toIntOrNull() ?: return null,
            height = parts[2].toIntOrNull() ?: return null,
            refreshRate = parts[3].toFloatOrNull() ?: return null
        )
    }

    fun encodeIdentity(identity: DisplayStableIdentity?): String {
        val value = identity?.normalized() ?: return AUTO_VALUE
        return listOf(value.uniqueId, value.physicalAddress, value.port?.toString())
            .joinToString(".") { part -> encodePart(part) }
    }

    fun decodeIdentity(raw: String?): DisplayStableIdentity? {
        if (raw.isNullOrBlank() || raw == AUTO_VALUE) return null
        val parts = raw.split('.')
        if (parts.size != 3) return null
        return DisplayStableIdentity(
            uniqueId = decodePart(parts[0]),
            physicalAddress = decodePart(parts[1]),
            port = decodePart(parts[2])?.toIntOrNull()
        ).normalized()
    }

    fun encodeStatus(status: CoverDisplayStatus): String = buildList {
        add("v1")
        add(encodeIdentity(status.manualIdentity))
        when (val resolution = status.resolution) {
            is CoverDisplayResolution.Resolved -> {
                add("resolved")
                add(resolution.source.name)
                add(encodeSnapshot(resolution.snapshot))
            }
            is CoverDisplayResolution.Unavailable -> {
                add("unavailable")
                add(encodePart(resolution.reason))
            }
            is CoverDisplayResolution.Ambiguous -> {
                add("ambiguous")
                add(resolution.candidateIds.joinToString(","))
            }
        }
        status.candidates.forEach { candidate ->
            add(
                listOf(
                    "candidate",
                    encodeIdentity(candidate.identity),
                    candidate.displayId.toString(),
                    candidate.width.toString(),
                    candidate.height.toString(),
                    candidate.densityDpi.toString(),
                    encodePart(candidate.name)
                ).joinToString("|")
            )
        }
        add("override|${encodeOverride(status.displayOverride)}")
        add("preferredMode|${encodeDisplayMode(status.preferredDisplayMode)}")
        status.supportedDisplayModes.forEach { mode ->
            add("supportedMode|${encodeDisplayMode(mode)}")
        }
        add("fullDex|${if (status.fullDexEnabled) 1 else 0}")
        add("halfMode|${if (status.halfModeEnabled) 1 else 0}")
        add("revision|${status.revision}")
    }.joinToString("\n")

    fun decodeStatus(raw: String?): CoverDisplayStatus? {
        val lines = raw?.lineSequence()?.filter(String::isNotBlank)?.toList() ?: return null
        if (lines.size < 4 || lines.first() != "v1") return null
        val manual = decodeIdentity(lines[1])
        val resolution = when (lines[2]) {
            "resolved" -> {
                if (lines.size < 5) return null
                val source = runCatching { CoverDisplaySelectionSource.valueOf(lines[3]) }
                    .getOrDefault(CoverDisplaySelectionSource.AUTO)
                val snapshot = decodeSnapshot(lines[4]) ?: return null
                CoverDisplayResolution.Resolved(snapshot, source)
            }
            "unavailable" -> CoverDisplayResolution.Unavailable(
                decodePart(lines[3]).orEmpty().ifBlank { "unknown" }
            )
            "ambiguous" -> CoverDisplayResolution.Ambiguous(
                lines[3].split(',').mapNotNull(String::toIntOrNull)
            )
            else -> return null
        }
        val candidateStart = if (lines[2] == "resolved") 5 else 4
        val candidates = lines.drop(candidateStart).mapNotNull(::decodeCandidate)
        val revision = lines.firstOrNull { it.startsWith("revision|") }
            ?.substringAfter('|')
            ?.toLongOrNull()
            ?: 0L
        val displayOverride = lines.firstOrNull { it.startsWith("override|") }
            ?.substringAfter('|')
            ?.let(::decodeOverride)
        val preferredDisplayMode = lines.firstOrNull { it.startsWith("preferredMode|") }
            ?.substringAfter('|')
            ?.let(::decodeDisplayMode)
        val supportedDisplayModes = lines
            .filter { it.startsWith("supportedMode|") }
            .mapNotNull { it.substringAfter('|').let(::decodeDisplayMode) }
        val fullDexEnabled = lines.firstOrNull { it.startsWith("fullDex|") }
            ?.substringAfter('|') == "1"
        val halfModeEnabled = lines.firstOrNull { it.startsWith("halfMode|") }
            ?.substringAfter('|') == "1"
        return CoverDisplayStatus(
            manualIdentity = manual,
            resolution = resolution,
            candidates = candidates,
            displayOverride = displayOverride,
            preferredDisplayMode = preferredDisplayMode,
            supportedDisplayModes = supportedDisplayModes,
            fullDexEnabled = fullDexEnabled,
            halfModeEnabled = halfModeEnabled,
            revision = revision
        )
    }

    private fun encodeSnapshot(snapshot: CoverDisplaySnapshot): String = listOf(
        snapshot.id.toString(),
        snapshot.width.toString(),
        snapshot.height.toString(),
        snapshot.rotation.toString(),
        encodePart(snapshot.uniqueId),
        encodePart(snapshot.name),
        snapshot.type.toString()
    ).joinToString("|")

    private fun decodeSnapshot(raw: String): CoverDisplaySnapshot? {
        val parts = raw.split('|')
        if (parts.size !in 6..7) return null
        return CoverDisplaySnapshot(
            id = parts[0].toIntOrNull() ?: return null,
            width = parts[1].toIntOrNull() ?: return null,
            height = parts[2].toIntOrNull() ?: return null,
            rotation = parts[3].toIntOrNull() ?: return null,
            uniqueId = decodePart(parts[4]),
            name = decodePart(parts[5]),
            type = parts.getOrNull(6)?.toIntOrNull() ?: 0
        )
    }

    private fun decodeCandidate(raw: String): CoverDisplayCandidateStatus? {
        val parts = raw.split('|')
        if (parts.size !in 6..7 || parts[0] != "candidate") return null
        val hasDensity = parts.size == 7
        return CoverDisplayCandidateStatus(
            identity = decodeIdentity(parts[1]) ?: return null,
            displayId = parts[2].toIntOrNull() ?: return null,
            width = parts[3].toIntOrNull() ?: return null,
            height = parts[4].toIntOrNull() ?: return null,
            densityDpi = if (hasDensity) parts[5].toIntOrNull() ?: return null else 0,
            name = decodePart(parts[if (hasDensity) 6 else 5])
        )
    }

    private fun encodePart(value: String?): String = if (value == null) {
        "-"
    } else {
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            value.toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun decodePart(value: String): String? {
        if (value == "-") return null
        return runCatching {
            String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
        }.getOrNull()
    }
}

internal fun normalizeLockscreenTimeoutMillis(value: Long): Long =
    value.takeIf(CoverDisplayConfig.LOCKSCREEN_TIMEOUT_OPTIONS_MILLIS::contains)
        ?: CoverDisplayConfig.DEFAULT_LOCKSCREEN_TIMEOUT_MILLIS

internal fun encodeSystemUpdatePackageStates(states: Map<String, Int>): String =
    states.entries
        .sortedBy(Map.Entry<String, Int>::key)
        .joinToString(prefix = "v1\n", separator = "\n") { (packageName, state) ->
            "$packageName|$state"
        }

internal fun decodeSystemUpdatePackageStates(raw: String?): Map<String, Int>? {
    if (raw.isNullOrBlank()) return null
    val lines = raw.lineSequence().toList()
    if (lines.firstOrNull() != "v1") return null
    return lines.drop(1).associate { line ->
        val parts = line.split('|')
        if (parts.size != 2 || parts[0] !in CoverDisplayConfig.SYSTEM_UPDATE_PACKAGES) return null
        val state = parts[1].toIntOrNull()?.takeIf { it in 0..4 } ?: return null
        parts[0] to state
    }.takeIf { it.isNotEmpty() }
}

internal fun resolveFullDexEnabled(persistedValue: Int, statusEnabled: Boolean): Boolean =
    when (persistedValue) {
        0 -> false
        1 -> true
        else -> statusEnabled
    }
