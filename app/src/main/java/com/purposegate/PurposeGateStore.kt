package com.purposegate

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalDate
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

internal data class AppVisitStats(val opens: Int = 0, val extendedMinutes: Int = 0)

object PurposeGateStore {
    private const val PREFS = "purpose_gate"
    private const val KEY_SELECTED = "selected_packages"
    private const val KEY_MODES = "modes"
    private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
    private const val KEY_OVERLAY_SCALE = "overlay_scale"
    private const val KEY_TIMER_PRESETS = "timer_presets"
    private const val KEY_PURPOSE_PRESETS = "purpose_presets"

    const val MODE_TIMER = "timer"
    const val MODE_PURPOSE = "purpose"



    val DEFAULT_TIMER_PRESETS = listOf(5, 15, 30)
    val DEFAULT_PURPOSE_PRESETS = listOf("查资料", "娱乐")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun rootKeepAliveEnabled(context: Context) =
        prefs(context).getBoolean("root_keep_alive", false)

    internal fun setRootKeepAliveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean("root_keep_alive", enabled)
            .putInt("root_keep_alive_script_version", RootKeepAlive.SCRIPT_VERSION)
            .apply()
    }

    internal fun rootKeepAliveNeedsUpdate(context: Context): Boolean =
        rootKeepAliveEnabled(context) &&
            prefs(context).getInt("root_keep_alive_script_version", 1) < RootKeepAlive.SCRIPT_VERSION

    internal fun bedtimeEnabled(context: Context) = prefs(context).getBoolean("bedtime_enabled", false)

    @Synchronized
    internal fun setBedtimeEnabled(context: Context, enabled: Boolean) {
        val editor = prefs(context).edit().putBoolean("bedtime_enabled", enabled)
        if (enabled) editor.putLong("bedtime_enabled_since", System.currentTimeMillis())
        else editor.remove("bedtime_enabled_since")
        editor.apply()
    }

    @Synchronized
    internal fun bedtimeEnabledSince(context: Context): Long? {
        if (!bedtimeEnabled(context)) return null
        val preferences = prefs(context)
        if (preferences.contains("bedtime_enabled_since")) {
            return preferences.getLong("bedtime_enabled_since", Long.MAX_VALUE)
        }
        // Existing installs start evaluating at migration, not before the feature existed.
        val now = System.currentTimeMillis()
        preferences.edit().putLong("bedtime_enabled_since", now).apply()
        return now
    }

    internal fun bedtimeEvaluated(context: Context, date: LocalDate) =
        prefs(context).getLong("bedtime_evaluated_day", Long.MIN_VALUE) == date.toEpochDay()

    @Synchronized
    internal fun finishBedtimeCheck(context: Context, enabledSince: Long, date: LocalDate, idle: Boolean) {
        if (bedtimeEnabledSince(context) != enabledSince) return
        val editor = prefs(context).edit().putLong("bedtime_evaluated_day", date.toEpochDay())
        if (idle) editor.putStringSet("bedtime_confirmations", bedtimeConfirmations(context) + date.toString())
        editor.apply()
    }

    internal fun bedtimeDelivered(context: Context, epochDay: Long) =
        prefs(context).getLong("bedtime_delivered_day", Long.MIN_VALUE) == epochDay

    internal fun markBedtimeDelivered(context: Context, epochDay: Long) {
        prefs(context).edit().putLong("bedtime_delivered_day", epochDay).apply()
    }

    internal fun bedtimeConfirmations(context: Context): Set<String> =
        prefs(context).getStringSet("bedtime_confirmations", emptySet()).orEmpty()

    @Synchronized
    internal fun confirmBedtime(context: Context, date: LocalDate): Boolean {
        val confirmations = bedtimeConfirmations(context)
        val day = date.toString()
        if (day in confirmations) return false
        prefs(context).edit().putStringSet("bedtime_confirmations", confirmations + day).apply()
        return true
    }

    /** Bridge service writes and UI writes into a lifecycle-cancelled observation stream. */
    internal fun observeChanges(context: Context): Flow<Unit> = callbackFlow {
        val preferences = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun selectedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SELECTED, emptySet())?.toSet().orEmpty()

    fun setSelectedPackages(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SELECTED, packages).apply()
        notifyFocusRules(context)
    }

    fun modes(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MODES, setOf(MODE_TIMER, MODE_PURPOSE))?.toSet().orEmpty()

    fun setModes(context: Context, modes: Set<String>) {
        prefs(context).edit().putStringSet(KEY_MODES, modes).apply()
        notifyFocusRules(context)
    }

    fun overlayAlpha(context: Context): Float =
        prefs(context).getFloat(KEY_OVERLAY_ALPHA, 1f).coerceIn(0.4f, 1f)

    fun setOverlayAlpha(context: Context, alpha: Float) {
        prefs(context).edit().putFloat(KEY_OVERLAY_ALPHA, alpha.coerceIn(0.4f, 1f)).apply()
    }

    fun overlayScale(context: Context): Float =
        prefs(context).getFloat(KEY_OVERLAY_SCALE, 1f).coerceIn(0.75f, 1.5f)

    fun setOverlayScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_OVERLAY_SCALE, scale.coerceIn(0.75f, 1.5f)).apply()
    }

    /**
     * 弹窗里的快捷选项。顺序就是显示顺序，用户可以增删和调整顺序；
     * 没有存过时用默认值，存成空表示用户把这些预设全删了（仍然可以现场自定义输入）。
     */
    fun timerPresets(context: Context): List<Int> {
        val raw = prefs(context).getString(KEY_TIMER_PRESETS, null) ?: return DEFAULT_TIMER_PRESETS
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..9999 }
    }

    fun setTimerPresets(context: Context, values: List<Int>) {
        prefs(context).edit()
            .putString(KEY_TIMER_PRESETS, values.filter { it in 1..9999 }.joinToString(","))
            .apply()
    }

    fun purposePresets(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_PURPOSE_PRESETS, null) ?: return DEFAULT_PURPOSE_PRESETS
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setPurposePresets(context: Context, values: List<String>) {
        prefs(context).edit()
            .putString(KEY_PURPOSE_PRESETS, values.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"))
            .apply()
    }

    // All new rules and statistics use the existing single persistence entry point.
    private fun notifyFocusRules(context: Context) {
        context.sendBroadcast(android.content.Intent(PurposeAccessibilityService.ACTION_RULES_CHANGED).setPackage(context.packageName))
    }
    fun globalFocusRule(context: Context): AppFocusRule =
        storedFocusRule(context, "global_focus_rule", AppFocusRule())

    fun hasFocusRule(context: Context, pkg: String): Boolean =
        prefs(context).contains("focus_rule_" + pkg)

    fun focusRule(context: Context, pkg: String): AppFocusRule {
        val key = "focus_rule_" + pkg
        return if (hasFocusRule(context, pkg)) storedFocusRule(context, key, AppFocusRule())
        else globalFocusRule(context)
    }

    private fun storedFocusRule(context: Context, key: String, fallback: AppFocusRule): AppFocusRule {
        val p = prefs(context)
        val raw = p.getString(key, null) ?: return fallback
        return runCatching {
            val o = org.json.JSONObject(raw)
            // Split the legacy shared delay without losing deliberate non-default settings.
            if (o.has("pause")) {
                val legacyPause = o.optInt("pause", 5).coerceIn(0, 60)
                if (!o.has("confirm_delay")) {
                    o.put("confirm_delay", if (legacyPause == 5) fallback.confirmDelaySeconds else legacyPause)
                }
                if (!o.has("skip_delay")) o.put("skip_delay", fallback.skipDelaySeconds)
                o.remove("pause")
                p.edit().putString(key, o.toString()).apply()
            }
            // Drop retired features while preserving the other global/app settings.
            if (o.has("allow") || o.has("skip_grace") || o.has("budget")) {
                o.remove("allow")
                o.remove("skip_grace")
                o.remove("budget")
                p.edit().putString(key, o.toString()).apply()
            }
            AppFocusRule(
                confirmDelaySeconds = o.optInt("confirm_delay", fallback.confirmDelaySeconds).coerceIn(0, 60),
                skipDelaySeconds = o.optInt("skip_delay", fallback.skipDelaySeconds).coerceIn(0, 60),
                cooldownSeconds = o.optInt("cooldown", fallback.cooldownSeconds).coerceIn(0, 3600),
                allowSkip = o.optBoolean("skip", fallback.allowSkip),
                sessionRetentionSeconds = o.optInt("session_retention", fallback.sessionRetentionSeconds).coerceIn(0, 3600)
            )
        }.getOrDefault(fallback)
    }

    private fun focusRuleJson(rule: AppFocusRule): String =
        org.json.JSONObject()
            .put("confirm_delay", rule.confirmDelaySeconds.coerceIn(0, 60))
            .put("skip_delay", rule.skipDelaySeconds.coerceIn(0, 60))
            .put("cooldown", rule.cooldownSeconds.coerceIn(0, 3600))
            .put("skip", rule.allowSkip)
            .put("session_retention", rule.sessionRetentionSeconds.coerceIn(0, 3600))
            .toString()

    fun setGlobalFocusRule(context: Context, rule: AppFocusRule) {
        prefs(context).edit().putString("global_focus_rule", focusRuleJson(rule)).apply()
        notifyFocusRules(context)
    }

    fun setFocusRule(context: Context, pkg: String, rule: AppFocusRule) {
        prefs(context).edit().putString("focus_rule_" + pkg, focusRuleJson(rule)).apply()
        notifyFocusRules(context)
    }

    fun clearFocusRule(context: Context, pkg: String) {
        prefs(context).edit().remove("focus_rule_" + pkg).apply()
        notifyFocusRules(context)
    }


    private fun focusDate(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())

    fun focusUsageMs(context: Context, pkg: String): Long {
        val p = prefs(context)
        return if (p.getString("focus_day", null) == focusDate()) p.getLong("focus_used_" + pkg, 0L) else 0L
    }

    fun focusCount(context: Context, name: String): Int {
        val p = prefs(context)
        return if (p.getString("focus_day", null) == focusDate()) p.getInt("focus_count_" + name, 0) else 0
    }

    internal fun appVisitStats(context: Context, pkg: String): AppVisitStats = AppVisitStats(
        opens = focusCount(context, "app_opens_$pkg"),
        extendedMinutes = focusCount(context, "app_extended_minutes_$pkg")
    )

    internal fun recordAppOpen(context: Context, pkg: String) =
        recordFocusEvent(context, "app_opens_$pkg")

    internal fun recordAppExtension(context: Context, pkg: String, minutes: Int) =
        addFocusCount(context, "app_extended_minutes_$pkg", minutes)

    private fun focusEditor(context: Context, day: String = focusDate()): SharedPreferences.Editor {
        val p = prefs(context)
        val edit = p.edit()
        if (p.getString("focus_day", null) != day) {
            p.all.keys.filter { it.startsWith("focus_used_") || it.startsWith("focus_count_") }.forEach { edit.remove(it) }
            edit.putString("focus_day", day)
        }
        return edit
    }

    fun addFocusUsage(context: Context, pkg: String, elapsedMs: Long) {
        if (elapsedMs <= 0L) return
        val now = System.currentTimeMillis()
        val midnight = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        // Only the part of this interval after local midnight belongs to today's quota.
        val delta = minOf(elapsedMs, (now - midnight).coerceAtLeast(0L))
        focusEditor(context).putLong("focus_used_" + pkg, focusUsageMs(context, pkg) + delta).apply()
    }

    fun recordFocusEvent(context: Context, name: String) {
        addFocusCount(context, name, 1)
    }

    private fun addFocusCount(context: Context, name: String, amount: Int) {
        val day = focusDate()
        val key = "focus_count_$name"
        val preferences = prefs(context)
        val previous = if (preferences.getString("focus_day", null) == day) preferences.getInt(key, 0) else 0
        focusEditor(context, day).putInt(key, previous + amount).apply()
    }
}
