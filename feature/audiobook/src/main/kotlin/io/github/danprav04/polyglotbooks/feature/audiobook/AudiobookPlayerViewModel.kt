package io.github.danprav04.polyglotbooks.feature.audiobook

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookmarkDao
import io.github.danprav04.polyglotbooks.core.data.db.BookmarkEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.playback.AudiobookServiceState
import io.github.danprav04.polyglotbooks.core.playback.AudiobookSessionManager
import io.github.danprav04.polyglotbooks.core.playback.NoOpLiveTranslationBackend
import io.github.danprav04.polyglotbooks.core.playback.PolyglotPlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Chapter(
    val index: Int,
    val title: String,
    val durationMs: Long,
    val startMs: Long = 0L
)

data class Bookmark(
    val id: String,
    val positionMs: Long,
    val title: String,
    val timestamp: Long
)

data class AudiobookPlayerUiState(
    val bookId: String = "",
    val bookTitle: String = "",
    val author: String = "",
    val bookFormat: String = "",
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferAheadMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val sourceLanguage: LanguageCode = LanguageCode.ENGLISH,
    val targetLanguage: LanguageCode = LanguageCode.RUSSIAN,
    val selectedLanguage: LanguageCode = LanguageCode.RUSSIAN,
    val isDuckingEnabled: Boolean = false,
    val duckLevel: Float = AudiobookSessionManager.DEFAULT_DUCK_LEVEL,
    val sourceSubtitle: String = "",
    val translatedSubtitle: String = "",
    val chapters: List<Chapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val bookmarks: List<Bookmark> = emptyList(),
    val sleepTimerOptionMinutes: Int = 0, // 0 = Off, 15, 30, 45, 60, -1 = End of chapter
    val sleepTimerMinutesRemaining: Int = 0,
    val sleepTimerRemainingMs: Long = 0L,
    val isSleepTimerRunning: Boolean = false,
    val isLiveTranslationActive: Boolean = true,
    val showChapterListSheet: Boolean = false,
    val showBookmarksDialog: Boolean = false,
    val showSleepTimerDialog: Boolean = false,
    val showAiReportDialog: Boolean = false,
    val showAddBookmarkDialog: Boolean = false,
    val showLanguageDropdown: Boolean = false,
    val coverBitmap: android.graphics.Bitmap? = null,
    val coverUri: String? = null,
    val newBookmarkNote: String = "",
    val errorMessage: String? = null,
    val isLoading: Boolean = false
)

