# FlexUnlock 更新日志

## v0.46.0 (2026-07-08)

### 闪退根因深度修复

经过对 AppRedirectService 在展开状态下行为的深度分析，发现 4 个致命闪退源：

#### 1. AppRedirectService 展开状态实际仍在调用 shell
- **原注释撒谎**：源码注释 "v0.45.2: 展开时完全不执行任何 shell 命令" 是错误的
- 实际每 2 秒调用 `cmd device_state print-state`
- 修复：展开时改为 5 秒轮询（减少 60% shell 调用），所有 shell 调用带 3 秒超时

#### 2. App.startVolumeKeyListener 在展开状态触发 wm size/am start
- **致命**：双击音量键时直接调用 `enableHomeMode()`，无视当前折叠状态
- 在展开状态下，display 1 已被系统关闭，对死 display 执行 `wm size -d 1` 和 `am start --display 1` 触发 SystemUI 异常
- 修复：增加状态门控，仅在 state==0 或 state==3 时才触发 enableHomeMode
- 修复：轮询间隔从 200ms 改为 500ms（减少 60% shell 调用）

#### 3. am start --display 1 -W 的 10 秒同步阻塞
- `-W` 标志让 am start 同步等待 Activity 启动完成
- 在折叠→展开过渡期触发时，Activity 卡在已死的 display 1 上
- 修复：去掉 `-W` 标志，改为异步启动
- 修复：所有 shell 调用带 5 秒超时

#### 4. 状态去抖 900ms 延迟期间的幽灵重定向
- 3 次确认机制导致 900ms 延迟，期间 redirectAppsFromDisplay0() 继续执行
- 修复：从 3 次确认改为 2 次确认（减少 300ms 延迟）
- 修复：展开时立即 `pkill -f 'am start --display 1'` 终止任何 in-flight 启动

#### 5. AccessibilityService 彻底移除
- 即使最小化配置（eventTypes="" + canRetrieveWindowContent=false），系统仍会绑定 AccessibilityService
- 一旦用户手动启用，立即注入所有 App 进程导致闪退
- 修复：从 AndroidManifest.xml 删除 service 注册
- 修复：删除 SideKeyAccessibilityService.kt 文件
- 修复：删除 accessibility_service_config.xml
- 修复：删除 RootA11yEnabler.kt（不再需要）

#### 6. movedTasks 跨周期污染
- 仅在 wasCoverActive==true 时清理，状态抖动时永不清理
- 修复：展开时无条件清理 movedTasks 和 resolveCache

#### 7. 合盖时轮询频率过高
- 原 300ms 轮询 + 300ms 节流 = 每秒 3+ 次 shell 调用
- 修复：改为 1000ms 轮询 + 1000ms 节流

### 测试要点
1. 展开后双击音量键：应被拒绝（logcat 显示 "ignoring volume double-click to avoid crash"）
2. 合盖后双击音量键：应正常触发外屏桌面
3. 展开后 logcat 不应出现任何 am start --display 1 调用
4. 折叠→展开过渡期不应再出现 ANR 或 SystemUI 异常

## v0.45.2 (2026-07-08)

### 修复
- 状态去抖 3 次确认（但仍有 900ms 延迟问题，见 v0.46.0）

## v0.39.0 (2026-07-08)

### 修复
- 从 v0.36 APK 反编译恢复所有源码（修复代码回退问题）
- App.kt: 启动 AppRedirectService（不是 CoverHomeGuardService）
- MainActivity: 恢复守护按钮 + 无障碍按钮 + 状态显示
- AndroidManifest: 恢复所有 4 个组件注册
- activity_main.xml: 恢复所有按钮 UI

### 新增
- AppRedirectService: I= 格式直接提取组件名（修复我的文件）
- 联系人: samsung.android.task.contacts 映射到 com.samsung.android.app.contacts

### 已知问题
- 联系人仍无法打开
- App 启动延迟较高（~2-3 秒）

## v0.36.0 (2026-07-08)

### 修复
- 设置: com.android.settings.root 别名映射
- 开盖残留: 展开时 force-stop launcher 清理 display 1 task
- 开盖再合盖: 不再清空 movedTasks

### 新增
- dumpsys 解析: 同时匹配 A= 和 I= 前缀
- 包名别名映射表

## v0.30.0 (2026-07-08)

### 修复
- 去掉错误的硬编码 Activity 映射（时钟/天气/笔记恢复正常）
- 只保留电话/相机的硬编码映射

## v0.28.0 (2026-07-07)

### 功能
- Samsung 系统 App 映射表
- resolve-activity isDefault=true/false 前缀修复
- 800ms 轮询，1.5 秒冷却期

## v0.18.0 (2026-07-07)

### 修复
- 砍掉守护服务轮询（修复 Chrome 闪退）
- 移除 forceAllAppsResizable 自动执行
- 保留双击音量键 + App 重定向

## v0.4.0 (2026-07-07)

### 功能
- state 4 + am start --display 1 启动桌面到外屏
- 首个可用版本
