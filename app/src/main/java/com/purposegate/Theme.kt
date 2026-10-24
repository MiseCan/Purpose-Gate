@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.purposegate

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val CountdownFontFamily = FontFamily.Monospace

/** One expressive system for pages, accessibility overlays and PiP. Respects system font scaling. */
@Composable
fun PurposeGateTheme(denseTypography: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val type = Typography()
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(12.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(20.dp),
            large = RoundedCornerShape(28.dp),
            extraLarge = RoundedCornerShape(36.dp)
        ),
        typography = type.copy(
            displayMedium = type.displayMedium.copy(fontWeight = FontWeight.Bold),
            headlineLarge = type.headlineLarge.copy(fontWeight = FontWeight.Bold),
            headlineMedium = type.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = type.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = type.titleMedium.copy(fontWeight = FontWeight.Medium)
        ),
        content = content
    )
}
