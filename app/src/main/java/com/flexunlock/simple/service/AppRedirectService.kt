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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class AppRedirectService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var isRunning = false
    private var pollJob: kotlinx.coroutines.Job? = null
    private val movedTasks = mutableSetOf<Int>()
    private var lastRedirectTime = 0L
    private val resolveCache = mutableMapOf<String, String?>()

    private val samsungAppComponents = mapOf(
        "samsung.android.task.dialtacts" to "com.samsung.android.dialer/.DialtactsActivity",
        "com.samsung.android.dialer" to "com.samsung.android.dialer/.DialtactsActivity",
        "android.task.camera" to "com.sec.android.app.camera/.Camera",
        "com.sec.android.app.camera" to "com.sec.android.app.camera/.Camera",
    )

    // v0.40.0: 联系人去掉硬编码（Activity 名可能不对），走 resolveActivity
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
            Timber.i("App redirect polling started (v0.37.0)")
            movedTasks.clear(); resolveCache.clear()
            var wasCoverActive = false
            while (isRunning) {
                try {
                    val stateStr = DeviceStateSwitcher.getCurrentState()
                    val state = stateStr.toIntOrNull() ?: -1
                    val coverActive = (state == 0 || state == 3)
                    if (coverActive) {
                        wasCoverActive = true
                        val now = System.currentTimeMillis()
                        // v0.40.0: 冷却期缩短到 300ms（更跟手）
                        if (now - lastRedirectTime > 300) redirectAppsFromDisplay0()
                    } else {
                        if (wasCoverActive) {
                            Timber.i("Phone opened, cleaning cover")
                            Shell.getShell().newJob().add("cmd device_state state reset").exec()
                            Thread.sleep(300)
                            Shell.getShell().newJob().add("am force-stop com.sec.android.app.launcher").exec()
                            wasCoverActive = false
                        }
                    }
                } catch (e: Exception) { Timber.e(e, "Polling error") }
                delay(300)  // v0.40.0: 300ms 轮询（更跟手）
            }
        }
    }

    private suspend fun redirectAppsFromDisplay0() {
        val cmd = """dumpsys activity activities | awk '/^Display #0/{f=1;next} /^Display #/{f=0} f' | grep 'type=standard'"""
        val out = ArrayList<String>()
        Shell.getShell().newJob().add(cmd).to(out, ArrayList()).exec()

        for (line in out) {
            if (!line.contains("type=standard")) continue
            val taskMatch = Regex("""#(\d+)""").find(line) ?: continue
            val taskId = taskMatch.groupValues[1].toIntOrNull() ?: continue

            // v0.38.0: 同时匹配 A=UID:pkg 和 I=pkg/act 两种格式
            // I= 格式直接包含完整组件名，可以直接用！
            var pkg: String? = null
            var directComponent: String? = null  // v0.38.0: I= 格式直接提取的组件名

            // 先试 I=pkg/act（直接包含组件名，最可靠）
            val iMatch = Regex("""I=(\S+/[^\s}]+)""").find(line)
            if (iMatch != null) {
                directComponent = iMatch.groupValues[1]
                pkg = directComponent.substringBefore("/")
                Timber.i("I= format: pkg=$pkg, component=$directComponent")
            }

            // 再试 A=UID:pkg
            if (pkg == null) {
                val aMatch = Regex("""A=\d+:([^\s}]+)""").find(line)
                if (aMatch != null) pkg = aMatch.groupValues[1]
            }
            if (pkg == null) continue

            if (pkg == "com.android.systemui" || pkg == "com.sec.android.app.launcher" ||
                pkg == "com.flexunlock.simple" || pkg == "android") continue
            if (taskId in movedTasks) continue

            Timber.i("Found $pkg (task=$taskId) on display 0 -> starting on display 1")

            // v0.38.0: 优先用 I= 格式直接提取的组件名（最可靠）
            // 其次用映射表 → resolveActivity → 别名 → pm dump
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
                    Shell.getShell().newJob().add("""su 2000 -c "wm size -d 1 720x1480" """).exec()
                    Thread.sleep(500)
                }

                val startCmd = """su 2000 -c "am start --display 1 -W -n $component" """
                val startOut = ArrayList<String>()
                val startErr = ArrayList<String>()
                val startResult = Shell.getShell().newJob().add(startCmd).to(startOut, startErr).exec()
                val startStdout = startOut.joinToString("").trim()
                val startStderr = startErr.joinToString("").trim()
                Timber.i("am start $pkg: exit=${startResult.code} stdout='${startStdout.take(100)}' stderr='${startStderr.take(100)}'")

                if (startResult.code == 0) {
                    Timber.i("Started $pkg on display 1")
                    movedTasks.add(taskId)
                    lastRedirectTime = System.currentTimeMillis()
                    if (isPhoneApp) {
                        serviceScope.launch { delay(3000); Shell.getShell().newJob().add("wm size -d 1 reset").exec() }
                    }
                } else {
                    // monkey fallback
                    val aliasPkg = pkgAlias[pkg] ?: "com.$pkg"
                    val monkeyPkg = if (samsungAppComponents.containsKey(pkg)) pkg else aliasPkg
                    Timber.i("Trying monkey for $monkeyPkg")
                    val monkeyCmd = "monkey -p $monkeyPkg --display 1 -c android.intent.category.LAUNCHER 1"
                    val monkeyResult = Shell.getShell().newJob().add(monkeyCmd).exec()
                    Timber.i("monkey: exit=${monkeyResult.code}")
                    if (monkeyResult.code == 0) {
                        Timber.i("Started $pkg via monkey")
                        movedTasks.add(taskId)
                        lastRedirectTime = System.currentTimeMillis()
                    } else {
                        Timber.w("All methods failed for $pkg")
                        movedTasks.add(taskId)
                    }
                    if (isPhoneApp) Shell.getShell().newJob().add("wm size -d 1 reset").exec()
                }
            } else {
                Timber.w("No component for $pkg")
                movedTasks.add(taskId)
            }
        }
    }

    private suspend fun resolveActivity(pkg: String): String? {
        val resolveOut = ArrayList<String>()
        Shell.getShell().newJob()
            .add("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg")
            .to(resolveOut, ArrayList()).exec()
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
        Shell.getShell().newJob().add("pm dump $pkg").to(out, ArrayList()).exec()
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
