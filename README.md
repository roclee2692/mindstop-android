<div align="center">
  <img src="app/src/main/res/drawable-nodpi/ic_mindstop_ginkgo_emblem.png" alt="知止 MindStop 银杏沙漏徽章" width="128" />
  <h1>知止 MindStop</h1>
  <p><strong>享受科技，掌控时间，回归生活。</strong><br/><em>Enjoy technology. Own your time. Live your life.</em></p>

  <p>
    <a href="README_EN.md">English README</a> ·
    <a href="https://github.com/roclee2692/mindstop-android/releases/latest">下载最新安装包 (Releases)</a> ·
    <a href="docs/mindstop/BUILD_AND_INSTALL.md">编译与安装指南</a> ·
    <a href="THIRD_PARTY_NOTICES.md">开源致谢与许可</a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
    <img src="https://img.shields.io/badge/Privacy-100%25%20Offline%20%7C%20Zero%20Internet-2E7D32?style=flat-square" alt="100% Offline" />
    <img src="https://img.shields.io/badge/License-GNU%20GPL%20v3.0-blue?style=flat-square" alt="GPL-3.0" />
    <img src="https://img.shields.io/badge/Version-v0.3.0-orange?style=flat-square" alt="v0.3.0" />
  </p>
</div>

---

## 简介

**知止 MindStop** 是一款**本地优先、零联网权限、永久开源免费无广告**的 Android 自控训练与数字健康应用。

与单纯“强硬锁机”的戒手机软件不同，知止的设计理念是**帮助你学会管理自己，而不是替你决定如何生活**：
- 不把休息和娱乐当成错误，而是通过**启动前意图确认、共享时间额度、渐进提醒、温和正向激励**让你带着目的打开、带着满足离开；
- 贯通一天中四个最容易失控的场景：**日间防刷屏与专注、夜间渐进收尾、晨间醒后 15 分钟防回床启动、以及理性购物比价与冷静清单**；
- **不申请 `INTERNET` 网络权限**，所有规则、学习目标、题库、作息、购物记录与行为数据 100% 保留在本机。

---

## 核心功能（v0.3.0）

### 1. 日间应用管控与自控训练
- **共享额度与智能预设分组**：支持按应用组/单应用/全局共享设置**每日限额**与**单次限额**；内置一键扫描国内外常见短视频、社区、游戏、网文、购物应用并自动分组。
- **启动意图确认与冷静等待**：打开受管应用前询问本次目的（学习 / 娱乐 / 办事）与计划时长，配合可调冷静倒计时与悬浮目标胶囊，打破“无意识点开”。
- **独立学习模式与弹性专注**：学习模式使用时间单独统计不扣娱乐额度；弹性专注支持合理的查资料/查词典临时中断额度与提前回归鼓励。
- **答题续期与冷静冷却**：额度用完后可选冷静等待或答题续期（内置数学题生成器 + 95 题英语/语境同义词库 + 支持 UTF-8 CSV 导入自定义题库）。
- **银杏成长世界与防套利奖励**：主动停下即种下待确认树苗，验证窗口内未重开则自动落根成林；不清空历史长期成就，不按刷屏时长发奖。
- **行为统计与 CSV 导出**：按今日 / 近 7 天 / 全部历史查看准时退出率、偏离复盘原因，并一键导出 UTF-8 BOM CSV。
- **每日百句离线双语寄语**：内置 100 句中英双语原创与公版古典寄语（《道德经》《大学》《沉思录》《瓦尔登湖》等），按自然日自动切换，整轮约 3 个月不重复，零联网依赖。

### 2. 夜间收尾（Bedtime Wind-down）
- 统一管理**工作日与休息日**睡眠作息计划，清晰区分“计划睡眠机会时长”与实际生理睡眠。
- 6 阶段渐进式夜间收尾提醒（自由休息 → 准备收尾 → 剩余倒计时 → 睡前缓冲 → 睡眠保护），支持独立夜间限制/放行应用清单。
- 夜间默认关闭高强度数学答题，采用低刺激柔和视觉与静默后台确认的夜间银杏安眠奖励。

### 3. 晨间启动与闹钟（Morning Activation）
- 支持单次/重复/工作日与休息日精确闹钟、锁屏响铃、自定义铃声、渐进音量、重启后自动恢复及多种关闭验证（普通 / 数学题 / 步数 / 二维码 / NFC）。
- 针对“关完闹钟又爬回床睡回笼觉”的场景，提供**醒后 15 分钟两阶段启动流程**（5 分钟身体唤醒 + 10 分钟晨间期待小事），搭配状态机跟踪与睡眠不足保护机制（不奖励极端睡眠剥夺早起）。

