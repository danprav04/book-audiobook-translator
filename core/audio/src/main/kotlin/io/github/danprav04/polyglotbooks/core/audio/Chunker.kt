package io.github.danprav04.polyglotbooks.core.audio

import java.io.ByteArrayOutputStream

/**
 * Audio chunker dividing incoming PCM byte streams into fixed-size chunks.
 *
 * Mathematical specification:
 * 16 kHz sample rate × 100 ms duration × 1 channel (mono) × 2 bytes/sample (16-bit LE)
 * = 1600 samples = 3200 bytes per chunk.
 *
 * Incoming bytes of arbitrary sizes are buffered. Whenever the buffer contains at least
 * [chunkSizeBytes] (default: 3200), complete chunks are extracted and returned.
 * Any remainder is retained in the internal buffer until more bytes arrive or until [flush]
 * is invoked upon reaching the end of the stream.
 *
 * Thread safety: This class is not thread-safe and should be called sequentially or synchronized.
 */
class Chunker(val chunkSizeBytes: Int = 3200) {

    init {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be strictly positive" }
    }

    private var buffer = ByteArrayOutputStream()

    /**
     * Appends [input] bytes to the internal buffer and returns all complete [chunkSizeBytes] chunks.
     * Any partial remainder is kept in the buffer.
     */
    fun addBytes(input: ByteArray): List<ByteArray> {
        if (input.isEmpty()) {
            return emptyList()
        }

        buffer.write(input)
        val currentBytes = buffer.toByteArray()

        if (currentBytes.size < chunkSizeBytes) {
            return emptyList()
        }

        val chunksCount = currentBytes.size / chunkSizeBytes
        val result = ArrayList<ByteArray>(chunksCount)

        for (i in 0 until chunksCount) {
            val fromIndex = i * chunkSizeBytes
            val toIndex = fromIndex + chunkSizeBytes
            result.add(currentBytes.copyOfRange(fromIndex, toIndex))
        }

        val remainderOffset = chunksCount * chunkSizeBytes
        buffer.reset()
        if (remainderOffset < currentBytes.size) {
            buffer.write(currentBytes, remainderOffset, currentBytes.size - remainderOffset)
        }

        return result
    }

    /**
     * Flushes and returns any remaining bytes left in the internal buffer upon end-of-stream.
     * Returns null if the buffer is empty.
     */
    fun flush(): ByteArray? {
        if (buffer.size() == 0) {
            return null
        }
        val remainder = buffer.toByteArray()
        buffer.reset()
        return remainder
    }

    /**
     * Clears all buffered bytes.
     */
    fun reset() {
        buffer.reset()
    }
}
