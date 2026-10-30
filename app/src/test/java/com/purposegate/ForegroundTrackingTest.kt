package com.purposegate

import org.junit.Assert.*
import org.junit.Test

class ForegroundTrackingTest {
    @Test fun focusedApplicationWinsOverThePreviousActiveTarget() {
        val windows = listOf(
            ForegroundWindow("target", layer = 10, active = true),
            ForegroundWindow("com.example.unselected", layer = 5, focused = true)
        )
        assertEquals("com.example.unselected", foregroundWindowPackage(windows, emptySet(), requireActive = true))
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
    @Test fun unreadableFocusedDestinationUsesOnlyItsOwnWindowEvent() {
        val windows = listOf(
            ForegroundWindow("target", 10, active = true, windowId = 1),
            ForegroundWindow(null, 20, focused = true, windowId = 2)
        )
        assertEquals("com.example.destination", foregroundWindowPackage(
            windows, emptySet(), true, mapOf(2 to "com.example.destination")))
        assertNull(foregroundWindowPackage(windows, emptySet(), true, mapOf(1 to "target")))
        assertNull(foregroundWindowPackage(windows, setOf("ime"), true, mapOf(2 to "ime")))
    }
}
