package com.suakitsu.picpilot.feature.canvas

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.suakitsu.picpilot.BuildConfig
import org.json.JSONObject

@Composable
fun CanvasRoute(
    onBack: () -> Unit,
    viewModel: CanvasViewModel = hiltViewModel(),
) {
    CanvasScreen(token = viewModel.token, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CanvasScreen(token: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf("") }
    var selectedCanvas by rememberSaveable { mutableIntStateOf(0) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var resumedAfterBackground by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        fileCallback?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
        fileCallback = null
    }
    val baseUri = remember { BuildConfig.WEB_BASE_URL.toUri() }
    val allowedHost = baseUri.host.orEmpty()
    val allowedOrigin = remember(baseUri) { baseUri.origin() }
    val canvasUrl = remember {
        Uri.withAppendedPath(baseUri, "workflows").buildUpon()
            .appendQueryParameter("client", "android")
            .appendQueryParameter("embedded", "1")
            .build()
            .toString()
    }

    DisposableEffect(Unit) {
        onDispose {
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            webView?.apply {
                evaluateJavascript(
                    "sessionStorage.removeItem('picpilot_native_token');localStorage.removeItem('openimage_token');",
                    null,
                )
                stopLoading()
                webChromeClient = null
                webViewClient = WebViewClient()
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }

    DisposableEffect(lifecycleOwner) {
        var wasPaused = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasPaused = true
                    webView?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    webView?.onResume()
                    if (wasPaused) {
                        wasPaused = false
                        resumedAfterBackground += 1
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumedAfterBackground) {
        if (resumedAfterBackground > 0) {
            kotlinx.coroutines.delay(350)
            val current = webView ?: return@LaunchedEffect
            current.invalidate()
            if (loadError.isNotBlank()) {
                loadError = ""
                loading = true
                current.reload()
            } else {
                current.evaluateJavascript(NATIVE_CANVAS_RESUME_SCRIPT) { result ->
                    if (result.contains("missing", ignoreCase = true)) {
                        current.postDelayed({
                            current.evaluateJavascript(NATIVE_CANVAS_RESUME_SCRIPT) { retryResult ->
                                if (retryResult.contains("missing", ignoreCase = true)) {
                                    loading = true
                                    current.reload()
                                }
                            }
                        }, 900)
                    }
                }
            }
        }
    }

    BackHandler {
        val current = webView
        if (current?.canGoBack() == true) current.goBack() else onBack()
    }

    fun selectCanvas(index: Int) {
        selectedCanvas = index
        webView?.evaluateJavascript(nativeCanvasSelectionScript(index), null)
    }

    fun waitForCanvasReady(view: WebView, attempt: Int = 0) {
        view.evaluateJavascript(
            "Boolean(document.querySelector('.canvas-lab-frame.is-active.is-ready'))",
        ) { result ->
            if (result == "true" || attempt >= 80) {
                loading = false
            } else {
                view.postDelayed({ waitForCanvasReady(view, attempt + 1) }, 250)
            }
        }
    }

    Scaffold(
        containerColor = if (selectedCanvas == 1) Color.Black else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                ) {
                    listOf("工作台", "自由画布").forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = selectedCanvas == index,
                            onClick = { selectCanvas(index) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { loadError = ""; loading = true; webView?.reload() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新画布")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    WebView(viewContext).apply {
                        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
                        webView = this
                        setBackgroundColor(android.graphics.Color.WHITE)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.offscreenPreRaster = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.setSupportMultipleWindows(false)
                        settings.userAgentString = "${settings.userAgentString} PicPilotAndroid/${BuildConfig.VERSION_NAME}"
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

                        val quotedToken = JSONObject.quote(token)
                        val quotedNativeStyle = JSONObject.quote(NATIVE_CANVAS_CSS)
                        val bootstrap = """
                            (() => {
                              try {
                                if (window.top !== window) return;
                                const nativeTokenKey = 'picpilot_native_token';
                                const authTokenKey = 'openimage_token';
                                const originalGet = Storage.prototype.getItem;
                                const originalSet = Storage.prototype.setItem;
                                const originalRemove = Storage.prototype.removeItem;
                                originalRemove.call(localStorage, authTokenKey);
                                originalSet.call(sessionStorage, nativeTokenKey, $quotedToken);
                                Storage.prototype.getItem = function(key) {
                                  if (this === localStorage && key === authTokenKey) {
                                    return originalGet.call(sessionStorage, nativeTokenKey) || '';
                                  }
                                  return originalGet.call(this, key);
                                };
                                Storage.prototype.setItem = function(key, value) {
                                  if (this === localStorage && key === authTokenKey) {
                                    return originalSet.call(sessionStorage, nativeTokenKey, value);
                                  }
                                  return originalSet.call(this, key, value);
                                };
                                Storage.prototype.removeItem = function(key) {
                                  if (this === localStorage && key === authTokenKey) {
                                    return originalRemove.call(sessionStorage, nativeTokenKey);
                                  }
                                  return originalRemove.call(this, key);
                                };
                                document.documentElement.dataset.picpilotClient = 'android';
                                window.__picpilotNativeCanvasIndex = 0;
                                window.__picpilotApplyNativeCanvas = () => {
                                  const buttons = document.querySelectorAll('.canvas-lab-switch button');
                                  const index = Number(window.__picpilotNativeCanvasIndex || 0);
                                  if (buttons.length < 2 || !buttons[index]) return false;
                                  buttons[index].click();
                                  return true;
                                };
                                window.__picpilotApplyNativeViewport = () => {
                                  const viewportHeight = Math.max(
                                    1,
                                    Math.round(window.visualViewport?.height || window.innerHeight || 1)
                                  );
                                  const viewportWidth = Math.max(
                                    1,
                                    Math.round(window.visualViewport?.width || window.innerWidth || 1)
                                  );
                                  document.documentElement.style.setProperty('--picpilot-native-height', `${'$'}{viewportHeight}px`);
                                  document.documentElement.style.setProperty('--picpilot-native-width', `${'$'}{viewportWidth}px`);
                                  const heightTargets = [
                                    document.documentElement,
                                    document.body,
                                    document.querySelector('.user-shell'),
                                    document.querySelector('.user-main'),
                                    document.querySelector('.user-route-stage'),
                                    document.querySelector('.canvas-lab'),
                                    document.querySelector('.canvas-lab-frame-wrap')
                                  ];
                                  heightTargets.forEach(element => {
                                    if (!element) return;
                                    element.style.setProperty('height', `${'$'}{viewportHeight}px`, 'important');
                                    element.style.setProperty('min-height', `${'$'}{viewportHeight}px`, 'important');
                                  });
                                  return viewportHeight;
                                };
                                const nativeCanvasObserver = new MutationObserver(() => {
                                  window.__picpilotApplyNativeViewport();
                                  window.__picpilotApplyNativeCanvas();
                                });
                                nativeCanvasObserver.observe(document.documentElement, { childList: true, subtree: true });
                                window.addEventListener('resize', window.__picpilotApplyNativeViewport, { passive: true });
                                window.visualViewport?.addEventListener('resize', window.__picpilotApplyNativeViewport, { passive: true });
                                const ensureNativeStyle = () => {
                                  if (document.getElementById('picpilot-native-embed-style')) return;
                                  const style = document.createElement('style');
                                  style.id = 'picpilot-native-embed-style';
                                  style.textContent = $quotedNativeStyle;
                                  (document.head || document.documentElement).appendChild(style);
                                };
                                ensureNativeStyle();
                                window.__picpilotApplyNativeViewport();
                                document.addEventListener('DOMContentLoaded', () => {
                                  ensureNativeStyle();
                                  window.__picpilotApplyNativeViewport();
                                }, { once: true });
                              } catch (_) {}
                            })();
                        """.trimIndent()
                        val supportsSecureBootstrap = if (
                            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                        ) {
                            WebViewCompat.addDocumentStartJavaScript(this, bootstrap, setOf(allowedOrigin))
                            true
                        } else {
                            loading = false
                            loadError = "系统 WebView 版本过旧，请更新 Android System WebView 后再打开画布"
                            false
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                webView: WebView?,
                                callback: ValueCallback<Array<Uri>>?,
                                params: FileChooserParams?,
                            ): Boolean {
                                fileCallback?.onReceiveValue(null)
                                fileCallback = callback
                                val types = params?.acceptTypes
                                    ?.flatMap(::safeChooserTypes)
                                    ?.distinct()
                                    ?.toTypedArray()
                                    ?.takeIf(Array<String>::isNotEmpty)
                                    ?: ALLOWED_CHOOSER_TYPES
                                picker.launch(types)
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                loading = true
                                loadError = ""
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                val current = url?.toUri()
                                if (current != null && current.isSameOrigin(baseUri) && supportsSecureBootstrap) {
                                    view?.evaluateJavascript("window.__picpilotApplyNativeViewport?.()", null)
                                    view?.evaluateJavascript(nativeCanvasSelectionScript(selectedCanvas), null)
                                    view?.let(::waitForCanvasReady)
                                } else if (current != null && !current.isSameOrigin(baseUri)) {
                                    loading = false
                                    loadError = "画布跳转到了未授权地址，已停止加载"
                                }
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val target = request?.url ?: return true
                                val allowed = target.isSameOrigin(baseUri)
                                if (allowed) return false
                                if (target.scheme.equals("https", ignoreCase = true)) {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }.onFailure { Toast.makeText(context, "无法打开该链接", Toast.LENGTH_SHORT).show() }
                                } else {
                                    Toast.makeText(context, "已阻止不安全的外部链接", Toast.LENGTH_SHORT).show()
                                }
                                return true
                            }

                            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                                handler?.cancel()
                                loadError = "安全连接校验失败，请检查网络和系统时间"
                                loading = false
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loadError = "画布暂时无法加载，请稍后重试"
                                    loading = false
                                }
                            }
                        }
                        setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                            enqueueDownload(context, url, userAgent, contentDisposition, mimeType, baseUri)
                        })
                        if (supportsSecureBootstrap) loadUrl(canvasUrl)
                    }
                },
            )

            if (loading) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(14.dp))
                        Text("正在打开 AI 画布")
                    }
                }
            }
            if (loadError.isNotBlank()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(loadError, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { loadError = ""; loading = true; webView?.reload() }) { Text("重新加载") }
                    }
                }
            }
        }
    }
}

