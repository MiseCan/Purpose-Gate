package com.purposegate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class FadingContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun newPageReceivesTouchWhileTheInvisibleOldLayerIsStillAnimating() {
        var page by mutableStateOf(false)
        var oldClicks = 0
        var newClicks = 0
        lateinit var animation: Transition<Boolean>
        run {
            compose.setContent {
                val transition = updateTransition(page, label = "page")
                animation = transition
                transition.AnimatedContent(transitionSpec = {
                    (fadeIn(tween(120)) togetherWith fadeOut(tween(120))).apply {
                        // Match a Back transition: the outgoing scene sits above the new page.
                        targetContentZIndex = if (targetState) -1f else 0f
                    }
                }) { target ->
                    // Simulate the slow corner/spatial spring retaining the old scene after fade-out.
                    this.transition.animateFloat(transitionSpec = { tween(900) }, label = "spatial tail") {
                        if (it == EnterExitState.Visible) 1f else 0f
                    }
                    FadingContent(isCurrent = target == page, fadeSpec = tween(120)) {
                        Box(Modifier.size(200.dp).testTag(if (target) "new" else "old").clickable {
                            if (target) newClicks++ else oldClicks++
                        })
                    }
                }
            }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { Snapshot.withMutableSnapshot { page = true } }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(180)
            compose.onNodeWithTag("new").performTouchInput { click() }
            compose.runOnIdle {
                assertTrue("The old scene's spatial animation must still be running", animation.isRunning)
                assertEquals(0, oldClicks)
                assertEquals(1, newClicks)
            }
        }
    }
}
