package com.flexunlock.dexlsp.config

import android.content.Context
import android.content.Intent
import android.provider.Settings

object CoverLaunchConfig {
    const val ACTION_UPDATE_ALLOWLIST =
        "com.flexunlock.dexlsp.action.UPDATE_COVER_LAUNCH_ALLOWLIST"
    const val EXTRA_PACKAGES = "packages"
    const val SETTINGS_KEY = "flexunlock_cover_launch_allowlist"

    fun read(context: Context): Set<String> {
        val raw = Settings.Global.getString(context.contentResolver, SETTINGS_KEY)
        return decode(raw)
    }

    /** 常用 Good Lock 模块默认包(仅用于合并展示,不强制写入)。 */
    val DEFAULT_MODULE_PACKAGES = setOf(
        "com.samsung.android.sidegesturepad",
        "com.samsung.android.multistar",
        "com.samsung.android.app.routines",
        "com.samsung.android.app.taskedge",
        "com.samsung.android.app.clipboardedge"
    )

    fun requestUpdate(context: Context, packages: Collection<String>) {
        context.sendBroadcast(
            Intent(ACTION_UPDATE_ALLOWLIST).apply {
                setPackage("android")
                putExtra(EXTRA_PACKAGES, normalize(packages).toTypedArray())
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
        )
    }

    fun encode(packages: Collection<String>): String {
        val normalized = normalize(packages)
        return if (normalized.isEmpty()) EMPTY_VALUE else normalized.joinToString("\n")
    }

    fun decode(raw: String?): Set<String> = when {
        raw == null -> emptySet()
        raw == EMPTY_VALUE -> emptySet()
        else -> normalize(raw.lineSequence().toList())
    }

    fun normalize(packages: Collection<String>): Set<String> = packages
        .asSequence()
        .map(String::trim)
        .filter(PACKAGE_PATTERN::matches)
        .toSortedSet()

    private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private const val EMPTY_VALUE = "-"
}