package io.github.danprav04.polyglotbooks.feature.audiobook

import androidx.lifecycle.ViewModel
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Chapter(
    val index: Int,
    val title: String,
    val durationMs: Long
)

data class Bookmark(
    val id: Long,
    val positionMs: Long,
    val title: String,
    val timestamp: Long
)

data class AudiobookPlayerUiState(
    val bookTitle: String = "The Count of Monte Cristo",
    val author: String = "Alexandre Dumas",
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 3600000L,
    val bufferAheadMs: Long = 15000L,
    val playbackSpeed: Float = 1.0f,
    val selectedLanguage: LanguageCode = LanguageCode.HEBREW,
    val isDuckingEnabled: Boolean = false,
    val duckLevel: Float = 0.15f,
    val sourceSubtitle: String = "Les misères du savoir et de l'ignorance...",
    val translatedSubtitle: String = "ייסורי הידע והבורות...",
    val chapters: List<Chapter> = listOf(
        Chapter(0, "Chapter 1: Marseilles - The Arrival", 900000L),
        Chapter(1, "Chapter 2: Father and Son", 1200000L),
        Chapter(2, "Chapter 3: The Catalans", 1500000L)
    ),
    val currentChapterIndex: Int = 0,
    val bookmarks: List<Bookmark> = emptyList(),
    val sleepTimerOptionMinutes: Int = 0, // 0 = Off, 15, 30, 45, 60, -1 = End of chapter
    val sleepTimerRemainingMs: Long = 0L,
    val isSleepTimerRunning: Boolean = false,
    val isLiveTranslationActive: Boolean = true,
    val showChapterListSheet: Boolean = false,
    val showBookmarksDialog: Boolean = false,
    val showSleepTimerDialog: Boolean = false,
    val showAiReportDialog: Boolean = false,
    val showAddBookmarkDialog: Boolean = false,
    val newBookmarkNote: String = ""
)

class AudiobookPlayerViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(AudiobookPlayerUiState())
    val uiState: StateFlow<AudiobookPlayerUiState> = _uiState.asStateFlow()

    fun togglePlayPause() {
        _uiState.value = _uiState.value.copy(isPlaying = !_uiState.value.isPlaying)
    }

    fun seekBy(deltaMs: Long) {
        val newPos = (_uiState.value.currentPositionMs + deltaMs).coerceIn(0L, _uiState.value.durationMs)
        _uiState.value = _uiState.value.copy(currentPositionMs = newPos)
    }

    fun seekTo(positionMs: Long) {
        val newPos = positionMs.coerceIn(0L, _uiState.value.durationMs)
        _uiState.value = _uiState.value.copy(currentPositionMs = newPos)
    }

    fun setLanguage(language: LanguageCode) {
        _uiState.value = _uiState.value.copy(selectedLanguage = language)
    }

    fun toggleDucking() {
        _uiState.value = _uiState.value.copy(isDuckingEnabled = !_uiState.value.isDuckingEnabled)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        _uiState.value = _uiState.value.copy(playbackSpeed = clamped)
    }

    fun toggleLiveTranslation() {
        _uiState.value = _uiState.value.copy(isLiveTranslationActive = !_uiState.value.isLiveTranslationActive)
    }

    fun setChapterListSheetVisible(visible: Bool) {
        _uiState.value = _uiState.value.copy(showChapterListSheet = visible)
    }

    fun setSleepTimerDialogVisible(visible: Bool) {
        _uiState.value = _uiState.value.copy(showSleepTimerDialog = visible)
    }

    fun setBookmarksDialogVisible(visible: Bool) {
        _uiState.value = _uiState.value.copy(showBookmarksDialog = visible)
    }

    fun setAiReportDialogVisible(visible: Bool) {
        _uiState.value = _uiState.value.copy(showAiReportDialog = visible)
    }

    fun setAddBookmarkDialogVisible(visible: Bool) {
        _uiState.value = _uiState.value.copy(showAddBookmarkDialog = visible, newBookmarkNote = "")
    }

    fun updateNewBookmarkNote(note: String) {
        _uiState.value = _uiState.value.copy(newBookmarkNote = note)
    }

    fun addBookmark(title: String) {
        val current = _uiState.value
        val newBm = Bookmark(
            id = System.currentTimeMillis(),
            positionMs = current.currentPositionMs,
            title = title.ifBlank { "Bookmark at ${formatTime(current.currentPositionMs)}" },
            timestamp = System.currentTimeMillis()
        )
        _uiState.value = current.copy(
            bookmarks = current.bookmarks + newBm,
            showAddBookmarkDialog = false,
            newBookmarkNote = ""
        )
    }

    fun deleteBookmark(id: Long) {
        val current = _uiState.value
        _uiState.value = current.copy(bookmarks = current.bookmarks.filter { it.id != id })
    }

    fun jumpToBookmark(positionMs: Long) {
        seekTo(positionMs)
        _uiState.value = _uiState.value.copy(showBookmarksDialog = false)
    }

    fun selectChapter(index: Int) {
        val current = _uiState.value
        val chapter = current.chapters.getOrNull(index) ?: return
        _uiState.value = current.copy(
            currentChapterIndex = index,
            durationMs = chapter.durationMs,
            currentPositionMs = 0L,
            showChapterListSheet = false
        )
    }

    fun setSleepTimer(minutes: Int) {
        val current = _uiState.value
        val remaining = if (minutes > 0) minutes * 60 * 1000L else 0L
        _uiState.value = current.copy(
            sleepTimerOptionMinutes = minutes,
            sleepTimerRemainingMs = remaining,
            isSleepTimerRunning = minutes != 0,
            showSleepTimerDialog = false
        )
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }
}

private typealias Bool = Boolean
