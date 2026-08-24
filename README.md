# FlexUnlock

FlexUnlock 是面向 Samsung Galaxy Z Flip5（`SM-F7310`）外屏的 LSPosed 模块。它复用三星原生 `SecondaryLauncher`、SystemUI、通知、Quick Settings 和 Recents，在不替换系统核心界面的前提下补齐外屏应用启动、旋转、手势、锁屏与息屏能力。

> 当前版本：`1.7.1`
> 当前适配对象：Samsung Galaxy Z Flip5 `SM-F7310`、Android 14 及 One UI 8.5 测试固件。项目依赖三星私有实现，其他机型或系统版本需要重新验证。

## 主要功能

- 外屏使用三星 `SecondaryLauncher` 作为 Home。
- 可在稳定外屏桌面与完整外屏 DeX 模式之间切换。
- 外屏控制支持读取并修改 display 1 分辨率与 DPI。
- 应用管理支持全局显示比例和单应用显示参数，单应用设置优先。
- 应用抽屉固定 `6 × 4`，每页最多 24 项。
- 外屏 App、Good Lock、Launcher 和 Recents 支持四方向传感器旋转。
- App 启动首帧直接使用当前物理角度，避免先显示 0°再旋转。
- QS 在物理旋转后同步 display 1 的 Configuration、Insets 和窗口布局。
- 外屏左下异形区提供 RECENT / HOME / BACK 三分手势，并随实际底边旋转。
- Recents snapshot、thumbnail 和 live surface 支持四方向及动态旋转。
- 完整 DeX 最近任务支持卡片和纵向标题列表两种显示方式。
- 完整 DeX 下普通应用统一绕过 CoverLauncher 的“展开手机”限制并路由到外屏。
- 解锁态读取系统 `screen_off_timeout`，到期进入 Keyguard 并独立关闭外屏。
- 锁屏页面使用独立 10 秒息屏策略。
- 锁屏状态下，system-server 会在 display 1 的 Activity 启动许可边界拒绝普通外部 App，必须先完成解锁。
- 电源键锁屏提交后立即进入 fail-closed 状态，快速再次唤醒只恢复 Keyguard，不恢复 SecondaryLauncher。
- 解锁后仍按允许列表和原有外屏会话策略启动 App。
- 更新检查展示 GitHub Release 的版本、发布时间和更新日志，并保留完整 Releases 页面入口。
- 模块 App 使用 adaptive、round 和 themed icon，适配 Samsung Launcher 图标蒙版。
- 输入法使用系统原生尺寸调节，不再注入会锁死二次调整的键盘尺寸 Hook。

完整实现边界见 [IMPLEMENTATION.md](IMPLEMENTATION.md)，版本变化见 [CHANGELOG.md](CHANGELOG.md)。

## 安装要求

1. Samsung Galaxy Z Flip5 `SM-F7310`。
2. 已安装并启用 Magisk、Zygisk 和 LSPosed。
3. Android API 31 及以上；当前发布在 API 34 固件验证。
4. 安装前建议备份现有模块 APK 和 LSPosed 作用域。

## 安装步骤

