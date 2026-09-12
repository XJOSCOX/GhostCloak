package org.ghostcloak.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.ghostcloak.app.R
import androidx.compose.ui.unit.sp

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val GhostFontFamily = FontFamily(
    listOf(FontWeight.Light, FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.space_grotesk, weight=weight,
            variationSettings=androidx.compose.ui.text.font.FontVariation.Settings(
                androidx.compose.ui.text.font.FontVariation.weight(weight.weight)))
    }
)

private fun TextStyle.withGhostFont(): TextStyle = copy(fontFamily = GhostFontFamily)

private val BaseTypography = Typography()

val Typography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.withGhostFont(),
    displayMedium = BaseTypography.displayMedium.withGhostFont(),
    displaySmall = BaseTypography.displaySmall.withGhostFont(),
    headlineMedium = BaseTypography.headlineMedium.withGhostFont(),
    headlineSmall = BaseTypography.headlineSmall.withGhostFont(),
    titleSmall = BaseTypography.titleSmall.withGhostFont(),
    bodySmall = BaseTypography.bodySmall.withGhostFont(),
    labelMedium = BaseTypography.labelMedium.withGhostFont(),
    labelSmall = BaseTypography.labelSmall.withGhostFont(),
    headlineLarge = TextStyle(
        fontFamily = GhostFontFamily,
        fontWeight = FontWeight.Light,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = GhostFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = GhostFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = GhostFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = GhostFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = GhostFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )
)

// Numeric identity comparisons keep their deliberate monospaced presentation.
val SafetyNumberStyle = Typography.titleMedium.copy(fontFamily = FontFamily.Monospace)
