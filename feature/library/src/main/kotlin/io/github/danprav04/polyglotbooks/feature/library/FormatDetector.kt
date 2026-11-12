package io.github.danprav04.polyglotbooks.feature.library

import java.nio.charset.StandardCharsets

/**
 * FormatDetector inspects raw file bytes (magic bytes / signatures) and falls back
 * to file extensions to detect 12 ebook formats and 7 audiobook formats.
 */
object FormatDetector {

    /**
     * Detects BookFormat from byte header and optional filename/path.
     */
    fun detect(bytes: ByteArray, fileName: String? = null): BookFormat {
        if (bytes.isEmpty()) {
            return fallbackExtension(fileName)
        }

        // 1. PDF: starts with "%PDF-" (0x25 0x50 0x44 0x46 0x2D)
        if (startsWith(bytes, "%PDF-".toByteArray(StandardCharsets.US_ASCII))) {
            return BookFormat.PDF
        }

        // 2. RTF: starts with "{\rtf1"
        if (startsWith(bytes, "{\\rtf1".toByteArray(StandardCharsets.US_ASCII))) {
            return BookFormat.RTF
        }

        // 3. FLAC: starts with "fLaC" (0x66 0x4C 0x61 0x43)
        if (startsWith(bytes, "fLaC".toByteArray(StandardCharsets.US_ASCII))) {
            return BookFormat.FLAC
        }

        // 4. OGG / OPUS: starts with "OggS" (0x4F 0x67 0x67 0x53)
        if (startsWith(bytes, "OggS".toByteArray(StandardCharsets.US_ASCII))) {
            // Check if OpusHead exists nearby or fall back to extension
            if (containsAscii(bytes, "OpusHead", 0, 64) || hasExtension(fileName, "opus")) {
                return BookFormat.OPUS
            }
            return BookFormat.OGG
        }

        // 5. WAV: starts with "RIFF" and at offset 8 contains "WAVE"
        if (bytes.size >= 12 &&
            startsWith(bytes, "RIFF".toByteArray(StandardCharsets.US_ASCII)) &&
            matchesAt(bytes, 8, "WAVE".toByteArray(StandardCharsets.US_ASCII))
        ) {
            return BookFormat.WAV
        }

        // 6. ADTS AAC header: 12 bits syncword (0xFFF -> 0xFF followed by 0xF0..0xFF) with layer == 00 (bits 13,14 == 0)
        // In ADTS: byte 0 is 0xFF, byte 1 has sync (4 bits: 1111), MPEG version (1 bit), layer (2 bits: 00), protection absent (1 bit)
        // So byte 1 & 0xF6 == 0xF0 indicates ADTS AAC (layer == 0).
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xF6) == 0xF0) {
            return BookFormat.AAC
        }

        // 7. MP3: ID3 header ("ID3") at offset 0, or MPEG audio frame sync (0xFF 0xFB/FA/F3/F2)
        if (startsWith(bytes, "ID3".toByteArray(StandardCharsets.US_ASCII))) {
            return BookFormat.MP3
        }
        if (isMp3FrameSync(bytes)) {
            return BookFormat.MP3
        }

        // 8. MOBI / AZW3: PalmDOC / Mobipocket container ("BOOKMOBI" signature at offset 60)
        if (bytes.size >= 68 && matchesAt(bytes, 60, "BOOKMOBI".toByteArray(StandardCharsets.US_ASCII))) {
            // Check KF8 or extension distinction
            if (hasExtension(fileName, "azw3") || hasExtension(fileName, "azw") || hasExtension(fileName, "kf8")) {
                return BookFormat.AZW3
            }
            return BookFormat.MOBI
        }

        // 9. MP4 container: M4B, M4A, AAC ("ftyp" at offset 4)
        if (bytes.size >= 12 && matchesAt(bytes, 4, "ftyp".toByteArray(StandardCharsets.US_ASCII))) {
            val majorBrand = String(bytes, 8, 4, StandardCharsets.US_ASCII)
            return when {
                majorBrand.startsWith("M4B") || hasExtension(fileName, "m4b") -> BookFormat.M4B
                majorBrand.startsWith("M4A") || hasExtension(fileName, "m4a") -> BookFormat.M4A
                hasExtension(fileName, "aac") -> BookFormat.AAC
                else -> BookFormat.M4A
            }
        }

        // 10. ZIP-based containers: PK\x03\x04
        if (startsWith(bytes, byteArrayOf(0x50, 0x4B, 0x03, 0x04))) {
            // EPUB check: check for "mimetype" + "application/epub+zip"
            if (containsAscii(bytes, "application/epub+zip", 0, 256) ||
                (containsAscii(bytes, "mimetype", 0, 100) && hasExtension(fileName, "epub"))
            ) {
                return BookFormat.EPUB
            }
            // Fall back to extension disambiguation for ZIP containers
            val extFormat = fallbackExtension(fileName)
            if (extFormat in setOf(BookFormat.EPUB, BookFormat.CBZ, BookFormat.DOCX, BookFormat.ODT)) {
                return extFormat
            }
            if (containsAscii(bytes, "word/", 0, 500)) return BookFormat.DOCX
            if (containsAscii(bytes, "mimetypeapplication/vnd.oasis.opendocument.text", 0, 200)) return BookFormat.ODT
            return BookFormat.EPUB // Default zip-book format in library context
        }

        // 11. XML-based text: FB2 ("<FictionBook" or "<?xml ... <FictionBook")
        val headText = String(bytes.take(1024).toByteArray(), StandardCharsets.UTF_8).trim()
        if (headText.contains("<FictionBook", ignoreCase = true)) {
            return BookFormat.FB2
        }

        // 12. HTML: "<html>", "<!DOCTYPE html", or "<head"
        if (headText.contains("<!DOCTYPE html", ignoreCase = true) ||
            headText.contains("<html", ignoreCase = true) ||
            headText.contains("<head", ignoreCase = true)
        ) {
            return BookFormat.HTML
        }

        // 13. CUE sheet: starts with "REM", "PERFORMER", "TITLE", "FILE" and extension or CUE keywords
        if (headText.contains("FILE \"") && headText.contains("TRACK 01")) {
            return BookFormat.CUE
        }

        // Fall back to extension if recognized
        val extDetected = fallbackExtension(fileName)
        if (extDetected != BookFormat.UNKNOWN) {
            return extDetected
        }

        // Check if plain text / markdown
        if (isAsciiOrUtf8Text(bytes)) {
            if (hasExtension(fileName, "md") || hasExtension(fileName, "markdown")) {
                return BookFormat.MD
            }
            if (hasExtension(fileName, "txt")) {
                return BookFormat.TXT
            }
        }

        return BookFormat.UNKNOWN
    }

    private fun startsWith(array: ByteArray, prefix: ByteArray): Boolean {
        if (array.size < prefix.size) return false
        for (i in prefix.indices) {
            if (array[i] != prefix[i]) return false
        }
        return true
    }

    private fun matchesAt(array: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset < 0 || array.size < offset + pattern.size) return false
        for (i in pattern.indices) {
            if (array[offset + i] != pattern[i]) return false
        }
        return true
    }

    private fun containsAscii(array: ByteArray, str: String, startOffset: Int, maxSearchBytes: Int): Boolean {
        val target = str.toByteArray(StandardCharsets.US_ASCII)
        val limit = minOf(array.size - target.size, startOffset + maxSearchBytes)
        if (startOffset > limit) return false
        for (i in startOffset..limit) {
            var match = true
            for (j in target.indices) {
                if (array[i + j] != target[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private fun isMp3FrameSync(bytes: ByteArray): Boolean {
        if (bytes.size < 2) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        // MPEG audio frame sync: 11 bits set (0xFF followed by 0xE0 mask)
        // Layer 3 (MP3) sync pattern usually 0xFF 0xFB, 0xFA, 0xF3, 0xF2
        return b0 == 0xFF && (b1 and 0xE0) == 0xE0
    }

    private fun isAsciiOrUtf8Text(bytes: ByteArray): Boolean {
        val sampleSize = minOf(bytes.size, 1024)
        var nonPrintable = 0
        for (i in 0 until sampleSize) {
            val b = bytes[i].toInt() and 0xFF
            // null byte or control characters other than \t, \r, \n
            if (b == 0 || (b < 32 && b != 9 && b != 10 && b != 13)) {
                nonPrintable++
            }
        }
        return nonPrintable == 0
    }

    private fun hasExtension(fileName: String?, ext: String): Boolean {
        if (fileName == null) return false
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex == -1 || dotIndex == fileName.length - 1) return false
        val fileExt = fileName.substring(dotIndex + 1).lowercase()
        return fileExt == ext.lowercase()
    }

    private fun fallbackExtension(fileName: String?): BookFormat {
        if (fileName.isNullOrBlank()) return BookFormat.UNKNOWN
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex == -1 || dotIndex == fileName.length - 1) return BookFormat.UNKNOWN
        val ext = fileName.substring(dotIndex + 1)
        return BookFormat.fromExtension(ext)
    }
}
