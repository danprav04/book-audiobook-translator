package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackStateDao {
    @Query("SELECT * FROM playback_state WHERE bookId = :bookId")
    suspend fun getPlaybackState(bookId: String): PlaybackStateEntity?

    @Query("SELECT * FROM playback_state WHERE bookId = :bookId")
    fun observePlaybackState(bookId: String): Flow<PlaybackStateEntity?>

    @Query("SELECT * FROM playback_state")
    fun observeAllPlaybackStates(): Flow<List<PlaybackStateEntity>>

    @Upsert
    suspend fun upsertPlaybackState(state: PlaybackStateEntity)

    @Query(
        "UPDATE playback_state SET chapterIndex = :chapterIndex, positionMs = :positionMs, " +
            "durationMs = :durationMs, updatedAt = :updatedAt WHERE bookId = :bookId"
    )
    suspend fun updateAudioProgress(
        bookId: String,
        chapterIndex: Int,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        "UPDATE playback_state SET lastCfi = :lastCfi, readingProgress = :readingProgress, " +
            "updatedAt = :updatedAt WHERE bookId = :bookId"
    )
    suspend fun updateReaderProgress(
        bookId: String,
        lastCfi: String?,
        readingProgress: Float,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Transaction
    suspend fun updateReaderProgress(
        bookId: String,
        lastCfi: String?,
        chapterIndex: Int,
        readingProgress: Float,
        updatedAt: Long = System.currentTimeMillis()
    ): Int {
        val existing = getPlaybackState(bookId) ?: return 0
        upsertPlaybackState(
            existing.copy(
                chapterIndex = chapterIndex,
                lastCfi = lastCfi,
                readingProgress = readingProgress,
                updatedAt = updatedAt
            )
        )
        return 1
    }

    @Query(
        "UPDATE playback_state SET playbackSpeed = :playbackSpeed, " +
            "isLiveTranslationActive = :isLiveTranslationActive, " +
            "isDuckingEnabled = :isDuckingEnabled, " +
            "targetLanguageCode = :targetLanguageCode, " +
            "updatedAt = :updatedAt WHERE bookId = :bookId"
    )
    suspend fun updatePlaybackSettings(
        bookId: String,
        playbackSpeed: Float,
        isLiveTranslationActive: Boolean,
        isDuckingEnabled: Boolean,
        targetLanguageCode: String?,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query(
        "UPDATE playback_state SET lastPauseTimestamp = :timestamp, updatedAt = :updatedAt " +
            "WHERE bookId = :bookId"
    )
    suspend fun updateLastPauseTimestamp(
        bookId: String,
        timestamp: Long,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("DELETE FROM playback_state WHERE bookId = :bookId")
    suspend fun deletePlaybackState(bookId: String)

    @Transaction
    suspend fun saveAudioProgress(
        bookId: String,
        chapterIndex: Int,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        if (updateAudioProgress(bookId, chapterIndex, positionMs, durationMs, updatedAt) == 0) {
            upsertPlaybackState(
                PlaybackStateEntity(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = updatedAt
                )
            )
        }
    }

    @Transaction
    suspend fun saveReaderProgress(
        bookId: String,
        lastCfi: String?,
        readingProgress: Float,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        if (updateReaderProgress(bookId, lastCfi, readingProgress, updatedAt) == 0) {
            upsertPlaybackState(
                PlaybackStateEntity(
                    bookId = bookId,
                    lastCfi = lastCfi,
                    readingProgress = readingProgress,
                    updatedAt = updatedAt
                )
            )
        }
    }

    @Transaction
    suspend fun saveReaderProgress(
        bookId: String,
        lastCfi: String?,
        chapterIndex: Int,
        readingProgress: Float,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        if (updateReaderProgress(bookId, lastCfi, chapterIndex, readingProgress, updatedAt) == 0) {
            upsertPlaybackState(
                PlaybackStateEntity(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    lastCfi = lastCfi,
                    readingProgress = readingProgress,
                    updatedAt = updatedAt
                )
            )
        }
    }

    @Transaction
    suspend fun savePlaybackSettings(
        bookId: String,
        playbackSpeed: Float,
        isLiveTranslationActive: Boolean,
        isDuckingEnabled: Boolean,
        targetLanguageCode: String?,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        if (updatePlaybackSettings(
                bookId = bookId,
                playbackSpeed = playbackSpeed,
                isLiveTranslationActive = isLiveTranslationActive,
                isDuckingEnabled = isDuckingEnabled,
                targetLanguageCode = targetLanguageCode,
                updatedAt = updatedAt
            ) == 0
        ) {
            upsertPlaybackState(
                PlaybackStateEntity(
                    bookId = bookId,
                    playbackSpeed = playbackSpeed,
                    isLiveTranslationActive = isLiveTranslationActive,
                    isDuckingEnabled = isDuckingEnabled,
                    targetLanguageCode = targetLanguageCode,
                    updatedAt = updatedAt
                )
            )
        }
    }

    @Transaction
    suspend fun saveLastPauseTimestamp(
        bookId: String,
        timestamp: Long,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        if (updateLastPauseTimestamp(bookId, timestamp, updatedAt) == 0) {
            upsertPlaybackState(
                PlaybackStateEntity(
                    bookId = bookId,
                    lastPauseTimestamp = timestamp,
                    updatedAt = updatedAt
                )
            )
        }
    }
}
