# 阶段 4 审查：即时奖励、延迟确认、撤销机制与简单成长世界（MVP 0.2）

日期：2026-11-26。分支 `mindstop`，基于提交 `fdb4e1e`。范围对应需求 `SPEC-MVP-0.2.md` §11（正向成长奖励）、§12（成长世界与视觉主题）、§20（阶段 4）及 §21 验收第 11–14 条。

## 本阶段交付

### 纯 Kotlin 核心 `app.mindstop.core.Reward.kt`（无 Android 依赖，全部有 JVM 单元测试）

- **奖励状态机 `RewardEngine`**：
  - 三种状态：`PENDING`（待确认）、`CONFIRMED`（已确认）、`CANCELLED`（已撤销）。
  - 三种来源严格区分：
    - `ACTIVE_EXIT`（主动退出：悬浮面板结束、续时页目标完成/离开、主动切回桌面或非受管应用）→ 完整奖励 `+10` 能量。
    - `INTENTION_DECLINE`（启动前意图页放弃打开）→ 完整奖励 `+10` 能量（同样受会话与同组每日上限保护，不能靠反复点开再离开刷取）。
    - `BLOCK_STOP`（额度到期拦截后点「结束使用」）→ 部分奖励 `+4` 能量，与自主退出明显区分。
    - **强制冷静期绝不发放自主控制奖励**；冷静期内再次尝试打开受限应用时，服务记录 `cooldown_reopen_attempt` 事件。
  - **延迟确认与防撤销保证**：
    - 默认 15 分钟验证窗口（可配置 1–60 分钟）。
    - `dueToConfirm` 永远先于 `dueToCancelOnReentry` 执行：等满验证窗口后再打开娱乐应用会先正式确认，不会被误撤销。
    - 已确认（`CONFIRMED`）的历史奖励永不因后续正常娱乐而撤销。
  - **防套利规则**：
    - 同一有效会话最多 1 条 `PENDING` 或 `CONFIRMED` 奖励；单次会话内撤销重试上限为 1 次，防止反复打开退出刷取反馈。
    - 同一娱乐分组（按 `APP > GROUP > GLOBAL` 最细生效规则归组）默认每天最多 1 次完整成长奖励（可配置 1–10 次），部分奖励同样限每日每组 1 次。
    - 奖励能量为固定值，不随观看视频时长或续期次数增长。
    - 预留实验性配置 `requireReminderFirst`（仅在首次时间提醒后发放主要奖励），**默认关闭**，确保提前主动退出与临近到期退出享有同等资格。
- **成长世界领域模型 `GrowthWorldPlanner`**（与 UI 完全解耦，便于未来扩展森林/岛屿/伙伴主题）：
  - 每 10 点已确认能量长成 1 棵常青树，余数显示距下一棵树的进度。
  - 五档花园阶段：`清晨空地`（0）→ `初萌新芽`（1+）→ `静谧小园`（30+）→ `林间小径`（80+）→ `常青林地`（150+），永不因未打开软件而衰减或死亡。
  - 基于 `SeededRandom` 的确定性树木排布（阔叶树 / 松柏 / 开花灌木三类），待确认树苗单独置于前景草地。

### 持久层 `mindstop_db` v5 与仓储 `RewardRepository`

- `Entities.kt` / `Daos.kt` / `MindstopDatabase.kt`：升至 **DB v5**，新增 `rewards` 表与 3 个索引（`(dayKey, groupKey)`、`status`、`sessionId`），编写显式 `MIGRATION_4_5`（禁止 destructive fallback）。
- 原子防重复结算：DAO 使用 `WHERE id = :id AND status = 'PENDING'` 执行确认与撤销，多进程并发调用不会重复确认或重复写事件。
- 事件流新增：`reward_pending`、`reward_confirmed`、`reward_cancelled`、`reward_settings_changed`、`cooldown_reopen_attempt`。

### 服务侧与即时反馈

- `MindstopEngine.kt`：
  - 每次前台切换与每 5 秒 `onTick()` 自动结算到期 `PENDING` 奖励。
  - 验证窗口内重新进入同组任一受管应用时，撤销对应 `PENDING` 奖励，并通过 `accountant.restore(cancelledSession, nowWall)` 恢复原会话（即使离开时间已超过 10 分钟但仍在 15 分钟验证窗口内），同时以温和无羞辱横幅告知用户。
  - 在悬浮面板「目标完成/结束本次使用」以及用户主动切回桌面/非受管应用时立即发放 `PENDING` 奖励，并通过 `MindstopReminderOverlay.showReward()`（`TYPE_ACCESSIBILITY_OVERLAY`，纯原生 View，不含 Material 组件）展示简短清新的即时成就反馈与 §13 原创中英文激励文案。
