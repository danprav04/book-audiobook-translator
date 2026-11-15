package io.github.danprav04.polyglotbooks.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
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

    /**
     * Signals the end of a spoken utterance/turn so real hardware outputs can apply a clean
     * micro fade-out and silence pad to avoid click artifacts on turn boundaries.
     */
    fun onUtteranceEnd() {}

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

    /**
     * True when this player reports monotonic [totalFramesWritten] and [playbackHeadPositionFrames]
     * progression suitable for real-time frame-span highlighting and timeline sync.
     */
    val supportsFrameTracking: Boolean
        get() = false
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

    /** Signals the end of an utterance so hardware adapters can smooth boundary transitions. */
    fun endUtterance() {}

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
            } catch (t: Throwable) {
                DiagnosticLogBuffer.record(
                    "AudioTrackPcmPlayer",
                    "AudioTrack init unavailable (rate=$sampleRate ch=$channels)",
                    level = "WARN",
                    throwable = t
                )
                null
            }
        }
    }
}

/**
 * Tracks hardware-written frame counts and non-speech padding intervals (boundary pre-rolls and
 * end-of-utterance silence tails) so [adjustedHeadFrames] progresses strictly monotonically along
 * actual speech frames with zero cumulative per-turn drift even when the next turn pre-buffers.
 */
internal class PaddingFrameTracker {
    private data class Span(
        val startRawFrame: Long,
        val frames: Long
    )

    private val lock = Any()
    private val spans = ArrayList<Span>()
    private var hardwareFramesWritten: Long = 0L

    fun recordSpeechFrames(frames: Long) {
        if (frames <= 0L) return
        synchronized(lock) {
            hardwareFramesWritten += frames
        }
    }

    fun recordPaddingFrames(frames: Long) {
        if (frames <= 0L) return
        synchronized(lock) {
            spans.add(Span(startRawFrame = hardwareFramesWritten, frames = frames))
            hardwareFramesWritten += frames
        }
    }

    fun adjustedHeadFrames(rawHeadFrame: Long): Long {
        if (rawHeadFrame <= 0L) return 0L
        synchronized(lock) {
            var renderedPadding = 0L
            for (i in 0 until spans.size) {
                val span = spans[i]
                if (rawHeadFrame <= span.startRawFrame) break
                val elapsed = rawHeadFrame - span.startRawFrame
                renderedPadding += if (elapsed >= span.frames) span.frames else elapsed
            }
            return (rawHeadFrame - renderedPadding).coerceAtLeast(0L)
        }
    }

    fun reset() {
        synchronized(lock) {
            spans.clear()
            hardwareFramesWritten = 0L
        }
    }
}

/**
 * [AudioTrackAdapter] backed by a streaming [AudioTrack] configured for speech media playback.
 */
class AndroidAudioTrackAdapter(sampleRate: Int, channels: Int) : AudioTrackAdapter {
    private val track: AudioTrack
    private val sampleRateHz: Int = sampleRate.coerceAtLeast(8_000)
    private val channelCount: Int = if (channels >= 2) 2 else 1
    private val bytesPerFrame: Int = channelCount * 2
    private val paddingTracker = PaddingFrameTracker()

    @Volatile
    private var hasCustomPlaybackParams = false

    @Volatile
    private var needsBoundaryFadeIn: Boolean = true

    private val lastFrameSamples = ShortArray(channelCount)

    init {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "Unsupported AudioTrack format: rate=$sampleRate channels=$channels" }
        // ~2s hardware buffer prevents AudioTrack underruns across Live turn bursts without delaying writes.
        val bytesPerSecond = sampleRate * channelCount * 2
        val bufferSize = maxOf(minBuffer * 2, bytesPerSecond * 2)

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