@HiltViewModel
class AudiobookPlayerViewModel @Inject constructor(
    private val bookDao: BookDao?,
    private val chapterDao: ChapterDao?,
    private val settingsRepository: SettingsRepository?,
    @ApplicationContext private val context: Context?,
    sessionManager: AudiobookSessionManager? = null,
    private val bookmarkDao: BookmarkDao? = null
) : ViewModel() {
    constructor() : this(null, null, null, null, null, null)

    internal val activeSessionManager: AudiobookSessionManager = sessionManager ?: AudiobookSessionManager(
        bookDao = bookDao,
        chapterDao = chapterDao,
        playbackStateDao = null,
        settingsRepository = settingsRepository,
        liveTranslationBackend = NoOpLiveTranslationBackend(),
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        context = context
    )

    private val _uiState = MutableStateFlow(AudiobookPlayerUiState())
    val uiState: StateFlow<AudiobookPlayerUiState> = _uiState.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var bookmarksJob: Job? = null
    private var observedBookmarksBookId: String? = null

    init {
        applyServiceState(activeSessionManager.state.value)

        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            activeSessionManager.state.collect { serviceState ->
                applyServiceState(serviceState)
            }
        }

        settingsRepository?.let { repo ->
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    repo.duckLevel.collect { level ->
                        _uiState.update { it.copy(duckLevel = level.coerceIn(0f, 1f)) }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                }
            }
        }

        if (context != null) {
            connectToPlaybackService()
        }
    }

    @OptIn(UnstableApi::class)
    private fun connectToPlaybackService() {
        val ctx = context ?: return
        if (controllerFuture != null) return
        val appContext = ctx.applicationContext ?: ctx

        viewModelScope.launch {
            try {
                val sessionToken = SessionToken(
                    appContext,
                    ComponentName(appContext, PolyglotPlaybackService::class.java)
                )
                val future = MediaController.Builder(appContext, sessionToken).buildAsync()
                controllerFuture = future
                mediaController = awaitListenableFuture(appContext, future)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Service may be unavailable in pure JVM unit tests
            }
        }
    }

    private suspend fun <T> awaitListenableFuture(
        context: Context,
        future: ListenableFuture<T>
    ): T = suspendCancellableCoroutine { continuation ->
        future.addListener(
            {
                if (continuation.isActive) {
                    try {
                        continuation.resume(future.get())
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t.cause ?: t)
                    }
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    private fun applyServiceState(serviceState: AudiobookServiceState) {
        val mappedChapters = serviceState.chapters.map { ch ->
            Chapter(
                index = ch.index,
                title = ch.title,
                durationMs = (ch.endMs - ch.startMs).coerceAtLeast(1000L),
                startMs = ch.startMs
            )
        }
        _uiState.update { current ->
            val sleepMinutes = serviceState.sleepTimerMinutesRemaining
            current.copy(
                bookId = serviceState.bookId,
                bookTitle = serviceState.bookTitle,
                author = serviceState.author,
                bookFormat = serviceState.bookFormat,
                coverUri = serviceState.coverUri,
                chapters = mappedChapters,
                currentChapterIndex = serviceState.currentChapterIndex,
                currentPositionMs = serviceState.currentPositionMs,
                durationMs = serviceState.durationMs,
                isPlaying = serviceState.isPlaying,
                playbackSpeed = serviceState.playbackSpeed,
                isLiveTranslationActive = serviceState.isLiveTranslationActive,
                isDuckingEnabled = serviceState.isDuckingEnabled,
                targetLanguage = serviceState.targetLanguage,
                selectedLanguage = serviceState.targetLanguage,
                sourceSubtitle = serviceState.sourceSubtitle,
                translatedSubtitle = serviceState.translatedSubtitle,
                bufferAheadMs = serviceState.bufferAheadMs,
                sleepTimerMinutesRemaining = sleepMinutes,
                sleepTimerRemainingMs = serviceState.sleepTimerRemainingMs,
                isSleepTimerRunning = sleepMinutes > 0,
                sleepTimerOptionMinutes = if (sleepMinutes > 0) {
                    if (current.sleepTimerOptionMinutes != 0) current.sleepTimerOptionMinutes else sleepMinutes
                } else {
                    0
                },
                errorMessage = serviceState.errorMessage,
                isLoading = serviceState.isLoading
            )
        }
        if (serviceState.bookId.isNotBlank() && serviceState.bookId != observedBookmarksBookId) {
            observeBookmarks(serviceState.bookId)
        }
    }

    fun loadBook(bookId: String) {
        if (bookId.isBlank()) return
        if (context != null) {
            connectToPlaybackService()
        }
        observeBookmarks(bookId)
        activeSessionManager.loadBook(bookId)
        applyServiceState(activeSessionManager.state.value)
    }

    private fun observeBookmarks(bookId: String) {
        if (bookId.isBlank() || observedBookmarksBookId == bookId) return
        val dao = bookmarkDao ?: return
        observedBookmarksBookId = bookId
        bookmarksJob?.cancel()
        bookmarksJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                dao.observeBookmarksForBook(bookId).collect { entities ->
                    val mapped = entities.map { entity ->
                        Bookmark(
                            id = entity.id,
                            positionMs = entity.positionMs,
                            title = entity.note?.takeIf { it.isNotBlank() }
                                ?: "Bookmark at ${formatTime(entity.positionMs)}",
                            timestamp = entity.createdAt
                        )
                    }
                    _uiState.update { it.copy(bookmarks = mapped) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
            }
        }
    }

    fun togglePlayPause() {
        activeSessionManager.togglePlayPause()
        applyServiceState(activeSessionManager.state.value)
    }

    fun seekBy(deltaMs: Long) {
        activeSessionManager.seekBy(deltaMs)
        applyServiceState(activeSessionManager.state.value)
    }

    fun seekTo(positionMs: Long) {
        activeSessionManager.seekTo(positionMs)
        applyServiceState(activeSessionManager.state.value)
    }

    fun previousChapter() {
        activeSessionManager.previousChapter()
        applyServiceState(activeSessionManager.state.value)
    }

    fun nextChapter() {
        activeSessionManager.nextChapter()
        applyServiceState(activeSessionManager.state.value)
    }

    fun setLanguage(language: LanguageCode) {
        setTargetLanguage(language)
    }

    fun setTargetLanguage(language: LanguageCode) {
        _uiState.update { it.copy(showLanguageDropdown = false) }
        activeSessionManager.setTargetLanguage(language)
        applyServiceState(activeSessionManager.state.value)
    }

    fun setLanguageDropdownVisible(visible: Boolean) {
        _uiState.update { it.copy(showLanguageDropdown = visible) }
    }

    fun toggleDucking() {
        activeSessionManager.toggleDucking()
        applyServiceState(activeSessionManager.state.value)
    }

    fun setPlaybackSpeed(speed: Float) {
        activeSessionManager.setPlaybackSpeed(speed)
        applyServiceState(activeSessionManager.state.value)
    }

    fun toggleLiveTranslation() {
        activeSessionManager.toggleLiveTranslation()
        applyServiceState(activeSessionManager.state.value)
    }

    fun setChapterListSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(showChapterListSheet = visible) }
    }

    fun setSleepTimerDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showSleepTimerDialog = visible) }
    }

    fun setBookmarksDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showBookmarksDialog = visible) }
    }

    fun setAiReportDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showAiReportDialog = visible) }
    }

    fun setAddBookmarkDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showAddBookmarkDialog = visible, newBookmarkNote = "") }
    }

    fun updateNewBookmarkNote(note: String) {
        _uiState.update { it.copy(newBookmarkNote = note) }
    }

    fun addBookmark(title: String) {
        val current = _uiState.value
        val posMs = activeSessionManager.getEffectivePositionMs()
        val now = System.currentTimeMillis()
        val resolvedTitle = title.ifBlank { "Bookmark at ${formatTime(posMs)}" }
        val newId = UUID.randomUUID().toString()
        val newBm = Bookmark(
            id = newId,
            positionMs = posMs,
            title = resolvedTitle,
            timestamp = now
        )

        _uiState.update {
            it.copy(
                bookmarks = listOf(newBm) + it.bookmarks,
                showAddBookmarkDialog = false,
                newBookmarkNote = ""
            )
        }

        val dao = bookmarkDao
        val bookId = current.bookId
        if (dao != null && bookId.isNotBlank()) {
            val entity = BookmarkEntity(
                id = newId,
                bookId = bookId,
                positionMs = posMs,
                cfi = null,
                createdAt = now,
                note = resolvedTitle
            )
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    dao.insertBookmark(entity)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun deleteBookmark(id: String) {
        val bookId = _uiState.value.bookId
        _uiState.update { current ->
            current.copy(bookmarks = current.bookmarks.filter { it.id != id })
        }
        val dao = bookmarkDao
        if (dao != null && bookId.isNotBlank()) {
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    dao.deleteBookmarkById(id)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun jumpToBookmark(positionMs: Long) {
        seekTo(positionMs)
        _uiState.update { it.copy(showBookmarksDialog = false) }
    }

    fun selectChapter(index: Int) {
        _uiState.update { it.copy(showChapterListSheet = false) }
        activeSessionManager.selectChapter(index)
        applyServiceState(activeSessionManager.state.value)
    }

    fun setSleepTimer(minutes: Int) {
        _uiState.update {
            it.copy(
                sleepTimerOptionMinutes = minutes,
                showSleepTimerDialog = false
            )
        }
        activeSessionManager.setSleepTimer(minutes)
        applyServiceState(activeSessionManager.state.value)
    }

    fun clearError() {
        activeSessionManager.clearError()
        applyServiceState(activeSessionManager.state.value)
    }

    fun flushProgress() {
        activeSessionManager.flushProgressNow()
    }

    override fun onCleared() {
        // Only release the MediaController future so background playback in PolyglotPlaybackService continues.
        controllerFuture?.let { future ->
            try {
                MediaController.releaseFuture(future)
            } catch (_: Throwable) {
            }
        }
        controllerFuture = null
        mediaController = null
        super.onCleared()
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%02d:%02d", minutes, seconds)
    }
}
