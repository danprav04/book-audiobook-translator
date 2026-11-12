package io.github.danprav04.polyglotbooks.core.playback

/**
 * Controller interface for character-cast ebook narration playback.
 * Coordinates TTS audio playback and reports the currently active paragraph and sentence/line CFI.
 */
interface NarrationPlayer {
    /**
     * Prepares narration for a given book and chapter.
     */
    fun prepare(bookId: Long, chapterIndex: Int)

    /**
     * Starts or resumes speech narration.
     */
    fun play()

    /**
     * Pauses speech narration.
     */
    fun pause()

    /**
     * Seeks narration to a specific paragraph index or line ID.
     */
    fun seekToParagraph(paragraphIndex: Int)

    /**
     * Sets narration speed.
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Releases narration player resources.
     */
    fun release()

    val isPlaying: Boolean
    val currentParagraphIndex: Int
    val currentCfi: String?
}

/**
 * Stub implementation of [NarrationPlayer].
 */
class StubNarrationPlayer : NarrationPlayer {
    private var _isPlaying = false
    private var _currentParagraph = 0
    private var _speed = 1.0f

    override fun prepare(bookId: Long, chapterIndex: Int) {
        _currentParagraph = 0
    }

    override fun play() {
        _isPlaying = true
    }

    override fun pause() {
        _isPlaying = false
    }

    override fun seekToParagraph(paragraphIndex: Int) {
        _currentParagraph = paragraphIndex
    }

    override fun setPlaybackSpeed(speed: Float) {
        _speed = speed
    }

    override fun release() {
        _isPlaying = false
    }

    override val isPlaying: Boolean
        get() = _isPlaying

    override val currentParagraphIndex: Int
        get() = _currentParagraph

    override val currentCfi: String?
        get() = "/6/4[chap01]!/4/2/$_currentParagraph"
}
