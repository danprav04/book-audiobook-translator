package io.github.danprav04.polyglotbooks.core.gemini

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Production implementation of [LiveSession] using OkHttp WebSocket connecting to the Gemini Live API.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OkHttpLiveSession(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
    private val connectTimeoutMs: Long = 10_000L,
    private val maxReconnectAttempts: Int = 2
) : LiveSession {

    internal val client: OkHttpClient
        get() = okHttpClient

    private val TAG = "OkHttpLiveSession"

    private val _incomingMessages = MutableSharedFlow<LiveServerMessage>(replay = 16, extraBufferCapacity = 64)
    private val _failures = MutableSharedFlow<Throwable>(replay = 1, extraBufferCapacity = 4)

    override val incomingMessages: Flow<LiveServerMessage> = flow {
        coroutineScope {
            val failureJob = launch {
                val error = _failures.first()
                throw error
            }
            try {
                _incomingMessages.collect { msg ->
                    emit(msg)
                }
            } finally {
                failureJob.cancel()
            }
        }
    }

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var _isConnected = false
    override val isConnected: Boolean
        get() = _isConnected

    @Volatile
    private var _isSetupComplete = false
    val isSetupComplete: Boolean
        get() = _isSetupComplete

    @Volatile
    private var _lastError: LiveErrorData? = null
    val lastError: LiveErrorData?
        get() = _lastError

    @Volatile
    private var openDeferred: CompletableDeferred<Unit>? = null

    @Volatile
    private var isManualDisconnect = false

    @Volatile
    private var hasEverOpened = false

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var activeConnectionId = 0L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentApiKey: String? = null
    private var currentSetup: SetupConfig? = null

    var currentResumptionHandle: String? = null
        private set

    override suspend fun connect(apiKey: String, setup: SetupConfig) {
        _incomingMessages.resetReplayCache()
        _failures.resetReplayCache()
        _lastError = null
        isManualDisconnect = false
        hasEverOpened = false
        reconnectAttempts = 0

        val signal = startWebSocketInternal(apiKey, setup)
        if (signal.isCompleted) {
            signal.await()
        } else if (connectTimeoutMs > 0) {
            withTimeoutOrNull(connectTimeoutMs) {
                signal.await()
            }
        }
    }

    private fun startWebSocketInternal(
        apiKey: String,
        setup: SetupConfig
    ): CompletableDeferred<Unit> {
        currentApiKey = apiKey
        val finalSetup = if (currentResumptionHandle != null && setup.sessionResumption?.handle == null) {
            setup.copy(sessionResumption = SessionResumptionConfig(handle = currentResumptionHandle))
        } else {
            if (setup.sessionResumption?.handle != null) {
                currentResumptionHandle = setup.sessionResumption.handle
            }
            setup
        }
        currentSetup = finalSetup

        val url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        val request = Request.Builder().url(url).build()

        closeCurrentSocketQuietly()
        _isSetupComplete = false

        val connectionId = ++activeConnectionId
        val signal = CompletableDeferred<Unit>()
        openDeferred = signal

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (connectionId != activeConnectionId) return
                this@OkHttpLiveSession.webSocket = webSocket
                _isConnected = true
                hasEverOpened = true
                logD("WebSocket opened. Sending setup...")
                val setupMsg = LiveClientMessage.SetupMessage(finalSetup)
                val jsonStr = json.encodeToString<LiveClientMessage>(setupMsg)
                webSocket.send(jsonStr)
                signal.complete(Unit)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (connectionId != activeConnectionId) return
                handleIncomingJson(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (connectionId != activeConnectionId) return
                handleIncomingJson(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionId != activeConnectionId) return
                logD("WebSocket closing: $code / $reason")
                val setupWasComplete = _isSetupComplete
                _isConnected = false
                _isSetupComplete = false
                try {
                    webSocket.close(code, reason)
                } catch (_: Throwable) {
                }
                if (code != 1000 && !isManualDisconnect) {
                    val err = IOException("WebSocket closed unexpectedly: code=$code, reason=$reason")
                    if (!signal.isCompleted) {
                        signal.completeExceptionally(err)
                    }
                    handleUnexpectedDisconnect(
                        cause = err,
                        setupWasComplete = setupWasComplete,
                        closeCode = code
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectionId != activeConnectionId) return
                logE("WebSocket failure", t)
                val setupWasComplete = _isSetupComplete
                _isConnected = false
                _isSetupComplete = false
                val httpCode = try {
                    response?.code
                } catch (_: Throwable) {
                    null
                }
                val failure = if (httpCode != null && httpCode >= 400 &&
                    t.message?.contains(httpCode.toString()) != true
                ) {
                    val reason = try {
                        response?.message.orEmpty()
                    } catch (_: Throwable) {
                        ""
                    }
                    IOException("WebSocket handshake failed (HTTP $httpCode $reason): ${t.message ?: "failure"}", t)
                } else {
                    t
                }
                if (!signal.isCompleted) {
                    signal.completeExceptionally(failure)
                }
                if (!isManualDisconnect) {
                    handleUnexpectedDisconnect(
                        cause = failure,
                        setupWasComplete = setupWasComplete,
                        httpStatusCode = httpCode
                    )
                }
            }
        })

        return signal
    }

    private fun handleIncomingJson(text: String) {
        if (text.isBlank()) return
        try {
            val serverMsg = json.decodeFromString<LiveServerMessage>(text)
            when (serverMsg) {
                is LiveServerMessage.SetupCompleteMessage -> {
                    _isSetupComplete = true
                    reconnectAttempts = 0
                }
                is LiveServerMessage.SessionResumptionUpdateMessage -> {
                    val handle = serverMsg.sessionResumptionUpdate.newHandle
                    if (!handle.isNullOrBlank()) {
                        currentResumptionHandle = handle
                        logD("Tracked new session resumption handle: $handle")
                    }
                }
                is LiveServerMessage.ServerContentMessage -> {
                    reconnectAttempts = 0
                }
                is LiveServerMessage.GoAwayMessage -> {
                    logW("Received GoAway message. Reconnecting with session resumption...")
                    reconnectAttempts = 0
                    reconnectWithResumption()
                }
                is LiveServerMessage.ErrorMessage -> {
                    _lastError = serverMsg.error
                    logW("Received server ErrorMessage: ${serverMsg.error}")
                }
                is LiveServerMessage.UsageMetadataMessage -> {
                    // Metadata frame; forwarded to collectors below.
                }
            }
            if (!_incomingMessages.tryEmit(serverMsg)) {
                scope.launch {
                    _incomingMessages.emit(serverMsg)
                }
            }
        } catch (e: Exception) {
            logE("Failed to parse incoming server message: $text", e)
        }
    }

    private fun handleUnexpectedDisconnect(
        cause: Throwable,
        setupWasComplete: Boolean,
        closeCode: Int? = null,
        httpStatusCode: Int? = null
    ) {
        val permanentError = isPermanentDisconnectError(cause, closeCode, httpStatusCode)
        val resumptionEnabled = currentSetup?.sessionResumption != null || currentResumptionHandle != null
        val eligibleForReconnect = setupWasComplete &&
            !permanentError &&
            resumptionEnabled &&
            (hasEverOpened || currentResumptionHandle != null) &&
            reconnectAttempts < maxReconnectAttempts

        if (eligibleForReconnect) {
            reconnectAttempts++
            logW("Attempting session resumption reconnect ($reconnectAttempts/$maxReconnectAttempts)...")
            if (reconnectWithResumption()) {
                return
            }
        }
        _failures.tryEmit(cause)
    }

    private fun isPermanentDisconnectError(
        cause: Throwable,
        closeCode: Int?,
        httpStatusCode: Int?
    ): Boolean {
        if (closeCode != null && closeCode in PERMANENT_CLOSE_CODES) return true
        if (httpStatusCode != null && httpStatusCode in PERMANENT_HTTP_CODES) return true

        return generateSequence(cause) { it.cause }.any { throwable ->
            val msg = throwable.message.orEmpty()
            PERMANENT_CODE_IN_MESSAGE_REGEX.containsMatchIn(msg) ||
                PERMANENT_HTTP_IN_MESSAGE_REGEX.containsMatchIn(msg)
        }
    }

    override suspend fun send(message: LiveClientMessage) {
        val pendingOpen = openDeferred
        if (!_isConnected && pendingOpen != null && !pendingOpen.isCompleted && connectTimeoutMs > 0) {
            withTimeoutOrNull(connectTimeoutMs) {
                try {
                    pendingOpen.await()
                } catch (_: Throwable) {
                }
            }
        }
        val jsonStr = json.encodeToString(message)
        webSocket?.send(jsonStr) ?: logW("Attempted to send message while WebSocket is null")
    }

    override suspend fun disconnect() {
        isManualDisconnect = true
        val pendingOpen = openDeferred
        openDeferred = null
        pendingOpen?.cancel()
        closeCurrentSocketQuietly()
    }

    private fun closeCurrentSocketQuietly() {
        activeConnectionId++
        val old = webSocket
        webSocket = null
        _isConnected = false
        _isSetupComplete = false
        try {
            old?.close(1000, "Client disconnect")
        } catch (_: Throwable) {
        }
    }

    private fun reconnectWithResumption(): Boolean {
        val apiKey = currentApiKey ?: return false
        val setup = currentSetup ?: return false
        return try {
            startWebSocketInternal(apiKey, setup)
            true
        } catch (e: Exception) {
            logE("Resumption reconnection failed, falling back to fresh session", e)
            currentResumptionHandle = null
            try {
                startWebSocketInternal(apiKey, setup.copy(sessionResumption = SessionResumptionConfig(handle = null)))
                true
            } catch (e2: Exception) {
                logE("Fresh session reconnection also failed", e2)
                false
            }
        }
    }

    private fun logD(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun logW(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    private fun logE(msg: String, t: Throwable) {
        try {
            Log.e(TAG, msg, t)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private val PERMANENT_CLOSE_CODES = setOf(1003, 1007, 1008, 4400, 4401, 4403, 4404)
        private val PERMANENT_HTTP_CODES = setOf(400, 401, 403, 404)
        private val PERMANENT_CODE_IN_MESSAGE_REGEX = Regex(
            """(?i)\b(?:code|close)\s*[=:]\s*(1003|1007|1008|4400|4401|4403|4404)\b|\bcode\s+(1003|1007|1008|4400|4401|4403|4404)\b"""
        )
        private val PERMANENT_HTTP_IN_MESSAGE_REGEX = Regex(
            """(?i)(?:\bHTTP[\s/]*|was\s+['"]?)(400|401|403|404)\b"""
        )

        fun defaultOkHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
    }
}

interface LiveSessionFactory {
    fun create(): LiveSession
}

class DefaultLiveSessionFactory(
    private val okHttpClient: OkHttpClient = OkHttpLiveSession.defaultOkHttpClient()
) : LiveSessionFactory {
    internal val configuredClient: OkHttpClient by lazy {
        try {
            val needsPing = okHttpClient.pingIntervalMillis <= 0
            val needsReadTimeout = okHttpClient.readTimeoutMillis != 0
            if (!needsPing && !needsReadTimeout) {
                okHttpClient
            } else {
                okHttpClient.newBuilder().apply {
                    if (needsPing) {
                        pingInterval(20, TimeUnit.SECONDS)
                    }
                    if (needsReadTimeout) {
                        readTimeout(0, TimeUnit.MILLISECONDS)
                    }
                }.build()
            }
        } catch (_: Throwable) {
            okHttpClient
        }
    }

    override fun create(): LiveSession = OkHttpLiveSession(okHttpClient = configuredClient)
}