    override fun write(data: ByteArray, offset: Int, size: Int): Int {
        if (size <= 0) return 0
        val alignedSize = (size / bytesPerFrame) * bytesPerFrame
        if (alignedSize <= 0) return track.write(data, offset, size, AudioTrack.WRITE_BLOCKING)

        if (needsBoundaryFadeIn) {
            val preRollFrames = (sampleRateHz / 50).coerceAtLeast(1) // 20 ms silence pre-roll
            val preRollBytes = ByteArray(preRollFrames * bytesPerFrame)
            val preWritten = track.write(preRollBytes, 0, preRollBytes.size, AudioTrack.WRITE_BLOCKING)
            if (preWritten > 0) {
                paddingTracker.recordPaddingFrames(preWritten.toLong() / bytesPerFrame)
            }

            val fadeFrames = minOf((sampleRateHz / 250).coerceAtLeast(1), alignedSize / bytesPerFrame) // 4 ms ramp
            val faded = ByteArray(alignedSize)
            data.copyInto(faded, destinationOffset = 0, startIndex = offset, endIndex = offset + alignedSize)
            for (f in 0 until fadeFrames) {
                val gain = (f + 1).toFloat() / fadeFrames.toFloat()
                for (ch in 0 until channelCount) {
                    val i = (f * channelCount + ch) * 2
                    val lo = faded[i].toInt() and 0xFF
                    val hi = faded[i + 1].toInt()
                    val sample = ((hi shl 8) or lo).toShort()
                    val scaled = kotlin.math.round(sample.toFloat() * gain).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    faded[i] = (scaled and 0xFF).toByte()
                    faded[i + 1] = ((scaled shr 8) and 0xFF).toByte()
                }
            }
            needsBoundaryFadeIn = false
            val written = track.write(faded, 0, alignedSize, AudioTrack.WRITE_BLOCKING)
            if (written > 0) {
                paddingTracker.recordSpeechFrames(written.toLong() / bytesPerFrame)
                captureLastFrame(faded, 0, written)
            }
            return written
        }

        val written = track.write(data, offset, size, AudioTrack.WRITE_BLOCKING)
        if (written > 0) {
            paddingTracker.recordSpeechFrames(written.toLong() / bytesPerFrame)
            captureLastFrame(data, offset, written)
        }
        return written
    }

    override fun endUtterance() {
        val fadeFrames = (sampleRateHz / 125).coerceAtLeast(1) // 8 ms smooth taper to zero
        val padFrames = (sampleRateHz * 36 / 1000).coerceAtLeast(1) // 36 ms silence tail
        val totalFrames = fadeFrames + padFrames
        val tail = ByteArray(totalFrames * bytesPerFrame)
        val hasNonZeroTail = lastFrameSamples.any { it.toInt() != 0 }
        if (hasNonZeroTail) {
            for (f in 0 until fadeFrames) {
                val gain = 1.0f - ((f + 1).toFloat() / fadeFrames.toFloat())
                for (ch in 0 until channelCount) {
                    val sample = kotlin.math.round(lastFrameSamples[ch].toFloat() * gain).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    val i = (f * channelCount + ch) * 2
                    tail[i] = (sample and 0xFF).toByte()
                    tail[i + 1] = ((sample shr 8) and 0xFF).toByte()
                }
            }
        }
        val written = track.write(tail, 0, tail.size, AudioTrack.WRITE_BLOCKING)
        if (written > 0) {
            // Record the utterance tail as a padding span so hardware continues draining through
            // endFrame while speech-frame tracking plateaus without accumulating 44 ms drift/turn.
            paddingTracker.recordPaddingFrames(written.toLong() / bytesPerFrame)
        }
        lastFrameSamples.fill(0)
        needsBoundaryFadeIn = true
    }

    override fun play() = track.play()

    override fun pause() = track.pause()

    override fun flush() {
        track.flush()
        resetTurnBoundaryState()
    }

    override fun stop() {
        if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
        resetTurnBoundaryState()
    }

    private fun resetTurnBoundaryState() {
        paddingTracker.reset()
        lastFrameSamples.fill(0)
        needsBoundaryFadeIn = true
    }

    override fun release() {
        paddingTracker.reset()
        track.release()
    }

    override fun setSpeed(speed: Float): Boolean = try {
        val isUnity = kotlin.math.abs(speed - 1.0f) <= 0.001f
        if (isUnity && !hasCustomPlaybackParams) {
            // Keep the native 1.0x AudioTrack path untouched so Sonic WSOLA stays bypassed.
            true
        } else {
            // Time-stretch (pitch preserved) via PlaybackParams.
            track.playbackParams = PlaybackParams().setSpeed(if (isUnity) 1.0f else speed).setPitch(1.0f)
            hasCustomPlaybackParams = !isUnity
            true
        }
    } catch (_: Throwable) {
        false
    }

    override fun setVolume(volume: Float) {
        track.setVolume(volume)
    }

    override val playbackHeadPositionFrames: Long
        get() {
            val raw = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            return paddingTracker.adjustedHeadFrames(raw)
        }

    private fun captureLastFrame(data: ByteArray, offset: Int, writtenBytes: Int) {
        if (writtenBytes < bytesPerFrame) return
        val lastFrameStart = offset + ((writtenBytes / bytesPerFrame) - 1) * bytesPerFrame
        for (ch in 0 until channelCount) {
            val i = lastFrameStart + ch * 2
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt()
            lastFrameSamples[ch] = ((hi shl 8) or lo).toShort()
        }
    }
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
    private var appliedAdapterSpeed = 1.0f
    private var pendingTailBytes = ByteArray(4)
    private var pendingTailCount = 0

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

