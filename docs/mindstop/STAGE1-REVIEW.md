# 阶段 1 审查：意图与共享额度（MVP 0.2）

日期：2026-11-25。分支 `mindstop`，基于阶段 0 提交 eaf2691。

## 本阶段交付

### 纯 Kotlin 核心 `app.mindstop.core`（无 Android 依赖，全部有 JVM 单元测试）

- `DayKeys.kt`：本地日历日切分（`dayKey`、`startOfDay`、`splitByDay` 按本地午夜切段）。
- `TrustedClockModel.kt`：可信时钟锚点。自动时间开启时信任系统时钟；关闭时忽略手动改时间（单调时钟推进）；重启后时钟不允许回退到最后可信时间之前（2 分钟漂移容差）。boot_count 读不到时降级为 elapsedRealtime 比较。
- `RuleEngine.kt`：规则模型（全局共享 / 应用组 / 单应用，每日限额 + 单次限额），`evaluate` 取剩余最少者为约束规则，`grantsToUnlock` 只为已耗尽的规则生成授权。
- `ForegroundAccountant.kt`：前台切换 → 使用片段。时长来自单调时钟；受管应用之间切换保持同一会话；离开受管应用超过 10 分钟才开新会话；支持服务重启后的会话恢复。

### 持久层 `app.mindstop.data`

- 独立 Room 库 `mindstop_db`（v1，显式迁移路线，不用 destructive fallback），含 rules / usage_segments / grants / events / meta 五张表。
- `enableMultiInstanceInvalidation()`，主进程与 `:app_blocker_service` 进程读写一致。

### 服务侧 `app.mindstop.service`

- `QuotaRepository.kt`：规则、豁免名单、片段、授权、事件、meta 的统一入口；提供块屏与首页用的只读评估。
- `MindstopEngine.kt`：接入 `AppBlockerService`（与其它 feature 同生命周期：onServiceConnected 装配、onAccessibilityEvent 内联轻检查、onDestroy 清理）。窗口变化事件驱动 + 5 秒 tick；每分钟落库一次；锁屏立即结段不计时；到点 `pressHome()` 并拉起块屏；必要应用白名单豁免；时钟事件写 events 表。

### UI

- 新底部导航首 Tab「知止」（`MindstopHomeFragment`）：今日额度进度、规则列表、添加/编辑规则、必要应用编辑、诊断入口。默认落到此 Tab。
- `MindstopRuleEditActivity`：新建/编辑规则（名称、全局共享或指定应用、每日/单次分钟数、启用开关、删除）。应用选择复用 `SelectAppsActivity`。
- `MindstopBlockActivity`：拦截页，显示应用名与约束规则，按钮「结束使用」「紧急解除 5 分钟」（只为已耗尽规则发授权并记录事件）。
- `MindstopDiagnosticsActivity`：无障碍服务、悬浮窗、电池优化、自启动（复用 `OemAutostartIntents`，覆盖小米）检查与直达设置按钮；今日计数汇总；最近 50 条事件流。
- 默认播种：首次打开首页时播种「全局每日共享 60 分钟」规则（仅一次，meta 标记）。

## 验证

- `./gradlew assembleFdroidDebug assembleFullDebug assemblePlaystoreDebug`：三 flavor 全部 BUILD SUCCESSFUL。
- `testFullDebugUnitTest`：216 个测试，1 个失败为上游既有失败（`ScriptLanguageTest.matchesRegexSupportsCommonFlags`，阶段 0 已确认在未改动的基线上同样失败）。
- 新增 `app.mindstop.core` 22 个 JVM 测试全部通过，覆盖：30/60 分钟共享额度跨早午晚扣减、切换应用不重置会话、短间隔保持会话、午夜切段、锁屏不计时、手动改时间被忽略、重启防回拨、最严规则决定拦截、会话授权不跨会话、只为耗尽规则发授权。

## 未在真机验证（无 USB 线、无 Wi-Fi，无法连接设备）

以下项目仅通过编译与单元测试，行为需真机确认：

