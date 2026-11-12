package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ResamplerTest {

    @Test
    fun testStereoToMonoDownmix() {
        // Create stereo 16-bit LE PCM: 2 frames (Left, Right)
        // Frame 0: L = 1000, R = 3000 -> Mono avg = 2000
        // Frame 1: L = -2000, R = -4000 -> Mono avg = -3000
        val byteBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.putShort(1000.toShort())
        byteBuffer.putShort(3000.toShort())
        byteBuffer.putShort((-2000).toShort())
        byteBuffer.putShort((-4000).toShort())

        val input = PcmChunk(
            data = byteBuffer.array(),
            sampleRate = 16000,
            channels = 2,
            timestampMs = 0L
        )

        val resampler = LinearInterpolationResampler()
        val result = resampler.resample(input, targetSampleRate = 16000, targetChannels = 1)

        assertEquals(1, result.channels)
        assertEquals(16000, result.sampleRate)
        assertEquals(4, result.data.size) // 2 samples * 2 bytes

        val outBuffer = ByteBuffer.wrap(result.data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2000.toShort(), outBuffer.short)
        assertEquals((-3000).toShort(), outBuffer.short)
    }

    @Test
    fun testResample48kTo16k() {
        // 3 samples at 48kHz should map to 1 sample at 16kHz (ratio 3.0)
        val byteBuffer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.putShort(100.toShort())
        byteBuffer.putShort(200.toShort())
        byteBuffer.putShort(300.toShort())

        val input = PcmChunk(
            data = byteBuffer.array(),
            sampleRate = 48000,
            channels = 1,
            timestampMs = 0L
        )

        val resampler = LinearInterpolationResampler()
        val result = resampler.resample(input, targetSampleRate = 16000, targetChannels = 1)

        assertEquals(16000, result.sampleRate)
        assertEquals(1, result.channels)
        assertTrue(result.data.size <= 6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testResampleZeroTargetSampleRateThrows() {
        val input = PcmChunk(ByteArray(4), sampleRate = 16000, channels = 1)
        val resampler = LinearInterpolationResampler()
        resampler.resample(input, targetSampleRate = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testResampleNegativeTargetSampleRateThrows() {
        val input = PcmChunk(ByteArray(4), sampleRate = 16000, channels = 1)
        val resampler = LinearInterpolationResampler()
        resampler.resample(input, targetSampleRate = -8000)
    }
}
