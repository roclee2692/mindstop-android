# v0.3 第三阶段审查：购物决策基础功能（Intentional Shopping Basics）

日期：2026-11-26。分支 `mindstop`，基于提交 `7704b96`。范围对应 `docs/mindstop/SPEC-V0.3-EXPANSION.md` §15–§19、§24–§26（购物应用 6 类启动意图、购买意图确认与决策分级建议、商品候选池与 Android 分享导入、24 小时新增需求缓冲池、轻量 7 分类实际支出预算与退货退款、购物成长奖励）。

## 本阶段交付

### 1. 纯 Kotlin 购物领域核心（`app.mindstop.core.Shopping.kt`）

- **6 类购物启动意图（`ShoppingIntentType`）**：`PLANNED_BUY`（购买计划商品）、`LOGISTICS`（查物流）、`CUSTOMER_SERVICE`（联系客服）、`AFTER_SALES`（处理售后）、`REVIEW`（评价商品）、`BROWSE`（随便逛逛）。事务类（物流/客服/售后/评价）免填预算并走快速通道。
- **透明决策分级建议（`DecisionTierAdvisor`）**：按预算、月度自由消费预算占比、安全性/兼容性敏感度及是否长期耐用品，透明给出 4 档初始主动搜索时长建议（5–10m / 10–20m / 20–40m / 贵重耐用品分多次每次 30–45m）。
- **精确货币整数分与解析器（`MoneyFormat`）**：全部金额使用 `Long` 分与 `BigDecimal` 换算，杜绝浮点误差。
- **电商分享文本解析（`ShareLinkParser`）**：从淘宝、京东、拼多多、闲鱼、浏览器分享文本中提取纯净商品标题与 URL。
- **轻量消费预算计算（`BudgetCalculator`）**：7 大分类（餐饮、日用品、衣物、学习及工作用品、耐用品、兴趣娱乐、其他），支持部分/全额退货退款扣减；**候选商品价格绝不自动计入实际支出**。

### 2. Room DB v9 显式迁移与仓储

- **`MindstopDatabase.kt`（v8 → v9，`MIGRATION_8_9`）**：新增 `shopping_tasks`、`product_candidates`、`purchase_backlog`、`expense_records` 四张表及对应索引（同步预留了第四阶段所需的 `comparisonGroupKey`、`quantityText`、`unitCode`、`unresolvedQuestions`、`decisionDateKey`、`cooldownHours` 字段，第四阶段无需再次升级数据库版本）。
- **`ShoppingRepository.kt`**：封装购物应用识别（复用 `PresetGroupScanner.SHOPPING_PACKAGES` 与 `preset_shopping` 规则）、任务、候选池、24h 冷却缓冲池与实际支出管理。
- **`RewardRepository.kt`**：新增 `RewardTrigger.SHOPPING_INTENTIONAL`（`+10` 能量，按 `shop_task:$taskId` 防刷）与 `RewardTrigger.SHOPPING_BACKLOG`（`+6` 能量，每日最多 2 次防刷），买到真正需要的商品与理性暂存非紧急需求同样获得成长世界奖励。

### 3. 意图确认复用、Android 分享导入与购物中心 UI

- **`MindstopIntentionActivity.kt`**：识别淘宝/京东/拼多多/闲鱼等购物应用后自动切换为 6 类购物意图选择；选「购买计划商品」可填商品名、预算、必要条件与一键复制搜索词，自动创建购买任务并让悬浮胶囊持续显示购物目标与倒计时。
- **`MindstopShareImportActivity.kt`**：注册 `ACTION_SEND`（`text/plain`）系统分享入口「保存到知止购物」，支持从电商 App 一键分享导入到「待购缓冲池」或「商品候选池」。
- **`MindstopShoppingActivity.kt`**：4 分组清晰视图（购买任务 / 候选池 / 待购缓冲 / 消费预算），不模仿电商推荐流；入口融入「今日」与「设置」页，保持 4 个底部 Tab。

## 验证结果

- **三风味构建**：`assembleFdroidDebug`、`assembleFullDebug`、`assemblePlaystoreDebug` 全部通过。
- **单元测试**：新增 `MindstopShoppingTest` 4 项测试，全量知止单元测试 **121/121 PASS（0 失败，0 跳过）**。
- **Room DB v9 迁移校验**：内存 `sqlite3` `v8 → v9` 全部 19 张表与索引 100% 一致。
- **Lint 与文案**：`lintFullDebug` 知止文件 0 Error；中英文字符串 100% 对齐且零连字符/破折号。

## 未在真机验证项

- 当前手机未连接 USB，尚未在 Redmi K80 真机验证 `v8 → v9` 升级、打开淘宝/京东触发 6 类购物意图弹窗、从淘宝/京东通过系统分享「保存到知止购物」导入链接。
