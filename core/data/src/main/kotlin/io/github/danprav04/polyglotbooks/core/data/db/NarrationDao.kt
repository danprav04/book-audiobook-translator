package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NarrationDao {
    @Query("SELECT * FROM narration_lines WHERE chapterId = :chapterId ORDER BY paraIndex ASC, startOffset ASC")
    fun getLinesForChapter(chapterId: String): Flow<List<NarrationLineEntity>>

    @Query("SELECT * FROM narration_lines WHERE chapterId = :chapterId ORDER BY paraIndex ASC, startOffset ASC")
    suspend fun getLinesForChapterOnce(chapterId: String): List<NarrationLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLines(lines: List<NarrationLineEntity>)

    @Query("DELETE FROM narration_lines WHERE chapterId = :chapterId")
    suspend fun deleteLinesForChapter(chapterId: String)

    @Query(
        """
        UPDATE narration_lines
        SET speakerId = :newSpeakerId
        WHERE speakerId = :oldSpeakerId
          AND chapterId IN (SELECT id FROM chapters WHERE bookId = :bookId)
        """
    )
    suspend fun reassignSpeakerForBook(bookId: String, oldSpeakerId: String, newSpeakerId: String)

    @Query("SELECT * FROM tts_segments WHERE lineId = :lineId")
    suspend fun getTtsSegment(lineId: String): TtsSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTtsSegment(segment: TtsSegmentEntity)

    @Delete
    suspend fun deleteTtsSegment(segment: TtsSegmentEntity)
}
