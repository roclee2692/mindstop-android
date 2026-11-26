# 知止 Mindstop 构建与安装说明（MVP 0.2）

## 1. 环境要求

- **JDK**：OpenJDK 17（macOS Homebrew 示例：`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`）
- **Android SDK**：`compileSdk 36` / `targetSdk 36` / `minSdk 26`（Android 8.0+）
- **构建镜像**：仓库根目录 `settings.gradle.kts` 已内置腾讯 Maven 镜像，无需改动系统全局 Gradle 配置。

## 2. 编译三风味 Debug APK 与运行单元测试

```bash
cd /path/to/mindstop-android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# 构建 fdroid / full / playstore 三种 Debug 包
./gradlew --no-configuration-cache assembleFdroidDebug assembleFullDebug assemblePlaystoreDebug

# 运行知止全部 JVM 单元测试（共 108 项）
./gradlew --no-configuration-cache testFullDebugUnitTest --tests 'app.mindstop.*'

# 运行 Lint 静态检查
./gradlew --no-configuration-cache lintFullDebug
```

产物位置：
- `app/build/outputs/apk/full/debug/app-full-debug.apk`（推荐日常与真机安装）
- `app/build/outputs/apk/playstore/debug/app-playstore-debug.apk`
- `app/build/outputs/apk/fdroid/debug/app-fdroid-universal-release-unsigned.apk`

## 3. 真机安装与必要权限配置

### 3.1 通过 ADB 安装（推荐）

```bash
adb install -r app/build/outputs/apk/full/debug/app-full-debug.apk
```

> 注意：若手机已启用其他无障碍服务（例如 GKD、李跳跳等），请**不要**运行 `./gradlew installAndGrantAccessibilityFullDebug`，该任务会覆盖系统的 `enabled_accessibility_services`。请在手机系统设置中手动开启「知止 Mindstop」无障碍服务，或通过 `adb shell settings put secure enabled_accessibility_services` 拼接保留原有服务。

### 3.2 小米 / 红米（MIUI / HyperOS）必开权限清单

打开知止后可点击「设置 → 诊断」逐项一键直达：

1. **无障碍服务（必需）**：系统设置 → 更多设置 → 无障碍 → 已下载的应用 → 开启「知止 Mindstop」。
2. **省电策略设为「无限制」（必需）**：应用信息 → 省电策略 → 无限制（否则 HyperOS 会在退到后台数秒内冻结无障碍与定时检查进程）。
3. **开启「自启动」（必需）**：应用信息 → 自启动 → 开启。
4. **开启「后台弹出界面」（必需）**：应用信息 → 权限管理 → 后台弹出界面 → 允许（`MIUIOP 10021`，否则在受管应用上方弹出意图确认页或到期拦截页会被系统静默拦截）。
5. **悬浮窗权限（推荐）**：应用信息 → 权限管理 → 显示悬浮窗 → 允许。

## 4. 自定义 CSV 题库导入

1. 使用本仓库提供的模板 `docs/mindstop/question_bank_template.csv`，用 Excel 或 Numbers 编辑并导出为 **UTF-8 CSV**。
2. 在知止 App 中进入「设置 → 题库 → 导入 CSV」，通过 Android 系统文件选择器选中文件即可完成校验与导入。
