package com.suakitsu.picpilot

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import android.widget.Toast
import com.suakitsu.picpilot.core.media.MediaFileManager
import com.suakitsu.picpilot.core.network.MediaClient
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltAndroidApp
class PicPilotApplication : Application(), ImageLoaderFactory {
    @Inject
    @MediaClient
    lateinit var mediaClient: OkHttpClient

    @Inject
    lateinit var mediaFiles: MediaFileManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun downloadMedia(url: String, title: String, mimeType: String) {
        applicationScope.launch {
            val message = mediaFiles.download(url, title, mimeType).fold(
                onSuccess = { it },
                onFailure = { it.message ?: "下载失败，请稍后重试" },
            )
            Toast.makeText(this@PicPilotApplication, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareMedia(url: String, title: String, mimeType: String) {
        applicationScope.launch {
            val message = mediaFiles.share(url, title, mimeType).exceptionOrNull()?.message
            if (message != null) {
                Toast.makeText(this@PicPilotApplication, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(mediaClient)
        .crossfade(180)
        .build()
}
