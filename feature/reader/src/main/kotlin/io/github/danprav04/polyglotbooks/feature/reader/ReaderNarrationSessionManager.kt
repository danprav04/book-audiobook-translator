package io.github.danprav04.polyglotbooks.feature.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danprav04.polyglotbooks.core.audio.AudioTrackPcmPlayer
import io.github.danprav04.polyglotbooks.core.audio.PcmPlayer
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.db.CharacterDao
import io.github.danprav04.polyglotbooks.core.data.db.CharacterEntity
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateDao
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.InteractionsTtsClient
import io.github.danprav04.polyglotbooks.core.gemini.LiveSessionFactory
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalogService
import io.github.danprav04.polyglotbooks.core.gemini.QuotaExhaustedException
import io.github.danprav04.polyglotbooks.core.gemini.TextModelClient
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.core.playback.ApplicationScope
import io.github.danprav04.polyglotbooks.core.playback.ExternalNarrationDelegate
import io.github.danprav04.polyglotbooks.core.playback.ExternalNarrationState
import io.github.danprav04.polyglotbooks.core.playback.PlaybackCoordinator
import io.github.danprav04.polyglotbooks.core.playback.SleepTimer
import io.github.danprav04.polyglotbooks.feature.narration.ChapterTranslator
import io.github.danprav04.polyglotbooks.feature.narration.GeminiLiveEngine
import io.github.danprav04.polyglotbooks.feature.narration.GeminiTtsEngine
import io.github.danprav04.polyglotbooks.feature.narration.SynthesisRequest
import io.github.danprav04.polyglotbooks.feature.narration.SystemTtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import io.github.danprav04.polyglotbooks.feature.narration.NarrationEngine as FeatureNarrationEngine

data class ParagraphTiming(
    val paragraphIndex: Int,
    val cfi: String,
    val startMs: Long,
    val durationMs: Long,
    val isMeasured: Boolean = false
)

