package com.suakitsu.picpilot

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    private var activeWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            PicPilotTheme {
                PicPilotApp(
                    initialUrl = intent.toPicPilotUrl(),
                    onWebViewReady = { activeWebView = it }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeWebView?.loadUrl(intent.toPicPilotUrl())
    }

    override fun onDestroy() {
        activeWebView = null
        super.onDestroy()
    }

    private fun Intent?.toPicPilotUrl(): String {
        val raw = this?.dataString.orEmpty()
        if (PicPilotUrlPolicy.isPicPilotUrl(raw)) return raw
        if (raw.startsWith("picpilot://open")) {
            val encodedPath = Uri.parse(raw).getQueryParameter("path").orEmpty()
            val path = URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.name())
            if (path.startsWith("/") && !path.startsWith("//")) {
                return "${BuildConfig.PIC_PILOT_BASE_URL}$path"
            }
        }
        return "${BuildConfig.PIC_PILOT_BASE_URL}/workspace?client=android"
    }
}

private data class NativeDestination(
    val label: String,
    val path: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val nativeDestinations = listOf(
    NativeDestination("工作台", "/workspace", Icons.Outlined.Home),
    NativeDestination("电商制图", "/commerce", Icons.Outlined.Image),
    NativeDestination("商品视频", "/video-create", Icons.Outlined.Movie),
    NativeDestination("AI 创作", "/ai-create", Icons.Outlined.AutoAwesome),
    NativeDestination("AI 画布", "/workflows", Icons.Outlined.AccountTree),
    NativeDestination("历史记录", "/history", Icons.Outlined.History)
)

@Composable
private fun PicPilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) {
            darkColorScheme(primary = Color(0xFF60A5FA), background = Color(0xFF0F172A))
        } else {
            lightColorScheme(primary = Color(0xFF2563EB), background = Color(0xFFF8FAFC))
        },
        content = content
    )
}

@Composable
private fun PicPilotApp(initialUrl: String, onWebViewReady: (WebView) -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var loading by remember { mutableStateOf(true) }
    var mainFrameError by remember { mutableStateOf(false) }
    val showNativeNavigation = PicPilotUrlPolicy.isAuthenticatedSurface(currentUrl)

    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showNativeNavigation) {
                NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                    nativeDestinations.forEach { destination ->
                        val selected = Uri.parse(currentUrl).path?.let {
                            it == destination.path || it.startsWith("${destination.path}/")
                        } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                val target = "${BuildConfig.PIC_PILOT_BASE_URL}${destination.path}?client=android"
                                if (currentUrl != target) webView?.loadUrl(target)
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label, maxLines = 1, fontSize = 10.sp) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .safeDrawingPadding()
        ) {
            PicPilotWebView(
                initialUrl = initialUrl,
                onWebViewReady = {
                    webView = it
                    onWebViewReady(it)
                },
                onUrlChanged = { currentUrl = it },
                onLoadingChanged = { loading = it },
                onMainFrameError = { mainFrameError = it }
            )
            if (loading) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            if (mainFrameError && !loading) {
                NativeError(onRetry = {
                    mainFrameError = false
                    webView?.reload()
                })
            }
        }
    }
}

@Composable
private fun NativeError(onRetry: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("页面暂时无法加载", style = MaterialTheme.typography.titleMedium)
            Text("请检查网络后重试", color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.Button(onClick = onRetry) { Text("重新加载") }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PicPilotWebView(
    initialUrl: String,
    onWebViewReady: (WebView) -> Unit,
    onUrlChanged: (String) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onMainFrameError: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback
        fileCallback = null
        callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    val downloadListener = remember(context) {
        DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!url.startsWith("https://")) {
                Toast.makeText(context, "暂不支持该下载地址", Toast.LENGTH_SHORT).show()
                return@DownloadListener
            }
            runCatching {
                val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
                    .setTitle(filename)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                manager.enqueue(request)
                Toast.makeText(context, context.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "下载失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { factoryContext ->
            WebView(factoryContext).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    setSupportZoom(true)
                    builtInZoomControls = false
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = true
                    userAgentString = "$userAgentString PicPilotAndroid/${BuildConfig.VERSION_NAME}"
                }
                val picPilotWebView = this
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(picPilotWebView, true)
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        fileCallback?.onReceiveValue(null)
                        fileCallback = filePathCallback
                        val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()
                            ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                        return try {
                            fileChooser.launch(intent)
                            true
                        } catch (_: ActivityNotFoundException) {
                            fileCallback?.onReceiveValue(null)
                            fileCallback = null
                            false
                        }
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url.toString()
                        if (PicPilotUrlPolicy.shouldStayInApp(target)) return false
                        return openExternal(factoryContext, request.url)
                    }

                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onLoadingChanged(true)
                        onMainFrameError(false)
                        onUrlChanged(url)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        onLoadingChanged(false)
                        onUrlChanged(url)
                        view.evaluateJavascript(
                            "document.documentElement.setAttribute('data-picpilot-client','android');void(0);",
                            null
                        )
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        super.onReceivedError(view, request, error)
                        if (request.isForMainFrame) {
                            onLoadingChanged(false)
                            onMainFrameError(true)
                        }
                    }

                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        view.destroy()
                        onLoadingChanged(false)
                        onMainFrameError(true)
                        return true
                    }
                }
                setDownloadListener(downloadListener)
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                loadUrl(initialUrl)
                onWebViewReady(this)
            }
        },
        update = { }
    )

    DisposableEffect(Unit) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
        }
    }
}

private fun openExternal(context: Context, uri: Uri): Boolean {
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        if (uri.scheme == "intent") {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Toast.makeText(context, context.getString(R.string.cannot_open_link), Toast.LENGTH_SHORT).show()
        }
        true
    }
}
