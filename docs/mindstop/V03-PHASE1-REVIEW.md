# v0.3 第一阶段审查：夜间收尾（Bedtime Wind-down）

日期：2026-11-26。分支 `mindstop`，基于提交 `bc253a9`。范围对应 `docs/mindstop/SPEC-V0.3-EXPANSION.md` §3–§8（夜间收尾：统一作息计划、渐进式收尾、夜间限制策略、安静睡前正向奖励、夜间行为记录），并附开题前 6 个开源候选仓库审查（见 `docs/mindstop/OSS_REUSE_AUDIT.md`）。

## 本阶段交付

### 1. 统一睡眠计划与渐进收尾引擎（`app.mindstop.core.Bedtime.kt`）

- **`SleepDaySchedule` 与工作日/休息日独立计划**：
  - 统一配置计划睡眠时间、计划起床时间、最晚娱乐结束时间、晚间自由休息时长、睡前缓冲时长、提前收尾提醒与剩余倒计时节点；支持周五/周六晚启用独立休息日作息。
  - 自动计算跨午夜安全的 **计划睡眠机会时长**（`plannedSleepOpportunityMinutes`）以及超过计划时间后的 **当前剩余睡眠机会**（`remainingSleepOpportunityMinutes`），界面明确声明其不代表实际生理睡眠时长。
  - 保持与 Stage 5 `bedtime_settings` 键值的 100% 向后兼容解码。
- **六阶段渐进状态机（`BedtimePhase`）**：
  - `DAYTIME` → `FREE_REST`（自由休息）→ `WIND_DOWN_PREP`（准备收尾提醒 + 明日起床与计划睡眠机会）→ `WIND_DOWN_COUNTDOWN`（精确显示夜间娱乐剩余分钟 + 悬浮胶囊倒计时联动）→ `BUFFER`（睡前缓冲保护）→ `SLEEP_PROTECTION`（睡眠保护时段，平和显示当前延迟对明日睡眠机会的影响，无恐吓焦虑文案）。
- **夜间限制与放行应用分离（`BedtimeEngine.isRestrictedAtNight`）**：
  - 支持单独勾选夜间限制应用（如短视频、游戏、购物），留空时自动跟随全部已启用的娱乐规则；
  - 必要应用（`exempt`）与用户勾选的夜间放行应用（阅读、音乐、闹钟等）永不被夜间限制误伤。
  - 夜间默认 `maxBedtimeRenewals = 0`（默认不推荐高强度数学答题，避免睡前认知刺激），同时始终保留用户主动解除能力（紧急解除 5 分钟）。

### 2. 睡前安静奖励与夜间行为记录（Room DB v7）

- **睡前正向奖励（`Reward.kt` / `RewardRepository.kt` / `MindstopReminderOverlay.kt`）**：
  - 新增 `RewardTrigger.BEDTIME_ON_TIME`（计划内主动结束，完整 `+12` 能量）与 `RewardTrigger.BEDTIME_LATE_STOP`（超时后及时停下，恢复控制部分 `+5` 能量），统一汇入现有成长世界，绝不清空历史成就。
  - 立即弹出安静、简短、低亮度的夜间银杏反馈卡片（`showNightReward`），后台 15 分钟验证窗口内未重开受限应用即自动确认，**无需用户重新打开软件领奖**；窗口内重开受限应用自动撤销待确认奖励。
- **Room DB 显式升级 v6 → v7（`Entities.kt` / `Daos.kt` / `MindstopDatabase.kt`）**：
  - 新增 `bedtime_logs` 表及 `index_bedtime_logs_updatedWall` 索引，编写显式 `MIGRATION_6_7`（禁止 destructive fallback）。
  - 记录每晚（按跨午夜 `nightKey` 归属）计划娱乐结束时间、最后娱乐时间、睡前娱乐超时分钟、夜间重开次数、主动停下次数、强制拦截次数，以及用户可选手动填写的自报入睡时间。

### 3. 服务与界面接线

- `MindstopEngine.kt`：接入 `BedtimeEngine.evaluateAtWall`，自动驱动自由休息→准备收尾→倒计时→缓冲→睡眠保护全链路、悬浮胶囊夜间倒计时、低刺激夜间横幅、安静睡前奖励发放及 `bedtime_logs` 落库。
- `MindstopBlockActivity.kt`：夜间模式支持低刺激深林静谧底色、平和展示明日睡眠机会影响，并在点击「结束使用」时发放恢复控制奖励与记入夜间主动停下记录。
- `MindstopBedtimeActivity.kt` + `activity_mindstop_bedtime.xml`：升级为完整「睡前规则与夜间收尾」页，实时预览计划睡眠机会与 5 节点收尾节奏、工作日/休息日配置、夜间限制/放行应用选择器、近期 7 晚夜间行为记录及「记录昨晚实际入睡时间（自报）」弹窗。
- 底部导航保持 4 Tab 不膨胀。

## 验证结果

- **三风味构建**：`assembleFdroidDebug`、`assembleFullDebug`、`assemblePlaystoreDebug` 全部通过。
- **单元测试**：新增 `MindstopBedtimeV03Test` 5 项测试，全量知止 JVM 单元测试 **113/113 PASS（0 失败，0 跳过）**。
- **Room DB v7 迁移校验**：`MIGRATION_6_7` SQL 与 Room KSP 生成的 `MindstopDatabase_Impl.java` 逐字核对，内存 `sqlite3` `v6 → v7` 校验全部 13 张表列与索引 **100% 一致**；并在 Redmi K80 真机验证 `v6 → v7` 覆盖升级 `PRAGMA user_version = 7` 零崩溃。
- **Lint 检查**：`lintFullDebug` 知止文件（除上游多语言 `MissingTranslation` 外）**0 Error**；中英文字符串 100% 对齐且零连字符/破折号。

## 未在真机逐项手测的交互项

- 真机在 22:30 / 22:45 / 22:50 / 23:00 实际跨节点触发低亮度悬浮提醒、夜间拦截低刺激界面、以及放下手机后 15 分钟后台静默确认银杏睡前奖励的完整时间流体验（待你实际使用或调整为临近测试时间点手测）。
