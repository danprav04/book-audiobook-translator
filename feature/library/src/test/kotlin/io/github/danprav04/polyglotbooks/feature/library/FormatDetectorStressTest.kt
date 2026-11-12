package io.github.danprav04.polyglotbooks.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class FormatDetectorStressTest {

    @Test
    fun testZeroLengthByteArray() {
        val empty = byteArrayOf()
        assertEquals(BookFormat.UNKNOWN, FormatDetector.detect(empty, null))
        assertEquals(BookFormat.UNKNOWN, FormatDetector.detect(empty, ""))
        assertEquals(BookFormat.UNKNOWN, FormatDetector.detect(empty, "file_with_no_ext"))
        assertEquals(BookFormat.EPUB, FormatDetector.detect(empty, "mybook.EPUB"))
        assertEquals(BookFormat.PDF, FormatDetector.detect(empty, "doc.Pdf"))
    }

    @Test
    fun testTruncatedHeadersNeverThrow() {
        // Test 1-byte, 2-byte, 3-byte prefixes of known signatures
        val prefixes = listOf(
            byteArrayOf(0x25), // '%' for PDF
            byteArrayOf(0x25, 0x50), // '%P'
            byteArrayOf(0x7B), // '{' for RTF
            byteArrayOf(0x50, 0x4B), // 'PK' for ZIP
            byteArrayOf(0x4F, 0x67), // 'Og' for OGG
            byteArrayOf('f'.code.toByte(), 'L'.code.toByte()), // 'fL' for FLAC
            byteArrayOf('R'.code.toByte(), 'I'.code.toByte()), // 'RI' for WAV
            byteArrayOf(0xFF.toByte()), // MP3/AAC sync
            byteArrayOf(0xFF.toByte(), 0x00.toByte()), // invalid sync
            byteArrayOf(0x00, 0x00, 0x00, 0x20, 'f'.code.toByte()) // truncated MP4 ftyp
        )

        for (prefix in prefixes) {
            try {
                val format = FormatDetector.detect(prefix, null)
                assertNotNull(format)
            } catch (e: Throwable) {
                fail("Truncated header ${prefix.toList()} threw exception: ${e.message}")
            }
        }
    }

    @Test
    fun testCorruptHeadersNeverThrowAndReturnSafely() {
        val rng = Random(12345)
        // Generate random garbage byte buffers of sizes 1 to 500
        for (i in 0 until 50) {
            val size = rng.nextInt(500) + 1
            val garbage = ByteArray(size)
            rng.nextBytes(garbage)

            try {
                val format = FormatDetector.detect(garbage, null)
                assertNotNull(format)
            } catch (e: Throwable) {
                fail("Corrupt garbage bytes of size $size threw exception: ${e.message}")
            }
        }
    }

    @Test
    fun testMixedCaseFileExtensions() {
        val testCases = listOf(
            "test.EpUb" to BookFormat.EPUB,
            "sample.MoBi" to BookFormat.MOBI,
            "file.AzW3" to BookFormat.AZW3,
            "book.Fb2" to BookFormat.FB2,
            "comic.Cbz" to BookFormat.CBZ,
            "doc.PdF" to BookFormat.PDF,
            "notes.TxT" to BookFormat.TXT,
            "doc.Md" to BookFormat.MD,
            "page.HtMl" to BookFormat.HTML,
            "word.DocX" to BookFormat.DOCX,
            "open.OdT" to BookFormat.ODT,
            "rich.RtF" to BookFormat.RTF,
            "audio.M4B" to BookFormat.M4B,
            "audio.M4a" to BookFormat.M4A,
            "stream.AaC" to BookFormat.AAC,
            "song.Mp3" to BookFormat.MP3,
            "track.OGG" to BookFormat.OGG,
            "speech.OpUs" to BookFormat.OPUS,
            "lossless.FlaC" to BookFormat.FLAC,
            "audio.WAV" to BookFormat.WAV,
            "disc.Cue" to BookFormat.CUE,
            "weird.XYZ" to BookFormat.UNKNOWN,
            "no_extension" to BookFormat.UNKNOWN,
            ".hidden" to BookFormat.UNKNOWN,
            "trailing_dot." to BookFormat.UNKNOWN
        )

        val emptyBytes = byteArrayOf()
        for ((fileName, expectedFormat) in testCases) {
            val format = FormatDetector.detect(emptyBytes, fileName)
            assertEquals("Mismatch for filename $fileName", expectedFormat, format)
        }
    }
}
