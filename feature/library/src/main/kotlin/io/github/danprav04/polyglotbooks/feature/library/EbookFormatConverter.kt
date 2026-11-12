package io.github.danprav04.polyglotbooks.feature.library

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Converts various ebook and document formats (TXT, MARKDOWN, HTML, DOCX, ODT, RTF)
 * into a valid standard EPUB 2/3 ZIP archive containing mimetype, container.xml,
 * content.opf, and chapter XHTML files.
 */
object EbookFormatConverter {

    fun convertToEpub(
        inputStream: InputStream,
        format: BookFormat,
        title: String = "Untitled",
        author: String = "Unknown Author",
        language: String = "en"
    ): ByteArray {
        val bytes = inputStream.use { it.readBytes() }
        val paragraphs = extractParagraphs(bytes, format)
        val chapters = splitIntoChapters(paragraphs)
        return buildEpubZip(chapters, title, author, language)
    }

    private fun extractParagraphs(bytes: ByteArray, format: BookFormat): List<String> {
        val text = String(bytes, StandardCharsets.UTF_8)
        return when (format) {
            BookFormat.TXT -> text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()

            BookFormat.MD -> {
                // Parse markdown lines or headings
                text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            }

            BookFormat.HTML -> {
                // Simple tag stripper / paragraph extractor
                val cleaned = text.replace(Regex("<(?i)p[^>]*>", RegexOption.DOT_MATCHES_ALL), "\n\n")
                    .replace(Regex("<(?i)br[^/]*/?>"), "\n")
                    .replace(Regex("<(?i)h[1-6][^>]*>", RegexOption.DOT_MATCHES_ALL), "\n\n# ")
                    .replace(Regex("<[^>]*>"), "")
                cleaned.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            }

            BookFormat.DOCX -> {
                // Extract from ZIP word/document.xml <w:t> paragraphs
                extractDocxParagraphs(bytes)
            }

            BookFormat.ODT -> {
                // Extract from ZIP content.xml <text:p> / <text:h> paragraphs
                extractOdtParagraphs(bytes)
            }

            BookFormat.RTF -> {
                // Strip RTF control words
                extractRtfParagraphs(text)
            }

            else -> {
                text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            }
        }
    }

