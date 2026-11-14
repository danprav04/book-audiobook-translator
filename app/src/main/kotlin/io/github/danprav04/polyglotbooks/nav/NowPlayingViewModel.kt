package io.github.danprav04.polyglotbooks.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danprav04.polyglotbooks.core.playback.ActivePlaybackSource
import io.github.danprav04.polyglotbooks.core.playback.AudiobookServiceState
import io.github.danprav04.polyglotbooks.core.playback.AudiobookSessionManager
import io.github.danprav04.polyglotbooks.core.playback.ExternalNarrationState
import io.github.danprav04.polyglotbooks.core.playback.PlaybackCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI snapshot for the global Mini-Player bar rendered on top-level tabs.
 */
data class NowPlayingUiState(
    val isVisible: Boolean = false,
    val source: ActivePlaybackSource = ActivePlaybackSource.NONE,
    val bookId: String = "",
    val title: String = "",
    val subtitle: String = "",
    val coverPath: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val progressFraction: Float = 0f,
    val isAudiobook: Boolean = false
)

/**
 * Coordinates the global Mini-Player state across both local audiobook playback
 * ([AudiobookSessionManager]) and background ebook narration ([PlaybackCoordinator]).
 */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playbackCoordinator: PlaybackCoordinator,
    private val audiobookSessionManager: AudiobookSessionManager
) : ViewModel() {

    private val dismissedAudiobookBookId = MutableStateFlow<String?>(null)
    private var lastAudiobookPlaying = false

    val uiState: StateFlow<NowPlayingUiState> = combine(
        playbackCoordinator.activeSource,
        playbackCoordinator.externalState,
        audiobookSessionManager.state,
        dismissedAudiobookBookId
    ) { activeSource, extState, abState, dismissedBookId ->
        if (abState.isPlaying && !lastAudiobookPlaying) {
            dismissedAudiobookBookId.value = null
        }
        lastAudiobookPlaying = abState.isPlaying

        buildUiState(
            activeSource = activeSource,
            extState = extState,
            abState = abState,
            dismissedBookId = if (abState.isPlaying) null else dismissedBookId
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = NowPlayingUiState()
    )

    fun togglePlayPause() {
        val current = uiState.value
        when (current.source) {
            ActivePlaybackSource.EBOOK_NARRATION -> {
                val delegate = playbackCoordinator.getExternalDelegate()
                if (current.isPlaying) {
                    delegate?.pause()
                } else {
                    delegate?.play()
                }
            }
            ActivePlaybackSource.AUDIOBOOK -> {
                audiobookSessionManager.togglePlayPause()
            }
            ActivePlaybackSource.NONE -> Unit
        }
    }

    fun stopAndDismiss() {
        val current = uiState.value
        when (current.source) {
            ActivePlaybackSource.EBOOK_NARRATION -> {
                playbackCoordinator.getExternalDelegate()?.stop()
                if (playbackCoordinator.externalState.value.isActiveSession) {
                    playbackCoordinator.releaseExternalNarration()
                }
            }
            ActivePlaybackSource.AUDIOBOOK -> {
                audiobookSessionManager.pause()
                if (current.bookId.isNotBlank()) {
                    dismissedAudiobookBookId.value = current.bookId
                }
            }
            ActivePlaybackSource.NONE -> Unit
        }
    }

    private fun buildUiState(
        activeSource: ActivePlaybackSource,
        extState: ExternalNarrationState,
        abState: AudiobookServiceState,
        dismissedBookId: String?
    ): NowPlayingUiState {
        val showExternal = extState.isActiveSession &&
            extState.bookId.isNotBlank() &&
            (activeSource == ActivePlaybackSource.EBOOK_NARRATION ||
                (activeSource == ActivePlaybackSource.NONE && !abState.isPlaying))

        if (showExternal) {
            val chapterLabel = extState.chapterTitle.ifBlank {
                if (extState.chapterCount > 0) {
                    "Chapter ${extState.chapterIndex + 1} of ${extState.chapterCount}"
                } else {
                    extState.author
                }
            }
            val modeLabel = if (extState.readTranslated && extState.spokenLanguageCode.isNotBlank()) {
                "${extState.spokenLanguageCode.uppercase()} Narration"
            } else {
                "Ebook Narration"
            }
            val subtitle = when {
                chapterLabel.isNotBlank() -> "$chapterLabel • $modeLabel"
                else -> modeLabel
            }
            val progress = when {
                extState.bookDurationMs > 0L ->
                    (extState.bookPositionMs.toFloat() / extState.bookDurationMs.toFloat()).coerceIn(0f, 1f)
                extState.chapterDurationMs > 0L ->
                    (extState.chapterPositionMs.toFloat() / extState.chapterDurationMs.toFloat()).coerceIn(0f, 1f)
                else -> 0f
            }
            return NowPlayingUiState(
                isVisible = true,
                source = ActivePlaybackSource.EBOOK_NARRATION,
                bookId = extState.bookId,
                title = extState.bookTitle.ifBlank { "Ebook Narration" },
                subtitle = subtitle,
                coverPath = extState.coverPath,
                isPlaying = extState.isPlaying,
                isBuffering = extState.isBuffering,
                progressFraction = progress,
                isAudiobook = false
            )
        }

        val showAudiobook = abState.bookId.isNotBlank() &&
            abState.bookId != dismissedBookId &&
            (activeSource == ActivePlaybackSource.AUDIOBOOK || abState.isPlaying)

        if (showAudiobook) {
            val chapterTitle = abState.chapters.getOrNull(abState.currentChapterIndex)?.title
                ?.takeIf { it.isNotBlank() }
                ?: abState.author
            val subtitle = buildString {
                if (chapterTitle.isNotBlank()) {
                    append(chapterTitle)
                    append(" • ")
                }
                append(if (abState.isLiveTranslationActive) "Live ${abState.targetLanguage.code.uppercase()}" else "Audiobook")
            }
            val progress = if (abState.durationMs > 0L) {
                (abState.currentPositionMs.toFloat() / abState.durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            return NowPlayingUiState(
                isVisible = true,
                source = ActivePlaybackSource.AUDIOBOOK,
                bookId = abState.bookId,
                title = abState.bookTitle.ifBlank { "Audiobook" },
                subtitle = subtitle,
                coverPath = abState.coverUri,
                isPlaying = abState.isPlaying,
                isBuffering = abState.isLoading,
                progressFraction = progress,
                isAudiobook = true
            )
        }

        return NowPlayingUiState()
    }
}
