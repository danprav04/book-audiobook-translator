package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.model.PcmChunk

/**
 * Low-level PCM audio player interface using Android AudioTrack for 24 kHz mono playback.
 */
interface PcmPlayer {
    /**
     * Initializes the player with sample rate (default 24 kHz for Gemini Live output) and channel count.
     */
    fun init(sampleRate: Int = 24000, channels: Int = 1)

    /**
     * Writes raw PCM audio chunk to the playout buffer.
     */
    fun write(chunk: PcmChunk): Int

    /**
     * Starts or resumes playback.
     */
    fun play()

    /**
     * Pauses playback.
     */
    fun pause()

    /**
     * Stops and flushes internal buffers.
     */
    fun stop()

    /**
     * Releases underlying AudioTrack resources.
     */
    fun release()

    /**
     * Sets playback speed multiplier (e.g. 0.75x to 2.0x).
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Current playback position in milliseconds.
     */
    val currentPositionMs: Long
}

/**
 * Stub implementation of [PcmPlayer].
 */
class StubPcmPlayer : PcmPlayer {
    private var isPlaying = false
    private var speed = 1.0f
    private var positionMs = 0L

    override fun init(sampleRate: Int, channels: Int) {
        positionMs = 0L
    }

    override fun write(chunk: PcmChunk): Int {
        // Calculate duration: samples = data.size / (channels * 2 bytes per sample)
        val samples = chunk.data.size / (chunk.channels * 2)
        val chunkDurationMs = (samples * 1000L) / chunk.sampleRate
        positionMs += chunkDurationMs
        return chunk.data.size
    }

    override fun play() {
        isPlaying = true
    }

    override fun pause() {
        isPlaying = false
    }

    override fun stop() {
        isPlaying = false
        positionMs = 0L
    }

    override fun release() {
        isPlaying = false
    }

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed
    }

    override val currentPositionMs: Long
        get() = positionMs
}