    override val supportsFrameTracking: Boolean
        get() = true

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
                pendingTailCount = 0
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
            pendingTailCount = 0
            fallbackTrackPlayedFrames = 0.0
            fallbackLastTickNs = nowNs
            _currentPositionMs.value = sessionMsOffset

            DiagnosticLogBuffer.record(
                "AudioTrackPcmPlayer",
                "Switching AudioTrack format from ${this.sampleRate}Hz/${this.channels}ch to ${normRate}Hz/${normChannels}ch"
            )
            formatEpoch++
            releaseAdapterLocked()
            this.sampleRate = normRate
            this.channels = normChannels
            adapter = createConfiguredAdapter(normRate, normChannels)
        }
    }

    override fun write(chunk: PcmChunk): Int {
        if (chunk.data.isEmpty()) return 0

        var prefixFrame: ByteArray? = null
        var chunkStartOffset = 0
        var chunkAlignedEnd = 0
        var trailingByteCount = 0
        var trailingStartOffset = 0
        var track: AudioTrackAdapter? = null
        var frameBytes = 2
        var epochAtStart = 0L

        synchronized(lock) {
            frameBytes = channels * 2
            if (pendingTailBytes.size < frameBytes) {
                val expanded = ByteArray(frameBytes)
                if (pendingTailCount > 0) {
                    pendingTailBytes.copyInto(expanded, endIndex = pendingTailCount)
                }
                pendingTailBytes = expanded
            }

            val currentTrack = adapter
            val tailCount = pendingTailCount
            val totalAvailable = tailCount + chunk.data.size
            val alignedTotal = totalAvailable - (totalAvailable % frameBytes)

            if (totalAvailable < frameBytes) {
                chunk.data.copyInto(pendingTailBytes, destinationOffset = tailCount)
                pendingTailCount = totalAvailable
                refreshPositionLocked(nanoTimeProvider())
                return chunk.data.size
            }

            if (tailCount > 0) {
                val neededFromChunk = frameBytes - tailCount
                val completedFrame = ByteArray(frameBytes)
                pendingTailBytes.copyInto(completedFrame, destinationOffset = 0, startIndex = 0, endIndex = tailCount)
                chunk.data.copyInto(completedFrame, destinationOffset = tailCount, startIndex = 0, endIndex = neededFromChunk)
                prefixFrame = completedFrame
                chunkStartOffset = neededFromChunk
                pendingTailCount = 0
            }

            val remainingInChunk = chunk.data.size - chunkStartOffset
            trailingByteCount = remainingInChunk % frameBytes
            chunkAlignedEnd = chunk.data.size - trailingByteCount
            trailingStartOffset = chunkAlignedEnd

            if (currentTrack == null) {
                // Tick fallback clock against prior buffered frames before queueing new ones so idle time
                // before write() never consumes the newly written chunk.
                val nowNs = nanoTimeProvider()
                advanceFallbackLocked(nowNs)
                val framesAdded = (alignedTotal / frameBytes).toLong()
                if (framesAdded > 0L) {
                    trackFramesWritten += framesAdded
                    monotonicFramesWritten += framesAdded
                }
                if (trailingByteCount > 0) {
                    chunk.data.copyInto(
                        pendingTailBytes,
                        destinationOffset = 0,
                        startIndex = trailingStartOffset,
                        endIndex = chunk.data.size
                    )
                    pendingTailCount = trailingByteCount
                }
                refreshPositionLocked(nowNs)
                return chunk.data.size
            }

            track = currentTrack
            epochAtStart = formatEpoch
        }

        val activeTrack = track ?: return 0
        val maxSubChunkBytes = frameBytes * WRITE_SUBCHUNK_FRAMES
        var totalBytesWritten = 0
        var uncreditedBytes = 0
        var abortedOrFailed = false

        fun writeSpan(data: ByteArray, start: Int, end: Int) {
            var offset = start
            while (offset < end) {
                val stillValid = synchronized(lock) {
                    formatEpoch == epochAtStart && adapter === activeTrack
                }
                if (!stillValid) {
                    abortedOrFailed = true
                    break
                }
                val stepSize = minOf(maxSubChunkBytes, end - offset)
                val result = try {
                    activeTrack.write(data, offset, stepSize)
                } catch (t: Throwable) {
                    DiagnosticLogBuffer.record(
                        "AudioTrackPcmPlayer",
                        "AudioTrack write threw exception (rate=$sampleRate ch=$channels offset=$offset/${chunk.data.size})",
                        level = "ERROR",
                        throwable = t
                    )
                    -1
                }
                if (result <= 0) {
                    if (result != 0) {
                        DiagnosticLogBuffer.record(
                            "AudioTrackPcmPlayer",
                            "AudioTrack write stopped early code=$result (rate=$sampleRate ch=$channels offset=$offset/${chunk.data.size})",
                            level = "WARN"
                        )
                    }
                    abortedOrFailed = true
                    break // stopped/flushed/released or error
                }
                offset += result
                totalBytesWritten += result
                uncreditedBytes += result
                val newFrames = (uncreditedBytes / frameBytes).toLong()
                uncreditedBytes %= frameBytes
                val continueWriting = synchronized(lock) {
                    if (formatEpoch == epochAtStart && adapter === activeTrack) {
                        if (newFrames > 0L) {
                            trackFramesWritten += newFrames
                            monotonicFramesWritten += newFrames
                        }
                        refreshPositionLocked(nanoTimeProvider())
                        true
                    } else {
                        false
                    }
                }
                if (!continueWriting) {
                    abortedOrFailed = true
                    break
                }
            }
        }

        prefixFrame?.let { frame ->
            writeSpan(frame, 0, frame.size)
        }
        if (!abortedOrFailed && chunkStartOffset < chunkAlignedEnd) {
            writeSpan(chunk.data, chunkStartOffset, chunkAlignedEnd)
        }

        synchronized(lock) {
            if (!abortedOrFailed && trailingByteCount > 0 && formatEpoch == epochAtStart && adapter === activeTrack) {
                chunk.data.copyInto(
                    pendingTailBytes,
                    destinationOffset = 0,
                    startIndex = trailingStartOffset,
                    endIndex = chunk.data.size
                )
                pendingTailCount = trailingByteCount
            }
            if (formatEpoch == epochAtStart) {
                refreshPositionLocked(nanoTimeProvider())
            }
        }
        return totalBytesWritten + if (!abortedOrFailed) trailingByteCount else 0
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
            pendingTailCount = 0
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
            pendingTailCount = 0
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
            pendingTailCount = 0
            fallbackTrackPlayedFrames = 0.0
            fallbackLastTickNs = nowNs

            isPlayingState = false
            initialized = false
            formatEpoch++
            releaseAdapterLocked()
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = normalizeSpeed(speed.coerceIn(0.5f, 3.0f))
        synchronized(lock) {
            val nowNs = nanoTimeProvider()
            if (adapter == null) {
                advanceFallbackLocked(nowNs)
            }
            this.speed = clamped
            adapter?.let { track ->
                if (kotlin.math.abs(clamped - appliedAdapterSpeed) > UNITY_SPEED_EPSILON) {
                    var applied = false
                    safely { applied = track.setSpeed(clamped) }
                    if (applied) {
                        appliedAdapterSpeed = clamped
                    }
                }
            }
        }
    }

    override fun setOriginalAudioVolume(volume: Float) {
        val clamped = volume.coerceIn(0.0f, 1.0f)
        synchronized(lock) {
            this.volume = clamped
            adapter?.let { safely { it.setVolume(clamped) } }
        }
    }

    override fun onUtteranceEnd() {
        val track = synchronized(lock) { adapter } ?: return
        safely { track.endUtterance() }
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
                    DiagnosticLogBuffer.record(
                        "AudioTrackPcmPlayer",
                        "AudioTrack drain stalled during format switch ($currentHead/$targetFrames frames)",
                        level = "WARN"
                    )
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
            if (kotlin.math.abs(speed - 1.0f) > UNITY_SPEED_EPSILON) {
                var applied = false
                safely { applied = track.setSpeed(speed) }
                appliedAdapterSpeed = if (applied) speed else 1.0f
                if (!applied) {
                    DiagnosticLogBuffer.record(
                        "AudioTrackPcmPlayer",
                        "AudioTrack rejected playback speed $speed",
                        level = "WARN"
                    )
                }
            } else {
                appliedAdapterSpeed = 1.0f
            }
            if (isPlayingState) safely { track.play() }
        }

    private fun releaseAdapterLocked() {
        adapter?.let { track ->
            safely { track.stop() }
            safely { track.release() }
        }
        adapter = null
        appliedAdapterSpeed = 1.0f
    }

    private fun normalizeSpeed(value: Float): Float =
        if (kotlin.math.abs(value - 1.0f) <= UNITY_SPEED_EPSILON) 1.0f else value

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val DRAIN_POLL_INTERVAL_MS = 10L
        const val MAX_DRAIN_POLL_ITERATIONS = 400
        const val WRITE_SUBCHUNK_FRAMES = 1024
        const val UNITY_SPEED_EPSILON = 0.001f
    }
}
