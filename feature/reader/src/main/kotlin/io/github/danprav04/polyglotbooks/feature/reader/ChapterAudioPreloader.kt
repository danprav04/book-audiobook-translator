package io.github.danprav04.polyglotbooks.feature.reader

import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.github.danprav04.polyglotbooks.core.model.PunctuationDialogueHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal data class PreloadedAudioSegment(
    val chunkIndex: Int,
    val chunk: SpeechChunk,
    val pcmBytes: ByteArray,
    val sampleRate: Int,
    val channels: Int,
    val fromCache: Boolean,
    val usedLiveEngine: Boolean,
    val usedFallback: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreloadedAudioSegment) return false
        return chunkIndex == other.chunkIndex &&
            chunk == other.chunk &&
            pcmBytes.contentEquals(other.pcmBytes) &&
            sampleRate == other.sampleRate &&
            channels == other.channels &&
            fromCache == other.fromCache &&
            usedLiveEngine == other.usedLiveEngine &&
            usedFallback == other.usedFallback
    }

    override fun hashCode(): Int {
        var result = chunkIndex
        result = 31 * result + chunk.hashCode()
        result = 31 * result + pcmBytes.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + fromCache.hashCode()
        result = 31 * result + usedLiveEngine.hashCode()
        result = 31 * result + usedFallback.hashCode()
        return result
    }
}

/**
 * Decouples chapter audio synthesis and disk-cache persistence from ordered [PcmPlayer] playback.
 *
 * Preloads the configured [initialPreloadChunks] before playback starts, saves verified Live audio
 * atomically into [TranslationDiskCache], and continues rolling lookahead generation bounded by
 * [lookaheadWindowChunks] ahead of the active playback cursor so long chapters are never eagerly
 * generated all at once.
 */
