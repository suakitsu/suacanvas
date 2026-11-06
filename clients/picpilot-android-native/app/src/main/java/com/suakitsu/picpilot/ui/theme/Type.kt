package com.suakitsu.picpilot.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

private fun picPilotTextStyle(
    fontSize: TextUnit,
    lineHeight: TextUnit,
    fontWeight: FontWeight,
) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = fontWeight,
    fontSize = fontSize,
    lineHeight = lineHeight,
    letterSpacing = 0.sp,
)

val PicPilotTypography = Typography(
    displayLarge = picPilotTextStyle(40.sp, 52.sp, FontWeight.SemiBold),
    displayMedium = picPilotTextStyle(36.sp, 48.sp, FontWeight.SemiBold),
    displaySmall = picPilotTextStyle(32.sp, 42.sp, FontWeight.SemiBold),
    headlineLarge = picPilotTextStyle(28.sp, 38.sp, FontWeight.SemiBold),
    headlineMedium = picPilotTextStyle(24.sp, 34.sp, FontWeight.SemiBold),
    headlineSmall = picPilotTextStyle(22.sp, 32.sp, FontWeight.SemiBold),
    titleLarge = picPilotTextStyle(20.sp, 30.sp, FontWeight.SemiBold),
    titleMedium = picPilotTextStyle(17.sp, 26.sp, FontWeight.SemiBold),
    titleSmall = picPilotTextStyle(15.sp, 24.sp, FontWeight.SemiBold),
    bodyLarge = picPilotTextStyle(16.sp, 25.sp, FontWeight.Normal),
    bodyMedium = picPilotTextStyle(14.sp, 22.sp, FontWeight.Normal),
    bodySmall = picPilotTextStyle(12.sp, 19.sp, FontWeight.Normal),
    labelLarge = picPilotTextStyle(15.sp, 22.sp, FontWeight.Medium),
    labelMedium = picPilotTextStyle(13.sp, 20.sp, FontWeight.Medium),
    labelSmall = picPilotTextStyle(11.sp, 18.sp, FontWeight.Medium),
)
