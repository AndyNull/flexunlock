package com.flexunlock.dexlsp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

internal enum class DebugLogMode(val value: String, val label: Int) {
    COMPACT("compact", R.string.ui_278),
    FULL("full", R.string.ui_279);

    companion object {
        fun from(value: String?): DebugLogMode = entries.firstOrNull { it.value == value } ?: COMPACT
    }
}

internal data class DebugLogStats(
    val count: Int,
    val totalBytes: Long,
    val latestUri: Uri?
)

internal fun normalizeDebugLogLimitMb(value: Int): Int = value.coerceIn(10, 500)

internal fun logcatCommand(mode: DebugLogMode): List<String> = when (mode) {
    DebugLogMode.COMPACT -> listOf(
        "logcat", "-b", "main", "-b", "system", "-b", "crash",
        "-v", "threadtime", "-T", "2000",
        "FlexUnlock-SystemBridge:V",
        "FlexUnlock-DisplayResolver:V",
        "FlexUnlock-CoverShell:V",
        "FlexUnlock-CoverIme:V",
        "FlexUnlock-RecentsPolicy:V",
        "FlexUnlock-FullDexIme:V",
        "LSPosedFramework:I",
        "ActivityTaskManager:I",
        "WindowManager:I",
        "DisplayManagerService:I",
        "SurfaceFlinger:I",
        "AndroidRuntime:W",
        "*:W"
    )
    DebugLogMode.FULL -> listOf(
        "logcat", "-b", "main", "-b", "system", "-b", "crash", "-b", "events",
        "-v", "threadtime", "-T", "10000", "*:D"
    )
}

internal fun diagnosticCommands(mode: DebugLogMode): List<List<String>> = when (mode) {
    DebugLogMode.COMPACT -> listOf(
        listOf("dumpsys", "display"),
        listOf("dumpsys", "window", "displays"),
        listOf("dumpsys", "activity", "displays"),
        listOf("dumpsys", "power"),
        listOf("dumpsys", "input"),
        listOf("dumpsys", "input_method"),
        listOf("dumpsys", "device_state")
    )
    DebugLogMode.FULL -> listOf(
        listOf("dumpsys", "display"),
        listOf("dumpsys", "window"),
        listOf("dumpsys", "activity", "activities"),
        listOf("dumpsys", "activity", "processes"),
        listOf("dumpsys", "power"),
        listOf("dumpsys", "input"),
        listOf("dumpsys", "input_method"),
        listOf("dumpsys", "device_state"),
        listOf("dumpsys", "SurfaceFlinger"),
        listOf("dumpsys", "package", "com.flexunlock.dexlsp")
    )
}

internal object DebugLogCaptureConfig {
    private const val PREFS = "debug_log_capture"
    private const val KEY_MODE = "mode"
    private const val KEY_LIMIT_MB = "limit_mb"
    const val DEFAULT_LIMIT_MB = 50

    fun readMode(context: Context): DebugLogMode = DebugLogMode.from(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
    )

    fun writeMode(context: Context, mode: DebugLogMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.value)
            .apply()
    }

    fun readLimitMb(context: Context): Int = normalizeDebugLogLimitMb(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LIMIT_MB, DEFAULT_LIMIT_MB)
    )

    fun writeLimitMb(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LIMIT_MB, normalizeDebugLogLimitMb(value))
            .apply()
    }
}

