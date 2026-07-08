package com.flexunlock.simple

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.flexunlock.simple.root.RootA11yEnabler
import com.flexunlock.simple.service.AppRedirectService
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER)
                .setTimeout(15)
        )
        Timber.i("FlexUnlock v${BuildConfig.VERSION_NAME} created")
        autoEnableFeatures()
    }

    private fun autoEnableFeatures() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Thread.sleep(1000)
                // 1. 启用 AccessibilityService（双击音量键）
                if (!RootA11yEnabler.isEnabled()) {
                    RootA11yEnabler.enable()
                }
                // v0.43.0: 移除 forceAllAppsResizable（导致内屏App首次闪退）
                // DeviceStateSwitcher.forceAllAppsResizable()
                // 2. 启动 App 重定向服务
                val intent = Intent(this@App, AppRedirectService::class.java).apply {
                    action = AppRedirectService.ACTION_START
                }
                ContextCompat.startForegroundService(this@App, intent)
                getSharedPreferences("flexunlock", MODE_PRIVATE)
                    .edit().putBoolean("redirect_enabled", true).apply()
                Timber.i("All features auto-enabled")
            } catch (e: Exception) {
                Timber.e(e, "Auto-enable failed")
            }
        }
    }
}
