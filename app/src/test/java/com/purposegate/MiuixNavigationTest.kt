package com.purposegate

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.compose.ui.geometry.Offset
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class MiuixNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var model: PurposeGateViewModel
    private lateinit var nav: PurposeNavigator
    private lateinit var back: OnBackPressedDispatcher

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("purpose_gate", 0).edit().clear().commit()
        model = PurposeGateViewModel(app, SavedStateHandle())
        store.put("app", model)
    }

    @After fun tearDown() { store.clear() }

    private fun start() {
        compose.setContent {
            nav = rememberPurposeNavigator()
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            PurposeGateTheme { PurposeGateApp(model, nav) }
        }
        compose.waitForIdle()
    }

    private fun reveal(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    private fun open(text: String) {
        reveal(text)
        compose.onNodeWithText(text).performClick()
    }

    @Test fun predictiveBackCanCancelAndThenCommit() {
        start()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.runOnIdle {
            back.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(100f, 300f, .3f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Page.SETTINGS, nav.currentEntry?.page) }
        compose.runOnIdle {
            back.dispatchOnBackStarted(BackEventCompat(0f, 300f, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(300f, 300f, .8f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnIdle { back.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Page.HOME, nav.currentEntry?.page) }
    }

    @Test fun pageInteriorSwipeUsesMiuixBackGesture() {
        start()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithTag("page_navigation").performTouchInput {
            swipe(Offset(width * .3f, height * .5f), Offset(width * .96f, height * .5f), 240)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Page.HOME, nav.currentEntry?.page) }
    }

    @Test fun savedStackPreservesEntryIdentityAndParent() {
        val original = PurposeNavigator()
        original.open(Page.SETTINGS, Page.HOME)
        val parentId = original.currentEntry!!.id
        original.open(Page.APPEARANCE, Page.HOME)
        val restored = PurposeNavigator.restore(original.save())
        assertEquals(original.currentEntry, restored.currentEntry)
        restored.pop()
        assertEquals(parentId, restored.currentEntry!!.id)
        assertEquals(Page.SETTINGS, restored.currentEntry!!.page)
    }

    @Test fun horizontalSliderKeepsItsDragInsteadOfPoppingThePage() {
        start()
        compose.onNodeWithContentDescription("设置").performClick()
        open("悬浮条外观")
        reveal("透明度")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))[0].performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress
        ) { it(.5f) }
        compose.waitUntil(5000) { model.uiState.value.alpha < .6f }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))[0].performScrollTo()
        val before = model.uiState.value.alpha
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))[0].performTouchInput {
            swipe(Offset(width * .25f, height * .5f), Offset(width * .8f, height * .5f), 300)
        }
        compose.waitForIdle()
        compose.waitUntil(5000) { model.uiState.value.alpha > before }
        compose.runOnIdle {
            assertEquals(Page.APPEARANCE, nav.currentEntry?.page)
            assertTrue("Slider must receive the rightward drag", model.uiState.value.alpha > before)
        }
    }

    @Test fun peerTabsAndFocusChildrenUseTheMiuixBackStack() {
        start()
        compose.onNodeWithText("专注").performClick()
        reveal("打开前提醒")
        compose.onNodeWithText("打开前提醒").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(Page.PAUSE.name, model.selectedRoot.value)
            assertEquals(Page.HOME.name, nav.currentEntry?.page?.name)
        }
        open("管控应用")
        compose.onNodeWithText("选择应用").assertIsDisplayed()
        compose.runOnIdle { assertEquals(Page.APPS.name, nav.currentEntry?.page?.name) }
        compose.onNodeWithContentDescription("返回").performClick()
        reveal("打开前提醒")
        compose.onNodeWithText("打开前提醒").assertIsDisplayed()
        open("专注规则")
        reveal("按应用设置边界")
        compose.onNodeWithText("按应用设置边界").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle {
            assertEquals(Page.PAUSE.name, model.selectedRoot.value)
            assertFalse("Neither peer tab creates a Back destination", nav.pop())
        }
    }

    @Test fun everySettingsChildReturnsToItsExistingParent() {
        start()
        compose.onNodeWithContentDescription("设置").performClick()
        val children = listOf(
            Triple("时间与目的预设", "预设管理", Page.PRESETS),
            Triple("悬浮条外观", "悬浮窗设置", Page.APPEARANCE),
            Triple("管控状态与权限", "管控状态与权限", Page.CHECKS),
            Triple("了解 PurposeGate", "关于", Page.ABOUT)
        )
        children.forEach { (label, title, page) ->
            open(label)
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.runOnIdle { assertEquals(page.name, nav.currentEntry?.page?.name) }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.runOnIdle { assertEquals(Page.SETTINGS.name, nav.currentEntry?.page?.name) }
        }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertEquals(Page.HOME.name, nav.currentEntry?.page?.name) }
    }

    @Test fun theNewPageReceivesRealTouchesImmediatelyAfterTheSharedTransitionEnds() {
        start()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("设置").performTouchInput { click() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(PageMotion.DurationMillis.toLong())
        reveal("时间与目的预设")
        compose.onNodeWithText("时间与目的预设").performTouchInput { click() }
        compose.runOnIdle { assertEquals(Page.PRESETS.name, nav.currentEntry?.page?.name) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(PageMotion.DurationMillis.toLong())
        compose.onNodeWithContentDescription("返回").performTouchInput { click() }
        compose.runOnIdle { assertEquals(Page.SETTINGS.name, nav.currentEntry?.page?.name) }
        compose.mainClock.autoAdvance = true
    }
}
