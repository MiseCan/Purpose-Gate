package com.purposegate

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import top.yukonga.miuix.kmp.nav.transition.NavSettleSpec
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

/** Child navigation timing for transition checks; peer tabs use Material motion effects. */
internal object PageMotion {
    private val timing: NavSettleSpec.Tween
        get() = NavTransitions.MiuixDefault.motion.programmatic as NavSettleSpec.Tween
    val DurationMillis: Int get() = timing.durationMillis
    fun <T> spec(): TweenSpec<T> = tween(timing.durationMillis, easing = timing.easing)
}
