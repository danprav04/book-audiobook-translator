package io.github.danprav04.polyglotbooks.core.gemini

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Production implementation of [LiveSession] using OkHttp WebSocket connecting to the Gemini Live API.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OkHttpLiveSession(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
) : LiveSession {

    private val TAG = "OkHttpLiveSession"

    private val _incomingMessages = MutableSharedFlow<LiveServerMessage>(replay = 16, extraBufferCapacity = 64)
    private val _failures = MutableSharedFlow<Throwable>(replay = 1)

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

    private var webSocket: WebSocket? = null
    private var _isConnected = false
    override val isConnected: Boolean
        get() = _isConnected

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentApiKey: String? = null
    private var currentSetup: SetupConfig? = null

    var currentResumptionHandle: String? = null
        private set

    override suspend fun connect(apiKey: String, setup: SetupConfig) {
        _incomingMessages.resetReplayCache()
        _failures.resetReplayCache()
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
        val request = okhttp3.Request.Builder().url(url).build()

        disconnectInternal()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                _isConnected = true
                Log.d(TAG, "WebSocket opened. Sending setup...")
                val setupMsg = LiveClientMessage.SetupMessage(finalSetup)
                val jsonStr = json.encodeToString<LiveClientMessage>(setupMsg)
                webSocket.send(jsonStr)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val serverMsg = json.decodeFromString<LiveServerMessage>(text)
                    if (serverMsg is LiveServerMessage.SessionResumptionUpdateMessage) {
                        val handle = serverMsg.sessionResumptionUpdate.newHandle
                        if (!handle.isNullOrBlank()) {
                            currentResumptionHandle = handle
                            Log.d(TAG, "Tracked new session resumption handle: $handle")
                        }
                    }
                    if (serverMsg is LiveServerMessage.GoAwayMessage) {
                        Log.w(TAG, "Received GoAway message. Reconnecting...")
                        scope.launch {
                            reconnectWithResumption()
                        }
                    }
                    if (!_incomingMessages.tryEmit(serverMsg)) {
                        scope.launch {
                            _incomingMessages.emit(serverMsg)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse incoming server message: $text", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                _isConnected = false
                if (code != 1000) {
                    _failures.tryEmit(java.io.IOException("WebSocket closed unexpectedly: code=$code, reason=$reason"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "WebSocket failure", t)
                _isConnected = false
                _failures.tryEmit(t)
            }
        })
    }

    override suspend fun send(message: LiveClientMessage) {
        val jsonStr = json.encodeToString(message)
        webSocket?.send(jsonStr) ?: Log.w(TAG, "Attempted to send message while WebSocket is null")
    }

    override suspend fun disconnect() {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        _isConnected = false
    }

    private suspend fun reconnectWithResumption() {
        val apiKey = currentApiKey ?: return
        val setup = currentSetup ?: return
        try {
            disconnectInternal()
            connect(apiKey, setup)
        } catch (e: Exception) {
            Log.e(TAG, "Resumption reconnection failed, falling back to fresh session", e)
            currentResumptionHandle = null
            connect(apiKey, setup.copy(sessionResumption = null))
        }
    }
}

interface LiveSessionFactory {
    fun create(): LiveSession
}

class DefaultLiveSessionFactory : LiveSessionFactory {
    override fun create(): LiveSession = OkHttpLiveSession()
}
