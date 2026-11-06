package com.suakitsu.picpilot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suakitsu.picpilot.ui.theme.picPilotMinimumTouchTarget
import com.suakitsu.picpilot.ui.theme.picPilotSpacing

@Composable
fun PicPilotLoadingState(
    modifier: Modifier = Modifier,
    title: String = "正在加载",
    message: String? = null,
) {
    FeedbackFrame(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        title = title,
        message = message,
        iconContent = {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp,
            )
        },
    )
}

@Composable
fun PicPilotEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
) {
    FeedbackFrame(
        modifier = modifier,
        title = title,
        message = message,
        iconContent = {
            FeedbackIcon(icon = icon, contentDescription = null)
        },
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
fun PicPilotErrorState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    retryLabel: String = "重试",
    onRetry: (() -> Unit)? = null,
    icon: ImageVector = Icons.Outlined.CloudOff,
) {
    FeedbackFrame(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        title = title,
        message = message,
        iconContent = {
            FeedbackIcon(
                icon = icon,
                contentDescription = null,
                isError = true,
            )
        },
        actionLabel = retryLabel,
        onAction = onRetry,
    )
}

@Composable
private fun FeedbackFrame(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    iconContent: @Composable () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val spacing = MaterialTheme.picPilotSpacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 160.dp)
            .padding(horizontal = spacing.lg, vertical = spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        iconContent()
        Spacer(modifier = Modifier.height(spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        message?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(spacing.lg))
            Button(
                onClick = onAction,
                modifier = Modifier.picPilotMinimumTouchTarget(),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun FeedbackIcon(
    icon: ImageVector,
    contentDescription: String?,
    isError: Boolean = false,
) {
    val container = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val content = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = Modifier.size(56.dp),
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
