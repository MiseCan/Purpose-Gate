package com.purposegate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethod
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

private sealed interface OverlayState {
    data class TimerPrompt(val appName: String) : OverlayState
    data class TimerRunning(val remainingSeconds: Long) : OverlayState
    data class TimerFinished(
        val minutes: Int,
        val timeoutCount: Int,
        val continueAtElapsedRealtime: Long,
        val purpose: String? = null
    ) : OverlayState
    data class PurposePrompt(val appName: String) : OverlayState
    data class CombinedPrompt(val appName: String) : OverlayState
    data class CombinedBar(val purpose: String, val remainingSeconds: Long) : OverlayState
    data class PurposeBar(val purpose: String) : OverlayState
    data object RunningBar : OverlayState
    data class FocusBlocked(val title: String, val body: String) : OverlayState
}

/** Include the owner so visually identical prompts from different apps cannot share callbacks. */
private data class OverlayUiTarget(val state: OverlayState?, val owner: String?) {
    val contentKey: Any
        get() = owner to when (state) {
            is OverlayState.TimerRunning, is OverlayState.CombinedBar, is OverlayState.PurposeBar,
            OverlayState.RunningBar -> state.kind()
            else -> state
        }
}

private enum class OverlayKind {
    TIMER_PROMPT,
    TIMER_RUNNING,
    TIMER_FINISHED,
    PURPOSE_PROMPT,
    COMBINED_PROMPT,
    COMBINED_BAR,
    PURPOSE_BAR,
    RUNNING_BAR,
    FOCUS_BLOCKED
}

/** 离开目标应用时保存下来的现场，只在该应用设定的保留期限内用来恢复。 */
private data class PausedSession(
    val packageName: String,
    /** 离开时正在显示的悬浮窗状态；停顿弹窗不是可恢复的会话。 */
    val state: OverlayState,
    val remainingSeconds: Long,
    val timerMinutes: Int,
    val timerTimeoutCount: Int,
    val savedAtElapsedRealtime: Long,
    val expiresAtElapsedRealtime: Long,
    val windowX: Int = 0,
    val windowY: Int = 0
)

private fun OverlayState.kind(): OverlayKind = when (this) {
    is OverlayState.TimerPrompt -> OverlayKind.TIMER_PROMPT
    is OverlayState.TimerRunning -> OverlayKind.TIMER_RUNNING
    is OverlayState.TimerFinished -> OverlayKind.TIMER_FINISHED
    is OverlayState.PurposePrompt -> OverlayKind.PURPOSE_PROMPT
    is OverlayState.CombinedPrompt -> OverlayKind.COMBINED_PROMPT
    is OverlayState.CombinedBar -> OverlayKind.COMBINED_BAR
    is OverlayState.PurposeBar -> OverlayKind.PURPOSE_BAR
    OverlayState.RunningBar -> OverlayKind.RUNNING_BAR
    is OverlayState.FocusBlocked -> OverlayKind.FOCUS_BLOCKED
}

/**
 * PurposeGate 的无障碍服务：整个应用的“大脑”。
 *
 * ## 访问与窗口归属（谁属于谁，别混）
 * - [currentPackage]：当前前台应用，判定用。
 * - [handledPackage]：本次进入前台已经处理过的应用，用来避免同一应用重复弹窗。
 * - [overlayOwnerPackage]：正在显示的悬浮窗属于哪个应用，同时也是这份会话的归属；
 *   不再另存“跳过状态”或“倒计时归属”之类的副本。
 *
 * ## 一次前台切换的判断顺序
 * 1. 事件过滤：监听窗口状态与窗口列表变化；过滤自身悬浮窗事件，主界面算正常目的地、通知栏与状态栏、输入法；
 *    桌面与多任务算一次正常的前台变化。
 * 2. 单次窗口快照 [observeForeground]：只信取得焦点的应用窗口；自身全屏悬浮窗在最上面时焦点不在应用上，
 *    改取层级最高的应用窗口。更高层、位置重叠、刚刚出现但还没拿到焦点的窗口表示切换尚未完成。
 * 3. 离开立即生效：确认换了应用（或切换已经开始）就先收层并按规则保存会话，不等去抖。
 * 4. 进入去抖：普通应用 650 毫秒，桌面/多任务 200 毫秒；切换未完成、目的地改变或读不到窗口都只等待复核，
 *    等待超时后放弃这次候选，绝不拿旧事件包名兜底弹窗。
 * 5. [handleForegroundPackage]：先结束上一次目标访问（按生效规则保存会话），再判断当前应用是否已选、
 *    是否选了显示界面、本次是否已处理过，最后弹窗或恢复会话。
 * 6. 息屏等同离开；解锁时重新确认真实最上层应用窗口，保留期限内回到同一应用则恢复状态。
 *
 * ## 计时与提醒
 * 倒计时由 [timerTick] 每秒推进，只负责数字与到点判断，直接更新同一悬浮窗内容。
 * 到点时：目标应用确实在前台且无待确认切换时才显示“时间到了”；离开时收起悬浮窗，保留期内返回再恢复或提醒。
 * 退出可立即点击；再次使用固定三分钟，第一次超时等待十秒，同一会话每再次超时增加十秒。
 *
 * ## 相关文件
 * OverlayUi.kt：悬浮层里的弹窗与悬浮条界面；ForegroundTracking.kt：前台窗口选择；
 * AppData.kt / UiComponents.kt / UiPages.kt / MainActivity.kt：主界面；PurposeGateStore.kt：配置读写。
 */
class PurposeAccessibilityService : AccessibilityService(), SavedStateRegistryOwner {
    companion object {
        private const val TAG = "PurposeGateA11y"
        // Give Recents/launcher transitions enough time to settle before
        // treating an accessibility event as the real foreground app.
        private const val FOREGROUND_DEBOUNCE_MS = 650L
        // 桌面/多任务事件是“已经切出应用”的强信号，用很短的去抖尽快收起弹窗。
        private const val SWITCHER_DEBOUNCE_MS = 200L
        private const val FOREGROUND_RECHECK_MS = 200L
        // 新窗口出现后的一小段时间内，它上面还没拿到焦点的窗口算“切换中”。
        private const val SWITCH_SETTLE_MS = 2_000L
        // 看到切走迹象后，暂停给被离开的应用重新弹窗，等新窗口就位。
        private const val SWITCH_SUPPRESS_MS = 1_200L
        // 一次进入最多等这么久；超时说明窗口列表一直没给出答案，交给下一轮复核。
        private const val PENDING_MAX_WAIT_MS = 4_000L
        private const val USAGE_LABEL_CACHE_MS = 20_000L
        private const val NO_TIMER = -1L
        private const val OVERLAY_DEFAULT_X = 24
        private const val OVERLAY_DEFAULT_Y = 64

        const val ACTION_REFRESH_NOTIFICATION = "com.purposegate.action.REFRESH_NOTIFICATION"
        const val ACTION_RULES_CHANGED = "com.purposegate.action.RULES_CHANGED"

        /** 桌面应用包名，用来区分“回到桌面/多任务”和“还在目标应用里”。 */
        private val LAUNCHER_PACKAGES = setOf(
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.huawei.android.launcher",
            "com.sec.android.app.launcher",
            "com.oneplus.launcher",
            "com.coloros.launcher",
            "com.vivo.launcher",
            "com.bbk.launcher2",
            "com.hihonor.android.launcher",
            "com.transsion.XOSLauncher",
            "com.nothing.launcher"
        )

        /** 系统级切换窗口与系统对话框：都不算真正的前台应用，也不该被当成“离开”。 */
        private val TRANSIENT_FOREGROUND_PACKAGES = setOf(
            "com.coloros.assistantscreen",
            "com.android.systemui",
            "android",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.intentresolver"
        )
    }

