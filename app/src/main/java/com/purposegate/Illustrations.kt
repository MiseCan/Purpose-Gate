package com.purposegate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/**
 * M3 风格几何插图：只用圆形、圆角矩形和主题色角色拼出来，
 * 因此会跟着动态取色一起变化，不需要额外的位图资源。
 */
@Composable
fun PauseIllustration(modifier: Modifier = Modifier, accent: Color? = null) {
    val container = accent ?: MaterialTheme.colorScheme.primaryContainer
    val arch = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    Canvas(modifier) {
        val side = min(size.width, size.height)
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        drawCircle(color = container, radius = side * 0.46f, center = Offset(centerX, centerY))

        // 一道“门”：上方半圆、下方方形的拱形
        val gateWidth = side * 0.34f
        val gateHeight = side * 0.46f
        val gateLeft = centerX - gateWidth / 2f
        val gateTop = centerY - gateHeight * 0.52f
        drawRoundRect(
            color = arch,
            topLeft = Offset(gateLeft, gateTop),
            size = Size(gateWidth, gateWidth),
            cornerRadius = CornerRadius(gateWidth / 2f, gateWidth / 2f)
        )
        drawRect(
            color = arch,
            topLeft = Offset(gateLeft, gateTop + gateWidth / 2f),
            size = Size(gateWidth, gateHeight - gateWidth / 2f)
        )

        // 门里的停顿符号，用底色“挖”出来
        val barWidth = gateWidth * 0.13f
        val barHeight = gateWidth * 0.42f
        val barTop = gateTop + gateWidth * 0.30f
        val barGap = gateWidth * 0.15f
        drawRoundRect(
            color = container,
            topLeft = Offset(centerX - barGap / 2f - barWidth, barTop),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        )
        drawRoundRect(
            color = container,
            topLeft = Offset(centerX + barGap / 2f, barTop),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        )

        drawCircle(color = tertiary, radius = side * 0.065f, center = Offset(size.width * 0.24f, size.height * 0.26f))
        drawCircle(color = secondary, radius = side * 0.042f, center = Offset(size.width * 0.79f, size.height * 0.77f))
    }
}

/** M3 的“状态/空态”排版：圆形图标底 + 标题 + 说明，整体居中对齐。 */
@Composable
fun IllustratedState(
    codePoint: Int,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 72.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(shape = CircleShape, color = containerColor, contentColor = contentColor) {
            Box(Modifier.size(iconSize), contentAlignment = Alignment.Center) {
                Symbol(codePoint, size = (iconSize.value * 0.45f).sp, tint = contentColor)
            }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/** 用于列表里的应用图标底板，统一 12dp 圆角与 M3 容器色。 */
@Composable
fun IconTile(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}
