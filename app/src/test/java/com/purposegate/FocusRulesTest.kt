package com.purposegate

import org.junit.Assert.*
import org.junit.Test

class FocusRulesTest {
    @Test fun helperEventsResolveToTheRealForegroundApp() {
        assertEquals("settings", verifiedForegroundPackage("helper", "settings", setOf("ime")))
        repeat(5) { assertEquals("a", verifiedForegroundPackage("helper", "a", emptySet())) }
    }

    @Test fun mainWindowCountsAsLeavingButKeyboardDoesNot() {
        assertEquals("self", verifiedForegroundPackage("a", "self", emptySet()))
        assertEquals("a", verifiedForegroundPackage("a", "ime", setOf("ime")))
        assertEquals("a", verifiedForegroundPackage("a", null, emptySet()))
        assertEquals("home", verifiedForegroundPackage("a", "home", emptySet()))
    }

    @Test fun cooldownIsPerAppAndRepeatedChecksDoNotExtendIt() {
        val rule = AppFocusRule(cooldownSeconds = 10)
        val visits = VisitPolicy()
        visits.leave("a", 1000, rule)
        assertEquals(FocusDecision.PROMPT, visits.decision("b", rule, 2000))
        assertEquals(FocusDecision.COOLDOWN, visits.decision("a", rule, 5000))
        assertEquals(FocusDecision.COOLDOWN, visits.decision("a", rule, 10_999))
        assertEquals(FocusDecision.PROMPT, visits.decision("a", rule, 11_000))
        assertEquals(0L, visits.cooldownRemaining("a", 12_000))
    }



    @Test fun zeroCooldownDisablesAnExistingDeadline() {
        val visits = VisitPolicy()
        visits.leave("a", 0, AppFocusRule(cooldownSeconds = 60))
        assertEquals(FocusDecision.PROMPT, visits.decision("a", AppFocusRule(), 1000))
    }
}
