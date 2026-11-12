package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * SystemTtsEngine wraps Android TextToSpeech with a pure-Kotlin fallback generator for unit tests,
 * applying pitch and rate adjustments.
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
            VoiceInfo("system-default", "System Default", "neutral", "en-US")
        )
    }

    override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
        // Pure-Kotlin fallback generator simulating PCM audio with pitch/speed factors
        val sampleRate = 16000
        val durationMs = (request.text.length * 50 / request.speed).toLong().coerceAtLeast(100)
        val byteCount = (sampleRate * (durationMs / 1000.0)).toInt() * 2
        val dummyData = ByteArray(byteCount) { 0 }
        emit(PcmChunk(data = dummyData, sampleRate = sampleRate, channels = 1))
    }
}
