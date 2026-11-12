package io.github.danprav04.polyglotbooks.core.model

import java.util.Arrays

/**
 * Raw PCM audio chunk with timing and format metadata.
 */
data class PcmChunk(
    val data: ByteArray,
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val timestampMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PcmChunk
        if (!data.contentEquals(other.data)) return false
        if (sampleRate != other.sampleRate) return false
        if (channels != other.channels) return false
        if (timestampMs != other.timestampMs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channels
        result = 31 * result + timestampMs.hashCode()
        return result
    }

    override fun toString(): String {
        return "PcmChunk(dataSize=${data.size}, sampleRate=$sampleRate, channels=$channels, timestampMs=$timestampMs)"
    }
}
