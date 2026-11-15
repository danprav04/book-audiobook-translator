package io.github.danprav04.polyglotbooks.core.gemini

import android.util.Log
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
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
        assertEquals(0, client.readTimeoutMillis)
    }

    @Test
    fun testDefaultLiveSessionFactoryForcesZeroReadTimeoutAndPingInterval() {
        val appClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        val factory = DefaultLiveSessionFactory(appClient)
        assertEquals(0, factory.configuredClient.readTimeoutMillis)
        assertEquals(20_000, factory.configuredClient.pingIntervalMillis)

        val createdSession = factory.create() as OkHttpLiveSession
        assertEquals(0, createdSession.client.readTimeoutMillis)
        assertEquals(20_000, createdSession.client.pingIntervalMillis)

        val noPingClient = OkHttpClient.Builder()
            .readTimeout(15, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
        val factoryNoPing = DefaultLiveSessionFactory(noPingClient)
        assertEquals(0, factoryNoPing.configuredClient.readTimeoutMillis)
        assertEquals(20_000, factoryNoPing.configuredClient.pingIntervalMillis)
    }

    @Test
    fun testNoAutoReconnectOn1007BeforeSetupComplete() = runTest {
        val mockClient = mockk<OkHttpClient>()
        val sockets = mutableListOf<WebSocket>()
        val listeners = mutableListOf<WebSocketListener>()

        every { mockClient.newWebSocket(any<Request>(), any()) } answers {
            val ws = mockk<WebSocket>(relaxed = true)
            sockets.add(ws)
            listeners.add(secondArg())
            ws
        }

        val session = OkHttpLiveSession(mockClient, maxReconnectAttempts = 2)
        val setup = SetupConfig(
            model = "models/gemini-3.5-live-translate-preview",
            sessionResumption = SessionResumptionConfig()
        )
        session.connect("fake-key", setup)
        assertEquals(1, listeners.size)

        listeners[0].onOpen(sockets[0], mockk(relaxed = true))
        listeners[0].onClosing(
            sockets[0],
            1007,
            "Invalid JSON payload received. Unknown name \"inputAudioTranscription\""
        )

        // Must fail fast without attempting any reconnect
        assertEquals(1, listeners.size)
        try {
            session.incomingMessages.first()
            fail("Expected IOException for 1007 close")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("code=1007"))
        }
    }

    @Test
    fun testNoAutoReconnectOn1008BeforeSetupComplete() = runTest {
        val mockClient = mockk<OkHttpClient>()
        val sockets = mutableListOf<WebSocket>()
        val listeners = mutableListOf<WebSocketListener>()

        every { mockClient.newWebSocket(any<Request>(), any()) } answers {
            val ws = mockk<WebSocket>(relaxed = true)
            sockets.add(ws)
            listeners.add(secondArg())
            ws
        }

        val session = OkHttpLiveSession(mockClient, maxReconnectAttempts = 2)
        val setup = SetupConfig(
            model = "models/gemini-3.5-live-translate-preview",
            sessionResumption = SessionResumptionConfig()
        )
        session.connect("fake-key", setup)
        listeners[0].onOpen(sockets[0], mockk(relaxed = true))
        listeners[0].onClosing(
            sockets[0],
            1008,
            "models/gemini-3.5-live-translate-preview is not found for API version v1beta"
        )

        assertEquals(1, listeners.size)
        try {
            session.incomingMessages.first()
            fail("Expected IOException for 1008 close")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("code=1008"))
        }
    }

    @Test
    fun testNoAutoReconnectOn1011BeforeSetupCompleteAndPermanentCloseAfterSetup() = runTest {
        val mockClient = mockk<OkHttpClient>()
        val sockets = mutableListOf<WebSocket>()
        val listeners = mutableListOf<WebSocketListener>()

        every { mockClient.newWebSocket(any<Request>(), any()) } answers {
            val ws = mockk<WebSocket>(relaxed = true)
            sockets.add(ws)
            listeners.add(secondArg())
            ws
        }

        // Before setupComplete, even 1011 must not auto-reconnect
        val session1 = OkHttpLiveSession(mockClient, maxReconnectAttempts = 2)
        session1.connect(
            "fake-key",
            SetupConfig(model = "models/gemini-3.8-live", sessionResumption = SessionResumptionConfig())
        )
        listeners[0].onOpen(sockets[0], mockk(relaxed = true))
        listeners[0].onClosing(sockets[0], 1011, "Closing before setupComplete")
        assertEquals(1, listeners.size)
        try {
            session1.incomingMessages.first()
            fail("Expected IOException")
        } catch (_: IOException) {
        }

        // After setupComplete, permanent protocol/policy error (1008/4401) must still not auto-reconnect
        val session2 = OkHttpLiveSession(mockClient, maxReconnectAttempts = 2)
        session2.connect(
            "fake-key",
            SetupConfig(model = "models/gemini-3.8-live", sessionResumption = SessionResumptionConfig())
        )
        listeners[1].onOpen(sockets[1], mockk(relaxed = true))
        listeners[1].onMessage(sockets[1], """{"setupComplete":{}}""")
        assertTrue(session2.isSetupComplete)
        listeners[1].onClosing(sockets[1], 4401, "Unauthorized")
        assertEquals(2, listeners.size)
    }

    @Test
    fun testAwaitSetupCompleteBeforeSendGatesClientContentUntilSetupComplete() = runTest {
        val mockClient = mockk<OkHttpClient>()
        val mockWebSocket = mockk<WebSocket>(relaxed = true)
        val listenerSlot = slot<WebSocketListener>()
        val sentPayloads = mutableListOf<String>()

        every { mockWebSocket.send(any<String>()) } answers {
            sentPayloads.add(firstArg())
            true
        }
        every { mockClient.newWebSocket(any<Request>(), capture(listenerSlot)) } returns mockWebSocket

        val session = OkHttpLiveSession(
            okHttpClient = mockClient,
            awaitSetupCompleteBeforeSend = true
        )
        session.connect("fake-key", SetupConfig(model = "models/gemini-3.8-live"))
        val listener = listenerSlot.captured
        listener.onOpen(mockWebSocket, mockk(relaxed = true))
        assertEquals(1, sentPayloads.size) // Only SetupMessage sent so far

        val sendJob = launch {
            session.send(
                LiveClientMessage.ClientContentMessage(
                    ClientContentData(
                        turns = listOf(ContentTurn(role = "user", parts = listOf(Part(text = "Hello world")))),
                        turnComplete = true
                    )
                )
            )
        }
        runCurrent()
        assertFalse("send() must suspend until setupComplete arrives", sendJob.isCompleted)
        assertEquals(1, sentPayloads.size)

        listener.onMessage(mockWebSocket, """{"setupComplete":{}}""")
        runCurrent()
        assertTrue("send() must resume once setupComplete arrives", sendJob.isCompleted)
        assertEquals(2, sentPayloads.size)
        assertTrue(sentPayloads[1].contains("Hello world"))
        session.disconnect()
    }

    @Test
    fun testDefaultLiveSessionFactoryDeferGoAwayDoesNotAutoReconnectMidTurn() = runTest {
        val mockClient = mockk<OkHttpClient>()
        val sockets = mutableListOf<WebSocket>()
        val listeners = mutableListOf<WebSocketListener>()

        every { mockClient.newWebSocket(any<Request>(), any()) } answers {
            val ws = mockk<WebSocket>(relaxed = true)
            sockets.add(ws)
            listeners.add(secondArg())
            ws
        }

        val factory = DefaultLiveSessionFactory(mockClient)
        val deferredGoAwaySession = factory.create(reconnectOnGoAway = false) as OkHttpLiveSession
        deferredGoAwaySession.connect(
            "fake-key",
            SetupConfig(model = "models/gemini-3.8-live", sessionResumption = SessionResumptionConfig())
        )
        listeners[0].onOpen(sockets[0], mockk(relaxed = true))
        listeners[0].onMessage(sockets[0], """{"setupComplete":{}}""")
        listeners[0].onMessage(sockets[0], """{"goAway":{"timeLeft":"30s"}}""")

        // Deferring GoAway keeps current socket alive for in-flight turn without reconnecting eagerly.
        assertEquals(1, listeners.size)
        assertTrue(deferredGoAwaySession.isConnected)
        val msg = deferredGoAwaySession.incomingMessages.first { it is LiveServerMessage.GoAwayMessage }
        assertEquals("30s", (msg as LiveServerMessage.GoAwayMessage).goAway.timeLeft)
        deferredGoAwaySession.disconnect()

        // Default factory session still auto-reconnects on GoAway for continuous live translation.
        val autoGoAwaySession = factory.create() as OkHttpLiveSession
        autoGoAwaySession.connect(
            "fake-key",
            SetupConfig(model = "models/gemini-3.5-live-translate-preview", sessionResumption = SessionResumptionConfig())
        )
        listeners[1].onOpen(sockets[1], mockk(relaxed = true))
        listeners[1].onMessage(sockets[1], """{"setupComplete":{}}""")
        listeners[1].onMessage(sockets[1], """{"goAway":{"timeLeft":"10s"}}""")
        assertEquals(3, listeners.size)
        autoGoAwaySession.disconnect()
    }

    @Test
    fun testEmptyKeepaliveEnvelopesDoNotLogErrorsAndBurstPreservesFifoOrder() = runTest {
        DiagnosticLogBuffer.clear()
        val mockClient = mockk<OkHttpClient>()
        val mockWebSocket = mockk<WebSocket>(relaxed = true)
        val listenerSlot = slot<WebSocketListener>()

        every { mockClient.newWebSocket(any<Request>(), capture(listenerSlot)) } returns mockWebSocket

        val session = OkHttpLiveSession(okHttpClient = mockClient)
        val collectedIndices = mutableListOf<Int>()
        val collectJob = launch {
            session.incomingMessages.collect { msg ->
                if (msg is LiveServerMessage.ServerContentMessage) {
                    val idx = msg.serverContent.outputTranscription?.text?.toIntOrNull()
                    if (idx != null) collectedIndices.add(idx)
                }
            }
        }
        runCurrent()

        session.connect("fake-key", SetupConfig(model = "models/gemini-3.8-live"))
        val listener = listenerSlot.captured
        listener.onOpen(mockWebSocket, mockk(relaxed = true))
        listener.onMessage(mockWebSocket, "{}")
        listener.onMessage(mockWebSocket, "{\n}")

        val burstCount = 500
        for (i in 0 until burstCount) {
            listener.onMessage(
                mockWebSocket,
                """{"serverContent":{"outputTranscription":{"text":"$i"}}}"""
            )
        }
        runCurrent()

        assertEquals((0 until burstCount).toList(), collectedIndices)
        val errorLogs = DiagnosticLogBuffer.recentEntries().filter { it.level == "ERROR" }
        assertTrue("Expected zero ERROR logs for empty keepalive envelopes, got $errorLogs", errorLogs.isEmpty())

        collectJob.cancel()
        session.disconnect()
    }
}