- `MindstopBlockActivity.kt`：非冷静状态下点「结束使用」发放 `BLOCK_STOP` 部分奖励并弹 Toast；冷静期内不发放。
- `MindstopIntentionActivity.kt`：点「目标完成，现在离开」与「不看了，带我离开」分别按 `ACTIVE_EXIT` / `INTENTION_DECLINE` 走同一防套利通道并给即时反馈。

### 成长世界与规则设置 UI

- `MindstopGardenView.kt`：原生 Android 2D Canvas 轻绘花园，适配深浅色模式与清新自然绿主题；已确认树永久扎根，待确认树苗在前景带呼吸光环；无外部图片依赖、无 `onDraw` 对象分配。
- `MindstopGrowthFragment.kt` + `fragment_mindstop_growth.xml`：完整替换为成长花园页（花园画布、阶段与下一棵树进度条、待确认树苗倒计时卡片、今日确认/待确认/累计成树三联指标、近期 20 条成长记录及清晰状态/来源标签、入口直达奖励规则页）。
- `MindstopRewardSettingsActivity.kt` + `activity_mindstop_reward_settings.xml`：配置验证窗口分钟数、同组每日上限、即时反馈开关及实验性「首次提醒后发放」开关；同时在「设置」Tab 增加入口。

## 验证

- **三 flavor 构建**：`assembleFdroidDebug`、`assembleFullDebug`、`assemblePlaystoreDebug` 全部构建成功。
- **单元测试**：`MindstopRewardTest` 新增 10 个 JVM 测试全部通过，覆盖主动退出发放、拦截后停止部分奖励、到点确认与已确认永不撤销、同组跨应用重入撤销、同会话/同组每日上限防套利、撤销后重复进出防刷、实验性首次提醒开关、规则归组优先级、设置编解码容错、成长世界树数/阶段与确定性排布；Mindstop 总计 **94/94 通过（0 失败）**（全量 `testFullDebugUnitTest` 唯一失败仍为上游既有 `ScriptLanguageTest.matchesRegexSupportsCommonFlags`）。
- **Room v5 迁移核对**：已将 `MIGRATION_4_5` 建表与 3 条建索引 SQL 与 Room 生成的 `MindstopDatabase_Impl.java` 逐字比对，并在内存 `sqlite3` 中验证 `PRAGMA table_info(rewards)` 与 `PRAGMA index_list(rewards)` 完全一致。
- **`lintFullDebug`（Mindstop 文件）**：**0 Error**，并顺手消除了 `MindstopGardenView` 的 `DrawAllocation` 警告。

## 未在真机验证（当前手机已断开 USB，待复连后实测）

以下项目仅通过编译、JVM 单元测试与 SQL 迁移校验，**尚未在 Redmi K80 真机上实际运行**：

1. 从当前手机上已装的 v4 数据库覆盖安装 v5 APK，验证 `MIGRATION_4_5` 真机升级不丢旧规则与使用记录。
2. 主动退出受管应用（切回桌面 / 悬浮面板点结束 / 意图页点离开）瞬间，`MindstopReminderOverlay.showReward()` 成就横幅在 HyperOS 上的视觉与自动消失表现。
3. 验证窗口内（如临时把验证窗口调成 1 分钟）重新打开同组另一个应用（计算器 → 时钟）触发 `reward_cancelled`、温和提示横幅以及原会话恢复。
4. 等满验证窗口后自动结算为 `reward_confirmed`，成长花园 Canvas 从前景呼吸树苗变为永久扎根树木，以及之后再次正常打开受管应用不会撤销已确认树木。
5. `MindstopGardenView` 在亮色与暗色模式下的真机渲染观感与滚动流畅度，以及「奖励与验证规则」页面修改并保存。

## 已知限制

- 奖励规则修改立即生效，未接入 Curbox 的「设置变更延迟」（与阶段 2、3 设置项保持一致）。
- 即时反馈文案内置 3 句原创中英文激励语，自定义激励文案与独立文案开关属于后续阶段优化项。
- 成长世界首版仅实现「森林花园」单一视觉主题，生态岛屿与伙伴主题按 §20 留作后续扩展（领域模型 `GrowthWorldState` 已预留完全解耦接口）。
