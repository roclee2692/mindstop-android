# 阶段 6 审查：基础数据统计、CSV 导出与 MVP 0.2 交付物整理

日期：2026-11-26。分支 `mindstop`，基于提交 `435ba55`。范围对应需求 `SPEC-MVP-0.2.md` §17（数据记录与实验能力）、§20（阶段 6）、§21（第 20 项）及最终交付物清单。

## 本阶段交付

### 1. 纯 Kotlin 统计引擎与 CSV 导出器（`app.mindstop.core.Stats.kt`）

- **时间维度**：支持 `TODAY`（今日）、`LAST_7_DAYS`（最近 7 天，DST 安全的本地日切分）、`ALL`（全部历史）。
- **核心 5 项行为指标（SPEC §17）**：
  1. **准时主动退出率**：主动退出（`session_exit` + `intention_left`）占总结束次数比例。
  2. **计划平均超时**：所有带明确计划时长的会话中，实际超出计划部分的平均超时时长。
  3. **退出后重开率**：已结算验证窗口中，被撤销奖励（窗口内重开）占（已确认 + 已撤销）比例（自动排除专注奖励，不扭曲数据）。
  4. **学习目标自报完成率**：学习复盘中自报 `COMPLETED` 占比，并明示完成数/总复盘数。
  5. **强制干预依赖度**：强制拦截（`block_shown` + `bedtime_block_shown`）占总结束次数比例；同时展示主动退出、强制拦截、答题续期、冷静与睡前非计划使用的原始次数。
  - **无数据时返回 `null` 并展示「暂无足够数据」**，绝不伪造 `0%` 或 `100%`。
- **最近 7 天趋势、娱乐应用排行与复盘常见偏离原因**：分离娱乐、学习模式（自报）与已完成专注时长，绝不把短期屏幕时长下降直接解读为自律能力提升。
- **RFC-4180 UTF-8 BOM CSV 导出（`StatsCsvExporter`）**：带 `\uFEFF` BOM 确保 Excel / Numbers 直接识别中文；正确转义逗号、双引号与换行；统一导出 `usage_segment`、`intention`、`reward`、`reflection`、`focus_session`、`event` 六类真实本地记录。

### 2. 数据层与界面（数据库保持 v6 无需迁移）

- `Daos.kt`：为 `UsageSegmentDao`、`RewardDao`、`ReflectionDao`、`FocusSessionDao` 新增只读查询 `allRecent(limit)`（无表结构改动）。
- `StatsRepository.kt`：从本机 `mindstop_db` v6 汇总统计与生成 CSV。
- `MindstopStatsActivity.kt` + `activity_mindstop_stats.xml`：完整的数据统计与导出页面，支持三档时间切换、7 天趋势进度条、娱乐应用排行、复盘偏离原因统计，以及基于 Android SAF（`CreateDocument("text/csv")`）的一键导出真实行为数据 CSV。
- 入口接线：「今日」页新增「今日数据」按钮；「设置」页核心模块区新增「数据统计与导出」入口；在 `AndroidManifest.xml` 注册 `MindstopStatsActivity`（`exported="false"`）；补全中英双语字符串且 100% 对齐。

### 3. MVP 0.2 最终交付文档

- `docs/mindstop/BUILD_AND_INSTALL.md`：三风味构建、单测、ADB 安装（保留其他无障碍）及小米/HyperOS 必开权限说明。
- `docs/mindstop/question_bank_template.csv`：可直接导入的 UTF-8 CSV 题库模板与示例题库（含 `word`/`synonym`/`custom`）。
- `docs/mindstop/TEST_REPORT.md`：全阶段 0–6 自动化测试（108 项）、Room 迁移与 SPEC §21 验收总表。
- `docs/mindstop/KNOWN_LIMITATIONS.md`：产品边界、工程限制与诚实标注的未真机逐项复测清单。
- `THIRD_PARTY_NOTICES.md`：GPL-3.0 开源许可证、上游 Curbox 衍生说明、第三方库清单及原创视觉/公版文案声明。

## 验证结果

- **三风味构建**：`assembleFdroidDebug`、`assembleFullDebug`、`assemblePlaystoreDebug` 全部通过。
- **单元测试**：`MindstopStatsTest` 新增 3 项测试全部通过，知止全量 JVM 单元测试 **108/108 PASS（0 失败，0 跳过）**。
- **`lintFullDebug`（知止文件）**：排除上游多语言 `MissingTranslation` 后 **0 Error**。
- **真机安装与启动**：已覆盖安装最新 `app-full-debug.apk` 到 Redmi K80（`ac29e005`），GKD 无障碍服务完整保留，数据库保持 v6 零崩溃启动。
- **未真机逐项手测项**：详见 `docs/mindstop/KNOWN_LIMITATIONS.md` 第 3 节（含 SAF 导出文件落地验证）。
