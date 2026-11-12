package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Request payload for text-to-speech synthesis via the Interactions API.
 */
data class TtsRequest(
    val text: String,
    val voiceName: String? = null,
    val speaker: String? = null,
    val style: String? = null,
    val model: String = DefaultModels.get(ModelRole.TTS)
)

/**
 * Client interface for Gemini Flash TTS (Interactions API / generateContent).
 */
interface InteractionsTtsClient {
    suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray
    fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk>
}

/**
 * Stub implementation of [InteractionsTtsClient].
 */
class StubInteractionsTtsClient : InteractionsTtsClient {
    override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray {
        // Return 100ms of silence (3200 bytes of 16-bit PCM) as stub response
        return ByteArray(3200)
    }

    override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> {
        return emptyFlow()
    }
}
