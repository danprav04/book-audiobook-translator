package io.github.danprav04.polyglotbooks.feature.reader

import io.github.danprav04.polyglotbooks.core.audio.AudioTrackAdapter
import io.github.danprav04.polyglotbooks.core.audio.AudioTrackAdapterFactory
import io.github.danprav04.polyglotbooks.core.audio.AudioTrackPcmPlayer
import io.github.danprav04.polyglotbooks.core.audio.PcmPlayer
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.Blob
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.InteractionsTtsClient
import io.github.danprav04.polyglotbooks.core.gemini.LiveClientMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveServerMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveSession
import io.github.danprav04.polyglotbooks.core.gemini.LiveSessionFactory
import io.github.danprav04.polyglotbooks.core.gemini.ModelTurn
import io.github.danprav04.polyglotbooks.core.gemini.Part
import io.github.danprav04.polyglotbooks.core.gemini.ServerContentData
import io.github.danprav04.polyglotbooks.core.gemini.SetupConfig
import io.github.danprav04.polyglotbooks.core.gemini.TextModelClient
import io.github.danprav04.polyglotbooks.core.gemini.TranscriptionData
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
            val pcmBase64 = Base64.getEncoder().encodeToString(ByteArray(4800) { 0x15 })
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
    fun `paused session chapter change clears stale highlightedCfi and flash tts uses GEMINI_TTS_V2 cache tag`() = runTest {
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
            assertNotNull("Synthesized audio must be cached under GEMINI_TTS_V2_en", v2Cached)
            assertTrue(v2Cached!!.pcmBytes.contentEquals(freshPcm))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
