# 知止 Mindstop 交接文件（给下一个 Agent）

更新：2026-11-26，完成 **v0.3 扩展全部四个阶段（夜间收尾 + 晨间启动 + 购物决策基础 + 价格分析与决策支持，DB v9，124 项知止单元测试全通过）**。本文件是本任务唯一的交接档，有实质变化时原地更新，不另建副本。

## 0. 接手先做

1. 读本文件，再读 `docs/mindstop/SPEC-V0.3-EXPANSION.md`、`docs/mindstop/OSS_REUSE_AUDIT.md`、`docs/mindstop/V03-PHASE4-REVIEW.md` 和仓库根 `AGENTS.md`。
2. 核对现状：`git -C <仓库> status --short --branch` 应为分支 `mindstop`、工作区干净（仅允许保留未跟踪的 `docs/mindstop/知止-UI原型.html` 与 `.agent-trash/`）。
3. **当前进度**：MVP 0.2（阶段 0–6）与 v0.3 三大新模块四阶段（夜间收尾 DB v7 → 晨间启动 DB v8 → 购物决策基础 DB v9 → 价格分析与决策支持 DB v9）均已开发完毕并在本地提交（从未推送远端）。下一步：用户重新连接 Redmi K80 USB 后同步安装最新 `app-full-debug.apk`，按 `V03-PHASE4-REVIEW.md` 清单执行真机实测（重点：闹钟 10 秒锁屏试响、淘宝/京东意图与系统分享导入、价格分析与研究计时）。
4. 工作区规范要求：回复用中文，结论放第一句；交付时**明文列出每个改动文件的完整绝对路径**（严禁仅显示短文件名）；不永久删除文件。

## 1. 目标与需求来源

- 任务：基于 Curbox（GPL-3.0）二次开发 Android 自控训练软件「知止 Mindstop」MVP 0.2。
- **完整需求原文**：`docs/mindstop/SPEC-MVP-0.2.md`（用户首条消息原文，逐字保存）。开发顺序见其 §20，验收清单见 §21，最终交付物见文末。以原文为准，本文件只是摘要。
- 交付要求要点：可安装 Debug APK、完整源码、构建安装说明、CSV 题库模板、测试报告、已知限制、开源许可证与第三方资源声明；**未经真机验证的功能必须明确标记**；不擅自扩大范围。

## 2. 用户已定的决定

| 决定 | 内容 |
|---|---|
| 名称与包名 | 知止 Mindstop，`applicationId = app.mindstop.android`（debug 加 `.debug` 后缀）；`namespace` 保留 `neth.iecal.curbox` 以便合并上游 |
| 位置 | `/Users/raelon/Drives/D-DevWorkspace/01-活跃项目/08-系统工具与基础设施/mindstop-android` |
| 测试手机 | Redmi K80（`24117RK2CC`，Android 16 / SDK 36，HyperOS `OS3.0.309.0.WOKCNXM`），USB 调试（注意机主开启了 GKD 无障碍服务，adb 操作 `enabled_accessibility_services` 时**必须保留 GKD**） |
| 工作节奏 | 用户批准了分阶段计划：**每个阶段做完停下来，交审查报告，等用户说「继续」再进入下一阶段** |
| 依赖镜像 | 只在本项目 `settings.gradle.kts` 配腾讯 Maven 镜像，不改全局配置 |
| 文案语言 | 用户界面字符串同时写 `values/strings.xml`（英文）和 `values-zh-rCN/strings.xml`（中文），阶段 1 起一直如此（仓库 AGENTS.md 默认只写英文，此项为本项目约定）；面向用户的文字不用连字符和破折号 |
| 推送 | 分支 `mindstop` **从未推送**。没有 origin，只有 `upstream` 指向原仓库。推送或建远端前必须先问用户 |

## 3. 环境与命令

- 每条 gradle 命令前：`export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`；SDK 在 `$HOME/Library/Android/sdk`（只有 platforms、build-tools、platform-tools、cmdline-tools，**没有 emulator 和系统镜像**）。
- 全量构建与测试（日志放 `~/.cache/mindstop-dl/`）：
  ```bash
  ./gradlew --no-configuration-cache --continue assembleFdroidDebug assembleFullDebug assemblePlaystoreDebug testFullDebugUnitTest > ~/.cache/mindstop-dl/build.log 2>&1
  ```
