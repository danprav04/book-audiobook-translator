package io.github.danprav04.polyglotbooks.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val chapters: List<ChapterEntity> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentCfi: String = "",
    val progress: Float = 0f,
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
    val highlightedCfi: String? = null
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    savedStateHandle: SavedStateHandle
) : ViewModel(), ReaderBridgeListener {

    private val bookId: String = savedStateHandle.get<String>("bookId") ?: "sample_book_id"

    private val _uiState = MutableStateFlow(ReaderUiState(bookId = bookId))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        loadBookAndChapters()
    }

    private fun loadBookAndChapters() {
        viewModelScope.launch {
            val book = bookDao.getBookById(bookId)
            if (book != null) {
                _uiState.update { it.copy(bookTitle = book.title) }
            }
            chapterDao.getChaptersForBook(bookId).collect { chapters ->
                _uiState.update { it.copy(chapters = chapters) }
            }
        }
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
        _uiState.update { it.copy(currentCfi = cfi, progress = progress) }
        viewModelScope.launch {
            val book = bookDao.getBookById(bookId)
            if (book != null) {
                bookDao.updateBook(book.copy(lastReadDate = System.currentTimeMillis()))
            }
        }
    }

    // ReaderBridgeListener callbacks
    override fun onParagraphsExtracted(chapterIndex: Int, paragraphsJson: String) {}

    override fun onPageTurned(cfi: String, fraction: Float, chapterTitle: String) {
        saveProgress(cfi, fraction)
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
