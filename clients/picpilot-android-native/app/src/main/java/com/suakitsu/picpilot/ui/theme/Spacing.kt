package com.suakitsu.picpilot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class PicPilotSpacing(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 24.dp,
    val xxxl: Dp = 32.dp,
    val section: Dp = 40.dp,
    val touchTarget: Dp = 48.dp,
    val compactPagePadding: Dp = 16.dp,
    val mediumPagePadding: Dp = 24.dp,
    val expandedPagePadding: Dp = 32.dp,
    val maxContentWidth: Dp = 1200.dp,
)

internal val LocalPicPilotSpacing = staticCompositionLocalOf { PicPilotSpacing() }

val MaterialTheme.picPilotSpacing: PicPilotSpacing
    @Composable
    @ReadOnlyComposable
    get() = LocalPicPilotSpacing.current
