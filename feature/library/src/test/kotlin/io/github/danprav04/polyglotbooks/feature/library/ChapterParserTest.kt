package io.github.danprav04.polyglotbooks.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

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
                java.nio.ByteBuffer.allocate(4).putInt(10).array() + // start time 10ms
                java.nio.ByteBuffer.allocate(4).putInt(50).array() + // end time 50ms
                java.nio.ByteBuffer.allocate(4).putInt(0).array() +  // start offset
                java.nio.ByteBuffer.allocate(4).putInt(0).array()    // end offset
        
        val frameId = "CHAP".toByteArray(Charsets.ISO_8859_1)
        val sizeBytes = java.nio.ByteBuffer.allocate(4).putInt(body.size).array()
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
    fun testMp4ChapterParser() {
        val count = 1
        val startTime100ns = 20000000L
        val title = "Mp4 Chapter 1"
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        
        val chplData = byteArrayOf(0, 0, 0, 0, count.toByte()) +
                java.nio.ByteBuffer.allocate(8).putLong(startTime100ns).array() +
                byteArrayOf(titleBytes.size.toByte()) + titleBytes

        val atomHeader = java.nio.ByteBuffer.allocate(4).putInt(8 + chplData.size).array() + "chpl".toByteArray()
        val syntheticBytes = atomHeader + chplData

        val parser = Mp4ChapterParser()
        val chapters = parser.parseChapters(syntheticBytes)

        assertEquals(1, chapters.size)
        assertEquals("Mp4 Chapter 1", chapters[0].title)
        assertEquals(2000L, chapters[0].startMs)
    }
}
