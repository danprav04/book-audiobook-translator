package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.model.PcmChunk

/**
 * Resamples arbitrary PCM audio sample rates and channel counts to standard 16 kHz mono 16-bit LE PCM.
 */
interface Resampler {
    /**
     * Resamples the given [input] PCM chunk to 16 kHz mono.
     */
    fun resample(input: PcmChunk, targetSampleRate: Int = 16000, targetChannels: Int = 1): PcmChunk
}

/**
 * Stub / passthrough resampler implementation.
 */
class StubResampler : Resampler {
    override fun resample(input: PcmChunk, targetSampleRate: Int, targetChannels: Int): PcmChunk {
        if (input.sampleRate == targetSampleRate && input.channels == targetChannels) {
            return input
        }
        // In stub mode, if already matching return as is, otherwise return new chunk with target metadata
        return PcmChunk(
            data = input.data,
            sampleRate = targetSampleRate,
            channels = targetChannels,
            timestampMs = input.timestampMs
        )
    }
}
