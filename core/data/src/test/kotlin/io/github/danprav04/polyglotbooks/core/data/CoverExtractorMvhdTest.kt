package io.github.danprav04.polyglotbooks.core.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CoverExtractorMvhdTest {

    @Test
    fun parseMvhdDurationMsHandlesVersion0AndVersion1() {
        // Version 0: timescale = 1000, duration = 23_466_000 -> 23_466_000 ms
        val v0 = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(32)
            put("mvhd".toByteArray())
            putInt(0) // version 0 + flags
            putInt(0) // creation_time
            putInt(0) // modification_time
            putInt(1000) // timescale
            putInt(23_466_000) // duration
        }.array()
        assertEquals(23_466_000L, CoverExtractor.parseMvhdDurationMs(v0))

        // Version 1: timescale = 600, duration = 14_079_600 -> 23_466_000 ms
        val v1 = ByteBuffer.allocate(44).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(44)
            put("mvhd".toByteArray())
            put(1.toByte()) // version 1
            put(0.toByte()); put(0.toByte()); put(0.toByte())
            putLong(0L) // creation_time
            putLong(0L) // modification_time
            putInt(600) // timescale
            putLong(14_079_600L) // duration
        }.array()
        assertEquals(23_466_000L, CoverExtractor.parseMvhdDurationMs(v1))
    }

    @Test
    fun extractChaptersOverridesLastChplDurationUsingMvhdDuration() {
        val mvhd = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(32)
            put("mvhd".toByteArray())
            putInt(0)
            putInt(0)
            putInt(0)
            putInt(1000)
            putInt(23_466_000)
        }.array()

        val title1 = "Opening".toByteArray()
        val title2 = "Finale".toByteArray()
        val chplBody = ByteBuffer.allocate(5 + 9 + title1.size + 9 + title2.size)
            .order(ByteOrder.BIG_ENDIAN).apply {
                put(0.toByte()) // version 0 legacy count=2
                put(0.toByte()); put(0.toByte()); put(0.toByte())
                put(2.toByte())
                putLong(0L)
                put(title1.size.toByte())
                put(title1)
                putLong(19_083_000L * 10_000L) // 100ns units -> 5:18:03
                put(title2.size.toByte())
                put(title2)
            }.array()
        val chplBox = ByteBuffer.allocate(8 + chplBody.size).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(8 + chplBody.size)
            put("chpl".toByteArray())
            put(chplBody)
        }.array()

        val combined = ByteArrayOutputStream().apply {
            write(mvhd)
            write(chplBox)
        }.toByteArray()

        val chapters = CoverExtractor.extractChapters(combined, "book-m4b")
        assertEquals(2, chapters.size)
        assertEquals(19_083_000L, chapters[0].endMs)
        assertEquals(19_083_000L, chapters[1].startMs)
        assertEquals(23_466_000L, chapters[1].endMs)
    }
}
