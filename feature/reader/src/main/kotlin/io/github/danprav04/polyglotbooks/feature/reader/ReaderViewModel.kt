package io.github.danprav04.polyglotbooks.feature.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danprav04.polyglotbooks.core.audio.PcmPlayer
import io.github.danprav04.polyglotbooks.core.audio.VoicePreviewPlayer
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
import io.github.danprav04.polyglotbooks.core.playback.PlaybackCoordinator
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
    val chapterNavigationNonce: Int = 0,
    val isCastEditorOpen: Boolean = false,
    val isBilingualEnabled: Boolean = false,
    val targetLanguage: LanguageCode = LanguageCode.SPANISH,
    val defaultNarratorVoiceId: String = GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID,
    val playingPreviewVoiceId: String? = null,
    val previewErrorMessage: String? = null,
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
    private val injectedSessionManager: ReaderNarrationSessionManager? = null,
    private val voicePreviewPlayer: VoicePreviewPlayer? = null,
    private val playbackCoordinator: PlaybackCoordinator? = null
) : ViewModel(), ReaderBridgeListener {

    private var bookId: String = savedStateHandle.get<String>("bookId") ?: "sample_book_id"
    private var loadJob: Job? = null
    private var charactersJob: Job? = null
    private var settingsJob: Job? = null
    private var sessionObserverJob: Job? = null
    private var castJob: Job? = null
    private val saveMutex = Mutex()
    private var lastSessionError: String? = null
    private var lastSessionStatus: String? = null

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
            playbackCoordinator = playbackCoordinator,
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
        val repo = settingsRepository
        if (repo == null && voicePreviewPlayer == null) return
        settingsJob = viewModelScope.launch {
            repo?.let { r ->
                launch {
                    r.targetLanguage.collect { lang ->
                        _uiState.update { it.copy(targetLanguage = lang) }
                    }
                }
                launch {
                    r.readerNarrationEngine.collect { engineName ->
                        val parsed = parseNarrationEngine(engineName)
                        _uiState.update { it.copy(narrationEngine = parsed) }
                    }
                }
                launch {
                    r.defaultNarratorVoiceId.collect { voiceId ->
                        val normalized = GeminiStudioVoices.getVoice(voiceId)?.id
                            ?: GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID
                        _uiState.update { it.copy(defaultNarratorVoiceId = normalized) }
                    }
                }
                launch {
                    r.readerReadTranslated.collect { readTranslated ->
                        _uiState.update { it.copy(narrateTranslatedText = readTranslated) }
                    }
                }
                launch {
                    r.readerNarrationSpeed.collect { speed ->
                        _uiState.update { it.copy(playbackSpeed = speed.coerceIn(0.5f, 2.5f)) }
                    }
                }
                launch {
                    r.readerTheme.collect { themeName ->
                        val parsed = ReaderTheme.values().firstOrNull {
                            it.name.equals(themeName, ignoreCase = true)
                        } ?: ReaderTheme.LIGHT
                        _uiState.update { it.copy(theme = parsed) }
                    }
                }
                launch {
                    r.readerFontSizeSp.collect { sizeSp ->
                        _uiState.update { it.copy(fontSizeSp = sizeSp.coerceIn(12, 32)) }
                    }
                }
                launch {
                    r.readerFontFamily.collect { family ->
                        if (family.isNotBlank()) {
                            _uiState.update { it.copy(fontFamily = family) }
                        }
                    }
                }
            }
            voicePreviewPlayer?.let { preview ->
                launch {
                    preview.playingVoiceId.collect { id ->
                        _uiState.update { it.copy(playingPreviewVoiceId = id) }
                    }
                }
                launch {
                    preview.previewError.collect { err ->
                        _uiState.update { it.copy(previewErrorMessage = err) }
                    }
                }
            }
        }
    }

    private fun observeSessionState() {
        sessionObserverJob?.cancel()
        sessionObserverJob = viewModelScope.launch {
            sessionManager.sessionState.collect { session ->
                val sessionErr = session.errorMessage
                val sessionStat = session.statusMessage
                val errChanged = sessionErr != lastSessionError
                val statChanged = sessionStat != lastSessionStatus
                lastSessionError = sessionErr
                lastSessionStatus = sessionStat

                _uiState.update { state ->
                    val base = state.copy(
                        narrationEngine = session.narrationEngine,
                        defaultNarratorVoiceId = session.defaultNarratorVoiceId,
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
                        val chapterChangedFromSession = session.isActiveSession &&
                            updatedChapterIndex != base.currentChapterIndex &&
                            (!session.isPlaying || base.followHighlight)
                        val syncedParagraphs = if (
                            session.isActiveSession ||
                            (session.currentChapterIndex == updatedChapterIndex && session.extractedBook != null)
                        ) {
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
                        val validSessionHighlight = session.highlightedCfi?.takeIf { cfi ->
                            cfi.isNotBlank() && cfiMatchesChapter(
                                cfi = cfi,
                                expectedChapterIndex = updatedChapterIndex,
                                chapters = base.chapters.ifEmpty { session.chapters },
                                bookFormat = base.bookFormat.ifBlank { session.bookFormat }
                            )
                        }
                        val syncedActiveCfi = if (session.isActiveSession && session.isPlaying) {
                            validSessionHighlight
                                ?: syncedParagraphs.getOrNull(session.activeParagraphIndex)?.cfi
                                ?: base.currentCfi
                        } else {
                            base.currentCfi
                        }
                        val syncedProgress = if (session.isActiveSession && session.isPlaying) {
                            val withinChap = if (syncedParagraphs.size <= 1) {
                                0f
                            } else {
                                session.activeParagraphIndex.toFloat() / (syncedParagraphs.size - 1)
                            }
                            if (totalChapters <= 1) {
                                withinChap.coerceIn(0f, 1f)
                            } else {
                                ((updatedChapterIndex + withinChap) / totalChapters).coerceIn(0f, 1f)
                            }
                        } else {
                            base.progress
                        }
                        base.copy(
                            currentChapterIndex = updatedChapterIndex,
                            chapterNavigationNonce = if (chapterChangedFromSession) {
                                base.chapterNavigationNonce + 1
                            } else {
                                base.chapterNavigationNonce
                            },
                            currentCfi = syncedActiveCfi,
                            progress = syncedProgress,
                            paragraphs = syncedParagraphs,
                            activeParagraphIndex = if (session.isActiveSession) {
                                session.activeParagraphIndex.coerceIn(0, maxOf(0, syncedParagraphs.lastIndex))
                            } else {
                                base.activeParagraphIndex
                            },
                            highlightedCfi = if (session.isActiveSession) validSessionHighlight else base.highlightedCfi,
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
                            narrationStatusMessage = if (statChanged) sessionStat else base.narrationStatusMessage,
                            errorMessage = if (errChanged) sessionErr else base.errorMessage
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
            lastSessionError = null
            lastSessionStatus = null
        }

        val existingSession = sessionManager.sessionState.value
        val activeForTarget = sessionManager.isSessionActiveFor(targetBookId)
        val validExistingHighlight = existingSession.highlightedCfi?.takeIf { cfi ->
            activeForTarget && cfi.isNotBlank() && cfiMatchesChapter(
                cfi = cfi,
                expectedChapterIndex = existingSession.currentChapterIndex,
                chapters = existingSession.chapters,
                bookFormat = existingSession.bookFormat
            )
        }

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
                    currentCfi = validExistingHighlight.orEmpty(),
                    progress = 0f,
                    isStateRestored = false,
                    isBookReady = false,
                    characters = if (activeForTarget) existingSession.characters else emptyList(),
                    extractedBook = if (activeForTarget) existingSession.extractedBook else null,
                    activeParagraphIndex = if (activeForTarget) existingSession.activeParagraphIndex else 0,
                    paragraphs = if (activeForTarget) existingSession.paragraphs else emptyList(),
                    highlightedCfi = validExistingHighlight,
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
                sessionManager.awaitPendingPersist(targetBookId)
                val book = bookDao.getBookById(targetBookId)
                if (book != null) {
                    val savedState = playbackStateDao?.getPlaybackState(targetBookId)
                    val isReattaching = sessionManager.isSessionActiveFor(targetBookId)
                    val liveSessionSnapshot = sessionManager.sessionState.value
                    val rawSavedCfi = savedState?.lastCfi.orEmpty()
                    val rawSavedProgress = (savedState?.readingProgress ?: 0f).coerceIn(0f, 1f)

                    val extracted = if (appContext != null && book.filePath.isNotBlank()) {
                        if (isReattaching && liveSessionSnapshot.extractedBook != null) {
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
                    } else {
                        if (isReattaching) liveSessionSnapshot.extractedBook else null
                    }

                    var dbChapters = chapterDao.getChaptersForBook(targetBookId).firstOrNull().orEmpty()
                    if (dbChapters.isEmpty() && extracted != null && extracted.chapters.isNotEmpty()) {
                        val isPdf = extracted.format.equals("PDF", ignoreCase = true) ||
                            book.format.equals("PDF", ignoreCase = true)
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
                        dbChapters = chapterEntities
                    }

                    val restoredChapterIndex = if (isReattaching) {
                        liveSessionSnapshot.currentChapterIndex
                    } else {
                        resolveChapterIndex(
                            chapters = dbChapters,
                            cfi = rawSavedCfi,
                            progress = rawSavedProgress,
                            fallbackIndex = savedState?.chapterIndex ?: 0
                        )
                    }
                    val totalChapters = maxOf(dbChapters.size, extracted?.chapters?.size ?: 0)
                    val clampedChapterIndex = if (totalChapters > 0) {
                        restoredChapterIndex.coerceIn(0, totalChapters - 1)
                    } else {
                        restoredChapterIndex.coerceAtLeast(0)
                    }

                    val isPdfBook = extracted?.format?.equals("PDF", ignoreCase = true) == true ||
                        book.format.equals("PDF", ignoreCase = true)
                    val sessionParasMatchChapter = isReattaching &&
                        liveSessionSnapshot.paragraphs.isNotEmpty() &&
                        cfiMatchesChapter(
                            cfi = liveSessionSnapshot.paragraphs.firstOrNull()?.cfi,
                            expectedChapterIndex = clampedChapterIndex,
                            chapters = dbChapters,
                            bookFormat = book.format
                        )
                    val initialParagraphs = when {
                        sessionParasMatchChapter -> liveSessionSnapshot.paragraphs
                        extracted != null -> sessionManager.buildParagraphsForChapter(extracted, clampedChapterIndex, book.format)
                        else -> emptyList()
                    }
                    val validLiveHighlight = liveSessionSnapshot.highlightedCfi?.takeIf { cfi ->
                        isReattaching && cfi.isNotBlank() && cfiMatchesChapter(
                            cfi = cfi,
                            expectedChapterIndex = clampedChapterIndex,
                            chapters = dbChapters,
                            bookFormat = book.format
                        )
                    }
                    val savedCfiMatchesChapter = rawSavedCfi.isNotBlank() && cfiMatchesChapter(
                        cfi = rawSavedCfi,
                        expectedChapterIndex = clampedChapterIndex,
                        chapters = dbChapters,
                        bookFormat = book.format
                    )
                    val clampedSessionParaIdx = liveSessionSnapshot.activeParagraphIndex
                        .coerceIn(0, maxOf(0, initialParagraphs.lastIndex))
                    val fallbackChapterCfi = initialParagraphs.getOrNull(clampedSessionParaIdx)?.cfi
                        ?: dbChapters.getOrNull(clampedChapterIndex)?.cfiRange?.takeIf { it.isNotBlank() }
                        ?: sessionManager.defaultChapterCfi(clampedChapterIndex, isPdfBook)
                    val restoredCfi = if (isReattaching) {
                        when {
                            validLiveHighlight != null -> validLiveHighlight
                            savedCfiMatchesChapter -> rawSavedCfi
                            else -> fallbackChapterCfi
                        }
                    } else {
                        rawSavedCfi
                    }
                    val restoredProgress = if (isReattaching && !savedCfiMatchesChapter && totalChapters > 1) {
                        (clampedChapterIndex.toFloat() / (totalChapters - 1)).coerceIn(0f, 1f)
                    } else {
                        rawSavedProgress
                    }
                    val initialActiveParaIndex = if (isReattaching) {
                        if (validLiveHighlight != null) {
                            clampedSessionParaIdx
                        } else {
                            deriveParagraphIndexOrNull(restoredCfi, initialParagraphs) ?: clampedSessionParaIdx
                        }
                    } else {
                        deriveParagraphIndexFromCfi(restoredCfi, initialParagraphs)
                    }

                    _uiState.update {
                        it.copy(
                            bookId = targetBookId,
                            bookTitle = book.title,
                            bookAuthor = book.author,
                            bookCoverUri = book.coverUri,
                            bookFormat = book.format,
                            bookFilePath = book.filePath,
                            chapters = dbChapters,
                            extractedBook = extracted,
                            paragraphs = initialParagraphs,
                            activeParagraphIndex = initialActiveParaIndex,
                            highlightedCfi = if (isReattaching) validLiveHighlight else it.highlightedCfi,
                            currentCfi = restoredCfi,
                            progress = restoredProgress,
                            currentChapterIndex = clampedChapterIndex,
                            isStateRestored = true,
                            errorMessage = null
                        )
                    }
                    withContext(NonCancellable) {
                        settingsRepository?.setLastOpenedBookId(targetBookId)
                        bookDao.updateLastReadDate(targetBookId, System.currentTimeMillis())
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
                        val derivedParaIdx = if (state.paragraphs.isEmpty() && refreshedParagraphs.isNotEmpty() && !isReattaching) {
                            deriveParagraphIndexFromCfi(state.currentCfi, refreshedParagraphs)
                        } else {
                            state.activeParagraphIndex.coerceIn(0, maxOf(0, refreshedParagraphs.lastIndex))
                        }
                        state.copy(
                            chapters = chapters,
                            currentChapterIndex = resolvedChapter,
                            paragraphs = refreshedParagraphs,
                            activeParagraphIndex = derivedParaIdx
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
        lastSessionError = sessionManager.sessionState.value.errorMessage
        lastSessionStatus = sessionManager.sessionState.value.statusMessage
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
        if (state.isNarrationPlaying && sessionManager.isSessionActiveFor(bookId)) {
            sessionManager.nextChapter()
            return
        }
        val total = maxOf(state.chapters.size, state.extractedBook?.chapters?.size ?: 0)
        if (total <= 0) return
        val next = (state.currentChapterIndex + 1).coerceAtMost(total - 1)
        if (next != state.currentChapterIndex) {
            selectChapterByIndex(next)
        }
    }

    fun prevChapter() {
        val state = _uiState.value
        if (state.isNarrationPlaying && sessionManager.isSessionActiveFor(bookId)) {
            sessionManager.previousChapter()
            return
        }
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
                chapterNavigationNonce = it.chapterNavigationNonce + 1,
                currentCfi = cfi,
                progress = progress,
                activeParagraphIndex = 0,
                paragraphs = newParagraphs,
                highlightedCfi = if (wasPlaying) newParagraphs.firstOrNull()?.cfi else null,
                bilingualTranslations = if (it.isBilingualEnabled || it.narrateTranslatedText) emptyMap() else it.bilingualTranslations
            )
        }

        if (bookId.isNotBlank() && !wasPlaying) {
            persistProgress(bookId, cfi, progress, chapterIndex = clampedIndex)
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

    fun selectDefaultNarratorVoice(voiceId: String) {
        val normalized = GeminiStudioVoices.getVoice(voiceId)?.id
            ?: GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID
        _uiState.update { it.copy(defaultNarratorVoiceId = normalized) }
        sessionManager.setDefaultNarratorVoiceId(normalized)
        viewModelScope.launch(NonCancellable) {
            try {
                settingsRepository?.setDefaultNarratorVoiceId(normalized)
            } catch (_: Throwable) {
            }
        }
    }

    fun toggleVoicePreview(voiceId: String) {
        voicePreviewPlayer?.togglePreview(
            voiceId = voiceId,
            onBeforeStart = {
                playbackCoordinator?.pauseActivePlayback()
                if (playbackCoordinator == null && _uiState.value.isNarrationPlaying) {
                    sessionManager.pause()
                }
            }
        )
    }

    fun stopVoicePreview() {
        voicePreviewPlayer?.stop()
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

                val narratorVoice = _uiState.value.defaultNarratorVoiceId.ifBlank {
                    GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID
                }
                val caster = VoiceCaster(defaultNarratorVoiceId = narratorVoice)
                val usedVoices = mergedDomain.mapNotNull { it.assignedVoiceId }.toMutableSet()
                val autoAssigned = caster.autoAssignVoices(mergedDomain)
                val availablePool = GeminiStudioVoices.catalog.map { it.id }.toMutableList()
                availablePool.removeAll(usedVoices)

                val finalEntities = mergedDomain.map { ch ->
                    val existingVoice = ch.assignedVoiceId?.takeIf { it.isNotBlank() }
                    val voice = if (existingVoice != null) {
                        existingVoice
                    } else if (ch.name.equals("Narrator", ignoreCase = true)) {
                        narratorVoice
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
        saveProgressInternal(cfi, progress)
    }

    fun flushProgress() {
        val state = _uiState.value
        if (!state.isStateRestored || state.bookId.isBlank()) return
        val sessionSnapshot = sessionManager.sessionState.value
        if (sessionManager.isSessionActiveFor(state.bookId) && sessionSnapshot.isPlaying) {
            val ttsCfi = sessionSnapshot.highlightedCfi?.takeIf { it.isNotBlank() } ?: return
            val ttsChapterIndex = sessionSnapshot.currentChapterIndex.coerceAtLeast(0)
            val totalChapters = maxOf(
                state.chapters.size,
                state.extractedBook?.chapters?.size ?: 0,
                sessionSnapshot.chapters.size
            )
            val paraCount = sessionSnapshot.paragraphs.size
            val withinChap = if (paraCount <= 1) {
                0f
            } else {
                (sessionSnapshot.activeParagraphIndex.toFloat() / (paraCount - 1)).coerceIn(0f, 1f)
            }
            val ttsProgress = if (totalChapters <= 1) {
                withinChap
            } else {
                ((ttsChapterIndex + withinChap) / totalChapters).coerceIn(0f, 1f)
            }
            persistProgress(
                targetBookId = state.bookId,
                cfi = ttsCfi,
                progress = ttsProgress,
                chapterIndex = ttsChapterIndex,
                forceExplicitParams = true
            )
            return
        }
        if (state.currentCfi.isBlank() && state.progress <= 0f) return
        persistProgress(state.bookId, state.currentCfi, state.progress, state.currentChapterIndex)
    }

    fun resetBookReady() {
        _uiState.update { it.copy(isBookReady = false) }
    }

    private fun saveProgressInternal(
        cfi: String,
        progress: Float,
        explicitChapterIndex: Int = -1
    ) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val targetBookId = bookId
        var resolvedChapter = 0
        var chapterChangedWhileNotPlaying = false
        _uiState.update { state ->
            val chapterIndex = resolveChapterIndex(
                chapters = state.chapters,
                cfi = cfi,
                progress = clampedProgress,
                explicitChapterIndex = explicitChapterIndex,
                fallbackIndex = state.currentChapterIndex
            )
            resolvedChapter = chapterIndex
            val chapterChanged = chapterIndex != state.currentChapterIndex
            chapterChangedWhileNotPlaying = !state.isNarrationPlaying && (
                chapterChanged || (
                    sessionManager.isSessionActiveFor(targetBookId) &&
                        !sessionManager.sessionState.value.isPlaying &&
                        chapterIndex != sessionManager.sessionState.value.currentChapterIndex
                    )
                )
            val updatedParagraphs = if (chapterChanged && !state.isNarrationPlaying && state.extractedBook != null) {
                sessionManager.buildParagraphsForChapter(state.extractedBook, chapterIndex, state.bookFormat)
            } else {
                state.paragraphs
            }
            val updatedPara = if (!state.isNarrationPlaying && updatedParagraphs.isNotEmpty()) {
                deriveParagraphIndexOrNull(cfi, updatedParagraphs) ?: if (chapterChanged) 0 else state.activeParagraphIndex
            } else if (chapterChanged && !state.isNarrationPlaying) {
                0
            } else {
                state.activeParagraphIndex
            }
            val updatedHighlight = if (
                !state.isNarrationPlaying &&
                !state.highlightedCfi.isNullOrBlank() &&
                !cfiMatchesChapter(state.highlightedCfi, chapterIndex, state.chapters, state.bookFormat)
            ) {
                null
            } else {
                state.highlightedCfi
            }
            state.copy(
                currentCfi = cfi,
                progress = clampedProgress,
                currentChapterIndex = chapterIndex,
                paragraphs = updatedParagraphs,
                activeParagraphIndex = updatedPara,
                highlightedCfi = updatedHighlight
            )
        }
        if (targetBookId.isNotBlank()) {
            persistProgress(targetBookId, cfi, clampedProgress, resolvedChapter)
            if (chapterChangedWhileNotPlaying &&
                sessionManager.isSessionActiveFor(targetBookId) &&
                !sessionManager.sessionState.value.isPlaying
            ) {
                syncContextToSession(forceWhenDifferentBook = false)
            }
        }
    }

    private fun persistProgress(
        targetBookId: String,
        cfi: String,
        progress: Float,
        chapterIndex: Int = _uiState.value.currentChapterIndex,
        forceExplicitParams: Boolean = false
    ) {
        viewModelScope.launch(NonCancellable) {
            try {
                saveMutex.withLock {
                    val latestState = _uiState.value
                    val useLatest = !forceExplicitParams && latestState.bookId == targetBookId
                    val cfiToSave = if (useLatest) latestState.currentCfi else cfi
                    val progressToSave = if (useLatest) latestState.progress else progress
                    val chapterToSave = (if (useLatest) latestState.currentChapterIndex else chapterIndex).coerceAtLeast(0)
                    val now = System.currentTimeMillis()
                    playbackStateDao?.saveReaderProgress(
                        bookId = targetBookId,
                        lastCfi = cfiToSave.ifBlank { null },
                        chapterIndex = chapterToSave,
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
        explicitChapterIndex: Int = -1,
        fallbackIndex: Int = 0
    ): Int {
        val total = maxOf(chapters.size, _uiState.value.extractedBook?.chapters?.size ?: 0)
        if (explicitChapterIndex >= 0 && (total == 0 || explicitChapterIndex < total)) {
            return explicitChapterIndex
        }
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

            PDF_PAGE_CFI_REGEX.find(cfi)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { pdfPage ->
                return if (total > 0) pdfPage.coerceIn(0, total - 1) else pdfPage.coerceAtLeast(0)
            }

            EPUB_SPINE_CFI_REGEX.find(cfi)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { spineStep ->
                val byChapterSpine = chapters.indexOfFirst { ch ->
                    val range = ch.cfiRange ?: return@indexOfFirst false
                    EPUB_SPINE_CFI_REGEX.find(range)?.groupValues?.getOrNull(1)?.toIntOrNull() == spineStep
                }
                if (byChapterSpine >= 0) return byChapterSpine
                val inferred = (spineStep - 2).coerceAtLeast(0) / 2
                return if (total > 0) inferred.coerceIn(0, total - 1) else inferred.coerceAtLeast(0)
            }
        }
        if (fallbackIndex > 0) {
            return if (total > 0) fallbackIndex.coerceIn(0, total - 1) else fallbackIndex
        }
        if (chapters.isNotEmpty() && progress > 0f) {
            return (progress * chapters.size).toInt().coerceIn(0, chapters.size - 1)
        }
        return if (chapters.isNotEmpty()) fallbackIndex.coerceIn(0, chapters.size - 1) else fallbackIndex.coerceAtLeast(0)
    }

    private fun cfiMatchesChapter(
        cfi: String?,
        expectedChapterIndex: Int,
        chapters: List<ChapterEntity>,
        bookFormat: String
    ): Boolean {
        if (cfi.isNullOrBlank()) return false
        val exactIndex = chapters.indexOfFirst { !it.cfiRange.isNullOrBlank() && it.cfiRange == cfi }
        if (exactIndex >= 0) return exactIndex == expectedChapterIndex

        val prefixMatch = chapters.mapIndexedNotNull { index, chapter ->
            val range = chapter.cfiRange?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val base = range.removeSuffix(")")
            if (cfi.startsWith("$base!") || cfi.startsWith("$base/") || cfi.startsWith("$range:")) {
                index to range.length
            } else {
                null
            }
        }.maxByOrNull { it.second }
        if (prefixMatch != null) return prefixMatch.first == expectedChapterIndex

        if (bookFormat.equals("PDF", ignoreCase = true) || cfi.startsWith("pdf:page:", ignoreCase = true)) {
            PDF_PAGE_CFI_REGEX.find(cfi)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { pdfPage ->
                val byPdfRange = chapters.indexOfLast { ch ->
                    val startPage = ch.cfiRange?.let { r ->
                        PDF_PAGE_CFI_REGEX.find(r)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    }
                    startPage != null && startPage <= pdfPage
                }
                return if (byPdfRange >= 0) {
                    byPdfRange == expectedChapterIndex
                } else {
                    pdfPage == expectedChapterIndex
                }
            }
        }

        EPUB_SPINE_CFI_REGEX.find(cfi)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { spineStep ->
            val byChapterSpine = chapters.indexOfFirst { ch ->
                val range = ch.cfiRange ?: return@indexOfFirst false
                EPUB_SPINE_CFI_REGEX.find(range)?.groupValues?.getOrNull(1)?.toIntOrNull() == spineStep
            }
            val resolved = if (byChapterSpine >= 0) {
                byChapterSpine
            } else {
                (spineStep - 2).coerceAtLeast(0) / 2
            }
            return resolved == expectedChapterIndex
        }

        return true
    }

    private fun deriveParagraphIndexOrNull(
        cfi: String,
        paragraphs: List<ReaderParagraph>
    ): Int? {
        if (cfi.isBlank() || paragraphs.isEmpty()) return null
        val exact = paragraphs.indexOfFirst { it.cfi == cfi }
        if (exact >= 0) return exact
        PDF_PARA_CFI_REGEX.find(cfi)?.groupValues?.getOrNull(2)?.toIntOrNull()?.let { pIdx ->
            return pIdx.coerceIn(0, paragraphs.lastIndex)
        }
        EPUB_PARA_CFI_REGEX.find(cfi)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { oneBasedPara ->
            return (oneBasedPara - 1).coerceIn(0, paragraphs.lastIndex)
        }
        return null
    }

    private fun deriveParagraphIndexFromCfi(
        cfi: String,
        paragraphs: List<ReaderParagraph>
    ): Int = deriveParagraphIndexOrNull(cfi, paragraphs) ?: 0

    override fun onCleared() {
        // Do NOT stop background narration on ViewModel teardown; Audio continues cleanly in PlaybackCoordinator.
        voicePreviewPlayer?.stop()
        flushProgress()
        super.onCleared()
    }

    override fun onParagraphsExtracted(chapterIndex: Int, paragraphsJson: String) {
        val state = _uiState.value
        val resolvedChapter = if (chapterIndex >= 0) chapterIndex else state.currentChapterIndex
        if (resolvedChapter != state.currentChapterIndex) return
        val sessionPlaying = sessionManager.sessionState.value.isPlaying &&
            sessionManager.isSessionActiveFor(state.bookId)
        if (state.isNarrationPlaying || sessionPlaying) return

        val isPdf = state.bookFormat.equals("PDF", ignoreCase = true) ||
            state.extractedBook?.format.equals("PDF", ignoreCase = true)
        val parsed = parseParagraphsJson(paragraphsJson, resolvedChapter, isPdf)
        if (parsed.isNotEmpty()) {
            val changed = parsed != state.paragraphs
            val derivedPara = deriveParagraphIndexFromCfi(state.currentCfi, parsed)
            _uiState.update {
                it.copy(
                    paragraphs = parsed,
                    activeParagraphIndex = if (changed) derivedPara else it.activeParagraphIndex.coerceIn(0, parsed.lastIndex)
                )
            }
            syncContextToSession(forceWhenDifferentBook = false)
            if (_uiState.value.isBilingualEnabled && (changed || state.bilingualTranslations.isEmpty())) {
                sessionManager.translateCurrentChapterPublic(fallbackDemoIfEmpty = false)
            }
        } else {
            val kotlinParagraphs = state.extractedBook?.let {
                sessionManager.buildParagraphsForChapter(it, resolvedChapter, state.bookFormat)
            }.orEmpty()
            if (kotlinParagraphs.isEmpty() && state.paragraphs.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        paragraphs = emptyList(),
                        activeParagraphIndex = 0,
                        bilingualTranslations = emptyMap()
                    )
                }
                syncContextToSession(forceWhenDifferentBook = false)
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

    override fun onPageTurned(cfi: String, fraction: Float, chapterIndex: Int, chapterTitle: String) {
        val state = _uiState.value
        val activeSession = sessionManager.isSessionActiveFor(state.bookId) &&
            (state.isNarrationPlaying || sessionManager.sessionState.value.isPlaying)
        // Guard against WebView smooth-scroll feedback overwrites during active follow-highlight narration.
        if (activeSession && state.followHighlight) return
        saveProgressInternal(cfi, fraction, explicitChapterIndex = chapterIndex)
    }

    override fun onPageTurned(cfi: String, fraction: Float, chapterTitle: String) {
        onPageTurned(cfi, fraction, chapterIndex = -1, chapterTitle = chapterTitle)
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
        private val EPUB_SPINE_CFI_REGEX = Regex("""/6/(\d+)""")
        private val EPUB_PARA_CFI_REGEX = Regex("""!/4/2/(\d+)(?::\d+)?""")
        private val PDF_PAGE_CFI_REGEX = Regex("""pdf:page:(\d+)""")
        private val PDF_PARA_CFI_REGEX = Regex("""pdf:page:(\d+):p:(\d+)""")
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
