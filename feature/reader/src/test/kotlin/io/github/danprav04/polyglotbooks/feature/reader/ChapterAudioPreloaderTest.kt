package io.github.danprav04.polyglotbooks.feature.reader

import io.github.danprav04.polyglotbooks.core.model.PunctuationDialogueHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ChapterAudioPreloaderTest {

    private fun makeChunk(idx: Int, text: String = "Paragraph $idx spoken text."): SpeechChunk =
        SpeechChunk(
            text = text,
            leadCfi = "epubcfi(/6/2!/4/2/${idx + 1}:0)",
            allCfis = listOf("epubcfi(/6/2!/4/2/${idx + 1}:0)"),
            leadIndex = idx,
            voiceId = "Zephyr",
            style = null,
            paragraphIndices = listOf(idx),
            paragraphCharWeights = listOf(text.length.coerceAtLeast(1)),
            languageCode = null
        )

    private fun fakePcmFor(idx: Int): ByteArray = ByteArray(2_400) { ((idx + 1) and 0x7F).toByte() }

    @Test
    fun `initial preload waits for N chunks and rolling lookahead avoids eager full-chapter generation`() = runTest {
        val tempDir = Files.createTempDirectory("preloader-rolling").toFile()
        try {
            val diskCache = TranslationDiskCache(tempDir)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val chunks = (0 until 12).map { makeChunk(it) }
            val synthesizedOrder = mutableListOf<Int>()
            val chunkGates = List(chunks.size) { CompletableDeferred<Unit>() }
            val progressEvents = mutableListOf<Pair<Int, Int>>()

            coroutineScope {
                val preloader = ChapterAudioPreloader(
                    chunks = chunks,
                    initialPreloadChunks = 3,
                    lookaheadWindowChunks = 4,
                    cacheTagForChunk = { "GEMINI_LIVE_V5" },
                    diskCache = diskCache,
                    maxCacheBytes = TranslationDiskCache.DEFAULT_MAX_AUDIO_BYTES,
                    isCurrentGeneration = { true },
                    synthesizeUncached = { idx, chunk ->
                        chunkGates[idx].await()
                        synthesizedOrder += idx
                        PreloadedAudioSegment(
                            chunkIndex = idx,
                            chunk = chunk,
                            pcmBytes = fakePcmFor(idx),
                            sampleRate = 24_000,
                            channels = 1,
                            fromCache = false,
                            usedLiveEngine = true,
                            usedFallback = false
                        )
                    }
                )

                preloader.start(this, dispatcher)
                assertFalse(preloader.areInitialChunksCachedOnDisk())

                // Release first 2 chunks: awaitInitialPreload(3) must still suspend.
                chunkGates[0].complete(Unit)
                chunkGates[1].complete(Unit)
                var preloadDone = false
                val preloadWaiter = launch(dispatcher) {
                    preloader.awaitInitialPreload { ready, target ->
                        progressEvents += ready to target
                    }
                    preloadDone = true
                }
                runCurrent()
                assertFalse("Preload must wait for all 3 initial chunks", preloadDone)
                assertEquals(listOf(0, 1), synthesizedOrder)

                // Release chunk 2 (3rd chunk): awaitInitialPreload completes; lookahead continues up to window (indices 0..3), NOT whole 12-chunk chapter!
                chunkGates[2].complete(Unit)
                runCurrent()
                assertTrue("Initial 3 chunks must complete awaitInitialPreload", preloadDone)
                preloadWaiter.join()
                assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progressEvents)

                // Allow remaining gates beyond lookahead window; at playbackCursor=0 and lookaheadWindow=4,
                // generator MUST stop at index 3 (generated 0..3) and NOT eagerly synthesize 4..11!
                for (i in 3 until chunks.size) chunkGates[i].complete(Unit)
                runCurrent()
                assertEquals(listOf(0, 1, 2, 3), synthesizedOrder)
                assertFalse("Must not eagerly preload chunk 4 when cursor=0 and window=4", preloader.isSegmentReady(4))

                // Ordered playback advances cursor and delivers every chunk 0..11 in exact order.
                val playedOrder = mutableListOf<Int>()
                for (i in chunks.indices) {
                    val seg = preloader.awaitSegment(i)
                    assertNotNull(seg)
                    playedOrder += seg!!.chunkIndex
                    assertArrayEquals(fakePcmFor(i), seg.pcmBytes)
                    assertNotNull(
                        "Completed chunk $i must already be persisted to disk cache before playback",
                        diskCache.loadAudioSegment("GEMINI_LIVE_V5", "Zephyr", null, chunks[i].text)
                    )
                }
                assertEquals((0 until 12).toList(), playedOrder)
                assertEquals((0 until 12).toList(), synthesizedOrder)
                preloader.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `cached initial chunks start immediately without synthesis calls and divider requires no API work`() = runTest {
        val tempDir = Files.createTempDirectory("preloader-cache-hit").toFile()
        try {
            val diskCache = TranslationDiskCache(tempDir)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val chunks = listOf(
                makeChunk(0, "First cached paragraph."),
                makeChunk(1, "---"),
                makeChunk(2, "Third cached paragraph."),
                makeChunk(3, "Fourth uncached paragraph.")
            )
            diskCache.saveAudioSegment("GEMINI_LIVE_V5", "Zephyr", null, chunks[0].text, 24_000, 1, fakePcmFor(0))
            diskCache.saveAudioSegment("GEMINI_LIVE_V5", "Zephyr", null, chunks[2].text, 24_000, 1, fakePcmFor(2))

            val uncachedCalls = mutableListOf<Int>()
            val cacheHits = AtomicInteger(0)
            coroutineScope {
                val preloader = ChapterAudioPreloader(
                    chunks = chunks,
                    initialPreloadChunks = 3,
                    cacheTagForChunk = { "GEMINI_LIVE_V5" },
                    diskCache = diskCache,
                    maxCacheBytes = TranslationDiskCache.DEFAULT_MAX_AUDIO_BYTES,
                    isCurrentGeneration = { true },
                    onCacheHit = { _, _ -> cacheHits.incrementAndGet() },
                    synthesizeUncached = { idx, chunk ->
                        uncachedCalls += idx
                        val kind = PunctuationDialogueHelper.resolve(chunk.text, chunk.style).kind
                        if (kind == PunctuationDialogueHelper.PunctuationKind.SILENT_DIVIDER) {
                            PreloadedAudioSegment(idx, chunk, ByteArray(4_800), 24_000, 1, fromCache = false, usedLiveEngine = false, usedFallback = false)
                        } else {
                            PreloadedAudioSegment(idx, chunk, fakePcmFor(idx), 24_000, 1, fromCache = false, usedLiveEngine = true, usedFallback = false)
                        }
                    }
                )
                assertTrue(preloader.areInitialChunksCachedOnDisk())

                preloader.start(this, dispatcher)
                preloader.awaitInitialPreload()
                advanceUntilIdle()

                val seg0 = preloader.awaitSegment(0)!!
                val seg1 = preloader.awaitSegment(1)!!
                val seg2 = preloader.awaitSegment(2)!!
                val seg3 = preloader.awaitSegment(3)!!

                assertTrue(seg0.fromCache)
                assertFalse(seg1.usedLiveEngine)
                assertTrue(seg2.fromCache)
                assertFalse(seg3.fromCache)
                assertEquals(2, cacheHits.get())
                assertEquals(listOf(1, 3), uncachedCalls)
                preloader.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `cancellation preserves completed cache files on disk for instant resume`() = runTest {
        val tempDir = Files.createTempDirectory("preloader-cancel-resume").toFile()
        try {
            val diskCache = TranslationDiskCache(tempDir)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val chunks = (0 until 4).map { makeChunk(it) }
            val holdChunk2 = CompletableDeferred<Unit>()
            var active = true

            // Pass 1: synthesize 0 and 1, pause/cancel while 2 is in flight.
            val pass1 = ChapterAudioPreloader(
                chunks = chunks,
                initialPreloadChunks = 3,
                cacheTagForChunk = { "GEMINI_LIVE_V5" },
                diskCache = diskCache,
                maxCacheBytes = TranslationDiskCache.DEFAULT_MAX_AUDIO_BYTES,
                isCurrentGeneration = { active },
                synthesizeUncached = { idx, chunk ->
                    if (idx == 2) {
                        holdChunk2.await()
                    }
                    PreloadedAudioSegment(idx, chunk, fakePcmFor(idx), 24_000, 1, fromCache = false, usedLiveEngine = true, usedFallback = false)
                }
            )
            pass1.start(this, dispatcher)
            runCurrent()
            active = false
            holdChunk2.cancel(CancellationException("Paused"))
            pass1.close()
            runCurrent()

            assertNotNull(diskCache.loadAudioSegment("GEMINI_LIVE_V5", "Zephyr", null, chunks[0].text))
            assertNotNull(diskCache.loadAudioSegment("GEMINI_LIVE_V5", "Zephyr", null, chunks[1].text))

            // Pass 2 (resume): 0 and 1 come straight from disk cache; only 2 and 3 synthesize.
            val pass2Uncached = mutableListOf<Int>()
            coroutineScope {
                val pass2 = ChapterAudioPreloader(
                    chunks = chunks,
                    initialPreloadChunks = 2,
                    cacheTagForChunk = { "GEMINI_LIVE_V5" },
                    diskCache = diskCache,
                    maxCacheBytes = TranslationDiskCache.DEFAULT_MAX_AUDIO_BYTES,
                    isCurrentGeneration = { true },
                    synthesizeUncached = { idx, chunk ->
                        pass2Uncached += idx
                        PreloadedAudioSegment(idx, chunk, fakePcmFor(idx), 24_000, 1, fromCache = false, usedLiveEngine = true, usedFallback = false)
                    }
                )
                assertTrue(pass2.areInitialChunksCachedOnDisk())
                pass2.start(this, dispatcher)
                pass2.awaitInitialPreload()
                val seg0 = pass2.awaitSegment(0)!!
                val seg1 = pass2.awaitSegment(1)!!
                val seg2 = pass2.awaitSegment(2)!!
                val seg3 = pass2.awaitSegment(3)!!
                assertTrue(seg0.fromCache)
                assertTrue(seg1.fromCache)
                assertArrayEquals(fakePcmFor(0), seg0.pcmBytes)
                assertArrayEquals(fakePcmFor(2), seg2.pcmBytes)
                assertArrayEquals(fakePcmFor(3), seg3.pcmBytes)
                assertEquals(listOf(2, 3), pass2Uncached)
                pass2.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `awaitInitialPreload proceeds once first chunk is ready if remaining initial chunks stall past patience timeout`() = runTest {
        val tempDir = Files.createTempDirectory("preloader-patience-timeout").toFile()
        try {
            val diskCache = TranslationDiskCache(tempDir)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val chunks = (0 until 5).map { makeChunk(it) }
            val gates = List(chunks.size) { CompletableDeferred<Unit>() }
            val progressEvents = mutableListOf<Pair<Int, Int>>()

            coroutineScope {
                val preloader = ChapterAudioPreloader(
                    chunks = chunks,
                    initialPreloadChunks = 3,
                    cacheTagForChunk = { "GEMINI_LIVE_V6" },
                    diskCache = diskCache,
                    maxCacheBytes = TranslationDiskCache.DEFAULT_MAX_AUDIO_BYTES,
                    isCurrentGeneration = { true },
                    synthesizeUncached = { idx, chunk ->
                        gates[idx].await()
                        PreloadedAudioSegment(
                            chunkIndex = idx,
                            chunk = chunk,
                            pcmBytes = fakePcmFor(idx),
                            sampleRate = 24_000,
                            channels = 1,
                            fromCache = false,
                            usedLiveEngine = true,
                            usedFallback = false
                        )
                    }
                )
                preloader.start(this, dispatcher)
                gates[0].complete(Unit)

                var preloadDone = false
                val waiter = launch(dispatcher) {
                    preloader.awaitInitialPreload(maxWaitAfterFirstReadyMs = 5_000L) { ready, target ->
                        progressEvents += ready to target
                    }
                    preloadDone = true
                }
                runCurrent()
                assertFalse("Should wait briefly for chunks 1..2 while within patience timeout", preloadDone)
                assertEquals(listOf(1 to 3), progressEvents)

                advanceTimeBy(4_000L)
                runCurrent()
                assertFalse("Still within 5,000ms patience timeout", preloadDone)

                advanceTimeBy(1_100L)
                runCurrent()
                assertTrue("Must proceed to playback after 5,000ms once chunk 0 is ready", preloadDone)
                waiter.join()

                val seg0 = preloader.awaitSegment(0)
                assertNotNull(seg0)
                assertArrayEquals(fakePcmFor(0), seg0!!.pcmBytes)

                // Remaining chunks continue synthesizing in background and deliver in exact order when unblocked.
                for (i in 1 until chunks.size) gates[i].complete(Unit)
                advanceUntilIdle()
                for (i in 1 until chunks.size) {
                    val seg = preloader.awaitSegment(i)
                    assertNotNull(seg)
                    assertArrayEquals(fakePcmFor(i), seg!!.pcmBytes)
                }
                preloader.close()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
