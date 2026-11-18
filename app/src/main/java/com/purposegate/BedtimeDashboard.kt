package com.purposegate

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun BedtimeConfirmationCard(onConfirm: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("今晚不带手机上床", style = MaterialTheme.typography.titleLarge)
            Text("准备上床时，把手机留在床外。可在 00:30 前确认。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onConfirm, shape = MaterialTheme.shapes.medium) { Text("我同意") }
        }
    }
}

private enum class BedtimeDayStatus { CONFIRMED, UNCONFIRMED, PENDING, FUTURE }

@Composable
internal fun BedtimeMonthCard(today: LocalDate, days: List<Int>, pendingToday: Boolean, modifier: Modifier = Modifier) {
    val month = YearMonth.from(today)
    val monthDays = 1..month.lengthOfMonth()
    val leadingSpaces = month.atDay(1).dayOfWeek.value - 1
    val weekCount = (leadingSpaces + monthDays.last + 6) / 7
    val colors = MaterialTheme.colorScheme
    fun statusOf(day: Int): BedtimeDayStatus? = when {
        day !in monthDays -> null
        day in days -> BedtimeDayStatus.CONFIRMED
        day == today.dayOfMonth && pendingToday -> BedtimeDayStatus.PENDING
        day > today.dayOfMonth -> BedtimeDayStatus.FUTURE
        else -> BedtimeDayStatus.UNCONFIRMED
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("睡前月历", style = MaterialTheme.typography.titleMedium)
            Text("${month.monthValue} 月 · 已确认 ${days.size} 晚",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
            Row(Modifier.fillMaxWidth()) {
                for (weekday in "一二三四五六日") {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(weekday.toString(), style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant)
                    }
                }
            }
            repeat(weekCount) { week ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val day = week * 7 + column - leadingSpaces + 1
                        val dayStatus = statusOf(day)
                        if (dayStatus == null) {
                            Spacer(Modifier.weight(1f))
                        } else {
                            val (background, foreground, status) = when (dayStatus) {
                                BedtimeDayStatus.CONFIRMED -> Triple(colors.primary, colors.onPrimary, "已确认")
                                BedtimeDayStatus.PENDING -> Triple(colors.surfaceContainer, colors.onSurfaceVariant, "待确认")
                                BedtimeDayStatus.FUTURE -> Triple(colors.surfaceContainer, colors.onSurfaceVariant, "未来日期")
                                BedtimeDayStatus.UNCONFIRMED -> Triple(colors.errorContainer, colors.onErrorContainer, "未确认")
                            }
                            val joinsStart = column > 0 && statusOf(day - 1) == dayStatus
                            val joinsEnd = column < 6 && statusOf(day + 1) == dayStatus
                            val startRadius = if (joinsStart) 0.dp else 6.dp
                            val endRadius = if (joinsEnd) 0.dp else 6.dp
                            Surface(
                                modifier = Modifier.weight(1f)
                                    .padding(start = if (joinsStart) 0.dp else 1.dp, end = if (joinsEnd) 0.dp else 1.dp)
                                    .clearAndSetSemantics {
                                        contentDescription = "${month.monthValue}月${day}日，$status"
                                    },
                                shape = RoundedCornerShape(
                                    topStart = startRadius, bottomStart = startRadius,
                                    topEnd = endRadius, bottomEnd = endRadius
                                ),
                                color = background,
                                contentColor = foreground
                            ) {
                                Box(Modifier.heightIn(min = 32.dp).padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center) {
                                    Text(day.toString(), style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BedtimeCelebration(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val latestFinish by rememberUpdatedState(onFinished)
    val colors = listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.error
    )
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 1300, easing = LinearEasing))
        delay(450)
        latestFinish()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            for (burst in 0..2) {
                val t = (progress.value * 1.25f - burst * .12f).coerceIn(0f, 1f)
                if (t == 0f || t == 1f) continue
                val origin = Offset(size.width * (.27f + burst * .23f), size.height * (.38f + (burst % 2) * .18f))
                val reach = size.minDimension * .29f * (1f - (1f - t) * (1f - t))
                for (ray in 0 until 16) {
                    val angle = 2 * PI * ray / 16 + burst * .2
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    val end = origin + direction * (reach * (.72f + (ray % 3) * .14f)) +
                        Offset(0f, size.minDimension * .12f * t * t)
                    val color = colors[(burst + ray) % colors.size].copy(alpha = 1f - t)
                    drawLine(color, end - direction * (10.dp.toPx() * (1f - t)), end,
                        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(color, radius = 2.5.dp.toPx(), center = end)
                }
            }
        }
        Surface(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        ) {
            Text("自律 +1", Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.headlineSmall)
        }
    }
}
