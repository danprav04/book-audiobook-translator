package io.github.danprav04.polyglotbooks.feature.reader

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.danprav04.polyglotbooks.core.data.db.CharacterEntity
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.feature.narration.GeminiStudioVoices
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

private const val READER_ORIGIN_HOST = "appassets.androidplatform.net"
private const val READER_BASE_URL = "https://$READER_ORIGIN_HOST"

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
    val latestUiState by rememberUpdatedState(uiState)
    val readerBridge = remember(viewModel) { ReaderBridge(viewModel) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isWebViewPageFinished by remember { mutableStateOf(false) }
    var hasOpenedBookInWebView by remember(bookId) { mutableStateOf(false) }
    var hasRestoredInitialCfi by remember(bookId) { mutableStateOf(false) }
    var lastSyncedChapterIndex by remember(bookId) { mutableStateOf<Int?>(null) }
    var isAppearanceDialogOpen by remember { mutableStateOf(false) }
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
            val jsBookUrl = JSONObject.quote("$READER_BASE_URL/book/file")
            val jsFormat = JSONObject.quote(uiState.bookFormat)
            val jsInitialCfi = JSONObject.quote(uiState.currentCfi)
            webView.evaluateJavascript(
                "window.FoliateBridge?.openBook($jsBookUrl, $jsFormat, $jsInitialCfi);",
                null
            )
        }
    }

    // Restore saved CFI position and sync initial chapter index after book reports ready.
    LaunchedEffect(isWebViewPageFinished, uiState.isBookReady, uiState.isStateRestored, webViewInstance, bookId) {
        val webView = webViewInstance
        if (webView != null && isWebViewPageFinished && uiState.isBookReady && uiState.isStateRestored && !hasRestoredInitialCfi) {
            hasRestoredInitialCfi = true
            lastSyncedChapterIndex = uiState.currentChapterIndex
            val savedCfi = uiState.currentCfi
            if (savedCfi.isNotBlank()) {
                val jsSavedCfi = JSONObject.quote(savedCfi)
                webView.evaluateJavascript("window.FoliateBridge?.goToCfi($jsSavedCfi, false);", null)
            }
        }
    }

    // Sync explicit chapter/page changes (TOC, Next, Prev) to the WebView.
    LaunchedEffect(isWebViewPageFinished, uiState.isBookReady, uiState.currentChapterIndex, webViewInstance) {
        val webView = webViewInstance
        if (webView != null && isWebViewPageFinished && uiState.isBookReady &&
            lastSyncedChapterIndex != null && uiState.currentChapterIndex != lastSyncedChapterIndex
        ) {
            lastSyncedChapterIndex = uiState.currentChapterIndex
            webView.evaluateJavascript("window.FoliateBridge?.loadChapter(${uiState.currentChapterIndex}, false);", null)
        }
    }

    // Sync theme, font size, and font family to WebView.
    LaunchedEffect(isWebViewPageFinished, uiState.theme, uiState.fontSizeSp, uiState.fontFamily, webViewInstance) {
        val webView = webViewInstance
        if (webView != null && isWebViewPageFinished) {
            val themeName = uiState.theme.name.lowercase()
            val cssFontFamily = when (uiState.fontFamily.lowercase()) {
                "serif" -> "Georgia, serif"
                "monospace" -> "monospace"
                else -> "sans-serif"
            }
            webView.evaluateJavascript(
                "window.FoliateBridge?.setTheme(${JSONObject.quote(themeName)}, ${uiState.fontSizeSp}, ${JSONObject.quote(cssFontFamily)});",
                null
            )
        }
    }

    // Sync bilingual translations to WebView.
    LaunchedEffect(
        isWebViewPageFinished,
        uiState.isBookReady,
        uiState.isBilingualEnabled,
        uiState.bilingualTranslations,
        webViewInstance
    ) {
        val webView = webViewInstance
        if (webView != null && isWebViewPageFinished) {
            if (uiState.isBilingualEnabled && uiState.bilingualTranslations.isNotEmpty()) {
                val jsonPayload = JSONObject.quote(JSONObject(uiState.bilingualTranslations).toString())
                webView.evaluateJavascript("window.FoliateBridge?.setBilingualTranslations($jsonPayload);", null)
            } else {
                webView.evaluateJavascript("window.FoliateBridge?.setBilingualTranslations({});", null)
            }
        }
    }

    // Sync active highlight CFI to WebView.
    LaunchedEffect(isWebViewPageFinished, uiState.isBookReady, uiState.highlightedCfi, webViewInstance) {
        val webView = webViewInstance
        if (webView != null && isWebViewPageFinished) {
            val cfi = uiState.highlightedCfi
            if (!cfi.isNullOrBlank()) {
                webView.evaluateJavascript("window.FoliateBridge?.highlightCfi(${JSONObject.quote(cfi)});", null)
            } else {
                webView.evaluateJavascript("window.FoliateBridge?.clearHighlights();", null)
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
            viewModel.stopNarration()
            viewModel.flushProgress()
        }
    }

    val currentChapterSubtitle = remember(uiState.chapters, uiState.currentChapterIndex, uiState.extractedBook) {
        val fromDb = uiState.chapters.getOrNull(uiState.currentChapterIndex)?.title
        val fromExtracted = uiState.extractedBook?.chapters?.getOrNull(uiState.currentChapterIndex)?.title
        val total = maxOf(uiState.chapters.size, uiState.extractedBook?.chapters?.size ?: 0)
        val label = fromDb?.takeIf { it.isNotBlank() } ?: fromExtracted?.takeIf { it.isNotBlank() }
        when {
            label != null && total > 0 -> "$label (${uiState.currentChapterIndex + 1}/$total)"
            total > 0 -> "Chapter ${uiState.currentChapterIndex + 1} / $total"
            else -> ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.bookTitle.ifEmpty { title },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (currentChapterSubtitle.isNotBlank()) {
                            Text(
                                text = currentChapterSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setSearchOpen(!uiState.isSearchOpen) }) {
                        Icon(Icons.Default.Search, contentDescription = "Search in Book")
                    }
                    BadgedBox(
                        badge = {
                            Badge(
                                containerColor = if (uiState.isBilingualEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = uiState.targetLanguage.code.uppercase().take(4),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    ) {
                        IconButton(onClick = { viewModel.toggleBilingualMode() }) {
                            Icon(
                                Icons.Default.Translate,
                                contentDescription = "Bilingual Mode",
                                tint = if (uiState.isBilingualEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                    IconButton(onClick = { isAppearanceDialogOpen = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "Appearance and Language")
                    }
                    IconButton(onClick = { viewModel.setTocOpen(!uiState.isTocOpen) }) {
                        Icon(Icons.Default.Menu, contentDescription = "Table of Contents")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.prevChapter() },
                            enabled = uiState.currentChapterIndex > 0
                        ) {
                            Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous Chapter")
                        }
                        IconButton(
                            onClick = { viewModel.nextChapter() },
                            enabled = uiState.currentChapterIndex < maxOf(
                                uiState.chapters.lastIndex,
                                (uiState.extractedBook?.chapters?.lastIndex ?: -1)
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next Chapter")
                        }
                    }

                    var engineExpanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            onClick = { engineExpanded = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = when (uiState.narrationEngine) {
                                    NarrationEngine.GEMINI_FLASH_TTS -> "Gemini TTS"
                                    NarrationEngine.GEMINI_LIVE -> "Gemini Live"
                                    NarrationEngine.SYSTEM_TTS -> "System TTS"
                                },
                                maxLines = 1
                            )
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

                    SmallFloatingActionButton(
                        onClick = { viewModel.toggleNarration() },
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            if (uiState.isNarrationPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause Narration"
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isTranslating || uiState.isCastAnalyzing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            uiState.errorMessage?.let { errorMsg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMsg,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss Error")
                        }
                    }
                }
            }

            uiState.narrationStatusMessage?.let { statusMsg ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = statusMsg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss Notice", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                settings.isAlgorithmicDarkeningAllowed = false
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val uri = request?.url ?: return null
                                    if (!uri.host.equals(READER_ORIGIN_HOST, ignoreCase = true)) {
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                    return handleAppAssetsRequest(
                                        context = context,
                                        path = uri.path.orEmpty(),
                                        uiState = latestUiState
                                    )
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isWebViewPageFinished = true
                                }
                            }
                            addJavascriptInterface(readerBridge, "AndroidReaderBridge")
                            loadUrl("$READER_BASE_URL/assets/reader/index.html")
                            webViewInstance = this
                        }
                    },
                    update = { _ -> }
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
                                        webViewInstance?.evaluateJavascript(
                                            "window.FoliateBridge?.searchInBook(${JSONObject.quote(query)});",
                                            null
                                        )
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
                            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                                items(uiState.searchResults) { result ->
                                    ListItem(
                                        headlineContent = { Text(result.excerpt) },
                                        supportingContent = {
                                            Text(result.cfi, style = MaterialTheme.typography.bodySmall)
                                        },
                                        modifier = Modifier.clickable {
                                            viewModel.highlightCfi(result.cfi)
                                            webViewInstance?.evaluateJavascript(
                                                "window.FoliateBridge?.goToCfi(${JSONObject.quote(result.cfi)});",
                                                null
                                            )
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
                                    val isSelected = index == uiState.currentChapterIndex
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                text = chapter.title,
                                                color = if (isSelected) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                        },
                                        modifier = Modifier
                                            .background(
                                                if (isSelected) {
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                                } else {
                                                    MaterialTheme.colorScheme.surface
                                                }
                                            )
                                            .clickable {
                                                viewModel.selectChapter(chapter)
                                                val chapterCfi = chapter.cfiRange.orEmpty()
                                                if (chapterCfi.isNotBlank()) {
                                                    webViewInstance?.evaluateJavascript(
                                                        "window.FoliateBridge?.goToCfi(${JSONObject.quote(chapterCfi)}, false);",
                                                        null
                                                    )
                                                }
                                                viewModel.setTocOpen(false)
                                            }
                                    )
                                }
                            }
                        }
                    }
                }

                if (isAppearanceDialogOpen) {
                    AppearanceAndLanguageDialog(
                        uiState = uiState,
                        onDismiss = { isAppearanceDialogOpen = false },
                        onThemeSelected = viewModel::setTheme,
                        onFontSizeChange = viewModel::setFontSize,
                        onFontFamilySelected = viewModel::setFontFamily,
                        onTargetLanguageSelected = viewModel::setTargetLanguage
                    )
                }

                if (uiState.isCastEditorOpen) {
                    CastEditorDialog(
                        characters = uiState.characters,
                        isAnalyzing = uiState.isCastAnalyzing,
                        onAnalyzeCast = { viewModel.analyzeCastForCurrentChapter() },
                        onUpdateVoice = { character, voiceId, style ->
                            viewModel.updateCharacterVoice(character, voiceId, style)
                        },
                        onDismiss = { viewModel.setCastEditorOpen(false) }
                    )
                }

                if (uiState.isReportDialogOpen) {
                    var feedbackText by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { viewModel.setReportDialogOpen(false) },
                        title = { Text("Report AI Output") },
                        text = {
                            Column {
                                Text("Submit translation or pronunciation feedback for improvement.")
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = feedbackText,
                                    onValueChange = { feedbackText = it },
                                    label = { Text("Details (optional)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
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
}

@Composable
private fun AppearanceAndLanguageDialog(
    uiState: ReaderUiState,
    onDismiss: () -> Unit,
    onThemeSelected: (ReaderTheme) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onFontFamilySelected: (String) -> Unit,
    onTargetLanguageSelected: (LanguageCode) -> Unit
) {
    var langMenuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reader Appearance & Language") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReaderTheme.values().forEach { theme ->
                        FilterChip(
                            selected = uiState.theme == theme,
                            onClick = { onThemeSelected(theme) },
                            label = { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }

                Text("Font Size: ${uiState.fontSizeSp}sp", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = { onFontSizeChange(uiState.fontSizeSp - 2) }) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease Font Size")
                    }
                    Slider(
                        value = uiState.fontSizeSp.toFloat(),
                        onValueChange = { onFontSizeChange(it.roundToInt()) },
                        valueRange = 12f..32f,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onFontSizeChange(uiState.fontSizeSp + 2) }) {
                        Icon(Icons.Default.Add, contentDescription = "Increase Font Size")
                    }
                }

                Text("Font Family", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Sans-Serif", "Serif", "Monospace").forEach { family ->
                        FilterChip(
                            selected = uiState.fontFamily.equals(family, ignoreCase = true),
                            onClick = { onFontFamilySelected(family) },
                            label = { Text(family) }
                        )
                    }
                }

                Text("Target Translation Language", style = MaterialTheme.typography.labelLarge)
                Box {
                    OutlinedButton(
                        onClick = { langMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${uiState.targetLanguage.displayName} (${uiState.targetLanguage.code})")
                    }
                    DropdownMenu(
                        expanded = langMenuExpanded,
                        onDismissRequest = { langMenuExpanded = false },
                        modifier = Modifier.heightIn(max = 280.dp)
                    ) {
                        LanguageCode.SUPPORTED_LIVE_LANGUAGES.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text("${lang.displayName} (${lang.code})") },
                                onClick = {
                                    onTargetLanguageSelected(lang)
                                    langMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun CastEditorDialog(
    characters: List<CharacterEntity>,
    isAnalyzing: Boolean,
    onAnalyzeCast: () -> Unit,
    onUpdateVoice: (CharacterEntity, String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Character Cast Editor") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onAnalyzeCast,
                    enabled = !isAnalyzing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Detecting Characters...")
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Auto-Detect Characters")
                    }
                }

                if (characters.isEmpty()) {
                    Text(
                        text = "No characters detected yet. Tap Auto-Detect Characters to assign distinct Gemini voices for dialogue in this chapter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(characters, key = { it.id }) { character ->
                            CastCharacterRow(
                                character = character,
                                onUpdateVoice = onUpdateVoice
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun CastCharacterRow(
    character: CharacterEntity,
    onUpdateVoice: (CharacterEntity, String, String?) -> Unit
) {
    var voiceDropdownExpanded by remember { mutableStateOf(false) }
    var styleText by remember(character.id, character.voiceTraits) { mutableStateOf(character.voiceTraits) }
    val selectedVoice = character.assignedVoiceId ?: "Charon"

    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = character.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    OutlinedButton(
                        onClick = { voiceDropdownExpanded = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(selectedVoice, style = MaterialTheme.typography.labelMedium)
                    }
                    DropdownMenu(
                        expanded = voiceDropdownExpanded,
                        onDismissRequest = { voiceDropdownExpanded = false },
                        modifier = Modifier.heightIn(max = 260.dp)
                    ) {
                        GeminiStudioVoices.catalog.forEach { voice ->
                            DropdownMenuItem(
                                text = {
                                    Text("${voice.name} (${voice.gender}, ${voice.traits.joinToString()})")
                                },
                                onClick = {
                                    voiceDropdownExpanded = false
                                    onUpdateVoice(character, voice.id, styleText)
                                }
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = styleText,
                    onValueChange = { styleText = it },
                    label = { Text("Voice style / traits") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onUpdateVoice(character, selectedVoice, styleText) }
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Save Voice Style")
                }
            }
        }
    }
}

private fun handleAppAssetsRequest(
    context: android.content.Context,
    path: String,
    uiState: ReaderUiState
): WebResourceResponse {
    val corsHeaders = mapOf("Access-Control-Allow-Origin" to "*")
    return try {
        when {
            path.startsWith("/assets/reader/") -> {
                val assetPath = "reader/" + path.removePrefix("/assets/reader/")
                val stream = context.assets.open(assetPath)
                WebResourceResponse(guessMimeType(assetPath), "utf-8", 200, "OK", corsHeaders, stream)
            }
            path.startsWith("/assets/foliate-js/") -> {
                val assetPath = "foliate-js/" + path.removePrefix("/assets/foliate-js/")
                val stream = context.assets.open(assetPath)
                WebResourceResponse(guessMimeType(assetPath), "utf-8", 200, "OK", corsHeaders, stream)
            }
            path == "/book/chapters.json" -> {
                val extracted = uiState.extractedBook ?: EbookContentExtractor.extractBook(
                    context = context,
                    filePathOrUri = uiState.bookFilePath,
                    formatHint = uiState.bookFormat
                )
                val jsonBytes = EbookContentExtractor.toChaptersJson(extracted).toByteArray(Charsets.UTF_8)
                WebResourceResponse(
                    "application/json",
                    "utf-8",
                    200,
                    "OK",
                    corsHeaders,
                    ByteArrayInputStream(jsonBytes)
                )
            }
            path.startsWith("/book/pdf-page/") && path.endsWith(".png") -> {
                val pageNumStr = path.removePrefix("/book/pdf-page/").removeSuffix(".png")
                val pageIndex = pageNumStr.toIntOrNull()
                val pngBytes = if (pageIndex != null) {
                    EbookContentExtractor.renderPdfPagePng(context, uiState.bookFilePath, pageIndex)
                } else {
                    null
                }
                if (pngBytes != null) {
                    WebResourceResponse(
                        "image/png",
                        null,
                        200,
                        "OK",
                        corsHeaders,
                        ByteArrayInputStream(pngBytes)
                    )
                } else {
                    notFoundResponse(corsHeaders)
                }
            }
            path == "/book/file" -> {
                val fileBytes = EbookContentExtractor.extractFileBytes(context, uiState.bookFilePath)
                if (fileBytes != null) {
                    val mime = when (uiState.bookFormat.uppercase()) {
                        "EPUB" -> "application/epub+zip"
                        "PDF" -> "application/pdf"
                        else -> "application/octet-stream"
                    }
                    WebResourceResponse(
                        mime,
                        null,
                        200,
                        "OK",
                        corsHeaders,
                        ByteArrayInputStream(fileBytes)
                    )
                } else {
                    notFoundResponse(corsHeaders)
                }
            }
            else -> notFoundResponse(corsHeaders)
        }
    } catch (_: Exception) {
        notFoundResponse(corsHeaders)
    }
}

private fun notFoundResponse(headers: Map<String, String>): WebResourceResponse {
    return WebResourceResponse(
        "text/plain",
        "utf-8",
        404,
        "Not Found",
        headers,
        ByteArrayInputStream(ByteArray(0))
    )
}

private fun guessMimeType(path: String): String {
    val lower = path.lowercase()
    return when {
        lower.endsWith(".js") || lower.endsWith(".mjs") -> "text/javascript"
        lower.endsWith(".css") -> "text/css"
        lower.endsWith(".html") || lower.endsWith(".htm") -> "text/html"
        lower.endsWith(".json") -> "application/json"
        lower.endsWith(".svg") -> "image/svg+xml"
        lower.endsWith(".png") -> "image/png"
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
        lower.endsWith(".gif") -> "image/gif"
        lower.endsWith(".webp") -> "image/webp"
        lower.endsWith(".woff") -> "font/woff"
        lower.endsWith(".woff2") -> "font/woff2"
        lower.endsWith(".wasm") -> "application/wasm"
        lower.endsWith(".xhtml") || lower.endsWith(".xml") -> "application/xhtml+xml"
        else -> "application/octet-stream"
    }
}
