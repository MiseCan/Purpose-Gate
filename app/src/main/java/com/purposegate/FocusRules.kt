package com.purposegate

/** Shared defaults and per-app overrides have the same rule shape. */
data class AppFocusRule(
    val pauseSeconds: Int = 5,
    val cooldownSeconds: Int = 0,
    val dailyLimitMinutes: Int = 0,
    val allowSkip: Boolean = true,
    val sessionRetentionSeconds: Int = 30
)

internal enum class FocusDecision { DAILY_LIMIT, COOLDOWN, PROMPT }

/** Application windows have already been filtered; our main Activity is a real foreground app. */
internal fun verifiedForegroundPackage(eventPackage: String, topPackage: String?, ignoredTopPackages: Set<String>): String =
    if (topPackage != null && topPackage !in ignoredTopPackages) topPackage else eventPackage
internal fun focusDecision(
    rule: AppFocusRule,
    usedMs: Long,
    now: Long,
    cooldownUntil: Long?
): FocusDecision = when {
    rule.dailyLimitMinutes > 0 && usedMs >= rule.dailyLimitMinutes * 60_000L -> FocusDecision.DAILY_LIMIT
    rule.cooldownSeconds > 0 && cooldownUntil != null && now < cooldownUntil -> FocusDecision.COOLDOWN
    else -> FocusDecision.PROMPT
}

/** Cooldown starts when an actual visit ends, and keeps its original deadline. */
internal class VisitPolicy {
    private val cooling = mutableMapOf<String, Long>()

    fun leave(pkg: String, now: Long, rule: AppFocusRule) {
        if (rule.cooldownSeconds > 0) cooling[pkg] = now + rule.cooldownSeconds * 1000L
    }

    fun decision(pkg: String, rule: AppFocusRule, usedMs: Long, now: Long) =
        focusDecision(rule, usedMs, now, cooling[pkg])

    fun cooldownRemaining(pkg: String, now: Long): Long =
        ((cooling[pkg] ?: now) - now).coerceAtLeast(0L)
}