### 4. 理性购物决策（Intentional Shopping）
- **购物启动 6 意图分流**：打开淘宝、京东、拼多多、闲鱼等购物应用时区分「计划购买 / 查物流 / 找客服 / 处理售后 / 评价 / 随便逛逛」，事务类免打扰，逛街类明确时限。
- **系统分享一键导入候选**：在任意电商 App 点击“分享”即可将商品标题、链接、价格与规格直接导入知止候选池。
- **同规格单位价格与分位数分析**：采用 `BigDecimal` 精确基准单位换算与同维度比较护栏，计算样本 `P25 / P50 / P75` 与均值，并内置 **3-2-1 停止规则**与基于用户自主假设的**搜索时间成本估算**。
- **24 小时冷静待买清单 & 实际消费退款记账**：非计划心动商品一键转入 24 小时冷却池，冷静期后再决定；记录 7 类实际消费与退款明细。

---

## 下载与安装（推荐分享地址）

- **最新安装包下载（GitHub Releases）**：https://github.com/roclee2692/mindstop-android/releases/latest
- **项目源码仓库**：https://github.com/roclee2692/mindstop-android

### 安装与权限配置说明
1. 前往 [Releases 最新版本页](https://github.com/roclee2692/mindstop-android/releases/latest) 下载 APK 安装（支持 Android 8.0 及以上）。
2. 首次启动按引导页开启：
   - **无障碍服务**（必需：识别受管应用与计时）
   - **悬浮窗权限**（推荐：悬浮目标倒计时）
   - **省电策略无限制**（推荐：防止后台被系统清理）
   - **小米 / 红米 / HyperOS 额外开启**：「后台弹出界面」与「自启动」权限；
   - 使用晨间闹钟时，按页面提示授予**精确闹钟权限**。
3. 详细本地构建步骤请参阅 [docs/mindstop/BUILD_AND_INSTALL.md](docs/mindstop/BUILD_AND_INSTALL.md)。

---

## 开源协议、版权与非商用声明

- **开发者**：Raelon Veritas Lee ([@roclee2692](https://github.com/roclee2692))
- **开源协议**：本项目源代码遵循 **GNU General Public License v3.0 (GPL-3.0)** 开源发布，完整条款见 [LICENSE](LICENSE)。任何二次分发 APK 的行为均须同时公开对应完整源代码、保留版权与修改声明。
- **品牌与非商用声明**：
  - 知止 MindStop 官方永久**开源免费、无广告、无内购、无隐私上传**。
  - **「知止 / MindStop」品牌名称、银杏沙漏徽章图标、水彩海报与原创图文素材禁止任何个人或机构未经授权用于商业打包、付费售卖、换皮倒卖或商业宣传。**
- **上游与开源致谢**：
  - 本项目基于 [Curbox Android](https://github.com/curbox-app/curbox-android)（GNU GPL v3.0）二次开发，并参考/复用了 [PriceGrab](https://github.com/mablanco/pricegrab)（MIT）、[Upupup](https://github.com/Purgator/Upupup)、[Fossify Clock](https://github.com/FossifyOrg/Clock)、[NFC Alarm Clock](https://github.com/gabeg805/NFC-Alarm-Clock)、[Loop Habit Tracker](https://github.com/iSoron/uhabits) 等优秀开源项目的设计思路。完整审计与声明见 [NOTICE.md](NOTICE.md) 与 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## 支持与赞赏（自愿）

知止 MindStop 永远免费使用，所有功能无任何付费墙。如果这款工具切实帮你找回了专注、早睡、顺利起床或少走了消费弯路：
- 欢迎给本仓库点一颗 **Star ⭐**，或点击 App「设置 → 关于知止 → 推荐下载给好友」分享给有需要的人；
- 也欢迎通过提交 Issue / PR 一起完善它；
- 如愿自愿请开发者喝杯咖啡以支持持续维护，欢迎在 [GitHub 仓库主页](https://github.com/roclee2692/mindstop-android) 联系或赞赏（后续赞赏入口将同步更新在仓库首页）。

---

## 搜索关键词（Keywords）

知止, MindStop, 安卓防沉迷, 戒手机软件, 屏幕时间管理, 应用时间限制, 专注软件, 番茄钟替代, 睡前防熬夜, 夜间收尾, 起床闹钟, 防回笼觉, 晨间启动, 理性购物, 单位价格比价, 消费记账, 自律训练, 本地优先, 零联网权限, 开源安卓应用, Digital Wellbeing, Screen Time, App Blocker, Mindful Phone Use, Bedtime Wind-down, Morning Alarm, Intentional Shopping, Unit Price Comparison, Offline Android App, Open Source GPLv3
