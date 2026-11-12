package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Gemini Live 3.8 bidirectional engine for conversational narration and live tone changes.
 */
class GeminiLiveEngine : NarrationEngine {
    override val id: EngineId = EngineId.GEMINI_LIVE
    override val capabilities: EngineCapabilities = EngineCapabilities(
        supportsMultiVoice = true,
        supportsEmotionalStyles = true,
        supportsStreaming = true,
        outputSampleRate = 24000
    )

    override suspend fun voices(): List<VoiceInfo> {
        return listOf(
            VoiceInfo("live-Puck", "Puck (Live)", "male", "en-US"),
            VoiceInfo("live-Aoede", "Aoede (Live)", "female", "en-US")
        )
    }

    override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> {
        return emptyFlow()
    }
}