- 只跑 Mindstop 测试：`./gradlew --no-configuration-cache testFullDebugUnitTest --tests 'app.mindstop.*'`。结果看 `app/build/test-results/testFullDebugUnitTest/TEST-app.mindstop.*.xml`（构建很快时要核对 XML 时间戳，确认确实重跑了）。
- **已知上游失败，忽略**：`ScriptLanguageTest.matchesRegexSupportsCommonFlags`（未改动的基线上同样失败），所以全量命令退出码是 1 属正常。
- APK 位置：`app/build/outputs/apk/full/debug/app-full-debug.apk`、`playstore/debug/app-playstore-debug.apk`、`fdroid/debug/app-fdroid-universal-release-unsigned.apk`（fdroid 名字怪是上游配置，不是错误）。
- 真机：`adb devices` 确认后用 `./gradlew installAndGrantAccessibilityFullDebug`。下载或安装任何东西前先加载 `fast-download` 技能（这台机器连境外很慢）。
- Room 迁移核对方法：构建后打开 `app/build/generated/ksp/fullDebug/java/app/mindstop/data/MindstopDatabase_Impl.java`，把其中 `createAllTables` 的 SQL 与 `MindstopDatabase.kt` 迁移里的 SQL 逐字比对（阶段 2、3 都这样做过，全部一致）。

## 4. 代码结构（Mindstop 新增部分）

全部在 `app/src/main/java/app/mindstop/`，上游代码基本不动：

- `core/`：纯 Kotlin，无 Android 依赖，全部有 JVM 测试（`MindstopCoreTest.kt`、`MindstopChallengeTest.kt`、`MindstopIntentionTest.kt`、`MindstopRewardTest.kt`、`MindstopPresetGroupsTest.kt`、`MindstopStage5Test.kt`、`DailyQuotesTest.kt`）。
  - `Focus.kt` / `Bedtime.kt` / `Reflection.kt`：阶段 5 核心引擎，覆盖弹性专注（真实暂停、50%/80%/100% 中断提醒、单次会话一次性提前返回鼓励）、睡前规则（跨午夜窗口、15m/5m 收尾提醒、预留 `WakeupPlan`）及轻量复盘编解码。
  - `PresetGroups.kt`：按 6 大沉迷场景（短视频与视频流、社交社区、手游、网文漫画资讯、购物直播、按需手动勾选的即时通信）内置国内外软件目录 + Android `ApplicationInfo.category` 双引擎扫描器 `PresetGroupScanner`；自动避开支付/办公/导航等必要工具。
  - `Intention.kt`：意图目的 `IntentPurpose`、冷静等待 `CalmWait`、悬浮倒计时阶段 `SessionCountdown`、设置 `IntentionSettings`、会话意图 `SessionIntent` 与门禁 `IntentionGate`（测试在 `MindstopIntentionTest.kt`）。
  - `DayKeys`（本地日切分）、`TrustedClockModel`（防改时间）、`RuleEngine`（规则、授权、冷静 `QuotaCooldown`）、`ForegroundAccountant`（前台片段与会话，10 分钟间隔开新会话，`forceNewSession()`）、`ReminderPlanner`（额度提醒阈值、计划提醒节点 `planNodes`/`planReminder`）、`SeededRandom`（SplitMix64）、`MathGenerator`、`QuestionBank`（题型、CSV 解析）、`Challenge`（配置、进度状态机、出题）。
