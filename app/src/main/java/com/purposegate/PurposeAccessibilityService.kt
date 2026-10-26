package com.purposegate

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.ContentTransform
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay

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
    data class FocusBlocked(val reason: String, val title: String, val body: String) : OverlayState
}

/** Include the owner so visually identical prompts from different apps cannot share callbacks. */
private data class OverlayUiTarget(val state: OverlayState?, val owner: String?) {
    val contentKey: Any
        get() = owner to when (state) {
            is OverlayState.TimerRunning, is OverlayState.CombinedBar, is OverlayState.PurposeBar -> state.kind()
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
    FOCUS_BLOCKED
}

/** 离开目标应用时保存下来的现场，只在该应用设定的保留期限内用来恢复。 */
private data class PausedSession(
    val packageName: String,
    val snapshot: SessionSnapshot,
    val savedAtElapsedRealtime: Long,
    val expiresAtElapsedRealtime: Long,
    val windowX: Int = 0,
    val windowY: Int = 0
)

private sealed interface SessionSnapshot {
    /** 跳过后的访问没有悬浮窗，但与已确认的访问共用退出后保留期限。 */
    data object Skipped : SessionSnapshot

    /** 离开时悬浮窗正在显示的状态，回到目标应用时原样恢复。 */
    data class Visible(
        val state: OverlayState,
        val remainingSeconds: Long,
        val timerMinutes: Int,
        val timerTimeoutCount: Int
    ) : SessionSnapshot

}

private fun OverlayState.kind(): OverlayKind = when (this) {
    is OverlayState.TimerPrompt -> OverlayKind.TIMER_PROMPT
    is OverlayState.TimerRunning -> OverlayKind.TIMER_RUNNING
    is OverlayState.TimerFinished -> OverlayKind.TIMER_FINISHED
    is OverlayState.PurposePrompt -> OverlayKind.PURPOSE_PROMPT
    is OverlayState.CombinedPrompt -> OverlayKind.COMBINED_PROMPT
    is OverlayState.CombinedBar -> OverlayKind.COMBINED_BAR
    is OverlayState.PurposeBar -> OverlayKind.PURPOSE_BAR
    is OverlayState.FocusBlocked -> OverlayKind.FOCUS_BLOCKED
}

/**
 * PurposeGate 的无障碍服务：整个应用的“大脑”。
 *
 * ## 访问与窗口归属（谁属于谁，别混）
 * - [currentPackage]：当前前台应用，判定用。
 * - [handledPackage]：本次进入前台已经处理过的应用，用来避免同一应用重复弹窗。
 * - [overlayOwnerPackage]：正在显示的全屏弹窗（退回模式下也包括悬浮条）属于哪个应用。
 * - [timerOwnerPackage]：倒计时属于哪个应用，决定到点在哪里弹提醒。
 * - [skippedPackage]：已跳过且正在使用的应用，没有悬浮窗；离开时同样保存会话。
 *
 * ## 一次前台切换的判断顺序
 * 1. 事件过滤：监听窗口状态与窗口列表变化；过滤自身悬浮窗事件，主界面算正常目的地、通知栏与状态栏、输入法；
 *    桌面与多任务算一次正常的前台变化。
 * 2. 去抖：普通应用 650 毫秒，桌面/多任务 200 毫秒；被判定为退场动画尾随事件时用窗口列表核对后丢弃。
 * 3. [handleForegroundPackage]：先结束上一次目标访问（按生效规则保存会话），再判断当前应用是否已选、
 *    是否选了显示界面、本次是否已处理过，最后弹窗或恢复会话。
 * 4. 离开目标应用：把悬浮条或跳过状态存成会话（保留时长可配置，默认 30 秒），到期由 [sweepSessions] 清理。
 * 5. 息屏等同离开；解锁时重新确认真实最上层应用窗口，保留期限内回到同一应用则恢复状态。
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
        private const val SWITCHER_RETRY_MS = 300L
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

        /** 系统级切换窗口：ColorOS 的辅助屏遮罩、系统界面等，都不算真正的前台应用。 */
        private val SYSTEM_WINDOW_PACKAGES = setOf(
            "com.coloros.assistantscreen",
            "com.android.systemui"
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
    private var pendingPackage: String? = null
    /** 本次前台访问里已经处理过的目标应用，用来避免同一个应用反复弹窗。 */
    private var handledPackage: String? = null
    /** 按包名保存的限时会话，离开目标应用后回到同一个应用时用来恢复。 */
    private val sessions = mutableMapOf<String, PausedSession>()
    /** 跳过状态只属于当前访问；真正离开时才开始计算保留期限。 */
    private var skippedPackage: String? = null
    private val activeSessionOwnerPackage: String?
        get() = overlayOwnerPackage ?: skippedPackage
    private val visitPolicy = VisitPolicy()
    private var visitPermitted = false
    private var lastUsageMark = SystemClock.elapsedRealtime()
    private var rulesRevision by mutableStateOf(0)
    private var blockedRemainingSeconds by mutableStateOf(0L)
    private var defaultHomePackage: String? = null

    private fun accountFocusUsage() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lastUsageMark).coerceAtLeast(0L)
        lastUsageMark = now
        val pkg = currentPackage ?: return
        if (visitPermitted && pendingPackage == null && isScreenOnNow() && !isDeviceLocked() &&
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
            PurposeGateStore.focusUsageMs(this, pkg), SystemClock.elapsedRealtime())
        if (decision == FocusDecision.DAILY_LIMIT || decision == FocusDecision.COOLDOWN) {
            showFocusBlock(pkg, decision)
        } else if (state is OverlayState.FocusBlocked) {
            clearOverlay()
            currentPackage = null
            handledPackage = null
            handleForegroundPackage(pkg)
        }
    }

    private fun showFocusBlock(pkg: String, decision: FocusDecision) {
        val reason = if (decision == FocusDecision.DAILY_LIMIT) "budget" else "cooldown"
        blockedRemainingSeconds = (visitPolicy.cooldownRemaining(pkg, SystemClock.elapsedRealtime()) + 999) / 1000
        if ((state as? OverlayState.FocusBlocked)?.reason == reason && overlayOwnerPackage == pkg) return
        accountFocusUsage()
        sessions.remove(pkg)
        visitPermitted = false
        clearOverlay()
        overlayOwnerPackage = pkg
        setOverlayState(OverlayState.FocusBlocked(reason,
            if (reason == "budget") "今天，就到这里。" else "留一点间隔。",
            if (reason == "budget") "这个应用今天的使用额度已经用完。明天零点自动恢复，也可以返回 PurposeGate 调整额度。"
            else "刚刚结束了一次使用。等冷却结束，再决定是否打开。"), fullScreen = true)
    }

    private fun permitVisit() {
        lastUsageMark = SystemClock.elapsedRealtime()
        visitPermitted = true
    }

    private fun leaveFocusPrompt() {
        PurposeGateStore.recordFocusEvent(this, "walkaways")
        overlayOwnerPackage?.let { sessions.remove(it) }
        clearOverlay()
        visitPermitted = false
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
    private var overlayOwnerPackage: String? by mutableStateOf(null)
    /** 桌面/多任务事件被判为尾随事件后，是否已经做过一次延迟复核。 */
    private var switcherRetryPending = false
    /** 输入法包名缓存：系统设置查询和包管理器查询都不能放在事件回调里反复做。 */
    private var defaultImePackage: String? = null
    private var imePackages: Set<String>? = null
    private var state: OverlayState? by mutableStateOf(null)
    private var timerSeconds = 0L
    private var timerMinutes = 0
    /** 同一会话内累计的超时次数，保留期内切出与返回不重置。 */
    private var timerTimeoutCount = 0
    /** 当前倒计时属于哪个应用；别的应用接管悬浮窗后由会话快照保存剩余时间。 */
    private var timerOwnerPackage: String? = null

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

    private fun currentUsageLabel(): String {
        val pkg = currentPackage ?: return "等待前台应用变化"
        val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
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
        // Flush the final interactive slice before the broadcast changes the screen state.
        lastUsageMark = SystemClock.elapsedRealtime()
        currentPackage?.let { pkg ->
            if (visitPermitted) visitPolicy.leave(pkg, lastUsageMark, PurposeGateStore.focusRule(this, pkg))
        }
        visitPermitted = false
        cancelForegroundTracking()
        val owner = activeSessionOwnerPackage ?: return
        endTargetVisit(owner)
    }

    /** 解锁后重新确认真实前台应用，避免息屏期间留下过期状态。 */
    private fun onScreenUnlocked() {
        val fallback = currentPackage
        cancelForegroundTracking()
        currentPackage = null
        handledPackage = null
        refreshInputMethodCache()
        val resolved = topApplicationWindowPackage() ?: fallback ?: return
        if (resolved == packageNameForService()) return
        handleForegroundPackage(resolved)
    }

    /** Read the real non-floating application window, preferring input focus over stale activity. */
    private fun topApplicationWindowPackage(requireActive: Boolean = false): String? {
        val info = serviceInfo ?: return null
        val hasFlag = info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
        val hasCapability = info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
        if (!hasFlag || !hasCapability) return null
        return runCatching {
            val snapshots = windows.orEmpty().filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.map {
                ForegroundWindow(
                    packageName = runCatching { it.root?.packageName?.toString() }.getOrNull(),
                    layer = it.layer, focused = it.isFocused, active = it.isActive,
                    inPictureInPicture = it.isInPictureInPictureMode
                )
            }
            foregroundWindowPackage(snapshots, SYSTEM_WINDOW_PACKAGES + imePackages.orEmpty(), requireActive)
        }.getOrNull()
    }

    private var windowRecheckScheduled = false
    private val windowRecheck = Runnable {
        windowRecheckScheduled = false
        reconcileForegroundWindow(force = true)
    }

    private fun requestWindowRecheck() {
        if (windowRecheckScheduled) return
        windowRecheckScheduled = true
        serviceHandler.postDelayed(windowRecheck, SWITCHER_DEBOUNCE_MS)
    }

    /** Window events repair missed app events; the existing one-second tick also checks active visits. */
    private fun reconcileForegroundWindow(force: Boolean = false) {
        if (!force && activeSessionOwnerPackage == null && !visitPermitted) return
        if (!isScreenOnNow() || isDeviceLocked()) {
            if (activeSessionOwnerPackage != null) onScreenOff()
            return
        }
        val top = topApplicationWindowPackage(requireActive = true) ?: return
        if (top != currentPackage) scheduleForegroundChange(top)
        else if (activeSessionOwnerPackage != null && activeSessionOwnerPackage != top) {
            activeSessionOwnerPackage?.let { endTargetVisit(it) }
        }
    }

    private fun cancelForegroundTracking() {
        pendingPackage = null
        switcherRetryPending = false
        windowRecheckScheduled = false
        serviceHandler.removeCallbacks(foregroundChange)
        serviceHandler.removeCallbacks(windowRecheck)
    }

    private fun scheduleForegroundChange(candidate: String) {
        // Repeated events for one destination must not postpone the same deadline indefinitely.
        if (candidate == pendingPackage) return
        pendingPackage = candidate
        switcherRetryPending = false
        serviceHandler.removeCallbacks(foregroundChange)
        serviceHandler.postDelayed(foregroundChange, debounceFor(candidate))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!isScreenOnNow() || isDeviceLocked()) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (Build.VERSION.SDK_INT >= 28) {
                val relevant = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
                    AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or
                    AccessibilityEvent.WINDOWS_CHANGE_LAYER or AccessibilityEvent.WINDOWS_CHANGE_PIP
                if (event.windowChanges and relevant == 0) return
            }
            requestWindowRecheck()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString() ?: return
        // Our overlay is not an application window. The actual PurposeGate main window is a destination.
        if (eventPackage == packageNameForService() &&
            event.className?.toString()?.contains("MainActivity") != true &&
            topApplicationWindowPackage(requireActive = true) != packageNameForService()) return
        if (isTransientSystemWindowEvent(event, eventPackage) || isInputMethodEvent(event, eventPackage)) return
        if (eventPackage == currentPackage) {
            if (pendingPackage != null) {
                val top = topApplicationWindowPackage(requireActive = true)
                if (top == null || top == currentPackage) {
                    pendingPackage = null
                    serviceHandler.removeCallbacks(foregroundChange)
                } else requestWindowRecheck()
            }
            return
        }
        scheduleForegroundChange(eventPackage)
    }

    private fun debounceFor(packageName: String): Long =
        if (isSwitcherPackage(packageName)) SWITCHER_DEBOUNCE_MS else FOREGROUND_DEBOUNCE_MS

    private fun isTransientSystemWindowEvent(event: AccessibilityEvent, packageName: String): Boolean {
        if (isSwitcherWindowEvent(event, packageName)) return false
        if (packageName == "android" || packageName == "com.android.systemui") return true
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
            pendingPackage = null
            val top = topApplicationWindowPackage()
            if (isSwitcherPackage(candidate) && top == currentPackage) {
                if (!switcherRetryPending) {
                    switcherRetryPending = true
                    pendingPackage = candidate
                    serviceHandler.postDelayed(this, SWITCHER_RETRY_MS)
                    return
                }
                switcherRetryPending = false
                // Dropping this one tail event does not disable future window/tick reconciliation.
                requestWindowRecheck()
                return
            }
            switcherRetryPending = false
            handleForegroundPackage(verifiedForegroundPackage(candidate, top, SYSTEM_WINDOW_PACKAGES + imePackages.orEmpty()))
        }
    }


    private fun isSwitcherPackage(packageName: String): Boolean =
        packageName in LAUNCHER_PACKAGES || packageName in SYSTEM_WINDOW_PACKAGES || packageName == defaultHomePackage

    private fun handleForegroundPackage(packageName: String) {
        if (packageName == currentPackage) {
            activeSessionOwnerPackage?.takeIf { it != packageName }?.let { endTargetVisit(it) }
            return
        }

        accountFocusUsage()
        val previousPackage = currentPackage
        if (previousPackage != null && visitPermitted) {
            visitPolicy.leave(previousPackage, SystemClock.elapsedRealtime(), PurposeGateStore.focusRule(this, previousPackage))
        }
        visitPermitted = false
        lastUsageMark = SystemClock.elapsedRealtime()
        Log.d(TAG, "foreground $previousPackage -> $packageName")
        val previousOwner = activeSessionOwnerPackage
        if (previousOwner != null && previousOwner != packageName) {
            // 完成一次目标应用访问：已确认或跳过的状态进入同一个会话保留期，
            // 同时收起该应用的悬浮条、停顿弹窗或到时提醒。
            endTargetVisit(previousOwner)
        }
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
                PurposeGateStore.focusUsageMs(this, packageName), SystemClock.elapsedRealtime())
            if (decision == FocusDecision.DAILY_LIMIT || decision == FocusDecision.COOLDOWN) {
                handledPackage = packageName
                showFocusBlock(packageName, decision)
                return
            }

            // 保留期限内回到同一个目标应用：恢复上次状态，不再弹窗。
            if (restoreSession(packageName)) {
                visitPermitted = true
                handledPackage = packageName
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
        PurposeGateStore.recordFocusEvent(this, "pauses")
        overlayOwnerPackage = packageName
        setOverlayState(nextState, fullScreen = true)
    }

    /** 跳过后不显示悬浮条，离开时仍按生效规则保留本次访问。 */
    private fun dismissPrompt() {
        val owner = overlayOwnerPackage ?: return
        sessions.remove(owner)
        permitVisit()
        handledPackage = owner
        scheduleSessionSweep()
        clearOverlay()
        skippedPackage = owner
    }

    /**
     * 目标应用离开前台：把悬浮窗或跳过状态按生效规则保存为限时会话。
     * 悬浮条、全屏弹窗与提醒立即收起，宽限期内仅保存会话并按实际时长计时。
     */
    private fun endTargetVisit(packageName: String) {
        if (activeSessionOwnerPackage != packageName) return
        val currentState = state
        val snapshot = when {
            skippedPackage == packageName -> SessionSnapshot.Skipped
            currentState is OverlayState.TimerRunning ||
                currentState is OverlayState.CombinedBar ||
                currentState is OverlayState.PurposeBar ||
                currentState is OverlayState.TimerFinished ->
                SessionSnapshot.Visible(
                    state = currentState,
                    remainingSeconds = timerSeconds,
                    timerMinutes = timerMinutes,
                    timerTimeoutCount = timerTimeoutCount
                )
            else -> null
        }
        val retentionMs = PurposeGateStore.focusRule(this, packageName).sessionRetentionSeconds * 1000L
        if (snapshot != null && retentionMs > 0) {
            val params = overlayLayoutParams
            val now = SystemClock.elapsedRealtime()
            sessions[packageName] = PausedSession(
                packageName = packageName,
                snapshot = snapshot,
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
        val session = sessions[packageName] ?: return false
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - session.savedAtElapsedRealtime
        if (now >= session.expiresAtElapsedRealtime) {
            dropSession(packageName)
            return false
        }
        sessions.remove(packageName)

        when (val snapshot = session.snapshot) {
            SessionSnapshot.Skipped -> {
                clearOverlay()
                skippedPackage = packageName
            }
            is SessionSnapshot.Visible -> {
                val savedState = snapshot.state
                state = savedState
                val remaining = snapshot.remainingSeconds - elapsed / 1000L
                timerMinutes = snapshot.timerMinutes
                timerTimeoutCount = snapshot.timerTimeoutCount
                when (savedState) {
                    is OverlayState.TimerRunning,
                    is OverlayState.CombinedBar -> {
                        val purpose = (savedState as? OverlayState.CombinedBar)?.purpose
                        if (remaining <= 0L) {
                            showTimerFinished(packageName)
                        } else {
                            timerSeconds = remaining
                            timerOwnerPackage = packageName
                            showBar(packageName, purpose, hasTimer = true, remainingSeconds = remaining)
                            restartTimerTick(packageName)
                        }
                    }
                    is OverlayState.PurposeBar -> {
                        timerOwnerPackage = packageName
                        showBar(
                            packageName,
                            savedState.purpose,
                            hasTimer = false,
                            remainingSeconds = NO_TIMER
                        )
                    }
                    is OverlayState.TimerFinished -> {
                        timerOwnerPackage = packageName
                        setOverlayState(savedState, fullScreen = true)
                    }
                    is OverlayState.TimerPrompt,
                    is OverlayState.PurposePrompt,
                    is OverlayState.CombinedPrompt,
                    is OverlayState.FocusBlocked -> return false
                }
                overlayOwnerPackage = packageName
                restoreOverlayPosition(session.windowX, session.windowY)
            }
        }
        scheduleSessionSweep()
        return true
    }

    private fun dropSession(packageName: String) {
        sessions.remove(packageName)
        if (activeSessionOwnerPackage == packageName) clearOverlay()
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

    private fun restartTimerTick(packageName: String) {
        timerOwnerPackage = packageName
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE,
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
        timerOwnerPackage = null
        skippedPackage = null
    }

    private fun startTimer(minutes: Int) {
        val owner = overlayOwnerPackage ?: return
        permitVisit()
        clearOverlay()
        overlayOwnerPackage = owner
        timerOwnerPackage = owner
        timerMinutes = minutes
        timerSeconds = minutes * 60L
        state = OverlayState.TimerRunning(timerSeconds)
        showBar(owner, purpose = null, hasTimer = true, remainingSeconds = timerSeconds)
        restartTimerTick(owner)
    }

    private fun startCombined(minutes: Int, purpose: String) {
        val owner = overlayOwnerPackage ?: return
        permitVisit()
        clearOverlay()
        overlayOwnerPackage = owner
        timerOwnerPackage = owner
        timerMinutes = minutes
        timerSeconds = minutes * 60L
        state = OverlayState.CombinedBar(purpose, timerSeconds)
        showBar(owner, purpose, hasTimer = true, remainingSeconds = timerSeconds)
        restartTimerTick(owner)
    }

    /** 只选了目的：悬浮条只显示目的，没有倒计时。 */
    private fun startPurposeBar(purpose: String) {
        val owner = overlayOwnerPackage ?: return
        permitVisit()
        clearOverlay()
        overlayOwnerPackage = owner
        state = OverlayState.PurposeBar(purpose)
        showBar(
            owner,
            purpose,
            hasTimer = false,
            remainingSeconds = NO_TIMER
        )
    }

    /** 每个计时段只记录一次超时；恢复同一到时提醒不重复累计。 */
    private fun showTimerFinished(packageName: String) {
        if (state is OverlayState.TimerFinished) return
        val purpose = (state as? OverlayState.CombinedBar)?.purpose
        timerSeconds = 0L
        timerTimeoutCount += 1
        overlayOwnerPackage = packageName
        timerOwnerPackage = packageName
        setOverlayState(
            OverlayState.TimerFinished(
                minutes = timerMinutes,
                timeoutCount = timerTimeoutCount,
                continueAtElapsedRealtime = SystemClock.elapsedRealtime() + timerTimeoutCount * 10_000L,
                purpose = purpose
            ),
            fullScreen = true
        )
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
            val owner = timerOwnerPackage ?: return
            timerSeconds = (timerSeconds - 1L).coerceAtLeast(0L)
            if (timerSeconds == 0L) {
                // Resolve a departure before presenting an expired timer over an unselected app.
                reconcileForegroundWindow()
                if (timerOwnerPackage != owner) return
                if (currentPackage == owner && pendingPackage == null) {
                    showTimerFinished(owner)
                    return
                }
                if (pendingPackage == null) {
                    if (overlayOwnerPackage == owner) endTargetVisit(owner)
                    return
                }
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
                    target.contentKey == OverlayUiTarget(state, overlayOwnerPackage).contentKey
                }
                val confirmTimer: (Int) -> Unit = { if (mayAct()) startTimer(it) }
                val dismiss = { if (mayAct()) dismissPrompt() }
                val leave = { if (mayAct()) leaveFocusPrompt() }
                when (currentState) {
                    is OverlayState.TimerPrompt -> TimerPromptContent(
                        currentState.appName, confirmTimer, dismiss, promptRule.pauseSeconds, promptRule.allowSkip, leave
                    )
                    is OverlayState.TimerRunning -> TimerBarContent(
                        currentState.remainingSeconds, PurposeGateStore.overlayScale(this@PurposeAccessibilityService)
                    )
                    is OverlayState.TimerFinished -> TimerFinishedContent(
                        minutes = currentState.minutes,
                        continueAtElapsedRealtime = currentState.continueAtElapsedRealtime,
                        onContinue = { if (mayAct()) continueTimerAfterTimeout() },
                        onLeave = leave
                    )
                    is OverlayState.PurposePrompt -> PurposePromptContent(
                        appName = currentState.appName,
                        onPurpose = { if (mayAct()) startPurposeBar(it) },
                        onDismiss = dismiss,
                        pauseSeconds = promptRule.pauseSeconds, allowSkip = promptRule.allowSkip, onLeave = leave
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
                        pauseSeconds = promptRule.pauseSeconds, allowSkip = promptRule.allowSkip, onLeave = leave
                    )
                    is OverlayState.CombinedBar -> CombinedBarContent(
                        purpose = currentState.purpose,
                        remainingSeconds = currentState.remainingSeconds,
                        scale = PurposeGateStore.overlayScale(this@PurposeAccessibilityService)
                    )
                    is OverlayState.PurposeBar -> PurposeBarContent(
                        currentState.purpose, PurposeGateStore.overlayScale(this@PurposeAccessibilityService)
                    )
                    is OverlayState.FocusBlocked -> FocusBlockedContent(
                        currentState.title, currentState.body,
                        if (currentState.reason == "cooldown") blockedRemainingSeconds else null, leave
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
