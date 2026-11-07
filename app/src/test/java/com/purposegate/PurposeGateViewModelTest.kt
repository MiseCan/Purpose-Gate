package com.purposegate

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PurposeGateViewModelTest {
    @Test fun savedRulesAndSharedSettingsUpdateTheStateAndSurviveRecreation() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("purpose_gate", 0).edit().clear().commit()
        val store = ViewModelStore()
        val model = PurposeGateViewModel(app, SavedStateHandle())
        store.put("first", model)
        try {
            model.selectRoot(Page.PAUSE)
            model.selectApp("com.example.target", true)
            val global = AppFocusRule(confirmDelaySeconds = 2, sessionRetentionSeconds = 40)
            val own = AppFocusRule(skipDelaySeconds = 9)
            model.saveRule(null, global)
            assertEquals(global, model.uiState.value.ruleFor("com.example.target"))
            model.saveRule("com.example.target", own)
            assertEquals(own, model.uiState.value.ruleFor("com.example.target"))
            model.saveRule("com.example.target", null)
            assertEquals(global, model.uiState.value.ruleFor("com.example.target"))
            model.saveTimers(listOf(3, 12))
            model.savePurposes(listOf("回复消息"))
            model.saveAlpha(.6f)
            assertEquals(.6f, model.uiState.value.alpha)
            assertEquals(listOf(3, 12), model.uiState.value.timerPresets)

            val recreated = PurposeGateViewModel(app, SavedStateHandle(mapOf("selected_root" to Page.PAUSE.name)))
            store.put("second", recreated)
            assertEquals(Page.PAUSE.name, recreated.selectedRoot.value)
            assertEquals(global, recreated.uiState.value.ruleFor("com.example.target"))
            assertEquals(listOf("回复消息"), recreated.uiState.value.purposePresets)
            assertTrue("Selection persists through recreation", "com.example.target" in recreated.uiState.value.selected)
        } finally { store.clear() }
    }
}
