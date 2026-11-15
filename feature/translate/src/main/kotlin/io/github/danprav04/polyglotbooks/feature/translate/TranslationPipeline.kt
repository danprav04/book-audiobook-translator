package io.github.danprav04.polyglotbooks.feature.translate

import io.github.danprav04.polyglotbooks.core.audio.AudioTrackPcmPlayer
import io.github.danprav04.polyglotbooks.core.audio.Chunker
import io.github.danprav04.polyglotbooks.core.audio.PcmPlayer
import io.github.danprav04.polyglotbooks.core.audio.PcmWaveSourceDecoder
import io.github.danprav04.polyglotbooks.core.audio.Resampler
import io.github.danprav04.polyglotbooks.core.audio.SourceDecoder
import io.github.danprav04.polyglotbooks.core.audio.StreamingLinearResampler
import io.github.danprav04.polyglotbooks.core.audio.TranslatedCache
import io.github.danprav04.polyglotbooks.core.data.cache.TranslatedCacheIndex
import io.github.danprav04.polyglotbooks.core.gemini.DefaultLiveTranslateClient
import io.github.danprav04.polyglotbooks.core.gemini.LiveTranslateClient
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * State representing live translation pipeline health and progress.
 */
data class TranslationPipelineState(
    val isRunning: Boolean = false,
    val sourcePositionMs: Long = 0L,
    val bufferAheadMs: Long = 0L,
    val speedMultiplier: Float = 1.0f,
    val currentSourceSubtitle: String = "",
    val currentTranslatedSubtitle: String = "",
    val errorMessage: String? = null
)

