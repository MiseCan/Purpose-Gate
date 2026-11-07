# PurposeGate 与 Miuix Nav 对比及迁移

2026-11-06。参考信息收录实际依赖，固定使用 `top.yukonga.miuix.kmp:miuix-nav-android:0.9.4`。保留 Kotlin、Compose、MD3E 和 ViewModel/StateFlow。

| 项目 | 上一版 PurposeGate | 本次实际 Miuix Nav |
| --- | --- | --- |
| 导航容器 | Navigation Compose NavHost | NavDisplay + NavBackStack |
| 普通切页 | 自定义仿 Miuix 分层动画 | MiuixDefault 内置动画 |
| 页面内横滑 | 未接入 Miuix 识别器 | 启用 LeftToRight，RTL 镜像 |
| 预测返回 | NavHost 控制动画时间轴 | 与页面内横滑共用连续位移进度 |
| 跟手 | 由 NavHost 动画映射 | 页面位移线性跟随进度 |
| 松手与取消 | NavHost 处理 | Miuix 根据进度和速度续接、回弹 |
| 横向控件 | Compose 默认仲裁 | 子控件先处理，导航识别器随后判断 |
| 圆角与遮罩 | 应用自行绘制近似值 | 组件效果层与系统圆角 API |
| 页面身份与恢复 | NavController | 稳定 Route ID，rememberSaveable 保存返回栈 |
| 输入 | 未启用整页锁 | blockInputDuringTransition=false |

普通转场默认 500ms，Miuix motion 的缓动参数 response=0.8、damping=0.95；交互松手使用组件内建弹簧（临界阻尼、stiffness=146），不另写固定等待时间。应用只调用公共 API，不复制内部手势算法。

所有子页面均通过同一 NavDisplay；返回按钮、页面横滑、系统返回操作同一条返回栈。看板与专注保持同级，切换不产生返回历史；它们的底栏按钮切换使用 Material 主题淡入淡出，不使用子页面分层滑动，主页面系统返回仍回桌面。规则编辑是 Material 3 模态底部弹窗，保留垂直拖动和返回关闭行为；滑块、列表及文本输入保留其原生控件手势。

Activity Compose 从 1.10.0 升到 1.13.0，接通 Miuix 使用的 NavigationEvent 返回事件接口。依赖要求 JVM 21，已同步 Java/Kotlin 编译目标；最低 Android API 为 31。该库尚处于实验阶段，锁定版本，不跟随快照升级。

## 核对来源

- [Miuix 官方仓库](https://github.com/miuix-kotlin-multiplatform/miuix)：`miuix-nav` 模块，实际阅读 NavDisplay.kt、NavBackStack.kt、NavDisplayEffects.kt、NavTransitions.kt、NavMotion.kt、NavDriver.kt 和 NavSettleEasing.kt。
- 本地官方仓库副本：`D:/PROGRAM/BUILD/Collection-reference-Miuix`。源码用于解释行为，最终 API 兼容性以 Maven 0.9.4 实际编译结果为准。
- 信息收录：`D:/PROGRAM/BUILD/Collection` 的 AppNavigation.kt 与 CollectionNavigator.kt，仅作只读参考。
- [AndroidX NavHost 官方实现](https://github.com/androidx/androidx/blob/androidx-main/navigation/navigation-compose/src/commonMain/kotlin/androidx/navigation/compose/NavHost.kt)，用于核对迁移前的预测返回与转场方式。

## 验证范围

针对同级页、子页返回、状态恢复、页面内横滑、预测返回提交/取消，以及动画结束后直接点击进行本地定向验证。手机端厂商手势实际观感仍需用户体验确认。

本次结果：assembleDebug 构建成功；MiuixNavigationTest 共 7 项全部通过，包含右滑调节透明度的真实触摸验证（数值变化且页面未返回）。未在手机上自动执行页面操作。
