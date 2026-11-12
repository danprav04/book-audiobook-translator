package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPipelineComponentsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testStreamingLinearResamplerStereo48kTo16kMono() {
        val resampler = StreamingLinearResampler()
        // 6 stereo frames at 48 kHz = 24 bytes -> 2 mono frames at 16 kHz = 4 bytes
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 6) {
            val v = ((i + 1) * 1000).toShort()
            buf.putShort(v) // L
            buf.putShort(v) // R
        }
        val input = PcmChunk(buf.array(), sampleRate = 48000, channels = 2, timestampMs = 100L)
        val out = resampler.resample(input, targetSampleRate = 16000, targetChannels = 1)

        assertEquals(16000, out.sampleRate)
        assertEquals(1, out.channels)
        assertEquals(100L, out.timestampMs)
        assertEquals(4, out.data.size)

        val outBuf = ByteBuffer.wrap(out.data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1000.toShort(), outBuf.short) // frame 0
        assertEquals(4000.toShort(), outBuf.short) // frame 3
    }

    @Test
    fun testAudioTrackPcmPlayerWithAdapterAndFallback() {
        var headFrames = 0L
        var playing = false
        var paused = false
        var flushed = false
        var stopped = false
        var released = false
        var lastSpeed = 1.0f
        var lastVolume = 1.0f
        val writtenBytes = mutableListOf<Byte>()

        val fakeAdapter = object : AudioTrackAdapter {
            override fun write(data: ByteArray, offset: Int, size: Int): Int {
                for (i in offset until offset + size) {
                    writtenBytes.add(data[i])
                }
                headFrames += size / 2
                return size
            }

            override fun play() {
                playing = true
                paused = false
            }

            override fun pause() {
                paused = true
                playing = false
            }

            override fun flush() {
                flushed = true
                headFrames = 0L
            }

            override fun stop() {
                stopped = true
                playing = false
            }

            override fun release() {
                released = true
            }

            override fun setSpeed(speed: Float): Boolean {
                lastSpeed = speed
                return true
            }

            override fun setVolume(volume: Float) {
                lastVolume = volume
            }

            override val playbackHeadPositionFrames: Long
                get() = headFrames
        }

        val player = AudioTrackPcmPlayer { _, _ -> fakeAdapter }
        player.init(sampleRate = 24000, channels = 1)
        assertTrue(player.isAudioOutputAvailable)

        player.setPlaybackSpeed(5.0f) // clamped to 3.0f
        assertEquals(3.0f, lastSpeed, 0.001f)
        player.setPlaybackSpeed(0.1f) // clamped to 0.5f
        assertEquals(0.5f, lastSpeed, 0.001f)

        player.setOriginalAudioVolume(0.25f)
        assertEquals(0.25f, lastVolume, 0.001f)

        player.play()
        assertTrue(playing)

        // Write 24000 frames (48000 bytes) = 1000ms at 24kHz mono 16-bit
        val oneSecondPcm = ByteArray(48000) { (it % 127).toByte() }
        val bytesWritten = player.write(PcmChunk(oneSecondPcm, sampleRate = 24000, channels = 1))
        assertEquals(48000, bytesWritten)
        assertEquals(1000L, player.currentPositionMs.value)

        player.pause()
        assertTrue(paused)

        player.flush()
        assertTrue(flushed)
        assertEquals(0L, player.currentPositionMs.value)

        player.stop()
        assertTrue(stopped)

        player.release()
        assertTrue(released)

        // Also verify pure-JVM fallback when adapterFactory returns null
        val fallbackPlayer = AudioTrackPcmPlayer { _, _ -> null }
        fallbackPlayer.init(24000, 1)
        assertFalse(fallbackPlayer.isAudioOutputAvailable)
        fallbackPlayer.write(PcmChunk(ByteArray(4800), sampleRate = 24000, channels = 1))
        assertEquals(100L, fallbackPlayer.currentPositionMs.value)
    }

    @Test
    fun testAacSegmentWriteAndReadFallbackRoundTrip() {
        val segmentFile = File(tempFolder.root, "cached_seg.aac")
        val pcm1 = ByteArray(4800) { (it and 0x7F).toByte() }
        val pcm2 = ByteArray(4800) { ((it + 17) and 0x7F).toByte() }

        val writer = DefaultAacSegmentWriter()
        writer.open(segmentFile, sampleRate = 24000, channels = 1)
        writer.writeChunk(PcmChunk(pcm1, sampleRate = 24000, channels = 1, timestampMs = 0L))
        writer.writeChunk(PcmChunk(pcm2, sampleRate = 24000, channels = 1, timestampMs = 100L))
        writer.close()

        assertTrue(segmentFile.exists())
        assertTrue(segmentFile.length() > 44)

        val reader = DefaultAacSegmentReader(chunkDurationMs = 100)
        reader.open(segmentFile)
        val read1 = reader.readNextChunk()
        val read2 = reader.readNextChunk()
        val readEnd = reader.readNextChunk()
        reader.close()

        assertNotNull(read1)
        assertNotNull(read2)
        assertNull(readEnd)
        assertEquals(24000, read1!!.sampleRate)
        assertArrayEquals(pcm1, read1.data)
        assertArrayEquals(pcm2, read2!!.data)
    }

    @Test
    fun testPcmWaveSourceDecoderWavSeekAndSlice() = runTest {
        val wavFile = File(tempFolder.root, "book.wav")
        // Write 1 second of 16 kHz mono PCM (32000 bytes) using WavSegmentWriter
        val pcm = ByteArray(32000) { (it % 100).toByte() }
        val wavWriter = WavSegmentWriter()
        wavWriter.open(wavFile, sampleRate = 16000, channels = 1)
        wavWriter.writeChunk(PcmChunk(pcm, sampleRate = 16000, channels = 1))
        wavWriter.close()

        val decoder = PcmWaveSourceDecoder()
        // Decode 200ms starting from 500ms -> 6400 bytes
        val chunks = decoder.decode(wavFile.absolutePath, startPositionMs = 500L, durationMs = 200L).toList()
        val totalBytes = chunks.sumOf { it.data.size }
        assertEquals(6400, totalBytes)
        assertEquals(500L, chunks.first().timestampMs)
        assertEquals(16000, chunks.first().sampleRate)
    }
}
