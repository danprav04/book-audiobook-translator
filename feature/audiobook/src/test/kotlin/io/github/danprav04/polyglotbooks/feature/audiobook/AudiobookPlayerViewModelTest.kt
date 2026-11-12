package io.github.danprav04.polyglotbooks.feature.audiobook

import androidx.media3.exoplayer.ExoPlayer
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.BookmarkDao
import io.github.danprav04.polyglotbooks.core.data.db.BookmarkEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.playback.AudiobookSessionManager
import io.github.danprav04.polyglotbooks.core.playback.LiveTranslationBackend
import io.github.danprav04.polyglotbooks.core.playback.LiveTranslationBackendState
import io.github.danprav04.polyglotbooks.core.playback.NoOpLiveTranslationBackend
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudiobookPlayerViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeBookDao : BookDao {
        val books = mutableMapOf<String, BookEntity>()
        override fun getAllBooks() = flowOf(books.values.toList())
        override suspend fun getBookById(id: String) = books[id]
        override fun getBooksByType(isAudiobook: Boolean) = flowOf(emptyList<BookEntity>())
        override suspend fun insertBook(book: BookEntity) {
            books[book.id] = book
        }
        override suspend fun insertBooks(books: List<BookEntity>) {}
        override suspend fun updateBook(book: BookEntity) {
            books[book.id] = book
        }
        override suspend fun deleteBook(book: BookEntity) {}
        override suspend fun deleteBookById(id: String) {}
        override suspend fun insertChapters(chapters: List<ChapterEntity>) {}
    }

    private class FakeBookmarkDao : BookmarkDao {
        private val state = MutableStateFlow<List<BookmarkEntity>>(emptyList())

        override fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>> =
            state.map { list ->
                list.filter { it.bookId == bookId }.sortedByDescending { it.createdAt }
            }

        override suspend fun getBookmarkById(id: String): BookmarkEntity? =
            state.value.firstOrNull { it.id == id }

        override suspend fun insertBookmark(bookmark: BookmarkEntity) {
            state.value = state.value.filterNot { it.id == bookmark.id } + bookmark
        }

        override suspend fun deleteBookmark(bookmark: BookmarkEntity) {
            state.value = state.value.filterNot { it.id == bookmark.id }
        }

        override suspend fun deleteBookmarkById(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }

        fun allEntities(): List<BookmarkEntity> = state.value
    }

    private class ControllableBackend : LiveTranslationBackend {
        private val _state = MutableStateFlow(LiveTranslationBackendState())
        override val state: StateFlow<LiveTranslationBackendState> = _state.asStateFlow()

        var stopCalls = 0
        var pauseCalls = 0

        override fun start(
            scope: CoroutineScope,
            bookId: String,
            filePath: String,
            targetLanguage: LanguageCode,
            apiKey: String?,
            modelId: String,
            echoTargetLanguage: Boolean,
            startPositionMs: Long,
            playbackSpeed: Float
        ) {
            if (apiKey.isNullOrBlank()) {
                _state.value = _state.value.copy(
                    isRunning = false,
                    errorMessage = NoOpLiveTranslationBackend.MISSING_API_KEY_MESSAGE
                )
            } else {
                _state.value = LiveTranslationBackendState(
                    isRunning = true,
                    sourcePositionMs = startPositionMs,
                    bufferAheadMs = 18_500L,
                    speedMultiplier = playbackSpeed,
                    currentSourceSubtitle = "Original sentence",
                    currentTranslatedSubtitle = "Переведенное предложение",
                    errorMessage = null
                )
            }
        }

        override fun updatePlaybackPosition(positionMs: Long) {
            _state.value = _state.value.copy(sourcePositionMs = positionMs)
        }

        override fun setPlaybackSpeed(speed: Float) {
            _state.value = _state.value.copy(speedMultiplier = speed)
        }

        override fun pauseAudio() {
            pauseCalls++
        }

        override fun resumeAudio() {}

        override fun stop() {
            stopCalls++
            _state.value = _state.value.copy(isRunning = false)
        }

        fun emitError(message: String) {
            _state.value = _state.value.copy(errorMessage = message)
        }
    }

    @Test
    fun testPlaybackSpeedClampingAndToggle() {
        val viewModel = AudiobookPlayerViewModel()
        assertEquals(1.0f, viewModel.uiState.value.playbackSpeed)

        viewModel.setPlaybackSpeed(2.5f)
        assertEquals(2.5f, viewModel.uiState.value.playbackSpeed)

        viewModel.setPlaybackSpeed(5.0f) // Should clamp to 3.0f
        assertEquals(3.0f, viewModel.uiState.value.playbackSpeed)
    }

    @Test
    fun testBookmarkCreationAndDeletion() {
        val viewModel = AudiobookPlayerViewModel()
        assertTrue(viewModel.uiState.value.bookmarks.isEmpty())

        viewModel.addBookmark("Test Bookmark")
        assertEquals(1, viewModel.uiState.value.bookmarks.size)
        assertEquals("Test Bookmark", viewModel.uiState.value.bookmarks[0].title)

        val bmId = viewModel.uiState.value.bookmarks[0].id
        viewModel.deleteBookmark(bmId)
        assertTrue(viewModel.uiState.value.bookmarks.isEmpty())
    }

    @Test
    fun testChapterNavigation() {
        val viewModel = AudiobookPlayerViewModel()
        // Default has chapter index 0
        assertEquals(0, viewModel.uiState.value.currentChapterIndex)

        // Previous on chapter 0 stays at 0
        viewModel.previousChapter()
        assertEquals(0, viewModel.uiState.value.currentChapterIndex)

        // Next on single/empty chapter list stays at 0
        viewModel.nextChapter()
        assertEquals(0, viewModel.uiState.value.currentChapterIndex)
    }

    @Test
    fun testDuckingToggle() {
        val viewModel = AudiobookPlayerViewModel()
        assertFalse(viewModel.uiState.value.isDuckingEnabled)

        viewModel.toggleDucking()
        assertTrue(viewModel.uiState.value.isDuckingEnabled)

        viewModel.toggleDucking()
        assertFalse(viewModel.uiState.value.isDuckingEnabled)
    }

    @Test
    fun testLiveTranslationToggle() {
        val viewModel = AudiobookPlayerViewModel()
        assertTrue(viewModel.uiState.value.isLiveTranslationActive)

        viewModel.toggleLiveTranslation()
        assertFalse(viewModel.uiState.value.isLiveTranslationActive)
    }

    @Test
    fun testNonExistentBookLoadingAndRetry() = runTest {
        val fakeBookDao = FakeBookDao()
        val viewModel = AudiobookPlayerViewModel(fakeBookDao, null, null, null)

        // Try to load non-existent book
        viewModel.loadBook("missing_book_id")
        assertTrue(viewModel.uiState.value.errorMessage?.contains("missing_book_id") == true)

        // Now add the book and retry loading it - it must succeed rather than being permanently locked out
        val book = BookEntity(
            id = "missing_book_id",
            title = "Found Book",
            author = "Author",
            format = "M4B",
            filePath = "",
            coverUri = null,
            addedDate = 0L,
            lastReadDate = 0L,
            isAudiobook = true
        )
        fakeBookDao.insertBook(book)

        viewModel.loadBook("missing_book_id")
        assertEquals("Found Book", viewModel.uiState.value.bookTitle)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testBookmarkPersistenceWithBookmarkDao() = runTest {
        val fakeBookDao = FakeBookDao()
        val fakeBookmarkDao = FakeBookmarkDao()
        val bookId = "book-bookmarks"

        fakeBookDao.insertBook(
            BookEntity(
                id = bookId,
                title = "Persisted Book",
                author = "Author",
                format = "M4B",
                filePath = "/tmp/book.m4b",
                coverUri = null,
                addedDate = 100L,
                lastReadDate = 100L,
                isAudiobook = true
            )
        )
        // Pre-seed an existing bookmark in Room
        fakeBookmarkDao.insertBookmark(
            BookmarkEntity(
                id = "existing-bm",
                bookId = bookId,
                positionMs = 12_000L,
                cfi = null,
                createdAt = 500L,
                note = "Existing Note"
            )
        )

        val viewModel = AudiobookPlayerViewModel(
            bookDao = fakeBookDao,
            chapterDao = null,
            settingsRepository = null,
            context = null,
            sessionManager = null,
            bookmarkDao = fakeBookmarkDao
        )

        viewModel.loadBook(bookId)
        assertEquals(1, viewModel.uiState.value.bookmarks.size)
        assertEquals("Existing Note", viewModel.uiState.value.bookmarks[0].title)
        assertEquals(12_000L, viewModel.uiState.value.bookmarks[0].positionMs)

        // Seek to 45s and add a new bookmark
        viewModel.seekTo(45_000L)
        viewModel.addBookmark("Chapter climax")

        assertEquals(2, fakeBookmarkDao.allEntities().size)
        assertEquals(2, viewModel.uiState.value.bookmarks.size)
        val createdBm = viewModel.uiState.value.bookmarks.first { it.title == "Chapter climax" }
        assertEquals(45_000L, createdBm.positionMs)

        // Delete the newly created bookmark and verify it is removed from Room and UI state
        viewModel.deleteBookmark(createdBm.id)
        assertEquals(1, fakeBookmarkDao.allEntities().size)
        assertEquals(1, viewModel.uiState.value.bookmarks.size)
        assertEquals("existing-bm", viewModel.uiState.value.bookmarks[0].id)
    }

    @Test
    fun testErrorPropagationAndClearError() = runTest {
        val fakeBookDao = FakeBookDao()
        val backend = ControllableBackend()
        val bookId = "book-err"
        fakeBookDao.insertBook(
            BookEntity(
                id = bookId,
                title = "Error Test Book",
                author = "Author",
                format = "M4B",
                filePath = "/tmp/book.m4b",
                coverUri = null,
                addedDate = 100L,
                lastReadDate = 100L,
                isAudiobook = true
            )
        )

        val sessionManager = AudiobookSessionManager(
            bookDao = fakeBookDao,
            liveTranslationBackend = backend,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        val viewModel = AudiobookPlayerViewModel(
            bookDao = fakeBookDao,
            chapterDao = null,
            settingsRepository = null,
            context = null,
            sessionManager = sessionManager
        )

        viewModel.loadBook(bookId)
        assertNull(viewModel.uiState.value.errorMessage)

        // Starting playback without an API key propagates the missing key error to uiState
        viewModel.togglePlayPause()
        assertEquals(
            NoOpLiveTranslationBackend.MISSING_API_KEY_MESSAGE,
            viewModel.uiState.value.errorMessage
        )

        // Clearing the error resets both UI state and sessionManager state
        viewModel.clearError()
        assertNull(viewModel.uiState.value.errorMessage)
        assertNull(sessionManager.state.value.errorMessage)

        // Backend live error also propagates to UI state
        backend.emitError("WebSocket 1011: quota exceeded")
        assertEquals("WebSocket 1011: quota exceeded", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testOnClearedLeavesSessionManagerPlaybackActive() = runTest {
        val fakeBookDao = FakeBookDao()
        val bookId = "book-bg"
        val fakeChapterDao = mockk<ChapterDao>(relaxed = true)
        every { fakeChapterDao.getChaptersForBook(bookId) } returns flowOf(
            listOf(
                ChapterEntity("${bookId}_0", bookId, 0, "Part 1", 0L, 60_000L, null, 0),
                ChapterEntity("${bookId}_1", bookId, 1, "Part 2", 60_000L, 120_000L, null, 0)
            )
        )
        val backend = ControllableBackend()
        val exoPlayer = mockk<ExoPlayer>(relaxed = true)
        every { exoPlayer.currentPosition } returns 30_000L
        every { exoPlayer.duration } returns 120_000L

        fakeBookDao.insertBook(
            BookEntity(
                id = bookId,
                title = "Background Book",
                author = "Author",
                format = "M4B",
                filePath = "/tmp/bg.m4b",
                coverUri = null,
                addedDate = 100L,
                lastReadDate = 100L,
                isAudiobook = true
            )
        )

        val sessionManager = AudiobookSessionManager(
            bookDao = fakeBookDao,
            chapterDao = fakeChapterDao,
            liveTranslationBackend = backend,
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        )
        sessionManager.attachPlayer(exoPlayer)

        val viewModel = AudiobookPlayerViewModel(
            bookDao = fakeBookDao,
            chapterDao = fakeChapterDao,
            settingsRepository = null,
            context = null,
            sessionManager = sessionManager
        )
        viewModel.loadBook(bookId)
        viewModel.seekTo(30_000L)
        viewModel.togglePlayPause()

        assertTrue(viewModel.uiState.value.isPlaying)
        assertTrue(sessionManager.state.value.isPlaying)

        // Simulate navigating away from AudiobookPlayerScreen -> ViewModel.onCleared()
        val onClearedMethod = AudiobookPlayerViewModel::class.java.getDeclaredMethod("onCleared").apply {
            isAccessible = true
        }
        onClearedMethod.invoke(viewModel)

        // Background playback in AudiobookSessionManager & ExoPlayer MUST remain active!
        assertTrue(sessionManager.state.value.isPlaying)
        assertEquals(0, backend.stopCalls)
        assertEquals(0, backend.pauseCalls)
        verify(exactly = 0) { exoPlayer.pause() }
        verify(exactly = 0) { exoPlayer.stop() }
        verify(exactly = 0) { exoPlayer.release() }

        // Returning to the screen creates a new ViewModel that seamlessly observes the active session
        val recreatedViewModel = AudiobookPlayerViewModel(
            bookDao = fakeBookDao,
            chapterDao = fakeChapterDao,
            settingsRepository = null,
            context = null,
            sessionManager = sessionManager
        )
        recreatedViewModel.loadBook(bookId)

        assertTrue(recreatedViewModel.uiState.value.isPlaying)
        assertEquals("Background Book", recreatedViewModel.uiState.value.bookTitle)
        assertEquals(30_000L, recreatedViewModel.uiState.value.currentPositionMs)
    }
}
