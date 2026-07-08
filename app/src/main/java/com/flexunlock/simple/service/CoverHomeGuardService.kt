package com.flexunlock.simple.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.flexunlock.simple.DeviceStateSwitcher
import com.flexunlock.simple.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * v0.14.0: 守护服务
 * - 每 3 秒轮询 device_state
 * - state 0 或 3 → 启动桌面到 display 1
 * - 10 秒防抖，避免频繁开合盖样式卡住
 */
class CoverHomeGuardService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var isGuarding = false
    private var pollJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("封面屏守护启动中..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_GUARD -> {
                isGuarding = true
                getSharedPreferences("flexunlock", MODE_PRIVATE)
                    .edit().putBoolean("guard_enabled", true).apply()
                updateNotification("封面屏守护运行中")
                startPolling()
            }
            ACTION_STOP_GUARD -> {
                isGuarding = false
                getSharedPreferences("flexunlock", MODE_PRIVATE)
                    .edit().putBoolean("guard_enabled", false).apply()
                pollJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            Timber.i("Polling started (v0.14.0)")
            var lastActionTime = 0L
            var lastState = -100

            while (isGuarding) {
                try {
                    val stateStr = DeviceStateSwitcher.getCurrentState()
                    val state = stateStr.toIntOrNull() ?: -1
                    val now = System.currentTimeMillis()

                    if (state != lastState) {
                        Timber.i("Device state: $lastState → $state (${DeviceStateSwitcher.stateName(state)})")
                        lastState = state
                    }

                    val coverActive = (state == 0 || state == 3)
                    val canAct = now - lastActionTime > 10000  // 10 秒防抖

                    if (coverActive && canAct) {
                        Timber.i("Cover active (state=$state) → launching home")
                        val result = DeviceStateSwitcher.enableHomeMode()
                        when (result) {
                            is DeviceStateSwitcher.Result.Success -> {
                                Timber.i("✓ Cover home launched")
                                lastActionTime = now
                            }
                            is DeviceStateSwitcher.Result.Failure -> {
                                Timber.w("✗ Launch failed: ${result.error}")
                                lastActionTime = now - 7000
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Polling error")
                }
                delay(3000)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "封面屏守护", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FlexUnlock 守护")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "cover_home_guard"
        private const val NOTIFICATION_ID = 2
        const val ACTION_START_GUARD = "com.flexunlock.simple.START_GUARD"
        const val ACTION_STOP_GUARD = "com.flexunlock.simple.STOP_GUARD"
    }
}
