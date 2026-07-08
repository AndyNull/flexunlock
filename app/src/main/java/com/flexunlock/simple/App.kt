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
                // v0.44.2: 清除旧版残留设置
                Shell.getShell().newJob().add("settings delete global force_resizable_activities").exec()
                Shell.getShell().newJob().add("settings delete global always_supports_multi_window").exec()
                Shell.getShell().newJob().add("settings delete global activities_resizable_for_all").exec()

                // v0.44.2: 禁用旧版自动启用的 AccessibilityService（导致内屏App首次闪退！）
                // AccessibilityService 会注入到每个 App 进程，首次启动时触发 configuration change
                val a11yOut = ArrayList<String>()
                Shell.getShell().newJob().add("settings get secure enabled_accessibility_services")
                    .to(a11yOut, ArrayList()).exec()
                val currentA11y = a11yOut.joinToString("").trim()
                if (currentA11y.contains("com.flexunlock.simple")) {
                    // 移除 FlexUnlock 的 AccessibilityService
                    val cleaned = currentA11y.split(":")
                        .filter { it.isNotEmpty() && it != "null" && !it.contains("com.flexunlock.simple") }
                        .joinToString(":")
                    if (cleaned.isEmpty()) {
                        Shell.getShell().newJob().add("settings put secure enabled_accessibility_services null").exec()
                        Shell.getShell().newJob().add("settings put secure accessibility_enabled 0").exec()
                    } else {
                        Shell.getShell().newJob().add("settings put secure enabled_accessibility_services '$cleaned'").exec()
                    }
                    Timber.i("Disabled legacy AccessibilityService (causes App crash)")
                }

                // v0.44.2: 不自动启用 AccessibilityService（闪退根因）
                // 双击音量键改为手动启用（在 App 设置里点击按钮）
                // 只启动 App 重定向服务
                val intent = Intent(this@App, AppRedirectService::class.java).apply {
                    action = AppRedirectService.ACTION_START
                }
                ContextCompat.startForegroundService(this@App, intent)
                getSharedPreferences("flexunlock", MODE_PRIVATE)
                    .edit().putBoolean("redirect_enabled", true).apply()
                Timber.i("Features auto-enabled (redirect only, no a11y)")
            } catch (e: Exception) {
                Timber.e(e, "Auto-enable failed")
            }
        }
    }
}
