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

/**
 * Per-part segment inside a single [SynthesisRequest] allowing multi-paragraph batches to
 * carry distinct single-narrator delivery cues (`style`, `speakerName`, `characterPersonality`)
 * while sharing one voice and one API request.
 */
data class SynthesisSegment(
    val text: String,
    val style: String? = null,
    val speakerName: String? = null,
    val characterPersonality: String? = null
)

data class SynthesisRequest(
    val text: String,
    val voiceId: String,
    val style: String? = null,
    val speed: Float = 1.0f,
    val languageCode: String? = null,
    val speakerName: String? = null,
    val characterPersonality: String? = null,
    val storySummary: String? = null,
    val segments: List<SynthesisSegment> = emptyList()
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
