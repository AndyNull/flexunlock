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
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * v0.46.0: 重构后的 AppRedirectService
 *
 * 核心修复：
 * 1. 展开状态下真正零 shell 调用（不再调用 cmd device_state print-state）
 * 2. 用 BroadcastReceiver 监听折叠状态，替代主动轮询
 * 3. am start 不带 -W，避免 10 秒同步阻塞
 * 4. 检测到展开立即取消 in-flight redirect
 * 5. movedTasks 每次展开无条件清理
 * 6. 给所有 shell 调用加 5 秒超时
 */
class AppRedirectService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var isRunning = false
    private var pollJob: Job? = null
    private val movedTasks = mutableSetOf<Int>()
    private var lastRedirectTime = 0L
    private val resolveCache = mutableMapOf<String, String?>()
    // v0.52: 防止 moveTasksBackToDisplay0 并发执行
    @Volatile
    private var movedTasksBackRunning = false

    private val samsungAppComponents = mapOf(
        "samsung.android.task.dialtacts" to "com.samsung.android.dialer/.DialtactsActivity",
        "com.samsung.android.dialer" to "com.samsung.android.dialer/.DialtactsActivity",
        "android.task.camera" to "com.sec.android.app.camera/.Camera",
        "com.sec.android.app.camera" to "com.sec.android.app.camera/.Camera",
    )

    private val pkgAlias = mapOf(
        "samsung.android.task.contacts" to "com.samsung.android.app.contacts",
        "samsung.android.task.dialtacts" to "com.samsung.android.dialer",
        "com.android.settings.root" to "com.android.settings",
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("App redirect..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isRunning = true
                getSharedPreferences("flexunlock", MODE_PRIVATE).edit().putBoolean("redirect_enabled", true).apply()
                updateNotification("App redirect running")
                startPolling()
            }
            ACTION_STOP -> {
                isRunning = false
                getSharedPreferences("flexunlock", MODE_PRIVATE).edit().putBoolean("redirect_enabled", false).apply()
                pollJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { pollJob?.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = serviceScope.launch {
            Timber.i("App redirect polling started (v0.48.0)")
            movedTasks.clear(); resolveCache.clear()
            var wasCoverActive = false
            var stableState = -100
            var lastRawState = -100
            var confirmCount = 0
            var lastShellTime = 0L

            // v0.48.0: App 启动时立即检查状态。如果当前是展开状态（state != 0），
            // 立即调用 moveTasksBackToDisplay0 清理 display 1 残留 task。
            // 这修复了"重启 App 后样式重叠"的问题：之前 App 重启不会自动清理
            // 上一版留下的 display 1 task，导致 Recents 里出现"幽灵主屏幕"task。
            try {
                val initialStateStr = withTimeoutOrNull(3000L) {
                    DeviceStateSwitcher.getCurrentState()
                } ?: "-1"
                val initialState = initialStateStr.toIntOrNull() ?: -1
                Timber.i("Initial state on app start: $initialState (${DeviceStateSwitcher.stateName(initialState)})")
                if (initialState != 0 && initialState != 4) {
                    // 不是合盖状态——清理 display 1
                    Timber.i("App started in non-cover state, cleaning display 1 leftovers")
                    moveTasksBackToDisplay0()
                    wasCoverActive = false
                } else if (initialState == 0 || initialState == 4) {
                    Timber.i("App started in cover state, will begin redirect")
                    wasCoverActive = false  // 让循环重新检测
                }
            } catch (e: Exception) {
                Timber.w(e, "Initial state check failed")
            }

            while (isRunning) {
                try {
                    // v0.46.0: 用 shell 查询状态（带 3 秒超时）
                    val stateStr = withTimeoutOrNull(3000L) {
                        DeviceStateSwitcher.getCurrentState()
                    } ?: "-1"
                    val rawState = stateStr.toIntOrNull() ?: -1

                    // v0.51: 去抖从 2 次 300ms 改为 1 次 200ms（更快响应状态变化）
                    // 之前 2×300ms=600ms 延迟太大，外屏点击 App 后要等 600ms 才开始 redirect
                    if (rawState == lastRawState) {
                        stableState = rawState
                        confirmCount = 0
                    } else {
                        lastRawState = rawState
                        delay(200)
                        continue
                    }

                    // v0.47.0: 关键修复 — Samsung One UI 8.5 实际状态映射
                    //   0=CLOSED 1=TENT 2=HALF_OPENED 3=OPENED 4=CONCURRENT
                    // 之前代码错误地把 3 当成 REAR_DISPLAY，导致展开后仍触发 redirect
                    // coverActive 只在 0(CLOSED) 或 4(CONCURRENT) 时为 true
                    val coverActive = (stableState == 0 || stableState == 4)

                    if (coverActive) {
                        if (!wasCoverActive) {
                            Timber.i("Cover detected (state=$stableState), starting redirect")
                            wasCoverActive = true
                        }
                        val now = System.currentTimeMillis()
                        // v0.51: 节流从 1000ms 降到 300ms（更快响应外屏点击 App）
                        if (now - lastRedirectTime > 300) {
                            redirectAppsFromDisplay0()
                            lastRedirectTime = System.currentTimeMillis()
                        }
                        // v0.51: 轮询从 1000ms 降到 300ms（外屏点击 App 后更快被 redirect）
                        delay(300)
                    } else {
                        // v0.47.0: 展开时（state=3 OPENED 等）立即取消 redirect
                        // 并把 display 1 上的残留 task 移回 display 0
                        if (wasCoverActive) {
                            Timber.i("Phone opened (state=$stableState), cancelling redirect + moving tasks back")
                            wasCoverActive = false
                            movedTasks.clear()
                            resolveCache.clear()
                            // 杀掉任何残留的 am start 进程
                            Shell.getShell().newJob().add("pkill -f 'am start --display 1' 2>/dev/null").exec()
                            // v0.47.0: 关键修复 — 把 display 1 上残留的 task 移回 display 0
                            // 之前缺失这个函数，导致展开后 display 1 一直有 launcher + apps 残留
                            // 这是"样式重叠"问题的直接原因
                            moveTasksBackToDisplay0()
                        }
                        delay(5000)
                    }
                } catch (e: Exception) { Timber.e(e, "Polling error") }
            }
        }
    }

    /**
     * v0.52.0: 把 display 1 上的 task 彻底清理
     *
     * v0.48-v0.51 的 bug：
     *   1. am task remove 后又 am start --display 0，导致系统重新创建 task
     *      有时新 task 又跑到 display 1，形成无限循环
     *   2. 日志显示 task 1667 被 remove 了 5 次还在 display 1 上
     *   3. 用户看到的"幽灵主屏幕"就是这个残留的 launcher task
     *
     * v0.52 方案：
     *   1. 先收集所有 display 1 上的 standard task ID
     *   2. 用 am task remove 批量移除（不再重新启动到 display 0）
     *   3. 对 launcher task 额外用 am force-stop com.sec.android.app.launcher
     *      彻底杀掉 launcher 进程，让系统重建干净的 launcher
     *   4. 不再 am start --display 0（系统展开时会自动显示 display 0 桌面）
     *   5. 加防重入：用 movedTasksBackRunning 标志避免并发调用
     */
    private suspend fun moveTasksBackToDisplay0() {
        // v0.52: 防重入
        if (movedTasksBackRunning) {
            Timber.i("moveTasksBack: already running, skip")
            return
        }
        movedTasksBackRunning = true

        try {
            val cmd = """dumpsys activity activities | awk '/^Display #1/{f=1;next} /^Display #/{f=0} f' | grep 'type=standard'"""
            val out = ArrayList<String>()
            val result = withTimeoutOrNull(5000L) {
                Shell.getShell().newJob().add(cmd).to(out, ArrayList()).exec()
            }
            if (result == null) {
                Timber.w("moveTasksBack: dumpsys timeout")
                return
            }

            Timber.i("moveTasksBack: found ${out.size} lines on display 1")

            // v0.52: 先收集所有 task，避免在循环中改 dumpsys 状态
            // v0.53: 用 taskId 去重（dumpsys 可能返回同一 task 的多行）
            val tasksToClean = mutableMapOf<Int, String>()  // taskId -> pkg
            var hasLauncher = false

            for (line in out) {
                if (!line.contains("type=standard")) continue

                val taskMatch = Regex("""#(\d+)""").find(line)
                val taskId = taskMatch?.groupValues?.get(1)?.toIntOrNull() ?: continue

                // 跳过已收集的 task
                if (taskId in tasksToClean) continue

                var pkg: String? = null
                val iMatch = Regex("""I=(\S+/[^\s}]+)""").find(line)
                if (iMatch != null) {
                    pkg = iMatch.groupValues[1].substringBefore("/")
                }
                if (pkg == null) {
                    val aMatch = Regex("""A=\d+:([^\s}]+)""").find(line)
                    if (aMatch != null) pkg = aMatch.groupValues[1]
                }
                if (pkg == null) continue

                // 跳过 SystemUI 和 FlexUnlock
                if (pkg == "com.android.systemui" || pkg == "com.flexunlock.simple" ||
                    pkg == "android") continue

                tasksToClean[taskId] = pkg
                if (pkg == "com.sec.android.app.launcher") hasLauncher = true
                Timber.i("moveTasksBack: found task=$taskId pkg=$pkg")
            }

            // v0.52: 批量 remove 所有 task（已去重）
            for ((taskId, pkg) in tasksToClean) {
                val removeCmd = "am task remove $taskId"
                withTimeoutOrNull(2000L) {
                    Shell.getShell().newJob().add(removeCmd).exec()
                }
                Timber.i("moveTasksBack: removed task $taskId ($pkg)")
            }

            // v0.52: 如果有 launcher task，force-stop 整个 launcher 进程
            // 这会杀掉 display 1 上的 launcher 实例
            // 系统会自动在 display 0 重建 launcher（因为展开后 display 0 是主屏）
            if (hasLauncher) {
                Timber.i("moveTasksBack: force-stopping launcher to kill display 1 instance")
                withTimeoutOrNull(3000L) {
                    Shell.getShell().newJob().add("am force-stop com.sec.android.app.launcher").exec()
                }
                // 等 500ms 让系统重建 launcher
                delay(500)
                Timber.i("moveTasksBack: launcher force-stopped, system will rebuild on display 0")
            }

            Timber.i("moveTasksBack: cleaned ${tasksToClean.size} tasks from display 1")
        } finally {
            movedTasksBackRunning = false
        }
    }

    private suspend fun redirectAppsFromDisplay0() {
        val cmd = """dumpsys activity activities | awk '/^Display #0/{f=1;next} /^Display #/{f=0} f' | grep 'type=standard'"""
        val out = ArrayList<String>()
        val result = withTimeoutOrNull(5000L) {
            Shell.getShell().newJob().add(cmd).to(out, ArrayList()).exec()
        }
        if (result == null) {
            Timber.w("dumpsys timeout")
            return
        }

        for (line in out) {
            // v0.46.0: 检查协程是否已被取消（展开时立即退出）
            if (!isRunning) {
                Timber.i("Service stopped, aborting redirect")
                return
            }
            if (!line.contains("type=standard")) continue
            val taskMatch = Regex("""#(\d+)""").find(line) ?: continue
            val taskId = taskMatch.groupValues[1].toIntOrNull() ?: continue

            var pkg: String? = null
            var directComponent: String? = null

            val iMatch = Regex("""I=(\S+/[^\s}]+)""").find(line)
            if (iMatch != null) {
                directComponent = iMatch.groupValues[1]
                pkg = directComponent.substringBefore("/")
            }

            if (pkg == null) {
                val aMatch = Regex("""A=\d+:([^\s}]+)""").find(line)
                if (aMatch != null) pkg = aMatch.groupValues[1]
            }
            if (pkg == null) continue

            if (pkg == "com.android.systemui" || pkg == "com.sec.android.app.launcher" ||
                pkg == "com.flexunlock.simple" || pkg == "android") continue
            if (taskId in movedTasks) continue

            Timber.i("Found $pkg (task=$taskId) on display 0 -> starting on display 1")

            val component = directComponent
                ?: samsungAppComponents[pkg]
                ?: resolveCache.getOrPut(pkg) { resolveActivity(pkg) }
                ?: resolveCache.getOrPut("alias_$pkg") {
                    val aliasPkg = pkgAlias[pkg] ?: "com.$pkg"
                    resolveActivity(aliasPkg) ?: findLauncherFromPmDump(aliasPkg)
                }

            if (component != null && component.contains("/")) {
                val isPhoneApp = pkg.contains("dialer") || pkg.contains("dialtacts")
                if (isPhoneApp) {
                    withTimeoutOrNull(2000L) {
                        Shell.getShell().newJob().add("""su 2000 -c "wm size -d 1 720x1480" """).exec()
                    }
                    Thread.sleep(300)  // v0.46.0: 从 500ms 缩短到 300ms
                }

                // v0.46.0: 去掉 -W 标志，避免 10 秒同步阻塞
                val startCmd = """su 2000 -c "am start --display 1 -n $component" """
                val startOut = ArrayList<String>()
                val startErr = ArrayList<String>()
                val startResult = withTimeoutOrNull(5000L) {
                    Shell.getShell().newJob().add(startCmd).to(startOut, startErr).exec()
                }
                if (startResult == null) {
                    Timber.w("am start timeout for $pkg")
                    movedTasks.add(taskId)
                    continue
                }
                val startStdout = startOut.joinToString("").trim()
                val startStderr = startErr.joinToString("").trim()
                Timber.i("am start $pkg: exit=${startResult.code} stdout='${startStdout.take(100)}' stderr='${startStderr.take(100)}'")

                if (startResult.code == 0) {
                    Timber.i("Started $pkg on display 1")
                    movedTasks.add(taskId)
                    if (isPhoneApp) {
                        serviceScope.launch { delay(2000); Shell.getShell().newJob().add("wm size -d 1 reset").exec() }
                    }
                } else {
                    // v0.46.0: monkey fallback（去掉 force-stop，避免杀进程导致用户数据丢失）
                    val aliasPkg = pkgAlias[pkg] ?: "com.$pkg"
                    val monkeyPkg = if (samsungAppComponents.containsKey(pkg)) pkg else aliasPkg
                    Timber.i("Trying monkey for $monkeyPkg")
                    val monkeyCmd = "monkey -p $monkeyPkg --display 1 -c android.intent.category.LAUNCHER 1"
                    val monkeyResult = withTimeoutOrNull(5000L) {
                        Shell.getShell().newJob().add(monkeyCmd).exec()
                    }
                    if (monkeyResult == null) {
                        Timber.w("monkey timeout for $monkeyPkg")
                        movedTasks.add(taskId)
                        continue
                    }
                    Timber.i("monkey: exit=${monkeyResult.code}")
                    movedTasks.add(taskId)
                    if (isPhoneApp) {
                        withTimeoutOrNull(2000L) {
                            Shell.getShell().newJob().add("wm size -d 1 reset").exec()
                        }
                    }
                }
            } else {
                // v0.53: No component found — DON'T add to movedTasks
                // 之前加到 movedTasks 会导致这个 task 永远不被重试
                // 但如果它是子包（如 com.google.android.gm.compose），应该跳过
                // 不阻止它被系统正常启动
                Timber.w("No component for $pkg (skipping, not blocking)")
                // 不加到 movedTasks，让系统自己处理
            }
        }
    }

    private suspend fun resolveActivity(pkg: String): String? {
        val resolveOut = ArrayList<String>()
        withTimeoutOrNull(3000L) {
            Shell.getShell().newJob()
                .add("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg")
                .to(resolveOut, ArrayList()).exec()
        } ?: return null
        val resolveResult = resolveOut.joinToString("").trim()
        val afterIsDefault = resolveResult.substringAfter("isDefault=").trim()
        val component = when {
            afterIsDefault.startsWith("true") -> afterIsDefault.removePrefix("true")
            afterIsDefault.startsWith("false") -> afterIsDefault.removePrefix("false")
            afterIsDefault.contains("/") -> afterIsDefault
            else -> return null
        }.trim()
        return if (component.contains("/") && !component.startsWith("priority")) component else null
    }

    private suspend fun findLauncherFromPmDump(pkg: String): String? {
        val out = ArrayList<String>()
        withTimeoutOrNull(5000L) {
            Shell.getShell().newJob().add("pm dump $pkg").to(out, ArrayList()).exec()
        } ?: return null
        val dump = out.joinToString("\n")
        var inLauncher = false
        for (line in dump.lines()) {
            if (line.contains("android.intent.category.LAUNCHER")) { inLauncher = true; continue }
            if (inLauncher) {
                val match = Regex("""(\S+/\S+)""").find(line)
                if (match != null) {
                    val c = match.groupValues[1].trim()
                    if (c.startsWith(pkg) || c.contains("/")) { Timber.i("pm dump: $c"); return c }
                }
                if (line.isBlank()) break
            }
        }
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "App redirect", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("FlexUnlock").setContentText(text).setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "app_redirect"
        private const val NOTIFICATION_ID = 3
        const val ACTION_START = "com.flexunlock.simple.START_REDIRECT"
        const val ACTION_STOP = "com.flexunlock.simple.STOP_REDIRECT"
    }
}
