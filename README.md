# FlexUnlock Cover DeX

FlexUnlock Cover DeX 是面向三星折叠屏外屏的 LSPosed / Xposed 模块，目标是在外屏运行原生桌面和普通 Android App，并补齐锁屏、旋转、最近任务、快捷设置及会话恢复行为。

## 当前版本

`1.4.5`

安装包请前往 [Releases](https://github.com/AndyNull/flexunlock/releases) 下载。

## 主要功能

- 在外屏启动和恢复原生 DeX 桌面及普通 App。
- 可配置外屏 App 启动允许列表，支持搜索、系统/用户应用筛选。
- 支持外屏四方向旋转、最近任务缩略图和底边手势方向同步。
- 支持外屏快捷设置布局与磁贴编辑。
- 支持外屏独立锁屏、息屏超时和亮屏恢复。
- 在锁屏解锁后恢复原有 App task，避免重复创建 App 实例。
- 提供模块控制中心、主题切换和 GitHub Release 更新检查。

## 使用要求

- 已安装并可正常使用 LSPosed 或兼容的 Xposed 框架。
- 三星折叠屏及对应的外屏系统组件。
- 当前版本已在 Samsung Galaxy Z Flip5（SM-F7310）外屏验证；其他型号和 One UI 版本可能需要额外适配。

## 安装

1. 从 [Releases](https://github.com/AndyNull/flexunlock/releases) 下载 `FlexUnlock-Cover-DeX-1.4.5-debug-signed.apk`。
2. 安装 APK，在 LSPosed 中启用模块并采用模块声明的作用域。
3. 重启设备，使 `system_server`、SystemUI、Launcher 和相关三星组件加载新版本。
4. 打开 FlexUnlock Cover DeX 控制页，配置外屏允许列表、磁贴布局和显示主题。

升级前建议保留现有可用版本。系统或 One UI 更新后，如遇异常，请先禁用模块确认是否为兼容性问题。

## 更新日志

详细变更见 [update.md](update.md)。

## 说明

本仓库仅用于模块介绍、更新日志和编译产物发布，不公开模块源代码。