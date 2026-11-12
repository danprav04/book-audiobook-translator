package io.github.danprav04.polyglotbooks.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {

    @Test
    fun testEmptyInputProducesEmptyList() {
        val chunker = Chunker(chunkSizeBytes = 3200)
        val chunks = chunker.addBytes(ByteArray(0))
        assertTrue(chunks.isEmpty())
        assertNull(chunker.flush())
    }

    @Test
    fun testExact3200BytesProducesOneChunk() {
        val chunker = Chunker(chunkSizeBytes = 3200)
        val input = ByteArray(3200) { (it % 128).toByte() }

        val chunks = chunker.addBytes(input)
        assertEquals(1, chunks.size)
        assertEquals(3200, chunks[0].size)
        assertArrayEquals(input, chunks[0])

        assertNull(chunker.flush())
    }

    @Test
    fun testChunker5000BytesThen2000BytesThenFlush() {
        val chunker = Chunker(chunkSizeBytes = 3200)

        // 1. Send 5000 bytes: expect 1 chunk of 3200 bytes, 1800 bytes buffered.
        val input1 = ByteArray(5000) { (it % 100).toByte() }
        val chunks1 = chunker.addBytes(input1)

        assertEquals(1, chunks1.size)
        assertEquals(3200, chunks1[0].size)
        assertArrayEquals(input1.copyOfRange(0, 3200), chunks1[0])

        // 2. Send 2000 bytes: expect 1 chunk of 3200 bytes (1800 + 1400), 600 bytes buffered.
        val input2 = ByteArray(2000) { ((it + 50) % 100).toByte() }
        val chunks2 = chunker.addBytes(input2)

        assertEquals(1, chunks2.size)
        assertEquals(3200, chunks2[0].size)

        // Combined expected chunk: remainder of input1 (1800 bytes) + first 1400 bytes of input2
        val expectedChunk2 = input1.copyOfRange(3200, 5000) + input2.copyOfRange(0, 1400)
        assertArrayEquals(expectedChunk2, chunks2[0])

        // 3. Flush: expect remaining 600 bytes.
        val remainder = chunker.flush()
        assertEquals(600, remainder?.size)
        val expectedRemainder = input2.copyOfRange(1400, 2000)
        assertArrayEquals(expectedRemainder, remainder)

        // 4. Invariant: total output bytes == total input bytes
        val totalInput = input1.size + input2.size
        val totalOutput = chunks1.sumOf { it.size } + chunks2.sumOf { it.size } + (remainder?.size ?: 0)
        assertEquals(totalInput, totalOutput)
        assertEquals(7000, totalOutput)
    }

    @Test
    fun testChunkerReset() {
        val chunker = Chunker(chunkSizeBytes = 3200)
        chunker.addBytes(ByteArray(1500))
        chunker.reset()
        assertNull(chunker.flush())
    }

    @Test
    fun testArbitraryStreamingByteSlicesInvariant() {
        val chunker = Chunker(chunkSizeBytes = 3200)
        val fullData = ByteArray(10_000) { (it % 256).toByte() }

        val collectedChunks = mutableListOf<ByteArray>()
        // Feed in irregular packet sizes: 500, 1100, 2400, 700, 3000, 2300
        val packetSizes = listOf(500, 1100, 2400, 700, 3000, 2300)
        var offset = 0
        for (size in packetSizes) {
            val slice = fullData.copyOfRange(offset, offset + size)
            offset += size
            collectedChunks.addAll(chunker.addBytes(slice))
        }

        val flushed = chunker.flush()
        val allOutputBytes = collectedChunks.flatMap { it.toList() } + (flushed?.toList() ?: emptyList())

        assertEquals(fullData.size, allOutputBytes.size)
        assertArrayEquals(fullData, allOutputBytes.toByteArray())
    }
}
