package io.github.danprav04.polyglotbooks.feature.library

import io.github.danprav04.polyglotbooks.core.data.CoverExtractor
import io.github.danprav04.polyglotbooks.core.model.Chapter
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
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
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Interface for parsing chapters out of ebook and audiobook containers.
 */
interface ChapterParser {
    suspend fun parseChapters(file: File, format: BookFormat): List<Chapter>
    suspend fun parseChapters(inputStream: InputStream, format: BookFormat): List<Chapter>
}

class DefaultChapterParser : ChapterParser {
    override suspend fun parseChapters(file: File, format: BookFormat): List<Chapter> {
        if (format.isAudiobook) {
            // Check companion .cue file
            val parent = file.parentFile
            val cueFile = if (parent != null) File(parent, "${file.nameWithoutExtension}.cue") else File("${file.nameWithoutExtension}.cue")
            if (cueFile.exists() && cueFile.canRead()) {
                val chapters = try {
                    cueFile.inputStream().use { CueSheetChapterParser().parseChapters(it, BookFormat.CUE) }
                } catch (_: Exception) {
                    emptyList()
                }
                if (chapters.isNotEmpty()) return chapters
            }

            if (format == BookFormat.M4B) {
                val chapters = Mp4ChapterParser().parseChapters(file)
                if (chapters.isNotEmpty()) return chapters
            }
        }
        return file.inputStream().use { parseChapters(it, format) }
    }

    override suspend fun parseChapters(inputStream: InputStream, format: BookFormat): List<Chapter> {
        return when (format) {
            BookFormat.EPUB -> EpubChapterParser().parseChapters(inputStream)
            BookFormat.FB2 -> Fb2ChapterParser().parseChapters(inputStream)
            BookFormat.PDF -> PdfChapterParser().parseChapters(inputStream)
            BookFormat.CUE -> CueSheetChapterParser().parseChapters(inputStream, format)
            BookFormat.M4B, BookFormat.MP3 -> {
                if (format == BookFormat.M4B && inputStream is FileInputStream) {
                    val channel = inputStream.channel
                    val chapters = CoverExtractor.extractChapters(channel, channel.size())
                    if (chapters.isNotEmpty()) return chapters
                }
                // Read up to 8MB max to avoid OOM on large audiobook files
                val maxRead = 8 * 1024 * 1024
                val buffer = ByteArray(maxRead)
                val readTotal = inputStream.read(buffer)
                val bytes = if (readTotal > 0) buffer.copyOf(readTotal) else byteArrayOf()
                if (format == BookFormat.M4B) {
                    Mp4ChapterParser().parseChapters(bytes)
                } else {
                    Id3ChapChapterParser().parseChapters(bytes)
                }
            }
            else -> emptyList()
        }
    }
}

class EpubChapterParser {

    private data class ManifestItem(
        val id: String,
        val resolvedPath: String,
        val mediaType: String,
        val properties: Set<String>
    )

