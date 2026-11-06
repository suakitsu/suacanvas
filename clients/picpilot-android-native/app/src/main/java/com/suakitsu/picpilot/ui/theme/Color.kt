package com.suakitsu.picpilot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val BrandBlue = Color(0xFF246BFD)
private val BrandBlueDark = Color(0xFFA9C3FF)

internal val PicPilotLightColorScheme = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF1FF),
    onPrimaryContainer = Color(0xFF163B7A),
    inversePrimary = BrandBlueDark,
    secondary = Color(0xFF0A9F8B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1F7F3),
    onSecondaryContainer = Color(0xFF073C38),
    tertiary = Color(0xFFB86100),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF4A2800),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F3F6),
    onSurfaceVariant = Color(0xFF475569),
    surfaceTint = BrandBlue,
    inverseSurface = Color(0xFF1F2937),
    inverseOnSurface = Color(0xFFF8FAFC),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFE2E0),
    onErrorContainer = Color(0xFF6E1010),
    outline = Color(0xFFA7B1C2),
    outlineVariant = Color(0xFFE5E9F0),
    scrim = Color(0x990F172A),
    surfaceDim = Color(0xFFE9EDF3),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainer = Color(0xFFF3F5F8),
    surfaceContainerHigh = Color(0xFFEDF0F5),
    surfaceContainerHighest = Color(0xFFE7EBF1),
)

internal val PicPilotDarkColorScheme = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF082A6A),
    primaryContainer = Color(0xFF173F8F),
    onPrimaryContainer = Color(0xFFDCE7FF),
    inversePrimary = BrandBlue,
    secondary = Color(0xFF7ED8CE),
    onSecondary = Color(0xFF063D38),
    secondaryContainer = Color(0xFF155E57),
    onSecondaryContainer = Color(0xFFD5F5F0),
    tertiary = Color(0xFFFFB66D),
    onTertiary = Color(0xFF512A00),
    tertiaryContainer = Color(0xFF713C00),
    onTertiaryContainer = Color(0xFFFFDCC2),
    background = Color(0xFF0D1118),
    onBackground = Color(0xFFE5EAF2),
    surface = Color(0xFF151B24),
    onSurface = Color(0xFFE5EAF2),
    surfaceVariant = Color(0xFF202834),
    onSurfaceVariant = Color(0xFFB8C2D1),
    surfaceTint = BrandBlueDark,
    inverseSurface = Color(0xFFE5EAF2),
    inverseOnSurface = Color(0xFF1F2937),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8491A3),
    outlineVariant = Color(0xFF303A48),
    scrim = Color(0xCC000000),
    surfaceDim = Color(0xFF0D1118),
    surfaceBright = Color(0xFF252E3A),
    surfaceContainerLowest = Color(0xFF0A0E14),
    surfaceContainerLow = Color(0xFF121821),
    surfaceContainer = Color(0xFF171E28),
    surfaceContainerHigh = Color(0xFF1D2530),
    surfaceContainerHighest = Color(0xFF242D39),
)

@Immutable
data class PicPilotSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
)

internal val PicPilotLightSemanticColors = PicPilotSemanticColors(
    success = Color(0xFF15803D),
    onSuccess = Color.White,
    successContainer = Color(0xFFDCFCE7),
    onSuccessContainer = Color(0xFF14532D),
    warning = Color(0xFFB45309),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFEDD5),
    onWarningContainer = Color(0xFF7C2D12),
    info = Color(0xFF0369A1),
    onInfo = Color.White,
    infoContainer = Color(0xFFE0F2FE),
    onInfoContainer = Color(0xFF0C4A6E),
    neutralContainer = Color(0xFFEFF2F6),
    onNeutralContainer = Color(0xFF475569),
)

internal val PicPilotDarkSemanticColors = PicPilotSemanticColors(
    success = Color(0xFF86EFAC),
    onSuccess = Color(0xFF052E16),
    successContainer = Color(0xFF14532D),
    onSuccessContainer = Color(0xFFDCFCE7),
    warning = Color(0xFFFDBA74),
    onWarning = Color(0xFF431407),
    warningContainer = Color(0xFF7C2D12),
    onWarningContainer = Color(0xFFFFEDD5),
    info = Color(0xFF7DD3FC),
    onInfo = Color(0xFF082F49),
    infoContainer = Color(0xFF0C4A6E),
    onInfoContainer = Color(0xFFE0F2FE),
    neutralContainer = Color(0xFF263244),
    onNeutralContainer = Color(0xFFD5DCE7),
)

internal val LocalPicPilotSemanticColors = staticCompositionLocalOf {
    PicPilotLightSemanticColors
}

val MaterialTheme.picPilotColors: PicPilotSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalPicPilotSemanticColors.current