internal class ChapterAudioPreloader(
    private val chunks: List<SpeechChunk>,
    initialPreloadChunks: Int,
    lookaheadWindowChunks: Int = maxOf(initialPreloadChunks + 4, DEFAULT_LOOKAHEAD_WINDOW),
    private val cacheTagForChunk: (SpeechChunk) -> String,
    private val diskCache: TranslationDiskCache?,
    private val maxCacheBytes: Long,
    private val isCurrentGeneration: () -> Boolean,
    private val synthesizeUncached: suspend (chunkIndex: Int, chunk: SpeechChunk) -> PreloadedAudioSegment,
    private val onCacheHit: suspend (chunkIndex: Int, chunk: SpeechChunk) -> Unit = { _, _ -> },
    private val onCloseResources: suspend () -> Unit = {}
) {
    val initialTargetCount: Int = if (chunks.isEmpty()) 0 else initialPreloadChunks.coerceIn(1, chunks.size)
    private val effectiveLookahead: Int = lookaheadWindowChunks.coerceAtLeast(initialTargetCount)
    private val slots: List<CompletableDeferred<PreloadedAudioSegment?>> =
        List(chunks.size) { CompletableDeferred() }
    private val playbackCursor = MutableStateFlow(0)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var generatorJob: Job? = null

    /**
     * Returns `true` when all [initialTargetCount] starting chunks already exist on disk (or are
     * silent dividers requiring no synthesis), allowing instant start without a preload wait.
     */
    fun areInitialChunksCachedOnDisk(): Boolean {
        if (initialTargetCount == 0) return true
        return (0 until initialTargetCount).all { idx ->
            val chunk = chunks[idx]
            val kind = PunctuationDialogueHelper.resolve(chunk.text, chunk.style).kind
            if (kind == PunctuationDialogueHelper.PunctuationKind.SILENT_DIVIDER ||
                kind == PunctuationDialogueHelper.PunctuationKind.EMPTY
            ) {
                true
            } else {
                diskCache?.hasAudioSegment(
                    engineTag = cacheTagForChunk(chunk),
                    voiceId = chunk.voiceId,
                    style = chunk.style,
                    text = chunk.text
                ) == true
            }
        }
    }

    /**
     * Launches the single ordered background preloader loop on [scope] using [dispatcher].
     */
    fun start(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        if (!started.compareAndSet(false, true)) return
        generatorJob = scope.launch(dispatcher) {
            try {
                for (genIdx in chunks.indices) {
                    if (!isActive || !isCurrentGeneration() || closed.get()) break

                    playbackCursor.first { cursor ->
                        genIdx < maxOf(initialTargetCount, cursor + effectiveLookahead) ||
                            !isCurrentGeneration() ||
                            closed.get()
                    }
                    if (!isActive || !isCurrentGeneration() || closed.get()) break

                    val chunk = chunks[genIdx]
                    val tag = cacheTagForChunk(chunk)
                    val cached = diskCache?.loadAudioSegment(tag, chunk.voiceId, chunk.style, chunk.text)
                    if (cached != null && cached.pcmBytes.isNotEmpty()) {
                        onCacheHit(genIdx, chunk)
                        slots[genIdx].complete(
                            PreloadedAudioSegment(
                                chunkIndex = genIdx,
                                chunk = chunk,
                                pcmBytes = cached.pcmBytes,
                                sampleRate = cached.sampleRate,
                                channels = cached.channels,
                                fromCache = true,
                                usedLiveEngine = true,
                                usedFallback = false
                            )
                        )
                        continue
                    }

                    val segment = synthesizeUncached(genIdx, chunk)
                    if (segment.usedLiveEngine && !segment.usedFallback &&
                        segment.pcmBytes.isNotEmpty() && diskCache != null
                    ) {
                        withContext(NonCancellable) {
                            diskCache.saveAudioSegment(
                                engineTag = tag,
                                voiceId = chunk.voiceId,
                                style = chunk.style,
                                text = chunk.text,
                                sampleRate = segment.sampleRate,
                                channels = segment.channels,
                                pcmBytes = segment.pcmBytes,
                                maxBytes = maxCacheBytes
                            )
                        }
                    }
                    slots[genIdx].complete(segment)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                DiagnosticLogBuffer.record(
                    tag = "ChapterAudioPreloader",
                    message = "Preloader loop stopped with error",
                    level = "WARN",
                    throwable = t
                )
            } finally {
                for (slot in slots) {
                    if (!slot.isCompleted) {
                        slot.complete(null)
                    }
                }
            }
        }
    }

    /**
     * Suspends until the initial [initialTargetCount] chunks are synthesized/cached and ready for
     * ordered playback, invoking [onProgress] as each chunk becomes ready.
     */
    suspend fun awaitInitialPreload(onProgress: (ready: Int, target: Int) -> Unit = { _, _ -> }) {
        for (idx in 0 until initialTargetCount) {
            if (!isCurrentGeneration() || closed.get()) break
            val seg = slots[idx].await() ?: break
            if (seg.pcmBytes.isEmpty()) continue
            onProgress(idx + 1, initialTargetCount)
        }
    }

    fun isSegmentReady(chunkIndex: Int): Boolean = slots.getOrNull(chunkIndex)?.isCompleted == true

    /**
     * Advances the rolling lookahead cursor to [chunkIndex] and awaits the ordered segment.
     */
    suspend fun awaitSegment(chunkIndex: Int): PreloadedAudioSegment? {
        if (chunkIndex !in slots.indices) return null
        if (chunkIndex > playbackCursor.value) {
            playbackCursor.value = chunkIndex
        }
        return slots[chunkIndex].await()
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Wake cursor gate if generator is suspended on rolling lookahead.
        playbackCursor.value = Int.MAX_VALUE
        generatorJob?.cancel()
        for (slot in slots) {
            if (!slot.isCompleted) {
                slot.complete(null)
            }
        }
        withContext(NonCancellable) {
            onCloseResources()
        }
    }

    companion object {
        const val DEFAULT_LOOKAHEAD_WINDOW = 6
    }
}