    private val serviceHandler = Handler(Looper.getMainLooper())
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val screenWidthPx by lazy { resources.displayMetrics.widthPixels }
    private val screenHeightPx by lazy { resources.displayMetrics.heightPixels }
    private var overlayView: View? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var overlayIsFullScreen = true
    private var currentPackage: String? = null
    /** 已去抖、等待复核的进入候选；只影响弹窗时机，不参与会话归属。 */
    private var pendingPackage: String? = null
    private var pendingSince = 0L
    /**
     * 已经看到“要切走”的证据（新应用的事件先到、窗口还没就位），
     * 这段时间内不再给被离开的应用重新弹窗。
     */
    private var switchFromPackage: String? = null
    private var switchSignalAt = 0L
    private var lastEventTime = Long.MIN_VALUE
    /** 本次前台访问里已经处理过的目标应用，用来避免同一个应用反复弹窗。 */
    private var handledPackage: String? = null
    /** 按包名保存的限时会话，离开目标应用后回到同一个应用时用来恢复。 */
    private val sessions = mutableMapOf<String, PausedSession>()
    /** 正在显示的悬浮窗即当前会话的唯一归属。 */
    private val activeSessionOwnerPackage: String?
        get() = overlayOwnerPackage
    private val visitPolicy = VisitPolicy()
    private var visitPermitted = false
    private var lastUsageMark = SystemClock.elapsedRealtime()
    private var rulesRevision by mutableStateOf(0)
    private var blockedRemainingSeconds by mutableStateOf(0L)
    private var defaultHomePackage: String? = null
    /** 窗口 id → 包名。窗口活着时包名不会变，事件与根节点各补一次即可。 */
    private val windowPackages = mutableMapOf<Int, String?>()
    /** 窗口 id → 首次出现在窗口列表里的时间，用来识别正在进行的切换。 */
    private val windowFirstSeen = mutableMapOf<Int, Long>()
    private var usageLabelCache: Pair<String, String>? = null
    private var usageLabelCacheAt = 0L

