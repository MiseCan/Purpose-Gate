package com.purposegate

/** 一个应用窗口在判定时需要的全部信息；悬浮层与输入法窗口不进入这里。 */
internal data class ForegroundWindow(
    val packageName: String?,
    val layer: Int,
    val focused: Boolean = false,
    val active: Boolean = false,
    val inPictureInPicture: Boolean = false,
    val windowId: Int = -1,
    val bounds: WindowBounds = WindowBounds.EMPTY
)

/** 纯几何实现，避免纯逻辑单元测试依赖 android.graphics。 */
internal data class WindowBounds(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    /** 两个窗口是否真的叠在一起；分屏里左右或上下相邻不算重叠。 */
    fun overlaps(other: WindowBounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    companion object {
        val EMPTY = WindowBounds()
    }
}

/**
 * 一次前台判定的结果。
 *
 * [packageName] 为 null 表示“读不到”，绝不能当成“还是上一个应用”。
 * [settling] 表示已经有一个更高层的应用窗口盖上来但还没取得焦点：
 * 用户已经在离开的路上，此时不能给旧应用弹窗，也不能把新应用当成已就位。
 */
internal data class ForegroundReading(
    val packageName: String?,
    val settling: Boolean = false
)

/**
 * 从一次窗口快照里选出真正的应用前台。
 *
 * 规则按优先级：
 * 1. 忽略画中画与被忽略的包（输入法、系统对话框、系统界面）。
 * 2. 自身全屏悬浮窗在最上面时取层级最高的应用窗口——此时焦点在悬浮窗上，
 *    应用窗口拿不到焦点，不能因为“没有焦点”就判成未知。
 * 3. 否则以取得焦点的窗口为准，其次才是 active 窗口。
 * 4. 若另有一个更高层、位置重叠、刚刚出现但还没拿到焦点的应用窗口，说明切换正在进行
 *    （冷启动或转场）：返回目的地并标记 [ForegroundReading.settling]。
 *
 * @param freshWindowIds 刚刚出现的窗口 id，用来区分“正在切换”和长期存在的异层窗口。
 */
internal fun readForeground(
    windows: List<ForegroundWindow>,
    ignoredPackages: Set<String>,
    freshWindowIds: Set<Int> = emptySet(),
    ownOverlayCovering: Boolean = false
): ForegroundReading {
    val candidates = windows
        .filter { !it.inPictureInPicture && it.packageName !in ignoredPackages }
        .sortedByDescending { it.layer }
    if (candidates.isEmpty()) return ForegroundReading(null)
    val chosen = if (ownOverlayCovering) {
        candidates.firstOrNull()
    } else {
        candidates.firstOrNull { it.focused } ?: candidates.firstOrNull { it.active }
    } ?: return ForegroundReading(null)

    val chosenPackage = chosen.packageName
    val incoming = if (ownOverlayCovering) null else candidates.firstOrNull { candidate ->
        candidate.layer > chosen.layer && candidate.windowId in freshWindowIds &&
            candidate.bounds.overlaps(chosen.bounds)
    }
    return when {
        incoming == null -> ForegroundReading(chosenPackage)
        incoming.packageName == null ->
            // 更高层但读不到包名：只能确认“还没就位”，不能指认是谁。
            ForegroundReading(chosenPackage, settling = true)
        incoming.packageName == chosenPackage ->
            // 同一个应用自己的新窗口（对话框、二级页面），不是切换。
            ForegroundReading(chosenPackage)
        else -> ForegroundReading(incoming.packageName, settling = true)
    }
}