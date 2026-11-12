package io.github.danprav04.polyglotbooks.feature.library

import io.github.danprav04.polyglotbooks.core.model.Book
import java.io.File

/**
 * Interface for extracting title, author, and cover art from books and audiobooks.
 */
interface MetadataExtractor {
    suspend fun extractMetadata(file: File, format: BookFormat): Book
}

class DefaultMetadataExtractor : MetadataExtractor {
    override suspend fun extractMetadata(file: File, format: BookFormat): Book {
        val baseName = file.nameWithoutExtension
        return Book(
            id = file.absolutePath.hashCode().toString(),
            title = baseName,
            author = "Unknown Author",
            format = format.name,
            filePath = file.absolutePath,
            isAudiobook = format.isAudiobook
        )
    }
}
