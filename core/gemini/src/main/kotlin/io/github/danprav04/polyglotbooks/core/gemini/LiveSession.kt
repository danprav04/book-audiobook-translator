package io.github.danprav04.polyglotbooks.core.gemini

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Interface representing a bidirectional Live WebSocket session with the Gemini Live API.
 */
interface LiveSession {
    val incomingMessages: Flow<LiveServerMessage>
    suspend fun connect(apiKey: String, setup: SetupConfig)
    suspend fun send(message: LiveClientMessage)
    suspend fun disconnect()
    val isConnected: Boolean
}

/**
 * Stub / test implementation of [LiveSession].
 */
class StubLiveSession : LiveSession {
    private val _incomingMessages = MutableSharedFlow<LiveServerMessage>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<LiveServerMessage> = _incomingMessages.asSharedFlow()

    private var _isConnected: Boolean = false
    override val isConnected: Boolean
        get() = _isConnected

    var lastSentMessage: LiveClientMessage? = null
        private set

    var currentSetup: SetupConfig? = null
        private set

    override suspend fun connect(apiKey: String, setup: SetupConfig) {
        currentSetup = setup
        _isConnected = true
    }

    override suspend fun send(message: LiveClientMessage) {
        lastSentMessage = message
    }

    override suspend fun disconnect() {
        _isConnected = false
    }

    suspend fun emitServerMessage(message: LiveServerMessage) {
        _incomingMessages.emit(message)
    }
}
