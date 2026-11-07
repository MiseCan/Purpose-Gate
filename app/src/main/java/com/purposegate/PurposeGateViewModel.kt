package com.purposegate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PurposeGateUiState(
    val apps: List<InstalledApp>? = null,
    val appsError: Boolean = false,
    val selected: Set<String> = emptySet(),
    val modes: Set<String> = emptySet(),
    val usage: Map<String, Long> = emptyMap(),
    val pauses: Int = 0,
    val walkaways: Int = 0,
    val serviceEnabled: Boolean = false,
    val usageAccess: Boolean = false,
    val globalRule: AppFocusRule = AppFocusRule(),
    val rules: Map<String, AppFocusRule> = emptyMap(),
    val ownRules: Set<String> = emptySet(),
    val alpha: Float = 1f,
    val scale: Float = 1f,
    val timerPresets: List<Int> = emptyList(),
    val purposePresets: List<String> = emptyList(),
    val notificationTitle: String = "",
    val notificationText: String = ""
) {
    fun ruleFor(pkg: String) = rules[pkg] ?: globalRule
}

/** Activity-scoped state survives page changes and configuration changes. */
internal class PurposeGateViewModel(
    application: Application,
    private val savedState: SavedStateHandle
) : AndroidViewModel(application) {
    private val repository = PurposeGateRepository(application)
    private val mutableUiState = MutableStateFlow(repository.snapshot(PurposeGateUiState()))
    val uiState = mutableUiState.asStateFlow()
    val selectedRoot = savedState.getStateFlow("selected_root", Page.HOME.name)
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            mutableUiState.subscriptionCount.collectLatest { subscribers ->
                if (subscribers > 0) repository.changes.collect { refresh() }
            }
        }
    }

    fun selectRoot(page: Page) {
        savedState["selected_root"] = if (page == Page.PAUSE) Page.PAUSE.name else Page.HOME.name
    }

    fun refresh() { mutableUiState.update(repository::snapshot) }

    fun onResume() {
        refresh()
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.loadApps() } }
            mutableUiState.update { state ->
                state.copy(apps = result.getOrElse { state.apps.orEmpty() }, appsError = result.isFailure)
            }
        }
    }

    private inline fun change(action: () -> Unit) { action(); refresh() }
    fun selectApp(pkg: String, selected: Boolean) = change { repository.selectApp(pkg, selected) }
    fun setMode(mode: String, enabled: Boolean) = change { repository.setMode(mode, enabled) }
    fun saveRule(pkg: String?, rule: AppFocusRule?) = change { repository.saveRule(pkg, rule) }
    fun saveAlpha(value: Float) = change { repository.saveAlpha(value) }
    fun saveScale(value: Float) = change { repository.saveScale(value) }
    fun saveTimers(values: List<Int>) = change { repository.saveTimers(values) }
    fun savePurposes(values: List<String>) = change { repository.savePurposes(values) }
    fun saveNotification(title: String, text: String) = change { repository.saveNotification(title, text) }
}
