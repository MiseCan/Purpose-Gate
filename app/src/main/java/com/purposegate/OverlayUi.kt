@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.purposegate

/**
 * 停顿弹窗与悬浮条的界面。
 *
 * 这些界面跑在无障碍服务创建的悬浮层里：服务决定显示哪个状态，这里只负责渲染。
 * 各按钮的等待（按应用设置的停顿、时间到 10 秒）也在各自的组件里。
 */

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.TextField
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 停顿弹窗刚出现时先禁止跳过，避免条件反射式地点掉。 */

/** 计时结束的提醒弹窗：继续按钮要等更久，先让用户真的停顿一下。 */
private const val REMINDER_GATE_SECONDS = 10

@Composable
internal fun rememberGateCountdown(seconds: Int): Int {
    var remaining by androidx.compose.runtime.remember(seconds) { mutableStateOf(seconds) }
    LaunchedEffect(seconds) {
        while (remaining > 0) {
            delay(1_000L)
            remaining -= 1
        }
    }
    return remaining
}

internal fun gateLabel(base: String, remainingSeconds: Int): String =
    if (remainingSeconds > 0) "$base（${remainingSeconds}s）" else base

/** 停顿弹窗使用 MD3 Expressive 布局：图标、标题、内容与全宽操作区。 */
@Composable
internal fun PromptCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                // 内容比屏幕高时可以滚动，避免小屏或大字号下被裁掉。
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content
        )
    }
}

@Composable
internal fun PromptTitle(codePoint: Int, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) { Symbol(codePoint, size = 26.sp) }
        }
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
    }
}

/** 预设选项使用带选中形变的 Expressive 切换按钮。 */
@Composable
private fun PromptChoice(
    checked: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    label: @Composable () -> Unit
) {
    FilledTonalToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        enabled = enabled,
        buttonSize = ToggleButtonSize.Small,
        modifier = Modifier.heightIn(min = 48.dp),
        icon = if (checked) ({ Symbol(Symbols.Check, size = 20.sp) }) else null
    ) { label() }
}

