package io.github.danprav04.polyglotbooks.feature.audiobook

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Chapter(
    val index: Int,
    val title: String,
    val durationMs: Long,
    val startMs: Long = 0L
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

@HiltViewModel
class AudiobookPlayerViewModel @Inject constructor(
    private val bookDao: BookDao?,
    private val chapterDao: ChapterDao?,
    @ApplicationContext private val context: Context?
) : ViewModel() {
    constructor() : this(null, null, null)
    private val _uiState = MutableStateFlow(AudiobookPlayerUiState())
    val uiState: StateFlow<AudiobookPlayerUiState> = _uiState.asStateFlow()

    private var exoPlayer: ExoPlayer? = null
    private var positionJob: Job? = null
    private var loadedBookId: String? = null

    fun loadBook(bookId: String) {
        if (bookDao == null || bookId.isBlank() || loadedBookId == bookId) return
        loadedBookId = bookId
        viewModelScope.launch {
            val book = bookDao.getBookById(bookId) ?: return@launch
            val dbChapters = chapterDao?.getChaptersForBook(bookId)?.firstOrNull() ?: emptyList()
            val mappedChapters = if (dbChapters.isNotEmpty()) {
                dbChapters.map { ch ->
                    Chapter(
                        index = ch.index,
                        title = ch.title,
                        durationMs = (ch.endMs - ch.startMs).coerceAtLeast(1000L),
                        startMs = ch.startMs
                    )
                }
            } else {
                listOf(Chapter(0, book.title, 3600000L, 0L))
            }
            _uiState.value = _uiState.value.copy(
                bookTitle = book.title,
                author = book.author,
                chapters = mappedChapters,
                durationMs = mappedChapters.firstOrNull()?.durationMs ?: 3600000L,
                currentPositionMs = 0L
            )
            setupPlayer(book.filePath)
        }
    }

    private fun setupPlayer(filePath: String) {
        val appContext = context ?: return
        if (filePath.isBlank()) return
        try {
            exoPlayer?.release()
            val player = ExoPlayer.Builder(appContext).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        val realDuration = player.duration
                        if (realDuration != C.TIME_UNSET && realDuration > 0L) {
                            _uiState.value = _uiState.value.copy(durationMs = realDuration)
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        _uiState.value = _uiState.value.copy(isPlaying = false)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.parse(filePath)))
            player.prepare()
            exoPlayer = player
            startPositionPolling()
        } catch (_: Exception) {
            // Keep UI functional even if URI cannot be opened
        }
    }

    private fun startPositionPolling() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (isActive) {
                val player = exoPlayer
                if (player != null) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration.let { if (it != C.TIME_UNSET && it > 0L) it else _uiState.value.durationMs }
                    if (pos != _uiState.value.currentPositionMs || dur != _uiState.value.durationMs) {
                        _uiState.value = _uiState.value.copy(
                            currentPositionMs = pos.coerceAtMost(dur),
                            durationMs = dur
                        )
                    }
                }
                delay(500L)
            }
        }
    }

    fun togglePlayPause() {
        val player = exoPlayer
        if (player != null) {
            if (player.isPlaying) player.pause() else player.play()
        } else {
            _uiState.value = _uiState.value.copy(isPlaying = !_uiState.value.isPlaying)
        }
    }

    fun seekBy(deltaMs: Long) {
        val newPos = (_uiState.value.currentPositionMs + deltaMs).coerceIn(0L, _uiState.value.durationMs)
        exoPlayer?.seekTo(newPos)
        _uiState.value = _uiState.value.copy(currentPositionMs = newPos)
    }

    fun seekTo(positionMs: Long) {
        val newPos = positionMs.coerceIn(0L, _uiState.value.durationMs)
        exoPlayer?.seekTo(newPos)
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
        exoPlayer?.playbackParameters = PlaybackParameters(clamped)
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
        val player = exoPlayer
        if (player != null) {
            player.seekTo(chapter.startMs)
            _uiState.value = current.copy(
                currentChapterIndex = index,
                currentPositionMs = chapter.startMs,
                showChapterListSheet = false
            )
        } else {
            _uiState.value = current.copy(
                currentChapterIndex = index,
                durationMs = chapter.durationMs,
                currentPositionMs = 0L,
                showChapterListSheet = false
            )
        }
    }

    override fun onCleared() {
        positionJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
        super.onCleared()
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
