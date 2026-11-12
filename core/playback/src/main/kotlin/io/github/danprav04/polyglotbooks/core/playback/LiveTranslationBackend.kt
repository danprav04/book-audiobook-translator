package io.github.danprav04.polyglotbooks.core.playback

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Qualifier

/**
 * Hilt qualifier for an application-scoped [CoroutineScope] that survives UI and ViewModel
 * destruction for background playback and non-cancellable persistence flushes.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * State snapshot of the live AI audiobook translation backend.
 */
data class LiveTranslationBackendState(
    val isRunning: Boolean = false,
    val sourcePositionMs: Long = 0L,
    val bufferAheadMs: Long = 0L,
    val speedMultiplier: Float = 1.0f,
    val currentSourceSubtitle: String = "",
    val currentTranslatedSubtitle: String = "",
    val errorMessage: String? = null
)

/**
 * Clean abstraction over the live AI translation pipeline so `:core:playback` remains
 * decoupled from `:feature:translate` and `:core:gemini`.
 */
interface LiveTranslationBackend {
    val state: StateFlow<LiveTranslationBackendState>

    fun start(
        scope: CoroutineScope,
        bookId: String,
        filePath: String,
        targetLanguage: LanguageCode,
        apiKey: String?,
        modelId: String = DefaultModels.get(ModelRole.LIVE_TRANSLATE),
        echoTargetLanguage: Boolean = false,
        startPositionMs: Long = 0L,
        playbackSpeed: Float = 1.0f
    )

    fun updatePlaybackPosition(positionMs: Long)

    fun setPlaybackSpeed(speed: Float)

    fun pauseAudio()

    fun resumeAudio()

    fun stop()
}

/**
 * Default no-op implementation of [LiveTranslationBackend] used when no AI backend is wired
 * or in standalone tests.
 */
class NoOpLiveTranslationBackend : LiveTranslationBackend {
    private val _state = MutableStateFlow(LiveTranslationBackendState())
    override val state: StateFlow<LiveTranslationBackendState> = _state.asStateFlow()

    override fun start(
        scope: CoroutineScope,
        bookId: String,
        filePath: String,
        targetLanguage: LanguageCode,
        apiKey: String?,
        modelId: String,
        echoTargetLanguage: Boolean,
        startPositionMs: Long,
        playbackSpeed: Float
    ) {
        if (apiKey.isNullOrBlank()) {
            _state.value = _state.value.copy(
                isRunning = false,
                sourcePositionMs = startPositionMs,
                bufferAheadMs = 0L,
                errorMessage = MISSING_API_KEY_MESSAGE
            )
            return
        }
        _state.value = LiveTranslationBackendState(
            isRunning = true,
            sourcePositionMs = startPositionMs,
            bufferAheadMs = 0L,
            speedMultiplier = playbackSpeed,
            errorMessage = null
        )
    }

    override fun updatePlaybackPosition(positionMs: Long) {
        _state.value = _state.value.copy(sourcePositionMs = positionMs.coerceAtLeast(0L))
    }

    override fun setPlaybackSpeed(speed: Float) {
        _state.value = _state.value.copy(speedMultiplier = speed.coerceIn(0.5f, 3.0f))
    }

    override fun pauseAudio() {}

    override fun resumeAudio() {}

    override fun stop() {
        _state.value = _state.value.copy(isRunning = false)
    }

    companion object {
        const val MISSING_API_KEY_MESSAGE =
            "Gemini API key is missing. Add it in Settings to use Live AI Translation."
    }
}
