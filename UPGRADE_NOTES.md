# PurposeGate 2.0

## 当前功能

- Material 3 Expressive 主题、动态取色、统一控件和动效，支持系统字体缩放与深浅色。
- 总览、专注、记录为同级根页面；返回交由系统处理。
- 总览右上角使用无底色的设置图标，标题保留大标题布局。
- 设置页集中管理了解、时间与目的预设、悬浮条外观、守护状态与权限。
- 专注页的时长和目的开关为两张独立卡片，间距 16dp。
- 全局规则为默认配置；应用可建立独立规则，或重新切回继承全局规则。
- 规则包括停顿 0–60 秒、退出后保留时间 0–3600 秒、冷却 0–3600 秒、每日额度 0–1440 分钟及是否允许跳过。
- 退出后保留时间默认 30 秒，期限内返回恢复计时与目的，超时结束会话；0 表示退出即结束。
- 每日额度优先于冷却和会话；冷却不会因在拦截界面反复切出、返回而延长。
- 规则编辑默认半屏，可上拉展开；返回直接关闭，拖动下拉仍可收回半屏。底部系统导航区域透明。
- 使用实际顶层应用窗口校正无障碍事件来源，过滤输入法和自身悬浮层产生的事件。
- 临时放行、跳过后的防重复静默和晚间提醒均已删除。

## 行为边界

- 每日用量仅统计无障碍服务运行期间、亮屏解锁时的守护应用实际使用，约一秒采样；不补算服务关闭或安装前的使用，不包含全屏停顿等待。
- 跨零点仅计入当天部分；该记录与系统全天使用统计可能不同。
- 倒计时结束沿用到时提醒，可继续相同时长或离开；按钮等待 10 秒后可点击。
- 会话和冷却保存在服务内存中，服务重启后清除；规则和每日记录保存于本地 SharedPreferences。
- 系统或厂商可能限制后台运行，无障碍授权被撤销后需要重新手动开启。
- 功能使用独立实现，没有合并第三方应用源码，也没有新增联网、网站 VPN、家长控制或防卸载能力。

## 设计与交互参考

- [AndroidX Material3 Catalog](https://github.com/androidx/androidx/tree/androidx-main/compose/material3/material3/integration-tests/material3-catalog)
- [Tomato](https://github.com/nsh07/Tomato)
- [Breezy Weather](https://github.com/breezy-weather/breezy-weather)
- [Pause](https://github.com/dtsykunov/pause-app)
- [Nudge](https://github.com/astraedus/nudge)

## 当前验证

- `:app:assembleDebug` 构建通过。
- 已在连接的手机上成功覆盖安装。
- 最新调整未重新运行单元测试或完整真机回归。

用户可见行为说明以应用内 [AboutContent.kt](app/src/main/java/com/purposegate/AboutContent.kt) 为准。
