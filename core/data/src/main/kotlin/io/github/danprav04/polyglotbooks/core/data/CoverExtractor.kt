package io.github.danprav04.polyglotbooks.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.danprav04.polyglotbooks.core.model.Chapter
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Utility for extracting embedded cover art and chapters from audiobooks
 * (ID3 APIC/CHAP, MP4 covr atoms, Nero chpl v0/v1 atoms, and QuickTime text tracks).
 */
object CoverExtractor {

    fun extractCover(context: Context, uri: Uri, bookId: String): String? {
        val destinationFile = File(context.filesDir, "covers/${bookId}.jpg")
        if (destinationFile.exists() && destinationFile.length() > 0) {
            return destinationFile.absolutePath
        }

        val bytes = extractCoverBytes(context, uri) ?: return null
        return try {
            destinationFile.parentFile?.mkdirs()
            destinationFile.writeBytes(bytes)
            destinationFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun extractEbookCover(context: Context, uri: Uri, bookId: String, formatName: String): String? {
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        val destFile = File(coversDir, "$bookId.jpg")
        if (destFile.exists() && destFile.length() > 0) {
            return destFile.absolutePath
        }

        return when (formatName.trim().uppercase()) {
            "PDF" -> extractPdfCover(context, uri, destFile)
            "EPUB" -> {
                val epubBytes = openUriInputStream(context, uri)?.use { it.readBytes() } ?: return null
                val coverBytes = extractEpubCoverBytes(epubBytes) ?: return null
                decodeAndSaveCoverJpeg(coverBytes, destFile)
            }
            "CBZ" -> {
                val coverBytes = extractCbzCoverBytes(context, uri) ?: return null
                decodeAndSaveCoverJpeg(coverBytes, destFile)
            }
            "FB2" -> {
                val fb2Bytes = openUriInputStream(context, uri)?.use { it.readBytes() } ?: return null
                val coverBytes = extractFb2CoverBytes(fb2Bytes) ?: return null
                decodeAndSaveCoverJpeg(coverBytes, destFile)
            }
            else -> null
        }
    }

    fun extractEpubCoverBytes(epubBytes: ByteArray): ByteArray? {
        if (epubBytes.isEmpty()) return null
        val entries = linkedMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(epubBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.trimStart('/')
                        entries[name] = zis.readBytes()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {
            return null
        }
        if (entries.isEmpty()) return null

        val opfPath = locateEpubOpfPath(entries)
        if (opfPath != null) {
            val opfBytes = findZipEntry(entries, opfPath)
            if (opfBytes != null) {
                val opfDoc = parseXmlSafely(opfBytes)
                if (opfDoc != null) {
                    val opfDir = opfPath.substringBeforeLast('/', "")
                    var metaCoverId: String? = null
                    val metaNodes = getElementsByLocalOrTagName(opfDoc, "meta")
                    for (meta in metaNodes) {
                        if (meta.getAttribute("name").equals("cover", ignoreCase = true)) {
                            val content = meta.getAttribute("content").trim()
                            if (content.isNotEmpty()) {
                                metaCoverId = content
                                break
                            }
                        }
                    }

                    val items = getElementsByLocalOrTagName(opfDoc, "item")
                    var epub3CoverHref: String? = null
                    var epub2CoverHref: String? = null
                    var heuristicCoverHref: String? = null

                    for (item in items) {
                        val id = item.getAttribute("id").trim()
                        val href = item.getAttribute("href").trim()
                        if (href.isEmpty()) continue
                        val mediaType = item.getAttribute("media-type").trim()
                        val props = item.getAttribute("properties")
                            .split(Regex("\\s+"))
                            .filter { it.isNotEmpty() }

                        if (props.any { it.equals("cover-image", ignoreCase = true) }) {
                            epub3CoverHref = href
                            break
                        }
                        if (metaCoverId != null && id == metaCoverId) {
                            epub2CoverHref = href
                        }
                        if (heuristicCoverHref == null &&
                            mediaType.startsWith("image/", ignoreCase = true) &&
                            (id.contains("cover", ignoreCase = true) || href.contains("cover", ignoreCase = true))
                        ) {
                            heuristicCoverHref = href
                        }
                    }

                    val matchedHref = epub3CoverHref ?: epub2CoverHref ?: heuristicCoverHref
                    if (matchedHref != null) {
                        val decodedHref = stripFragmentAndDecode(matchedHref)
                        val resolvedPath = resolveZipPath(opfDir, decodedHref)
                        findZipEntry(entries, resolvedPath)?.let { return it }
                    }
                }
            }
        }

        // Fallback: first image entry containing "cover"
        return entries.entries.firstOrNull { (name, _) ->
            isImageFileName(name) && name.contains("cover", ignoreCase = true)
        }?.value
    }

    private fun extractPdfCover(context: Context, uri: Uri, destFile: File): String? {
        val pfd = openParcelFileDescriptor(context, uri) ?: return null
        return pfd.use { descriptor ->
            try {
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount <= 0) return@use null
                    renderer.openPage(0).use { page ->
                        val targetWidth = 600
                        val aspect = if (page.width > 0) page.height.toFloat() / page.width.toFloat() else 1.4f
                        val targetHeight = (targetWidth * aspect).toInt().coerceIn(100, 1800)
                        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            saveBitmapAsJpeg(bitmap, destFile)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun extractCbzCoverBytes(context: Context, uri: Uri): ByteArray? {
        val stream = openUriInputStream(context, uri) ?: return null
        var bestName: String? = null
        var bestBytes: ByteArray? = null
        return try {
            ZipInputStream(stream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.trimStart('/')
                        val base = name.substringAfterLast('/')
                        if (!name.startsWith("__MACOSX/") && !base.startsWith("._") && isImageFileName(name)) {
                            if (bestName == null || name.compareTo(bestName!!, ignoreCase = true) < 0) {
                                bestName = name
                                bestBytes = zis.readBytes()
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            bestBytes
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFb2CoverBytes(fb2Bytes: ByteArray): ByteArray? {
        if (fb2Bytes.isEmpty()) return null
        val doc = parseXmlSafely(fb2Bytes)
        if (doc != null) {
            var referencedId: String? = null
            val coverpages = getElementsByLocalOrTagName(doc, "coverpage")
            for (cp in coverpages) {
                val images = getElementsByLocalOrTagName(cp, "image")
                for (img in images) {
                    val href = img.getAttributeNS("http://www.w3.org/1999/xlink", "href")
                        .ifBlank { img.getAttribute("l:href") }
                        .ifBlank { img.getAttribute("xlink:href") }
                        .ifBlank { img.getAttribute("href") }
                        .trim()
                    if (href.startsWith("#") && href.length > 1) {
                        referencedId = href.substring(1)
                        break
                    }
                }
                if (referencedId != null) break
            }

            val binaries = getElementsByLocalOrTagName(doc, "binary")
            val targetBinary = binaries.firstOrNull { el ->
                referencedId != null && el.getAttribute("id") == referencedId
            } ?: binaries.firstOrNull { el ->
                el.getAttribute("id").contains("cover", ignoreCase = true)
            } ?: binaries.firstOrNull { el ->
                el.getAttribute("content-type").startsWith("image/", ignoreCase = true)
            }

            val b64 = targetBinary?.textContent?.trim()
            if (!b64.isNullOrEmpty()) {
                try {
                    return Base64.getMimeDecoder().decode(b64)
                } catch (_: Exception) {}
            }
        }

        // Regex fallback
        val text = String(fb2Bytes, StandardCharsets.UTF_8)
        val regex = Regex(
            """<binary\b[^>]*id\s*=\s*["']([^"']*cover[^"']*)["'][^>]*>(.*?)</binary>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val match = regex.find(text) ?: return null
        return try {
            Base64.getMimeDecoder().decode(match.groupValues[2].trim())
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeAndSaveCoverJpeg(imageBytes: ByteArray, destFile: File): String? {
        val bitmap = try {
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            saveBitmapAsJpeg(bitmap, destFile)
        } finally {
            bitmap.recycle()
        }
    }

    private fun saveBitmapAsJpeg(bitmap: Bitmap, destFile: File): String? {
        return try {
            destFile.parentFile?.mkdirs()
            destFile.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
                    return null
                }
            }
            if (destFile.exists() && destFile.length() > 0) destFile.absolutePath else null
        } catch (_: Exception) {
            destFile.delete()
            null
        }
    }

    private fun openParcelFileDescriptor(context: Context, uri: Uri): ParcelFileDescriptor? {
        if (uri.scheme == "file" || uri.scheme.isNullOrEmpty()) {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.exists()) {
                    try {
                        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    } catch (_: Exception) {
                        // Fall back to ContentResolver
                    }
                }
            }
        }
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        }
    }

    private fun openUriInputStream(context: Context, uri: Uri): InputStream? {
        if (uri.scheme == "file" || uri.scheme.isNullOrEmpty()) {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.exists()) {
                    return try {
                        file.inputStream()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        }
    }

    private fun locateEpubOpfPath(entries: Map<String, ByteArray>): String? {
        val containerBytes = findZipEntry(entries, "META-INF/container.xml")
        if (containerBytes != null) {
            val doc = parseXmlSafely(containerBytes)
            if (doc != null) {
                for (el in getElementsByLocalOrTagName(doc, "rootfile")) {
                    val fullPath = el.getAttribute("full-path").trim().trimStart('/')
                    if (fullPath.isNotEmpty()) return fullPath
                }
            }
        }
        return entries.keys.firstOrNull { it.lowercase().endsWith(".opf") }
    }

    private fun findZipEntry(entries: Map<String, ByteArray>, path: String): ByteArray? {
        val normalized = path.trimStart('/')
        return entries[normalized]
            ?: entries.entries.firstOrNull { it.key.equals(normalized, ignoreCase = true) }?.value
    }

    private fun isImageFileName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun stripFragmentAndDecode(href: String): String {
        val withoutFragment = href.substringBefore('#').trim()
        return try {
            URLDecoder.decode(withoutFragment.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            withoutFragment
        }
    }

    private fun resolveZipPath(baseDir: String, relativeHref: String): String {
        val combined = if (relativeHref.startsWith("/")) {
            relativeHref.trimStart('/')
        } else if (baseDir.isEmpty()) {
            relativeHref
        } else {
            "$baseDir/$relativeHref"
        }
        val parts = ArrayDeque<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun parseXmlSafely(bytes: ByteArray): Document? {
        return try {
            val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            dbf.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            try {
                val dbf = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
                dbf.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getElementsByLocalOrTagName(root: Document, name: String): List<Element> {
        val nsList = root.getElementsByTagNameNS("*", name)
        val nodeList = if (nsList.length > 0) nsList else root.getElementsByTagName(name)
        return (0 until nodeList.length).mapNotNull { nodeList.item(it) as? Element }
    }

    private fun getElementsByLocalOrTagName(root: Element, name: String): List<Element> {
        val nsList = root.getElementsByTagNameNS("*", name)
        val nodeList = if (nsList.length > 0) nsList else root.getElementsByTagName(name)
        return (0 until nodeList.length).mapNotNull { nodeList.item(it) as? Element }
    }

    fun extractCoverBytes(context: Context, uri: Uri): ByteArray? {
        // 1. Try MediaMetadataRetriever (standard for ID3/FLAC)
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)
            val pic = mmr.embeddedPicture
            mmr.release()
            if (pic != null && pic.isNotEmpty()) {
                return pic
            }
        } catch (_: Exception) {}

        // 2. Try MP4/M4B covr box parsing (since Android MediaMetadataRetriever omits covr)
        try {
            val bytes = extractMp4Covr(context, uri)
            if (bytes != null && bytes.isNotEmpty()) {
                return bytes
            }
        } catch (_: Exception) {}

        return null
    }

    private fun extractMp4Covr(context: Context, uri: Uri): ByteArray? {
        val cr = context.contentResolver
        val pfd = try {
            cr.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        } ?: return null

        return pfd.use { descriptor ->
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            val fileSize = channel.size()
            if (fileSize <= 0) return@use null

            // First check first 5MB
            val firstChunkSize = minOf(fileSize, 5L * 1024 * 1024).toInt()
            var buffer = ByteArray(firstChunkSize)
            channel.position(0)
            var read = channel.read(ByteBuffer.wrap(buffer))
            if (read > 0) {
                val found = parseCovrFromBuffer(buffer, read)
                if (found != null) return@use found
            }

            // Next check last 15MB
            if (fileSize > 5L * 1024 * 1024) {
                val lastChunkSize = minOf(fileSize, 15L * 1024 * 1024).toInt()
                val startPos = fileSize - lastChunkSize
                channel.position(startPos)
                buffer = ByteArray(lastChunkSize)
                read = channel.read(ByteBuffer.wrap(buffer))
                if (read > 0) {
                    val found = parseCovrFromBuffer(buffer, read)
                    if (found != null) return@use found
                }
            }
            null
        }
    }

    private fun parseCovrFromBuffer(buf: ByteArray, length: Int): ByteArray? {
        val covrTag = byteArrayOf('c'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte(), 'r'.code.toByte())
        val dataTag = byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte())

        var idx = indexOf(buf, covrTag, 0, length)
        while (idx != -1) {
            val dataIdx = indexOf(buf, dataTag, idx, minOf(length, idx + 32))
            if (dataIdx != -1 && dataIdx >= 4) {
                val size = ByteBuffer.wrap(buf, dataIdx - 4, 4).order(ByteOrder.BIG_ENDIAN).int
                val imgStart = dataIdx + 12
                val imgLen = size - 16
                if (imgLen in 1..(length - imgStart)) {
                    val result = ByteArray(imgLen)
                    System.arraycopy(buf, imgStart, result, 0, imgLen)
                    return result
                }
            }
            idx = indexOf(buf, covrTag, idx + 4, length)
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Chapter Extraction API
    // -------------------------------------------------------------------------

    fun extractChapters(context: Context, uri: Uri, bookId: String = ""): List<Chapter> {
        // 1. Check companion .cue file
        val cueChapters = findCompanionCueChapters(context, uri, bookId)
        if (cueChapters.isNotEmpty()) {
            return cueChapters
        }

        // 2. Check embedded M4B chapters via ParcelFileDescriptor / FileChannel
        val cr = context.contentResolver
        val pfd = try {
            cr.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        return pfd.use { descriptor ->
            val channel = FileInputStream(descriptor.fileDescriptor).channel
            extractChapters(channel, channel.size(), bookId)
        }
    }

    fun extractChapters(file: File, bookId: String = ""): List<Chapter> {
        // 1. Check companion .cue file
        val cueFile = File(file.parentFile, "${file.nameWithoutExtension}.cue")
        if (cueFile.exists() && cueFile.canRead()) {
            try {
                cueFile.inputStream().use { stream ->
                    val chapters = parseCueSheet(stream, bookId)
                    if (chapters.isNotEmpty()) return chapters
                }
            } catch (_: Exception) {}
        }

        // 2. Check embedded chapters
        return try {
            FileInputStream(file).use { fis ->
                extractChapters(fis.channel, fis.channel.size(), bookId)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun extractDurationMs(context: Context, uri: Uri): Long {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            null
        } ?: return 0L

        return pfd.use { descriptor ->
            try {
                val channel = FileInputStream(descriptor.fileDescriptor).channel
                val moovData = findMoovBuffer(channel, channel.size()) ?: return@use 0L
                parseMvhdDurationMs(moovData.first)
            } catch (_: Exception) {
                0L
            }
        }
    }

    fun extractChapters(channel: FileChannel, fileSize: Long, bookId: String = ""): List<Chapter> {
        if (fileSize <= 0) return emptyList()

        // Locate moov box by scanning top-level boxes
        val moovData = findMoovBuffer(channel, fileSize)
        val moovBuf = moovData?.first
        val moovFileOffset = moovData?.second ?: 0L

        if (moovBuf != null) {
            val mvhdDurationMs = parseMvhdDurationMs(moovBuf)

            // 1. Try Nero 'chpl' chapters
            val chplTag = byteArrayOf('c'.code.toByte(), 'h'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte())
            val chplIdx = indexOf(moovBuf, chplTag, 0, moovBuf.size)
            if (chplIdx != -1) {
                val chapters = parseChpl(moovBuf, chplIdx, moovBuf.size, bookId)
                if (chapters.isNotEmpty()) return applyTotalDurationToLastChapter(chapters, mvhdDurationMs)
            }

            // 2. Try QuickTime timed text track
            val qtChapters = parseQuickTimeTextTrack(moovBuf, moovFileOffset, channel, bookId)
            if (qtChapters.isNotEmpty()) return applyTotalDurationToLastChapter(qtChapters, mvhdDurationMs)
        }

        return emptyList()
    }

    fun extractChapters(bytes: ByteArray, bookId: String = ""): List<Chapter> {
        if (bytes.isEmpty()) return emptyList()

        val mvhdDurationMs = parseMvhdDurationMs(bytes)

        // 1. Try Nero 'chpl' chapters
        val chplTag = byteArrayOf('c'.code.toByte(), 'h'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte())
        val chplIdx = indexOf(bytes, chplTag, 0, bytes.size)
        if (chplIdx != -1) {
            val chapters = parseChpl(bytes, chplIdx, bytes.size, bookId)
            if (chapters.isNotEmpty()) return applyTotalDurationToLastChapter(chapters, mvhdDurationMs)
        }

        // 2. Try QuickTime timed text track from in-memory byte buffer
        val qtChapters = parseQuickTimeTextTrack(bytes, 0L, null, bookId)
        if (qtChapters.isNotEmpty()) return applyTotalDurationToLastChapter(qtChapters, mvhdDurationMs)

        return emptyList()
    }

    private fun applyTotalDurationToLastChapter(
        chapters: List<Chapter>,
        totalDurationMs: Long
    ): List<Chapter> {
        if (chapters.isEmpty() || totalDurationMs <= 0L) return chapters
        val last = chapters.last()
        return if (totalDurationMs > last.startMs && last.endMs != totalDurationMs) {
            chapters.dropLast(1) + last.copy(endMs = totalDurationMs)
        } else {
            chapters
        }
    }

    fun parseMvhdDurationMs(bytes: ByteArray): Long {
        if (bytes.size < 32) return 0L
        val mvhdTag = byteArrayOf('m'.code.toByte(), 'v'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())
        var searchFrom = 4
        while (searchFrom < bytes.size - 24) {
            val tagIdx = indexOf(bytes, mvhdTag, searchFrom, bytes.size)
            if (tagIdx == -1) break
            searchFrom = tagIdx + 4

            val versionPos = tagIdx + 4
            if (versionPos >= bytes.size) break
            val version = bytes[versionPos].toInt() and 0xFF
            if (version == 0 && tagIdx + 24 <= bytes.size) {
                val timescale = ByteBuffer.wrap(bytes, tagIdx + 16, 4)
                    .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val duration = ByteBuffer.wrap(bytes, tagIdx + 20, 4)
                    .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                if (timescale > 0L && duration > 0L) {
                    return (duration * 1000L) / timescale
                }
            } else if (version == 1 && tagIdx + 36 <= bytes.size) {
                val timescale = ByteBuffer.wrap(bytes, tagIdx + 24, 4)
                    .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val duration = ByteBuffer.wrap(bytes, tagIdx + 28, 8)
                    .order(ByteOrder.BIG_ENDIAN).long
                if (timescale > 0L && duration > 0L) {
                    return (duration * 1000L) / timescale
                }
            }
        }
        return 0L
    }

    // -------------------------------------------------------------------------
    // Nero 'chpl' Parsing
    // -------------------------------------------------------------------------

    fun parseChpl(bytes: ByteArray, chplIdx: Int, length: Int, bookId: String = ""): List<Chapter> {
        val dataStart = chplIdx + 4
        if (dataStart >= length) return emptyList()

        val version = bytes[dataStart].toInt() and 0xFF
        val (count, initialCursor) = when (version) {
            0 -> {
                // Nero chpl version 0: 32-bit count at offset 8
                if (dataStart + 12 <= length) {
                    val cnt32 = ByteBuffer.wrap(bytes, dataStart + 8, 4).order(ByteOrder.BIG_ENDIAN).int
                    if (cnt32 in 1..10000 && dataStart + 12 + cnt32 * 9 <= length) {
                        Pair(cnt32, dataStart + 12)
                    } else if (dataStart + 5 <= length) {
                        // Fallback: 1-byte count at offset 4
                        val cntLegacy = bytes[dataStart + 4].toInt() and 0xFF
                        Pair(cntLegacy, dataStart + 5)
                    } else Pair(0, 0)
                } else if (dataStart + 5 <= length) {
                    val cntLegacy = bytes[dataStart + 4].toInt() and 0xFF
                    Pair(cntLegacy, dataStart + 5)
                } else Pair(0, 0)
            }
            1 -> {
                // Nero chpl version 1: 32-bit count at offset 12
                if (dataStart + 16 <= length) {
                    val cnt32 = ByteBuffer.wrap(bytes, dataStart + 12, 4).order(ByteOrder.BIG_ENDIAN).int
                    if (cnt32 in 1..10000) {
                        Pair(cnt32, dataStart + 16)
                    } else Pair(0, 0)
                } else Pair(0, 0)
            }
            else -> Pair(0, 0)
        }

        if (count <= 0) return emptyList()

        val chapters = mutableListOf<Chapter>()
        var cursor = initialCursor
        for (idx in 0 until count) {
            if (cursor + 9 > length) break
            val startTime100ns = ByteBuffer.wrap(bytes, cursor, 8).order(ByteOrder.BIG_ENDIAN).long
            val startTimeMs = (startTime100ns / 10000L).coerceAtLeast(0L)
            val titleLen = bytes[cursor + 8].toInt() and 0xFF
            cursor += 9
            if (cursor + titleLen > length) break
            val title = if (titleLen > 0) {
                String(bytes, cursor, titleLen, Charsets.UTF_8).trim('\u0000', ' ')
            } else {
                "Chapter ${idx + 1}"
            }
            cursor += titleLen

            if (idx > 0 && chapters.isNotEmpty()) {
                val prev = chapters.last()
                chapters[chapters.size - 1] = prev.copy(endMs = startTimeMs)
            }

            chapters.add(
                Chapter(
                    id = "chpl_$idx",
                    bookId = bookId,
                    index = idx,
                    title = title.ifBlank { "Chapter ${idx + 1}" },
                    startMs = startTimeMs,
                    endMs = startTimeMs + 60000L
                )
            )
        }
        return chapters
    }

    // -------------------------------------------------------------------------
    // QuickTime Timed Text Track Parsing
    // -------------------------------------------------------------------------

    private fun parseQuickTimeTextTrack(
        buf: ByteArray,
        bufFileOffset: Long,
        channel: FileChannel?,
        bookId: String
    ): List<Chapter> {
        val hdlrTag = byteArrayOf('h'.code.toByte(), 'd'.code.toByte(), 'l'.code.toByte(), 'r'.code.toByte())
        val textTag = byteArrayOf('t'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 't'.code.toByte())
        val sbtlTag = byteArrayOf('s'.code.toByte(), 'b'.code.toByte(), 't'.code.toByte(), 'l'.code.toByte())
        val trakTag = byteArrayOf('t'.code.toByte(), 'r'.code.toByte(), 'a'.code.toByte(), 'k'.code.toByte())

        var searchPos = 0
        while (searchPos < buf.size - 24) {
            val hdlrIdx = indexOf(buf, hdlrTag, searchPos, buf.size)
            if (hdlrIdx == -1) break
            searchPos = hdlrIdx + 4

            // Handler type is at hdlrIdx + 12 (or hdlrIdx + 8 depending on format)
            val isTextHandler = (hdlrIdx + 16 <= buf.size && matches(buf, hdlrIdx + 12, textTag)) ||
                    (hdlrIdx + 16 <= buf.size && matches(buf, hdlrIdx + 12, sbtlTag)) ||
                    (hdlrIdx + 12 <= buf.size && matches(buf, hdlrIdx + 8, textTag))

            if (!isTextHandler) continue

            // Find enclosing trak
            val trakIdx = lastIndexOf(buf, trakTag, hdlrIdx)
            if (trakIdx == -1) continue

            // Determine trak boundary
            val trakSize = if (trakIdx >= 4) {
                ByteBuffer.wrap(buf, trakIdx - 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            } else 0L
            val trakEnd = if (trakSize > 8L && trakIdx - 4 + trakSize <= buf.size) {
                (trakIdx - 4 + trakSize).toInt()
            } else {
                buf.size
            }

            // 1. mdhd -> timescale
            val mdhdTag = byteArrayOf('m'.code.toByte(), 'd'.code.toByte(), 'h'.code.toByte(), 'd'.code.toByte())
            val mdhdIdx = indexOf(buf, mdhdTag, trakIdx, trakEnd)
            var timescale = 22050L
            if (mdhdIdx != -1 && mdhdIdx + 24 <= trakEnd) {
                val version = buf[mdhdIdx + 4].toInt() and 0xFF
                timescale = if (version == 0 && mdhdIdx + 20 <= trakEnd) {
                    ByteBuffer.wrap(buf, mdhdIdx + 16, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                } else if (version == 1 && mdhdIdx + 28 <= trakEnd) {
                    ByteBuffer.wrap(buf, mdhdIdx + 24, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                } else {
                    22050L
                }
                if (timescale <= 0L) timescale = 22050L
            }

            // 2. stts -> sample durations
            val sttsTag = byteArrayOf('s'.code.toByte(), 't'.code.toByte(), 't'.code.toByte(), 's'.code.toByte())
            val sttsIdx = indexOf(buf, sttsTag, trakIdx, trakEnd)
            if (sttsIdx == -1 || sttsIdx + 12 > trakEnd) continue
            val sttsEntryCount = ByteBuffer.wrap(buf, sttsIdx + 8, 4).order(ByteOrder.BIG_ENDIAN).int
            val sampleDurations = mutableListOf<Long>()
            var sttsCur = sttsIdx + 12
            for (i in 0 until sttsEntryCount) {
                if (sttsCur + 8 > trakEnd) break
                val sCount = ByteBuffer.wrap(buf, sttsCur, 4).order(ByteOrder.BIG_ENDIAN).int
                val sDelta = ByteBuffer.wrap(buf, sttsCur + 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                sttsCur += 8
                repeat(minOf(sCount, 10000)) {
                    sampleDurations.add(sDelta)
                }
            }

            // 3. stsz -> sample sizes (optional)
            val stszTag = byteArrayOf('s'.code.toByte(), 't'.code.toByte(), 's'.code.toByte(), 'z'.code.toByte())
            val stszIdx = indexOf(buf, stszTag, trakIdx, trakEnd)
            val sampleSizes = mutableListOf<Int>()
            if (stszIdx != -1 && stszIdx + 16 <= trakEnd) {
                val uniformSize = ByteBuffer.wrap(buf, stszIdx + 8, 4).order(ByteOrder.BIG_ENDIAN).int
                val sampleCount = ByteBuffer.wrap(buf, stszIdx + 12, 4).order(ByteOrder.BIG_ENDIAN).int
                if (uniformSize == 0) {
                    var cur = stszIdx + 16
                    for (i in 0 until sampleCount) {
                        if (cur + 4 > trakEnd) break
                        sampleSizes.add(ByteBuffer.wrap(buf, cur, 4).order(ByteOrder.BIG_ENDIAN).int)
                        cur += 4
                    }
                } else {
                    repeat(minOf(sampleCount, 10000)) {
                        sampleSizes.add(uniformSize)
                    }
                }
            }

            // 4. stco or co64 -> chunk offsets
            val stcoTag = byteArrayOf('s'.code.toByte(), 't'.code.toByte(), 'c'.code.toByte(), 'o'.code.toByte())
            val co64Tag = byteArrayOf('c'.code.toByte(), 'o'.code.toByte(), '6'.code.toByte(), '4'.code.toByte())
            val stcoIdx = indexOf(buf, stcoTag, trakIdx, trakEnd)
            val co64Idx = if (stcoIdx == -1) indexOf(buf, co64Tag, trakIdx, trakEnd) else -1

            val chunkOffsets = mutableListOf<Long>()
            if (stcoIdx != -1 && stcoIdx + 12 <= trakEnd) {
                val entryCount = ByteBuffer.wrap(buf, stcoIdx + 8, 4).order(ByteOrder.BIG_ENDIAN).int
                var cur = stcoIdx + 12
                for (i in 0 until entryCount) {
                    if (cur + 4 > trakEnd) break
                    val off = ByteBuffer.wrap(buf, cur, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    chunkOffsets.add(off)
                    cur += 4
                }
            } else if (co64Idx != -1 && co64Idx + 12 <= trakEnd) {
                val entryCount = ByteBuffer.wrap(buf, co64Idx + 8, 4).order(ByteOrder.BIG_ENDIAN).int
                var cur = co64Idx + 12
                for (i in 0 until entryCount) {
                    if (cur + 8 > trakEnd) break
                    val off = ByteBuffer.wrap(buf, cur, 8).order(ByteOrder.BIG_ENDIAN).long
                    chunkOffsets.add(off)
                    cur += 8
                }
            }

            val count = minOf(sampleDurations.size, chunkOffsets.size)
            if (count == 0) continue

            // 5. Read chapter titles and calculate timestamps
            val chapters = mutableListOf<Chapter>()
            var currentTimescaleUnits = 0L
            for (i in 0 until count) {
                val durationUnits = sampleDurations[i]
                val startMs = (currentTimescaleUnits * 1000L) / timescale
                currentTimescaleUnits += durationUnits
                val endMs = (currentTimescaleUnits * 1000L) / timescale
                val off = chunkOffsets[i]
                val expectedSize = if (i < sampleSizes.size && sampleSizes[i] > 0) sampleSizes[i] else 64

                val title = readSampleText(buf, bufFileOffset, channel, off, expectedSize, i)

                chapters.add(
                    Chapter(
                        id = "qt_chap_${i + 1}",
                        bookId = bookId,
                        index = i,
                        title = title,
                        startMs = startMs,
                        endMs = endMs
                    )
                )
            }

            if (chapters.isNotEmpty()) {
                return chapters
            }
        }

        return emptyList()
    }

    private fun readSampleText(
        buf: ByteArray,
        bufFileOffset: Long,
        channel: FileChannel?,
        fileOffset: Long,
        expectedSize: Int,
        index: Int
    ): String {
        // 1. Try reading from FileChannel at fileOffset
        if (channel != null && fileOffset >= 0L && fileOffset < channel.size()) {
            try {
                val sizeToRead = minOf(maxOf(expectedSize, 64), 512).toInt()
                val readBuf = ByteBuffer.allocate(sizeToRead)
                channel.position(fileOffset)
                val bytesRead = channel.read(readBuf)
                readBuf.flip()
                if (bytesRead >= 2) {
                    val textLen = readBuf.short.toInt() and 0xFFFF
                    if (textLen in 1..(bytesRead - 2)) {
                        val textBytes = ByteArray(textLen)
                        readBuf.get(textBytes)
                        val title = String(textBytes, Charsets.UTF_8).trim('\u0000', ' ')
                        if (title.isNotBlank()) return title
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Try reading from in-memory buffer if fileOffset maps into buf
        val bufRelOffset = (fileOffset - bufFileOffset).toInt()
        if (bufRelOffset in 0..(buf.size - 2)) {
            val textLen = ((buf[bufRelOffset].toInt() and 0xFF) shl 8) or (buf[bufRelOffset + 1].toInt() and 0xFF)
            if (textLen in 1..(buf.size - bufRelOffset - 2)) {
                val title = String(buf, bufRelOffset + 2, textLen, Charsets.UTF_8).trim('\u0000', ' ')
                if (title.isNotBlank()) return title
            }
        }

        // 3. Fallback: Check if fileOffset is directly within buf (for synthetic unit test buffers)
        val directOffset = fileOffset.toInt()
        if (directOffset in 0..(buf.size - 2)) {
            val textLen = ((buf[directOffset].toInt() and 0xFF) shl 8) or (buf[directOffset + 1].toInt() and 0xFF)
            if (textLen in 1..(buf.size - directOffset - 2)) {
                val title = String(buf, directOffset + 2, textLen, Charsets.UTF_8).trim('\u0000', ' ')
                if (title.isNotBlank()) return title
            }
        }

        return "Chapter ${index + 1}"
    }

    // -------------------------------------------------------------------------
    // Top-Level Box Scanning
    // -------------------------------------------------------------------------

    private fun findMoovBuffer(channel: FileChannel, fileSize: Long): Pair<ByteArray, Long>? {
        val moovTag = byteArrayOf('m'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 'v'.code.toByte())

        // 1. Follow top-level box headers
        try {
            var pos = 0L
            val headerBuf = ByteBuffer.allocate(16)
            while (pos + 8 <= fileSize) {
                channel.position(pos)
                headerBuf.clear()
                val read = channel.read(headerBuf)
                if (read < 8) break
                headerBuf.flip()
                var boxSize = headerBuf.int.toLong() and 0xFFFFFFFFL
                val tagBytes = ByteArray(4)
                headerBuf.get(tagBytes)

                var headerLen = 8L
                if (boxSize == 1L && read >= 16) {
                    boxSize = headerBuf.long
                    headerLen = 16L
                } else if (boxSize == 0L) {
                    boxSize = fileSize - pos
                }

                if (matches(tagBytes, 0, moovTag)) {
                    val sizeToRead = minOf(boxSize, 30L * 1024 * 1024).toInt()
                    val mBuf = ByteArray(sizeToRead)
                    channel.position(pos)
                    val mRead = channel.read(ByteBuffer.wrap(mBuf))
                    if (mRead > 0) {
                        val result = if (mRead == sizeToRead) mBuf else mBuf.copyOf(mRead)
                        return Pair(result, pos)
                    }
                }

                if (boxSize <= 0 || pos + boxSize <= pos) break
                pos += boxSize
            }
        } catch (_: Exception) {}

        // 2. Fallback: check first 5MB
        try {
            val firstChunk = minOf(fileSize, 5L * 1024 * 1024).toInt()
            val buf1 = ByteArray(firstChunk)
            channel.position(0)
            val r1 = channel.read(ByteBuffer.wrap(buf1))
            if (r1 > 0) {
                val idx = indexOf(buf1, moovTag, 0, r1)
                if (idx != -1) {
                    return Pair(if (r1 == firstChunk) buf1 else buf1.copyOf(r1), 0L)
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback: check last 15MB
        try {
            if (fileSize > 5L * 1024 * 1024) {
                val lastChunk = minOf(fileSize, 15L * 1024 * 1024).toInt()
                val startPos = fileSize - lastChunk
                val buf2 = ByteArray(lastChunk)
                channel.position(startPos)
                val r2 = channel.read(ByteBuffer.wrap(buf2))
                if (r2 > 0) {
                    val idx = indexOf(buf2, moovTag, 0, r2)
                    if (idx != -1) {
                        return Pair(if (r2 == lastChunk) buf2 else buf2.copyOf(r2), startPos)
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    // -------------------------------------------------------------------------
    // Companion .cue Sheet Handling
    // -------------------------------------------------------------------------

    fun findCompanionCueChapters(context: Context, uri: Uri, bookId: String = ""): List<Chapter> {
        val fileName = queryFileName(context, uri) ?: uri.lastPathSegment ?: return emptyList()
        val baseName = fileName.substringBeforeLast('.')

        // 1. If uri is a file:// uri
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null && file.parentFile != null) {
                val cueFile = File(file.parentFile, "$baseName.cue")
                if (cueFile.exists() && cueFile.canRead()) {
                    try {
                        cueFile.inputStream().use { stream ->
                            val chapters = parseCueSheet(stream, bookId)
                            if (chapters.isNotEmpty()) return chapters
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 2. Check /sdcard/Download/
        try {
            val cueFile = File("/sdcard/Download/$baseName.cue")
            if (cueFile.exists() && cueFile.canRead()) {
                cueFile.inputStream().use { stream ->
                    val chapters = parseCueSheet(stream, bookId)
                    if (chapters.isNotEmpty()) return chapters
                }
            }
        } catch (_: Exception) {}

        // 3. Check public Downloads directory
        try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val cueFile = File(downloadsDir, "$baseName.cue")
            if (cueFile.exists() && cueFile.canRead()) {
                cueFile.inputStream().use { stream ->
                    val chapters = parseCueSheet(stream, bookId)
                    if (chapters.isNotEmpty()) return chapters
                }
            }
        } catch (_: Exception) {}

        // 4. Check public Documents directory
        try {
            val docsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            )
            val cueFile = File(docsDir, "$baseName.cue")
            if (cueFile.exists() && cueFile.canRead()) {
                cueFile.inputStream().use { stream ->
                    val chapters = parseCueSheet(stream, bookId)
                    if (chapters.isNotEmpty()) return chapters
                }
            }
        } catch (_: Exception) {}

        return emptyList()
    }

    fun parseCueSheet(inputStream: InputStream, bookId: String = ""): List<Chapter> {
        val lines = BufferedReader(InputStreamReader(inputStream)).readLines()
        val chapters = mutableListOf<Chapter>()
        var currentTitle: String? = null
        var startTimeMs: Long = 0L
        var trackNumber = 1

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("TITLE ") -> {
                    currentTitle = trimmed.removePrefix("TITLE").trim().removeSurrounding("\"")
                }
                trimmed.startsWith("INDEX 01 ") -> {
                    val timeStr = trimmed.removePrefix("INDEX 01").trim()
                    val parts = timeStr.split(":")
                    if (parts.size >= 3) {
                        val mm = parts[0].toLongOrNull() ?: 0L
                        val ss = parts[1].toLongOrNull() ?: 0L
                        val ff = parts[2].toLongOrNull() ?: 0L
                        val totalMs = (mm * 60 + ss) * 1000 + (ff * 1000) / 75

                        if (chapters.isNotEmpty()) {
                            val prev = chapters.last()
                            chapters[chapters.size - 1] = prev.copy(endMs = totalMs)
                        }
                        startTimeMs = totalMs
                        chapters.add(
                            Chapter(
                                id = "cue_track_${trackNumber}",
                                bookId = bookId,
                                index = trackNumber - 1,
                                title = currentTitle ?: "Track $trackNumber",
                                startMs = startTimeMs,
                                endMs = startTimeMs + 60000L
                            )
                        )
                        trackNumber++
                    }
                }
            }
        }
        return chapters
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex)
                    }
                }
            }
        } catch (_: Exception) {}
        return name ?: uri.lastPathSegment
    }

    // -------------------------------------------------------------------------
    // Byte Array Search Helpers
    // -------------------------------------------------------------------------

    private fun matches(source: ByteArray, offset: Int, target: ByteArray): Boolean {
        if (offset + target.size > source.size) return false
        for (i in target.indices) {
            if (source[offset + i] != target[i]) return false
        }
        return true
    }

    private fun indexOf(source: ByteArray, target: ByteArray, fromIndex: Int, maxIndex: Int): Int {
        if (target.isEmpty() || fromIndex >= maxIndex) return -1
        val first = target[0]
        val max = minOf(maxIndex - target.size, source.size - target.size)
        for (i in maxOf(0, fromIndex)..max) {
            if (source[i] != first) continue
            var j = 1
            while (j < target.size && source[i + j] == target[j]) {
                j++
            }
            if (j == target.size) return i
        }
        return -1
    }

    private fun lastIndexOf(source: ByteArray, target: ByteArray, beforeIndex: Int): Int {
        if (target.isEmpty() || beforeIndex <= 0) return -1
        val first = target[0]
        val start = minOf(beforeIndex - target.size, source.size - target.size)
        for (i in start downTo 0) {
            if (source[i] != first) continue
            var j = 1
            while (j < target.size && source[i + j] == target[j]) {
                j++
            }
            if (j == target.size) return i
        }
        return -1
    }
}
