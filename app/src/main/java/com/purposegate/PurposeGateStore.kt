package com.purposegate

import android.content.Context

object PurposeGateStore {
    fun ongoingTitle(c: Context) = prefs(c).getString("ongoing_title", "PurposeGate 正在运行")!!
    fun ongoingText(c: Context) = prefs(c).getString("ongoing_text", "停顿监听已连接 · 点此检查权限与后台设置")!!
    fun saveOngoing(c: Context, title: String, text: String) {
        prefs(c).edit().putString("ongoing_title", title.trim().take(80).ifBlank { "PurposeGate 正在运行" }).putString("ongoing_text", text.trim().take(200)).apply()
    }

    private const val PREFS = "purpose_gate"
    private const val KEY_SELECTED = "selected_packages"
    private const val KEY_MODES = "modes"
    private const val KEY_OVERLAY_ALPHA = "overlay_alpha"
    private const val KEY_OVERLAY_SCALE = "overlay_scale"
    private const val KEY_BAR_MODE = "bar_mode"
    private const val KEY_TIMER_PRESETS = "timer_presets"
    private const val KEY_PURPOSE_PRESETS = "purpose_presets"

    const val MODE_TIMER = "timer"
    const val MODE_PURPOSE = "purpose"

    /** 悬浮条的两种承载方式：无障碍悬浮窗，或系统原生画中画。 */
    const val BAR_MODE_OVERLAY = "overlay"
    const val BAR_MODE_PIP = "pip"

    val DEFAULT_TIMER_PRESETS = listOf(5, 15, 30)
    val DEFAULT_PURPOSE_PRESETS = listOf("查资料", "娱乐")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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

    fun barMode(context: Context): String =
        prefs(context).getString(KEY_BAR_MODE, BAR_MODE_PIP) ?: BAR_MODE_PIP

    fun setBarMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_BAR_MODE, mode).apply()
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
            // Drop retired features while preserving the other global/app settings.
            if (o.has("allow") || o.has("skip_grace")) {
                o.remove("allow")
                o.remove("skip_grace")
                p.edit().putString(key, o.toString()).apply()
            }
            AppFocusRule(
                pauseSeconds = o.optInt("pause", fallback.pauseSeconds).coerceIn(0, 60),
                cooldownSeconds = o.optInt("cooldown", fallback.cooldownSeconds).coerceIn(0, 3600),
                dailyLimitMinutes = o.optInt("budget", fallback.dailyLimitMinutes).coerceIn(0, 1440),
                allowSkip = o.optBoolean("skip", fallback.allowSkip),
                sessionRetentionSeconds = o.optInt("session_retention", fallback.sessionRetentionSeconds).coerceIn(0, 3600)
            )
        }.getOrDefault(fallback)
    }

    private fun focusRuleJson(rule: AppFocusRule): String =
        org.json.JSONObject()
            .put("pause", rule.pauseSeconds.coerceIn(0, 60))
            .put("cooldown", rule.cooldownSeconds.coerceIn(0, 3600))
            .put("budget", rule.dailyLimitMinutes.coerceIn(0, 1440))
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

    private fun focusEditor(context: Context): android.content.SharedPreferences.Editor {
        val p = prefs(context)
        val edit = p.edit()
        if (p.getString("focus_day", null) != focusDate()) {
            p.all.keys.filter { it.startsWith("focus_used_") || it.startsWith("focus_count_") }.forEach { edit.remove(it) }
            edit.putString("focus_day", focusDate())
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
        focusEditor(context).putInt("focus_count_" + name, focusCount(context, name) + 1).apply()
    }
}
