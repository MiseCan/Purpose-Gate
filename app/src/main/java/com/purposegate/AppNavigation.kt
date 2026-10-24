@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.purposegate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent

private val NavigationStateSaver = listSaver<AppNavigationState, String>(
    save = { listOf(it.selectedRoot.name) + it.backStack.map(Page::name) },
    restore = { saved ->
        val root = if (saved.firstOrNull() == Page.PAUSE.name) Page.PAUSE else Page.HOME
        val stack = saved.drop(1).mapNotNull { name -> Page.entries.find { it.name == name } }
        AppNavigationState(root, stack.ifEmpty { listOf(Page.HOME) })
    }
)

@Composable
internal fun PurposeGateApp() {
    var navigation by rememberSaveable(stateSaver = NavigationStateSaver) {
        mutableStateOf(AppNavigationState())
    }
    val navigate: (Page) -> Unit = { navigation = navigation.open(it) }
    val motion = MaterialTheme.motionScheme
    val effects = motion.defaultEffectsSpec<Float>()
    val spatial = motion.defaultSpatialSpec<Float>()
    val offset = motion.defaultSpatialSpec<IntOffset>()
    val distance = with(LocalDensity.current) { 64.dp.roundToPx() }

    NavDisplay(
        backStack = navigation.backStack,
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        onBack = { navigation = navigation.pop() },
        transitionSpec = {
            (fadeIn(effects) + slideInHorizontally(offset) { distance } +
                scaleIn(spatial, initialScale = 0.98f)).togetherWith(
                fadeOut(effects) + slideOutHorizontally(offset) { -distance / 3 } +
                    scaleOut(spatial, targetScale = 0.99f)
            )
        },
        popTransitionSpec = {
            (fadeIn(effects) + slideInHorizontally(offset) { -distance / 3 } +
                scaleIn(spatial, initialScale = 0.99f)).togetherWith(
                fadeOut(effects) + slideOutHorizontally(offset) { distance } +
                    scaleOut(spatial, targetScale = 0.98f)
            )
        },
        predictivePopTransitionSpec = { edge ->
            val direction = if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1
            (fadeIn(effects, initialAlpha = 0.6f) + scaleIn(spatial, initialScale = 0.98f))
                .togetherWith(
                    fadeOut(effects) + scaleOut(spatial, targetScale = 0.92f) +
                        slideOutHorizontally(offset) { distance * direction }
                )
        },
        entryProvider = { page ->
            NavEntry(page, contentKey = page.name) {
                AnimatedPageSurface(isActive = page == navigation.backStack.last()) {
                    val pageNavigate: (Page) -> Unit = { target ->
                        if (page == navigation.backStack.last()) navigate(target)
                    }
                    if (page == Page.HOME) {
                        RootPages(navigation.selectedRoot, pageNavigate)
                    } else {
                        PageHost(page, pageNavigate, appsParent = navigation.selectedRoot)
                    }
                }
            }
        }
    )
}

/** Keep the dock still and restore the original MD3E effects-only tab crossfade. */
@Composable
private fun RootPages(selected: Page, navigate: (Page) -> Unit) {
    val stateHolder = rememberSaveableStateHolder()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val tabs = updateTransition(selected, label = "root tabs")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            FocusNavigation(if (selected == Page.PAUSE) 1 else 0,
                { navigate(Page.HOME) }, { navigate(Page.PAUSE) })
        }
    ) { padding ->
        tabs.AnimatedContent(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            transitionSpec = { fadeIn(effects) togetherWith fadeOut(effects) }
        ) { page ->
            FadingContent(isCurrent = page == selected, fadeSpec = effects) {
                stateHolder.SaveableStateProvider(page.name) { PageHost(page, navigate) }
            }
        }
    }
}

/** MD3E motion stays unchanged; transparent outgoing content leaves the hit-test tree. */
@Composable
private fun AnimatedPageSurface(isActive: Boolean, content: @Composable () -> Unit) {
    val scope = LocalNavAnimatedContentScope.current
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.Dp>()
    val corners = scope.transition.animateDp(
        transitionSpec = { spatial },
        label = "page corners"
    ) { state -> if (state == EnterExitState.Visible) 0.dp else 28.dp }
    scope.FadingContent(
        isCurrent = isActive,
        fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        modifier = Modifier.fillMaxSize().graphicsLayer {
            shape = RoundedCornerShape(corners.value.coerceAtLeast(0.dp))
            clip = true
        },
        content = content
    )
}
