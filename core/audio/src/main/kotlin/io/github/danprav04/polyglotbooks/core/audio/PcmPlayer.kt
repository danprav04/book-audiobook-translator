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

    /**
     * Total PCM frames written in the current continuous playback session across format transitions
     * and [flush] calls until reset by [stop] or a same-format fresh [init].
     */
    val totalFramesWritten: Long
        get() = 0L

    /**
     * Monotonic frame count rendered by the playback head across format transitions and [flush]
     * calls, paired with [totalFramesWritten] for producer-consumer frame-span timelines.
     */
    fun playbackHeadPositionFrames(): Long = 0L
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
class AudioTrackPcmPlayer private constructor(
    private val adapterFactory: AudioTrackAdapterFactory,
    private val nanoTimeProvider: () -> Long,
    private val drainSleepMs: (Long) -> Unit,
    @Suppress("UNUSED_PARAMETER") internalMarker: Boolean
) : PcmPlayer {

    constructor(
        adapterFactory: AudioTrackAdapterFactory = AudioTrackAdapterFactory.Default
    ) : this(
        adapterFactory = adapterFactory,
        nanoTimeProvider = System::nanoTime,
        drainSleepMs = Thread::sleep,
        internalMarker = true
    )

    internal constructor(
        adapterFactory: AudioTrackAdapterFactory,
        nanoTimeProvider: () -> Long,
        drainSleepMs: (Long) -> Unit
    ) : this(
        adapterFactory = adapterFactory,
        nanoTimeProvider = nanoTimeProvider,
        drainSleepMs = drainSleepMs,
        internalMarker = true
    )

    private val _currentPositionMs = MutableStateFlow(0L)
    override val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val lock = Any()

    @Volatile
    private var adapter: AudioTrackAdapter? = null

    @Volatile
    private var initialized = false

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

    private var formatEpoch = 0L

    @Volatile
    private var monotonicFramesWritten = 0L

    private var headFrameOffset = 0L
    private var trackFramesWritten = 0L
    private var maxObservedTrackHeadFrames = 0L
    private var sessionMsOffset = 0L

    // Fallback wall-clock pacing state used when no AudioTrackAdapter is available (e.g. JVM tests).
    private var fallbackTrackPlayedFrames = 0.0
    private var fallbackLastTickNs = 0L

    /** True when a real AudioTrack backs this player. */
    val isAudioOutputAvailable: Boolean
        get() = adapter != null

    val isPlaying: Boolean
        get() = isPlayingState

    val playbackSpeed: Float
        get() = speed

    val volumeLevel: Float
        get() = volume

    override val totalFramesWritten: Long
        get() = synchronized(lock) { monotonicFramesWritten }

    override fun playbackHeadPositionFrames(): Long =
        synchronized(lock) {
            refreshPositionLocked(nanoTimeProvider()).first
        }

    override fun init(sampleRate: Int, channels: Int) {
        val normRate = if (sampleRate > 0) sampleRate else 24000
        val normChannels = maxOf(1, channels)

        var drainAdapter: AudioTrackAdapter? = null
        var drainSampleRate = normRate
        var drainTargetFrames = 0L
        var capturedEpoch = 0L

        synchronized(lock) {
            val isFormatSwitch = initialized && (normRate != this.sampleRate || normChannels != this.channels)
            if (!isFormatSwitch) {
                formatEpoch++
                releaseAdapterLocked()
                this.sampleRate = normRate
                this.channels = normChannels
                monotonicFramesWritten = 0L
                headFrameOffset = 0L
                trackFramesWritten = 0L
                maxObservedTrackHeadFrames = 0L
                sessionMsOffset = 0L
                fallbackTrackPlayedFrames = 0.0
                fallbackLastTickNs = nanoTimeProvider()
                _currentPositionMs.value = 0L
                initialized = true
                adapter = createConfiguredAdapter(normRate, normChannels)
                return
            }

            capturedEpoch = formatEpoch
            val nowNs = nanoTimeProvider()
            if (adapter == null) {
                advanceFallbackLocked(nowNs)
            }
            val played = readCurrentTrackPlayedFramesLocked(nowNs)
            if (isPlayingState && trackFramesWritten > played) {
                drainAdapter = adapter
                drainSampleRate = this.sampleRate
                drainTargetFrames = trackFramesWritten
            }
        }

        if (drainTargetFrames > 0L) {
            drainBufferedFrames(
                expectedAdapter = drainAdapter,
                trackSampleRate = drainSampleRate,
                targetFrames = drainTargetFrames,
                expectedEpoch = capturedEpoch
            )
        }

        synchronized(lock) {
            // Abort format switch only if stop()/release()/another init() superseded us while draining.
            if (!initialized || formatEpoch != capturedEpoch) {
                return
            }
            val nowNs = nanoTimeProvider()
            if (adapter == null) {
                advanceFallbackLocked(nowNs)
            }
            val oldPlayed = readCurrentTrackPlayedFramesLocked(nowNs)
            sessionMsOffset += oldPlayed * 1000L / this.sampleRate
            headFrameOffset = monotonicFramesWritten
            trackFramesWritten = 0L
            maxObservedTrackHeadFrames = 0L
            fallbackTrackPlayedFrames = 0.0
            fallbackLastTickNs = nowNs
            _currentPositionMs.value = sessionMsOffset

            formatEpoch++
            releaseAdapterLocked()
            this.sampleRate = normRate
            this.channels = normChannels
            adapter = createConfiguredAdapter(normRate, normChannels)
        }
    }

    override fun write(chunk: PcmChunk): Int {
        if (chunk.data.isEmpty()) return 0

        val (track, frameBytes, epochAtStart) = synchronized(lock) {
            val fb = channels * 2
            val currentTrack = adapter
            if (currentTrack == null) {
                // Tick fallback clock against prior buffered frames before queueing new ones so idle time
                // before write() never consumes the newly written chunk.
                val nowNs = nanoTimeProvider()
                advanceFallbackLocked(nowNs)
                val alignedSize = chunk.data.size - (chunk.data.size % fb)
                val framesAdded = (alignedSize / fb).toLong()
                if (framesAdded > 0L) {
                    trackFramesWritten += framesAdded
                    monotonicFramesWritten += framesAdded
                }
                refreshPositionLocked(nowNs)
                return alignedSize
            }
            Triple(currentTrack, fb, formatEpoch)
        }

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

        synchronized(lock) {
            if (formatEpoch == epochAtStart && offset > 0) {
                val framesAdded = (offset / frameBytes).toLong()
                trackFramesWritten += framesAdded
                monotonicFramesWritten += framesAdded
            }
            refreshPositionLocked(nanoTimeProvider())
        }
        return offset
    }

    override fun play() {
        synchronized(lock) {
            fallbackLastTickNs = nanoTimeProvider()
            isPlayingState = true
            adapter?.let { safely { it.play() } }
        }
    }

    override fun pause() {
        synchronized(lock) {
            val nowNs = nanoTimeProvider()
            if (adapter == null) {
                advanceFallbackLocked(nowNs)
            }
            isPlayingState = false
            adapter?.let { safely { it.pause() } }
            refreshPositionLocked(nowNs)
        }
    }

    override fun flush() {
        synchronized(lock) {
            formatEpoch++
            adapter?.let { track ->
                // AudioTrack.flush() only works while paused/stopped.
                safely { track.pause() }
                safely { track.flush() }
                if (isPlayingState) safely { track.play() }
            }
            headFrameOffset = monotonicFramesWritten
            trackFramesWritten = 0L
            maxObservedTrackHeadFrames = 0L
            sessionMsOffset = 0L
            fallbackTrackPlayedFrames = 0.0
            fallbackLastTickNs = nanoTimeProvider()
            _currentPositionMs.value = 0L
        }
    }

    override fun stop() {
        synchronized(lock) {
            isPlayingState = false
            initialized = false
            formatEpoch++
            adapter?.let { track ->
                safely { track.pause() }
                safely { track.flush() }
                safely { track.stop() }
            }
            monotonicFramesWritten = 0L
            headFrameOffset = 0L
            trackFramesWritten = 0L
            maxObservedTrackHeadFrames = 0L
            sessionMsOffset = 0L
            fallbackTrackPlayedFrames = 0.0
            fallbackLastTickNs = nanoTimeProvider()
            _currentPositionMs.value = 0L
        }
    }

    override fun release() {
        synchronized(lock) {
            val nowNs = nanoTimeProvider()
            if (adapter == null) {
                advanceFallbackLocked(nowNs)
            }
            val played = readCurrentTrackPlayedFramesLocked(nowNs)
            headFrameOffset += played
            sessionMsOffset += played * 1000L / sampleRate
            trackFramesWritten = 0L
            maxObservedTrackHeadFrames = 0L
            fallbackTrackPlayedFrames = 0.0
            fallbackLastTickNs = nowNs

            isPlayingState = false
            initialized = false
            formatEpoch++
            releaseAdapterLocked()
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        synchronized(lock) {
            val nowNs = nanoTimeProvider()
            if (adapter == null) {
                advanceFallbackLocked(nowNs)
            }
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
        get() = synchronized(lock) {
            val nowNs = nanoTimeProvider()
            if (adapter == null) {
                advanceFallbackLocked(nowNs)
            }
            val played = readCurrentTrackPlayedFramesLocked(nowNs)
            val pendingFrames = (trackFramesWritten - played).coerceAtLeast(0L)
            pendingFrames * 1000L / sampleRate
        }

    /**
     * Re-reads the playback head and updates [currentPositionMs].
     */
    fun refreshPosition(): Long =
        synchronized(lock) {
            refreshPositionLocked(nanoTimeProvider()).second
        }

    private fun refreshPositionLocked(nowNs: Long): Pair<Long, Long> {
        if (adapter == null) {
            advanceFallbackLocked(nowNs)
        }
        val trackPlayed = readCurrentTrackPlayedFramesLocked(nowNs)
        val monotonicHead = headFrameOffset + trackPlayed
        val posMs = sessionMsOffset + (trackPlayed * 1000L / sampleRate)
        _currentPositionMs.value = posMs
        return monotonicHead to posMs
    }

    private fun advanceFallbackLocked(nowNs: Long) {
        if (!isPlayingState) {
            fallbackLastTickNs = nowNs
            return
        }
        val elapsedNs = (nowNs - fallbackLastTickNs).coerceAtLeast(0L)
        fallbackLastTickNs = nowNs
        if (elapsedNs > 0L && fallbackTrackPlayedFrames < trackFramesWritten.toDouble()) {
            val framesPerSecond = sampleRate.toDouble() * speed.toDouble()
            val deltaFrames = (elapsedNs.toDouble() / 1_000_000_000.0) * framesPerSecond
            fallbackTrackPlayedFrames = minOf(
                trackFramesWritten.toDouble(),
                fallbackTrackPlayedFrames + deltaFrames
            )
        }
    }

    private fun readCurrentTrackPlayedFramesLocked(nowNs: Long): Long {
        val track = adapter
        if (track == null) {
            val played = fallbackTrackPlayedFrames.toLong().coerceIn(0L, trackFramesWritten)
            if (played > maxObservedTrackHeadFrames) {
                maxObservedTrackHeadFrames = played
            }
            return maxObservedTrackHeadFrames
        }
        val raw = try {
            track.playbackHeadPositionFrames.coerceIn(0L, trackFramesWritten)
        } catch (_: Throwable) {
            trackFramesWritten
        }
        if (raw > maxObservedTrackHeadFrames) {
            maxObservedTrackHeadFrames = raw
        }
        return maxObservedTrackHeadFrames
    }

    private fun drainBufferedFrames(
        expectedAdapter: AudioTrackAdapter?,
        trackSampleRate: Int,
        targetFrames: Long,
        expectedEpoch: Long
    ) {
        var lastHead = synchronized(lock) {
            if (formatEpoch != expectedEpoch || !isPlayingState || adapter !== expectedAdapter) {
                return
            }
            val nowNs = nanoTimeProvider()
            if (adapter == null) {
                advanceFallbackLocked(nowNs)
            }
            readCurrentTrackPlayedFramesLocked(nowNs)
        }
        if (lastHead >= targetFrames) return

        val remainingFrames = targetFrames - lastHead
        val ratePerMs = (trackSampleRate.toDouble() * speed.toDouble()) / 1000.0
        val expectedDrainMs = if (ratePerMs > 0.0) {
            (remainingFrames.toDouble() / ratePerMs).toLong()
        } else {
            0L
        }
        val maxTotalNs = ((expectedDrainMs + 300L).coerceIn(100L, 3000L)) * 1_000_000L
        val maxStallMs = 150L
        val startNs = nanoTimeProvider()
        var prevNowNs = startNs
        var stalledMs = 0L

        for (step in 0 until MAX_DRAIN_POLL_ITERATIONS) {
            val nowNs = nanoTimeProvider()
            if (nowNs - startNs >= maxTotalNs) break

            val currentHead = synchronized(lock) {
                if (formatEpoch != expectedEpoch || !isPlayingState || adapter !== expectedAdapter) {
                    return
                }
                if (adapter == null) {
                    advanceFallbackLocked(nowNs)
                }
                readCurrentTrackPlayedFramesLocked(nowNs)
            }

            if (currentHead >= targetFrames) break
            if (currentHead > lastHead) {
                lastHead = currentHead
                stalledMs = 0L
            } else if (expectedAdapter != null || nowNs <= prevNowNs) {
                stalledMs += DRAIN_POLL_INTERVAL_MS
                if (stalledMs >= maxStallMs) {
                    break
                }
            }
            prevNowNs = nowNs
            drainSleepMs(DRAIN_POLL_INTERVAL_MS)
        }
    }

    private fun createConfiguredAdapter(sampleRate: Int, channels: Int): AudioTrackAdapter? =
        adapterFactory.create(sampleRate, channels)?.also { track ->
            safely { track.setVolume(volume) }
            if (speed != 1.0f) safely { track.setSpeed(speed) }
            if (isPlayingState) safely { track.play() }
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

    private companion object {
        const val DRAIN_POLL_INTERVAL_MS = 10L
        const val MAX_DRAIN_POLL_ITERATIONS = 400
    }
}
