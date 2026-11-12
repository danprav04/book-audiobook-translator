package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Interface for decoding audio containers (MP3, M4B, AAC, FLAC, OGG, WAV) to raw PCM chunks
 * using Android MediaExtractor and MediaCodec.
 */
interface SourceDecoder {
    /**
     * Decodes the audio track at [filePath] starting from [startPositionMs] up to [durationMs].
     */
    fun decode(filePath: String, startPositionMs: Long = 0L, durationMs: Long = -1L): Flow<PcmChunk>

    /**
     * Releases any active decoder resources.
     */
    fun release()
}

/**
 * Stub implementation of [SourceDecoder].
 */
class StubSourceDecoder : SourceDecoder {
    override fun decode(filePath: String, startPositionMs: Long, durationMs: Long): Flow<PcmChunk> {
        return emptyFlow()
    }

    override fun release() {
        // No-op for stub
    }
}
