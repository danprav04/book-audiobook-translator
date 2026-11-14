package io.github.danprav04.polyglotbooks.feature.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danprav04.polyglotbooks.core.audio.PcmPlayer
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.db.CharacterDao
import io.github.danprav04.polyglotbooks.core.data.db.CharacterEntity
import io.github.danprav04.polyglotbooks.core.data.db.NarrationDao
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateDao
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.InteractionsTtsClient
import io.github.danprav04.polyglotbooks.core.gemini.LiveSessionFactory
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalogService
import io.github.danprav04.polyglotbooks.core.gemini.TextModelClient
import io.github.danprav04.polyglotbooks.core.model.Character
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.feature.narration.CastingService
import io.github.danprav04.polyglotbooks.feature.narration.GeminiStudioVoices
import io.github.danprav04.polyglotbooks.feature.narration.VoiceCaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import io.github.danprav04.polyglotbooks.feature.narration.NarrationEngine as FeatureNarrationEngine

enum class ReaderTheme { LIGHT, SEPIA, DARK }
enum class NarrationEngine { GEMINI_FLASH_TTS, GEMINI_LIVE, SYSTEM_TTS }

data class SearchResultItem(
    val cfi: String,
    val excerpt: String
)

data class ReaderParagraph(
    val index: Int,
    val id: String,
    val text: String,
    val cfi: String
)

data class SpeechChunk(
    val text: String,
    val leadCfi: String,
    val allCfis: List<String>,
    val leadIndex: Int,
    val voiceId: String,
    val style: String?,
    val paragraphIndices: List<Int> = emptyList(),
    val paragraphCharWeights: List<Int> = emptyList(),
    val languageCode: String? = null
)

