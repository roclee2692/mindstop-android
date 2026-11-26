# 开源许可证与第三方资源声明（THIRD_PARTY_NOTICES）

## 1. 本项目许可证

**知止 Mindstop** 以 **GNU General Public License v3.0 (GPL-3.0)** 发布，完整许可文本见仓库根目录 `LICENSE`。对外分发 APK 时，必须同步提供对应的完整源代码与修改说明。

## 2. 上游衍生来源

- **Curbox Android**（原名 Digipaws）
  - 仓库：https://github.com/curbox-app/curbox-android
  - 作者：Nethical 及社区贡献者
  - 许可证：GNU GPL v3.0
  - 基线提交：`kt-rewrite` 分支 `ab7b9b2`（2026-11-23）
  - 说明：知止保留了原仓库的版权声明、无障碍服务基础框架与合规开源致谢，并在 `app.mindstop.*` 命名空间下独立构建了共享额度、意图确认、答题挑战、防套利奖励、成长世界、独立学习模式、轻量复盘、弹性专注、睡前规则与数据导出体系。
- **Purgator/Upupup**（晨间闹钟轻量化架构参考）
  - 仓库：https://github.com/Purgator/Upupup
  - 许可证：GNU GPL v3.0
  - 说明：参考其 `AlarmScheduler.kt`（`AlarmManager.setAlarmClock`）、`AlarmReceiver.kt`（重复闹钟即时重调度）、`AlarmService.kt`（前台响铃服务与锁屏全屏意图）架构设计，实现知止 `MindstopAlarmScheduler.kt`、`MindstopAlarmReceiver.kt`、`MindstopAlarmService.kt`。
- **FossifyOrg/Clock**（精确闹钟与锁屏兼容参考）
  - 仓库：https://github.com/FossifyOrg/Clock
  - 许可证：GNU GPL v3.0
  - 说明：参考 Android 12–16 精确闹钟权限检查与锁屏唤醒兼容处理。
- **gabeg805/NFC-Alarm-Clock**（渐进音量与 NFC 校验参考）
  - 仓库：https://github.com/gabeg805/NFC-Alarm-Clock
  - 许可证：GNU GPL v3.0
  - 说明：参考渐进音量爬坡与 NFC 标签读取验证流程。
- **Ivy-Apps/ivy-wallet**（仅消费分类与本地预算设计参考，未引入代码依赖）
  - 仓库：https://github.com/Ivy-Apps/ivy-wallet（已归档停更）
  - 许可证：GNU GPL v3.0
  - 说明：仅参考消费分类与本地月度预算信息结构，未引入任何源码或库依赖。
- **mablanco/pricegrab**（单位价格标准化与维度校验算法复用）
  - 仓库：https://github.com/mablanco/pricegrab
  - 许可证：MIT License（与 GNU GPL v3.0 完全兼容）
  - 说明：参考其 `core/model/QuantityUnit.kt`、`Dimension.kt`、`PriceComparator.kt` 的 `BigDecimal` 精确基准单位换算与同维度比较护栏，实现知止 `app.mindstop.core.PriceAnalysis.kt`（并扩展 P25/P50/P75 R-7 分位数、3-2-1 停止规则与搜索成本估算）。
- **iSoron/uhabits**（非惩罚性长期习惯设计思想参考）
  - 仓库：https://github.com/iSoron/uhabits
  - 许可证：GNU GPL v3.0
  - 说明：参考其避免单次失败清空历史长期成果的评分理念，延续至知止成长世界与夜间/晨间记录。

## 3. 主要第三方开源库

以下运行时依赖沿用自 Gradle 构建配置，各自遵循对应开源许可（主要为 Apache License 2.0）：

- **Kotlin Standard Library & Kotlinx Coroutines** — Apache-2.0
- **AndroidX Core / AppCompat / Fragment / Activity / Lifecycle / RecyclerView / DataStore / SplashScreen** — Apache-2.0
- **AndroidX Room**（本地 SQLite 持久化与显式迁移） — Apache-2.0
- **Material Components for Android** — Apache-2.0
- **ZXing Android Embedded**（二维码扫描能力，沿用上游） — Apache-2.0
- **Shizuku API**（可选高级系统集成，沿用上游） — Apache-2.0
- **JUnit 4**（单元测试） — Eclipse Public License 1.0

## 4. 图标、插画与文案资源声明

- **品牌图标（沙漏 + 银杏）与开屏水彩背景**：为本项目定制生成并本地打包的原创视觉资源，未引入任何第三方闭源或商业版权素材。
- **成长花园画面**：由 `MindstopGardenView.kt` 使用 Android 原生 2D Canvas 实时程序化绘制，无外部位图依赖。
- **激励文案与每日寄语**：
  - 节点提醒与成就反馈采用原创中英文文案，不虚构名人署名。
  - 每日寄语中引用的公版古典文献（如《道德经》《瓦尔登湖》）均核对了公版原文出处（Wikisource）并附带来源标注。
- **内置英语题库（`app/src/main/assets/mindstop/builtin_english.csv`）**：95 道原创整理的常见英文词汇与语境同义题，随本仓库以 GPL-3.0 开源发布。
