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
            val format = FormatDetector.detect(context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf(), fileName)

            val bookModel = context.contentResolver.openInputStream(uri)?.use { stream ->
                metadataExtractor.extractMetadata(stream, format, fileName)
            }?.copy(filePath = uri.toString())

            if (bookModel != null) {
                val chapters = context.contentResolver.openInputStream(uri)?.use { stream ->
                    chapterParser.parseChapters(stream, format)
                } ?: emptyList()

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
