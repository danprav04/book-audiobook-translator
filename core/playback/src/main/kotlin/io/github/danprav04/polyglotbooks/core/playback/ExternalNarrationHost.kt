package io.github.danprav04.polyglotbooks.core.playback

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of an active external narration session (e.g. Ebook Reader TTS / Gemini narration)
 * surfaced to [PolyglotPlaybackService], [ExternalNarrationPlayer], notifications, and the global mini-player.
 */
data class ExternalNarrationState(
    val isActiveSession: Boolean = false,
    val bookId: String = "",
    val bookTitle: String = "",
    val author: String = "",
    val coverPath: String? = null,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String = "",
    val paragraphIndex: Int = 0,
    val paragraphCount: Int = 0,
    val currentCfi: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val chapterPositionMs: Long = 0L,
    val chapterDurationMs: Long = 0L,
    val bookPositionMs: Long = 0L,
    val bookDurationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerMinutesRemaining: Int = 0,
    val stopAfterCurrentChapter: Boolean = false,
    val readTranslated: Boolean = false,
    val spokenLanguageCode: String = "",
    val narrationEngineName: String = ""
)

/**
 * Transport and state delegate implemented by an external narration session manager
 * (such as `ReaderNarrationSessionManager`) so [PolyglotPlaybackService] and Media3
 * notification/lockscreen controls can drive playback in the background.
 */
interface ExternalNarrationDelegate {
    val externalState: StateFlow<ExternalNarrationState>
    fun play()
    fun pause()
    fun stop()
    fun seekToChapterPositionMs(positionMs: Long)
    fun seekByDeltaMs(deltaMs: Long)
    fun nextParagraph()
    fun previousParagraph()
    fun nextChapter()
    fun previousChapter()
    fun setPlaybackSpeed(speed: Float)
    fun onAudioBecomingNoisy()
}

/**
 * Identifies which playback subsystem currently owns the foreground [PolyglotPlaybackService] MediaSession.
 */
enum class ActivePlaybackSource {
    NONE,
    AUDIOBOOK,
    EBOOK_NARRATION
}

/**
 * Singleton coordinator ensuring mutual exclusion and seamless MediaSession handoff between
 * local audiobook playback ([AudiobookSessionManager]) and external ebook narration ([ExternalNarrationDelegate]).
 */
@Singleton
class PlaybackCoordinator @Inject constructor() {

    private val _activeSource = MutableStateFlow(ActivePlaybackSource.NONE)
    val activeSource: StateFlow<ActivePlaybackSource> = _activeSource.asStateFlow()

    private val _externalState = MutableStateFlow(ExternalNarrationState())
    val externalState: StateFlow<ExternalNarrationState> = _externalState.asStateFlow()

    var onPauseAudiobook: (() -> Unit)? = null
    var onServiceCommand: ((action: String) -> Unit)? = null

    @Volatile
    private var externalDelegate: ExternalNarrationDelegate? = null

    fun getExternalDelegate(): ExternalNarrationDelegate? = externalDelegate

    fun registerExternalDelegate(delegate: ExternalNarrationDelegate) {
        externalDelegate = delegate
        val currentDelegateState = delegate.externalState.value
        if (currentDelegateState.isActiveSession) {
            _externalState.value = currentDelegateState
        }
    }

    /**
     * Switches the active playback source to [ActivePlaybackSource.AUDIOBOOK], pausing any active
     * external narration first, and notifies [PolyglotPlaybackService].
     */
    fun activateAudiobook() {
        val delegate = externalDelegate
        val currentExt = _externalState.value
        val delegatePlaying = try {
            delegate?.externalState?.value?.isPlaying == true
        } catch (_: Throwable) {
            false
        }
        if (delegate != null && (currentExt.isPlaying || delegatePlaying)) {
            try {
                delegate.pause()
            } catch (_: Throwable) {
            }
        }
        _activeSource.value = ActivePlaybackSource.AUDIOBOOK
        onServiceCommand?.invoke(ACTION_ACTIVATE_AUDIOBOOK)
    }

    /**
     * Switches the active playback source to [ActivePlaybackSource.EBOOK_NARRATION], pauses any
     * playing audiobook, promotes [PolyglotPlaybackService] to foreground, and notifies the service.
     */
    fun activateExternalNarration(context: Context?) {
        try {
            onPauseAudiobook?.invoke()
        } catch (_: Throwable) {
        }
        _activeSource.value = ActivePlaybackSource.EBOOK_NARRATION
        if (context != null) {
            try {
                val intent = Intent(context, PolyglotPlaybackService::class.java).apply {
                    action = PolyglotPlaybackService.ACTION_START_EXTERNAL_NARRATION
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Throwable) {
            }
        }
        onServiceCommand?.invoke(PolyglotPlaybackService.ACTION_START_EXTERNAL_NARRATION)
    }

    fun updateExternalState(state: ExternalNarrationState) {
        _externalState.value = state
        onServiceCommand?.invoke(ACTION_EXTERNAL_STATE_UPDATED)
    }

    fun releaseExternalNarration() {
        _externalState.value = ExternalNarrationState()
        if (_activeSource.value == ActivePlaybackSource.EBOOK_NARRATION) {
            _activeSource.value = ActivePlaybackSource.NONE
        }
        onServiceCommand?.invoke(ACTION_RELEASE_EXTERNAL_NARRATION)
    }

    companion object {
        const val ACTION_ACTIVATE_AUDIOBOOK =
            "io.github.danprav04.polyglotbooks.action.ACTIVATE_AUDIOBOOK"
        const val ACTION_EXTERNAL_STATE_UPDATED =
            "io.github.danprav04.polyglotbooks.action.EXTERNAL_STATE_UPDATED"
        const val ACTION_RELEASE_EXTERNAL_NARRATION =
            "io.github.danprav04.polyglotbooks.action.RELEASE_EXTERNAL_NARRATION"
    }
}
