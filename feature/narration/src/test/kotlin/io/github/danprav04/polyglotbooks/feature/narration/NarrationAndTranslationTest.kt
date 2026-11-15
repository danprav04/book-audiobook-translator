package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.gemini.Blob
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.InteractionsTtsClient
import io.github.danprav04.polyglotbooks.core.gemini.LiveClientMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveErrorData
import io.github.danprav04.polyglotbooks.core.gemini.LiveServerMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveSession
import io.github.danprav04.polyglotbooks.core.gemini.ModelTurn
import io.github.danprav04.polyglotbooks.core.gemini.Part
import io.github.danprav04.polyglotbooks.core.gemini.ServerContentData
import io.github.danprav04.polyglotbooks.core.gemini.SetupConfig
import io.github.danprav04.polyglotbooks.core.gemini.TextModelClient
import io.github.danprav04.polyglotbooks.core.gemini.TranscriptionData
import io.github.danprav04.polyglotbooks.core.gemini.TtsRequest
import io.github.danprav04.polyglotbooks.core.model.Character
import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class NarrationAndTranslationTest {

    @Test
    fun `VoiceCaster assigns distinct voices from 30 studio catalog and supports custom voices`() = runTest {
        assertEquals(30, GeminiStudioVoices.catalog.size)
        val caster = VoiceCaster()
        val characters = listOf(
            Character(id = "narrator", bookId = "b1", name = "Narrator", gender = "male", age = "mature", voiceTraits = "deep", assignedVoiceId = null),
            Character(id = "c1", bookId = "b1", name = "Elizabeth", gender = "female", age = "young", voiceTraits = "bright", assignedVoiceId = null),
            Character(id = "c2", bookId = "b1", name = "Darcy", gender = "male", age = "adult", voiceTraits = "steady", assignedVoiceId = null)
        )

        val map = caster.autoAssignVoices(characters)
        assertEquals("Charon", map["narrator"])
        assertNotEquals(map["narrator"], map["c1"])
        assertNotEquals(map["c1"], map["c2"])

        val custom = caster.assignCustomDesignedVoice("c2", "Custom Darcy", "male", "adult", listOf("aristocratic"))
        assertEquals(custom.id, caster.getVoice("c2"))
    }

    @Test
    fun `GeminiLiveEngine falls back to TTS engine when transcript similarity drops below 0_75`() = runTest {
        val fallbackMarker = ByteArray(12) { 42 }
        val fakeFallback = object : NarrationEngine {
            override val id = EngineId.GEMINI_TTS
            override val capabilities = EngineCapabilities(true, true, true, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> =
                flowOf(PcmChunk(fallbackMarker, 24000, 1))
        }

        val paraphrasingEngine = GeminiLiveEngine(
            fallbackEngine = fakeFallback,
            transcriptProvider = { "Something completely different and unrelated" }
        )

        val chunk = paraphrasingEngine.synthesize(
            SynthesisRequest(text = "It is a truth universally acknowledged", voiceId = "Kore")
        ).first()
        assertEquals(12, chunk.data.size)
        assertEquals(42.toByte(), chunk.data[0])
    }

    @Test
    fun `GeminiLiveEngine gracefully falls back to TTS engine when transcriptProvider throws an exception`() = runTest {
        val fallbackMarker = ByteArray(16) { 99 }
        val fakeFallback = object : NarrationEngine {
            override val id = EngineId.GEMINI_TTS
            override val capabilities = EngineCapabilities(true, true, true, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> =
                flowOf(PcmChunk(fallbackMarker, 24000, 1))
        }

        val failingEngine = GeminiLiveEngine(
            fallbackEngine = fakeFallback,
            transcriptProvider = { throw java.io.IOException("WebSocket connection lost") }
        )

        val chunk = failingEngine.synthesize(
            SynthesisRequest(text = "Any book sentence", voiceId = "Kore")
        ).first()
        assertEquals(16, chunk.data.size)
        assertEquals(99.toByte(), chunk.data[0])
    }

    @Test
    fun `ChapterTranslator returns bilingual paragraph mapping and preserves lines`() = runTest {
        val translator = ChapterTranslator()
        val paragraphs = mapOf("p1" to "Hello world", "p2" to "Good morning")
        val lines = listOf(
            NarrationLine(
                id = "l1",
                chapterId = "ch1",
                paraIndex = 0,
                startOffset = 0,
                endOffset = 11,
                speakerId = "narrator",
                style = "warm"
            )
        )

        val result = translator.translateChapter(paragraphs, lines, "es")
        assertEquals(2, result.bilingualParagraphs.size)
        assertTrue(result.bilingualParagraphs["p1"]!!.contains("Hello world"))
        assertEquals(1, result.translatedLines.size)
        assertEquals("narrator", result.translatedLines[0].speakerId)
    }

    @Test
    fun `ChapterTranslator uses TextModelClient for batch translation when API key is present`() = runTest {
        var capturedApiKey: String? = null
        var capturedModel: String? = null
        var capturedSchema: String? = null
        val fakeClient = object : TextModelClient {
            override suspend fun generateContent(
                apiKey: String,
                prompt: String,
                systemInstruction: String?,
                responseJsonSchema: String?,
                model: String
            ): String {
                capturedApiKey = apiKey
                capturedModel = model
                capturedSchema = responseJsonSchema
                return """["Hola mundo", "Buenos días"]"""
            }
        }

        val translator = ChapterTranslator(
            textModelClient = fakeClient,
            apiKeyProvider = { "test-api-key" },
            modelProvider = { "gemini-2.5-flash" }
        )

        val paragraphs = linkedMapOf("p1" to "Hello world", "p2" to "Good morning")
        val lines = listOf(
            NarrationLine(
                id = "l1",
                chapterId = "ch1",
                paraIndex = 0,
                startOffset = 0,
                endOffset = 11,
                speakerId = "narrator",
                style = null
            )
        )

        val result = translator.translateChapter(paragraphs, lines, "es")
        assertEquals("test-api-key", capturedApiKey)
        assertEquals("gemini-2.5-flash", capturedModel)
        assertTrue(capturedSchema != null)
        assertEquals("Hola mundo", result.bilingualParagraphs["p1"])
        assertEquals("Buenos días", result.bilingualParagraphs["p2"])
        assertEquals("neutral", result.translatedLines[0].style)
    }

    @Test
    fun `ChapterTranslator adaptive batching deduplicates inputs and shrinks budget for Gemma 4`() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        var callCount = 0
        val callSizes = mutableListOf<Int>()

        val echoTranslatorClient = object : TextModelClient {
            override suspend fun generateContent(
                apiKey: String,
                prompt: String,
                systemInstruction: String?,
                responseJsonSchema: String?,
                model: String
            ): String {
                callCount++
                val start = prompt.indexOf('[')
                val end = prompt.lastIndexOf(']')
                val inputArray = json.decodeFromString<List<String>>(prompt.substring(start, end + 1))
                callSizes.add(inputArray.size)
                return json.encodeToString(inputArray.map { "ES:$it" })
            }
        }

        val tracker = GeminiQuotaTracker()
        val flashTranslator = ChapterTranslator(
            textModelClient = echoTranslatorClient,
            apiKeyProvider = { "key" },
            modelProvider = { "gemini-3.5-flash-lite" },
            quotaTracker = tracker
        )

        // 30 paragraphs where 10 are duplicates -> only 20 unique strings, fits in 1 Flash-Lite batch!
        val paragraphs = linkedMapOf<String, String>()
        for (i in 0 until 30) {
            val text = if (i >= 20) "Unique paragraph ${i - 20}" else "Unique paragraph $i"
            paragraphs["p$i"] = text
        }
        val batchCallbacks = mutableListOf<Map<String, String>>()
        val res = flashTranslator.translateChapter(
            paragraphs = paragraphs,
            lines = emptyList(),
            targetLanguageCode = "es",
            onBatchTranslated = { batchCallbacks.add(it) }
        )

        assertEquals(1, callCount)
        assertEquals(listOf(20), callSizes)
        assertEquals(1, batchCallbacks.size)
        assertEquals("ES:Unique paragraph 0", res.bilingualParagraphs["p0"])
        assertEquals("ES:Unique paragraph 0", res.bilingualParagraphs["p20"])

        // Cool down all Flash models so Gemma 4 (3200 chars / 35 items) becomes active
        callCount = 0
        callSizes.clear()
        listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite", "gemini-2.5-flash").forEach {
            tracker.recordHttpError(it, 429, "Please retry in 60s")
        }
        val largeParas = linkedMapOf<String, String>()
        for (i in 0 until 10) {
            largeParas["k$i"] = "Paragraph $i " + "x".repeat(900) // ~912 chars each -> ~4 items per 3200-char Gemma batch
        }
        flashTranslator.translateChapter(largeParas, emptyList(), "es")
        assertTrue("Expected multiple smaller Gemma batches, got $callCount", callCount >= 3)
        assertTrue(callSizes.all { it <= 4 })
    }

    @Test
    fun `GeminiTtsEngine streams audio from InteractionsTtsClient and rejects missing API key`() = runTest {
        var capturedRequest: TtsRequest? = null
        val expectedBytes1 = byteArrayOf(1, 2, 3, 4)
        val expectedBytes2 = byteArrayOf(5, 6, 7, 8)
        val fakeClient = object : InteractionsTtsClient {
            override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray {
                return expectedBytes1 + expectedBytes2
            }

            override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> = flow {
                capturedRequest = request
                emit(PcmChunk(expectedBytes1, sampleRate = 24000, channels = 1))
                emit(PcmChunk(expectedBytes2, sampleRate = 24000, channels = 1))
            }
        }

        val engine = GeminiTtsEngine(
            ttsClient = fakeClient,
            apiKeyProvider = { "valid-key" },
            modelProvider = { "gemini-2.5-flash-preview-tts" }
        )

        val chunks = engine.synthesize(
            SynthesisRequest(text = "Chapter one begins.", voiceId = "Kore", style = "calm")
        ).toList()

        assertEquals(2, chunks.size)
        assertEquals(24000, chunks[0].sampleRate)
        assertTrue(expectedBytes1.contentEquals(chunks[0].data))
        assertTrue(expectedBytes2.contentEquals(chunks[1].data))
        assertEquals("Kore", capturedRequest?.voiceName)
        assertEquals("calm", capturedRequest?.style)
        assertEquals("gemini-2.5-flash-preview-tts", capturedRequest?.model)

        val missingKeyEngine = GeminiTtsEngine(
            ttsClient = fakeClient,
            apiKeyProvider = { "   " }
        )
        var threwMissingKey = false
        try {
            missingKeyEngine.synthesize(SynthesisRequest(text = "Hello", voiceId = "Puck")).toList()
        } catch (e: IllegalStateException) {
            threwMissingKey = e.message?.contains("Missing API key") == true
        }
        assertTrue(threwMissingKey)
    }

    @Test
    fun `GeminiLiveEngine collects audio and transcription from LiveSession and rotates across Live models`() = runTest {
        val rawAudio = byteArrayOf(10, 20, 30, 40)
        val base64Audio = Base64.getEncoder().encodeToString(rawAudio)
        val attemptedModels = mutableListOf<String>()
        val tracker = GeminiQuotaTracker()

        val fallbackMarker = byteArrayOf(77, 88)
        val fallbackEngine = object : NarrationEngine {
            override val id = EngineId.GEMINI_TTS
            override val capabilities = EngineCapabilities(true, true, true, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> =
                flowOf(PcmChunk(fallbackMarker, 24000, 1))
        }

        // First Live model returns 429; second Live model (native-audio-dialog) succeeds!
        val rotatingEngine = GeminiLiveEngine(
            fallbackEngine = fallbackEngine,
            liveSessionFactory = {
                object : LiveSession {
                    private val incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 8)
                    override val incomingMessages: Flow<LiveServerMessage> = incoming.asSharedFlow()
                    override var isConnected: Boolean = false
                        private set
                    private var modelName: String = ""

                    override suspend fun connect(apiKey: String, setup: SetupConfig) {
                        modelName = setup.model
                        attemptedModels.add(modelName)
                        isConnected = true
                    }

                    override suspend fun send(message: LiveClientMessage) {
                        if (modelName.contains("3.8-live")) {
                            incoming.emit(LiveServerMessage.ErrorMessage(LiveErrorData(code = 429, message = "Please retry in 30s")))
                        } else {
                            incoming.emit(
                                LiveServerMessage.ServerContentMessage(
                                    ServerContentData(
                                        modelTurn = ModelTurn(
                                            parts = listOf(
                                                Part(inlineData = Blob("audio/pcm;rate=24000", base64Audio))
                                            )
                                        ),
                                        outputTranscription = TranscriptionData("Call me Ishmael"),
                                        turnComplete = true
                                    )
                                )
                            )
                        }
                    }

                    override suspend fun disconnect() {
                        isConnected = false
                    }
                }
            },
            apiKeyProvider = { "live-key" },
            quotaTracker = tracker
        )

        val chunks = rotatingEngine.synthesize(
            SynthesisRequest(text = "Call me Ishmael", voiceId = "Charon")
        ).toList()

        assertEquals(1, chunks.size)
        assertTrue(rawAudio.contentEquals(chunks[0].data))
        assertEquals(2, attemptedModels.size)
        assertTrue(attemptedModels[0].contains("gemini-3.8-live"))
        assertTrue(attemptedModels[1].contains("gemini-2.5-flash-native-audio-dialog"))
        assertFalse(tracker.isModelAvailable("gemini-3.8-live"))

        // If all Live candidates error, fall back to fallbackEngine
        val errorEngine = GeminiLiveEngine(
            fallbackEngine = fallbackEngine,
            liveSessionFactory = {
                object : LiveSession {
                    private val incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 8)
                    override val incomingMessages: Flow<LiveServerMessage> = incoming.asSharedFlow()
                    override val isConnected: Boolean = true
                    override suspend fun connect(apiKey: String, setup: SetupConfig) {}
                    override suspend fun send(message: LiveClientMessage) {
                        incoming.emit(LiveServerMessage.ErrorMessage(LiveErrorData(code = 500, message = "Live error")))
                    }
                    override suspend fun disconnect() {}
                }
            },
            apiKeyProvider = { "live-key" }
        )

        val fallbackChunks = errorEngine.synthesize(
            SynthesisRequest(text = "Call me Ishmael", voiceId = "Charon")
        ).toList()
        assertEquals(1, fallbackChunks.size)
        assertTrue(fallbackMarker.contentEquals(fallbackChunks[0].data))
    }

    @Test
    fun `SystemTtsEngine resamples WAV PCM to 24000 Hz mono and NarrationCoordinator uses lineTexts`() = runTest {
        val pcmSamples = ByteArray(32) { i -> (i % 64).toByte() }
        val wavOut = ByteArrayOutputStream()
        fun writeLeInt(v: Int) {
            wavOut.write(v and 0xFF)
            wavOut.write((v shr 8) and 0xFF)
            wavOut.write((v shr 16) and 0xFF)
            wavOut.write((v shr 24) and 0xFF)
        }
        fun writeLeShort(v: Int) {
            wavOut.write(v and 0xFF)
            wavOut.write((v shr 8) and 0xFF)
        }
        wavOut.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLeInt(36 + pcmSamples.size)
        wavOut.write("WAVE".toByteArray(Charsets.US_ASCII))
        wavOut.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeLeInt(16)
        writeLeShort(1)
        writeLeShort(1)
        writeLeInt(16000)
        writeLeInt(32000)
        writeLeShort(2)
        writeLeShort(16)
        wavOut.write("data".toByteArray(Charsets.US_ASCII))
        writeLeInt(pcmSamples.size)
        wavOut.write(pcmSamples)

        val resampled = SystemTtsEngine.parseAndResampleWav(wavOut.toByteArray(), 24000)
        assertEquals(48, resampled.size)

        var capturedText: String? = null
        val capturingEngine = object : NarrationEngine {
            override val id = EngineId.GEMINI_TTS
            override val capabilities = EngineCapabilities(true, true, true, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> {
                capturedText = request.text
                return flowOf(PcmChunk(ByteArray(4), 24000, 1))
            }
        }
        val coordinator = NarrationCoordinator(capturingEngine, prefetchAheadCount = 1)
        val line = NarrationLine(
            id = "line-1",
            chapterId = "ch-1",
            paraIndex = 0,
            startOffset = 0,
            endOffset = 12,
            speakerId = "narrator"
        )
        coordinator.prepareLines(
            lines = listOf(line),
            voiceMap = mapOf("narrator" to "Charon"),
            lineTexts = mapOf("line-1" to "Actual prose")
        )
        assertEquals("Actual prose", capturedText)
    }

    @Test
    fun `SystemTtsEngine normalizes BCP-47 tags and emits PCM when context is null`() = runTest {
        assertEquals(listOf("zh-CN", "zh"), SystemTtsEngine.candidateLanguageTags("zh-Hans"))
        assertEquals(listOf("zh-TW", "zh"), SystemTtsEngine.candidateLanguageTags("zh-Hant"))
        assertEquals(listOf("fil-PH", "tl-PH", "tl", "fil"), SystemTtsEngine.candidateLanguageTags("fil"))
        assertEquals(listOf("nb-NO", "no-NO", "no", "nb"), SystemTtsEngine.candidateLanguageTags("nb"))

        val chunks = SystemTtsEngine().synthesize(
            SynthesisRequest(text = "Hola mundo", voiceId = "default", languageCode = "es")
        ).toList()
        assertEquals(1, chunks.size)
        assertEquals(16000, chunks[0].data.size)
        assertEquals(16000, chunks[0].sampleRate)
    }

    @Test
    fun `GeminiTtsEngine sends clean book text without spoken prompt instructions regardless of languageCode or neutral style`() = runTest {
        val capturedRequests = mutableListOf<TtsRequest>()
        val fakeClient = object : InteractionsTtsClient {
            override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray {
                return byteArrayOf(1, 2, 3, 4)
            }

            override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> {
                capturedRequests.add(request)
                return flowOf(PcmChunk(byteArrayOf(1, 2, 3, 4), 24000, 1))
            }
        }
        val engine = GeminiTtsEngine(
            ttsClient = fakeClient,
            apiKeyProvider = { "test-key" }
        )

        engine.synthesize(SynthesisRequest(text = "Plain text", voiceId = "Charon", style = null, languageCode = null)).toList()
        engine.synthesize(SynthesisRequest(text = "Call me Ishmael.", voiceId = "Charon", style = "neutral", languageCode = "en")).toList()
        engine.synthesize(SynthesisRequest(text = "Bonjour le monde", voiceId = "Charon", style = "NEUTRAL", languageCode = "fr")).toList()
        engine.synthesize(SynthesisRequest(text = "こんにちは", voiceId = "Charon", style = "warm", languageCode = "ja")).toList()

        // Blank text emits nothing; punctuation-only divider emits brief silence without calling TTS client
        val blankChunks = engine.synthesize(SynthesisRequest(text = "   ", voiceId = "Charon", languageCode = "en")).toList()
        val dividerChunks = engine.synthesize(SynthesisRequest(text = "* * *", voiceId = "Charon", languageCode = "en")).toList()
        assertTrue(blankChunks.isEmpty())
        assertEquals(1, dividerChunks.size)
        assertTrue(dividerChunks[0].data.all { it == 0.toByte() })

        assertEquals(4, capturedRequests.size)
        assertEquals("Plain text", capturedRequests[0].text)
        assertEquals(null, capturedRequests[0].style)
        assertEquals(null, capturedRequests[0].languageCode)

        assertEquals("Call me Ishmael.", capturedRequests[1].text)
        assertEquals(null, capturedRequests[1].style)
        assertEquals("en", capturedRequests[1].languageCode)

        assertEquals("Bonjour le monde", capturedRequests[2].text)
        assertEquals(null, capturedRequests[2].style)
        assertEquals("fr", capturedRequests[2].languageCode)

        assertEquals("こんにちは", capturedRequests[3].text)
        assertEquals("warm", capturedRequests[3].style)
        assertEquals("ja", capturedRequests[3].languageCode)

        capturedRequests.forEach { req ->
            assertFalse("Spoken text must never inject 'Read aloud'", req.text.contains("Read aloud", ignoreCase = true))
            assertFalse("Spoken text must never inject 'without translating'", req.text.contains("without translating", ignoreCase = true))
            assertFalse("Spoken text must never inject 'commentary'", req.text.contains("commentary", ignoreCase = true))
        }
    }

    @Test
    fun `GeminiLiveEngine fallback to GeminiTtsEngine passes clean text without spoken instructions`() = runTest {
        val capturedTtsRequests = mutableListOf<TtsRequest>()
        val fakeTtsClient = object : InteractionsTtsClient {
            override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray = byteArrayOf(9, 9)
            override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> {
                capturedTtsRequests.add(request)
                return flowOf(PcmChunk(byteArrayOf(9, 9), 24000, 1))
            }
        }
        val realFallback = GeminiTtsEngine(
            ttsClient = fakeTtsClient,
            apiKeyProvider = { "tts-key" }
        )
        val liveEngine = GeminiLiveEngine(
            fallbackEngine = realFallback,
            liveSessionFactory = {
                object : LiveSession {
                    private val incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 4)
                    override val incomingMessages: Flow<LiveServerMessage> = incoming.asSharedFlow()
                    override val isConnected: Boolean = true
                    override suspend fun connect(apiKey: String, setup: SetupConfig) {}
                    override suspend fun send(message: LiveClientMessage) {
                        incoming.emit(LiveServerMessage.ErrorMessage(LiveErrorData(code = 503, message = "Unavailable")))
                    }
                    override suspend fun disconnect() {}
                }
            },
            apiKeyProvider = { "live-key" }
        )

        val chunks = liveEngine.synthesize(
            SynthesisRequest(
                text = "It was a bright cold day in April.",
                voiceId = "Charon",
                style = "neutral",
                languageCode = "en"
            )
        ).toList()

        assertEquals(1, chunks.size)
        assertEquals(1, capturedTtsRequests.size)
        assertEquals("It was a bright cold day in April.", capturedTtsRequests[0].text)
        assertEquals(null, capturedTtsRequests[0].style)
        assertEquals("en", capturedTtsRequests[0].languageCode)
    }

    @Test
    fun `GeminiLiveEngine includes verbatim target-language instruction in SetupConfig when languageCode is set`() = runTest {
        val defaultRequest = SynthesisRequest(text = "Hello", voiceId = "Charon", style = "neutral", languageCode = null)
        val defaultInstruction = GeminiLiveEngine.buildSystemInstruction(defaultRequest)
        assertFalse(defaultInstruction.contains("without translating"))
        assertFalse(defaultInstruction.contains("Tone: neutral"))

        val jaRequest = SynthesisRequest(text = "こんにちは", voiceId = "Charon", languageCode = "ja")
        val jaInstruction = GeminiLiveEngine.buildSystemInstruction(jaRequest)
        assertTrue(jaInstruction.startsWith(defaultInstruction))
        assertTrue(jaInstruction.contains("Japanese (ja)"))
        assertTrue(jaInstruction.contains("without translating"))

        val capturedSetups = mutableListOf<SetupConfig>()
        val capturedClientTurns = mutableListOf<String>()
        val rawAudio = byteArrayOf(5, 6, 7, 8)
        val base64Audio = Base64.getEncoder().encodeToString(rawAudio)
        val engine = GeminiLiveEngine(
            liveSessionFactory = {
                object : LiveSession {
                    private val incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 8)
                    override val incomingMessages: Flow<LiveServerMessage> = incoming.asSharedFlow()
                    override var isConnected: Boolean = false
                        private set

                    override suspend fun connect(apiKey: String, setup: SetupConfig) {
                        capturedSetups.add(setup)
                        isConnected = true
                    }

                    override suspend fun send(message: LiveClientMessage) {
                        val text = (message as LiveClientMessage.ClientContentMessage)
                            .clientContent.turns?.firstOrNull()?.parts?.firstOrNull()?.text.orEmpty()
                        capturedClientTurns.add(text)
                        incoming.emit(
                            LiveServerMessage.ServerContentMessage(
                                ServerContentData(
                                    modelTurn = ModelTurn(
                                        parts = listOf(Part(inlineData = Blob("audio/pcm;rate=24000", base64Audio)))
                                    ),
                                    outputTranscription = TranscriptionData("こんにちは"),
                                    turnComplete = true
                                )
                            )
                        )
                    }

                    override suspend fun disconnect() {
                        isConnected = false
                    }
                }
            },
            apiKeyProvider = { "live-key" }
        )

        val chunks = engine.synthesize(jaRequest).toList()
        assertEquals(1, chunks.size)
        assertTrue(rawAudio.contentEquals(chunks[0].data))
        assertEquals(1, capturedSetups.size)
        assertEquals(jaInstruction, capturedSetups[0].systemInstruction?.parts?.firstOrNull()?.text)
        assertEquals(listOf("こんにちは"), capturedClientTurns)

        // Blank and structural divider turns must not be sent to Live model, whereas expressive punctuation dialogue IS sent
        val liveSession = engine.openStreamingSession()
        try {
            assertTrue(liveSession.synthesizeTurnStreaming(SynthesisRequest(text = "   ", voiceId = "Charon")).toList().isEmpty())
            val divider = liveSession.synthesizeTurnStreaming(SynthesisRequest(text = "---", voiceId = "Charon")).toList()
            assertEquals(1, divider.size)
            assertTrue(divider[0].data.all { it == 0.toByte() })
            assertEquals(1, capturedClientTurns.size)

            val expressive = liveSession.synthesizeTurnStreaming(SynthesisRequest(text = "\"......?\"", voiceId = "Charon")).toList()
            assertEquals(1, expressive.size)
            assertTrue(rawAudio.contentEquals(expressive[0].data))
            assertEquals(2, capturedClientTurns.size)
            assertEquals("Hmm...?", capturedClientTurns[1])
            assertTrue(
                capturedSetups.last().systemInstruction?.parts?.firstOrNull()?.text.orEmpty()
                    .contains("puzzled", ignoreCase = true)
            )
        } finally {
            liveSession.close()
        }
    }

    @Test
    fun `GeminiTtsEngine and SystemTtsEngine keep structural dividers silent while vocalizing expressive punctuation`() = runTest {
        val capturedTtsRequests = mutableListOf<TtsRequest>()
        val fakeClient = object : InteractionsTtsClient {
            override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray = byteArrayOf(7, 8)
            override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> {
                capturedTtsRequests += request
                return flowOf(PcmChunk(byteArrayOf(7, 8), 24000, 1))
            }
        }
        val ttsEngine = GeminiTtsEngine(
            ttsClient = fakeClient,
            apiKeyProvider = { "test-key" }
        )

        listOf("---", "* * *", "===", "___").forEach { divider ->
            val chunks = ttsEngine.synthesize(SynthesisRequest(text = divider, voiceId = "Charon")).toList()
            assertEquals(1, chunks.size)
            assertTrue("Divider '$divider' should stay silent", chunks[0].data.all { it == 0.toByte() })
        }
        assertTrue(capturedTtsRequests.isEmpty())

        listOf("\"......?\"", "\"?!\"", "\"—\"").forEach { cue ->
            val chunks = ttsEngine.synthesize(SynthesisRequest(text = cue, voiceId = "Aoede")).toList()
            assertEquals(1, chunks.size)
            assertEquals(7.toByte(), chunks[0].data[0])
        }
        assertEquals(listOf("Hmm...?", "Huh?!", "Ah—"), capturedTtsRequests.map { it.text })
        assertTrue(capturedTtsRequests.all { !it.style.isNullOrBlank() })

        val sysDivider = SystemTtsEngine().synthesize(SynthesisRequest(text = "===", voiceId = "default")).toList()
        assertEquals(1, sysDivider.size)
        assertTrue(sysDivider[0].data.all { it == 0.toByte() })
    }
}
