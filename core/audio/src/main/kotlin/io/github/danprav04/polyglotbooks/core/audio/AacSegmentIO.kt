package io.github.danprav04.polyglotbooks.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes PCM audio chunks into compact segments (ADTS AAC via MediaCodec on Android,
 * with automatic RIFF/WAV fallback when MediaCodec is unavailable on JVM unit tests) for offline caching.
 */
interface AacSegmentWriter {
    fun open(outputFile: File, sampleRate: Int = 24000, channels: Int = 1, bitrate: Int = 64000)
    fun writeChunk(chunk: PcmChunk)
    fun close()
}

/**
 * Decodes cached audio segments back to raw 16-bit PCM chunks for playback.
 */
interface AacSegmentReader {
    fun open(inputFile: File)
    fun readNextChunk(): PcmChunk?
    fun close()
}

/**
 * Production [AacSegmentWriter] that encodes ADTS AAC via [MediaCodecAacSegmentWriter] when the
 * Android MediaCodec AAC encoder is available, and transparently falls back to [WavSegmentWriter]
 * on pure JVM unit tests or devices without an AAC encoder.
 */
class DefaultAacSegmentWriter(
    private val preferAac: Boolean = true
) : AacSegmentWriter {
    private var delegate: AacSegmentWriter? = null

    override fun open(outputFile: File, sampleRate: Int, channels: Int, bitrate: Int) {
        close()
        outputFile.parentFile?.mkdirs()
        if (preferAac) {
            try {
                val aacWriter = MediaCodecAacSegmentWriter()
                aacWriter.open(outputFile, sampleRate, channels, bitrate)
                delegate = aacWriter
                return
            } catch (_: Throwable) {
                // MediaCodec unavailable (e.g. JVM unit tests) — fall back to WAV below.
            }
        }
        val wavWriter = WavSegmentWriter()
        wavWriter.open(outputFile, sampleRate, channels, bitrate)
        delegate = wavWriter
    }

    override fun writeChunk(chunk: PcmChunk) {
        val writer = delegate ?: throw IllegalStateException("AacSegmentWriter is not open")
        writer.writeChunk(chunk)
    }

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }
}

/**
 * Production [AacSegmentReader] that inspects the segment file header and decodes ADTS/AAC via
 * [MediaCodecAacSegmentReader] or RIFF/WAV/PCM via [WavSegmentReader].
 */
class DefaultAacSegmentReader(
    private val chunkDurationMs: Int = 100
) : AacSegmentReader {
    private var delegate: AacSegmentReader? = null

    override fun open(inputFile: File) {
        close()
        if (!inputFile.exists() || inputFile.length() == 0L) {
            delegate = null
            return
        }
        val header = ByteArray(4)
        val read = inputFile.inputStream().use { it.read(header) }
        val isRiffWav = read == 4 &&
            header[0] == 'R'.code.toByte() &&
            header[1] == 'I'.code.toByte() &&
            header[2] == 'F'.code.toByte() &&
            header[3] == 'F'.code.toByte()
        val isAdts = read >= 2 &&
            header[0] == 0xFF.toByte() &&
            (header[1].toInt() and 0xF0) == 0xF0

        if (!isRiffWav && (isAdts || inputFile.name.endsWith(".aac", ignoreCase = true))) {
            try {
                val aacReader = MediaCodecAacSegmentReader()
                aacReader.open(inputFile)
                delegate = aacReader
                return
            } catch (_: Throwable) {
                // Fall back to WAV/raw PCM reader below.
            }
        }
        val wavReader = WavSegmentReader(chunkDurationMs)
        wavReader.open(inputFile)
        delegate = wavReader
    }

    override fun readNextChunk(): PcmChunk? = delegate?.readNextChunk()

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }
}

/**
 * Backwards-compatible alias of [DefaultAacSegmentWriter] that writes real WAV/AAC segments
 * instead of silently dropping metadata.
 */
class StubAacSegmentWriter : AacSegmentWriter by DefaultAacSegmentWriter(preferAac = false)

/**
 * Backwards-compatible alias of [DefaultAacSegmentReader] that reads cached segments back into
 * [PcmChunk]s instead of returning `null`.
 */
class StubAacSegmentReader : AacSegmentReader by DefaultAacSegmentReader()

/**
 * Helper for writing and reading complete cached segments.
 */
object AudioSegmentIO {
    fun writeAll(
        outputFile: File,
        pcmData: ByteArray,
        sampleRate: Int = 24000,
        channels: Int = 1,
        writer: AacSegmentWriter = DefaultAacSegmentWriter()
    ) {
        writer.open(outputFile, sampleRate, channels)
        try {
            if (pcmData.isNotEmpty()) {
                writer.writeChunk(PcmChunk(pcmData, sampleRate, channels, 0L))
            }
        } finally {
            writer.close()
        }
    }

