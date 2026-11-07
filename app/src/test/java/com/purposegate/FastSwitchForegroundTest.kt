package com.purposegate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 快速切换的场景：在目标应用 A 里立刻打开另一个应用 B。
 * 这些用例锁住“不能用旧应用的证据弹窗”和“B 还没拿到焦点时不能当成已就位”。
 */
class FastSwitchForegroundTest {
    private val screen = WindowBounds(0, 0, 1080, 2400)

    @Test fun aSwitchInProgressIsReportedAsTheDestinationButNotSettled() {
        val windows = listOf(
            ForegroundWindow("com.example.target", layer = 10, focused = true, active = true,
                windowId = 1, bounds = screen),
            ForegroundWindow("com.example.other", layer = 20, windowId = 2, bounds = screen)
        )
        assertEquals(
            ForegroundReading("com.example.other", settling = true),
            readForeground(windows, emptySet(), freshWindowIds = setOf(2))
        )
    }

    @Test fun aLateTargetEventCannotWinOverTheNewFocusedApplication() {
        val windows = listOf(
            ForegroundWindow("com.example.target", layer = 10, active = true, windowId = 1,
                bounds = screen),
            ForegroundWindow("com.example.other", layer = 20, focused = true, windowId = 2,
                bounds = screen)
        )
        assertEquals(
            ForegroundReading("com.example.other"),
            readForeground(windows, emptySet(), freshWindowIds = setOf(2))
        )
    }

    @Test fun theOldTargetStaysForegroundOnlyWhileNothingElseIsOnTop() {
        val windows = listOf(
            ForegroundWindow("com.example.target", layer = 10, focused = true, active = true,
                windowId = 1, bounds = screen)
        )
        assertEquals(
            ForegroundReading("com.example.target"),
            readForeground(windows, emptySet(), freshWindowIds = setOf(1))
        )
    }

    @Test fun ownOverlayCannotTurnAStaleTargetIntoForeground() {
        // 悬浮窗在最上面、焦点不在应用上：取层级最高的应用窗口，旧目标不会因为还 active 而获胜。
        val applications = listOf(
            ForegroundWindow("com.example.target", layer = 10, active = true, windowId = 1,
                bounds = screen),
            ForegroundWindow("com.example.other", layer = 20, windowId = 2, bounds = screen)
        )
        assertEquals(
            ForegroundReading("com.example.other"),
            readForeground(applications, emptySet(), ownOverlayCovering = true)
        )
    }

    @Test fun anUnknownHigherWindowStillBlocksTheOldPrompt() {
        val windows = listOf(
            ForegroundWindow("com.example.target", layer = 10, focused = true, active = true,
                windowId = 1, bounds = screen),
            ForegroundWindow(null, layer = 20, windowId = 2, bounds = screen)
        )
        val reading = readForeground(windows, emptySet(), freshWindowIds = setOf(2))
        assertEquals("com.example.target", reading.packageName)
        assertEquals(true, reading.settling)
    }
}