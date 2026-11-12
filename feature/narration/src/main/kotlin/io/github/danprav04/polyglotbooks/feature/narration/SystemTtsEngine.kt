package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * System TTS engine (android.speech.tts.TextToSpeech) offline fallback.
 */
class SystemTtsEngine : NarrationEngine {
    override val id: EngineId = EngineId.SYSTEM_TTS
    override val capabilities: EngineCapabilities = EngineCapabilities(
        supportsMultiVoice = false,
        supportsEmotionalStyles = false,
        supportsStreaming = false,
        outputSampleRate = 16000
    )

    override suspend fun voices(): List<VoiceInfo> {
        return listOf(
            VoiceInfo("system-default", "System Default", "neutral", "default")
        )
    }

    override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> {
        return emptyFlow()
    }
}
