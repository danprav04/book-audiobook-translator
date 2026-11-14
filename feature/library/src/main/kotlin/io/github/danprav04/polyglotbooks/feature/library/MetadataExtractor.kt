package io.github.danprav04.polyglotbooks.feature.library

import io.github.danprav04.polyglotbooks.core.model.Book
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Interface for extracting title, author, and cover art from books and audiobooks.
 */
interface MetadataExtractor {
    suspend fun extractMetadata(file: File, format: BookFormat): Book
    suspend fun extractMetadata(inputStream: InputStream, format: BookFormat, fileName: String): Book
}

class DefaultMetadataExtractor : MetadataExtractor {
    override suspend fun extractMetadata(file: File, format: BookFormat): Book {
        return file.inputStream().use { extractMetadata(it, format, file.name) }.copy(
            filePath = file.absolutePath,
            id = file.absolutePath.hashCode().toString()
        )
    }

    override suspend fun extractMetadata(inputStream: InputStream, format: BookFormat, fileName: String): Book {
        val bytes = inputStream.readBytes()
        val baseName = fileName.substringBeforeLast('.').ifBlank { fileName }
        var title = baseName
        var author = "Unknown Author"

        when (format) {
            BookFormat.EPUB -> {
                val (parsedTitle, parsedAuthor) = extractEpubMetadata(bytes)
                if (!parsedTitle.isNullOrBlank()) title = parsedTitle
                if (!parsedAuthor.isNullOrBlank()) author = parsedAuthor
            }
            BookFormat.FB2 -> {
                val (parsedTitle, parsedAuthor) = extractFb2Metadata(bytes)
                if (!parsedTitle.isNullOrBlank()) title = parsedTitle
                if (!parsedAuthor.isNullOrBlank()) author = parsedAuthor
            }
            BookFormat.HTML -> {
                val (parsedTitle, parsedAuthor) = extractHtmlMetadata(bytes)
                if (!parsedTitle.isNullOrBlank()) title = parsedTitle
                if (!parsedAuthor.isNullOrBlank()) author = parsedAuthor
            }
            BookFormat.PDF -> {
                val (parsedTitle, parsedAuthor) = extractPdfMetadata(bytes)
                if (!parsedTitle.isNullOrBlank()) title = parsedTitle
                if (!parsedAuthor.isNullOrBlank()) author = parsedAuthor
            }
            else -> Unit
        }

        return Book(
            id = fileName.hashCode().toString(),
            title = title,
            author = author,
            format = format.name,
            filePath = "",
            isAudiobook = format.isAudiobook
        )
    }

