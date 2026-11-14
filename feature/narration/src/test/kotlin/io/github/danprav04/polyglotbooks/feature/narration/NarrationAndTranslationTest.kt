package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.gemini.Blob
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
    fun `GeminiLiveEngine collects audio and transcription from LiveSession and falls back on error`() = runTest {
        val rawAudio = byteArrayOf(10, 20, 30, 40)
        val base64Audio = Base64.getEncoder().encodeToString(rawAudio)
        val incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 16)
        var disconnected = false

        val fakeSession = object : LiveSession {
            override val incomingMessages: Flow<LiveServerMessage> = incoming.asSharedFlow()
            override var isConnected: Boolean = false
                private set

            override suspend fun connect(apiKey: String, setup: SetupConfig) {
                isConnected = true
            }

            override suspend fun send(message: LiveClientMessage) {
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

            override suspend fun disconnect() {
                isConnected = false
                disconnected = true
            }
        }

        val fallbackMarker = byteArrayOf(77, 88)
        val fallbackEngine = object : NarrationEngine {
            override val id = EngineId.GEMINI_TTS
            override val capabilities = EngineCapabilities(true, true, true, 24000)
            override suspend fun voices(): List<VoiceInfo> = emptyList()
            override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> =
                flowOf(PcmChunk(fallbackMarker, 24000, 1))
        }

        val liveEngine = GeminiLiveEngine(
            fallbackEngine = fallbackEngine,
            liveSessionFactory = { fakeSession },
            apiKeyProvider = { "live-key" }
        )

        val chunks = liveEngine.synthesize(
            SynthesisRequest(text = "Call me Ishmael", voiceId = "Charon")
        ).toList()

        assertEquals(1, chunks.size)
        assertTrue(rawAudio.contentEquals(chunks[0].data))
        assertTrue(disconnected)
        assertFalse(fakeSession.isConnected)

        val errorIncoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 16)
        val errorSession = object : LiveSession {
            override val incomingMessages: Flow<LiveServerMessage> = errorIncoming.asSharedFlow()
            override val isConnected: Boolean = true
            override suspend fun connect(apiKey: String, setup: SetupConfig) {}
            override suspend fun send(message: LiveClientMessage) {
                errorIncoming.emit(
                    LiveServerMessage.ErrorMessage(LiveErrorData(code = 500, message = "Live error"))
                )
            }
            override suspend fun disconnect() {}
        }

        val errorLiveEngine = GeminiLiveEngine(
            fallbackEngine = fallbackEngine,
            liveSessionFactory = { errorSession },
            apiKeyProvider = { "live-key" }
        )

        val fallbackChunks = errorLiveEngine.synthesize(
            SynthesisRequest(text = "Call me Ishmael", voiceId = "Charon")
        ).toList()
        assertEquals(1, fallbackChunks.size)
        assertTrue(fallbackMarker.contentEquals(fallbackChunks[0].data))
    }

    @Test
    fun `SystemTtsEngine resamples WAV PCM to 24000 Hz mono and NarrationCoordinator uses lineTexts`() = runTest {
        // Build a tiny 16kHz mono 16-bit WAV (16 samples -> resampled to 24 samples at 24kHz)
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
        writeLeShort(1) // PCM
        writeLeShort(1) // mono
        writeLeInt(16000) // sampleRate
        writeLeInt(32000) // byteRate
        writeLeShort(2) // blockAlign
        writeLeShort(16) // bitsPerSample
        wavOut.write("data".toByteArray(Charsets.US_ASCII))
        writeLeInt(pcmSamples.size)
        wavOut.write(pcmSamples)

        val resampled = SystemTtsEngine.parseAndResampleWav(wavOut.toByteArray(), 24000)
        assertEquals(48, resampled.size) // 24 samples * 2 bytes

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
}
