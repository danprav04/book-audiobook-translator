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
import io.github.danprav04.polyglotbooks.core.data.db.NarrationLineEntity
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
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.core.playback.PlaybackCoordinator
import io.github.danprav04.polyglotbooks.feature.narration.CastingService
import io.github.danprav04.polyglotbooks.feature.narration.GeminiStudioVoices
import io.github.danprav04.polyglotbooks.feature.narration.VoiceCaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
import java.util.UUID
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
    val languageCode: String? = null,
    val speakerName: String? = null,
    val characterPersonality: String? = null,
    val storySummary: String? = null
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
    private var backgroundCastJob: Job? = null
    private var lastAutoScanTarget: Pair<String, Int>? = null
    private val saveMutex = Mutex()
    private val castScanMutex = Mutex()
    private val aiScannedChapterKeys = mutableSetOf<String>()
    private var lastSessionError: String? = null
    private var lastSessionStatus: String? = null
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var bookExtractor: ((String, String) -> EbookContentExtractor.ExtractedBook?)? = null

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
            applicationScope = viewModelScope,
            narrationDao = narrationDao
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

                var chapterAdvanceForScan: Pair<String, Int>? = null
                _uiState.update { state ->
                    chapterAdvanceForScan = null
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
                        if (session.isActiveSession && updatedChapterIndex != state.currentChapterIndex) {
                            chapterAdvanceForScan = bookId to updatedChapterIndex
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
                chapterAdvanceForScan?.let { (targetId, nextChapterIndex) ->
                    scheduleAutoCastScan(
                        targetBookId = targetId,
                        chapterIndex = nextChapterIndex,
                        includeLookahead = true
                    )
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
            backgroundCastJob?.cancel()
            lastAutoScanTarget = null
            aiScannedChapterKeys.clear()
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

                    val customExtractor = bookExtractor
                    val extracted = if (isReattaching && liveSessionSnapshot.extractedBook != null) {
                        liveSessionSnapshot.extractedBook
                    } else if (customExtractor != null) {
                        try {
                            withContext(ioDispatcher) {
                                customExtractor(book.filePath, book.format)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            DiagnosticLogBuffer.record(
                                tag = "ReaderVM",
                                message = "Ebook extraction failed format=${book.format}",
                                level = "WARN",
                                throwable = e
                            )
                            null
                        }
                    } else if (appContext != null && book.filePath.isNotBlank()) {
                        try {
                            withContext(ioDispatcher) {
                                EbookContentExtractor.extractBook(appContext, book.filePath, book.format)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            DiagnosticLogBuffer.record(
                                tag = "ReaderVM",
                                message = "Ebook extraction failed format=${book.format}",
                                level = "WARN",
                                throwable = e
                            )
                            null
                        }
                    } else {
                        null
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
                    if (initialParagraphs.isNotEmpty() || extracted?.chapters?.any { it.paragraphs.isNotEmpty() } == true) {
                        scheduleAutoCastScan(
                            targetBookId = targetBookId,
                            chapterIndex = clampedChapterIndex,
                            includeLookahead = true
                        )
                    }
                } else {
                    DiagnosticLogBuffer.record(
                        tag = "ReaderVM",
                        message = "Book not found id=$targetBookId",
                        level = "ERROR"
                    )
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
                DiagnosticLogBuffer.record(
                    tag = "ReaderVM",
                    message = "Failed to load book id=$targetBookId",
                    level = "ERROR",
                    throwable = e
                )
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
        if (bookId.isNotBlank()) {
            scheduleAutoCastScan(
                targetBookId = bookId,
                chapterIndex = clampedIndex,
                includeLookahead = true
            )
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
        scheduleAutoCastScan(
            targetBookId = state.bookId,
            chapterIndex = state.currentChapterIndex,
            includeLookahead = true
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
        backgroundCastJob?.cancel()
        castJob = viewModelScope.launch {
            _uiState.update { it.copy(isCastAnalyzing = true) }
            try {
                val snapshot = _uiState.value
                analyzeChapterCastInternal(
                    targetBookId = snapshot.bookId.ifBlank { bookId },
                    chapterIndex = snapshot.currentChapterIndex,
                    force = true,
                    updateUiSpinner = true,
                    reportErrors = true
                )
            } finally {
                _uiState.update { it.copy(isCastAnalyzing = false) }
            }
        }
    }

    fun reanalyzeCastForCurrentChapter() {
        analyzeCastForCurrentChapter()
    }

    private fun scheduleAutoCastScan(
        targetBookId: String = bookId,
        chapterIndex: Int = _uiState.value.currentChapterIndex,
        includeLookahead: Boolean = true
    ) {
        if (narrationDao == null || textModelClient == null || targetBookId.isBlank() || chapterIndex < 0) return
        val target = targetBookId to chapterIndex
        if (backgroundCastJob?.isActive == true && lastAutoScanTarget == target) return
        if (lastAutoScanTarget != target) {
            backgroundCastJob?.cancel()
        }
        lastAutoScanTarget = target
        backgroundCastJob = viewModelScope.launch {
            try {
                val apiKey = resolveApiKey()
                if (apiKey.isNullOrBlank()) return@launch
                analyzeChapterCastInternal(
                    targetBookId = targetBookId,
                    chapterIndex = chapterIndex,
                    force = false,
                    updateUiSpinner = false,
                    reportErrors = false
                )
                if (includeLookahead) {
                    val state = _uiState.value
                    if (state.bookId != targetBookId) return@launch
                    val totalChapters = maxOf(state.chapters.size, state.extractedBook?.chapters?.size ?: 0)
                    val nextIndex = chapterIndex + 1
                    if (totalChapters > 0 && nextIndex < totalChapters) {
                        analyzeChapterCastInternal(
                            targetBookId = targetBookId,
                            chapterIndex = nextIndex,
                            force = false,
                            updateUiSpinner = false,
                            reportErrors = false
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                DiagnosticLogBuffer.record(
                    tag = "ReaderVM",
                    message = "Background cast scan failed",
                    level = "WARN",
                    throwable = t
                )
            }
        }
    }

    private fun resolveChapterParagraphs(
        state: ReaderUiState,
        chapterIndex: Int
    ): List<ReaderParagraph> {
        if (chapterIndex == state.currentChapterIndex && state.paragraphs.isNotEmpty()) {
            return state.paragraphs.filter { it.text.isNotBlank() }
        }
        val extracted = state.extractedBook
        if (extracted != null) {
            return sessionManager.buildParagraphsForChapter(extracted, chapterIndex, state.bookFormat)
        }
        return emptyList()
    }

    private suspend fun ensureChapterEntityId(
        targetBookId: String,
        state: ReaderUiState,
        chapterIndex: Int
    ): String {
        state.chapters.getOrNull(chapterIndex)?.id?.takeIf { it.isNotBlank() }?.let { cid ->
            if (chapterDao.getChapterById(cid) != null) return cid
        }
        chapterDao.getChapterByIndex(targetBookId, chapterIndex)?.id?.takeIf { it.isNotBlank() }?.let { return it }
        val extractedChapter = state.extractedBook?.chapters?.getOrNull(chapterIndex)
        val isPdf = state.bookFormat.equals("PDF", ignoreCase = true) ||
            state.extractedBook?.format.equals("PDF", ignoreCase = true)
        val fallbackId = state.chapters.getOrNull(chapterIndex)?.id?.takeIf { it.isNotBlank() }
            ?: "${targetBookId}_chap_$chapterIndex"
        val bookExists = bookDao.getBookById(targetBookId) != null
        if (bookExists && extractedChapter != null) {
            val entity = ChapterEntity(
                id = fallbackId,
                bookId = targetBookId,
                index = chapterIndex,
                title = extractedChapter.title.ifBlank {
                    if (isPdf) "Page ${chapterIndex + 1}" else "Chapter ${chapterIndex + 1}"
                },
                startMs = chapterIndex * 1000L,
                endMs = (chapterIndex + 1) * 1000L,
                cfiRange = sessionManager.defaultChapterCfi(chapterIndex, isPdf),
                textLength = extractedChapter.paragraphs.sumOf { it.length }
            )
            chapterDao.insertChapter(entity)
            return entity.id
        }
        return fallbackId
    }

    private suspend fun analyzeChapterCastInternal(
        targetBookId: String,
        chapterIndex: Int,
        force: Boolean,
        updateUiSpinner: Boolean,
        reportErrors: Boolean
    ) {
        if (targetBookId.isBlank() || chapterIndex < 0) return
        castScanMutex.withLock {
            if (targetBookId != bookId) return
            val state = _uiState.value
            val chapterParagraphs = resolveChapterParagraphs(state, chapterIndex)
            if (chapterParagraphs.isEmpty() && !force) {
                return
            }

            val chapterId = ensureChapterEntityId(targetBookId, state, chapterIndex)
            val scanKey = "$targetBookId::$chapterId"
            val loadedContext = sessionManager.getBookNarrationContext(targetBookId)
            if (!force) {
                if (scanKey in aiScannedChapterKeys) return
                val existingLines = try {
                    narrationDao?.getLinesForChapterOnce(chapterId).orEmpty()
                } catch (_: Throwable) {
                    emptyList()
                }
                val needsContextUpgrade = sessionManager.hasDiskCache() &&
                    chapterIndex !in loadedContext.chapterSummaries &&
                    existingLines.none { !it.style.isNullOrBlank() }
                if (existingLines.isNotEmpty() && !needsContextUpgrade) {
                    aiScannedChapterKeys.add(scanKey)
                    return
                }
            }

            try {
                val existingEntities = (characterDao?.getCharactersForBook(targetBookId)?.firstOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: state.characters)
                    .filter { it.bookId == targetBookId || it.bookId.isBlank() }

                val existingNarrator = existingEntities.firstOrNull { isNarratorEntity(it) }
                val workingCharacters = existingEntities.map { it.toDomain() }.toMutableList()
                val workingPersonalities = loadedContext.characterPersonalities.toMutableMap()
                var rollingSummary = loadedContext.chapterSummaries[chapterIndex - 1]
                    ?: loadedContext.chapterSummaries.entries
                        .filter { it.key < chapterIndex }
                        .maxByOrNull { it.key }
                        ?.value
                    ?: loadedContext.storySummary
                val apiKey = resolveApiKey()
                val castingService = CastingService()

                val aiLines = mutableListOf<NarrationLineEntity>()
                var anyBatchSucceeded = false
                var allBatchesSucceeded = false
                val chapterTitle = state.chapters.getOrNull(chapterIndex)?.title
                    ?: state.extractedBook?.chapters?.getOrNull(chapterIndex)?.title

                if (textModelClient != null && !apiKey.isNullOrBlank() && chapterParagraphs.isNotEmpty()) {
                    val batches = partitionIntoCastingBatches(chapterParagraphs)
                    val model = resolveModel(ModelRole.TEXT_ANALYSIS)
                    var rollingContext = if (chapterIndex > 0) {
                        resolveChapterParagraphs(state, chapterIndex - 1).takeLast(2).map { it.text }
                    } else {
                        emptyList()
                    }
                    allBatchesSucceeded = true
                    for (batch in batches) {
                        val batchTexts = batch.map { it.text }
                        try {
                            val prompt = castingService.buildCastingPrompt(
                                paragraphs = batchTexts,
                                existingCharacters = workingCharacters,
                                previousContextParagraphs = rollingContext,
                                chapterTitle = chapterTitle,
                                previousStorySummary = rollingSummary,
                                knownPersonalities = workingPersonalities
                            )
                            val responseJson = withContext(ioDispatcher) {
                                textModelClient.generateContent(
                                    apiKey = apiKey,
                                    prompt = prompt,
                                    systemInstruction = CastingService.CASTING_SYSTEM_INSTRUCTION,
                                    responseJsonSchema = castingService.getPromptJsonSchema(),
                                    model = model
                                )
                            }
                            val result = castingService.parseAndValidateCasting(
                                jsonResponse = responseJson,
                                paragraphs = batchTexts,
                                existingCharacters = workingCharacters,
                                bookId = targetBookId,
                                chapterId = chapterId,
                                previousStorySummary = rollingSummary,
                                knownPersonalities = workingPersonalities
                            )
                            val rawIdToSavedId = mutableMapOf<String, String>()
                            val usedIdsLower = workingCharacters.map { it.id.lowercase() }.toMutableSet()

                            for (candidate in result.characters) {
                                if (isNarratorDescriptor(candidate.id, candidate.name)) {
                                    val canonNarratorId = existingNarrator?.id ?: "${targetBookId}_narrator"
                                    rawIdToSavedId[candidate.id] = "narrator"
                                    val idx = workingCharacters.indexOfFirst { isNarratorDescriptor(it.id, it.name) }
                                    if (idx >= 0) {
                                        val prev = workingCharacters[idx]
                                        workingCharacters[idx] = prev.copy(
                                            bookId = targetBookId,
                                            gender = pickNonUnknownField(prev.gender, candidate.gender),
                                            age = pickNonUnknownField(prev.age, candidate.age),
                                            voiceTraits = prev.voiceTraits.ifBlank { candidate.voiceTraits }
                                        )
                                    } else {
                                        workingCharacters.add(0, candidate.copy(id = canonNarratorId, bookId = targetBookId))
                                        usedIdsLower.add(canonNarratorId.lowercase())
                                    }
                                    continue
                                }

                                val existingIdx = workingCharacters.indexOfFirst {
                                    it.id.equals(candidate.id, ignoreCase = true) ||
                                        it.name.equals(candidate.name, ignoreCase = true) ||
                                        it.aliases.any { a -> a.equals(candidate.name, ignoreCase = true) } ||
                                        candidate.aliases.any { ca ->
                                            ca.equals(it.name, ignoreCase = true) ||
                                                it.aliases.any { ia -> ia.equals(ca, ignoreCase = true) }
                                        }
                                }
                                if (existingIdx >= 0) {
                                    val prev = workingCharacters[existingIdx]
                                    val extraAlias = if (!candidate.name.equals(prev.name, ignoreCase = true)) {
                                        listOf(candidate.name)
                                    } else {
                                        emptyList()
                                    }
                                    val updated = prev.copy(
                                        bookId = targetBookId,
                                        aliases = mergeAliasLists(prev.name, prev.aliases, extraAlias + candidate.aliases),
                                        gender = pickNonUnknownField(prev.gender, candidate.gender),
                                        age = pickNonUnknownField(prev.age, candidate.age),
                                        voiceTraits = prev.voiceTraits.ifBlank { candidate.voiceTraits },
                                        assignedVoiceId = prev.assignedVoiceId ?: candidate.assignedVoiceId
                                    )
                                    workingCharacters[existingIdx] = updated
                                    rawIdToSavedId[candidate.id] = prev.id
                                    usedIdsLower.add(prev.id.lowercase())
                                } else {
                                    val baseId = if (candidate.id.startsWith("${targetBookId}_")) {
                                        candidate.id
                                    } else {
                                        "${targetBookId}_${candidate.id}"
                                    }
                                    var savedId = baseId
                                    var suffix = 2
                                    while (savedId.lowercase() in usedIdsLower) {
                                        savedId = "${baseId}_$suffix"
                                        suffix++
                                    }
                                    usedIdsLower.add(savedId.lowercase())
                                    rawIdToSavedId[candidate.id] = savedId
                                    workingCharacters.add(
                                        candidate.copy(
                                            id = savedId,
                                            bookId = targetBookId
                                        )
                                    )
                                }
                            }

                            for (line in result.lines) {
                                val origPara = batch.getOrNull(line.paraIndex) ?: continue
                                val mappedSpeaker = if (isNarratorSpeakerId(line.speakerId)) {
                                    "narrator"
                                } else {
                                    rawIdToSavedId[line.speakerId] ?: line.speakerId
                                }
                                aiLines.add(
                                    NarrationLineEntity(
                                        id = line.id.ifBlank { UUID.randomUUID().toString() },
                                        chapterId = chapterId,
                                        paraIndex = origPara.index,
                                        startOffset = line.startOffset,
                                        endOffset = line.endOffset,
                                        speakerId = mappedSpeaker,
                                        style = line.style,
                                        cfi = origPara.cfi
                                    )
                                )
                            }
                            for ((rawCharId, personalityDesc) in result.characterPersonalities) {
                                val mappedCharId = if (isNarratorSpeakerId(rawCharId)) {
                                    "narrator"
                                } else {
                                    rawIdToSavedId[rawCharId] ?: rawCharId
                                }
                                val cleanDesc = personalityDesc.trim()
                                if (cleanDesc.isNotEmpty()) {
                                    workingPersonalities[mappedCharId] = cleanDesc
                                }
                            }
                            if (result.storySummary.isNotBlank()) {
                                rollingSummary = result.storySummary
                            }
                            anyBatchSucceeded = true
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            allBatchesSucceeded = false
                            DiagnosticLogBuffer.record(
                                tag = "ReaderVM",
                                message = "AI cast analysis failed for ch=$chapterIndex; falling back cleanly",
                                level = "WARN",
                                throwable = e
                            )
                        }
                        rollingContext = batch.takeLast(3).map { it.text }
                    }
                }

                if (!anyBatchSucceeded && !force) {
                    return
                }

                var finalLines = aiLines.toList()
                if (!anyBatchSucceeded && chapterParagraphs.isNotEmpty()) {
                    val (heuristicChars, heuristicLines) = detectSpeakersHeuristicallyWithLines(
                        paragraphs = chapterParagraphs,
                        targetBookId = targetBookId,
                        chapterId = chapterId,
                        existingCharacters = workingCharacters
                    )
                    val existingIds = workingCharacters.map { it.id.lowercase() }.toMutableSet()
                    for (candidate in heuristicChars) {
                        val existingIdx = workingCharacters.indexOfFirst {
                            it.id.equals(candidate.id, ignoreCase = true) ||
                                it.name.equals(candidate.name, ignoreCase = true) ||
                                it.aliases.any { a -> a.equals(candidate.name, ignoreCase = true) }
                        }
                        if (existingIdx < 0) {
                            val prefixedId = if (candidate.id.startsWith("${targetBookId}_")) {
                                candidate.id
                            } else {
                                "${targetBookId}_${candidate.id}"
                            }
                            if (existingIds.add(prefixedId.lowercase())) {
                                workingCharacters.add(candidate.copy(id = prefixedId, bookId = targetBookId))
                            }
                        }
                    }
                    finalLines = heuristicLines
                }

                val hasNarrator = workingCharacters.any { isNarratorDescriptor(it.id, it.name) }
                if (!hasNarrator) {
                    workingCharacters.add(
                        0,
                        Character(
                            id = "${targetBookId}_narrator",
                            bookId = targetBookId,
                            name = "Narrator",
                            gender = "neutral",
                            age = "mature",
                            voiceTraits = "clear, storytelling"
                        )
                    )
                }

                val narratorVoice = _uiState.value.defaultNarratorVoiceId.ifBlank {
                    GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID
                }
                val caster = VoiceCaster(defaultNarratorVoiceId = narratorVoice)
                val usedVoices = workingCharacters.mapNotNull { it.assignedVoiceId?.takeIf { v -> v.isNotBlank() } }.toMutableSet()
                val autoAssigned = caster.autoAssignVoices(workingCharacters)
                val availablePool = GeminiStudioVoices.catalog.map { it.id }.toMutableList()
                availablePool.removeAll(usedVoices)

                val finalEntities = workingCharacters.map { ch ->
                    val existingVoice = ch.assignedVoiceId?.takeIf { it.isNotBlank() }
                    val voice = if (existingVoice != null) {
                        existingVoice
                    } else if (isNarratorDescriptor(ch.id, ch.name)) {
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
                    CharacterEntity.fromDomain(ch.copy(bookId = targetBookId, assignedVoiceId = voice))
                }

                characterDao?.insertCharacters(finalEntities)
                if (finalLines.isNotEmpty() && narrationDao != null && chapterDao.getChapterById(chapterId) != null) {
                    narrationDao.deleteLinesForChapter(chapterId)
                    narrationDao.insertLines(finalLines)
                }
                if (anyBatchSucceeded) {
                    val updatedChapterSummaries = loadedContext.chapterSummaries.toMutableMap()
                    if (rollingSummary.isNotBlank()) {
                        updatedChapterSummaries[chapterIndex] = rollingSummary
                    }
                    sessionManager.saveBookNarrationContext(
                        bookId = targetBookId,
                        context = TranslationDiskCache.BookNarrationContext(
                            storySummary = rollingSummary.ifBlank { loadedContext.storySummary },
                            chapterSummaries = updatedChapterSummaries,
                            characterPersonalities = workingPersonalities.toMap()
                        )
                    )
                }
                if (anyBatchSucceeded && allBatchesSucceeded) {
                    aiScannedChapterKeys.add(scanKey)
                }
                sessionManager.invalidateNarrationLines(targetBookId, chapterId)

                if (targetBookId == bookId) {
                    _uiState.update { cur ->
                        cur.copy(
                            characters = finalEntities,
                            isCastAnalyzing = if (updateUiSpinner) false else cur.isCastAnalyzing
                        )
                    }
                    syncContextToSession(forceWhenDifferentBook = false)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                DiagnosticLogBuffer.record(
                    tag = "ReaderVM",
                    message = "Cast analysis pipeline failed",
                    level = "ERROR",
                    throwable = e
                )
                if (reportErrors && targetBookId == bookId) {
                    _uiState.update {
                        it.copy(
                            isCastAnalyzing = false,
                            errorMessage = sessionManager.formatConciseError(e, "Failed to analyze character cast")
                        )
                    }
                }
            }
        }
    }

    fun addCharacter(
        name: String,
        aliases: String = "",
        gender: String = "unknown",
        age: String = "unknown",
        voiceTraits: String = "",
        assignedVoiceId: String? = null
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val currentBookId = bookId.ifBlank { return }
        val currentChars = _uiState.value.characters
        if (cleanName.equals("Narrator", ignoreCase = true)) {
            val existingNarrator = currentChars.firstOrNull { isNarratorEntity(it) }
            if (existingNarrator != null) {
                updateCharacterDetails(
                    character = existingNarrator,
                    name = "Narrator",
                    aliases = "",
                    gender = gender,
                    age = age,
                    voiceTraits = voiceTraits,
                    assignedVoiceId = assignedVoiceId
                )
                return
            }
        }

        val existingIds = currentChars.map { it.id.lowercase() }.toSet()
        val baseSlug = cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "char" }
        var candidateId = "${currentBookId}_$baseSlug"
        var suffix = 2
        while (candidateId.lowercase() in existingIds) {
            candidateId = "${currentBookId}_${baseSlug}_$suffix"
            suffix++
        }

        val normalizedGender = gender.trim().ifBlank { "unknown" }
        val normalizedAge = age.trim().ifBlank { "unknown" }
        val usedVoices = currentChars.mapNotNull { it.assignedVoiceId?.takeIf { v -> v.isNotBlank() } }.toSet()
        val resolvedVoice = assignedVoiceId?.trim()?.takeIf { it.isNotBlank() }?.let {
            GeminiStudioVoices.getVoice(it)?.id ?: it
        } ?: GeminiStudioVoices.catalog.firstOrNull { voice ->
            voice.id !in usedVoices &&
                (normalizedGender.equals("unknown", true) || voice.gender.equals(normalizedGender, true)) &&
                (normalizedAge.equals("unknown", true) || voice.age.equals(normalizedAge, true))
        }?.id ?: GeminiStudioVoices.catalog.firstOrNull { voice ->
            voice.id !in usedVoices &&
                (normalizedGender.equals("unknown", true) || voice.gender.equals(normalizedGender, true))
        }?.id ?: GeminiStudioVoices.catalog.firstOrNull { it.id !in usedVoices }?.id
            ?: GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID

        val created = CharacterEntity(
            id = candidateId,
            bookId = currentBookId,
            name = cleanName,
            aliases = normalizeAliasesString(aliases, cleanName),
            gender = normalizedGender,
            age = normalizedAge,
            voiceTraits = voiceTraits.trim(),
            assignedVoiceId = resolvedVoice
        )
        _uiState.update { state ->
            state.copy(characters = state.characters + created)
        }
        syncContextToSession(forceWhenDifferentBook = false)
        viewModelScope.launch(NonCancellable) {
            try {
                characterDao?.insertCharacter(created)
            } catch (_: Throwable) {
            }
        }
    }

    fun updateCharacterDetails(
        character: CharacterEntity,
        name: String,
        aliases: String,
        gender: String,
        age: String,
        voiceTraits: String,
        assignedVoiceId: String?
    ) {
        val isNarrator = isNarratorEntity(character)
        val cleanName = if (isNarrator) "Narrator" else name.trim().ifBlank { character.name }
        val resolvedVoice = assignedVoiceId?.trim()?.takeIf { it.isNotBlank() }?.let {
            GeminiStudioVoices.getVoice(it)?.id ?: it
        } ?: character.assignedVoiceId
        val updated = character.copy(
            name = cleanName,
            aliases = if (isNarrator) "" else normalizeAliasesString(aliases, cleanName),
            gender = gender.trim().ifBlank { "unknown" },
            age = age.trim().ifBlank { "unknown" },
            voiceTraits = voiceTraits.trim(),
            assignedVoiceId = resolvedVoice
        )
        _uiState.update { state ->
            state.copy(
                characters = state.characters.map { if (it.id == updated.id) updated else it }
            )
        }
        syncContextToSession(forceWhenDifferentBook = false)
        viewModelScope.launch(NonCancellable) {
            try {
                characterDao?.updateCharacter(updated)
            } catch (_: Throwable) {
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
        viewModelScope.launch(NonCancellable) {
            try {
                characterDao?.updateCharacter(updated)
            } catch (_: Throwable) {
            }
        }
    }

    fun deleteCharacter(character: CharacterEntity) {
        if (isNarratorEntity(character)) return
        val targetBookId = character.bookId.ifBlank { bookId }
        val remainingIds = _uiState.value.characters
            .filterNot { it.id == character.id }
            .map { it.id.lowercase() }
            .toSet()

        _uiState.update { state ->
            state.copy(characters = state.characters.filterNot { it.id == character.id })
        }
        sessionManager.invalidateNarrationLines(targetBookId)
        syncContextToSession(forceWhenDifferentBook = false)
        viewModelScope.launch(NonCancellable) {
            try {
                if (targetBookId.isNotBlank()) {
                    narrationDao?.reassignSpeakerForBook(
                        bookId = targetBookId,
                        oldSpeakerId = character.id,
                        newSpeakerId = "narrator"
                    )
                    val unprefixed = character.id.removePrefix("${targetBookId}_")
                    if (unprefixed != character.id && unprefixed.isNotBlank() && unprefixed.lowercase() !in remainingIds) {
                        narrationDao?.reassignSpeakerForBook(
                            bookId = targetBookId,
                            oldSpeakerId = unprefixed,
                            newSpeakerId = "narrator"
                        )
                    }
                }
                characterDao?.deleteCharacter(character)
                sessionManager.invalidateNarrationLines(targetBookId)
            } catch (_: Throwable) {
            }
        }
    }

    fun mergeCharacters(source: CharacterEntity, target: CharacterEntity) {
        if (source.id == target.id || isNarratorEntity(source)) return
        val targetBookId = target.bookId.ifBlank { source.bookId }.ifBlank { bookId }
        val mergingIntoNarrator = isNarratorEntity(target)
        val mergedTarget = if (mergingIntoNarrator) {
            target
        } else {
            val combinedAliases = mergeAliasLists(
                canonicalName = target.name,
                first = parseAliasesList(target.aliases),
                second = listOf(source.name) + parseAliasesList(source.aliases)
            )
            target.copy(
                aliases = combinedAliases.joinToString(", "),
                gender = pickNonUnknownField(target.gender, source.gender),
                age = pickNonUnknownField(target.age, source.age),
                voiceTraits = target.voiceTraits.ifBlank { source.voiceTraits },
                assignedVoiceId = target.assignedVoiceId?.takeIf { it.isNotBlank() } ?: source.assignedVoiceId
            )
        }
        val remainingIds = _uiState.value.characters
            .filterNot { it.id == source.id }
            .map { it.id.lowercase() }
            .toSet()

        _uiState.update { state ->
            state.copy(
                characters = state.characters
                    .filterNot { it.id == source.id }
                    .map { if (it.id == mergedTarget.id) mergedTarget else it }
            )
        }
        sessionManager.invalidateNarrationLines(targetBookId)
        syncContextToSession(forceWhenDifferentBook = false)
        val newSpeakerId = if (mergingIntoNarrator) "narrator" else mergedTarget.id
        viewModelScope.launch(NonCancellable) {
            try {
                if (!mergingIntoNarrator) {
                    characterDao?.updateCharacter(mergedTarget)
                }
                if (targetBookId.isNotBlank()) {
                    narrationDao?.reassignSpeakerForBook(
                        bookId = targetBookId,
                        oldSpeakerId = source.id,
                        newSpeakerId = newSpeakerId
                    )
                    val unprefixed = source.id.removePrefix("${targetBookId}_")
                    if (unprefixed != source.id && unprefixed.isNotBlank() && unprefixed.lowercase() !in remainingIds) {
                        narrationDao?.reassignSpeakerForBook(
                            bookId = targetBookId,
                            oldSpeakerId = unprefixed,
                            newSpeakerId = newSpeakerId
                        )
                    }
                }
                characterDao?.deleteCharacter(source)
                sessionManager.invalidateNarrationLines(targetBookId)
            } catch (_: Throwable) {
            }
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
        var chapterDidChange = false
        var chapterChangedWhileNotPlaying = false
        _uiState.update { state ->
            chapterDidChange = false
            val chapterIndex = resolveChapterIndex(
                chapters = state.chapters,
                cfi = cfi,
                progress = clampedProgress,
                explicitChapterIndex = explicitChapterIndex,
                fallbackIndex = state.currentChapterIndex
            )
            resolvedChapter = chapterIndex
            val chapterChanged = chapterIndex != state.currentChapterIndex
            chapterDidChange = chapterChanged
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
            if (chapterDidChange) {
                scheduleAutoCastScan(
                    targetBookId = targetBookId,
                    chapterIndex = resolvedChapter,
                    includeLookahead = true
                )
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
            val wasEmpty = state.paragraphs.isEmpty()
            val changed = parsed != state.paragraphs
            val derivedPara = deriveParagraphIndexFromCfi(state.currentCfi, parsed)
            _uiState.update {
                it.copy(
                    paragraphs = parsed,
                    activeParagraphIndex = if (changed) derivedPara else it.activeParagraphIndex.coerceIn(0, parsed.lastIndex)
                )
            }
            syncContextToSession(forceWhenDifferentBook = false)
            if (wasEmpty && backgroundCastJob?.isActive == true && lastAutoScanTarget == (state.bookId to resolvedChapter)) {
                backgroundCastJob?.cancel()
            }
            scheduleAutoCastScan(
                targetBookId = state.bookId,
                chapterIndex = resolvedChapter,
                includeLookahead = true
            )
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
            DiagnosticLogBuffer.record(
                tag = "ReaderWebView",
                message = message,
                level = "ERROR"
            )
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

    private fun detectSpeakersHeuristicallyWithLines(
        paragraphs: List<ReaderParagraph>,
        targetBookId: String,
        chapterId: String,
        existingCharacters: List<Character>
    ): Pair<List<Character>, List<NarrationLineEntity>> {
        val discoveredByKey = linkedMapOf<String, Character>()
        val existingTermMap = mutableMapOf<String, Character>()
        existingCharacters
            .filterNot { isNarratorDescriptor(it.id, it.name) }
            .forEach { existing ->
                val cleanName = existing.name.trim()
                if (cleanName.isNotEmpty()) {
                    existingTermMap.putIfAbsent(cleanName.lowercase(), existing)
                }
                existing.aliases.forEach { alias ->
                    val cleanAlias = alias.trim()
                    if (cleanAlias.isNotEmpty()) {
                        existingTermMap.putIfAbsent(cleanAlias.lowercase(), existing)
                    }
                }
            }

        fun resolveOrCreateSpeaker(rawName: String, traits: String): Character? {
            val cleanName = rawName.trim()
            if (cleanName.isEmpty() || cleanName in STOPWORD_SPEAKERS) return null
            val key = cleanName.lowercase()
            existingTermMap[key]?.let { existing ->
                discoveredByKey.putIfAbsent(existing.id.lowercase(), existing)
                return existing
            }
            discoveredByKey[key]?.let { return it }
            val slug = cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "char" }
            val candidateId = "${targetBookId}_$slug"
            val created = Character(
                id = candidateId,
                bookId = targetBookId,
                name = cleanName,
                voiceTraits = traits
            )
            discoveredByKey[key] = created
            discoveredByKey.putIfAbsent(candidateId.lowercase(), created)
            return created
        }

        val lines = mutableListOf<NarrationLineEntity>()
        for (para in paragraphs) {
            val text = para.text
            if (text.isBlank()) continue
            var detectedSpeaker: Character? = null
            var detectedStyle: String? = null

            val colonName = COLON_SPEAKER_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
            if (!colonName.isNullOrBlank()) {
                detectedSpeaker = resolveOrCreateSpeaker(colonName, "conversational")
            }

            AFTER_QUOTE_SPEAKER_REGEX.find(text)?.let { match ->
                val name = match.groupValues.getOrNull(2)?.trim().orEmpty()
                val verb = match.groupValues.getOrNull(1)?.trim().orEmpty()
                val char = resolveOrCreateSpeaker(name, "expressive")
                if (char != null && detectedSpeaker == null) {
                    detectedSpeaker = char
                    detectedStyle = verbToDeliveryStyle(verb)
                }
            }

            BEFORE_QUOTE_SPEAKER_REGEX.find(text)?.let { match ->
                val name = match.groupValues.getOrNull(1)?.trim().orEmpty()
                val verb = match.groupValues.getOrNull(2)?.trim().orEmpty()
                val char = resolveOrCreateSpeaker(name, "expressive")
                if (char != null && detectedSpeaker == null) {
                    detectedSpeaker = char
                    detectedStyle = verbToDeliveryStyle(verb)
                }
            }

            if (detectedSpeaker == null) {
                ACTION_BEAT_BEFORE_QUOTE_REGEX.find(text)?.let { match ->
                    val name = match.groupValues.getOrNull(1)?.trim().orEmpty()
                    detectedSpeaker = resolveOrCreateSpeaker(name, "expressive")
                }
            }

            lines.add(
                NarrationLineEntity(
                    id = UUID.randomUUID().toString(),
                    chapterId = chapterId,
                    paraIndex = para.index,
                    startOffset = 0,
                    endOffset = text.length,
                    speakerId = detectedSpeaker?.id ?: "narrator",
                    style = detectedStyle,
                    cfi = para.cfi
                )
            )
        }

        val uniqueCharacters = discoveredByKey.values.distinctBy { it.id }
        return uniqueCharacters to lines
    }

    private fun verbToDeliveryStyle(verb: String): String? = when (verb.lowercase()) {
        "whispered" -> "whispering"
        "murmured", "muttered" -> "quiet, intense"
        "shouted", "yelled", "screamed", "cried" -> "urgent, raised voice"
        "sighed" -> "sighing"
        "gasped" -> "breathless"
        "laughed" -> "amused"
        "hissed", "snapped", "growled" -> "sharp, tense"
        else -> null
    }

    private fun partitionIntoCastingBatches(
        paragraphs: List<ReaderParagraph>,
        maxParagraphs: Int = 35,
        maxChars: Int = 12_000
    ): List<List<ReaderParagraph>> {
        if (paragraphs.isEmpty()) return emptyList()
        val batches = mutableListOf<List<ReaderParagraph>>()
        var current = mutableListOf<ReaderParagraph>()
        var currentChars = 0
        for (para in paragraphs) {
            val len = para.text.length
            if (current.isNotEmpty() && (current.size >= maxParagraphs || currentChars + len > maxChars)) {
                batches.add(current)
                current = mutableListOf()
                currentChars = 0
            }
            current.add(para)
            currentChars += len
        }
        if (current.isNotEmpty()) {
            batches.add(current)
        }
        return batches
    }

    private fun isNarratorDescriptor(id: String, name: String): Boolean {
        val cleanId = id.trim()
        val cleanName = name.trim()
        return cleanId.equals("narrator", ignoreCase = true) ||
            cleanId.endsWith("_narrator", ignoreCase = true) ||
            cleanName.equals("Narrator", ignoreCase = true)
    }

    private fun isNarratorEntity(character: CharacterEntity): Boolean =
        isNarratorDescriptor(character.id, character.name)

    private fun isNarratorSpeakerId(speakerId: String): Boolean {
        val clean = speakerId.trim()
        return clean.equals("narrator", ignoreCase = true) ||
            clean.endsWith("_narrator", ignoreCase = true)
    }

    private fun parseAliasesList(aliases: String): List<String> {
        if (aliases.isBlank()) return emptyList()
        return aliases.split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }

    private fun normalizeAliasesString(raw: String, excludeName: String): String {
        return parseAliasesList(raw)
            .filterNot { it.equals(excludeName.trim(), ignoreCase = true) }
            .joinToString(", ")
    }

    private fun mergeAliasLists(
        canonicalName: String,
        first: List<String>,
        second: List<String>
    ): List<String> {
        return (first + second)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(canonicalName.trim(), ignoreCase = true) }
            .distinctBy { it.lowercase() }
    }

    private fun pickNonUnknownField(primary: String?, secondary: String?): String {
        val first = primary?.trim().orEmpty()
        if (first.isNotEmpty() && !first.equals("unknown", ignoreCase = true)) return first
        val second = secondary?.trim().orEmpty()
        if (second.isNotEmpty() && !second.equals("unknown", ignoreCase = true)) return second
        return first.ifEmpty { second.ifEmpty { "unknown" } }
    }

    companion object {
        private val EPUB_SPINE_CFI_REGEX = Regex("""/6/(\d+)""")
        private val EPUB_PARA_CFI_REGEX = Regex("""!/4/2/(\d+)(?::\d+)?""")
        private val PDF_PAGE_CFI_REGEX = Regex("""pdf:page:(\d+)""")
        private val PDF_PARA_CFI_REGEX = Regex("""pdf:page:(\d+):p:(\d+)""")
        private val COLON_SPEAKER_REGEX = Regex("""^([A-Z][a-zA-Z]{1,24}(?:\s+[A-Z][a-zA-Z]{1,20})?):\s+\S""")
        private const val REPORTING_VERBS =
            "said|asked|replied|whispered|shouted|murmured|exclaimed|answered|cried|added|noted|observed|muttered|called|sighed|gasped|laughed|hissed|snapped|growled|warned|begged|pleaded|countered|responded|yelled|screamed"
        private val AFTER_QUOTE_SPEAKER_REGEX = Regex(
            """["“”«»「」『』][^"“”«»「」『』]{2,}["“”«»「」『』]\s*,?\s*($REPORTING_VERBS)\s+([A-Z][a-zA-Z]{1,24})\b"""
        )
        private val BEFORE_QUOTE_SPEAKER_REGEX = Regex(
            """\b([A-Z][a-zA-Z]{1,24})\s+($REPORTING_VERBS)\s*[,:]\s*["“«「『]"""
        )
        private val ACTION_BEAT_BEFORE_QUOTE_REGEX = Regex(
            """^([A-Z][a-zA-Z]{1,24})\s+[^"“”«»「」『』]{2,40}[.!]\s+["“«「『]"""
        )
        private val STOPWORD_SPEAKERS = setOf(
            "The", "And", "But", "She", "He", "They", "It", "We", "You", "Chapter", "Page",
            "Narrator", "This", "That", "There", "Then", "Now", "Later", "Meanwhile",
            "Suddenly", "Finally", "When", "While", "What", "Why", "How", "Where", "Who",
            "Look", "Wait", "Please", "Everyone", "Someone", "Nobody", "After", "Before",
            "Yes", "No", "Well", "Oh", "Ah", "However", "Instead", "Still", "Perhaps"
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
