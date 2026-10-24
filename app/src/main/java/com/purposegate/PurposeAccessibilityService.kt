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
    data class TimerFinished(val minutes: Int) : OverlayState
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
    /** 离开时悬浮窗正在显示的状态，回到目标应用时原样恢复。 */
    data class Visible(
        val state: OverlayState,
        val remainingSeconds: Long,
        val timerMinutes: Int
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
 * ## 五个归属变量（谁属于谁，别混）
 * - [currentPackage]：当前前台应用，判定用。
 * - [handledPackage]：本次进入前台已经处理过的应用，用来避免同一应用重复弹窗。
 * - [overlayOwnerPackage]：正在显示的全屏弹窗（退回模式下也包括悬浮条）属于哪个应用。
 * - [pipBarPackage]：画中画悬浮条属于哪个应用。
 * - [timerOwnerPackage]：倒计时属于哪个应用，决定到点在哪里弹提醒。
 *
 * ## 一次前台切换的判断顺序
 * 1. 事件过滤：只收 TYPE_WINDOW_STATE_CHANGED；忽略本应用自己的悬浮窗/界面事件、通知栏与状态栏、输入法；
 *    桌面与多任务算一次正常的前台变化。
 * 2. 去抖：普通应用 650 毫秒，桌面/多任务 200 毫秒；被判定为退场动画尾随事件时用窗口列表核对后丢弃。
 * 3. [handleForegroundPackage]：先结束上一次目标访问（按生效规则保存会话），再判断当前应用是否已选、
 *    是否选了显示界面、本次是否已处理过，最后弹窗或恢复会话。
 * 4. 离开目标应用：把悬浮条状态存成会话（保留时长可配置，默认 30 秒），到期由 [sweepSessions] 清理。
 * 5. 息屏等同离开；解锁时重新确认真实最上层应用窗口，保留期限内回到同一应用则恢复状态。
 *
 * ## 计时与提醒
 * 倒计时由 [timerTick] 每秒推进，只负责数字与到点判断，画面交给悬浮条自己刷新。
 * 到点时：目标应用在前台 → 收起悬浮条并弹“时间到了”；不在前台 → 悬浮条停在 00:00，等回到应用再弹。
 *
 * ## 相关文件
 * OverlayUi.kt：悬浮层里的弹窗与悬浮条界面；GatePipActivity.kt：画中画悬浮条宿主；
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
            accountFocusUsage()
            enforceCurrentFocusRule()
            serviceHandler.postDelayed(this, 1000L)
        }
    }

    private fun enforceCurrentFocusRule() {
        if (pendingPackage != null || !isScreenOnNow() || isDeviceLocked()) return
        val pkg = currentPackage ?: return
        if (pkg !in PurposeGateStore.selectedPackages(this)) {
            if (overlayOwnerPackage == pkg || pipBarPackage == pkg) { hidePipBar(); clearOverlay() }
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
        hidePipBar()
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
        hidePipBar()
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
    /** 当前倒计时属于哪个应用；别的应用接管悬浮窗后由会话快照保存剩余时间。 */
    private var timerOwnerPackage: String? = null
    /** 悬浮条是否由画中画承载（不可用时退回无障碍悬浮条）。 */
    private var pipBarPackage: String? = null
    private var pipUnavailable = false
    /** 画中画悬浮条当前显示的内容，单独保存，避免被别的应用弹窗覆盖后丢失。 */
    private var barPurpose: String? = null
    private var barHasTimer = false
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
    private var barReceiverRegistered = false
    /** 画中画悬浮条回传的状态：用户主动关闭，或系统不允许画中画。 */
    private val barStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GatePipActivity.ACTION_BAR_CLOSED -> onBarClosedByUser()
                GatePipActivity.ACTION_PIP_UNAVAILABLE -> onPipUnavailable()
                ACTION_REFRESH_NOTIFICATION -> startOngoingNotification()
                ACTION_RULES_CHANGED -> { rulesRevision++; enforceCurrentFocusRule() }
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
        registerBarStateReceiver()
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

    private fun registerBarStateReceiver() {
        if (barReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(GatePipActivity.ACTION_BAR_CLOSED)
            addAction(GatePipActivity.ACTION_PIP_UNAVAILABLE)
            addAction(ACTION_REFRESH_NOTIFICATION)
            addAction(ACTION_RULES_CHANGED)
        }
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(this, barStateReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onSuccess { barReceiverRegistered = true }
    }

    private fun unregisterBarStateReceiver() {
        if (!barReceiverRegistered) return
        runCatching { unregisterReceiver(barStateReceiver) }
        barReceiverRegistered = false
    }

    /**
     * 显示悬浮条。两种承载方式由用户在设置里选择：
     * - 画中画：交给系统，原生负责拖动、关闭、展开；
     * - 悬浮窗：继续用无障碍悬浮条，可自行拖动、可调透明度和大小。
     * 画中画进入失败时自动退回悬浮窗，保证功能不丢。
     */
    private fun showBar(packageName: String, purpose: String?, hasTimer: Boolean, remainingSeconds: Long) {
        barPurpose = purpose
        barHasTimer = hasTimer
        val pipMode = PurposeGateStore.barMode(this) == PurposeGateStore.BAR_MODE_PIP
        if (pipMode && !pipUnavailable) {
            pipBarPackage = packageName
            val launched = runCatching {
                startActivity(
                    GatePipActivity.intent(
                        this,
                        purpose,
                        if (hasTimer) remainingSeconds else GatePipActivity.NO_TIMER
                    )
                )
            }.isSuccess
            if (launched) return
            // 设备不支持画中画：退回悬浮窗
            pipUnavailable = true
            pipBarPackage = null
        }
        overlayOwnerPackage = packageName
        showOverlayBar(purpose, if (hasTimer) remainingSeconds else GatePipActivity.NO_TIMER)
    }

    private fun hidePipBar() {
        serviceHandler.removeCallbacks(timerTick)
        timerOwnerPackage = null
        barPurpose = null
        barHasTimer = false
        if (pipBarPackage == null) return
        pipBarPackage = null
        sendBroadcast(Intent(GatePipActivity.ACTION_FINISH_BAR).setPackage(packageName))
    }

    /** 画中画不可用时的退路：仍然用无障碍悬浮条显示。 */
    private fun showOverlayBar(purpose: String?, remainingSeconds: Long) {
        val nextState = when {
            purpose != null && remainingSeconds >= 0L -> OverlayState.CombinedBar(purpose, remainingSeconds)
            purpose != null -> OverlayState.PurposeBar(purpose)
            remainingSeconds >= 0L -> OverlayState.TimerRunning(remainingSeconds)
            else -> return
        }
        setOverlayState(nextState, fullScreen = false)
    }

    /** 用户用画中画窗口的关闭按钮结束了悬浮条：结束这次会话。 */
    private fun onBarClosedByUser() {
        val owner = pipBarPackage ?: return
        pipBarPackage = null
        serviceHandler.removeCallbacks(timerTick)
        timerOwnerPackage = null
        barPurpose = null
        barHasTimer = false
        sessions.remove(owner)
        scheduleSessionSweep()
        if (overlayOwnerPackage == owner) clearOverlay()
    }

    private fun onPipUnavailable() {
        pipUnavailable = true
        pipBarPackage = null
        when (val current = state) {
            is OverlayState.TimerRunning -> {
                showOverlayBar(null, timerSeconds)
                timerOwnerPackage?.let { restartTimerTick(it) }
            }
            is OverlayState.CombinedBar -> {
                showOverlayBar(current.purpose, timerSeconds)
                timerOwnerPackage?.let { restartTimerTick(it) }
            }
            is OverlayState.PurposeBar -> showOverlayBar(current.purpose, GatePipActivity.NO_TIMER)
            else -> Unit
        }
    }

    private fun isScreenOnNow(): Boolean =
        (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive == true

    /**
     * XML 里的 canRetrieveWindowContent 只会打开 CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT，
     * getWindows() 还需要 FLAG_RETRIEVE_INTERACTIVE_WINDOWS，所以在这里补上。
     */
    private fun enableWindowListAccess() {
        val info = serviceInfo ?: return
        if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0) return
        runCatching {
            serviceInfo = info.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        accountFocusUsage()
        serviceHandler.removeCallbacks(focusPolicyTick)
        serviceHandler.removeCallbacks(notificationRefresh)
        stopForeground(STOP_FOREGROUND_REMOVE)
        unregisterScreenStateReceiver()
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
        pendingPackage = null
        switcherRetryPending = false
        serviceHandler.removeCallbacks(foregroundChange)
        val owner = overlayOwnerPackage ?: return
        endTargetVisit(owner)
    }

    /** 解锁后重新确认真实前台应用，避免息屏期间留下过期状态。 */
    private fun onScreenUnlocked() {
        val fallback = currentPackage
        pendingPackage = null
        switcherRetryPending = false
        serviceHandler.removeCallbacks(foregroundChange)
        currentPackage = null
        handledPackage = null
        refreshInputMethodCache()
        val resolved = topApplicationWindowPackage() ?: fallback ?: return
        if (resolved == packageNameForService()) return
        handleForegroundPackage(resolved)
    }

    /**
     * 读取真正位于最上层的应用窗口包名。
     * 依赖 accessibility_service_config.xml 里的 canRetrieveWindowContent，
     * 拿不到时返回 null，由调用方回退到无障碍事件里的包名。
     */
    private fun topApplicationWindowPackage(): String? {
        val info = serviceInfo ?: return null
        val hasFlag = info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
        val hasCapability = info.capabilities and
            AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
        if (!hasFlag && !hasCapability) {
            return null
        }
        return runCatching {
            val all = windows.orEmpty()
            val applicationWindows = all
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val target = applicationWindows.firstOrNull { it.isActive }
                ?: applicationWindows.firstOrNull { it.isFocused }
                ?: applicationWindows.firstOrNull()
            target?.root?.packageName?.toString()
        }.getOrNull()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!isScreenOnNow() || isDeviceLocked()) return
        val packageName = event?.packageName?.toString() ?: return
        val eventStartedAt = SystemClock.elapsedRealtimeNanos()
        // 悬浮窗和画中画窗口都属于本应用，它们自己的窗口事件不能算前台变化；
        // 但用户主动打开 PurposeGate 主界面时，目标应用确实已经离开前台，
        // 需要按正常的前台切换处理，否则悬浮条会一直飘在本应用上面。
        if (packageName == packageNameForService() &&
            event.className?.toString()?.contains("MainActivity") != true
        ) return
        // 通知栏、状态栏、悬浮通知、输入法都不算前台应用变化，不能因此收起悬浮窗；
        // 桌面和多任务界面算前台变化，用来判断“已经离开目标应用”。
        if (isTransientSystemWindowEvent(event, packageName)) return
        // Opening a text field also creates a window-state event for the IME.
        // The keyboard is not a foreground-app change and must not dismiss the
        // purpose/timer prompt while the user is typing.
        if (isInputMethodEvent(event, packageName)) return
        if (packageName == currentPackage) {
            pendingPackage = null
            serviceHandler.removeCallbacks(foregroundChange)
            return
        }

        // Some apps emit a short burst of window-state events while settling on
        // one activity. Only commit the last package after it remains stable.
        pendingPackage = packageName
        switcherRetryPending = false
        serviceHandler.removeCallbacks(foregroundChange)
        serviceHandler.postDelayed(foregroundChange, debounceFor(packageName))
        val costMs = (SystemClock.elapsedRealtimeNanos() - eventStartedAt) / 1_000_000
        if (costMs >= 20) Log.d(TAG, "event slow ${costMs}ms pkg=$packageName")
    }

    private fun debounceFor(packageName: String): Long =
        if (isSwitcherPackage(packageName)) SWITCHER_DEBOUNCE_MS else FOREGROUND_DEBOUNCE_MS

    private fun isTransientSystemWindowEvent(
        event: AccessibilityEvent,
        packageName: String
    ): Boolean {
        if (isSwitcherWindowEvent(event, packageName)) return false
        if (
            packageName == "android" ||
            packageName == "com.android.systemui"
        ) return true
        val className = event.className?.toString().orEmpty()
        return className.contains("StatusBar", ignoreCase = true) ||
            className.contains("Notification", ignoreCase = true) ||
            className.contains("HeadsUp", ignoreCase = true) ||
            className == "android.widget.Toast"
    }

    /**
     * 桌面和多任务界面。这里只按明确的桌面包名和“多任务界面”类名判断，
     * 不再用 contains("Launcher")/endsWith("HomeActivity") 这类宽泛匹配，
     * 否则微信的 LauncherUI 之类入口 Activity 会被误判成桌面，导致打开应用时被忽略。
     */
    private fun isSwitcherWindowEvent(
        event: AccessibilityEvent,
        packageName: String
    ): Boolean {
        if (packageName in LAUNCHER_PACKAGES || packageName == defaultHomePackage) return true
        val className = event.className?.toString().orEmpty()
        return className.endsWith("RecentsActivity") ||
            className.endsWith("RecentTasksActivity") ||
            className.endsWith("OverviewActivity")
    }

    private fun isInputMethodEvent(event: AccessibilityEvent, packageName: String): Boolean {
        if (event.className?.toString()?.contains("InputMethod", ignoreCase = true) == true) {
            return true
        }
        // 这两项查询都是跨进程调用，不能在事件回调里每秒做几十次，缓存起来即可。
        if (defaultImePackage == null && imePackages == null) refreshInputMethodCache()
        if (defaultImePackage == packageName) return true
        return imePackages?.contains(packageName) == true
    }

    private fun refreshInputMethodCache() {
        defaultImePackage = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        }.getOrNull()
        imePackages = runCatching {
            packageManager.queryIntentServices(Intent(InputMethod.SERVICE_INTERFACE), 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .toSet()
        }.getOrNull()
    }

    private val foregroundChange: Runnable = object : Runnable {
        override fun run() {
            val packageName = pendingPackage ?: return
            pendingPackage = null
            val top = topApplicationWindowPackage()
            if (isStaleSwitcherCommit(packageName, top)) {
                if (!switcherRetryPending) {
                    // 窗口列表可能还没更新完，稍后再确认一次，避免漏掉真正的切换。
                    switcherRetryPending = true
                    pendingPackage = packageName
                    serviceHandler.removeCallbacks(this)
                    serviceHandler.postDelayed(this, SWITCHER_RETRY_MS)
                    return
                }
                switcherRetryPending = false
                Log.d(TAG, "skip stale switcher commit $packageName top=$top current=$currentPackage")
                return
            }
            switcherRetryPending = false
            val verified = verifiedForegroundPackage(packageName, top, packageNameForService(),
                SYSTEM_WINDOW_PACKAGES + imePackages.orEmpty())
            if (verified != packageName) Log.d(TAG, "verify foreground event=$packageName top=$verified")
            handleForegroundPackage(verified)
        }
    }

    /**
     * 桌面、多任务、系统遮罩窗口在退场动画里会晚一点再发一次事件。
     * 如果这一刻窗口列表还显示用户就在原来的目标应用里，说明这只是尾随事件，
     * 不能当成“已经离开应用”，否则刚弹出的界面会先消失再重弹。
     */
    private fun isStaleSwitcherCommit(pending: String, topApplicationWindow: String?): Boolean {
        val previous = currentPackage ?: return false
        if (!isSwitcherPackage(pending)) return false
        // 窗口列表确认用户还在原来的目标应用里：说明这只是退场动画的尾随事件。
        return topApplicationWindow == previous
    }

    private fun isSwitcherPackage(packageName: String): Boolean =
        packageName in LAUNCHER_PACKAGES || packageName in SYSTEM_WINDOW_PACKAGES || packageName == defaultHomePackage

    private fun handleForegroundPackage(packageName: String) {
        if (packageName == currentPackage) return

        accountFocusUsage()
        val previousPackage = currentPackage
        if (previousPackage != null && visitPermitted) {
            visitPolicy.leave(previousPackage, SystemClock.elapsedRealtime(), PurposeGateStore.focusRule(this, previousPackage))
        }
        visitPermitted = false
        lastUsageMark = SystemClock.elapsedRealtime()
        Log.d(TAG, "foreground $previousPackage -> $packageName")
        if (previousPackage != null && previousPackage != packageName &&
            (previousPackage == overlayOwnerPackage || previousPackage == pipBarPackage)
        ) {
            // 完成一次目标应用访问：悬浮条立即隐藏并进入设定的会话保留期，
            // 全屏弹窗和“时间到了”提醒立刻收起，但同样保留会话。
            endTargetVisit(previousPackage)
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

    /** 跳过只处理这次访问；再次离开并进入时按正常规则判断。 */
    private fun dismissPrompt() {
        val owner = overlayOwnerPackage ?: return
        sessions.remove(owner)
        permitVisit()
        handledPackage = owner
        scheduleSessionSweep()
        clearOverlay()
    }

    /**
     * 目标应用离开前台：把当前悬浮窗状态按生效规则保存为限时会话。
     * 悬浮条、全屏弹窗与提醒立即收起，宽限期内仅保存会话并按实际时长计时。
     */
    private fun endTargetVisit(packageName: String) {
        val currentState = state
        val leavesPipBar = packageName == pipBarPackage
        val snapshot = when {
            // 画中画悬浮条的内容单独记录，不会被别的应用弹窗顶掉。
            leavesPipBar -> SessionSnapshot.Visible(
                state = barState(),
                remainingSeconds = timerSeconds,
                timerMinutes = timerMinutes
            )
            currentState is OverlayState.TimerRunning ||
                currentState is OverlayState.CombinedBar ||
                currentState is OverlayState.PurposeBar ||
                currentState is OverlayState.TimerFinished ->
                SessionSnapshot.Visible(
                    state = currentState,
                    remainingSeconds = timerSeconds,
                    timerMinutes = timerMinutes
                )
            else -> null
        }
        val retentionMs = PurposeGateStore.focusRule(this, packageName).sessionRetentionSeconds * 1000L
        if (snapshot is SessionSnapshot.Visible && retentionMs > 0) {
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

        // 会话快照已保存：离开后立即收起两种悬浮条，恢复时按离开时长扣减。
        hidePipBar()
        clearOverlay()
    }

    private fun barState(): OverlayState {
        val purpose = barPurpose
        return when {
            barHasTimer && purpose != null -> OverlayState.CombinedBar(purpose, timerSeconds)
            barHasTimer -> OverlayState.TimerRunning(timerSeconds)
            else -> OverlayState.PurposeBar(purpose.orEmpty())
        }
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
            is SessionSnapshot.Visible -> {
                val savedState = snapshot.state
                state = savedState
                val remaining = snapshot.remainingSeconds - elapsed / 1000L
                timerMinutes = snapshot.timerMinutes
                when (savedState) {
                    is OverlayState.TimerRunning,
                    is OverlayState.CombinedBar -> {
                        val purpose = (savedState as? OverlayState.CombinedBar)?.purpose
                        if (remaining <= 0L) {
                            timerSeconds = 0L
                            hidePipBar()
                            setOverlayState(
                                OverlayState.TimerFinished(snapshot.timerMinutes),
                                fullScreen = true
                            )
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
                            remainingSeconds = GatePipActivity.NO_TIMER
                        )
                    }
                    is OverlayState.TimerFinished -> {
                        timerOwnerPackage = packageName
                        hidePipBar()
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
        if (pipBarPackage == packageName) hidePipBar()
        if (overlayOwnerPackage == packageName) clearOverlay()
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
        unregisterBarStateReceiver()
        serviceHandler.removeCallbacks(sessionSweep)
        sessions.clear()
        hidePipBar()
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
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
        overlayLayoutParams = null
        overlayIsFullScreen = true
        state = null
        overlayOwnerPackage = null
        // 画中画不可用时悬浮条就画在这个悬浮窗里，收起它即结束这条会话的计时。
        // 画中画悬浮条是独立窗口，由 hidePipBar() 单独负责。
        if (pipBarPackage == null) {
            timerOwnerPackage = null
            barPurpose = null
            barHasTimer = false
        }
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
            remainingSeconds = GatePipActivity.NO_TIMER
        )
    }

    private val timerTick = object : Runnable {
        override fun run() {
            val owner = timerOwnerPackage ?: return
            timerSeconds--
            if (timerSeconds <= 0) {
                if (currentPackage == owner) {
                    // 目标应用正在前台：收起悬浮条并弹出“时间到了”。
                    timerSeconds = 0L
                    hidePipBar()
                    setOverlayState(OverlayState.TimerFinished(timerMinutes), fullScreen = true)
                } else {
                    // 目标应用不在前台：悬浮条停在 00:00，等回到应用后再弹提醒。
                    timerSeconds = 0L
                    sendBroadcast(
                        Intent(GatePipActivity.ACTION_TIMER_ZERO).setPackage(packageName)
                    )
                }
            } else {
                // 只更新内部状态（会话快照要用）；画中画悬浮条自己每秒刷新数字，
                // 别的应用的全屏弹窗也不会被这里的赋值顶掉。
                val currentState = state
                state = when (currentState) {
                    is OverlayState.CombinedBar -> OverlayState.CombinedBar(currentState.purpose, timerSeconds)
                    is OverlayState.TimerRunning -> OverlayState.TimerRunning(timerSeconds)
                    else -> currentState
                }
                serviceHandler.postDelayed(this, 1000L)
            }
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
                    is OverlayState.TimerFinished -> TimerFinishedContent(currentState.minutes, confirmTimer, leave)
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
