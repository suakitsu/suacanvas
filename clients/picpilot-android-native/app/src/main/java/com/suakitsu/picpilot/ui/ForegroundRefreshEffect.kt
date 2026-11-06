package com.suakitsu.picpilot.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Refreshes only after returning from the background, not during the initial composition. */
@Composable
fun ForegroundRefreshEffect(
    delayMillis: Long = 650,
    onRefresh: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestRefresh by rememberUpdatedState(onRefresh)
    var resumeCount by remember(lifecycleOwner) { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumeCount) {
        if (resumeCount > 1) {
            delay(delayMillis)
            latestRefresh()
        }
    }
}
