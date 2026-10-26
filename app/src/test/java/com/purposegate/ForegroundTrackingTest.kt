package com.purposegate

import org.junit.Assert.*
import org.junit.Test

class ForegroundTrackingTest {
    @Test fun focusedWechatWinsOverThePreviousActiveTarget() {
        val windows = listOf(
            ForegroundWindow("target", layer = 10, active = true),
            ForegroundWindow("com.tencent.mm", layer = 5, focused = true)
        )
        assertEquals("com.tencent.mm", foregroundWindowPackage(windows, emptySet(), requireActive = true))
    }

    @Test fun aFloatingApplicationCannotHideTheHomeWindow() {
        val windows = listOf(
            ForegroundWindow("target", layer = 20, active = true, inPictureInPicture = true),
            ForegroundWindow("home", layer = 5, focused = true)
        )
        assertEquals("home", foregroundWindowPackage(windows, emptySet(), requireActive = true))
    }

    @Test fun backgroundOnlyAndUnreadableWindowsDoNotProveAForegroundChange() {
        assertNull(foregroundWindowPackage(listOf(ForegroundWindow("old", 1)), emptySet(), true))
        assertNull(foregroundWindowPackage(listOf(
            ForegroundWindow(null, 3, focused = true), ForegroundWindow("old", 1, active = true)
        ), emptySet(), true))
    }

    @Test fun keyboardIsIgnoredAndPurposeGateMainWindowIsARealDestination() {
        val windows = listOf(ForegroundWindow("ime", 10, focused = true),
            ForegroundWindow("com.purposegate", 5, active = true))
        assertEquals("com.purposegate", foregroundWindowPackage(windows, setOf("ime"), true))
    }
}
