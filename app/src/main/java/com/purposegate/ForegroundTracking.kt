package com.purposegate

/** Only application windows are passed here; input methods and accessibility overlays are excluded. */
internal data class ForegroundWindow(
    val packageName: String?,
    val layer: Int,
    val focused: Boolean = false,
    val active: Boolean = false,
    val inPictureInPicture: Boolean = false,
    val windowId: Int = -1
)

internal fun foregroundWindowPackage(
    windows: List<ForegroundWindow>,
    ignoredPackages: Set<String>,
    requireActive: Boolean = false,
    eventPackages: Map<Int, String> = emptyMap()
): String? {
    val candidates = windows.filter {
        !it.inPictureInPicture && it.packageName !in ignoredPackages
    }.sortedByDescending { it.layer }
    val top = candidates.firstOrNull { it.focused }
        ?: candidates.firstOrNull { it.active }
        ?: if (requireActive) null else candidates.firstOrNull()
    // An unreadable foreground root is unknown, not evidence that a background app is on top.
    val resolved = top?.packageName ?: top?.let { eventPackages[it.windowId] }
    return resolved?.takeUnless { it in ignoredPackages }
}