@Composable
internal fun SupportingText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 悬浮条统一外观：M3 大圆角卡片 + 图标 + 文本。 */
@Composable
internal fun BarCard(scale: Float, content: @Composable RowScope.() -> Unit) {
    Card(
        modifier = Modifier.padding((12 * scale).dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier.padding((14 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((10 * scale).dp),
            content = content
        )
    }
}

@Composable
internal fun TimerPromptContent(appName: String, onStart: (Int) -> Unit, onDismiss: () -> Unit, pauseSeconds: Int, allowSkip: Boolean, onLeave: () -> Unit) {
    var customMinutes by androidx.compose.runtime.remember { mutableStateOf("") }
    var selected by androidx.compose.runtime.remember { mutableStateOf(5) }
    // 快捷选项来自「预设管理」，顺序就是显示顺序。
    val timerPresets = PurposeGateStore.timerPresets(LocalContext.current)
    androidx.compose.runtime.LaunchedEffect(timerPresets) {
        if (timerPresets.isNotEmpty() && selected !in timerPresets) selected = timerPresets.first()
    }
    val skipCountdown = rememberGateCountdown(pauseSeconds)
    OverlaySurface {
        PromptCard {
            PromptTitle(Symbols.Timer, "这次，想用多久？")
            PauseBreath(skipCountdown)
            ExpressiveTonalButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("先不打开，回到桌面") }
            SupportingText("你正在打开 $appName。设置一段专注时间后继续使用。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                timerPresets.forEach { minutes ->
                    PromptChoice(checked = selected == minutes && customMinutes.isBlank(), onClick = {
                        selected = minutes
                        customMinutes = ""
                    }, label = { Text("$minutes 分钟") })
                }
            }
            TextField(
                value = customMinutes,
                onValueChange = { value -> customMinutes = value.filter(Char::isDigit).take(4) },
                label = { Text("自定义分钟数") },
                shape = MaterialTheme.shapes.large,
                leadingIcon = { Symbol(Symbols.Timer) },
                suffix = { Text("分钟") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExpressiveButton(onClick = { onStart(customMinutes.toIntOrNull()?.coerceAtLeast(1) ?: selected) }, enabled = skipCountdown == 0 && (customMinutes.toIntOrNull()?.let { it > 0 } ?: (timerPresets.isNotEmpty())), modifier = Modifier.fillMaxWidth()) {
                    Text(gateLabel("确认并开始", skipCountdown))
                }
                ExpressiveTonalButton(onClick = onDismiss, enabled = allowSkip && skipCountdown == 0, modifier = Modifier.fillMaxWidth()) {
                    Text(gateLabel("跳过", skipCountdown))
                }
            }
        }
    }
}

@Composable
internal fun TimerBarContent(remainingSeconds: Long, scale: Float) {
    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    OverlaySurface(fullScreen = false) {
        BarCard(scale) {
            Symbol(
                Symbols.Timer,
                contentDescription = null,
                size = (20 * scale).sp,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "专注计时  %02d:%02d".format(minutes, seconds),
                Modifier
                    // 固定最小宽度：秒数变化时文本盒宽度不变，窗口尺寸也就不会每秒重算。
                    .widthIn(min = (128 * scale).dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = CountdownFontFamily,
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * scale
                )
            )
        }
    }
}

@Composable
internal fun TimerFinishedContent(minutes: Int, onContinue: (Int) -> Unit, onLeave: () -> Unit) {
    // 时间到的提醒弹窗：继续按钮要等 10 秒，先让用户真的停顿一下。
    val gateCountdown = rememberGateCountdown(REMINDER_GATE_SECONDS)
    OverlaySurface {
        PromptCard {
            PromptTitle(Symbols.Hourglass, "时间到了")
            PauseBreath(gateCountdown)
            SupportingText("这段 $minutes 分钟的专注时间已经结束，先停一下，再决定是否继续。")
            ExpressiveOutlinedButton(onClick = onLeave, enabled = gateCountdown == 0, modifier = Modifier.fillMaxWidth()) { Text(gateLabel("离开应用", gateCountdown)) }
            ExpressiveButton(onClick = { onContinue(minutes) }, enabled = gateCountdown == 0, modifier = Modifier.fillMaxWidth()) {
                Text(gateLabel("继续 $minutes 分钟", gateCountdown))
            }
        }
    }
}

@Composable
internal fun PurposePromptContent(
    appName: String,
    onPurpose: (String) -> Unit,
    onDismiss: () -> Unit,
    pauseSeconds: Int,
    allowSkip: Boolean,
    onLeave: () -> Unit
) {
    val skipCountdown = rememberGateCountdown(pauseSeconds)
    var custom by androidx.compose.runtime.remember { mutableStateOf("") }
    var selectedPurpose by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val purposePresets = PurposeGateStore.purposePresets(LocalContext.current)
    OverlaySurface {
        PromptCard {
            PromptTitle(Symbols.Flag, "这次，为了什么？")
            PauseBreath(skipCountdown)
            ExpressiveTonalButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("先不打开，回到桌面") }
            SupportingText("你正在打开 $appName。选择这次使用的目的。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                purposePresets.forEach { purpose ->
                    PromptChoice(
                        checked = selectedPurpose == purpose && custom.isBlank(),
                        enabled = skipCountdown == 0,
                        onClick = {
                            selectedPurpose = purpose
                            custom = ""
                            if (skipCountdown == 0) onPurpose(purpose)
                        },
                        label = { Text(purpose) }
                    )
                }
            }
            TextField(
                value = custom,
                onValueChange = { custom = it },
                label = { Text("自定义目的") },
                shape = MaterialTheme.shapes.large,
                leadingIcon = { Symbol(Symbols.Flag) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            ExpressiveButton(
                onClick = {
                    if (custom.isNotBlank()) {
                        selectedPurpose = null
                        onPurpose(custom.trim())
                    }
                },
                enabled = custom.isNotBlank() && skipCountdown == 0,
                modifier = Modifier.fillMaxWidth()
            ) { Text("使用自定义目的") }
            ExpressiveTonalButton(
                onClick = onDismiss,
                enabled = allowSkip && skipCountdown == 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(gateLabel("跳过", skipCountdown))
            }
        }
    }
}

@Composable
internal fun CombinedPromptContent(
    appName: String,
    onConfirm: (minutes: Int?, purpose: String?) -> Unit,
    onDismiss: () -> Unit,
    pauseSeconds: Int,
    allowSkip: Boolean,
    onLeave: () -> Unit
) {
    var customMinutes by androidx.compose.runtime.remember { mutableStateOf("") }
    var selectedMinutes by androidx.compose.runtime.remember { mutableStateOf(5) }
    var timerSelected by androidx.compose.runtime.remember { mutableStateOf(false) }
    var customPurpose by androidx.compose.runtime.remember { mutableStateOf("") }
    var selectedPurpose by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val resolvedPurpose = selectedPurpose ?: customPurpose.trim().takeIf { it.isNotEmpty() }
    val resolvedMinutes = customMinutes.toIntOrNull()?.coerceAtLeast(1) ?: selectedMinutes
    val hasTimer = timerSelected || customMinutes.isNotBlank()
    val timerPresets = PurposeGateStore.timerPresets(LocalContext.current)
    val purposePresets = PurposeGateStore.purposePresets(LocalContext.current)
    val skipCountdown = rememberGateCountdown(pauseSeconds)
    OverlaySurface {
        PromptCard {
            PromptTitle(Symbols.PauseCircle, "先停顿，再选择。")
            PauseBreath(skipCountdown)
            ExpressiveTonalButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("先不打开，回到桌面") }
            SupportingText("你正在打开 $appName。选择本次使用的定时或目的。")

            Text(
                "定时",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    timerPresets.forEach { minutes ->
                        PromptChoice(
                            checked = timerSelected && selectedMinutes == minutes && customMinutes.isBlank(),
                            onClick = {
                                selectedMinutes = minutes
                                timerSelected = true
                                customMinutes = ""
                            },
                            label = { Text("$minutes 分钟") }
                        )
                    }
            }
            TextField(
                value = customMinutes,
                onValueChange = { value ->
                    customMinutes = value.filter(Char::isDigit).take(4)
                    timerSelected = customMinutes.isNotBlank()
                },
                label = { Text("自定义分钟数") },
                shape = MaterialTheme.shapes.large,
                leadingIcon = { Symbol(Symbols.Timer) },
                suffix = { Text("分钟") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                "选择目的",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                purposePresets.forEach { purpose ->
                    PromptChoice(
                        checked = selectedPurpose == purpose && customPurpose.isBlank(),
                        onClick = {
                            selectedPurpose = purpose
                            customPurpose = ""
                        },
                        label = { Text(purpose) }
                    )
                }
            }
            TextField(
                value = customPurpose,
                onValueChange = {
                    customPurpose = it
                    selectedPurpose = null
                },
                label = { Text("自定义目的") },
                shape = MaterialTheme.shapes.large,
                leadingIcon = { Symbol(Symbols.Flag) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SupportingText(
                when {
                    hasTimer && resolvedPurpose != null -> "已选择定时和目的"
                    hasTimer -> "已选择定时"
                    resolvedPurpose != null -> "已选择目的"
                    else -> "请选择定时或目的"
                }
            )
            ExpressiveButton(
                onClick = {
                    onConfirm(if (hasTimer) resolvedMinutes else null, resolvedPurpose)
                },
                enabled = skipCountdown == 0 && (hasTimer || resolvedPurpose != null) && (!hasTimer || (customMinutes.isBlank() || (customMinutes.toIntOrNull() ?: 0) > 0)),
                modifier = Modifier.fillMaxWidth()
            ) { Text("确认") }
            ExpressiveTonalButton(
                onClick = onDismiss,
                enabled = allowSkip && skipCountdown == 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(gateLabel("跳过", skipCountdown))
            }
        }
    }
}

@Composable
internal fun CombinedBarContent(purpose: String, remainingSeconds: Long, scale: Float) {
    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    OverlaySurface(fullScreen = false) {
        BarCard(scale) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((4 * scale).dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy((6 * scale).dp)
                ) {
                    Symbol(
                        Symbols.Flag,
                        contentDescription = null,
                        size = (18 * scale).sp,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "目的：$purpose",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = MaterialTheme.typography.bodyLarge.fontSize * scale
                        )
                    )
                }
                Text(
                    "专注计时  %02d:%02d".format(minutes, seconds),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(min = (128 * scale).dp),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = CountdownFontFamily,
                        fontSize = MaterialTheme.typography.bodyLarge.fontSize * scale
                    )
                )
            }
        }
    }
}

@Composable
internal fun PurposeBarContent(purpose: String, scale: Float) {
    OverlaySurface(fullScreen = false) {
        BarCard(scale) {
            Symbol(
                Symbols.Flag,
                contentDescription = null,
                size = (20 * scale).sp,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "目的：$purpose",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * scale
                )
            )
        }
    }
}

@Composable
internal fun OverlaySurface(fullScreen: Boolean = true, content: @Composable () -> Unit) {
    val surfaceModifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.wrapContentSize()
    Surface(modifier = surfaceModifier, color = Color.Transparent) {
        val boxModifier = if (fullScreen) Modifier.fillMaxSize() else Modifier.wrapContentSize()
        Box(boxModifier, contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
internal fun FocusBlockedContent(title: String, body: String, remainingSeconds: Long?, onLeave: () -> Unit) {
    OverlaySurface {
        PromptCard {
            PromptTitle(Symbols.Shield, title)
            if (remainingSeconds != null) {
                Text("%02d:%02d".format(remainingSeconds / 60, remainingSeconds % 60),
                    style = MaterialTheme.typography.displayMedium, fontFamily = CountdownFontFamily)
            }
            SupportingText(body)
            ExpressiveButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("回到桌面") }
        }
    }
}
