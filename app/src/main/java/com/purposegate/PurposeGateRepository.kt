package com.purposegate

import android.content.Context

/** UI data access; persistence and service notifications stay in PurposeGateStore. */
internal class PurposeGateRepository(context: Context) {
    private val context = context.applicationContext
    val changes get() = PurposeGateStore.observeChanges(context)

    fun snapshot(previous: PurposeGateUiState): PurposeGateUiState {
        val selected = PurposeGateStore.selectedPackages(context)
        return previous.copy(
            selected = selected,
            modes = PurposeGateStore.modes(context),
            usage = selected.associateWith { PurposeGateStore.focusUsageMs(context, it) },
            pauses = PurposeGateStore.focusCount(context, "pauses"),
            walkaways = PurposeGateStore.focusCount(context, "walkaways"),
            serviceEnabled = isAccessibilityServiceEnabled(context),
            usageAccess = hasUsageStatsAccess(context),
            globalRule = PurposeGateStore.globalFocusRule(context),
            rules = selected.associateWith { PurposeGateStore.focusRule(context, it) },
            ownRules = selected.filterTo(mutableSetOf()) { PurposeGateStore.hasFocusRule(context, it) },
            alpha = PurposeGateStore.overlayAlpha(context),
            scale = PurposeGateStore.overlayScale(context),
            timerPresets = PurposeGateStore.timerPresets(context),
            purposePresets = PurposeGateStore.purposePresets(context),
            notificationTitle = PurposeGateStore.ongoingTitle(context),
            notificationText = PurposeGateStore.ongoingText(context)
        )
    }

    fun loadApps() = loadInstalledApps(context)
    fun selectApp(pkg: String, selected: Boolean) = PurposeGateStore.setSelectedPackages(
        context, PurposeGateStore.selectedPackages(context).setMembership(pkg, selected))
    fun setMode(mode: String, enabled: Boolean) = PurposeGateStore.setModes(
        context, PurposeGateStore.modes(context).setMembership(mode, enabled))
    fun saveRule(pkg: String?, rule: AppFocusRule?) {
        when {
            pkg == null -> PurposeGateStore.setGlobalFocusRule(context, requireNotNull(rule))
            rule == null -> PurposeGateStore.clearFocusRule(context, pkg)
            else -> PurposeGateStore.setFocusRule(context, pkg, rule)
        }
    }
    fun saveAlpha(value: Float) = PurposeGateStore.setOverlayAlpha(context, value)
    fun saveScale(value: Float) = PurposeGateStore.setOverlayScale(context, value)
    fun saveTimers(values: List<Int>) = PurposeGateStore.setTimerPresets(context, values)
    fun savePurposes(values: List<String>) = PurposeGateStore.setPurposePresets(context, values)
    fun saveNotification(title: String, text: String) {
        PurposeGateStore.saveOngoing(context, title, text)
        context.sendBroadcast(android.content.Intent(PurposeAccessibilityService.ACTION_REFRESH_NOTIFICATION)
            .setPackage(context.packageName))
    }
}
