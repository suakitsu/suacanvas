package com.suakitsu.picpilot.ui.theme

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Modifier

fun Modifier.picPilotMinimumTouchTarget(): Modifier = sizeIn(
    minWidth = PicPilotSpacing().touchTarget,
    minHeight = PicPilotSpacing().touchTarget,
)
