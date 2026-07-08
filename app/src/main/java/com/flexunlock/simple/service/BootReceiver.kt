package com.flexunlock.simple.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Timber.i("BootReceiver received: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                // 开机自启
                startGuardService(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // v0.51: App 更新后自动启动
                // 这解决了"首次打开闪退"问题：
                // 安装/更新后包处于 STOPPED 状态，第一次点图标会超时失败
                // 收到 MY_PACKAGE_REPLACED 后启动一次 App，让它脱离 STOPPED 状态
                Timber.i("Package replaced, auto-starting to avoid first-open crash")
                startGuardService(context)
                // 也启动 MainActivity 一次（让包脱离 STOPPED 状态）
                try {
                    val mainIntent = Intent(context, com.flexunlock.simple.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    context.startActivity(mainIntent)
                    Timber.i("MainActivity started to un-stop package")
                } catch (e: Exception) {
                    Timber.w(e, "Failed to start MainActivity from package replaced")
                }
            }
        }
    }

    private fun startGuardService(context: Context) {
        val shouldAutoStart = context.getSharedPreferences("flexunlock", Context.MODE_PRIVATE)
            .getBoolean("guard_enabled", false)
        if (!shouldAutoStart) {
            Timber.i("Guard not enabled, skipping")
            return
        }

        val serviceIntent = Intent(context, CoverHomeGuardService::class.java).apply {
            action = CoverHomeGuardService.ACTION_START_GUARD
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Timber.i("CoverHomeGuardService auto-started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start service")
        }
    }
}
