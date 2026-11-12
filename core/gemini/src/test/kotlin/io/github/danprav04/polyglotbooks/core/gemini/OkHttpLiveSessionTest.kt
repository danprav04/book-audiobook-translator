package io.github.danprav04.polyglotbooks.core.gemini

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

class OkHttpLiveSessionTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

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

        // Simulate incoming server message
        val contentJson = """{"serverContent":{"turnComplete":true,"interrupted":false}}"""
        listener.onMessage(mockWebSocket, contentJson)

        val receivedMessage = session.incomingMessages.first()
        assertTrue(receivedMessage is LiveServerMessage.ServerContentMessage)
        val content = (receivedMessage as LiveServerMessage.ServerContentMessage).serverContent
        assertEquals(true, content.turnComplete)
        assertEquals(false, content.interrupted)

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