    private fun extractDocxParagraphs(bytes: ByteArray): List<String> {
        val paragraphs = mutableListOf<String>()
        try {
            val zis = ZipInputStream(bytes.inputStream())
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    val xmlContent = zis.bufferedReader(StandardCharsets.UTF_8).readText()
                    // Extract text inside <w:t>...</w:t> or split by <w:p>
                    val pRegex = Regex("<w:p[^>]*>(.*?)</w:p>", RegexOption.DOT_MATCHES_ALL)
                    val tRegex = Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                    
                    for (pMatch in pRegex.findAll(xmlContent)) {
                        val pContent = pMatch.groupValues[1]
                        val sb = StringBuilder()
                        for (tMatch in tRegex.findAll(pContent)) {
                            sb.append(tMatch.groupValues[1])
                        }
                        val para = sb.toString().trim()
                        if (para.isNotEmpty()) {
                            paragraphs.add(para)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        } catch (e: Exception) {
            // Fallback to raw string if zip extraction fails
        }
        if (paragraphs.isEmpty()) {
            paragraphs.add(String(bytes, StandardCharsets.UTF_8))
        }
        return paragraphs
    }

    private fun extractOdtParagraphs(bytes: ByteArray): List<String> {
        val paragraphs = mutableListOf<String>()
        try {
            val zis = ZipInputStream(bytes.inputStream())
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "content.xml") {
                    val xmlContent = zis.bufferedReader(StandardCharsets.UTF_8).readText()
                    // Extract text inside <text:p...>...</text:p> or <text:h...>...</text:h>
                    val tagRegex = Regex("<text:(p|h)[^>]*>(.*?)</text:(p|h)>", RegexOption.DOT_MATCHES_ALL)
                    for (match in tagRegex.findAll(xmlContent)) {
                        val inner = match.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
                        if (inner.isNotEmpty()) {
                            paragraphs.add(inner)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        } catch (e: Exception) {
            // Fallback
        }
        if (paragraphs.isEmpty()) {
            paragraphs.add(String(bytes, StandardCharsets.UTF_8))
        }
        return paragraphs
    }

    private fun extractRtfParagraphs(text: String): List<String> {
        val normalized = text.replace("\\par", "\n")
            .replace(Regex("\\\\[a-z]+\\-?[0-9]* ?"), "")
            .replace(Regex("[{}]"), "")
        return normalized.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    data class Chapter(
        val title: String,
        val paragraphs: List<String>
    )

    private fun splitIntoChapters(paragraphs: List<String>): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var currentTitle = "Chapter 1"
        var currentParas = mutableListOf<String>()

        for (p in paragraphs) {
            val isHeading = p.startsWith("# ") ||
                    p.startsWith("Chapter ") ||
                    (p.length < 80 && (p.uppercase() == p || p.startsWith("Section ")))

            if (isHeading && currentParas.isNotEmpty()) {
                chapters.add(Chapter(currentTitle, currentParas))
                currentTitle = p.removePrefix("# ").trim()
                currentParas = mutableListOf()
            } else if (isHeading && currentParas.isEmpty()) {
                currentTitle = p.removePrefix("# ").trim()
            } else {
                currentParas.add(p)
            }
        }

        if (currentParas.isNotEmpty() || chapters.isEmpty()) {
            chapters.add(Chapter(currentTitle, currentParas))
        }

        return chapters
    }

    private fun buildEpubZip(
        chapters: List<Chapter>,
        title: String,
        author: String,
        language: String
    ): ByteArray {
        val bao = ByteArrayOutputStream()
        ZipOutputStream(bao).use { zos ->
            // 1. mimetype (must be stored, uncompressed, first entry)
            val mimetypeBytes = "application/epub+zip".toByteArray(StandardCharsets.US_ASCII)
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetypeBytes.size.toLong()
                compressedSize = mimetypeBytes.size.toLong()
                crc = CRC32().apply { update(mimetypeBytes) }.value
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mimetypeBytes)
            zos.closeEntry()

            // 2. META-INF/container.xml
            val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
""".trimIndent()
            addZipEntry(zos, "META-INF/container.xml", containerXml.toByteArray(StandardCharsets.UTF_8))

            // 3. Chapter XHTML files
            val manifestItems = StringBuilder()
            val spineItems = StringBuilder()

            chapters.forEachIndexed { index, chapter ->
                val fileName = "chapter-${index + 1}.xhtml"
                val id = "ch${index + 1}"
                manifestItems.append("    <item id=\"$id\" href=\"$fileName\" media-type=\"application/xhtml+xml\"/>\n")
                spineItems.append("    <itemref idref=\"$id\"/>\n")

                val xhtmlContent = buildString {
                    append("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" lang="$language">
<head>
  <title>${escapeXml(chapter.title)}</title>
</head>
<body>
  <h1>${escapeXml(chapter.title)}</h1>
""")
                    chapter.paragraphs.forEachIndexed { pIdx, p ->
                        append("  <p id=\"p${pIdx + 1}\">${escapeXml(p)}</p>\n")
                    }
                    append("""</body>
</html>
""")
                }
                addZipEntry(zos, "OEBPS/$fileName", xhtmlContent.toByteArray(StandardCharsets.UTF_8))
            }

            // 4. OEBPS/content.opf
            val contentOpf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>${escapeXml(title)}</dc:title>
    <dc:creator>${escapeXml(author)}</dc:creator>
    <dc:language>$language</dc:language>
    <dc:identifier id="book-id">urn:uuid:${java.util.UUID.randomUUID()}</dc:identifier>
  </metadata>
  <manifest>
$manifestItems  </manifest>
  <spine>
$spineItems  </spine>
</package>
""".trimIndent()
            addZipEntry(zos, "OEBPS/content.opf", contentOpf.toByteArray(StandardCharsets.UTF_8))
        }
        return bao.toByteArray()
    }

    private fun addZipEntry(zos: ZipOutputStream, path: String, data: ByteArray) {
        val entry = ZipEntry(path).apply {
            method = ZipEntry.DEFLATED
            size = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
