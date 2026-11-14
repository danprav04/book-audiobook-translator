package io.github.danprav04.polyglotbooks.feature.library

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ChapterParserTest {

    @Test
    fun testCueSheetParser() {
        val cueContent = """
            TITLE "Test Album"
            PERFORMER "Test Artist"
            FILE "audio.mp3" MP3
              TRACK 01 AUDIO
                TITLE "Chapter One"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Chapter Two"
                INDEX 01 02:30:00
        """.trimIndent()

        val parser = CueSheetChapterParser()
        val chapters = parser.parseChapters(ByteArrayInputStream(cueContent.toByteArray()))

        assertEquals(2, chapters.size)
        assertEquals("Chapter One", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals("Chapter Two", chapters[1].title)
        assertEquals(150000L, chapters[1].startMs)
    }

    @Test
    fun testFolderChapterParser() {
        val entries = listOf(
            AudioFileEntry("01_intro.mp3", "content://audio/1", 60000L),
            AudioFileEntry("02_body.mp3", "content://audio/2", 120000L)
        )

        val parser = FolderChapterParser()
        val chapters = parser.parseChapters(entries)

        assertEquals(2, chapters.size)
        assertEquals("01_intro", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(60000L, chapters[0].endMs)

        assertEquals("02_body", chapters[1].title)
        assertEquals(60000L, chapters[1].startMs)
        assertEquals(180000L, chapters[1].endMs)
    }

    @Test
    fun testId3ChapParser() {
        val body = "ch1\u0000".toByteArray(Charsets.ISO_8859_1) +
                ByteBuffer.allocate(4).putInt(10).array() + // start time 10ms
                ByteBuffer.allocate(4).putInt(50).array() + // end time 50ms
                ByteBuffer.allocate(4).putInt(0).array() +  // start offset
                ByteBuffer.allocate(4).putInt(0).array()    // end offset
        
        val frameId = "CHAP".toByteArray(Charsets.ISO_8859_1)
        val sizeBytes = ByteBuffer.allocate(4).putInt(body.size).array()
        val flags = byteArrayOf(0, 0)
        
        val syntheticBytes = frameId + sizeBytes + flags + body
        val parser = Id3ChapChapterParser()
        val chapters = parser.parseChapters(syntheticBytes)

        assertEquals(1, chapters.size)
        assertEquals("ch1", chapters[0].id)
        assertEquals(10L, chapters[0].startMs)
        assertEquals(50L, chapters[0].endMs)
    }

    @Test
    fun testId3ChapParserCorruptedNonPositiveSizeDoesNotHang() {
        val parser = Id3ChapChapterParser()
        val frameId = "CHAP".toByteArray(Charsets.ISO_8859_1)
        val flags = byteArrayOf(0, 0)

        // Negative size (-10)
        val negSizeBytes = ByteBuffer.allocate(4).putInt(-10).array()
        val corruptBytes1 = frameId + negSizeBytes + flags + ByteArray(20)
        val chapters1 = parser.parseChapters(corruptBytes1)
        assertEquals(0, chapters1.size)

        // Zero size (0)
        val zeroSizeBytes = ByteBuffer.allocate(4).putInt(0).array()
        val corruptBytes2 = frameId + zeroSizeBytes + flags + ByteArray(20)
        val chapters2 = parser.parseChapters(corruptBytes2)
        assertEquals(0, chapters2.size)
    }

    @Test
    fun testDefaultChapterParserWithNullParent() = kotlinx.coroutines.test.runTest {
        val parser = DefaultChapterParser()
        // File in current directory with null parentFile
        val orphanFile = java.io.File("orphan_test_${System.currentTimeMillis()}.m4b")
        orphanFile.writeBytes(ByteArray(0))
        try {
            org.junit.Assert.assertNull(orphanFile.parentFile)
            val chapters = parser.parseChapters(orphanFile, BookFormat.M4B)
            assertEquals(0, chapters.size)
        } finally {
            orphanFile.delete()
        }
    }

    @Test
    fun testMp4ChapterParser() {
        val count = 1
        val startTime100ns = 20000000L
        val title = "Mp4 Chapter 1"
        val titleBytes = title.toByteArray(Charsets.UTF_8)
        
        val chplData = byteArrayOf(0, 0, 0, 0, count.toByte()) +
                ByteBuffer.allocate(8).putLong(startTime100ns).array() +
                byteArrayOf(titleBytes.size.toByte()) + titleBytes

        val atomHeader = ByteBuffer.allocate(4).putInt(8 + chplData.size).array() + "chpl".toByteArray()
        val syntheticBytes = atomHeader + chplData

        val parser = Mp4ChapterParser()
        val chapters = parser.parseChapters(syntheticBytes)

        assertEquals(1, chapters.size)
        assertEquals("Mp4 Chapter 1", chapters[0].title)
        assertEquals(2000L, chapters[0].startMs)
    }

    @Test
    fun testNeroChplVersion0() {
        val count = 2
        val title1 = "Introduction"
        val title1Bytes = title1.toByteArray(Charsets.UTF_8)
        val title2 = "The Theory"
        val title2Bytes = title2.toByteArray(Charsets.UTF_8)

        // Version 0: version (0), flags (0,0,0), reserved (0,0,0,0), count at offset 8 (32-bit int)
        val chplPayload = ByteBuffer.allocate(8 + 4 + (8 + 1 + title1Bytes.size) + (8 + 1 + title2Bytes.size)).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0) // version 0
            put(0); put(0); put(0) // flags
            putInt(0) // 4 bytes reserved (offset 4..7)
            putInt(count) // 32-bit count at offset 8
            // Entry 1
            putLong(0L) // 0 ms
            put(title1Bytes.size.toByte())
            put(title1Bytes)
            // Entry 2
            putLong(50000000L) // 5000 ms (50,000,000 * 100ns)
            put(title2Bytes.size.toByte())
            put(title2Bytes)
        }.array()

        val atomHeader = ByteBuffer.allocate(8).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + chplPayload.size)
            put("chpl".toByteArray(Charsets.ISO_8859_1))
        }.array()

        val parser = Mp4ChapterParser()
        val chapters = parser.parseChapters(atomHeader + chplPayload)

        assertEquals(2, chapters.size)
        assertEquals("Introduction", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(5000L, chapters[0].endMs)
        assertEquals("The Theory", chapters[1].title)
        assertEquals(5000L, chapters[1].startMs)
    }

    @Test
    fun testNeroChplVersion1() {
        val count = 2
        val title1 = "Prologue"
        val title1Bytes = title1.toByteArray(Charsets.UTF_8)
        val title2 = "Chapter One"
        val title2Bytes = title2.toByteArray(Charsets.UTF_8)

        // Version 1: version (1), flags (0,0,0), reserved 8 bytes (0), count at offset 12 (32-bit int)
        val chplPayload = ByteBuffer.allocate(4 + 8 + 4 + (8 + 1 + title1Bytes.size) + (8 + 1 + title2Bytes.size)).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(1) // version 1
            put(0); put(0); put(0) // flags
            putLong(0L) // 8 bytes reserved (offset 4..11)
            putInt(count) // 32-bit count at offset 12
            // Entry 1
            putLong(0L)
            put(title1Bytes.size.toByte())
            put(title1Bytes)
            // Entry 2
            putLong(120000000L) // 12000 ms
            put(title2Bytes.size.toByte())
            put(title2Bytes)
        }.array()

        val atomHeader = ByteBuffer.allocate(8).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + chplPayload.size)
            put("chpl".toByteArray(Charsets.ISO_8859_1))
        }.array()

        val parser = Mp4ChapterParser()
        val chapters = parser.parseChapters(atomHeader + chplPayload)

        assertEquals(2, chapters.size)
        assertEquals("Prologue", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(12000L, chapters[0].endMs)
        assertEquals("Chapter One", chapters[1].title)
        assertEquals(12000L, chapters[1].startMs)
    }

    @Test
    fun testQuickTimeTimedTextTrack() {
        // Samples placed at start of buffer:
        // Sample 1 at offset 0: 2-byte len + "Opening Credits"
        val sample1Str = "Opening Credits"
        val sample1Bytes = sample1Str.toByteArray(Charsets.UTF_8)
        val sample1Data = ByteBuffer.allocate(2 + sample1Bytes.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(sample1Bytes.size.toShort())
            put(sample1Bytes)
        }.array()

        // Sample 2 immediately follows
        val sample2Str = "Session 1"
        val sample2Bytes = sample2Str.toByteArray(Charsets.UTF_8)
        val sample2Data = ByteBuffer.allocate(2 + sample2Bytes.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putShort(sample2Bytes.size.toShort())
            put(sample2Bytes)
        }.array()

        val sampleSection = sample1Data + sample2Data
        val sample1Offset = 0L
        val sample2Offset = sample1Data.size.toLong()

        // Build mdhd atom (timescale = 22050)
        val mdhdPayload = ByteBuffer.allocate(4 + 8 + 4 + 4).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0); put(0); put(0); put(0) // ver 0 + flags
            putInt(0); putInt(0) // creation / mod
            putInt(22050) // timescale
            putInt(66150) // duration
        }.array()
        val mdhdAtom = ByteBuffer.allocate(8 + mdhdPayload.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + mdhdPayload.size)
            put("mdhd".toByteArray())
            put(mdhdPayload)
        }.array()

        // Build hdlr atom (type 'text')
        val hdlrPayload = ByteBuffer.allocate(4 + 4 + 4 + 12).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0); put(0); put(0); put(0) // ver + flags
            put("mhlr".toByteArray())
            put("text".toByteArray()) // handler type
            put(ByteArray(12))
        }.array()
        val hdlrAtom = ByteBuffer.allocate(8 + hdlrPayload.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + hdlrPayload.size)
            put("hdlr".toByteArray())
            put(hdlrPayload)
        }.array()

        // Build stts atom (2 entries: 22050 units = 1000ms, 44100 units = 2000ms)
        val sttsPayload = ByteBuffer.allocate(4 + 4 + 16).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0); put(0); put(0); put(0)
            putInt(2) // entry count
            putInt(1); putInt(22050) // 1 sample with 22050 delta
            putInt(1); putInt(44100) // 1 sample with 44100 delta
        }.array()
        val sttsAtom = ByteBuffer.allocate(8 + sttsPayload.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + sttsPayload.size)
            put("stts".toByteArray())
            put(sttsPayload)
        }.array()

        // Build stco atom pointing to sample offsets
        val stcoPayload = ByteBuffer.allocate(4 + 4 + 8).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(0); put(0); put(0); put(0)
            putInt(2) // entry count
            putInt(sample1Offset.toInt())
            putInt(sample2Offset.toInt())
        }.array()
        val stcoAtom = ByteBuffer.allocate(8 + stcoPayload.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + stcoPayload.size)
            put("stco".toByteArray())
            put(stcoPayload)
        }.array()

        // stbl atom containing stts and stco
        val stblInner = sttsAtom + stcoAtom
        val stblAtom = ByteBuffer.allocate(8 + stblInner.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + stblInner.size)
            put("stbl".toByteArray())
            put(stblInner)
        }.array()

        // trak atom containing mdhd, hdlr, stbl
        val trakInner = mdhdAtom + hdlrAtom + stblAtom
        val trakAtom = ByteBuffer.allocate(8 + trakInner.size).apply {
            order(ByteOrder.BIG_ENDIAN)
            putInt(8 + trakInner.size)
            put("trak".toByteArray())
            put(trakInner)
        }.array()

        // Combined synthetic file: samples at head, followed by trak
        val syntheticBytes = sampleSection + trakAtom
        val parser = Mp4ChapterParser()
        val chapters = parser.parseChapters(syntheticBytes)

        assertEquals(2, chapters.size)
        assertEquals("Opening Credits", chapters[0].title)
        assertEquals(0L, chapters[0].startMs)
        assertEquals(1000L, chapters[0].endMs)

        assertEquals("Session 1", chapters[1].title)
        assertEquals(1000L, chapters[1].startMs)
        assertEquals(3000L, chapters[1].endMs)
    }

    @Test
    fun testEpubChapterParserFromConvertedEpub() = kotlinx.coroutines.test.runTest {
        val md = "# Dawn\n\nFirst paragraph.\n\n# Dusk\n\nSecond paragraph."
        val epubBytes = EbookFormatConverter.convertToEpub(
            md.byteInputStream(),
            BookFormat.MD,
            title = "Day Cycle",
            author = "Ada Lovelace"
        )

        val chapters = EpubChapterParser().parseChapters(ByteArrayInputStream(epubBytes))
        assertEquals(2, chapters.size)
        assertEquals("chap_0", chapters[0].id)
        assertEquals("Dawn", chapters[0].title)
        assertEquals("epubcfi(/6/2!/4/2/1:0)", chapters[0].cfiRange)

        assertEquals("chap_1", chapters[1].id)
        assertEquals("Dusk", chapters[1].title)
        assertEquals("epubcfi(/6/4!/4/2/1:0)", chapters[1].cfiRange)

        val viaDefault = DefaultChapterParser().parseChapters(ByteArrayInputStream(epubBytes), BookFormat.EPUB)
        assertEquals(2, viaDefault.size)
        assertEquals("Dawn", viaDefault[0].title)
    }

    @Test
    fun testEpubChapterParserWithNcxAndEpub3Nav() {
        val bao = ByteArrayOutputStream()
        ZipOutputStream(bao).use { zos ->
            fun add(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }
            add(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent()
            )
            add(
                "OPS/book.opf",
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Nav Book</dc:title>
                    <dc:creator>Nav Author</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="p1" href="text/part1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="p2" href="text/part2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="p1"/>
                    <itemref idref="p2"/>
                  </spine>
                </package>""".trimIndent()
            )
            add(
                "OPS/nav.xhtml",
                """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <body><nav epub:type="toc"><ol>
                    <li><a href="text/part1.xhtml#top">Nav Part One</a></li>
                  </ol></nav></body>
                </html>"""
            )
            add(
                "OPS/toc.ncx",
                """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="np2" playOrder="2">
                      <navLabel><text>NCX Part Two</text></navLabel>
                      <content src="text/part2.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>"""
            )
            add("OPS/text/part1.xhtml", "<html><body><p>Body 1</p></body></html>")
            add("OPS/text/part2.xhtml", "<html><body><p>Body 2</p></body></html>")
        }

        val chapters = EpubChapterParser().parseChapters(ByteArrayInputStream(bao.toByteArray()))
        assertEquals(2, chapters.size)
        assertEquals("Nav Part One", chapters[0].title)
        assertEquals("NCX Part Two", chapters[1].title)
    }

    @Test
    fun testFb2ChapterParser() = kotlinx.coroutines.test.runTest {
        val fb2Xml = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description>
                <title-info>
                  <book-title>FB2 Novel</book-title>
                  <author><first-name>Leo</first-name><last-name>Tolstoy</last-name></author>
                </title-info>
              </description>
              <body>
                <section>
                  <title><p>Book One</p></title>
                  <p>Some text.</p>
                </section>
                <section>
                  <title><p>Book Two</p></title>
                  <p>More text.</p>
                </section>
              </body>
            </FictionBook>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val chapters = Fb2ChapterParser().parseChapters(ByteArrayInputStream(fb2Xml))
        assertEquals(2, chapters.size)
        assertEquals("Book One", chapters[0].title)
        assertEquals("epubcfi(/6/2!/4/2/1:0)", chapters[0].cfiRange)
        assertEquals("Book Two", chapters[1].title)
        assertEquals("epubcfi(/6/4!/4/2/1:0)", chapters[1].cfiRange)

        val viaDefault = DefaultChapterParser().parseChapters(ByteArrayInputStream(fb2Xml), BookFormat.FB2)
        assertEquals(2, viaDefault.size)
    }

    @Test
    fun testPdfChapterParserSmallAndLargePageCounts() = kotlinx.coroutines.test.runTest {
        val smallPdf = """
            %PDF-1.4
            1 0 obj << /Type /Pages /Count 3 /Kids [2 0 R 3 0 R 4 0 R] >> endobj
            2 0 obj << /Type /Page /Parent 1 0 R >> endobj
            3 0 obj << /Type /Page /Parent 1 0 R >> endobj
            4 0 obj << /Type /Page /Parent 1 0 R >> endobj
        """.trimIndent().toByteArray(StandardCharsets.ISO_8859_1)

        val smallChapters = PdfChapterParser().parseChapters(ByteArrayInputStream(smallPdf))
        assertEquals(3, smallChapters.size)
        assertEquals("Page 1", smallChapters[0].title)
        assertEquals("pdf:page:0", smallChapters[0].cfiRange)
        assertEquals("Page 3", smallChapters[2].title)
        assertEquals("pdf:page:2", smallChapters[2].cfiRange)

        val largePdfBuilder = StringBuilder("%PDF-1.4\n1 0 obj << /Type /Pages /Count 25 >> endobj\n")
        for (i in 1..25) {
            largePdfBuilder.append("${i + 1} 0 obj << /Type /Page /Parent 1 0 R >> endobj\n")
        }
        val largeChapters = DefaultChapterParser().parseChapters(
            ByteArrayInputStream(largePdfBuilder.toString().toByteArray(StandardCharsets.ISO_8859_1)),
            BookFormat.PDF
        )
        assertEquals(3, largeChapters.size)
        assertEquals("Pages 1\u201310", largeChapters[0].title)
        assertEquals("pdf:page:0", largeChapters[0].cfiRange)
        assertEquals("Pages 11\u201320", largeChapters[1].title)
        assertEquals("pdf:page:10", largeChapters[1].cfiRange)
        assertEquals("Pages 21\u201325", largeChapters[2].title)
        assertEquals("pdf:page:20", largeChapters[2].cfiRange)
    }

    @Test
    fun testMetadataExtractorEpubZipOpfFb2HtmlAndPdf() = kotlinx.coroutines.test.runTest {
        val extractor = DefaultMetadataExtractor()

        // 1. EPUB ZIP
        val epubBytes = EbookFormatConverter.convertToEpub(
            "Hello world".byteInputStream(),
            BookFormat.TXT,
            title = "Real EPUB Title",
            author = "Jane Austen"
        )
        val epubMeta = extractor.extractMetadata(ByteArrayInputStream(epubBytes), BookFormat.EPUB, "fallback.epub")
        assertEquals("Real EPUB Title", epubMeta.title)
        assertEquals("Jane Austen", epubMeta.author)

        // 2. Direct OPF XML (non-ZIP unit test compatibility)
        val opfXml = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>OPF Direct Title</dc:title>
                <dc:creator>OPF Creator</dc:creator>
              </metadata>
            </package>""".trimIndent().toByteArray(StandardCharsets.UTF_8)
        val opfMeta = extractor.extractMetadata(ByteArrayInputStream(opfXml), BookFormat.EPUB, "fallback.epub")
        assertEquals("OPF Direct Title", opfMeta.title)
        assertEquals("OPF Creator", opfMeta.author)

        // 3. FB2
        val fb2Xml = """<?xml version="1.0" encoding="utf-8"?>
            <FictionBook>
              <description><title-info>
                <book-title>War and Peace</book-title>
                <author><first-name>Leo</first-name><last-name>Tolstoy</last-name></author>
              </title-info></description>
              <body/>
            </FictionBook>""".trimIndent().toByteArray(StandardCharsets.UTF_8)
        val fb2Meta = extractor.extractMetadata(ByteArrayInputStream(fb2Xml), BookFormat.FB2, "wp.fb2")
        assertEquals("War and Peace", fb2Meta.title)
        assertEquals("Leo Tolstoy", fb2Meta.author)

        val fb2NickXml = """<FictionBook><description><title-info>
            <book-title>Nick Book</book-title>
            <author><nickname>AnonNick</nickname></author>
        </title-info></description></FictionBook>""".toByteArray(StandardCharsets.UTF_8)
        val fb2NickMeta = extractor.extractMetadata(ByteArrayInputStream(fb2NickXml), BookFormat.FB2, "nick.fb2")
        assertEquals("AnonNick", fb2NickMeta.author)

        // 4. HTML
        val htmlBytes = """<!DOCTYPE html>
            <html><head>
              <title>HTML Essay</title>
              <meta name="author" content="Mary Shelley">
            </head><body><p>Text</p></body></html>""".trimIndent().toByteArray(StandardCharsets.UTF_8)
        val htmlMeta = extractor.extractMetadata(ByteArrayInputStream(htmlBytes), BookFormat.HTML, "essay.html")
        assertEquals("HTML Essay", htmlMeta.title)
        assertEquals("Mary Shelley", htmlMeta.author)

        // 5. PDF literal + noisy filtering + UTF-16BE hex + XMP
        val pdfLiteral = "%PDF-1.4\n1 0 obj << /Title (Clean PDF Title) /Author (Ada Lovelace) >> endobj"
            .toByteArray(StandardCharsets.ISO_8859_1)
        val pdfLiteralMeta = extractor.extractMetadata(ByteArrayInputStream(pdfLiteral), BookFormat.PDF, "doc.pdf")
        assertEquals("Clean PDF Title", pdfLiteralMeta.title)
        assertEquals("Ada Lovelace", pdfLiteralMeta.author)

        val pdfNoisyAndXmp = """%PDF-1.4
            1 0 obj << /Title (Untitled) /Author (Microsoft Word) >> endobj
            <x:xmpmeta><rdf:RDF><rdf:Description>
              <dc:title><rdf:Alt><rdf:li xml:lang="x-default">XMP Book</rdf:li></rdf:Alt></dc:title>
              <dc:creator><rdf:Seq><rdf:li>XMP Author</rdf:li></rdf:Seq></dc:creator>
            </rdf:Description></rdf:RDF></x:xmpmeta>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val pdfXmpMeta = extractor.extractMetadata(ByteArrayInputStream(pdfNoisyAndXmp), BookFormat.PDF, "noisy.pdf")
        assertEquals("XMP Book", pdfXmpMeta.title)
        assertEquals("XMP Author", pdfXmpMeta.author)

        // UTF-16BE hex for "Hi" -> FEFF 0048 0069
        val pdfHex = "%PDF-1.4\n1 0 obj << /Title <FEFF00480069> >> endobj"
            .toByteArray(StandardCharsets.ISO_8859_1)
        val pdfHexMeta = extractor.extractMetadata(ByteArrayInputStream(pdfHex), BookFormat.PDF, "hex.pdf")
        assertEquals("Hi", pdfHexMeta.title)
        assertEquals("Unknown Author", pdfHexMeta.author)
    }
}