    private fun extractEpubMetadata(bytes: ByteArray): Pair<String?, String?> {
        if (bytes.isEmpty()) return null to null
        val isZip = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        if (!isZip) {
            return extractOpfMetadata(bytes)
        }

        return try {
            var containerBytes: ByteArray? = null
            val opfEntries = LinkedHashMap<String, ByteArray>()

            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val normalizedName = entry.name.trimStart('/')
                        if (normalizedName.equals("META-INF/container.xml", ignoreCase = true)) {
                            containerBytes = zis.readBytes()
                        } else if (normalizedName.lowercase().endsWith(".opf")) {
                            opfEntries[normalizedName] = zis.readBytes()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val rootfilePath = containerBytes?.let { parseContainerOpfPath(it) }?.trimStart('/')
            val opfBytes = when {
                rootfilePath != null && opfEntries.containsKey(rootfilePath) -> opfEntries[rootfilePath]
                rootfilePath != null -> opfEntries.entries.firstOrNull {
                    it.key.equals(rootfilePath, ignoreCase = true)
                }?.value
                else -> null
            } ?: opfEntries.values.firstOrNull()

            if (opfBytes != null) extractOpfMetadata(opfBytes) else (null to null)
        } catch (_: Exception) {
            null to null
        }
    }

    private fun parseContainerOpfPath(containerBytes: ByteArray): String? {
        val doc = parseXmlSafely(containerBytes) ?: return null
        val rootfiles = doc.getElementsByTagNameNS("*", "rootfile")
        for (i in 0 until rootfiles.length) {
            val el = rootfiles.item(i) as? Element ?: continue
            val fullPath = el.getAttribute("full-path").trim()
            if (fullPath.isNotEmpty()) return fullPath
        }
        val fallback = doc.getElementsByTagName("rootfile")
        for (i in 0 until fallback.length) {
            val el = fallback.item(i) as? Element ?: continue
            val fullPath = el.getAttribute("full-path").trim()
            if (fullPath.isNotEmpty()) return fullPath
        }
        return null
    }

    private fun extractOpfMetadata(opfBytes: ByteArray): Pair<String?, String?> {
        val doc = parseXmlSafely(opfBytes) ?: return null to null
        val title = findFirstText(doc, "dc:title", "title")
        val author = findFirstText(doc, "dc:creator", "creator")
        return title to author
    }

    private fun extractFb2Metadata(bytes: ByteArray): Pair<String?, String?> {
        if (bytes.isEmpty()) return null to null
        val doc = parseXmlSafely(bytes) ?: return null to null

        val title = findFirstText(doc, "book-title", "book-title")

        var author: String? = null
        val titleInfoList = doc.getElementsByTagNameNS("*", "title-info")
        val searchRoot: Element? = if (titleInfoList.length > 0) {
            titleInfoList.item(0) as? Element
        } else {
            null
        }

        val authorNodes = searchRoot?.getElementsByTagNameNS("*", "author")
            ?: doc.getElementsByTagNameNS("*", "author")
        val resolvedAuthorNodes = if (authorNodes.length > 0) {
            authorNodes
        } else {
            searchRoot?.getElementsByTagName("author") ?: doc.getElementsByTagName("author")
        }

        if (resolvedAuthorNodes.length > 0) {
            val authorEl = resolvedAuthorNodes.item(0) as? Element
            if (authorEl != null) {
                val firstName = findFirstTextInElement(authorEl, "first-name")
                val lastName = findFirstTextInElement(authorEl, "last-name")
                val nickname = findFirstTextInElement(authorEl, "nickname")
                val fullName = listOfNotNull(firstName, lastName)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                author = fullName.ifBlank { nickname?.trim() }?.takeIf { it.isNotBlank() }
            }
        }

        return title to author
    }

    private fun extractHtmlMetadata(bytes: ByteArray): Pair<String?, String?> {
        if (bytes.isEmpty()) return null to null
        val headAscii = String(bytes, 0, minOf(bytes.size, 2048), StandardCharsets.ISO_8859_1)
        val charsetMatch = Regex("""charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
            .find(headAscii)
        val charset = charsetMatch?.groupValues?.get(1)?.let {
            try {
                Charset.forName(it)
            } catch (_: Exception) {
                null
            }
        } ?: StandardCharsets.UTF_8

        val html = String(bytes, charset)

        val titleMatch = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
        val title = titleMatch?.groupValues?.get(1)
            ?.replace(Regex("<[^>]+>"), "")
            ?.let { decodeHtmlEntities(it) }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        var author: String? = null
        val metaRegex = Regex("""<meta\b([^>]*)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (match in metaRegex.findAll(html)) {
            val attrs = match.groupValues[1]
            val name = extractHtmlAttribute(attrs, "name")
            if (name.equals("author", ignoreCase = true)) {
                val content = extractHtmlAttribute(attrs, "content")
                    ?.let { decodeHtmlEntities(it) }
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                if (!content.isNullOrEmpty()) {
                    author = content
                    break
                }
            }
        }

        return title to author
    }

    private fun extractHtmlAttribute(attrs: String, attrName: String): String? {
        val regex = Regex(
            """\b${Regex.escape(attrName)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(attrs) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
    }

    private fun extractPdfMetadata(bytes: ByteArray): Pair<String?, String?> {
        if (bytes.isEmpty()) return null to null

        val slices = if (bytes.size > 2 * 1024 * 1024) {
            listOf(
                bytes.copyOfRange(0, 1024 * 1024),
                bytes.copyOfRange(bytes.size - 1024 * 1024, bytes.size)
            )
        } else {
            listOf(bytes)
        }

        var title: String? = null
        var author: String? = null

        for (slice in slices) {
            val isoText = String(slice, StandardCharsets.ISO_8859_1)
            if (title == null) {
                title = extractPdfDictString(isoText, "Title")?.takeUnless(::isNoisyPdfMetadata)
            }
            if (author == null) {
                author = extractPdfDictString(isoText, "Author")?.takeUnless(::isNoisyPdfMetadata)
            }

            if (title == null || author == null) {
                val utf8Text = String(slice, StandardCharsets.UTF_8)
                if (title == null) {
                    title = extractXmpDcField(utf8Text, "title")?.takeUnless(::isNoisyPdfMetadata)
                }
                if (author == null) {
                    author = extractXmpDcField(utf8Text, "creator")?.takeUnless(::isNoisyPdfMetadata)
                }
            }
        }

        return title to author
    }

    private fun extractPdfDictString(isoText: String, key: String): String? {
        val keyRegex = Regex("""/$key\b\s*([(<])""")
        for (match in keyRegex.findAll(isoText)) {
            val delimiter = match.groupValues[1]
            val valueStart = match.range.last + 1
            if (delimiter == "(") {
                val raw = readBalancedPdfParens(isoText, valueStart) ?: continue
                val decoded = decodePdfLiteralString(raw).trim()
                if (decoded.isNotEmpty()) return decoded
            } else if (delimiter == "<") {
                // Skip PDF dictionary opener "<<"
                if (valueStart < isoText.length && isoText[valueStart] == '<') continue
                val closeIdx = isoText.indexOf('>', valueStart)
                if (closeIdx != -1) {
                    val hex = isoText.substring(valueStart, closeIdx)
                    val decoded = decodePdfHexString(hex).trim()
                    if (decoded.isNotEmpty()) return decoded
                }
            }
        }
        return null
    }

    private fun readBalancedPdfParens(text: String, startIndex: Int): String? {
        var depth = 1
        var i = startIndex
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\') {
                i += 2
                continue
            }
            if (ch == '(') {
                depth++
            } else if (ch == ')') {
                depth--
                if (depth == 0) {
                    return text.substring(startIndex, i)
                }
            }
            i++
        }
        return null
    }

    private fun decodePdfLiteralString(raw: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                val next = raw[i + 1]
                when (next) {
                    'n' -> { out.write('\n'.code); i += 2 }
                    'r' -> { out.write('\r'.code); i += 2 }
                    't' -> { out.write('\t'.code); i += 2 }
                    'b' -> { out.write('\b'.code); i += 2 }
                    'f' -> { out.write('\u000C'.code); i += 2 }
                    '(', ')', '\\' -> { out.write(next.code); i += 2 }
                    '\r' -> {
                        i += if (i + 2 < raw.length && raw[i + 2] == '\n') 3 else 2
                    }
                    '\n' -> i += 2
                    in '0'..'7' -> {
                        var octal = 0
                        var count = 0
                        while (count < 3 && i + 1 + count < raw.length && raw[i + 1 + count] in '0'..'7') {
                            octal = (octal shl 3) or (raw[i + 1 + count] - '0')
                            count++
                        }
                        out.write(octal and 0xFF)
                        i += 1 + count
                    }
                    else -> { out.write(next.code and 0xFF); i += 2 }
                }
            } else {
                out.write(ch.code and 0xFF)
                i++
            }
        }
        return decodePdfTextBytes(out.toByteArray())
    }

    private fun decodePdfHexString(hex: String): String {
        val cleaned = hex.filter { !it.isWhitespace() }
        if (cleaned.isEmpty() || !cleaned.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return ""
        }
        val padded = if (cleaned.length % 2 != 0) cleaned + "0" else cleaned
        val bytes = ByteArray(padded.length / 2)
        for (i in bytes.indices) {
            bytes[i] = padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return decodePdfTextBytes(bytes)
    }

    private fun decodePdfTextBytes(rawBytes: ByteArray): String {
        if (rawBytes.isEmpty()) return ""
        return when {
            rawBytes.size >= 2 && rawBytes[0] == 0xFE.toByte() && rawBytes[1] == 0xFF.toByte() ->
                String(rawBytes, 2, rawBytes.size - 2, StandardCharsets.UTF_16BE)
            rawBytes.size >= 2 && rawBytes[0] == 0xFF.toByte() && rawBytes[1] == 0xFE.toByte() ->
                String(rawBytes, 2, rawBytes.size - 2, StandardCharsets.UTF_16LE)
            rawBytes.size >= 3 && rawBytes[0] == 0xEF.toByte() && rawBytes[1] == 0xBB.toByte() && rawBytes[2] == 0xBF.toByte() ->
                String(rawBytes, 3, rawBytes.size - 3, StandardCharsets.UTF_8)
            else -> {
                val utf8 = String(rawBytes, StandardCharsets.UTF_8)
                if (utf8.contains('\uFFFD')) String(rawBytes, StandardCharsets.ISO_8859_1) else utf8
            }
        }.replace("\u0000", "").trim()
    }

    private fun extractXmpDcField(utf8Text: String, field: String): String? {
        val fieldRegex = Regex(
            """<dc:$field\b[^>]*>(.*?)</dc:$field>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val match = fieldRegex.find(utf8Text) ?: return null
        val inner = match.groupValues[1]
        val liMatch = Regex("""<rdf:li\b[^>]*>(.*?)</rdf:li>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(inner)
        val rawValue = liMatch?.groupValues?.get(1) ?: inner
        return decodeHtmlEntities(rawValue.replace(Regex("<[^>]+>"), ""))
            .replace(Regex("\\s+"), " ")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun isNoisyPdfMetadata(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized.isEmpty() || normalized in setOf(
            "untitled",
            "microsoft word",
            "unknown",
            "unknown author",
            "no title",
            "none"
        )
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

    private fun findFirstText(doc: Document, qualifiedName: String, localName: String): String? {
        val nsNodes = doc.getElementsByTagNameNS("*", localName)
        for (i in 0 until nsNodes.length) {
            val text = nsNodes.item(i).textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        val qNodes = doc.getElementsByTagName(qualifiedName)
        for (i in 0 until qNodes.length) {
            val text = qNodes.item(i).textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        if (qualifiedName != localName) {
            val localNodes = doc.getElementsByTagName(localName)
            for (i in 0 until localNodes.length) {
                val text = localNodes.item(i).textContent?.trim()
                if (!text.isNullOrEmpty()) return text
            }
        }
        return null
    }

    private fun findFirstTextInElement(element: Element, localName: String): String? {
        val nsNodes = element.getElementsByTagNameNS("*", localName)
        for (i in 0 until nsNodes.length) {
            val text = nsNodes.item(i).textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        val localNodes = element.getElementsByTagName(localName)
        for (i in 0 until localNodes.length) {
            val text = localNodes.item(i).textContent?.trim()
            if (!text.isNullOrEmpty()) return text
        }
        return null
    }

    private fun decodeHtmlEntities(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
    }
}
