package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.danprav04.polyglotbooks.core.model.PlaybackState

/**
 * Room entity storing persisted playback and reading progress per book.
 *
 * Intentionally avoids a foreign key with ON DELETE CASCADE to [BookEntity] so re-importing
 * a book via `OnConflictStrategy.REPLACE` (which performs DELETE + INSERT) never wipes progress.
 */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int = 0,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val isLiveTranslationActive: Boolean = true,
    val isDuckingEnabled: Boolean = false,
    val targetLanguageCode: String? = null,
    val lastCfi: String? = null,
    val readingProgress: Float = 0f,
    val lastPauseTimestamp: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun toDomain(): PlaybackState = PlaybackState(
        bookId = bookId,
        chapterIndex = chapterIndex,
        positionMs = positionMs,
        durationMs = durationMs,
        playbackSpeed = playbackSpeed,
        isLiveTranslationActive = isLiveTranslationActive,
        isDuckingEnabled = isDuckingEnabled,
        targetLanguageCode = targetLanguageCode,
        lastCfi = lastCfi,
        readingProgress = readingProgress,
        lastPauseTimestamp = lastPauseTimestamp,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(state: PlaybackState): PlaybackStateEntity = PlaybackStateEntity(
            bookId = state.bookId,
            chapterIndex = state.chapterIndex,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            playbackSpeed = state.playbackSpeed,
            isLiveTranslationActive = state.isLiveTranslationActive,
            isDuckingEnabled = state.isDuckingEnabled,
            targetLanguageCode = state.targetLanguageCode,
            lastCfi = state.lastCfi,
            readingProgress = state.readingProgress,
            lastPauseTimestamp = state.lastPauseTimestamp,
            updatedAt = state.updatedAt
        )
    }
}
