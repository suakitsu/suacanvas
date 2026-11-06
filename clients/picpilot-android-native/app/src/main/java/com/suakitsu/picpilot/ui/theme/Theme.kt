package com.suakitsu.picpilot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme

@Composable
fun PicPilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) PicPilotDarkColorScheme else PicPilotLightColorScheme
    val semanticColors = if (darkTheme) {
        PicPilotDarkSemanticColors
    } else {
        PicPilotLightSemanticColors
    }

    CompositionLocalProvider(
        LocalPicPilotSemanticColors provides semanticColors,
        LocalPicPilotSpacing provides PicPilotSpacing(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PicPilotTypography,
            shapes = PicPilotShapes,
            content = content,
        )
    }
}
