# FlexUnlock

FlexUnlock 是面向 Samsung Galaxy Z Flip5 外屏的 LSPosed 模块。

> 当前版本：`1.8.20`
>
> 主要适配：Samsung Galaxy Z Flip5 、Android 14、One UI 8.5 测试固件。

## 主要功能

- 在外屏使用三星桌面、应用抽屉、任务栏和最近任务。
- 原始外屏桌面与完整 DeX 桌面切换。
- 原始快捷设置与完整快捷设置切换。
- 外屏应用允许列表、搜索、全局缩放和单应用显示配置。
- 外屏分辨率、DPI、旋转角度和应用布局控制。
- HDMI、DisplayPort 与 scrcpy 虚拟显示器的独立显示目标。
- 有线外屏分辨率与刷新率独立选择，选项来自实际支持的显示 mode。
- scrcpy 外接桌面、任务栏、应用菜单、最近任务、壁纸和导航按钮隔离。
- 隐藏异形区域：裁剪外屏显示与触摸区域，并同步圆角屏幕形状。
- 0°、90°、180°、270°旋转，以及系统旋转锁定同步。
- 外屏通知、亮度、媒体、真实闪光灯手电筒和锁屏组件。
- 外屏锁屏自动息屏时间独立设置；解锁后继续使用系统屏幕超时。
- 视频、导航等应用的屏幕常亮策略。
- Honeyboard 紧凑键盘及百分比调整。
- 停止和恢复三星系统更新。
- 关于页反馈与诊断日志：精简/全量采集、大小上限、分享和删除。
- 浅色、深色和跟随系统主题，以及应用内更新检查。
- 默认简体中文；主页语言卡片可切换英文，退出应用后保留语言选择。

## 安装要求

1. Samsung Galaxy Z Flip5 或 Z Flip6等
2. One UI 8.5 固件。
3. Magisk、Zygisk 和 LSPosed 已安装并启用。
4. 在 LSPosed 中启用 FlexUnlock，并配置正确作用域。

## 安装方法

1. 从 GitHub Releases 下载已签名 APK。
2. 安装 APK；升级时可直接覆盖安装。
3. 在 LSPosed 中启用模块和作用域。
4. 完整重启手机，使 `system_server`、SystemUI 和 Launcher Hook 生效。

## 基本使用

- “应用”：选择允许从外屏启动的应用，并设置显示比例。
- “磁贴”：选择原始或完整快捷设置布局。
- “控制”：切换桌面、DeX、分辨率、刷新率、旋转、异形区域和息屏时间。
- “外接显示”：选择 HDMI、DisplayPort 或 scrcpy 目标及独立 mode。
- “主题”：切换模块 App 的显示主题。
- “关于 / 反馈与诊断”：生成精简或全量日志并分享给维护者。

## 构建

需要 JDK 17、Android SDK 34 和 Gradle 8.x。仓库不提交 Gradle Wrapper，请使用本机 Gradle 发行版：

```powershell
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" `
  :cover-shell:testReleaseUnitTest `
  :cover-shell:lintVitalRelease `
  :cover-shell:assembleRelease `
  --no-daemon
```

APK 输出：`cover-shell/build/outputs/apk/release/cover-shell-release.apk`

## 常见问题

### 安装后没有效果

- 确认 LSPosed 已启用模块并设置正确作用域。
- 确认安装后已经完整重启手机。
- 检查日志是否包含 `FlexUnlock-SystemBridge` 和 `FlexUnlock-CoverShell`。

### 外屏自动息屏异常

- 在模块“控制”页面重新选择外屏锁屏时间。
- 锁屏时使用该独立设置，解锁后使用系统 `SCREEN_OFF_TIMEOUT`。
- scrcpy 的 `--stay-awake`、`svc power stayon true` 或其他常亮应用会影响测试结果。

### scrcpy 无法显示或操作

- 使用 `scrcpy --new-display=1920x1080` 创建虚拟显示器。
- 确认 scrcpy 连接后重新选择目标显示器。
- 多个 scrcpy 实例可能产生重复显示任务，建议保留一个实例进行验证。

## 当前限制

- 模块依赖三星系统组件，系统更新后可能需要重新适配。
- 其他机型和系统版本尚未完整验证。
- 第三方输入法的显示尺寸受输入法自身策略影响，系统键盘支持更完整。
- 修改完整模式、显示目标或 LSPosed 作用域后建议完整重启手机。

## 发布内容

GitHub Release 提供：

- `FlexUnlock-1.8.18-release.apk`：已签名 APK。
- `FlexUnlock-1.8.18-source.zip`：清理后的源代码包。

[GitHub Releases](https://github.com/AndyNull/flexunlock/releases)