class DebugLogCaptureService : Service() {
    @Volatile private var logcatProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        updateNotificationChannel()
    }

    private fun updateNotificationChannel() {
        val context = ModuleLanguageStore.wrap(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.ui_280), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_LANGUAGE -> {
                updateNotificationChannel()
                if (active) getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(DebugLogCaptureConfig.readMode(this), DebugLogCaptureConfig.readLimitMb(this))
                )
            }
            else -> startCapture()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        active = false
        logcatProcess?.destroy()
        super.onDestroy()
    }

    private fun startCapture() {
        if (active) return
        active = true
        val mode = DebugLogCaptureConfig.readMode(this)
        val limitMb = DebugLogCaptureConfig.readLimitMb(this)
        startForeground(NOTIFICATION_ID, notification(mode, limitMb))
        thread(name = "FlexUnlock-log-capture") {
            runCatching { captureSession(mode, limitMb) }
            active = false
            logcatProcess?.destroy()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopCapture() {
        if (!active) return stopSelf()
        active = false
        logcatProcess?.destroy()
    }

    private fun captureSession(mode: DebugLogMode, limitMb: Int) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "FlexUnlock-$stamp-${mode.value}.log"
        val maxBytes = limitMb.toLong() * 1024L * 1024L
        val uri = createDownload(name)
        latestCaptureUri = uri
        contentResolver.openOutputStream(uri, "w")!!.buffered().use { output ->
            var written = writeText(output, captureHeader(mode, limitMb), maxBytes, 0L)
            written = writeSnapshot(output, mode, "START", maxBytes, written)
            if (active && written < maxBytes) {
                written = streamLogcat(output, mode, maxBytes, written)
            }
            if (written < maxBytes) {
                writeSnapshot(output, mode, "STOP", maxBytes, written)
            }
        }
    }

    private fun streamLogcat(
        output: OutputStream,
        mode: DebugLogMode,
        maxBytes: Long,
        initialBytes: Long
    ): Long {
        var written = initialBytes
        val process = ProcessBuilder(logcatCommand(mode)).redirectErrorStream(true).start()
        logcatProcess = process
        process.inputStream.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (active && written < maxBytes) {
                val count = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    break
                }
                if (count < 0) break
                val allowed = minOf(count.toLong(), maxBytes - written).toInt()
                output.write(buffer, 0, allowed)
                written += allowed
            }
        }
        process.destroy()
        logcatProcess = null
        return written
    }

    private fun writeSnapshot(
        output: OutputStream,
        mode: DebugLogMode,
        phase: String,
        maxBytes: Long,
        initialBytes: Long
    ): Long {
        var written = writeText(output, "\n===== SNAPSHOT $phase =====\n", maxBytes, initialBytes)
        for (command in diagnosticCommands(mode)) {
            if (written >= maxBytes) break
            written = writeText(
                output,
                "\n===== ${command.joinToString(" ")} =====\n",
                maxBytes,
                written
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.use { input ->
                val buffer = ByteArray(32 * 1024)
                while (written < maxBytes) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val allowed = minOf(count.toLong(), maxBytes - written).toInt()
                    output.write(buffer, 0, allowed)
                    written += allowed
                }
            }
            process.destroy()
        }
        output.flush()
        return written
    }

    private fun writeText(
        output: OutputStream,
        value: String,
        maxBytes: Long,
        initialBytes: Long
    ): Long {
        val bytes = value.toByteArray()
        val allowed = minOf(bytes.size.toLong(), maxBytes - initialBytes).coerceAtLeast(0L).toInt()
        output.write(bytes, 0, allowed)
        return initialBytes + allowed
    }

    private fun createDownload(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, LOG_DIRECTORY)
        }
        return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create $name")
    }

    private fun captureHeader(mode: DebugLogMode, limitMb: Int): String = buildString {
        append("FlexUnlock diagnostic log\n")
        append("mode=${mode.value}\n")
        append("limitMb=$limitMb\n")
        append("startedAt=${Date()}\n")
    }

    private fun notification(mode: DebugLogMode, limitMb: Int): Notification {
        val context = ModuleLanguageStore.wrap(this)
        val stopIntent = Intent(this, DebugLogCaptureService::class.java).setAction(ACTION_STOP)
        val pendingStop = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.ui_281, context.getString(mode.label)))
            .setContentText(context.getString(R.string.ui_282, limitMb))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.ui_205), pendingStop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.flexunlock.dexlsp.action.START_DEBUG_LOG"
        const val ACTION_STOP = "com.flexunlock.dexlsp.action.STOP_DEBUG_LOG"
        private const val ACTION_LANGUAGE = "com.flexunlock.dexlsp.action.DEBUG_LOG_LANGUAGE"
        private const val CHANNEL_ID = "flexunlock_debug_log"
        private const val NOTIFICATION_ID = 1739
        private const val LOG_DIRECTORY = "Download/FlexUnlockLogs/"

        @Volatile private var active = false
        @Volatile private var latestCaptureUri: Uri? = null

        fun isCaptureActive(): Boolean = active

        fun refreshLanguage(context: Context) {
            if (active) context.startService(
                Intent(context, DebugLogCaptureService::class.java).setAction(ACTION_LANGUAGE)
            )
        }

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, DebugLogCaptureService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DebugLogCaptureService::class.java).setAction(ACTION_STOP)
            )
        }

        internal fun logStats(context: Context): DebugLogStats {
            var count = 0
            var totalBytes = 0L
            var latest = latestCaptureUri
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.SIZE)
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                LOG_SELECTION,
                LOG_SELECTION_ARGS,
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (count == 0) {
                        latest = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(0)
                        )
                    }
                    totalBytes += cursor.getLong(1)
                    count++
                }
            }
            return DebugLogStats(count, totalBytes, latest)
        }

        internal fun clearLogs(context: Context): Int = context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            LOG_SELECTION,
            LOG_SELECTION_ARGS
        ).also {
            latestCaptureUri = null
        }

        private val LOG_SELECTION =
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        private val LOG_SELECTION_ARGS = arrayOf(LOG_DIRECTORY, "FlexUnlock-%")
    }
}
