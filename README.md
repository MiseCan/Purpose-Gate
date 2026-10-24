# PurposeGate

一个使用 Kotlin、Jetpack Compose 和 Material 3 Expressive 构建的 Android 本地专注工具。在打开选定应用前先停顿，确认这次的使用目的或时长。

## 功能

- 选择需要守护的应用，在进入时显示停顿界面。
- 使用计时、选择目的，或将两种模式合并；支持自定义快捷预设。
- 配置全局规则；没有独立设置的应用自动继承，也可为每个应用单独设置。
- 规则包括打开前等待、退出后保留时间、离开后的冷却、每日使用额度，以及是否允许跳过。
- 离开应用后立即收起悬浮条，保留期限内返回恢复原来的计时与目的；超过期限结束会话。保留时间默认 30 秒，可设为 0–3600 秒，0 表示退出即结束。
- 悬浮条支持系统画中画或无障碍悬浮窗，可调整悬浮窗大小与透明度。
- 总览展示今日状态，记录页展示守护应用的本地使用记录。
- 总览右上角进入设置，集中管理预设、悬浮条外观、权限和应用说明。
- 动态取色、深浅色主题、Material 3 Expressive 控件与系统字体缩放。

应用不申请联网权限，规则与记录保存在本机。每日使用时长仅统计无障碍服务运行期间的守护应用使用，存在采样误差，并非系统完整屏幕时间。

## 使用

1. 安装应用后选择守护应用。
2. 在系统设置中开启 PurposeGate 无障碍服务。
3. 在“专注 → 专注规则”中设置全局默认，按需为应用建立独立规则。
4. 在总览右上角的设置页检查通知、使用情况访问及后台运行授权。

无障碍服务用于识别前台应用并显示停顿界面。使用情况访问用于应用列表排序及通知中的系统使用记录；通知用于显示服务运行状态。详细行为和限制见应用内“了解 PurposeGate”，其内容由 [AboutContent.kt](app/src/main/java/com/purposegate/AboutContent.kt) 维护。

## 构建

本地已使用 JDK 27、Android SDK 37 构建。Gradle Wrapper 和依赖版本在仓库中固定。

用 Android Studio 打开项目，选择本机安装的 Gradle JDK，并配置 Android SDK。命令行构建前设置 `JAVA_HOME`；SDK 路径可由 Android Studio 写入本机的 `local.properties`，该文件不入库。

```powershell
.\gradlew.bat --no-daemon :app:assembleDebug
```

生成的调试安装包位于：

```text
app/build-upgraded/outputs/apk/debug/app-debug.apk
```

覆盖安装：

```powershell
adb install -r app/build-upgraded/outputs/apk/debug/app-debug.apk
```

调试签名由每台开发机器的 Android 工具生成，仓库不包含本机签名文件。

## 代码

- [PurposeAccessibilityService.kt](app/src/main/java/com/purposegate/PurposeAccessibilityService.kt)：前台识别、计时、会话与规则执行。
- [FocusRules.kt](app/src/main/java/com/purposegate/FocusRules.kt)：规则模型和优先级。
- [PurposeGateStore.kt](app/src/main/java/com/purposegate/PurposeGateStore.kt)：全局规则、应用独立规则与本地记录。
- [FocusPages.kt](app/src/main/java/com/purposegate/FocusPages.kt)、[UiPages.kt](app/src/main/java/com/purposegate/UiPages.kt)：主页面、设置和规则编辑。
- [OverlayUi.kt](app/src/main/java/com/purposegate/OverlayUi.kt)、[GatePipActivity.kt](app/src/main/java/com/purposegate/GatePipActivity.kt)：停顿弹窗与悬浮条。
- [UiComponents.kt](app/src/main/java/com/purposegate/UiComponents.kt)、[Theme.kt](app/src/main/java/com/purposegate/Theme.kt)：共享控件与主题。

设计和交互参考见 [UPGRADE_NOTES.md](UPGRADE_NOTES.md)。目前验证了调试构建和覆盖安装；最新调整未重新进行完整单元测试或真机回归。