1. 从 [GitHub Releases](https://github.com/AndyNull/flexunlock/releases) 下载对应版本 APK。
2. 安装 APK；升级时可直接覆盖安装。
3. 在 LSPosed 中启用 `FlexUnlock`。
4. 按模块页面建议配置作用域，至少确保系统框架、SystemUI 和三星 Launcher 可以加载模块。
5. 完整重启手机。仅重启 Launcher 或 SystemUI 不足以加载 `system_server` 中的策略。
6. 合盖后点亮外屏，确认三星外屏桌面正常出现。

## 使用指南

### 外屏桌面和应用

- 在外屏桌面使用应用抽屉浏览应用。
- 抽屉每页最多显示 24 项，多余应用自动分页。
- 普通 App 与 Good Lock App 从外屏启动后会使用当前物理方向。
- 返回桌面时由系统 Home transition 接管，不需要手动迁移任务。

### 底边手势

实际物理底边划分为三个区域：

| 区域 | 动作 |
| --- | --- |
| 左侧三分之一 | Recents |
| 中间三分之一 | Home |
| 右侧三分之一 | Back |

手机旋转后区域会跟随新的物理底边，不以固定屏幕坐标判断。

### 通知与 Quick Settings

- 从状态栏区域下拉进入三星原生通知或 QS。
- 旋转设备后，QS 会根据 display 1 的最新 Configuration 重新布局。
- 通知与 QS 保留三星原生分页、动画和通知卡，不使用自制替代界面。

### 锁屏和自动息屏

- **解锁态**：沿用系统“设置 → 显示 → 屏幕超时”的 `screen_off_timeout`。
- **超时到期**：先进入系统 Keyguard，再单独关闭外屏。
- **锁屏态**：固定 10 秒后关闭外屏。
- **重新唤醒**：清除 display 1 OFF 覆盖；若此前因系统超时锁定，会先显示锁屏页。
- 该策略不会主动调用全局 `goToSleep`，避免连带关闭或改变 display 0 状态。

## 模块页面

模块页面提供运行状态、外屏控制、允许启动的应用、外观设置和版本信息。更新检查固定使用：

[https://github.com/AndyNull/flexunlock/releases](https://github.com/AndyNull/flexunlock/releases)

模块页面显示的版本来自 APK `versionName`，它与项目根目录 `gradle.properties` 中的 `VERSION_NAME` 同源。

## 版本规则

项目采用语义化版本：

- 兼容性或架构大改：增加主版本，例如 `2.0.0`。
- 完整功能版本：增加次版本，例如 `1.4.0`。
- 发布后的修复和 App 优化：增加补丁版本，例如 `1.4.1`、`1.4.2`。

唯一版本源：

```properties
VERSION_NAME=1.7.1
VERSION_CODE=10701
```

`cover-shell`、保留的 `app` 模块和 `system-bridge` 都读取同一组值，禁止再单独写模块版本号。

## 构建

构建环境：JDK 17、Android SDK 34、Gradle 8.9。

```powershell
& "C:\Users\andy\.gradle\wrapper\dists\gradle-8.9-bin\78qddjpeqn5v6yec3xb8kv9ca\gradle-8.9\bin\gradle.bat" `
  :cover-shell:lintDebug `
  :cover-shell:assembleDebug `
  --no-daemon
```

产物路径：

```text
cover-shell/build/outputs/apk/debug/cover-shell-debug.apk
```

实际发布构建入口是 `cover-shell`。它通过 source set 编入 `system-bridge` 源码；`settings.gradle.kts` 不单独构建历史 `app` 和 `system-bridge` APK。

## 设备验证:截图识别校验

验证 UI 状态时,可使用仓库内的 OCR 截图校验工具对设备截图(adb screencap 或
`build/device-evidence/` 历史证据)做自动识别与断言:

```powershell
# 校验外屏规格 + 关键文本 + 版本号(空白/换行噪声自动归一化)
python tools/verify_screenshot.py --image home.png --size 748x720 `
  --expect 控制中心 --expect FlexUnlock --regex "v1[.]4[.]5"

# 仅输出 OCR 全文
python tools/verify_screenshot.py --image about.png --ocr-only

# JSON 输出供脚本消费;退出码 0=通过 1=失败
python tools/verify_screenshot.py --image home.png --expect 控制中心 --json
```

依赖:Python 3 + `pip install pillow pytesseract` + Tesseract OCR(Windows 可用
`winget install --id UB-Mannheim.TesseractOCR -e`,中文识别需
`chi_sim.traineddata` 放入 Tesseract `tessdata` 目录)。该工具只读取截图,
不向设备写入任何内容。

## 故障排查

### 安装后没有效果
- 确认 LSPosed 中模块已启用且作用域正确。
- 确认完成了整机重启。
- 检查 LSPosed 日志是否包含 `FlexUnlock-SystemBridge` 和 `FlexUnlock-CoverShell`。

### App 启动仍先显示错误方向

- 确认系统自动旋转开启。
- 确认 display 1 的 `fixed-to-user-rotation` 保持默认。
- 记录启动前物理方向、display 1 rotation、Activity configuration 和模块日志。

### QS 方向不正确

- 先收起 QS，再真实旋转设备后重新下拉。
- 同时检查 display 1 尺寸、Activity configuration 和物理截图，不能只看单个 `mRotation` 字段。

### 外屏不会自动锁屏

- 确认系统 `screen_off_timeout` 不是异常值。
- USB 调试环境可以保留 `stay-awake=2`；FlexUnlock 的 display 1 策略不依赖默认 power group 超时。
- 检查日志中是否出现 `timeout expired mode=system`、`keyguardRequested=true` 和 `requested display-1 OFF`。

## 当前限制

- 三星私有类名、字段和资源可能随 One UI 更新变化。
- 完整 DeX 应用菜单的个人/工作资料切换控件仍依赖三星 Launcher 当前固件布局。
- 1.4.1 最终构建已完成 0° edge-to-edge、左下异形区覆盖、版本同步和更新失败反馈验证；90°、180°、270°仍应在目标固件上通过真实物理转动复验。
- 不建议清除 Launcher、SystemUI 或设置数据来解决显示问题，这会破坏可复现状态。

## 发布内容

`1.7.1` 发布包含：

- 一个经过发布证书签名的 APK：`FlexUnlock-1.7.1-release.apk`。
- 简略更新日志与本使用指南。

发布页：[https://github.com/AndyNull/flexunlock/releases](https://github.com/AndyNull/flexunlock/releases)
