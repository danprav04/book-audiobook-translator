package io.github.danprav04.polyglotbooks.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class FormatDetectorTest {

    @Test
    fun testEmptyBytesReturnsUnknownOrExtension() {
        assertEquals(BookFormat.UNKNOWN, FormatDetector.detect(byteArrayOf()))
        assertEquals(BookFormat.EPUB, FormatDetector.detect(byteArrayOf(), "mybook.epub"))
        assertEquals(BookFormat.UNKNOWN, FormatDetector.detect(byteArrayOf(), "unknown.xyz"))
    }

    @Test
    fun testPdfMagicBytes() {
        val pdfBytes = "%PDF-1.7 standard header".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(BookFormat.PDF, FormatDetector.detect(pdfBytes))
    }

    @Test
    fun testRtfMagicBytes() {
        val rtfBytes = "{\\rtf1\\ansi\\deff0...".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(BookFormat.RTF, FormatDetector.detect(rtfBytes))
    }

    @Test
    fun testFlacMagicBytes() {
        val flacBytes = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(), 0x00, 0x01)
        assertEquals(BookFormat.FLAC, FormatDetector.detect(flacBytes))
    }

    @Test
    fun testOggAndOpusMagicBytes() {
        val oggBytes = "OggS\u0000\u0002...".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(BookFormat.OGG, FormatDetector.detect(oggBytes))

        val opusBytes = "OggS\u0000\u0002...OpusHead...".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(BookFormat.OPUS, FormatDetector.detect(opusBytes))

        assertEquals(BookFormat.OPUS, FormatDetector.detect(oggBytes, "audio.opus"))
    }

    @Test
    fun testWavMagicBytes() {
        val wavHeader = ByteArray(16)
        "RIFF".toByteArray(StandardCharsets.US_ASCII).copyInto(wavHeader, 0)
        "WAVE".toByteArray(StandardCharsets.US_ASCII).copyInto(wavHeader, 8)
        assertEquals(BookFormat.WAV, FormatDetector.detect(wavHeader))
    }

    @Test
    fun testMp3MagicBytesAndFrameSync() {
        val id3Bytes = "ID3\u0003\u0000\u0000...".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(BookFormat.MP3, FormatDetector.detect(id3Bytes))

        val frameSyncBytes = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte())
        assertEquals(BookFormat.MP3, FormatDetector.detect(frameSyncBytes))
    }

    @Test
    fun testMobiAndAzw3() {
        val mobiHeader = ByteArray(100)
        "BOOKMOBI".toByteArray(StandardCharsets.US_ASCII).copyInto(mobiHeader, 60)
        assertEquals(BookFormat.MOBI, FormatDetector.detect(mobiHeader, "book.mobi"))
        assertEquals(BookFormat.AZW3, FormatDetector.detect(mobiHeader, "book.azw3"))
    }

    @Test
    fun testMp4AudioContainersM4bM4aAac() {
        val m4bHeader = ByteArray(20)
        "....ftypM4B ....".toByteArray(StandardCharsets.US_ASCII).copyInto(m4bHeader, 0)
        assertEquals(BookFormat.M4B, FormatDetector.detect(m4bHeader, "audio.m4b"))

        val m4aHeader = ByteArray(20)
        "....ftypM4A ....".toByteArray(StandardCharsets.US_ASCII).copyInto(m4aHeader, 0)
        assertEquals(BookFormat.M4A, FormatDetector.detect(m4aHeader, "track.m4a"))

        val aacAdts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50.toByte(), 0x80.toByte())
        assertEquals(BookFormat.AAC, FormatDetector.detect(aacAdts, "stream.aac"))
    }

    @Test
    fun testZipContainersEpubCbzDocxOdt() {
        val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val epubBytes = zipHeader + "mimetypeapplication/epub+zip".toByteArray(StandardCharsets.US_ASCII)
        assertEquals(BookFormat.EPUB, FormatDetector.detect(epubBytes))

        assertEquals(BookFormat.CBZ, FormatDetector.detect(zipHeader, "comic.cbz"))
        assertEquals(BookFormat.DOCX, FormatDetector.detect(zipHeader, "doc.docx"))
        assertEquals(BookFormat.ODT, FormatDetector.detect(zipHeader, "text.odt"))
    }

    @Test
    fun testFb2AndHtml() {
        val fb2Xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">".toByteArray(StandardCharsets.UTF_8)
        assertEquals(BookFormat.FB2, FormatDetector.detect(fb2Xml))

        val htmlDoc = "<!DOCTYPE html>\n<html><head><title>Test</title></head><body>Hello</body></html>".toByteArray(StandardCharsets.UTF_8)
        assertEquals(BookFormat.HTML, FormatDetector.detect(htmlDoc))
    }

    @Test
    fun testCueSheet() {
        val cueText = "REM GENRE Audiobook\nFILE \"part1.mp3\" MP3\n  TRACK 01 AUDIO\n    TITLE \"Chapter 1\"".toByteArray(StandardCharsets.UTF_8)
        assertEquals(BookFormat.CUE, FormatDetector.detect(cueText, "index.cue"))
    }

    @Test
    fun testPlainTextAndMarkdown() {
        val textBytes = "This is a plain english text file with simple thoughts.".toByteArray(StandardCharsets.UTF_8)
        assertEquals(BookFormat.TXT, FormatDetector.detect(textBytes, "notes.txt"))
        assertEquals(BookFormat.MD, FormatDetector.detect(textBytes, "README.md"))
    }

    @Test
    fun testAll12EbookExtensionsFallback() {
        val ebookExts = mapOf(
            "epub" to BookFormat.EPUB,
            "mobi" to BookFormat.MOBI,
            "azw3" to BookFormat.AZW3,
            "fb2" to BookFormat.FB2,
            "cbz" to BookFormat.CBZ,
            "pdf" to BookFormat.PDF,
            "txt" to BookFormat.TXT,
            "md" to BookFormat.MD,
            "html" to BookFormat.HTML,
            "docx" to BookFormat.DOCX,
            "odt" to BookFormat.ODT,
            "rtf" to BookFormat.RTF
        )

        for ((ext, format) in ebookExts) {
            assertEquals("Failed for extension: $ext", format, BookFormat.fromExtension(ext))
            assertEquals("Failed for fallback: $ext", format, FormatDetector.detect(byteArrayOf(), "file.$ext"))
        }
    }

    @Test
    fun testAll7AudiobookExtensionsFallback() {
        val audioExts = mapOf(
            "m4b" to BookFormat.M4B,
            "m4a" to BookFormat.M4A,
            "aac" to BookFormat.AAC,
            "mp3" to BookFormat.MP3,
            "ogg" to BookFormat.OGG,
            "opus" to BookFormat.OPUS,
            "flac" to BookFormat.FLAC,
            "wav" to BookFormat.WAV,
            "cue" to BookFormat.CUE
        )

        for ((ext, format) in audioExts) {
            assertEquals("Failed for extension: $ext", format, BookFormat.fromExtension(ext))
            assertEquals("Failed for fallback: $ext", format, FormatDetector.detect(byteArrayOf(), "audio.$ext"))
        }
    }
}