- `data/`：独立 Room 库 `mindstop_db`，**当前 v9**，显式迁移 1→2 至 8→9（v9 新增 `shopping_tasks`、`product_candidates`、`purchase_backlog`、`expense_records` 四表及索引），禁止 destructive fallback。共 19 张业务表。主进程与服务进程共用，开了多实例失效通知。
- `service/`：`MindstopEngine`（挂在 `neth.iecal.curbox.services.AppBlockerService` 的 setup / onEvent / onDestroy，5 秒 tick，每 12 次 tick 落库，内置到期奖励自动结算、重入撤销+会话恢复、独立学习模式不扣娱乐额度与切娱乐保时长、弹性专注自动中断/完成奖励、睡前收尾提醒与睡前拦截）、`QuotaRepository`、`ChallengeRepository`、`RewardRepository`、`Stage5Repository`、`MindstopReminderOverlay`、`MindstopSessionOverlay`。引擎协程作用域带 `CoroutineExceptionHandler`，异常记 CrashLogger 后吞掉。
- `ui/`：已重构为**知止专属 4 Tab 底部导航**（`MindstopTodayFragment` 今日、`MindstopRulesFragment` 规则、`MindstopGrowthFragment` 成长占位、`MindstopSettingsFragment` 设置）与新引导页 `MindstopOnboardingFragment`，共享辅助类 `MindstopUi`（Android 16 edge-to-edge insets、Toolbar、时长格式化、MIUI `op 10021` 检测与权限页跳转）。Curbox 原生不相关功能（深色 Curbox 引导、What's New、ExtensionPromo、DonationPrompt、Anti-Uninstall、Reels/Shorts、UI Hider、Grayscale、网站关键词、Sync 等）已从导航隐藏，保留可复用后台代码与 GPL-3.0 开源致谢。旧 `MindstopHomeFragment.kt` 暂未删除（未再引用）。
- 视觉主题与品牌图标：移除 `Curbox.kt` 中的 `DynamicColors.applyToActivitiesIfAvailable(this)`；浅色模式升级为温润象牙白+淡鼠尾草绿（`#ECF0E6` 页面底、`#F8F9F3` 宣纸白卡片、`#E1EADB` 每日寄语卡与底栏、`#1F5A40` 深森林绿主色、`#8A6228` 银杏金点缀），暗色保持深林绿提亮基调（`#111C16` 底、`#18261F` 卡片、`#7CDAA9` 主色、`#E5C97B` 银杏金）；正式应用图标与冷启动开屏动画统一为「沙漏 + 银杏」融合徽章（深林绿沙漏 + 上嫩绿/下金黄银杏叶 + 中央饱满金色银杏种子 + 暖象牙白 `#F9F5E9` 底色），支持浅色/深色/中英文切换、12 条本地轮换每日寄语与水彩海报。
- 资源：`assets/mindstop/builtin_english.csv`（内置 95 道英语题）；布局 `activity_mindstop_*.xml`、`fragment_mindstop_{today,rules,growth,settings,onboarding}.xml`、`item_mindstop_bank_question.xml`。

## 4b. 阶段 2 的关键设计（改动前先理解）

- **意图门禁在每次检查时都跑**，不是只在新会话开始时跑一次：`MindstopEngine.afterVerdict` 调 `IntentionGate.promptNeeded`。本会话没有进行中的意图就弹 NEW，计划用完就弹 EXTEND；切回应用立刻弹，停留不答每 10 秒再弹。
- **会话不因「结束本次使用」而重置**（防刷单次额度）。同一会话内重新写的计划从 `baseUsedMs`（写计划时会话已用的受管时间）算起。
- 计划剩余和额度剩余哪个先到，悬浮倒计时就跟哪个（`SessionCountdown.targetMs`）。计划到点弹续时页，额度到点走阶段 1 的拦截页。
- 冷静等待起点存在 meta `calm_wait`，形如 `起点#会话|模式|意图wall|分钟`。重开同一意图页不会重置等待。
- 意图设置存 meta `intention_settings`（`IntentionSettings.encode/decode`，不是 Gson，不受 R8 影响）。
- 可供阶段 4 使用的事件：`session_exit`（悬浮面板上点目标完成或结束，detail 含 DONE/ENDED）、`intention_left`（意图页选择离开）、`plan_ended`、`intention_extended`、`intention_finished`，以及阶段 1、3 的 `block_shown`、`block_end_use`、`cooldown_started`、`challenge_passed`、`unlock_used`。
- `ForegroundAccountant` 把所有非本应用、非 systemui 的前台都算作「在用」，会话号也跨这些应用延续。规则只统计受管包的时间。所以「新会话」基本只在锁屏或离开 10 分钟以上后才出现，意图门禁按「本会话有没有进行中的意图」判断，不依赖新会话。

## 4c. HyperOS OS3.0 真机关键发现与硬约束（2026-11-26）

