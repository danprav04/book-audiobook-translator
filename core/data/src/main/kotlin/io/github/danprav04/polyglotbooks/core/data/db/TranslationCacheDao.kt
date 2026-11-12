package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationCacheDao {
    @Query("SELECT * FROM translated_segments WHERE bookId = :bookId AND targetLang = :targetLang ORDER BY sourceStartMs ASC")
    fun getSegmentsForBook(bookId: String, targetLang: String): Flow<List<TranslatedSegmentEntity>>

    @Query("SELECT * FROM translated_segments WHERE bookId = :bookId AND targetLang = :targetLang ORDER BY sourceStartMs ASC")
    suspend fun getSegmentsListForBook(bookId: String, targetLang: String): List<TranslatedSegmentEntity>

    @Query("SELECT * FROM translated_segments WHERE bookId = :bookId AND targetLang = :targetLang AND sourceStartMs <= :timeMs AND sourceEndMs > :timeMs LIMIT 1")
    suspend fun getSegmentAt(bookId: String, targetLang: String, timeMs: Long): TranslatedSegmentEntity?

    @Query("SELECT SUM(fileSizeBytes) FROM translated_segments")
    suspend fun getTotalCacheSizeBytes(): Long?

    @Query("SELECT * FROM translated_segments ORDER BY createdAt ASC")
    suspend fun getOldestSegments(): List<TranslatedSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: TranslatedSegmentEntity)

    @Delete
    suspend fun deleteSegment(segment: TranslatedSegmentEntity)

    @Query("DELETE FROM translated_segments WHERE bookId = :bookId")
    suspend fun deleteSegmentsForBook(bookId: String)
}