data class ReaderNarrationSessionState(
    val isActiveSession: Boolean = false,
    val bookId: String = "",
    val bookTitle: String = "",
    val author: String = "",
    val coverPath: String? = null,
    val bookFormat: String = "EPUB",
    val bookFilePath: String = "",
    val extractedBook: EbookContentExtractor.ExtractedBook? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentChapterTitle: String = "",
    val paragraphs: List<ReaderParagraph> = emptyList(),
    val activeParagraphIndex: Int = 0,
    val highlightedCfi: String? = null,
    val characters: List<CharacterEntity> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isTranslating: Boolean = false,
    val isTranslationCached: Boolean = false,
    val isBilingualEnabled: Boolean = false,
    val narrationEngine: NarrationEngine = NarrationEngine.GEMINI_FLASH_TTS,
    val narrateTranslatedText: Boolean = false,
    val targetLanguage: LanguageCode = LanguageCode.SPANISH,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerMinutesRemaining: Int = 0,
    val stopAfterCurrentChapter: Boolean = false,
    val chapterPositionMs: Long = 0L,
    val chapterDurationMs: Long = 0L,
    val bookPositionMs: Long = 0L,
    val bookDurationMs: Long = 0L,
    val paragraphTimings: List<ParagraphTiming> = emptyList(),
    val bilingualTranslations: Map<String, String> = emptyMap(),
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

private data class PrefetchedChunkAudio(
    val chunkIndex: Int,
    val engineTagUsed: String?,
    val pcmChunks: List<PcmChunk>,
    val totalBytes: Int,
    val sampleRate: Int,
    val channels: Int
)

/**
 * Application-scoped singleton coordinating audiobook-grade ebook narration:
 * persistent provider/speed/translated-speech settings, AudioFocus, SleepTimer,
 * weighted & measured chapter/book timelines, chapter auto-advance, gapless prefetch
 * (for Gemini Live & System TTS), and [PlaybackCoordinator] background integration.
 */
@Singleton
class ReaderNarrationSessionManager @Inject constructor(
    private val bookDao: BookDao?,
    private val chapterDao: ChapterDao?,
    private val playbackStateDao: PlaybackStateDao?,
    private val characterDao: CharacterDao?,
    private val settingsRepository: SettingsRepository?,
    private val secureKeyStore: SecureKeyStore?,
    private val interactionsTtsClient: InteractionsTtsClient?,
    private val textModelClient: TextModelClient?,
    private val liveSessionFactory: LiveSessionFactory?,
    private val quotaTracker: GeminiQuotaTracker?,
    private val modelCatalogService: ModelCatalogService?,
    private val playbackCoordinator: PlaybackCoordinator?,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @ApplicationContext private val appContext: Context?
) : ExternalNarrationDelegate {

    constructor(
        bookDao: BookDao? = null,
        chapterDao: ChapterDao? = null,
        playbackStateDao: PlaybackStateDao? = null,
        characterDao: CharacterDao? = null,
        settingsRepository: SettingsRepository? = null,
        secureKeyStore: SecureKeyStore? = null,
        interactionsTtsClient: InteractionsTtsClient? = null,
        textModelClient: TextModelClient? = null,
        liveSessionFactory: LiveSessionFactory? = null,
        quotaTracker: GeminiQuotaTracker? = null,
        modelCatalogService: ModelCatalogService? = null,
        playbackCoordinator: PlaybackCoordinator? = null,
        applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    ) : this(
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
        applicationScope = applicationScope,
        appContext = null
    )

    private val _sessionState = MutableStateFlow(ReaderNarrationSessionState())
    val sessionState: StateFlow<ReaderNarrationSessionState> = _sessionState.asStateFlow()

    private val _externalState = MutableStateFlow(ExternalNarrationState())
    override val externalState: StateFlow<ExternalNarrationState> = _externalState.asStateFlow()

    val sleepTimer = SleepTimer(applicationScope) {
        pause()
    }

    private var narrationJob: Job? = null
    private var tickerJob: Job? = null
    private var sleepObserverJob: Job? = null
    private var settingsObserverJob: Job? = null
    private var translationJob: Job? = null
    private var prefetchJob: Deferred<PrefetchedChunkAudio?>? = null
    private var activePcmPlayer: PcmPlayer? = null

    @Volatile
    private var audioManager: AudioManager? = null

    @Volatile
    private var audioFocusRequest: Any? = null

    @Volatile
    private var pausedDueToTransientFocusLoss: Boolean = false

    @Volatile
    private var activeChunkContext: ActiveChunkTickerContext? = null

    private data class ActiveChunkTickerContext(
        val chunk: SpeechChunk,
        val player: PcmPlayer,
        val playerStartPosMs: Long,
        val chapterStartMs: Long,
        val chunkEstimatedMs: Long,
        val chapterIndex: Int,
        val chapterStartInBookMs: Long
    )

    private val defaultDiskCache: TranslationDiskCache? by lazy {
        appContext?.let { TranslationDiskCache(File(it.filesDir, "quota_cache")) }
    }

    internal var testDiskCache: TranslationDiskCache? = null
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var playerFactory: () -> PcmPlayer = { AudioTrackPcmPlayer() }
    internal var systemTtsFactory: () -> FeatureNarrationEngine = { SystemTtsEngine(appContext) }
    internal var clock: () -> Long = { System.currentTimeMillis() }

    init {
        playbackCoordinator?.registerExternalDelegate(this)
        observeSettings()
        observeSleepTimer()
    }

    private fun resolveDiskCache(): TranslationDiskCache? = testDiskCache ?: defaultDiskCache

    private fun observeSettings() {
        val repo = settingsRepository ?: return
        settingsObserverJob = applicationScope.launch {
            launch {
                repo.targetLanguage.collect { lang ->
                    val prevLang = _sessionState.value.targetLanguage
                    if (prevLang != lang) {
                        _sessionState.update { it.copy(targetLanguage = lang) }
                        pushExternalState()
                        val st = _sessionState.value
                        if (st.bookId.isNotBlank() && (st.isBilingualEnabled || st.narrateTranslatedText)) {
                            if (st.isPlaying && st.narrateTranslatedText) {
                                startNarrationFromCurrent()
                            } else if (st.isBilingualEnabled) {
                                translateCurrentChapterInternal(allowRestart = false)
                            }
                        }
                    }
                }
            }
            launch {
                repo.readerNarrationEngine.collect { engineName ->
                    val parsed = parseNarrationEngine(engineName)
                    if (_sessionState.value.narrationEngine != parsed) {
                        _sessionState.update { it.copy(narrationEngine = parsed) }
                        pushExternalState()
                        if (_sessionState.value.isPlaying) {
                            startNarrationFromCurrent()
                        }
                    }
                }
            }
            launch {
                repo.readerReadTranslated.collect { readTranslated ->
                    if (_sessionState.value.narrateTranslatedText != readTranslated) {
                        _sessionState.update { it.copy(narrateTranslatedText = readTranslated) }
                        recalculateTimelines()
                        if (_sessionState.value.isPlaying) {
                            startNarrationFromCurrent()
                        }
                    }
                }
            }
            launch {
                repo.readerNarrationSpeed.collect { speed ->
                    val clamped = speed.coerceIn(0.5f, 2.5f)
                    if (_sessionState.value.playbackSpeed != clamped) {
                        _sessionState.update { it.copy(playbackSpeed = clamped) }
                        activePcmPlayer?.setPlaybackSpeed(clamped)
                        pushExternalState()
                    }
                }
            }
        }
    }

    private fun observeSleepTimer() {
        sleepObserverJob = applicationScope.launch {
            sleepTimer.remainingTimeMs.collect { remainingMs ->
                val mins = if (remainingMs <= 0L) 0 else ceil(remainingMs / 60_000.0).toInt()
                if (_sessionState.value.sleepTimerMinutesRemaining != mins) {
                    _sessionState.update { it.copy(sleepTimerMinutesRemaining = mins) }
                    pushExternalState()
                }
            }
        }
    }

    fun isSessionActiveFor(targetBookId: String): Boolean {
        val state = _sessionState.value
        return state.isActiveSession && state.bookId == targetBookId && targetBookId.isNotBlank()
    }

    fun updateBookContext(
        bookId: String,
        bookTitle: String,
        author: String = "",
        coverPath: String? = null,
        bookFormat: String = "EPUB",
        bookFilePath: String = "",
        extractedBook: EbookContentExtractor.ExtractedBook? = null,
        chapters: List<ChapterEntity> = emptyList(),
        currentChapterIndex: Int = 0,
        paragraphs: List<ReaderParagraph> = emptyList(),
        activeParagraphIndex: Int = 0,
        characters: List<CharacterEntity> = emptyList(),
        isBilingualEnabled: Boolean = false,
        bilingualTranslations: Map<String, String> = emptyMap(),
        forceWhenDifferentBook: Boolean = false
    ) {
        val existing = _sessionState.value
        if (existing.isActiveSession && existing.bookId.isNotBlank() && existing.bookId != bookId && !forceWhenDifferentBook) {
            return
        }

        val resolvedExtracted = extractedBook ?: if (existing.bookId == bookId) existing.extractedBook else null
        val resolvedChapters = chapters.ifEmpty { if (existing.bookId == bookId) existing.chapters else emptyList() }
        val resolvedParagraphs = paragraphs.ifEmpty {
            resolvedExtracted?.let { buildParagraphsForChapter(it, currentChapterIndex, bookFormat) }.orEmpty()
        }
        val clampedParaIndex = if (resolvedParagraphs.isEmpty()) 0 else activeParagraphIndex.coerceIn(0, resolvedParagraphs.lastIndex)
        val chapterTitle = resolveChapterTitle(resolvedChapters, resolvedExtracted, currentChapterIndex)

        _sessionState.update { state ->
            val next = state.copy(
                bookId = bookId,
                bookTitle = bookTitle.ifBlank { state.bookTitle },
                author = author.ifBlank { state.author },
                coverPath = coverPath ?: state.coverPath,
                bookFormat = bookFormat,
                bookFilePath = bookFilePath.ifBlank { state.bookFilePath },
                extractedBook = resolvedExtracted,
                chapters = resolvedChapters,
                currentChapterIndex = currentChapterIndex,
                currentChapterTitle = chapterTitle,
                paragraphs = resolvedParagraphs,
                activeParagraphIndex = clampedParaIndex,
                characters = characters,
                isBilingualEnabled = isBilingualEnabled,
                bilingualTranslations = if (bilingualTranslations.isNotEmpty() || !isBilingualEnabled) {
                    bilingualTranslations
                } else {
                    state.bilingualTranslations
                }
            )
            withRecomputedTimelines(next, preservePositionAtCurrentPara = !state.isPlaying)
        }
        pushExternalState()
    }

    fun startOrResume(
        bookId: String,
        bookTitle: String,
        author: String = "",
        coverPath: String? = null,
        bookFormat: String = "EPUB",
        bookFilePath: String = "",
        extractedBook: EbookContentExtractor.ExtractedBook? = null,
        chapters: List<ChapterEntity> = emptyList(),
        currentChapterIndex: Int = 0,
        paragraphs: List<ReaderParagraph> = emptyList(),
        activeParagraphIndex: Int = 0,
        characters: List<CharacterEntity> = emptyList(),
        isBilingualEnabled: Boolean = false,
        bilingualTranslations: Map<String, String> = emptyMap()
    ) {
        val current = _sessionState.value
        val sameBookPaused = current.isActiveSession && current.bookId == bookId && !current.isPlaying
        updateBookContext(
            bookId = bookId,
            bookTitle = bookTitle,
            author = author,
            coverPath = coverPath,
            bookFormat = bookFormat,
            bookFilePath = bookFilePath,
            extractedBook = extractedBook,
            chapters = chapters,
            currentChapterIndex = if (sameBookPaused) current.currentChapterIndex else currentChapterIndex,
            paragraphs = if (sameBookPaused && current.paragraphs.isNotEmpty()) current.paragraphs else paragraphs,
            activeParagraphIndex = if (sameBookPaused) current.activeParagraphIndex else activeParagraphIndex,
            characters = characters,
            isBilingualEnabled = isBilingualEnabled,
            bilingualTranslations = bilingualTranslations,
            forceWhenDifferentBook = true
        )
        startNarrationFromCurrent()
    }

    override fun play() {
        val state = _sessionState.value
        if (state.isPlaying) return
        if (state.bookId.isBlank() && state.paragraphs.isEmpty()) return
        startNarrationFromCurrent()
    }

    override fun pause() {
        pausedDueToTransientFocusLoss = false
        cancelPlaybackJobsOnly()
        abandonAudioFocus()
        val state = _sessionState.value
        _sessionState.update {
            it.copy(
                isPlaying = false,
                isBuffering = false
            )
        }
        pushExternalState()
        persistCurrentProgress(state.bookId, state.highlightedCfi ?: state.paragraphs.getOrNull(state.activeParagraphIndex)?.cfi)
    }

    override fun stop() {
        pausedDueToTransientFocusLoss = false
        cancelPlaybackJobsOnly()
        translationJob?.cancel()
        sleepTimer.cancel()
        abandonAudioFocus()
        val state = _sessionState.value
        persistCurrentProgress(state.bookId, state.highlightedCfi ?: state.paragraphs.getOrNull(state.activeParagraphIndex)?.cfi)
        _sessionState.update {
            it.copy(
                isActiveSession = false,
                isPlaying = false,
                isBuffering = false,
                highlightedCfi = null,
                sleepTimerMinutesRemaining = 0,
                stopAfterCurrentChapter = false,
                statusMessage = null
            )
        }
        playbackCoordinator?.releaseExternalNarration()
        pushExternalState()
    }

    override fun onAudioBecomingNoisy() {
        if (_sessionState.value.isPlaying) {
            pause()
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.5f)
        _sessionState.update { it.copy(playbackSpeed = clamped) }
        activePcmPlayer?.setPlaybackSpeed(clamped)
        pushExternalState()
        applicationScope.launch(NonCancellable) {
            try {
                settingsRepository?.setReaderNarrationSpeed(clamped)
            } catch (_: Throwable) {
            }
        }
    }

    fun setNarrationEngine(engine: NarrationEngine) {
        val prev = _sessionState.value.narrationEngine
        _sessionState.update { it.copy(narrationEngine = engine, statusMessage = null) }
        pushExternalState()
        applicationScope.launch(NonCancellable) {
            try {
                settingsRepository?.setReaderNarrationEngine(engine.name)
            } catch (_: Throwable) {
            }
        }
        if (prev != engine && _sessionState.value.isPlaying) {
            startNarrationFromCurrent()
        }
    }

    fun setNarrateTranslatedText(enabled: Boolean) {
        val prev = _sessionState.value.narrateTranslatedText
        _sessionState.update { it.copy(narrateTranslatedText = enabled, statusMessage = null) }
        recalculateTimelines()
        pushExternalState()
        applicationScope.launch(NonCancellable) {
            try {
                settingsRepository?.setReaderReadTranslated(enabled)
            } catch (_: Throwable) {
            }
        }
        if (prev != enabled && _sessionState.value.isPlaying) {
            startNarrationFromCurrent()
        }
    }

    fun setTargetLanguage(lang: LanguageCode) {
        val prev = _sessionState.value.targetLanguage
        _sessionState.update { it.copy(targetLanguage = lang) }
        pushExternalState()
        applicationScope.launch(NonCancellable) {
            try {
                settingsRepository?.setTargetLanguage(lang)
            } catch (_: Throwable) {
            }
        }
        if (prev != lang) {
            val st = _sessionState.value
            if (st.isPlaying && st.narrateTranslatedText) {
                startNarrationFromCurrent()
            } else if (st.isBilingualEnabled) {
                translateCurrentChapterInternal(allowRestart = false)
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        when {
            minutes == -1 -> {
                sleepTimer.cancel()
                _sessionState.update {
                    it.copy(
                        sleepTimerMinutesRemaining = 0,
                        stopAfterCurrentChapter = true
                    )
                }
            }
            minutes <= 0 -> {
                sleepTimer.cancel()
                _sessionState.update {
                    it.copy(
                        sleepTimerMinutesRemaining = 0,
                        stopAfterCurrentChapter = false
                    )
                }
            }
            else -> {
                _sessionState.update {
                    it.copy(
                        stopAfterCurrentChapter = false,
                        sleepTimerMinutesRemaining = minutes
                    )
                }
                sleepTimer.start(minutes * 60_000L)
            }
        }
        pushExternalState()
    }

    override fun seekToChapterPositionMs(positionMs: Long) {
        val state = _sessionState.value
        val timings = state.paragraphTimings
        if (timings.isEmpty() || state.paragraphs.isEmpty()) return
        val clampedPos = positionMs.coerceIn(0L, maxOf(1L, state.chapterDurationMs))
        val targetTiming = timings.lastOrNull { it.startMs <= clampedPos } ?: timings.first()
        val targetIndex = targetTiming.paragraphIndex.coerceIn(0, state.paragraphs.lastIndex)
        seekToParagraph(targetIndex)
    }

    override fun seekByDeltaMs(deltaMs: Long) {
        val state = _sessionState.value
        if (state.paragraphs.isEmpty()) return
        val targetPos = (state.chapterPositionMs + deltaMs).coerceIn(0L, maxOf(1L, state.chapterDurationMs))
        val timings = state.paragraphTimings
        if (timings.isEmpty()) {
            if (deltaMs >= 0L) nextParagraph() else previousParagraph()
            return
        }
        val targetTiming = timings.lastOrNull { it.startMs <= targetPos } ?: timings.first()
        var targetIndex = targetTiming.paragraphIndex.coerceIn(0, state.paragraphs.lastIndex)
        if (targetIndex == state.activeParagraphIndex) {
            targetIndex = if (deltaMs >= 0L) {
                (state.activeParagraphIndex + 1).coerceAtMost(state.paragraphs.lastIndex)
            } else {
                (state.activeParagraphIndex - 1).coerceAtLeast(0)
            }
        }
        seekToParagraph(targetIndex)
    }

    fun seekToParagraph(paragraphIndex: Int) {
        val state = _sessionState.value
        if (state.paragraphs.isEmpty()) return
        val clamped = paragraphIndex.coerceIn(0, state.paragraphs.lastIndex)
        val cfi = state.paragraphs.getOrNull(clamped)?.cfi
        val wasPlaying = state.isPlaying
        _sessionState.update { s ->
            val updated = s.copy(
                activeParagraphIndex = clamped,
                highlightedCfi = if (s.isActiveSession) cfi else s.highlightedCfi
            )
            withRecomputedTimelines(updated, preservePositionAtCurrentPara = true)
        }
        pushExternalState()
        if (wasPlaying) {
            startNarrationFromCurrent()
        }
    }

    override fun nextParagraph() {
        val state = _sessionState.value
        if (state.paragraphs.isEmpty()) return
        if (state.activeParagraphIndex < state.paragraphs.lastIndex) {
            seekToParagraph(state.activeParagraphIndex + 1)
        } else {
            nextChapter()
        }
    }

    override fun previousParagraph() {
        val state = _sessionState.value
        if (state.paragraphs.isEmpty()) return
        if (state.activeParagraphIndex > 0) {
            seekToParagraph(state.activeParagraphIndex - 1)
        } else {
            previousChapter()
        }
    }

    override fun nextChapter() {
        val state = _sessionState.value
        val total = maxOf(state.chapters.size, state.extractedBook?.chapters?.size ?: 0)
        if (total <= 0) return
        val next = (state.currentChapterIndex + 1).coerceAtMost(total - 1)
        if (next != state.currentChapterIndex) {
            switchChapter(next, continuePlaying = state.isPlaying)
        }
    }

    override fun previousChapter() {
        val state = _sessionState.value
        val total = maxOf(state.chapters.size, state.extractedBook?.chapters?.size ?: 0)
        if (total <= 0) return
        val prev = (state.currentChapterIndex - 1).coerceAtLeast(0)
        if (prev != state.currentChapterIndex) {
            switchChapter(prev, continuePlaying = state.isPlaying)
        }
    }

    fun switchChapter(targetChapterIndex: Int, continuePlaying: Boolean) {
        val wasPlaying = _sessionState.value.isPlaying
        if (wasPlaying) {
            cancelPlaybackJobsOnly()
        }
        applicationScope.launch {
            val loaded = loadChapterContentIntoState(targetChapterIndex)
            if (loaded && continuePlaying) {
                startNarrationFromCurrent()
            } else {
                pushExternalState()
            }
        }
    }

    fun clearNotices() {
        _sessionState.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    fun retryTranslation() {
        clearNotices()
        if (_sessionState.value.isBilingualEnabled || _sessionState.value.narrateTranslatedText) {
            translateCurrentChapterInternal(allowRestart = _sessionState.value.narrateTranslatedText && _sessionState.value.isPlaying)
        }
    }

    fun setBilingualMode(enabled: Boolean) {
        _sessionState.update { it.copy(isBilingualEnabled = enabled) }
        if (enabled) {
            translateCurrentChapterInternal(allowRestart = false)
        } else {
            translationJob?.cancel()
            _sessionState.update {
                it.copy(
                    bilingualTranslations = if (it.narrateTranslatedText) it.bilingualTranslations else emptyMap(),
                    isTranslating = false,
                    isTranslationCached = false
                )
            }
        }
    }

    fun translateCurrentChapterPublic(fallbackDemoIfEmpty: Boolean = true) {
        translateCurrentChapterInternal(allowRestart = false, fallbackDemoIfEmpty = fallbackDemoIfEmpty)
    }

    private fun translateCurrentChapterInternal(allowRestart: Boolean, fallbackDemoIfEmpty: Boolean = true) {
        translationJob?.cancel()
        val state = _sessionState.value
        val activeParagraphs = state.paragraphs.ifEmpty {
            state.extractedBook?.let { buildParagraphsForChapter(it, state.currentChapterIndex, state.bookFormat) }.orEmpty()
        }
        val targetLangCode = state.targetLanguage.code
        val chapterIdx = state.currentChapterIndex
        val currentBookId = state.bookId

        val paragraphMap: Map<String, String> = if (activeParagraphs.isNotEmpty()) {
            activeParagraphs.filter { it.text.isNotBlank() }.associate { it.cfi to it.text }
        } else if (fallbackDemoIfEmpty) {
            mapOf(
                "epubcfi(/6/1!/4/2/1:0)" to "Welcome to Polyglot Books. This is an immersive ebook reader.",
                "epubcfi(/6/1!/4/2/1:1)" to "With advanced AI narration engines like Gemini Flash TTS.",
                "epubcfi(/6/1!/4/2/1:2)" to "Switch between Light, Sepia, and Dark themes and adjust font sizes."
            )
        } else {
            emptyMap()
        }

        if (paragraphMap.isEmpty()) return

        translationJob = applicationScope.launch {
            ensureChapterTranslations(
                targetBookId = currentBookId,
                chapterIdx = chapterIdx,
                targetLangCode = targetLangCode,
                paragraphMap = paragraphMap,
                updateErrorOnFailure = true
            )
            if (allowRestart && _sessionState.value.isPlaying) {
                startNarrationFromCurrent()
            }
        }
    }

    /**
     * Ensures translations exist for [paragraphMap], serving cache hits with 0 network calls
     * and incrementally persisting any remaining paragraphs via [ChapterTranslator].
     * Returns true if all needed paragraphs have translations available (or on partial cache hit when empty).
     */
    internal suspend fun ensureChapterTranslations(
        targetBookId: String,
        chapterIdx: Int,
        targetLangCode: String,
        paragraphMap: Map<String, String>,
        updateErrorOnFailure: Boolean,
        requireRealTranslation: Boolean = false
    ): Map<String, String>? {
        val diskCache = resolveDiskCache()
        val cached = diskCache?.loadTranslations(
            bookId = targetBookId,
            chapterIndex = chapterIdx,
            targetLang = targetLangCode,
            currentParagraphsByCfi = paragraphMap
        ).orEmpty()

        val remaining = paragraphMap.filterKeys { it !in cached }
        if (cached.isNotEmpty()) {
            _sessionState.update {
                val next = it.copy(
                    bilingualTranslations = cached,
                    isTranslationCached = remaining.isEmpty()
                )
                withRecomputedTimelines(next, preservePositionAtCurrentPara = false)
            }
        }
        if (remaining.isEmpty()) {
            _sessionState.update { it.copy(isTranslating = false, isTranslationCached = cached.isNotEmpty()) }
            return cached
        }

        val apiKey = resolveApiKey()
        val allowMockFallback = !requireRealTranslation && (appContext == null || textModelClient == null)
        if ((textModelClient == null || apiKey.isNullOrBlank()) && !allowMockFallback) {
            if (updateErrorOnFailure) {
                _sessionState.update {
                    it.copy(
                        isTranslating = false,
                        errorMessage = "Add a Gemini API key in Settings to translate chapters."
                    )
                }
            }
            return null
        }

        val translator = ChapterTranslator(
            textModelClient = textModelClient,
            apiKeyProvider = { apiKey },
            modelProvider = { resolveModel(ModelRole.TEXT_TRANSLATION) },
            quotaTracker = quotaTracker
        )

        _sessionState.update { it.copy(isTranslating = true, isTranslationCached = false, errorMessage = null) }
        val merged = LinkedHashMap<String, String>(paragraphMap.size).apply { putAll(cached) }
        return try {
            translator.translateChapter(
                paragraphs = remaining,
                lines = emptyList(),
                targetLanguageCode = targetLangCode,
                onBatchTranslated = { batch ->
                    if (batch.isNotEmpty()) {
                        diskCache?.saveTranslations(
                            bookId = targetBookId,
                            chapterIndex = chapterIdx,
                            targetLang = targetLangCode,
                            sourceParagraphsByCfi = paragraphMap,
                            translatedByCfi = batch
                        )
                        merged.putAll(batch)
                        _sessionState.update {
                            val next = it.copy(bilingualTranslations = merged.toMap())
                            withRecomputedTimelines(next, preservePositionAtCurrentPara = false)
                        }
                    }
                }
            )
            val approxTokens = estimateTokens(remaining.values, merged.values)
            if (approxTokens > 0L) {
                try {
                    settingsRepository?.recordUsage(textTranslationTokensDelta = approxTokens)
                } catch (_: Throwable) {
                }
            }
            _sessionState.update { it.copy(isTranslating = false, isTranslationCached = false) }
            merged
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (updateErrorOnFailure) {
                _sessionState.update {
                    it.copy(
                        isTranslating = false,
                        errorMessage = formatConciseError(e, "Translation failed")
                    )
                }
            }
            null
        }
    }

    private fun startNarrationFromCurrent() {
        val state = _sessionState.value
        val activeParagraphs = state.paragraphs.ifEmpty {
            state.extractedBook?.let { buildParagraphsForChapter(it, state.currentChapterIndex, state.bookFormat) }.orEmpty()
        }

        if (appContext == null && activeParagraphs.isEmpty()) {
            // Pure unit-test compatibility when appContext & paragraphs are absent.
            playbackCoordinator?.activateExternalNarration(appContext)
            _sessionState.update { it.copy(isActiveSession = true, isPlaying = true) }
            pushExternalState()
            return
        }

        if (activeParagraphs.isEmpty()) {
            _sessionState.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = "No readable text found in this chapter to narrate."
                )
            }
            pushExternalState()
            return
        }

        if (state.narrateTranslatedText) {
            val startIdx = state.activeParagraphIndex.coerceIn(0, activeParagraphs.lastIndex)
            val hasMissingTranslations = activeParagraphs.drop(startIdx)
                .filter { it.text.isNotBlank() }
                .any { state.bilingualTranslations[it.cfi].isNullOrBlank() }
            if (hasMissingTranslations && textModelClient == null && resolveDiskCache() == null) {
                _sessionState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        isTranslating = false,
                        errorMessage = "Add a Gemini API key in Settings to translate chapters."
                    )
                }
                pushExternalState()
                return
            }
        }

        cancelPlaybackJobsOnly()
        requestAudioFocusSafely()
        playbackCoordinator?.activateExternalNarration(appContext)

        _sessionState.update {
            val next = it.copy(
                isActiveSession = true,
                isPlaying = true,
                isBuffering = true,
                paragraphs = activeParagraphs,
                statusMessage = null,
                errorMessage = null
            )
            withRecomputedTimelines(next, preservePositionAtCurrentPara = true)
        }
        pushExternalState()

        narrationJob = applicationScope.launch(ioDispatcher) {
            val player = playerFactory()
            activePcmPlayer = player
            var configuredSampleRate = 24000
            var configuredChannels = 1
            var sessionEngineFailedTts = false

            try {
                player.init(configuredSampleRate, configuredChannels)
                player.setPlaybackSpeed(_sessionState.value.playbackSpeed)
                player.play()
                startTickerLoop()

                while (isActive) {
                    val cur = _sessionState.value
                    val chapterIdx = cur.currentChapterIndex
                    val chapterParas = cur.paragraphs
                    if (chapterParas.isEmpty()) break

                    val startIdx = cur.activeParagraphIndex.coerceIn(0, chapterParas.lastIndex)
                    val remainingParas = chapterParas.drop(startIdx)
                    val readTranslated = cur.narrateTranslatedText
                    val targetLang = cur.targetLanguage

                    var translationsForChapter = cur.bilingualTranslations
                    if (readTranslated) {
                        val neededMap = remainingParas.filter { it.text.isNotBlank() }.associate { it.cfi to it.text }
                        val missing = neededMap.filterKeys { it !in translationsForChapter || translationsForChapter[it].isNullOrBlank() }
                        if (missing.isNotEmpty()) {
                            val ensured = ensureChapterTranslations(
                                targetBookId = cur.bookId,
                                chapterIdx = chapterIdx,
                                targetLangCode = targetLang.code,
                                paragraphMap = chapterParas.filter { it.text.isNotBlank() }.associate { it.cfi to it.text },
                                updateErrorOnFailure = true,
                                requireRealTranslation = true
                            )
                            if (ensured == null || neededMap.keys.all { ensured[it].isNullOrBlank() }) {
                                _sessionState.update {
                                    it.copy(
                                        isPlaying = false,
                                        isBuffering = false,
                                        errorMessage = it.errorMessage ?: "Unable to translate chapter for ${targetLang.displayName} narration."
                                    )
                                }
                                pushExternalState()
                                return@launch
                            }
                            translationsForChapter = ensured
                        }
                    }

                    val engineChoice = _sessionState.value.narrationEngine
                    val spokenLangCode = if (readTranslated) targetLang.code else null
                    val chunks = buildSpeechChunks(
                        paragraphs = remainingParas,
                        characters = _sessionState.value.characters,
                        engine = engineChoice,
                        translatedTextsByCfi = if (readTranslated) translationsForChapter else null,
                        languageCode = spokenLangCode
                    )
                    if (chunks.isEmpty()) break

                    val systemFallback = systemTtsFactory()
                    val diskCache = resolveDiskCache()
                    val maxCacheBytes = resolveAudioCacheCapBytes()
                    val allowPrefetch = engineChoice == NarrationEngine.GEMINI_LIVE ||
                        engineChoice == NarrationEngine.SYSTEM_TTS

                    for (chunkIdx in chunks.indices) {
                        ensureActive()
                        val chunk = chunks[chunkIdx]
                        val timings = _sessionState.value.paragraphTimings
                        val chunkTiming = timings.firstOrNull { it.paragraphIndex == chunk.leadIndex }
                        val chunkStartChapterMs = chunkTiming?.startMs ?: _sessionState.value.chapterPositionMs
                        val chunkEstimatedMs = chunk.allCfis.indices.sumOf { offset ->
                            val pIdx = chunk.paragraphIndices.getOrNull(offset) ?: (chunk.leadIndex + offset)
                            timings.firstOrNull { it.paragraphIndex == pIdx }?.durationMs ?: 3_000L
                        }.coerceAtLeast(800L)

                        _sessionState.update {
                            it.copy(
                                activeParagraphIndex = chunk.leadIndex,
                                highlightedCfi = chunk.leadCfi,
                                chapterPositionMs = chunkStartChapterMs,
                                bookPositionMs = computeChapterStartInBookMs(it) + chunkStartChapterMs,
                                isBuffering = true
                            )
                        }
                        pushExternalState()
                        persistCurrentProgress(_sessionState.value.bookId, chunk.leadCfi)

                        val preloaded = if (allowPrefetch) {
                            takePrefetchedIfMatches(chunkIdx)
                        } else {
                            null
                        }

                        // Kick off gapless prefetch for next chunk ONLY on Live / System TTS.
                        if (allowPrefetch && chunkIdx + 1 < chunks.size) {
                            val nextChunk = chunks[chunkIdx + 1]
                            val nextEngine = _sessionState.value.narrationEngine
                            prefetchJob?.cancel()
                            prefetchJob = async(ioDispatcher) {
                                prefetchChunkAudio(
                                    chunkIndex = chunkIdx + 1,
                                    chunk = nextChunk,
                                    engineChoice = nextEngine,
                                    systemFallback = systemFallback,
                                    diskCache = diskCache,
                                    maxCacheBytes = maxCacheBytes,
                                    ttsAlreadyFailed = sessionEngineFailedTts
                                )
                            }
                        }

                        var chunkPlayerStartPosMs = (player as? AudioTrackPcmPlayer)?.refreshPosition()
                            ?: player.currentPositionMs.value
                        var chunkTotalBytes = 0
                        var chunkSampleRate = configuredSampleRate
                        var chunkChannels = configuredChannels

                        activeChunkContext = ActiveChunkTickerContext(
                            chunk = chunk,
                            player = player,
                            playerStartPosMs = chunkPlayerStartPosMs,
                            chapterStartMs = chunkStartChapterMs,
                            chunkEstimatedMs = chunkEstimatedMs,
                            chapterIndex = chapterIdx,
                            chapterStartInBookMs = computeChapterStartInBookMs(_sessionState.value)
                        )

                        suspend fun emitChunkToPlayer(pcm: PcmChunk) {
                            ensureActive()
                            if (pcm.data.isEmpty()) return
                            if (pcm.sampleRate != configuredSampleRate || pcm.channels != configuredChannels) {
                                configuredSampleRate = pcm.sampleRate
                                configuredChannels = pcm.channels
                                player.init(configuredSampleRate, configuredChannels)
                                player.setPlaybackSpeed(_sessionState.value.playbackSpeed)
                                player.play()
                                chunkPlayerStartPosMs = 0L
                                activeChunkContext = activeChunkContext?.copy(playerStartPosMs = 0L)
                            }
                            if (_sessionState.value.isBuffering) {
                                _sessionState.update { it.copy(isBuffering = false) }
                                pushExternalState()
                            }
                            chunkSampleRate = pcm.sampleRate
                            chunkChannels = pcm.channels
                            chunkTotalBytes += pcm.data.size
                            player.write(pcm)
                        }

                        if (preloaded != null) {
                            for (pcm in preloaded.pcmChunks) {
                                emitChunkToPlayer(pcm)
                            }
                        } else {
                            synthesizeSpeechChunk(
                                chunk = chunk,
                                engineChoice = engineChoice,
                                systemFallback = systemFallback,
                                diskCache = diskCache,
                                maxCacheBytes = maxCacheBytes,
                                ttsAlreadyFailed = sessionEngineFailedTts,
                                onTtsFailed = { sessionEngineFailedTts = true },
                                onStatusUpdate = { status ->
                                    _sessionState.update { it.copy(statusMessage = status) }
                                },
                                onPcmChunk = { pcm -> emitChunkToPlayer(pcm) }
                            )
                        }

                        // Refine measured paragraph timing after chunk completes.
                        if (chunkTotalBytes > 0 && chunkSampleRate > 0 && chunkChannels > 0) {
                            val measuredMs = (chunkTotalBytes * 1000L) / (chunkSampleRate * chunkChannels * 2L)
                            if (measuredMs > 0L) {
                                applyMeasuredDurationToChunk(chunk, measuredMs)
                            }
                        }
                    }

                    // Drain hardware audio buffer before transitioning to next chapter or finishing.
                    var drainWaitMs = 0L
                    while (player.bufferedMs > 0L && drainWaitMs < 4_000L) {
                        ensureActive()
                        delay(80L)
                        drainWaitMs += 80L
                    }
                    activeChunkContext = null

                    val latestState = _sessionState.value
                    if (latestState.stopAfterCurrentChapter) {
                        _sessionState.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                stopAfterCurrentChapter = false
                            )
                        }
                        pushExternalState()
                        break
                    }

                    val totalChapters = maxOf(latestState.chapters.size, latestState.extractedBook?.chapters?.size ?: 0)
                    val nextChapterIdx = latestState.currentChapterIndex + 1
                    if (nextChapterIdx < totalChapters) {
                        val advanced = loadChapterContentIntoState(nextChapterIdx)
                        if (!advanced) break
                    } else {
                        // Finished entire book cleanly.
                        _sessionState.update {
                            it.copy(
                                isPlaying = false,
                                isBuffering = false,
                                chapterPositionMs = it.chapterDurationMs,
                                bookPositionMs = it.bookDurationMs
                            )
                        }
                        pushExternalState()
                        break
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _sessionState.update {
                    it.copy(
                        isPlaying = false,
                        isBuffering = false,
                        errorMessage = formatConciseError(e, "Narration failed")
                    )
                }
                pushExternalState()
            } finally {
                activeChunkContext = null
                tickerJob?.cancel()
                tickerJob = null
                prefetchJob?.cancel()
                prefetchJob = null
                try {
                    player.stop()
                    player.release()
                } catch (_: Throwable) {
                }
                if (activePcmPlayer === player) {
                    activePcmPlayer = null
                }
                abandonAudioFocus()
                _sessionState.update { it.copy(isPlaying = false, isBuffering = false) }
                pushExternalState()
            }
        }
    }

    private fun startTickerLoop() {
        tickerJob?.cancel()
        tickerJob = applicationScope.launch {
            while (isActive) {
                delay(150L)
                val ctx = activeChunkContext ?: continue
                if (!_sessionState.value.isPlaying) continue
                val currentPlayerPosMs = (ctx.player as? AudioTrackPcmPlayer)?.refreshPosition()
                    ?: ctx.player.currentPositionMs.value
                val chunkElapsedMs = (currentPlayerPosMs - ctx.playerStartPosMs)
                    .coerceIn(0L, ctx.chunkEstimatedMs)

                // Map chunkElapsedMs proportionally across paragraphs inside this SpeechChunk.
                val subWeights = ctx.chunk.paragraphCharWeights
                var subIdxOffset = 0
                if (subWeights.size > 1 && ctx.chunkEstimatedMs > 0L) {
                    val totalW = subWeights.sum().coerceAtLeast(1)
                    var accWeight = 0L
                    for (i in subWeights.indices) {
                        accWeight += subWeights[i]
                        val thresholdMs = (accWeight * ctx.chunkEstimatedMs) / totalW
                        if (chunkElapsedMs <= thresholdMs || i == subWeights.lastIndex) {
                            subIdxOffset = i
                            break
                        }
                    }
                }

                val resolvedParaIdx = ctx.chunk.paragraphIndices.getOrNull(subIdxOffset)
                    ?: (ctx.chunk.leadIndex + subIdxOffset)
                val resolvedCfi = ctx.chunk.allCfis.getOrNull(subIdxOffset) ?: ctx.chunk.leadCfi

                _sessionState.update { s ->
                    if (s.currentChapterIndex != ctx.chapterIndex) return@update s
                    val newChapterPos = (ctx.chapterStartMs + chunkElapsedMs).coerceIn(0L, maxOf(1L, s.chapterDurationMs))
                    val newBookPos = (ctx.chapterStartInBookMs + newChapterPos).coerceIn(0L, maxOf(1L, s.bookDurationMs))
                    s.copy(
                        activeParagraphIndex = resolvedParaIdx.coerceIn(0, maxOf(0, s.paragraphs.lastIndex)),
                        highlightedCfi = resolvedCfi,
                        chapterPositionMs = newChapterPos,
                        bookPositionMs = newBookPos
                    )
                }
                pushExternalState()
            }
        }
    }

    private suspend fun takePrefetchedIfMatches(chunkIdx: Int): PrefetchedChunkAudio? {
        val job = prefetchJob ?: return null
        val prefetched = try {
            job.await()
        } catch (_: Throwable) {
            null
        }
        prefetchJob = null
        return if (prefetched != null && prefetched.chunkIndex == chunkIdx) prefetched else null
    }

    private suspend fun prefetchChunkAudio(
        chunkIndex: Int,
        chunk: SpeechChunk,
        engineChoice: NarrationEngine,
        systemFallback: FeatureNarrationEngine,
        diskCache: TranslationDiskCache?,
        maxCacheBytes: Long,
        ttsAlreadyFailed: Boolean
    ): PrefetchedChunkAudio? {
        return try {
            val collected = mutableListOf<PcmChunk>()
            var bytes = 0
            var rate = 24000
            var ch = 1
            synthesizeSpeechChunk(
                chunk = chunk,
                engineChoice = engineChoice,
                systemFallback = systemFallback,
                diskCache = diskCache,
                maxCacheBytes = maxCacheBytes,
                ttsAlreadyFailed = ttsAlreadyFailed,
                onTtsFailed = {},
                onStatusUpdate = {},
                onPcmChunk = { pcm ->
                    if (pcm.data.isNotEmpty()) {
                        collected.add(pcm)
                        bytes += pcm.data.size
                        rate = pcm.sampleRate
                        ch = pcm.channels
                    }
                }
            )
            if (collected.isEmpty()) null
            else PrefetchedChunkAudio(chunkIndex, engineChoice.name, collected, bytes, rate, ch)
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun loadChapterContentIntoState(targetChapterIndex: Int): Boolean {
        val state = _sessionState.value
        val total = maxOf(state.chapters.size, state.extractedBook?.chapters?.size ?: 0)
        if (total <= 0) return false
        val clampedIndex = targetChapterIndex.coerceIn(0, total - 1)
        val extracted = state.extractedBook ?: if (appContext != null && state.bookFilePath.isNotBlank()) {
            withContext(ioDispatcher) {
                runCatching {
                    EbookContentExtractor.extractBook(appContext, state.bookFilePath, state.bookFormat)
                }.getOrNull()
            }
        } else {
            null
        }
        val newParagraphs = if (extracted != null) {
            buildParagraphsForChapter(extracted, clampedIndex, state.bookFormat)
        } else {
            emptyList()
        }
        val chapterTitle = resolveChapterTitle(state.chapters, extracted, clampedIndex)
        val firstCfi = newParagraphs.firstOrNull()?.cfi
            ?: state.chapters.getOrNull(clampedIndex)?.cfiRange
            ?: defaultChapterCfi(clampedIndex, state.bookFormat.equals("PDF", ignoreCase = true))

        _sessionState.update { s ->
            val updated = s.copy(
                extractedBook = extracted ?: s.extractedBook,
                currentChapterIndex = clampedIndex,
                currentChapterTitle = chapterTitle,
                paragraphs = newParagraphs,
                activeParagraphIndex = 0,
                highlightedCfi = firstCfi,
                bilingualTranslations = if (s.isBilingualEnabled || s.narrateTranslatedText) emptyMap() else s.bilingualTranslations,
                isTranslationCached = false
            )
            withRecomputedTimelines(updated, preservePositionAtCurrentPara = false).copy(chapterPositionMs = 0L)
        }
        pushExternalState()
        persistCurrentProgress(state.bookId, firstCfi)

        if (_sessionState.value.isBilingualEnabled && !_sessionState.value.narrateTranslatedText) {
            translateCurrentChapterInternal(allowRestart = false, fallbackDemoIfEmpty = false)
        }
        return true
    }

    private fun cancelPlaybackJobsOnly() {
        activeChunkContext = null
        tickerJob?.cancel()
        tickerJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        narrationJob?.cancel()
        narrationJob = null
        releaseActivePlayer()
    }

    private fun releaseActivePlayer() {
        val player = activePcmPlayer
        activePcmPlayer = null
        if (player != null) {
            try {
                player.stop()
                player.release()
            } catch (_: Throwable) {
            }
        }
    }

    internal fun buildSpeechChunks(
        paragraphs: List<ReaderParagraph>,
        characters: List<CharacterEntity>,
        engine: NarrationEngine,
        translatedTextsByCfi: Map<String, String>? = null,
        languageCode: String? = null
    ): List<SpeechChunk> {
        val nonBlank = paragraphs.filter { it.text.isNotBlank() }
        if (nonBlank.isEmpty()) return emptyList()

        val narratorVoice = characters
            .firstOrNull { it.name.equals("Narrator", ignoreCase = true) }
            ?.assignedVoiceId ?: "Charon"

        val maxChars = when (engine) {
            NarrationEngine.SYSTEM_TTS -> 0
            NarrationEngine.GEMINI_FLASH_TTS -> 1000
            NarrationEngine.GEMINI_LIVE -> 1400
        }

        val result = mutableListOf<SpeechChunk>()
        var currentGroup = mutableListOf<Pair<ReaderParagraph, String>>()
        var currentVoice = ""
        var currentStyle: String? = null
        var currentChars = 0

        fun flushGroup() {
            if (currentGroup.isEmpty()) return
            val lead = currentGroup.first().first
            result.add(
                SpeechChunk(
                    text = currentGroup.joinToString("\n") { it.second },
                    leadCfi = lead.cfi,
                    allCfis = currentGroup.map { it.first.cfi },
                    leadIndex = lead.index,
                    voiceId = currentVoice,
                    style = currentStyle,
                    paragraphIndices = currentGroup.map { it.first.index },
                    paragraphCharWeights = currentGroup.map { it.second.length.coerceAtLeast(1) },
                    languageCode = languageCode
                )
            )
            currentGroup = mutableListOf()
            currentChars = 0
        }

        for (para in nonBlank) {
            val spokenText = translatedTextsByCfi?.get(para.cfi)?.trim()?.takeIf { it.isNotEmpty() } ?: para.text
            val (voiceId, style) = resolveSpeakerVoiceAndStyle(
                paragraphText = para.text,
                characters = characters,
                defaultNarratorVoice = narratorVoice
            )
            if (maxChars <= 0) {
                result.add(
                    SpeechChunk(
                        text = spokenText,
                        leadCfi = para.cfi,
                        allCfis = listOf(para.cfi),
                        leadIndex = para.index,
                        voiceId = voiceId,
                        style = style,
                        paragraphIndices = listOf(para.index),
                        paragraphCharWeights = listOf(spokenText.length.coerceAtLeast(1)),
                        languageCode = languageCode
                    )
                )
                continue
            }

            val separatorLen = if (currentGroup.isEmpty()) 0 else 1
            val wouldExceed = currentGroup.isNotEmpty() &&
                (voiceId != currentVoice || style != currentStyle || currentChars + separatorLen + spokenText.length > maxChars)
            if (wouldExceed) {
                flushGroup()
            }
            if (currentGroup.isEmpty()) {
                currentVoice = voiceId
                currentStyle = style
            }
            currentGroup.add(para to spokenText)
            currentChars += separatorLen + spokenText.length
        }
        flushGroup()
        return result
    }

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
        // Apply playback speed uniformly on PcmPlayer; pass speed = 1.0f to all synthesis engines.
        val request = SynthesisRequest(
            text = chunk.text,
            voiceId = chunk.voiceId,
            style = chunk.style,
            speed = 1.0f,
            languageCode = chunk.languageCode
        )

        if (engineChoice == NarrationEngine.SYSTEM_TTS) {
            systemFallback.synthesize(request).collect { onPcmChunk(it) }
            return
        }

        val langTagSuffix = chunk.languageCode?.takeIf { it.isNotBlank() }?.let { "_$it" }.orEmpty()
        val primaryTag = when (engineChoice) {
            NarrationEngine.GEMINI_FLASH_TTS -> "GEMINI_TTS$langTagSuffix"
            NarrationEngine.GEMINI_LIVE -> "GEMINI_LIVE$langTagSuffix"
            NarrationEngine.SYSTEM_TTS -> "SYSTEM_TTS$langTagSuffix"
        }

        val cachedPrimary = diskCache?.loadAudioSegment(primaryTag, chunk.voiceId, chunk.style, chunk.text)
        if (cachedPrimary != null) {
            onPcmChunk(PcmChunk(cachedPrimary.pcmBytes, cachedPrimary.sampleRate, cachedPrimary.channels))
            return
        }

        if (engineChoice == NarrationEngine.GEMINI_FLASH_TTS) {
            val ttsExhausted = ttsAlreadyFailed || (quotaTracker?.isTtsQuotaLikelyExhausted() == true)
            if (!ttsExhausted) {
                val pcmBuffer = ByteArrayOutputStream()
                var sampleRate = 24000
                var channels = 1
                var emitted = false
                try {
                    createGeminiTtsEngine().synthesize(request).collect { pcm ->
                        if (pcm.data.isNotEmpty()) {
                            emitted = true
                            sampleRate = pcm.sampleRate
                            channels = pcm.channels
                            pcmBuffer.write(pcm.data)
                            onPcmChunk(pcm)
                        }
                    }
                    if (emitted) {
                        val bytes = pcmBuffer.toByteArray()
                        diskCache?.saveAudioSegment(
                            engineTag = primaryTag,
                            voiceId = chunk.voiceId,
                            style = chunk.style,
                            text = chunk.text,
                            sampleRate = sampleRate,
                            channels = channels,
                            pcmBytes = bytes,
                            maxBytes = maxCacheBytes
                        )
                        recordAudioUsage(isLive = false, byteCount = bytes.size, sampleRate = sampleRate, channels = channels)
                        return
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    onTtsFailed()
                }
            }

            onStatusUpdate("Free-tier TTS quota limit reached — switched to Gemini Live")
            val liveTag = "GEMINI_LIVE$langTagSuffix"
            val cachedLive = diskCache?.loadAudioSegment(liveTag, chunk.voiceId, chunk.style, chunk.text)
            if (cachedLive != null) {
                onPcmChunk(PcmChunk(cachedLive.pcmBytes, cachedLive.sampleRate, cachedLive.channels))
                return
            }

            val playedViaLive = runGeminiLiveWithTracking(
                request = request,
                chunk = chunk,
                cacheTag = liveTag,
                fallbackEngine = systemFallback,
                diskCache = diskCache,
                maxCacheBytes = maxCacheBytes,
                onSystemFallbackUsed = {
                    onStatusUpdate("Using device TTS fallback (Gemini unavailable)")
                },
                onPcmChunk = onPcmChunk
            )
            if (!playedViaLive) {
                onStatusUpdate("Using device TTS fallback (Gemini unavailable)")
            }
            return
        }

        val quotaAwareFallback = object : FeatureNarrationEngine {
            override val id = systemFallback.id
            override val capabilities = systemFallback.capabilities
            override suspend fun voices() = systemFallback.voices()
            override fun synthesize(req: SynthesisRequest) = kotlinx.coroutines.flow.flow {
                val canTryTts = !ttsAlreadyFailed &&
                    interactionsTtsClient != null &&
                    (quotaTracker?.isTtsQuotaLikelyExhausted() != true)
                if (canTryTts) {
                    try {
                        var ttsEmitted = false
                        createGeminiTtsEngine().synthesize(req).collect { pcm ->
                            if (pcm.data.isNotEmpty()) {
                                ttsEmitted = true
                                emit(pcm)
                            }
                        }
                        if (ttsEmitted) return@flow
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        onTtsFailed()
                    }
                }
                onStatusUpdate("Using device TTS fallback (Gemini Live unavailable)")
                systemFallback.synthesize(req).collect { emit(it) }
            }
        }

        runGeminiLiveWithTracking(
            request = request,
            chunk = chunk,
            cacheTag = primaryTag,
            fallbackEngine = quotaAwareFallback,
            diskCache = diskCache,
            maxCacheBytes = maxCacheBytes,
            onSystemFallbackUsed = { },
            onPcmChunk = onPcmChunk
        )
    }

    private suspend fun runGeminiLiveWithTracking(
        request: SynthesisRequest,
        chunk: SpeechChunk,
        cacheTag: String,
        fallbackEngine: FeatureNarrationEngine,
        diskCache: TranslationDiskCache?,
        maxCacheBytes: Long,
        onSystemFallbackUsed: () -> Unit,
        onPcmChunk: suspend (PcmChunk) -> Unit
    ): Boolean {
        var usedFallback = false
        val trackedFallback = object : FeatureNarrationEngine {
            override val id = fallbackEngine.id
            override val capabilities = fallbackEngine.capabilities
            override suspend fun voices() = fallbackEngine.voices()
            override fun synthesize(req: SynthesisRequest) = kotlinx.coroutines.flow.flow {
                usedFallback = true
                onSystemFallbackUsed()
                fallbackEngine.synthesize(req).collect { emit(it) }
            }
        }

        val liveEngine = GeminiLiveEngine(
            fallbackEngine = trackedFallback,
            liveSessionFactory = liveSessionFactory?.let { factory -> { factory.create() } },
            apiKeyProvider = { resolveApiKey() },
            modelProvider = { resolveModel(ModelRole.LIVE_NARRATION) },
            quotaTracker = quotaTracker,
            modelCatalogService = modelCatalogService
        )

        val pcmBuffer = ByteArrayOutputStream()
        var sampleRate = 24000
        var channels = 1
        var emitted = false

        liveEngine.synthesize(request).collect { pcm ->
            if (pcm.data.isNotEmpty()) {
                emitted = true
                sampleRate = pcm.sampleRate
                channels = pcm.channels
                if (!usedFallback) {
                    pcmBuffer.write(pcm.data)
                }
                onPcmChunk(pcm)
            }
        }

        if (emitted && !usedFallback) {
            val bytes = pcmBuffer.toByteArray()
            diskCache?.saveAudioSegment(
                engineTag = cacheTag,
                voiceId = chunk.voiceId,
                style = chunk.style,
                text = chunk.text,
                sampleRate = sampleRate,
                channels = channels,
                pcmBytes = bytes,
                maxBytes = maxCacheBytes
            )
            recordAudioUsage(isLive = true, byteCount = bytes.size, sampleRate = sampleRate, channels = channels)
            return true
        }
        return false
    }

    private fun createGeminiTtsEngine(): GeminiTtsEngine {
        return GeminiTtsEngine(
            ttsClient = interactionsTtsClient,
            apiKeyProvider = { resolveApiKey() },
            modelProvider = { resolveModel(ModelRole.TTS) },
            quotaTracker = quotaTracker
        )
    }

    private suspend fun resolveAudioCacheCapBytes(): Long {
        val settingLimit = try {
            settingsRepository?.cacheLimitBytes?.firstOrNull()
        } catch (_: Throwable) {
            null
        } ?: TranslationDiskCache.DEFAULT_MAX_AUDIO_BYTES
        return minOf(settingLimit, TranslationDiskCache.DEFAULT_MAX_AUDIO_BYTES)
    }

    private suspend fun recordAudioUsage(isLive: Boolean, byteCount: Int, sampleRate: Int, channels: Int) {
        if (byteCount <= 0 || sampleRate <= 0 || channels <= 0) return
        val bytesPerSecond = sampleRate * channels * 2.0
        val minutes = (byteCount / bytesPerSecond) / 60.0
        if (minutes <= 0.0) return
        try {
            if (isLive) {
                settingsRepository?.recordUsage(liveNarrationMinutesDelta = minutes)
            } else {
                settingsRepository?.recordUsage(ttsMinutesDelta = minutes)
            }
        } catch (_: Throwable) {
        }
    }

    private fun recalculateTimelines() {
        _sessionState.update { state ->
            withRecomputedTimelines(state, preservePositionAtCurrentPara = !state.isPlaying)
        }
    }

    private fun withRecomputedTimelines(
        state: ReaderNarrationSessionState,
        preservePositionAtCurrentPara: Boolean
    ): ReaderNarrationSessionState {
        val existingByIndex = state.paragraphTimings.associateBy { it.paragraphIndex }
        var runningMs = 0L
        val timings = state.paragraphs.map { para ->
            val prev = existingByIndex[para.index]
            val textForWeight = if (state.narrateTranslatedText) {
                state.bilingualTranslations[para.cfi]?.takeIf { it.isNotBlank() } ?: para.text
            } else {
                para.text
            }
            val duration = if (prev != null && prev.isMeasured) {
                prev.durationMs
            } else {
                estimateParagraphDurationMs(textForWeight)
            }
            val item = ParagraphTiming(
                paragraphIndex = para.index,
                cfi = para.cfi,
                startMs = runningMs,
                durationMs = duration,
                isMeasured = prev?.isMeasured == true
            )
            runningMs += duration
            item
        }

        val chapterDur = runningMs
        val currentParaTiming = timings.firstOrNull { it.paragraphIndex == state.activeParagraphIndex }
        val chapterPos = if (preservePositionAtCurrentPara) {
            currentParaTiming?.startMs ?: 0L
        } else {
            state.chapterPositionMs.coerceIn(0L, chapterDur)
        }

        val (chapterOffsetInBookMs, bookDur) = computeBookTimelineMetrics(state, chapterDur)
        val bookPos = (chapterOffsetInBookMs + chapterPos).coerceIn(0L, maxOf(bookDur, chapterPos))

        return state.copy(
            paragraphTimings = timings,
            chapterDurationMs = chapterDur,
            chapterPositionMs = chapterPos,
            bookDurationMs = bookDur,
            bookPositionMs = bookPos
        )
    }

    private fun applyMeasuredDurationToChunk(chunk: SpeechChunk, totalMeasuredMs: Long) {
        _sessionState.update { state ->
            val indicesSet = chunk.paragraphIndices.toSet()
            val totalWeight = chunk.paragraphCharWeights.sum().coerceAtLeast(1)
            val measuredMap = HashMap<Int, Long>(chunk.paragraphIndices.size)
            chunk.paragraphIndices.forEachIndexed { idx, paraIndex ->
                val weight = chunk.paragraphCharWeights.getOrElse(idx) { 1 }
                val sliceMs = ((totalMeasuredMs * weight) / totalWeight).coerceAtLeast(300L)
                measuredMap[paraIndex] = sliceMs
            }

            var cursorMs = 0L
            val updatedTimings = state.paragraphTimings.map { timing ->
                val dur = if (timing.paragraphIndex in indicesSet) {
                    measuredMap[timing.paragraphIndex] ?: timing.durationMs
                } else {
                    timing.durationMs
                }
                val item = timing.copy(
                    startMs = cursorMs,
                    durationMs = dur,
                    isMeasured = timing.isMeasured || (timing.paragraphIndex in indicesSet)
                )
                cursorMs += dur
                item
            }
            val chapterDur = cursorMs
            val (offsetMs, bookDur) = computeBookTimelineMetrics(state, chapterDur)
            val clampedChapPos = state.chapterPositionMs.coerceIn(0L, chapterDur)
            state.copy(
                paragraphTimings = updatedTimings,
                chapterDurationMs = chapterDur,
                chapterPositionMs = clampedChapPos,
                bookDurationMs = bookDur,
                bookPositionMs = (offsetMs + clampedChapPos).coerceIn(0L, maxOf(bookDur, clampedChapPos))
            )
        }
        pushExternalState()
    }

    private fun computeChapterStartInBookMs(state: ReaderNarrationSessionState): Long {
        return computeBookTimelineMetrics(state, state.chapterDurationMs).first
    }

    private fun computeBookTimelineMetrics(
        state: ReaderNarrationSessionState,
        currentChapterDurationMs: Long
    ): Pair<Long, Long> {
        val extractedChapters = state.extractedBook?.chapters.orEmpty()
        val dbChapters = state.chapters
        val totalChapters = maxOf(extractedChapters.size, dbChapters.size)
        if (totalChapters <= 0) {
            return 0L to currentChapterDurationMs
        }

        var currentChapterStart = 0L
        var totalDuration = 0L
        for (idx in 0 until totalChapters) {
            val chDur = if (idx == state.currentChapterIndex) {
                currentChapterDurationMs.coerceAtLeast(1L)
            } else {
                val extCh = extractedChapters.getOrNull(idx)
                if (extCh != null && extCh.paragraphs.isNotEmpty()) {
                    extCh.paragraphs.sumOf { estimateParagraphDurationMs(it) }
                } else {
                    val len = dbChapters.getOrNull(idx)?.textLength ?: 0
                    if (len > 0) (len * 68L).coerceAtLeast(5_000L) else 45_000L
                }
            }
            if (idx < state.currentChapterIndex) {
                currentChapterStart += chDur
            }
            totalDuration += chDur
        }
        return currentChapterStart to totalDuration
    }

    private fun estimateParagraphDurationMs(text: String): Long {
        val len = text.trim().length
        if (len == 0) return 0L
        return (len * 68L).coerceIn(1_200L, 120_000L)
    }

    private fun pushExternalState() {
        val s = _sessionState.value
        val totalChapters = maxOf(s.chapters.size, s.extractedBook?.chapters?.size ?: 0)
        _externalState.value = ExternalNarrationState(
            isActiveSession = s.isActiveSession,
            bookId = s.bookId,
            bookTitle = s.bookTitle,
            author = s.author,
            coverPath = s.coverPath,
            chapterIndex = s.currentChapterIndex,
            chapterCount = totalChapters,
            chapterTitle = s.currentChapterTitle,
            paragraphIndex = s.activeParagraphIndex,
            paragraphCount = s.paragraphs.size,
            currentCfi = s.highlightedCfi ?: s.paragraphs.getOrNull(s.activeParagraphIndex)?.cfi,
            isPlaying = s.isPlaying,
            isBuffering = s.isBuffering,
            chapterPositionMs = s.chapterPositionMs,
            chapterDurationMs = s.chapterDurationMs,
            bookPositionMs = s.bookPositionMs,
            bookDurationMs = s.bookDurationMs,
            playbackSpeed = s.playbackSpeed,
            sleepTimerMinutesRemaining = s.sleepTimerMinutesRemaining,
            stopAfterCurrentChapter = s.stopAfterCurrentChapter,
            readTranslated = s.narrateTranslatedText,
            spokenLanguageCode = if (s.narrateTranslatedText) s.targetLanguage.code else "",
            narrationEngineName = s.narrationEngine.name
        )
        playbackCoordinator?.updateExternalState(_externalState.value)
    }

    private fun persistCurrentProgress(targetBookId: String, cfi: String?) {
        if (targetBookId.isBlank() || cfi.isNullOrBlank()) return
        val s = _sessionState.value
        val totalChapters = maxOf(s.chapters.size, s.extractedBook?.chapters?.size ?: 0)
        val withinChapter = if (s.paragraphs.size <= 1) 0f else s.activeParagraphIndex.toFloat() / (s.paragraphs.size - 1)
        val bookFraction = if (totalChapters <= 1) {
            withinChapter
        } else {
            ((s.currentChapterIndex + withinChapter) / totalChapters).coerceIn(0f, 1f)
        }
        applicationScope.launch(NonCancellable) {
            try {
                val now = clock()
                playbackStateDao?.saveReaderProgress(
                    bookId = targetBookId,
                    lastCfi = cfi,
                    readingProgress = bookFraction,
                    updatedAt = now
                )
                bookDao?.updateLastReadDate(targetBookId, now)
            } catch (_: Throwable) {
            }
        }
    }

    private fun requestAudioFocusSafely() {
        val ctx = appContext ?: return
        try {
            val mgr = audioManager ?: (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager).also {
                audioManager = it
            } ?: return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            if (_sessionState.value.isPlaying) {
                                pausedDueToTransientFocusLoss = true
                                pause()
                            }
                        }
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            activePcmPlayer?.setOriginalAudioVolume(0.25f)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            activePcmPlayer?.setOriginalAudioVolume(1.0f)
                            if (pausedDueToTransientFocusLoss) {
                                pausedDueToTransientFocusLoss = false
                                play()
                            }
                        }
                    }
                }
                .build()
            audioFocusRequest = focusReq
            mgr.requestAudioFocus(focusReq)
        } catch (_: Throwable) {
        }
    }

    private fun abandonAudioFocus() {
        try {
            val mgr = audioManager ?: return
            val req = audioFocusRequest as? AudioFocusRequest ?: return
            mgr.abandonAudioFocusRequest(req)
            audioFocusRequest = null
        } catch (_: Throwable) {
        }
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

    internal fun buildParagraphsForChapter(
        extracted: EbookContentExtractor.ExtractedBook,
        chapterIndex: Int,
        formatHint: String
    ): List<ReaderParagraph> {
        val chapter = extracted.chapters.getOrNull(chapterIndex) ?: return emptyList()
        val isPdf = extracted.format.equals("PDF", ignoreCase = true) ||
            formatHint.equals("PDF", ignoreCase = true)
        return chapter.paragraphs.mapIndexedNotNull { pIndex, text ->
            val trimmed = text.trim()
            if (trimmed.isEmpty()) null
            else ReaderParagraph(
                index = pIndex,
                id = "p_${chapterIndex}_$pIndex",
                text = trimmed,
                cfi = defaultParagraphCfi(chapterIndex, pIndex, isPdf)
            )
        }
    }

    internal fun defaultChapterCfi(chapterIndex: Int, isPdf: Boolean): String {
        return if (isPdf) {
            "pdf:page:$chapterIndex"
        } else {
            "epubcfi(/6/${(chapterIndex + 1) * 2}!/4/2/1:0)"
        }
    }

    internal fun defaultParagraphCfi(chapterIndex: Int, paragraphIndex: Int, isPdf: Boolean): String {
        return if (isPdf) {
            "pdf:page:$chapterIndex:p:$paragraphIndex"
        } else {
            "epubcfi(/6/${(chapterIndex + 1) * 2}!/4/2/${paragraphIndex + 1}:0)"
        }
    }

    private fun resolveChapterTitle(
        chapters: List<ChapterEntity>,
        extracted: EbookContentExtractor.ExtractedBook?,
        chapterIndex: Int
    ): String {
        val fromDb = chapters.getOrNull(chapterIndex)?.title?.takeIf { it.isNotBlank() }
        val fromExt = extracted?.chapters?.getOrNull(chapterIndex)?.title?.takeIf { it.isNotBlank() }
        return fromDb ?: fromExt ?: "Chapter ${chapterIndex + 1}"
    }

    private fun resolveSpeakerVoiceAndStyle(
        paragraphText: String,
        characters: List<CharacterEntity>,
        defaultNarratorVoice: String
    ): Pair<String, String?> {
        if (characters.isEmpty()) return defaultNarratorVoice to null
        val colonPrefix = COLON_SPEAKER_REGEX.find(paragraphText)?.groupValues?.get(1)?.trim()
        if (!colonPrefix.isNullOrBlank()) {
            val match = characters.firstOrNull {
                it.name.equals(colonPrefix, ignoreCase = true) ||
                    it.aliases.split(",").any { alias -> alias.trim().equals(colonPrefix, ignoreCase = true) }
            }
            val matchVoice = match?.assignedVoiceId?.takeIf { it.isNotBlank() }
            if (match != null && matchVoice != null) {
                return matchVoice to match.voiceTraits.ifBlank { null }
            }
        }

        val hasQuote = paragraphText.contains('"') || paragraphText.contains('“')
        if (hasQuote) {
            val nonNarrator = characters.firstOrNull { char ->
                !char.name.equals("Narrator", ignoreCase = true) &&
                    (paragraphText.contains(char.name, ignoreCase = true) ||
                        char.aliases.split(",").any { alias ->
                            val trimmed = alias.trim()
                            trimmed.isNotEmpty() && paragraphText.contains(trimmed, ignoreCase = true)
                        })
            }
            val nonNarratorVoice = nonNarrator?.assignedVoiceId?.takeIf { it.isNotBlank() }
            if (nonNarrator != null && nonNarratorVoice != null) {
                return nonNarratorVoice to nonNarrator.voiceTraits.ifBlank { null }
            }
        }
        return defaultNarratorVoice to null
    }

    internal fun estimateTokens(sources: Collection<String>, translations: Collection<String>): Long {
        val totalChars = sources.sumOf { it.length } + translations.sumOf { it.length }
        return (totalChars / 4L).coerceAtLeast(if (totalChars > 0) 1L else 0L)
    }

    internal fun formatConciseError(error: Throwable, fallback: String): String {
        if (error is QuotaExhaustedException) {
            return error.message?.take(220) ?: "Gemini free-tier quota reached. Please wait or switch models."
        }
        val raw = error.message?.trim().orEmpty()
        if (raw.isEmpty()) return fallback
        val isRawJsonOrHuge = raw.startsWith("{") || raw.contains("\"error\"") || raw.length > 220
        if (!isRawJsonOrHuge) return raw
        return when {
            raw.contains("429") || raw.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ->
                "Gemini rate limit reached (429). Wait briefly or switch to another model in Settings."
            raw.contains("403") || raw.contains("401") ->
                "Gemini API key rejected. Verify your API key in Settings."
            else -> raw.lineSequence().firstOrNull()?.take(180) ?: fallback
        }
    }

    private fun parseNarrationEngine(name: String): NarrationEngine {
        return NarrationEngine.values().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: NarrationEngine.GEMINI_FLASH_TTS
    }

    companion object {
        private val defaultPlayerFactoryRef: () -> PcmPlayer = { AudioTrackPcmPlayer() }
        private val COLON_SPEAKER_REGEX = Regex("""^([A-Z][a-zA-Z]{1,24}(?:\s+[A-Z][a-zA-Z]{1,20})?):\s+\S""")
    }
}
