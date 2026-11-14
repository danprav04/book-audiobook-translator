package io.github.danprav04.polyglotbooks.feature.reader

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import io.github.danprav04.polyglotbooks.feature.settings.VoicePickerDialog
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
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

    // Sync explicit chapter/page changes (TOC, Next, Prev, narration chapter advance) to the WebView.
    LaunchedEffect(
        isWebViewPageFinished,
        uiState.isBookReady,
        uiState.chapterNavigationNonce,
        webViewInstance
    ) {
        val webView = webViewInstance
        if (webView != null && isWebViewPageFinished && uiState.isBookReady &&
            uiState.chapterNavigationNonce > 0 && lastSyncedChapterIndex != null
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

    // Sync active highlight CFI to WebView respecting followHighlight setting.
    LaunchedEffect(
        isWebViewPageFinished,
        uiState.isBookReady,
        uiState.highlightedCfi,
        uiState.followHighlight,
        webViewInstance
    ) {
        val webView = webViewInstance
        if (webView != null && isWebViewPageFinished) {
            val cfi = uiState.highlightedCfi
            if (!cfi.isNullOrBlank()) {
                webView.evaluateJavascript(
                    "window.FoliateBridge?.highlightCfi(${JSONObject.quote(cfi)}, ${uiState.followHighlight});",
                    null
                )
            } else {
                webView.evaluateJavascript("window.FoliateBridge?.clearHighlights();", null)
            }
        }
    }

    // Explicit "Jump to current" action requested by the user.
    LaunchedEffect(uiState.jumpToHighlightNonce, isWebViewPageFinished, uiState.isBookReady, webViewInstance) {
        val webView = webViewInstance
        if (webView != null && isWebViewPageFinished && uiState.jumpToHighlightNonce > 0) {
            webView.evaluateJavascript("window.FoliateBridge?.jumpToHighlight?.();", null)
        }
    }

    // Flush reading position on pause/stop; keep background narration running when navigating away!
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                val wv = webViewInstance
                if (wv != null) {
                    wv.evaluateJavascript("window.FoliateBridge?.flushPosition?.();") {
                        viewModel.flushProgress()
                    }
                } else {
                    viewModel.flushProgress()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webViewInstance?.evaluateJavascript("window.FoliateBridge?.flushPosition?.();", null)
            viewModel.stopVoicePreview()
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
                    IconButton(
                        onClick = {
                            viewModel.stopVoicePreview()
                            val wv = webViewInstance
                            if (wv != null) {
                                wv.evaluateJavascript("window.FoliateBridge?.flushPosition?.();") {
                                    viewModel.flushProgress()
                                    onBackClick()
                                }
                            } else {
                                viewModel.flushProgress()
                                onBackClick()
                            }
                        }
                    ) {
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
            NarrationControlDock(
                uiState = uiState,
                onToggleNarrateTranslated = { viewModel.setNarrateTranslatedText(!uiState.narrateTranslatedText) },
                onSelectEngine = viewModel::setNarrationEngine,
                onPrevParagraph = { viewModel.previousParagraph() },
                onTogglePlayPause = { viewModel.toggleNarration() },
                onNextParagraph = { viewModel.nextParagraph() },
                onSeekToMs = viewModel::seekToChapterPositionMs,
                onExpandSheet = { viewModel.setNarrationSheetExpanded(true) }
            )
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

            if (uiState.isBilingualEnabled && (uiState.isTranslating || uiState.isTranslationCached)) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (uiState.isTranslating) "Translating..." else "Cached",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
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
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        if (uiState.isBilingualEnabled || uiState.narrateTranslatedText) {
                            TextButton(onClick = { viewModel.retryTranslation() }) {
                                Text("Retry")
                            }
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
                        onTargetLanguageSelected = viewModel::setTargetLanguage,
                        onNarrateTranslatedSelected = viewModel::setNarrateTranslatedText
                    )
                }

                if (uiState.isNarrationSheetExpanded) {
                    ExpandedNarrationSheet(
                        uiState = uiState,
                        chapterSubtitle = currentChapterSubtitle,
                        onDismiss = { viewModel.setNarrationSheetExpanded(false) },
                        onPrevChapter = { viewModel.prevChapter() },
                        onNextChapter = { viewModel.nextChapter() },
                        onSeekByDelta = viewModel::seekByDeltaMs,
                        onSeekToChapterMs = viewModel::seekToChapterPositionMs,
                        onTogglePlayPause = { viewModel.toggleNarration() },
                        onStopNarration = {
                            viewModel.stopNarration()
                            viewModel.setNarrationSheetExpanded(false)
                        },
                        onSelectNarrateTranslated = viewModel::setNarrateTranslatedText,
                        onSelectEngine = viewModel::setNarrationEngine,
                        onSelectDefaultNarratorVoice = viewModel::selectDefaultNarratorVoice,
                        onToggleVoicePreview = viewModel::toggleVoicePreview,
                        onStopVoicePreview = viewModel::stopVoicePreview,
                        onSelectSpeed = viewModel::setPlaybackSpeed,
                        onSelectSleepTimer = viewModel::setSleepTimer,
                        onToggleFollowHighlight = viewModel::setFollowHighlight,
                        onJumpToCurrent = {
                            viewModel.jumpToActiveHighlight()
                            viewModel.setNarrationSheetExpanded(false)
                        },
                        onOpenCastEditor = {
                            viewModel.setNarrationSheetExpanded(false)
                            viewModel.setCastEditorOpen(true)
                        }
                    )
                }

                if (uiState.isCastEditorOpen) {
                    CastEditorDialog(
                        characters = uiState.characters,
                        defaultNarratorVoiceId = uiState.defaultNarratorVoiceId,
                        playingPreviewVoiceId = uiState.playingPreviewVoiceId,
                        previewErrorMessage = uiState.previewErrorMessage,
                        isAnalyzing = uiState.isCastAnalyzing,
                        onAnalyzeCast = { viewModel.analyzeCastForCurrentChapter() },
                        onUpdateVoice = { character, voiceId, style ->
                            viewModel.updateCharacterVoice(character, voiceId, style)
                        },
                        onTogglePreview = viewModel::toggleVoicePreview,
                        onStopPreview = viewModel::stopVoicePreview,
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
    onTargetLanguageSelected: (LanguageCode) -> Unit,
    onNarrateTranslatedSelected: (Boolean) -> Unit
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

                Text("Narration Speech Source", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !uiState.narrateTranslatedText,
                        onClick = { onNarrateTranslatedSelected(false) },
                        label = { Text("Original") }
                    )
                    FilterChip(
                        selected = uiState.narrateTranslatedText,
                        onClick = { onNarrateTranslatedSelected(true) },
                        label = { Text("Translated (${uiState.targetLanguage.code.uppercase()})") }
                    )
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
    defaultNarratorVoiceId: String,
    playingPreviewVoiceId: String?,
    previewErrorMessage: String?,
    isAnalyzing: Boolean,
    onAnalyzeCast: () -> Unit,
    onUpdateVoice: (CharacterEntity, String, String?) -> Unit,
    onTogglePreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDismiss: () -> Unit
) {
    DisposableEffect(Unit) {
        onDispose { onStopPreview() }
    }
    AlertDialog(
        onDismissRequest = {
            onStopPreview()
            onDismiss()
        },
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
                                defaultNarratorVoiceId = defaultNarratorVoiceId,
                                playingPreviewVoiceId = playingPreviewVoiceId,
                                previewErrorMessage = previewErrorMessage,
                                onUpdateVoice = onUpdateVoice,
                                onTogglePreview = onTogglePreview,
                                onStopPreview = onStopPreview
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onStopPreview()
                    onDismiss()
                }
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun CastCharacterRow(
    character: CharacterEntity,
    defaultNarratorVoiceId: String,
    playingPreviewVoiceId: String?,
    previewErrorMessage: String?,
    onUpdateVoice: (CharacterEntity, String, String?) -> Unit,
    onTogglePreview: (String) -> Unit,
    onStopPreview: () -> Unit
) {
    var showVoicePicker by remember { mutableStateOf(false) }
    var styleText by remember(character.id, character.voiceTraits) { mutableStateOf(character.voiceTraits) }
    val selectedVoice = character.assignedVoiceId?.takeIf { it.isNotBlank() } ?: defaultNarratorVoiceId
    val voiceProfile = remember(selectedVoice) { GeminiStudioVoices.getVoice(selectedVoice) }

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
                OutlinedButton(
                    onClick = { showVoicePicker = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = voiceProfile?.let { "${it.name} (${it.gender})" } ?: selectedVoice,
                        style = MaterialTheme.typography.labelMedium
                    )
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

    if (showVoicePicker) {
        VoicePickerDialog(
            selectedVoiceId = selectedVoice,
            playingVoiceId = playingPreviewVoiceId,
            previewErrorMessage = previewErrorMessage,
            onSelectVoice = { voiceId ->
                onStopPreview()
                showVoicePicker = false
                onUpdateVoice(character, voiceId, styleText)
            },
            onTogglePreview = onTogglePreview,
            onStopPreview = onStopPreview,
            onDismiss = {
                onStopPreview()
                showVoicePicker = false
            },
            title = "Voice for ${character.name}",
            subtitle = "Choose a Gemini Studio voice and tap play to preview"
        )
    }
}

@Composable
private fun NarrationControlDock(
    uiState: ReaderUiState,
    onToggleNarrateTranslated: () -> Unit,
    onSelectEngine: (NarrationEngine) -> Unit,
    onPrevParagraph: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNextParagraph: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onExpandSheet: () -> Unit
) {
    val totalChapters = maxOf(uiState.chapters.size, uiState.extractedBook?.chapters?.size ?: 0).coerceAtLeast(1)
    val totalParagraphs = uiState.paragraphs.size
    val activeParaDisplay = if (totalParagraphs > 0) (uiState.activeParagraphIndex + 1).coerceIn(1, totalParagraphs) else 0
    val chapterDurationMs = uiState.chapterDurationMs.coerceAtLeast(1L)

    var isDraggingSlider by remember(uiState.bookId, uiState.currentChapterIndex) { mutableStateOf(false) }
    var dragPosMs by remember(uiState.bookId, uiState.currentChapterIndex) { mutableStateOf(uiState.chapterPositionMs) }
    val displayedPosMs = if (isDraggingSlider) dragPosMs.coerceIn(0L, chapterDurationMs) else uiState.chapterPositionMs.coerceIn(0L, chapterDurationMs)

    var engineExpanded by remember { mutableStateOf(false) }

    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            if (uiState.chapterDurationMs > 0L) {
                Slider(
                    value = displayedPosMs.toFloat(),
                    onValueChange = { next ->
                        isDraggingSlider = true
                        dragPosMs = next.toLong()
                    },
                    onValueChangeFinished = {
                        val target = dragPosMs.coerceIn(0L, chapterDurationMs)
                        isDraggingSlider = false
                        onSeekToMs(target)
                    },
                    valueRange = 0f..chapterDurationMs.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (uiState.chapterDurationMs > 0L) {
                        "${formatNarrationTime(displayedPosMs)} / ${formatNarrationTime(chapterDurationMs)}"
                    } else {
                        "Ch ${uiState.currentChapterIndex + 1}/$totalChapters"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (totalParagraphs > 0) {
                        "Ch ${uiState.currentChapterIndex + 1}/$totalChapters · Para $activeParaDisplay/$totalParagraphs"
                    } else {
                        "Ch ${uiState.currentChapterIndex + 1}/$totalChapters"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val speedAndTimer = buildString {
                    append("${uiState.playbackSpeed}x")
                    if (uiState.stopAfterCurrentChapter) append(" · End Ch")
                    else if (uiState.sleepTimerMinutesRemaining > 0) append(" · ${uiState.sleepTimerMinutesRemaining}m")
                }
                Text(
                    text = speedAndTimer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Speech Source pill: Original vs Translated (<LANG>)
                FilterChip(
                    selected = uiState.narrateTranslatedText,
                    onClick = onToggleNarrateTranslated,
                    label = {
                        Text(
                            text = if (uiState.narrateTranslatedText) {
                                uiState.targetLanguage.code.uppercase().take(4)
                            } else {
                                "Orig"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                )

                // Provider pill + quota hints menu
                Box {
                    OutlinedButton(
                        onClick = { engineExpanded = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            text = narrationEngineShortLabel(uiState.narrationEngine),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                    DropdownMenu(
                        expanded = engineExpanded,
                        onDismissRequest = { engineExpanded = false }
                    ) {
                        NarrationEngine.values().forEach { engine ->
                            val (title, hint) = narrationEngineQuotaInfo(engine)
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(text = title, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = hint,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onSelectEngine(engine)
                                    engineExpanded = false
                                }
                            )
                        }
                    }
                }

                // Transport: Prev Para, Play/Pause, Next Para
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onPrevParagraph,
                        enabled = totalParagraphs > 0
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Paragraph")
                    }
                    SmallFloatingActionButton(
                        onClick = onTogglePlayPause,
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        if (uiState.isBuffering && uiState.isNarrationPlaying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                if (uiState.isNarrationPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (uiState.isNarrationPlaying) "Pause Narration" else "Play Narration"
                            )
                        }
                    }
                    IconButton(
                        onClick = onNextParagraph,
                        enabled = totalParagraphs > 0
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next Paragraph")
                    }
                }

                IconButton(onClick = onExpandSheet) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Expand Narration Player")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedNarrationSheet(
    uiState: ReaderUiState,
    chapterSubtitle: String,
    onDismiss: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekByDelta: (Long) -> Unit,
    onSeekToChapterMs: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onStopNarration: () -> Unit,
    onSelectNarrateTranslated: (Boolean) -> Unit,
    onSelectEngine: (NarrationEngine) -> Unit,
    onSelectDefaultNarratorVoice: (String) -> Unit,
    onToggleVoicePreview: (String) -> Unit,
    onStopVoicePreview: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectSleepTimer: (Int) -> Unit,
    onToggleFollowHighlight: (Boolean) -> Unit,
    onJumpToCurrent: () -> Unit,
    onOpenCastEditor: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var sleepMenuExpanded by remember { mutableStateOf(false) }
    var showNarratorVoicePicker by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { onStopVoicePreview() }
    }

    val coverBitmap = remember(uiState.bookCoverUri, uiState.bookId) {
        val uriFile = uiState.bookCoverUri?.let { File(it) }
        val defaultCover = if (uiState.bookId.isNotBlank()) File(context.filesDir, "covers/${uiState.bookId}.jpg") else null
        val path = when {
            uriFile != null && uriFile.exists() -> uriFile.absolutePath
            defaultCover != null && defaultCover.exists() -> defaultCover.absolutePath
            !uiState.bookCoverUri.isNullOrBlank() -> uiState.bookCoverUri
            else -> null
        }
        path?.let {
            try {
                BitmapFactory.decodeFile(it)
            } catch (_: Throwable) {
                null
            }
        }
    }

    val activeParagraph = uiState.paragraphs.getOrNull(uiState.activeParagraphIndex)
    val snippetText = remember(activeParagraph, uiState.narrateTranslatedText, uiState.bilingualTranslations) {
        if (activeParagraph == null) {
            "Press Play to start audiobook-grade narration."
        } else if (uiState.narrateTranslatedText) {
            uiState.bilingualTranslations[activeParagraph.cfi]?.takeIf { it.isNotBlank() } ?: activeParagraph.text
        } else {
            activeParagraph.text
        }
    }

    val totalChapters = maxOf(uiState.chapters.size, uiState.extractedBook?.chapters?.size ?: 0).coerceAtLeast(1)
    val chapterDurationMs = uiState.chapterDurationMs.coerceAtLeast(1L)
    var isDraggingSlider by remember(uiState.bookId, uiState.currentChapterIndex) { mutableStateOf(false) }
    var dragPosMs by remember(uiState.bookId, uiState.currentChapterIndex) { mutableStateOf(uiState.chapterPositionMs) }
    val displayedChapterPosMs = if (isDraggingSlider) dragPosMs.coerceIn(0L, chapterDurationMs) else uiState.chapterPositionMs.coerceIn(0L, chapterDurationMs)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Cover/Icon + Book Title + Chapter Subtitle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(64.dp)
                ) {
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap.asImageBitmap(),
                            contentDescription = uiState.bookTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.bookTitle.ifBlank { "Reader Narration" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (uiState.bookAuthor.isNotBlank()) {
                        Text(
                            text = uiState.bookAuthor,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = chapterSubtitle.ifBlank { "Chapter ${uiState.currentChapterIndex + 1} / $totalChapters" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onStopNarration) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop Background Narration")
                }
            }

            // Active paragraph snippet preview
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (uiState.paragraphs.isNotEmpty()) {
                            "Paragraph ${uiState.activeParagraphIndex + 1} of ${uiState.paragraphs.size}"
                        } else {
                            "Current Segment"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = snippetText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Chapter Timeline Slider & Book Progress
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = displayedChapterPosMs.toFloat(),
                    onValueChange = { next ->
                        isDraggingSlider = true
                        dragPosMs = next.toLong()
                    },
                    onValueChangeFinished = {
                        val target = dragPosMs.coerceIn(0L, chapterDurationMs)
                        isDraggingSlider = false
                        onSeekToChapterMs(target)
                    },
                    valueRange = 0f..chapterDurationMs.toFloat()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatNarrationTime(displayedChapterPosMs), style = MaterialTheme.typography.labelSmall)
                    Text(formatNarrationTime(chapterDurationMs), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(modifier = Modifier.height(6.dp))
                val bookDurationMs = uiState.bookDurationMs.coerceAtLeast(1L)
                val bookFraction = if (uiState.bookDurationMs > 0L) {
                    (uiState.bookPositionMs.toFloat() / bookDurationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    uiState.progress.coerceIn(0f, 1f)
                }
                LinearProgressIndicator(
                    progress = { bookFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (uiState.bookDurationMs > 0L) {
                            "Book: ${formatNarrationTime(uiState.bookPositionMs)} / ${formatNarrationTime(uiState.bookDurationMs)}"
                        } else {
                            "Book Progress"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(bookFraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 5-button Transport Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPrevChapter,
                    enabled = uiState.currentChapterIndex > 0
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Chapter")
                }
                IconButton(onClick = { onSeekByDelta(-15_000L) }) {
                    Icon(Icons.Default.FastRewind, contentDescription = "Rewind 15s / Previous Paragraph")
                }
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(60.dp)
                ) {
                    if (uiState.isBuffering && uiState.isNarrationPlaying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (uiState.isNarrationPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isNarrationPlaying) "Pause" else "Play",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                IconButton(onClick = { onSeekByDelta(15_000L) }) {
                    Icon(Icons.Default.FastForward, contentDescription = "Forward 15s / Next Paragraph")
                }
                IconButton(
                    onClick = onNextChapter,
                    enabled = uiState.currentChapterIndex < totalChapters - 1
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next Chapter")
                }
            }

            // Speech Source Selector: Original Text vs Translated (<LANG>)
            Text("Speech Source", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !uiState.narrateTranslatedText,
                    onClick = { onSelectNarrateTranslated(false) },
                    label = { Text("Original Text") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = uiState.narrateTranslatedText,
                    onClick = { onSelectNarrateTranslated(true) },
                    label = { Text("Translated (${uiState.targetLanguage.code.uppercase()})") },
                    modifier = Modifier.weight(1f)
                )
            }

            // Provider Chips + Quota Hint
            Text("Voice Provider", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NarrationEngine.values().forEach { engine ->
                    FilterChip(
                        selected = uiState.narrationEngine == engine,
                        onClick = { onSelectEngine(engine) },
                        label = {
                            Text(
                                text = narrationEngineShortLabel(engine),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            val (_, activeHint) = narrationEngineQuotaInfo(uiState.narrationEngine)
            Text(
                text = activeHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Default Narrator Voice Selector
            val activeNarratorVoice = remember(uiState.defaultNarratorVoiceId) {
                GeminiStudioVoices.getVoice(uiState.defaultNarratorVoiceId)
            }
            OutlinedCard(
                onClick = { showNarratorVoicePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Default Narrator Voice",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = activeNarratorVoice?.let {
                                "${it.name} · ${it.gender} · ${it.traits.joinToString(", ")}"
                            } ?: uiState.defaultNarratorVoiceId,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "Change",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Comfort Controls Row: Speed, Sleep Timer, Follow Highlight, Jump to current, Cast Editor
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    OutlinedButton(onClick = { speedMenuExpanded = true }) {
                        Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("${uiState.playbackSpeed}x")
                    }
                    DropdownMenu(
                        expanded = speedMenuExpanded,
                        onDismissRequest = { speedMenuExpanded = false }
                    ) {
                        listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { spd ->
                            DropdownMenuItem(
                                text = { Text("${spd}x") },
                                onClick = {
                                    onSelectSpeed(spd)
                                    speedMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Box {
                    OutlinedButton(onClick = { sleepMenuExpanded = true }) {
                        Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            when {
                                uiState.stopAfterCurrentChapter -> "End Ch"
                                uiState.sleepTimerMinutesRemaining > 0 -> "${uiState.sleepTimerMinutesRemaining}m"
                                else -> "Sleep"
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = sleepMenuExpanded,
                        onDismissRequest = { sleepMenuExpanded = false }
                    ) {
                        listOf(0, 10, 15, 30, 45, 60, -1).forEach { mins ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (mins) {
                                            0 -> "Off"
                                            -1 -> "End of Chapter"
                                            else -> "$mins minutes"
                                        }
                                    )
                                },
                                onClick = {
                                    onSelectSleepTimer(mins)
                                    sleepMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                IconButton(onClick = onOpenCastEditor) {
                    Icon(Icons.Default.Group, contentDescription = "Cast Editor")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = uiState.followHighlight,
                    onClick = { onToggleFollowHighlight(!uiState.followHighlight) },
                    label = { Text("Follow highlight") }
                )
                TextButton(
                    onClick = onJumpToCurrent,
                    enabled = !uiState.highlightedCfi.isNullOrBlank()
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Jump to current")
                }
            }
        }
    }

    if (showNarratorVoicePicker) {
        VoicePickerDialog(
            selectedVoiceId = uiState.defaultNarratorVoiceId,
            playingVoiceId = uiState.playingPreviewVoiceId,
            previewErrorMessage = uiState.previewErrorMessage,
            onSelectVoice = { voiceId ->
                onStopVoicePreview()
                onSelectDefaultNarratorVoice(voiceId)
                showNarratorVoicePicker = false
            },
            onTogglePreview = onToggleVoicePreview,
            onStopPreview = onStopVoicePreview,
            onDismiss = {
                onStopVoicePreview()
                showNarratorVoicePicker = false
            },
            title = "Default Narrator Voice",
            subtitle = "Used for narration unless overridden per character"
        )
    }
}

private fun narrationEngineShortLabel(engine: NarrationEngine): String {
    return when (engine) {
        NarrationEngine.GEMINI_FLASH_TTS -> "Gemini TTS"
        NarrationEngine.GEMINI_LIVE -> "Gemini Live"
        NarrationEngine.SYSTEM_TTS -> "System TTS"
    }
}

private fun narrationEngineQuotaInfo(engine: NarrationEngine): Pair<String, String> {
    return when (engine) {
        NarrationEngine.GEMINI_FLASH_TTS ->
            "Gemini Flash TTS" to "3 RPM · 10 RPD (batched & cached)"
        NarrationEngine.GEMINI_LIVE ->
            "Gemini Live" to "Unlimited RPM/RPD (recommended for free tier)"
        NarrationEngine.SYSTEM_TTS ->
            "System TTS" to "Offline · Unlimited"
    }
}

private fun formatNarrationTime(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1000L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
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
