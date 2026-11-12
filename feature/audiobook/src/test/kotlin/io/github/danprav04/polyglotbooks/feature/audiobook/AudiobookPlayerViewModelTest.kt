package io.github.danprav04.polyglotbooks.feature.audiobook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Default has 1 chapter: index 0
        assertEquals(0, viewModel.uiState.value.currentChapterIndex)

        // Previous on chapter 0 stays at 0
        viewModel.previousChapter()
        assertEquals(0, viewModel.uiState.value.currentChapterIndex)

        // Next on single chapter stays at 0
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
    fun testNonExistentBookLoadingAndRetry() = kotlinx.coroutines.test.runTest {
        val fakeBookDao = object : io.github.danprav04.polyglotbooks.core.data.db.BookDao {
            val books = mutableMapOf<String, io.github.danprav04.polyglotbooks.core.data.db.BookEntity>()
            override fun getAllBooks() = kotlinx.coroutines.flow.flowOf(books.values.toList())
            override suspend fun getBookById(id: String) = books[id]
            override fun getBooksByType(isAudiobook: Boolean) = kotlinx.coroutines.flow.flowOf(emptyList<io.github.danprav04.polyglotbooks.core.data.db.BookEntity>())
            override suspend fun insertBook(book: io.github.danprav04.polyglotbooks.core.data.db.BookEntity) { books[book.id] = book }
            override suspend fun insertBooks(books: List<io.github.danprav04.polyglotbooks.core.data.db.BookEntity>) {}
            override suspend fun updateBook(book: io.github.danprav04.polyglotbooks.core.data.db.BookEntity) { books[book.id] = book }
            override suspend fun deleteBook(book: io.github.danprav04.polyglotbooks.core.data.db.BookEntity) {}
            override suspend fun deleteBookById(id: String) {}
            override suspend fun insertChapters(chapters: List<io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity>) {}
        }

        val viewModel = AudiobookPlayerViewModel(fakeBookDao, null, null, null)

        // Try to load non-existent book
        viewModel.loadBook("missing_book_id")
        assertTrue(viewModel.uiState.value.errorMessage?.contains("missing_book_id") == true)

        // Now add the book and retry loading it - it must succeed rather than being permanently locked out
        val book = io.github.danprav04.polyglotbooks.core.data.db.BookEntity(
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
        org.junit.Assert.assertNull(viewModel.uiState.value.errorMessage)
    }
}
