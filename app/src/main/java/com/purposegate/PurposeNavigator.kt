package com.purposegate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import org.json.JSONObject
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import java.util.UUID

/** Stable instance identity keeps page-local state attached to the correct stack entry. */
internal data class PurposeRoute(val page: Page, val id: String = UUID.randomUUID().toString()) : NavKey

@Stable
internal class PurposeNavigator(
    val backStack: NavBackStack = navBackStackOf(PurposeRoute(Page.HOME))
) {
    val currentEntry get() = backStack.lastOrNull() as? PurposeRoute

    fun open(target: Page, selectedRoot: Page): Page {
        val destination = AppNavigationState(selectedRoot).open(target)
        val wanted = destination.backStack
        // Preserve matching parent identities; never rebuild Settings when returning to it.
        Snapshot.withMutableSnapshot {
            var common = 0
            while (common < wanted.size && common < backStack.size &&
                (backStack[common] as PurposeRoute).page == wanted[common]) common++
            while (backStack.size > common) backStack.removeAt(backStack.lastIndex)
            wanted.drop(common).forEach { backStack.add(PurposeRoute(it)) }
        }
        return destination.selectedRoot
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun save(): ArrayList<String> = ArrayList(backStack.map {
        val route = it as PurposeRoute
        JSONObject().put("page", route.page.name).put("id", route.id).toString()
    })

    companion object {
        fun restore(saved: List<String>): PurposeNavigator = runCatching {
            val routes = saved.map {
                val item = JSONObject(it)
                PurposeRoute(Page.valueOf(item.getString("page")), item.getString("id"))
            }
            require(routes.firstOrNull()?.page == Page.HOME)
            require(routes.map { it.id }.distinct().size == routes.size)
            require(routes.none { it.page == Page.PAUSE || it.page == Page.STATS })
            PurposeNavigator(navBackStackOf(*routes.toTypedArray()))
        }.getOrElse { PurposeNavigator() }
    }
}

@Composable
internal fun rememberPurposeNavigator(): PurposeNavigator = rememberSaveable(
    saver = Saver<PurposeNavigator, ArrayList<String>>(save = { it.save() }, restore = { PurposeNavigator.restore(it) })
) { PurposeNavigator() }

@Composable
internal fun PurposeNavigator.currentEntryAsState() = remember(this) { derivedStateOf { currentEntry } }
