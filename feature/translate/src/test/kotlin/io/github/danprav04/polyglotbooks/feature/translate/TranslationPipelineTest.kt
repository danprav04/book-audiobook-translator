package io.github.danprav04.polyglotbooks.feature.translate

import io.github.danprav04.polyglotbooks.core.audio.AudioTrackPcmPlayer
import io.github.danprav04.polyglotbooks.core.audio.Chunker
import io.github.danprav04.polyglotbooks.core.audio.PcmPlayer
import io.github.danprav04.polyglotbooks.core.audio.SourceDecoder
import io.github.danprav04.polyglotbooks.core.audio.StreamingLinearResampler
import io.github.danprav04.polyglotbooks.core.audio.TranslatedCache
import io.github.danprav04.polyglotbooks.core.data.cache.TranslatedCacheIndex
import io.github.danprav04.polyglotbooks.core.data.db.TranslatedSegmentEntity
import io.github.danprav04.polyglotbooks.core.gemini.LiveTranslateClient
import io.github.danprav04.polyglotbooks.core.gemini.TranslationOutput
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TranslationPipelineTest {

    @Test
    fun testMissingApiKeySetsActionableErrorMessageImmediately() = runTest {
        val pipeline = TranslationPipeline()

        pipeline.start(
            scope = backgroundScope,
            bookId = "book-1",
            filePath = "/tmp/book.m4b",
            targetLanguage = LanguageCode.RUSSIAN,
            apiKey = "   ",
            modelId = "gemini-3.5-live-translate-preview"
        )

        val state = pipeline.state.value
        assertFalse(state.isRunning)
        assertEquals(TranslationPipeline.MISSING_API_KEY_MESSAGE, state.errorMessage)
    }

    @Test
    fun testEndToEndDecodeResampleChunkSendAndPlayTranslatedAudio() = runTest {
        // Fake decoder emitting 200ms of 16kHz mono PCM (6400 bytes -> two 3200B chunks)
        val fakeDecoder = object : SourceDecoder {
            var lastStartMs = -1L
            var released = false
            override fun decode(filePath: String, startPositionMs: Long, durationMs: Long): Flow<PcmChunk> = flow {
                lastStartMs = startPositionMs
                emit(PcmChunk(ByteArray(6400) { 1 }, sampleRate = 16000, channels = 1, timestampMs = startPositionMs))
            }

            override fun release() {
                released = true
            }
        }

        val sentChunks = mutableListOf<PcmChunk>()
        val outputFlow = MutableSharedFlow<TranslationOutput>(extraBufferCapacity = 16)
        val fakeClient = object : LiveTranslateClient {
            var startedModel: String? = null
            var startedLang: String? = null
            var stopped = false

            override val translationStream: Flow<TranslationOutput> = outputFlow.asSharedFlow()

            override suspend fun start(
                apiKey: String,
                targetLanguageCode: String,
                echoTargetLanguage: Boolean,
                model: String
            ) {
                startedLang = targetLanguageCode
                startedModel = model
            }

            override suspend fun sendAudio(chunk: PcmChunk) {
                sentChunks.add(chunk)
                if (sentChunks.size == 2) {
                    outputFlow.emit(
                        TranslationOutput(
                            audioChunk = PcmChunk(ByteArray(4800) { 7 }, sampleRate = 24000, channels = 1),
                            sourceTranscript = "Vulnerability is courage.",
                            targetTranscript = "Уязвимость — это смелость.",
                            isTurnComplete = true
                        )
                    )
                }
            }

            override suspend fun stop() {
                stopped = true
            }
        }

        val playedChunks = mutableListOf<PcmChunk>()
        val realPlayer = AudioTrackPcmPlayer { _, _ -> null }
        val spyPlayer = object : PcmPlayer by realPlayer {
            override fun write(chunk: PcmChunk): Int {
                playedChunks.add(chunk)
                return realPlayer.write(chunk)
            }
        }

        val cachedSegments = mutableListOf<TranslatedSegmentEntity>()
        val fakeCache = object : TranslatedCache {
            override suspend fun findCachedRange(
                bookId: String,
                targetLanguage: String,
                sourceMs: Long
            ): TranslatedSegmentEntity? {
                return if (sourceMs == 1000L) {
                    TranslatedSegmentEntity(
                        id = "seg1",
                        bookId = bookId,
                        targetLang = targetLanguage,
                        sourceStartMs = 1000L,
                        sourceEndMs = 1500L,
                        aacFilePath = "/tmp/cached.pcm"
                    )
                } else {
                    null
                }
            }

            override suspend fun putSegment(
                bookId: String,
                targetLanguage: String,
                sourceStartMs: Long,
                sourceEndMs: Long,
                pcmData: ByteArray
            ) {
                cachedSegments.add(
                    TranslatedSegmentEntity(
                        id = "${bookId}_${sourceStartMs}_$sourceEndMs",
                        bookId = bookId,
                        targetLang = targetLanguage,
                        sourceStartMs = sourceStartMs,
                        sourceEndMs = sourceEndMs,
                        aacFilePath = "/tmp/out.pcm",
                        fileSizeBytes = pcmData.size.toLong()
                    )
                )
            }

            override suspend fun getSegmentPcm(segment: TranslatedSegmentEntity): ByteArray = ByteArray(2400) { 9 }
            override suspend fun evictToMaxBytes(maxBytes: Long) {}
            override suspend fun clearCache() {}
        }

        val fakeCacheIndex = object : TranslatedCacheIndex {
            override suspend fun coveredUntil(bookId: String, targetLang: String, fromMs: Long): Long = 1500L
            override suspend fun firstGapAfter(bookId: String, targetLang: String, afterMs: Long): Long = 1500L
        }

        val pipeline = TranslationPipeline(
            sourceDecoder = fakeDecoder,
            resampler = StreamingLinearResampler(),
            chunker = Chunker(3200),
            pacer = Pacer(),
            liveTranslateClient = fakeClient,
            pcmPlayer = spyPlayer,
            translatedCache = fakeCache,
            translatedCacheIndex = fakeCacheIndex,
            enablePacingDelay = false
        )

        pipeline.start(
            scope = backgroundScope,
            bookId = "book-1",
            filePath = "content://com.android.providers.downloads/document/42",
            targetLanguage = LanguageCode.RUSSIAN,
            apiKey = "valid-api-key",
            modelId = "gemini-3.5-live-translate-preview",
            echoTargetLanguage = false,
            startPositionMs = 1000L,
            playbackSpeed = 1.25f
        )

        advanceUntilIdle()

        // Cache covered 1000..1500ms, so decoder should start at 1500ms
        assertEquals(1500L, fakeDecoder.lastStartMs)
        // Two 3200B (100ms) chunks sent to LiveTranslateClient
        assertEquals(2, sentChunks.size)
        assertEquals(3200, sentChunks[0].data.size)
        assertEquals(3200, sentChunks[1].data.size)
        assertEquals(16000, sentChunks[0].sampleRate)

        // One cached chunk + one live AI chunk played via PcmPlayer
        assertEquals(2, playedChunks.size)
        assertEquals(1, cachedSegments.size)

        val state = pipeline.state.value
        assertTrue(state.isRunning)
        assertNull(state.errorMessage)
        assertEquals("Vulnerability is courage.", state.currentSourceSubtitle)
        assertEquals("Уязвимость — это смелость.", state.currentTranslatedSubtitle)
        assertTrue(state.bufferAheadMs > 0L)

        // Verify position update, speed control, pause/resume, and stop
        pipeline.updatePlaybackPosition(1600L)
        assertEquals(100L, pipeline.state.value.bufferAheadMs)

        pipeline.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, realPlayer.playbackSpeed, 0.001f)

        pipeline.pauseAudio()
        assertFalse(realPlayer.isPlaying)
        pipeline.resumeAudio()
        assertTrue(realPlayer.isPlaying)

        pipeline.stop()
        assertFalse(pipeline.state.value.isRunning)
        assertTrue(fakeDecoder.released)
        assertTrue(fakeClient.stopped)
        assertNotNull(fakeClient.startedModel)
    }

    @Test
    fun testTerminalErrorMessageStopsDecodingAndSetsIsRunningFalse() = runTest {
        val chunksDecoded = AtomicInteger(0)
        val fakeDecoder = object : SourceDecoder {
            var released = false
            override fun decode(filePath: String, startPositionMs: Long, durationMs: Long): Flow<PcmChunk> = flow {
                repeat(100) {
                    chunksDecoded.incrementAndGet()
                    emit(PcmChunk(ByteArray(3200) { 1 }, sampleRate = 16000, channels = 1, timestampMs = it * 100L))
                    delay(20)
                }
            }

            override fun release() {
                released = true
            }
        }

        val outputFlow = MutableSharedFlow<TranslationOutput>(extraBufferCapacity = 16)
        val sentChunks = AtomicInteger(0)
        val fakeClient = object : LiveTranslateClient {
            override val translationStream: Flow<TranslationOutput> = outputFlow.asSharedFlow()
            override suspend fun start(
                apiKey: String,
                targetLanguageCode: String,
                echoTargetLanguage: Boolean,
                model: String
            ) {}

            override suspend fun sendAudio(chunk: PcmChunk) {
                if (sentChunks.incrementAndGet() == 1) {
                    outputFlow.emit(TranslationOutput(errorMessage = "WebSocket closed unexpectedly: code=1008"))
                }
            }

            override suspend fun stop() {}
        }

        val realPlayer = AudioTrackPcmPlayer { _, _ -> null }
        val pipeline = TranslationPipeline(
            sourceDecoder = fakeDecoder,
            resampler = StreamingLinearResampler(),
            chunker = Chunker(3200),
            pacer = Pacer(),
            liveTranslateClient = fakeClient,
            pcmPlayer = realPlayer,
            enablePacingDelay = false
        )

        pipeline.start(
            scope = backgroundScope,
            bookId = "book-err",
            filePath = "/tmp/book.m4b",
            targetLanguage = LanguageCode.SPANISH,
            apiKey = "valid-key"
        )
        advanceUntilIdle()

        val state = pipeline.state.value
        assertFalse(state.isRunning)
        assertEquals("WebSocket closed unexpectedly: code=1008", state.errorMessage)
        assertTrue(fakeDecoder.released)
        assertTrue("Decoding must stop early after terminal errorMessage", chunksDecoded.get() < 10)
    }

    @Test
    fun testRapidStopFollowedByStartDoesNotClobberNewSession() = runTest {
        val fakeDecoder = object : SourceDecoder {
            override fun decode(filePath: String, startPositionMs: Long, durationMs: Long): Flow<PcmChunk> = flow {}
            override fun release() {}
        }

        val outputFlow = MutableSharedFlow<TranslationOutput>(extraBufferCapacity = 16)
        val stopGate = CompletableDeferred<Unit>()
        val firstStopDone = CompletableDeferred<Unit>()
        val secondStartDone = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val fakeClient = object : LiveTranslateClient {
            @Volatile
            var active = false

            override val translationStream: Flow<TranslationOutput> = outputFlow.asSharedFlow()
            override suspend fun start(
                apiKey: String,
                targetLanguageCode: String,
                echoTargetLanguage: Boolean,
                model: String
            ) {
                events.add("start:$targetLanguageCode")
                active = true
                if (targetLanguageCode == LanguageCode.GERMAN.code) {
                    secondStartDone.countDown()
                }
            }

            override suspend fun sendAudio(chunk: PcmChunk) {}

            override suspend fun stop() {
                events.add("stop:begin")
                stopGate.await()
                active = false
                events.add("stop:end")
                firstStopDone.complete(Unit)
            }
        }

        val realPlayer = AudioTrackPcmPlayer { _, _ -> null }
        val pipeline = TranslationPipeline(
            sourceDecoder = fakeDecoder,
            resampler = StreamingLinearResampler(),
            chunker = Chunker(3200),
            pacer = Pacer(),
            liveTranslateClient = fakeClient,
            pcmPlayer = realPlayer,
            enablePacingDelay = false
        )

        // Start session 1
        pipeline.start(
            scope = backgroundScope,
            bookId = "book-1",
            filePath = "/tmp/book.m4b",
            targetLanguage = LanguageCode.FRENCH,
            apiKey = "valid-key"
        )
        assertTrue(fakeClient.active)

        // Rapid stop() -> start() while stop() is suspended on cleanupScope
        pipeline.stop()
        pipeline.start(
            scope = backgroundScope,
            bookId = "book-1",
            filePath = "/tmp/book.m4b",
            targetLanguage = LanguageCode.GERMAN,
            apiKey = "valid-key"
        )

        // Unblock async stop cleanup on Dispatchers.IO and wait for it to release clientMutex.
        // firstStopDone completes while the mutex is still held (it is the last statement inside
        // the fake stop()), so we must also wait for the follow-up start("de") that is serialized
        // behind the mutex release before draining the test scheduler.
        stopGate.complete(Unit)
        withContext(Dispatchers.Default) {
            firstStopDone.await()
        }
        // The mutex is released by the real Dispatchers.IO cleanup thread, which re-queues the
        // follow-up start("de") continuation onto this test scheduler. A single blocking
        // secondStartDone.await(...) would starve the scheduler, and advanceUntilIdle() alone ignores
        // backgroundScope tasks when no foreground task is pending, so pump runCurrent() +
        // advanceUntilIdle() between short waits.
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var secondSessionStarted = false
        while (!secondSessionStarted && System.nanoTime() < deadlineNs) {
            secondSessionStarted = secondStartDone.await(10, TimeUnit.MILLISECONDS)
            runCurrent()
            advanceUntilIdle()
        }
        assertTrue(
            "New session client start() must run after the old stop() cleanup releases clientMutex",
            secondSessionStarted
        )
        advanceUntilIdle()

        // New session must not be stopped by older stop() cleanup
        assertTrue(pipeline.state.value.isRunning)
        assertTrue(fakeClient.active)
        assertEquals(listOf("start:fr", "stop:begin", "stop:end", "start:de"), events.toList())
        pipeline.stop()
    }

    @Test
    fun testIncrementalSubtitlesAccumulatePerTurnAndMidSegmentCacheResumeSlicesOffset() = runTest {
        val fakeDecoder = object : SourceDecoder {
            override fun decode(filePath: String, startPositionMs: Long, durationMs: Long): Flow<PcmChunk> = flow {
                emit(PcmChunk(ByteArray(3200) { 1 }, sampleRate = 16000, channels = 1, timestampMs = startPositionMs))
            }

            override fun release() {}
        }

        val outputFlow = MutableSharedFlow<TranslationOutput>(extraBufferCapacity = 16)
        val fakeClient = object : LiveTranslateClient {
            override val translationStream: Flow<TranslationOutput> = outputFlow.asSharedFlow()
            override suspend fun start(
                apiKey: String,
                targetLanguageCode: String,
                echoTargetLanguage: Boolean,
                model: String
            ) {}

            override suspend fun sendAudio(chunk: PcmChunk) {}
            override suspend fun stop() {}
        }

        // Cached 1000ms segment [0..1000], resuming at fromMs = 500 -> should slice last half of bytes
        val fullCachedPcm = ByteArray(100) { idx -> idx.toByte() }
        val cachedSeg = TranslatedSegmentEntity(
            id = "b1_ru_0_1000",
            bookId = "b1",
            targetLang = "ru",
            sourceStartMs = 0L,
            sourceEndMs = 1000L,
            aacFilePath = "/tmp/cache.pcm",
            fileSizeBytes = fullCachedPcm.size.toLong(),
            createdAt = 1L
        )
        val cache = object : TranslatedCache {
            override suspend fun findCachedRange(bookId: String, targetLanguage: String, sourceMs: Long): TranslatedSegmentEntity? {
                return if (sourceMs in 0L until 1000L) cachedSeg else null
            }

            override suspend fun putSegment(bookId: String, targetLanguage: String, sourceStartMs: Long, sourceEndMs: Long, pcmData: ByteArray) {}
            override suspend fun getSegmentPcm(segment: TranslatedSegmentEntity): ByteArray? = fullCachedPcm
            override suspend fun evictToMaxBytes(maxBytes: Long) {}
            override suspend fun clearCache() {}
        }

        val writtenChunks = mutableListOf<PcmChunk>()
        val recordingPlayer = object : PcmPlayer {
            override val currentPositionMs = kotlinx.coroutines.flow.MutableStateFlow(0L)
            override fun init(sampleRate: Int, channels: Int) {}
            override fun write(chunk: PcmChunk): Int {
                writtenChunks.add(chunk)
                return chunk.data.size
            }
            override fun setPlaybackSpeed(speed: Float) {}
            override fun setOriginalAudioVolume(volume: Float) {}
            override fun play() {}
            override fun pause() {}
            override fun stop() {}
            override fun flush() {}
            override fun release() {}
        }

        val pipeline = TranslationPipeline(
            sourceDecoder = fakeDecoder,
            resampler = StreamingLinearResampler(),
            chunker = Chunker(3200),
            pacer = Pacer(),
            liveTranslateClient = fakeClient,
            pcmPlayer = recordingPlayer,
            translatedCache = cache,
            enablePacingDelay = false
        )

        pipeline.start(
            scope = backgroundScope,
            bookId = "b1",
            filePath = "/tmp/book.m4b",
            targetLanguage = LanguageCode.RUSSIAN,
            apiKey = "valid-key",
            startPositionMs = 500L
        )
        advanceUntilIdle()

        // Mid-segment resume should have written the second half (50 bytes starting at 50)
        assertEquals(1, writtenChunks.size)
        assertEquals(50, writtenChunks[0].data.size)
        assertEquals(50.toByte(), writtenChunks[0].data[0])

        // Incremental deltas within one turn should accumulate cleanly, not overwrite with 1 word
        outputFlow.emit(TranslationOutput(sourceTranscript = "Hello ", targetTranscript = "Привет "))
        outputFlow.emit(TranslationOutput(sourceTranscript = "world", targetTranscript = "мир", isTurnComplete = true))
        advanceUntilIdle()

        assertEquals("Hello world", pipeline.state.value.currentSourceSubtitle)
        assertEquals("Привет мир", pipeline.state.value.currentTranslatedSubtitle)

        // Next turn after isTurnComplete resets cleanly to the new turn's text
        outputFlow.emit(TranslationOutput(sourceTranscript = "Next sentence", targetTranscript = "Следующая фраза"))
        advanceUntilIdle()

        assertEquals("Next sentence", pipeline.state.value.currentSourceSubtitle)
        assertEquals("Следующая фраза", pipeline.state.value.currentTranslatedSubtitle)

        pipeline.stop()
    }

    @Test
    fun testPausedWebsocketErrorDoesNotExposeErrorBanner() = runTest {
        val fakeDecoder = object : SourceDecoder {
            override fun decode(filePath: String, startPositionMs: Long, durationMs: Long): Flow<PcmChunk> = flow {}
            override fun release() {}
        }
        val outputFlow = MutableSharedFlow<TranslationOutput>(extraBufferCapacity = 16)
        val fakeClient = object : LiveTranslateClient {
            override val translationStream: Flow<TranslationOutput> = outputFlow.asSharedFlow()
            override suspend fun start(apiKey: String, targetLanguageCode: String, echoTargetLanguage: Boolean, model: String) {}
            override suspend fun sendAudio(chunk: PcmChunk) {}
            override suspend fun stop() {}
        }
        val pipeline = TranslationPipeline(
            sourceDecoder = fakeDecoder,
            resampler = StreamingLinearResampler(),
            chunker = Chunker(3200),
            pacer = Pacer(),
            liveTranslateClient = fakeClient,
            pcmPlayer = AudioTrackPcmPlayer { _, _ -> null },
            enablePacingDelay = false
        )

        pipeline.start(
            scope = backgroundScope,
            bookId = "b2",
            filePath = "/tmp/book.m4b",
            targetLanguage = LanguageCode.SPANISH,
            apiKey = "valid-key"
        )
        advanceUntilIdle()
        pipeline.pauseAudio()

        outputFlow.emit(TranslationOutput(errorMessage = "Idle timeout"))
        advanceUntilIdle()

        assertFalse(pipeline.state.value.isRunning)
        assertNull(pipeline.state.value.errorMessage)
    }

    @Test
    fun testPlaybackSpeedAppliedBeforeInitAndUnityBypassedOnFormatSwitch() = runTest {
        val fakeDecoder = object : SourceDecoder {
            override fun decode(filePath: String, startPositionMs: Long, durationMs: Long): Flow<PcmChunk> = flow {}
            override fun release() {}
        }
        val outputFlow = MutableSharedFlow<TranslationOutput>(extraBufferCapacity = 16)
        val fakeClient = object : LiveTranslateClient {
            override val translationStream: Flow<TranslationOutput> = outputFlow.asSharedFlow()
            override suspend fun start(apiKey: String, targetLanguageCode: String, echoTargetLanguage: Boolean, model: String) {}
            override suspend fun sendAudio(chunk: PcmChunk) {}
            override suspend fun stop() {}
        }
        val events = mutableListOf<String>()
        val recordingPlayer = object : PcmPlayer {
            override val currentPositionMs = kotlinx.coroutines.flow.MutableStateFlow(0L)
            override fun init(sampleRate: Int, channels: Int) {
                events.add("init:$sampleRate:$channels")
            }

            override fun write(chunk: PcmChunk): Int = chunk.data.size

            override fun setPlaybackSpeed(speed: Float) {
                events.add("speed:$speed")
            }

            override fun setOriginalAudioVolume(volume: Float) {}
            override fun play() {
                events.add("play")
            }

            override fun pause() {}
            override fun stop() {}
            override fun flush() {}
            override fun release() {}
        }

        val pipeline = TranslationPipeline(
            sourceDecoder = fakeDecoder,
            resampler = StreamingLinearResampler(),
            chunker = Chunker(3200),
            pacer = Pacer(),
            liveTranslateClient = fakeClient,
            pcmPlayer = recordingPlayer,
            enablePacingDelay = false
        )

        pipeline.start(
            scope = backgroundScope,
            bookId = "b-speed",
            filePath = "/tmp/book.m4b",
            targetLanguage = LanguageCode.SPANISH,
            apiKey = "valid-key",
            playbackSpeed = 1.0f
        )
        runCurrent()
        assertEquals(listOf("speed:1.0", "init:24000:1", "play"), events)

        // Format switch at 1.0x should re-init without re-calling setPlaybackSpeed(1.0f)
        events.clear()
        outputFlow.emit(TranslationOutput(audioChunk = PcmChunk(ByteArray(480) { 1 }, sampleRate = 16000, channels = 1)))
        runCurrent()
        assertEquals(listOf("init:16000:1", "play"), events)

        // Format switch at 1.5x MUST re-apply non-unity speed after init(...)
        events.clear()
        pipeline.setPlaybackSpeed(1.5f)
        outputFlow.emit(TranslationOutput(audioChunk = PcmChunk(ByteArray(480) { 1 }, sampleRate = 48000, channels = 1)))
        runCurrent()
        assertEquals(listOf("speed:1.5", "init:48000:1", "speed:1.5", "play"), events)

        pipeline.stop()
    }
}
