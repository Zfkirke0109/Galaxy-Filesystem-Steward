package com.galaxy.steward.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF5B4BD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF170065),
    secondary = Color(0xFF006B60),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA6F2E4),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF8C4F00),
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2D1600),
    background = Color(0xFFF8F7FF),
    surface = Color(0xFFF8F7FF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F0FB),
    surfaceContainer = Color(0xFFECEAF6),
    surfaceContainerHigh = Color(0xFFE6E4F0),
    surfaceContainerHighest = Color(0xFFE1DEEB),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF2A1A8C),
    primaryContainer = Color(0xFF4130B8),
    onPrimaryContainer = Color(0xFFE4DFFF),
    secondary = Color(0xFF5FDBCB),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFA6F2E4),
    tertiary = Color(0xFFFFB77C),
    tertiaryContainer = Color(0xFF6B3B00),
    onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF0F0E1C),
    surface = Color(0xFF0F0E1C),
    surfaceContainerLowest = Color(0xFF0A0916),
    surfaceContainerLow = Color(0xFF171627),
    surfaceContainer = Color(0xFF1C1A2C),
    surfaceContainerHigh = Color(0xFF262437),
    surfaceContainerHighest = Color(0xFF312F42),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}

@Composable
fun StewardTheme(dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
}
