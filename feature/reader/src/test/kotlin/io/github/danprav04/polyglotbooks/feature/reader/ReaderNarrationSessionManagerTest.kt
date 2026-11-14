package io.github.danprav04.polyglotbooks.feature.reader

import io.github.danprav04.polyglotbooks.core.audio.PcmPlayer
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.InteractionsTtsClient
import io.github.danprav04.polyglotbooks.core.gemini.TextModelClient
import io.github.danprav04.polyglotbooks.core.gemini.TtsRequest
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.core.playback.ActivePlaybackSource
import io.github.danprav04.polyglotbooks.core.playback.PlaybackCoordinator
import io.github.danprav04.polyglotbooks.feature.narration.EngineCapabilities
import io.github.danprav04.polyglotbooks.feature.narration.EngineId
import io.github.danprav04.polyglotbooks.feature.narration.SynthesisRequest
import io.github.danprav04.polyglotbooks.feature.narration.VoiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import io.github.danprav04.polyglotbooks.feature.narration.NarrationEngine as FeatureNarrationEngine

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderNarrationSessionManagerTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingPcmPlayer(
        private val writeGate: CompletableDeferred<Unit>? = null
    ) : PcmPlayer {
        private val _currentPositionMs = MutableStateFlow(0L)
        override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

        val recordedSpeeds = mutableListOf<Float>()
        val writtenChunks = mutableListOf<PcmChunk>()
        var stopped = false

        override fun init(sampleRate: Int, channels: Int) {
            _currentPositionMs.value = 0L
        }

        override fun write(chunk: PcmChunk): Int {
            writeGate?.let { gate ->
                while (!gate.isCompleted) {
                    Thread.sleep(5)
                }
            }
            writtenChunks.add(chunk)
            _currentPositionMs.value += 100L
            return chunk.data.size
        }

        override fun play() {}
        override fun pause() {}
        override fun stop() {
            stopped = true
        }
        override fun release() {}
        override fun setPlaybackSpeed(speed: Float) {
            recordedSpeeds.add(speed)
        }
        override fun setOriginalAudioVolume(volume: Float) {}
    }

    private class CapturingSystemEngine : FeatureNarrationEngine {
        override val id: EngineId = EngineId.SYSTEM_TTS
        override val capabilities = EngineCapabilities(
            supportsMultiVoice = false,
            supportsEmotionalStyles = false,
            supportsStreaming = false,
            outputSampleRate = 24000
        )

        val requests = mutableListOf<SynthesisRequest>()

        override suspend fun voices(): List<VoiceInfo> = emptyList()

        override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
            requests.add(request)
            emit(PcmChunk(byteArrayOf(1, 2, 3, 4, 5, 6), sampleRate = 24000, channels = 1))
        }
    }

    @Test
    fun `narrating translated text translates on demand passes languageCode and applies speed uniformly via PcmPlayer`() = runTest {
        val tempDir = Files.createTempDirectory("session-translated-test").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setTargetLanguage(LanguageCode.SPANISH)
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
                setReaderReadTranslated(true)
                setReaderNarrationSpeed(1.5f)
            }
            val keyStore = object : SecureKeyStore {
                override suspend fun getApiKey(): String = "api-key-1"
                override suspend fun saveApiKey(key: String) {}
                override suspend fun deleteApiKey() {}
            }
            var translateCalls = 0
            val textClient = object : TextModelClient {
                override suspend fun generateContent(
                    apiKey: String,
                    prompt: String,
                    systemInstruction: String?,
                    responseJsonSchema: String?,
                    model: String
                ): String {
                    translateCalls++
                    return """["Hola mundo.","Buenos dias."]"""
                }
            }
            val fakePlayer = RecordingPcmPlayer()
            val capturingEngine = CapturingSystemEngine()

            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                playbackStateDao = FakePlaybackStateDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = keyStore,
                textModelClient = textClient,
                quotaTracker = GeminiQuotaTracker(),
                applicationScope = backgroundScope
            ).apply {
                testDiskCache = TranslationDiskCache(tempDir)
                ioDispatcher = StandardTestDispatcher(testScheduler)
                playerFactory = { fakePlayer }
                systemTtsFactory = { capturingEngine }
            }
            advanceUntilIdle()

            val paragraphs = listOf(
                ReaderParagraph(0, "p0", "Hello world.", "epubcfi(/6/2!/4/2/1:0)"),
                ReaderParagraph(1, "p1", "Good morning.", "epubcfi(/6/2!/4/2/2:0)")
            )
            manager.startOrResume(
                bookId = "book-1",
                bookTitle = "Dual Book",
                paragraphs = paragraphs,
                chapters = listOf(ChapterEntity("c0", "book-1", 0, "Ch 1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 25))
            )
            advanceUntilIdle()

            assertEquals(1, translateCalls)
            assertEquals(2, capturingEngine.requests.size)
            assertEquals("Hola mundo.", capturingEngine.requests[0].text)
            assertEquals("Buenos dias.", capturingEngine.requests[1].text)
            // Engine receives speed=1.0f; playback speed is applied uniformly via PcmPlayer
            assertTrue(capturingEngine.requests.all { it.speed == 1.0f })
            assertTrue(capturingEngine.requests.all { it.languageCode == "es" })
            assertTrue(fakePlayer.recordedSpeeds.contains(1.5f))

            // Changing speed mid-session persists and updates settings without re-translating
            manager.setPlaybackSpeed(1.75f)
            advanceUntilIdle()
            assertEquals(1.75f, settingsRepo.readerNarrationSpeed.first())
            assertEquals(1.75f, manager.sessionState.value.playbackSpeed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `narrating translated text pauses cleanly on translation failure without speaking wrong language`() = runTest {
        val settingsRepo = FakeSettingsRepository().apply {
            setTargetLanguage(LanguageCode.FRENCH)
            setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
            setReaderReadTranslated(true)
        }
        val capturingEngine = CapturingSystemEngine()
        val fakePlayer = RecordingPcmPlayer()

        val manager = ReaderNarrationSessionManager(
            bookDao = FakeBookDao(),
            chapterDao = FakeChapterDao(),
            settingsRepository = settingsRepo,
            secureKeyStore = null, // No API key!
            textModelClient = null,
            applicationScope = backgroundScope
        ).apply {
            ioDispatcher = StandardTestDispatcher(testScheduler)
            playerFactory = { fakePlayer }
            systemTtsFactory = { capturingEngine }
        }
        advanceUntilIdle()

        manager.startOrResume(
            bookId = "book-fail",
            bookTitle = "Untranslated Book",
            paragraphs = listOf(ReaderParagraph(0, "p0", "Original English text.", "epubcfi(/6/2!/4/2/1:0)"))
        )
        advanceUntilIdle()

        assertFalse(manager.sessionState.value.isPlaying)
        assertNotNull(manager.sessionState.value.errorMessage)
        assertTrue(capturingEngine.requests.isEmpty())
    }

    @Test
    fun `timeline navigation seek and sleep timer end of chapter stop cleanly`() = runTest {
        val settingsRepo = FakeSettingsRepository().apply {
            setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
        }
        val playbackDao = FakePlaybackStateDao()
        val bookDao = FakeBookDao()
        val extracted = EbookContentExtractor.ExtractedBook(
            format = "EPUB",
            title = "Multi Chapter",
            author = "Author",
            pageCount = 0,
            chapters = listOf(
                EbookContentExtractor.ExtractedChapter(0, "Ch 1", "ch1.html", "", listOf("One.", "Two.")),
                EbookContentExtractor.ExtractedChapter(1, "Ch 2", "ch2.html", "", listOf("Three."))
            )
        )
        val chapters = listOf(
            ChapterEntity("c0", "book-multi", 0, "Ch 1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 8),
            ChapterEntity("c1", "book-multi", 1, "Ch 2", 1000L, 2000L, "epubcfi(/6/4!/4/2/1:0)", 6)
        )

        val capturingEngine = CapturingSystemEngine()
        val manager = ReaderNarrationSessionManager(
            bookDao = bookDao,
            chapterDao = FakeChapterDao(),
            playbackStateDao = playbackDao,
            settingsRepository = settingsRepo,
            applicationScope = backgroundScope
        ).apply {
            ioDispatcher = StandardTestDispatcher(testScheduler)
            playerFactory = { RecordingPcmPlayer() }
            systemTtsFactory = { capturingEngine }
        }
        advanceUntilIdle()

        val ch0Paras = manager.buildParagraphsForChapter(extracted, 0, "EPUB")
        manager.updateBookContext(
            bookId = "book-multi",
            bookTitle = "Multi Chapter",
            extractedBook = extracted,
            chapters = chapters,
            currentChapterIndex = 0,
            paragraphs = ch0Paras
        )

        assertTrue(manager.sessionState.value.chapterDurationMs > 0L)
        assertTrue(manager.sessionState.value.bookDurationMs >= manager.sessionState.value.chapterDurationMs)

        // Seek to second paragraph via timeline position
        val secondParaStart = manager.sessionState.value.paragraphTimings[1].startMs
        manager.seekToChapterPositionMs(secondParaStart)
        assertEquals(1, manager.sessionState.value.activeParagraphIndex)
        manager.previousParagraph()
        assertEquals(0, manager.sessionState.value.activeParagraphIndex)

        // Enable "End of Chapter" sleep timer: stops cleanly after Ch 1 instead of advancing to Ch 2
        manager.setSleepTimer(-1)
        assertTrue(manager.sessionState.value.stopAfterCurrentChapter)

        manager.startOrResume(
            bookId = "book-multi",
            bookTitle = "Multi Chapter",
            extractedBook = extracted,
            chapters = chapters,
            currentChapterIndex = 0,
            paragraphs = ch0Paras
        )
        advanceUntilIdle()

        assertEquals(0, manager.sessionState.value.currentChapterIndex)
        assertFalse(manager.sessionState.value.isPlaying)
        assertFalse(manager.sessionState.value.stopAfterCurrentChapter)
        assertEquals(2, capturingEngine.requests.size)

        // With sleep timer off, playback auto-advances from Ch 1 through Ch 2 and persists progress!
        capturingEngine.requests.clear()
        manager.startOrResume(
            bookId = "book-multi",
            bookTitle = "Multi Chapter",
            extractedBook = extracted,
            chapters = chapters,
            currentChapterIndex = 0,
            paragraphs = ch0Paras
        )
        advanceUntilIdle()

        assertEquals(1, manager.sessionState.value.currentChapterIndex)
        assertEquals(3, capturingEngine.requests.size)
        assertEquals("Three.", capturingEngine.requests.last().text)
        assertEquals("epubcfi(/6/4!/4/2/1:0)", playbackDao.getPlaybackState("book-multi")?.lastCfi)
    }

    @Test
    fun `prefetch runs for System TTS but is skipped for Gemini Flash TTS to save quota`() = runTest {
        val tempDir = Files.createTempDirectory("session-prefetch-test").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_FLASH_TTS.name)
            }
            var ttsCallCount = 0
            val gate = CompletableDeferred<Unit>()
            val countingTts = object : InteractionsTtsClient {
                override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray {
                    ttsCallCount++
                    return ByteArray(1600)
                }

                override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> = emptyFlow()
            }
            val keyStore = object : SecureKeyStore {
                override suspend fun getApiKey(): String = "api-key-tts"
                override suspend fun saveApiKey(key: String) {}
                override suspend fun deleteApiKey() {}
            }

            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = keyStore,
                interactionsTtsClient = countingTts,
                quotaTracker = GeminiQuotaTracker(),
                applicationScope = backgroundScope
            ).apply {
                testDiskCache = TranslationDiskCache(tempDir)
                ioDispatcher = StandardTestDispatcher(testScheduler)
                playerFactory = { RecordingPcmPlayer(gate) }
            }
            advanceUntilIdle()

            // Two chunks (each > 1000 chars or separated by speaker), pause/stop during chunk 0
            val p0 = ReaderParagraph(0, "p0", "First block. " + "A".repeat(700), "epubcfi(/6/2!/4/2/1:0)")
            val p1 = ReaderParagraph(1, "p1", "Second block. " + "B".repeat(700), "epubcfi(/6/2!/4/2/2:0)")

            manager.startOrResume(
                bookId = "book-quota",
                bookTitle = "Quota Safe",
                paragraphs = listOf(p0, p1)
            )
            // Let chunk 0 synthesize and reach player.write(), then pause BEFORE chunk 1
            testScheduler.runCurrent()
            gate.complete(Unit)
            manager.pause()
            advanceUntilIdle()

            // Chunk 1 must NOT have been prefetched under GEMINI_FLASH_TTS
            assertEquals(1, ttsCallCount)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `PlaybackCoordinator activates external session and delegates play pause noisy and stop`() = runTest {
        val settingsRepo = FakeSettingsRepository().apply {
            setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
        }
        val coordinator = PlaybackCoordinator()
        val manager = ReaderNarrationSessionManager(
            bookDao = FakeBookDao(),
            chapterDao = FakeChapterDao(),
            settingsRepository = settingsRepo,
            playbackCoordinator = coordinator,
            applicationScope = backgroundScope
        ).apply {
            ioDispatcher = StandardTestDispatcher(testScheduler)
        }
        advanceUntilIdle()

        manager.startOrResume(
            bookId = "book-coord",
            bookTitle = "Coordinator Book",
            paragraphs = emptyList()
        )
        assertEquals(ActivePlaybackSource.EBOOK_NARRATION, coordinator.activeSource.value)
        assertTrue(coordinator.externalState.value.isActiveSession)
        assertTrue(coordinator.externalState.value.isPlaying)

        coordinator.getExternalDelegate()?.onAudioBecomingNoisy()
        assertFalse(manager.sessionState.value.isPlaying)
        assertFalse(coordinator.externalState.value.isPlaying)

        coordinator.getExternalDelegate()?.play()
        assertTrue(manager.sessionState.value.isPlaying)

        coordinator.getExternalDelegate()?.stop()
        assertFalse(manager.sessionState.value.isActiveSession)
        assertEquals(ActivePlaybackSource.NONE, coordinator.activeSource.value)
        assertNull(manager.sessionState.value.highlightedCfi)
    }
}
