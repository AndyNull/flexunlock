# FlexUnlock

FlexUnlock 是用于 Samsung Galaxy Z Flip5 外屏的 LSPosed 模块。

> 当前版本：`1.7.7.2`
>
> 当前适配：Samsung Galaxy Z Flip5 `SM-F7310`、Android 14、One UI 8.5 测试固件。

## 主要功能

- 在外屏使用三星桌面、应用抽屉和最近任务。
- 支持原始外屏桌面与完整 DeX 桌面切换。
- 支持原始快捷设置与完整快捷设置切换。
- 支持外屏应用启动、应用筛选和显示比例调整。
- 支持修改外屏分辨率和 DPI。
- 支持 0°、90°、180°、270°旋转。
- 支持返回、桌面和最近任务手势。
- 支持外屏通知、亮度、媒体和真实闪光灯手电筒。
- 支持锁屏相机和锁屏组件中的应用启动。
- 支持单独设置外屏锁屏自动息屏时间。
- 支持视频、导航等应用保持屏幕常亮。
- 支持完整模式相机布局切换。
- 支持停止和恢复三星系统更新。
- 内置街猫公益项目入口。
- 支持浅色、深色主题和应用内检查更新。

版本变化见 [CHANGELOG.md](CHANGELOG.md)。

## 安装要求

1. Samsung Galaxy Z Flip5 `SM-F7310`。
2. Android 14 或兼容固件。
3. 已安装并启用 Magisk、Zygisk 和 LSPosed。

## 安装方法

1. 从 [GitHub Releases](https://github.com/AndyNull/flexunlock/releases) 下载 APK。
2. 安装 APK，升级时可直接覆盖安装。
3. 在 LSPosed 中启用 FlexUnlock，并按模块提示设置作用域。
4. 完整重启手机。

## 基本使用

- 在“应用”中选择允许从外屏启动的应用，并设置显示比例。
- 在“磁贴”中选择外屏快捷设置布局。
- 在“控制”中切换桌面、快捷设置、分辨率、相机和息屏时间。
- 在“主题”中切换模块 App 的显示主题。
- 在“公益”中查看街猫项目或复制项目链接。

## 构建

需要 JDK 17、Android SDK 34 和 Gradle 8.9。

```powershell
& "C:\Users\andy\.gradle\wrapper\dists\gradle-8.9-bin\78qddjpeqn5v6yec3xb8kv9ca\gradle-8.9\bin\gradle.bat" `
  :cover-shell:testReleaseUnitTest `
  :cover-shell:lintVitalRelease `
  :cover-shell:assembleRelease `
  --no-daemon
```

APK 输出位置：

```text
cover-shell/build/outputs/apk/release/cover-shell-release.apk
```

## 常见问题

### 安装后没有效果

- 确认 LSPosed 已启用模块并设置正确作用域。
- 确认安装后完整重启过手机。
- 检查 LSPosed 日志是否包含 `FlexUnlock-SystemBridge` 和 `FlexUnlock-CoverShell`。

### 外屏旋转不生效

- 确认系统自动旋转已经开启。
- 收起快捷设置后再旋转设备并重新下拉。

### 外屏自动息屏异常

- 在模块“控制”页面重新选择息屏时间。
- 视频或导航仍会息屏时，确认应用自身允许保持屏幕常亮。

## 当前限制

- 模块依赖三星系统组件，系统更新后可能需要重新适配。
- 其他机型和系统版本尚未完整验证。
- 修改完整模式或 LSPosed 作用域后，建议完整重启手机。

## 发布内容

GitHub Release 只提供签名 APK：`FlexUnlock-1.7.7.2-release.apk`。

[前往 GitHub Releases](https://github.com/AndyNull/flexunlock/releases)
