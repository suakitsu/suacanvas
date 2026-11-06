package com.suakitsu.picpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.suakitsu.picpilot.core.media.MediaFileManager
import dagger.hilt.android.AndroidEntryPoint
import com.suakitsu.picpilot.ui.PicPilotApp
import com.suakitsu.picpilot.ui.components.LocalPicPilotMediaFiles
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var mediaFiles: MediaFileManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalPicPilotMediaFiles provides mediaFiles) {
                PicPilotApp()
            }
        }
    }
}
