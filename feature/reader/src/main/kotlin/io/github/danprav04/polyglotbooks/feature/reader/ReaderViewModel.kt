package io.github.danprav04.polyglotbooks.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateDao
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import javax.inject.Inject

enum class ReaderTheme { LIGHT, SEPIA, DARK }
enum class NarrationEngine { GEMINI_FLASH_TTS, GEMINI_LIVE, SYSTEM_TTS }

data class SearchResultItem(
    val cfi: String,
    val excerpt: String
)

data class ReaderUiState(
    val bookId: String = "",
    val bookTitle: String = "Reader",
    val bookFilePath: String = "",
    val chapters: List<ChapterEntity> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentCfi: String = "",
    val progress: Float = 0f,
    val isStateRestored: Boolean = false,
    val isBookReady: Boolean = false,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontSizeSp: Int = 18,
    val fontFamily: String = "Sans-Serif",
    val isTocOpen: Boolean = false,
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchResultItem> = emptyList(),
    val isNarrationPlaying: Boolean = false,
    val narrationEngine: NarrationEngine = NarrationEngine.GEMINI_FLASH_TTS,
    val isCastEditorOpen: Boolean = false,
    val isBilingualEnabled: Boolean = false,
    val targetLanguage: String = "Spanish",
    val bilingualTranslations: Map<String, String> = emptyMap(),
    val isReportDialogOpen: Boolean = false,
    val highlightedCfi: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    savedStateHandle: SavedStateHandle,
    private val playbackStateDao: PlaybackStateDao? = null,
    private val settingsRepository: SettingsRepository? = null
) : ViewModel(), ReaderBridgeListener {

    private var bookId: String = savedStateHandle.get<String>("bookId") ?: "sample_book_id"
    private var loadJob: Job? = null
    private val saveMutex = Mutex()

    private val _uiState = MutableStateFlow(ReaderUiState(bookId = bookId))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        loadBookAndChapters(bookId)
    }

    fun loadBookAndChapters(targetBookId: String = bookId) {
        if (targetBookId.isBlank()) return
        val switchingBook = _uiState.value.bookId != targetBookId
        bookId = targetBookId
        loadJob?.cancel()
        _uiState.update { state ->
            if (switchingBook) {
                state.copy(
                    bookId = targetBookId,
                    bookTitle = "Reader",
                    bookFilePath = "",
                    chapters = emptyList(),
                    currentChapterIndex = 0,
                    currentCfi = "",
                    progress = 0f,
                    isStateRestored = false,
                    isBookReady = false,
                    errorMessage = null
                )
            } else {
                state.copy(isStateRestored = false)
            }
        }

        loadJob = viewModelScope.launch {
            try {
                val book = bookDao.getBookById(targetBookId)
                if (book != null) {
                    val savedState = playbackStateDao?.getPlaybackState(targetBookId)
                    val restoredCfi = savedState?.lastCfi.orEmpty()
                    val restoredProgress = (savedState?.readingProgress ?: 0f).coerceIn(0f, 1f)
                    val existingChapters = _uiState.value.chapters
                    val restoredChapterIndex = resolveChapterIndex(
                        chapters = existingChapters,
                        cfi = restoredCfi,
                        progress = restoredProgress,
                        fallbackIndex = savedState?.chapterIndex ?: 0
                    )
                    _uiState.update {
                        it.copy(
                            bookId = targetBookId,
                            bookTitle = book.title,
                            bookFilePath = book.filePath,
                            currentCfi = restoredCfi,
                            progress = restoredProgress,
                            currentChapterIndex = restoredChapterIndex,
                            isStateRestored = true,
                            errorMessage = null
                        )
                    }
                    withContext(NonCancellable) {
                        settingsRepository?.setLastOpenedBookId(targetBookId)
                        bookDao.updateLastReadDate(targetBookId, System.currentTimeMillis())
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            bookId = targetBookId,
                            isStateRestored = true,
                            errorMessage = "Book not found or unreadable: $targetBookId"
                        )
                    }
                }
                chapterDao.getChaptersForBook(targetBookId).collect { chapters ->
                    _uiState.update { state ->
                        val resolvedChapter = resolveChapterIndex(
                            chapters = chapters,
                            cfi = state.currentCfi,
                            progress = state.progress,
                            fallbackIndex = state.currentChapterIndex
                        )
                        state.copy(
                            chapters = chapters,
                            currentChapterIndex = resolvedChapter
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isStateRestored = true,
                        errorMessage = e.message ?: "Failed to load book: $targetBookId"
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun setTheme(theme: ReaderTheme) {
        _uiState.update { it.copy(theme = theme) }
    }

    fun setFontSize(size: Int) {
        val clamped = size.coerceIn(12, 32)
        _uiState.update { it.copy(fontSizeSp = clamped) }
    }

    fun setFontFamily(family: String) {
        _uiState.update { it.copy(fontFamily = family) }
    }

    fun setTocOpen(open: Boolean) {
        _uiState.update { it.copy(isTocOpen = open) }
    }

    fun setSearchOpen(open: Boolean) {
        _uiState.update { it.copy(isSearchOpen = open, searchQuery = if (!open) "" else it.searchQuery, searchResults = if (!open) emptyList() else it.searchResults) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleNarration() {
        _uiState.update { it.copy(isNarrationPlaying = !it.isNarrationPlaying) }
    }

    fun setNarrationEngine(engine: NarrationEngine) {
        _uiState.update { it.copy(narrationEngine = engine) }
    }

    fun setCastEditorOpen(open: Boolean) {
        _uiState.update { it.copy(isCastEditorOpen = open) }
    }

    fun toggleBilingualMode() {
        val newState = !_uiState.value.isBilingualEnabled
        _uiState.update { it.copy(isBilingualEnabled = newState) }
        if (newState) {
            val sampleTranslations = mapOf(
                "epubcfi(/6/1!/4/2/1:0)" to "Bienvenido a Polyglot Books. Este es un lector de libros electrónicos inmersivo...",
                "epubcfi(/6/1!/4/2/1:1)" to "Con motores avanzados de narración por IA como Gemini Flash TTS...",
                "epubcfi(/6/1!/4/2/1:2)" to "Cambie entre temas Claro, Sepia y Oscuro, ajuste tamaños de fuente..."
            )
            setBilingualTranslations(sampleTranslations)
        } else {
            setBilingualTranslations(emptyMap())
        }
    }

    fun setTargetLanguage(lang: String) {
        _uiState.update { it.copy(targetLanguage = lang) }
    }

    fun setBilingualTranslations(translations: Map<String, String>) {
        _uiState.update { it.copy(bilingualTranslations = translations) }
    }

    fun setReportDialogOpen(open: Boolean) {
        _uiState.update { it.copy(isReportDialogOpen = open) }
    }

    fun highlightCfi(cfi: String) {
        _uiState.update { it.copy(highlightedCfi = cfi) }
    }

    fun saveProgress(cfi: String, progress: Float) {
        saveProgressInternal(cfi, progress, chapterTitle = "")
    }

    /**
     * Flushes the currently restored/active reading position to durable storage.
     * Skips writes if initial state has not finished loading or no position is set yet.
     */
    fun flushProgress() {
        val state = _uiState.value
        if (!state.isStateRestored || state.bookId.isBlank()) return
        if (state.currentCfi.isBlank() && state.progress <= 0f) return
        persistProgress(state.bookId, state.currentCfi, state.progress)
    }

    fun resetBookReady() {
        _uiState.update { it.copy(isBookReady = false) }
    }

    private fun saveProgressInternal(cfi: String, progress: Float, chapterTitle: String) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val targetBookId = bookId
        _uiState.update { state ->
            val chapterIndex = resolveChapterIndex(
                chapters = state.chapters,
                cfi = cfi,
                progress = clampedProgress,
                chapterTitle = chapterTitle,
                fallbackIndex = state.currentChapterIndex
            )
            state.copy(
                currentCfi = cfi,
                progress = clampedProgress,
                currentChapterIndex = chapterIndex
            )
        }
        if (targetBookId.isNotBlank()) {
            persistProgress(targetBookId, cfi, clampedProgress)
        }
    }

    private fun persistProgress(targetBookId: String, cfi: String, progress: Float) {
        // Detach parent Job via NonCancellable so abrupt UI/ViewModel teardown does not abort SQLite writes.
        viewModelScope.launch(NonCancellable) {
            try {
                saveMutex.withLock {
                    val latestState = _uiState.value
                    val cfiToSave = if (latestState.bookId == targetBookId) latestState.currentCfi else cfi
                    val progressToSave = if (latestState.bookId == targetBookId) latestState.progress else progress
                    val now = System.currentTimeMillis()
                    playbackStateDao?.saveReaderProgress(
                        bookId = targetBookId,
                        lastCfi = cfiToSave.ifBlank { null },
                        readingProgress = progressToSave,
                        updatedAt = now
                    )
                    bookDao.updateLastReadDate(targetBookId, now)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private fun resolveChapterIndex(
        chapters: List<ChapterEntity>,
        cfi: String,
        progress: Float,
        chapterTitle: String = "",
        fallbackIndex: Int = 0
    ): Int {
        if (chapters.isEmpty()) return fallbackIndex.coerceAtLeast(0)
        if (cfi.isNotBlank()) {
            val exactIndex = chapters.indexOfFirst { !it.cfiRange.isNullOrBlank() && it.cfiRange == cfi }
            if (exactIndex >= 0) return exactIndex

            val prefixMatch = chapters.mapIndexedNotNull { index, chapter ->
                val range = chapter.cfiRange?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val base = range.removeSuffix(")")
                if (cfi.startsWith("$base!") || cfi.startsWith("$base/")) {
                    index to range.length
                } else {
                    null
                }
            }.maxByOrNull { it.second }
            if (prefixMatch != null) return prefixMatch.first
        }
        if (chapterTitle.isNotBlank()) {
            val titleIndex = chapters.indexOfFirst { it.title.equals(chapterTitle.trim(), ignoreCase = true) }
            if (titleIndex >= 0) return titleIndex
        }
        if (progress > 0f) {
            return (progress * chapters.size).toInt().coerceIn(0, chapters.size - 1)
        }
        return fallbackIndex.coerceIn(0, chapters.size - 1)
    }

    override fun onCleared() {
        flushProgress()
        super.onCleared()
    }

    // ReaderBridgeListener callbacks
    override fun onParagraphsExtracted(chapterIndex: Int, paragraphsJson: String) {}

    override fun onPageTurned(cfi: String, fraction: Float, chapterTitle: String) {
        saveProgressInternal(cfi, fraction, chapterTitle)
    }

    override fun onBookReady() {
        _uiState.update { it.copy(isBookReady = true) }
    }

    override fun onSelectionChanged(cfiRange: String, selectedText: String) {}

    override fun onSearchResults(resultsJson: String) {
        val objectRegex = Regex("""\{[^{}]*\}""")
        val cfiRegex = Regex(""""cfi"\s*:\s*"([^"]*)"""")
        val excerptRegex = Regex(""""excerpt"\s*:\s*"([^"]*)"""")
        val list = objectRegex.findAll(resultsJson).mapNotNull { match ->
            val obj = match.value
            val cfi = cfiRegex.find(obj)?.groupValues?.get(1) ?: return@mapNotNull null
            val excerpt = excerptRegex.find(obj)?.groupValues?.get(1) ?: ""
            SearchResultItem(cfi = cfi, excerpt = excerpt)
        }.toList()
        _uiState.update { it.copy(searchResults = list) }
    }
}
