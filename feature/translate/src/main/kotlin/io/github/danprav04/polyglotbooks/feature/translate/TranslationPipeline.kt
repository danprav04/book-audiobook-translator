package io.github.danprav04.polyglotbooks.feature.translate

import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State representing live translation pipeline health and progress.
 */
data class TranslationPipelineState(
    val isRunning: Boolean = false,
    val sourcePositionMs: Long = 0L,
    val bufferAheadMs: Long = 0L,
    val speedMultiplier: Float = 1.0f,
    val currentSourceSubtitle: String = "",
    val currentTranslatedSubtitle: String = "",
    val errorMessage: String? = null
)

/**
 * High-level orchestration pipeline skeleton coordinating audio decoding,
 * look-ahead buffer pacing via [Pacer], WebSocket streaming to Gemini Live Translate,
 * and audio playback/caching.
 */
class TranslationPipeline(
    private val pacer: Pacer = Pacer(),
    private val timelineMapper: TimelineMapper = DefaultTimelineMapper()
) {
    private val _state = MutableStateFlow(TranslationPipelineState())
    val state: Flow<TranslationPipelineState> = _state.asStateFlow()

    fun start(bookId: String, targetLanguage: LanguageCode, startPositionMs: Long = 0L) {
        _state.value = _state.value.copy(
            isRunning = true,
            sourcePositionMs = startPositionMs,
            bufferAheadMs = 0L,
            speedMultiplier = pacer.maxSendSpeed,
            errorMessage = null
        )
    }

    fun updateBuffer(currentBufferAheadMs: Long, sourcePosMs: Long) {
        val speed = pacer.calculateSpeedMultiplier(currentBufferAheadMs)
        _state.value = _state.value.copy(
            sourcePositionMs = sourcePosMs,
            bufferAheadMs = currentBufferAheadMs,
            speedMultiplier = speed
        )
    }

    fun updateSubtitles(sourceTranscript: String, translatedTranscript: String) {
        _state.value = _state.value.copy(
            currentSourceSubtitle = sourceTranscript,
            currentTranslatedSubtitle = translatedTranscript
        )
    }

    fun stop() {
        _state.value = _state.value.copy(isRunning = false)
    }
}