data class ReaderUiState(
    val bookId: String = "",
    val bookTitle: String = "Reader",
    val bookAuthor: String = "",
    val bookCoverUri: String? = null,
    val bookFormat: String = "EPUB",
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
    val isBuffering: Boolean = false,
    val narrationEngine: NarrationEngine = NarrationEngine.GEMINI_FLASH_TTS,
    val narrateTranslatedText: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerMinutesRemaining: Int = 0,
    val stopAfterCurrentChapter: Boolean = false,
    val chapterPositionMs: Long = 0L,
    val chapterDurationMs: Long = 0L,
    val bookPositionMs: Long = 0L,
    val bookDurationMs: Long = 0L,
    val followHighlight: Boolean = true,
    val isNarrationSheetExpanded: Boolean = false,
    val jumpToHighlightNonce: Int = 0,
    val isCastEditorOpen: Boolean = false,
    val isBilingualEnabled: Boolean = false,
    val targetLanguage: LanguageCode = LanguageCode.SPANISH,
    val bilingualTranslations: Map<String, String> = emptyMap(),
    val isReportDialogOpen: Boolean = false,
    val highlightedCfi: String? = null,
    val errorMessage: String? = null,
    val characters: List<CharacterEntity> = emptyList(),
    val isCastAnalyzing: Boolean = false,
    val isTranslating: Boolean = false,
    val isTranslationCached: Boolean = false,
    val extractedBook: EbookContentExtractor.ExtractedBook? = null,
    val activeParagraphIndex: Int = 0,
    val paragraphs: List<ReaderParagraph> = emptyList(),
    val narrationStatusMessage: String? = null
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    savedStateHandle: SavedStateHandle,
    private val playbackStateDao: PlaybackStateDao? = null,
    private val settingsRepository: SettingsRepository? = null,
    @ApplicationContext private val appContext: Context? = null,
    private val characterDao: CharacterDao? = null,
    private val narrationDao: NarrationDao? = null,
    private val secureKeyStore: SecureKeyStore? = null,
    private val interactionsTtsClient: InteractionsTtsClient? = null,
    private val textModelClient: TextModelClient? = null,
    private val liveSessionFactory: LiveSessionFactory? = null,
    private val quotaTracker: GeminiQuotaTracker? = null,
    private val modelCatalogService: ModelCatalogService? = null,
    private val injectedSessionManager: ReaderNarrationSessionManager? = null
) : ViewModel(), ReaderBridgeListener {

    private var bookId: String = savedStateHandle.get<String>("bookId") ?: "sample_book_id"
    private var loadJob: Job? = null
    private var charactersJob: Job? = null
    private var settingsJob: Job? = null
    private var sessionObserverJob: Job? = null
    private var castJob: Job? = null
    private val saveMutex = Mutex()

    private val localFallbackSessionManager: ReaderNarrationSessionManager by lazy {
        ReaderNarrationSessionManager(
            bookDao = bookDao,
            chapterDao = chapterDao,
            playbackStateDao = playbackStateDao,
            characterDao = characterDao,
            settingsRepository = settingsRepository,
            secureKeyStore = secureKeyStore,
            interactionsTtsClient = interactionsTtsClient,
            textModelClient = textModelClient,
            liveSessionFactory = liveSessionFactory,
            quotaTracker = quotaTracker,
            modelCatalogService = modelCatalogService,
            playbackCoordinator = null,
            applicationScope = viewModelScope
        )
    }

    internal val sessionManager: ReaderNarrationSessionManager
        get() = injectedSessionManager ?: localFallbackSessionManager

    internal var testDiskCache: TranslationDiskCache?
        get() = sessionManager.testDiskCache
        set(value) {
            sessionManager.testDiskCache = value
        }

    private val _uiState = MutableStateFlow(ReaderUiState(bookId = bookId))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
        observeSessionState()
        loadBookAndChapters(bookId)
    }

    private fun observeSettings() {
        settingsJob?.cancel()
        val repo = settingsRepository ?: return
        settingsJob = viewModelScope.launch {
            launch {
                repo.targetLanguage.collect { lang ->
                    _uiState.update { it.copy(targetLanguage = lang) }
                }
            }
            launch {
                repo.readerNarrationEngine.collect { engineName ->
                    val parsed = parseNarrationEngine(engineName)
                    _uiState.update { it.copy(narrationEngine = parsed) }
                }
            }
            launch {
                repo.readerReadTranslated.collect { readTranslated ->
                    _uiState.update { it.copy(narrateTranslatedText = readTranslated) }
                }
            }
            launch {
                repo.readerNarrationSpeed.collect { speed ->
                    _uiState.update { it.copy(playbackSpeed = speed.coerceIn(0.5f, 2.5f)) }
                }
            }
            launch {
                repo.readerTheme.collect { themeName ->
                    val parsed = ReaderTheme.values().firstOrNull {
                        it.name.equals(themeName, ignoreCase = true)
                    } ?: ReaderTheme.LIGHT
                    _uiState.update { it.copy(theme = parsed) }
                }
            }
            launch {
                repo.readerFontSizeSp.collect { sizeSp ->
                    _uiState.update { it.copy(fontSizeSp = sizeSp.coerceIn(12, 32)) }
                }
            }
            launch {
                repo.readerFontFamily.collect { family ->
                    if (family.isNotBlank()) {
                        _uiState.update { it.copy(fontFamily = family) }
                    }
                }
            }
        }
    }

    private fun observeSessionState() {
        sessionObserverJob?.cancel()
        sessionObserverJob = viewModelScope.launch {
            sessionManager.sessionState.collect { session ->
                _uiState.update { state ->
                    val base = state.copy(
                        narrationEngine = session.narrationEngine,
                        narrateTranslatedText = session.narrateTranslatedText,
                        playbackSpeed = session.playbackSpeed,
                        targetLanguage = session.targetLanguage
                    )
                    if (session.bookId != bookId || bookId.isBlank()) {
                        base.copy(isNarrationPlaying = false, isBuffering = false)
                    } else {
                        val totalChapters = maxOf(
                            base.chapters.size,
                            base.extractedBook?.chapters?.size ?: 0,
                            session.chapters.size
                        )
                        val updatedChapterIndex = if (session.isActiveSession && totalChapters > 0) {
                            session.currentChapterIndex.coerceIn(0, totalChapters - 1)
                        } else {
                            base.currentChapterIndex
                        }
                        val syncedParagraphs = if (session.isActiveSession && session.paragraphs.isNotEmpty()) {
                            session.paragraphs
                        } else {
                            base.paragraphs
                        }
                        val syncedTranslations = if (
                            session.bilingualTranslations.isNotEmpty() ||
                            (!base.isBilingualEnabled && !session.isBilingualEnabled)
                        ) {
                            session.bilingualTranslations
                        } else {
                            base.bilingualTranslations
                        }
                        base.copy(
                            currentChapterIndex = updatedChapterIndex,
                            paragraphs = syncedParagraphs,
                            activeParagraphIndex = if (session.isActiveSession) {
                                session.activeParagraphIndex
                            } else {
                                base.activeParagraphIndex
                            },
                            highlightedCfi = if (session.isActiveSession) session.highlightedCfi else base.highlightedCfi,
                            isNarrationPlaying = session.isPlaying,
                            isBuffering = session.isBuffering,
                            isTranslating = session.isTranslating,
                            isTranslationCached = session.isTranslationCached,
                            isBilingualEnabled = base.isBilingualEnabled || session.isBilingualEnabled,
                            bilingualTranslations = syncedTranslations,
                            sleepTimerMinutesRemaining = session.sleepTimerMinutesRemaining,
                            stopAfterCurrentChapter = session.stopAfterCurrentChapter,
                            chapterPositionMs = session.chapterPositionMs,
                            chapterDurationMs = session.chapterDurationMs,
                            bookPositionMs = session.bookPositionMs,
                            bookDurationMs = session.bookDurationMs,
                            narrationStatusMessage = session.statusMessage ?: base.narrationStatusMessage,
                            errorMessage = session.errorMessage ?: base.errorMessage
                        )
                    }
                }
            }
        }
    }

    fun loadBookAndChapters(targetBookId: String = bookId) {
        if (targetBookId.isBlank()) return
        val switchingBook = _uiState.value.bookId != targetBookId
        bookId = targetBookId
        loadJob?.cancel()
        charactersJob?.cancel()
        if (switchingBook) {
            castJob?.cancel()
        }

        val existingSession = sessionManager.sessionState.value
        val activeForTarget = sessionManager.isSessionActiveFor(targetBookId)

        _uiState.update { state ->
            if (switchingBook) {
                state.copy(
                    bookId = targetBookId,
                    bookTitle = if (activeForTarget) existingSession.bookTitle.ifBlank { "Reader" } else "Reader",
                    bookAuthor = if (activeForTarget) existingSession.author else "",
                    bookCoverUri = if (activeForTarget) existingSession.coverPath else null,
                    bookFormat = if (activeForTarget) existingSession.bookFormat else "EPUB",
                    bookFilePath = if (activeForTarget) existingSession.bookFilePath else "",
                    chapters = if (activeForTarget) existingSession.chapters else emptyList(),
                    currentChapterIndex = if (activeForTarget) existingSession.currentChapterIndex else 0,
                    currentCfi = if (activeForTarget) existingSession.highlightedCfi.orEmpty() else "",
                    progress = 0f,
                    isStateRestored = false,
                    isBookReady = false,
                    characters = if (activeForTarget) existingSession.characters else emptyList(),
                    extractedBook = if (activeForTarget) existingSession.extractedBook else null,
                    activeParagraphIndex = if (activeForTarget) existingSession.activeParagraphIndex else 0,
                    paragraphs = if (activeForTarget) existingSession.paragraphs else emptyList(),
                    highlightedCfi = if (activeForTarget) existingSession.highlightedCfi else null,
                    isNarrationPlaying = activeForTarget && existingSession.isPlaying,
                    bilingualTranslations = if (activeForTarget) existingSession.bilingualTranslations else emptyMap(),
                    isBilingualEnabled = activeForTarget && (existingSession.isBilingualEnabled || existingSession.narrateTranslatedText),
                    errorMessage = null,
                    narrationStatusMessage = if (activeForTarget) existingSession.statusMessage else null
                )
            } else {
                state.copy(isStateRestored = false)
            }
        }

        characterDao?.let { dao ->
            charactersJob = viewModelScope.launch {
                dao.getCharactersForBook(targetBookId).collect { chars ->
                    _uiState.update { it.copy(characters = chars) }
                    if (sessionManager.sessionState.value.bookId == targetBookId) {
                        syncContextToSession(forceWhenDifferentBook = false)
                    }
                }
            }
        }

        loadJob = viewModelScope.launch {
            try {
                val book = bookDao.getBookById(targetBookId)
                if (book != null) {
                    val savedState = playbackStateDao?.getPlaybackState(targetBookId)
                    val isReattaching = sessionManager.isSessionActiveFor(targetBookId)
                    val liveSessionSnapshot = sessionManager.sessionState.value

                    val restoredCfi = if (isReattaching && !liveSessionSnapshot.highlightedCfi.isNullOrBlank()) {
                        liveSessionSnapshot.highlightedCfi
                    } else {
                        savedState?.lastCfi.orEmpty()
                    }
                    val restoredProgress = (savedState?.readingProgress ?: 0f).coerceIn(0f, 1f)
                    val existingChapters = _uiState.value.chapters
                    val restoredChapterIndex = if (isReattaching) {
                        liveSessionSnapshot.currentChapterIndex
                    } else {
                        resolveChapterIndex(
                            chapters = existingChapters,
                            cfi = restoredCfi,
                            progress = restoredProgress,
                            fallbackIndex = savedState?.chapterIndex ?: 0
                        )
                    }

                    _uiState.update {
                        it.copy(
                            bookId = targetBookId,
                            bookTitle = book.title,
                            bookAuthor = book.author,
                            bookCoverUri = book.coverUri,
                            bookFormat = book.format,
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

                    if (appContext != null && book.filePath.isNotBlank()) {
                        val extracted = if (isReattaching && liveSessionSnapshot.extractedBook != null) {
                            liveSessionSnapshot.extractedBook
                        } else {
                            try {
                                withContext(Dispatchers.IO) {
                                    EbookContentExtractor.extractBook(appContext, book.filePath, book.format)
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                null
                            }
                        }

                        if (extracted != null) {
                            val isPdf = extracted.format.equals("PDF", ignoreCase = true) ||
                                book.format.equals("PDF", ignoreCase = true)
                            val dbChapters = chapterDao.getChaptersForBook(targetBookId).firstOrNull().orEmpty()
                            if (dbChapters.isEmpty() && extracted.chapters.isNotEmpty()) {
                                val chapterEntities = extracted.chapters.map { ch ->
                                    ChapterEntity(
                                        id = "${targetBookId}_chap_${ch.index}",
                                        bookId = targetBookId,
                                        index = ch.index,
                                        title = ch.title.ifBlank {
                                            if (isPdf) "Page ${ch.index + 1}" else "Chapter ${ch.index + 1}"
                                        },
                                        startMs = ch.index * 1000L,
                                        endMs = (ch.index + 1) * 1000L,
                                        cfiRange = sessionManager.defaultChapterCfi(ch.index, isPdf),
                                        textLength = ch.paragraphs.sumOf { it.length }
                                    )
                                }
                                chapterDao.insertChapters(chapterEntities)
                            }
                            _uiState.update { state ->
                                val idx = state.currentChapterIndex.coerceIn(0, maxOf(0, extracted.chapters.lastIndex))
                                val initialParagraphs = if (state.paragraphs.isEmpty()) {
                                    sessionManager.buildParagraphsForChapter(extracted, idx, book.format)
                                } else {
                                    state.paragraphs
                                }
                                state.copy(
                                    extractedBook = extracted,
                                    paragraphs = initialParagraphs
                                )
                            }
                        }
                    }
                    syncContextToSession(forceWhenDifferentBook = false)
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
                        val isReattaching = sessionManager.isSessionActiveFor(targetBookId)
                        val resolvedChapter = if (isReattaching) {
                            sessionManager.sessionState.value.currentChapterIndex
                        } else {
                            resolveChapterIndex(
                                chapters = chapters,
                                cfi = state.currentCfi,
                                progress = state.progress,
                                fallbackIndex = state.currentChapterIndex
                            )
                        }
                        val refreshedParagraphs = if (state.paragraphs.isEmpty() && state.extractedBook != null) {
                            sessionManager.buildParagraphsForChapter(state.extractedBook, resolvedChapter, state.bookFormat)
                        } else {
                            state.paragraphs
                        }
                        state.copy(
                            chapters = chapters,
                            currentChapterIndex = resolvedChapter,
                            paragraphs = refreshedParagraphs
                        )
                    }
                    syncContextToSession(forceWhenDifferentBook = false)
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

    private fun syncContextToSession(forceWhenDifferentBook: Boolean) {
        val state = _uiState.value
        if (state.bookId.isBlank()) return
        sessionManager.updateBookContext(
            bookId = state.bookId,
            bookTitle = state.bookTitle,
            author = state.bookAuthor,
            coverPath = state.bookCoverUri,
            bookFormat = state.bookFormat,
            bookFilePath = state.bookFilePath,
            extractedBook = state.extractedBook,
            chapters = state.chapters,
            currentChapterIndex = state.currentChapterIndex,
            paragraphs = state.paragraphs,
            activeParagraphIndex = state.activeParagraphIndex,
            characters = state.characters,
            isBilingualEnabled = state.isBilingualEnabled,
            bilingualTranslations = state.bilingualTranslations,
            forceWhenDifferentBook = forceWhenDifferentBook
        )
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, narrationStatusMessage = null) }
        sessionManager.clearNotices()
    }

    fun setTheme(theme: ReaderTheme) {
        _uiState.update { it.copy(theme = theme) }
        viewModelScope.launch(NonCancellable) {
            try {
                settingsRepository?.setReaderTheme(theme.name)
            } catch (_: Throwable) {
            }
        }
    }

    fun setFontSize(size: Int) {
        val clamped = size.coerceIn(12, 32)
        _uiState.update { it.copy(fontSizeSp = clamped) }
        viewModelScope.launch(NonCancellable) {
            try {
                settingsRepository?.setReaderFontSizeSp(clamped)
            } catch (_: Throwable) {
            }
        }
    }

    fun setFontFamily(family: String) {
        _uiState.update { it.copy(fontFamily = family) }
        viewModelScope.launch(NonCancellable) {
            try {
                settingsRepository?.setReaderFontFamily(family)
            } catch (_: Throwable) {
            }
        }
    }

    fun setTocOpen(open: Boolean) {
        _uiState.update { it.copy(isTocOpen = open) }
    }

    fun setSearchOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isSearchOpen = open,
                searchQuery = if (!open) "" else it.searchQuery,
                searchResults = if (!open) emptyList() else it.searchResults
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFollowHighlight(follow: Boolean) {
        _uiState.update { it.copy(followHighlight = follow) }
    }

    fun setNarrationSheetExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isNarrationSheetExpanded = expanded) }
    }

    fun jumpToActiveHighlight() {
        _uiState.update { it.copy(jumpToHighlightNonce = it.jumpToHighlightNonce + 1) }
    }

    fun selectChapter(chapter: ChapterEntity) {
        val chapters = _uiState.value.chapters
        val index = chapters.indexOfFirst { it.id == chapter.id }.takeIf { it >= 0 } ?: chapter.index
        selectChapterByIndex(index)
    }

    fun nextChapter() {
        val state = _uiState.value
        val total = maxOf(state.chapters.size, state.extractedBook?.chapters?.size ?: 0)
        if (total <= 0) return
        val next = (state.currentChapterIndex + 1).coerceAtMost(total - 1)
        if (next != state.currentChapterIndex) {
            selectChapterByIndex(next)
        }
    }

    fun prevChapter() {
        val state = _uiState.value
        val total = maxOf(state.chapters.size, state.extractedBook?.chapters?.size ?: 0)
        if (total <= 0) return
        val prev = (state.currentChapterIndex - 1).coerceAtLeast(0)
        if (prev != state.currentChapterIndex) {
            selectChapterByIndex(prev)
        }
    }

    private fun selectChapterByIndex(index: Int) {
        val state = _uiState.value
        val wasPlaying = state.isNarrationPlaying && sessionManager.isSessionActiveFor(bookId)
        val chapters = state.chapters
        val extracted = state.extractedBook
        val total = maxOf(chapters.size, extracted?.chapters?.size ?: 0)
        val clampedIndex = if (total > 0) index.coerceIn(0, total - 1) else index.coerceAtLeast(0)
        val chapter = chapters.getOrNull(clampedIndex)
        val isPdf = state.bookFormat.equals("PDF", ignoreCase = true) ||
            extracted?.format.equals("PDF", ignoreCase = true)
        val cfi = chapter?.cfiRange?.takeIf { it.isNotBlank() } ?: sessionManager.defaultChapterCfi(clampedIndex, isPdf)
        val progress = if (total <= 1) 0f else (clampedIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
        val newParagraphs = if (extracted != null) {
            sessionManager.buildParagraphsForChapter(extracted, clampedIndex, state.bookFormat)
        } else {
            emptyList()
        }

        _uiState.update {
            it.copy(
                currentChapterIndex = clampedIndex,
                currentCfi = cfi,
                progress = progress,
                activeParagraphIndex = 0,
                paragraphs = newParagraphs,
                highlightedCfi = if (wasPlaying) newParagraphs.firstOrNull()?.cfi else null,
                bilingualTranslations = if (it.isBilingualEnabled || it.narrateTranslatedText) emptyMap() else it.bilingualTranslations
            )
        }

        if (bookId.isNotBlank()) {
            persistProgress(bookId, cfi, progress)
        }
        if (wasPlaying) {
            sessionManager.switchChapter(clampedIndex, continuePlaying = true)
        } else {
            syncContextToSession(forceWhenDifferentBook = sessionManager.isSessionActiveFor(bookId))
            if (_uiState.value.isBilingualEnabled) {
                sessionManager.translateCurrentChapterPublic(fallbackDemoIfEmpty = true)
            }
        }
    }

    fun toggleNarration() {
        if (_uiState.value.isNarrationPlaying) {
            pauseNarration()
        } else {
            startNarration()
        }
    }

    fun startNarration() {
        val state = _uiState.value
        sessionManager.startOrResume(
            bookId = state.bookId,
            bookTitle = state.bookTitle,
            author = state.bookAuthor,
            coverPath = state.bookCoverUri,
            bookFormat = state.bookFormat,
            bookFilePath = state.bookFilePath,
            extractedBook = state.extractedBook,
            chapters = state.chapters,
            currentChapterIndex = state.currentChapterIndex,
            paragraphs = state.paragraphs,
            activeParagraphIndex = state.activeParagraphIndex,
            characters = state.characters,
            isBilingualEnabled = state.isBilingualEnabled,
            bilingualTranslations = state.bilingualTranslations
        )
    }

    fun pauseNarration() {
        if (sessionManager.isSessionActiveFor(bookId)) {
            sessionManager.pause()
        } else {
            _uiState.update { it.copy(isNarrationPlaying = false) }
        }
    }

    fun stopNarration() {
        if (sessionManager.isSessionActiveFor(bookId) || sessionManager.sessionState.value.bookId == bookId) {
            sessionManager.stop()
        }
        _uiState.update {
            it.copy(
                isNarrationPlaying = false,
                highlightedCfi = null,
                narrationStatusMessage = null
            )
        }
    }

    fun seekToChapterPositionMs(positionMs: Long) {
        if (sessionManager.sessionState.value.bookId == bookId) {
            sessionManager.seekToChapterPositionMs(positionMs)
        }
    }

    fun seekByDeltaMs(deltaMs: Long) {
        if (sessionManager.sessionState.value.bookId == bookId) {
            sessionManager.seekByDeltaMs(deltaMs)
        }
    }

    fun seekToParagraph(paragraphIndex: Int) {
        if (sessionManager.sessionState.value.bookId == bookId) {
            sessionManager.seekToParagraph(paragraphIndex)
        } else {
            val paras = _uiState.value.paragraphs
            if (paras.isEmpty()) return
            val clamped = paragraphIndex.coerceIn(0, paras.lastIndex)
            _uiState.update {
                it.copy(
                    activeParagraphIndex = clamped,
                    highlightedCfi = paras[clamped].cfi
                )
            }
        }
    }

    fun nextParagraph() {
        if (sessionManager.sessionState.value.bookId == bookId) {
            sessionManager.nextParagraph()
        }
    }

    fun previousParagraph() {
        if (sessionManager.sessionState.value.bookId == bookId) {
            sessionManager.previousParagraph()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.5f)
        _uiState.update { it.copy(playbackSpeed = clamped) }
        sessionManager.setPlaybackSpeed(clamped)
    }

    fun setSleepTimer(minutes: Int) {
        sessionManager.setSleepTimer(minutes)
    }

    fun setNarrateTranslatedText(enabled: Boolean) {
        _uiState.update { it.copy(narrateTranslatedText = enabled) }
        syncContextToSession(forceWhenDifferentBook = false)
        sessionManager.setNarrateTranslatedText(enabled)
    }

    fun setNarrationEngine(engine: NarrationEngine) {
        _uiState.update { it.copy(narrationEngine = engine) }
        sessionManager.setNarrationEngine(engine)
    }

    internal fun buildSpeechChunks(
        paragraphs: List<ReaderParagraph>,
        characters: List<CharacterEntity>,
        engine: NarrationEngine
    ): List<SpeechChunk> = sessionManager.buildSpeechChunks(paragraphs, characters, engine)

    internal suspend fun synthesizeSpeechChunk(
        chunk: SpeechChunk,
        engineChoice: NarrationEngine,
        systemFallback: FeatureNarrationEngine,
        diskCache: TranslationDiskCache?,
        maxCacheBytes: Long,
        ttsAlreadyFailed: Boolean,
        onTtsFailed: () -> Unit,
        onStatusUpdate: (String) -> Unit,
        onPcmChunk: suspend (PcmChunk) -> Unit
    ) {
        sessionManager.synthesizeSpeechChunk(
            chunk = chunk,
            engineChoice = engineChoice,
            systemFallback = systemFallback,
            diskCache = diskCache,
            maxCacheBytes = maxCacheBytes,
            ttsAlreadyFailed = ttsAlreadyFailed,
            onTtsFailed = onTtsFailed,
            onStatusUpdate = onStatusUpdate,
            onPcmChunk = onPcmChunk
        )
    }

    fun setCastEditorOpen(open: Boolean) {
        _uiState.update { it.copy(isCastEditorOpen = open) }
    }

    fun analyzeCastForCurrentChapter() {
        castJob?.cancel()
        castJob = viewModelScope.launch {
            _uiState.update { it.copy(isCastAnalyzing = true) }
            try {
                val state = _uiState.value
                val chapterParagraphs = state.paragraphs.ifEmpty {
                    state.extractedBook?.let {
                        sessionManager.buildParagraphsForChapter(it, state.currentChapterIndex, state.bookFormat)
                    }.orEmpty()
                }
                val paragraphTexts = chapterParagraphs.map { it.text }.filter { it.isNotBlank() }
                val existing = state.characters
                val apiKey = resolveApiKey()
                val castingService = CastingService()

                var detectedDomainChars: List<Character> = emptyList()
                if (textModelClient != null && !apiKey.isNullOrBlank() && paragraphTexts.isNotEmpty()) {
                    try {
                        val prompt = buildString {
                            append("Identify all speaking characters and the narrator in these book paragraphs. ")
                            append("Return JSON matching the schema.\n\n")
                            paragraphTexts.take(35).forEachIndexed { idx, text ->
                                append("[$idx] $text\n")
                            }
                        }
                        val model = resolveModel(ModelRole.TEXT_ANALYSIS)
                        val responseJson = withContext(Dispatchers.IO) {
                            textModelClient.generateContent(
                                apiKey = apiKey,
                                prompt = prompt,
                                systemInstruction = "Extract characters and dialogue attribution accurately in JSON.",
                                responseJsonSchema = castingService.getPromptJsonSchema(),
                                model = model
                            )
                        }
                        val chapterId = state.chapters.getOrNull(state.currentChapterIndex)?.id
                            ?: "${bookId}_chap_${state.currentChapterIndex}"
                        val result = castingService.parseAndValidateCasting(
                            jsonResponse = responseJson,
                            paragraphs = paragraphTexts,
                            existingCharacters = existing.map { it.toDomain() },
                            bookId = bookId,
                            chapterId = chapterId
                        )
                        detectedDomainChars = result.characters
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }

                if (detectedDomainChars.isEmpty()) {
                    detectedDomainChars = detectSpeakersHeuristically(paragraphTexts, bookId)
                }

                val hasNarrator = detectedDomainChars.any {
                    it.id == "narrator" || it.name.equals("Narrator", ignoreCase = true)
                }
                if (!hasNarrator) {
                    detectedDomainChars = listOf(
                        Character(
                            id = "${bookId}_narrator",
                            bookId = bookId,
                            name = "Narrator",
                            gender = "neutral",
                            age = "mature",
                            voiceTraits = "clear, storytelling"
                        )
                    ) + detectedDomainChars
                }

                val existingByName = existing.associateBy { it.name.lowercase() }
                val mergedDomain = mutableListOf<Character>()
                for (existingChar in existing) {
                    mergedDomain.add(existingChar.toDomain())
                }
                for (candidate in detectedDomainChars) {
                    val key = candidate.name.lowercase()
                    if (!existingByName.containsKey(key)) {
                        mergedDomain.add(
                            candidate.copy(
                                id = if (candidate.id.startsWith("${bookId}_")) candidate.id else "${bookId}_${candidate.id}"
                            )
                        )
                    }
                }

                val caster = VoiceCaster()
                val usedVoices = mergedDomain.mapNotNull { it.assignedVoiceId }.toMutableSet()
                val autoAssigned = caster.autoAssignVoices(mergedDomain)
                val availablePool = GeminiStudioVoices.catalog.map { it.id }.toMutableList()
                availablePool.removeAll(usedVoices)

                val finalEntities = mergedDomain.map { ch ->
                    val existingVoice = ch.assignedVoiceId?.takeIf { it.isNotBlank() }
                    val voice = if (existingVoice != null) {
                        existingVoice
                    } else if (ch.name.equals("Narrator", ignoreCase = true)) {
                        "Charon"
                    } else {
                        val preferred = autoAssigned[ch.id]
                        if (preferred != null && preferred !in usedVoices) {
                            usedVoices.add(preferred)
                            availablePool.remove(preferred)
                            preferred
                        } else {
                            val nextUnused = availablePool.removeFirstOrNull() ?: "Zephyr"
                            usedVoices.add(nextUnused)
                            nextUnused
                        }
                    }
                    CharacterEntity.fromDomain(ch.copy(bookId = bookId, assignedVoiceId = voice))
                }

                characterDao?.insertCharacters(finalEntities)
                _uiState.update { it.copy(characters = finalEntities, isCastAnalyzing = false) }
                syncContextToSession(forceWhenDifferentBook = false)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isCastAnalyzing = false,
                        errorMessage = sessionManager.formatConciseError(e, "Failed to analyze character cast")
                    )
                }
            }
        }
    }

    fun updateCharacterVoice(character: CharacterEntity, newVoiceId: String, newStyle: String?) {
        val updated = character.copy(
            assignedVoiceId = newVoiceId,
            voiceTraits = newStyle?.trim() ?: character.voiceTraits
        )
        _uiState.update { state ->
            state.copy(
                characters = state.characters.map { if (it.id == updated.id) updated else it }
            )
        }
        syncContextToSession(forceWhenDifferentBook = false)
        viewModelScope.launch {
            characterDao?.updateCharacter(updated)
        }
    }

    fun toggleBilingualMode() {
        val newState = !_uiState.value.isBilingualEnabled
        _uiState.update { it.copy(isBilingualEnabled = newState) }
        syncContextToSession(forceWhenDifferentBook = false)
        sessionManager.setBilingualMode(newState)
    }

    fun setTargetLanguage(lang: LanguageCode) {
        _uiState.update { it.copy(targetLanguage = lang) }
        syncContextToSession(forceWhenDifferentBook = false)
        sessionManager.setTargetLanguage(lang)
    }

    fun setTargetLanguage(lang: String) {
        setTargetLanguage(LanguageCode.fromCode(lang))
    }

    fun retryTranslation() {
        clearError()
        sessionManager.retryTranslation()
    }

    fun setBilingualTranslations(translations: Map<String, String>) {
        _uiState.update { it.copy(bilingualTranslations = translations) }
        syncContextToSession(forceWhenDifferentBook = false)
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
                if (cfi.startsWith("$base!") || cfi.startsWith("$base/") || cfi.startsWith("$range:")) {
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
        // Do NOT stop background narration on ViewModel teardown; Audio continues cleanly in PlaybackCoordinator.
        flushProgress()
        super.onCleared()
    }

    override fun onParagraphsExtracted(chapterIndex: Int, paragraphsJson: String) {
        val state = _uiState.value
        val resolvedChapter = if (chapterIndex >= 0) chapterIndex else state.currentChapterIndex
        val isPdf = state.bookFormat.equals("PDF", ignoreCase = true) ||
            state.extractedBook?.format.equals("PDF", ignoreCase = true)
        val parsed = parseParagraphsJson(paragraphsJson, resolvedChapter, isPdf)
        if (parsed.isNotEmpty()) {
            val changed = parsed != state.paragraphs
            _uiState.update {
                it.copy(
                    paragraphs = parsed,
                    activeParagraphIndex = if (changed && !it.isNarrationPlaying) 0 else it.activeParagraphIndex
                )
            }
            syncContextToSession(forceWhenDifferentBook = false)
            if (_uiState.value.isBilingualEnabled && (changed || state.bilingualTranslations.isEmpty())) {
                sessionManager.translateCurrentChapterPublic(fallbackDemoIfEmpty = false)
            }
        }
    }

    override fun onChaptersExtracted(chaptersJson: String) {
        val currentBookId = bookId
        if (currentBookId.isBlank()) return
        try {
            val root = JsonMiniParser.parse(chaptersJson)
            val array: List<*> = when (root) {
                is Map<*, *> -> (root["chapters"] as? List<*>) ?: emptyList<Any?>()
                is List<*> -> root
                else -> emptyList<Any?>()
            }
            if (array.isEmpty()) return
            val existing = _uiState.value.chapters
            if (existing.isNotEmpty()) return
            val isPdf = _uiState.value.bookFormat.equals("PDF", ignoreCase = true)
            val entities = buildList {
                array.forEachIndexed { i, item ->
                    val obj = item as? Map<*, *> ?: return@forEachIndexed
                    val idx = (obj["index"] as? Number)?.toInt() ?: i
                    val title = (obj["title"] as? String)?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"
                    val cfi = (obj["cfi"] as? String)?.takeIf { it.isNotBlank() }
                        ?: (obj["href"] as? String)?.takeIf { it.startsWith("pdf:page:") || it.startsWith("epubcfi(") }
                        ?: sessionManager.defaultChapterCfi(idx, isPdf)
                    add(
                        ChapterEntity(
                            id = "${currentBookId}_chap_$idx",
                            bookId = currentBookId,
                            index = idx,
                            title = title,
                            startMs = idx * 1000L,
                            endMs = (idx + 1) * 1000L,
                            cfiRange = cfi,
                            textLength = 0
                        )
                    )
                }
            }
            if (entities.isNotEmpty()) {
                viewModelScope.launch {
                    chapterDao.insertChapters(entities)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onReaderError(message: String) {
        if (message.isNotBlank()) {
            _uiState.update { it.copy(errorMessage = message) }
        }
    }

    override fun onPageTurned(cfi: String, fraction: Float, chapterTitle: String) {
        saveProgressInternal(cfi, fraction, chapterTitle)
    }

    override fun onBookReady() {
        _uiState.update { it.copy(isBookReady = true) }
    }

    override fun onSelectionChanged(cfiRange: String, selectedText: String) {}

    override fun onSearchResults(resultsJson: String) {
        val list = try {
            val arr = JsonMiniParser.parse(resultsJson) as? List<*> ?: emptyList<Any?>()
            arr.mapNotNull { item ->
                val obj = item as? Map<*, *> ?: return@mapNotNull null
                val cfi = obj["cfi"] as? String ?: return@mapNotNull null
                if (cfi.isBlank()) return@mapNotNull null
                val excerpt = obj["excerpt"] as? String ?: ""
                SearchResultItem(cfi = cfi, excerpt = excerpt)
            }
        } catch (_: Exception) {
            emptyList()
        }
        _uiState.update { it.copy(searchResults = list) }
    }

    private suspend fun resolveApiKey(): String? {
        val fromStore = try {
            secureKeyStore?.getApiKey()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
        if (fromStore != null) return fromStore
        return try {
            settingsRepository?.apiKey?.firstOrNull()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun resolveModel(role: ModelRole): String {
        return try {
            settingsRepository?.modelPerRole?.firstOrNull()?.get(role) ?: DefaultModels.get(role)
        } catch (_: Throwable) {
            DefaultModels.get(role)
        }
    }

    private fun parseNarrationEngine(name: String): NarrationEngine {
        return NarrationEngine.values().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: NarrationEngine.GEMINI_FLASH_TTS
    }

    private fun parseParagraphsJson(
        paragraphsJson: String,
        chapterIndex: Int,
        isPdf: Boolean
    ): List<ReaderParagraph> {
        return try {
            val arr = JsonMiniParser.parse(paragraphsJson) as? List<*> ?: return emptyList()
            buildList {
                arr.forEachIndexed { i, item ->
                    when (item) {
                        is Map<*, *> -> {
                            val text = (item["text"] as? String).orEmpty().trim()
                            if (text.isNotEmpty()) {
                                val cfi = (item["cfiRange"] as? String).orEmpty()
                                    .ifBlank { (item["cfi"] as? String).orEmpty() }
                                    .ifBlank { sessionManager.defaultParagraphCfi(chapterIndex, i, isPdf) }
                                val id = (item["id"] as? String)?.takeIf { it.isNotBlank() }
                                    ?: "p_${chapterIndex}_$i"
                                add(ReaderParagraph(index = i, id = id, text = text, cfi = cfi))
                            }
                        }
                        is String -> {
                            val text = item.trim()
                            if (text.isNotEmpty()) {
                                add(
                                    ReaderParagraph(
                                        index = i,
                                        id = "p_${chapterIndex}_$i",
                                        text = text,
                                        cfi = sessionManager.defaultParagraphCfi(chapterIndex, i, isPdf)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun detectSpeakersHeuristically(paragraphs: List<String>, targetBookId: String): List<Character> {
        val discovered = linkedMapOf<String, Character>()
        for (para in paragraphs) {
            val colonName = COLON_SPEAKER_REGEX.find(para)?.groupValues?.get(1)?.trim()
            if (!colonName.isNullOrBlank() && colonName !in STOPWORD_SPEAKERS) {
                discovered.putIfAbsent(
                    colonName.lowercase(),
                    Character(
                        id = "${targetBookId}_${colonName.lowercase().replace(Regex("[^a-z0-9]"), "_")}",
                        bookId = targetBookId,
                        name = colonName,
                        voiceTraits = "conversational"
                    )
                )
            }
            for (match in AFTER_QUOTE_SPEAKER_REGEX.findAll(para)) {
                val name = match.groupValues[1].trim()
                if (name.isNotBlank() && name !in STOPWORD_SPEAKERS) {
                    discovered.putIfAbsent(
                        name.lowercase(),
                        Character(
                            id = "${targetBookId}_${name.lowercase().replace(Regex("[^a-z0-9]"), "_")}",
                            bookId = targetBookId,
                            name = name,
                            voiceTraits = "expressive"
                        )
                    )
                }
            }
            for (match in BEFORE_QUOTE_SPEAKER_REGEX.findAll(para)) {
                val name = match.groupValues[1].trim()
                if (name.isNotBlank() && name !in STOPWORD_SPEAKERS) {
                    discovered.putIfAbsent(
                        name.lowercase(),
                        Character(
                            id = "${targetBookId}_${name.lowercase().replace(Regex("[^a-z0-9]"), "_")}",
                            bookId = targetBookId,
                            name = name,
                            voiceTraits = "expressive"
                        )
                    )
                }
            }
        }
        return discovered.values.toList()
    }

    companion object {
        private val COLON_SPEAKER_REGEX = Regex("""^([A-Z][a-zA-Z]{1,24}(?:\s+[A-Z][a-zA-Z]{1,20})?):\s+\S""")
        private val AFTER_QUOTE_SPEAKER_REGEX = Regex(
            """["“”][^"“”]{2,}["””]\s*,?\s*(?:said|asked|replied|whispered|shouted|murmured|exclaimed|answered|cried|added|noted|observed|muttered|called)\s+([A-Z][a-zA-Z]{1,24})\b"""
        )
        private val BEFORE_QUOTE_SPEAKER_REGEX = Regex(
            """\b([A-Z][a-zA-Z]{1,24})\s+(?:said|asked|replied|whispered|shouted|murmured|exclaimed|answered|cried|added|noted|observed|muttered|called)\s*[,:]\s*["“]"""
        )
        private val STOPWORD_SPEAKERS = setOf(
            "The", "And", "But", "She", "He", "They", "It", "We", "You", "Chapter", "Page", "Narrator", "This", "That"
        )
    }
}

private object JsonMiniParser {
    fun parse(input: String): Any? {
        val parser = Cursor(input)
        parser.skipWhitespace()
        return if (parser.hasNext()) parser.readValue() else null
    }

    private class Cursor(private val s: String) {
        private var pos = 0

        fun hasNext(): Boolean = pos < s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (!hasNext()) return null
            return when (val ch = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected JSON char '$ch' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (hasNext() && s[pos] == '}') {
                pos++
                return map
            }
            while (hasNext()) {
                skipWhitespace()
                if (s[pos] == '}') {
                    pos++
                    break
                }
                val key = readString()
                skipWhitespace()
                if (!hasNext() || s[pos] != ':') throw IllegalArgumentException("Expected ':' at $pos")
                pos++
                val value = readValue()
                map[key] = value
                skipWhitespace()
                if (hasNext() && s[pos] == ',') {
                    pos++
                } else if (hasNext() && s[pos] == '}') {
                    pos++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
            return map
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (hasNext() && s[pos] == ']') {
                pos++
                return list
            }
            while (hasNext()) {
                skipWhitespace()
                if (s[pos] == ']') {
                    pos++
                    break
                }
                list.add(readValue())
                skipWhitespace()
                if (hasNext() && s[pos] == ',') {
                    pos++
                } else if (hasNext() && s[pos] == ']') {
                    pos++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
            return list
        }

        private fun readString(): String {
            if (!hasNext() || s[pos] != '"') throw IllegalArgumentException("Expected '\"' at $pos")
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) break
                        when (val esc = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= s.length) {
                                    val hex = s.substring(pos, pos + 4)
                                    sb.append(hex.toInt(16).toChar())
                                    pos += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun readBoolean(): Boolean {
            return if (s.startsWith("true", pos)) {
                pos += 4
                true
            } else if (s.startsWith("false", pos)) {
                pos += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean at $pos")
            }
        }

        private fun readNull(): Any? {
            if (s.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null at $pos")
        }

        private fun readNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) {
                pos++
            }
            val raw = s.substring(start, pos)
            return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                raw.toDouble()
            } else {
                raw.toLong()
            }
        }
    }
}