    fun parseChapters(inputStream: InputStream): List<Chapter> {
        val entries = readZipEntries(inputStream)
        if (entries.isEmpty()) return emptyList()

        val opfPath = locateOpfPath(entries) ?: return emptyList()
        val opfBytes = findEntry(entries, opfPath) ?: return emptyList()
        val opfDoc = parseXmlSafely(opfBytes) ?: return emptyList()
        val opfDir = parentDir(opfPath)

        val manifestById = linkedMapOf<String, ManifestItem>()
        val itemNodes = getElementsByLocalOrTagName(opfDoc, "item")
        for (i in 0 until itemNodes.size) {
            val el = itemNodes[i]
            val id = el.getAttribute("id").trim()
            val href = el.getAttribute("href").trim()
            if (id.isEmpty() || href.isEmpty()) continue
            val mediaType = el.getAttribute("media-type").trim()
            val props = el.getAttribute("properties")
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            val resolvedPath = resolveZipPath(opfDir, stripFragmentAndDecode(href))
            manifestById[id] = ManifestItem(id, resolvedPath, mediaType, props)
        }

        val spineElements = getElementsByLocalOrTagName(opfDoc, "spine")
        val spineTocId = spineElements.firstOrNull()?.getAttribute("toc")?.trim().orEmpty()
        val spineIdrefs = getElementsByLocalOrTagName(opfDoc, "itemref")
            .mapNotNull { it.getAttribute("idref").trim().takeIf(String::isNotEmpty) }

        if (spineIdrefs.isEmpty()) return emptyList()

        val tocTitlesByPath = linkedMapOf<String, String>()

        // 1. EPUB 3 <nav epub:type="toc">
        val navItem = manifestById.values.firstOrNull { "nav" in it.properties }
        if (navItem != null) {
            val navBytes = findEntry(entries, navItem.resolvedPath)
            if (navBytes != null) {
                parseEpub3NavTitles(navBytes, parentDir(navItem.resolvedPath), tocTitlesByPath)
            }
        }

        // 2. EPUB 2 NCX
        val ncxItem = manifestById[spineTocId]
            ?: manifestById.values.firstOrNull { it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) }
            ?: manifestById.values.firstOrNull { it.resolvedPath.lowercase().endsWith(".ncx") }
        if (ncxItem != null) {
            val ncxBytes = findEntry(entries, ncxItem.resolvedPath)
            if (ncxBytes != null) {
                parseNcxTitles(ncxBytes, parentDir(ncxItem.resolvedPath), tocTitlesByPath)
            }
        }

        val chapters = mutableListOf<Chapter>()
        spineIdrefs.forEachIndexed { spineIndex, idref ->
            val item = manifestById[idref] ?: return@forEachIndexed
            val tocTitle = findTocTitle(tocTitlesByPath, item.resolvedPath)
            val xhtmlTitle = if (tocTitle.isNullOrBlank()) {
                findEntry(entries, item.resolvedPath)?.let { extractXhtmlTitle(it) }
            } else {
                null
            }
            val title = tocTitle?.takeIf { it.isNotBlank() }
                ?: xhtmlTitle?.takeIf { it.isNotBlank() }
                ?: "Chapter ${spineIndex + 1}"

            val index = chapters.size
            chapters.add(
                Chapter(
                    id = "chap_$index",
                    bookId = "",
                    index = index,
                    title = title,
                    startMs = index * 1000L,
                    endMs = (index + 1) * 1000L,
                    cfiRange = "epubcfi(/6/${(spineIndex + 1) * 2}!/4/2/1:0)"
                )
            )
        }

