package io.github.danprav04.polyglotbooks.core.data.cache

import io.github.danprav04.polyglotbooks.core.data.db.TranslationCacheDao
import java.io.File

/**
 * LRU cache evictor that enforces maximum storage limits for translated segment files.
 */
class LruCacheEvictor(
    private val translationCacheDao: TranslationCacheDao
) {
    /**
     * Checks total cached size against [cacheLimitBytes] and deletes oldest segment files and records
     * until total size is within the limit.
     *
     * @return Number of evicted segments.
     */
    suspend fun evictIfNeeded(cacheLimitBytes: Long): Int {
        var totalBytes = translationCacheDao.getTotalCacheSizeBytes() ?: 0L
        if (totalBytes <= cacheLimitBytes) return 0

        val oldestSegments = translationCacheDao.getOldestSegments()
        var evictedCount = 0

        for (segment in oldestSegments) {
            if (totalBytes <= cacheLimitBytes) break

            // Attempt to remove file on disk
            try {
                val file = File(segment.aacFilePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (_: Exception) {
            }

            // Remove database entry
            translationCacheDao.deleteSegment(segment)
            totalBytes -= segment.fileSizeBytes
            evictedCount++
        }

        return evictedCount
    }
}
