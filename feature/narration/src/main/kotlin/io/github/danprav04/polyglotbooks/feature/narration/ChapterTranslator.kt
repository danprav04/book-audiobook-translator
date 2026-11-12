package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.NarrationLine

/**
 * ChapterTranslator translates book chapter text while preserving speaker tag annotations.
 */
interface ChapterTranslator {
    suspend fun translateChapter(
        lines: List<NarrationLine>,
        targetLanguage: LanguageCode
    ): List<NarrationLine>
}

class DefaultChapterTranslator : ChapterTranslator {
    override suspend fun translateChapter(
        lines: List<NarrationLine>,
        targetLanguage: LanguageCode
    ): List<NarrationLine> {
        return lines // In Phase 1 skeleton, returns original lines
    }
}
