package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 * Pure Kotlin linear interpolation resampler that downmixes multi-channel audio to mono
 * and resamples from arbitrary sample rates (e.g. 44100, 48000, 24000, 16000) to targetSampleRate.
 */
class LinearInterpolationResampler : Resampler {
    override fun resample(input: PcmChunk, targetSampleRate: Int, targetChannels: Int): PcmChunk {
        require(targetChannels == 1) { "Only mono output is supported currently" }
        if (input.data.isEmpty()) {
            return PcmChunk(ByteArray(0), targetSampleRate, targetChannels, input.timestampMs)
        }

        val bytes = input.data
        val channels = maxOf(1, input.channels)
        val inputSampleRate = if (input.sampleRate > 0) input.sampleRate else 16000

        val totalFrames = bytes.size / (channels * 2)
        if (totalFrames <= 0) {
            return PcmChunk(ByteArray(0), targetSampleRate, targetChannels, input.timestampMs)
        }

        val monoSamples = DoubleArray(totalFrames)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until totalFrames) {
            var sum = 0.0
            for (c in 0 until channels) {
                if (buffer.remaining() >= 2) {
                    sum += buffer.short.toDouble()
                }
            }
            monoSamples[i] = sum / channels
        }

        if (inputSampleRate == targetSampleRate && channels == 1) {
            return input
        }

        val ratio = inputSampleRate.toDouble() / targetSampleRate.toDouble()
        val outputFrames = if (inputSampleRate == targetSampleRate) {
            totalFrames
        } else {
            ((totalFrames.toDouble() * targetSampleRate) / inputSampleRate).toLong().toInt()
        }

        val outputSamples = DoubleArray(outputFrames)
        for (outIdx in 0 until outputFrames) {
            val inPos = outIdx * ratio
            val idx0 = inPos.toInt()
            val idx1 = minOf(idx0 + 1, totalFrames - 1)
            val frac = inPos - idx0

            val sample0 = monoSamples[idx0]
            val sample1 = monoSamples[idx1]
            outputSamples[outIdx] = sample0 + (sample1 - sample0) * frac
        }

        val outputBytes = ByteArray(outputSamples.size * 2)
        val outBuffer = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in outputSamples) {
            val clamped = sample.coerceIn(-32768.0, 32767.0).toInt().toShort()
            outBuffer.putShort(clamped)
        }

        return PcmChunk(
            data = outputBytes,
            sampleRate = targetSampleRate,
            channels = targetChannels,
            timestampMs = input.timestampMs
        )
    }
}