1. 无障碍事件驱动的前台识别在 HyperOS 上的准确性（`rootInActiveWindow` 回退 `event.packageName`）。
2. 到点拦截的端到端链路：pressHome + 拉起块屏的时序与回退栈行为。
3. 锁屏/解锁（USER_PRESENT）恢复前台识别。
4. 块屏「紧急解除」后重新拉起被拦应用。
5. `boot_count` 在小米设备上是否可读（不可读时自动降级，不影响功能，仅跨启动防回拨精度下降）。
6. 诊断页各项状态读取与小米自启动入口跳转。
7. 多进程 Room 失效通知在真机上的及时性（首页状态 30 秒轮询兜底）。

## 已知限制

- 紧急解除固定 5 分钟，挑战/冷静期机制属后续阶段。
- 会话缺口固定 10 分钟，未做设置项。
- 时钟锚点每分钟才持久化一次，极端崩溃场景最多丢失 1 分钟的可信时间推进。
- 首页会话类规则的“本次已用”在服务未运行时可能滞后一个落库周期（最多 1 分钟）。

---

## 真机实测记录（2026-11-26，Redmi K80 · Android 16 / HyperOS OS3.0）

实测机型：`24117RK2CC`（Redmi K80，Android 16 / SDK 36，HyperOS `OS3.0.309.0.WOKCNXM`），使用 `com.miui.calculator` 与 `com.android.deskclock` 组成测试组验证，全程保留机主既有 GKD 无障碍服务。

### 已真机验证通过

1. **前台识别与补漏**：HyperOS 上偶发漏发或乱序的窗口切换事件，已通过 `MindstopEngine` 增加 350ms `settleCheck` 与 5 秒 tick 活跃窗口对账（`activeWindowPackage()`）兜底，实测切入/切出受管应用与组内跨应用累计准确。
2. **到点强制拦截与 HyperOS 兼容修复**：
   - 发现原逻辑先 `pressHome()` 再 `startActivity(MindstopBlockActivity)` 会被 HyperOS `ActivityStarterImpl` 对 `com.miui.home` 触发 `START_ABORTED (result code=102)`；已改为直接拉起全屏 `MindstopBlockActivity` 覆盖被拦应用，由拦截页「结束使用 / 冷静 / 返回键」负责回桌面，实测拦截稳定生效。
   - 发现原逻辑拉起拦截页前未先将内存中未落库的使用片段 `flush` 到数据库，导致拦截页偶发读到未耗尽的旧总额；已改为在启动 `MindstopBlockActivity` 前同步 `accountant.flush()` 并落库，实测修复生效。
   - 发现服务在未完成 `onServiceConnected()` 前销毁会触发 `MindstopEngine.onDestroy()` 空引用崩溃；已补 `::service.isInitialized` 保护及 `withTimeoutOrNull(1500L)`。
3. **紧急解除 5 分钟**：在拦截页及冷静期内点「紧急解除 5 分钟」均可正常发授权、取消冷静并重新拉起被拦应用，事件流记录 `unlock_used`。
4. **`boot_count` 可读性**：小米真机读取正常（实测 `boot_count=113`），跨重启防回拨无需降级。
5. **诊断页与 HyperOS 后台守护**：
   - 确认 HyperOS 后台冻结（Greezer / PowerKeeper）忽略 AOSP 电池白名单，需用户开启「省电策略 → 无限制」+「自启动」；此外从桌面后台拉起意图页/拦截页受 MIUI `op 10021`（「后台弹出界面」）严格管控。
   - 诊断页已新增 `op 10021` 实时检测与 MIUI 权限管理直达入口，小米机型电池按钮改为跳转应用详情便于设置「无限制」。
6. **手动添加包名**：确认 `SelectAppsActivity` 溢出菜单已支持「添加自定义安卓应用包」。
7. **多进程 Room 与首页展示**：服务进程写入的片段、授权、事件可在主进程「今日」与「诊断」页及时刷新；已修复隔夜陈旧 `sessionId` 在今日页误显示的问题。

### 仍未在真机验证

- 锁屏 / 解锁（`USER_PRESENT`）后前台计时恢复与连续性。
- 手动关闭自动时间并拨改系统时钟的跨重启真机实测（逻辑已由 JVM 单元测试覆盖）。
