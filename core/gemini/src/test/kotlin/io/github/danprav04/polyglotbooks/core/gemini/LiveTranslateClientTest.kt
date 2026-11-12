package io.github.danprav04.polyglotbooks.core.gemini

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
