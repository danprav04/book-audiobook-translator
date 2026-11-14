package io.github.danprav04.polyglotbooks.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EbookContentExtractorTest {

    @Test
    fun `extractTitleFromHtml prefers h1-h3 headings before document title tag`() {
        val withHeading = """
            <html>
              <head><title>Book Wide Doc Title</title></head>
              <body>
                <h2>  Chapter &amp; Two: The Harbor </h2>
                <p>First paragraph text.</p>
              </body>
            </html>
        """.trimIndent()
        assertEquals("Chapter & Two: The Harbor", EbookContentExtractor.extractTitleFromHtml(withHeading))

        val withoutHeading = """
            <html>
              <head><title>Fallback Head Title</title></head>
              <body><p>Body without heading.</p></body>
            </html>
        """.trimIndent()
        assertEquals("Fallback Head Title", EbookContentExtractor.extractTitleFromHtml(withoutHeading))
    }

    @Test
    fun `extractParagraphsFromHtml matches Chromium DOM leaf blocks across self-closing spacers and nested lists`() {
        val html = """
            <html>
              <head><style>p { margin: 0; }</style><script>var ignored = "<p>nope</p>";</script></head>
              <body>
                <h1>Chapter Heading</h1>
                <p/>
                <div class="spacer" />
                <p>First body paragraph.
                <!-- inline comment -->
                <p>Implicitly closed second paragraph.</p>
                <ul>
                  <li><p>Nested list paragraph only.</p></li>
                  <li>Plain list item.</li>
                </ul>
                <blockquote/>
                <blockquote>Quoted block text.</blockquote>
              </body>
            </html>
        """.trimIndent()

        val extracted = EbookContentExtractor.extractParagraphsFromHtml(html)
        assertEquals(
            listOf(
                "Chapter Heading",
                "First body paragraph.",
                "Implicitly closed second paragraph.",
                "Nested list paragraph only.",
                "Plain list item.",
                "Quoted block text."
            ),
            extracted
        )
    }

    @Test
    fun `extractParagraphsFromHtml treats image-only and whitespace spacer markup as empty`() {
        val imageOnly = """
            <html><body>
              <div class="cover"><img src="cover.jpg" alt="Cover Image"/></div>
              <p/>
              <p>   &#160; </p>
            </body></html>
        """.trimIndent()
        assertTrue(EbookContentExtractor.extractParagraphsFromHtml(imageOnly).isEmpty())
    }

    @Test
    fun `extractBook extracts EPUB chapters preserving empty image pages and DOM paragraph counts`() {
        val epubBytes = buildMinimalEpub(
            spineFiles = listOf(
                "ch1.xhtml" to """
                    <html><head><title>Ignored</title></head><body>
                      <h1>Same Title</h1>
                      <p/>
                      <p>Chapter one sentence one.</p>
                      <p>Chapter one sentence two.</p>
                    </body></html>
                """.trimIndent(),
                "blank.xhtml" to """
                    <html><body><div class="plate"><img src="plate.png" alt="Plate"/></div></body></html>
                """.trimIndent(),
                "ch2.xhtml" to """
                    <html><head><title>Ignored</title></head><body>
                      <h1>Same Title</h1>
                      <p>Chapter three paragraph one.</p>
                    </body></html>
                """.trimIndent()
            )
        )
        val tmpFile = Files.createTempFile("parity-test", ".epub").toFile()
        try {
            tmpFile.writeBytes(epubBytes)
            val book = EbookContentExtractor.extractBook(null, tmpFile.absolutePath, "EPUB")
            assertEquals(3, book.chapters.size)
            assertEquals("Same Title", book.chapters[0].title)
            assertEquals(
                listOf("Same Title", "Chapter one sentence one.", "Chapter one sentence two."),
                book.chapters[0].paragraphs
            )
            assertTrue(book.chapters[1].paragraphs.isEmpty())
            assertEquals("Same Title", book.chapters[2].title)
            assertEquals(
                listOf("Same Title", "Chapter three paragraph one."),
                book.chapters[2].paragraphs
            )
        } finally {
            tmpFile.delete()
        }
    }

    private fun buildMinimalEpub(spineFiles: List<Pair<String, String>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/epub+zip".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(
                (
                    """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                    """.trimIndent()
                ).toByteArray()
            )
            zos.closeEntry()

            val manifestItems = spineFiles.mapIndexed { idx, (name, _) ->
                """    <item id="item$idx" href="$name" media-type="application/xhtml+xml"/>"""
            }.joinToString("\n")
            val spineRefs = spineFiles.indices.joinToString("\n") { idx ->
                """    <itemref idref="item$idx"/>"""
            }
            val opfXml = buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">\n")
                append("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n")
                append("    <dc:title>Parity Book</dc:title>\n")
                append("    <dc:creator>Test Author</dc:creator>\n")
                append("  </metadata>\n")
                append("  <manifest>\n")
                append(manifestItems).append('\n')
                append("  </manifest>\n")
                append("  <spine>\n")
                append(spineRefs).append('\n')
                append("  </spine>\n")
                append("</package>\n")
            }
            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opfXml.toByteArray())
            zos.closeEntry()

            spineFiles.forEach { (name, content) ->
                zos.putNextEntry(ZipEntry("OEBPS/$name"))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
