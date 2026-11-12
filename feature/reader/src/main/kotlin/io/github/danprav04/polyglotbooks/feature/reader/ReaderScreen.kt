package io.github.danprav04.polyglotbooks.feature.reader

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    val readerBridge = remember { ReaderBridge(viewModel) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

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
                        webViewClient = WebViewClient()
                        addJavascriptInterface(readerBridge, "AndroidReaderBridge")
                        loadUrl("file:///android_asset/reader/index.html")
                        webViewInstance = this
                    }
                },
                update = { webView ->
                    val themeName = uiState.theme.name.lowercase()
                    val fontSize = uiState.fontSizeSp
                    webView.evaluateJavascript("window.FoliateBridge?.setTheme('$themeName', $fontSize, null);", null)

                    if (uiState.bilingualTranslations.isNotEmpty()) {
                        val jsonMap = StringBuilder("{")
                        uiState.bilingualTranslations.entries.forEachIndexed { idx, entry ->
                            if (idx > 0) jsonMap.append(",")
                            jsonMap.append("\"${entry.key}\": \"${entry.value.replace("\"", "\\\"")}\"")
                        }
                        jsonMap.append("}")
                        webView.evaluateJavascript("window.FoliateBridge?.setBilingualTranslations($jsonMap);", null)
                    } else {
                        webView.evaluateJavascript("window.FoliateBridge?.setBilingualTranslations({});", null)
                    }

                    uiState.highlightedCfi?.let { cfi ->
                        webView.evaluateJavascript("window.FoliateBridge?.highlightCfi('$cfi');", null)
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
                                    webViewInstance?.evaluateJavascript("window.FoliateBridge?.searchInBook('$query');", null)
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
                                        webViewInstance?.evaluateJavascript("window.FoliateBridge?.goToCfi('${result.cfi}');", null)
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
                            items(uiState.chapters) { chapter ->
                                ListItem(
                                    headlineContent = { Text(chapter.title) },
                                    modifier = Modifier.clickable {
                                        viewModel.saveProgress(chapter.cfiRange ?: "", chapter.index.toFloat() / maxOf(1, uiState.chapters.size))
                                        webViewInstance?.evaluateJavascript("window.FoliateBridge?.goToCfi('${chapter.cfiRange ?: ""}');", null)
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
