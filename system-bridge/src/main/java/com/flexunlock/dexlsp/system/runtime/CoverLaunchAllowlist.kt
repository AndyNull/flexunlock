package com.flexunlock.dexlsp.system.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.provider.Settings
import android.util.Log
import com.flexunlock.dexlsp.config.CoverLaunchConfig
import com.flexunlock.dexlsp.config.CoverDisplayConfig
import de.robv.android.xposed.XposedBridge

object CoverLaunchAllowlist {
    private const val TAG = "FlexUnlock-SystemBridge"
    private const val CONTROL_PACKAGE = "com.flexunlock.dexlsp"
    private const val SAMSUNG_LAUNCHER_PACKAGE = "com.sec.android.app.launcher"
    private const val SECONDARY_LAUNCHER_HINT = "SecondaryLauncher"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val SUB_HOME_ACTIVITY = "com.android.systemui.subscreen.SubHomeActivity"
    private const val CONTROL_PERMISSION =
        "com.flexunlock.dexlsp.permission.CONTROL_COVER_DEX"

    @Volatile
    private var packages: Set<String> = emptySet()

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var initialized = false

    fun initialize(context: Context, handler: Handler) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            systemContext = context
            packages = CoverLaunchConfig.read(context)
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context?, intent: Intent?) {
                        if (intent?.action != CoverLaunchConfig.ACTION_UPDATE_ALLOWLIST) return
                        val requested = intent.getStringArrayExtra(
                            CoverLaunchConfig.EXTRA_PACKAGES
                        )?.asList() ?: return
                        val normalized = CoverLaunchConfig.normalize(requested)
                        val encoded = CoverLaunchConfig.encode(normalized)
                        if (!Settings.Global.putString(
                                context.contentResolver,
                                CoverLaunchConfig.SETTINGS_KEY,
                                encoded
                            )
                        ) {
                            log("cover launch allowlist persistence rejected")
                            return
                        }
                        packages = normalized
                        log("cover launch allowlist updated packages=${normalized.size}")
                    }
                },
                IntentFilter(CoverLaunchConfig.ACTION_UPDATE_ALLOWLIST),
                CONTROL_PERMISSION,
                handler,
                Context.RECEIVER_EXPORTED
            )
            initialized = true
            log("cover launch allowlist loaded packages=${packages.size}")
        }
    }

    /**
     * 系统设置及其外屏配对对话框。三星 CoverLauncher 默认把它们当成内屏应用,
     * 合盖时子页会延迟到展开或直接 finish。这些包不走用户白名单。
     */
    private val SYSTEM_COVER_PACKAGES = setOf(
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.samsung.android.settings.intelligence",
        "com.android.systemui"
    )

    /** Good Lock 模块(系统 UI 插件/常见模块)自动放行,无需加入白名单。 */
    private val GOOD_LOCK_MODULE_PREFIXES = listOf(
        "com.samsung.systemui.",
        "com.samsung.android.app.dressroom",
        "com.samsung.android.multistar",
        "com.samsung.android.sidegesturepad",
        "com.samsung.android.app.routines",
        "com.samsung.android.app.taskedge",
        "com.samsung.android.app.clipboardedge",
        "com.samsung.android.soundassistant",
        "com.samsung.android.notistar",
        "com.samsung.android.app.watchface",
        "com.samsung.android.pentastic",
        "com.samsung.android.wonderland",
        "com.samsung.android.clockface",
        "com.samsung.android.app.stepbystep"
    )

    fun containsConfigured(packageName: String?): Boolean {
        val target = packageName ?: return false
        if (target in packages) return true
        return systemContext?.let { target in CoverLaunchConfig.read(it) } == true
    }

    fun fullDexEnabled(): Boolean = systemContext?.let(CoverDisplayConfig::readFullDex) == true

    fun contains(packageName: String?): Boolean {
        if (packageName == null) return false
        if (packageName == CONTROL_PACKAGE) return true
        if (
            packageName != SAMSUNG_LAUNCHER_PACKAGE &&
            fullDexEnabled()
        ) return true
        if (packageName in SYSTEM_COVER_PACKAGES) return true
        if (packageName in packages) return true
        return GOOD_LOCK_MODULE_PREFIXES.any { prefix ->
            if (prefix.endsWith(".")) packageName.startsWith(prefix)
            else packageName == prefix
        }
    }

    /**
     * 合盖外屏启动是否应留在外屏:目标本身可显示,或调用方已是外屏允许包
     * (设置子页、无线调试配对对话框等跨包系统界面)。
     */
    fun allowsCoverLaunch(targetPackage: String?, vararg sourcePackages: String?): Boolean {
        if (contains(targetPackage)) return true
        if (
            targetPackage != SAMSUNG_LAUNCHER_PACKAGE &&
            sourcePackages.any { it == SAMSUNG_LAUNCHER_PACKAGE }
        ) return true
        return sourcePackages.any { contains(it) }
    }

    /**
     * TaskLaunchParams 是否应把这次启动钉到外屏。
     * 三星主桌面 `LauncherActivity` 与外屏 Home 同包名 `com.sec.android.app.launcher`,
     * 白名单/调用方放行后如果整包强制 display 1,系统会反复拉起主桌面再 abort。
     * 只有外屏 SecondaryLauncher 允许被强制;识别不出 Activity 时宁可不强制。
     */
    fun shouldForceCoverTaskDisplay(
        targetPackage: String?,
        activityName: String?,
        vararg sourcePackages: String?
    ): Boolean {
        if (!allowsCoverLaunch(targetPackage, *sourcePackages)) return false
        if (targetPackage == SYSTEM_UI_PACKAGE && activityName == SUB_HOME_ACTIVITY) return false
        if (targetPackage != SAMSUNG_LAUNCHER_PACKAGE) return true
        return activityName.orEmpty().contains(SECONDARY_LAUNCHER_HINT)
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        XposedBridge.log("$TAG: $message")
    }
}
