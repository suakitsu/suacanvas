package com.suakitsu.picpilot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.suakitsu.picpilot.ui.theme.picPilotColors
import com.suakitsu.picpilot.ui.theme.picPilotSpacing

enum class PicPilotStatusTone {
    Neutral,
    Info,
    Success,
    Warning,
    Error,
}

@Composable
fun PicPilotStatusBadge(
    text: String,
    tone: PicPilotStatusTone,
    modifier: Modifier = Modifier,
    accessibilityLabel: String = text,
    leadingIcon: ImageVector? = null,
) {
    val colors = badgeColors(tone)
    val spacing = MaterialTheme.picPilotSpacing
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            stateDescription = accessibilityLabel
        },
        color = colors.container,
        contentColor = colors.content,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private data class BadgeColors(
    val container: Color,
    val content: Color,
)

@Composable
private fun badgeColors(tone: PicPilotStatusTone): BadgeColors {
    val semantic = MaterialTheme.picPilotColors
    return when (tone) {
        PicPilotStatusTone.Neutral -> BadgeColors(
            semantic.neutralContainer,
            semantic.onNeutralContainer,
        )
        PicPilotStatusTone.Info -> BadgeColors(semantic.infoContainer, semantic.onInfoContainer)
        PicPilotStatusTone.Success -> BadgeColors(
            semantic.successContainer,
            semantic.onSuccessContainer,
        )
        PicPilotStatusTone.Warning -> BadgeColors(
            semantic.warningContainer,
            semantic.onWarningContainer,
        )
        PicPilotStatusTone.Error -> BadgeColors(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
