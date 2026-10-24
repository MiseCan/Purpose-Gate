package com.purposegate

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

private class LastContentSize(var value: IntSize = IntSize.Zero)

/**
 * A faded-out page must leave the layout's hit-test tree even while spatial springs finish.
 * The current page always stays live. No event interception, disabled controls or unlock timer.
 */
@Composable
internal fun AnimatedContentScope.FadingContent(
    isCurrent: Boolean,
    fadeSpec: FiniteAnimationSpec<Float>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val opacity = transition.animateFloat(transitionSpec = { fadeSpec }, label = "content presence") {
        if (it == EnterExitState.Visible) 1f else 0f
    }
    val showContent = isCurrent || opacity.value > 0.001f
    val savedState = rememberSaveableStateHolder()
    val lastSize = remember { LastContentSize() }

    // Preserve the outgoing layer's dimensions, but remove its controls once it is transparent.
    Layout(modifier = modifier, content = {
        if (showContent) {
            savedState.SaveableStateProvider("content") { Box { content() } }
        }
    }) { measurables, constraints ->
        val placeable = measurables.singleOrNull()?.measure(constraints)
        if (placeable != null) lastSize.value = IntSize(placeable.width, placeable.height)
        layout(constraints.constrainWidth(lastSize.value.width), constraints.constrainHeight(lastSize.value.height)) {
            placeable?.placeRelative(0, 0)
        }
    }
}
