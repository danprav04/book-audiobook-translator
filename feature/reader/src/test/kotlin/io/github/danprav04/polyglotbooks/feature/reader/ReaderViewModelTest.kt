package io.github.danprav04.polyglotbooks.feature.reader

import androidx.lifecycle.SavedStateHandle
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBookDao : BookDao {
    var book = BookEntity(
        id = "test_book",
        title = "Test Book",
        author = "Author",
        format = "EPUB",
        filePath = "",
        coverUri = null,
        addedDate = System.currentTimeMillis(),
        lastReadDate = System.currentTimeMillis(),
        isAudiobook = false
    )

    override fun getAllBooks(): Flow<List<BookEntity>> = flowOf(listOf(book))
    override suspend fun getBookById(id: String): BookEntity? = if (id == book.id) book else null
    override fun getBooksByType(isAudiobook: Boolean): Flow<List<BookEntity>> = flowOf(emptyList())
    override suspend fun insertBook(book: BookEntity) { this.book = book }
    override suspend fun insertBooks(books: List<BookEntity>) {}
    override suspend fun updateBook(book: BookEntity) { this.book = book }
    override suspend fun deleteBook(book: BookEntity) {}
    override suspend fun deleteBookById(id: String) {}
}

class FakeChapterDao : ChapterDao {
    val chapters = listOf(
        ChapterEntity(id = "ch_1", bookId = "test_book", index = 0, title = "Chapter 1", startMs = 0, endMs = 1000, cfiRange = "cfi_1", textLength = 100),
        ChapterEntity(id = "ch_2", bookId = "test_book", index = 1, title = "Chapter 2", startMs = 1000, endMs = 2000, cfiRange = "cfi_2", textLength = 100)
    )

    override fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>> = flowOf(chapters)
    override suspend fun getChapterById(id: String): ChapterEntity? = chapters.find { it.id == id }
    override suspend fun getChapterByIndex(bookId: String, index: Int): ChapterEntity? = chapters.find { it.index == index }
    override suspend fun insertChapter(chapter: ChapterEntity) {}
    override suspend fun insertChapters(chapters: List<ChapterEntity>) {}
    override suspend fun updateChapter(chapter: ChapterEntity) {}
    override suspend fun deleteChapter(chapter: ChapterEntity) {}
    override suspend fun deleteChaptersByBookId(bookId: String) {}
}

class ReaderViewModelTest {

    @Test
    fun testThemeAndUpdate() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book"))

        val viewModel = ReaderViewModel(bookDao, chapterDao, savedStateHandle)
        assertEquals(ReaderTheme.LIGHT, viewModel.uiState.value.theme)

        viewModel.setTheme(ReaderTheme.SEPIA)
        assertEquals(ReaderTheme.SEPIA, viewModel.uiState.value.theme)

        viewModel.setFontSize(24)
        assertEquals(24, viewModel.uiState.value.fontSizeSp)
    }

    @Test
    fun testProgressSaving() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book"))

        val viewModel = ReaderViewModel(bookDao, chapterDao, savedStateHandle)
        viewModel.saveProgress("epubcfi(/6/1!/4)", 0.5f)

        assertEquals("epubcfi(/6/1!/4)", viewModel.uiState.value.currentCfi)
        assertEquals(0.5f, viewModel.uiState.value.progress)
    }

    @Test
    fun testSearchQueryHandling() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book"))

        val viewModel = ReaderViewModel(bookDao, chapterDao, savedStateHandle)
        viewModel.setSearchOpen(true)
        assertTrue(viewModel.uiState.value.isSearchOpen)

        viewModel.setSearchQuery("Polyglot")
        assertEquals("Polyglot", viewModel.uiState.value.searchQuery)

        viewModel.onSearchResults("[{\"cfi\":\"cfi_1\",\"excerpt\":\"Polyglot Books test excerpt\"}]")
        assertEquals(1, viewModel.uiState.value.searchResults.size)
        assertEquals("cfi_1", viewModel.uiState.value.searchResults[0].cfi)
    }

    @Test
    fun testBilingualTranslationMapping() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book"))

        val viewModel = ReaderViewModel(bookDao, chapterDao, savedStateHandle)
        assertEquals(false, viewModel.uiState.value.isBilingualEnabled)

        viewModel.toggleBilingualMode()
        assertTrue(viewModel.uiState.value.isBilingualEnabled)
        assertTrue(viewModel.uiState.value.bilingualTranslations.isNotEmpty())
    }
}
