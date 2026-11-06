package com.suakitsu.picpilot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.suakitsu.picpilot.ui.theme.picPilotMinimumTouchTarget
import com.suakitsu.picpilot.ui.theme.picPilotSpacing

@Immutable
data class PicPilotConfirmationItem(
    val label: String,
    val value: String,
    val emphasize: Boolean = false,
)

@Composable
fun PicPilotConfirmDialog(
    visible: Boolean,
    title: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    items: List<PicPilotConfirmationItem> = emptyList(),
    warning: String? = null,
    isSubmitting: Boolean = false,
    destructive: Boolean = false,
) {
    if (!visible) return

    val spacing = MaterialTheme.picPilotSpacing
    AlertDialog(
        modifier = modifier,
        onDismissRequest = {
            if (!isSubmitting) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !isSubmitting,
            dismissOnClickOutside = !isSubmitting,
            usePlatformDefaultWidth = true,
        ),
        icon = {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                message?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items.forEachIndexed { index, item ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    ConfirmationItem(item)
                }
                warning?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isSubmitting,
                modifier = Modifier.picPilotMinimumTouchTarget(),
                colors = if (destructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.size(spacing.sm))
                }
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting,
                modifier = Modifier.picPilotMinimumTouchTarget(),
            ) {
                Text(dismissLabel)
            }
        },
    )
}

@Composable
private fun ConfirmationItem(item: PicPilotConfirmationItem) {
    val spacing = MaterialTheme.picPilotSpacing
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = item.value,
            style = if (item.emphasize) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = if (item.emphasize) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