    fun readAllChunks(
        inputFile: File,
        reader: AacSegmentReader = DefaultAacSegmentReader()
    ): List<PcmChunk> {
        if (!inputFile.exists()) return emptyList()
        val chunks = mutableListOf<PcmChunk>()
        reader.open(inputFile)
        try {
            while (true) {
                val next = reader.readNextChunk() ?: break
                chunks.add(next)
            }
        } finally {
            reader.close()
        }
        return chunks
    }

    fun readAllPcmBytes(
        inputFile: File,
        reader: AacSegmentReader = DefaultAacSegmentReader()
    ): ByteArray? {
        if (!inputFile.exists()) return null
        val chunks = readAllChunks(inputFile, reader)
        if (chunks.isEmpty()) return ByteArray(0)
        val out = ByteArrayOutputStream()
        for (chunk in chunks) {
            out.write(chunk.data)
        }
        return out.toByteArray()
    }
}

/**
 * Pure Kotlin WAV (RIFF, 16-bit LE PCM) segment writer. JVM testable and always available.
 */
class WavSegmentWriter : AacSegmentWriter {
    private var raf: RandomAccessFile? = null
    private var dataBytes = 0L
    private var sampleRate = 24000
    private var channels = 1

    override fun open(outputFile: File, sampleRate: Int, channels: Int, bitrate: Int) {
        close()
        outputFile.parentFile?.mkdirs()
        this.sampleRate = if (sampleRate > 0) sampleRate else 24000
        this.channels = maxOf(1, channels)
        dataBytes = 0L
        val file = RandomAccessFile(outputFile, "rw")
        file.setLength(0)
        file.write(buildHeader(0))
        raf = file
    }

    override fun writeChunk(chunk: PcmChunk) {
        val file = raf ?: throw IllegalStateException("WavSegmentWriter is not open")
        if (chunk.data.isEmpty()) return
        if (dataBytes == 0L && chunk.sampleRate > 0) {
            this.sampleRate = chunk.sampleRate
            this.channels = maxOf(1, chunk.channels)
        }
        file.write(chunk.data)
        dataBytes += chunk.data.size
    }

    override fun close() {
        val file = raf ?: return
        try {
            file.seek(0)
            file.write(buildHeader(dataBytes))
        } finally {
            file.close()
            raf = null
        }
    }

    private fun buildHeader(dataSize: Long): ByteArray {
        val byteRate = sampleRate * channels * 2
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt((36 + dataSize).coerceAtMost(0xFFFFFFFFL).toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * 2).toShort())
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize.coerceAtMost(0xFFFFFFFFL).toInt())
        return buffer.array()
    }
}

/**
 * Pure Kotlin WAV / raw PCM segment reader returning ~[chunkDurationMs] ms chunks.
 * Files without a RIFF header are treated as raw 24 kHz mono 16-bit PCM.
 */
class WavSegmentReader(private val chunkDurationMs: Int = 100) : AacSegmentReader {
    private var pcm: ByteArray = ByteArray(0)
    private var offset = 0
    private var sampleRate = 24000
    private var channels = 1

    override fun open(inputFile: File) {
        if (!inputFile.exists()) {
            pcm = ByteArray(0)
            offset = 0
            return
        }
        val bytes = inputFile.readBytes()
        offset = 0
        if (bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            val format = WavFormat.parse(bytes)
            sampleRate = format.sampleRate
            channels = format.channels
            pcm = bytes.copyOfRange(format.dataOffset, minOf(bytes.size, format.dataOffset + format.dataSize))
        } else {
            sampleRate = 24000
            channels = 1
            pcm = bytes
        }
    }

    override fun readNextChunk(): PcmChunk? {
        if (offset >= pcm.size) return null
        val frameBytes = maxOf(1, channels) * 2
        val bytesPerSecond = maxOf(frameBytes.toLong(), sampleRate.toLong() * frameBytes)
        val chunkBytes = maxOf(
            frameBytes,
            ((bytesPerSecond * chunkDurationMs / 1000L) / frameBytes * frameBytes).toInt()
        )
        val end = minOf(pcm.size, offset + chunkBytes)
        val timestampMs = offset * 1000L / bytesPerSecond
        val data = pcm.copyOfRange(offset, end)
        offset = end
        return PcmChunk(data, sampleRate, channels, timestampMs)
    }

    override fun close() {
        pcm = ByteArray(0)
        offset = 0
    }
}

/**
 * ADTS AAC-LC segment writer using the platform [MediaCodec] encoder.
 */
class MediaCodecAacSegmentWriter : AacSegmentWriter {
    private var codec: MediaCodec? = null
    private var output: BufferedOutputStream? = null
    private var sampleRate = 24000
    private var channels = 1
    private var framesQueued = 0L
    private val info = MediaCodec.BufferInfo()

