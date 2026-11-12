package io.github.danprav04.polyglotbooks.core.audio

import io.github.danprav04.polyglotbooks.core.data.db.TranslationCacheDao
import io.github.danprav04.polyglotbooks.core.data.db.TranslatedSegmentEntity
import java.io.File
import java.io.FileOutputStream

/**
 * Disk-based translated audio cache managing segment files and Room metadata.
 */
interface TranslatedCache {
    suspend fun findCachedRange(bookId: String, targetLanguage: String, sourceMs: Long): TranslatedSegmentEntity?
    suspend fun putSegment(bookId: String, targetLanguage: String, sourceStartMs: Long, sourceEndMs: Long, pcmData: ByteArray)
    suspend fun getSegmentPcm(segment: TranslatedSegmentEntity): ByteArray?
    suspend fun evictToMaxBytes(maxBytes: Long)
    suspend fun clearCache()
}

/**
 * Implementation of [TranslatedCache] using file storage and [TranslationCacheDao].
 */
class DiskTranslatedCache(
    private val cacheDir: File,
    private val cacheDao: TranslationCacheDao
) : TranslatedCache {

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    override suspend fun findCachedRange(bookId: String, targetLanguage: String, sourceMs: Long): TranslatedSegmentEntity? {
        return cacheDao.getSegmentAt(bookId, targetLanguage, sourceMs)
    }

    override suspend fun putSegment(
        bookId: String,
        targetLanguage: String,
        sourceStartMs: Long,
        sourceEndMs: Long,
        pcmData: ByteArray
    ) {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val fileName = "seg_${bookId}_${targetLanguage}_${sourceStartMs}_${sourceEndMs}.pcm"
        val segmentFile = File(cacheDir, fileName)
        FileOutputStream(segmentFile).use { it.write(pcmData) }

        val entity = TranslatedSegmentEntity(
            id = "${bookId}_${targetLanguage}_${sourceStartMs}_${sourceEndMs}",
            bookId = bookId,
            targetLang = targetLanguage,
            sourceStartMs = sourceStartMs,
            sourceEndMs = sourceEndMs,
            aacFilePath = segmentFile.absolutePath,
            fileSizeBytes = segmentFile.length(),
            createdAt = System.currentTimeMillis()
        )
        cacheDao.insertSegment(entity)
    }

    override suspend fun getSegmentPcm(segment: TranslatedSegmentEntity): ByteArray? {
        val file = File(segment.aacFilePath)
        if (!file.exists()) return null
        if (file.name.endsWith(".pcm", ignoreCase = true)) {
            return file.readBytes()
        }
        return AudioSegmentIO.readAllPcmBytes(file) ?: file.readBytes()
    }

    override suspend fun evictToMaxBytes(maxBytes: Long) {
        var totalBytes = cacheDao.getTotalCacheSizeBytes() ?: 0L
        if (totalBytes <= maxBytes) return

        val oldest = cacheDao.getOldestSegments()
        for (segment in oldest) {
            if (totalBytes <= maxBytes) break
            val file = File(segment.aacFilePath)
            if (file.exists()) {
                totalBytes -= segment.fileSizeBytes
                file.delete()
            }
            cacheDao.deleteSegment(segment)
        }
    }

    override suspend fun clearCache() {
        val oldest = cacheDao.getOldestSegments()
        for (segment in oldest) {
            val file = File(segment.aacFilePath)
            if (file.exists()) {
                file.delete()
            }
            cacheDao.deleteSegment(segment)
        }
    }
}