        return chapters
    }

    private fun readZipEntries(inputStream: InputStream): Map<String, ByteArray> {
        val map = linkedMapOf<String, ByteArray>()
        try {
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.trimStart('/')
                        map[name] = zis.readBytes()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {
            // Return whatever valid entries were read
        }
        return map
    }

    private fun locateOpfPath(entries: Map<String, ByteArray>): String? {
        val containerBytes = findEntry(entries, "META-INF/container.xml")
        if (containerBytes != null) {
            val doc = parseXmlSafely(containerBytes)
            if (doc != null) {
                val rootfiles = getElementsByLocalOrTagName(doc, "rootfile")
                for (el in rootfiles) {
                    val fullPath = el.getAttribute("full-path").trim().trimStart('/')
                    if (fullPath.isNotEmpty()) return fullPath
                }
            }
        }
        return entries.keys.firstOrNull { it.lowercase().endsWith(".opf") }
    }

    private fun parseEpub3NavTitles(
        navBytes: ByteArray,
        navDir: String,
        out: MutableMap<String, String>
    ) {
        val doc = parseXmlSafely(navBytes)
        if (doc != null) {
            val navs = getElementsByLocalOrTagName(doc, "nav")
            val tocNav = navs.firstOrNull { nav ->
                val epubType = nav.getAttributeNS("http://www.idpf.org/2007/ops", "type")
                    .ifBlank { nav.getAttribute("epub:type") }
                    .ifBlank { nav.getAttribute("type") }
                epubType.split(Regex("\\s+")).any { it.equals("toc", ignoreCase = true) }
            } ?: navs.firstOrNull()

            if (tocNav != null) {
                val anchors = getElementsByLocalOrTagName(tocNav, "a")
                for (anchor in anchors) {
                    val href = anchor.getAttribute("href").trim()
                    val label = cleanText(anchor.textContent)
                    if (href.isNotEmpty() && label.isNotEmpty()) {
                        val resolved = resolveZipPath(navDir, stripFragmentAndDecode(href))
                        out.putIfAbsent(resolved, label)
                    }
                }
                return
            }
        }

        // Regex fallback for non-well-formed HTML5 nav documents
        val html = String(navBytes, StandardCharsets.UTF_8)
        val navMatch = Regex(
            """<nav\b[^>]*epub:type\s*=\s*["'][^"']*\btoc\b[^"']*["'][^>]*>(.*?)</nav>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html) ?: Regex(
            """<nav\b[^>]*>(.*?)</nav>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html) ?: return

        val anchorRegex = Regex(
            """<a\b[^>]*href\s*=\s*(?:"([^"]*)"|'([^']*)')[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        for (match in anchorRegex.findAll(navMatch.groupValues[1])) {
            val href = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
            val label = cleanText(stripTags(match.groupValues[3]))
            if (href.isNotEmpty() && label.isNotEmpty()) {
                val resolved = resolveZipPath(navDir, stripFragmentAndDecode(href))
                out.putIfAbsent(resolved, label)
            }
        }
    }

    private fun parseNcxTitles(
        ncxBytes: ByteArray,
        ncxDir: String,
        out: MutableMap<String, String>
    ) {
        val doc = parseXmlSafely(ncxBytes) ?: return
        val navPoints = getElementsByLocalOrTagName(doc, "navPoint")
        for (navPoint in navPoints) {
            val navLabel = directChildByLocalName(navPoint, "navLabel")
            val textEl = navLabel?.let { directChildByLocalName(it, "text") }
            val label = cleanText(textEl?.textContent)
            val contentEl = directChildByLocalName(navPoint, "content")
            val src = contentEl?.getAttribute("src")?.trim().orEmpty()
            if (src.isNotEmpty() && label.isNotEmpty()) {
                val resolved = resolveZipPath(ncxDir, stripFragmentAndDecode(src))
                out.putIfAbsent(resolved, label)
            }
        }
    }

    private fun extractXhtmlTitle(xhtmlBytes: ByteArray): String? {
        val text = String(xhtmlBytes, StandardCharsets.UTF_8)
        for (tag in listOf("h1", "h2", "h3", "title")) {
            val regex = Regex(
                """<$tag\b[^>]*>(.*?)</$tag>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            for (match in regex.findAll(text)) {
                val candidate = cleanText(stripTags(match.groupValues[1]))
                if (candidate.isNotEmpty() &&
                    !(tag == "title" && candidate.lowercase() in setOf("unknown", "untitled"))
                ) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun findTocTitle(tocTitlesByPath: Map<String, String>, resolvedPath: String): String? {
        return tocTitlesByPath[resolvedPath]
            ?: tocTitlesByPath.entries.firstOrNull { it.key.equals(resolvedPath, ignoreCase = true) }?.value
    }

    private fun findEntry(entries: Map<String, ByteArray>, path: String): ByteArray? {
        val normalized = path.trimStart('/')
        return entries[normalized]
            ?: entries.entries.firstOrNull { it.key.equals(normalized, ignoreCase = true) }?.value
    }

    private fun parentDir(path: String): String {
        val normalized = path.trimStart('/')
        val idx = normalized.lastIndexOf('/')
        return if (idx == -1) "" else normalized.substring(0, idx)
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
}

class Fb2ChapterParser {
    fun parseChapters(inputStream: InputStream): List<Chapter> {
        val bytes = inputStream.readBytes()
        if (bytes.isEmpty()) return emptyList()
        val doc = parseXmlSafely(bytes) ?: return emptyList()

        val bodies = getElementsByLocalOrTagName(doc, "body").filter { body ->
            val name = body.getAttribute("name").trim().lowercase()
            name != "notes" && name != "comments"
        }
        val sectionNodes = if (bodies.isNotEmpty()) {
            bodies.flatMap { getElementsByLocalOrTagName(it, "section") }
        } else {
            getElementsByLocalOrTagName(doc, "section")
        }

        val chapters = mutableListOf<Chapter>()
        for (section in sectionNodes) {
            val titleEl = directChildByLocalName(section, "title") ?: continue
            val pElements = getElementsByLocalOrTagName(titleEl, "p")
            val rawTitle = if (pElements.isNotEmpty()) {
                pElements.mapNotNull { cleanText(it.textContent).takeIf(String::isNotEmpty) }
                    .joinToString(" ")
            } else {
                cleanText(titleEl.textContent)
            }
            val title = rawTitle.takeIf { it.isNotEmpty() } ?: continue
            val index = chapters.size
            chapters.add(
                Chapter(
                    id = "chap_$index",
                    bookId = "",
                    index = index,
                    title = title,
                    startMs = index * 1000L,
                    endMs = (index + 1) * 1000L,
                    cfiRange = "epubcfi(/6/${(index + 1) * 2}!/4/2/1:0)"
                )
            )
        }
        return chapters
    }
}

class PdfChapterParser {
    fun parseChapters(inputStream: InputStream): List<Chapter> {
        val bytes = inputStream.readBytes()
        if (bytes.isEmpty()) return emptyList()
        val isoText = String(bytes, StandardCharsets.ISO_8859_1)
        val pageCount = Regex("""/Type\s*/Page\b""").findAll(isoText).count()
        if (pageCount <= 0) return emptyList()

        val chapters = mutableListOf<Chapter>()
        if (pageCount <= 15) {
            for (pageIndex in 0 until pageCount) {
                chapters.add(
                    Chapter(
                        id = "chap_$pageIndex",
                        bookId = "",
                        index = pageIndex,
                        title = "Page ${pageIndex + 1}",
                        startMs = pageIndex * 1000L,
                        endMs = (pageIndex + 1) * 1000L,
                        cfiRange = "pdf:page:$pageIndex"
                    )
                )
            }
        } else {
            var chapterIndex = 0
            var startPage = 0
            while (startPage < pageCount) {
                val endPage = minOf(startPage + 9, pageCount - 1)
                chapters.add(
                    Chapter(
                        id = "chap_$chapterIndex",
                        bookId = "",
                        index = chapterIndex,
                        title = "Pages ${startPage + 1}\u2013${endPage + 1}",
                        startMs = chapterIndex * 1000L,
                        endMs = (chapterIndex + 1) * 1000L,
                        cfiRange = "pdf:page:$startPage"
                    )
                )
                startPage += 10
                chapterIndex++
            }
        }
        return chapters
    }
}

private fun parseXmlSafely(bytes: ByteArray): Document? {
    return try {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        dbf.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (_: Exception) {
        try {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }
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

private fun directChildByLocalName(parent: Element, localName: String): Element? {
    val children = parent.childNodes
    for (i in 0 until children.length) {
        val node = children.item(i)
        if (node.nodeType == Node.ELEMENT_NODE && node is Element) {
            val nodeName = (node.localName ?: node.tagName).substringAfter(':')
            if (nodeName.equals(localName, ignoreCase = true)) {
                return node
            }
        }
    }
    return null
}

private fun stripTags(html: String): String = html.replace(Regex("<[^>]+>"), " ")

private fun cleanText(text: String?): String {
    if (text.isNullOrBlank()) return ""
    return text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

class CueSheetChapterParser {
    fun parseChapters(inputStream: InputStream, format: BookFormat = BookFormat.CUE): List<Chapter> {
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
                                bookId = "",
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
}

class Id3ChapChapterParser {
    fun parseChapters(bytes: ByteArray): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var i = 0
        var orderIndex = 0
        while (i < bytes.size - 16) {
            if (bytes[i] == 'C'.code.toByte() &&
                bytes[i+1] == 'H'.code.toByte() &&
                bytes[i+2] == 'A'.code.toByte() &&
                bytes[i+3] == 'P'.code.toByte()) {
                
                try {
                    val size = ByteBuffer.wrap(bytes, i + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                    val frameDataStart = i + 10
                    if (size > 0 && frameDataStart + size <= bytes.size) {
                        var idx = frameDataStart
                        while (idx < frameDataStart + size && bytes[idx] != 0.toByte()) {
                            idx++
                        }
                        val elementId = String(bytes, frameDataStart, idx - frameDataStart, Charsets.ISO_8859_1)
                        idx++
                        
                        if (idx + 12 <= frameDataStart + size) {
                            val startTime = ByteBuffer.wrap(bytes, idx, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                            val endTime = ByteBuffer.wrap(bytes, idx + 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                            
                            var title = "Chapter $elementId"
                            val subDataEnd = frameDataStart + size
                            var subIdx = idx + 16
                            while (subIdx < subDataEnd - 10) {
                                if (bytes[subIdx] == 'T'.code.toByte() &&
                                    bytes[subIdx+1] == 'I'.code.toByte() &&
                                    bytes[subIdx+2] == 'T'.code.toByte() &&
                                    bytes[subIdx+3] == '2'.code.toByte()) {
                                    val subSize = ByteBuffer.wrap(bytes, subIdx + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                                    if (subSize > 0 && subIdx + 10 + subSize <= subDataEnd) {
                                        val encoding = bytes[subIdx + 10]
                                        val textBytes = bytes.copyOfRange(subIdx + 11, subIdx + 10 + subSize)
                                        title = if (encoding == 1.toByte() || encoding == 2.toByte()) {
                                            String(textBytes, Charsets.UTF_16)
                                        } else {
                                            String(textBytes, Charsets.UTF_8)
                                        }.trim('\u0000', ' ')
                                    }
                                    break
                                }
                                subIdx++
                            }

                            chapters.add(
                                Chapter(
                                    id = elementId,
                                    bookId = "",
                                    index = orderIndex++,
                                    title = title,
                                    startMs = startTime,
                                    endMs = endTime
                                )
                            )
                        }
                        i += 10 + size
                    } else {
                        i++
                    }
                } catch (e: Exception) {
                    i++
                }
            } else {
                i++
            }
        }
        return chapters
    }
}

class Mp4ChapterParser {
    fun parseChapters(bytes: ByteArray, bookId: String = ""): List<Chapter> {
        return CoverExtractor.extractChapters(bytes, bookId)
    }

    fun parseChapters(file: File, bookId: String = ""): List<Chapter> {
        return CoverExtractor.extractChapters(file, bookId)
    }

    fun parseChapters(channel: FileChannel, fileSize: Long, bookId: String = ""): List<Chapter> {
        return CoverExtractor.extractChapters(channel, fileSize, bookId)
    }
}

class FolderChapterParser {
    fun parseChapters(audioFiles: List<AudioFileEntry>): List<Chapter> {
        var cumulativeTime = 0L
        return audioFiles.mapIndexed { index, entry ->
            val startTime = cumulativeTime
            cumulativeTime += entry.durationMs
            Chapter(
                id = "folder_chap_$index",
                bookId = "",
                index = index,
                title = entry.name.substringBeforeLast('.'),
                startMs = startTime,
                endMs = cumulativeTime
            )
        }
    }
}

data class AudioFileEntry(
    val name: String,
    val uri: String,
    val durationMs: Long
)
