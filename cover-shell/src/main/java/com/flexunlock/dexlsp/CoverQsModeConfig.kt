package com.flexunlock.dexlsp

import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.nio.charset.StandardCharsets
import java.util.Base64

internal enum class CoverQsMode(val value: Int) {
    ORIGINAL(0),
    FULL(1);

    companion object {
        fun from(value: Int): CoverQsMode = entries.firstOrNull { it.value == value } ?: ORIGINAL
        fun fromOrNull(value: Int): CoverQsMode? = entries.firstOrNull { it.value == value }
    }
}

internal enum class CoverQsTransition {
    ORIGINAL,
    ENABLING,
    FULL,
    DISABLING,
    RECOVERING
}

internal data class DisplayMetricsSnapshot(
    val displayId: Int,
    val uniqueId: String,
    val initialWidth: Int,
    val initialHeight: Int,
    val initialDensity: Int,
    val baseWidth: Int,
    val baseHeight: Int,
    val baseDensity: Int
)

internal data class CoverQsTransaction(
    val requested: CoverQsMode = CoverQsMode.ORIGINAL,
    val applied: CoverQsMode = CoverQsMode.ORIGINAL,
    val state: CoverQsTransition = CoverQsTransition.ORIGINAL,
    val snapshots: List<DisplayMetricsSnapshot> = emptyList()
) {
    val isStableOriginal: Boolean
        get() = state == CoverQsTransition.ORIGINAL && applied == CoverQsMode.ORIGINAL

    val isStableFull: Boolean
        get() = state == CoverQsTransition.FULL && applied == CoverQsMode.FULL
}

internal object CoverQsModeConfig {
    const val ACTION_SET = "com.flexunlock.dexlsp.action.SET_COVER_QS_MODE"
    const val EXTRA_MODE = "cover_qs_mode"
    const val SETTINGS_TRANSACTION_KEY = "flexunlock_cover_qs_transaction"

    fun readTransaction(context: Context): CoverQsTransaction = decode(
        Settings.Global.getString(context.contentResolver, SETTINGS_TRANSACTION_KEY)
    ) ?: CoverQsTransaction()

    fun readApplied(context: Context): CoverQsMode = readTransaction(context).applied

    fun write(context: Context, transaction: CoverQsTransaction): Boolean =
        Settings.Global.putString(
            context.contentResolver,
            SETTINGS_TRANSACTION_KEY,
            encode(transaction)
        )

    fun request(context: Context, mode: CoverQsMode) {
        context.sendBroadcast(
            Intent(ACTION_SET).apply {
                setPackage("android")
                putExtra(EXTRA_MODE, mode.value)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun encode(transaction: CoverQsTransaction): String = buildList {
        add(
            listOf(
                "v1",
                transaction.state.name,
                transaction.requested.value,
                transaction.applied.value
            ).joinToString("|")
        )
        transaction.snapshots.forEach { snapshot ->
            add(
                listOf(
                    "display",
                    snapshot.displayId,
                    encodePart(snapshot.uniqueId),
                    snapshot.initialWidth,
                    snapshot.initialHeight,
                    snapshot.initialDensity,
                    snapshot.baseWidth,
                    snapshot.baseHeight,
                    snapshot.baseDensity
                ).joinToString("|")
            )
        }
    }.joinToString("\n")

    fun decode(raw: String?): CoverQsTransaction? {
        val lines = raw?.lineSequence()?.filter(String::isNotBlank)?.toList() ?: return null
        val header = lines.firstOrNull()?.split('|') ?: return null
        if (header.size != 4 || header[0] != "v1") return null
        val state = runCatching { CoverQsTransition.valueOf(header[1]) }.getOrNull() ?: return null
        val requested = header[2].toIntOrNull()?.let(CoverQsMode::fromOrNull) ?: return null
        val applied = header[3].toIntOrNull()?.let(CoverQsMode::fromOrNull) ?: return null
        val snapshots = lines.drop(1).map { decodeSnapshot(it) ?: return null }
        return CoverQsTransaction(requested, applied, state, snapshots)
    }

    private fun decodeSnapshot(raw: String): DisplayMetricsSnapshot? {
        val fields = raw.split('|')
        if (fields.size != 9 || fields[0] != "display") return null
        return DisplayMetricsSnapshot(
            displayId = fields[1].toIntOrNull() ?: return null,
            uniqueId = decodePart(fields[2]) ?: return null,
            initialWidth = fields[3].toIntOrNull() ?: return null,
            initialHeight = fields[4].toIntOrNull() ?: return null,
            initialDensity = fields[5].toIntOrNull() ?: return null,
            baseWidth = fields[6].toIntOrNull() ?: return null,
            baseHeight = fields[7].toIntOrNull() ?: return null,
            baseDensity = fields[8].toIntOrNull() ?: return null
        )
    }

    private fun encodePart(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodePart(value: String): String? = runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrNull()
}