    override fun open(outputFile: File, sampleRate: Int, channels: Int, bitrate: Int) {
        close()
        this.sampleRate = if (sampleRate > 0) sampleRate else 24000
        this.channels = maxOf(1, channels)
        framesQueued = 0L
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, this.sampleRate, this.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (t: Throwable) {
            try { encoder.release() } catch (_: Throwable) {}
            throw t
        }
        outputFile.parentFile?.mkdirs()
        codec = encoder
        output = BufferedOutputStream(FileOutputStream(outputFile))
    }

    override fun writeChunk(chunk: PcmChunk) {
        val encoder = codec ?: throw IllegalStateException("MediaCodecAacSegmentWriter is not open")
        var offset = 0
        val data = chunk.data
        while (offset < data.size) {
            val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val inBuffer = encoder.getInputBuffer(inIndex) ?: continue
                inBuffer.clear()
                val len = minOf(inBuffer.remaining(), data.size - offset)
                inBuffer.put(data, offset, len)
                val ptsUs = framesQueued * 1_000_000L / sampleRate
                encoder.queueInputBuffer(inIndex, 0, len, ptsUs, 0)
                framesQueued += len / (channels * 2)
                offset += len
            }
            drain(endOfStream = false)
        }
    }

    override fun close() {
        val encoder = codec ?: return
        try {
            var queuedEos = false
            var attempts = 0
            while (!queuedEos && attempts++ < 100) {
                val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    encoder.queueInputBuffer(
                        inIndex,
                        0,
                        0,
                        framesQueued * 1_000_000L / sampleRate,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    queuedEos = true
                } else {
                    drain(endOfStream = false)
                }
            }
            if (queuedEos) drain(endOfStream = true)
        } finally {
            try { encoder.stop() } catch (_: Throwable) {}
            try { encoder.release() } catch (_: Throwable) {}
            try { output?.flush(); output?.close() } catch (_: Throwable) {}
            codec = null
            output = null
        }
    }

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        val out = output ?: return
        var idleLoops = 0
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0L)
            if (outIndex >= 0) {
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (!isConfig && info.size > 0) {
                    val buffer = encoder.getOutputBuffer(outIndex)
                    if (buffer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val frame = ByteArray(info.size)
                        buffer.get(frame)
                        out.write(AdtsHeader.build(frame.size, sampleRate, channels))
                        out.write(frame)
                    }
                }
                encoder.releaseOutputBuffer(outIndex, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream || ++idleLoops > 200) return
            }
        }
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}

/**
 * ADTS AAC segment reader using [MediaExtractor] + [MediaCodec] decoder.
 */
class MediaCodecAacSegmentReader : AacSegmentReader {
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var inputDone = false
    private var outputDone = false
    private var sampleRate = 24000
    private var channels = 1
    private val info = MediaCodec.BufferInfo()

    override fun open(inputFile: File) {
        close()
        if (!inputFile.exists()) throw IOException("Segment not found: $inputFile")
        val ex = MediaExtractor()
        try {
            ex.setDataSource(inputFile.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track in segment $inputFile")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()
            codec = decoder
            extractor = ex
            inputDone = false
            outputDone = false
        } catch (t: Throwable) {
            try { ex.release() } catch (_: Throwable) {}
            throw t
        }
    }

    override fun readNextChunk(): PcmChunk? {
        val decoder = codec ?: return null
        val ex = extractor ?: return null
        var spins = 0
        while (!outputDone && spins++ < 1000) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = decoder.getInputBuffer(inIndex)
                    val size = if (buffer != null) ex.readSampleData(buffer, 0) else -1
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                sampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (outIndex >= 0) {
                val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                var chunk: PcmChunk? = null
                if (info.size > 0) {
                    val buffer = decoder.getOutputBuffer(outIndex)
                    if (buffer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buffer.get(data)
                        chunk = PcmChunk(data, sampleRate, channels, info.presentationTimeUs / 1000L)
                    }
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if (eos) outputDone = true
                if (chunk != null) return chunk
            }
        }
        return null
    }

    override fun close() {
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { extractor?.release() } catch (_: Throwable) {}
        codec = null
        extractor = null
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
    }
}

/**
 * Builds 7-byte ADTS headers (no CRC) for raw AAC-LC frames.
 */
object AdtsHeader {
    private val SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    fun build(frameLength: Int, sampleRate: Int, channels: Int): ByteArray {
        val packetLength = frameLength + 7
        val profile = 2 // AAC LC
        val freqIndex = SAMPLE_RATES.indexOf(sampleRate).let { if (it < 0) 6 else it }
        val channelConfig = channels.coerceIn(1, 7)
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            (((profile - 1) shl 6) + (freqIndex shl 2) + (channelConfig shr 2)).toByte(),
            (((channelConfig and 3) shl 6) + (packetLength shr 11)).toByte(),
            ((packetLength and 0x7FF) shr 3).toByte(),
            (((packetLength and 7) shl 5) + 0x1F).toByte(),
            0xFC.toByte()
        )
    }
}
