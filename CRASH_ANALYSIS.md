# FlexUnlock 闪退根因深度分析

## 一、核心问题：展开状态下 AppRedirectService 实际执行了什么？

源代码注释声称 **"v0.45.2: 展开时完全不执行任何 shell 命令"**，但**这是一个错误的自我安慰**。实际运行时，展开状态下至少有 **3 个独立的 shell 调用源** 持续在执行 root 命令。

### 1.1 AppRedirectService 自身的状态轮询

`AppRedirectService.startPolling()` 中的循环：

```kotlin
while (isRunning) {
    val stateStr = DeviceStateSwitcher.getCurrentState()  // ← 这里！
    val rawState = stateStr.toIntOrNull() ?: -1
    // ... 状态去抖 ...
    if (coverActive) {
        // 合盖分支：redirectAppsFromDisplay0()
    } else {
        // v0.45.2: 展开时完全不执行任何 shell 命令  ← 注释撒谎
        delay(2000)
    }
}
```

`DeviceStateSwitcher.getCurrentState()` 的实现：

```kotlin
suspend fun getCurrentState(): String = withContext(Dispatchers.IO) {
    val out = ArrayList<String>()
    val r = Shell.getShell().newJob()
        .add("cmd device_state print-state")   // ← 这就是 shell 命令
        .to(out, ArrayList()).exec()
    // ...
}
```

**结论：展开状态下，每 2 秒会执行一次 `cmd device_state print-state`，这本身就是一次 root shell 调用。** 注释中"完全不执行任何 shell 命令"的说法是错误的。

### 1.2 App.startVolumeKeyListener() 的 200ms 轮询

`App.kt` 中有一个**独立的无限循环**，与 AppRedirectService 并行运行：

```kotlin
private fun startVolumeKeyListener() {
    CoroutineScope(Dispatchers.IO).launch {
        // ... 初始化 ...
        while (true) {                                    // ← 无限循环
            val checkOut = ArrayList<String>()
            Shell.getShell().newJob()
                .add("[ -f /data/local/tmp/flexunlock_vol_trigger ] && echo yes || echo no")
                .to(checkOut, ArrayList()).exec()         // ← 每 200ms 一次 root shell
            if (checkOut.joinToString("").trim() == "yes") {
                Shell.getShell().newJob().add("rm -f /data/local/tmp/flexunlock_vol_trigger").exec()
                DeviceStateSwitcher.enableHomeMode()      // ← 触发完整切换流程！
            }
            Thread.sleep(200)                              // ← 200ms 间隔
        }
    }
}
```

**关键风险**：当用户在展开状态下双击音量键时，`enableHomeMode()` 会执行以下命令链：

```kotlin
Shell.getShell().newJob().add("cmd device_state state reset").exec()
Thread.sleep(300)
Shell.getShell().newJob().add("wm size -d 1 1080x2640").exec()   // ← 修改外屏分辨率
Shell.getShell().newJob().add("wm density -d 1 480").exec()      // ← 修改外屏密度
Shell.getShell().newJob().add("am start --display 1 -W -n com.sec.android.app.launcher/.activities.LauncherActivity").exec()
```

**在展开状态下，display 1（外屏）已被系统关闭。对已关闭的 display 执行 `wm size -d 1` 和 `am start --display 1` 极有可能触发 SystemUI 异常或 WindowManager 状态混乱，这正是用户报告的"展开后闪退"的核心成因。**

### 1.3 状态去抖延迟期间的"幽灵重定向"

`AppRedirectService` 使用 3 次确认机制识别状态变化：

```kotlin
if (rawState == lastRawState) {
    confirmCount++
    if (confirmCount >= 3) {
        stableState = rawState  // ← 900ms 后才生效
        confirmCount = 0
    }
}
```

**3 × 300ms = 900ms 的延迟窗口**。当用户从合盖切换到展开时：

- 物理状态立即变为 OPENED
- 但代码在 900ms 内仍认为状态是 CLOSED
- 在这 900ms 内，`redirectAppsFromDisplay0()` 会继续每 300ms 执行一次
- 这意味着：用户刚展开手机的瞬间，App 仍会向正在被系统关闭的 display 1 启动 Activity

### 1.4 am start --display 1 -W 的同步阻塞陷阱

```kotlin
val startCmd = """su 2000 -c "am start --display 1 -W -n $component" """
```

`-W` 标志让 `am start` **同步等待 Activity 启动完成**（默认超时 10 秒）。当用户在合盖→展开的过渡期触发此命令：

| 时间点 | 系统状态 | am start -W 行为 |
|--------|----------|------------------|
| T+0ms | 合盖，display 1 ON | 正常启动 |
| T+200ms | 用户开始展开 | display 1 正在被系统关闭 |
| T+400ms | 展开中 | Activity 启动卡住，等待 display |
| T+1000ms | 展开完成 | 超时或返回错误，Activity 已分配到已死的 display |
| T+10000ms | 已展开 | am start -W 最终超时返回 |

**期间 ActivityManager 可能因尝试将 Activity 绑定到不存在的 display 而抛出 `ActivityNotFoundException` 或 `IllegalArgumentException`，进而触发 ANR 或 SystemUI 重启。**

---

## 二、次要但累积的闪退诱因

### 2.1 `wm size -d 1 720x1480` 对电话 App 的副作用

```kotlin
val isPhoneApp = pkg.contains("dialer") || pkg.contains("dialtacts")
if (isPhoneApp) {
    Shell.getShell().newJob().add("""su 2000 -c "wm size -d 1 720x1480" """).exec()
    Thread.sleep(500)
}
// ... am start ...
if (isPhoneApp) {
    serviceScope.launch { delay(3000); Shell.getShell().newJob().add("wm size -d 1 reset").exec() }
}
```

