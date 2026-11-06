package com.suakitsu.picpilot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.suakitsu.picpilot.ui.theme.picPilotSpacing

enum class PicPilotWindowClass {
    Compact,
    Medium,
    Expanded,
}

fun picPilotWindowClassFor(width: Dp): PicPilotWindowClass = when {
    width < 600.dp -> PicPilotWindowClass.Compact
    width < 840.dp -> PicPilotWindowClass.Medium
    else -> PicPilotWindowClass.Expanded
}

@Composable
fun PicPilotResponsivePage(
    modifier: Modifier = Modifier,
    applySafeDrawingInsets: Boolean = true,
    maxContentWidth: Dp = MaterialTheme.picPilotSpacing.maxContentWidth,
    content: @Composable BoxScope.(PicPilotWindowClass) -> Unit,
) {
    val insetModifier = if (applySafeDrawingInsets) {
        modifier.windowInsetsPadding(WindowInsets.safeDrawing)
    } else {
        modifier
    }

    Box(
        modifier = insetModifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .widthIn(max = maxContentWidth)
                .fillMaxWidth(),
        ) {
            content(picPilotWindowClassFor(maxWidth))
        }
    }
}

@Composable
fun PicPilotAdaptiveTwoPane(
    windowClass: PicPilotWindowClass,
    primary: @Composable () -> Unit,
    secondary: @Composable (() -> Unit)?,
    modifier: Modifier = Modifier,
    primaryWeight: Float = 1f,
    secondaryWeight: Float = 1f,
) {
    val spacing = MaterialTheme.picPilotSpacing
    if (windowClass == PicPilotWindowClass.Compact || secondary == null) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            primary()
            secondary?.invoke()
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xxl),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.weight(primaryWeight)) { primary() }
            Box(modifier = Modifier.weight(secondaryWeight)) { secondary() }
        }
    }
}

@Composable
fun PicPilotAdaptiveActions(
    windowClass: PicPilotWindowClass,
    primaryAction: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    secondaryAction: (@Composable () -> Unit)? = null,
) {
    val spacing = MaterialTheme.picPilotSpacing
    if (windowClass == PicPilotWindowClass.Compact) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            primaryAction()
            secondaryAction?.invoke()
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            secondaryAction?.invoke()
            if (secondaryAction != null) Spacer(modifier = Modifier.width(spacing.sm))
            primaryAction()
        }
    }
}

@Composable
fun PicPilotWindowClass.pageHorizontalPadding(): Dp {
    val spacing = MaterialTheme.picPilotSpacing
    return when (this) {
        PicPilotWindowClass.Compact -> spacing.compactPagePadding
        PicPilotWindowClass.Medium -> spacing.mediumPagePadding
        PicPilotWindowClass.Expanded -> spacing.expandedPagePadding
    }
}
