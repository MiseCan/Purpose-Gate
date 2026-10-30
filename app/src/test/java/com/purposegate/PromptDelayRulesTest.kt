package com.purposegate

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PromptDelayRulesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun legacyDefaultSplitsToThreeAndSixWithoutChangingTheOtherRules() {
        val preferences = context.getSharedPreferences("purpose_gate", Context.MODE_PRIVATE)
        preferences.edit().putString("global_focus_rule", JSONObject()
            .put("pause", 5).put("cooldown", 17).put("skip", false)
            .put("session_retention", 45).toString()).apply()

        val rule = PurposeGateStore.globalFocusRule(context)
        assertEquals(3, rule.confirmDelaySeconds)
        assertEquals(6, rule.skipDelaySeconds)
        assertEquals(17, rule.cooldownSeconds)
        assertEquals(45, rule.sessionRetentionSeconds)
        assertFalse(rule.allowSkip)
        assertFalse(JSONObject(preferences.getString("global_focus_rule", "{}")!!).has("pause"))
    }

    @Test fun deliberateLegacyDelayBecomesConfirmationDelay() {
        context.getSharedPreferences("purpose_gate", Context.MODE_PRIVATE).edit()
            .putString("focus_rule_target", "{\"pause\":13,\"session_retention\":90}").apply()
        val rule = PurposeGateStore.focusRule(context, "target")
        assertEquals(13, rule.confirmDelaySeconds)
        assertEquals(6, rule.skipDelaySeconds)
        assertEquals(90, rule.sessionRetentionSeconds)
    }

    @Test fun bothDelaysPersistAndPerAppOverridesRemainIndependentOfGlobalRules() {
        val global = AppFocusRule(confirmDelaySeconds = 1, skipDelaySeconds = 8)
        PurposeGateStore.setGlobalFocusRule(context, global)
        assertEquals(global, PurposeGateStore.focusRule(context, "inherited"))
        PurposeGateStore.setFocusRule(context, "target",
            AppFocusRule(confirmDelaySeconds = 9, skipDelaySeconds = 2))
        val own = PurposeGateStore.focusRule(context, "target")
        assertEquals(9, own.confirmDelaySeconds)
        assertEquals(2, own.skipDelaySeconds)
        assertEquals(global, PurposeGateStore.globalFocusRule(context))
    }
}
