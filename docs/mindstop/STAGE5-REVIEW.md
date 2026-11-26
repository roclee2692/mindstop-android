# 阶段 5 审查：独立学习模式、轻量复盘、弹性专注与睡前限制（MVP 0.2）

日期：2026-11-26。分支 `mindstop`，基于提交 `0e665f7`。范围对应需求 `SPEC-MVP-0.2.md` §7（独立学习模式）、§14（轻量复盘）、§15（弹性专注模式）、§16（睡前限制与早起预留）、§20（阶段 5）及 §21 验收第 15–17 条。

## 本阶段交付

### 1. 纯 Kotlin 核心状态机与领域模型（无 Android 依赖，全部有 JVM 测试）

- **`app.mindstop.core.Focus.kt`（弹性专注引擎 `FocusEngine`）**：
  - 支持自定义专注时长（5–180 分钟）、累计临时中断额度（0–60 分钟）与学习工具白名单（`whitelistPackages`）。
  - 真实暂停语义：调用 `pauseForInterrupt` 会暂停专注计时器并改记临时中断额度；调用 `resumeFromInterrupt` 恢复专注计时。
  - 中断额度渐进提醒节点：`FocusInterruptNode.HALF`（50%）、`EIGHTY`（80%）、`EXHAUSTED`（100%）。
  - 防刷机制：中断后提前返回专注仅在每次专注会话中最多触发 1 次正向鼓励（`earlyReturnPraised`），重复暂停/恢复不能刷奖励；只有真正达成专注目标时长（`COMPLETED`）才发放成长世界奖励（`RewardTrigger.FOCUS_COMPLETE`，`+15` 能量）。
  - 自研无 Gson 的键值编解码 `encode/decode`，防 R8 混淆且支持进程重启恢复。
- **`app.mindstop.core.Bedtime.kt`（睡前模式引擎 `BedtimeEngine`）**：
  - 将白天与睡前娱乐规则彻底分离，支持跨午夜时段回绕判断（如 `22:30` 至次日 `07:00`）。
  - 支持睡前 15 分钟与 5 分钟温和收尾提醒（`BedtimeReminderNode.BEFORE_15M` / `BEFORE_5M`）。
  - 配置项涵盖：总开关、睡前起止时间、睡眠时段暂停受管娱乐应用、夜间最大答题续期次数（0–5）、夜间冷静时长（1–720 分钟）、降低视觉刺激开关，并按 §16 预留后续早起模块数据结构 `WakeupConfirmMethod`（`MANUAL`/`QR`/`NFC`）与 `WakeupPlan`（`plannedWakeMinutesOfDay`）。
- **`app.mindstop.core.Reflection.kt`（轻量复盘模型）**：
  - 4 种非评判性完成结果 `ReflectionOutcome`（`COMPLETED` / `PARTIAL` / `NOT_COMPLETED` / `SWITCHED`）与 6 种可多选偏离原因 `ReflectionReason`（推荐视频、评论区、忘记目标、疲劳压力、想继续休息、其他），含编解码工具，绝不做自动化心理诊断。
- **`app.mindstop.core.Reward.kt`**：
  - 新增 `RewardTrigger.FOCUS_COMPLETE`（默认 `+15` 确认能量）。

### 2. 持久层 `mindstop_db` v6 与仓储

- **`Entities.kt` / `Daos.kt` / `MindstopDatabase.kt`**：
  - 升级至 **DB v6**，编写显式 `MIGRATION_5_6`（禁止 destructive fallback）：
    1. `usage_segments` 表新增可空列 `mode TEXT`（区分 `STUDY` 与娱乐 `FUN`/旧记录 `NULL`）。
    2. 新增 `reflections` 表及索引 `index_reflections_dayKey`。
    3. 新增 `focus_sessions` 表及索引 `index_focus_sessions_dayKey`。
  - `UsageSegmentDao.totalsByPackage` 与 `totalForPackages` 自动排除 `mode = 'STUDY'`，保证学习模式时长**不扣减娱乐额度**；新增 `studyTotalForDay` 单独汇总「今日学习模式使用（自报）」。
  - `IntentionDao.updatePurpose` 支持学习模式中途显式切换为娱乐模式。
