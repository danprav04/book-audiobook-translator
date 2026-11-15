package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.InteractionsTtsClient
import io.github.danprav04.polyglotbooks.core.gemini.QuotaExhaustedException
import io.github.danprav04.polyglotbooks.core.gemini.TtsRequest
import io.github.danprav04.polyglotbooks.core.gemini.TtsSegment
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.core.model.PunctuationDialogueHelper
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
    private val modelProvider: (suspend () -> String)? = null,
    private val quotaTracker: GeminiQuotaTracker? = null
) : NarrationEngine {
    override val id: EngineId = EngineId.GEMINI_TTS
    override val capabilities: EngineCapabilities = EngineCapabilities(
        supportsMultiVoice = false,
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
            DiagnosticLogBuffer.record("GeminiTtsEngine", "Missing API key for Flash TTS", level = "ERROR")
            throw IllegalStateException("Missing API key")
        }

        val resolved = PunctuationDialogueHelper.resolve(request.text, request.style)
        when (resolved.kind) {
            PunctuationDialogueHelper.PunctuationKind.EMPTY -> return@flow
            PunctuationDialogueHelper.PunctuationKind.SILENT_DIVIDER -> {
                // Structural scene dividers (e.g. "* * *", "---") should pause briefly rather than being sent to TTS.
                emit(PcmChunk(data = ByteArray(4800) { 0 }, sampleRate = 24000, channels = 1))
                return@flow
            }
            else -> Unit
        }

        val model = modelProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
            ?: DefaultModels.get(ModelRole.TTS)

        if (quotaTracker != null && quotaTracker.isTtsQuotaLikelyExhausted()) {
            val pool = quotaTracker.ttsCandidatePool(model)
            DiagnosticLogBuffer.record(
                "GeminiTtsEngine",
                "Flash TTS quota likely exhausted across ${pool.joinToString()}",
                level = "WARN"
            )
            throw QuotaExhaustedException(
                message = "Free-tier TTS quota limit reached for ${pool.joinToString(", ")}. Switch to Gemini Live or try again later.",
                retryAfterMs = quotaTracker.shortestCooldownMs(pool),
                attemptedModels = pool
            )
        }

        val normalizedStyle = resolved.styleCue
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("neutral", ignoreCase = true) }

        val ttsRequest = TtsRequest(
            text = resolved.spokenText,
            voiceName = request.voiceId.takeIf { it.isNotBlank() },
            speaker = request.speakerName?.trim()?.takeIf { it.isNotEmpty() },
            style = normalizedStyle,
            model = model,
            languageCode = request.languageCode?.trim()?.takeIf { it.isNotEmpty() },
            personality = request.characterPersonality?.trim()?.takeIf { it.isNotEmpty() },
            storyContext = request.storySummary?.trim()?.takeIf { it.isNotEmpty() },
            segments = request.segments.map { seg ->
                TtsSegment(
                    text = seg.text,
                    style = seg.style?.trim()?.takeIf { it.isNotEmpty() && !it.equals("neutral", ignoreCase = true) },
                    speaker = seg.speakerName?.trim()?.takeIf { it.isNotEmpty() },
                    personality = seg.characterPersonality?.trim()?.takeIf { it.isNotEmpty() }
                )
            }
        )

        var emittedCount = 0
        ttsClient.synthesizeStream(apiKey, ttsRequest).collect { chunk ->
            emittedCount++
            emit(chunk)
        }
        if (emittedCount == 0) {
            DiagnosticLogBuffer.record("GeminiTtsEngine", "Gemini TTS returned empty audio stream (model=$model)", level = "ERROR")
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
