package io.github.danprav04.polyglotbooks.feature.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object EbookContentExtractor {

    data class ExtractedChapter(
        val index: Int,
        val title: String,
        val href: String,
        val html: String,
        val paragraphs: List<String>
    )

    data class ExtractedBook(
        val format: String,
        val title: String,
        val author: String,
        val pageCount: Int,
        val chapters: List<ExtractedChapter>
    )

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")

    fun extractBook(context: Context?, filePathOrUri: String, formatHint: String): ExtractedBook {
        val bytes = extractFileBytes(context, filePathOrUri)
        val format = resolveFormat(formatHint, filePathOrUri, bytes)
        if (bytes == null || bytes.isEmpty()) {
            return ExtractedBook(
                format = format,
                title = "",
                author = "",
                pageCount = 0,
                chapters = emptyList()
            )
        }

        return try {
            when (format) {
                "EPUB" -> extractEpub(bytes)
                "FB2" -> extractFb2(bytes)
                "CBZ" -> extractCbz(bytes)
                "PDF" -> extractPdf(context, filePathOrUri, bytes)
                "MD" -> extractMarkdown(bytes)
                "HTML" -> extractHtml(bytes)
                "TXT" -> extractTxt(bytes)
                else -> {
                    if (isZipBytes(bytes)) extractEpub(bytes)
                    else if (isPdfBytes(bytes)) extractPdf(context, filePathOrUri, bytes)
                    else extractTxt(bytes)
                }
            }
        } catch (_: Exception) {
            ExtractedBook(
                format = format,
                title = "",
                author = "",
                pageCount = 0,
                chapters = emptyList()
            )
        }
    }

    fun extractFileBytes(context: Context?, filePathOrUri: String): ByteArray? {
        if (filePathOrUri.isBlank()) return null
        val trimmed = filePathOrUri.trim()

        if (trimmed.startsWith("content://", ignoreCase = true)) {
            return try {
                context?.contentResolver?.openInputStream(Uri.parse(trimmed))?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
        }

        if (trimmed.startsWith("file:", ignoreCase = true)) {
            val file = resolveLocalFile(trimmed)
            if (file != null && file.exists() && file.isFile) {
                return try {
                    file.readBytes()
                } catch (_: Exception) {
                    null
                }
            }
            return try {
                context?.contentResolver?.openInputStream(Uri.parse(trimmed))?.use { it.readBytes() }
            } catch (_: Exception) {
                null
            }
        }

        val file = File(trimmed)
        if (file.exists() && file.isFile) {
            return try {
                file.readBytes()
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    fun renderPdfPagePng(
        context: Context?,
        filePathOrUri: String,
        pageIndex: Int,
        widthPx: Int = 1080
    ): ByteArray? {
        if (pageIndex < 0 || widthPx <= 0 || filePathOrUri.isBlank()) return null
        var tempFile: File? = null
        var pfd: ParcelFileDescriptor? = null
        return try {
            pfd = openParcelFileDescriptor(context, filePathOrUri)
            if (pfd == null) {
                val bytes = extractFileBytes(context, filePathOrUri) ?: return null
                val cacheDir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
                tempFile = File.createTempFile("polyglot_pdf_", ".pdf", cacheDir).apply {
                    writeBytes(bytes)
                }
                pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            PdfRenderer(pfd).use { renderer ->
                if (pageIndex >= renderer.pageCount) return null
                renderer.openPage(pageIndex).use { page ->
                    val targetWidth = widthPx.coerceAtLeast(1)
                    val targetHeight = ((page.height.toFloat() / maxOf(1, page.width)) * targetWidth)
                        .toInt()
                        .coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
            tempFile?.delete()
        }
    }

    fun toChaptersJson(book: ExtractedBook): String {
        val sb = StringBuilder(4096)
        sb.append('{')
        sb.append("\"format\":\"").append(escapeJson(book.format)).append("\",")
        sb.append("\"title\":\"").append(escapeJson(book.title)).append("\",")
        sb.append("\"author\":\"").append(escapeJson(book.author)).append("\",")
        sb.append("\"pageCount\":").append(book.pageCount).append(',')
        sb.append("\"chapters\":[")
        book.chapters.forEachIndexed { cIdx, chapter ->
            if (cIdx > 0) sb.append(',')
            sb.append('{')
            sb.append("\"index\":").append(chapter.index).append(',')
            sb.append("\"title\":\"").append(escapeJson(chapter.title)).append("\",")
            sb.append("\"href\":\"").append(escapeJson(chapter.href)).append("\",")
            sb.append("\"html\":\"").append(escapeJson(chapter.html)).append("\",")
            sb.append("\"paragraphs\":[")
            chapter.paragraphs.forEachIndexed { pIdx, paragraph ->
                if (pIdx > 0) sb.append(',')
                sb.append('"').append(escapeJson(paragraph)).append('"')
            }
            sb.append(']')
            sb.append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun resolveFormat(formatHint: String, filePathOrUri: String, bytes: ByteArray?): String {
        val hint = formatHint.trim().uppercase(Locale.ROOT)
        val normalizedHint = when (hint) {
            "HTM", "XHTML" -> "HTML"
            "MARKDOWN" -> "MD"
            "FBZ" -> "FB2"
            "", "UNKNOWN" -> ""
            else -> hint
        }
        if (normalizedHint.isNotEmpty()) return normalizedHint

        val cleanPath = filePathOrUri.substringBefore('?').substringBefore('#')
        val ext = cleanPath.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val byExt = when (ext) {
            "epub" -> "EPUB"
            "fb2", "fbz" -> "FB2"
            "cbz" -> "CBZ"
            "pdf" -> "PDF"
            "md", "markdown" -> "MD"
            "html", "htm", "xhtml" -> "HTML"
            "txt" -> "TXT"
            else -> ""
        }
        if (byExt.isNotEmpty()) return byExt

        if (bytes != null) {
            if (isPdfBytes(bytes)) return "PDF"
            if (isZipBytes(bytes)) return "EPUB"
        }
        return "TXT"
    }

    private fun resolveLocalFile(uriString: String): File? {
        return try {
            File(URI(uriString))
        } catch (_: Exception) {
            val stripped = when {
                uriString.startsWith("file://", ignoreCase = true) -> uriString.substring(7)
                uriString.startsWith("file:", ignoreCase = true) -> uriString.substring(5)
                else -> uriString
            }
            File(stripped)
        }
    }

    private fun openParcelFileDescriptor(context: Context?, filePathOrUri: String): ParcelFileDescriptor? {
        return try {
            val trimmed = filePathOrUri.trim()
            when {
                trimmed.startsWith("content://", ignoreCase = true) ->
                    context?.contentResolver?.openFileDescriptor(Uri.parse(trimmed), "r")
                trimmed.startsWith("file:", ignoreCase = true) -> {
                    val file = resolveLocalFile(trimmed)
                    if (file != null && file.exists()) {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    } else {
                        context?.contentResolver?.openFileDescriptor(Uri.parse(trimmed), "r")
                    }
                }
                else -> {
                    val file = File(trimmed)
                    if (file.exists()) ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) else null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun isZipBytes(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun isPdfBytes(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == '%'.code.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    // ==================== EPUB ====================

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normalizedName = normalizeZipPath(entry.name)
                    entries[normalizedName] = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return entries
    }

    private fun findZipEntry(
        entries: Map<String, ByteArray>,
        lowerEntries: Map<String, ByteArray>,
        path: String
    ): ByteArray? {
        val norm = normalizeZipPath(path)
        return entries[norm] ?: lowerEntries[norm.lowercase(Locale.ROOT)]
    }

    private data class OpfManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String
    )

    private fun extractEpub(bytes: ByteArray): ExtractedBook {
        val entries = readZipEntries(bytes)
        val lowerEntries = entries.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }

        val opfPath = findOpfPath(entries, lowerEntries)
        val opfDir = opfPath?.substringBeforeLast('/', "")?.let {
            if (opfPath.contains('/')) it else ""
        } ?: ""

        var bookTitle = ""
        var bookAuthor = ""
        val manifestById = LinkedHashMap<String, OpfManifestItem>()
        val spineIds = mutableListOf<String>()
        var spineTocId = ""
        val tocTitles = LinkedHashMap<String, String>()

        val opfBytes = opfPath?.let { findZipEntry(entries, lowerEntries, it) }
        if (opfBytes != null) {
            val opfDoc = parseXmlSafe(opfBytes)
            if (opfDoc != null) {
                bookTitle = firstElementText(opfDoc.documentElement, "title")
                bookAuthor = firstElementText(opfDoc.documentElement, "creator")

                val items = opfDoc.getElementsByTagNameNS("*", "item")
                val fallbackItems = if (items.length == 0) opfDoc.getElementsByTagName("item") else items
                for (i in 0 until fallbackItems.length) {
                    val el = fallbackItems.item(i) as? Element ?: continue
                    val id = el.getAttribute("id")
                    val rawHref = el.getAttribute("href")
                    if (id.isNotBlank() && rawHref.isNotBlank()) {
                        val resolvedHref = resolveZipPath(opfDir, rawHref)
                        manifestById[id] = OpfManifestItem(
                            id = id,
                            href = resolvedHref,
                            mediaType = el.getAttribute("media-type"),
                            properties = el.getAttribute("properties")
                        )
                    }
                }

                val spines = opfDoc.getElementsByTagNameNS("*", "spine")
                val fallbackSpines = if (spines.length == 0) opfDoc.getElementsByTagName("spine") else spines
                if (fallbackSpines.length > 0) {
                    val spineEl = fallbackSpines.item(0) as? Element
                    spineTocId = spineEl?.getAttribute("toc").orEmpty()
                }

                val itemRefs = opfDoc.getElementsByTagNameNS("*", "itemref")
                val fallbackRefs = if (itemRefs.length == 0) opfDoc.getElementsByTagName("itemref") else itemRefs
                for (i in 0 until fallbackRefs.length) {
                    val el = fallbackRefs.item(i) as? Element ?: continue
                    val idref = el.getAttribute("idref")
                    if (idref.isNotBlank()) spineIds.add(idref)
                }
            }
        }

        // Parse EPUB 3 nav TOC
        val navItem = manifestById.values.firstOrNull {
            it.properties.split(Regex("\\s+")).contains("nav")
        }
        if (navItem != null) {
            findZipEntry(entries, lowerEntries, navItem.href)?.let { navBytes ->
                parseEpub3NavToc(navBytes, navItem.href, tocTitles)
            }
        }

        // Parse EPUB 2 NCX TOC
        val ncxItem = (if (spineTocId.isNotBlank()) manifestById[spineTocId] else null)
            ?: manifestById.values.firstOrNull { it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) }
            ?: manifestById.values.firstOrNull { it.href.endsWith(".ncx", ignoreCase = true) }
        if (ncxItem != null) {
            findZipEntry(entries, lowerEntries, ncxItem.href)?.let { ncxBytes ->
                parseNcxToc(ncxBytes, ncxItem.href, tocTitles)
            }
        }

        val spineItems: List<Pair<String, ByteArray>> = if (spineIds.isNotEmpty()) {
            spineIds.mapNotNull { id ->
                val item = manifestById[id] ?: return@mapNotNull null
                val data = findZipEntry(entries, lowerEntries, item.href) ?: return@mapNotNull null
                item.href to data
            }
        } else {
            entries.entries
                .filter { (name, _) ->
                    val lower = name.lowercase(Locale.ROOT)
                    lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
                }
                .sortedBy { it.key }
                .map { it.key to it.value }
        }

        val chapters = mutableListOf<ExtractedChapter>()
        spineItems.forEachIndexed { index, (href, xhtmlBytes) ->
            val rawHtml = String(xhtmlBytes, Charsets.UTF_8)
            val bodyHtml = extractBodyHtml(rawHtml)
            val chapterDir = href.substringBeforeLast('/', "").let { if (href.contains('/')) it else "" }
            val cleanedHtml = inlineHtmlImages(stripScripts(bodyHtml), chapterDir, entries, lowerEntries)
            val paragraphs = extractParagraphsFromHtml(cleanedHtml)

            val tocTitle = tocTitles[href] ?: tocTitles[href.lowercase(Locale.ROOT)]
            val headingTitle = extractTitleFromHtml(rawHtml)
            val title = tocTitle?.takeIf { it.isNotBlank() }
                ?: headingTitle?.takeIf { it.isNotBlank() }
                ?: "Chapter ${index + 1}"

            chapters.add(
                ExtractedChapter(
                    index = index,
                    title = title,
                    href = href,
                    html = cleanedHtml,
                    paragraphs = paragraphs
                )
            )
        }

        return ExtractedBook(
            format = "EPUB",
            title = bookTitle,
            author = bookAuthor,
            pageCount = chapters.size,
            chapters = chapters
        )
    }

    private fun findOpfPath(entries: Map<String, ByteArray>, lowerEntries: Map<String, ByteArray>): String? {
        val containerBytes = findZipEntry(entries, lowerEntries, "META-INF/container.xml")
        if (containerBytes != null) {
            val doc = parseXmlSafe(containerBytes)
            if (doc != null) {
                val rootfiles = doc.getElementsByTagNameNS("*", "rootfile")
                val fallback = if (rootfiles.length == 0) doc.getElementsByTagName("rootfile") else rootfiles
                if (fallback.length > 0) {
                    val el = fallback.item(0) as? Element
                    val fullPath = el?.getAttribute("full-path")
                    if (!fullPath.isNullOrBlank()) return normalizeZipPath(fullPath)
                }
            }
            val containerText = String(containerBytes, Charsets.UTF_8)
            val match = Regex("""full-path\s*=\s*["']([^"']+)["']""").find(containerText)
            if (match != null) return normalizeZipPath(match.groupValues[1])
        }
        return entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
    }

    private fun parseNcxToc(ncxBytes: ByteArray, ncxHref: String, out: MutableMap<String, String>) {
        val doc = parseXmlSafe(ncxBytes) ?: return
        val ncxDir = ncxHref.substringBeforeLast('/', "").let { if (ncxHref.contains('/')) it else "" }
        val navPoints = doc.getElementsByTagNameNS("*", "navPoint")
        val fallback = if (navPoints.length == 0) doc.getElementsByTagName("navPoint") else navPoints
        for (i in 0 until fallback.length) {
            val np = fallback.item(i) as? Element ?: continue
            val label = firstDirectChildText(np, "navLabel", "text")
            val contentEl = firstDirectChildElement(np, "content")
            val src = contentEl?.getAttribute("src")?.substringBefore('#')
            if (!label.isNullOrBlank() && !src.isNullOrBlank()) {
                val resolved = resolveZipPath(ncxDir, src)
                out.putIfAbsent(resolved, label.trim())
                out.putIfAbsent(resolved.lowercase(Locale.ROOT), label.trim())
            }
        }
    }

    private fun parseEpub3NavToc(navBytes: ByteArray, navHref: String, out: MutableMap<String, String>) {
        val doc = parseXmlSafe(navBytes) ?: return
        val navDir = navHref.substringBeforeLast('/', "").let { if (navHref.contains('/')) it else "" }
        val navs = doc.getElementsByTagNameNS("*", "nav")
        val fallbackNavs = if (navs.length == 0) doc.getElementsByTagName("nav") else navs
        for (i in 0 until fallbackNavs.length) {
            val nav = fallbackNavs.item(i) as? Element ?: continue
            val epubType = nav.getAttributeNS("http://www.idpf.org/2007/ops", "type")
                .ifBlank { nav.getAttribute("epub:type") }
            if (epubType.isNotBlank() && !epubType.contains("toc", ignoreCase = true)) continue
            val links = nav.getElementsByTagNameNS("*", "a")
            val fallbackLinks = if (links.length == 0) nav.getElementsByTagName("a") else links
            for (j in 0 until fallbackLinks.length) {
                val a = fallbackLinks.item(j) as? Element ?: continue
                val href = a.getAttribute("href").substringBefore('#')
                val label = a.textContent?.replace(Regex("\\s+"), " ")?.trim()
                if (href.isNotBlank() && !label.isNullOrBlank()) {
                    val resolved = resolveZipPath(navDir, href)
                    out.putIfAbsent(resolved, label)
                    out.putIfAbsent(resolved.lowercase(Locale.ROOT), label)
                }
            }
        }
    }

    private fun inlineHtmlImages(
        html: String,
        baseDir: String,
        entries: Map<String, ByteArray>,
        lowerEntries: Map<String, ByteArray>
    ): String {
        val imgRegex = Regex(
            """(<(?:img|image)\b[^>]*?\b(?:src|xlink:href|href)\s*=\s*)(["'])([^"']+)\2""",
            RegexOption.IGNORE_CASE
        )
        return imgRegex.replace(html) { match ->
            val prefix = match.groupValues[1]
            val quote = match.groupValues[2]
            val rawSrc = match.groupValues[3].trim()
            if (rawSrc.startsWith("data:", ignoreCase = true) ||
                rawSrc.startsWith("http://", ignoreCase = true) ||
                rawSrc.startsWith("https://", ignoreCase = true)
            ) {
                match.value
            } else {
                val cleanSrc = rawSrc.substringBefore('#').substringBefore('?')
                val resolved = resolveZipPath(baseDir, cleanSrc)
                val imgBytes = findZipEntry(entries, lowerEntries, resolved)
                if (imgBytes != null) {
                    val mime = mimeTypeForPath(resolved)
                    val b64 = Base64.getEncoder().encodeToString(imgBytes)
                    "${prefix}${quote}data:$mime;base64,$b64${quote}"
                } else {
                    match.value
                }
            }
        }
    }

    // ==================== FB2 ====================

    private fun extractFb2(bytes: ByteArray): ExtractedBook {
        val doc = parseXmlSafe(bytes)
            ?: return extractTxt(bytes).copy(format = "FB2")

        val root = doc.documentElement
        val title = firstElementText(root, "book-title")
        val authorEl = firstDescendantElement(root, "author")
        val author = if (authorEl != null) {
            val first = firstElementText(authorEl, "first-name")
            val middle = firstElementText(authorEl, "middle-name")
            val last = firstElementText(authorEl, "last-name")
            val nick = firstElementText(authorEl, "nickname")
            listOf(first, middle, last).filter { it.isNotBlank() }.joinToString(" ").ifBlank { nick }
        } else {
            ""
        }

        // Collect binary images
        val binaries = HashMap<String, String>()
        val binaryNodes = root.getElementsByTagNameNS("*", "binary")
        val fallbackBinaries = if (binaryNodes.length == 0) root.getElementsByTagName("binary") else binaryNodes
        for (i in 0 until fallbackBinaries.length) {
            val el = fallbackBinaries.item(i) as? Element ?: continue
            val id = el.getAttribute("id")
            val contentType = el.getAttribute("content-type").ifBlank { "image/jpeg" }
            val b64 = el.textContent?.replace(Regex("\\s+"), "") ?: ""
            if (id.isNotBlank() && b64.isNotBlank()) {
                binaries[id] = "data:$contentType;base64,$b64"
            }
        }

        val chapters = mutableListOf<ExtractedChapter>()
        val bodies = root.getElementsByTagNameNS("*", "body")
        val fallbackBodies = if (bodies.length == 0) root.getElementsByTagName("body") else bodies
        if (fallbackBodies.length > 0) {
            val mainBody = fallbackBodies.item(0) as? Element
            if (mainBody != null) {
                collectFb2Sections(mainBody, binaries, chapters)
                if (chapters.isEmpty()) {
                    val paragraphs = mutableListOf<String>()
                    val html = renderFb2ElementChildrenToHtml(mainBody, binaries, paragraphs)
                    if (paragraphs.isNotEmpty() || html.isNotBlank()) {
                        chapters.add(
                            ExtractedChapter(
                                index = 0,
                                title = title.ifBlank { "Chapter 1" },
                                href = "fb2:0",
                                html = html,
                                paragraphs = paragraphs
                            )
                        )
                    }
                }
            }
        }

        return ExtractedBook(
            format = "FB2",
            title = title,
            author = author,
            pageCount = chapters.size,
            chapters = chapters
        )
    }

    private fun collectFb2Sections(
        parent: Element,
        binaries: Map<String, String>,
        out: MutableList<ExtractedChapter>
    ) {
        val childSections = directChildElements(parent, "section")
        for (section in childSections) {
            val nestedSections = directChildElements(section, "section")
            val hasDirectParagraphs = directChildElements(section, "p").isNotEmpty() ||
                directChildElements(section, "poem").isNotEmpty() ||
                directChildElements(section, "cite").isNotEmpty()
            if (nestedSections.isNotEmpty() && !hasDirectParagraphs) {
                collectFb2Sections(section, binaries, out)
            } else {
                val idx = out.size
                val titleEl = firstDirectChildElement(section, "title")
                val chapterTitle = titleEl?.textContent?.replace(Regex("\\s+"), " ")?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"
                val paragraphs = mutableListOf<String>()
                val html = renderFb2ElementChildrenToHtml(section, binaries, paragraphs)
                out.add(
                    ExtractedChapter(
                        index = idx,
                        title = chapterTitle,
                        href = "fb2:$idx",
                        html = html,
                        paragraphs = paragraphs
                    )
                )
            }
        }
    }

    private fun renderFb2ElementChildrenToHtml(
        container: Element,
        binaries: Map<String, String>,
        paragraphs: MutableList<String>
    ): String {
        val sb = StringBuilder()
        val children = container.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (localNameOf(el)) {
                "title" -> {
                    val text = normalizeSpaces(el.textContent)
                    if (text.isNotBlank()) {
                        paragraphs.add(text)
                        sb.append("<h2>").append(escapeHtml(text)).append("</h2>")
                    }
                }
                "subtitle" -> {
                    val text = normalizeSpaces(el.textContent)
                    if (text.isNotBlank()) {
                        paragraphs.add(text)
                        sb.append("<h3>").append(escapeHtml(text)).append("</h3>")
                    }
                }
                "p" -> {
                    val text = normalizeSpaces(el.textContent)
                    val innerHtml = renderFb2InlineToHtml(el, binaries)
                    if (text.isNotBlank()) {
                        paragraphs.add(text)
                        sb.append("<p>").append(innerHtml).append("</p>")
                    } else if (innerHtml.contains("<img")) {
                        sb.append("<p>").append(innerHtml).append("</p>")
                    }
                }
                "empty-line" -> sb.append("<br/>")
                "cite" -> {
                    sb.append("<blockquote>")
                    sb.append(renderFb2ElementChildrenToHtml(el, binaries, paragraphs))
                    sb.append("</blockquote>")
                }
                "epigraph" -> {
                    sb.append("<blockquote class=\"epigraph\">")
                    sb.append(renderFb2ElementChildrenToHtml(el, binaries, paragraphs))
                    sb.append("</blockquote>")
                }
                "poem" -> {
                    sb.append("<div class=\"poem\">")
                    sb.append(renderFb2ElementChildrenToHtml(el, binaries, paragraphs))
                    sb.append("</div>")
                }
                "stanza" -> {
                    sb.append("<div class=\"stanza\">")
                    sb.append(renderFb2ElementChildrenToHtml(el, binaries, paragraphs))
                    sb.append("</div>")
                }
                "v" -> {
                    val text = normalizeSpaces(el.textContent)
                    if (text.isNotBlank()) {
                        paragraphs.add(text)
                        sb.append("<p class=\"verse\">").append(renderFb2InlineToHtml(el, binaries)).append("</p>")
                    }
                }
                "image" -> {
                    val imgHtml = renderFb2Image(el, binaries)
                    if (imgHtml.isNotEmpty()) sb.append(imgHtml)
                }
                "section" -> {
                    sb.append(renderFb2ElementChildrenToHtml(el, binaries, paragraphs))
                }
                else -> {
                    val text = normalizeSpaces(el.textContent)
                    if (text.isNotBlank()) {
                        paragraphs.add(text)
                        sb.append("<p>").append(escapeHtml(text)).append("</p>")
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun renderFb2InlineToHtml(element: Element, binaries: Map<String, String>): String {
        val sb = StringBuilder()
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(escapeHtml(child.textContent ?: ""))
                Node.ELEMENT_NODE -> {
                    val childEl = child as Element
                    when (localNameOf(childEl)) {
                        "emphasis" -> sb.append("<em>").append(renderFb2InlineToHtml(childEl, binaries)).append("</em>")
                        "strong" -> sb.append("<strong>").append(renderFb2InlineToHtml(childEl, binaries)).append("</strong>")
                        "strikethrough" -> sb.append("<s>").append(renderFb2InlineToHtml(childEl, binaries)).append("</s>")
                        "sup" -> sb.append("<sup>").append(renderFb2InlineToHtml(childEl, binaries)).append("</sup>")
                        "sub" -> sb.append("<sub>").append(renderFb2InlineToHtml(childEl, binaries)).append("</sub>")
                        "code" -> sb.append("<code>").append(renderFb2InlineToHtml(childEl, binaries)).append("</code>")
                        "image" -> sb.append(renderFb2Image(childEl, binaries))
                        else -> sb.append(renderFb2InlineToHtml(childEl, binaries))
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun renderFb2Image(el: Element, binaries: Map<String, String>): String {
        val rawHref = el.getAttributeNS("http://www.w3.org/1999/xlink", "href")
            .ifBlank { el.getAttribute("l:href") }
            .ifBlank { el.getAttribute("xlink:href") }
            .ifBlank { el.getAttribute("href") }
        val id = rawHref.removePrefix("#").trim()
        val dataUri = binaries[id] ?: return ""
        return "<img src=\"$dataUri\" style=\"max-width:100%;height:auto;display:block;margin:12px auto;\" />"
    }

    // ==================== CBZ ====================

    private fun extractCbz(bytes: ByteArray): ExtractedBook {
        val entries = readZipEntries(bytes)
        val imageEntries = entries.entries
            .filter { (name, _) ->
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                ext in IMAGE_EXTENSIONS && !name.substringAfterLast('/').startsWith(".")
            }
            .sortedWith(compareBy({ naturalSortKey(it.key) }, { it.key }))

        if (imageEntries.isEmpty()) {
            return ExtractedBook("CBZ", "", "", 0, emptyList())
        }

        val chunkSize = 20
        val chapters = imageEntries.chunked(chunkSize).mapIndexed { cIdx, chunk ->
            val startPage = cIdx * chunkSize + 1
            val endPage = startPage + chunk.size - 1
            val title = if (imageEntries.size <= chunkSize) "Pages 1–${imageEntries.size}" else "Pages $startPage–$endPage"
            val htmlSb = StringBuilder()
            val paragraphs = mutableListOf<String>()
            chunk.forEachIndexed { offset, (name, imgBytes) ->
                val pageNum = startPage + offset
                val mime = mimeTypeForPath(name)
                val b64 = Base64.getEncoder().encodeToString(imgBytes)
                htmlSb.append("<figure class=\"cbz-page\" style=\"margin:0 0 16px 0;\">")
                    .append("<img src=\"data:").append(mime).append(";base64,").append(b64)
                    .append("\" alt=\"Page ").append(pageNum)
                    .append("\" style=\"max-width:100%;height:auto;display:block;margin:0 auto;\"/>")
                    .append("</figure>")
                paragraphs.add("Page $pageNum")
            }
            ExtractedChapter(
                index = cIdx,
                title = title,
                href = "cbz:$cIdx",
                html = htmlSb.toString(),
                paragraphs = paragraphs
            )
        }

        return ExtractedBook(
            format = "CBZ",
            title = "",
            author = "",
            pageCount = imageEntries.size,
            chapters = chapters
        )
    }

    private fun naturalSortKey(s: String): String {
        return Regex("\\d+").replace(s.lowercase(Locale.ROOT)) { match ->
            match.value.padStart(8, '0')
        }
    }

    // ==================== TXT / MD / HTML ====================

    private fun extractTxt(bytes: ByteArray): ExtractedBook {
        val text = decodeUtf8StrippingBom(bytes).replace("\r\n", "\n").replace("\r", "\n")
        val rawParagraphs = splitTextIntoParagraphs(text)
        if (rawParagraphs.isEmpty()) {
            return ExtractedBook("TXT", "", "", 0, emptyList())
        }

        val maxParagraphsPerChapter = 80
        val chapters = rawParagraphs.chunked(maxParagraphsPerChapter).mapIndexed { idx, chunk ->
            val html = chunk.joinToString("\n") { p -> "<p>${escapeHtml(p)}</p>" }
            val title = if (rawParagraphs.size <= maxParagraphsPerChapter) "Full Text" else "Part ${idx + 1}"
            ExtractedChapter(
                index = idx,
                title = title,
                href = "txt:$idx",
                html = html,
                paragraphs = chunk
            )
        }

        return ExtractedBook(
            format = "TXT",
            title = "",
            author = "",
            pageCount = chapters.size,
            chapters = chapters
        )
    }

    private fun extractMarkdown(bytes: ByteArray): ExtractedBook {
        val text = decodeUtf8StrippingBom(bytes).replace("\r\n", "\n").replace("\r", "\n")
        val lines = text.lines()

        data class MdSection(val title: String, val lines: MutableList<String>)
        val sections = mutableListOf<MdSection>()
        var currentTitle = ""
        var currentLines = mutableListOf<String>()

        for (line in lines) {
            val h1OrH2 = Regex("""^(#{1,2})\s+(.+)$""").find(line.trim())
            if (h1OrH2 != null && currentLines.any { it.isNotBlank() }) {
                sections.add(MdSection(currentTitle.ifBlank { "Section ${sections.size + 1}" }, currentLines))
                currentTitle = h1OrH2.groupValues[2].trim()
                currentLines = mutableListOf(line)
            } else {
                if (currentTitle.isBlank() && h1OrH2 != null) {
                    currentTitle = h1OrH2.groupValues[2].trim()
                }
                currentLines.add(line)
            }
        }
        if (currentLines.any { it.isNotBlank() }) {
            sections.add(MdSection(currentTitle.ifBlank { "Document" }, currentLines))
        }

        val chapters = sections.mapIndexed { idx, section ->
            val html = markdownToHtml(section.lines)
            val paragraphs = extractParagraphsFromHtml(html)
            ExtractedChapter(
                index = idx,
                title = section.title,
                href = "md:$idx",
                html = html,
                paragraphs = paragraphs
            )
        }

        val bookTitle = sections.firstOrNull()?.title?.takeIf { it != "Document" }.orEmpty()
        return ExtractedBook(
            format = "MD",
            title = bookTitle,
            author = "",
            pageCount = chapters.size,
            chapters = chapters
        )
    }

    private fun markdownToHtml(lines: List<String>): String {
        val sb = StringBuilder()
        val paragraphBuf = mutableListOf<String>()
        var inList = false

        fun flushParagraph() {
            if (paragraphBuf.isNotEmpty()) {
                val joined = paragraphBuf.joinToString(" ")
                sb.append("<p>").append(formatMarkdownInline(joined)).append("</p>\n")
                paragraphBuf.clear()
            }
        }

        fun closeList() {
            if (inList) {
                sb.append("</ul>\n")
                inList = false
            }
        }

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                flushParagraph()
                closeList()
                continue
            }
            val heading = Regex("""^(#{1,6})\s+(.+)$""").find(trimmed)
            if (heading != null) {
                flushParagraph()
                closeList()
                val level = heading.groupValues[1].length
                sb.append("<h$level>").append(formatMarkdownInline(heading.groupValues[2].trim())).append("</h$level>\n")
                continue
            }
            if (trimmed.startsWith(">")) {
                flushParagraph()
                closeList()
                val quote = trimmed.removePrefix(">").trim()
                sb.append("<blockquote>").append(formatMarkdownInline(quote)).append("</blockquote>\n")
                continue
            }
            val listMatch = Regex("""^[-*+]\s+(.+)$""").find(trimmed)
            if (listMatch != null) {
                flushParagraph()
                if (!inList) {
                    sb.append("<ul>\n")
                    inList = true
                }
                sb.append("<li>").append(formatMarkdownInline(listMatch.groupValues[1].trim())).append("</li>\n")
                continue
            }
            closeList()
            paragraphBuf.add(trimmed)
        }
        flushParagraph()
        closeList()
        return sb.toString()
    }

    private fun formatMarkdownInline(text: String): String {
        var out = escapeHtml(text)
        out = Regex("""`([^`]+)`""").replace(out) { "<code>${it.groupValues[1]}</code>" }
        out = Regex("""\*\*([^*]+)\*\*""").replace(out) { "<strong>${it.groupValues[1]}</strong>" }
        out = Regex("""__([^_]+)__""").replace(out) { "<strong>${it.groupValues[1]}</strong>" }
        out = Regex("""\*([^*]+)\*""").replace(out) { "<em>${it.groupValues[1]}</em>" }
        out = Regex("""_([^_]+)_""").replace(out) { "<em>${it.groupValues[1]}</em>" }
        out = Regex("""\[([^\]]+)\]\(([^)]+)\)""").replace(out) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        return out
    }

    private fun extractHtml(bytes: ByteArray): ExtractedBook {
        val rawHtml = decodeUtf8StrippingBom(bytes)
        val title = extractTitleFromHtml(rawHtml).orEmpty()
        val authorMatch = Regex(
            """<meta\b[^>]*name\s*=\s*["']author["'][^>]*content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(rawHtml) ?: Regex(
            """<meta\b[^>]*content\s*=\s*["']([^"']+)["'][^>]*name\s*=\s*["']author["']""",
            RegexOption.IGNORE_CASE
        ).find(rawHtml)
        val author = authorMatch?.groupValues?.get(1)?.let { decodeHtmlEntities(it).trim() }.orEmpty()

        val bodyHtml = stripScripts(extractBodyHtml(rawHtml))
        val paragraphs = extractParagraphsFromHtml(bodyHtml)
        val chapters = if (paragraphs.isNotEmpty() || bodyHtml.isNotBlank()) {
            listOf(
                ExtractedChapter(
                    index = 0,
                    title = title.ifBlank { "Document" },
                    href = "html:0",
                    html = bodyHtml,
                    paragraphs = paragraphs
                )
            )
        } else {
            emptyList()
        }

        return ExtractedBook(
            format = "HTML",
            title = title,
            author = author,
            pageCount = chapters.size,
            chapters = chapters
        )
    }

    // ==================== PDF ====================

    private fun extractPdf(context: Context?, filePathOrUri: String, bytes: ByteArray): ExtractedBook {
        val pageCount = getPdfPageCount(context, filePathOrUri, bytes)
        val (title, author) = extractPdfMetadata(bytes)
        val perPageParagraphs = extractPdfPageParagraphs(bytes, pageCount)

        val safePageCount = maxOf(pageCount, perPageParagraphs.size)
        if (safePageCount <= 0) {
            return ExtractedBook("PDF", title, author, 0, emptyList())
        }

        val chapters = mutableListOf<ExtractedChapter>()
        if (safePageCount <= 15) {
            for (p in 0 until safePageCount) {
                val pList = perPageParagraphs.getOrElse(p) { emptyList() }
                val html = pList.joinToString("\n") { "<p>${escapeHtml(it)}</p>" }
                chapters.add(
                    ExtractedChapter(
                        index = p,
                        title = "Page ${p + 1}",
                        href = "pdf:page:$p",
                        html = html,
                        paragraphs = pList
                    )
                )
            }
        } else {
            val chunkSize = 10
            var chunkIndex = 0
            var start = 0
            while (start < safePageCount) {
                val end = minOf(safePageCount - 1, start + chunkSize - 1)
                val combinedParagraphs = mutableListOf<String>()
                for (p in start..end) {
                    combinedParagraphs.addAll(perPageParagraphs.getOrElse(p) { emptyList() })
                }
                val html = combinedParagraphs.joinToString("\n") { "<p>${escapeHtml(it)}</p>" }
                chapters.add(
                    ExtractedChapter(
                        index = chunkIndex,
                        title = "Pages ${start + 1}–${end + 1}",
                        href = "pdf:page:$start",
                        html = html,
                        paragraphs = combinedParagraphs
                    )
                )
                chunkIndex++
                start += chunkSize
            }
        }

        return ExtractedBook(
            format = "PDF",
            title = title,
            author = author,
            pageCount = safePageCount,
            chapters = chapters
        )
    }

    private fun getPdfPageCount(context: Context?, filePathOrUri: String, bytes: ByteArray): Int {
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = openParcelFileDescriptor(context, filePathOrUri)
            if (pfd != null) {
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount > 0) return renderer.pageCount
                }
            }
        } catch (_: Throwable) {
            // Fall through to byte scan (used in JVM unit tests or when PFD fails)
        } finally {
            try {
                pfd?.close()
            } catch (_: Exception) {
            }
        }

        val text = String(bytes, Charsets.ISO_8859_1)
        val count = Regex("""/Type\s*/Page(?![a-zA-Z])""").findAll(text).count()
        return if (count > 0) count else 1
    }

    private fun extractPdfMetadata(bytes: ByteArray): Pair<String, String> {
        val latin = String(bytes, Charsets.ISO_8859_1)
        var title = extractPdfDictString(latin, "Title")
        var author = extractPdfDictString(latin, "Author")
        if (title.isBlank()) {
            title = Regex("""<dc:title[^>]*>.*?<rdf:li[^>]*>([^<]+)</rdf:li>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(latin)?.groupValues?.get(1)?.trim().orEmpty()
        }
        if (author.isBlank()) {
            author = Regex("""<dc:creator[^>]*>.*?<rdf:li[^>]*>([^<]+)</rdf:li>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(latin)?.groupValues?.get(1)?.trim().orEmpty()
        }
        return title to author
    }

    private fun extractPdfDictString(latin: String, key: String): String {
        val parenMatch = Regex("""/$key\s*\(([^)]{1,300})\)""").find(latin)
        if (parenMatch != null) {
            val valStr = decodePdfLiteralString(parenMatch.groupValues[1]).trim()
            if (valStr.isNotBlank() && !valStr.equals("Untitled", ignoreCase = true)) return valStr
        }
        val hexMatch = Regex("""/$key\s*<([0-9A-Fa-f\s]{2,600})>""").find(latin)
        if (hexMatch != null) {
            val valStr = decodePdfHexString(hexMatch.groupValues[1]).trim()
            if (valStr.isNotBlank() && !valStr.equals("Untitled", ignoreCase = true)) return valStr
        }
        return ""
    }

    private data class PdfObj(
        val objNum: Int,
        val dict: String,
        val streamStart: Int,
        val streamEnd: Int
    )

    private fun extractPdfPageParagraphs(bytes: ByteArray, pageCount: Int): List<List<String>> {
        val latin = String(bytes, Charsets.ISO_8859_1)
        val objects = HashMap<Int, PdfObj>()
        val pageContentRefs = mutableListOf<List<Int>>()

        val objHeaderRegex = Regex("""(\d+)\s+\d+\s+obj\b""")
        val matches = objHeaderRegex.findAll(latin).toList()
        for (m in matches) {
            val objNum = m.groupValues[1].toIntOrNull() ?: continue
            val bodyStart = m.range.last + 1
            val endObjIdx = latin.indexOf("endobj", bodyStart)
            if (endObjIdx < 0) continue

            val streamKeywordIdx = findStreamKeyword(latin, bodyStart, endObjIdx)
            val dictEnd = if (streamKeywordIdx >= 0) streamKeywordIdx else endObjIdx
            val dict = latin.substring(bodyStart, dictEnd)

            var streamStart = -1
            var streamEnd = -1
            if (streamKeywordIdx >= 0) {
                var sStart = streamKeywordIdx + "stream".length
                if (sStart < endObjIdx && latin[sStart] == '\r') sStart++
                if (sStart < endObjIdx && latin[sStart] == '\n') sStart++
                val sEnd = latin.indexOf("endstream", sStart)
                if (sEnd in sStart..endObjIdx) {
                    var trimmedEnd = sEnd
                    if (trimmedEnd > sStart && latin[trimmedEnd - 1] == '\n') trimmedEnd--
                    if (trimmedEnd > sStart && latin[trimmedEnd - 1] == '\r') trimmedEnd--
                    streamStart = sStart
                    streamEnd = trimmedEnd
                }
            }

            objects[objNum] = PdfObj(objNum, dict, streamStart, streamEnd)

            if (Regex("""/Type\s*/Page(?![a-zA-Z])""").containsMatchIn(dict)) {
                val refs = parsePdfContentsRefs(dict)
                pageContentRefs.add(refs)
            }
        }

        val pageParagraphs = mutableListOf<List<String>>()
        if (pageContentRefs.isNotEmpty()) {
            for (refs in pageContentRefs) {
                val pageTextBuilder = StringBuilder()
                for (refObjNum in refs) {
                    val obj = objects[refObjNum] ?: continue
                    val streamText = readPdfContentStreamText(bytes, obj)
                    if (streamText.isNotBlank()) {
                        if (pageTextBuilder.isNotEmpty()) pageTextBuilder.append('\n')
                        pageTextBuilder.append(streamText)
                    }
                }
                pageParagraphs.add(splitTextIntoParagraphs(pageTextBuilder.toString()))
            }
        }

        // Fallback if page->Contents refs didn't yield text (e.g. inline or unusual structure)
        if (pageParagraphs.all { it.isEmpty() }) {
            val allExtractedBlocks = mutableListOf<String>()
            for (obj in objects.values.sortedBy { it.objNum }) {
                val text = readPdfContentStreamText(bytes, obj)
                if (text.isNotBlank()) allExtractedBlocks.add(text)
            }
            if (allExtractedBlocks.isEmpty()) {
                // Direct raw scan for BT...ET in uncompressed bytes
                val rawText = extractTextFromPdfContentOperators(latin)
                if (rawText.isNotBlank()) allExtractedBlocks.add(rawText)
            }
            val combined = splitTextIntoParagraphs(allExtractedBlocks.joinToString("\n\n"))
            if (combined.isNotEmpty()) {
                val targetPages = maxOf(1, pageCount)
                val result = MutableList(targetPages) { mutableListOf<String>() }
                val perPage = maxOf(1, (combined.size + targetPages - 1) / targetPages)
                combined.forEachIndexed { idx, para ->
                    val pIdx = (idx / perPage).coerceAtMost(targetPages - 1)
                    result[pIdx].add(para)
                }
                return result
            }
        }

        return pageParagraphs
    }

    private fun findStreamKeyword(latin: String, from: Int, to: Int): Int {
        var idx = latin.indexOf("stream", from)
        while (idx in from until to) {
            val prevOk = idx == from || latin[idx - 1].isWhitespace() || latin[idx - 1] == '>'
            val nextIdx = idx + "stream".length
            val nextOk = nextIdx < to && (latin[nextIdx] == '\r' || latin[nextIdx] == '\n')
            if (prevOk && nextOk) return idx
            idx = latin.indexOf("stream", idx + 6)
        }
        return -1
    }

    private fun parsePdfContentsRefs(dict: String): List<Int> {
        val arrayMatch = Regex("""/Contents\s*\[([^\]]+)\]""").find(dict)
        if (arrayMatch != null) {
            return Regex("""(\d+)\s+\d+\s+R""").findAll(arrayMatch.groupValues[1])
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toList()
        }
        val singleMatch = Regex("""/Contents\s+(\d+)\s+\d+\s+R""").find(dict)
        if (singleMatch != null) {
            val num = singleMatch.groupValues[1].toIntOrNull()
            if (num != null) return listOf(num)
        }
        return emptyList()
    }

    private fun readPdfContentStreamText(bytes: ByteArray, obj: PdfObj): String {
        if (obj.streamStart < 0 || obj.streamEnd <= obj.streamStart) return ""
        if (obj.dict.contains("/Subtype /Image") || obj.dict.contains("/Subtype/Image") ||
            obj.dict.contains("/Type /XRef") || obj.dict.contains("/Type/XRef") ||
            obj.dict.contains("/Subtype /Type1C") || obj.dict.contains("/Subtype/Type1C")
        ) {
            return ""
        }

        val rawStream = bytes.copyOfRange(obj.streamStart, obj.streamEnd)
        val decodedStream = if (obj.dict.contains("/FlateDecode")) {
            inflateFlateBytes(rawStream) ?: return ""
        } else {
            rawStream
        }

        val streamStr = String(decodedStream, Charsets.ISO_8859_1)
        if (!streamStr.contains("BT") && !streamStr.contains("Tj") && !streamStr.contains("TJ")) {
            return ""
        }
        return extractTextFromPdfContentOperators(streamStr)
    }

    private fun inflateFlateBytes(compressed: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream(compressed.size * 2)
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buf, 0, count)
                }
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun extractTextFromPdfContentOperators(content: String): String {
        val sb = StringBuilder()
        var pos = 0
        while (pos < content.length) {
            val btIdx = findPdfToken(content, "BT", pos)
            if (btIdx < 0) break
            val etIdx = findPdfToken(content, "ET", btIdx + 2)
            val blockEnd = if (etIdx >= 0) etIdx else content.length
            val block = content.substring(btIdx + 2, blockEnd)
            extractTextFromBtBlock(block, sb)
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
            pos = blockEnd + 2
        }
        return sb.toString().trim()
    }

    private fun findPdfToken(text: String, token: String, from: Int): Int {
        var idx = text.indexOf(token, from)
        while (idx >= 0) {
            val beforeOk = idx == 0 || text[idx - 1].isWhitespace()
            val afterIdx = idx + token.length
            val afterOk = afterIdx >= text.length || text[afterIdx].isWhitespace()
            if (beforeOk && afterOk) return idx
            idx = text.indexOf(token, idx + token.length)
        }
        return -1
    }

    private fun extractTextFromBtBlock(block: String, out: StringBuilder) {
        var i = 0
        val currentLine = StringBuilder()

        fun flushLine(doubleBreak: Boolean = false) {
            val line = currentLine.toString().trim()
            if (line.isNotEmpty() && isMostlyPrintable(line)) {
                if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                out.append(line)
                if (doubleBreak) out.append("\n\n") else out.append('\n')
            }
            currentLine.setLength(0)
        }

        while (i < block.length) {
            val ch = block[i]
            when {
                ch == '(' -> {
                    val (str, nextIdx) = readPdfLiteralString(block, i)
                    i = nextIdx
                    // Peek next operator (skip whitespace)
                    val opStart = skipSpaces(block, i)
                    if (opStart < block.length && (block.startsWith("Tj", opStart) || block.startsWith("'", opStart) || block.startsWith("\"", opStart))) {
                        currentLine.append(str)
                        if (block[opStart] == '\'' || block[opStart] == '"') {
                            flushLine(false)
                        }
                    }
                }
                ch == '<' && i + 1 < block.length && block[i + 1] != '<' -> {
                    val close = block.indexOf('>', i + 1)
                    if (close > i) {
                        val hexStr = decodePdfHexString(block.substring(i + 1, close))
                        i = close + 1
                        val opStart = skipSpaces(block, i)
                        if (opStart < block.length && block.startsWith("Tj", opStart)) {
                            currentLine.append(hexStr)
                        }
                    } else {
                        i++
                    }
                }
                ch == '[' -> {
                    val (arrayText, nextIdx) = readPdfTjArray(block, i)
                    i = nextIdx
                    val opStart = skipSpaces(block, i)
                    if (opStart < block.length && block.startsWith("TJ", opStart)) {
                        currentLine.append(arrayText)
                    }
                }
                ch == 'T' && i + 1 < block.length && block[i + 1] in charArrayOf('d', 'D', '*', 'm') -> {
                    val prevOk = i == 0 || block[i - 1].isWhitespace() || block[i - 1] in '0'..'9'
                    if (prevOk && (i + 2 >= block.length || block[i + 2].isWhitespace() || block[i + 2] == '(' || block[i + 2] == '[')) {
                        if (currentLine.isNotEmpty()) {
                            currentLine.append(' ')
                        }
                        if (block[i + 1] == '*') {
                            flushLine(false)
                        }
                    }
                    i += 2
                }
                else -> i++
            }
        }
        flushLine(false)
    }

    private fun readPdfTjArray(block: String, startBracket: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = startBracket + 1
        while (i < block.length && block[i] != ']') {
            when {
                block[i] == '(' -> {
                    val (str, nextIdx) = readPdfLiteralString(block, i)
                    sb.append(str)
                    i = nextIdx
                }
                block[i] == '<' -> {
                    val close = block.indexOf('>', i + 1)
                    if (close > i) {
                        sb.append(decodePdfHexString(block.substring(i + 1, close)))
                        i = close + 1
                    } else {
                        i++
                    }
                }
                block[i] == '-' || block[i].isDigit() -> {
                    val numStart = i
                    if (block[i] == '-') i++
                    while (i < block.length && (block[i].isDigit() || block[i] == '.')) i++
                    val num = block.substring(numStart, i).toDoubleOrNull()
                    if (num != null && num < -180.0 && sb.isNotEmpty() && sb.last() != ' ') {
                        sb.append(' ')
                    }
                }
                else -> i++
            }
        }
        return sb.toString() to (if (i < block.length) i + 1 else i)
    }

    private fun readPdfLiteralString(text: String, openParenIdx: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = openParenIdx + 1
        var depth = 1
        while (i < text.length && depth > 0) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                val next = text[i + 1]
                when (next) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    '(', ')', '\\' -> { sb.append(next); i += 2 }
                    '\r' -> { i += if (i + 2 < text.length && text[i + 2] == '\n') 3 else 2 }
                    '\n' -> i += 2
                    in '0'..'7' -> {
                        var octEnd = i + 2
                        while (octEnd < text.length && octEnd < i + 4 && text[octEnd] in '0'..'7') octEnd++
                        val code = text.substring(i + 1, octEnd).toInt(8)
                        sb.append(code.toChar())
                        i = octEnd
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else if (c == '(') {
                depth++
                sb.append(c)
                i++
            } else if (c == ')') {
                depth--
                if (depth > 0) sb.append(c)
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString() to i
    }

    private fun decodePdfLiteralString(raw: String): String {
        val (decoded, _) = readPdfLiteralString("($raw)", 0)
        return decoded
    }

    private fun decodePdfHexString(hex: String): String {
        val clean = hex.replace(Regex("\\s+"), "")
        if (clean.isEmpty()) return ""
        val padded = if (clean.length % 2 == 1) "${clean}0" else clean
        val bytes = ByteArray(padded.length / 2) { idx ->
            padded.substring(idx * 2, idx * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }
        return if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        } else {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun skipSpaces(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun isMostlyPrintable(s: String): Boolean {
        if (s.isBlank()) return false
        var printable = 0
        for (ch in s) {
            if (!ch.isISOControl() || ch.isWhitespace()) printable++
        }
        return printable * 10 >= s.length * 7
    }

    // ==================== HTML / XML / TEXT HELPERS ====================

    private fun parseXmlSafe(bytes: ByteArray): org.w3c.dom.Document? {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
                try {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                } catch (_: Exception) {
                }
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            null
        }
    }

    private fun localNameOf(node: Node): String =
        node.localName ?: node.nodeName.substringAfter(':')

    private fun firstElementText(root: Element, localName: String): String {
        val nsList = root.getElementsByTagNameNS("*", localName)
        if (nsList.length > 0) {
            return normalizeSpaces(nsList.item(0).textContent)
        }
        val plainList = root.getElementsByTagName(localName)
        if (plainList.length > 0) {
            return normalizeSpaces(plainList.item(0).textContent)
        }
        return ""
    }

    private fun firstDescendantElement(root: Element, localName: String): Element? {
        val nsList = root.getElementsByTagNameNS("*", localName)
        if (nsList.length > 0) return nsList.item(0) as? Element
        val plainList = root.getElementsByTagName(localName)
        if (plainList.length > 0) return plainList.item(0) as? Element
        return null
    }

    private fun firstDirectChildElement(parent: Element, localName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && localNameOf(child) == localName) {
                return child as Element
            }
        }
        return null
    }

    private fun directChildElements(parent: Element, localName: String): List<Element> {
        val list = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && localNameOf(child) == localName) {
                list.add(child as Element)
            }
        }
        return list
    }

    private fun firstDirectChildText(parent: Element, childName: String, grandchildName: String): String? {
        val child = firstDirectChildElement(parent, childName) ?: return null
        val grandchild = firstDirectChildElement(child, grandchildName) ?: return null
        return normalizeSpaces(grandchild.textContent)
    }

    private fun extractBodyHtml(rawHtml: String): String {
        val bodyMatch = Regex("""<body\b[^>]*>([\s\S]*?)</body>""", RegexOption.IGNORE_CASE).find(rawHtml)
        if (bodyMatch != null) return bodyMatch.groupValues[1].trim()
        return rawHtml
            .replace(Regex("""<\?xml[^?]*\?>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<!DOCTYPE[^>]*>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<head\b[^>]*>[\s\S]*?</head>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""</?html\b[^>]*>""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun stripScripts(html: String): String {
        return html
            .replace(Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<script\b[^>]*/\s*>""", RegexOption.IGNORE_CASE), "")
    }

    private fun extractTitleFromHtml(rawHtml: String): String? {
        val titleMatch = Regex("""<title\b[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE).find(rawHtml)
        val titleText = titleMatch?.groupValues?.get(1)?.let { stripTagsAndDecode(it) }
        if (!titleText.isNullOrBlank()) return titleText

        val headingMatch = Regex("""<h[1-3]\b[^>]*>([\s\S]*?)</h[1-3]>""", RegexOption.IGNORE_CASE).find(rawHtml)
        val headingText = headingMatch?.groupValues?.get(1)?.let { stripTagsAndDecode(it) }
        return headingText?.takeIf { it.isNotBlank() }
    }

    private fun extractParagraphsFromHtml(html: String): List<String> {
        val result = mutableListOf<String>()
        collectParagraphBlocks(html, result)
        if (result.isEmpty()) {
            val plain = decodeHtmlEntities(html.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("""</(?:div|p|section|article|tr)\s*>""", RegexOption.IGNORE_CASE), "\n\n")
                .replace(Regex("""<[^>]+>"""), " "))
            result.addAll(splitTextIntoParagraphs(plain))
        }
        return result
    }

    private val BLOCK_TAG_REGEX = Regex(
        """<(p|h[1-6]|li|blockquote)\b[^>]*>([\s\S]*?)</\1>""",
        RegexOption.IGNORE_CASE
    )
    private val NESTED_BLOCK_CHECK = Regex("""<(?:p|h[1-6]|li|blockquote)\b""", RegexOption.IGNORE_CASE)

    private fun collectParagraphBlocks(fragment: String, out: MutableList<String>) {
        for (match in BLOCK_TAG_REGEX.findAll(fragment)) {
            val inner = match.groupValues[2]
            if (NESTED_BLOCK_CHECK.containsMatchIn(inner)) {
                collectParagraphBlocks(inner, out)
            } else {
                val text = stripTagsAndDecode(inner)
                if (text.isNotBlank()) out.add(text)
            }
        }
    }

    private fun stripTagsAndDecode(htmlFragment: String): String {
        val noTags = htmlFragment
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""<[^>]+>"""), " ")
        return normalizeSpaces(decodeHtmlEntities(noTags))
    }

    private fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text
        var out = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
        out = Regex("""&#(\d+);""").replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        out = Regex("""&#x([0-9a-fA-F]+);""").replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        return out
    }

    private fun splitTextIntoParagraphs(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n").trim()
        if (normalized.isEmpty()) return emptyList()
        val blankLineBlocks = normalized.split(Regex("""\n\s*\n+"""))
            .map { normalizeSpaces(it) }
            .filter { it.isNotBlank() }
        if (blankLineBlocks.size > 1) return blankLineBlocks
        return normalized.lines()
            .map { normalizeSpaces(it) }
            .filter { it.isNotBlank() }
    }

    private fun normalizeSpaces(s: String?): String =
        s?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

    private fun decodeUtf8StrippingBom(bytes: ByteArray): String {
        val str = String(bytes, Charsets.UTF_8)
        return if (str.startsWith('\uFEFF')) str.substring(1) else str
    }

    private fun normalizeZipPath(path: String): String {
        val decoded = try {
            URLDecoder.decode(path, "UTF-8")
        } catch (_: Exception) {
            path
        }
        val parts = decoded.replace('\\', '/').trimStart('/').split('/')
        val stack = ArrayDeque<String>()
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun resolveZipPath(baseDir: String, relPath: String): String {
        val cleanRel = relPath.substringBefore('#').substringBefore('?')
        val combined = if (baseDir.isBlank() || cleanRel.startsWith('/')) {
            cleanRel
        } else {
            "$baseDir/$cleanRel"
        }
        return normalizeZipPath(combined)
    }

    private fun mimeTypeForPath(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
    }

    private fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun escapeJson(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}
