package io.github.danprav04.polyglotbooks.feature.reader

import io.github.danprav04.polyglotbooks.core.audio.AudioTrackAdapter
import io.github.danprav04.polyglotbooks.core.audio.AudioTrackAdapterFactory
import io.github.danprav04.polyglotbooks.core.audio.AudioTrackPcmPlayer
import io.github.danprav04.polyglotbooks.core.audio.PcmPlayer
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.db.CharacterEntity
import io.github.danprav04.polyglotbooks.core.data.db.NarrationLineEntity
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.Blob
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.InteractionsTtsClient
import io.github.danprav04.polyglotbooks.core.gemini.LiveClientMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveErrorData
import io.github.danprav04.polyglotbooks.core.gemini.LiveServerMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveSession
import io.github.danprav04.polyglotbooks.core.gemini.LiveSessionFactory
import io.github.danprav04.polyglotbooks.core.gemini.ModelTurn
import io.github.danprav04.polyglotbooks.core.gemini.Part
import io.github.danprav04.polyglotbooks.core.gemini.QuotaExhaustedException
import io.github.danprav04.polyglotbooks.core.gemini.ServerContentData
import io.github.danprav04.polyglotbooks.core.gemini.SetupConfig
import io.github.danprav04.polyglotbooks.core.gemini.TextModelClient
import io.github.danprav04.polyglotbooks.core.gemini.TranscriptionData
import io.github.danprav04.polyglotbooks.core.gemini.TtsRequest
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.core.playback.ActivePlaybackSource
import io.github.danprav04.polyglotbooks.core.playback.PlaybackCoordinator
import io.github.danprav04.polyglotbooks.feature.narration.EngineCapabilities
import io.github.danprav04.polyglotbooks.feature.narration.EngineId
import io.github.danprav04.polyglotbooks.feature.narration.SynthesisRequest
import io.github.danprav04.polyglotbooks.feature.narration.VoiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import io.github.danprav04.polyglotbooks.feature.narration.NarrationEngine as FeatureNarrationEngine

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderNarrationSessionManagerTest {

    private class RecordingPcmPlayer(
        private val onWrite: ((Int) -> Unit)? = null
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
            writtenChunks.add(chunk)
            _currentPositionMs.value += 100L
            onWrite?.invoke(writtenChunks.size)
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
            runCurrent()

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
            runCurrent()

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
            runCurrent()
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
        runCurrent()

        manager.startOrResume(
            bookId = "book-fail",
            bookTitle = "Untranslated Book",
            paragraphs = listOf(ReaderParagraph(0, "p0", "Original English text.", "epubcfi(/6/2!/4/2/1:0)"))
        )
        runCurrent()

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
        runCurrent()

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
        runCurrent()

        assertEquals(0, manager.sessionState.value.currentChapterIndex)
        assertFalse(manager.sessionState.value.isPlaying)
        assertFalse(manager.sessionState.value.stopAfterCurrentChapter)
        assertEquals(2, capturingEngine.requests.size)

        // With sleep timer off, playback auto-advances from Ch 1 through Ch 2 and persists progress!
        capturingEngine.requests.clear()
        manager.stop()
        manager.startOrResume(
            bookId = "book-multi",
            bookTitle = "Multi Chapter",
            extractedBook = extracted,
            chapters = chapters,
            currentChapterIndex = 0,
            paragraphs = ch0Paras
        )
        runCurrent()

        assertEquals(1, manager.sessionState.value.currentChapterIndex)
        assertEquals(3, capturingEngine.requests.size)
        assertEquals("Three.", capturingEngine.requests.last().text)
        assertEquals("epubcfi(/6/4!/4/2/1:0)", playbackDao.getPlaybackState("book-multi")?.lastCfi)
    }

    @Test
    fun `prefetch runs for System TTS but is skipped for Gemini Flash TTS to save quota`() = runTest {
        val tempDir = Files.createTempDirectory("session-prefetch-test").toFile()
        try {
            val flashSettings = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_FLASH_TTS.name)
            }
            var ttsCallCount = 0
            val countingTts = object : InteractionsTtsClient {
                override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray {
                    ttsCallCount++
                    return ByteArray(1600)
                }

                override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> = flow {
                    ttsCallCount++
                    emit(PcmChunk(ByteArray(1600), 24000, 1))
                }
            }
            val keyStore = object : SecureKeyStore {
                override suspend fun getApiKey(): String = "api-key-tts"
                override suspend fun saveApiKey(key: String) {}
                override suspend fun deleteApiKey() {}
            }

            lateinit var flashManager: ReaderNarrationSessionManager
            flashManager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = flashSettings,
                secureKeyStore = keyStore,
                interactionsTtsClient = countingTts,
                quotaTracker = GeminiQuotaTracker(),
                applicationScope = backgroundScope
            ).apply {
                testDiskCache = TranslationDiskCache(tempDir)
                ioDispatcher = StandardTestDispatcher(testScheduler)
                playerFactory = {
                    RecordingPcmPlayer { writtenCount ->
                        if (writtenCount == 1) {
                            flashManager.pause()
                        }
                    }
                }
            }
            runCurrent()

            // Two chunks (each > 1000 chars combined), pause during chunk 0 write
            val p0 = ReaderParagraph(0, "p0", "First block. " + "A".repeat(700), "epubcfi(/6/2!/4/2/1:0)")
            val p1 = ReaderParagraph(1, "p1", "Second block. " + "B".repeat(700), "epubcfi(/6/2!/4/2/2:0)")

            flashManager.startOrResume(
                bookId = "book-quota",
                bookTitle = "Quota Safe",
                paragraphs = listOf(p0, p1)
            )
            runCurrent()

            // Chunk 1 must NOT have been prefetched under GEMINI_FLASH_TTS
            assertEquals(1, ttsCallCount)
            assertFalse(flashManager.sessionState.value.isPlaying)

            // Under SYSTEM_TTS, chunk 1 IS prefetched while chunk 0 synthesizes before pausing
            val sysSettings = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
            }
            var systemCallCount = 0
            val countingSystemEngine = object : FeatureNarrationEngine {
                override val id: EngineId = EngineId.SYSTEM_TTS
                override val capabilities = EngineCapabilities(
                    supportsMultiVoice = false,
                    supportsEmotionalStyles = false,
                    supportsStreaming = false,
                    outputSampleRate = 24000
                )

                override suspend fun voices(): List<VoiceInfo> = emptyList()

                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    systemCallCount++
                    yield()
                    emit(PcmChunk(ByteArray(1600), 24000, 1))
                }
            }
            lateinit var sysManager: ReaderNarrationSessionManager
            sysManager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = sysSettings,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                systemTtsFactory = { countingSystemEngine }
                playerFactory = {
                    RecordingPcmPlayer { writtenCount ->
                        if (writtenCount == 1) {
                            sysManager.pause()
                        }
                    }
                }
            }
            runCurrent()

            sysManager.startOrResume(
                bookId = "book-sys-prefetch",
                bookTitle = "System Prefetch",
                paragraphs = listOf(p0, p1)
            )
            runCurrent()

            assertEquals(2, systemCallCount)
            assertFalse(sysManager.sessionState.value.isPlaying)
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
        runCurrent()

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

    private fun testCapabilities(streaming: Boolean = true) = EngineCapabilities(
        supportsMultiVoice = false,
        supportsEmotionalStyles = false,
        supportsStreaming = streaming,
        outputSampleRate = 24000
    )

    @Test
    fun `defaultNarratorVoiceId from settings overrides Charon fallback in synthesis requests`() = runTest {
        val tempDir = Files.createTempDirectory("reader-voice-pref").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
                setDefaultNarratorVoiceId("Aoede")
            }
            val recordedVoices = mutableListOf<String>()
            val capturingEngine = object : FeatureNarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = testCapabilities(streaming = false)
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    recordedVoices += request.voiceId
                    emit(PcmChunk(ByteArray(1600), 24000, 1))
                }
            }
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                systemTtsFactory = { capturingEngine }
                playerFactory = { RecordingPcmPlayer() }
            }
            runCurrent()

            manager.startOrResume(
                bookId = "book-voice",
                bookTitle = "Voice Pref",
                paragraphs = listOf(ReaderParagraph(0, "p0", "Hello narrator world.", "epubcfi(/6/2!/4/2/1:0)"))
            )
            runCurrent()
            advanceUntilIdle()

            assertEquals(listOf("Aoede"), recordedVoices)
            assertEquals("Aoede", manager.sessionState.value.defaultNarratorVoiceId)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `cancelled narration job finally block cannot clobber newer generation player or state`() = runTest {
        val tempDir = Files.createTempDirectory("reader-gen-guard").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
            }
            val gen1Gate = CompletableDeferred<Unit>()
            val gen2Hold = CompletableDeferred<Unit>()
            var playerCount = 0
            val players = mutableListOf<RecordingPcmPlayer>()
            val engine = object : FeatureNarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = testCapabilities(streaming = false)
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    if (request.text.contains("Gen1")) {
                        gen1Gate.await()
                    } else {
                        emit(PcmChunk(ByteArray(4800), 24000, 1))
                        gen2Hold.await()
                    }
                }
            }
            val chapters = listOf(
                ChapterEntity("c0", "book-gen", 0, "One", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 20),
                ChapterEntity("c1", "book-gen", 1, "Two", 1000L, 2000L, "epubcfi(/6/4!/4/2/1:0)", 20)
            )
            val bookFile = java.io.File(tempDir, "gen.txt").apply {
                writeText("Gen1 body.\n\nGen2 body.")
            }
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao().apply {
                    insertBook(
                        BookEntity(
                            id = "book-gen",
                            title = "Gen Book",
                            author = "Author",
                            format = "TXT",
                            filePath = bookFile.absolutePath,
                            coverUri = null,
                            addedDate = 0L,
                            lastReadDate = 0L,
                            isAudiobook = false
                        )
                    )
                },
                chapterDao = FakeChapterDao().apply { insertChapters(chapters) },
                settingsRepository = settingsRepo,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                systemTtsFactory = { engine }
                playerFactory = {
                    RecordingPcmPlayer().also {
                        playerCount++
                        players += it
                    }
                }
            }
            runCurrent()

            manager.startOrResume(
                bookId = "book-gen",
                bookTitle = "Gen Book",
                chapters = chapters,
                currentChapterIndex = 0,
                paragraphs = listOf(ReaderParagraph(0, "p0", "Gen1 line.", "epubcfi(/6/2!/4/2/1:0)"))
            )
            runCurrent()
            assertEquals(1, playerCount)

            // Switch directly to generation 2 while generation 1 is suspended on gen1Gate
            val gen2Paragraph = ReaderParagraph(0, "p1", "Gen2 line.", "epubcfi(/6/4!/4/2/1:0)")
            manager.updateBookContext(
                bookId = "book-gen",
                bookTitle = "Gen Book",
                chapters = chapters,
                currentChapterIndex = 1,
                paragraphs = listOf(gen2Paragraph),
                activeParagraphIndex = 0,
                forceWhenDifferentBook = true
            )
            manager.startOrResume(
                bookId = "book-gen",
                bookTitle = "Gen Book",
                chapters = chapters,
                currentChapterIndex = 1,
                paragraphs = listOf(gen2Paragraph),
                activeParagraphIndex = 0
            )
            runCurrent()
            assertEquals(2, playerCount)
            assertTrue(manager.sessionState.value.isPlaying)
            assertEquals("epubcfi(/6/4!/4/2/1:0)", manager.sessionState.value.highlightedCfi)

            // Now let cancelled generation 1 finish and enter its finally block
            gen1Gate.complete(Unit)
            runCurrent()

            assertTrue("New generation must remain playing after old finally runs", manager.sessionState.value.isPlaying)
            assertTrue("New generation player must remain attached", manager.activePcmPlayer === players[1])
            assertFalse("New generation player must not be stopped by old job", players[1].stopped)
            assertEquals("epubcfi(/6/4!/4/2/1:0)", manager.sessionState.value.highlightedCfi)

            gen2Hold.complete(Unit)
            advanceUntilIdle()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `frame-exact SegmentFrameSpan holds highlight on buffered paragraph until playbackHeadPositionFrames crosses span end`() = runTest {
        val tempDir = Files.createTempDirectory("reader-frame-span").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
            }
            val holdLastTurn = CompletableDeferred<Unit>()
            // 12,000 frames @ 24kHz mono 16-bit = 24,000 bytes (500ms)
            val halfSecondPcm = ByteArray(24_000) { 0x10 }
            val adapter = object : AudioTrackAdapter {
                @Volatile var headFrames = 0L
                override fun write(data: ByteArray, offset: Int, size: Int): Int = size
                override fun play() {}
                override fun pause() {}
                override fun flush() {}
                override fun stop() {}
                override fun release() {}
                override fun setSpeed(speed: Float): Boolean = true
                override fun setVolume(volume: Float) {}
                override val playbackHeadPositionFrames: Long
                    get() = headFrames
            }
            val player = AudioTrackPcmPlayer(AudioTrackAdapterFactory { _, _ -> adapter })
            val ttsEngine = object : FeatureNarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = testCapabilities(streaming = false)
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    if (request.text.contains("Third")) {
                        holdLastTurn.await()
                    }
                    emit(PcmChunk(halfSecondPcm, 24000, 1))
                }
            }
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                systemTtsFactory = { ttsEngine }
                playerFactory = { player }
            }
            runCurrent()

            val p0 = ReaderParagraph(0, "p0", "First spoken paragraph.", "epubcfi(/6/2!/4/2/1:0)")
            val p1 = ReaderParagraph(1, "p1", "Second buffered paragraph.", "epubcfi(/6/2!/4/2/2:0)")
            val p2 = ReaderParagraph(2, "p2", "Third holding paragraph.", "epubcfi(/6/2!/4/2/3:0)")
            manager.startOrResume(
                bookId = "book-frames",
                bookTitle = "Frame Sync Book",
                paragraphs = listOf(p0, p1, p2)
            )
            // Pump coroutine execution so both p0 and p1 are written to AudioTrack buffer while head stays at 0
            advanceTimeBy(150)
            runCurrent()

            assertEquals(24_000L, player.totalFramesWritten)
            // Even though p1's audio was already written into AudioTrack's buffer, headFrames is still 0 (< 12,000)
            assertEquals("epubcfi(/6/2!/4/2/1:0)", manager.sessionState.value.highlightedCfi)
            assertEquals(0, manager.sessionState.value.activeParagraphIndex)

            // Advance hardware playback head past span 0 (12,000 frames) into p1
            adapter.headFrames = 13_000L
            advanceTimeBy(120)
            runCurrent()

            assertEquals("epubcfi(/6/2!/4/2/2:0)", manager.sessionState.value.highlightedCfi)
            assertEquals(1, manager.sessionState.value.activeParagraphIndex)

            holdLastTurn.complete(Unit)
            advanceTimeBy(120)
            runCurrent()
            adapter.headFrames = player.totalFramesWritten
            advanceUntilIdle()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `gemini live streaming session sends one paragraph per turn through liveSessionFactory`() = runTest {
        val tempDir = Files.createTempDirectory("reader-live-stream").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
            }
            val p0Text = "First live paragraph."
            val p1Text = "Second live paragraph."
            val sentTexts = mutableListOf<String>()
            val pcmBase64 = Base64.getEncoder().encodeToString(ByteArray(48_000) { 0x15 })
            val fakeSession = object : LiveSession {
                private val _incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 16)
                override val incomingMessages: Flow<LiveServerMessage> = _incoming.asSharedFlow()
                private var connected = false
                override val isConnected: Boolean get() = connected

                override suspend fun connect(apiKey: String, setup: SetupConfig) {
                    connected = true
                }

                override suspend fun send(message: LiveClientMessage) {
                    val clientMsg = message as? LiveClientMessage.ClientContentMessage ?: return
                    val promptText = clientMsg.clientContent.turns.orEmpty()
                        .flatMap { it.parts }
                        .mapNotNull { it.text }
                        .joinToString("\n")
                    sentTexts += promptText
                    val verbatim = when {
                        promptText.contains(p0Text) -> p0Text
                        promptText.contains(p1Text) -> p1Text
                        else -> promptText
                    }
                    _incoming.emit(
                        LiveServerMessage.ServerContentMessage(
                            ServerContentData(
                                modelTurn = ModelTurn(
                                    parts = listOf(Part(inlineData = Blob("audio/pcm;rate=24000", pcmBase64)))
                                ),
                                outputTranscription = TranscriptionData(verbatim),
                                turnComplete = true
                            )
                        )
                    )
                }

                override suspend fun disconnect() {
                    connected = false
                }
            }
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("live-test-key"),
                liveSessionFactory = object : LiveSessionFactory {
                    override fun create(): LiveSession = fakeSession
                },
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { RecordingPcmPlayer() }
            }
            runCurrent()

            manager.startOrResume(
                bookId = "book-live",
                bookTitle = "Live Streaming Book",
                paragraphs = listOf(
                    ReaderParagraph(0, "p0", p0Text, "epubcfi(/6/2!/4/2/1:0)"),
                    ReaderParagraph(1, "p1", p1Text, "epubcfi(/6/2!/4/2/2:0)")
                )
            )
            runCurrent()
            advanceUntilIdle()

            assertEquals(2, sentTexts.size)
            assertTrue(sentTexts[0].contains(p0Text) && !sentTexts[0].contains(p1Text))
            assertTrue(sentTexts[1].contains(p1Text) && !sentTexts[1].contains(p0Text))
            assertFalse(manager.sessionState.value.isPlaying)
            assertNull(manager.sessionState.value.errorMessage)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `computeSegmentFrameSpansForBatch weights CJK density lower min floor and snaps boundary to silence valley`() = runTest {
        val manager = ReaderNarrationSessionManager(
            bookDao = FakeBookDao(),
            chapterDao = FakeChapterDao(),
            applicationScope = backgroundScope
        )
        val totalFrames = 48_000 // 2.0s @ 24kHz mono 16-bit
        val pcmBytes = ByteArray(totalFrames * 2)
        // Fill audio with steady loud signal (~4000) everywhere EXCEPT frames 18,000..20,000
        for (frame in 0 until totalFrames) {
            val sample: Short = if (frame in 18_000..20_000) 0 else 4000
            val idx = frame * 2
            pcmBytes[idx] = (sample.toInt() and 0xFF).toByte()
            pcmBytes[idx + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        val paras = listOf(
            ReaderParagraph(0, "p0", "中文繁體語句。", "epubcfi(/6/2!/4/2/1:0)"),
            ReaderParagraph(1, "p1", "Second English paragraph text.", "epubcfi(/6/2!/4/2/2:0)")
        )
        val chunk = manager.buildSpeechChunks(paras, emptyList(), NarrationEngine.GEMINI_FLASH_TTS).first()
        assertTrue(
            "CJK paragraph weight should exceed raw char length",
            chunk.paragraphCharWeights[0] > paras[0].text.length
        )
        val timings = listOf(
            ParagraphTiming(0, "epubcfi(/6/2!/4/2/1:0)", 0L, 600L),
            ParagraphTiming(1, "epubcfi(/6/2!/4/2/2:0)", 600L, 2000L)
        )

        val spans = manager.computeSegmentFrameSpansForBatch(
            chapterIndex = 0,
            chunk = chunk,
            pcmBytes = pcmBytes,
            sampleRate = 24000,
            channels = 1,
            baseStartFrame = 100L,
            timings = timings
        )

        assertEquals(2, spans.size)
        assertEquals(100L, spans[0].startFrame)
        assertEquals(spans[0].endFrame, spans[1].startFrame)
        assertEquals(100L + totalFrames, spans[1].endFrame)
        val boundaryFrame = spans[0].endFrame - 100L
        assertTrue(
            "Expected boundary $boundaryFrame to snap into silence valley 18000..20000",
            boundaryFrame in 18_000L..20_000L
        )
        // Verify CJK 7-char sentence still got >= 350ms minimum floor (8,400 frames)
        assertTrue(spans[0].endFrame - spans[0].startFrame >= 8_400L)
    }

    @Test
    fun `switching active playback from Book 1 to Book 2 flushes Book 1 chapterIndex and highlightedCfi`() = runTest {
        val tempDir = Files.createTempDirectory("reader-multibook").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
            }
            val playbackDao = FakePlaybackStateDao()
            val book1Hold = CompletableDeferred<Unit>()
            val engine = object : FeatureNarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = testCapabilities(streaming = false)
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    emit(PcmChunk(ByteArray(2400), 24000, 1))
                    if (request.text.contains("Book1")) {
                        book1Hold.await()
                    }
                }
            }
            val b1Chapters = listOf(
                ChapterEntity("b1_c0", "book-1", 0, "Ch1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 50),
                ChapterEntity("b1_c1", "book-1", 1, "Ch2", 1000L, 2000L, "epubcfi(/6/4!/4/2/1:0)", 50)
            )
            val b2Chapters = listOf(
                ChapterEntity("b2_c0", "book-2", 0, "Ch1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 50)
            )
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao().apply {
                    insertChapters(b1Chapters)
                    insertChapters(b2Chapters)
                },
                playbackStateDao = playbackDao,
                settingsRepository = settingsRepo,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                systemTtsFactory = { engine }
                playerFactory = { RecordingPcmPlayer() }
            }
            runCurrent()

            val b1Paras = listOf(
                ReaderParagraph(0, "p0", "Book1 paragraph zero.", "epubcfi(/6/4!/4/2/1:0)"),
                ReaderParagraph(1, "p1", "Book1 paragraph one.", "epubcfi(/6/4!/4/2/2:0)")
            )
            manager.startOrResume(
                bookId = "book-1",
                bookTitle = "Book One",
                chapters = b1Chapters,
                currentChapterIndex = 1,
                paragraphs = b1Paras,
                activeParagraphIndex = 1
            )
            runCurrent()

            // Start Book 2 while Book 1 is actively playing chapter 1, para 1
            val b2Paras = listOf(
                ReaderParagraph(0, "p0", "Book2 intro.", "epubcfi(/6/2!/4/2/1:0)")
            )
            manager.startOrResume(
                bookId = "book-2",
                bookTitle = "Book Two",
                chapters = b2Chapters,
                currentChapterIndex = 0,
                paragraphs = b2Paras,
                activeParagraphIndex = 0
            )
            advanceUntilIdle()
            book1Hold.complete(Unit)

            val savedBook1 = playbackDao.getPlaybackState("book-1")
            assertNotNull("Book 1 state must be flushed when switching to Book 2", savedBook1)
            assertEquals("epubcfi(/6/4!/4/2/2:0)", savedBook1?.lastCfi)
            assertEquals(1, savedBook1?.chapterIndex)
            assertEquals("book-2", manager.sessionState.value.bookId)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `skips consecutive empty chapters on start, auto-advance, and next prev while paused preserves browsing`() = runTest {
        val tempDir = Files.createTempDirectory("reader-empty-skip").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
            }
            val spokenTexts = mutableListOf<String>()
            val holdChapter4 = CompletableDeferred<Unit>()
            var replayChapter2Gate: CompletableDeferred<Unit>? = null
            var suspendedOnce = false
            val engine = object : FeatureNarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = testCapabilities(streaming = false)
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    spokenTexts += request.text
                    emit(PcmChunk(ByteArray(1600), 24000, 1))
                    if (request.text.contains("Final Readable") && !suspendedOnce) {
                        suspendedOnce = true
                        holdChapter4.await()
                    }
                    replayChapter2Gate?.takeIf { request.text.contains("First readable") }?.await()
                }
            }
            // Spine: 0=empty cover, 1=empty dedication, 2=readable A, 3=empty plate, 4=Final Readable B
            val extractedBook = EbookContentExtractor.ExtractedBook(
                format = "EPUB",
                title = "Illustrated Novel",
                author = "Author",
                pageCount = 5,
                chapters = listOf(
                    EbookContentExtractor.ExtractedChapter(0, "Cover", "ch0.xhtml", "", emptyList()),
                    EbookContentExtractor.ExtractedChapter(1, "Dedication", "ch1.xhtml", "", emptyList()),
                    EbookContentExtractor.ExtractedChapter(2, "Chapter One", "ch2.xhtml", "", listOf("First readable text.")),
                    EbookContentExtractor.ExtractedChapter(3, "Plate", "ch3.xhtml", "", emptyList()),
                    EbookContentExtractor.ExtractedChapter(4, "Chapter Two", "ch4.xhtml", "", listOf("Final Readable text."))
                )
            )
            val chapters = extractedBook.chapters.map { ch ->
                ChapterEntity(
                    id = "c_${ch.index}",
                    bookId = "book-skip",
                    index = ch.index,
                    title = ch.title,
                    startMs = 0L,
                    endMs = 1000L,
                    cfiRange = "epubcfi(/6/${(ch.index + 1) * 2}!/4/2/1:0)",
                    textLength = ch.paragraphs.sumOf { it.length }
                )
            }
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao().apply { insertChapters(chapters) },
                settingsRepository = settingsRepo,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                systemTtsFactory = { engine }
                playerFactory = { RecordingPcmPlayer() }
            }
            runCurrent()

            manager.updateBookContext(
                bookId = "book-skip",
                bookTitle = "Illustrated Novel",
                chapters = chapters,
                extractedBook = extractedBook,
                currentChapterIndex = 0,
                paragraphs = emptyList(),
                forceWhenDifferentBook = true
            )
            // Paused browsing on chapter 1 must remain on chapter 1
            manager.nextChapter()
            runCurrent()
            assertEquals(1, manager.sessionState.value.currentChapterIndex)
            assertFalse(manager.sessionState.value.isPlaying)

            // Pressing Play on empty chapter 1 skips consecutive empty chapter(s) to chapter 2,
            // then finishes chapter 2 and auto-advances across empty chapter 3 onto chapter 4
            manager.startOrResume(bookId = "book-skip", bookTitle = "Illustrated Novel", currentChapterIndex = 1, paragraphs = emptyList())
            runCurrent()
            advanceUntilIdle()

            assertEquals(listOf("First readable text.", "Final Readable text."), spokenTexts)
            assertEquals(4, manager.sessionState.value.currentChapterIndex)

            // Pressing previousChapter while playing skips empty chapter 3 back to chapter 2
            replayChapter2Gate = CompletableDeferred()
            manager.previousChapter()
            runCurrent()
            assertEquals(2, manager.sessionState.value.currentChapterIndex)
            assertTrue(manager.sessionState.value.isPlaying)
            assertEquals(
                listOf("First readable text.", "Final Readable text.", "First readable text."),
                spokenTexts
            )

            holdChapter4.complete(Unit)
            replayChapter2Gate?.complete(Unit)
            runCurrent()
            advanceUntilIdle()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `trailing blank back-cover finishes cleanly without error and manual start on all-empty stops with error`() = runTest {
        val tempDir = Files.createTempDirectory("reader-trailing-empty").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
            }
            val playbackDao = FakePlaybackStateDao()
            val engine = object : FeatureNarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = testCapabilities(streaming = false)
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    emit(PcmChunk(ByteArray(2400), 24000, 1))
                }
            }
            val extractedBook = EbookContentExtractor.ExtractedBook(
                format = "EPUB",
                title = "Back Cover Book",
                author = "Author",
                pageCount = 3,
                chapters = listOf(
                    EbookContentExtractor.ExtractedChapter(0, "Story", "ch0.xhtml", "", listOf("Ending line of the story.")),
                    EbookContentExtractor.ExtractedChapter(1, "Blank Plate", "ch1.xhtml", "", emptyList()),
                    EbookContentExtractor.ExtractedChapter(2, "Back Cover", "ch2.xhtml", "", emptyList())
                )
            )
            val chapters = extractedBook.chapters.map { ch ->
                ChapterEntity(
                    id = "bc_${ch.index}",
                    bookId = "book-back",
                    index = ch.index,
                    title = ch.title,
                    startMs = 0L,
                    endMs = 1000L,
                    cfiRange = "epubcfi(/6/${(ch.index + 1) * 2}!/4/2/1:0)",
                    textLength = ch.paragraphs.sumOf { it.length }
                )
            }
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao().apply { insertChapters(chapters) },
                playbackStateDao = playbackDao,
                settingsRepository = settingsRepo,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                systemTtsFactory = { engine }
                playerFactory = { RecordingPcmPlayer() }
            }
            runCurrent()

            manager.updateBookContext(
                bookId = "book-back",
                bookTitle = "Back Cover Book",
                chapters = chapters,
                extractedBook = extractedBook,
                currentChapterIndex = 0,
                paragraphs = listOf(ReaderParagraph(0, "p0", "Ending line of the story.", "epubcfi(/6/2!/4/2/1:0)")),
                forceWhenDifferentBook = true
            )
            manager.startOrResume(
                bookId = "book-back",
                bookTitle = "Back Cover Book",
                currentChapterIndex = 0,
                paragraphs = listOf(ReaderParagraph(0, "p0", "Ending line of the story.", "epubcfi(/6/2!/4/2/1:0)"))
            )
            runCurrent()
            advanceUntilIdle()

            // Completed cleanly without error and without saving progress onto empty trailing chapter 1 or 2
            val state = manager.sessionState.value
            assertFalse(state.isPlaying)
            assertNull(state.errorMessage)
            assertEquals(state.chapterDurationMs, state.chapterPositionMs)
            assertEquals(state.bookDurationMs, state.bookPositionMs)
            assertEquals(0, playbackDao.getPlaybackState("book-back")?.chapterIndex)

            // computeBookTimelineMetrics must assign 0ms to empty chapters 1 and 2
            val (_, bookDur) = manager.computeBookTimelineMetrics(state, state.chapterDurationMs)
            assertEquals(state.chapterDurationMs, bookDur)

            // Starting narration manually on trailing blank chapter 1 must stop with clear error
            manager.switchChapter(1, continuePlaying = false)
            runCurrent()
            manager.startOrResume(bookId = "book-back", bookTitle = "Back Cover Book", currentChapterIndex = 1, paragraphs = emptyList())
            runCurrent()
            advanceUntilIdle()

            assertFalse(manager.sessionState.value.isPlaying)
            assertNotNull(manager.sessionState.value.errorMessage)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `paused session chapter change clears stale highlightedCfi and flash tts uses GEMINI_TTS_V4 cache tag`() = runTest {
        val tempDir = Files.createTempDirectory("reader-cache-v2").toFile()
        try {
            val diskCache = TranslationDiskCache(tempDir)
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_FLASH_TTS.name)
            }
            val keyStore = object : SecureKeyStore {
                override suspend fun getApiKey(): String = "test-key"
                override suspend fun saveApiKey(key: String) {}
                override suspend fun deleteApiKey() {}
            }
            val freshPcm = byteArrayOf(9, 8, 7, 6, 5, 4)
            val poisonedLegacyPcm = byteArrayOf(1, 1, 1, 1)
            var ttsCallCount = 0
            val ttsClient = object : InteractionsTtsClient {
                override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray {
                    ttsCallCount++
                    return freshPcm
                }

                override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> = flow {
                    ttsCallCount++
                    emit(PcmChunk(freshPcm, 24000, 1))
                }
            }

            // Seed legacy v1 cache keys ("GEMINI_TTS" and "GEMINI_TTS_en") with poisoned audio
            diskCache.saveAudioSegment(
                engineTag = "GEMINI_TTS",
                voiceId = "Charon",
                style = null,
                text = "Clean book sentence.",
                sampleRate = 24000,
                channels = 1,
                pcmBytes = poisonedLegacyPcm
            )
            diskCache.saveAudioSegment(
                engineTag = "GEMINI_TTS_en",
                voiceId = "Charon",
                style = null,
                text = "Clean book sentence.",
                sampleRate = 24000,
                channels = 1,
                pcmBytes = poisonedLegacyPcm
            )

            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = keyStore,
                interactionsTtsClient = ttsClient,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = diskCache
                playerFactory = { RecordingPcmPlayer() }
            }
            runCurrent()

            val chunk = SpeechChunk(
                text = "Clean book sentence.",
                leadCfi = "epubcfi(/6/2!/4/2/1:0)",
                allCfis = listOf("epubcfi(/6/2!/4/2/1:0)"),
                leadIndex = 0,
                voiceId = "Charon",
                style = null,
                paragraphIndices = listOf(0),
                paragraphCharWeights = listOf(20),
                languageCode = "en"
            )
            val emitted = mutableListOf<PcmChunk>()
            manager.synthesizeSpeechChunk(
                chunk = chunk,
                engineChoice = NarrationEngine.GEMINI_FLASH_TTS,
                systemFallback = CapturingSystemEngine(),
                diskCache = diskCache,
                maxCacheBytes = TranslationDiskCache.DEFAULT_MAX_AUDIO_BYTES,
                ttsAlreadyFailed = false,
                onTtsFailed = {},
                onStatusUpdate = {},
                onPcmChunk = { emitted += it }
            )

            assertEquals("Legacy GEMINI_TTS cache entry must be bypassed", 1, ttsCallCount)
            assertEquals(1, emitted.size)
            assertTrue(emitted.single().data.contentEquals(freshPcm))

            val v2Cached = diskCache.loadAudioSegment(
                engineTag = "${ReaderNarrationSessionManager.GEMINI_TTS_CACHE_TAG_PREFIX}_en",
                voiceId = "Charon",
                style = null,
                text = "Clean book sentence."
            )
            assertNotNull("Synthesized audio must be cached under GEMINI_TTS_V4_en", v2Cached)
            assertTrue(v2Cached!!.pcmBytes.contentEquals(freshPcm))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `flash tts multi-paragraph batch advances highlight and timeline in real time during AudioTrack write without 1x setSpeed`() = runTest {
        val tempDir = Files.createTempDirectory("reader-flash-realtime-sync").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_FLASH_TTS.name)
                setReaderNarrationSpeed(1.0f)
            }
            val speedCalls = mutableListOf<Float>()
            val writeSliceSizes = mutableListOf<Int>()
            val headFrames = java.util.concurrent.atomic.AtomicLong(0L)
            lateinit var player: AudioTrackPcmPlayer

            val adapter = object : AudioTrackAdapter {
                override fun write(data: ByteArray, offset: Int, size: Int): Int {
                    writeSliceSizes += size
                    return size
                }
                override fun play() {}
                override fun pause() {}
                override fun flush() {}
                override fun stop() {}
                override fun release() {}
                override fun setSpeed(speed: Float): Boolean {
                    speedCalls += speed
                    return true
                }
                override fun setVolume(volume: Float) {}
                override val playbackHeadPositionFrames: Long get() = headFrames.get()
            }
            player = AudioTrackPcmPlayer(AudioTrackAdapterFactory { _, _ -> adapter })

            // 2.0s of 24kHz mono 16-bit PCM = 96,000 bytes (48,000 frames) across 2 paragraphs,
            // plus a 3rd paragraph in batch 1 held on synthesis so batch 0 stays active in ticker.
            val totalPcm = ByteArray(96_000) { 0x12 }
            val holdBatch1 = CompletableDeferred<Unit>()
            val ttsClient = object : InteractionsTtsClient {
                override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray = totalPcm
                override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> = flow {
                    if (request.text.contains("BatchOneMarker")) {
                        holdBatch1.await()
                    }
                    emit(PcmChunk(totalPcm, 24000, 1))
                }
            }

            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("flash-sync-key"),
                interactionsTtsClient = ttsClient,
                quotaTracker = GeminiQuotaTracker(),
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { player }
            }
            runCurrent()

            val p0 = ReaderParagraph(0, "p0", "First short paragraph in Flash TTS batch.", "epubcfi(/6/2!/4/2/1:0)")
            val p1 = ReaderParagraph(1, "p1", "Second paragraph inside the exact same Flash TTS batch.", "epubcfi(/6/2!/4/2/2:0)")
            val p2 = ReaderParagraph(2, "p2", "BatchOneMarker " + "Z".repeat(950), "epubcfi(/6/2!/4/2/3:0)")

            manager.startOrResume(
                bookId = "book-flash-sync",
                bookTitle = "Flash Sync",
                paragraphs = listOf(p0, p1, p2)
            )
            runCurrent()

            // Verify 1.0x playback NEVER called AudioTrack.setSpeed and batch 0 wrote in bounded sub-chunks
            assertTrue("1.0x narration must bypass AudioTrack.setSpeed", speedCalls.isEmpty())
            assertTrue("Expected sub-chunked writes (<= 2048B)", writeSliceSizes.size > 1 && writeSliceSizes.all { it <= 2048 })
            assertEquals(48_000L, player.totalFramesWritten)

            // With headFrames still inside p0 (< ~20,000), highlight stays on p0
            headFrames.set(5_000L)
            advanceTimeBy(120)
            runCurrent()
            assertEquals("epubcfi(/6/2!/4/2/1:0)", manager.sessionState.value.highlightedCfi)
            assertEquals(0, manager.sessionState.value.activeParagraphIndex)
            assertTrue(manager.sessionState.value.chapterPositionMs > 0L)

            // Advance hardware playback head into p1 within the SAME batch -> highlight & timeline follow voice
            headFrames.set(36_000L)
            advanceTimeBy(120)
            runCurrent()
            assertEquals("epubcfi(/6/2!/4/2/2:0)", manager.sessionState.value.highlightedCfi)
            assertEquals(1, manager.sessionState.value.activeParagraphIndex)

            holdBatch1.complete(Unit)
            advanceUntilIdle()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `flash tts prefetches next batch during playback without duplicate network calls`() = runTest {
        val tempDir = Files.createTempDirectory("reader-flash-prefetch-live").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_FLASH_TTS.name)
            }
            var ttsCallCount = 0
            val synthesizedTexts = mutableListOf<String>()
            val ttsClient = object : InteractionsTtsClient {
                override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray {
                    ttsCallCount++
                    synthesizedTexts += request.text
                    return ByteArray(4800) { 0x05 }
                }

                override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> = flow {
                    ttsCallCount++
                    synthesizedTexts += request.text
                    emit(PcmChunk(ByteArray(4800) { 0x05 }, 24000, 1))
                }
            }

            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("flash-prefetch-key"),
                interactionsTtsClient = ttsClient,
                quotaTracker = GeminiQuotaTracker(),
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { RecordingPcmPlayer() }
            }
            runCurrent()

            val p0 = ReaderParagraph(0, "p0", "First batch. " + "A".repeat(700), "epubcfi(/6/2!/4/2/1:0)")
            val p1 = ReaderParagraph(1, "p1", "Second batch. " + "B".repeat(700), "epubcfi(/6/2!/4/2/2:0)")

            manager.startOrResume(
                bookId = "book-flash-prefetch",
                bookTitle = "Flash Prefetch",
                paragraphs = listOf(p0, p1)
            )
            runCurrent()
            advanceUntilIdle()

            assertEquals("Two batches must make exactly 2 TTS network calls (no double synthesis)", 2, ttsCallCount)
            assertEquals(2, synthesizedTexts.size)
            assertFalse(manager.sessionState.value.isPlaying)
            assertNull(manager.sessionState.value.errorMessage)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `buildSpeechChunks isolates silent dividers and punctuation-only dialogue cleanly from prose batches`() {
        val manager = ReaderNarrationSessionManager(
            bookDao = FakeBookDao(),
            chapterDao = FakeChapterDao(),
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
        val paragraphs = listOf(
            ReaderParagraph(0, "p0", "Opening narrative sentence.", "epubcfi(/6/2!/4/2/1:0)"),
            ReaderParagraph(1, "p1", "\"......?\"", "epubcfi(/6/2!/4/2/2:0)"),
            ReaderParagraph(2, "p2", "* * *", "epubcfi(/6/2!/4/2/3:0)"),
            ReaderParagraph(3, "p3", "Closing narrative sentence.", "epubcfi(/6/2!/4/2/4:0)")
        )

        val chunks = manager.buildSpeechChunks(paragraphs, emptyList(), NarrationEngine.GEMINI_FLASH_TTS)
        assertEquals(4, chunks.size)
        assertEquals("Opening narrative sentence.", chunks[0].text)
        assertEquals(null, chunks[0].style)

        assertEquals("\"......?\"", chunks[1].text)
        assertEquals(null, chunks[1].style)

        assertEquals("* * *", chunks[2].text)
        assertEquals(null, chunks[2].style)

        assertEquals("Closing narrative sentence.", chunks[3].text)
        assertEquals(null, chunks[3].style)
    }

    @Test
    fun `gemini live mid-turn error retries cleanly without partial drop double playback or corrupted cache`() = runTest {
        val tempDir = Files.createTempDirectory("reader-live-verify").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
            }
            val p0Text = "First sentence survives a mid-turn interruption cleanly."
            val partialBadBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val goodBytes = ByteArray(96_000) { 0x2A }
            val partialB64 = Base64.getEncoder().encodeToString(partialBadBytes)
            val goodB64 = Base64.getEncoder().encodeToString(goodBytes)
            var sessionCreateCount = 0

            val factory = object : LiveSessionFactory {
                override fun create(): LiveSession = create(reconnectOnGoAway = true)
                override fun create(reconnectOnGoAway: Boolean): LiveSession {
                    assertFalse("Reader Live narration must defer GoAway rotation to GeminiLiveEngine", reconnectOnGoAway)
                    val idx = sessionCreateCount++
                    return object : LiveSession {
                        private val _incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 16)
                        override val incomingMessages: Flow<LiveServerMessage> = _incoming.asSharedFlow()
                        private var connected = false
                        override val isConnected: Boolean get() = connected

                        override suspend fun connect(apiKey: String, setup: SetupConfig) {
                            connected = true
                        }

                        override suspend fun send(message: LiveClientMessage) {
                            if (idx == 0) {
                                _incoming.emit(
                                    LiveServerMessage.ServerContentMessage(
                                        ServerContentData(
                                            modelTurn = ModelTurn(parts = listOf(Part(inlineData = Blob("audio/pcm;rate=24000", partialB64)))),
                                            outputTranscription = TranscriptionData("First sentence"),
                                            turnComplete = false
                                        )
                                    )
                                )
                                _incoming.emit(
                                    LiveServerMessage.ErrorMessage(
                                        io.github.danprav04.polyglotbooks.core.gemini.LiveErrorData(code = 503, message = "Overloaded")
                                    )
                                )
                            } else {
                                _incoming.emit(
                                    LiveServerMessage.ServerContentMessage(
                                        ServerContentData(
                                            modelTurn = ModelTurn(parts = listOf(Part(inlineData = Blob("audio/pcm;rate=24000", goodB64)))),
                                            outputTranscription = TranscriptionData(p0Text),
                                            turnComplete = true
                                        )
                                    )
                                )
                            }
                        }

                        override suspend fun disconnect() {
                            connected = false
                        }
                    }
                }
            }

            val writtenChunks = mutableListOf<ByteArray>()
            val recordingPlayer = object : PcmPlayer {
                override val currentPositionMs: StateFlow<Long> = MutableStateFlow(0L).asStateFlow()
                override val supportsFrameTracking: Boolean = true
                override var totalFramesWritten: Long = 0L
                override fun init(sampleRate: Int, channels: Int) {}
                override fun play() {}
                override fun pause() {}
                override fun stop() {}
                override fun flush() {}
                override fun release() {}
                override fun setPlaybackSpeed(speed: Float) {}
                override fun setOriginalAudioVolume(volume: Float) {}
                override fun playbackHeadPositionFrames(): Long = totalFramesWritten
                override fun write(chunk: PcmChunk): Int {
                    if (chunk.data.isNotEmpty()) {
                        writtenChunks += chunk.data
                        totalFramesWritten += (chunk.data.size / 2).toLong()
                    }
                    return chunk.data.size
                }
            }

            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("live-verify-key"),
                liveSessionFactory = factory,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { recordingPlayer }
            }
            runCurrent()

            manager.startOrResume(
                bookId = "book-live-verify",
                bookTitle = "Live Verify",
                paragraphs = listOf(
                    ReaderParagraph(0, "p0", p0Text, "epubcfi(/6/2!/4/2/1:0)")
                )
            )
            runCurrent()
            advanceUntilIdle()

            assertEquals(2, sessionCreateCount)
            assertEquals(1, writtenChunks.size)
            assertTrue("Only complete verified Live audio must play (no partial chunk before retry)", writtenChunks[0].contentEquals(goodBytes))

            val cached = TranslationDiskCache(tempDir).loadAudioSegment(
                engineTag = ReaderNarrationSessionManager.GEMINI_LIVE_CACHE_TAG_PREFIX,
                voiceId = "Charon",
                style = null,
                text = p0Text
            )
            assertNotNull(cached)
            assertTrue("Cache must store only complete verified audio", cached!!.pcmBytes.contentEquals(goodBytes))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `playback honors persisted NarrationLineEntity speakers styles and narrator override without inline attribution`() = runTest {
        val tempDir = Files.createTempDirectory("reader-lines-playback").toFile()
        try {
            val bookDao = FakeBookDao().apply {
                insertBook(
                    BookEntity(
                        id = "book-cast-lines",
                        title = "Persisted Cast Lines",
                        author = "Author",
                        format = "EPUB",
                        filePath = "",
                        coverUri = null,
                        addedDate = 100L,
                        lastReadDate = 100L,
                        isAudiobook = false
                    )
                )
            }
            val chapterDao = FakeChapterDao(
                listOf(
                    ChapterEntity("c0", "book-cast-lines", 0, "Ch 1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 120)
                )
            )
            val narrationDao = FakeNarrationDao(chapterDao).apply {
                insertLines(
                    listOf(
                        NarrationLineEntity(
                            id = "l0",
                            chapterId = "c0",
                            paraIndex = 0,
                            startOffset = 0,
                            endOffset = 34,
                            speakerId = "narrator",
                            style = "calm",
                            cfi = "epubcfi(/6/2!/4/2/1:0)"
                        ),
                        NarrationLineEntity(
                            id = "l1",
                            chapterId = "c0",
                            paraIndex = 1,
                            startOffset = 0,
                            endOffset = 22,
                            speakerId = "book-cast-lines_alice",
                            style = "whispering",
                            cfi = "epubcfi(/6/2!/4/2/2:0)"
                        ),
                        NarrationLineEntity(
                            id = "l2",
                            chapterId = "c0",
                            paraIndex = 2,
                            startOffset = 0,
                            endOffset = 19,
                            speakerId = "bob",
                            style = null,
                            cfi = "epubcfi(/6/2!/4/2/3:0)"
                        )
                    )
                )
            }
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
                setDefaultNarratorVoiceId("Charon")
            }
            val capturingEngine = CapturingSystemEngine()
            val manager = ReaderNarrationSessionManager(
                bookDao = bookDao,
                chapterDao = chapterDao,
                settingsRepository = settingsRepo,
                applicationScope = backgroundScope,
                narrationDao = narrationDao
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { RecordingPcmPlayer() }
                systemTtsFactory = { capturingEngine }
            }
            runCurrent()

            val characters = listOf(
                CharacterEntity("book-cast-lines_narrator", "book-cast-lines", "Narrator", "", "neutral", "mature", "storytelling", "Sulafat"),
                CharacterEntity("book-cast-lines_alice", "book-cast-lines", "Alice", "", "female", "young", "warm", "Zephyr"),
                CharacterEntity("book-cast-lines_bob", "book-cast-lines", "Bob", "", "male", "adult", "gruff", "Fenrir")
            )
            val paragraphs = listOf(
                ReaderParagraph(0, "p0", "The train arrived right on time.", "epubcfi(/6/2!/4/2/1:0)"),
                ReaderParagraph(1, "p1", "Did you bring the key?", "epubcfi(/6/2!/4/2/2:0)"),
                ReaderParagraph(2, "p2", "It is in my coat.", "epubcfi(/6/2!/4/2/3:0)")
            )

            manager.startOrResume(
                bookId = "book-cast-lines",
                bookTitle = "Persisted Cast Lines",
                chapters = chapterDao.chapters,
                currentChapterIndex = 0,
                paragraphs = paragraphs,
                characters = characters
            )
            runCurrent()
            advanceUntilIdle()

            assertEquals(3, capturingEngine.requests.size)
            assertEquals("Sulafat", capturingEngine.requests[0].voiceId)
            assertEquals("calm", capturingEngine.requests[0].style)

            // Single-voice mode keeps the narrator voice consistent while still passing character delivery cues.
            assertEquals("Sulafat", capturingEngine.requests[1].voiceId)
            assertEquals("Alice", capturingEngine.requests[1].speakerName)
            assertEquals("whispering", capturingEngine.requests[1].style)

            assertEquals("Sulafat", capturingEngine.requests[2].voiceId)
            assertEquals("Bob", capturingEngine.requests[2].speakerName)
            assertEquals("gruff", capturingEngine.requests[2].style)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `buildSpeechChunks heuristic fallback handles multi-script quotes dashes aliases and whole-word boundaries`() {
        val manager = ReaderNarrationSessionManager(
            bookDao = FakeBookDao(),
            chapterDao = FakeChapterDao(),
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
        val characters = listOf(
            CharacterEntity("b_narr", "b1", "Narrator", "", "neutral", "mature", "steady", "Sulafat"),
            CharacterEntity("b_al", "b1", "Al", "", "male", "young", "brisk", "Puck"),
            CharacterEntity("b_elena", "b1", "Elena", "Lena, Captain Elena", "female", "adult", "poised", "Kore"),
            CharacterEntity("b_taro", "b1", "太郎", "", "male", "young", "bright", "Orus")
        )
        val paragraphs = listOf(
            ReaderParagraph(0, "p0", "\"Always keep moving forward,\" remarked the traveler.", "epubcfi(/6/2!/4/2/1:0)"),
            ReaderParagraph(1, "p1", "«Quel beau matin !» murmura Lena.", "epubcfi(/6/2!/4/2/2:0)"),
            ReaderParagraph(2, "p2", "— Look out! — shouted Al.", "epubcfi(/6/2!/4/2/3:0)"),
            ReaderParagraph(3, "p3", "「おはようございます」と太郎が言った。", "epubcfi(/6/2!/4/2/4:0)")
        )

        val chunks = manager.buildSpeechChunks(
            paragraphs = paragraphs,
            characters = characters,
            engine = NarrationEngine.SYSTEM_TTS,
            defaultNarratorVoiceId = "Charon"
        )

        assertEquals(4, chunks.size)
        assertTrue(chunks.all { it.voiceId == "Sulafat" })
        assertEquals("Narrator", chunks[0].speakerName)
        assertEquals("Elena", chunks[1].speakerName)
        assertEquals("poised", chunks[1].style)
        assertEquals("Al", chunks[2].speakerName)
        assertEquals("brisk", chunks[2].style)
        assertEquals("太郎", chunks[3].speakerName)
        assertEquals("bright", chunks[3].style)
    }

    @Test
    fun `TranslationDiskCache persists and reloads BookNarrationContext with story progression and personalities`() {
        val tempDir = Files.createTempDirectory("reader-narration-context").toFile()
        try {
            val cache = TranslationDiskCache(tempDir)
            val empty = cache.loadNarrationContext("book-ctx")
            assertEquals("", empty.storySummary)
            assertTrue(empty.chapterSummaries.isEmpty())
            assertTrue(empty.characterPersonalities.isEmpty())

            val expected = TranslationDiskCache.BookNarrationContext(
                storySummary = "Alice unlocks the lower archive while Station Nine loses hull pressure.",
                chapterSummaries = mapOf(
                    0 to "Alice boards the silent relay station.",
                    1 to "The backup core activates with a lockdown alert."
                ),
                characterPersonalities = mapOf(
                    "alice" to "Resourceful engineer; composed even under extreme stress.",
                    "narrator" to "Steady, atmospheric, and keenly observant."
                ),
                contextVersion = TranslationDiskCache.CURRENT_NARRATION_CONTEXT_VERSION
            )
            cache.saveNarrationContext("book-ctx", expected)

            val reloaded = TranslationDiskCache(tempDir).loadNarrationContext("book-ctx")
            assertEquals(expected, reloaded)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `gemini live receives book context personalities tailored styles and reuses session beyond two turns`() = runTest {
        val tempDir = Files.createTempDirectory("reader-live-ctx").toFile()
        try {
            val bookId = "book-live-ctx"
            val p0Text = "Stay behind me."
            val p1Text = "[Security lockdown engaged.]"
            val p2Text = "The corridor lights flickered out."
            val p3Text = "Water crept over the steel grating."
            val p4Text = "Someone tapped twice from the dark."
            val allTexts = listOf(p0Text, p1Text, p2Text, p3Text, p4Text)

            val bookDao = FakeBookDao().apply {
                insertBook(
                    BookEntity(
                        id = bookId,
                        title = "Deep Relay",
                        author = "Author",
                        format = "EPUB",
                        filePath = "",
                        coverUri = null,
                        addedDate = 100L,
                        lastReadDate = 100L,
                        isAudiobook = false
                    )
                )
            }
            val chapterDao = FakeChapterDao(
                listOf(ChapterEntity("c0", bookId, 0, "Ch 1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 200))
            )
            val narrationDao = FakeNarrationDao(chapterDao).apply {
                insertLines(
                    listOf(
                        NarrationLineEntity("l0", "c0", 0, 0, p0Text.length, "${bookId}_alice", "quiet, urgent command", "epubcfi(/6/2!/4/2/1:0)"),
                        NarrationLineEntity("l1", "c0", 1, 0, p1Text.length, "narrator", null, "epubcfi(/6/2!/4/2/2:0)"),
                        NarrationLineEntity("l2", "c0", 2, 0, p2Text.length, "narrator", "tense, measured dread", "epubcfi(/6/2!/4/2/3:0)"),
                        NarrationLineEntity("l3", "c0", 3, 0, p3Text.length, "narrator", "tense, measured dread", "epubcfi(/6/2!/4/2/4:0)"),
                        NarrationLineEntity("l4", "c0", 4, 0, p4Text.length, "narrator", "tense, measured dread", "epubcfi(/6/2!/4/2/5:0)")
                    )
                )
            }
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
                setDefaultNarratorVoiceId("Charon")
            }

            data class SessionRecord(
                val systemInstruction: String,
                val prompts: MutableList<String> = mutableListOf()
            )
            val sessionRecords = mutableListOf<SessionRecord>()
            val pcmBase64 = Base64.getEncoder().encodeToString(ByteArray(48_000) { 0x15 })

            val factory = object : LiveSessionFactory {
                override fun create(): LiveSession = create(reconnectOnGoAway = true)
                override fun create(reconnectOnGoAway: Boolean): LiveSession {
                    return object : LiveSession {
                        private val _incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 32)
                        override val incomingMessages: Flow<LiveServerMessage> = _incoming.asSharedFlow()
                        private var connected = false
                        private var currentRecord: SessionRecord? = null
                        override val isConnected: Boolean get() = connected

                        override suspend fun connect(apiKey: String, setup: SetupConfig) {
                            connected = true
                            val instruction = setup.systemInstruction?.parts.orEmpty()
                                .mapNotNull { it.text }
                                .joinToString("\n")
                            currentRecord = SessionRecord(instruction).also { sessionRecords += it }
                        }

                        override suspend fun send(message: LiveClientMessage) {
                            val clientMsg = message as? LiveClientMessage.ClientContentMessage ?: return
                            val promptText = clientMsg.clientContent.turns.orEmpty()
                                .flatMap { it.parts }
                                .mapNotNull { it.text }
                                .joinToString("\n")
                            currentRecord?.prompts?.add(promptText)
                            val verbatim = allTexts.firstOrNull { promptText.contains(it) } ?: promptText
                            _incoming.emit(
                                LiveServerMessage.ServerContentMessage(
                                    ServerContentData(
                                        modelTurn = ModelTurn(
                                            parts = listOf(Part(inlineData = Blob("audio/pcm;rate=24000", pcmBase64)))
                                        ),
                                        outputTranscription = TranscriptionData(verbatim),
                                        turnComplete = true
                                    )
                                )
                            )
                        }

                        override suspend fun disconnect() {
                            connected = false
                        }
                    }
                }
            }

            val manager = ReaderNarrationSessionManager(
                bookDao = bookDao,
                chapterDao = chapterDao,
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("live-test-key"),
                liveSessionFactory = factory,
                applicationScope = backgroundScope,
                narrationDao = narrationDao
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { RecordingPcmPlayer() }
            }
            runCurrent()

            manager.saveBookNarrationContext(
                bookId = bookId,
                context = TranslationDiskCache.BookNarrationContext(
                    storySummary = "Alice escapes the station in Chapter 1.",
                    chapterSummaries = mapOf(
                        0 to "Lockdown traps Alice in the lower corridor.",
                        1 to "Later chapter spoiler."
                    ),
                    characterPersonalities = mapOf(
                        "alice" to "Calm under pressure; clipped, decisive leader.",
                        "narrator" to "Grave, atmospheric storyteller."
                    )
                )
            )

            val characters = listOf(
                CharacterEntity("${bookId}_narrator", bookId, "Narrator", "", "neutral", "mature", "atmospheric", "Charon"),
                CharacterEntity("${bookId}_alice", bookId, "Alice", "", "female", "adult", "steady", "Zephyr")
            )
            val paragraphs = allTexts.mapIndexed { idx, text ->
                ReaderParagraph(idx, "p$idx", text, "epubcfi(/6/2!/4/2/${idx + 1}:0)")
            }

            manager.startOrResume(
                bookId = bookId,
                bookTitle = "Deep Relay",
                chapters = chapterDao.chapters,
                currentChapterIndex = 0,
                paragraphs = paragraphs,
                characters = characters
            )
            runCurrent()
            advanceUntilIdle()

            assertNull(manager.sessionState.value.errorMessage)
            // Single-voice continuity keeps ONE persistent Live WebSocket across all 5 paragraphs
            // instead of disconnecting/reconnecting whenever character style or speaker changes.
            assertEquals(1, sessionRecords.size)

            val session = sessionRecords[0]
            assertTrue(session.systemInstruction.contains("Alice"))
            assertTrue(session.systemInstruction.contains("Calm under pressure"))
            assertTrue(session.systemInstruction.contains("Lockdown traps Alice in the lower corridor."))
            assertFalse(session.systemInstruction.contains("Later chapter spoiler."))
            assertTrue(session.systemInstruction.contains("quiet, urgent command"))
            assertEquals(allTexts.map { "<read>$it</read>" }, session.prompts)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `flash tts single-voice batching includes character acting cues via speechMetadata without speaking Say as`() = runTest {
        val tempDir = Files.createTempDirectory("reader-flash-single-voice").toFile()
        try {
            val bookId = "book-flash-single"
            val capturedRequests = mutableListOf<TtsRequest>()
            val pcm = ByteArray(2400) { 0x12 }

            val ttsClient = object : InteractionsTtsClient {
                override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray = pcm
                override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> {
                    capturedRequests += request
                    return flowOf(PcmChunk(pcm, 24000, 1))
                }
            }
            val bookDao = FakeBookDao().apply {
                insertBook(
                    BookEntity(bookId, "ORV", "Author", "EPUB", "", null, 100L, 100L, false)
                )
            }
            val chapterDao = FakeChapterDao(
                listOf(ChapterEntity("c0", bookId, 0, "Ch 1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 120))
            )
            val narrationDao = FakeNarrationDao(chapterDao).apply {
                insertLines(
                    listOf(
                        NarrationLineEntity("l0", "c0", 0, 0, 20, "narrator", "tense storytelling", "epubcfi(/6/2!/4/2/1:0)"),
                        NarrationLineEntity("l1", "c0", 1, 0, 34, "${bookId}_namwoon", "mocking, aggressive", "epubcfi(/6/2!/4/2/2:0)")
                    )
                )
            }
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_FLASH_TTS.name)
                setDefaultNarratorVoiceId("Charon")
            }
            val manager = ReaderNarrationSessionManager(
                bookDao = bookDao,
                chapterDao = chapterDao,
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("flash-test-key"),
                interactionsTtsClient = ttsClient,
                applicationScope = backgroundScope,
                narrationDao = narrationDao
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { RecordingPcmPlayer() }
            }
            runCurrent()

            val characters = listOf(
                CharacterEntity("${bookId}_narrator", bookId, "Narrator", "", "neutral", "mature", "clear", "Charon"),
                CharacterEntity("${bookId}_namwoon", bookId, "Kim Namwoon", "", "male", "teen", "brash", "Zephyr")
            )
            val paragraphs = listOf(
                ReaderParagraph(0, "p0", "The subway halted abruptly.", "epubcfi(/6/2!/4/2/1:0)"),
                ReaderParagraph(1, "p1", "\"Hey! Why would you do that?!\"", "epubcfi(/6/2!/4/2/2:0)")
            )

            manager.startOrResume(
                bookId = bookId,
                bookTitle = "ORV",
                chapters = chapterDao.chapters,
                currentChapterIndex = 0,
                paragraphs = paragraphs,
                characters = characters
            )
            runCurrent()
            advanceUntilIdle()

            // Both paragraphs merge into 1 single-narrator Flash request carrying per-segment styles
            assertEquals(1, capturedRequests.size)
            val req = capturedRequests.single()
            assertEquals("Charon", req.voiceName)
            assertEquals(2, req.segments.size)
            assertEquals("The subway halted abruptly.", req.segments[0].text)
            assertEquals("\"Hey! Why would you do that?!\"", req.segments[1].text)
            assertEquals("Kim Namwoon", req.segments[1].speaker)
            assertEquals("mocking, aggressive", req.segments[1].style)
            assertFalse(req.text.contains("Say as "))
            assertFalse((req.style ?: "").contains("Say as "))
            assertTrue(req.segments.none { it.text.contains("Say as ") })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `buildSpeechChunks keeps contractions and apostrophe words intact without breaking before apostrophes`() {
        val manager = ReaderNarrationSessionManager(
            bookDao = FakeBookDao(),
            chapterDao = FakeChapterDao(),
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        )
        val sentence = "I don't know if it's I'm, O'Brien's, or the dogs' toy\u2019s fault."
        val paragraphs = listOf(
            ReaderParagraph(0, "p0", sentence, "epubcfi(/6/2!/4/2/1:0)")
        )

        listOf(
            NarrationEngine.GEMINI_FLASH_TTS,
            NarrationEngine.GEMINI_LIVE,
            NarrationEngine.SYSTEM_TTS
        ).forEach { engine ->
            val chunks = manager.buildSpeechChunks(
                paragraphs = paragraphs,
                characters = emptyList(),
                engine = engine,
                defaultNarratorVoiceId = "Charon"
            )
            assertEquals("Engine $engine should keep apostrophe sentence in one chunk", 1, chunks.size)
            assertEquals(sentence, chunks.single().text)
        }
    }

    private fun failingLiveSessionFactory(errorMessage: String = "Live overloaded"): LiveSessionFactory =
        object : LiveSessionFactory {
            override fun create(): LiveSession = create(reconnectOnGoAway = true)
            override fun create(reconnectOnGoAway: Boolean): LiveSession {
                return object : LiveSession {
                    private val _incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 16)
                    override val incomingMessages: Flow<LiveServerMessage> = _incoming.asSharedFlow()
                    private var connected = false
                    override val isConnected: Boolean get() = connected

                    override suspend fun connect(apiKey: String, setup: SetupConfig) {
                        connected = true
                    }

                    override suspend fun send(message: LiveClientMessage) {
                        _incoming.emit(
                            LiveServerMessage.ErrorMessage(
                                LiveErrorData(code = 503, message = errorMessage)
                            )
                        )
                    }

                    override suspend fun disconnect() {
                        connected = false
                    }
                }
            }
        }

    @Test
    fun `gemini live clears device TTS fallback status banner once next paragraph recovers on Live`() = runTest {
        val tempDir = Files.createTempDirectory("reader-live-status-recover").toFile()
        try {
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
            }
            val p0Text = "Paragraph zero fails all Live attempts."
            val p1Text = "Paragraph one recovers cleanly on Gemini Live."
            val deviceFallbackPcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val liveRecoveredPcm = ByteArray(96_000) { 0x33 }
            val liveRecoveredB64 = Base64.getEncoder().encodeToString(liveRecoveredPcm)

            val p1ReachedLive = CompletableDeferred<Unit>()
            val allowP1Live = CompletableDeferred<Unit>()

            val systemFallbackEngine = object : FeatureNarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = testCapabilities(streaming = false)
                val requests = mutableListOf<SynthesisRequest>()
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    requests += request
                    emit(PcmChunk(deviceFallbackPcm, sampleRate = 24000, channels = 1))
                }
            }

            val factory = object : LiveSessionFactory {
                override fun create(): LiveSession = create(reconnectOnGoAway = true)
                override fun create(reconnectOnGoAway: Boolean): LiveSession {
                    return object : LiveSession {
                        private val _incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 16)
                        override val incomingMessages: Flow<LiveServerMessage> = _incoming.asSharedFlow()
                        private var connected = false
                        override val isConnected: Boolean get() = connected

                        override suspend fun connect(apiKey: String, setup: SetupConfig) {
                            connected = true
                        }

                        override suspend fun send(message: LiveClientMessage) {
                            val clientMsg = message as? LiveClientMessage.ClientContentMessage ?: return
                            val promptText = clientMsg.clientContent.turns.orEmpty()
                                .flatMap { it.parts }
                                .mapNotNull { it.text }
                                .joinToString("\n")
                            if (promptText.contains(p0Text)) {
                                _incoming.emit(
                                    LiveServerMessage.ErrorMessage(
                                        LiveErrorData(code = 503, message = "Overloaded")
                                    )
                                )
                            } else {
                                p1ReachedLive.complete(Unit)
                                allowP1Live.await()
                                _incoming.emit(
                                    LiveServerMessage.ServerContentMessage(
                                        ServerContentData(
                                            modelTurn = ModelTurn(
                                                parts = listOf(Part(inlineData = Blob("audio/pcm;rate=24000", liveRecoveredB64)))
                                            ),
                                            outputTranscription = TranscriptionData(p1Text),
                                            turnComplete = true
                                        )
                                    )
                                )
                            }
                        }

                        override suspend fun disconnect() {
                            connected = false
                        }
                    }
                }
            }

            val player = RecordingPcmPlayer()
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("live-recover-key"),
                liveSessionFactory = factory,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { player }
                systemTtsFactory = { systemFallbackEngine }
            }
            runCurrent()

            manager.startOrResume(
                bookId = "book-live-recover",
                bookTitle = "Live Recovery",
                paragraphs = listOf(
                    ReaderParagraph(0, "p0", p0Text, "epubcfi(/6/2!/4/2/1:0)"),
                    ReaderParagraph(1, "p1", p1Text, "epubcfi(/6/2!/4/2/2:0)")
                )
            )
            runCurrent()

            assertTrue("Paragraph 1 should reach Live turn after paragraph 0 fell back", p1ReachedLive.isCompleted)
            assertEquals(1, systemFallbackEngine.requests.size)
            assertEquals(p0Text, systemFallbackEngine.requests[0].text)
            assertEquals(
                "Using device TTS fallback (Gemini Live unavailable)",
                manager.sessionState.value.statusMessage
            )

            allowP1Live.complete(Unit)
            runCurrent()
            advanceUntilIdle()

            assertNull(
                "Recovered Live turn must clear device TTS fallback statusMessage back to null",
                manager.sessionState.value.statusMessage
            )
            assertNull(manager.sessionState.value.errorMessage)
            assertFalse(manager.sessionState.value.isPlaying)
            assertEquals(2, player.writtenChunks.size)
            assertTrue(player.writtenChunks[0].data.contentEquals(deviceFallbackPcm))
            assertTrue(player.writtenChunks[1].data.contentEquals(liveRecoveredPcm))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `gemini live cached playback hit clears device TTS fallback status banner and synthesizeSpeechChunk notifies onClearFallbackStatus`() = runTest {
        val tempDir = Files.createTempDirectory("reader-live-cache-recover").toFile()
        try {
            val diskCache = TranslationDiskCache(tempDir)
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
            }
            val p0Text = "Uncached paragraph zero fails Live."
            val p1Text = "Cached paragraph one hits Gemini Live V4 cache."
            val deviceFallbackPcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val cachedLivePcm = ByteArray(2000) { 0x55 }

            diskCache.saveAudioSegment(
                engineTag = ReaderNarrationSessionManager.GEMINI_LIVE_CACHE_TAG_PREFIX,
                voiceId = "Charon",
                style = null,
                text = p1Text,
                sampleRate = 24000,
                channels = 1,
                pcmBytes = cachedLivePcm
            )

            val systemFallbackEngine = object : FeatureNarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = testCapabilities(streaming = false)
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    emit(PcmChunk(deviceFallbackPcm, sampleRate = 24000, channels = 1))
                }
            }

            val failingLiveFactory = failingLiveSessionFactory("Overloaded")

            val player = RecordingPcmPlayer()
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("live-cache-recover-key"),
                liveSessionFactory = failingLiveFactory,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = diskCache
                playerFactory = { player }
                systemTtsFactory = { systemFallbackEngine }
            }
            runCurrent()

            val observedStatuses = mutableListOf<String?>()
            val collectorJob = backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
                manager.sessionState.collect { state ->
                    if (observedStatuses.lastOrNull() != state.statusMessage) {
                        observedStatuses += state.statusMessage
                    }
                }
            }

            manager.startOrResume(
                bookId = "book-live-cache-recover",
                bookTitle = "Live Cache Recovery",
                paragraphs = listOf(
                    ReaderParagraph(0, "p0", p0Text, "epubcfi(/6/2!/4/2/1:0)"),
                    ReaderParagraph(1, "p1", p1Text, "epubcfi(/6/2!/4/2/2:0)")
                )
            )
            runCurrent()
            advanceUntilIdle()
            collectorJob.cancel()

            assertTrue(
                "Device TTS fallback banner must have been displayed before cached recovery",
                observedStatuses.contains("Using device TTS fallback (Gemini Live unavailable)")
            )
            assertNull(manager.sessionState.value.statusMessage)
            assertEquals(2, player.writtenChunks.size)
            assertTrue(player.writtenChunks[0].data.contentEquals(deviceFallbackPcm))
            assertTrue(player.writtenChunks[1].data.contentEquals(cachedLivePcm))

            var clearCalls = 0
            val emittedChunks = mutableListOf<PcmChunk>()
            manager.synthesizeSpeechChunk(
                chunk = SpeechChunk(
                    text = p1Text,
                    leadCfi = "epubcfi(/6/2!/4/2/2:0)",
                    allCfis = listOf("epubcfi(/6/2!/4/2/2:0)"),
                    leadIndex = 1,
                    voiceId = "Charon",
                    style = null,
                    paragraphIndices = listOf(1),
                    paragraphCharWeights = listOf(p1Text.length)
                ),
                engineChoice = NarrationEngine.GEMINI_LIVE,
                systemFallback = systemFallbackEngine,
                diskCache = diskCache,
                maxCacheBytes = TranslationDiskCache.DEFAULT_MAX_AUDIO_BYTES,
                ttsAlreadyFailed = false,
                onTtsFailed = {},
                onStatusUpdate = {},
                onClearFallbackStatus = { clearCalls++ },
                onPcmChunk = { emittedChunks += it }
            )
            assertEquals(1, clearCalls)
            assertEquals(1, emittedChunks.size)
            assertTrue(emittedChunks.single().data.contentEquals(cachedLivePcm))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `transient QuotaExhaustedException on Flash TTS fallback does not permanently latch sessionEngineFailedTts once cooldown clears`() = runTest {
        val tempDir = Files.createTempDirectory("reader-quota-latch-test").toFile()
        try {
            var nowMs = 1_000_000L
            val quotaTracker = GeminiQuotaTracker(clockMs = { nowMs })
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
            }
            val p0Text = "Paragraph zero hits Live error and transient Flash TTS 429 cooldown."
            val p1Text = "Paragraph one falls back to device TTS while Flash TTS cooldown is active."
            val p2Text = "Paragraph two re-enables Flash TTS fallback once cooldown expires."

            val systemPcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            val flashTtsPcm = ByteArray(1600) { 0x44 }

            val p1FallbackDone = CompletableDeferred<Unit>()
            val allowP2Start = CompletableDeferred<Unit>()

            var ttsCallCount = 0
            val ttsClient = object : InteractionsTtsClient {
                override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray = flashTtsPcm

                override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> = flow {
                    ttsCallCount++
                    if (ttsCallCount == 1) {
                        quotaTracker.ttsCandidatePool(DefaultModels.get(ModelRole.TTS)).forEach { model ->
                            quotaTracker.recordHttpError(
                                modelId = model,
                                httpCode = 429,
                                responseBody = "ResourceExhausted",
                                retryAfterHeader = "10"
                            )
                        }
                        throw QuotaExhaustedException("Transient 429 cooldown", retryAfterMs = 10_000L)
                    }
                    emit(PcmChunk(flashTtsPcm, 24000, 1))
                }
            }

            var systemCallCount = 0
            val systemFallbackEngine = object : FeatureNarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = testCapabilities(streaming = false)
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    val callNum = ++systemCallCount
                    emit(PcmChunk(systemPcm, 24000, 1))
                    if (callNum == 2) {
                        p1FallbackDone.complete(Unit)
                        allowP2Start.await()
                    }
                }
            }

            val liveFactory = failingLiveSessionFactory("Live overloaded")

            val player = RecordingPcmPlayer()
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("quota-latch-key"),
                interactionsTtsClient = ttsClient,
                liveSessionFactory = liveFactory,
                quotaTracker = quotaTracker,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { player }
                systemTtsFactory = { systemFallbackEngine }
            }
            runCurrent()

            manager.startOrResume(
                bookId = "book-quota-latch",
                bookTitle = "Quota Latch",
                paragraphs = listOf(
                    ReaderParagraph(0, "p0", p0Text, "epubcfi(/6/2!/4/2/1:0)"),
                    ReaderParagraph(1, "p1", p1Text, "epubcfi(/6/2!/4/2/2:0)"),
                    ReaderParagraph(2, "p2", p2Text, "epubcfi(/6/2!/4/2/3:0)")
                )
            )
            runCurrent()

            assertTrue("Paragraph 1 should complete device fallback before paragraph 2", p1FallbackDone.isCompleted)
            assertEquals("Paragraph 0 calls Flash TTS once; paragraph 1 skips during cooldown", 1, ttsCallCount)
            assertEquals(2, systemCallCount)
            assertTrue(quotaTracker.isTtsQuotaLikelyExhausted())

            nowMs += 15_000L
            assertFalse(quotaTracker.isTtsQuotaLikelyExhausted())

            allowP2Start.complete(Unit)
            runCurrent()
            advanceUntilIdle()

            assertEquals(
                "Flash TTS fallback must re-enable automatically once quota cooldown clears",
                2,
                ttsCallCount
            )
            assertEquals("Paragraph 2 must use Flash TTS instead of device TTS", 2, systemCallCount)
            assertEquals(3, player.writtenChunks.size)
            assertTrue(player.writtenChunks[0].data.contentEquals(systemPcm))
            assertTrue(player.writtenChunks[1].data.contentEquals(systemPcm))
            assertTrue(player.writtenChunks[2].data.contentEquals(flashTtsPcm))
            assertNull(manager.sessionState.value.errorMessage)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `non-quota exception on Flash TTS fallback still latches sessionEngineFailedTts for rest of session`() = runTest {
        val tempDir = Files.createTempDirectory("reader-nonquota-latch-test").toFile()
        try {
            val quotaTracker = GeminiQuotaTracker()
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
            }
            val systemPcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
            var ttsCallCount = 0
            val ttsClient = object : InteractionsTtsClient {
                override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray = systemPcm
                override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> = flow {
                    ttsCallCount++
                    throw IllegalStateException("Unexpected malformed audio payload")
                }
            }
            var systemCallCount = 0
            val systemFallbackEngine = object : FeatureNarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = testCapabilities(streaming = false)
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    systemCallCount++
                    emit(PcmChunk(systemPcm, 24000, 1))
                }
            }
            val failingLiveFactory = failingLiveSessionFactory("Live overloaded")

            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("nonquota-latch-key"),
                interactionsTtsClient = ttsClient,
                liveSessionFactory = failingLiveFactory,
                quotaTracker = quotaTracker,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { RecordingPcmPlayer() }
                systemTtsFactory = { systemFallbackEngine }
            }
            runCurrent()

            manager.startOrResume(
                bookId = "book-nonquota-latch",
                bookTitle = "Non-quota Latch",
                paragraphs = listOf(
                    ReaderParagraph(0, "p0", "First paragraph hits non-quota TTS error.", "epubcfi(/6/2!/4/2/1:0)"),
                    ReaderParagraph(1, "p1", "Second paragraph must skip TTS after latch.", "epubcfi(/6/2!/4/2/2:0)")
                )
            )
            runCurrent()
            advanceUntilIdle()

            assertFalse(quotaTracker.isTtsQuotaLikelyExhausted())
            assertEquals("Non-quota error must latch sessionEngineFailedTts on first failure", 1, ttsCallCount)
            assertEquals(2, systemCallCount)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `gemini live preloads configured livePreloadChunks before first player write and caches under V5 for pause-resume`() = runTest {
        val tempDir = Files.createTempDirectory("reader-live-preload-setting").toFile()
        try {
            val diskCache = TranslationDiskCache(tempDir)
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
                setDefaultNarratorVoiceId("Zephyr")
                setLivePreloadChunks(2)
            }
            val texts = listOf(
                "Zero paragraph preloaded.",
                "First paragraph preloaded.",
                "Second paragraph synthesized later.",
                "Third paragraph synthesized last."
            )
            val gateFor2 = CompletableDeferred<Unit>()
            val synthesized = mutableListOf<String>()
            var playerWritesWhen2Reached = -1

            val pcmForText: (String) -> ByteArray = { text ->
                val tag = texts.indexOf(text).coerceAtLeast(0) + 11
                ByteArray(72_000) { tag.toByte() }
            }

            val factory = object : LiveSessionFactory {
                override fun create(): LiveSession = create(reconnectOnGoAway = true)
                override fun create(reconnectOnGoAway: Boolean): LiveSession = object : LiveSession {
                    private val _incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 32)
                    override val incomingMessages: Flow<LiveServerMessage> = _incoming.asSharedFlow()
                    private var connected = false
                    override val isConnected: Boolean get() = connected

                    override suspend fun connect(apiKey: String, setup: SetupConfig) {
                        connected = true
                    }

                    override suspend fun send(message: LiveClientMessage) {
                        val clientMsg = message as? LiveClientMessage.ClientContentMessage ?: return
                        val promptText = clientMsg.clientContent.turns.orEmpty()
                            .flatMap { it.parts }
                            .mapNotNull { it.text }
                            .joinToString("\n")
                        val matched = texts.first { promptText.contains(it) }
                        if (matched == texts[2]) {
                            gateFor2.await()
                        }
                        synthesized += matched
                        val b64 = Base64.getEncoder().encodeToString(pcmForText(matched))
                        _incoming.emit(
                            LiveServerMessage.ServerContentMessage(
                                ServerContentData(
                                    modelTurn = ModelTurn(
                                        parts = listOf(Part(inlineData = Blob("audio/pcm;rate=24000", b64)))
                                    ),
                                    outputTranscription = TranscriptionData(matched),
                                    turnComplete = true
                                )
                            )
                        )
                    }

                    override suspend fun disconnect() {
                        connected = false
                    }
                }
            }

            lateinit var player: RecordingPcmPlayer
            player = RecordingPcmPlayer(onWrite = { _ -> })
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("preload-key"),
                liveSessionFactory = factory,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = diskCache
                playerFactory = { player }
            }
            runCurrent()
            assertEquals(2, manager.sessionState.value.livePreloadChunks)

            manager.startOrResume(
                bookId = "book-preload-test",
                bookTitle = "Preload Test",
                paragraphs = texts.mapIndexed { idx, txt ->
                    ReaderParagraph(idx, "p$idx", txt, "epubcfi(/6/2!/4/2/${idx + 1}:0)")
                }
            )
            runCurrent()

            // First 2 chunks are synthesized and cached BEFORE chunk 2 completes; player starts playing chunks 0..1 while chunk 2 waits.
            assertEquals(listOf(texts[0], texts[1]), synthesized)
            playerWritesWhen2Reached = player.writtenChunks.size
            assertEquals(2, playerWritesWhen2Reached)

            // Pause before chunks 2..3 finish; verify chunks 0..1 are cached under GEMINI_LIVE_CACHE_TAG_PREFIX on disk.
            manager.pause()
            runCurrent()
            assertNotNull(diskCache.loadAudioSegment(ReaderNarrationSessionManager.GEMINI_LIVE_CACHE_TAG_PREFIX, "Zephyr", null, texts[0]))
            assertNotNull(diskCache.loadAudioSegment(ReaderNarrationSessionManager.GEMINI_LIVE_CACHE_TAG_PREFIX, "Zephyr", null, texts[1]))

            // Stop session and re-start from paragraph 0 after releasing gateFor2: chunks 0 & 1 come from disk cache (0 new synthesis calls!).
            manager.stop()
            runCurrent()
            gateFor2.complete(Unit)
            synthesized.clear()
            player.writtenChunks.clear()
            manager.startOrResume(
                bookId = "book-preload-test",
                bookTitle = "Preload Test",
                paragraphs = texts.mapIndexed { idx, txt ->
                    ReaderParagraph(idx, "p$idx", txt, "epubcfi(/6/2!/4/2/${idx + 1}:0)")
                },
                activeParagraphIndex = 0
            )
            runCurrent()
            advanceUntilIdle()

            assertEquals("Only uncached chunks 2 and 3 should synthesize on resume", listOf(texts[2], texts[3]), synthesized)
            assertEquals(4, player.writtenChunks.size)
            texts.forEachIndexed { idx, txt ->
                assertTrue("Chunk $idx must play in exact order", player.writtenChunks[idx].data.contentEquals(pcmForText(txt)))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `gemini live completion resets active session so subsequent startOrResume from paragraph 0 replays full chapter from cache`() = runTest {
        val tempDir = Files.createTempDirectory("reader-live-replay-completion").toFile()
        try {
            val diskCache = TranslationDiskCache(tempDir)
            val texts = listOf("First line.", "Second line.", "Third line.")
            fun pcmForText(text: String): ByteArray {
                val tag = (texts.indexOf(text).coerceAtLeast(0) + 11).toByte()
                return ByteArray(24_000) { tag }
            }

            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
                setDefaultNarratorVoiceId("Zephyr")
                setLivePreloadChunks(2)
            }
            val synthCount = AtomicInteger(0)
            val factory = object : LiveSessionFactory {
                override fun create(): LiveSession = create(reconnectOnGoAway = true)
                override fun create(reconnectOnGoAway: Boolean): LiveSession = object : LiveSession {
                    private val _incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 16)
                    override val incomingMessages: Flow<LiveServerMessage> = _incoming.asSharedFlow()
                    private var connected = false
                    override val isConnected: Boolean get() = connected

                    override suspend fun connect(apiKey: String, setup: SetupConfig) {
                        connected = true
                    }

                    override suspend fun send(message: LiveClientMessage) {
                        val clientMsg = message as? LiveClientMessage.ClientContentMessage ?: return
                        val promptText = clientMsg.clientContent.turns.orEmpty()
                            .flatMap { it.parts }
                            .mapNotNull { it.text }
                            .joinToString("\n")
                        val matched = texts.first { promptText.contains(it) }
                        synthCount.incrementAndGet()
                        val b64 = Base64.getEncoder().encodeToString(pcmForText(matched))
                        _incoming.emit(
                            LiveServerMessage.ServerContentMessage(
                                ServerContentData(
                                    modelTurn = ModelTurn(
                                        parts = listOf(Part(inlineData = Blob("audio/pcm;rate=24000", b64)))
                                    ),
                                    outputTranscription = TranscriptionData(matched),
                                    turnComplete = true
                                )
                            )
                        )
                    }

                    override suspend fun disconnect() {
                        connected = false
                    }
                }
            }

            val player = RecordingPcmPlayer(onWrite = { _ -> })
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("replay-key"),
                liveSessionFactory = factory,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = diskCache
                playerFactory = { player }
            }
            runCurrent()

            val paragraphs = texts.mapIndexed { idx, txt ->
                ReaderParagraph(idx, "p$idx", txt, "epubcfi(/6/2!/4/2/${idx + 1}:0)")
            }
            manager.startOrResume(
                bookId = "book-replay-complete",
                bookTitle = "Replay Book",
                paragraphs = paragraphs,
                activeParagraphIndex = 0
            )
            runCurrent()
            advanceUntilIdle()

            assertFalse(manager.sessionState.value.isPlaying)
            assertFalse(manager.sessionState.value.isActiveSession)
            assertNull(manager.sessionState.value.highlightedCfi)
            assertEquals(3, player.writtenChunks.size)
            assertEquals(3, synthCount.get())

            player.writtenChunks.clear()
            manager.startOrResume(
                bookId = "book-replay-complete",
                bookTitle = "Replay Book",
                paragraphs = paragraphs,
                activeParagraphIndex = 0
            )
            runCurrent()
            advanceUntilIdle()

            assertFalse(manager.sessionState.value.isPlaying)
            assertFalse(manager.sessionState.value.isActiveSession)
            assertEquals("Replaying from paragraph 0 must synthesize 0 new chunks", 3, synthCount.get())
            assertEquals("All 3 paragraphs must replay from index 0", 3, player.writtenChunks.size)
            texts.forEachIndexed { idx, txt ->
                assertTrue(player.writtenChunks[idx].data.contentEquals(pcmForText(txt)))
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `gemini live fallback exception on one chunk skips only that chunk continues playback and clears Preparing audio banner`() = runTest {
        val tempDir = Files.createTempDirectory("reader-live-fallback-skip").toFile()
        try {
            val texts = listOf(
                "Paragraph zero fails Live and fallback.",
                "Paragraph one plays cleanly.",
                "Paragraph two plays cleanly."
            )
            fun pcmFor(text: String): ByteArray {
                val tag = (texts.indexOf(text).coerceAtLeast(0) + 21).toByte()
                return ByteArray(48_000) { tag }
            }

            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.GEMINI_LIVE.name)
                setDefaultNarratorVoiceId("Zephyr")
                setLivePreloadChunks(3)
            }
            val throwingFallback = object : FeatureNarrationEngine {
                override val id = EngineId.SYSTEM_TTS
                override val capabilities = testCapabilities(streaming = false)
                override suspend fun voices(): List<VoiceInfo> = emptyList()
                override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
                    throw IllegalStateException("Device TTS crashed")
                }
            }
            val factory = object : LiveSessionFactory {
                override fun create(): LiveSession = create(reconnectOnGoAway = true)
                override fun create(reconnectOnGoAway: Boolean): LiveSession = object : LiveSession {
                    private val _incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 16)
                    override val incomingMessages: Flow<LiveServerMessage> = _incoming.asSharedFlow()
                    private var connected = false
                    override val isConnected: Boolean get() = connected

                    override suspend fun connect(apiKey: String, setup: SetupConfig) {
                        connected = true
                    }

                    override suspend fun send(message: LiveClientMessage) {
                        val clientMsg = message as? LiveClientMessage.ClientContentMessage ?: return
                        val promptText = clientMsg.clientContent.turns.orEmpty()
                            .flatMap { it.parts }
                            .mapNotNull { it.text }
                            .joinToString("\n")
                        if (promptText.contains(texts[0])) {
                            _incoming.emit(
                                LiveServerMessage.ErrorMessage(
                                    LiveErrorData(code = 400, message = "Bad request")
                                )
                            )
                        } else {
                            val matched = texts.first { promptText.contains(it) }
                            val b64 = Base64.getEncoder().encodeToString(pcmFor(matched))
                            _incoming.emit(
                                LiveServerMessage.ServerContentMessage(
                                    ServerContentData(
                                        modelTurn = ModelTurn(
                                            parts = listOf(Part(inlineData = Blob("audio/pcm;rate=24000", b64)))
                                        ),
                                        outputTranscription = TranscriptionData(matched),
                                        turnComplete = true
                                    )
                                )
                            )
                        }
                    }

                    override suspend fun disconnect() {
                        connected = false
                    }
                }
            }

            val player = RecordingPcmPlayer(onWrite = { _ -> })
            val manager = ReaderNarrationSessionManager(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                settingsRepository = settingsRepo,
                secureKeyStore = FakeSecureKeyStore("fallback-skip-key"),
                liveSessionFactory = factory,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = StandardTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                playerFactory = { player }
                systemTtsFactory = { throwingFallback }
            }
            runCurrent()

            manager.startOrResume(
                bookId = "book-fallback-skip",
                bookTitle = "Fallback Skip Book",
                paragraphs = texts.mapIndexed { idx, txt ->
                    ReaderParagraph(idx, "p$idx", txt, "epubcfi(/6/2!/4/2/${idx + 1}:0)")
                }
            )
            runCurrent()
            advanceUntilIdle()

            assertFalse(manager.sessionState.value.isPlaying)
            assertNull(manager.sessionState.value.errorMessage)
            assertFalse(
                "Preparing audio banner must never remain stuck",
                manager.sessionState.value.statusMessage?.startsWith("Preparing audio") == true
            )
            assertEquals(2, player.writtenChunks.size)
            assertTrue(player.writtenChunks[0].data.contentEquals(pcmFor(texts[1])))
            assertTrue(player.writtenChunks[1].data.contentEquals(pcmFor(texts[2])))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
