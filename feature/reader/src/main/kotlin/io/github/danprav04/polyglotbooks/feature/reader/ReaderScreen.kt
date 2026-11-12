package io.github.danprav04.polyglotbooks.feature.reader

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject

@SuppressLint("JavascriptInterface")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    title: String = "Reader",
    onBackClick: () -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val readerBridge = remember(viewModel) { ReaderBridge(viewModel) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isWebViewPageFinished by remember { mutableStateOf(false) }
    var hasOpenedBookInWebView by remember(bookId) { mutableStateOf(false) }
    var hasRestoredInitialCfi by remember(bookId) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(bookId) {
        if (bookId.isNotBlank() && viewModel.uiState.value.bookId != bookId) {
            viewModel.loadBookAndChapters(bookId)
        }
    }

    // Open book in WebView once both the HTML bridge and persisted Room state are ready.
    LaunchedEffect(isWebViewPageFinished, uiState.isStateRestored, webViewInstance, bookId) {
        val webView = webViewInstance
        if (webView != null && isWebViewPageFinished && uiState.isStateRestored && !hasOpenedBookInWebView) {
            hasOpenedBookInWebView = true
            viewModel.resetBookReady()
            val jsBookUri = JSONObject.quote(uiState.bookFilePath)
            webView.evaluateJavascript("window.FoliateBridge?.openBook($jsBookUri);", null)
        }
    }

    // Restore saved CFI position after the book reports ready inside the WebView.
    LaunchedEffect(isWebViewPageFinished, uiState.isBookReady, uiState.isStateRestored, webViewInstance, bookId) {
        val webView = webViewInstance
        if (webView != null && isWebViewPageFinished && uiState.isBookReady && uiState.isStateRestored && !hasRestoredInitialCfi) {
            hasRestoredInitialCfi = true
            val savedCfi = uiState.currentCfi
            if (savedCfi.isNotBlank()) {
                val jsSavedCfi = JSONObject.quote(savedCfi)
                webView.evaluateJavascript("window.FoliateBridge?.goToCfi($jsSavedCfi, false);", null)
            }
        }
    }

    // Flush reading position on pause/stop and when navigating away from the reader.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                webViewInstance?.evaluateJavascript("window.FoliateBridge?.flushPosition?.();", null)
                viewModel.flushProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.flushProgress()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.bookTitle.ifEmpty { title }) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setSearchOpen(!uiState.isSearchOpen) }) {
                        Icon(Icons.Default.Search, contentDescription = "Search in Book")
                    }
                    IconButton(onClick = { viewModel.toggleBilingualMode() }) {
                        Icon(
                            Icons.Default.Translate,
                            contentDescription = "Bilingual Mode",
                            tint = if (uiState.isBilingualEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { viewModel.setTocOpen(!uiState.isTocOpen) }) {
                        Icon(Icons.Default.Menu, contentDescription = "Table of Contents")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        var engineExpanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { engineExpanded = true }) {
                                Text(uiState.narrationEngine.name.replace("_", " "))
                            }
                            DropdownMenu(
                                expanded = engineExpanded,
                                onDismissRequest = { engineExpanded = false }
                            ) {
                                NarrationEngine.values().forEach { engine ->
                                    DropdownMenuItem(
                                        text = { Text(engine.name.replace("_", " ")) },
                                        onClick = {
                                            viewModel.setNarrationEngine(engine)
                                            engineExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { viewModel.toggleNarration() }) {
                            Icon(
                                if (uiState.isNarrationPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause"
                            )
                        }

                        IconButton(onClick = { viewModel.setCastEditorOpen(true) }) {
                            Icon(Icons.Default.Group, contentDescription = "Cast Editor")
                        }
                        IconButton(onClick = { viewModel.setReportDialogOpen(true) }) {
                            Icon(Icons.Default.Report, contentDescription = "Report AI Output")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isWebViewPageFinished = true
                            }
                        }
                        addJavascriptInterface(readerBridge, "AndroidReaderBridge")
                        loadUrl("file:///android_asset/reader/index.html")
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    if (!isWebViewPageFinished) return@AndroidView
                    val themeName = uiState.theme.name.lowercase()
                    val fontSize = uiState.fontSizeSp
                    webView.evaluateJavascript("window.FoliateBridge?.setTheme('$themeName', $fontSize, null);", null)

                    if (uiState.bilingualTranslations.isNotEmpty()) {
                        val jsonMap = JSONObject(uiState.bilingualTranslations).toString()
                        webView.evaluateJavascript("window.FoliateBridge?.setBilingualTranslations($jsonMap);", null)
                    } else {
                        webView.evaluateJavascript("window.FoliateBridge?.setBilingualTranslations({});", null)
                    }

                    uiState.highlightedCfi?.let { cfi ->
                        webView.evaluateJavascript("window.FoliateBridge?.highlightCfi(${JSONObject.quote(cfi)});", null)
                    }
                }
            )

            if (uiState.isSearchOpen) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    tonalElevation = 16.dp,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = uiState.searchQuery,
                                onValueChange = { query ->
                                    viewModel.setSearchQuery(query)
                                    webViewInstance?.evaluateJavascript("window.FoliateBridge?.searchInBook(${JSONObject.quote(query)});", null)
                                },
                                label = { Text("Search in book") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(onClick = { viewModel.setSearchOpen(false) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close Search")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(uiState.searchResults) { result ->
                                ListItem(
                                    headlineContent = { Text(result.excerpt) },
                                    supportingContent = { Text(result.cfi, style = MaterialTheme.typography.bodySmall) },
                                    modifier = Modifier.clickable {
                                        viewModel.highlightCfi(result.cfi)
                                        webViewInstance?.evaluateJavascript("window.FoliateBridge?.goToCfi(${JSONObject.quote(result.cfi)});", null)
                                        viewModel.setSearchOpen(false)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.isTocOpen) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(300.dp)
                        .align(Alignment.CenterStart),
                    tonalElevation = 16.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Table of Contents", style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { viewModel.setTocOpen(false) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close TOC")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyColumn {
                            itemsIndexed(uiState.chapters) { index, chapter ->
                                ListItem(
                                    headlineContent = { Text(chapter.title) },
                                    modifier = Modifier.clickable {
                                        val chapterCfi = chapter.cfiRange.orEmpty()
                                        val chapterProgress = (index.toFloat() / maxOf(1, uiState.chapters.size)).coerceIn(0f, 1f)
                                        viewModel.saveProgress(chapterCfi, chapterProgress)
                                        if (chapterCfi.isNotBlank()) {
                                            webViewInstance?.evaluateJavascript("window.FoliateBridge?.goToCfi(${JSONObject.quote(chapterCfi)}, false);", null)
                                        }
                                        viewModel.setTocOpen(false)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.isCastEditorOpen) {
                AlertDialog(
                    onDismissRequest = { viewModel.setCastEditorOpen(false) },
                    title = { Text("Character Cast Editor") },
                    text = { Text("Manage character voices, custom names, and audio profiles for narration.") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.setCastEditorOpen(false) }) {
                            Text("Close")
                        }
                    }
                )
            }

            if (uiState.isReportDialogOpen) {
                AlertDialog(
                    onDismissRequest = { viewModel.setReportDialogOpen(false) },
                    title = { Text("Report AI Output") },
                    text = { Text("Submit translation or pronunciation feedback for improvement.") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.setReportDialogOpen(false) }) {
                            Text("Submit")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.setReportDialogOpen(false) }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
