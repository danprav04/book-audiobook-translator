package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow

/**
 * Unique identifier for a narration engine.
 */
enum class EngineId {
    GEMINI_TTS,
    GEMINI_LIVE,
    SYSTEM_TTS
}

/**
 * Engine capabilities describing multi-voice and emotional style support.
 */
data class EngineCapabilities(
    val supportsMultiVoice: Boolean,
    val supportsEmotionalStyles: Boolean,
    val supportsStreaming: Boolean,
    val outputSampleRate: Int
)

data class VoiceInfo(
    val id: String,
    val name: String,
    val gender: String,
    val language: String
)

data class SynthesisRequest(
    val text: String,
    val voiceId: String,
    val style: String? = null,
    val speed: Float = 1.0f
)

/**
 * Core interface for ebook text-to-speech synthesis engines.
 */
interface NarrationEngine {
    val id: EngineId
    val capabilities: EngineCapabilities
    suspend fun voices(): List<VoiceInfo>
    fun synthesize(request: SynthesisRequest): Flow<PcmChunk>
}
