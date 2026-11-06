package com.suakitsu.picpilot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.suakitsu.picpilot.ui.theme.picPilotMinimumTouchTarget
import com.suakitsu.picpilot.ui.theme.picPilotSpacing

@Composable
fun PicPilotTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onNavigateBack: (() -> Unit)? = null,
    backContentDescription: String = "返回",
    showDivider: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val spacing = MaterialTheme.picPilotSpacing
    val stackActions = LocalDensity.current.fontScale >= 1.6f

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = spacing.sm,
                        end = spacing.sm,
                        top = spacing.sm,
                        bottom = if (stackActions) spacing.xs else spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onNavigateBack != null) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.picPilotMinimumTouchTarget(),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = backContentDescription,
                        )
                    }
                }
                TitleBlock(
                    title = title,
                    subtitle = subtitle,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = spacing.sm),
                )
                if (!stackActions) {
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
            if (stackActions) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = spacing.lg, end = spacing.sm, bottom = spacing.sm),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
            if (showDivider) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun TitleBlock(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) {
    val spacing = MaterialTheme.picPilotSpacing
    Column(modifier = modifier) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(spacing.xxs))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