1. **后台冻结（Greezer / PowerKeeper）**：HyperOS 忽略 AOSP `deviceidle whitelist`，必须让用户在应用信息设置「省电策略 → 无限制（`noRestrict`）」并开启「自启动」，否则离开前台 3 秒即被内核冻结。
2. **后台弹出界面（`MIUIOP(10021)`）**：从桌面打开受管应用时，HyperOS 严格校验 `op 10021`，未开启会报 `MIUILOG- Permission Denied Activity` 并返回 `START_ABORTED (102)`。今日页预警卡与诊断页已集成 `op 10021` 检测与 MIUI 权限页直达。
3. **不要给意图页/拦截页加 `android:taskAffinity=""`**：实测加空 `taskAffinity` 在 `op 10021` 未开时会强制触发 HyperOS `code=102`；保持默认 affinity，并在 `MindstopIntentionActivity.start()` 用 `moveTaskToBack(true)` + `finish()` 避免露出后方的 `FragmentActivity`。
4. **禁止拦截前先 `pressHome()`**：先回桌面再拉起 `MindstopBlockActivity` 会被 HyperOS 对桌面 `com.miui.home` 拦截（`code=102`）；必须直接拉起全屏 `MindstopBlockActivity`，由拦截页按钮负责 `goHome()`。
5. **拦截前必须同步 `accountant.flush()`**：避免拦截页读库时漏掉最后几秒内存片段导致 `exhausted` 为空（冷静按钮/每日提示缺失）。
6. **真机 adb 勿直接跑 `installAndGrantAccessibilityFullDebug`**：该任务会覆盖 `enabled_accessibility_services`，把机主的 GKD 清掉；须用 `settings put secure` 合并保留 GKD。

## 5. 进度

| 阶段 | 提交 | 状态 | 审查文档 |
|---|---|---|---|
| 0 改名、上游审查、三 flavor 构建 | `eaf2691` | 完成（已于 Redmi K80 真机安装运行） | `docs/mindstop/STAGE0-REVIEW.md` |
| 1 共享额度、监控、强制拦截、诊断 | `88d4e5f` + 真机修复 | 完成（Redmi K80 真机核心链路验证通过） | `STAGE1-REVIEW.md` |
| 2 意图确认与渐进提醒 | `22268dd` + `9afbac6` + 真机修复 | 完成（Redmi K80 真机核心链路验证通过） | `STAGE2-REVIEW.md` |
| 3 数学挑战、英语题库、CSV 导入、冷静期 | `5760f34` + 真机修复 | 完成（Redmi K80 真机核心链路验证通过） | `STAGE3-REVIEW.md` |
| 视觉与架构精简（清新自然绿 + 4 Tab + HyperOS 修复） | `fdb4e1e` | 完成（三 flavor 构建 + 84 单元测 + lint 0 error + K80 实测） | 见各 STAGE-REVIEW.md |
| 4 即时奖励、延迟确认、撤销、简单成长世界 | `bf1a036` | 完成（三 flavor 构建 + 94 单元测 + v5 迁移核对 + lint 0 error） | `STAGE4-REVIEW.md` |
| 智能预设软件组 + 银杏沙漏品牌图标/浅绿主题/水彩开屏 | `55f49e7` / `466d559` / `0e665f7` | 完成（Redmi K80 真机安装确认通过） | — |
| 5 独立学习模式、轻量复盘、弹性专注、睡前限制 | `435ba55` | 完成（Redmi K80 已覆盖安装并验证 DB `v5→v6` 升级无崩溃，细项交互待手测） | `STAGE5-REVIEW.md` |
| 6 基础统计、CSV 导出、最终交付文档整理 | 本轮提交 | 完成（三 flavor 构建 + 108 单元测 + lint 0 error + K80 安装启动） | `STAGE6-REVIEW.md`、`TEST_REPORT.md` |

最近一次验证（2026-11-26，阶段 6 / MVP 0.2 完结）：三 flavor debug APK 全部构建成功；Mindstop 108 个单元测试 0 失败（上游 `ScriptLanguageTest.matchesRegexSupportsCommonFlags` 仍为已知基线失败）；Room v6 迁移在内存 sqlite3 与 Redmi K80 真机均已验证通过；`lintFullDebug` Mindstop 文件（除上游多语言 `MissingTranslation` 外）0 error。未在真机逐项手测的交互细节详见 `docs/mindstop/KNOWN_LIMITATIONS.md`。

## 6. 与需求原文的已知缺口（不要当成已完成）

