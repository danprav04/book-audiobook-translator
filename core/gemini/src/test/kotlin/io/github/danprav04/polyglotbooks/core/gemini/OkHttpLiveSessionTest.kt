package io.github.danprav04.polyglotbooks.core.gemini

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    @Test
    fun testConnectFailsFastWhenOnFailureFiresDuringHandshake() = runTest {
        val mockClient = mockk<OkHttpClient>()
        val mockWebSocket = mockk<WebSocket>(relaxed = true)

        every { mockClient.newWebSocket(any<Request>(), any()) } answers {
            val listener = secondArg<WebSocketListener>()
            listener.onFailure(mockWebSocket, IOException("TLS handshake failed"), null)
            mockWebSocket
        }

        val session = OkHttpLiveSession(mockClient)
        try {
            session.connect("fake-key", SetupConfig(model = "models/gemini-3.8-live"))
            fail("Expected connect() to throw IOException when handshake fails")
        } catch (e: IOException) {
            assertEquals("TLS handshake failed", e.message)
        }
    }

    @Test
    fun testBoundedReconnectWithSessionResumptionOnUnexpectedDisconnect() = runTest {
        val mockClient = mockk<OkHttpClient>()
        val sockets = mutableListOf<WebSocket>()
        val listeners = mutableListOf<WebSocketListener>()
        val sentPayloads = mutableListOf<String>()

        every { mockClient.newWebSocket(any<Request>(), any()) } answers {
            val ws = mockk<WebSocket>(relaxed = true)
            every { ws.send(any<String>()) } answers {
                sentPayloads.add(firstArg())
                true
            }
            sockets.add(ws)
            listeners.add(secondArg())
            ws
        }

        val session = OkHttpLiveSession(mockClient, maxReconnectAttempts = 1)
        val setup = SetupConfig(
            model = "models/gemini-3.5-live-translate-preview",
            sessionResumption = SessionResumptionConfig()
        )
        session.connect("fake-key", setup)
        assertEquals(1, listeners.size)

        // 1. Open first WebSocket and receive setupComplete + resumption token
        val mockResponse = mockk<okhttp3.Response>(relaxed = true)
        listeners[0].onOpen(sockets[0], mockResponse)
        listeners[0].onMessage(sockets[0], """{"setupComplete":{}}""")
        assertTrue(session.isSetupComplete)
        listeners[0].onMessage(
            sockets[0],
            """{"sessionResumptionUpdate":{"resumable":true,"newHandle":"resume-token-xyz"}}"""
        )
        assertEquals("resume-token-xyz", session.currentResumptionHandle)

        // 2. Simulate unexpected disconnect -> should automatically reconnect with handle
        listeners[0].onClosing(sockets[0], 1011, "Internal restart")
        assertEquals(2, listeners.size)

        listeners[1].onOpen(sockets[1], mockResponse)
        assertTrue(session.isConnected)
        assertTrue(sentPayloads.last().contains("resume-token-xyz"))

        // 3. Second failure exceeds maxReconnectAttempts=1 -> emits terminal failure
        listeners[1].onFailure(sockets[1], IOException("Network unreachable"), null)
        assertEquals(2, listeners.size)
        try {
            // Drain the buffered setupComplete and sessionResumptionUpdate first
            session.incomingMessages.first { it is LiveServerMessage.SessionResumptionUpdateMessage }
            session.incomingMessages.first { false }
            fail("Expected terminal IOException after exhausting reconnect attempts")
        } catch (e: IOException) {
            assertEquals("Network unreachable", e.message)
        }
    }

    @Test
    fun testServerErrorMessageParsedAndSurfaced() = runTest {
        val mockClient = mockk<OkHttpClient>()
        val mockWebSocket = mockk<WebSocket>(relaxed = true)
        val listenerSlot = slot<WebSocketListener>()
        every { mockClient.newWebSocket(any<Request>(), capture(listenerSlot)) } returns mockWebSocket

        val session = OkHttpLiveSession(mockClient)
        session.connect("fake-key", SetupConfig(model = "models/gemini-3.8-live"))
        val listener = listenerSlot.captured
        listener.onOpen(mockWebSocket, mockk(relaxed = true))

        listener.onMessage(
            mockWebSocket,
            """{"error":{"code":400,"message":"Unsupported translationConfig","status":"INVALID_ARGUMENT"}}"""
        )

        val msg = session.incomingMessages.first()
        assertTrue(msg is LiveServerMessage.ErrorMessage)
        assertNotNull(session.lastError)
        assertEquals(400, session.lastError?.code)
        verify(atLeast = 1) { mockWebSocket.send(any<String>()) }
    }

    @Test
    fun testDefaultOkHttpClientConfiguresPingInterval() {
        val client = OkHttpLiveSession.defaultOkHttpClient()
        assertEquals(20_000, client.pingIntervalMillis)
    }
}
