package io.github.danprav04.polyglotbooks.feature.library

import io.github.danprav04.polyglotbooks.core.model.Chapter
import java.io.File

/**
 * Interface for parsing chapters out of ebook and audiobook containers.
 */
interface ChapterParser {
    suspend fun parseChapters(file: File, format: BookFormat): List<Chapter>
}

class DefaultChapterParser : ChapterParser {
    override suspend fun parseChapters(file: File, format: BookFormat): List<Chapter> {
        return emptyList()
    }
}
