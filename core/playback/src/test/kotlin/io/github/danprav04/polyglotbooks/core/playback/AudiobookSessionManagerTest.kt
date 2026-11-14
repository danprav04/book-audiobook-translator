package io.github.danprav04.polyglotbooks.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateDao
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateEntity
import io.github.danprav04.polyglotbooks.core.data.prefs.FeatureUsageStats
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudiobookSessionManagerTest {

    private class FakeLiveTranslationBackend : LiveTranslationBackend {
        private val _state = MutableStateFlow(LiveTranslationBackendState())
        override val state: StateFlow<LiveTranslationBackendState> = _state.asStateFlow()

        var startCalls = 0
        var pauseCalls = 0
        var resumeCalls = 0
        var stopCalls = 0
        var lastStartedBookId: String? = null
        var lastStartPositionMs: Long = -1L
        var lastTargetLanguage: LanguageCode? = null
        var lastApiKey: String? = null
        var lastUpdatedPositionMs: Long = -1L
        var lastSpeed: Float = 1.0f

        override fun start(
            scope: CoroutineScope,
            bookId: String,
            filePath: String,
            targetLanguage: LanguageCode,
            apiKey: String?,
            modelId: String,
            echoTargetLanguage: Boolean,
            startPositionMs: Long,
            playbackSpeed: Float
        ) {
            startCalls++
            lastStartedBookId = bookId
            lastStartPositionMs = startPositionMs
            lastTargetLanguage = targetLanguage
            lastApiKey = apiKey
            lastSpeed = playbackSpeed
            _state.value = LiveTranslationBackendState(
                isRunning = true,
                sourcePositionMs = startPositionMs,
                bufferAheadMs = 12_000L,
                speedMultiplier = playbackSpeed
            )
        }

        override fun updatePlaybackPosition(positionMs: Long) {
            lastUpdatedPositionMs = positionMs
            _state.value = _state.value.copy(sourcePositionMs = positionMs)
        }

        override fun setPlaybackSpeed(speed: Float) {
            lastSpeed = speed
            _state.value = _state.value.copy(speedMultiplier = speed)
        }

        override fun pauseAudio() {
            pauseCalls++
        }

        override fun resumeAudio() {
            resumeCalls++
        }

        override fun stop() {
            stopCalls++
            _state.value = _state.value.copy(isRunning = false)
        }

        fun emitSubtitles(source: String, target: String, bufferMs: Long = 15_000L) {
            _state.value = _state.value.copy(
                currentSourceSubtitle = source,
                currentTranslatedSubtitle = target,
                bufferAheadMs = bufferMs
            )
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        val apiKeyFlow = MutableStateFlow<String?>("test-api-key")
        val modelMapFlow = MutableStateFlow(
            ModelRole.values().associateWith { DefaultModels.get(it) }
        )
        val targetLangFlow = MutableStateFlow(LanguageCode.RUSSIAN)
        val cacheLimitFlow = MutableStateFlow(500L * 1024L * 1024L)
        val duckLevelFlow = MutableStateFlow(0.15f)
        val echoFlow = MutableStateFlow(false)
        val usageFlow = MutableStateFlow(FeatureUsageStats())
        val lastOpenedBookFlow = MutableStateFlow<String?>(null)

        override val apiKey: Flow<String?> = apiKeyFlow
        override val modelPerRole: Flow<Map<ModelRole, String>> = modelMapFlow
        override val targetLanguage: Flow<LanguageCode> = targetLangFlow
        override val cacheLimitBytes: Flow<Long> = cacheLimitFlow
        override val duckLevel: Flow<Float> = duckLevelFlow
        override val echoTargetLanguage: Flow<Boolean> = echoFlow
        override val featureUsageStats: Flow<FeatureUsageStats> = usageFlow
        override val lastOpenedBookId: Flow<String?> = lastOpenedBookFlow

        override suspend fun saveApiKey(key: String) {
            apiKeyFlow.value = key
        }

        override suspend fun clearApiKey() {
            apiKeyFlow.value = null
        }

        override suspend fun setModel(role: ModelRole, modelId: String) {
            modelMapFlow.value = modelMapFlow.value + (role to modelId)
        }

        override suspend fun resetModelsToDefaults() {}

        override suspend fun setTargetLanguage(language: LanguageCode) {
            targetLangFlow.value = language
        }

        override suspend fun setCacheLimit(bytes: Long) {
            cacheLimitFlow.value = bytes
        }

        override suspend fun setDuckLevel(level: Float) {
            duckLevelFlow.value = level
        }

        override suspend fun setEchoTargetLanguage(echo: Boolean) {
            echoFlow.value = echo
        }

        override suspend fun setFreeTier(isFreeTier: Boolean) {}

        override suspend fun setLastOpenedBookId(bookId: String?) {
            lastOpenedBookFlow.value = bookId
        }

        override suspend fun recordUsage(
            liveTranslateMinutesDelta: Double,
            ttsMinutesDelta: Double,
            liveNarrationMinutesDelta: Double,
            textAnalysisTokensDelta: Long,
            textTranslationTokensDelta: Long
        ) {}

        override suspend fun resetUsageStats() {}
    }

    private fun sampleBook(id: String = "book-1") = BookEntity(
        id = id,
        title = "Sample Audiobook",
        author = "Author Name",
        format = "M4B",
        filePath = "/storage/emulated/0/Books/sample.m4b",
        coverUri = null,
        addedDate = 1_000L,
        lastReadDate = 1_000L,
        isAudiobook = true
    )

    private fun sampleChapters(bookId: String = "book-1") = listOf(
        ChapterEntity(
            id = "${bookId}_0",
            bookId = bookId,
            index = 0,
            title = "Chapter 1",
            startMs = 0L,
            endMs = 30_000L,
            cfiRange = null,
            textLength = 0
        ),
        ChapterEntity(
            id = "${bookId}_1",
            bookId = bookId,
            index = 1,
            title = "Chapter 2",
            startMs = 30_000L,
            endMs = 90_000L,
            cfiRange = null,
            textLength = 0
        )
    )

    @Test
    fun `loadBook restores state before playback and guards against pre-seek 0L overwrite`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val chapterDao = mockk<ChapterDao>(relaxed = true)
        val playbackStateDao = mockk<PlaybackStateDao>(relaxed = true)
        val settingsRepo = FakeSettingsRepository()
        val backend = FakeLiveTranslationBackend()
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)

        coEvery { bookDao.getBookById("book-1") } returns sampleBook("book-1")
        every { chapterDao.getChaptersForBook("book-1") } returns flowOf(sampleChapters("book-1"))
        coEvery { playbackStateDao.getPlaybackState("book-1") } returns PlaybackStateEntity(
            bookId = "book-1",
            chapterIndex = 1,
            positionMs = 45_000L,
            durationMs = 90_000L,
            playbackSpeed = 1.5f,
            isLiveTranslationActive = true,
            isDuckingEnabled = true,
            targetLanguageCode = LanguageCode.SPANISH.code,
            lastPauseTimestamp = 0L, // Abrupt shutdown (not explicit pause)
            updatedAt = 5_000L
        )

        // Simulate ExoPlayer reporting 0L before initial seek finishes
        var simulatedPlayerPos = 0L
        every { exoPlayer.currentPosition } answers { simulatedPlayerPos }
        every { exoPlayer.duration } returns 90_000L
        every { exoPlayer.playbackState } returns Player.STATE_BUFFERING

        val manager = AudiobookSessionManager(
            bookDao = bookDao,
            chapterDao = chapterDao,
            playbackStateDao = playbackStateDao,
            settingsRepository = settingsRepo,
            liveTranslationBackend = backend,
            applicationScope = backgroundScope
        )
        manager.clock = { 500_000L }
        manager.attachPlayer(exoPlayer)

        manager.loadBook("book-1")
        runCurrent()

        val state = manager.state.value
        assertEquals("book-1", state.bookId)
        assertEquals("Sample Audiobook", state.bookTitle)
        assertEquals(1, state.currentChapterIndex)
        assertEquals(45_000L, state.currentPositionMs)
        assertEquals(1.5f, state.playbackSpeed)
        assertTrue(state.isLiveTranslationActive)
        assertTrue(state.isDuckingEnabled)
        assertEquals(LanguageCode.SPANISH, state.targetLanguage)
        assertTrue(state.isPrepared)
        assertEquals("book-1", settingsRepo.lastOpenedBookFlow.value)

        // Poll while ExoPlayer still reports 0L (pre-seek): must NOT overwrite 45_000L with 0L!
        manager.pollPlayerPositionOnce()
        manager.flushProgressNow()
        runCurrent()

        assertEquals(45_000L, manager.state.value.currentPositionMs)
        coVerify {
            playbackStateDao.saveAudioProgress(
                bookId = "book-1",
                chapterIndex = 1,
                positionMs = 45_000L,
                durationMs = 90_000L,
                updatedAt = 500_000L
            )
        }

        // Now ExoPlayer completes seek and advances to 46_200L
        every { exoPlayer.playbackState } returns Player.STATE_READY
        simulatedPlayerPos = 46_200L
        manager.pollPlayerPositionOnce()
        runCurrent()

        assertFalse(manager.isInitialSeekPending)
        assertEquals(46_200L, manager.state.value.currentPositionMs)

        // Calling loadBook("book-1") again when already loaded & prepared is a NO-OP!
        manager.loadBook("book-1")
        runCurrent()
        assertEquals(46_200L, manager.state.value.currentPositionMs)
        coVerify(exactly = 1) { bookDao.getBookById("book-1") }
    }

    @Test
    fun `2s rewind applies only after explicit pause over 60s and not after abrupt shutdown`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val chapterDao = mockk<ChapterDao>(relaxed = true)
        val playbackStateDao = mockk<PlaybackStateDao>(relaxed = true)

        coEvery { bookDao.getBookById("book-pause") } returns sampleBook("book-pause")
        every { chapterDao.getChaptersForBook("book-pause") } returns flowOf(sampleChapters("book-pause"))
        coEvery { playbackStateDao.getPlaybackState("book-pause") } returns PlaybackStateEntity(
            bookId = "book-pause",
            chapterIndex = 1,
            positionMs = 50_000L,
            durationMs = 90_000L,
            lastPauseTimestamp = 100_000L // Paused at t=100s
        )

        val manager = AudiobookSessionManager(
            bookDao = bookDao,
            chapterDao = chapterDao,
            playbackStateDao = playbackStateDao,
            applicationScope = backgroundScope
        )
        // Resume at t=170s (70s > 60s threshold) -> should rewind 2000ms to 48_000L
        manager.clock = { 170_000L }
        manager.loadBook("book-pause")
        runCurrent()

        assertEquals(48_000L, manager.state.value.currentPositionMs)
    }

    @Test
    fun `volume ducking and live translation sync with playback and noisy broadcast`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val chapterDao = mockk<ChapterDao>(relaxed = true)
        val playbackStateDao = mockk<PlaybackStateDao>(relaxed = true)
        val settingsRepo = FakeSettingsRepository()
        val backend = FakeLiveTranslationBackend()
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)

        coEvery { bookDao.getBookById("book-1") } returns sampleBook("book-1")
        every { chapterDao.getChaptersForBook("book-1") } returns flowOf(sampleChapters("book-1"))
        coEvery { playbackStateDao.getPlaybackState("book-1") } returns null

        var playerVolume = 1.0f
        every { exoPlayer.volume = any() } answers { playerVolume = firstArg() }
        every { exoPlayer.volume } answers { playerVolume }
        every { exoPlayer.currentPosition } returns 10_000L
        every { exoPlayer.duration } returns 90_000L

        val manager = AudiobookSessionManager(
            bookDao = bookDao,
            chapterDao = chapterDao,
            playbackStateDao = playbackStateDao,
            settingsRepository = settingsRepo,
            liveTranslationBackend = backend,
            applicationScope = backgroundScope
        )
        manager.attachPlayer(exoPlayer)
        manager.loadBook("book-1")
        runCurrent()

        // Default: isLiveTranslationActive = true, isDuckingEnabled = false -> volume = 0f
        assertEquals(0.0f, playerVolume, 0.001f)

        // Enable ducking -> volume = duckLevel (0.15f)
        manager.toggleDucking()
        runCurrent()
        assertTrue(manager.state.value.isDuckingEnabled)
        assertEquals(0.15f, playerVolume, 0.001f)

        // Reactive duckLevel update from SettingsRepository -> volume = 0.30f
        settingsRepo.setDuckLevel(0.30f)
        runCurrent()
        assertEquals(0.30f, playerVolume, 0.001f)

        // Start playing -> starts LiveTranslationBackend
        manager.play()
        runCurrent()
        assertTrue(manager.state.value.isPlaying)
        assertEquals(1, backend.startCalls)
        assertEquals("book-1", backend.lastStartedBookId)

        // Subtitles and bufferAheadMs propagate to AudiobookServiceState
        backend.emitSubtitles("Hello world", "Привет мир", 22_000L)
        runCurrent()
        assertEquals("Hello world", manager.state.value.sourceSubtitle)
        assertEquals("Привет мир", manager.state.value.targetSubtitle)
        assertEquals(22_000L, manager.state.value.bufferAheadMs)

        // Periodic checkpoint every 2000ms while playing
        advanceTimeBy(2_100L)
        runCurrent()
        coVerify(atLeast = 1) {
            playbackStateDao.saveAudioProgress(
                bookId = "book-1",
                chapterIndex = any(),
                positionMs = any(),
                durationMs = any(),
                updatedAt = any()
            )
        }

        // Headphone disconnect (ACTION_AUDIO_BECOMING_NOISY) pauses both translated PCM and ExoPlayer
        manager.onAudioBecomingNoisy()
        runCurrent()
        assertFalse(manager.state.value.isPlaying)
        assertTrue(backend.pauseCalls >= 1)
        verify { exoPlayer.pause() }

        // Disable live translation -> volume restores to 1.0f and backend stops
        manager.toggleLiveTranslation()
        runCurrent()
        assertFalse(manager.state.value.isLiveTranslationActive)
        assertEquals(1.0f, playerVolume, 0.001f)
        assertEquals(1, backend.stopCalls)
    }

    @Test
    fun `pending loadBook queues when invoked before PolyglotPlaybackService attaches`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val chapterDao = mockk<ChapterDao>(relaxed = true)
        val playbackStateDao = mockk<PlaybackStateDao>(relaxed = true)
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)
        val service = mockk<PolyglotPlaybackService>(relaxed = true)
        every { service.exoPlayer } returns exoPlayer

        coEvery { bookDao.getBookById("book-queued") } returns sampleBook("book-queued")
        every { chapterDao.getChaptersForBook("book-queued") } returns flowOf(sampleChapters("book-queued"))
        coEvery { playbackStateDao.getPlaybackState("book-queued") } returns PlaybackStateEntity(
            bookId = "book-queued",
            positionMs = 15_000L,
            durationMs = 90_000L
        )

        val manager = AudiobookSessionManager(
            bookDao = bookDao,
            chapterDao = chapterDao,
            playbackStateDao = playbackStateDao,
            applicationScope = backgroundScope
        )

        // Call loadBook before service is attached
        manager.loadBook("book-queued")
        runCurrent()
        assertEquals("book-queued", manager.state.value.bookId)
        assertEquals(15_000L, manager.state.value.currentPositionMs)

        // Now service finishes starting and attaches
        manager.attachService(service)
        runCurrent()

        verify { exoPlayer.prepare() }
        verify { exoPlayer.seekTo(15_000L) }
    }

    @Test
    fun `play with missing API key blocks original audio playback and clears error when key is saved`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val chapterDao = mockk<ChapterDao>(relaxed = true)
        val playbackStateDao = mockk<PlaybackStateDao>(relaxed = true)
        val settingsRepo = FakeSettingsRepository().apply { apiKeyFlow.value = "" }
        val backend = FakeLiveTranslationBackend()
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)

        coEvery { bookDao.getBookById("book-nokey") } returns sampleBook("book-nokey")
        every { chapterDao.getChaptersForBook("book-nokey") } returns flowOf(sampleChapters("book-nokey"))
        coEvery { playbackStateDao.getPlaybackState("book-nokey") } returns null

        val manager = AudiobookSessionManager(
            bookDao = bookDao,
            chapterDao = chapterDao,
            playbackStateDao = playbackStateDao,
            settingsRepository = settingsRepo,
            liveTranslationBackend = backend,
            applicationScope = backgroundScope
        )
        manager.attachPlayer(exoPlayer)
        manager.loadBook("book-nokey")
        runCurrent()

        manager.play()
        runCurrent()

        assertFalse(manager.state.value.isPlaying)
        assertEquals(NoOpLiveTranslationBackend.MISSING_API_KEY_MESSAGE, manager.state.value.errorMessage)
        verify(exactly = 0) { exoPlayer.play() }

        // Save API key -> error clears and subsequent Play succeeds
        settingsRepo.saveApiKey("AIza-valid-key")
        runCurrent()
        assertNull(manager.state.value.errorMessage)

        manager.play()
        runCurrent()
        assertTrue(manager.state.value.isPlaying)
        assertEquals(1, backend.startCalls)
        verify(exactly = 1) { exoPlayer.play() }
    }

    @Test
    fun `seeking and chapter switching while playing restarts live translation at new position`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val chapterDao = mockk<ChapterDao>(relaxed = true)
        val playbackStateDao = mockk<PlaybackStateDao>(relaxed = true)
        val settingsRepo = FakeSettingsRepository()
        val backend = FakeLiveTranslationBackend()
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)

        val threeChapters = listOf(
            ChapterEntity("c0", "book-seek", 0, "Ch 1", 0L, 30_000L, null, 0),
            ChapterEntity("c1", "book-seek", 1, "Ch 2", 30_000L, 60_000L, null, 0),
            ChapterEntity("c2", "book-seek", 2, "Ch 3", 60_000L, 90_000L, null, 0)
        )
        coEvery { bookDao.getBookById("book-seek") } returns sampleBook("book-seek")
        every { chapterDao.getChaptersForBook("book-seek") } returns flowOf(threeChapters)
        coEvery { playbackStateDao.getPlaybackState("book-seek") } returns null

        val manager = AudiobookSessionManager(
            bookDao = bookDao,
            chapterDao = chapterDao,
            playbackStateDao = playbackStateDao,
            settingsRepository = settingsRepo,
            liveTranslationBackend = backend,
            applicationScope = backgroundScope
        )
        manager.seekRestartDebounceMs = 0L
        manager.attachPlayer(exoPlayer)
        manager.loadBook("book-seek")
        runCurrent()

        manager.play()
        runCurrent()
        backend.emitSubtitles("Old source", "Старые субтитры", 18_000L)
        runCurrent()
        assertEquals("Old source", manager.state.value.sourceSubtitle)

        manager.seekTo(42_000L)
        runCurrent()

        assertEquals(42_000L, manager.state.value.currentPositionMs)
        assertEquals("", manager.state.value.sourceSubtitle)
        assertEquals("", manager.state.value.targetSubtitle)
        assertEquals(12_000L, manager.state.value.bufferAheadMs)
        assertEquals(1, backend.stopCalls)
        assertEquals(2, backend.startCalls)
        assertEquals(42_000L, backend.lastStartPositionMs)

        manager.selectChapter(2) // startMs = 60_000L
        runCurrent()
        assertEquals(2, manager.state.value.currentChapterIndex)
        assertEquals(60_000L, manager.state.value.currentPositionMs)
        assertEquals(2, backend.stopCalls)
        assertEquals(3, backend.startCalls)
        assertEquals(60_000L, backend.lastStartPositionMs)
    }

    @Test
    fun `STATE_READY updates last chapter endMs for multi-chapter book and persists to Room`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val chapterDao = mockk<ChapterDao>(relaxed = true)
        val playbackStateDao = mockk<PlaybackStateDao>(relaxed = true)
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)
        val listenerSlot = slot<Player.Listener>()
        every { exoPlayer.addListener(capture(listenerSlot)) } returns Unit
        every { exoPlayer.duration } returns 125_000L

        coEvery { bookDao.getBookById("book-dur") } returns sampleBook("book-dur")
        every { chapterDao.getChaptersForBook("book-dur") } returns flowOf(sampleChapters("book-dur"))
        coEvery { playbackStateDao.getPlaybackState("book-dur") } returns null

        val manager = AudiobookSessionManager(
            bookDao = bookDao,
            chapterDao = chapterDao,
            playbackStateDao = playbackStateDao,
            applicationScope = backgroundScope
        )
        manager.attachPlayer(exoPlayer)
        manager.loadBook("book-dur")
        runCurrent()

        listenerSlot.captured.onPlaybackStateChanged(Player.STATE_READY)
        runCurrent()

        assertEquals(125_000L, manager.state.value.durationMs)
        assertEquals(125_000L, manager.state.value.chapters.last().endMs)
        coVerify {
            chapterDao.insertChapters(match { list -> list.size == 1 && list[0].endMs == 125_000L })
        }
    }

    private class InMemoryPlaybackStateDao(
        initial: Map<String, PlaybackStateEntity> = emptyMap()
    ) : PlaybackStateDao {
        private val store = MutableStateFlow(initial)
        val audioProgressSaves = mutableListOf<PlaybackStateEntity>()

        override suspend fun getPlaybackState(bookId: String): PlaybackStateEntity? =
            store.value[bookId]

        override fun observePlaybackState(bookId: String): Flow<PlaybackStateEntity?> =
            store.map { it[bookId] }

        override fun observeAllPlaybackStates(): Flow<List<PlaybackStateEntity>> =
            store.map { it.values.toList() }

        override suspend fun upsertPlaybackState(state: PlaybackStateEntity) {
            store.value = store.value + (state.bookId to state)
            audioProgressSaves += state
        }

        override suspend fun updateAudioProgress(
            bookId: String,
            chapterIndex: Int,
            positionMs: Long,
            durationMs: Long,
            updatedAt: Long
        ): Int {
            val existing = store.value[bookId] ?: return 0
            val updated = existing.copy(
                chapterIndex = chapterIndex,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = updatedAt
            )
            store.value = store.value + (bookId to updated)
            audioProgressSaves += updated
            return 1
        }

        override suspend fun updateReaderProgress(
            bookId: String,
            lastCfi: String?,
            readingProgress: Float,
            updatedAt: Long
        ): Int {
            val existing = store.value[bookId] ?: return 0
            store.value = store.value + (
                bookId to existing.copy(
                    lastCfi = lastCfi,
                    readingProgress = readingProgress,
                    updatedAt = updatedAt
                )
            )
            return 1
        }

        override suspend fun updatePlaybackSettings(
            bookId: String,
            playbackSpeed: Float,
            isLiveTranslationActive: Boolean,
            isDuckingEnabled: Boolean,
            targetLanguageCode: String?,
            updatedAt: Long
        ): Int {
            val existing = store.value[bookId] ?: return 0
            store.value = store.value + (
                bookId to existing.copy(
                    playbackSpeed = playbackSpeed,
                    isLiveTranslationActive = isLiveTranslationActive,
                    isDuckingEnabled = isDuckingEnabled,
                    targetLanguageCode = targetLanguageCode,
                    updatedAt = updatedAt
                )
            )
            return 1
        }

        override suspend fun updateLastPauseTimestamp(
            bookId: String,
            timestamp: Long,
            updatedAt: Long
        ): Int {
            val existing = store.value[bookId] ?: return 0
            store.value = store.value + (
                bookId to existing.copy(
                    lastPauseTimestamp = timestamp,
                    updatedAt = updatedAt
                )
            )
            return 1
        }

        override suspend fun deletePlaybackState(bookId: String) {
            store.value = store.value - bookId
        }
    }

    @Test
    fun `switching Book1 to Book2 and back preserves Book1 exact position and pauseTimestamp`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val chapterDao = mockk<ChapterDao>(relaxed = true)
        val playbackStateDao = InMemoryPlaybackStateDao()
        val settingsRepo = FakeSettingsRepository()
        val backend = FakeLiveTranslationBackend()
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)
        val listenerSlot = slot<Player.Listener>()
        every { exoPlayer.addListener(capture(listenerSlot)) } returns Unit

        coEvery { bookDao.getBookById("book-1") } returns sampleBook("book-1").copy(title = "First Book")
        coEvery { bookDao.getBookById("book-2") } returns sampleBook("book-2").copy(title = "Second Book")
        every { chapterDao.getChaptersForBook("book-1") } returns flowOf(sampleChapters("book-1"))
        every { chapterDao.getChaptersForBook("book-2") } returns flowOf(sampleChapters("book-2"))

        var activeMediaId = ""
        var activePlayerPosMs = 0L
        var activePlayerDurMs = 90_000L
        every { exoPlayer.setMediaItem(any(), any<Long>()) } answers {
            val item = firstArg<MediaItem>()
            activeMediaId = item.mediaId
            activePlayerPosMs = secondArg()
            activePlayerDurMs = if (activeMediaId == "book-1") 90_000L else 120_000L
        }
        every { exoPlayer.seekTo(any<Long>()) } answers {
            activePlayerPosMs = firstArg()
        }
        every { exoPlayer.currentPosition } answers { activePlayerPosMs }
        every { exoPlayer.duration } answers { activePlayerDurMs }
        every { exoPlayer.playbackState } returns Player.STATE_READY

        var currentTimeMs = 100_000L
        val manager = AudiobookSessionManager(
            bookDao = bookDao,
            chapterDao = chapterDao,
            playbackStateDao = playbackStateDao,
            settingsRepository = settingsRepo,
            liveTranslationBackend = backend,
            applicationScope = backgroundScope
        )
        manager.clock = { currentTimeMs }
        manager.attachPlayer(exoPlayer)

        // 1. Open Book 1 and listen until 45_000L (Chapter 1)
        manager.loadBook("book-1")
        runCurrent()
        manager.play()
        runCurrent()
        activePlayerPosMs = 45_000L
        currentTimeMs = 110_000L

        // 2. Switch directly to Book 2 while Book 1 is playing -> Book 1's 45_000L & pauseTimestamp must persist
        manager.loadBook("book-2")
        runCurrent()

        val savedBook1AfterSwitch = playbackStateDao.getPlaybackState("book-1")!!
        assertEquals(45_000L, savedBook1AfterSwitch.positionMs)
        assertEquals(1, savedBook1AfterSwitch.chapterIndex)
        assertEquals(90_000L, savedBook1AfterSwitch.durationMs)
        assertEquals(110_000L, savedBook1AfterSwitch.lastPauseTimestamp)
        assertEquals("book-2", manager.state.value.bookId)
        assertEquals(0L, manager.state.value.currentPositionMs)
        assertFalse(manager.state.value.isPlaying)

        // 3. Listen to Book 2 until 22_000L (Chapter 0), pause explicitly at t=120_000L
        manager.play()
        runCurrent()
        activePlayerPosMs = 22_000L
        currentTimeMs = 120_000L
        manager.pause()
        runCurrent()

        // 4. Reload Book 1 at t=130_000L (20s since Book 1 paused < 60s rewind threshold -> exact 45_000L)
        currentTimeMs = 130_000L
        manager.loadBook("book-1")
        runCurrent()

        // Book 2 retained its position and preserved its earlier pause timestamp (120_000L, not 130_000L)
        val savedBook2AfterSwitch = playbackStateDao.getPlaybackState("book-2")!!
        assertEquals(22_000L, savedBook2AfterSwitch.positionMs)
        assertEquals(0, savedBook2AfterSwitch.chapterIndex)
        assertEquals(120_000L, savedBook2AfterSwitch.lastPauseTimestamp)

        // Book 1 restored exact position, chapter, duration, and pause timestamp
        assertEquals("book-1", manager.state.value.bookId)
        assertEquals(45_000L, manager.state.value.currentPositionMs)
        assertEquals(1, manager.state.value.currentChapterIndex)
        assertEquals(90_000L, manager.state.value.durationMs)
        assertEquals(110_000L, manager.lastPauseTimestamp)
        assertEquals("book-1", activeMediaId)
        assertEquals(45_000L, activePlayerPosMs)
    }

    @Test
    fun `onIsPlayingChanged false during book handoff does not flush Book1 position under Book2 ID`() = runTest {
        val bookDao = mockk<BookDao>(relaxed = true)
        val chapterDao = mockk<ChapterDao>(relaxed = true)
        val playbackStateDao = InMemoryPlaybackStateDao(
            initial = mapOf(
                "book-2" to PlaybackStateEntity(
                    bookId = "book-2",
                    chapterIndex = 0,
                    positionMs = 12_000L,
                    durationMs = 80_000L,
                    updatedAt = 1_000L
                )
            )
        )
        val settingsRepo = FakeSettingsRepository()
        val backend = FakeLiveTranslationBackend()
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)
        val listenerSlot = slot<Player.Listener>()
        every { exoPlayer.addListener(capture(listenerSlot)) } returns Unit

        val book2LoadGate = CompletableDeferred<BookEntity>()
        coEvery { bookDao.getBookById("book-1") } returns sampleBook("book-1")
        coEvery { bookDao.getBookById("book-2") } coAnswers { book2LoadGate.await() }
        every { chapterDao.getChaptersForBook("book-1") } returns flowOf(sampleChapters("book-1"))
        every { chapterDao.getChaptersForBook("book-2") } returns flowOf(sampleChapters("book-2"))

        var simulatedPlayerPos = 0L
        var simulatedDuration = 90_000L
        every { exoPlayer.setMediaItem(any(), any<Long>()) } answers {
            val item = firstArg<MediaItem>()
            simulatedPlayerPos = secondArg()
            simulatedDuration = if (item.mediaId == "book-1") 90_000L else 80_000L
        }
        every { exoPlayer.seekTo(any<Long>()) } answers {
            simulatedPlayerPos = firstArg()
        }
        every { exoPlayer.currentPosition } answers { simulatedPlayerPos }
        every { exoPlayer.duration } answers { simulatedDuration }
        every { exoPlayer.playbackState } returns Player.STATE_READY

        val manager = AudiobookSessionManager(
            bookDao = bookDao,
            chapterDao = chapterDao,
            playbackStateDao = playbackStateDao,
            settingsRepository = settingsRepo,
            liveTranslationBackend = backend,
            applicationScope = backgroundScope
        )
        manager.clock = { 200_000L }
        manager.attachPlayer(exoPlayer)

        // Load and play Book 1 up to 45_000L
        manager.loadBook("book-1")
        runCurrent()
        manager.play()
        runCurrent()
        simulatedPlayerPos = 45_000L

        // Start switching to Book 2 while book-2 DB lookup is still suspended
        manager.loadBook("book-2")

        // Synchronously inside UNDISPATCHED loadBook block: bookId updated and isPlaying cleared
        assertEquals("book-2", manager.state.value.bookId)
        assertFalse(manager.state.value.isPlaying)

        // Simulate stale ExoPlayer onIsPlayingChanged(false) arriving during handoff before setMediaItem
        listenerSlot.captured.onIsPlayingChanged(false)
        runCurrent()

        // Complete Book 2 DB load
        book2LoadGate.complete(sampleBook("book-2"))
        runCurrent()

        // Book 1 saved its 45_000L, and Book 2 NEVER got overwritten with Book 1's 45_000L
        assertEquals(45_000L, playbackStateDao.getPlaybackState("book-1")?.positionMs)
        assertEquals(12_000L, playbackStateDao.getPlaybackState("book-2")?.positionMs)
        assertEquals(12_000L, manager.state.value.currentPositionMs)
        assertTrue(
            "Book 2 must never receive Book 1's 45_000L position",
            playbackStateDao.audioProgressSaves.none { it.bookId == "book-2" && it.positionMs == 45_000L }
        )
    }
}
