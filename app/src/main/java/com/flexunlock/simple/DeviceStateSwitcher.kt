package com.flexunlock.simple

import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Samsung Z Flip 设备状态 ID（来自 DeviceStateManager）
 *
 * 0: CLOSED          合盖
 * 1: HALF_OPENED     半开
 * 2: OPENED          展开
 * 3: REAR_DISPLAY    后屏模式
 * 4: CONCURRENT      双屏同时显示 ← 本工具核心切换目标
 *
 * Samsung 还可能有自定义状态 ID（如 5/6/7），可通过 print-states 列出。
 */
object DeviceStateSwitcher {

    /** 目标状态：CONCURRENT — 双屏同时显示，外屏显示内屏完整内容 */
    const val STATE_CONCURRENT = 4

    /** 系统默认（合盖 = 0，展开 = 2，由系统自动管理） */
    const val STATE_RESET = -1

    /** 切换结果 */
    sealed class Result {
        data class Success(val message: String) : Result()
        data class Failure(val error: String) : Result()
    }

    /**
     * 检查 root 是否可用。
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            Timber.w(e, "root check failed")
            false
        }
    }

    // ── 已验证可用的 Samsung Z Flip 5 (One UI 8.5) 组件名 ────────────────────
    // 主桌面：com.sec.android.app.launcher / com.sec.android.app.launcher.activities.LauncherActivity
    // 最近任务：com.sec.android.app.launcher / com.android.quickstep.RecentsActivity ← 已实测可显示在外屏
    private const val LAUNCHER_PKG = "com.sec.android.app.launcher"
    private const val LAUNCHER_HOME_ACT = "com.sec.android.app.launcher.activities.LauncherActivity"
    private const val LAUNCHER_RECENTS_ACT = "com.android.quickstep.RecentsActivity"

    /**
     * 一键组合：state 4 + 启动指定 Activity 到 display 1
     *
     * 这是视频方案的核心：
     *   1. cmd device_state state 4 — 进入 CONCURRENT 状态（外屏不被切回小组件）
     *   2. sleep 1 — 等待系统状态稳定
     *   3. am start --display 1 -n <pkg>/<act> — 启动目标 Activity 到外屏
     *
     * @param activityClass 目标 Activity 全名（如 "com.android.quickstep.RecentsActivity"）
     */
    private suspend fun enableConcurrentAndLaunch(activityClass: String): Result = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) {
            return@withContext Result.Failure("未授权 root 权限")
        }

        // v0.15.0: 移除 force-stop（它杀了整个 launcher 进程，导致 Chrome 等正在运行的 App 闪退）
        // 改为：只检测 display 1 是否已有桌面，有就不重复启动

        // 1. reset state
        Shell.getShell().newJob().add("cmd device_state state reset").exec()
        Thread.sleep(300)

        // 2. 设置 display 1 渲染参数（内屏参数）
        Timber.i("Setting display 1 to inner-screen params")
        Shell.getShell().newJob().add("wm size -d 1 1080x2640").exec()
        Thread.sleep(100)
        Shell.getShell().newJob().add("wm density -d 1 480").exec()
        Thread.sleep(300)

        // 3. 启动 LauncherActivity 到 display 1（用 -S 0 不强制重启，只 bring to front）
        val launchCmd = """su 2000 -c "am start --display 1 -W -n $LAUNCHER_PKG/$activityClass" """.trimIndent()
        Timber.i("Launching: $launchCmd")

        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val r = Shell.getShell().newJob().add(launchCmd).to(out, err).exec()

        val stdout = out.joinToString("\n").trim()
        val stderr = err.joinToString("\n").trim()
        Timber.i("Result: exit=${r.code}\nstdout=$stdout\nstderr=$stderr")

        if (r.code == 0) {
            val hasError = stdout.contains("Error type", ignoreCase = true) ||
                          stdout.contains("does not exist", ignoreCase = true)
            if (hasError) {
                Result.Failure(stdout.take(300))
            } else {
                val modeName = when (activityClass) {
                    LAUNCHER_HOME_ACT -> "完整桌面"
                    LAUNCHER_RECENTS_ACT -> "最近任务"
                    else -> activityClass.substringAfterLast('.')
                }
                Result.Success("已切换：外屏显示 $modeName")
            }
        } else {
            Result.Failure(stderr.ifEmpty { stdout.ifEmpty { "exit=${r.code}" } })
        }
    }

    /**
     * 启用"最近任务"模式（已实测可用）。
     */
    suspend fun enableRecentsMode(): Result = enableConcurrentAndLaunch(LAUNCHER_RECENTS_ACT)

    /**
     * 启用"完整桌面"模式（用 resolve-activity 找到的正确类名）。
     */
    suspend fun enableHomeMode(): Result = enableConcurrentAndLaunch(LAUNCHER_HOME_ACT)

    /**
     * v0.14.0: 强制所有 App 可跨 display 启动。
     */
    suspend fun forceAllAppsResizable(): Result = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext Result.Failure("no root")
        Timber.i("Forcing all apps resizable...")
        listOf(
            "settings put global force_resizable_activities 1",
            "settings put global always_supports_multi_window 1",
            "settings put global activities_resizable_for_all 1"
        ).forEach { cmd ->
            Shell.getShell().newJob().add(cmd).exec()
        }
        Result.Success("done")
    }

    /**
     * 执行 `cmd device_state state <id>` 切换设备状态。
     *
     * @param stateId 目标状态 ID（4 = CONCURRENT，-1 = reset 恢复默认）
     */
    suspend fun setState(stateId: Int): Result = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) {
            return@withContext Result.Failure("未授权 root 权限")
        }

        val cmd = if (stateId == STATE_RESET) {
            "cmd device_state state reset"
        } else {
            "cmd device_state state $stateId"
        }

        Timber.i("Executing: $cmd")
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val r = Shell.getShell().newJob().add(cmd).to(out, err).exec()

        val stdout = out.joinToString("\n").trim()
        val stderr = err.joinToString("\n").trim()
        Timber.i("Result: exit=${r.code}, stdout=$stdout, stderr=$stderr")

        if (r.code == 0 && !stdout.contains("Error", ignoreCase = true)) {
            val msg = if (stateId == STATE_RESET) "已重置为系统默认状态"
                      else "已切换到状态 $stateId"
            Result.Success(msg)
        } else {
            Result.Failure(stderr.ifEmpty { stdout.ifEmpty { "exit=${r.code}" } })
        }
    }

    /**
     * 切换到 CONCURRENT 状态（外屏显示内屏完整内容）。
     * 这是核心功能。
     */
    suspend fun enableConcurrent(): Result = setState(STATE_CONCURRENT)

    /**
     * 重置为系统默认状态。
     */
    suspend fun reset(): Result = setState(STATE_RESET)

    /**
     * 查询当前设备状态 ID。
     * 返回状态数字字符串（如 "0" = CLOSED, "4" = CONCURRENT），或错误消息。
     */
    suspend fun getCurrentState(): String = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext "no root"

        val out = ArrayList<String>()
        val r = Shell.getShell().newJob().add("cmd device_state print-state").to(out, ArrayList()).exec()
        val stdout = out.joinToString("\n").trim()
        if (r.code == 0 && stdout.isNotEmpty()) {
            // 输出格式: "Current device state: 4" 或直接 "4"
            val match = Regex("""(\d+)""").find(stdout)
            match?.groupValues?.get(1) ?: stdout
        } else {
            "error: ${stdout.ifEmpty { "exit=${r.code}" }}"
        }
    }

    /**
     * 列出设备支持的所有状态。
     * Samsung Z Flip 5 通常支持 0/1/2/3/4。
     */
    suspend fun getSupportedStates(): String = withContext(Dispatchers.IO) {
        if (!isRootAvailable()) return@withContext "no root"

        val out = ArrayList<String>()
        val r = Shell.getShell().newJob().add("cmd device_state print-states").to(out, ArrayList()).exec()
        val stdout = out.joinToString("\n").trim()
        if (r.code == 0) stdout else "error: ${stdout.ifEmpty { "exit=${r.code}" }}"
    }

    /** 状态 ID 转可读名 */
    fun stateName(id: Int): String = when (id) {
        0 -> "CLOSED (合盖)"
        1 -> "HALF_OPENED (半开)"
        2 -> "OPENED (展开)"
        3 -> "REAR_DISPLAY (后屏)"
        4 -> "CONCURRENT (双屏同时显示) ← 目标"
        -1 -> "RESET (系统默认)"
        else -> "UNKNOWN ($id)"
    }
}
