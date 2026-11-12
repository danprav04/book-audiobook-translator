package io.github.danprav04.polyglotbooks.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controller for local audiobook playback wrapping ExoPlayer with speed control,
 * auto-rewind on pause/resume after > 1 min, and progress tracking.
 */
class AudiobookPlaybackController(
    private val exoPlayer: ExoPlayer,
    private val scope: CoroutineScope,
    private val onProgressChanged: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> }
) {
    private var lastPauseTimestamp: Long = 0L

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    val sleepTimer = SleepTimer(scope) {
        pause()
    }

    init {
        exoPlayer.playbackParameters = PlaybackParameters(1.0f)
    }

    fun prepareAndPlay(mediaItems: List<MediaItem>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        exoPlayer.setMediaItems(mediaItems, startIndex, startPositionMs)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun play() {
        val now = System.currentTimeMillis()
        if (lastPauseTimestamp > 0L && (now - lastPauseTimestamp) > 60_000L) {
            // Auto-rewind 2 seconds on resume after pause > 1 min
            val target = (exoPlayer.currentPosition - 2000L).coerceAtLeast(0L)
            exoPlayer.seekTo(target)
        }
        exoPlayer.play()
    }

    fun pause() {
        lastPauseTimestamp = System.currentTimeMillis()
        exoPlayer.pause()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
    }

    fun seekToChapter(chapterIndex: Int, positionMs: Long = 0L) {
        exoPlayer.seekTo(chapterIndex, positionMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        _playbackSpeed.value = clamped
        exoPlayer.playbackParameters = PlaybackParameters(clamped)
    }

    fun release() {
        sleepTimer.cancel()
        exoPlayer.release()
    }

    val isPlaying: Boolean
        get() = exoPlayer.isPlaying

    val currentPositionMs: Long
        get() = exoPlayer.currentPosition

    val durationMs: Long
        get() = if (exoPlayer.duration == androidx.media3.common.C.TIME_UNSET) 0L else exoPlayer.duration
}
