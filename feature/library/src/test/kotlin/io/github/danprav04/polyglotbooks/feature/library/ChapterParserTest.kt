package io.github.danprav04.polyglotbooks.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChapterParserTest {

    @Test
    fun testCueSheetParser() {
        val cueContent = """
            TITLE "Test Album"
            PERFORMER "Test Artist"
            FILE "audio.mp3" MP3
              TRACK 01 AUDIO
                TITLE "Chapter One"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Chapter Two"
                INDEX 01 02:30:00
        """.trimIndent()

        val parser = CueSheetChapterParser()
        val chapters = parser.parseChapters(ByteArrayInputStream(cueContent.toByteArray()))

        assertEquals(2, chapters.size)
        assertEquals("Chapter One", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals("Chapter Two", chapters[1].title)
        assertEquals(150000L, chapters[1].startMs)
    }

    @Test
    fun testFolderChapterParser() {
        val entries = listOf(
            AudioFileEntry("01_intro.mp3", "content://audio/1", 60000L),
            AudioFileEntry("02_body.mp3", "content://audio/2", 120000L)
        )

        val parser = FolderChapterParser()
        val chapters = parser.parseChapters(entries)

        assertEquals(2, chapters.size)
        assertEquals("01_intro", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(60000L, chapters[0].endMs)

        assertEquals("02_body", chapters[1].title)
        assertEquals(60000L, chapters[1].startMs)
        assertEquals(180000L, chapters[1].endMs)
    }

    @Test
    fun testId3ChapParser() {
        val body = "ch1\u0000".toByteArray(Charsets.ISO_8859_1) +
                ByteBuffer.allocate(4).putInt(10).array() + // start time 10ms
                ByteBuffer.allocate(4).putInt(50).array() + // end time 50ms
                ByteBuffer.allocate(4).putInt(0).array() +  // start offset
                ByteBuffer.allocate(4).putInt(0).array()    // end offset
        
        val frameId = "CHAP".toByteArray(Charsets.ISO_8859_1)
        val sizeBytes = ByteBuffer.allocate(4).putInt(body.size).array()
        val flags = byteArrayOf(0, 0)
        
        val syntheticBytes = frameId + sizeBytes + flags + body
        val parser = Id3ChapChapterParser()
        val chapters = parser.parseChapters(syntheticBytes)

        assertEquals(1, chapters.size)
        assertEquals("ch1", chapters[0].id)
        assertEquals(10L, chapters[0].startMs)
        assertEquals(50L, chapters[0].endMs)
    }

    @Test
    fun testId3ChapParserCorruptedNonPositiveSizeDoesNotHang() {
        val parser = Id3ChapChapterParser()
        val frameId = "CHAP".toByteArray(Charsets.ISO_8859_1)
        val flags = byteArrayOf(0, 0)

        // Negative size (-10)
        val negSizeBytes = ByteBuffer.allocate(4).putInt(-10).array()
        val corruptBytes1 = frameId + negSizeBytes + flags + ByteArray(20)
        val chapters1 = parser.parseChapters(corruptBytes1)
        assertEquals(0, chapters1.size)

        // Zero size (0)
        val zeroSizeBytes = ByteBuffer.allocate(4).putInt(0).array()
        val corruptBytes2 = frameId + zeroSizeBytes + flags + ByteArray(20)
        val chapters2 = parser.parseChapters(corruptBytes2)
        assertEquals(0, chapters2.size)
    }

    @Test
    fun testDefaultChapterParserWithNullParent() = kotlinx.coroutines.test.runTest {
        val parser = DefaultChapterParser()
        // File in current directory with null parentFile
        val orphanFile = java.io.File("orphan_test_${System.currentTimeMillis()}.m4b")
        orphanFile.writeBytes(ByteArray(0))
        try {
            org.junit.Assert.assertNull(orphanFile.parentFile)
            val chapters = parser.parseChapters(orphanFile, BookFormat.M4B)
            assertEquals(0, chapters.size)
        } finally {
            orphanFile.delete()
        }
    }

    @Test
    fun testMp4ChapterParser() {
        val count = 1
        val startTime100ns = 20000000L
        val title = "Mp4 Chapter 1"
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        
        val chplData = byteArrayOf(0, 0, 0, 0, count.toByte()) +
                ByteBuffer.allocate(8).putLong(startTime100ns).array() +
                byteArrayOf(titleBytes.size.toByte()) + titleBytes

        val atomHeader = ByteBuffer.allocate(4).putInt(8 + chplData.size).array() + "chpl".toByteArray()
        val syntheticBytes = atomHeader + chplData

        val parser = Mp4ChapterParser()
        val chapters = parser.parseChapters(syntheticBytes)

        assertEquals(1, chapters.size)
        assertEquals("Mp4 Chapter 1", chapters[0].title)
        assertEquals(2000L, chapters[0].startMs)
    }

    @Test
    fun testNeroChplVersion0() {
        val count = 2
        val title1 = "Introduction"
        val title1Bytes = title1.toByteArray(Charsets.UTF_8)
        val title2 = "The Theory"
        val title2Bytes = title2.toByteArray(Charsets.UTF_8)

        // Version 0: version (0), flags (0,0,0), reserved (0,0,0,0), count at offset 8 (32-bit int)
        val chplPayload = ByteBuffer.allocate(8 + 4 + (8 + 1 + title1Bytes.size) + (8 + 1 + title2Bytes.size)).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0) // version 0
            put(0); put(0); put(0) // flags
            putInt(0) // 4 bytes reserved (offset 4..7)
            putInt(count) // 32-bit count at offset 8
            // Entry 1
            putLong(0L) // 0 ms
            put(title1Bytes.size.toByte())
            put(title1Bytes)
            // Entry 2
            putLong(50000000L) // 5000 ms (50,000,000 * 100ns)
            put(title2Bytes.size.toByte())
            put(title2Bytes)
        }.array()

        val atomHeader = ByteBuffer.allocate(8).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + chplPayload.size)
            put("chpl".toByteArray(Charsets.ISO_8859_1))
        }.array()

        val parser = Mp4ChapterParser()
        val chapters = parser.parseChapters(atomHeader + chplPayload)

        assertEquals(2, chapters.size)
        assertEquals("Introduction", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(5000L, chapters[0].endMs)
        assertEquals("The Theory", chapters[1].title)
        assertEquals(5000L, chapters[1].startMs)
    }

    @Test
    fun testNeroChplVersion1() {
        val count = 2
        val title1 = "Prologue"
        val title1Bytes = title1.toByteArray(Charsets.UTF_8)
        val title2 = "Chapter One"
        val title2Bytes = title2.toByteArray(Charsets.UTF_8)

        // Version 1: version (1), flags (0,0,0), reserved 8 bytes (0), count at offset 12 (32-bit int)
        val chplPayload = ByteBuffer.allocate(4 + 8 + 4 + (8 + 1 + title1Bytes.size) + (8 + 1 + title2Bytes.size)).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(1) // version 1
            put(0); put(0); put(0) // flags
            putLong(0L) // 8 bytes reserved (offset 4..11)
            putInt(count) // 32-bit count at offset 12
            // Entry 1
            putLong(0L)
            put(title1Bytes.size.toByte())
            put(title1Bytes)
            // Entry 2
            putLong(120000000L) // 12000 ms
            put(title2Bytes.size.toByte())
            put(title2Bytes)
        }.array()

        val atomHeader = ByteBuffer.allocate(8).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + chplPayload.size)
            put("chpl".toByteArray(Charsets.ISO_8859_1))
        }.array()

        val parser = Mp4ChapterParser()
        val chapters = parser.parseChapters(atomHeader + chplPayload)

        assertEquals(2, chapters.size)
        assertEquals("Prologue", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(12000L, chapters[0].endMs)
        assertEquals("Chapter One", chapters[1].title)
        assertEquals(12000L, chapters[1].startMs)
    }

    @Test
    fun testQuickTimeTimedTextTrack() {
        // Samples placed at start of buffer:
        // Sample 1 at offset 0: 2-byte len + "Opening Credits"
        val sample1Str = "Opening Credits"
        val sample1Bytes = sample1Str.toByteArray(Charsets.UTF_8)
        val sample1Data = ByteBuffer.allocate(2 + sample1Bytes.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(sample1Bytes.size.toShort())
            put(sample1Bytes)
        }.array()

        // Sample 2 immediately follows
        val sample2Str = "Session 1"
        val sample2Bytes = sample2Str.toByteArray(Charsets.UTF_8)
        val sample2Data = ByteBuffer.allocate(2 + sample2Bytes.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(sample2Bytes.size.toShort())
            put(sample2Bytes)
        }.array()

        val sampleSection = sample1Data + sample2Data
        val sample1Offset = 0L
        val sample2Offset = sample1Data.size.toLong()

        // Build mdhd atom (timescale = 22050)
        val mdhdPayload = ByteBuffer.allocate(4 + 8 + 4 + 4).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0); put(0); put(0); put(0) // ver 0 + flags
            putInt(0); putInt(0) // creation / mod
            putInt(22050) // timescale
            putInt(66150) // duration
        }.array()
        val mdhdAtom = ByteBuffer.allocate(8 + mdhdPayload.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + mdhdPayload.size)
            put("mdhd".toByteArray())
            put(mdhdPayload)
        }.array()

        // Build hdlr atom (type 'text')
        val hdlrPayload = ByteBuffer.allocate(4 + 4 + 4 + 12).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0); put(0); put(0); put(0) // ver + flags
            put("mhlr".toByteArray())
            put("text".toByteArray()) // handler type
            put(ByteArray(12))
        }.array()
        val hdlrAtom = ByteBuffer.allocate(8 + hdlrPayload.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + hdlrPayload.size)
            put("hdlr".toByteArray())
            put(hdlrPayload)
        }.array()

        // Build stts atom (2 entries: 22050 units = 1000ms, 44100 units = 2000ms)
        val sttsPayload = ByteBuffer.allocate(4 + 4 + 16).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0); put(0); put(0); put(0)
            putInt(2) // entry count
            putInt(1); putInt(22050) // 1 sample with 22050 delta
            putInt(1); putInt(44100) // 1 sample with 44100 delta
        }.array()
        val sttsAtom = ByteBuffer.allocate(8 + sttsPayload.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + sttsPayload.size)
            put("stts".toByteArray())
            put(sttsPayload)
        }.array()

        // Build stco atom pointing to sample offsets
        val stcoPayload = ByteBuffer.allocate(4 + 4 + 8).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0); put(0); put(0); put(0)
            putInt(2) // entry count
            putInt(sample1Offset.toInt())
            putInt(sample2Offset.toInt())
        }.array()
        val stcoAtom = ByteBuffer.allocate(8 + stcoPayload.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + stcoPayload.size)
            put("stco".toByteArray())
            put(stcoPayload)
        }.array()

        // stbl atom containing stts and stco
        val stblInner = sttsAtom + stcoAtom
        val stblAtom = ByteBuffer.allocate(8 + stblInner.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + stblInner.size)
            put("stbl".toByteArray())
            put(stblInner)
        }.array()

        // trak atom containing mdhd, hdlr, stbl
        val trakInner = mdhdAtom + hdlrAtom + stblAtom
        val trakAtom = ByteBuffer.allocate(8 + trakInner.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + trakInner.size)
            put("trak".toByteArray())
            put(trakInner)
        }.array()

        // Combined synthetic file: samples at head, followed by trak
        val syntheticBytes = sampleSection + trakAtom
        val parser = Mp4ChapterParser()
        val chapters = parser.parseChapters(syntheticBytes)

        assertEquals(2, chapters.size)
        assertEquals("Opening Credits", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(1000L, chapters[0].endMs)

        assertEquals("Session 1", chapters[1].title)
        assertEquals(1000L, chapters[1].startMs)
        assertEquals(3000L, chapters[1].endMs)
    }
}