private fun enqueueDownload(
    context: Context,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
    baseUri: Uri,
) {
    val target = runCatching { url.toUri() }.getOrNull() ?: return
    if (!target.isSameOrigin(baseUri)) {
        Toast.makeText(context, "仅允许下载 PicPilot 生成的文件", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(target)
            .setTitle(filename)
            .setMimeType(mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, filename)
        if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        Toast.makeText(context, "已加入下载任务", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "下载失败，请稍后重试", Toast.LENGTH_SHORT).show()
    }
}

private fun Uri.origin(): String = buildString {
    append(scheme?.lowercase().orEmpty())
    append("://")
    append(host?.lowercase().orEmpty())
    if (port != -1) append(':').append(port)
}

private fun Uri.isSameOrigin(other: Uri): Boolean =
    scheme.equals(other.scheme, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        effectivePort() == other.effectivePort()

private fun Uri.effectivePort(): Int = when {
    port != -1 -> port
    scheme.equals("https", ignoreCase = true) -> 443
    scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
}

private fun safeChooserTypes(value: String): List<String> {
    val normalized = value.substringBefore(';').trim().lowercase()
    return when {
        normalized == "image/*" || normalized.startsWith("image/") -> listOf("image/*")
        normalized == "video/*" || normalized.startsWith("video/") -> listOf("video/*")
        normalized == "audio/*" || normalized.startsWith("audio/") -> listOf("audio/*")
        else -> emptyList()
    }
}

private val ALLOWED_CHOOSER_TYPES = arrayOf("image/*", "video/*", "audio/*")

internal val NATIVE_CANVAS_CSS = """
    .user-sidebar,.user-topbar,.user-mobile-dock,.global-assistant,
    .community-float,.user-back-to-top,.canvas-lab-toolbar{display:none!important}
    html,body{width:100%!important;height:var(--picpilot-native-height,100vh)!important;
      min-height:var(--picpilot-native-height,100vh)!important;margin:0!important;
      overflow:hidden!important;scrollbar-gutter:auto!important}
    .user-shell{display:block!important;width:100%!important;
      height:var(--picpilot-native-height,100vh)!important;min-height:var(--picpilot-native-height,100vh)!important}
    .user-main,.user-main-workflow{width:100%!important;max-width:none!important;
      margin:0!important;padding:0!important;height:var(--picpilot-native-height,100vh)!important;
      min-height:var(--picpilot-native-height,100vh)!important}
    .user-route-stage,.canvas-lab{height:var(--picpilot-native-height,100vh)!important;
      min-height:var(--picpilot-native-height,100vh)!important;border:0!important;border-radius:0!important}
    .canvas-lab{display:block!important;position:relative!important}
    .canvas-lab-frame-wrap{position:absolute!important;inset:0!important;width:100%!important;
      height:var(--picpilot-native-height,100vh)!important;
      min-height:var(--picpilot-native-height,100vh)!important;border:0!important;border-radius:0!important}
    .canvas-lab-frame.is-active,.canvas-lab-frame.is-active.is-ready{
      opacity:1!important;visibility:visible!important;pointer-events:auto!important}
""".trimIndent()

private fun nativeCanvasSelectionScript(index: Int) = """
    (() => {
      window.__picpilotNativeCanvasIndex = ${index.coerceIn(0, 1)};
      const apply = window.__picpilotApplyNativeCanvas || (() => {
        const buttons = document.querySelectorAll('.canvas-lab-switch button');
        const button = buttons[window.__picpilotNativeCanvasIndex];
        if (!button) return false;
        button.click();
        return true;
      });
      apply();
      window.setTimeout(apply, 350);
      window.setTimeout(apply, 1200);
    })();
""".trimIndent()

private val NATIVE_CANVAS_RESUME_SCRIPT = """
    (() => {
      window.dispatchEvent(new Event('focus'));
      document.dispatchEvent(new Event('visibilitychange'));
      window.__picpilotApplyNativeViewport?.();
      window.__picpilotApplyNativeCanvas?.();
      const frame = document.querySelector('.canvas-lab-frame.is-active');
      if (!frame) return 'missing';
      frame.style.opacity = '1';
      frame.style.visibility = 'visible';
      frame.style.pointerEvents = 'auto';
      frame.contentWindow?.postMessage({
        protocol: 'picpilot-canvas-v1',
        type: 'picpilot:host:resume'
      }, '*');
      return 'ok';
    })();
""".trimIndent()
