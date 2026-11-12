package io.github.danprav04.polyglotbooks.core.gemini

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class OkHttpLiveSessionTest {

    @Test
    fun testIncomingMessagesDeliveredSuccessfully() = runTest {
        val mockClient = mockk<OkHttpClient>()
        val mockWebSocket = mockk<WebSocket>(relaxed = true)
        val listenerSlot = slot<WebSocketListener>()

        every { mockClient.newWebSocket(any<Request>(), capture(listenerSlot)) } returns mockWebSocket

        val session = OkHttpLiveSession(mockClient)
        val setup = SetupConfig(model = "models/gemini-3.8-live")
        session.connect("fake-key", setup)

        assertTrue(listenerSlot.isCaptured)
        val listener = listenerSlot.captured

        // Simulate WebSocket open
        val mockResponse = mockk<okhttp3.Response>(relaxed = true)
        listener.onOpen(mockWebSocket, mockResponse)
        assertTrue(session.isConnected)

        // Launch collector for incoming messages
        val received = mutableListOf<LiveServerMessage>()
        val collectJob = launch {
            session.incomingMessages.collect {
                received.add(it)
            }
        }

        // Simulate incoming server message
        val goAwayJson = """{"goAway":{"timeLeft":"45s"}}"""
        listener.onMessage(mockWebSocket, goAwayJson)

        assertEquals(1, received.size)
        assertTrue(received[0] is LiveServerMessage.GoAwayMessage)
        assertEquals("45s", (received[0] as LiveServerMessage.GoAwayMessage).goAway.timeLeft)

        collectJob.cancel()
        session.disconnect()
    }

    @Test
    fun testWebSocketFailureSignalsErrorToIncomingMessagesFlow() = runTest {
        val mockClient = mockk<OkHttpClient>()
        val mockWebSocket = mockk<WebSocket>(relaxed = true)
        val listenerSlot = slot<WebSocketListener>()

        every { mockClient.newWebSocket(any<Request>(), capture(listenerSlot)) } returns mockWebSocket

        val session = OkHttpLiveSession(mockClient)
        val setup = SetupConfig(model = "models/gemini-3.8-live")
        session.connect("fake-key", setup)

        val listener = listenerSlot.captured

        // Simulate failure before or during streaming
        val expectedException = IOException("Connection reset by peer")
        listener.onFailure(mockWebSocket, expectedException, null)

        assertFalse(session.isConnected)

        // Collecting incomingMessages should now throw expectedException instead of hanging
        try {
            session.incomingMessages.first()
            fail("Expected incomingMessages to throw IOException on WebSocket failure")
        } catch (e: IOException) {
            assertEquals("Connection reset by peer", e.message)
        }
    }
}
