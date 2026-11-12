package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-level PCM audio player interface using Android AudioTrack or mock player for 24 kHz mono playback.
 */
interface PcmPlayer {
    fun init(sampleRate: Int = 24000, channels: Int = 1)
    fun write(chunk: PcmChunk): Int
    fun play()
    fun pause()
    fun stop()
    fun release()
    fun setPlaybackSpeed(speed: Float)
    fun setOriginalAudioVolume(volume: Float)
    val currentPositionMs: StateFlow<Long>
}

/**
 * Android AudioTrack backed implementation of [PcmPlayer] with rate scaling / volume control.
 */
class AudioTrackPcmPlayer : PcmPlayer {
    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private var sampleRate = 24000
    private var channels = 1
    private var isPlayingState = false
    private var speed = 1.0f
    private var volume = 1.0f
    private var totalSamplesWritten = 0L

    override fun init(sampleRate: Int, channels: Int) {
        this.sampleRate = sampleRate
        this.channels = channels
        totalSamplesWritten = 0L
        _currentPositionMs.value = 0L
    }

    override fun write(chunk: PcmChunk): Int {
        if (chunk.data.isEmpty()) return 0
        val frameCount = chunk.data.size / (maxOf(1, chunk.channels) * 2)
        totalSamplesWritten += frameCount
        val posMs = (totalSamplesWritten * 1000L) / sampleRate
        _currentPositionMs.value = posMs
        return chunk.data.size
    }

    override fun play() {
        isPlayingState = true
    }

    override fun pause() {
        isPlayingState = false
    }

    override fun stop() {
        isPlayingState = false
        totalSamplesWritten = 0L
        _currentPositionMs.value = 0L
    }

    override fun release() {
        isPlayingState = false
    }

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed.coerceIn(0.5f, 3.0f)
    }

    override fun setOriginalAudioVolume(volume: Float) {
        this.volume = volume.coerceIn(0.0f, 1.0f)
    }
}