    /** [screenOn] 只给息屏结算用：广播到达时 isInteractive 可能已经是 false。 */
    private fun accountFocusUsage(screenOn: Boolean = isScreenOnNow()) {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lastUsageMark).coerceAtLeast(0L)
        lastUsageMark = now
        val pkg = currentPackage ?: return
        if (visitPermitted && screenOn && !isDeviceLocked() &&
            (state == null || !overlayIsFullScreen) && pkg in PurposeGateStore.selectedPackages(this)) {
            PurposeGateStore.addFocusUsage(this, pkg, elapsed)
        }
    }

    private fun isDeviceLocked(): Boolean =
        (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked

    private val focusPolicyTick = object : Runnable {
        override fun run() {
            reconcileForegroundWindow()
            accountFocusUsage()
            enforceCurrentFocusRule()
            serviceHandler.postDelayed(this, 1000L)
        }
    }

    private fun enforceCurrentFocusRule() {
        if (pendingPackage != null || !isScreenOnNow() || isDeviceLocked()) return
        val pkg = currentPackage ?: return
        if (pkg !in PurposeGateStore.selectedPackages(this)) {
            if (activeSessionOwnerPackage == pkg) clearOverlay()
            visitPermitted = false
            return
        }
        val decision = visitPolicy.decision(pkg, PurposeGateStore.focusRule(this, pkg),
            SystemClock.elapsedRealtime())
        if (decision == FocusDecision.COOLDOWN) {
            showFocusBlock(pkg)
        } else if (state is OverlayState.FocusBlocked) {
            clearOverlay()
            currentPackage = null
            handledPackage = null
            handleForegroundPackage(pkg)
        }
    }

    private fun showFocusBlock(pkg: String) {
        if (!canPresentFor(pkg)) return
        blockedRemainingSeconds = (visitPolicy.cooldownRemaining(pkg, SystemClock.elapsedRealtime()) + 999) / 1000
        if (state is OverlayState.FocusBlocked && overlayOwnerPackage == pkg) return
        accountFocusUsage()
        // 冷却只阻止进入；已有会话按原保留期限继续，活动会话也先保存再遮挡。
        if (activeSessionOwnerPackage == pkg) endTargetVisit(pkg)
        visitPermitted = false
        clearOverlay()
        overlayOwnerPackage = pkg
        setOverlayState(OverlayState.FocusBlocked(
            "留一点间隔。",
            "刚刚结束了一次使用。等冷却结束，再决定是否打开。"), fullScreen = true)
    }

    private fun permitVisit() {
        lastUsageMark = SystemClock.elapsedRealtime()
        visitPermitted = true
    }

    private fun leaveFocusPrompt() {
        PurposeGateStore.recordFocusEvent(this, "walkaways")
        val cooling = state is OverlayState.FocusBlocked
        if (!cooling) overlayOwnerPackage?.let { sessions.remove(it) }
        clearOverlay()
        visitPermitted = false
        if (cooling) {
            cancelForegroundTracking()
            currentPackage = null
            handledPackage = null
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
    /** 超时后的退出只离开应用，原到时提醒按退出后保留期限保存。 */
    private fun leaveExpiredSession() {
        val owner = overlayOwnerPackage ?: return
        if (state !is OverlayState.TimerFinished) return
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) return
        PurposeGateStore.recordFocusEvent(this, "walkaways")
        accountFocusUsage()
        endTargetVisit(owner)
        visitPermitted = false
        cancelForegroundTracking()
        // 即使迅速返回、桌面事件还未完成去抖，也必须重新恢复这份会话。
        currentPackage = null
        handledPackage = null
    }

    private var overlayOwnerPackage: String? by mutableStateOf(null)

    /** 输入法包名缓存：系统设置查询和包管理器查询都不能放在事件回调里反复做。 */
    private var defaultImePackage: String? = null
    private var imePackages: Set<String>? = null
    private var state: OverlayState? by mutableStateOf(null)
    private var timerSeconds = 0L
    private var timerMinutes = 0
    /** 同一会话内累计的超时次数，保留期内切出与返回不重置。 */
    private var timerTimeoutCount = 0

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragPendingX = 0
    private var dragPendingY = 0
    private var dragUpdatePosted = false
    // 拖动诊断：只在真的出现“有连续触摸但窗口位置长时间没更新”时记录一次。
    private var dragMoveCount = 0
    private var dragUpdateCount = 0
    private var dragGapMaxNanos = 0L
    private var dragLastUpdateNanos = 0L
    private var dragEventGapMaxMs = 0L
    private var dragLastEventTime = 0L

    private val dragUpdate = Runnable {
        dragUpdatePosted = false
        val view = overlayView ?: return@Runnable
        val params = overlayLayoutParams ?: return@Runnable
        if (params.x == dragPendingX && params.y == dragPendingY) return@Runnable
        params.x = dragPendingX
        params.y = dragPendingY
        val startedAt = SystemClock.elapsedRealtimeNanos()
        if (dragLastUpdateNanos != 0L) {
            dragGapMaxNanos = maxOf(dragGapMaxNanos, startedAt - dragLastUpdateNanos)
        }
        dragLastUpdateNanos = startedAt
        runCatching { windowManager.updateViewLayout(view, params) }
        dragUpdateCount++
    }

    private val sessionSweep = Runnable { sweepSessions() }

    private var screenReceiverRegistered = false
    private var settingsReceiverRegistered = false
    /** 应用内部的通知和规则更新广播。 */
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REFRESH_NOTIFICATION -> startOngoingNotification()
                ACTION_RULES_CHANGED -> { rulesRevision++; reconcileForegroundWindow(); enforceCurrentFocusRule() }
            }
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_USER_PRESENT -> onScreenUnlocked()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
            }
        }
    }

    private inner class OverlayRoot(context: Context) : FrameLayout(context) {
        init {
            isClickable = true
        }

        // ComposeView consumes touch events first. Intercept the complete
        // floating-bar gesture at the window root so its position can move.
        override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean {
            return !overlayIsFullScreen
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            return if (!overlayIsFullScreen) onOverlayTouch(event) else super.onTouchEvent(event)
        }
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(Bundle())
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        startOngoingNotification()
        serviceHandler.removeCallbacks(notificationRefresh)
        serviceHandler.postDelayed(notificationRefresh, 60_000L)
        enableWindowListAccess()
        refreshInputMethodCache()
        registerScreenStateReceiver()
        registerSettingsReceiver()
        defaultHomePackage = runCatching {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName
        }.getOrNull()
        serviceHandler.removeCallbacks(focusPolicyTick)
        lastUsageMark = SystemClock.elapsedRealtime()
        serviceHandler.postDelayed(focusPolicyTick, 1000L)
    }

    private val notificationRefresh = object : Runnable {
        override fun run() {
            startOngoingNotification()
            serviceHandler.postDelayed(this, 60_000L)
        }
    }

    /** 常驻通知里的今日用量：系统前台事件查询较贵，按应用缓存一小段时间。 */
    private fun currentUsageLabel(): String {
        val pkg = currentPackage ?: return "等待前台应用变化"
        val cached = usageLabelCache
        if (cached != null && cached.first == pkg &&
            SystemClock.elapsedRealtime() - usageLabelCacheAt < USAGE_LABEL_CACHE_MS
        ) {
            return cached.second
        }
        val label = runCatching { computeUsageLabel(pkg) }.getOrElse { fallbackAppName(pkg) }
        usageLabelCache = pkg to label
        usageLabelCacheAt = SystemClock.elapsedRealtime()
        return label
    }

    private fun fallbackAppName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun computeUsageLabel(pkg: String): String {
        val name = fallbackAppName(pkg)
        if (!hasUsageStatsAccess(this)) return "$name · 未授予使用情况访问"
        val now = System.currentTimeMillis()
        val midnight = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val events = (getSystemService(USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager).queryEvents(midnight - 86_400_000L, now)
        val event = android.app.usage.UsageEvents.Event()
        var activeSince: Long? = null
        var total = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != pkg) continue
            when (event.eventType) {
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> if (activeSince == null) activeSince = event.timeStamp
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED -> {
                    activeSince?.let { total += (event.timeStamp - maxOf(it, midnight)).coerceAtLeast(0L) }
                    activeSince = null
                }
            }
        }
        activeSince?.let { total += (now - maxOf(it, midnight)).coerceAtLeast(0L) }
        val minutes = total / 60_000L
        return "$name · 今日 ${minutes / 60}小时${minutes % 60}分钟（系统前台记录）"
    }

    private fun startOngoingNotification() {
        val channelId = "accessibility_running"
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel(channelId, "后台停顿服务", android.app.NotificationManager.IMPORTANCE_LOW).apply {
            description = "无障碍服务运行状态，协助保持后台运行"
            setShowBadge(false)
        })
        val intent = android.app.PendingIntent.getActivity(this, 2720, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val usageLabel = currentUsageLabel()
        val notification = android.app.Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_purposegate)
            .setContentTitle(PurposeGateStore.ongoingTitle(this))
            .setContentText(PurposeGateStore.ongoingText(this) + " · " + usageLabel)
            .setStyle(android.app.Notification.BigTextStyle().bigText(PurposeGateStore.ongoingText(this) + "\n" + usageLabel))
            .setContentIntent(intent).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(android.app.Notification.CATEGORY_SERVICE).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(2720, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(2720, notification)
            }
        }.onFailure { Log.w(TAG, "Foreground notification unavailable", it) }
    }

    private fun registerSettingsReceiver() {
        if (settingsReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_REFRESH_NOTIFICATION)
            addAction(ACTION_RULES_CHANGED)
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(this, settingsReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onSuccess { settingsReceiverRegistered = true }
    }

    private fun unregisterSettingsReceiver() {
        if (!settingsReceiverRegistered) return
        runCatching { unregisterReceiver(settingsReceiver) }
        settingsReceiverRegistered = false
    }

    /** One synchronous accessibility overlay owns the purpose/timer bar. */
    private fun showBar(packageName: String, purpose: String?, hasTimer: Boolean, remainingSeconds: Long) {
        overlayOwnerPackage = packageName
        val seconds = if (hasTimer) remainingSeconds else NO_TIMER
        val nextState = when {
            purpose != null && seconds >= 0L -> OverlayState.CombinedBar(purpose, seconds)
            purpose != null -> OverlayState.PurposeBar(purpose)
            seconds >= 0L -> OverlayState.TimerRunning(seconds)
            else -> return
        }
        setOverlayState(nextState, fullScreen = false)
    }
    private fun showRunningBar(packageName: String) {
        overlayOwnerPackage = packageName
        setOverlayState(OverlayState.RunningBar, fullScreen = false)
    }

    private fun isScreenOnNow(): Boolean =
        (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive == true

    /**
     * XML 里的 canRetrieveWindowContent 只会打开 CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT，
     * getWindows() 还需要 FLAG_RETRIEVE_INTERACTIVE_WINDOWS，所以在这里补上。
     */
    private fun enableWindowListAccess() {
        val info = serviceInfo ?: return
        val flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        val events = info.eventTypes or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (info.flags == flags && info.eventTypes == events) return
        runCatching { serviceInfo = info.apply { this.flags = flags; eventTypes = events } }
            .onFailure { Log.e(TAG, "无法开启窗口列表访问，前台判定会退化", it) }
        // 服务信息由系统异步生效：读回确认一次，失败时留下可查的日志。
        serviceHandler.postDelayed({
            val applied = runCatching { serviceInfo }.getOrNull()
            if (applied == null ||
                applied.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0
            ) {
                Log.e(TAG, "FLAG_RETRIEVE_INTERACTIVE_WINDOWS 未生效，getWindows() 可能为空")
            }
        }, 1_000L)
    }
    override fun onUnbind(intent: Intent?): Boolean {
        accountFocusUsage()
        serviceHandler.removeCallbacks(focusPolicyTick)
        serviceHandler.removeCallbacks(notificationRefresh)
        stopForeground(STOP_FOREGROUND_REMOVE)
        unregisterScreenStateReceiver()
        unregisterSettingsReceiver()
        cancelForegroundTracking()
        serviceHandler.removeCallbacks(sessionSweep)
        sessions.clear()
        clearOverlay()
        visitPermitted = false
        currentPackage = null
        handledPackage = null
        return super.onUnbind(intent)
    }

    private fun registerScreenStateReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, filter)
            }
        }.onSuccess { screenReceiverRegistered = true }
    }

    private fun unregisterScreenStateReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenStateReceiver) }
        screenReceiverRegistered = false
    }

    /** Reset the usage sampling boundary when the display wakes. */
    private fun onScreenOn() {
        lastUsageMark = SystemClock.elapsedRealtime()
    }

    /**
     * 息屏等同于离开目标应用：收起悬浮窗不再压在锁屏上，
     * 但按该应用的规则保留会话，解锁回到同一个应用时可以恢复。
     */
    private fun onScreenOff() {
        // 息屏前的最后一段也算一次使用：先按亮屏结算，再按离开处理。
        cancelForegroundTracking()
        endCurrentForeground(screenOn = true)
    }

    /** 解锁后重新确认真实前台应用，避免息屏期间留下过期状态。 */
    private fun onScreenUnlocked() {
        cancelForegroundTracking()
        currentPackage = null
        handledPackage = null
        refreshInputMethodCache()
        // Unlock is a new foreground observation, never permission to reuse a cached app.
        reconcileForegroundWindow()
    }

    /**
     * 一次前台快照：先把窗口补上包名，再交给 [readForeground] 做纯判定。
     * 读失败返回 null（读不到不等于没有前台应用）。
     */
    private fun observeForeground(): ForegroundReading? = runCatching {
        val applicationWindows = windows.orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val now = SystemClock.elapsedRealtime()
        val liveIds = applicationWindows.mapTo(HashSet()) { it.id }
        windowPackages.keys.retainAll(liveIds)
        windowFirstSeen.keys.retainAll(liveIds)
        val freshIds = HashSet<Int>()
        val snapshots = ArrayList<ForegroundWindow>(applicationWindows.size)
        for (window in applicationWindows) {
            val firstSeen = windowFirstSeen.getOrPut(window.id) { now }
            if (now - firstSeen < SWITCH_SETTLE_MS) freshIds += window.id
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            snapshots += ForegroundWindow(
                packageName = windowPackageName(window),
                layer = window.layer,
                focused = window.isFocused,
                active = window.isActive,
                inPictureInPicture = window.isInPictureInPictureMode,
                windowId = window.id,
                bounds = WindowBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
            )
        }
        readForeground(
            windows = snapshots,
            ignoredPackages = ignoredForegroundPackages,
            freshWindowIds = freshIds,
            // 自己的全屏悬浮窗一定在最上面，但它不会出现在应用窗口列表里：
            // 此时应用拿不到焦点，不能因为“没有焦点窗口”就判成未知。
            ownOverlayCovering = overlayView != null && overlayIsFullScreen && state != null
        )
    }.getOrNull()

    /** 包名优先读根节点，读不到时用事件绑定过的包名；每个窗口只读一次。 */
    private fun windowPackageName(window: AccessibilityWindowInfo): String? {
        if (windowPackages.containsKey(window.id)) return windowPackages[window.id]
        val node = runCatching { window.root }.getOrNull() ?: return null
        val packageName = runCatching { node.packageName?.toString() }.getOrNull()
        runCatching { node.recycle() }
        windowPackages[window.id] = packageName
        return packageName
    }

    private val ignoredForegroundPackages: Set<String>
        get() = buildSet {
            addAll(TRANSIENT_FOREGROUND_PACKAGES)
            imePackages?.let(::addAll)
            defaultImePackage?.let(::add)
        }

    private var windowRecheckScheduled = false
    private val windowRecheck = Runnable {
        windowRecheckScheduled = false
        reconcileForegroundWindow()
    }

    private fun requestWindowRecheck() {
        if (windowRecheckScheduled) return
        windowRecheckScheduled = true
        serviceHandler.postDelayed(windowRecheck, FOREGROUND_RECHECK_MS)
    }

    /**
     * 观察前台，并在确认离开时立刻结束当前访问。
     * 弹窗、授权与复核都经过这里，避免“已经切走还弹窗”。
     */
    private fun observeAndRetire(): ForegroundReading? {
        val reading = observeForeground() ?: return null
        val destination = reading.packageName
        if (reading.settling || (destination != null && destination != currentPackage)) {
            endCurrentForeground()
        }
        return reading
    }

    /** 结束当前前台访问：结算用量、按规则开始冷却、保存会话并立即收层。可重复调用。 */
    private fun endCurrentForeground(screenOn: Boolean = isScreenOnNow()) {
        val previous = currentPackage
        if (previous != null) {
            accountFocusUsage(screenOn)
            if (visitPermitted) {
                visitPolicy.leave(previous, SystemClock.elapsedRealtime(), PurposeGateStore.focusRule(this, previous))
            }
        }
        activeSessionOwnerPackage?.let { endTargetVisit(it) }
        visitPermitted = false
        currentPackage = null
        handledPackage = null
    }

    /** 窗口事件补前台变化；一秒一次的 tick 也会调用，用来修复漏发的事件。 */
    private fun reconcileForegroundWindow() {
        if (!isScreenOnNow() || isDeviceLocked()) {
            if (activeSessionOwnerPackage != null) onScreenOff()
            return
        }
        val reading = observeAndRetire() ?: return
        if (reading.settling) {
            // 切换还没完成：不给旧应用弹窗，等新窗口就位。
            requestWindowRecheck()
            return
        }
        val top = reading.packageName ?: return
        if (top == currentPackage && pendingPackage == null) return
        scheduleForegroundChange(top)
    }

    private fun clearPendingForeground() {
        pendingPackage = null
        pendingSince = 0L
        serviceHandler.removeCallbacks(foregroundChange)
    }

    private fun cancelForegroundTracking() {
        clearPendingForeground()
        windowRecheckScheduled = false
        serviceHandler.removeCallbacks(windowRecheck)
    }

    private fun scheduleForegroundChange(candidate: String) {
        // 同一目的地重复的事件不能反复推迟同一次进入的去抖。
        if (candidate == pendingPackage) return
        pendingPackage = candidate
        pendingSince = SystemClock.elapsedRealtime()
        serviceHandler.removeCallbacks(foregroundChange)
        serviceHandler.postDelayed(foregroundChange, debounceFor(candidate))
    }

    /** 切换还没完成或暂时读不到：继续复核；等太久就放弃这次候选，交给下一轮观察。 */
    private fun waitForForegroundToSettle(candidate: String) {
        val waited = SystemClock.elapsedRealtime() - pendingSince
        if (waited >= PENDING_MAX_WAIT_MS) {
            Log.d(TAG, "放弃等待 $candidate（已等 ${waited}ms）")
            clearPendingForeground()
            requestWindowRecheck()
            return
        }
        serviceHandler.removeCallbacks(foregroundChange)
        serviceHandler.postDelayed(foregroundChange, FOREGROUND_RECHECK_MS)
    }

    /** 看到切走迹象：先压住被离开应用的重新弹窗，避免旧弹窗在切换中重新出现。 */
    private fun noteSwitchAway(from: String?) {
        if (from == null) return
        switchFromPackage = from
        switchSignalAt = SystemClock.elapsedRealtime()
    }

    private fun entryIsSuppressed(packageName: String): Boolean =
        packageName == switchFromPackage &&
            SystemClock.elapsedRealtime() - switchSignalAt < SWITCH_SUPPRESS_MS

    /** 每次展示、每个按钮动作都要有一次新的、肯定在前台的证据。 */
    private fun canPresentFor(owner: String): Boolean {
        if (!isScreenOnNow() || isDeviceLocked()) return false
        val reading = observeAndRetire() ?: return false
        if (reading.settling) {
            requestWindowRecheck()
            return false
        }
        val top = reading.packageName
        if (top == null) {
            // 自身全屏悬浮窗在最上面、窗口列表又读不到应用时，至少别让弹窗点不动。
            return overlayView != null && overlayIsFullScreen && overlayOwnerPackage == owner
        }
        if (top == owner && (pendingPackage == null || pendingPackage == owner)) return true
        if (top != owner) {
            // 被拒绝的第一次进入还没有弹过窗，保持可重试。
            if (activeSessionOwnerPackage == null && currentPackage == owner) {
                currentPackage = null
                handledPackage = null
                visitPermitted = false
            }
            requestWindowRecheck()
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!isScreenOnNow() || isDeviceLocked()) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val relevant = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
                AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or
                AccessibilityEvent.WINDOWS_CHANGE_LAYER or AccessibilityEvent.WINDOWS_CHANGE_PIP
            if (event.windowChanges and relevant == 0) return
            requestWindowRecheck()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString() ?: return
        // 自己的悬浮层不是应用窗口；只有 PurposeGate 主界面本身才算一次正常的前台变化。
        if (eventPackage == packageNameForService() && observeForeground()?.packageName != eventPackage) return
        if (isTransientSystemWindowEvent(event, eventPackage) || isInputMethodEvent(event, eventPackage)) return
        if (event.eventTime < lastEventTime) return
        lastEventTime = event.eventTime

        val eventWindow = runCatching {
            windows.orEmpty().firstOrNull {
                it.id == event.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION
            }
        }.getOrNull()
        if (eventWindow == null) {
            // 新应用的事件可能先于窗口出现在列表里：这只能证明“要切走”，不能立刻入驻。
            // 先压住正要弹窗或被离开的应用的弹窗并复核，不确定时绝不拿事件包名兜底弹窗。
            noteSwitchAway(pendingPackage ?: currentPackage ?: observeForeground()?.packageName)
            clearPendingForeground()
            requestWindowRecheck()
            return
        }
        val rootPackage = runCatching { eventWindow.root?.packageName?.toString() }.getOrNull()
        if (rootPackage != null && rootPackage != eventPackage) return
        // 事件是窗口包名的可靠来源：根节点暂时读不到时也能补上，
        // 正在切入的窗口因此能被下一轮快照识别为“切换中”。
        windowPackages[eventWindow.id] = eventPackage

        val reading = observeAndRetire() ?: return
        if (reading.packageName != null && reading.packageName != eventPackage) {
            // 事件来自一个不是当前前台的应用（旧应用尾随事件，或正在切入的窗口）：
            // 不据此入驻新应用，只压住旧应用的弹窗，交给窗口列表复核。
            noteSwitchAway(reading.packageName)
            requestWindowRecheck()
            return
        }
        if (eventPackage != currentPackage) {
            scheduleForegroundChange(eventPackage)
        }
    }

    private fun debounceFor(packageName: String): Long =
        if (isSwitcherPackage(packageName)) SWITCHER_DEBOUNCE_MS else FOREGROUND_DEBOUNCE_MS

    private fun isTransientSystemWindowEvent(event: AccessibilityEvent, packageName: String): Boolean {
        if (isSwitcherWindowEvent(event, packageName)) return false
        if (packageName in TRANSIENT_FOREGROUND_PACKAGES) return true
        val className = event.className?.toString().orEmpty()
        return className.contains("StatusBar", true) || className.contains("Notification", true) ||
            className.contains("HeadsUp", true) || className == "android.widget.Toast"
    }

    private fun isSwitcherWindowEvent(event: AccessibilityEvent, packageName: String): Boolean {
        if (packageName in LAUNCHER_PACKAGES || packageName == defaultHomePackage) return true
        val className = event.className?.toString().orEmpty()
        return className.endsWith("RecentsActivity") || className.endsWith("RecentTasksActivity") ||
            className.endsWith("OverviewActivity")
    }

    private fun isInputMethodEvent(event: AccessibilityEvent, packageName: String): Boolean {
        if (event.className?.toString()?.contains("InputMethod", true) == true) return true
        if (defaultImePackage == null && imePackages == null) refreshInputMethodCache()
        return defaultImePackage == packageName || imePackages?.contains(packageName) == true
    }

    private fun refreshInputMethodCache() {
        defaultImePackage = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        }.getOrNull()
        imePackages = runCatching {
            packageManager.queryIntentServices(Intent(InputMethod.SERVICE_INTERFACE), 0).mapNotNull { it.serviceInfo?.packageName }.toSet()
        }.getOrNull()
    }

    private val foregroundChange: Runnable = object : Runnable {
        override fun run() {
            val candidate = pendingPackage ?: return
            if (!isScreenOnNow() || isDeviceLocked()) {
                clearPendingForeground()
                return
            }
            val reading = observeAndRetire()
            when {
                reading == null -> waitForForegroundToSettle(candidate)
                reading.settling -> waitForForegroundToSettle(candidate)
                // Unknown is not the previous app: keep waiting rather than showing it.
                reading.packageName == null -> waitForForegroundToSettle(candidate)
                reading.packageName != candidate -> {
                    // 等待期间目的地变了：新目的地拿自己的完整去抖，不复用旧候选的证据。
                    clearPendingForeground()
                    scheduleForegroundChange(reading.packageName)
                }
                entryIsSuppressed(candidate) -> waitForForegroundToSettle(candidate)
                else -> {
                    clearPendingForeground()
                    handleForegroundPackage(candidate)
                }
            }
        }
    }


    private fun isSwitcherPackage(packageName: String): Boolean =
        packageName in LAUNCHER_PACKAGES || packageName == defaultHomePackage

    private fun handleForegroundPackage(packageName: String) {
        if (packageName in PurposeGateStore.selectedPackages(this) && !canPresentFor(packageName)) return
        if (packageName == currentPackage) {
            activeSessionOwnerPackage?.takeIf { it != packageName }?.let { endTargetVisit(it) }
            return
        }

        // 结束上一次访问（结算用量、开始冷却、保存会话并收层）。可重复调用。
        endCurrentForeground()
        Log.d(TAG, "foreground -> $packageName")
        currentPackage = packageName
        startOngoingNotification()

        runCatching {
            val selected = PurposeGateStore.selectedPackages(this)
            val modes = PurposeGateStore.modes(this)
            if (packageName !in selected) {
                // 停在未选中的应用或桌面上：结束上一轮判断，等重新回到目标应用。
                handledPackage = null
                return
            }

            val decision = visitPolicy.decision(packageName, PurposeGateStore.focusRule(this, packageName),
                SystemClock.elapsedRealtime())
            if (decision == FocusDecision.COOLDOWN) {
                handledPackage = packageName
                showFocusBlock(packageName)
                return
            }

            // 保留期限内回到同一个目标应用：恢复上次状态，不再弹窗。
            if (restoreSession(packageName)) {
                visitPermitted = true
                handledPackage = packageName
                return
            }
            if (sessions.containsKey(packageName)) {
                // A temporarily refused restore is not a new session or a new prompt.
                currentPackage = null
                handledPackage = null
                visitPermitted = false
                requestWindowRecheck()
                return
            }

            if (modes.isEmpty()) {
                visitPermitted = true
                handledPackage = packageName
                return
            }
            if (handledPackage == packageName) return
            handledPackage = packageName

            val appName = runCatching {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
            }.getOrDefault(packageName)
            when {
                PurposeGateStore.MODE_TIMER in modes && PurposeGateStore.MODE_PURPOSE in modes ->
                    showPrompt(packageName, OverlayState.CombinedPrompt(appName))
                PurposeGateStore.MODE_TIMER in modes ->
                    showPrompt(packageName, OverlayState.TimerPrompt(appName))
                PurposeGateStore.MODE_PURPOSE in modes ->
                    showPrompt(packageName, OverlayState.PurposePrompt(appName))
            }
        }
    }

    private fun showPrompt(packageName: String, nextState: OverlayState) {
        if (!canPresentFor(packageName)) return
        PurposeGateStore.recordFocusEvent(this, "pauses")
        overlayOwnerPackage = packageName
        setOverlayState(nextState, fullScreen = true)
        if (overlayView == null) {
            // 悬浮窗没能创建：不能把这次进入记成“已处理”，否则会静默放行。
            Log.e(TAG, "悬浮窗创建失败，$packageName 的停顿弹窗没有显示")
            clearOverlay()
            handledPackage = null
            currentPackage = null
        }
    }

    /** 跳过后显示运行悬浮条，离开时按生效规则保留本次访问。 */
    private fun dismissPrompt() {
        val owner = overlayOwnerPackage ?: return
        if (!canPresentFor(owner)) return
        sessions.remove(owner)
        permitVisit()
        handledPackage = owner
        scheduleSessionSweep()
        // 同一个悬浮窗直接从全屏弹窗缩成悬浮条，不销毁重建，避免闪一下。
        showRunningBar(owner)
    }

    /**
     * 目标应用离开前台：把悬浮窗或跳过状态按生效规则保存为限时会话。
     * 悬浮条、全屏弹窗与提醒立即收起，宽限期内仅保存会话并按实际时长计时。
     */
    private fun endTargetVisit(packageName: String) {
        if (activeSessionOwnerPackage != packageName) return
        val currentState = state
        if (currentState is OverlayState.FocusBlocked) {
            // 离开冷却页不删除会话，也不重置原先的保留期限。
            clearOverlay()
            return
        }
        val snapshot = when (currentState) {
            is OverlayState.TimerRunning,
            is OverlayState.CombinedBar,
            is OverlayState.PurposeBar,
            is OverlayState.TimerFinished,
            OverlayState.RunningBar -> currentState
            // 停顿弹窗不是可恢复的会话：离开就当作没进过，回来重新弹。
            else -> null
        }
        val retentionMs = PurposeGateStore.focusRule(this, packageName).sessionRetentionSeconds * 1000L
        if (snapshot != null && retentionMs > 0) {
            val params = overlayLayoutParams
            val now = SystemClock.elapsedRealtime()
            sessions[packageName] = PausedSession(
                packageName = packageName,
                state = snapshot,
                remainingSeconds = timerSeconds,
                timerMinutes = timerMinutes,
                timerTimeoutCount = timerTimeoutCount,
                savedAtElapsedRealtime = now,
                expiresAtElapsedRealtime = now + retentionMs,
                windowX = params?.x ?: OVERLAY_DEFAULT_X,
                windowY = params?.y ?: OVERLAY_DEFAULT_Y
            )
        } else {
            sessions.remove(packageName)
        }
        scheduleSessionSweep()

        // 会话快照已保存：离开后立即收起悬浮条，恢复时按离开时长扣减。
        clearOverlay()
    }

    /** 保留期限内回到目标应用：恢复上次状态，返回 false 表示需要重新弹窗。 */
    private fun restoreSession(packageName: String): Boolean {
        if (!canPresentFor(packageName)) return false
        val session = sessions[packageName] ?: return false
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - session.savedAtElapsedRealtime
        if (now >= session.expiresAtElapsedRealtime) {
            dropSession(packageName)
            return false
        }
        val savedState = session.state
        val remaining = session.remainingSeconds - elapsed / 1000L
        state = savedState
        timerMinutes = session.timerMinutes
        timerTimeoutCount = session.timerTimeoutCount
        when (savedState) {
            is OverlayState.TimerRunning,
            is OverlayState.CombinedBar -> {
                val purpose = (savedState as? OverlayState.CombinedBar)?.purpose
                if (remaining <= 0L) {
                    if (!showTimerFinished(packageName)) {
                        clearOverlay()
                        return false
                    }
                } else {
                    timerSeconds = remaining
                    showBar(packageName, purpose, hasTimer = true, remainingSeconds = remaining)
                    restartTimerTick()
                }
            }
            is OverlayState.PurposeBar -> showBar(
                packageName,
                savedState.purpose,
                hasTimer = false,
                remainingSeconds = NO_TIMER
            )
            OverlayState.RunningBar -> showRunningBar(packageName)
            is OverlayState.TimerFinished -> setOverlayState(savedState, fullScreen = true)
            // 停顿弹窗不是可恢复的会话。
            else -> return false
        }
        overlayOwnerPackage = packageName
        restoreOverlayPosition(session.windowX, session.windowY)
        sessions.remove(packageName)
        scheduleSessionSweep()
        return true
    }

    private fun dropSession(packageName: String) {
        sessions.remove(packageName)
        if (activeSessionOwnerPackage == packageName && state !is OverlayState.FocusBlocked) clearOverlay()
    }

    private fun sweepSessions() {
        serviceHandler.removeCallbacks(sessionSweep)
        val now = SystemClock.elapsedRealtime()
        val expired = sessions.values
            .filter { now >= it.expiresAtElapsedRealtime }
            .map { it.packageName }
        expired.forEach { dropSession(it) }
        scheduleSessionSweep()
    }

    private fun scheduleSessionSweep() {
        serviceHandler.removeCallbacks(sessionSweep)
        val nextDue = sessions.values
            .minOfOrNull { it.expiresAtElapsedRealtime } ?: return
        serviceHandler.postDelayed(sessionSweep, (nextDue - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
    }

    private fun restartTimerTick() {
        serviceHandler.removeCallbacks(timerTick)
        serviceHandler.postDelayed(timerTick, 1000L)
    }

    private fun restoreOverlayPosition(x: Int, y: Int) {
        val view = overlayView ?: return
        val params = overlayLayoutParams ?: return
        if (overlayIsFullScreen) return
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        accountFocusUsage()
        serviceHandler.removeCallbacks(focusPolicyTick)
        serviceHandler.removeCallbacks(notificationRefresh)
        stopForeground(STOP_FOREGROUND_REMOVE)
        unregisterScreenStateReceiver()
        unregisterSettingsReceiver()
        cancelForegroundTracking()
        serviceHandler.removeCallbacks(sessionSweep)
        sessions.clear()
        clearOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun packageNameForService(): String = packageName

    /**
     * 切换悬浮窗状态。窗口已经存在时只换内容、不销毁重建，
     * 所以保留期限内回到应用不会出现“消失一下又出现”。
     */
    private fun setOverlayState(nextState: OverlayState, fullScreen: Boolean) {
        state = nextState
        val view = overlayView
        if (view == null) {
            createOverlayWindow(fullScreen)
            return
        }
        if (overlayIsFullScreen != fullScreen) {
            overlayIsFullScreen = fullScreen
            overlayLayoutParams = overlayParams(fullScreen)
        } else {
            val params = overlayLayoutParams ?: return
            // 透明度放在窗口参数上，由合成层直接处理，Compose 不再需要离屏合成。
            val alpha = if (fullScreen) 1f else PurposeGateStore.overlayAlpha(this)
            if (params.alpha == alpha) return
            params.alpha = alpha
        }
        val params = overlayLayoutParams ?: return
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { clearOverlay() }
    }

    private fun createOverlayWindow(fullScreen: Boolean) {
        overlayIsFullScreen = fullScreen
        val overlayRoot = OverlayRoot(this).apply {
            setViewTreeLifecycleOwner(this@PurposeAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@PurposeAccessibilityService)
        }
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PurposeAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@PurposeAccessibilityService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { OverlayTheme { OverlayContent() } }
        }
        overlayRoot.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        overlayView = overlayRoot
        val params = overlayParams(fullScreen)
        overlayLayoutParams = params
        runCatching {
            windowManager.addView(overlayRoot, params)
        }.onFailure {
            overlayView = null
            overlayLayoutParams = null
            state = null
        }
    }

    private fun overlayParams(fullScreen: Boolean): WindowManager.LayoutParams = WindowManager.LayoutParams(
        if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        (if (fullScreen) WindowManager.LayoutParams.FLAG_DIM_BEHIND
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = if (fullScreen) Gravity.CENTER else Gravity.TOP or Gravity.START
        alpha = if (fullScreen) 1f else PurposeGateStore.overlayAlpha(this@PurposeAccessibilityService)
        if (!fullScreen) {
            x = 24
            y = 64
        }
        if (fullScreen) dimAmount = 0.45f
    }

    private fun onOverlayTouch(event: android.view.MotionEvent): Boolean {
        if (overlayIsFullScreen) return false
        val view = overlayView ?: return false
        val params = overlayLayoutParams ?: return false
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                dragMoveCount = 0
                dragUpdateCount = 0
                dragGapMaxNanos = 0L
                dragLastUpdateNanos = 0L
                dragEventGapMaxMs = 0L
                dragLastEventTime = 0L
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = params.x
                dragStartY = params.y
                dragPendingX = params.x
                dragPendingY = params.y
                view.removeCallbacks(dragUpdate)
                dragUpdatePosted = false
                return true
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                dragMoveCount++
                if (dragLastEventTime != 0L) {
                    dragEventGapMaxMs = maxOf(dragEventGapMaxMs, event.eventTime - dragLastEventTime)
                }
                dragLastEventTime = event.eventTime
                val maxX = (screenWidthPx - view.width).coerceAtLeast(0)
                val maxY = (screenHeightPx - view.height).coerceAtLeast(0)
                dragPendingX = (dragStartX + (event.rawX - dragStartRawX).toInt()).coerceIn(0, maxX)
                dragPendingY = (dragStartY + (event.rawY - dragStartRawY).toInt()).coerceIn(0, maxY)
                if (!dragUpdatePosted) {
                    dragUpdatePosted = true
                    // Align window updates to the display vsync instead of using
                    // a fixed timer, which can cap high-refresh screens and
                    // accumulate jitter during a long drag.
                    view.postOnAnimation(dragUpdate)
                }
                return true
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(dragUpdate)
                dragUpdatePosted = false
                params.x = dragPendingX
                params.y = dragPendingY
                runCatching { windowManager.updateViewLayout(view, params) }
                // 触摸样本一直在来，但窗口位置有超过 100ms 没更新，才是真的卡住。
                if (dragGapMaxNanos > 100_000_000L && dragEventGapMaxMs < 60) {
                    Log.d(
                        TAG,
                        "drag stalled moves=$dragMoveCount updates=$dragUpdateCount " +
                            "maxUpdateGap=${dragGapMaxNanos / 1_000_000}ms " +
                            "maxEventGap=${dragEventGapMaxMs}ms"
                    )
                }
                return true
            }
        }
        return false
    }

    private fun clearOverlay() {
        serviceHandler.removeCallbacks(timerTick)
        overlayView?.removeCallbacks(dragUpdate)
        dragUpdatePosted = false
        timerSeconds = 0
        timerMinutes = 0
        timerTimeoutCount = 0
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
        overlayLayoutParams = null
        overlayIsFullScreen = true
        state = null
        overlayOwnerPackage = null
    }

    /** 由停顿弹窗进入正式使用：三处入口共用同一套授权、结算与建窗流程。 */
    private fun beginVisit(minutes: Int, purpose: String?, hasTimer: Boolean) {
        val owner = overlayOwnerPackage ?: return
        if (!canPresentFor(owner)) return
        permitVisit()
        clearOverlay()
        overlayOwnerPackage = owner
        timerMinutes = if (hasTimer) minutes else 0
        timerSeconds = if (hasTimer) minutes * 60L else 0L
        showBar(owner, purpose, hasTimer, timerSeconds)
        if (overlayView == null) {
            // 悬浮条没能创建：撤销这次授权，别让用户以为已经在计时。
            Log.e(TAG, "悬浮窗创建失败，$owner 的悬浮条没有显示")
            clearOverlay()
            visitPermitted = false
            currentPackage = null
            handledPackage = null
            return
        }
        if (hasTimer) restartTimerTick()
    }

    private fun startTimer(minutes: Int) = beginVisit(minutes, purpose = null, hasTimer = true)

    private fun startCombined(minutes: Int, purpose: String) = beginVisit(minutes, purpose, hasTimer = true)

    /** 只选了目的：悬浮条只显示目的，没有倒计时。 */
    private fun startPurposeBar(purpose: String) = beginVisit(0, purpose, hasTimer = false)

    /** 每个计时段只记录一次超时；恢复同一到时提醒不重复累计。 */
    private fun showTimerFinished(packageName: String): Boolean {
        if (!canPresentFor(packageName)) return false
        if (state is OverlayState.TimerFinished) return true
        val purpose = (state as? OverlayState.CombinedBar)?.purpose
        timerSeconds = 0L
        timerTimeoutCount += 1
        overlayOwnerPackage = packageName
        setOverlayState(
            OverlayState.TimerFinished(
                minutes = timerMinutes,
                timeoutCount = timerTimeoutCount,
                continueAtElapsedRealtime = SystemClock.elapsedRealtime() + timerTimeoutCount * 10_000L,
                purpose = purpose
            ),
            fullScreen = true
        )
        return true
    }

    /** 追加固定三分钟，沿用本次目的与超时次数，而非开始新的会话。 */
    private fun continueTimerAfterTimeout() {
        val finished = state as? OverlayState.TimerFinished ?: return
        if (SystemClock.elapsedRealtime() < finished.continueAtElapsedRealtime) return
        if (finished.purpose != null) startCombined(3, finished.purpose) else startTimer(3)
        timerTimeoutCount = finished.timeoutCount
    }

    private val timerTick = object : Runnable {
        override fun run() {
            val owner = overlayOwnerPackage ?: return
            if (state !is OverlayState.TimerRunning && state !is OverlayState.CombinedBar) return
            timerSeconds = (timerSeconds - 1L).coerceAtLeast(0L)
            if (timerSeconds == 0L) {
                // Resolve a departure before presenting an expired timer over an unselected app.
                reconcileForegroundWindow()
                if (overlayOwnerPackage != owner) return
                if (currentPackage == owner && pendingPackage == null) {
                    if (showTimerFinished(owner)) return
                }
                // Unknown foreground is not a departure: keep checking this expired timer.
            }
            state = when (val current = state) {
                is OverlayState.CombinedBar -> current.copy(remainingSeconds = timerSeconds)
                is OverlayState.TimerRunning -> current.copy(remainingSeconds = timerSeconds)
                else -> current
            }
            // A pending departure may be cancelled; recheck an expired timer after it settles.
            serviceHandler.postDelayed(this, 1000L)
        }
    }


    @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun OverlayContent() {
        val motion = MaterialTheme.motionScheme
        val effects = motion.defaultEffectsSpec<Float>()
        val spatial = motion.defaultSpatialSpec<Float>()
        val scene = OverlayUiTarget(state, overlayOwnerPackage)
        val transition = androidx.compose.animation.core.updateTransition(scene, label = "overlay")

        val revision = rulesRevision
        transition.AnimatedContent(
            // Countdown values keep their content key; only a new owner or kind changes screens.
            contentKey = { it.contentKey },
            transitionSpec = {
                (fadeIn(effects) + scaleIn(spatial, initialScale = 0.96f)).togetherWith(
                    fadeOut(effects) + scaleOut(spatial, targetScale = 0.96f)
                )
            }
        ) { target ->
            val currentState = target.state
            val promptRule = androidx.compose.runtime.remember(revision, target.owner, currentState?.kind()) {
                PurposeGateStore.focusRule(this@PurposeAccessibilityService, target.owner.orEmpty())
            }
            FadingContent(isCurrent = target.contentKey == scene.contentKey, fadeSpec = effects) {
                // Outgoing lambdas must never act on a different application's new overlay.
                val mayAct = {
                    target.contentKey == OverlayUiTarget(state, overlayOwnerPackage).contentKey &&
                        target.owner?.let { canPresentFor(it) } == true
                }
                val confirmTimer: (Int) -> Unit = { if (mayAct()) startTimer(it) }
                val dismiss = { if (mayAct()) dismissPrompt() }
                val leave = { if (mayAct()) leaveFocusPrompt() }
                when (currentState) {
                    is OverlayState.TimerPrompt -> TimerPromptContent(
                        currentState.appName, confirmTimer, dismiss,
                        promptRule.confirmDelaySeconds, promptRule.skipDelaySeconds, promptRule.allowSkip, leave
                    )
                    is OverlayState.TimerRunning -> TimerBarContent(
                        currentState.remainingSeconds, PurposeGateStore.overlayScale(this@PurposeAccessibilityService)
                    )
                    is OverlayState.TimerFinished -> TimerFinishedContent(
                        minutes = currentState.minutes,
                        continueAtElapsedRealtime = currentState.continueAtElapsedRealtime,
                        onContinue = { if (mayAct()) continueTimerAfterTimeout() },
                        onLeave = { if (mayAct()) leaveExpiredSession() }
                    )
                    is OverlayState.PurposePrompt -> PurposePromptContent(
                        appName = currentState.appName,
                        onPurpose = { if (mayAct()) startPurposeBar(it) },
                        onDismiss = dismiss,
                        confirmDelaySeconds = promptRule.confirmDelaySeconds,
                        skipDelaySeconds = promptRule.skipDelaySeconds,
                        allowSkip = promptRule.allowSkip, onLeave = leave
                    )
                    is OverlayState.CombinedPrompt -> CombinedPromptContent(
                        appName = currentState.appName,
                        onConfirm = { minutes, purpose ->
                            if (mayAct()) {
                                when {
                                    minutes != null && purpose != null -> startCombined(minutes, purpose)
                                    minutes != null -> startTimer(minutes)
                                    purpose != null -> startPurposeBar(purpose)
                                }
                            }
                        },
                        onDismiss = dismiss,
                        confirmDelaySeconds = promptRule.confirmDelaySeconds,
                        skipDelaySeconds = promptRule.skipDelaySeconds,
                        allowSkip = promptRule.allowSkip, onLeave = leave
                    )
                    is OverlayState.CombinedBar -> CombinedBarContent(
                        purpose = currentState.purpose,
                        remainingSeconds = currentState.remainingSeconds,
                        scale = PurposeGateStore.overlayScale(this@PurposeAccessibilityService)
                    )
                    is OverlayState.PurposeBar -> PurposeBarContent(
                        currentState.purpose, PurposeGateStore.overlayScale(this@PurposeAccessibilityService)
                    )
                    OverlayState.RunningBar -> RunningBarContent(
                        PurposeGateStore.overlayScale(this@PurposeAccessibilityService)
                    )
                    is OverlayState.FocusBlocked -> FocusBlockedContent(
                        currentState.title, currentState.body,
                        blockedRemainingSeconds, leave
                    )
                    null -> Unit
                }
            }
        }
    }


    @Composable
    private fun OverlayTheme(content: @Composable () -> Unit) {
        // 悬浮窗使用同一套 M3 设计令牌，但不套用主界面的尺寸压缩：
        // 悬浮窗的大小由用户自己的“大小”设置控制。
        PurposeGateTheme(denseTypography = false, content = content)
    }
}
