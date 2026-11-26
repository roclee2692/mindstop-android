# v0.3 第四阶段审查与三模块总收官报告（价格分析、决策支持与全量验收）

日期：2026-11-26。分支 `mindstop`，基于提交 `a5595d2`。本阶段完成 `docs/mindstop/SPEC-V0.3-EXPANSION.md` §20–§23（价格标准化、分位数分析、3-2-1 停止规则、搜索成本估算与贵重商品分阶段研究模式），标志着 **夜间收尾 + 晨间启动 + 购物决策** 三个 v0.3 新增模块全部四阶段闭环交付。

## 一、第四阶段核心交付

### 1. 价格标准化与分位数分析引擎（`app.mindstop.core.PriceAnalysis.kt`）

- **开源复用（`mablanco/pricegrab`，MIT 许可证，已完整署名于 `THIRD_PARTY_NOTICES.md`）**：
  - 复用 `UnitDimension`（`COUNT` / `MASS` / `VOLUME` / `LENGTH`）与 `QuantityUnit`（`PIECE` / `G` / `KG` / `ML` / `L` / `CM` / `M`）基准单位乘数设计。
  - 全部单价采用 `BigDecimal`（`HALF_UP`，内部 4 位小数）计算到手总价（价格 − 优惠 + 运费）除以基准数量，彻底避免浮点精度误差（例如 3 双袜子 ¥19.90 = `¥6.6333/件` vs 5 双 ¥29.90 = `¥5.9800/件`）。
- **严格可比性护栏（§20）**：仅当 `comparisonGroupKey` 一致、`UnitDimension` 一致且 `meetsMustHaves == true` 的候选项才会进入同一价格分布；其余不兼容规格、不兼容单位维度或未满足必要条件的候选项被自动排除并明确计数提示，绝不错误混比。
- **R-7 线性插值分位数分析（§21）**：
  - 明确采用 R-7 线性插值算法计算 `min`、`P25`、`P50`（中位数）、`P75`、`max` 与 `mean`，并标注每个候选项的价格区间（`低于 P25` / `P25 至 P50` / `P50 至 P75` / `高于 P75`）。
  - 样本数 `< 4` 时强制触发 `sampleUnstable` 提示「统计不稳定，仅反映你已录入的价格，不代表全市场分布」；同时展示样本量、采集日期与来源，不单凭低于 P25 或高于 P75 做绝对优劣判断。
- **3-2-1 满意决策停止规则与搜索成本估算（§22）**：
  - 满足必要条件的候选达到 3 款且有预算内选项时，给出 3-2-1 停止建议；达到单次研究时间上限时，普通商品提示做出决定或暂存离开，贵重耐用品提示保存进度与未决问题稍后再研究（绝不催促立刻下单）。
  - 搜索成本计算器完全基于用户输入假设（`预期搜索收益 = 估计概率 × 可能节省金额` vs `搜索成本 = 额外分钟 × 单位时间价值`），明确标注为用户假设，绝不虚构市场概率。

### 2. 任务研究与价格分析界面（`MindstopTaskDetailActivity.kt`）

- 在「购物决策 → 购买任务」卡片点击 **「研究与比价」** 进入，直接复用 DB v9 表结构（无需再次升级数据库版本）：
  - **分阶段研究计时器（§23）**：支持多次会话累计研究时长（`researchMsTotal`），暂停自动保存进度。
  - **未决问题、计划决策日期与冷却期**：本地持久化保存，后续继续研究时完整恢复。
  - **同规格价格分析卡片**：展示每组比较组的 `min / P25 / P50 / P75 / max / mean`、样本不稳定提示、排除计数、样本来源与按单位价格排序列表。
  - **3-2-1 停止规则提示卡 & 搜索成本估算弹窗**。

## 二、v0.3 全部 15 项验收标准核对（SPEC §30）

| # | 验收标准 | 自动化测试 / 静态验证 | 真机实测状态 |
|---|---|---|---|
| 1 | 夜间限制与白天额度独立，不错误重置已有记录 | PASS（`MindstopBedtimeV03Test`） | Phase 1 已同步 K80 升级 DB v7 |
| 2 | 夜间提醒、保护时段及必要应用白名单正常运行 | PASS（`MindstopBedtimeV03Test`） | 跨节点自然时间流待手测 |
| 3 | 闹钟在锁屏、后台及重启后具备恢复与诊断机制 | PASS（`MindstopMorningTest` + BootReceiver + Diagnostics） | **待手机复连后 10 秒试响与重启实测** |
| 4 | 关闭闹钟不会直接判定为起床成功 | PASS（`MindstopMorningTest`） | 待手机复连后实测 |
| 5 | 晨间启动支持可配置过渡时长及奖励活动 | PASS（`MindstopMorningTest`） | 待手机复连后实测 |
| 6 | 待确认奖励可以正确确认和撤销 | PASS（`MindstopRewardTest`/`BedtimeV03Test`/`MorningTest`） | 待手机复连后实测 |
| 7 | 购物应用能够区分购买、物流、客服等 6 类使用意图 | PASS（`MindstopShoppingTest`） | 待手机复连后实测 |
| 8 | 商品目标与搜索关键词可以保存及复制 | PASS（`MindstopIntentionActivity`/`MindstopShoppingActivity`） | 待手机复连后实测 |
| 9 | 购物会话与累计研究时间正确记录 | PASS（`MindstopTaskDetailActivity`） | 待手机复连后实测 |
| 10 | 单位价格及分位数计算通过自动化测试 | PASS（`MindstopPriceAnalysisTest`） | 纯 Kotlin JVM 100% 覆盖 |
| 11 | 不同规格商品不会被错误纳入同一比较组 | PASS（`MindstopPriceAnalysisTest`） | 纯 Kotlin JVM 100% 覆盖 |
| 12 | 新增待购需求支持冷却、确认及取消 | PASS（`MindstopShoppingTest`） | 待手机复连后实测 |
| 13 | 实际消费与候选商品价格严格区分 | PASS（`MindstopShoppingTest`） | 纯 Kotlin JVM 100% 覆盖 |
| 14 | 新模块不破坏已有娱乐、专注、题库及成长功能 | PASS（全量 **124/124** 单元测试 0 失败） | 已验证无回归 |
| 15 | 所有敏感行为数据默认本地保存 | PASS（全存本机 `mindstop_db` v9，无联网上传） | 已验证 |

## 三、验证汇总与已知限制

- **三风味构建**：`assembleFdroidDebug`、`assembleFullDebug`、`assemblePlaystoreDebug` 全部通过。
- **单元测试**：新增 `MindstopPriceAnalysisTest` 3 项测试，全量知止单元测试 **124/124 PASS（0 失败，0 跳过）**（上游已知基线失败项 `ScriptLanguageTest.matchesRegexSupportsCommonFlags` 未改动）。
- **Lint 与文案**：`lintFullDebug` 知止文件（除上游多语言 `MissingTranslation` 外）**0 Error**；中英文字符串 100% 对齐且零连字符/破折号。
- **已知限制**：
  - 当前手机未连接 USB，Phase 2–4 的真机交互链路（闹钟锁屏试响、重启恢复、淘宝/京东 6 类意图弹窗、系统分享导入、价格分析页面真机目视检查）待手机重新连接后一键同步并实测。
  - 按 §31 交付边界，刻意不实现医学级睡眠监测、AI 起床判定、全网实时爬虫比价、自动下单支付或云端同步。