/**
 * High-level orchestration pipeline coordinating source audio decoding, resampling to 16 kHz mono,
 * 100 ms / 3200 B chunking, look-ahead buffer pacing via [Pacer], WebSocket streaming to
 * [LiveTranslateClient], and translated PCM playback via [PcmPlayer] with optional segment caching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationPipeline(
    private val sourceDecoder: SourceDecoder = PcmWaveSourceDecoder(),
    private val resampler: Resampler = StreamingLinearResampler(),
    private val chunker: Chunker = Chunker(CHUNK_SIZE_BYTES),
    private val pacer: Pacer = Pacer(),
    private val timelineMapper: TimelineMapper = DefaultTimelineMapper(),
    private val liveTranslateClient: LiveTranslateClient = DefaultLiveTranslateClient(),
    private val pcmPlayer: PcmPlayer = AudioTrackPcmPlayer(),
    private val translatedCache: TranslatedCache? = null,
    private val translatedCacheIndex: TranslatedCacheIndex? = null,
    private val enablePacingDelay: Boolean = true
) {
    constructor(
        pacer: Pacer,
        timelineMapper: TimelineMapper = DefaultTimelineMapper()
    ) : this(
        sourceDecoder = PcmWaveSourceDecoder(),
        resampler = StreamingLinearResampler(),
        chunker = Chunker(CHUNK_SIZE_BYTES),
        pacer = pacer,
        timelineMapper = timelineMapper
    )

    private val _state = MutableStateFlow(TranslationPipelineState())
    val state: StateFlow<TranslationPipelineState> = _state.asStateFlow()

    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycleLock = Any()
    private val clientMutex = Mutex()
    private val pipelineGeneration = AtomicLong(0L)
    private var pipelineJob: Job? = null
    private var collectorJob: Job? = null

    @Volatile
    private var hasActiveClientSession = false

    private val currentPlaybackPositionMs = AtomicLong(0L)
    private val sourceDecodedCursorMs = AtomicLong(0L)
    private val receivedTranslatedDurationMs = AtomicLong(0L)
    private val isPaused = AtomicBoolean(false)

    @Volatile
    private var currentPlaybackSpeed: Float = 1.0f

    /**
     * Backwards-compatible state-only start overload.
     */
    fun start(bookId: String, targetLanguage: LanguageCode, startPositionMs: Long = 0L) {
        currentPlaybackPositionMs.set(startPositionMs)
        sourceDecodedCursorMs.set(startPositionMs)
        _state.value = _state.value.copy(
            isRunning = true,
            sourcePositionMs = startPositionMs,
            bufferAheadMs = 0L,
            speedMultiplier = pacer.maxSendSpeed,
            errorMessage = null
        )
    }

    /**
     * Starts the full end-to-end live translation pipeline on [scope].
     */
    fun start(
        scope: CoroutineScope,
        bookId: String,
        filePath: String,
        targetLanguage: LanguageCode,
        apiKey: String?,
        modelId: String = DefaultModels.get(ModelRole.LIVE_TRANSLATE),
        echoTargetLanguage: Boolean = false,
        startPositionMs: Long = 0L,
        playbackSpeed: Float = 1.0f
    ) {
        val runGen: Long
        val initialPosMs = startPositionMs.coerceAtLeast(0L)
        val shouldStopStaleClient: Boolean
        synchronized(lifecycleLock) {
            runGen = pipelineGeneration.incrementAndGet()
            cancelRunningJobsLocked()

            if (apiKey.isNullOrBlank()) {
                DiagnosticLogBuffer.record(
                    "TranslationPipeline",
                    "Cannot start live translation: API key missing",
                    level = "WARN"
                )
                _state.value = _state.value.copy(
                    isRunning = false,
                    sourcePositionMs = startPositionMs,
                    bufferAheadMs = 0L,
                    errorMessage = MISSING_API_KEY_MESSAGE
                )
                shouldStopStaleClient = hasActiveClientSession
            } else {
                DiagnosticLogBuffer.record(
                    "TranslationPipeline",
                    "Starting live translation pipeline model=$modelId lang=${targetLanguage.code} speed=${playbackSpeed}x startMs=$initialPosMs"
                )
                shouldStopStaleClient = false
                currentPlaybackPositionMs.set(initialPosMs)
                sourceDecodedCursorMs.set(initialPosMs)
                receivedTranslatedDurationMs.set(0L)
                isPaused.set(false)
                currentPlaybackSpeed = playbackSpeed.coerceIn(0.5f, 3.0f)

                resampler.reset()
                chunker.reset()
                pcmPlayer.setPlaybackSpeed(currentPlaybackSpeed)
                pcmPlayer.init(sampleRate = OUTPUT_SAMPLE_RATE, channels = 1)
                pcmPlayer.play()

                _state.value = TranslationPipelineState(
                    isRunning = true,
                    sourcePositionMs = initialPosMs,
                    bufferAheadMs = 0L,
                    speedMultiplier = pacer.calculateSpeedMultiplier(0L),
                    currentSourceSubtitle = "",
                    currentTranslatedSubtitle = "",
                    errorMessage = null
                )
            }
        }

        if (apiKey.isNullOrBlank()) {
            if (shouldStopStaleClient) {
                scheduleClientStop(runGen)
            }
            return
        }

        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val thisPipelineJob = coroutineContext[Job]
            val abortedByError = AtomicBoolean(false)
            val readyToCollect = AtomicBoolean(false)
            try {
                if (pipelineGeneration.get() != runGen) return@launch

                // 1. Check cache first
                val decodeStartMs = playCachedSegmentsIfAvailable(
                    runGen = runGen,
                    bookId = bookId,
                    targetLanguageCode = targetLanguage.code,
                    fromMs = initialPosMs
                )
                if (pipelineGeneration.get() != runGen) return@launch
                sourceDecodedCursorMs.set(decodeStartMs)

                // 2. Collect incoming translated audio + transcripts from LiveTranslateClient
                var activeOutputSampleRate = OUTPUT_SAMPLE_RATE
                var activeOutputChannels = 1
                val turnBuffer = ByteArrayOutputStream()
                var turnStartSourceMs = decodeStartMs
                val sourceTurnBuilder = StringBuilder()
                val targetTurnBuilder = StringBuilder()
                var resetSourceOnNextText = false
                var resetTargetOnNextText = false

                val newCollectorJob = launch(Dispatchers.Unconfined) {
                    liveTranslateClient.translationStream.collect { output ->
                        if (!readyToCollect.get() ||
                            pipelineGeneration.get() != runGen ||
                            abortedByError.get()
                        ) {
                            return@collect
                        }

                        if (!output.errorMessage.isNullOrBlank()) {
                            abortedByError.set(true)
                            val pausedWhenClosed = isPaused.get()
                            if (!pausedWhenClosed) {
                                DiagnosticLogBuffer.record(
                                    "TranslationPipeline",
                                    "Live translation error: ${output.errorMessage}",
                                    level = "ERROR"
                                )
                            }
                            _state.update {
                                it.copy(
                                    isRunning = false,
                                    errorMessage = if (pausedWhenClosed) null else output.errorMessage
                                )
                            }
                            try {
                                sourceDecoder.release()
                            } catch (_: Throwable) {
                            }
                            try {
                                pcmPlayer.stop()
                            } catch (_: Throwable) {
                            }
                            scheduleClientStop(runGen)
                            thisPipelineJob?.cancel()
                            return@collect
                        }

                        val newSource = output.sourceTranscript?.takeIf { it.isNotBlank() }
                        val newTarget = output.targetTranscript?.takeIf { it.isNotBlank() }
                        if (newSource != null || newTarget != null) {
                            val mergedSource = if (newSource != null) {
                                val reset = resetSourceOnNextText
                                resetSourceOnNextText = false
                                mergeSubtitleDelta(sourceTurnBuilder, newSource, reset)
                            } else {
                                null
                            }
                            val mergedTarget = if (newTarget != null) {
                                val reset = resetTargetOnNextText
                                resetTargetOnNextText = false
                                mergeSubtitleDelta(targetTurnBuilder, newTarget, reset)
                            } else {
                                null
                            }
                            _state.update { prev ->
                                prev.copy(
                                    currentSourceSubtitle = mergedSource ?: prev.currentSourceSubtitle,
                                    currentTranslatedSubtitle = mergedTarget ?: prev.currentTranslatedSubtitle
                                )
                            }
                        }

                        output.audioChunk?.let { chunk ->
                            if (chunk.data.isNotEmpty()) {
                                val chunkRate = if (chunk.sampleRate > 0) chunk.sampleRate else OUTPUT_SAMPLE_RATE
                                val chunkChannels = maxOf(1, chunk.channels)
                                if (chunkRate != activeOutputSampleRate || chunkChannels != activeOutputChannels) {
                                    activeOutputSampleRate = chunkRate
                                    activeOutputChannels = chunkChannels
                                    val speed = currentPlaybackSpeed
                                    pcmPlayer.init(activeOutputSampleRate, activeOutputChannels)
                                    if (kotlin.math.abs(speed - 1.0f) > 0.001f) {
                                        pcmPlayer.setPlaybackSpeed(speed)
                                    }
                                    if (!isPaused.get()) {
                                        pcmPlayer.play()
                                    }
                                }
                                pcmPlayer.write(chunk)
                                turnBuffer.write(chunk.data)

                                val bytesPerSec = maxOf(1, activeOutputSampleRate * activeOutputChannels * 2)
                                val chunkDurationMs = (chunk.data.size * 1000L) / bytesPerSec
                                val totalTranslatedMs = receivedTranslatedDurationMs.addAndGet(chunkDurationMs)
                                val sourcePos = sourceDecodedCursorMs.get()
                                timelineMapper.recordAnchor(sourcePos, totalTranslatedMs)
                                val bufferAhead = calculateBufferAheadMs(sourcePos)
                                updateBuffer(bufferAhead, sourcePos)
                            }
                        }

                        if (output.isTurnComplete) {
                            if (sourceTurnBuilder.isNotEmpty()) {
                                resetSourceOnNextText = true
                            }
                            if (targetTurnBuilder.isNotEmpty()) {
                                resetTargetOnNextText = true
                            }
                            if (turnBuffer.size() > 0) {
                                val endSourceMs = sourceDecodedCursorMs.get()
                                if (translatedCache != null && endSourceMs > turnStartSourceMs) {
                                    val pcmBytes = turnBuffer.toByteArray()
                                    try {
                                        translatedCache.putSegment(
                                            bookId = bookId,
                                            targetLanguage = targetLanguage.code,
                                            sourceStartMs = turnStartSourceMs,
                                            sourceEndMs = endSourceMs,
                                            pcmData = pcmBytes
                                        )
                                    } catch (_: Throwable) {
                                    }
                                }
                                turnBuffer.reset()
                                turnStartSourceMs = endSourceMs
                            }
                        }
                    }
                }

                val attachedCollector = synchronized(lifecycleLock) {
                    if (pipelineGeneration.get() == runGen) {
                        collectorJob = newCollectorJob
                        true
                    } else {
                        false
                    }
                }
                if (!attachedCollector) {
                    newCollectorJob.cancel()
                    return@launch
                }

                // 3. Connect LiveTranslateClient session (serialized with async stop() cleanup)
                readyToCollect.set(true)
                clientMutex.withLock {
                    if (pipelineGeneration.get() == runGen && !abortedByError.get()) {
                        hasActiveClientSession = true
                        liveTranslateClient.start(
                            apiKey = apiKey,
                            targetLanguageCode = targetLanguage.code,
                            echoTargetLanguage = echoTargetLanguage,
                            model = modelId
                        )
                    }
                }
                if (pipelineGeneration.get() != runGen || abortedByError.get()) return@launch

                // 4. Decode source audio -> resample to 16 kHz mono -> chunk 100 ms (3200 B) -> pace -> send
                var lastChunkSentWallMs = 0L
                sourceDecoder.decode(filePath = filePath, startPositionMs = decodeStartMs).collect { decodedChunk ->
                    if (pipelineGeneration.get() != runGen || abortedByError.get()) {
                        throw CancellationException("Pipeline stopped")
                    }
                    while (isPaused.get()) {
                        delay(PAUSE_POLL_MS)
                        lastChunkSentWallMs = 0L
                    }

                    val resampled = resampler.resample(
                        input = decodedChunk,
                        targetSampleRate = INPUT_SAMPLE_RATE,
                        targetChannels = 1
                    )
                    val frames = chunker.addBytes(resampled.data)
                    for (frame in frames) {
                        if (pipelineGeneration.get() != runGen || abortedByError.get()) {
                            throw CancellationException("Pipeline stopped")
                        }
                        while (isPaused.get()) {
                            delay(PAUSE_POLL_MS)
                            lastChunkSentWallMs = 0L
                        }

                        val frameStartMs = sourceDecodedCursorMs.get()
                        val frameEndMs = sourceDecodedCursorMs.addAndGet(CHUNK_DURATION_MS)
                        var bufferAhead = calculateBufferAheadMs(frameEndMs)
                        updateBuffer(bufferAhead, frameEndMs)

                        if (enablePacingDelay) {
                            while (isActive && pipelineGeneration.get() == runGen && !abortedByError.get()) {
                                if (isPaused.get()) {
                                    delay(PAUSE_POLL_MS)
                                    lastChunkSentWallMs = 0L
                                    continue
                                }
                                bufferAhead = calculateBufferAheadMs(frameEndMs)
                                updateBuffer(bufferAhead, frameEndMs)
                                val rawDelay = pacer.calculateDelayMs(
                                    chunkDurationMs = CHUNK_DURATION_MS,
                                    currentBufferAheadMs = bufferAhead
                                )
                                if (rawDelay < 0L) {
                                    // Backpressure limit reached: wait until playback drains below targetBufferAheadMs
                                    delay(PAUSE_POLL_MS)
                                    lastChunkSentWallMs = 0L
                                    continue
                                }
                                val speedFactor = currentPlaybackSpeed.coerceIn(0.5f, 3.0f)
                                val scaledDelay = (rawDelay / speedFactor).toLong()
                                val elapsedMs = if (lastChunkSentWallMs > 0L) {
                                    (System.currentTimeMillis() - lastChunkSentWallMs).coerceAtLeast(0L)
                                } else {
                                    0L
                                }
                                val waitMs = (scaledDelay - elapsedMs).coerceAtLeast(0L)
                                if (waitMs > 0L) {
                                    delay(waitMs)
                                }
                                break
                            }
                            lastChunkSentWallMs = System.currentTimeMillis()
                        }

                        if (pipelineGeneration.get() != runGen || abortedByError.get()) {
                            throw CancellationException("Pipeline stopped")
                        }
                        liveTranslateClient.sendAudio(
                            PcmChunk(
                                data = frame,
                                sampleRate = INPUT_SAMPLE_RATE,
                                channels = 1,
                                timestampMs = frameStartMs
                            )
                        )
                    }
                }

                if (pipelineGeneration.get() != runGen || abortedByError.get()) return@launch

                // Flush any remaining partial PCM bytes from the chunker
                chunker.flush()?.let { remainder ->
                    if (remainder.isNotEmpty() &&
                        pipelineGeneration.get() == runGen &&
                        !abortedByError.get()
                    ) {
                        val remainderDurationMs = (remainder.size * 1000L) / (INPUT_SAMPLE_RATE * 2)
                        val frameStartMs = sourceDecodedCursorMs.get()
                        val frameEndMs = sourceDecodedCursorMs.addAndGet(remainderDurationMs)
                        val bufferAhead = calculateBufferAheadMs(frameEndMs)
                        updateBuffer(bufferAhead, frameEndMs)

                        liveTranslateClient.sendAudio(
                            PcmChunk(
                                data = remainder,
                                sampleRate = INPUT_SAMPLE_RATE,
                                channels = 1,
                                timestampMs = frameStartMs
                            )
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (!abortedByError.get() && pipelineGeneration.get() == runGen && !isPaused.get()) {
                    DiagnosticLogBuffer.record(
                        "TranslationPipeline",
                        "Live translation pipeline exception",
                        level = "ERROR",
                        throwable = e
                    )
                    _state.update {
                        it.copy(
                            isRunning = false,
                            errorMessage = e.message ?: "Live translation pipeline failed"
                        )
                    }
                } else if (isPaused.get() && pipelineGeneration.get() == runGen) {
                    _state.update { it.copy(isRunning = false, errorMessage = null) }
                }
            }
        }
        synchronized(lifecycleLock) {
            if (pipelineGeneration.get() == runGen && !job.isCompleted) {
                pipelineJob = job
            }
        }
    }

    private suspend fun playCachedSegmentsIfAvailable(
        runGen: Long,
        bookId: String,
        targetLanguageCode: String,
        fromMs: Long
    ): Long {
        var cursorMs = fromMs
        val indexCoveredUntil = try {
            translatedCacheIndex?.coveredUntil(bookId, targetLanguageCode, fromMs) ?: fromMs
        } catch (_: Throwable) {
            fromMs
        }

        if (translatedCache != null) {
            var safetyCounter = 0
            while (safetyCounter++ < 10_000) {
                if (pipelineGeneration.get() != runGen) break
                while (enablePacingDelay && isPaused.get() && pipelineGeneration.get() == runGen) {
                    delay(PAUSE_POLL_MS)
                }
                if (pipelineGeneration.get() != runGen) break

                val segment = try {
                    translatedCache.findCachedRange(bookId, targetLanguageCode, cursorMs)
                } catch (_: Throwable) {
                    null
                } ?: break

                if (segment.sourceEndMs <= cursorMs) break
                val cachedPcm = try {
                    translatedCache.getSegmentPcm(segment)
                } catch (_: Throwable) {
                    null
                }
                if (cachedPcm == null || cachedPcm.isEmpty()) break

                val segSpanMs = (segment.sourceEndMs - segment.sourceStartMs).coerceAtLeast(1L)
                val trimOffsetMs = (cursorMs - segment.sourceStartMs).coerceIn(0L, segSpanMs)
                val rawByteOffset = ((cachedPcm.size.toDouble() * trimOffsetMs) / segSpanMs).toInt()
                val alignedByteOffset = (rawByteOffset / 2) * 2
                val pcmToWrite = if (alignedByteOffset in 1 until cachedPcm.size) {
                    cachedPcm.copyOfRange(alignedByteOffset, cachedPcm.size)
                } else {
                    cachedPcm
                }

                if (enablePacingDelay) {
                    while (pipelineGeneration.get() == runGen &&
                        pacer.calculateDelayMs(CHUNK_DURATION_MS, calculateBufferAheadMs(cursorMs)) < 0L
                    ) {
                        delay(PAUSE_POLL_MS)
                    }
                    if (pipelineGeneration.get() != runGen) break
                }

                pcmPlayer.write(
                    PcmChunk(
                        data = pcmToWrite,
                        sampleRate = OUTPUT_SAMPLE_RATE,
                        channels = 1,
                        timestampMs = cursorMs
                    )
                )
                val segDurationMs = (segment.sourceEndMs - cursorMs).coerceAtLeast(0L)
                receivedTranslatedDurationMs.addAndGet(segDurationMs)
                cursorMs = segment.sourceEndMs
                sourceDecodedCursorMs.set(cursorMs)
                val bufferAhead = calculateBufferAheadMs(cursorMs)
                updateBuffer(bufferAhead, cursorMs)
            }
        } else if (indexCoveredUntil > cursorMs) {
            cursorMs = indexCoveredUntil
            val bufferAhead = calculateBufferAheadMs(cursorMs)
            updateBuffer(bufferAhead, cursorMs)
        }

        return cursorMs
    }

    private fun mergeSubtitleDelta(
        builder: StringBuilder,
        delta: String,
        resetBeforeAppend: Boolean
    ): String {
        if (resetBeforeAppend) {
            builder.clear()
        }
        if (builder.isEmpty()) {
            builder.append(delta.trimStart())
        } else {
            val current = builder.toString()
            if (delta.startsWith(current)) {
                builder.clear()
                builder.append(delta)
            } else {
                builder.append(delta)
            }
        }
        if (builder.length > MAX_SUBTITLE_CHARS) {
            val trimmed = builder.substring(builder.length - MAX_SUBTITLE_CHARS)
            val firstSpace = trimmed.indexOf(' ')
            val cleanTail = if (firstSpace in 0 until 24) trimmed.substring(firstSpace + 1) else trimmed
            builder.clear()
            builder.append("...").append(cleanTail)
        }
        return builder.toString().trim()
    }

    private fun calculateBufferAheadMs(sourceCursorMs: Long): Long {
        val playbackPos = currentPlaybackPositionMs.get()
        val sourceLookahead = (sourceCursorMs - playbackPos).coerceAtLeast(0L)
        val translatedLookahead = (receivedTranslatedDurationMs.get() - pcmPlayer.currentPositionMs.value).coerceAtLeast(0L)
        return maxOf(sourceLookahead, translatedLookahead)
    }

    fun updatePlaybackPosition(positionMs: Long) {
        val clampedPos = positionMs.coerceAtLeast(0L)
        currentPlaybackPositionMs.set(clampedPos)
        val sourceCursor = maxOf(sourceDecodedCursorMs.get(), clampedPos)
        val bufferAhead = (sourceCursor - clampedPos).coerceAtLeast(0L)
        updateBuffer(bufferAhead, sourceCursor)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        currentPlaybackSpeed = clamped
        pcmPlayer.setPlaybackSpeed(clamped)
    }

    fun pauseAudio() {
        isPaused.set(true)
        pcmPlayer.pause()
    }

    fun resumeAudio() {
        isPaused.set(false)
        pcmPlayer.play()
    }

    fun updateBuffer(currentBufferAheadMs: Long, sourcePosMs: Long) {
        val speed = pacer.calculateSpeedMultiplier(currentBufferAheadMs)
        _state.update {
            it.copy(
                sourcePositionMs = sourcePosMs,
                bufferAheadMs = currentBufferAheadMs,
                speedMultiplier = speed
            )
        }
    }

    fun updateSubtitles(sourceTranscript: String, translatedTranscript: String) {
        _state.update {
            it.copy(
                currentSourceSubtitle = sourceTranscript,
                currentTranslatedSubtitle = translatedTranscript
            )
        }
    }

    fun stop() {
        val stopGen: Long
        synchronized(lifecycleLock) {
            stopGen = pipelineGeneration.incrementAndGet()
            isPaused.set(false)
            cancelRunningJobsLocked()
            try {
                sourceDecoder.release()
            } catch (_: Throwable) {
            }
            try {
                pcmPlayer.stop()
            } catch (_: Throwable) {
            }
            _state.update {
                it.copy(
                    isRunning = false,
                    bufferAheadMs = 0L,
                    currentSourceSubtitle = "",
                    currentTranslatedSubtitle = ""
                )
            }
        }
        scheduleClientStop(stopGen)
    }

    private fun scheduleClientStop(targetGen: Long) {
        cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
            clientMutex.withLock {
                if (pipelineGeneration.get() == targetGen) {
                    hasActiveClientSession = false
                    try {
                        liveTranslateClient.stop()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private fun cancelRunningJobsLocked() {
        collectorJob?.cancel()
        collectorJob = null
        pipelineJob?.cancel()
        pipelineJob = null
    }

    companion object {
        const val INPUT_SAMPLE_RATE = 16000
        const val OUTPUT_SAMPLE_RATE = 24000
        const val CHUNK_DURATION_MS = 100L
        const val CHUNK_SIZE_BYTES = 3200
        private const val PAUSE_POLL_MS = 50L
        private const val MAX_SUBTITLE_CHARS = 220

        const val MISSING_API_KEY_MESSAGE =
            "Gemini API key is missing. Add it in Settings to use Live AI Translation."
    }
}
