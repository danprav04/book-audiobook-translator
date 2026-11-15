package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackPcmPlayerTest {

    private class FakeAdapter(val sampleRate: Int, val channels: Int) : AudioTrackAdapter {
        var headFrames: Long = 0L
        var writtenBytes: Long = 0L
        var playing = false
        var paused = false
        var flushed = false
        var stopped = false
        var released = false
        var lastSpeed = 1.0f
        var lastVolume = 1.0f

        override fun write(data: ByteArray, offset: Int, size: Int): Int {
            writtenBytes += size
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

    @Test
    fun `zero arg constructor remains usable without Android AudioTrack`() {
        val player = AudioTrackPcmPlayer()
        player.init(24000, 1)
        assertFalse(player.isAudioOutputAvailable)
        assertEquals(0L, player.totalFramesWritten)
        assertEquals(0L, player.playbackHeadPositionFrames())
        player.release()
    }

    @Test
    fun `fallback mode paces by wall clock freezes on pause and scales cleanly on speed changes`() {
        var nowNs = 1_000_000_000L
        val player = AudioTrackPcmPlayer(
            adapterFactory = { _, _ -> null },
            nanoTimeProvider = { nowNs },
            drainSleepMs = { ms -> nowNs += ms * 1_000_000L }
        )

        player.init(24000, 1)
        player.play()
        // Idle delay before first chunk arrives must not consume newly written frames.
        nowNs += 5_000_000_000L

        // 1 second of 24kHz mono 16-bit PCM = 24000 frames = 48000 bytes.
        player.write(PcmChunk(ByteArray(48000), sampleRate = 24000, channels = 1))
        assertEquals(24000L, player.totalFramesWritten)
        assertEquals(0L, player.playbackHeadPositionFrames())
        assertEquals(1000L, player.bufferedMs)

        // Advance 250ms at 1.0x -> 6000 frames (250ms).
        nowNs += 250_000_000L
        assertEquals(6000L, player.playbackHeadPositionFrames())
        assertEquals(250L, player.currentPositionMs.value)
        assertEquals(750L, player.bufferedMs)

        // Pause for 4 seconds -> playback head stays frozen at 6000 frames.
        player.pause()
        nowNs += 4_000_000_000L
        assertEquals(6000L, player.playbackHeadPositionFrames())
        assertEquals(250L, player.currentPositionMs.value)

        // Resume, run 250ms at 1.0x -> 12000 frames, then switch to 2.0x for 250ms -> 24000 frames.
        player.play()
        nowNs += 250_000_000L
        player.setPlaybackSpeed(2.0f)
        assertEquals(12000L, player.playbackHeadPositionFrames())

        nowNs += 250_000_000L
        assertEquals(24000L, player.playbackHeadPositionFrames())
        assertEquals(1000L, player.currentPositionMs.value)
        assertEquals(0L, player.bufferedMs)
    }

    @Test
    fun `flush preserves monotonic frame counters while resetting currentPositionMs and bufferedMs`() {
        var nowNs = 0L
        lateinit var adapter: FakeAdapter
        val player = AudioTrackPcmPlayer(
            adapterFactory = { rate, ch -> FakeAdapter(rate, ch).also { adapter = it } },
            nanoTimeProvider = { nowNs },
            drainSleepMs = { ms -> nowNs += ms * 1_000_000L }
        )

        player.init(24000, 1)
        player.play()
        // Write 1000 frames (2000 bytes), play 400 frames.
        player.write(PcmChunk(ByteArray(2000), sampleRate = 24000, channels = 1))
        adapter.headFrames = 400L
        assertEquals(1000L, player.totalFramesWritten)
        assertEquals(400L, player.playbackHeadPositionFrames())

        player.flush()
        assertTrue(adapter.flushed)
        assertEquals(0L, player.currentPositionMs.value)
        assertEquals(0L, player.bufferedMs)
        // Frame timeline stays monotonic: remaining discarded frames retire cleanly to 1000.
        assertEquals(1000L, player.totalFramesWritten)
        assertEquals(1000L, player.playbackHeadPositionFrames())

        // Writing 600 more frames starts at 1000..1600 in frame timeline while ms restarts at 0.
        player.write(PcmChunk(ByteArray(1200), sampleRate = 24000, channels = 1))
        adapter.headFrames = 300L
        assertEquals(1600L, player.totalFramesWritten)
        assertEquals(1300L, player.playbackHeadPositionFrames())
        assertEquals(12L, player.currentPositionMs.value) // 300 * 1000 / 24000 = 12ms
    }

    @Test
    fun `format change drains buffered frames before switching track and keeps monotonic frames and ms`() {
        var nowNs = 0L
        val adapters = mutableListOf<FakeAdapter>()
        var releasedBeforeDrainFinished = false

        val player = AudioTrackPcmPlayer(
            adapterFactory = { rate, ch -> FakeAdapter(rate, ch).also { adapters += it } },
            nanoTimeProvider = { nowNs },
            drainSleepMs = { ms ->
                nowNs += ms * 1_000_000L
                val first = adapters.first()
                if (first.released) {
                    releasedBeforeDrainFinished = true
                }
                // Advance 240 frames per 10ms poll until all 2400 frames render.
                first.headFrames = minOf(2400L, first.headFrames + 240L)
            }
        )

        player.init(24000, 1)
        player.setOriginalAudioVolume(0.75f)
        player.setPlaybackSpeed(1.25f)
        player.play()
        // Write 2400 frames = 100ms at 24kHz mono.
        player.write(PcmChunk(ByteArray(4800), sampleRate = 24000, channels = 1))
        assertEquals(2400L, player.totalFramesWritten)
        assertEquals(0L, player.playbackHeadPositionFrames())

        // Switching sample rate must drain the 2400 buffered frames before releasing adapter 0.
        player.init(16000, 1)

        assertFalse(releasedBeforeDrainFinished)
        assertEquals(2, adapters.size)
        assertTrue(adapters[0].released)
        assertEquals(2400L, adapters[0].headFrames)
        assertEquals(2400L, player.totalFramesWritten)
        assertEquals(2400L, player.playbackHeadPositionFrames())
        assertEquals(100L, player.currentPositionMs.value)
        assertEquals(0.75f, adapters[1].lastVolume, 0.001f)
        assertEquals(1.25f, adapters[1].lastSpeed, 0.001f)
        assertTrue(adapters[1].playing)

        // Write 1600 frames = 100ms at 16kHz mono and advance new adapter by half.
        player.write(PcmChunk(ByteArray(3200), sampleRate = 16000, channels = 1))
        adapters[1].headFrames = 800L
        assertEquals(4000L, player.totalFramesWritten)
        assertEquals(3200L, player.playbackHeadPositionFrames())
        assertEquals(150L, player.currentPositionMs.value)
    }

    @Test
    fun `format change in fallback mode drains via wall clock and preserves continuity`() {
        var nowNs = 0L
        val player = AudioTrackPcmPlayer(
            adapterFactory = { _, _ -> null },
            nanoTimeProvider = { nowNs },
            drainSleepMs = { ms -> nowNs += ms * 1_000_000L }
        )

        player.init(24000, 1)
        player.play()
        player.write(PcmChunk(ByteArray(4800), sampleRate = 24000, channels = 1)) // 2400 frames = 100ms

        player.init(16000, 1)
        assertEquals(2400L, player.playbackHeadPositionFrames())
        assertEquals(100L, player.currentPositionMs.value)
        assertEquals(0L, player.bufferedMs)

        player.write(PcmChunk(ByteArray(6400), sampleRate = 16000, channels = 1)) // 3200 frames = 200ms
        nowNs += 100_000_000L
        assertEquals(5600L, player.totalFramesWritten)
        assertEquals(4000L, player.playbackHeadPositionFrames()) // 2400 + 1600
        assertEquals(200L, player.currentPositionMs.value) // 100ms + 100ms
    }

    @Test
    fun `stalled hardware head during format switch exits promptly and stop resets session`() {
        var nowNs = 0L
        val adapters = mutableListOf<FakeAdapter>()
        val player = AudioTrackPcmPlayer(
            adapterFactory = { rate, ch -> FakeAdapter(rate, ch).also { adapters += it } },
            nanoTimeProvider = { nowNs },
            drainSleepMs = { ms -> nowNs += ms * 1_000_000L }
        )

        player.init(24000, 1)
        player.play()
        player.write(PcmChunk(ByteArray(4800), sampleRate = 24000, channels = 1))
        // Keep adapter[0].headFrames = 0 forever to simulate stalled hardware head.

        player.init(16000, 1)
        assertTrue(adapters[0].released)
        // Discarded old frames are retired so new track starts with 0 pending bufferedMs.
        assertEquals(2400L, player.totalFramesWritten)
        assertEquals(2400L, player.playbackHeadPositionFrames())
        assertEquals(0L, player.bufferedMs)

        player.stop()
        assertEquals(0L, player.totalFramesWritten)
        assertEquals(0L, player.playbackHeadPositionFrames())
        assertEquals(0L, player.currentPositionMs.value)
    }

    @Test
    fun `large chunk write sub-chunks incrementally updates frames and aborts promptly on flush`() {
        val observedFramesDuringWrite = mutableListOf<Long>()
        val writtenSliceSizes = mutableListOf<Int>()
        lateinit var player: AudioTrackPcmPlayer

        val adapter = object : AudioTrackAdapter {
            var head = 0L
            override fun write(data: ByteArray, offset: Int, size: Int): Int {
                writtenSliceSizes += size
                observedFramesDuringWrite += player.totalFramesWritten
                head += size / 2L
                // Flush halfway through to verify mid-batch abort leaves clean state
                if (writtenSliceSizes.size == 2) {
                    player.flush()
                }
                return size
            }
            override fun play() {}
            override fun pause() {}
            override fun flush() { head = 0L }
            override fun stop() {}
            override fun release() {}
            override fun setSpeed(speed: Float): Boolean = true
            override fun setVolume(volume: Float) {}
            override val playbackHeadPositionFrames: Long get() = head
        }

        player = AudioTrackPcmPlayer(AudioTrackAdapterFactory { _, _ -> adapter })
        assertTrue(player.supportsFrameTracking)
        player.init(24000, 1)
        player.play()

        // 8000 frames = 16000 bytes (larger than one 1024-frame / 2048-byte sub-chunk)
        val accepted = player.write(PcmChunk(ByteArray(16000), sampleRate = 24000, channels = 1))

        assertTrue("Expected bounded sub-chunk writes instead of single 16KB write", writtenSliceSizes.all { it <= 2048 })
        assertEquals(2, writtenSliceSizes.size)
        // Second sub-chunk observed frames credited by the first sub-chunk BEFORE write() returned
        assertEquals(listOf(0L, 1024L), observedFramesDuringWrite)
        // Flush during sub-chunk 2 retired sub-chunk 1's 1024 frames and prevented sub-chunk 2 from leaving phantom buffered frames
        assertEquals(1024L, player.totalFramesWritten)
        assertEquals(1024L, player.playbackHeadPositionFrames())
        assertEquals(0L, player.bufferedMs)
        assertEquals(4096, accepted)

        // Subsequent write after flush continues monotonically from 1024L
        player.write(PcmChunk(ByteArray(1000), sampleRate = 24000, channels = 1))
        assertEquals(1524L, player.totalFramesWritten)
    }

    @Test
    fun `odd-byte unaligned chunks carry trailing bytes cleanly across writes without sample shift`() {
        val writtenBytesOut = java.io.ByteArrayOutputStream()
        val adapter = object : AudioTrackAdapter {
            override fun write(data: ByteArray, offset: Int, size: Int): Int {
                writtenBytesOut.write(data, offset, size)
                return size
            }
            override fun play() {}
            override fun pause() {}
            override fun flush() {}
            override fun stop() {}
            override fun release() {}
            override fun setSpeed(speed: Float): Boolean = true
            override fun setVolume(volume: Float) {}
            override val playbackHeadPositionFrames: Long = 0L
        }

        val player = AudioTrackPcmPlayer(AudioTrackAdapterFactory { _, _ -> adapter })
        player.init(24000, 1) // frameBytes = 2
        player.write(PcmChunk(byteArrayOf(10, 20, 30), 24000, 1))
        player.write(PcmChunk(byteArrayOf(40, 50), 24000, 1))
        player.write(PcmChunk(byteArrayOf(60), 24000, 1))

        assertTrue(byteArrayOf(10, 20, 30, 40, 50, 60).contentEquals(writtenBytesOut.toByteArray()))
        assertEquals(3L, player.totalFramesWritten)

        // Verify fallback mode also preserves carried bytes cleanly
        val fallbackPlayer = AudioTrackPcmPlayer { _, _ -> null }
        fallbackPlayer.init(24000, 1)
        fallbackPlayer.write(PcmChunk(byteArrayOf(1, 2, 3), 24000, 1))
        assertEquals(1L, fallbackPlayer.totalFramesWritten)
        fallbackPlayer.write(PcmChunk(byteArrayOf(4), 24000, 1))
        assertEquals(2L, fallbackPlayer.totalFramesWritten)
    }

    @Test
    fun `unity 1_0x speed avoids calling adapter setSpeed while non-unity transitions still update`() {
        val speedCalls = mutableListOf<Float>()
        val adapter = object : AudioTrackAdapter {
            override fun write(data: ByteArray, offset: Int, size: Int): Int = size
            override fun play() {}
            override fun pause() {}
            override fun flush() {}
            override fun stop() {}
            override fun release() {}
            override fun setSpeed(speed: Float): Boolean {
                speedCalls += speed
                return true
            }
            override fun setVolume(volume: Float) {}
            override val playbackHeadPositionFrames: Long = 0L
        }

        val player = AudioTrackPcmPlayer(AudioTrackAdapterFactory { _, _ -> adapter })
        player.init(24000, 1)
        player.setPlaybackSpeed(1.0f)
        player.setPlaybackSpeed(1.0002f)
        assertTrue("1.0x speed must not invoke adapter.setSpeed", speedCalls.isEmpty())

        player.setPlaybackSpeed(1.5f)
        player.setPlaybackSpeed(1.5f) // duplicate skipped
        player.setPlaybackSpeed(1.0f) // returning from custom speed applies 1.0f once
        player.setPlaybackSpeed(1.0f) // already back at 1.0f, skipped
        assertEquals(listOf(1.5f, 1.0f), speedCalls)

        // Fresh init at 1.0x must again skip adapter.setSpeed
        speedCalls.clear()
        player.init(24000, 1)
        player.setPlaybackSpeed(1.0f)
        assertTrue(speedCalls.isEmpty())
    }

    @Test
    fun `onUtteranceEnd delegates to adapter without altering speech frame totals or failing in fallback`() {
        var endUtteranceCalls = 0
        val adapter = object : AudioTrackAdapter {
            var head = 0L
            override fun write(data: ByteArray, offset: Int, size: Int): Int = size
            override fun play() {}
            override fun pause() {}
            override fun flush() {}
            override fun stop() {}
            override fun release() {}
            override fun setSpeed(speed: Float): Boolean = true
            override fun setVolume(volume: Float) {}
            override fun endUtterance() {
                endUtteranceCalls++
            }
            override val playbackHeadPositionFrames: Long get() = head
        }

        val player = AudioTrackPcmPlayer(AudioTrackAdapterFactory { _, _ -> adapter })
        player.init(24000, 1)
        player.play()
        player.write(PcmChunk(ByteArray(4800), sampleRate = 24000, channels = 1)) // 2400 speech frames
        player.onUtteranceEnd()
        adapter.head = 2400L

        assertEquals(1, endUtteranceCalls)
        assertEquals(2400L, player.totalFramesWritten)
        assertEquals(2400L, player.playbackHeadPositionFrames())

        // Fallback mode (no adapter) safely ignores onUtteranceEnd()
        val fallbackPlayer = AudioTrackPcmPlayer { _, _ -> null }
        fallbackPlayer.init(24000, 1)
        fallbackPlayer.write(PcmChunk(ByteArray(4800), sampleRate = 24000, channels = 1))
        fallbackPlayer.onUtteranceEnd()
        assertEquals(2400L, fallbackPlayer.totalFramesWritten)
    }
}