- **阶段 2**：§5、§6 已补完（2026-11-25）。仍属后续阶段的：学习时间不扣娱乐额度、实际时长与偏离目的记录（阶段 5，§7）；意图设置未接设置变更延迟；把系统时间往后拨可缩短跨次重开的冷静等待。详见 `STAGE2-REVIEW.md` 的已知限制。
- **阶段 0**：五个 `RECEIVER_EXPORTED` 接收器仍用 curbox 风格的 action 名（NodePicker、UiHider、ReelsCountTracker、GrayScaleFilter、MindfulMessage），同机装有 Curbox 时可能互相收到广播，待改名或限制。
- **阶段 1（§3）**：应用选择复用了上游 `SelectAppsActivity`；「手动添加包名」「识别失败诊断」是否满足没有逐项核对。
- **阶段 3**：续时规则改动立即生效，没有接 Curbox 的「设置变更延迟」；内置英语题未经人工二审。详见 `STAGE3-REVIEW.md` 的已知限制。
- 第三方美术资源（Kenney 等）尚未引入；引入前逐个核对许可证，记到 `THIRD_PARTY_NOTICES.md`（还不存在）。`NOTICE.md` 已有。

## 7. 下一步（按顺序）

1. 等用户审阅阶段 5 审查报告 `docs/mindstop/STAGE5-REVIEW.md`。用户回复「继续」后再进入阶段 6，不要自行跨阶段推进。
2. 阶段 6：按 `SPEC-MVP-0.2.md` §17、§20、§21 实现基础统计（日/周娱乐总时长、分组与单应用时长、计划vs实际时长、主动退出与续期/拦截统计、冷静与复盘汇总）、本地数据 CSV 导出、Redmi K80 真机全链路实测（覆盖阶段 4 与阶段 5 未真机验证清单），并整理最终交付物（构建安装说明、CSV 题库模板、测试报告、已知限制、许可证与声明）。

## 8. 容易踩的坑

- 在 Script 工具里写多行文本时，凡含 `${...}`、反引号或三引号的，都放进 `// @input:` 块，否则会被 JS 模板字符串吃掉。用 Edit 时，old_str 以换行结尾，new_str 也要以换行结尾。
- 服务进程里的时间统一用 `MindstopEngine.trustedNow()`；界面进程用 `QuotaRepository.approxTrustedNow()`（不早于服务最后可信时间）。
- 授权只给已耗尽的规则（`RuleEngine.grantsToUnlock`），这是需求「不能自动绕过其他限制」的落点，不要放宽。
- 任何会改表的实体变更都要升 `mindstop_db` 版本并写显式迁移，再按第 3 节核对 SQL。不要动上游的 `curbox_db`（它用 destructive 迁移）。
- 字符串资源里单独的 `%`（如「10%」）在带参数 `getString` 时会抛异常，服务里会崩。要写 `%%`；纯展示、不格式化的文字加 `formatted="false"`。阶段 2 补完时修过一处。
- 跑 lint 后筛 Mindstop 的问题时，**不要按路径里有 mindstop 来筛**，因为仓库目录名就叫 mindstop-android，会把全部问题都算进来。要按 `/app/mindstop/` 或文件名以 `mindstop_` 开头来筛。上游本身有上千条 MissingTranslation 等问题，与本项目无关。
- Room 加列用 `ALTER TABLE ADD COLUMN`，实体字段做成可空、不设 `defaultValue`，这样迁移结果才能通过 Room 的表结构校验。核对方法：除了第 3 节的 SQL 比对，还可以用 sqlite3 把旧建表加 ALTER 跑一遍，再对比 `PRAGMA table_info`（v4 就是这样核对的）。
- `MindstopEngine` 的协程作用域已带异常处理器。新功能放进 `scope.launch` 里就受保护，但主线程 `mainHandler.post` 里的代码仍要自己 try/catch。
- 悬浮窗用 `TYPE_ACCESSIBILITY_OVERLAY`，服务的 Context 没有 Material 主题，Material 组件会要求 Material 主题，有崩溃风险（推断，未实测），所以 `MindstopSessionOverlay` 只用普通 TextView，新悬浮界面照此做。
- 工作区规范要求：每回合先加载 `execution-delivery-gate` 技能；删除文件不能永久删，要移到工作区 `.agent-trash/` 并登记；交付时列出每个改动文件的完整绝对路径。
