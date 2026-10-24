package com.purposegate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Material Symbols Rounded：取自 Google Material Design 官方图标资产
 * （material-design-icons/variablefont/MaterialSymbolsRounded），已按本项目用到的
 * 字形子集化，体积从 14.5 MB 降到 9.5 KB。
 */
private val MaterialSymbols = FontFamily(Font(R.font.material_symbols_rounded))

/** Material Symbols 码点，取自官方 MaterialSymbolsRounded.codepoints。 */
object Symbols {
    const val Timer = 0xE425
    const val Flag = 0xF0C6
    const val Label = 0xE893
    const val Apps = 0xE5C3
    const val Tune = 0xE429
    const val Search = 0xEF7A
    const val Close = 0xE5CD
    const val Check = 0xE668
    const val CheckCircle = 0xF0BE
    const val ChevronRight = 0xE5CC
    const val ArrowBack = 0xE5C4
    const val DarkMode = 0xE51C
    const val Schedule = 0xEFD6
    const val Opacity = 0xE91C
    const val FormatSize = 0xE245
    const val Info = 0xE88E
    const val PauseCircle = 0xE1A2
    const val Notifications = 0xE7F5
    const val SkipNext = 0xE044
    const val PlayArrow = 0xE037
    const val Logout = 0xE9BA
    const val VerifiedUser = 0xF013
    const val Error = 0xF8B6
    const val Cancel = 0xE888
    const val OpenInNew = 0xE89E
    const val Hourglass = 0xEBFF
    const val TaskAlt = 0xE2E6
    const val Block = 0xF08C
    const val Settings = 0xE8B8
    const val Shield = 0xE9E0
    const val Lock = 0xE899
    const val Visibility = 0xE8F4
    const val RestartAlt = 0xF053
    const val LightMode = 0xE518
    const val DoNotDisturbOn = 0xF08F
    const val Speed = 0xE9E4
    const val AvgPace = 0xF6BB
    const val Insights = 0xF092
    const val MoreVert = 0xE5D4
    const val ShoppingBag = 0xF1CC
    const val HourglassTop = 0xEA5B
    const val KeyboardArrowUp = 0xE316
    const val KeyboardArrowDown = 0xE313
    const val Add = 0xE145
    const val Delete = 0xE92E
    const val PlaylistAdd = 0xE03B
}

/** 用 Material Symbols 字形代替 ImageVector，尺寸与 24dp 图标网格一致。 */
@Composable
fun Symbol(
    codePoint: Int,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: TextUnit = 24.sp,
    tint: Color = LocalContentColor.current
) {
    val side = with(LocalDensity.current) { size.toDp() }
    // Material Symbols 的字形在自身 em 框里偏下（实测笔画中心比容器中心低约 9% 字号高度），
    // 这里按字号等比补偿，让笔画中心落在容器中心；比例与字号无关，所以各种尺寸都适用。
    val verticalNudge = with(LocalDensity.current) { (size * 0.09f).toDp() }
    val semantics = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Box(
        modifier = modifier.then(semantics).size(side),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = String(Character.toChars(codePoint)),
            modifier = Modifier.offset(y = -verticalNudge),
            color = tint,
            fontFamily = MaterialSymbols,
            fontSize = size,
            lineHeight = size,
            textAlign = TextAlign.Center,
            style = LocalTextStyle.current.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                letterSpacing = 0.sp
            )
        )
    }
}

/** M3 常见的“圆形色底 + 图标”组合，用于列表项前导图标。 */
@Composable
fun SymbolBadge(
    codePoint: Int,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    iconSize: TextUnit = 22.sp
) {
    Surface(
        modifier = modifier.size(size),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Symbol(codePoint, size = iconSize, tint = contentColor)
        }
    }
}
