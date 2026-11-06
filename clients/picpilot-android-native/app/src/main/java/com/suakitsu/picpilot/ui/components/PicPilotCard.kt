package com.suakitsu.picpilot.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.suakitsu.picpilot.ui.theme.picPilotMinimumTouchTarget
import com.suakitsu.picpilot.ui.theme.picPilotSpacing

@Composable
fun PicPilotCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(MaterialTheme.picPilotSpacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)

    if (onClick == null) {
        Card(
            modifier = modifier,
            shape = MaterialTheme.shapes.medium,
            colors = colors,
            border = border,
            elevation = elevation,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
        }
    } else {
        Card(
            onClick = onClick,
            modifier = modifier.picPilotMinimumTouchTarget(),
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            colors = colors,
            border = border,
            elevation = elevation,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
        }
    }
}

@Composable
fun PicPilotSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = MaterialTheme.picPilotSpacing
    PicPilotCard(modifier = modifier) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action?.let {
            Spacer(modifier = Modifier.height(spacing.sm))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                it()
            }
        }
        Spacer(modifier = Modifier.height(spacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(spacing.md))
        content()
    }
}
