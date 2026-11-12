package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

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
}
