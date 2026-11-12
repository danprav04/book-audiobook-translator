package io.github.danprav04.polyglotbooks.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Low-level PCM audio player interface (Android AudioTrack in production) for 16-bit PCM playback.
 */
interface PcmPlayer {
    fun init(sampleRate: Int = 24000, channels: Int = 1)

    /**
     * Writes a PCM chunk. May block while the hardware buffer is full (natural backpressure).
     * Returns the number of bytes accepted.
     */
    fun write(chunk: PcmChunk): Int
    fun play()
    fun pause()
    fun stop()
    fun release()
    fun setPlaybackSpeed(speed: Float)

    /** Sets the output volume (0.0..1.0) of this player. */
    fun setOriginalAudioVolume(volume: Float)

    /** Playback-head position (ms of audio actually rendered since [init] / [stop] / [flush]). */
    val currentPositionMs: StateFlow<Long>

    /** Discards any audio written but not yet played. */
    fun flush() {}

    /** Milliseconds of audio written to the player but not yet rendered. */
    val bufferedMs: Long
        get() = 0L
}

/**
 * Thin abstraction over [android.media.AudioTrack] so [AudioTrackPcmPlayer] can be unit tested on
 * the JVM (where the Android framework is unavailable).
 */
interface AudioTrackAdapter {
    /** Blocking write; returns bytes written or a negative error code. */
    fun write(data: ByteArray, offset: Int, size: Int): Int
    fun play()
    fun pause()
    fun flush()
    fun stop()
    fun release()

    /** Returns false if the speed could not be applied. */
    fun setSpeed(speed: Float): Boolean
    fun setVolume(volume: Float)

    /** Frames rendered since the last flush/stop. */
    val playbackHeadPositionFrames: Long
}

/**
 * Creates an [AudioTrackAdapter] for the given format, or returns null if audio output is unavailable.
 */
