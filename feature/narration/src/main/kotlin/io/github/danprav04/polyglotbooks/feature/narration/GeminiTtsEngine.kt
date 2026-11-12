package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Gemini 3.8 Flash TTS engine implementation skeleton (via Interactions API).
 */
class GeminiTtsEngine : NarrationEngine {
    override val id: EngineId = EngineId.GEMINI_TTS
    override val capabilities: EngineCapabilities = EngineCapabilities(
        supportsMultiVoice = true,
        supportsEmotionalStyles = true,
        supportsStreaming = true,
        outputSampleRate = 24000
    )

    override suspend fun voices(): List<VoiceInfo> {
        return listOf(
            VoiceInfo("en-US-Puck", "Puck", "male", "en-US"),
            VoiceInfo("en-US-Charon", "Charon", "male", "en-US"),
            VoiceInfo("en-US-Kore", "Kore", "female", "en-US"),
            VoiceInfo("en-US-Fenrir", "Fenrir", "male", "en-US"),
            VoiceInfo("en-US-Aoede", "Aoede", "female", "en-US")
        )
    }

    override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> {
        return emptyFlow()
    }
}
