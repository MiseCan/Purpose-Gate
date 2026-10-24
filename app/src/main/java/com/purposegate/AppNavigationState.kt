package com.purposegate

/** HOME is the persistent tab container; the selected tab is independent of the child stack. */
internal data class AppNavigationState(
    val selectedRoot: Page = Page.HOME,
    val backStack: List<Page> = listOf(Page.HOME)
) {
    fun open(target: Page): AppNavigationState = when (target) {
        Page.HOME, Page.STATS -> AppNavigationState(Page.HOME)
        Page.PAUSE -> AppNavigationState(Page.PAUSE)
        Page.SETTINGS -> AppNavigationState(Page.HOME, listOf(Page.HOME, Page.SETTINGS))
        Page.ABOUT, Page.PRESETS, Page.APPEARANCE, Page.CHECKS ->
            AppNavigationState(Page.HOME, listOf(Page.HOME, Page.SETTINGS, target))
        Page.RULES -> AppNavigationState(Page.PAUSE, listOf(Page.HOME, Page.RULES))
        Page.APPS -> copy(backStack = listOf(Page.HOME, Page.APPS))
    }

    // Root tabs delegate Back to Android and never return to each other.
    fun pop(): AppNavigationState =
        if (backStack.size > 1) copy(backStack = backStack.dropLast(1)) else this
}
