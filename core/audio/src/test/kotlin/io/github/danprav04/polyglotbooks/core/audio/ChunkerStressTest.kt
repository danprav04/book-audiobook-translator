package io.github.danprav04.polyglotbooks.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ChunkerStressTest {

    @Test
    fun testEmpiricalChunkerStreamingStress() {
        val chunker = Chunker(chunkSizeBytes = 3200)
        val rng = Random(42)

        // Stream arbitrary random byte buffers of varying chunk sizes:
        // 1 byte, 3199 bytes, 3201 bytes, 100,000 bytes, and random sizes
        val sliceSizes = listOf(1, 3199, 3201, 100_000, 1, 3200, 6400, 1600, 1599, 1601, 75_432)
        val totalBytesTarget = sliceSizes.sum()

        val fullStream = ByteArray(totalBytesTarget)
        rng.nextBytes(fullStream)

        val outputChunks = mutableListOf<ByteArray>()
        var offset = 0

        for (size in sliceSizes) {
            val slice = fullStream.copyOfRange(offset, offset + size)
            offset += size

            val chunks = chunker.addBytes(slice)
            for (chunk in chunks) {
                // Invariant 1: Every single output chunk must be exactly 3200 bytes
                assertEquals(3200, chunk.size)
                outputChunks.add(chunk)
            }
        }

        // Flush remainder
        val flushed = chunker.flush()
        if (flushed != null) {
            assertTrue("Flushed remainder must be strictly less than chunkSizeBytes (3200)", flushed.size < 3200)
            assertTrue("Flushed remainder must be strictly positive", flushed.size > 0)
        }

        // Invariant 2: Total flushed output bytes strictly equals total input bytes
        val totalOutputBytes = outputChunks.sumOf { it.size } + (flushed?.size ?: 0)
        assertEquals(totalBytesTarget, totalOutputBytes)

        // Invariant 3: Byte-for-byte exact equality across the entire stream
        val reconstructed = ByteArray(totalOutputBytes)
        var writePos = 0
        for (chunk in outputChunks) {
            System.arraycopy(chunk, 0, reconstructed, writePos, chunk.size)
            writePos += chunk.size
        }
        if (flushed != null) {
            System.arraycopy(flushed, 0, reconstructed, writePos, flushed.size)
            writePos += flushed.size
        }

        assertArrayEquals(fullStream, reconstructed)
    }

    @Test
    fun testChunkerBoundaryConditions() {
        val chunker = Chunker(3200)

        // 1. Multiple empty byte arrays
        assertTrue(chunker.addBytes(ByteArray(0)).isEmpty())
        assertTrue(chunker.addBytes(ByteArray(0)).isEmpty())
        assertNull(chunker.flush())

        // 2. Exactly 3199 bytes -> 0 chunks, flush returns 3199
        val b3199 = ByteArray(3199) { 0x01 }
        val c1 = chunker.addBytes(b3199)
        assertTrue(c1.isEmpty())
        val f1 = chunker.flush()
        assertNotNull(f1)
        assertEquals(3199, f1?.size)

        // 3. Exactly 3201 bytes -> 1 chunk of 3200, flush returns 1 byte
        val b3201 = ByteArray(3201) { 0x02 }
        val c2 = chunker.addBytes(b3201)
        assertEquals(1, c2.size)
        assertEquals(3200, c2[0].size)
        val f2 = chunker.flush()
        assertNotNull(f2)
        assertEquals(1, f2?.size)
        assertEquals(0x02.toByte(), f2?.get(0))

        // 4. Reset cleans state completely
        chunker.addBytes(ByteArray(1000))
        chunker.reset()
        assertNull(chunker.flush())
    }
}
