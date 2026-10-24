@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class
)

package com.purposegate

/** Shared Material 3 Expressive components for the app and its child pages. */

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Connected list groups use broad outer corners and tighter adjoining corners. */
internal fun ExpressiveGroupShape(index: Int, count: Int): RoundedCornerShape =
    RoundedCornerShape(
        topStart = if (index == 0 || count <= 1) 28.dp else 8.dp,
        topEnd = if (index == 0 || count <= 1) 28.dp else 8.dp,
        bottomStart = if (index == count - 1 || count <= 1) 28.dp else 8.dp,
        bottomEnd = if (index == count - 1 || count <= 1) 28.dp else 8.dp
    )

@Composable
internal fun ExpressiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val height = ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        shapes = ButtonDefaults.shapesFor(height),
        contentPadding = ButtonDefaults.contentPaddingFor(height)
    ) {
        ProvideTextStyle(ButtonDefaults.textStyleFor(height)) { content() }
    }
}

@Composable
internal fun ExpressiveTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val height = ButtonDefaults.MediumContainerHeight
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        shapes = ButtonDefaults.shapesFor(height),
        contentPadding = ButtonDefaults.contentPaddingFor(height)
    ) {
        ProvideTextStyle(ButtonDefaults.textStyleFor(height)) { content() }
    }
}

@Composable
internal fun ExpressiveOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val height = ButtonDefaults.MediumContainerHeight
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        shapes = ButtonDefaults.shapesFor(height),
        contentPadding = ButtonDefaults.contentPaddingFor(height)
    ) {
        ProvideTextStyle(ButtonDefaults.textStyleFor(height)) { content() }
    }
}

@Composable
internal fun ExpressiveIconButton(
    codePoint: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        enabled = enabled,
        shapes = IconButtonDefaults.shapes()
    ) {
        Symbol(codePoint, contentDescription = contentDescription)
    }
}

@Composable
internal fun TopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null
) {
    if (onBack != null) {
        // A child destination has one horizontal title line, matching the system settings app.
        TopAppBar(
            title = {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                ExpressiveIconButton(
                    Symbols.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                    modifier = Modifier.padding(start = 8.dp, end = 12.dp)
                )
            },
            expandedHeight = 80.dp,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
    } else {
        MediumTopAppBar(
            actions = {
                if (onSettings != null) {
                    IconButton(
                        onClick = onSettings,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.padding(end = 8.dp).size(48.dp)
                    ) {
                        Symbol(Symbols.Settings, contentDescription = "设置")
                    }
                }
            },
            title = {
                Column {
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )
    }
}

@Composable
internal fun SectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EntryCard(
    codePoint: Int,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badgeCount: Int? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow
) {
    val contentColor = when (containerColor) {
        MaterialTheme.colorScheme.tertiaryContainer -> MaterialTheme.colorScheme.onTertiaryContainer
        MaterialTheme.colorScheme.errorContainer -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (badgeCount == null) {
                SymbolBadge(codePoint)
            } else {
                BadgedBox(badge = { Badge { Text(badgeCount.toString()) } }) {
                    SymbolBadge(codePoint)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = contentColor.copy(alpha = 0.76f))
            }
            Symbol(Symbols.ChevronRight, contentDescription = null, tint = contentColor.copy(alpha = 0.72f))
        }
    }
}

@Composable
internal fun SwitchRow(
    codePoint: Int,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .heightIn(min = 88.dp)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SymbolBadge(codePoint, size = 40.dp, iconSize = 22.sp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
                thumbContent = if (checked) {
                    { Symbol(Symbols.Check, contentDescription = null, size = 16.sp) }
                } else null
            )
        }
    }
}

@Composable
internal fun InfoSurface(codePoint: Int, text: String, action: (@Composable () -> Unit)? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.56f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(codePoint, contentDescription = null, size = 22.sp)
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (action != null) {
                Box(Modifier.align(Alignment.End)) { action() }
            }
        }
    }
}

@Composable
internal fun StatusCard(title: String, detail: String, ok: Boolean, okText: String, badText: String) {
    val contentColor = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.errorContainer,
            contentColor = contentColor
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.medium,
                color = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.error,
                contentColor = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onError
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Symbol(if (ok) Symbols.TaskAlt else Symbols.Error, contentDescription = null, size = 24.sp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = contentColor.copy(alpha = 0.78f))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (ok) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.error,
                    contentColor = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onError
                ) {
                    Text(
                        if (ok) okText else badText,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun SliderCard(
    codePoint: Int,
    title: String,
    valueText: String,
    supporting: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    val sliderState = remember(steps, valueRange) {
        SliderState(value = value, steps = steps, trackRange = valueRange)
    }
    val interactionSource = remember { MutableInteractionSource() }
    SideEffect { sliderState.value = value }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SymbolBadge(codePoint, size = 40.dp, iconSize = 22.sp)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(valueText, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                state = sliderState,
                onValueChange = {
                    sliderState.value = it
                    onValueChange(it)
                },
                interactionSource = interactionSource,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    contentDescription = title
                    stateDescription = valueText
                },
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = interactionSource,
                        isVertical = false,
                        thumbSize = DpSize(4.dp, 44.dp)
                    )
                },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        trackCornerSize = 8.dp,
                        modifier = Modifier.height(16.dp),
                        thumbTrackGapSize = 6.dp,
                        trackInsideCornerSize = 2.dp
                    )
                }
            )
        }
    }
}
