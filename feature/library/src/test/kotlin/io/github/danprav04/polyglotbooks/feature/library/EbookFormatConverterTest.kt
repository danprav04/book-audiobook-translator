package io.github.danprav04.polyglotbooks.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class EbookFormatConverterTest {

    @Test
    fun testTxtConversion() {
        val txtContent = "Hello World\n\nChapter 1: Intro\nThis is a test text file."
        val epubBytes = EbookFormatConverter.convertToEpub(
            txtContent.byteInputStream(),
            BookFormat.TXT,
            title = "Test TXT",
            author = "Author"
        )
        verifyValidEpub(epubBytes)
    }

    @Test
    fun testMarkdownConversion() {
        val mdContent = "# Main Title\n\n## Chapter 1\nMarkdown paragraph here."
        val epubBytes = EbookFormatConverter.convertToEpub(
            mdContent.byteInputStream(),
            BookFormat.MD,
            title = "Test MD",
            author = "Author"
        )
        verifyValidEpub(epubBytes)
    }

    @Test
    fun testHtmlConversion() {
        val htmlContent = "<html><body><h1>HTML Title</h1><p>Paragraph 1</p></body></html>"
        val epubBytes = EbookFormatConverter.convertToEpub(
            htmlContent.byteInputStream(),
            BookFormat.HTML,
            title = "Test HTML",
            author = "Author"
        )
        verifyValidEpub(epubBytes)
    }

    @Test
    fun testDocxConversion() {
        val docxBytes = createSyntheticDocx("Hello DOCX World")
        val epubBytes = EbookFormatConverter.convertToEpub(
            docxBytes.inputStream(),
            BookFormat.DOCX,
            title = "Test DOCX",
            author = "Author"
        )
        verifyValidEpub(epubBytes)
    }

    @Test
    fun testOdtConversion() {
        val odtBytes = createSyntheticOdt("Hello ODT World")
        val epubBytes = EbookFormatConverter.convertToEpub(
            odtBytes.inputStream(),
            BookFormat.ODT,
            title = "Test ODT",
            author = "Author"
        )
        verifyValidEpub(epubBytes)
    }

    @Test
    fun testRtfConversion() {
        val rtfContent = "{\\rtf1\\ansi Hello RTF \\par World}"
        val epubBytes = EbookFormatConverter.convertToEpub(
            rtfContent.byteInputStream(),
            BookFormat.RTF,
            title = "Test RTF",
            author = "Author"
        )
        verifyValidEpub(epubBytes)
    }

    private fun verifyValidEpub(epubBytes: ByteArray) {
        val zis = ZipInputStream(epubBytes.inputStream())
        val entries = mutableSetOf<String>()
        var hasMimetype = false
        var mimetypeStored = false

        var entry = zis.nextEntry
        while (entry != null) {
            entries.add(entry.name)
            if (entry.name == "mimetype") {
                hasMimetype = true
                if (entry.method == ZipEntry.STORED) {
                    mimetypeStored = true
                }
                val content = zis.readBytes().toString(StandardCharsets.US_ASCII)
                assertEquals("application/epub+zip", content)
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }

        assertTrue("EPUB must contain mimetype", hasMimetype)
        assertTrue("mimetype must be STORED uncompressed", mimetypeStored)
        assertTrue("EPUB must contain container.xml", entries.contains("META-INF/container.xml"))
        assertTrue("EPUB must contain content.opf", entries.contains("OEBPS/content.opf"))
        assertTrue("EPUB must contain at least one chapter XHTML", entries.any { it.startsWith("OEBPS/chapter-") })
    }

    private fun createSyntheticDocx(text: String): ByteArray {
        val bao = ByteArrayOutputStream()
        ZipOutputStream(bao).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            val xml = "<w:document><w:body><w:p><w:t>$text</w:t></w:p></w:body></w:document>"
            zos.write(xml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }
        return bao.toByteArray()
    }

    private fun createSyntheticOdt(text: String): ByteArray {
        val bao = ByteArrayOutputStream()
        ZipOutputStream(bao).use { zos ->
            zos.putNextEntry(ZipEntry("content.xml"))
            val xml = "<office:document-content><office:body><office:text><text:p>$text</text:p></office:text></office:body></office:document-content>"
            zos.write(xml.toByteArray(StandardCharsets.UTF_8))
            zos.closeEntry()
        }
        return bao.toByteArray()
    }
}
