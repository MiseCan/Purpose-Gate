package com.purposegate

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime

/** UI data access; persistence and service notifications stay in PurposeGateStore. */
internal class PurposeGateRepository(context: Context) {
    private val context = context.applicationContext
    val changes get() = PurposeGateStore.observeChanges(context)

    fun snapshot(previous: PurposeGateUiState): PurposeGateUiState {
        val selected = PurposeGateStore.selectedPackages(context)
        val now = ZonedDateTime.now()
        val month = YearMonth.from(now)
        val confirmations = PurposeGateStore.bedtimeConfirmations(context)
        return previous.copy(
            selected = selected,
            modes = PurposeGateStore.modes(context),
            usage = selected.associateWith { PurposeGateStore.focusUsageMs(context, it) },
            appVisitStats = selected.associateWith { PurposeGateStore.appVisitStats(context, it) },
            pauses = PurposeGateStore.focusCount(context, "pauses"),
            walkaways = PurposeGateStore.focusCount(context, "walkaways"),
            serviceEnabled = isAccessibilityServiceEnabled(context),
            rootKeepAliveEnabled = PurposeGateStore.rootKeepAliveEnabled(context),
            usageAccess = hasUsageStatsAccess(context),
            globalRule = PurposeGateStore.globalFocusRule(context),
            rules = selected.associateWith { PurposeGateStore.focusRule(context, it) },
            ownRules = selected.filterTo(mutableSetOf()) { PurposeGateStore.hasFocusRule(context, it) },
            alpha = PurposeGateStore.overlayAlpha(context),
            scale = PurposeGateStore.overlayScale(context),
            timerPresets = PurposeGateStore.timerPresets(context),
            purposePresets = PurposeGateStore.purposePresets(context),
            bedtimeConfirmationDate = openBedtimeDate(now)?.takeUnless { it.toString() in confirmations },
            bedtimeToday = now.toLocalDate(),
            bedtimePending = PurposeGateStore.bedtimeEnabled(context) && now.toLocalTime().isBefore(BEDTIME_CHECK_AT),
            bedtimeDays = (1..month.lengthOfMonth()).filter { month.atDay(it).toString() in confirmations }
        )
    }

    private fun openBedtimeDate(now: ZonedDateTime): LocalDate? =
        if (PurposeGateStore.bedtimeEnabled(context)) {
            bedtimeConfirmationDate(now)
        } else null

    fun confirmBedtime(date: LocalDate): Boolean =
        openBedtimeDate(ZonedDateTime.now()) == date && PurposeGateStore.confirmBedtime(context, date)

    fun refreshBedtime() {
        if (!PurposeGateStore.bedtimeEnabled(context)) return
        BedtimeReminder.reschedule(context)
        BedtimeCheck.settle(context)
    }

    fun loadApps() = loadInstalledApps(context)
    fun saveRootKeepAlive(enabled: Boolean) = PurposeGateStore.setRootKeepAliveEnabled(context, enabled)
    fun rootKeepAliveNeedsUpdate() = PurposeGateStore.rootKeepAliveNeedsUpdate(context)
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
}
