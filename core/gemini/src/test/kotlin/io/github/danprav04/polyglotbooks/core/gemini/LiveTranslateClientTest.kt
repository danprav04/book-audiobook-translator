package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class LiveTranslateClientTest {

    @Test
    fun testClientSetupAndResumption() = runTest {
        val stubSession = StubLiveSession()
        val factory = object : LiveSessionFactory {
            override fun create(): LiveSession = stubSession
        }

        val client = DefaultLiveTranslateClient(factory)
        client.start("test-api-key", "es", echoTargetLanguage = true)

        assertTrue(stubSession.isConnected)
        assertNotNull(stubSession.currentSetup)
        assertEquals("models/gemini-3.5-live-translate-preview", stubSession.currentSetup?.model)
        assertEquals("es", stubSession.currentSetup?.generationConfig?.translationConfig?.targetLanguageCode)
        assertTrue(stubSession.currentSetup?.generationConfig?.translationConfig?.echoTargetLanguage == true)

        // Test session resumption update simulation
        stubSession.emitServerMessage(
            LiveServerMessage.SessionResumptionUpdateMessage(
                SessionResumptionUpdateData(resumable = true, newHandle = "handle-123")
            )
        )

        client.stop()
    }

    @Test
    fun testGoAwayHandling() = runTest {
        val stubSession = StubLiveSession()
        val factory = object : LiveSessionFactory {
            override fun create(): LiveSession = stubSession
        }

        val client = DefaultLiveTranslateClient(factory)
        client.start("test-api-key", "fr")

        // Emit GoAway
        stubSession.emitServerMessage(
            LiveServerMessage.GoAwayMessage(GoAwayData(timeLeft = "5s"))
        )

        assertTrue(stubSession.isConnected)
        client.stop()
    }

    @Test
    fun testModelPrefixAndMimeTypeSampleRateParsing() = runTest {
        val stubSession = StubLiveSession()
        val client = DefaultLiveTranslateClient(object : LiveSessionFactory {
            override fun create(): LiveSession = stubSession
        })

        client.start("test-key", "ru", model = "gemini-3.5-live-translate-preview")
        assertEquals("models/gemini-3.5-live-translate-preview", stubSession.currentSetup?.model)

        // Emit server audio with rate=16000 and transcripts
        val expectedPcm = byteArrayOf(10, 20, 30, 40)
        val encoded = Base64.getEncoder().encodeToString(expectedPcm)
        stubSession.emitServerMessage(
            LiveServerMessage.ServerContentMessage(
                ServerContentData(
                    modelTurn = ModelTurn(
                        parts = listOf(
                            Part(inlineData = Blob(mimeType = "audio/pcm;rate=16000", data = encoded))
                        )
                    ),
                    inputTranscription = TranscriptionData(text = "Hello", languageCode = "en"),
                    outputTranscription = TranscriptionData(text = "Привет", languageCode = "ru"),
                    turnComplete = true
                )
            )
        )

        val output = client.translationStream.first()
        assertNotNull(output.audioChunk)
        assertEquals(16000, output.audioChunk?.sampleRate)
        assertArrayEquals(expectedPcm, output.audioChunk?.data)
        assertEquals("Hello", output.sourceTranscript)
        assertEquals("Привет", output.targetTranscript)
        assertTrue(output.isTurnComplete)

        client.stop()
    }

    @Test
    fun testFallbackToSystemInstructionWhenServerRejectsTranslationConfig() = runTest {
        val createdSessions = mutableListOf<StubLiveSession>()
        val client = DefaultLiveTranslateClient(object : LiveSessionFactory {
            override fun create(): LiveSession {
                val session = StubLiveSession()
                createdSessions.add(session)
                return session
            }
        })

        client.start("test-key", "ru", echoTargetLanguage = false, model = "gemini-3.5-live-translate-preview")
        assertEquals(1, createdSessions.size)
        assertNotNull(createdSessions[0].currentSetup?.generationConfig?.translationConfig)
        assertNull(createdSessions[0].currentSetup?.systemInstruction)

        // Server rejects translationConfig during setup
        createdSessions[0].emitServerMessage(
            LiveServerMessage.ErrorMessage(
                LiveErrorData(code = 400, message = "Unknown name \"translationConfig\"", status = "INVALID_ARGUMENT")
            )
        )

        // Client should automatically reconnect with systemInstruction fallback
        assertEquals(2, createdSessions.size)
        val fallbackSetup = createdSessions[1].currentSetup
        assertNotNull(fallbackSetup)
        assertNull(fallbackSetup?.generationConfig?.translationConfig)
        assertNotNull(fallbackSetup?.systemInstruction)
        val instructionText = fallbackSetup?.systemInstruction?.parts?.firstOrNull()?.text.orEmpty()
        assertTrue(instructionText.contains("Russian (ru)"))

        // Subsequent server error after fallback surfaces in TranslationOutput.errorMessage
        createdSessions[1].emitServerMessage(
            LiveServerMessage.ErrorMessage(
                LiveErrorData(code = 429, message = "Quota exceeded", status = "RESOURCE_EXHAUSTED")
            )
        )
        val errorOutput = client.translationStream.first()
        assertTrue(errorOutput.errorMessage?.contains("Quota exceeded") == true)

        client.stop()
    }

    @Test
    fun testNonTranslateLiveModelUsesSystemInstructionDirectly() = runTest {
        val stubSession = StubLiveSession()
        val client = DefaultLiveTranslateClient(object : LiveSessionFactory {
            override fun create(): LiveSession = stubSession
        })

        client.start("test-key", "de", echoTargetLanguage = true, model = "gemini-3.8-live")
        assertEquals("models/gemini-3.8-live", stubSession.currentSetup?.model)
        assertNull(stubSession.currentSetup?.generationConfig?.translationConfig)
        assertNotNull(stubSession.currentSetup?.systemInstruction)
        assertTrue(
            stubSession.currentSetup?.systemInstruction?.parts?.firstOrNull()?.text?.contains("German (de)") == true
        )
        client.stop()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun testOutgoingJsonPlacesTranscriptionAtTopLevelSetupNotInGenerationConfig() = runTest {
        val capturedSetups = mutableListOf<SetupConfig>()
        val client = DefaultLiveTranslateClient(object : LiveSessionFactory {
            override fun create(): LiveSession = object : LiveSession {
                private val _incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 8)
                override val incomingMessages: Flow<LiveServerMessage> = _incoming.asSharedFlow()
                override var isConnected: Boolean = false
                    private set

                override suspend fun connect(apiKey: String, setup: SetupConfig) {
                    capturedSetups.add(setup)
                    isConnected = true
                }

                override suspend fun send(message: LiveClientMessage) {}
                override suspend fun disconnect() {
                    isConnected = false
                }
            }
        })

        // Primary translate setup
        client.start("key", "pl", echoTargetLanguage = true, model = "gemini-3.5-live-translate-preview")
        client.stop()
        // Live systemInstruction setup
        client.start("key", "pl", echoTargetLanguage = false, model = "gemini-3.8-live")
        client.stop()

        assertEquals(2, capturedSetups.size)
        val wireJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        capturedSetups.forEach { setup ->
            val encoded = wireJson.encodeToString<LiveClientMessage>(LiveClientMessage.SetupMessage(setup))
            val setupObj = wireJson.parseToJsonElement(encoded).jsonObject["setup"]!!.jsonObject
            val genObj = setupObj["generationConfig"]!!.jsonObject
            assertTrue("inputAudioTranscription must be at top-level setup", "inputAudioTranscription" in setupObj)
            assertTrue("outputAudioTranscription must be at top-level setup", "outputAudioTranscription" in setupObj)
            assertFalse(
                "inputAudioTranscription must not be in generationConfig",
                "inputAudioTranscription" in genObj
            )
            assertFalse(
                "outputAudioTranscription must not be in generationConfig",
                "outputAudioTranscription" in genObj
            )
        }
    }

    @Test
    fun testFallbackOnIOException1007ToSystemInstructionAndMinimalSetupWithoutLoops() = runTest {
        val sessions = mutableListOf<ScriptedLiveSession>()
        val client = DefaultLiveTranslateClient(object : LiveSessionFactory {
            override fun create(): LiveSession {
                val s = ScriptedLiveSession()
                sessions.add(s)
                return s
            }
        })

        client.start("test-key", "ru", model = "gemini-3.5-live-translate-preview")
        assertEquals(1, sessions.size)
        assertNotNull(sessions[0].currentSetup?.generationConfig?.translationConfig)
        assertNull(sessions[0].currentSetup?.systemInstruction)

        // 1. Primary translate setup fails pre-content with IOException code=1007 -> falls back to systemInstruction
        sessions[0].failWith(
            IOException("WebSocket closed unexpectedly: code=1007, reason=Unknown name \"translationConfig\"")
        )
        assertEquals(2, sessions.size)
        val setup2 = sessions[1].currentSetup!!
        assertEquals("models/gemini-3.5-live-translate-preview", setup2.model)
        assertNull(setup2.generationConfig?.translationConfig)
        assertNotNull(setup2.systemInstruction)
        assertNotNull(setup2.inputAudioTranscription)
        assertNotNull(setup2.outputAudioTranscription)

        // 2. Full live setup also fails pre-content with IOException code=1007 -> falls back ONCE to minimal live setup
        sessions[1].failWith(
            IOException("WebSocket closed unexpectedly: code=1007, reason=Unknown name \"inputAudioTranscription\"")
        )
        assertEquals(3, sessions.size)
        val setup3 = sessions[2].currentSetup!!
        assertEquals("models/gemini-3.5-live-translate-preview", setup3.model)
        assertEquals(listOf("AUDIO"), setup3.generationConfig?.responseModalities)
        assertNotNull(setup3.systemInstruction)
        assertNull(setup3.generationConfig?.translationConfig)
        assertNull(setup3.inputAudioTranscription)
        assertNull(setup3.outputAudioTranscription)
        assertNull(setup3.sessionResumption)
        assertNull(setup3.contextWindowCompression)

        // 3. Minimal live setup also fails with 1007 -> must NOT loop; emits terminal error
        sessions[2].failWith(IOException("WebSocket closed unexpectedly: code=1007, reason=Bad minimal setup"))
        assertEquals(3, sessions.size)
        val err = client.translationStream.first()
        assertTrue(err.errorMessage?.contains("code=1007") == true)

        client.stop()
    }

    @Test
    fun testFallbackOnIOException1008UsesModelCatalogServiceWithoutTranslationConfigOrLoops() = runTest {
        val sessions = mutableListOf<ScriptedLiveSession>()
        val catalogCalls = AtomicInteger(0)
        val fakeCatalog = object : ModelCatalogService {
            override suspend fun listModels(apiKey: String): ModelListResponse {
                catalogCalls.incrementAndGet()
                return ModelListResponse(
                    models = listOf(
                        ModelItem(
                            name = "models/gemini-3.5-live-translate-preview",
                            supportedGenerationMethods = listOf("bidiGenerateContent")
                        ),
                        ModelItem(
                            name = "models/gemini-2.5-flash-native-audio-preview",
                            supportedGenerationMethods = listOf("bidiGenerateContent")
                        ),
                        ModelItem(
                            name = "models/gemini-3.5-flash-lite",
                            supportedGenerationMethods = listOf("generateContent")
                        )
                    )
                )
            }
        }

        val client = DefaultLiveTranslateClient(
            sessionFactory = object : LiveSessionFactory {
                override fun create(): LiveSession {
                    val s = ScriptedLiveSession()
                    sessions.add(s)
                    return s
                }
            },
            modelCatalogService = fakeCatalog
        )

        client.start("test-key", "ru", model = "gemini-3.5-live-translate-preview")
        assertEquals(1, sessions.size)

        // Server rejects model with 1008 (not found / not supported for bidiGenerateContent)
        sessions[0].failWith(
            IOException(
                "WebSocket closed unexpectedly: code=1008, " +
                    "reason=models/gemini-3.5-live-translate-preview is not found for API version v1beta"
            )
        )

        assertEquals(1, catalogCalls.get())
        assertEquals(2, sessions.size)
        val fallbackSetup = sessions[1].currentSetup!!
        assertEquals("models/gemini-2.5-flash-native-audio-preview", fallbackSetup.model)
        assertNull(fallbackSetup.generationConfig?.translationConfig)
        assertNotNull(fallbackSetup.systemInstruction)

        // If the discovered alternate model also fails with 1008, do not retry any tried model or loop
        sessions[1].failWith(IOException("WebSocket closed unexpectedly: code=1008, reason=Model not supported"))
        assertEquals(2, sessions.size)
        val err = client.translationStream.first()
        assertTrue(err.errorMessage?.contains("code=1008") == true)

        client.stop()
    }

    @Test
    fun testFallbackOn1008WithoutCatalogUsesDefaultLiveNarrationModelOnce() = runTest {
        val sessions = mutableListOf<ScriptedLiveSession>()
        val client = DefaultLiveTranslateClient(object : LiveSessionFactory {
            override fun create(): LiveSession {
                val s = ScriptedLiveSession()
                sessions.add(s)
                return s
            }
        })

        client.start("test-key", "es", model = "gemini-3.5-live-translate-preview")
        sessions[0].failWith(IOException("WebSocket closed unexpectedly: code=1008, reason=Not found"))

        assertEquals(2, sessions.size)
        val expectedFallbackModel = "models/${DefaultModels.get(ModelRole.LIVE_NARRATION)}"
        assertEquals(expectedFallbackModel, sessions[1].currentSetup?.model)
        assertNull(sessions[1].currentSetup?.generationConfig?.translationConfig)
        assertNotNull(sessions[1].currentSetup?.systemInstruction)

        // Same model failing again must not retry itself
        sessions[1].failWith(IOException("WebSocket closed unexpectedly: code=1008, reason=Not found"))
        assertEquals(2, sessions.size)
        val err = client.translationStream.first()
        assertTrue(err.errorMessage?.contains("code=1008") == true)

        client.stop()
    }

    @Test
    fun testStopDuringAsyncModelCatalogLookupAbortsStaleFallbackAndPreservesNewSession() = runTest {
        val catalogGate = CompletableDeferred<Unit>()
        val sessions = mutableListOf<ScriptedLiveSession>()
        val fakeCatalog = object : ModelCatalogService {
            override suspend fun listModels(apiKey: String): ModelListResponse {
                catalogGate.await()
                return ModelListResponse(
                    models = listOf(
                        ModelItem(
                            name = "models/gemini-2.5-flash-native-audio-preview",
                            supportedGenerationMethods = listOf("bidiGenerateContent")
                        )
                    )
                )
            }
        }

        val client = DefaultLiveTranslateClient(
            sessionFactory = object : LiveSessionFactory {
                override fun create(): LiveSession {
                    val s = ScriptedLiveSession()
                    sessions.add(s)
                    return s
                }
            },
            modelCatalogService = fakeCatalog
        )

        // Start session 1 and trigger 1008 fallback suspended on catalogGate
        client.start("key", "ru", model = "gemini-3.5-live-translate-preview")
        val fallbackJob = launch {
            sessions[0].failWith(IOException("WebSocket closed unexpectedly: code=1008, reason=not found"))
        }

        // Stop and immediately start session 2 with non-translate model while session 1's catalog lookup is paused
        client.stop()
        client.start("key", "de", model = "gemini-3.8-live")
        assertEquals(2, sessions.size)
        assertTrue(sessions[1].isConnected)

        // Unblock stale catalog lookup -> stale fallback must abort without touching session 2
        catalogGate.complete(Unit)
        fallbackJob.join()

        assertEquals(2, sessions.size)
        assertTrue(sessions[1].isConnected)
        assertEquals("models/gemini-3.8-live", sessions[1].currentSetup?.model)
        client.stop()
    }

    @Test
    fun testEmptyFinishedTranscriptionDoesNotEmitSpuriousBlankTranscript() = runTest {
        val stubSession = StubLiveSession()
        val client = DefaultLiveTranslateClient(object : LiveSessionFactory {
            override fun create(): LiveSession = stubSession
        })

        client.start("test-key", "ru")

        // Finished-only outputTranscription without audio or turnComplete must produce null (nothing emitted)
        val finishedOnlyMsg = LiveServerMessage.ServerContentMessage(
            ServerContentData(
                inputTranscription = TranscriptionData(text = "", finished = true),
                outputTranscription = TranscriptionData(text = "", finished = true)
            )
        )
        assertNull(client.parseServerMessage(finishedOnlyMsg))

        // Finished outputTranscription alongside modelTurn audio emits audio with null targetTranscript
        val expectedPcm = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(expectedPcm)
        stubSession.emitServerMessage(finishedOnlyMsg)
        stubSession.emitServerMessage(
            LiveServerMessage.ServerContentMessage(
                ServerContentData(
                    modelTurn = ModelTurn(
                        parts = listOf(Part(inlineData = Blob(mimeType = "audio/pcm;rate=24000", data = encoded)))
                    ),
                    inputTranscription = TranscriptionData(text = "", finished = true),
                    interimInputTranscription = TranscriptionData(text = "Interim text"),
                    outputTranscription = TranscriptionData(text = "", finished = true)
                )
            )
        )

        val emitted = client.translationStream.first()
        assertNotNull(emitted.audioChunk)
        assertArrayEquals(expectedPcm, emitted.audioChunk?.data)
        assertEquals("Interim text", emitted.sourceTranscript)
        assertNull(emitted.targetTranscript)

        client.stop()
    }

    private class ScriptedLiveSession : LiveSession {
        private val _incoming = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 16)
        private val _failures = MutableSharedFlow<Throwable>(replay = 1, extraBufferCapacity = 4)
        override val incomingMessages: Flow<LiveServerMessage> = flow {
            coroutineScope {
                val failJob = launch { throw _failures.first() }
                try {
                    _incoming.collect { emit(it) }
                } finally {
                    failJob.cancel()
                }
            }
        }

        override var isConnected: Boolean = false
            private set

        var currentSetup: SetupConfig? = null
            private set

        override suspend fun connect(apiKey: String, setup: SetupConfig) {
            currentSetup = setup
            isConnected = true
        }

        override suspend fun send(message: LiveClientMessage) {}

        override suspend fun disconnect() {
            isConnected = false
        }

        suspend fun failWith(t: Throwable) {
            isConnected = false
            _failures.emit(t)
        }
    }
}
