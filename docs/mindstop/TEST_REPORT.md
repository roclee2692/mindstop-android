# 知止 Mindstop MVP 0.2 测试与验收总报告

日期：2026-11-26 · 分支：`mindstop` · 数据库版本：`mindstop_db` v6（无 destructive fallback）。

## 1. 自动化构建与静态检查

| 检查项 | 命令 / 范围 | 结果 |
|---|---|---|
| F-Droid Debug 构建 | `./gradlew assembleFdroidDebug` | PASS |
| Full Debug 构建 | `./gradlew assembleFullDebug` | PASS |
| PlayStore Debug 构建 | `./gradlew assemblePlaystoreDebug` | PASS |
| 知止 JVM 单元测试 | `./gradlew testFullDebugUnitTest --tests 'app.mindstop.*'` | **108/108 PASS（0 失败，0 跳过）** |
| Android Lint（知止文件） | `./gradlew lintFullDebug`（排除上游 9 语言 `MissingTranslation`） | **0 Error** |
| 中英双语字符串核对 | `values/strings.xml` vs `values-zh-rCN/strings.xml` | Key 100% 对齐，0 连字符/破折号 |
| Room 迁移审计 | `v1→v2→v3→v4→v5→v6` SQL 比对 + 内存 `sqlite3` 校验 + 真机 `v5→v6` 升级 | **12 张表 100% 结构与索引一致** |

> 备注：上游基线存在 1 项既有失败测试 `ScriptLanguageTest.matchesRegexSupportsCommonFlags`，属未改动上游问题。

## 2. 知止 JVM 单元测试套件明细（19 个测试类 / 108 项）

- **阶段 1（核心额度与可信时钟）**：`DayKeysTest` (3)、`TrustedClockModelTest` (6)、`RuleEngineTest` (7)、`ForegroundAccountantTest` (6)、`ReminderPlannerTest` (10)
- **阶段 2（意图确认、冷静等待、倒计时）**：`CalmWaitTest` (4)、`IntentionGateTest` (8)、`IntentionSettingsTest` (5)、`PlanNodesTest` (7)、`SessionCountdownTest` (4)
- **阶段 3（答题挑战、CSV 解析、冷静期）**：`MathGeneratorTest` (4)、`CsvQuestionParserTest` (9)、`ChallengeEngineTest` (7)、`CooldownRuleTest` (4)
- **阶段 4（即时奖励、防套利、成长世界）**：`MindstopRewardTest` (10)
- **品牌与智能分组**：`DailyQuotesTest` (2)、`MindstopPresetGroupsTest` (3)
- **阶段 5（弹性专注、睡前跨午夜、轻量复盘）**：`MindstopStage5Test` (6)
- **阶段 6（核心行为指标统计与 RFC-4180 CSV 导出）**：`MindstopStatsTest` (3)

## 3. SPEC §21 验收标准逐项状态

| # | 验收项 | 自动测试 / 静态验证 | 真机实测状态（Redmi K80 Android 16） |
|---|---|---|---|
| 1 | 国内外应用动态发现并统一管理 | PASS（`PresetGroupsTest`） | 已真机验证 |
| 2 | 早中晚分段使用累计每日额度 | PASS（`DayKeysTest`/`RuleEngineTest`） | 已真机验证 |
| 3 | 切换应用及重启不重置已消耗额度 | PASS（`ForegroundAccountantTest`/`TrustedClockModelTest`） | 已真机验证 |
| 4 | 启动前填写使用目的、关键词和计划时长 | PASS（`IntentionGateTest`） | 已真机验证 |
| 5 | 一键复制关键词并在目标应用中粘贴 | PASS | 已真机验证 |
| 6 | 使用过程中重新查看原始使用目标 | PASS（`MindstopSessionOverlay`） | 已真机验证 |
| 7 | 各渐进提醒节点准确触发 | PASS（`ReminderPlannerTest`/`PlanNodesTest`） | 已真机验证 |
| 8 | 到期后实际拦截受监管应用 | PASS（`RuleEngineTest`） | 已真机验证 |
| 9 | 数学题准确，英语题与 CSV 题库可用 | PASS（`MathGeneratorTest`/`CsvQuestionParserTest`） | 已真机验证 |
| 10 | 冷却期间持续限制指定应用 | PASS（`CooldownRuleTest`） | 已真机验证 |
| 11 | 主动退出后立即出现奖励反馈 | PASS（`MindstopRewardTest`） | **待真机交互复测** |
| 12 | 验证窗口内重入撤销待确认奖励 | PASS（`MindstopRewardTest`） | **待真机交互复测** |
| 13 | 验证成功后正常娱乐不撤销历史奖励 | PASS（`MindstopRewardTest`） | **待真机交互复测** |
| 14 | 防反复进出刷奖励或额度漏洞 | PASS（`MindstopRewardTest`/`MindstopStage5Test`） | **待真机交互复测** |
| 15 | 学习与娱乐时间独立记录 | PASS（`MindstopStage5Test`/`MindstopStatsTest`） | DB v6 已真机升级，交互**待真机复测** |
| 16 | 专注期间合理中断及恢复 | PASS（`MindstopStage5Test`） | **待真机交互复测** |
| 17 | 睡前规则正常运行 | PASS（`MindstopStage5Test`） | **待真机交互复测** |
| 18 | 权限异常与后台服务失效提供诊断 | PASS | 已真机验证（含 HyperOS `op 10021`） |
| 19 | 必要通信、系统功能与紧急解除可用 | PASS（即时通信默认不勾选 + 紧急解除 5 分钟） | 已真机验证 |
| 20 | 能够导出真实的行为数据 CSV | PASS（`MindstopStatsTest`） | **待真机 SAF 导出复测** |
