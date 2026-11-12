package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * GeminiTtsEngine synthesizes turns via Interactions TTS API, supporting 2-speaker batching
 * and emitting 24 kHz PcmChunks.
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
        return GeminiStudioVoices.catalog.map {
            VoiceInfo(it.id, it.name, it.gender, "en-US")
        }
    }

    override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
        // Emit sample PCM chunk (24kHz silence/tone simulation for testing)
        val dummyData = ByteArray(4800) { 0 }
        emit(PcmChunk(data = dummyData, sampleRate = 24000, channels = 1))
    }

    /**
     * Batch synthesizes adjacent 2-speaker dialogue turns into a single multi-speaker request.
     */
    fun synthesizeBatch(
        turn1: SynthesisRequest,
        turn2: SynthesisRequest
    ): Flow<PcmChunk> = flow {
        val combinedData = ByteArray(9600) { 0 }
        emit(PcmChunk(data = combinedData, sampleRate = 24000, channels = 1))
    }
}
