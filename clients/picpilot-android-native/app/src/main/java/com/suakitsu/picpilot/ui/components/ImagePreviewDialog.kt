package com.suakitsu.picpilot.ui.components

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.suakitsu.picpilot.core.media.MediaFileManager
import kotlinx.coroutines.launch

val LocalPicPilotMediaFiles = staticCompositionLocalOf<MediaFileManager> {
    error("MediaFileManager is not available")
}

data class PicPilotImagePreview(
    val url: String,
    val title: String = "原图预览",
    val mimeType: String = "image/png",
)

@Composable
fun PicPilotImagePreviewDialog(
    request: PicPilotImagePreview,
    mediaFiles: MediaFileManager = LocalPicPilotMediaFiles.current,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember(request.url) { mutableFloatStateOf(1f) }
    var offset by remember(request.url) { mutableStateOf(Offset.Zero) }
    var reloadKey by remember(request.url) { mutableIntStateOf(0) }
    var mediaAction by remember(request.url) { mutableStateOf<String?>(null) }
    val imageRequest = remember(request.url, reloadKey) {
        ImageRequest.Builder(context)
            .data(request.url)
            .memoryCacheKey("${request.url}#full-preview-$reloadKey")
            .crossfade(180)
            .build()
    }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = nextScale
        offset = if (nextScale == 1f) Offset.Zero else offset + panChange
    }

    fun runMediaAction(label: String, block: suspend () -> Result<*>) {
        if (mediaAction != null) return
        mediaAction = label
        scope.launch {
            val result = block()
            mediaAction = null
            val message = result.fold(
                onSuccess = { value -> (value as? String).orEmpty().ifBlank { "$label 已准备" } },
                onFailure = { error -> error.message ?: "$label 失败，请稍后重试" },
            )
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Color.Black.copy(alpha = 0.97f), modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                SubcomposeAsyncImage(
                    model = imageRequest,
                    contentDescription = request.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .transformable(transformState),
                ) {
                    when (painter.state) {
                        is AsyncImagePainter.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(34.dp))
                                Spacer(Modifier.height(12.dp))
                                Text("正在加载原图", color = Color.White.copy(alpha = 0.8f))
                            }
                        }
                        is AsyncImagePainter.State.Error -> Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(Icons.Outlined.BrokenImage, null, tint = Color.White, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("原图加载失败，请检查网络后重试", color = Color.White)
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = { reloadKey += 1 }) {
                                Icon(Icons.Outlined.Refresh, null)
                                Spacer(Modifier.width(6.dp))
                                Text("重新加载")
                            }
                        }
                        else -> SubcomposeAsyncImageContent()
                    }
                }

                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 66.dp, vertical = 12.dp),
                    color = Color.Black.copy(alpha = 0.62f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        request.title.ifBlank { "原图预览" },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭预览")
                    }
                    FilledTonalIconButton(onClick = {
                        scale = 1f
                        offset = Offset.Zero
                    }) {
                        Icon(Icons.Outlined.ZoomIn, contentDescription = "重置缩放")
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PreviewAction(
                                label = "下载",
                                loading = mediaAction == "下载",
                                enabled = mediaAction == null,
                                icon = { Icon(Icons.Outlined.Download, null, tint = Color.White) },
                                onClick = {
                                    runMediaAction("下载") {
                                        mediaFiles.download(request.url, request.title, request.mimeType)
                                    }
                                },
                            )
                            PreviewAction(
                                label = "分享",
                                loading = mediaAction == "分享",
                                enabled = mediaAction == null,
                                icon = { Icon(Icons.Outlined.Share, null, tint = Color.White) },
                                onClick = {
                                    runMediaAction("分享") {
                                        mediaFiles.share(request.url, request.title, request.mimeType)
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun PreviewAction(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            icon()
        }
        Spacer(Modifier.width(6.dp))
        Text(if (loading) "处理中" else label, color = Color.White)
    }
}
