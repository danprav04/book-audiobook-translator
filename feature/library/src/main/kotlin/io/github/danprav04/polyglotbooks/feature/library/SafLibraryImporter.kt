package io.github.danprav04.polyglotbooks.feature.library

import android.content.Context
import android.net.Uri
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

class SafLibraryImporter(
    private val context: Context,
    private val bookDao: BookDao,
    private val chapterParser: ChapterParser = DefaultChapterParser(),
    private val metadataExtractor: MetadataExtractor = DefaultMetadataExtractor()
) {
    suspend fun importUris(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        val importedBookIds = mutableListOf<String>()
        for (uri in uris) {
            try {
                val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (e: Exception) {
                // Ignore
            }

            val fileName = queryFileName(context, uri) ?: uri.lastPathSegment ?: "unknown"
            // Read at most 64KB for format detection to prevent OutOfMemoryError on large audiobooks/ebooks
            val headerBuffer = ByteArray(64 * 1024)
            val headerLen = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.read(headerBuffer)
            } ?: 0
            val headerBytes = if (headerLen > 0) headerBuffer.copyOf(headerLen) else byteArrayOf()
            val format = FormatDetector.detect(headerBytes, fileName)

            val effectiveFormat = if (format in listOf(BookFormat.TXT, BookFormat.MD, BookFormat.HTML, BookFormat.DOCX, BookFormat.ODT, BookFormat.RTF)) {
                BookFormat.EPUB
            } else {
                format
            }

            var audioDurationMs = 3600000L
            val bookModel = if (format.isAudiobook) {
                var title = fileName.substringBeforeLast('.')
                var author = "Unknown Author"
                try {
                    val mmr = android.media.MediaMetadataRetriever()
                    mmr.setDataSource(context, uri)
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() }?.let { title = it }
                    (mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                        ?: mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?: mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST))
                        ?.takeIf { it.isNotBlank() }?.let { author = it }
                    mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()?.takeIf { it > 0L }?.let { audioDurationMs = it }
                    mmr.release()
                } catch (_: Exception) {
                    // Fallback to filename metadata
                }
                io.github.danprav04.polyglotbooks.core.model.Book(
                    id = uri.toString().hashCode().toString(),
                    title = title,
                    author = author,
                    format = format.name,
                    filePath = uri.toString(),
                    isAudiobook = true
                )
            } else if (format in listOf(BookFormat.TXT, BookFormat.MD, BookFormat.HTML, BookFormat.DOCX, BookFormat.ODT, BookFormat.RTF)) {
                val fullBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
                val epubBytes = EbookFormatConverter.convertToEpub(
                    fullBytes.inputStream(),
                    format,
                    title = fileName.substringBeforeLast('.'),
                    author = "Unknown Author"
                )
                epubBytes.inputStream().use { stream ->
                    metadataExtractor.extractMetadata(stream, effectiveFormat, fileName)
                }?.copy(filePath = uri.toString())
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    metadataExtractor.extractMetadata(stream, effectiveFormat, fileName)
                }?.copy(filePath = uri.toString())
            }

            if (bookModel != null) {
                val parsedChapters = try {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        chapterParser.parseChapters(stream, effectiveFormat)
                    } ?: emptyList()
                } catch (e: Throwable) {
                    emptyList()
                }

                val chapters = if (parsedChapters.isEmpty() && format.isAudiobook) {
                    listOf(
                        io.github.danprav04.polyglotbooks.core.model.Chapter(
                            id = "chap_0",
                            bookId = bookModel.id,
                            index = 0,
                            title = bookModel.title,
                            startMs = 0L,
                            endMs = audioDurationMs
                        )
                    )
                } else {
                    parsedChapters
                }

                val bookEntity = BookEntity(
                    id = bookModel.id,
                    title = bookModel.title,
                    author = bookModel.author,
                    format = bookModel.format,
                    filePath = bookModel.filePath,
                    coverUri = null,
                    addedDate = System.currentTimeMillis(),
                    lastReadDate = System.currentTimeMillis(),
                    isAudiobook = bookModel.isAudiobook
                )

                val chapterEntities = chapters.map { ch ->
                    ChapterEntity(
                        id = "${bookModel.id}_${ch.id}",
                        bookId = bookModel.id,
                        index = ch.index,
                        title = ch.title,
                        startMs = ch.startMs,
                        endMs = ch.endMs,
                        cfiRange = ch.cfiRange,
                        textLength = ch.textLength
                    )
                }

                bookDao.insertBookWithChapters(bookEntity, chapterEntities)
                importedBookIds.add(bookModel.id)
            }
        }
        importedBookIds
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
    }
}
