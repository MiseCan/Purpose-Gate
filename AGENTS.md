# PurposeGate 项目约定

## 代码结构

```
app/src/main/java/com/purposegate/
  PurposeAccessibilityService.kt   无障碍服务：前台判定、会话、倒计时、专注规则（逻辑核心，文件顶部有状态机说明）
  OverlayUi.kt                     悬浮层里的停顿弹窗 / 悬浮条界面（含各按钮门禁）
  GatePipActivity.kt               画中画悬浮条宿主（系统原生窗口）
  MainActivity.kt                  应用入口
  AppNavigation.kt                Navigation 3 + MD3E 页面动效、固定底栏与预测式返回
  AppNavigationState.kt           同级主页面与子页返回栈
  FadingContent.kt                淡出后移除旧控件树，当前页面直接接收操作
  AppData.kt                       页面枚举、应用清单、权限与偏好访问
  UiComponents.kt                  复用的 Material 3 组件
  FocusPages.kt                    看板 / 专注 / 规则编辑
  UiPages.kt                       选择应用 / 设置 / 权限检查 / 预设管理 / 关于
  PurposeGateStore.kt              SharedPreferences 读写（唯一的数据落盘入口）
  AboutContent.kt                  「关于」页文案（行为说明的唯一来源）
  Theme.kt / MaterialSymbols.kt / Illustrations.kt   M3 令牌、Material Symbols 图标、几何插图
```

改行为时的落点：判定与阈值在 `PurposeAccessibilityService.kt`；界面与门禁在 `OverlayUi.kt`
或 `UiPages.kt`；可配置项与预设列表在 `PurposeGateStore.kt`；行为说明在 `AboutContent.kt`。

## 关于页内容必须同步

应用内「关于」页面的文案由 `app/src/main/java/com/purposegate/AboutContent.kt` 提供，
它是用户可见行为的唯一说明来源。

**任何一次改动，只要影响用户可见的行为，就必须在同一个改动里更新 `AboutContent.kt`**，
否则「关于」页会与实际行为不一致。需要同步的典型情况：

- 触发条件、判定顺序、去抖时长、过滤规则（例如前台应用判定、窗口类型过滤）；
- 交互规则与门禁时长（跳过 / 确认 / 提醒按钮各等多少秒）；
- 会话与宽限规则（多少秒内回到应用恢复状态、超时后如何处理）；
- 悬浮条承载方式（画中画 / 悬浮窗）、入口位置、开关与设置项；
- 权限用途、隐私说明、版本号来源。

同时检查这些位置是否需要一起调整：

- `app/src/main/java/com/purposegate/MainActivity.kt`：设置页、关于页入口、页面列表；
- `app/src/main/java/com/purposegate/PurposeGateStore.kt`：新增配置项与默认值。

## 构建与安装

```powershell
$env:JAVA_HOME='C:\Users\erqi0\.jdks\openjdk-27'
.\gradlew.bat --no-daemon --offline :app:assembleDebug
adb install -r app\build-upgraded\outputs\apk\debug\app-debug.apk
```

不要对已安装的应用使用 `am force-stop`：ColorOS 会因此把无障碍服务从系统设置里取消，
需要重新开启。需要重启服务进程时，先临时关闭无障碍服务、`am kill`，再打开。
