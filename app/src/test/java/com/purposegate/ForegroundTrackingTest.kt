package com.purposegate

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundTrackingTest {
    private val screen = WindowBounds(0, 0, 1080, 2400)

    @Test fun focusedApplicationWinsOverThePreviousActiveTarget() {
        val windows = listOf(
            ForegroundWindow("target", layer = 10, active = true),
            ForegroundWindow("com.example.unselected", layer = 5, focused = true)
        )
        assertEquals(ForegroundReading("com.example.unselected"), readForeground(windows, emptySet()))
    }

    @Test fun aFloatingApplicationCannotHideTheHomeWindow() {
        val windows = listOf(
            ForegroundWindow("target", layer = 20, active = true, inPictureInPicture = true),
            ForegroundWindow("home", layer = 5, focused = true)
        )
        assertEquals(ForegroundReading("home"), readForeground(windows, emptySet()))
    }

    @Test fun backgroundOnlyAndUnreadableWindowsDoNotProveAForegroundChange() {
        assertEquals(ForegroundReading(null), readForeground(listOf(ForegroundWindow("old", 1)), emptySet()))
        // 读不到包名的焦点窗口不能证明换了应用，也不能退回后台的 active 窗口。
        assertEquals(
            ForegroundReading(null),
            readForeground(
                listOf(
                    ForegroundWindow(null, 3, focused = true),
                    ForegroundWindow("old", 1, active = true)
                ),
                emptySet()
            )
        )
    }

    @Test fun keyboardIsIgnoredAndPurposeGateMainWindowIsARealDestination() {
        val windows = listOf(
            ForegroundWindow("ime", 10, focused = true),
            ForegroundWindow("com.purposegate", 5, active = true)
        )
        assertEquals(
            ForegroundReading("com.purposegate"),
            readForeground(windows, setOf("ime"))
        )
    }

    @Test fun ownFullScreenOverlayUsesTheHighestApplicationWindow() {
        // 焦点在自己的悬浮窗上：应用窗口拿不到焦点，改取层级最高的应用窗口。
        val applications = listOf(
            ForegroundWindow("com.example.old", layer = 10, active = true, windowId = 1),
            ForegroundWindow("com.example.new", layer = 20, windowId = 2)
        )
        assertEquals(
            ForegroundReading("com.example.new"),
            readForeground(applications, emptySet(), ownOverlayCovering = true)
        )
    }

    @Test fun unreadableWindowAboveOurOverlayIsUnknownNotTheOldApplication() {
        val applications = listOf(
            ForegroundWindow("com.example.old", layer = 10, active = true, windowId = 1),
            ForegroundWindow(null, layer = 20, windowId = 2)
        )
        assertEquals(
            ForegroundReading(null),
            readForeground(applications, emptySet(), ownOverlayCovering = true)
        )
    }

    @Test fun aNewWindowAboveTheFocusedOneMeansTheSwitchIsNotSettledYet() {
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

    @Test fun anOldHigherWindowDoesNotBlockAPromptForever() {
        val windows = listOf(
            ForegroundWindow("com.example.target", layer = 10, focused = true, active = true,
                windowId = 1, bounds = screen),
            ForegroundWindow("com.example.other", layer = 20, windowId = 2, bounds = screen)
        )
        // 不是刚出现的窗口：当作长期存在的异层窗口，不阻塞判定。
        assertEquals(
            ForegroundReading("com.example.target"),
            readForeground(windows, emptySet(), freshWindowIds = emptySet())
        )
    }

    @Test fun aTiledWindowAboveTheFocusedOneIsSplitScreenNotASwitch() {
        val windows = listOf(
            ForegroundWindow("com.example.target", layer = 10, focused = true, active = true,
                windowId = 1, bounds = WindowBounds(0, 1200, 1080, 2400)),
            ForegroundWindow("com.example.other", layer = 20, windowId = 2,
                bounds = WindowBounds(0, 0, 1080, 1200))
        )
        assertEquals(
            ForegroundReading("com.example.target"),
            readForeground(windows, emptySet(), freshWindowIds = setOf(2))
        )
    }

    @Test fun theSameApplicationOpeningItsOwnWindowIsNotASwitch() {
        val windows = listOf(
            ForegroundWindow("com.example.target", layer = 10, focused = true, active = true,
                windowId = 1, bounds = screen),
            ForegroundWindow("com.example.target", layer = 20, windowId = 2, bounds = screen)
        )
        assertEquals(
            ForegroundReading("com.example.target"),
            readForeground(windows, emptySet(), freshWindowIds = setOf(2))
        )
    }

    @Test fun anUnreadableWindowAboveTheFocusedOneBlocksWithoutNamingTheDestination() {
        val windows = listOf(
            ForegroundWindow("com.example.target", layer = 10, focused = true, windowId = 1,
                bounds = screen),
            ForegroundWindow(null, layer = 20, windowId = 2, bounds = screen)
        )
        assertEquals(
            ForegroundReading("com.example.target", settling = true),
            readForeground(windows, emptySet(), freshWindowIds = setOf(2))
        )
    }

    @Test fun anIgnoredSystemDialogDoesNotEndTheApplicationVisit() {
        val windows = listOf(
            ForegroundWindow("com.android.permissioncontroller", layer = 30, focused = true,
                windowId = 2, bounds = screen),
            ForegroundWindow("com.example.target", layer = 10, active = true, windowId = 1,
                bounds = screen)
        )
        // 对话框被过滤后，焦点窗口不可用，退回仍然 active 的应用：系统对话框不算“离开”。
        assertEquals(
            ForegroundReading("com.example.target"),
            readForeground(windows, setOf("com.android.permissioncontroller"), freshWindowIds = setOf(2))
        )
    }
}