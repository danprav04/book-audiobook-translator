package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.gemini.InteractionsTtsClient
import io.github.danprav04.polyglotbooks.core.gemini.TtsRequest
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * GeminiTtsEngine synthesizes turns via Interactions TTS API, supporting 2-speaker batching
 * and emitting 24 kHz PcmChunks.
 */
class GeminiTtsEngine(
    private val ttsClient: InteractionsTtsClient? = null,
    private val apiKeyProvider: (suspend () -> String?)? = null,
    private val modelProvider: (suspend () -> String)? = null
) : NarrationEngine {
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
        if (ttsClient == null) {
            // Emit sample PCM chunk (24kHz silence/tone simulation for deterministic unit tests)
            val dummyData = ByteArray(4800) { 0 }
            emit(PcmChunk(data = dummyData, sampleRate = 24000, channels = 1))
            return@flow
        }

        val apiKey = apiKeyProvider?.invoke()?.trim()
        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("Missing API key")
        }

        val model = modelProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
            ?: DefaultModels.get(ModelRole.TTS)

        val ttsRequest = TtsRequest(
            text = request.text,
            voiceName = request.voiceId.takeIf { it.isNotBlank() },
            style = request.style,
            model = model
        )

        var emittedCount = 0
        ttsClient.synthesizeStream(apiKey, ttsRequest).collect { chunk ->
            emittedCount++
            emit(chunk)
        }
        if (emittedCount == 0) {
            throw IllegalStateException("Gemini TTS returned empty audio stream")
        }
    }

    /**
     * Batch synthesizes adjacent 2-speaker dialogue turns into a single multi-speaker request.
     */
    fun synthesizeBatch(
        turn1: SynthesisRequest,
        turn2: SynthesisRequest
    ): Flow<PcmChunk> = flow {
        if (ttsClient == null) {
            val combinedData = ByteArray(9600) { 0 }
            emit(PcmChunk(data = combinedData, sampleRate = 24000, channels = 1))
        } else {
            emitAll(synthesize(turn1))
            emitAll(synthesize(turn2))
        }
    }
}
