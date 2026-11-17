package com.karelisio.fichio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val base = Typography()

/** Expressive typography: tighter, heavier display/headline styles, relaxed body text. */
val FichioTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.04).em),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.02).em),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.015).em),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
)

/** Huge numerals for the storage gauge. */
val GaugeNumberStyle = TextStyle(
    fontSize = 64.sp,
    lineHeight = 64.sp,
    fontWeight = FontWeight.Black,
    letterSpacing = (-0.05).em,
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