- **`QuotaRepository.kt`**：新增 `MODE_STUDY`/`MODE_FUN`、`allSessionTotals`、`studyTotalForDay`、`switchIntentionToEntertainment`，以及带 `mode` 参数的 `segmentRows`。
- **`RewardRepository.kt`**：新增 `grantFocusCompleteReward(sessionId, nowWall)`，按 `sessionId` 去重防重发，完成专注时直接写入成长世界已确认 `+15` 能量。
- **`Stage5Repository.kt`**：统一封装睡前设置读写、活跃与历史专注会话、学习工具白名单、轻量复盘保存与跳过事件日志。

### 3. 服务引擎、悬浮窗与拦截页联动

- **`MindstopEngine.kt`**：
  - **独立学习模式（§7）**：当会话意图为 `STUDY` 且进行中时，前台时长写入 `mode = "STUDY"`，不扣减日/单次娱乐额度，不触发娱乐超额拦截；切屏、熄屏（`ACTION_SCREEN_OFF`）、服务销毁（`onDestroy`）及定期落库均保持 `STUDY`/`FUN` 精确区分。
  - **显式切换为娱乐模式**：悬浮面板在学习模式下显示「切换为娱乐模式」按钮，点击后先 `flush` 保存全部已累计学习时长（绝不清零历史统计），再把会话目的切为 `FUN` 并立即按娱乐额度规则重新评估。
  - **弹性专注联动**：每次前台切换与 5 秒 `onTick()` 推进活跃专注计时；切入非白名单且非豁免的受管娱乐应用时自动暂停专注并扣减中断额度，在 50%/80%/100% 弹出温和悬浮提醒；专注目标达成时落库并弹出 `+15` 成长能量横幅。
  - **睡前模式联动**：临近睡前 15 分钟 / 5 分钟弹出温和收尾横幅；进入睡前时段且开启「暂停娱乐」时（且非学习模式），拦截受管娱乐应用并传 `EXTRA_BEDTIME_MODE` 展示平静睡前页。
  - **轻量复盘触发**：在悬浮面板或续时页结束学习会话后，自动拉起可一键跳过的轻量复盘页。
- **`MindstopSessionOverlay.kt`**：学习模式下在悬浮展开面板展示「切换为娱乐模式」按钮。
- **`MindstopBlockActivity.kt`**：支持 `EXTRA_BEDTIME_MODE` 平静睡前拦截卡片（紧急解除 5 分钟记录 `bedtime_unplanned_use` 与 `KEY_BEDTIME_ALLOW_UNTIL`）；在睡前时段内（即使未开启整夜暂停，而是白天额度耗尽时）自动采用 `bedtimeCooldownMinutes` 与 `maxBedtimeRenewals == 0` 限制。
- **`MindstopGrowthFragment.kt`**：成长记录列表支持展示 `FOCUS_COMPLETE`（「完成专注」）来源标签。

### 4. 新增界面与入口接线

- **轻量复盘页**：`MindstopReflectionActivity.kt` + `activity_mindstop_reflection.xml`（展示目标与计划/实际时长、4 选 1 完成度、6 项多选偏离原因、保存/跳过，完成后回桌面）。
- **弹性专注页**：`MindstopFocusActivity.kt` + `activity_mindstop_focus.xml`（专注时长与中断额度快捷芯片 25/45/60/90 与 5/10/15/20、复用 `SelectAppsActivity` 配置学习工具白名单、进行中 1 秒实时倒计时、临时中断/返回专注含一次性提前返回鼓励、提前结束、近期 10 条历史记录）。
- **睡前规则页**：`MindstopBedtimeActivity.kt` + `activity_mindstop_bedtime.xml`（开关、24 小时制起止时间、暂停娱乐开关、夜间最大续期次数、夜间冷静时长、降低刺激开关、预留计划起床时间）。
- **入口与首页摘要**：
  - 「今日」页（`MindstopTodayFragment` + `fragment_mindstop_today.xml`）：新增「开启专注」「睡前规则」快捷入口；当今日存在学习模式记录时显示「今日学习模式使用（自报）· 单独统计，不扣娱乐额度」卡片（绝不标为已验证有效学习）；当存在进行中专注时顶部展示实时状态卡。
  - 「设置」页（`MindstopSettingsFragment` + `fragment_mindstop_settings.xml`）：核心模块区新增「弹性专注」「睡前规则」入口。
  - `AndroidManifest.xml`：注册 3 个新 Activity（均 `exported="false"`）。
  - 中英双语文案（`values/strings.xml`、`values-zh-rCN/strings.xml`）同步补全，中英文 key 100% 对齐，零连字符/破折号。

