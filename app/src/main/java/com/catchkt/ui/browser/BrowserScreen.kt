package com.catchkt.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.catchkt.data.model.DownloadStatus
import com.catchkt.data.model.SniffedResource
import com.catchkt.service.DownloadService
import kotlinx.coroutines.launch

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel()
) {
    val currentUrl by viewModel.currentUrl.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadingProgress by viewModel.loadingProgress.collectAsState()
    val latestResource by viewModel.latestSniffedResource.collectAsState()
    val showSheet by viewModel.showDownloadSheet.collectAsState()
    val downloadTasks by viewModel.downloadTasks.collectAsState()
    val progressMap by viewModel.downloadProgress.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var urlInput by remember { mutableStateOf(currentUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Show snackbar when a resource is sniffed
    LaunchedEffect(latestResource) {
        latestResource?.let { resource ->
            val sizeStr = formatFileSize(resource.contentLength)
            val result = snackbarHostState.showSnackbar(
                message = "發現媒體資源: ${resource.fileName} ($sizeStr)",
                actionLabel = "下載"
            )
            if (result == SnackbarResult.ActionPerformed) {
                context.startForegroundService(
                    DownloadService.startDownload(context, resource)
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("輸入網址") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    val url = normalizeUrl(urlInput)
                                    urlInput = url
                                    webView?.loadUrl(url)
                                }
                            )
                        )
                    },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "重新整理")
                        }
                    }
                )
                if (isLoading && loadingProgress < 100) {
                    @Suppress("DEPRECATION")
                    LinearProgressIndicator(
                        progress = loadingProgress / 100f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        bottomBar = {
            BottomNavigationBar(
                webView = webView,
                downloadCount = downloadTasks.size,
                onDownloadListClick = { viewModel.toggleDownloadList() }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            BrowserWebView(
                initialUrl = currentUrl,
                onWebViewCreated = { webView = it },
                onUrlChanged = { url ->
                    viewModel.onUrlChanged(url)
                    urlInput = url
                },
                onPageStarted = { viewModel.onPageStarted(it) },
                onPageFinished = { viewModel.onPageFinished() },
                onProgressChanged = { viewModel.onProgressChanged(it) },
                onUrlIntercepted = { viewModel.onUrlIntercepted(it) }
            )
        }
    }

    // Download list bottom sheet
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissDownloadSheet() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            DownloadListSheet(
                tasks = downloadTasks,
                progressMap = progressMap,
                latestResource = latestResource,
                onDownloadResource = { resource ->
                    context.startForegroundService(
                        DownloadService.startDownload(context, resource)
                    )
                    viewModel.dismissDownloadSheet()
                },
                onPause = { taskId ->
                    context.startService(DownloadService.pauseDownload(context, taskId))
                },
                onResume = { taskId ->
                    context.startForegroundService(
                        DownloadService.resumeDownload(context, taskId)
                    )
                }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserWebView(
    initialUrl: String,
    onWebViewCreated: (WebView) -> Unit,
    onUrlChanged: (String) -> Unit,
    onPageStarted: (String) -> Unit,
    onPageFinished: () -> Unit,
    onProgressChanged: (Int) -> Unit,
    onUrlIntercepted: (String) -> Unit
) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_USER_AGENT
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = true
                    displayZoomControls = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    mediaPlaybackRequiresUserGesture = false
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        url?.let { onPageStarted(it) }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        onPageFinished()
                        url?.let { onUrlChanged(it) }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        request?.url?.toString()?.let { url ->
                            // Non-blocking: just notify the sniffer and return null immediately
                            onUrlIntercepted(url)
                        }
                        return null // Let WebView handle all requests normally
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress)
                    }
                }
                loadUrl(initialUrl)
                onWebViewCreated(this)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun BottomNavigationBar(
    webView: WebView?,
    downloadCount: Int,
    onDownloadListClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { webView?.goBack() }, enabled = webView?.canGoBack() == true) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
        }
        IconButton(onClick = { webView?.goForward() }, enabled = webView?.canGoForward() == true) {
            Icon(Icons.Default.ArrowForward, contentDescription = "前進")
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onDownloadListClick) {
            BadgedBox(
                badge = {
                    if (downloadCount > 0) {
                        Badge { Text("$downloadCount") }
                    }
                }
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = "下載列表")
            }
        }
    }
}

@Composable
fun DownloadListSheet(
    tasks: List<com.catchkt.data.model.DownloadTask>,
    progressMap: Map<Long, com.catchkt.engine.download.DownloadProgress>,
    latestResource: SniffedResource?,
    onDownloadResource: (SniffedResource) -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            "下載管理",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Show latest sniffed resource if available
        latestResource?.let { resource ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            resource.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${resource.contentType} - ${formatFileSize(resource.contentLength)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { onDownloadResource(resource) }) {
                        Text("下載")
                    }
                }
            }
        }

        if (tasks.isEmpty()) {
            Text(
                "尚無下載任務",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            LazyColumn {
                items(tasks, key = { it.id }) { task ->
                    val progress = progressMap[task.id]
                    DownloadTaskItem(
                        task = task,
                        progress = progress,
                        onPause = { onPause(task.id) },
                        onResume = { onResume(task.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun DownloadTaskItem(
    task: com.catchkt.data.model.DownloadTask,
    progress: com.catchkt.engine.download.DownloadProgress?,
    onPause: () -> Unit,
    onResume: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val statusText = when (progress?.status ?: task.status) {
                        DownloadStatus.PENDING -> "等待中"
                        DownloadStatus.DOWNLOADING -> "下載中"
                        DownloadStatus.PAUSED -> "已暫停"
                        DownloadStatus.COMPLETED -> "已完成"
                        DownloadStatus.ERROR -> "錯誤"
                    }
                    val downloaded = progress?.downloadedSize ?: task.downloadedSize
                    val total = progress?.totalSize ?: task.totalSize
                    Text(
                        "$statusText - ${formatFileSize(downloaded)} / ${formatFileSize(total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val currentStatus = progress?.status ?: task.status
                when (currentStatus) {
                    DownloadStatus.DOWNLOADING -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Default.Close, contentDescription = "暫停")
                        }
                    }
                    DownloadStatus.PAUSED, DownloadStatus.ERROR -> {
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.Download, contentDescription = "繼續")
                        }
                    }
                    else -> {}
                }
            }

            val downloaded = progress?.downloadedSize ?: task.downloadedSize
            val total = progress?.totalSize ?: task.totalSize
            if (total > 0 && (progress?.status ?: task.status) == DownloadStatus.DOWNLOADING) {
                @Suppress("DEPRECATION")
                LinearProgressIndicator(
                    progress = (downloaded.toFloat() / total).coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    }
}

private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.contains('.') -> "https://$trimmed"
        else -> "https://www.google.com/search?q=${trimmed}"
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "未知大小"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return "%.1f %s".format(size, units[unitIndex])
}
