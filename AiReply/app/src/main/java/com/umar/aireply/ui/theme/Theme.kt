package com.umar.aireply.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colour tokens. Deep navy ink, a mint "live" accent, and a metallic gold reserved for the signature badge. */
object AppColors {
    val Ink = Color(0xFF080D18)
    val InkSoft = Color(0xFF0E1526)
    val Surface = Color(0xFF131C30)
    val SurfaceHigh = Color(0xFF1B2640)
    val Outline = Color(0xFF2A3858)

    val Mint = Color(0xFF2EE6A6)
    val MintDim = Color(0xFF1B9C74)

    val Gold = Color(0xFFE2BE62)
    val GoldLight = Color(0xFFF8E9AE)
    val GoldDark = Color(0xFF9A7728)

    val TextPrimary = Color(0xFFEAF0FF)
    val TextSecondary = Color(0xFF9AA8C7)

    val Danger = Color(0xFFFF6B7A)
    val Warning = Color(0xFFFFC857)
    val Info = Color(0xFF6CB6FF)
}

private val DarkScheme = darkColorScheme(
    primary = AppColors.Mint,
    onPrimary = AppColors.Ink,
    primaryContainer = AppColors.MintDim,
    onPrimaryContainer = AppColors.TextPrimary,
    secondary = AppColors.Gold,
    onSecondary = AppColors.Ink,
    background = AppColors.Ink,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceHigh,
    onSurfaceVariant = AppColors.TextSecondary,
    outline = AppColors.Outline,
    error = AppColors.Danger,
    onError = AppColors.Ink
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.4).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp
    )
)

@Composable
fun AiReplyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        content = content
    )
}
