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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
