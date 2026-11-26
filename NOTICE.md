# NOTICE

知止 Mindstop 是 [Curbox](https://github.com/curbox-app/curbox-android) 的衍生作品。

- 上游：Curbox（原名 Digipaws），作者 Nethical 及贡献者，许可证 GNU GPL v3.0。
- 基线提交：`kt-rewrite` 分支 `ab7b9b2`（2026-11-23）。
- 本项目同样以 GNU GPL v3.0 发布，完整条款见 [LICENSE](LICENSE)。上游源文件中的版权声明保持不变。
- 对外分发本应用的 APK 时，必须同时提供对应的完整源码。

## 本项目的主要修改

- 应用 ID 改为 `app.mindstop.android`，应用名改为“知止 / Mindstop”，NFC 深链 scheme 改为 `mindstop`。
- 构建配置加入国内 Maven 镜像（仅项目内生效）。
- 后续阶段新增的意图确认、会话额度、挑战、奖励、复盘等模块见 `docs/mindstop/`。

## 第三方资源

第三方代码与美术资源在引入时逐项核对许可证，并记录在 `THIRD_PARTY_NOTICES.md`。