- 修改 display 1 分辨率会触发该 display 上**所有** Activity 的 configuration change
- 如果用户在 3 秒窗口内展开手机，`wm size -d 1 reset` 会作用于已关闭的 display，可能失败或污染下次合盖时的初始分辨率

### 2.2 force-stop 在 fallback 路径中的破坏性

```kotlin
} else {
    // monkey fallback
    val aliasPkg = pkgAlias[pkg] ?: "com.$pkg"
    // ... monkey ...
    if (monkeyResult.code == 0) { ... } else {
        Timber.w("All methods failed for $pkg")
        movedTasks.add(taskId)
    }
}
```

**注意**：源代码注释提到 "am start failed, trying force-stop + restart"，但实际代码在 fallback 路径中**没有真正执行 force-stop**。但日志显示 v0.20.0 版本确实执行了 force-stop。如果当前版本仍残留此逻辑（或 v0.36 反编译版本包含），那么当用户正在使用某 App 时，`am force-stop <pkg>` 会**杀死整个 App 进程**，造成用户当前数据丢失和闪退体验。

### 2.3 movedTasks / resolveCache 的跨折叠周期污染

```kotlin
} else {
    // 展开分支
    if (wasCoverActive) {
        wasCoverActive = false
        movedTasks.clear()
        resolveCache.clear()
    }
    delay(2000)
}
```

- `movedTasks` 只在 `wasCoverActive == true` 时清理
- 如果状态在 0 → 1（半开）→ 0（合盖）之间抖动，`wasCoverActive` 始终为 true，`movedTasks` 不会被清理
- 但 task ID 在折叠周期之间会被系统重新分配，导致：
  - 新 task 复用旧 ID 时被错误地认为"已移动"而跳过
  - 旧 task ID 永远停留在 movedTasks 中，造成内存缓慢增长

### 2.4 Shell.getShell() 的全局阻塞风险

```kotlin
Shell.setDefaultBuilder(
    Shell.Builder.create()
        .setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER)
        .setTimeout(15)
)
```

- libsu 默认使用**单一全局 root shell**
- AppRedirectService、VolumeKeyListener、MainActivity 三个调用点共享同一 shell
- 任一调用阻塞（如 `am start -W` 等待 10s 超时），所有其他 shell 调用都会被排队
- 这会导致状态轮询假死，错过关键的状态变化

### 2.5 AccessibilityService 的隐形注入

虽然 `App.kt` 在启动时主动禁用了 AccessibilityService：

```kotlin
if (currentA11y.contains("com.flexunlock.simple")) {
    // ... remove from enabled list ...
}
```

但 `AndroidManifest.xml` 中仍然注册了 `SideKeyAccessibilityService`。如果用户手动在系统设置中重新启用，AccessibilityService 会立即注入所有 App 进程（即使配置了 `eventTypes=""`），导致第三方 App 闪退。**这是 v0.45.0 之前版本的核心闪退原因。**

---

## 三、根因总结

| 严重程度 | 根因 | 影响范围 | 触发条件 |
|---------|------|---------|---------|
| **致命** | VolumeKeyListener 在展开状态下触发 `wm size -d 1` + `am start --display 1` | SystemUI 异常、外屏状态混乱 | 展开时双击音量键 |
| **致命** | am start --display 1 -W 同步阻塞 10 秒 | ANR、ActivityManager 异常 | 折叠→展开过渡期触发重定向 |
| **严重** | 状态去抖 900ms 延迟期间继续 redirect | 向已关闭 display 启动 Activity | 任何折叠/展开切换 |
| **严重** | AppRedirectService 注释撒谎，仍执行 shell | 持续占用 root shell，干扰系统 | 展开状态下持续 |
| **中等** | movedTasks 跨周期污染 | 漏重定向或重复重定向 | 多次折叠/展开 |
| **中等** | wm size -d 1 reset 时机错误 | 外屏分辨率错乱 | 电话 App 重定向期间展开 |
| **轻微** | Shell.getShell() 全局阻塞 | 响应延迟 | 高频 shell 调用 |
| **轻微** | AccessibilityService 残留注册 | 第三方 App 闪退（仅当用户手动启用） | 用户手动启用 a11y |

---

## 四、修复方案 (v0.46.0)

### 4.1 AppRedirectService 重构

1. **展开状态下真正零 shell 调用**：用 `DeviceStateMonitor`（基于 BroadcastReceiver 监听 `Intent.ACTION_SCREEN_ON` / `FOLD_STATE`）替代主动轮询
2. **去除 -W 标志**：改为 `am start --display 1 -n`（不等待），避免 10 秒阻塞
3. **去除状态去抖**：直接信任 `cmd device_state print-state` 的返回值（系统已有内部去抖）
4. **展开立即取消**：检测到展开状态时立即 `pollJob?.cancel()`，终止任何 in-flight 的 am start

### 4.2 VolumeKeyListener 重构

1. **状态门控**：在触发 `enableHomeMode()` 前先检查 `getCurrentState()`，仅在合盖状态（state == 0 或 3）下才执行
2. **延长轮询间隔**：从 200ms 改为 500ms，减少 60% shell 调用
3. **失败回退**：如果 `enableHomeMode()` 失败，自动 reset 状态避免卡死

### 4.3 彻底移除 AccessibilityService

1. 从 `AndroidManifest.xml` 中删除 `<service android:name=".accessibility.SideKeyAccessibilityService">` 整段
2. 删除 `SideKeyAccessibilityService.kt` 文件
3. 删除 `accessibility_service_config.xml`
4. 防止用户误启用导致 App 注入

### 4.4 movedTasks 周期性清理

每次展开时**无条件**清理 `movedTasks` 和 `resolveCache`，不再依赖 `wasCoverActive` 标志。
