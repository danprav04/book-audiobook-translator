package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resamples arbitrary PCM audio sample rates and channel counts to standard 16 kHz mono 16-bit LE PCM.
 */
interface Resampler {
    /**
     * Resamples the given [input] PCM chunk to [targetSampleRate] mono.
     */
    fun resample(input: PcmChunk, targetSampleRate: Int = 16000, targetChannels: Int = 1): PcmChunk

    /**
     * Clears any streaming state (call between independent streams, e.g. after a seek).
     */
    fun reset() {}
}

/**
 * Pure Kotlin linear interpolation resampler that downmixes multi-channel audio to mono
 * and resamples from arbitrary sample rates (e.g. 44100, 48000, 24000, 16000) to targetSampleRate.
 *
 * Stateless: every chunk is resampled independently. For continuous streams prefer
 * [StreamingLinearResampler], which avoids phase drift and clicks at chunk boundaries.
 */
class LinearInterpolationResampler : Resampler {
    override fun resample(input: PcmChunk, targetSampleRate: Int, targetChannels: Int): PcmChunk {
        require(targetChannels == 1) { "Only mono output is supported currently" }
        require(targetSampleRate > 0) { "Target sample rate must be positive, was $targetSampleRate" }
        if (input.data.isEmpty()) {
            return PcmChunk(ByteArray(0), targetSampleRate, targetChannels, input.timestampMs)
        }

        val channels = maxOf(1, input.channels)
        val inputSampleRate = if (input.sampleRate > 0) input.sampleRate else 16000

        val totalFrames = input.data.size / (channels * 2)
        if (totalFrames <= 0) {
            return PcmChunk(ByteArray(0), targetSampleRate, targetChannels, input.timestampMs)
        }

        if (inputSampleRate == targetSampleRate && channels == 1) {
            return input
        }

        val monoSamples = PcmMath.downmixToMono(input.data, channels, totalFrames)

        val ratio = inputSampleRate.toDouble() / targetSampleRate.toDouble()
        val outputFrames = if (inputSampleRate == targetSampleRate) {
            totalFrames
        } else {
            ((totalFrames.toDouble() * targetSampleRate) / inputSampleRate).toLong().toInt()
        }

        val outputSamples = DoubleArray(outputFrames)
        for (outIdx in 0 until outputFrames) {
            val inPos = outIdx * ratio
            val idx0 = minOf(inPos.toInt(), totalFrames - 1)
            val idx1 = minOf(idx0 + 1, totalFrames - 1)
            val frac = inPos - idx0

            val sample0 = monoSamples[idx0]
            val sample1 = monoSamples[idx1]
            outputSamples[outIdx] = sample0 + (sample1 - sample0) * frac
        }

        return PcmChunk(
            data = PcmMath.toPcm16Bytes(outputSamples, outputSamples.size),
            sampleRate = targetSampleRate,
            channels = targetChannels,
            timestampMs = input.timestampMs
        )
    }
}

/**
 * Stateful streaming linear-interpolation resampler + downmixer.
 *
 * Keeps the fractional read position, the last input sample and any partial frame bytes across
 * calls, so a continuous stream split into arbitrary chunk sizes is resampled seamlessly
 * (no per-chunk rounding drift, no discontinuities at chunk boundaries). State is reset
 * automatically when the input format changes, or explicitly via [reset].
 *
 * Not thread-safe.
 */
class StreamingLinearResampler : Resampler {
    private var inputRate = -1
    private var inputChannels = -1
    private var outputRate = -1

    /** Absolute index (in input frames) of the first frame of the next chunk. */
    private var consumedFrames = 0L

    /** Absolute input-frame position of the next output sample. */
    private var nextOutputPos = 0.0
    private var previousSample = 0.0
    private var hasPrevious = false
    private var pendingBytes = ByteArray(0)

    override fun reset() {
        inputRate = -1
        inputChannels = -1
        outputRate = -1
        consumedFrames = 0L
        nextOutputPos = 0.0
        previousSample = 0.0
        hasPrevious = false
        pendingBytes = ByteArray(0)
    }

    override fun resample(input: PcmChunk, targetSampleRate: Int, targetChannels: Int): PcmChunk {
        require(targetChannels == 1) { "Only mono output is supported currently" }
        require(targetSampleRate > 0) { "Target sample rate must be positive, was $targetSampleRate" }

        val channels = maxOf(1, input.channels)
        val rate = if (input.sampleRate > 0) input.sampleRate else 16000
        if (rate != inputRate || channels != inputChannels || targetSampleRate != outputRate) {
            reset()
            inputRate = rate
            inputChannels = channels
            outputRate = targetSampleRate
        }

        val frameBytes = channels * 2
        val bytes = if (pendingBytes.isEmpty()) input.data else pendingBytes + input.data
        val totalFrames = bytes.size / frameBytes
        val usedBytes = totalFrames * frameBytes
        pendingBytes = if (usedBytes < bytes.size) bytes.copyOfRange(usedBytes, bytes.size) else ByteArray(0)

        if (totalFrames <= 0) {
            return PcmChunk(ByteArray(0), targetSampleRate, targetChannels, input.timestampMs)
        }

        val mono = PcmMath.downmixToMono(bytes, channels, totalFrames)

        if (rate == targetSampleRate) {
            consumedFrames += totalFrames
            previousSample = mono[totalFrames - 1]
            hasPrevious = true
            nextOutputPos = consumedFrames.toDouble()
            return PcmChunk(PcmMath.toPcm16Bytes(mono, totalFrames), targetSampleRate, targetChannels, input.timestampMs)
        }

        val ratio = rate.toDouble() / targetSampleRate.toDouble()
        val firstIndex = consumedFrames
        val lastIndex = consumedFrames + totalFrames - 1
        val estimated = ((totalFrames + 1) / ratio).toInt() + 2
        val out = DoubleArray(estimated)
        var count = 0

        while (nextOutputPos <= lastIndex.toDouble() && count < out.size) {
            val i0 = kotlin.math.floor(nextOutputPos).toLong()
            val frac = nextOutputPos - i0
            val s0 = sampleAt(i0, firstIndex, mono)
            val s1 = if (frac > 0.0 && i0 + 1 <= lastIndex) sampleAt(i0 + 1, firstIndex, mono) else s0
            out[count++] = s0 + (s1 - s0) * frac
            nextOutputPos += ratio
        }

        consumedFrames += totalFrames
        previousSample = mono[totalFrames - 1]
        hasPrevious = true

        return PcmChunk(PcmMath.toPcm16Bytes(out, count), targetSampleRate, targetChannels, input.timestampMs)
    }

    private fun sampleAt(absoluteIndex: Long, firstIndex: Long, mono: DoubleArray): Double {
        val local = absoluteIndex - firstIndex
        return when {
            local >= 0 -> mono[local.toInt()]
            hasPrevious -> previousSample
            else -> mono[0]
        }
    }
}

internal object PcmMath {
    fun downmixToMono(bytes: ByteArray, channels: Int, totalFrames: Int): DoubleArray {
        val mono = DoubleArray(totalFrames)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalFrames) {
            var sum = 0.0
            for (c in 0 until channels) {
                sum += buffer.short.toDouble()
            }
            mono[i] = sum / channels
        }
        return mono
    }

    fun toPcm16Bytes(samples: DoubleArray, count: Int): ByteArray {
        val outputBytes = ByteArray(count * 2)
        val outBuffer = ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val clamped = kotlin.math.round(samples[i]).coerceIn(-32768.0, 32767.0).toInt().toShort()
            outBuffer.putShort(clamped)
        }
        return outputBytes
    }
}
