package io.github.danprav04.polyglotbooks.core.playback

/**
 * Controller interface for translated audiobook playback.
 * Coordinates playout of the translated audio stream while tracking the source audiobook timeline.
 */
interface TranslatedPlayer {
    /**
     * Initializes playback for a given audiobook and target language.
     */
    fun prepare(bookId: Long, targetLanguageCode: String)

    /**
     * Starts or resumes playback.
     */
    fun play()

    /**
     * Pauses playback.
     */
    fun pause()

    /**
     * Seeks to a position in milliseconds on the source timeline.
     */
    fun seekTo(positionMs: Long)

    /**
     * Sets playback speed multiplier.
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Sets volume ducking for original audio (e.g. 0.0f for mute, 0.15f for 15% ducking).
     */
    fun setOriginalAudioDuckLevel(level: Float)

    /**
     * Releases player resources.
     */
    fun release()

    val isPlaying: Boolean
    val currentPositionMs: Long
    val durationMs: Long
    val bufferedPositionMs: Long
}

/**
 * Stub implementation of [TranslatedPlayer].
 */
class StubTranslatedPlayer : TranslatedPlayer {
    private var _isPlaying = false
    private var _positionMs = 0L
    private var _durationMs = 0L
    private var _bufferedMs = 0L
    private var _speed = 1.0f
    private var _duckLevel = 0.0f

    val playbackSpeed: Float
        get() = _speed

    val duckLevel: Float
        get() = _duckLevel

    override fun prepare(bookId: Long, targetLanguageCode: String) {
        _positionMs = 0L
        _durationMs = 3600_000L // 1 hour mock
        _bufferedMs = 30_000L   // 30s buffer mock
    }

    override fun play() {
        _isPlaying = true
    }

    override fun pause() {
        _isPlaying = false
    }

    override fun seekTo(positionMs: Long) {
        _positionMs = positionMs.coerceAtLeast(0L)
    }

    override fun setPlaybackSpeed(speed: Float) {
        _speed = speed.coerceIn(0.5f, 3.0f)
    }

    override fun setOriginalAudioDuckLevel(level: Float) {
        _duckLevel = level.coerceIn(0.0f, 1.0f)
    }

    override fun release() {
        _isPlaying = false
    }

    override val isPlaying: Boolean
        get() = _isPlaying

    override val currentPositionMs: Long
        get() = _positionMs

    override val durationMs: Long
        get() = _durationMs

    override val bufferedPositionMs: Long
        get() = _bufferedMs
}
