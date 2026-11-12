package io.github.danprav04.polyglotbooks.core.data.cache

import io.github.danprav04.polyglotbooks.core.data.db.TranslationCacheDao

/**
 * Helper interface for querying covered time spans and cache gaps for audiobook translations.
 */
interface TranslatedCacheIndex {
    /**
     * Returns the continuous timestamp (in milliseconds) up to which audio is cached starting from [fromMs].
     */
    suspend fun coveredUntil(bookId: String, targetLang: String, fromMs: Long): Long

    /**
     * Returns the start timestamp of the first gap in cache after [afterMs], or null if covered through end.
     */
    suspend fun firstGapAfter(bookId: String, targetLang: String, afterMs: Long): Long?
}

/**
 * Concrete implementation of [TranslatedCacheIndex] backed by [TranslationCacheDao].
 */
class DefaultTranslatedCacheIndex(
    private val translationCacheDao: TranslationCacheDao
) : TranslatedCacheIndex {

    override suspend fun coveredUntil(bookId: String, targetLang: String, fromMs: Long): Long {
        val segments = translationCacheDao.getSegmentsListForBook(bookId, targetLang)
        var currentCovered = fromMs

        for (segment in segments) {
            if (segment.sourceStartMs <= currentCovered && segment.sourceEndMs > currentCovered) {
                currentCovered = segment.sourceEndMs
            } else if (segment.sourceStartMs > currentCovered) {
                break
            }
        }
        return currentCovered
    }

    override suspend fun firstGapAfter(bookId: String, targetLang: String, afterMs: Long): Long? {
        val segments = translationCacheDao.getSegmentsListForBook(bookId, targetLang)
        var pointer = afterMs

        for (segment in segments) {
            if (segment.sourceEndMs <= pointer) continue

            if (segment.sourceStartMs > pointer) {
                return pointer
            }
            pointer = segment.sourceEndMs
        }
        return pointer
    }
}
