# 开源候选项目审查与复用评估（v0.3 扩展）

审查日期：2026-11-26（开发前通过 GitHub API 与源码目录核查，遵循本项目 GNU GPL v3.0 许可证兼容要求）。

## 1. 候选仓库核查总表

| 候选仓库 | 许可证 | 语言 | 活跃状态（最近推送） | 复用评估与结论 |
|---|---|---|---|---|
| `FossifyOrg/Clock` | GPL-3.0 | Kotlin | 活跃（2026-11-24） | **参考复用（第二阶段）**：精确闹钟（`AlarmManager.setAlarmClock`）、锁屏响铃（`setShowWhenLocked`/`setTurnScreenOn`）、Android 14+ `SCHEDULE_EXACT_ALARM` 诊断与多厂商兼容模式。整库体量较大且耦合 Fossify Commons，**不整体引入**。 |
| `Purgator/Upupup` | GPL-3.0 | Kotlin | 活跃（2026-11-25） | **重点轻量架构参考（第二阶段）**：仅 39 个 Kotlin 文件，结构极简清晰（`Alarm`、`AlarmScheduler`、`AlarmReceiver`、`AlarmService`、`BootReceiver`、`RingActivity`、`StepDetector`、`RoutineRunner`）。第二阶段按其架构移植轻量化闹钟调度、响铃服务、步数检测与重启恢复，数学挑战直接复用知止现有 `ChallengeEngine`。 |
| `gabeg805/NFC-Alarm-Clock` | GPL-3.0 | Kotlin | 活跃（2026-10-11） | **按需参考（第二阶段）**：参考渐进音量爬坡（AudioManager/MediaPlayer volume ramp）、自定义铃声选择与 NFC 标签读取校验（知止 Manifest 已具备 NFC 权限与深链基础）。整库 201 文件，不整体合并。 |
| `mablanco/pricegrab` | MIT | Kotlin | 活跃（2026-11-23） | **核心算法重点复用（第四阶段）**：MIT 许可证（与 GPL-3.0 完全兼容），纯 Kotlin 核心模块清晰（`core/model/Dimension.kt`、`QuantityUnit.kt`、`Offer.kt`、`core/calc/PriceComparator.kt`），采用 `BigDecimal` 精确计算单位价格并区分不兼容单位维度（重量/体积/件数）。第四阶段将直接吸收其单位维度与精确单价换算设计并补充单元测试。 |
| `Ivy-Apps/ivy-wallet` | GPL-3.0 | Kotlin | **已归档停更（archived=true）** | **仅参考设计（第三阶段）**：参考消费 7 分类、月度/自由预算数据结构与本地统计展示，**严禁作为代码依赖**。 |
| `iSoron/uhabits` | GPL-3.0 | Kotlin | 活跃（2026-10-22） | **算法思想参考（全阶段）**：参考其非惩罚性长期习惯评分（单次失误仅轻微回落、绝不因一次失败清零历史成就）与长期趋势视角，与知止成长世界原则一致。 |

## 2. 本次四阶段复用落点

1. **第一阶段（夜间收尾）**：主要复用知止现有 `MindstopEngine`、`RuleEngine`、`RewardEngine`、`MindstopReminderOverlay`、`MindstopSessionOverlay` 与 `mindstop_db`（显式升级至 v7），避免引入多余外部依赖；借鉴 `uhabits` 的非惩罚性长期记录原则。
2. **第二阶段（晨间启动）**：以 `Purgator/Upupup` 的轻量闹钟骨架（`AlarmScheduler`/`AlarmReceiver`/`AlarmService`/`BootReceiver`/`StepDetector`）为主干，融合 `FossifyOrg/Clock` 的锁屏与精确闹钟兼容、`gabeg805/NFC-Alarm-Clock` 的渐进音量与 NFC 校验，并直接复用知止现有 `ChallengeEngine` 与 `RewardEngine`。
3. **第三阶段（购物决策基础）**：复用知止现有 `MindstopIntentionActivity`（意图确认+关键词复制）、`MindstopSessionOverlay`、`RewardEngine` 与 `mindstop_db`，参考 `Ivy-Apps/ivy-wallet` 的分类与本地预算模型独立实现。
4. **第四阶段（价格分析与决策支持）**：基于 `mablanco/pricegrab`（MIT）的精确单位价格换算思路（`BigDecimal` + 维度校验），新增分位数分析（P25/P50/P75 显式插值算法）、3-2-1 停止规则与贵重商品分阶段研究模式。

> 所有实际移植或借鉴的算法与源码文件均在对应代码头注释及根目录 `THIRD_PARTY_NOTICES.md` 中保留原始作者、仓库地址与许可证声明。
