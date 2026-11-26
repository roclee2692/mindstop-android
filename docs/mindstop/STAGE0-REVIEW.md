# 知止 Mindstop：阶段0 上游审查与复用评估

基线：Curbox `kt-rewrite` @ `ab7b9b2`（2026-11-23 推送），GPL-3.0。本仓库在 `mindstop` 分支开发，`upstream` 远端指向原仓库。

## 关键结论

- 上游使用 Kotlin + 传统 View/Fragment + ViewBinding，不用 Compose。Mindstop 沿用这一栈，成长世界用自定义 Canvas View 绘制。
- 所有拦截、统计和悬浮层都运行在 `:app_blocker_service` 进程的无障碍服务 `AppBlockerService` 里。新引擎按上游同样的生命周期（setup → receivers → inline check → onDestroy）挂进去。
- 上游 Room 库 `curbox_db` 使用 `fallbackToDestructiveMigration()`，升级会清空数据。Mindstop 的新数据放进独立数据库 `mindstop_db`，带显式迁移，不碰上游表结构。
- 上游的使用时长按小时桶存储，窗口边缘精度约 1 小时，不够做单次会话和精确每日额度。Mindstop 的会话引擎自己用 `SystemClock.elapsedRealtime()` 计时，并定期把会话片段写入 `mindstop_db`。

## 规格功能 → 复用方式

| 规格功能 | 方式 | 上游位置 |
|---|---|---|
| 应用发现、搜索、手动包名 | 直接复用 | `ui/activity/SelectAppsActivity.kt`，`QUERY_ALL_PACKAGES`，`LauncherApps` |
| 安装/卸载刷新 | 复用回调 | `blockers/NewAppAutoAdder.kt` |
| 分组共享额度 | 扩展：上游按“时间段窗口”共享，Mindstop 改为按自然日 + 精确会话片段 | `blockers/AppBlocker.kt:156` |
| 单次会话额度、渐进提醒 | 新建 SessionEngine | 挂在 `AppUsageTracker.switchTo` 同一事件点 |
| 拦截动作（回桌面 + 拦截页） | 复用 `pressHome()` 与 Activity 启动方式 | `AppBlocker.showWarningScreen` |
| 拦截页与解锁链 | 新建 Mindstop 拦截页，参考 `WarningActivity` 的倒计时与挑战链 | `ui/activity/WarningActivity.kt` |
| 数学题 | 新建确定性种子生成器（上游 `AdaptiveMathChallenge` 无种子、不可持久化） | `ui/activity/AdaptiveMathChallenge.kt` |
| 冷却 | 新建（上游只有“解锁后可用时长”，没有拦截后的冷却锁定） | — |
| 悬浮倒计时/目标 | 扩展：复制 `TYPE_ACCESSIBILITY_OVERLAY` 悬浮层与 ticker | `ui/overlay/MindfulMessageOverlayManager.kt` |
| 意图记录 | 参考 `IntentLogEntity`，Mindstop 另存更完整的会话意图 | `data/db/IntentLogEntity.kt` |
| 专注模式 | 复用并扩展暂停/中断额度 | `blockers/FocusModeBlocker.kt`，`PomodoroState` |
| NFC/二维码解锁 | 复用 | `nfc/NfcUnlockUtils.kt`，`WarningScreenConfig` |
| 服务保活、开机恢复 | 复用 | `ServiceWatchdogJob`，`BootReceiver`，`ServiceProtectionManager` |
| 小米自启动入口 | 复用 | `hardcoded/OemAutostartIntents.kt:17` |
| 诊断页 | 新建，基于现有 `PermissionUtils` 等工具函数 | — |
| 统计与 CSV 导出 | 新建导出；图表组件可复用 | `ui/views/*`，`AdvancedAnalytics*` |

## 改名清单（applicationId → `app.mindstop.android`）

- 保留 `namespace = "neth.iecal.curbox"` 与原包结构，避免大范围移动文件，也保持上游可合并。
- 修改：`applicationId`、`app_name`、调试名、无障碍服务标签、深链 scheme、`AntiUninstallBlocker.kt:187` 的 `"curbox"` 文本检测、用户可见的 “Curbox” 文案。
- 广播 action 字符串（如 `neth.iecal.curbox.refresh.appblocker`）暂不改。若同一台手机也装了 Curbox，两者可能收到对方的广播；阶段1核查这些广播是否限定了接收包名，没有限定就改成 `app.mindstop` 前缀。

## 许可证

- 保留 `LICENSE`（GPL-3.0）与上游版权声明，新增 `NOTICE.md` 说明修改来源。
- 对外分发 APK 时必须同时提供对应源码。
- 第三方美术资源（Kenney 等）引入前逐个核对许可证，记录在 `THIRD_PARTY_NOTICES.md`。
