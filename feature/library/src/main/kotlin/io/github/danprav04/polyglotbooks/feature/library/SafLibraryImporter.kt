package io.github.danprav04.polyglotbooks.feature.library

import android.content.Context
import android.net.Uri
import io.github.danprav04.polyglotbooks.core.data.CoverExtractor
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.model.Chapter
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class SafLibraryImporter(
    private val context: Context,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao? = null,
    private val chapterParser: ChapterParser = DefaultChapterParser(),
    private val metadataExtractor: MetadataExtractor = DefaultMetadataExtractor()
) {
    suspend fun importUris(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        val importedBookIds = mutableListOf<String>()

        // 1. Separate companion files (.cue, cover images) from actual book candidates
        val cueUrisByBaseName = mutableMapOf<String, Uri>()
        val imageUrisByBaseName = mutableMapOf<String, Uri>()
        val candidateUris = mutableListOf<Triple<Uri, String, BookFormat>>()
        val urisNeedingLocalCopy = mutableSetOf<Uri>()

        for (uri in uris) {
            if (uri.scheme == "content") {
                try {
                    val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (_: Exception) {
                    // SecurityException / UnsupportedOperationException: fallback to internal copy unless covered by a persisted tree URI.
                    if (!hasPersistedReadPermission(context, uri)) {
                        urisNeedingLocalCopy.add(uri)
                    }
                }
            }

            val fileName = queryFileName(context, uri) ?: uri.lastPathSegment ?: "unknown"
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val baseName = fileName.substringBeforeLast('.')

            val headerBuffer = ByteArray(64 * 1024)
            val headerLen = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.read(headerBuffer)
                } ?: 0
            } catch (e: Exception) {
                DiagnosticLogBuffer.record(
                    tag = "SafLibraryImporter",
                    message = "Failed to open header stream (ext=${ext.ifBlank { "none" }})",
                    level = "WARN",
                    throwable = e
                )
                0
            }
            val headerBytes = if (headerLen > 0) headerBuffer.copyOf(headerLen) else byteArrayOf()
            val format = FormatDetector.detect(headerBytes, fileName)

            if (ext == "cue" || format == BookFormat.CUE) {
                cueUrisByBaseName[baseName] = uri
            } else if (ext in listOf("jpg", "jpeg", "png", "webp")) {
                imageUrisByBaseName[baseName] = uri
                imageUrisByBaseName["cover"] = uri
            } else if (format != BookFormat.UNKNOWN) {
                candidateUris.add(Triple(uri, fileName, format))
            }
        }

        // 2. Process each candidate book safely
        for ((uri, fileName, format) in candidateUris) {
            try {
                val baseName = fileName.substringBeforeLast('.')
                val bookId = uri.toString().hashCode().toString()
                val isConvertedFormat = format in listOf(
                    BookFormat.TXT,
                    BookFormat.MD,
                    BookFormat.HTML,
                    BookFormat.DOCX,
                    BookFormat.ODT,
                    BookFormat.RTF
                )
                val effectiveFormat = if (isConvertedFormat) BookFormat.EPUB else format

                var audioDurationMs = 3600000L
                var extractedCoverPath: String? = null
                var preParsedEbookChapters: List<Chapter>? = null

                val bookModel = if (format.isAudiobook) {
                    var title = baseName
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
                    if (audioDurationMs == 3600000L) {
                        val headerDurationMs = try {
                            CoverExtractor.extractDurationMs(context, uri)
                        } catch (_: Exception) {
                            0L
                        }
                        if (headerDurationMs > 0L) {
                            audioDurationMs = headerDurationMs
                        }
                    }

                    extractedCoverPath = CoverExtractor.extractCover(context, uri, bookId)

                    // If embedded cover art wasn't found, check if a companion image was selected
                    if (extractedCoverPath == null) {
                        val companionImageUri = imageUrisByBaseName[baseName] ?: imageUrisByBaseName["cover"] ?: imageUrisByBaseName.values.firstOrNull()
                        if (companionImageUri != null) {
                            try {
                                val destFile = java.io.File(context.filesDir, "covers/${bookId}.jpg")
                                destFile.parentFile?.mkdirs()
                                context.contentResolver.openInputStream(companionImageUri)?.use { input ->
                                    destFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                if (destFile.exists() && destFile.length() > 0) {
                                    extractedCoverPath = destFile.absolutePath
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    val durableAudioPath = if (uri in urisNeedingLocalCopy) {
                        copyUriToInternalStorage(context, uri, bookId, fileName, format)
                            ?: uri.toString()
                    } else {
                        uri.toString()
                    }

                    io.github.danprav04.polyglotbooks.core.model.Book(
                        id = bookId,
                        title = title,
                        author = author,
                        format = format.name,
                        filePath = durableAudioPath,
                        coverUri = extractedCoverPath,
                        isAudiobook = true
                    )
                } else if (isConvertedFormat) {
                    val fullBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
                    val (extractedTitle, extractedAuthor) = if (format == BookFormat.HTML) {
                        val htmlMeta = metadataExtractor.extractMetadata(
                            fullBytes.inputStream(),
                            BookFormat.HTML,
                            fileName
                        )
                        htmlMeta.title to htmlMeta.author
                    } else {
                        baseName to "Unknown Author"
                    }

                    val epubBytes = EbookFormatConverter.convertToEpub(
                        fullBytes.inputStream(),
                        format,
                        title = extractedTitle,
                        author = extractedAuthor
                    )

                    val booksDir = File(context.filesDir, "books").apply { mkdirs() }
                    val epubFile = File(booksDir, "$bookId.epub")
                    epubFile.writeBytes(epubBytes)
                    val durableFilePath = Uri.fromFile(epubFile).toString()

                    preParsedEbookChapters = try {
                        epubBytes.inputStream().use { stream ->
                            chapterParser.parseChapters(stream, BookFormat.EPUB)
                        }
                    } catch (_: Throwable) {
                        emptyList()
                    }

                    epubBytes.inputStream().use { stream ->
                        metadataExtractor.extractMetadata(stream, BookFormat.EPUB, fileName)
                    }.copy(
                        id = bookId,
                        filePath = durableFilePath
                    )
                } else {
                    val booksDir = File(context.filesDir, "books").apply { mkdirs() }
                    val copiedFile = copyNativeEbookToFile(
                        context = context,
                        uri = uri,
                        destFile = File(booksDir, "$bookId.${format.name.lowercase()}")
                    )
                    val durableFilePath = copiedFile?.let { Uri.fromFile(it).toString() } ?: uri.toString()

                    preParsedEbookChapters = try {
                        if (copiedFile != null && copiedFile.exists()) {
                            chapterParser.parseChapters(copiedFile, effectiveFormat)
                        } else {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                chapterParser.parseChapters(stream, effectiveFormat)
                            } ?: emptyList()
                        }
                    } catch (_: Throwable) {
                        emptyList()
                    }

                    val extractedBook = if (copiedFile != null && copiedFile.exists()) {
                        metadataExtractor.extractMetadata(copiedFile, effectiveFormat)
                    } else {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            metadataExtractor.extractMetadata(stream, effectiveFormat, fileName)
                        }
                    }
                    extractedBook?.copy(
                        id = bookId,
                        filePath = durableFilePath
                    )
                }

                if (bookModel != null) {
                    val durableFilePath = bookModel.filePath
                    if (!format.isAudiobook) {
                        extractedCoverPath = CoverExtractor.extractEbookCover(
                            context,
                            Uri.parse(durableFilePath),
                            bookModel.id,
                            effectiveFormat.name
                        ) ?: CoverExtractor.extractEbookCover(
                            context,
                            uri,
                            bookModel.id,
                            effectiveFormat.name
                        )
                    }

                    val companionCueUri = cueUrisByBaseName[baseName] ?: cueUrisByBaseName.values.firstOrNull()
                    val parsedChapters = if (format.isAudiobook) {
                        extractChapters(context, uri, bookModel.id, audioDurationMs, companionCueUri)
                    } else {
                        preParsedEbookChapters ?: emptyList()
                    }

                    val chapters = if (parsedChapters.isEmpty()) {
                        if (format.isAudiobook) {
                            listOf(
                                Chapter(
                                    id = "chap_0",
                                    bookId = bookModel.id,
                                    index = 0,
                                    title = bookModel.title,
                                    startMs = 0L,
                                    endMs = audioDurationMs
                                )
                            )
                        } else {
                            listOf(
                                Chapter(
                                    id = "${bookModel.id}_chap_0",
                                    bookId = bookModel.id,
                                    index = 0,
                                    title = bookModel.title,
                                    startMs = 0L,
                                    endMs = 0L,
                                    cfiRange = if (effectiveFormat == BookFormat.PDF) {
                                        "pdf:page:0"
                                    } else {
                                        "epubcfi(/6/2!/4/2/1:0)"
                                    }
                                )
                            )
                        }
                    } else {
                        parsedChapters
                    }

                    val bookEntity = BookEntity(
                        id = bookModel.id,
                        title = bookModel.title,
                        author = bookModel.author,
                        format = bookModel.format,
                        filePath = durableFilePath,
                        coverUri = bookModel.coverUri ?: extractedCoverPath,
                        addedDate = System.currentTimeMillis(),
                        lastReadDate = System.currentTimeMillis(),
                        isAudiobook = bookModel.isAudiobook
                    )

                    val chapterEntities = chapters.map { ch ->
                        val chapterId = if (ch.id.startsWith("${bookModel.id}_")) {
                            ch.id
                        } else {
                            "${bookModel.id}_${ch.id}"
                        }
                        ChapterEntity(
                            id = chapterId,
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
                } else {
                    DiagnosticLogBuffer.record(
                        tag = "SafLibraryImporter",
                        message = "Metadata extraction returned null (format=${format.name})",
                        level = "WARN"
                    )
                }
            } catch (e: Exception) {
                DiagnosticLogBuffer.record(
                    tag = "SafLibraryImporter",
                    message = "Failed to import file (format=${format.name})",
                    level = "ERROR",
                    throwable = e
                )
                runCatching { android.util.Log.e("SafLibraryImporter", "Failed to import file $fileName", e) }
            }
        }
        importedBookIds
    }

    suspend fun extractChapters(
        context: Context,
        uri: Uri,
        bookId: String,
        audioDurationMs: Long = 0L,
        companionCueUri: Uri? = null
    ): List<Chapter> = withContext(Dispatchers.IO) {
        // 0. If a companion .cue URI was explicitly provided (e.g. from multi-selection or folder import)
        if (companionCueUri != null) {
            try {
                val chapters = context.contentResolver.openInputStream(companionCueUri)?.use { stream ->
                    CoverExtractor.parseCueSheet(stream, bookId)
                } ?: emptyList()
                if (chapters.isNotEmpty()) {
                    return@withContext chapters
                }
            } catch (_: Exception) {}
        }

        // 1. Check companion .cue file from disk/filesystem
        val cueChapters = CoverExtractor.findCompanionCueChapters(context, uri, bookId)
        if (cueChapters.isNotEmpty()) {
            return@withContext cueChapters
        }

        // 2. Extract embedded M4B chapters (Nero chpl v0/v1 or QuickTime text track)
        val embeddedChapters = CoverExtractor.extractChapters(context, uri, bookId)
        if (embeddedChapters.isNotEmpty()) {
            if (audioDurationMs > 0L) {
                val last = embeddedChapters.last()
                if (audioDurationMs > last.startMs && last.endMs != audioDurationMs) {
                    val updated = embeddedChapters.toMutableList()
                    updated[updated.size - 1] = last.copy(endMs = audioDurationMs)
                    return@withContext updated
                }
            }
            return@withContext embeddedChapters
        }

        // 3. Fallback to stream parsing via ChapterParser (e.g. ID3 CHAP frames)
        val streamChapters = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                chapterParser.parseChapters(stream, BookFormat.M4B)
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (streamChapters.isNotEmpty()) {
            return@withContext streamChapters
        }

        emptyList()
    }

    suspend fun extractChapters(
        uri: Uri,
        bookId: String,
        audioDurationMs: Long = 0L
    ): List<Chapter> = extractChapters(context, uri, bookId, audioDurationMs)

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

    private fun hasPersistedReadPermission(context: Context, uri: Uri): Boolean {
        return try {
            val persisted = context.contentResolver.persistedUriPermissions
            if (persisted.any { it.isReadPermission && it.uri == uri }) {
                return true
            }
            if (android.provider.DocumentsContract.isTreeUri(uri)) {
                val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                persisted.any { perm ->
                    perm.isReadPermission &&
                        perm.uri.authority == uri.authority &&
                        android.provider.DocumentsContract.isTreeUri(perm.uri) &&
                        android.provider.DocumentsContract.getTreeDocumentId(perm.uri) == treeDocId
                }
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun copyUriToInternalStorage(
        context: Context,
        uri: Uri,
        bookId: String,
        fileName: String,
        format: BookFormat
    ): String? {
        val rawExt = fileName.substringAfterLast('.', "").lowercase()
        val ext = rawExt.takeIf { it.isNotBlank() && it.length <= 10 && it.all { c -> c.isLetterOrDigit() } }
            ?: format.extension.ifBlank { "dat" }
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val destFile = File(booksDir, "$bookId.$ext")
        return copyNativeEbookToFile(context, uri, destFile)?.let { Uri.fromFile(it).toString() }
    }

    private fun copyNativeEbookToFile(
        context: Context,
        uri: Uri,
        destFile: File
    ): File? {
        destFile.parentFile?.mkdirs()
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        return try {
            val copiedBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            if (copiedBytes <= 0L || !tempFile.exists() || tempFile.length() <= 0L) {
                tempFile.delete()
                return null
            }
            if (destFile.exists()) {
                destFile.delete()
            }
            if (!tempFile.renameTo(destFile)) {
                tempFile.copyTo(destFile, overwrite = true)
                tempFile.delete()
            }
            destFile
        } catch (e: Exception) {
            tempFile.delete()
            DiagnosticLogBuffer.record(
                tag = "SafLibraryImporter",
                message = "Failed copying URI to internal storage (ext=${destFile.extension.ifBlank { "unknown" }})",
                level = "WARN",
                throwable = e
            )
            runCatching {
                android.util.Log.w(
                    "SafLibraryImporter",
                    "Failed to copy URI to internal storage: ${destFile.name}",
                    e
                )
            }
            null
        }
    }
}
