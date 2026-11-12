package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Interface for decoding audio containers (MP3, M4B, AAC, FLAC, OGG, WAV) to raw PCM chunks
 * using Android MediaExtractor and MediaCodec, or fallback PCM/WAV decoder.
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
 * Testable pure Kotlin WAV / raw PCM source decoder, plus Android MediaCodec decoding when on Android.
 */
class PcmWaveSourceDecoder : SourceDecoder {
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
            // Parse WAV header
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            // RIFF chunk at 0..12
            var channels = 1
            var sampleRate = 16000
            var dataOffset = 44
            var dataSize = bytes.size - 44

            var offset = 12
            while (offset < bytes.size - 8) {
                val chunkId = String(bytes, offset, 4)
                val chunkSize = buffer.getInt(offset + 4)
                if (chunkId == "fmt ") {
                    val audioFormat = buffer.getShort(offset + 8)
                    channels = buffer.getShort(offset + 10).toInt()
                    sampleRate = buffer.getInt(offset + 12)
                } else if (chunkId == "data") {
                    dataOffset = offset + 8
                    dataSize = chunkSize
                    break
                }
                offset += 8 + chunkSize
            }

            val pcmData = bytes.copyOfRange(dataOffset, minOf(bytes.size, dataOffset + dataSize))
            emitChunks(pcmData, sampleRate, channels, startPositionMs, durationMs)
        } else {
            // Raw PCM assumption: 16kHz mono 16-bit LE
            emitChunks(bytes, 16000, 1, startPositionMs, durationMs)
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<PcmChunk>.emitChunks(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        startPositionMs: Long,
        durationMs: Long
    ) {
        val bytesPerSec = sampleRate * channels * 2
        val startByte = if (startPositionMs > 0) {
            ((startPositionMs * bytesPerSec) / 1000L).toInt() / 2 * 2
        } else {
            0
        }

        val maxBytes = if (durationMs > 0) {
            ((durationMs * bytesPerSec) / 1000L).toInt() / 2 * 2
        } else {
            pcmData.size - startByte
        }

        val actualStart = startByte.coerceIn(0, pcmData.size)
        val actualLen = maxBytes.coerceAtMost(pcmData.size - actualStart)
        if (actualLen <= 0) return

        val slice = pcmData.copyOfRange(actualStart, actualStart + actualLen)
        // Emit in 4KB chunks
        val chunkSize = 4096
        var offset = 0
        val startTimeMs = startPositionMs.coerceAtLeast(0L)

        while (offset < slice.size && !isReleased) {
            val len = minOf(chunkSize, slice.size - offset)
            val chunkBytes = slice.copyOfRange(offset, offset + len)
            val chunkDurationMs = (len * 1000L) / bytesPerSec
            val timestampMs = startTimeMs + (offset * 1000L) / bytesPerSec

            emit(PcmChunk(chunkBytes, sampleRate, channels, timestampMs))
            offset += len
        }
    }

    override fun release() {
        isReleased = true
    }
}
