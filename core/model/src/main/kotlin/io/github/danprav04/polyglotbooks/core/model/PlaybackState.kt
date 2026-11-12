package io.github.danprav04.polyglotbooks.core.model

import kotlinx.serialization.Serializable

/**
 * Persisted playback and reading position state for a book (audiobook or ebook).
 */
@Serializable
data class PlaybackState(
    val bookId: String,
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
)
