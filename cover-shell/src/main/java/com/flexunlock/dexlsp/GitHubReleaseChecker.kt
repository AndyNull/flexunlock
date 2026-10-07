package com.flexunlock.dexlsp

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL

internal data class ReleaseInfo(
    val tag: String,
    val name: String,
    val publishedAt: String,
    val notes: String
)

internal sealed interface ReleaseCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : ReleaseCheckResult
    data object UpToDate : ReleaseCheckResult
}

internal object GitHubReleaseChecker {
    const val RELEASES_PAGE = "https://github.com/AndyNull/flexunlock/releases"
    private const val RELEASES_API =
        "https://api.github.com/repos/AndyNull/flexunlock/releases?per_page=30"
    private const val MAX_RESPONSE_CHARS = 256_000
    private const val MAX_RELEASE_NOTES_CHARS = 6_000

    fun check(context: Context, currentVersion: String): Result<ReleaseCheckResult> = runCatching {
        val connection = URL(RELEASES_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "FlexUnlock/$currentVersion")
            val status = connection.responseCode
            if (status !in 200..299) error(context.getString(R.string.ui_283, status))
            val response = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8_192)
                val text = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (text.length + count > MAX_RESPONSE_CHARS) {
                        error(context.getString(R.string.ui_284))
                    }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            val releases = JSONArray(response)
            val newest = (0 until releases.length())
                .map { releases.getJSONObject(it) }
                .filterNot { it.optBoolean("draft") || it.optBoolean("prerelease") }
                .map { json ->
                    val tag = json.getString("tag_name")
                    ReleaseInfo(
                        tag = tag,
                        name = json.optString("name").ifBlank { tag },
                        publishedAt = json.optString("published_at")
                            .substringBefore('T')
                            .ifBlank { context.getString(R.string.ui_233) },
                        notes = sanitizeReleaseNotes(json.optString("body"))
                    )
                }
                .filter { release -> compareVersions(release.tag, currentVersion) > 0 }
                .maxWithOrNull { left, right -> compareVersions(left.tag, right.tag) }

            newest
                ?.let(ReleaseCheckResult::UpdateAvailable)
                ?: ReleaseCheckResult.UpToDate
        } finally {
            connection.disconnect()
        }
    }

    fun failureMessage(context: Context, error: Throwable): String = when (error) {
        is UnknownHostException -> context.getString(R.string.ui_285)
        is SocketTimeoutException -> context.getString(R.string.ui_286)
        else -> error.message ?: context.getString(R.string.ui_287)
    }

    internal fun compareVersions(candidate: String, current: String): Int {
        val candidateParts = numericParts(candidate)
        val currentParts = numericParts(current)
        val size = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until size) {
            val left = candidateParts.getOrElse(index) { 0 }
            val right = currentParts.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    private fun sanitizeReleaseNotes(value: String): String {
        val plainText = value
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { character ->
                character == '\n' || character == '\t' || !character.isISOControl()
            }
            .trim()
        return if (plainText.length <= MAX_RELEASE_NOTES_CHARS) {
            plainText
        } else {
            plainText.take(MAX_RELEASE_NOTES_CHARS).trimEnd() + "\n…"
        }
    }

    private fun numericParts(value: String): List<Int> =
        Regex("\\d+").findAll(value).map { it.value.toIntOrNull() ?: 0 }.toList()
}
