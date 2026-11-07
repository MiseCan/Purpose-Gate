@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.purposegate

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.testTag
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

internal val LocalPurposeGateViewModel = staticCompositionLocalOf<PurposeGateViewModel> {
    error("PurposeGateViewModel must be provided by PurposeGateApp")
}

@Composable
internal fun PurposeGateApp(
    model: PurposeGateViewModel = viewModel(),
    navigator: PurposeNavigator = rememberPurposeNavigator()
) {
    val selectedName by model.selectedRoot.collectAsStateWithLifecycle()
    val selected = if (selectedName == Page.PAUSE.name) Page.PAUSE else Page.HOME
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.onResume()
            while (true) { model.refresh(); delay(2000) }
        }
    }
    val currentEntry by navigator.currentEntryAsState()
    val currentId by rememberUpdatedState(currentEntry?.id)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val swipe = if (rtl) NavSwipeDirection.RightToLeft else NavSwipeDirection.LeftToRight
    val navigate: (Page) -> Unit = { target ->
        val root = if (model.selectedRoot.value == Page.PAUSE.name) Page.PAUSE else Page.HOME
        model.selectRoot(navigator.open(target, root))
    }
    val latestNavigate by rememberUpdatedState(navigate)
    CompositionLocalProvider(LocalPurposeGateViewModel provides model) {
        NavDisplay(
            backStack = navigator.backStack,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("page_navigation"),
            transition = NavTransitions.MiuixDefault,
            effects = NavDisplayEffects(
                cornerClipRadius = rememberNavSystemCornerRadius(),
                blockInputDuringTransition = false,
                backdropColor = MaterialTheme.colorScheme.surface
            ),
            onBack = { navigator.pop() }
        ) {
            entry<PurposeRoute>(swipeDismiss = swipe) { route ->
                // Covered pages keep drawing, but only the actual top exposes accessibility actions.
                val active = currentId == route.id
                val semantics = if (active) Modifier else Modifier.clearAndSetSemantics { }
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().testTag("page_" + route.page.name).then(semantics)
                ) {
                    val pageNavigate: (Page) -> Unit = { target ->
                        if (navigator.currentEntry?.id == route.id) latestNavigate(target)
                    }
                    if (route.page == Page.HOME) RootPages(selected, pageNavigate)
                    else PageHost(route.page, pageNavigate, onBack = {
                        if (navigator.currentEntry?.id == route.id) navigator.pop()
                    })
                }
            }
        }
    }
}

/** Two peer tabs share one root destination; Back at either root exits to Android. */
@Composable
private fun RootPages(selected: Page, navigate: (Page) -> Unit) {
    val stateHolder = rememberSaveableStateHolder()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val tabs = updateTransition(selected, label = "root tabs")
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { FocusNavigation(if (selected == Page.PAUSE) 1 else 0,
            { navigate(Page.HOME) }, { navigate(Page.PAUSE) }) }
    ) { padding ->
        tabs.AnimatedContent(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).background(MaterialTheme.colorScheme.surface),
            transitionSpec = {
                // Peer destinations have no entering/covered hierarchy or directional Back motion.
                (fadeIn(effects) togetherWith fadeOut(effects)).using(null)
            }
        ) { page ->
            stateHolder.SaveableStateProvider(page.name) {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize().then(if (page == selected) Modifier else Modifier.clearAndSetSemantics { })
                ) {
                    PageHost(page, { target -> if (page == selected) navigate(target) })
                }
            }
        }
    }
}
