package io.github.danprav04.polyglotbooks.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Interface for decoding audio containers (MP3, M4B, AAC, FLAC, OGG, OPUS, WAV) to raw
 * 16-bit little-endian PCM chunks.
 */
interface SourceDecoder {
    /**
     * Decodes the audio track at [filePath] (a local file path or a `content://` / `file://` URI)
     * starting from [startPositionMs] up to [durationMs] (or until EOF when [durationMs] <= 0).
     */
    fun decode(filePath: String, startPositionMs: Long = 0L, durationMs: Long = -1L): Flow<PcmChunk>

    /**
     * Releases any active decoder resources / aborts in-flight decodes.
     */
    fun release()
}

/**
 * Testable pure Kotlin WAV / raw PCM source decoder (no Android framework dependencies).
 * Used for JVM unit tests and as a fallback for raw PCM / WAV files.
 */
class PcmWaveSourceDecoder : SourceDecoder {
    @Volatile
    private var isReleased = false

    override fun decode(filePath: String, startPositionMs: Long, durationMs: Long): Flow<PcmChunk> = flow {
        if (isReleased) return@flow
        val file = File(filePath)
        if (!file.exists()) return@flow

        val bytes = file.readBytes()
        // Check if WAV header exists ("RIFF" ... "WAVE")
        if (bytes.size > 44 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) {
            val header = WavFormat.parse(bytes)
            val pcmData = bytes.copyOfRange(header.dataOffset, minOf(bytes.size, header.dataOffset + header.dataSize))
            emitChunks(pcmData, header.sampleRate, header.channels, startPositionMs, durationMs)
        } else {
            // Raw PCM assumption: 16kHz mono 16-bit LE
            emitChunks(bytes, 16000, 1, startPositionMs, durationMs)
        }
    }

    private suspend fun FlowCollector<PcmChunk>.emitChunks(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        startPositionMs: Long,
        durationMs: Long
    ) {
        val frameBytes = maxOf(1, channels) * 2
        val bytesPerSec = sampleRate.toLong() * frameBytes
        val startByte = if (startPositionMs > 0) {
            ((startPositionMs * bytesPerSec) / 1000L / frameBytes * frameBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            0
        }

        val maxBytes = if (durationMs > 0) {
            ((durationMs * bytesPerSec) / 1000L / frameBytes * frameBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            pcmData.size - startByte
        }

        val actualStart = startByte.coerceIn(0, pcmData.size)
        val actualLen = maxBytes.coerceAtMost(pcmData.size - actualStart)
        if (actualLen <= 0) return

        val slice = pcmData.copyOfRange(actualStart, actualStart + actualLen)
        // Emit in 4KB chunks (frame aligned)
        val chunkSize = (4096 / frameBytes) * frameBytes
        var offset = 0
        val startTimeMs = startPositionMs.coerceAtLeast(0L)

        while (offset < slice.size && !isReleased) {
            currentCoroutineContext().ensureActive()
            val len = minOf(chunkSize, slice.size - offset)
            val chunkBytes = slice.copyOfRange(offset, offset + len)
            val timestampMs = startTimeMs + (offset * 1000L) / bytesPerSec

            emit(PcmChunk(chunkBytes, sampleRate, channels, timestampMs))
            offset += len
        }
    }

    override fun release() {
        isReleased = true
    }
}

/**
 * Minimal RIFF/WAVE header parser.
 */
internal data class WavFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Int,
    val dataSize: Int
) {
    companion object {
        fun parse(bytes: ByteArray): WavFormat {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var channels = 1
            var sampleRate = 16000
            var bitsPerSample = 16
            var dataOffset = 44
            var dataSize = (bytes.size - 44).coerceAtLeast(0)

            var offset = 12
            while (offset <= bytes.size - 8) {
                val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
                val chunkSize = buffer.getInt(offset + 4)
                if (chunkId == "fmt " && offset + 24 <= bytes.size) {
                    channels = buffer.getShort(offset + 10).toInt().coerceAtLeast(1)
                    sampleRate = buffer.getInt(offset + 12)
                    bitsPerSample = buffer.getShort(offset + 22).toInt()
                } else if (chunkId == "data") {
                    dataOffset = offset + 8
                    dataSize = if (chunkSize < 0 || dataOffset + chunkSize > bytes.size) {
                        bytes.size - dataOffset
                    } else {
                        chunkSize
                    }
                    break
                }
                if (chunkSize < 0) break
                // RIFF chunks are word aligned
                offset += 8 + chunkSize + (chunkSize and 1)
            }
            return WavFormat(sampleRate, channels, bitsPerSample, dataOffset, dataSize)
        }
    }
}

/**
 * Production [SourceDecoder] using Android [MediaExtractor] + [MediaCodec] to decode every
 * container/codec supported by the platform (MP3, M4B/M4A/AAC, FLAC, OGG/Vorbis, OPUS, WAV ...)
 * from either a `content://` (SAF) / `file://` URI or a plain local file path.
 *
 * Output is interleaved 16-bit little-endian PCM at the source sample rate / channel count.
 * Use a [Resampler] to convert to 16 kHz mono before streaming to Gemini Live.
 */
class MediaCodecSourceDecoder(private val context: Context) : SourceDecoder {