fun interface AudioTrackAdapterFactory {
    fun create(sampleRate: Int, channels: Int): AudioTrackAdapter?

    companion object {
        /** Real [AudioTrack] backed factory. Returns null when AudioTrack can't be created (e.g. JVM tests). */
        val Default: AudioTrackAdapterFactory = AudioTrackAdapterFactory { sampleRate, channels ->
            try {
                AndroidAudioTrackAdapter(sampleRate, channels)
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/**
 * [AudioTrackAdapter] backed by a streaming [AudioTrack] configured for speech media playback.
 */
class AndroidAudioTrackAdapter(sampleRate: Int, channels: Int) : AudioTrackAdapter {
    private val track: AudioTrack

    init {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "Unsupported AudioTrack format: rate=$sampleRate channels=$channels" }
        // ~500 ms hardware buffer keeps writes smooth without adding much latency.
        val bytesPerSecond = sampleRate * (if (channels >= 2) 2 else 1) * 2
        val bufferSize = maxOf(minBuffer * 2, bytesPerSecond / 2)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AudioTrack failed to initialize")
        }
    }

    override fun write(data: ByteArray, offset: Int, size: Int): Int =
        track.write(data, offset, size, AudioTrack.WRITE_BLOCKING)

    override fun play() = track.play()

    override fun pause() = track.pause()

    override fun flush() = track.flush()

    override fun stop() {
        if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
    }

    override fun release() = track.release()

    override fun setSpeed(speed: Float): Boolean = try {
        // Time-stretch (pitch preserved) via PlaybackParams.
        track.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1.0f)
        true
    } catch (_: Throwable) {
        false
    }

    override fun setVolume(volume: Float) {
        track.setVolume(volume)
    }

    override val playbackHeadPositionFrames: Long
        get() = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
}

/**
 * Android [AudioTrack] backed implementation of [PcmPlayer] with speed scaling (0.5x..3.0x),
 * volume control, pause/flush/stop/release and playback-head position tracking.
 *
 * When no [AudioTrackAdapter] can be created (pure JVM unit tests, or a device without audio
 * output) it degrades gracefully to a silent sample counter so callers never crash.
 */
class AudioTrackPcmPlayer(
    private val adapterFactory: AudioTrackAdapterFactory = AudioTrackAdapterFactory.Default
) : PcmPlayer {
    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val lock = Any()

    @Volatile
    private var adapter: AudioTrackAdapter? = null

    @Volatile
    private var sampleRate = 24000

    @Volatile
    private var channels = 1

    @Volatile
    private var isPlayingState = false

    @Volatile
    private var speed = 1.0f

    @Volatile
    private var volume = 1.0f

    @Volatile
    private var framesWritten = 0L

    /** True when a real AudioTrack backs this player. */
    val isAudioOutputAvailable: Boolean
        get() = adapter != null

    val isPlaying: Boolean
        get() = isPlayingState

    val playbackSpeed: Float
        get() = speed

    val volumeLevel: Float
        get() = volume

    override fun init(sampleRate: Int, channels: Int) {
        synchronized(lock) {
            releaseAdapterLocked()
            this.sampleRate = if (sampleRate > 0) sampleRate else 24000
            this.channels = maxOf(1, channels)
            framesWritten = 0L
            _currentPositionMs.value = 0L
            adapter = adapterFactory.create(this.sampleRate, this.channels)?.also { track ->
                safely { track.setVolume(volume) }
                if (speed != 1.0f) safely { track.setSpeed(speed) }
                if (isPlayingState) safely { track.play() }
            }
        }
    }

    override fun write(chunk: PcmChunk): Int {
        if (chunk.data.isEmpty()) return 0
        val frameBytes = this.channels * 2
        val track = adapter
        val written = if (track != null) {
            var offset = 0
            val size = chunk.data.size - (chunk.data.size % frameBytes)
            while (offset < size) {
                val result = try {
                    track.write(chunk.data, offset, size - offset)
                } catch (_: Throwable) {
                    -1
                }
                if (result <= 0) break // stopped/flushed/released or error
                offset += result
            }
            offset
        } else {
            chunk.data.size
        }
        framesWritten += written / frameBytes
        refreshPosition()
        return written
    }

    override fun play() {
        synchronized(lock) {
            isPlayingState = true
            adapter?.let { safely { it.play() } }
        }
    }

    override fun pause() {
        synchronized(lock) {
            isPlayingState = false
            adapter?.let { safely { it.pause() } }
        }
        refreshPosition()
    }

    override fun flush() {
        synchronized(lock) {
            adapter?.let { track ->
                // AudioTrack.flush() only works while paused/stopped.
                safely { track.pause() }
                safely { track.flush() }
                if (isPlayingState) safely { track.play() }
            }
            framesWritten = 0L
            _currentPositionMs.value = 0L
        }
    }

    override fun stop() {
        synchronized(lock) {
            isPlayingState = false
            adapter?.let { track ->
                safely { track.pause() }
                safely { track.flush() }
                safely { track.stop() }
            }
            framesWritten = 0L
            _currentPositionMs.value = 0L
        }
    }

    override fun release() {
        synchronized(lock) {
            isPlayingState = false
            releaseAdapterLocked()
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        synchronized(lock) {
            this.speed = clamped
            adapter?.let { safely { it.setSpeed(clamped) } }
        }
    }

    override fun setOriginalAudioVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        synchronized(lock) {
            this.volume = clamped
            adapter?.let { safely { it.setVolume(clamped) } }
        }
    }

    override val bufferedMs: Long
        get() {
            val track = adapter ?: return 0L
            val played = try {
                track.playbackHeadPositionFrames
            } catch (_: Throwable) {
                framesWritten
            }
            val pendingFrames = (framesWritten - played).coerceAtLeast(0L)
            return pendingFrames * 1000L / sampleRate
        }

    /**
     * Re-reads the playback head and updates [currentPositionMs].
     */
    fun refreshPosition(): Long {
        val track = adapter
        val frames = if (track != null) {
            try {
                minOf(track.playbackHeadPositionFrames, framesWritten)
            } catch (_: Throwable) {
                framesWritten
            }
        } else {
            framesWritten
        }
        val posMs = frames * 1000L / sampleRate
        _currentPositionMs.value = posMs
        return posMs
    }

    private fun releaseAdapterLocked() {
        adapter?.let { track ->
            safely { track.stop() }
            safely { track.release() }
        }
        adapter = null
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }
}
