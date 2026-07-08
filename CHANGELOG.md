# FlexUnlock 更新日志

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