## 验证结果

- **三 flavor Debug 构建**：`assembleFdroidDebug`、`assembleFullDebug`、`assemblePlaystoreDebug` 全部通过。
- **Mindstop 单元测试**：`MindstopStage5Test` 新增 6 项 JVM 单元测试全部通过，全量 Mindstop 单元测试 **105/105 PASS（0 失败，0 跳过）**（上游已知基线失败项 `ScriptLanguageTest.matchesRegexSupportsCommonFlags` 未改动）。
- **Room DB v6 迁移校验**：提取 `MindstopDatabase.kt` 中 `MIGRATION_5_6` 的全部 5 条 SQL，与 Room KSP 生成的 `MindstopDatabase_Impl.java` 逐字核对，并在内存 `sqlite3` 中真实执行 `v5 → v6` 迁移对比新装 `v6` 数据库，全部 12 张表的列、类型、非空约束、默认值、主键与索引 **100% 完全一致**。
- **`lintFullDebug`（Mindstop 文件）**：排除上游多语言 `MissingTranslation` 后 **0 Error**；顺手清除了 `activity_mindstop_focus.xml` 的全部 `HardcodedText` 警告。

## 未在真机验证（本阶段仅完成本地构建、单测、迁移与 Lint 校验）

以下交互链路**尚未在 Redmi K80 真机上实际运行验证**，待真机安装后逐项确认：

1. 从现有 v5 数据库覆盖安装 v6 APK，验证 `MIGRATION_5_6` 真机升级不丢失既有规则、成长树苗与历史记录。
2. 在受管应用选择「学习」目的，验证使用时长归入今日学习模式（自报）卡片而不扣减娱乐额度；在悬浮面板点击「切换为娱乐模式」后验证原时长保留且立即按娱乐额度生效。
3. 在悬浮面板或续时页结束学习会话时，HyperOS 上 `MindstopReflectionActivity` 后台弹出、保存复盘与一键跳过回桌面的实际表现。
4. 开启「弹性专注」后切换到非白名单受管应用自动暂停专注、在 50%/80%/100% 中断额度弹出悬浮提醒、点击「返回专注」显示一次性鼓励提示、专注完成后成长世界新增 `+15` 能量并长树。
5. 开启「睡前规则」跨午夜时段（如 `22:30`–`07:00`），验证 15 分钟/5 分钟收尾提醒、睡前拦截页与「紧急解除 5 分钟」恢复链路。

## 已知限制

- 轻量复盘目前自动在结束「学习模式」会话时弹出；针对娱乐显著超时的自动复盘触发与历史复盘统计汇总将在阶段 6（数据统计页）一并呈现。
- 睡前模式的「夜间最大续期次数」当前在进入睡前时段且设为 `0` 时完全禁用答题续期，设为 `1–5` 时沿用白天续期档位（尚未独立按夜间单独计数），将在阶段 6 与统计数据一起细化。
- 「睡前降低动画与视觉刺激」开关与「计划起床时间」已完整持久化并在数据层预留 `WakeupPlan` 接口，实际闹钟与扫码/NFC 起床确认按 §16 / §20 留作后续版本扩展。
- 与阶段 2–4 一致，弹性专注与睡前规则的设置变更立即生效，未接入 Curbox 原生的设置变更延迟机制。
