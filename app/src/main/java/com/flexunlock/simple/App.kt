package com.flexunlock.simple

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.flexunlock.simple.service.AppRedirectService
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        appScope.launch {
            try {
                Thread.sleep(500)

                // 清除旧版残留设置
                Shell.getShell().newJob().add("settings delete global force_resizable_activities").exec()
                Shell.getShell().newJob().add("settings delete global always_supports_multi_window").exec()
                Shell.getShell().newJob().add("settings delete global activities_resizable_for_all").exec()

                // 彻底禁用 AccessibilityService
                val a11yOut = ArrayList<String>()
                Shell.getShell().newJob().add("settings get secure enabled_accessibility_services")
                    .to(a11yOut, ArrayList()).exec()
                val currentA11y = a11yOut.joinToString("").trim()
                if (currentA11y.contains("com.flexunlock.simple")) {
                    val cleaned = currentA11y.split(":")
                        .filter { it.isNotEmpty() && it != "null" && !it.contains("com.flexunlock.simple") }
                        .joinToString(":")
                    if (cleaned.isEmpty()) {
                        Shell.getShell().newJob().add("settings put secure enabled_accessibility_services null").exec()
                        Shell.getShell().newJob().add("settings put secure accessibility_enabled 0").exec()
                    } else {
                        Shell.getShell().newJob().add("settings put secure enabled_accessibility_services '$cleaned'").exec()
                    }
                    Timber.i("Disabled AccessibilityService")
                }

                // 启动 App 重定向服务
                val intent = Intent(this@App, AppRedirectService::class.java).apply {
                    action = AppRedirectService.ACTION_START
                }
                ContextCompat.startForegroundService(this@App, intent)
                getSharedPreferences("flexunlock", MODE_PRIVATE)
                    .edit().putBoolean("redirect_enabled", true).apply()

                // v0.50.0: 音量键监听（修复 v0.48/v0.49 的 shell 阻塞问题）
                startVolumeKeyListener()

                Timber.i("Features auto-enabled (v0.50.0)")
            } catch (e: Exception) {
                Timber.e(e, "Auto-enable failed")
            }
        }
    }

    /**
     * v0.50.0: 音量键双击监听
     *
     * 历史方案问题：
     * - v0.46: nohup 脚本被 OOM killer 杀 + 500ms 轮询阻塞共享 shell → 命中率 18%
     * - v0.48: getevent exec() 永久持有共享 shell → 状态检查/AppRedirectService 全部阻塞
     * - v0.49: Shell.Builder.build() 创建第二个 root shell → "Shell check timeout" 失败
     *
     * v0.50 方案：
     * 1. 用 setsid + disown 启动 getevent 监听脚本（比 nohup 更抗 OOM kill）
     * 2. 脚本检测到双击时，写 "UP" 或 "DOWN" 到 trigger 文件
     * 3. 主 App 每 1000ms 轮询 trigger 文件（比 v0.46 的 500ms 慢，减少 shell 争用）
     * 4. 双击窗口 600ms（比 v0.46 的 400ms 宽容）
     * 5. 轮询用 Shell.getShell()，每次只占 shell 几十毫秒，不阻塞 AppRedirectService
     *
     * 关键改进 vs v0.46：
     * - setsid 替代 nohup（创建新会话，不受父进程退出影响，更抗 OOM）
     * - 轮询间隔 500ms → 1000ms（减少 50% shell 调用）
     * - 双击窗口 400ms → 600ms（命中率提升）
     * - 脚本写 "UP"/"DOWN" 区分按键（v0.46 只写空文件）
     */
    private fun startVolumeKeyListener() {
        appScope.launch {
            try {
                // 杀掉旧的监听脚本
                Shell.getShell().newJob().add("pkill -f flexunlock_vol.sh 2>/dev/null").exec()
                delay(200)

                // v0.51: 不再找特定 device — Z Flip 5 的 VOL_UP 和 VOL_DOWN 在不同 device 上
                // (VOL_UP=deviceId 4, VOL_DOWN=deviceId 2)
                // 直接用 getevent -ql 不带 device 参数，监听所有设备
                Timber.i("Volume key listener: monitoring ALL devices (v0.51.0)")

                // 双击窗口（毫秒）
                val DOUBLE_CLICK_MS = 800

                // v0.54: 彻底简化 — 用最基础的 shell，不依赖 awk/systime
                // v0.53 失败原因：busybox awk 不支持 systime()
                //
                // v0.54 方案：用 while read + 文件存储状态
                // 文件 /data/local/tmp/flexunlock_vol_state 存 "UP_TS DOWN_TS"
                // 每次按键读文件、更新文件（文件 IO 跨子 shell 持久）
                // 时间用 cat /proc/uptime（毫秒级，Android 都支持）
                val script = """cat > /data/local/tmp/flexunlock_vol.sh << 'VOLEOF'
#!/system/bin/sh
TRIGGER=/data/local/tmp/flexunlock_vol_trigger
STATE=/data/local/tmp/flexunlock_vol_state
rm -f ${'$'}TRIGGER
echo "0 0" > ${'$'}STATE
getevent -ql 2>/dev/null | while IFS= read -r line; do
    case "${'$'}line" in
        *KEY_VOLUMEUP*DOWN*)
            # 用 /proc/uptime 获取时间（秒.毫秒），转成毫秒整数
            NOW=${'$'}(cat /proc/uptime | awk '{print int($1*1000)}')
            read VUP VDN < ${'$'}STATE
            DIFF=$(( NOW - VUP ))
            if [ "${'$'}DIFF" -lt $DOUBLE_CLICK_MS ] && [ "${'$'}VUP" -gt 0 ]; then
                echo "UP" > ${'$'}TRIGGER
                echo "0 0" > ${'$'}STATE
            else
                echo "${'$'}NOW ${'$'}VDN" > ${'$'}STATE
            fi
            ;;
        *KEY_VOLUMEDOWN*DOWN*)
            NOW=${'$'}(cat /proc/uptime | awk '{print int($1*1000)}')
            read VUP VDN < ${'$'}STATE
            DIFF=$(( NOW - VDN ))
            if [ "${'$'}DIFF" -lt $DOUBLE_CLICK_MS ] && [ "${'$'}VDN" -gt 0 ]; then
                echo "DOWN" > ${'$'}TRIGGER
                echo "0 0" > ${'$'}STATE
            else
                echo "${'$'}VUP ${'$'}NOW" > ${'$'}STATE
            fi
            ;;
    esac
done
VOLEOF
chmod 755 /data/local/tmp/flexunlock_vol.sh""".trimIndent()

                Shell.getShell().newJob().add(script).exec()

                // v0.50: 用 setsid 启动，创建新会话，不受 App 进程影响
                Shell.getShell().newJob().add("setsid sh /data/local/tmp/flexunlock_vol.sh < /dev/null > /dev/null 2>&1 &").exec()
                Timber.i("Volume key listener started (v0.54.0, file state, double-click window=${DOUBLE_CLICK_MS}ms)")

                // 轮询 trigger 文件
                // v0.50: 每 1000ms 轮询一次（v0.46 是 500ms）
                while (true) {
                    try {
                        val checkOut = ArrayList<String>()
                        Shell.getShell().newJob().add("cat /data/local/tmp/flexunlock_vol_trigger 2>/dev/null")
                            .to(checkOut, ArrayList()).exec()
                        val triggerContent = checkOut.joinToString("").trim()

                        if (triggerContent == "UP" || triggerContent == "DOWN") {
                            // 立即删除 trigger 文件
                            Shell.getShell().newJob().add("rm -f /data/local/tmp/flexunlock_vol_trigger").exec()

                            val keyName = if (triggerContent == "UP") "VOL_UP" else "VOL_DOWN"
                            Timber.i("Volume double-click detected: $keyName")

                            // 状态检查 + 触发
                            handleVolumeDouble(keyName)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Volume poll error")
                    }
                    delay(300)  // v0.52: 1000ms → 300ms（更快响应双击）
                }
            } catch (e: Exception) {
                Timber.e(e, "Volume key listener init failed")
            }
        }
    }

    /**
     * 处理双击事件：状态检查 + 触发 enableHomeMode
     */
    private fun handleVolumeDouble(keyName: String) {
        appScope.launch {
            try {
                val stateOut = ArrayList<String>()
                Shell.getShell().newJob().add("cmd device_state print-state")
                    .to(stateOut, ArrayList()).exec()
                val stateStr = stateOut.joinToString("").trim()
                val stateMatch = Regex("""(\d+)""").find(stateStr)
                val stateId = stateMatch?.groupValues?.get(1)?.toIntOrNull() ?: -1

                if (stateId == 0) {
                    Timber.i("State=$stateId (CLOSED), triggering enableHomeMode ($keyName)")
                    DeviceStateSwitcher.enableHomeMode()
                } else {
                    Timber.w("State=$stateId (not CLOSED), ignoring $keyName double-click")
                }
            } catch (e: Exception) {
                Timber.e(e, "State check failed for $keyName")
            }
        }
    }
}
