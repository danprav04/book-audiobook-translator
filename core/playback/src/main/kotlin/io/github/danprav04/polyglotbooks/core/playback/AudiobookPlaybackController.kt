package io.github.danprav04.polyglotbooks.core.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Controller for local audiobook playback wrapping ExoPlayer with speed control,
 * auto-rewind on pause/resume after > 1 min, and progress tracking.
 */
class AudiobookPlaybackController(
    private val exoPlayer: ExoPlayer,
    private val scope: CoroutineScope,
    private val onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> }
) {
    var lastPauseTimestamp: Long = 0L
        internal set

    var clock: () -> Long = { System.currentTimeMillis() }

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private var progressJob: Job? = null

    val sleepTimer = SleepTimer(scope) {
        pause()
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startProgressLoop()
            } else {
                stopProgressLoop()
                notifyProgress()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                notifyProgress()
            }
        }
    }

    init {
        try {
            exoPlayer.playbackParameters = PlaybackParameters(1.0f)
            exoPlayer.addListener(listener)
        } catch (_: Throwable) {
        }
    }

    fun prepareAndPlay(mediaItems: List<MediaItem>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        exoPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.play()
        notifyProgress()
    }

    fun restoreAndPrepare(
        mediaItems: List<MediaItem>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        speed: Float = 1.0f,
        savedPauseTimestamp: Long = 0L
    ) {
        lastPauseTimestamp = savedPauseTimestamp
        setPlaybackSpeed(speed)
        exoPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
        exoPlayer.prepare()
    }

    fun play() {
        val now = clock()
        if (lastPauseTimestamp > 0L && (now - lastPauseTimestamp) > 60_000L) {
            // Auto-rewind 2 seconds on resume after pause > 1 min
            val target = (exoPlayer.currentPosition - 2000L).coerceAtLeast(0L)
            exoPlayer.seekTo(target)
        }
        lastPauseTimestamp = 0L
        exoPlayer.play()
        notifyProgress()
    }

    fun pause() {
        lastPauseTimestamp = clock()
        exoPlayer.pause()
        notifyProgress()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        notifyProgress()
    }

    fun seekToChapter(chapterIndex: Int, positionMs: Long = 0L) {
        exoPlayer.seekTo(chapterIndex, positionMs)
        notifyProgress()
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        _playbackSpeed.value = clamped
        exoPlayer.playbackParameters = PlaybackParameters(clamped)
    }

    fun release() {
        stopProgressLoop()
        sleepTimer.cancel()
        try {
            exoPlayer.removeListener(listener)
        } catch (_: Throwable) {
        }
        exoPlayer.release()
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                notifyProgress()
                delay(500L)
            }
        }
    }

    private fun stopProgressLoop() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun notifyProgress() {
        try {
            onProgressChanged(currentPositionMs, durationMs)
        } catch (_: Throwable) {
        }
    }

    val isPlaying: Boolean
        get() = exoPlayer.isPlaying

    val currentPositionMs: Long
        get() = exoPlayer.currentPosition

    val durationMs: Long
        get() = if (exoPlayer.duration == C.TIME_UNSET) 0L else exoPlayer.duration
}
