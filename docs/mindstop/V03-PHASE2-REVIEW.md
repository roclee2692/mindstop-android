# v0.3 第二阶段审查：晨间启动（Morning Activation）

日期：2026-11-26。分支 `mindstop`，基于提交 `7729a9b`。范围对应 `docs/mindstop/SPEC-V0.3-EXPANSION.md` §9–§14（晨间启动：开源轻量化闹钟、锁屏响铃、重启恢复、精确闹钟诊断、醒后 15 分钟身体唤醒与晨间期待过渡、防回床状态机、双阶段奖励及睡前晨间联动）。

## 本阶段交付

### 1. 开源复用落点（均已登记于 `THIRD_PARTY_NOTICES.md`）

- **`Purgator/Upupup`（GPL-3.0）**：参考其轻量化闹钟骨架，实现 `MindstopAlarmScheduler.kt`（`AlarmManager.setAlarmClock` 精确调度与免 Doze）、`MindstopAlarmReceiver.kt`（触发时即时重排下一次重复闹钟）、`MindstopAlarmService.kt`（前台响铃服务、有界 WakeLock、锁屏全屏意图通知）。
- **`FossifyOrg/Clock`（GPL-3.0）**：参考 Android 12–16 精确闹钟权限诊断（`canScheduleExactAlarms` + `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`）与锁屏唤醒（`setShowWhenLocked`/`setTurnScreenOn`）。
- **`gabeg805/NFC-Alarm-Clock`（GPL-3.0）**：参考渐进音量淡入（默认 30 秒平滑爬坡）与 NFC 前台读取验证。
- **知止既有能力复用**：数学起床验证直接复用 `MathGenerator`（不重复造题库）；扫码验证复用已有 ZXing `PortraitCaptureActivity`；晨间成就统一汇入现有 `RewardRepository` 与成长花园；闹钟时间一键从第一阶段统一睡眠计划 `BedtimeSettings` 同步。

### 2. 核心状态机与 Room DB v8

- **`app.mindstop.core.Morning.kt`**：
  - 完整状态机：`SCHEDULED → RINGING → ACKNOWLEDGED → ACTIVATION → COMPLETED / RETURNED_TO_BED / UNVERIFIED`。
  - **关闭闹钟绝不直接判定起床成功**，而是先进入醒后 15 分钟过渡期（默认 5 分钟身体唤醒 + 10 分钟晨间期待，均可配置）。
  - 辅助运动信号（`TYPE_STEP_DETECTOR`）仅作记录，永不自动判定清醒；超时未确认诚实落为 `UNVERIFIED`。
  - 双阶段奖励护栏：响应闹钟即种下待确认树苗（`RewardTrigger.MORNING_WAKE`，`+12` 能量），完成晨间启动后确认落根；若自报回床则收回待确认树苗但不影响历史成就；若计划睡眠机会不足 5 小时（`< 300` 分钟），自动跳过晨间奖励，**绝不奖励睡眠不足的极端早起**。
- **Room DB 显式迁移 v7 → v8（`MindstopDatabase.kt`）**：
  - 新增 `morning_alarms` 表与 `morning_sessions` 表（含 `index_morning_sessions_dayKey` 索引），编写显式 `MIGRATION_7_8`。

### 3. 服务、重启恢复与界面

- **`BootReceiver.kt` + `AndroidManifest.xml`**：监听 `BOOT_COMPLETED`、`QUICKBOOT_POWERON`、`MY_PACKAGE_REPLACED`、`TIME_SET`、`TIMEZONE_CHANGED`，系统重启或改时区后自动重排全部启用闹钟。
- **`MindstopAlarmRingActivity.kt`**：柔和晨光配色、锁屏直接亮屏弹出、支持「轻触关闭 / 简单数学 / 步数 / 二维码 / NFC」5 种验证方式及小睡，关闭闹钟后直接进入 `MindstopMorningActivationActivity`。
- **`MindstopMorningActivationActivity.kt`**：三阶段引导（已响应闹钟待确认树苗 → 5 分钟身体唤醒清单 + 5 分钟轻确认 → 10 分钟晨间期待与独立晨间娱乐提醒），提供「我已清醒，完成晨间启动」与「如实记录：我又回床睡了」明确选择，不无限响铃。
- **`MindstopMorningActivity.kt`**：闹钟列表与编辑器、一键「同步作息计划」、**「10 秒试响」真机一键自检按钮**、精确闹钟权限提示条、以及「睡前与晨间联动记录」（仅客观并排展示昨晚超时与次日响应延迟，不做虚假因果归因）。
- **`MindstopDiagnosticsActivity.kt`**：新增「精确闹钟权限」状态检查与一键直达系统设置。
- 入口自然融入「今日」页（新增「晨间启动」按钮）与「设置」页，底部保持 4 Tab。

## 验证结果

- **三风味构建**：`assembleFdroidDebug`、`assembleFullDebug`、`assemblePlaystoreDebug` 全部通过。
- **单元测试**：新增 `MindstopMorningTest` 4 项测试，全量知止单元测试 **117/117 PASS（0 失败，0 跳过）**。
- **Room DB v8 迁移校验**：内存 `sqlite3` `v7 → v8` 全部 15 张表与索引 100% 一致。
- **未在真机验证（当前手机未连接 USB，待复连后实测）**：
  - 从 v7 覆盖安装 v8 APK 到 Redmi K80 验证 `MIGRATION_7_8`。
  - 真机验证精确闹钟权限（`SCHEDULE_EXACT_ALARM`）、锁屏响铃（点「10 秒试响」后锁屏）、渐进音量、5 种起床验证（含数学/步数/QR/NFC）、关闭闹钟后进入 15 分钟过渡页与第 5 / 15 分钟通知提醒，以及重启手机后的闹钟自动恢复。

## 待用户真机手测步骤

- 重新插上手机或安装最新 `app-full-debug.apk` 后，打开「今日 → 晨间启动 → 同步作息计划 → 10 秒试响」并锁屏，即可完整实测锁屏响铃、渐进铃声与 15 分钟晨间启动流程。