    /** Incremented by [release] to abort in-flight decode loops; new decodes keep working. */
    private val generation = AtomicInteger(0)

    override fun decode(filePath: String, startPositionMs: Long, durationMs: Long): Flow<PcmChunk> = flow {
        val myGeneration = generation.get()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        try {
            setDataSource(extractor, filePath)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("No audio track found in $filePath")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Audio track has no MIME type in $filePath")

            val startUs = startPositionMs.coerceAtLeast(0L) * 1000L
            val endUs = if (durationMs > 0) startUs + durationMs * 1000L else Long.MAX_VALUE
            if (startUs > 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }

            var sampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var channels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            codecStarted = true

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (generation.get() != myGeneration) break

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        val size = if (inBuffer != null) extractor.readSampleData(inBuffer, 0) else -1
                        val sampleTimeUs = extractor.sampleTime
                        if (size < 0 || sampleTimeUs > endUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sampleRate = outFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = outFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = outFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    outIndex >= 0 -> {
                        val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        var pcm: ByteArray? = null
                        val presentationUs = info.presentationTimeUs
                        if (info.size > 0) {
                            val outBuffer = decoder.getOutputBuffer(outIndex)
                            if (outBuffer != null) {
                                outBuffer.position(info.offset)
                                outBuffer.limit(info.offset + info.size)
                                val raw = ByteArray(info.size)
                                outBuffer.get(raw)
                                pcm = PcmConversions.toPcm16(raw, pcmEncoding)
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)

                        if (pcm != null && pcm.isNotEmpty()) {
                            val chunk = trimToWindow(pcm, presentationUs, startUs, endUs, sampleRate, channels)
                            if (chunk != null) emit(chunk)
                            if (presentationUs >= endUs) outputDone = true
                        }
                        if (isEos) outputDone = true
                    }
                    // INFO_TRY_AGAIN_LATER / INFO_OUTPUT_BUFFERS_CHANGED: loop again
                }
            }
        } finally {
            try {
                if (codecStarted) codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun release() {
        generation.incrementAndGet()
    }

    private fun setDataSource(extractor: MediaExtractor, filePath: String) {
        val hasScheme = filePath.startsWith("content://") ||
            filePath.startsWith("file://") ||
            filePath.startsWith("android.resource://")
        if (hasScheme) {
            extractor.setDataSource(context, Uri.parse(filePath), null)
        } else {
            val file = File(filePath)
            if (!file.exists()) throw IOException("Audio file not found: $filePath")
            extractor.setDataSource(file.absolutePath)
        }
    }

    /**
     * Drops decoded pre-roll before [startUs] (the extractor seeks to the previous sync frame)
     * and anything after [endUs].
     */
    private fun trimToWindow(
        pcm: ByteArray,
        presentationUs: Long,
        startUs: Long,
        endUs: Long,
        sampleRate: Int,
        channels: Int
    ): PcmChunk? {
        val frameBytes = maxOf(1, channels) * 2
        val totalFrames = pcm.size / frameBytes
        if (totalFrames <= 0 || sampleRate <= 0) return null

        var startFrame = 0
        if (presentationUs < startUs) {
            startFrame = (((startUs - presentationUs) * sampleRate) / 1_000_000L).coerceAtMost(totalFrames.toLong()).toInt()
        }
        var endFrame = totalFrames
        if (endUs != Long.MAX_VALUE) {
            val framesUntilEnd = ((endUs - presentationUs) * sampleRate) / 1_000_000L
            endFrame = framesUntilEnd.coerceIn(0L, totalFrames.toLong()).toInt()
        }
        if (endFrame <= startFrame) return null

        val data = if (startFrame == 0 && endFrame == totalFrames && pcm.size == totalFrames * frameBytes) {
            pcm
        } else {
            pcm.copyOfRange(startFrame * frameBytes, endFrame * frameBytes)
        }
        val timestampUs = maxOf(presentationUs, startUs)
        return PcmChunk(data, sampleRate, channels, timestampUs / 1000L)
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        try {
            if (containsKey(key)) getInteger(key) else default
        } catch (_: Throwable) {
            default
        }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}

/**
 * PCM sample format conversions (pure Kotlin, JVM testable).
 */
object PcmConversions {
    private const val ENCODING_PCM_16BIT = 2 // AudioFormat.ENCODING_PCM_16BIT
    private const val ENCODING_PCM_8BIT = 3 // AudioFormat.ENCODING_PCM_8BIT
    private const val ENCODING_PCM_FLOAT = 4 // AudioFormat.ENCODING_PCM_FLOAT
    private const val ENCODING_PCM_24BIT_PACKED = 21 // AudioFormat.ENCODING_PCM_24BIT_PACKED
    private const val ENCODING_PCM_32BIT = 22 // AudioFormat.ENCODING_PCM_32BIT

    /**
     * Converts decoder output in the given Android `AudioFormat.ENCODING_*` to 16-bit LE PCM.
     */
    fun toPcm16(raw: ByteArray, encoding: Int): ByteArray = when (encoding) {
        ENCODING_PCM_FLOAT -> floatToPcm16(raw)
        ENCODING_PCM_8BIT -> pcm8ToPcm16(raw)
        ENCODING_PCM_24BIT_PACKED -> pcm24ToPcm16(raw)
        ENCODING_PCM_32BIT -> pcm32ToPcm16(raw)
        else -> raw
    }

    fun floatToPcm16(raw: ByteArray): ByteArray {
        val samples = raw.size / 4
        val inBuf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val out = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) {
            val f = inBuf.float.coerceIn(-1f, 1f)
            out.putShort((f * 32767f).toInt().toShort())
        }
        return out.array()
    }

    private fun pcm8ToPcm16(raw: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(raw.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (b in raw) {
            out.putShort((((b.toInt() and 0xFF) - 128) shl 8).toShort())
        }
        return out.array()
    }

    private fun pcm24ToPcm16(raw: ByteArray): ByteArray {
        val samples = raw.size / 3
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            // Little endian: keep the two most significant bytes
            out[i * 2] = raw[i * 3 + 1]
            out[i * 2 + 1] = raw[i * 3 + 2]
        }
        return out
    }

    private fun pcm32ToPcm16(raw: ByteArray): ByteArray {
        val samples = raw.size / 4
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            out[i * 2] = raw[i * 4 + 2]
            out[i * 2 + 1] = raw[i * 4 + 3]
        }
        return out
    }
}
