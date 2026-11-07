package com.purposegate

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.MutableState
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Replays foreground changes without launching real apps or waiting on UI animation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class FastSwitchOverlayTest {
    private lateinit var controller: ServiceController<PurposeAccessibilityService>
    private lateinit var service: PurposeAccessibilityService

    @Before fun setUp() {
        controller = Robolectric.buildService(PurposeAccessibilityService::class.java).create()
        service = controller.get()
        service.getSharedPreferences("purpose_gate", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(service.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        shadowOf(service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
            .setKeyguardLocked(false)
        PurposeGateStore.setSelectedPackages(service, setOf(TARGET))
        PurposeGateStore.setModes(service, setOf(PurposeGateStore.MODE_PURPOSE))
        PurposeGateStore.setGlobalFocusRule(service, AppFocusRule(sessionRetentionSeconds = 30))
    }

    @After fun tearDown() {
        controller.destroy()
    }

    @Test fun queuedTargetCannotOpenAPromptWhenTheActualWindowIsUnknown() {
        queueEntry(TARGET)
        showWindow(null)

        change().run()

        assertNoOverlay()
        assertNull(field("handledPackage").get(service))
    }

    @Test fun aDifferentVerifiedTargetGetsItsOwnDebounceBeforeAnyPrompt() {
        PurposeGateStore.setSelectedPackages(service, setOf(TARGET, OTHER))
        queueEntry(OTHER)
        showWindow(TARGET)

        change().run()

        assertEquals(TARGET, field("pendingPackage").get(service))
        assertNoOverlay()
        assertNull(field("handledPackage").get(service))
    }

    @Test fun verifiedDepartureImmediatelyHidesTheOldBarAndPreservesItsSession() {
        seedBar()
        showWindow(OTHER)

        call("reconcileForegroundWindow")

        // No looper advancement: a confirmed departure must not wait for the entry debounce.
        assertNoOverlay()
        assertTrue(sessions().containsKey(TARGET))
    }

    @Test fun unknownWindowCannotConsumeASavedSession() {
        seedBar()
        showWindow(OTHER)
        call("handleForegroundPackage", String::class.java, OTHER)
        val saved = sessions()[TARGET]
        assertTrue(saved != null)

        field("currentPackage").set(service, TARGET)
        showWindow(null)
        val restored = call("restoreSession", String::class.java, TARGET) as Boolean

        assertFalse(restored)
        assertSame(saved, sessions()[TARGET])
        assertNoOverlay()
    }

    @Test fun aPromptQueuedBeforeTheUserSwitchesAwayIsNeverShown() {
        showWindow(TARGET)
        queueEntry(TARGET)
        // 用户立刻打开另一个应用：它的窗口已经在目标应用上方，但还没拿到焦点。
        addWindowAbove(OTHER)

        change().run()

        assertNull(owner.value)
        assertNull(state.value)
        assertNull(field("overlayView").get(service))
        // 旧候选只是被压住等复核，不会变成旧应用的弹窗。
        assertEquals(TARGET, field("pendingPackage").get(service))
    }

    @Test fun aSwitchInProgressImmediatelyHidesTheOwnersOverlay() {
        showWindow(TARGET)
        seedBar()
        addWindowAbove(OTHER)

        call("reconcileForegroundWindow")

        assertNoOverlay()
        assertTrue(sessions().containsKey(TARGET))
    }

    @Test fun newerActiveDestinationIsNotReplacedByAnOlderFocusedWindow() {
        showWindow(TARGET)
        queueEntry(OTHER)
        addWindowAbove(OTHER, active = true)

        repeat(3) {
            change().run()
            call("reconcileForegroundWindow")
        }

        assertEquals(OTHER, field("pendingPackage").get(service))
        assertNoOverlay()
    }

    @Test fun unknownForegroundKeepsTheTimerRunningInsteadOfFinishing() {
        seedTimerRunning()
        showWindow(null)
        val tick = field("timerTick").get(service) as Runnable

        tick.run()

        assertEquals(0, field("timerTimeoutCount").getInt(service))
        assertEquals("TimerRunning", state.value?.javaClass?.simpleName)
        val handler = field("serviceHandler").get(service) as android.os.Handler
        assertTrue(handler.hasCallbacks(tick))
    }

    @Test fun aSettledTargetStillStartsItsPrompt() {
        showWindow(TARGET)
        queueEntry(TARGET)

        change().run()

        assertEquals(TARGET, owner.value)
        assertEquals("PurposePrompt", state.value?.javaClass?.simpleName)
    }

    @Test fun aPreviouslyFocusedCandidateCannotBlockANewFocusedDestination() {
        showWindow(OTHER)
        val focused = service.windows.single()
        val previous = newWindow(TARGET, id = 43, layer = 5, focused = false, active = true)
        shadowOf(service).setWindows(listOf(previous, focused))
        queueEntry(TARGET)

        change().run()

        assertEquals(OTHER, field("pendingPackage").get(service))
        assertNoOverlay()
    }

    @Test fun aSwitchReportedBeforeItsWindowAppearsVetoesTheOldPrompt() {
        showWindow(TARGET)
        queueEntry(TARGET)

        service.onAccessibilityEvent(eventFrom(OTHER, windowId = 43))

        // 事件窗口还没出现：旧应用的待定弹窗立刻作废。
        assertNull(field("pendingPackage").get(service))
        // 复核也不会立刻改用旧应用弹窗。
        call("reconcileForegroundWindow")
        change().run()
        assertNoOverlay()
    }

    @Test fun aQueuedEntryGivesUpWhenTheWindowListNeverSettles() {
        queueEntry(TARGET)
        showWindow(null)

        change().run()
        assertEquals(TARGET, field("pendingPackage").get(service))

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))

        assertNull(field("pendingPackage").get(service))
        assertNoOverlay()
    }

    private fun assertNoOverlay() {
        assertNull(owner.value)
        assertNull(state.value)
        assertNull(field("overlayView").get(service))
    }

    private fun queueEntry(packageName: String) {
        field("pendingPackage").set(service, packageName)
        field("pendingSince").setLong(service, SystemClock.elapsedRealtime())
    }

    private fun change() = field("foregroundChange").get(service) as Runnable

    private fun seedBar() {
        field("currentPackage").set(service, TARGET)
        owner.value = TARGET
        state.value = overlayState("PurposeBar", String::class.java, "查资料")
    }

    private fun seedTimerRunning() {
        field("currentPackage").set(service, TARGET)
        field("timerSeconds").setLong(service, 1L)
        field("timerMinutes").setInt(service, 5)
        owner.value = TARGET
        state.value = overlayState("TimerRunning", Long::class.javaPrimitiveType!!, 1L)
    }

    private fun overlayState(name: String, parameterType: Class<*>, value: Any): Any =
        Class.forName("com.purposegate.OverlayState" + '$' + name)
            .getDeclaredConstructor(parameterType).apply { isAccessible = true }
            .newInstance(value)

    private fun showWindow(packageName: String?) {
        shadowOf(service).setWindows(listOf(newWindow(packageName, id = 42, layer = 10)))
    }

    /** 另一个应用的窗口盖在当前前台之上（冷启动时很常见：已加入窗口列表，但还没取得焦点）。 */
    private fun addWindowAbove(packageName: String, active: Boolean = false) {
        val above = newWindow(packageName, id = 43, layer = 20, focused = false, active = active)
        shadowOf(service).setWindows(service.windows + above)
    }

    private fun newWindow(
        packageName: String?,
        id: Int,
        layer: Int,
        focused: Boolean = true,
        active: Boolean = true
    ): AccessibilityWindowInfo {
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setId(id)
            setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            setFocused(focused)
            setActive(active)
            setLayer(layer)
            setBoundsInScreen(Rect(0, 0, 1080, 2400))
            if (packageName != null) {
                setRoot(AccessibilityNodeInfo.obtain().apply { this.packageName = packageName })
            }
        }
        return window
    }

    private fun eventFrom(packageName: String, windowId: Int): AccessibilityEvent {
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            this.packageName = packageName
            eventTime = SystemClock.uptimeMillis()
        }
        ReflectionHelpers.callInstanceMethod<Void>(
            event, "setWindowId",
            ClassParameter.from(Int::class.javaPrimitiveType, windowId)
        )
        return event
    }

    private fun field(name: String) = service.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private val owner: MutableState<String?>
        get() = field("overlayOwnerPackage" + '$' + "delegate").get(service) as MutableState<String?>

    @Suppress("UNCHECKED_CAST")
    private val state: MutableState<Any?>
        get() = field("state" + '$' + "delegate").get(service) as MutableState<Any?>

    private fun sessions() = field("sessions").get(service) as Map<*, *>

    private fun call(name: String): Any? = service.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(service)

    private fun call(name: String, argumentType: Class<*>, argument: Any): Any? =
        service.javaClass.getDeclaredMethod(name, argumentType)
            .apply { isAccessible = true }.invoke(service, argument)

    private companion object {
        const val TARGET = "com.example.target"
        const val OTHER = "com.example.other"
    }
}