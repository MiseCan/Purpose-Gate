package com.purposegate

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNavigationStateTest {
    @Test fun rootTabsArePeersAndDoNotAccumulateHistory() {
        val focus = AppNavigationState().open(Page.PAUSE)
        assertEquals(Page.PAUSE, focus.selectedRoot)
        assertEquals(listOf(Page.HOME), focus.backStack)
        assertEquals(focus, focus.pop())
        assertEquals(AppNavigationState(), focus.open(Page.HOME))
    }

    @Test fun settingsChildrenReturnOneLevelAtATime() {
        val about = AppNavigationState().open(Page.SETTINGS).open(Page.ABOUT)
        assertEquals(listOf(Page.HOME, Page.SETTINGS, Page.ABOUT), about.backStack)
        assertEquals(about.pop(), about.open(Page.SETTINGS))
        assertEquals(AppNavigationState(), about.pop().pop())
    }

    @Test fun focusChildrenReturnToFocusForBothBackActions() {
        val focus = AppNavigationState().open(Page.PAUSE)
        listOf(Page.APPS, Page.RULES).forEach { target ->
            val child = focus.open(target)
            assertEquals(focus, child.pop())
            assertEquals(focus, child.open(Page.PAUSE))
        }
    }

    @Test fun appsKeepTheirCallerAndLegacyRecordsOpenDashboard() {
        assertEquals(AppNavigationState(), AppNavigationState().open(Page.APPS).pop())
        assertEquals(AppNavigationState(), AppNavigationState().open(Page.PAUSE).open(Page.STATS))
    }
}
