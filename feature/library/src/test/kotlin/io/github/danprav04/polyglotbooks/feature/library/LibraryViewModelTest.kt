package io.github.danprav04.polyglotbooks.feature.library

import android.content.Context
import android.net.Uri
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    private class FakeBookDao : BookDao {
        val booksFlow = MutableStateFlow<List<BookEntity>>(emptyList())
        var deletedIds = mutableListOf<String>()
        var throwOnDelete: Throwable? = null

        override fun getAllBooks(): Flow<List<BookEntity>> = booksFlow
        override suspend fun getBookById(id: String): BookEntity? = booksFlow.value.firstOrNull { it.id == id }
        override fun getBooksByType(isAudiobook: Boolean): Flow<List<BookEntity>> = booksFlow
        override suspend fun insertBook(book: BookEntity) {
            booksFlow.value = booksFlow.value + book
        }
        override suspend fun insertBooks(books: List<BookEntity>) {
            booksFlow.value = booksFlow.value + books
        }
        override suspend fun updateBook(book: BookEntity) {
            booksFlow.value = booksFlow.value.map { if (it.id == book.id) book else it }
        }
        override suspend fun deleteBook(book: BookEntity) {
            deleteBookById(book.id)
        }
        override suspend fun deleteBookById(id: String) {
            throwOnDelete?.let { throw it }
            deletedIds.add(id)
            booksFlow.value = booksFlow.value.filterNot { it.id == id }
        }
        override suspend fun insertChapters(chapters: List<ChapterEntity>) {}
    }

    private class FakeChapterDao : ChapterDao {
        override fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>> = MutableStateFlow(emptyList())
        override suspend fun getChapterById(id: String): ChapterEntity? = null
        override suspend fun getChapterByIndex(bookId: String, index: Int): ChapterEntity? = null
        override suspend fun insertChapter(chapter: ChapterEntity) {}
        override suspend fun insertChapters(chapters: List<ChapterEntity>) {}
        override suspend fun updateChapter(chapter: ChapterEntity) {}
        override suspend fun deleteChapter(chapter: ChapterEntity) {}
        override suspend fun deleteChaptersByBookId(bookId: String) {}
    }

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var bookDao: FakeBookDao
    private lateinit var chapterDao: FakeChapterDao
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        DiagnosticLogBuffer.clear()
        bookDao = FakeBookDao()
        chapterDao = FakeChapterDao()
        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        DiagnosticLogBuffer.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun importUrisSuccessClearsErrorAndRecordsDiagnosticLog() = runTest(testDispatcher) {
        val vm = LibraryViewModel(bookDao, chapterDao)
        vm.uriImporterOverride = { _, uris ->
            listOf("book_${uris.size}")
        }

        vm.importUris(context, listOf(mockk<Uri>(relaxed = true)))
        advanceUntilIdle()

        assertNull(vm.errorMessage.value)
        assertTrue(DiagnosticLogBuffer.dumpText().contains("Imported 1 book(s) from 1 file(s)"))
    }

    @Test
    fun importUrisEmptyResultSetsErrorAndRecordsDiagnosticLog() = runTest(testDispatcher) {
        val vm = LibraryViewModel(bookDao, chapterDao)
        vm.uriImporterOverride = { _, _ -> emptyList() }

        vm.importUris(context, listOf(mockk<Uri>(relaxed = true)))
        advanceUntilIdle()

        val err = vm.errorMessage.value
        assertNotNull(err)
        assertTrue(err!!.contains("Could not import"))
        assertTrue(DiagnosticLogBuffer.dumpText().contains("URI import returned 0 items"))

        vm.clearError()
        assertNull(vm.errorMessage.value)
    }

    @Test
    fun importUrisExceptionSetsErrorWithoutCrashingOnLog() = runTest(testDispatcher) {
        val vm = LibraryViewModel(bookDao, chapterDao)
        vm.uriImporterOverride = { _, _ -> throw IllegalStateException("Corrupt ZIP header") }

        vm.importUris(context, listOf(mockk<Uri>(relaxed = true)))
        advanceUntilIdle()

        assertEquals("Corrupt ZIP header", vm.errorMessage.value)
        val dump = DiagnosticLogBuffer.dumpText()
        assertTrue(dump.contains("Error importing selected files"))
        assertTrue(dump.contains("IllegalStateException: Corrupt ZIP header"))
    }

    @Test
    fun importUrisPropagatesCancellationWithoutSettingError() = runTest(testDispatcher) {
        val vm = LibraryViewModel(bookDao, chapterDao)
        vm.uriImporterOverride = { _, _ -> throw CancellationException("User cancelled") }

        vm.importUris(context, listOf(mockk<Uri>(relaxed = true)))
        advanceUntilIdle()

        assertNull(vm.errorMessage.value)
        assertFalse(DiagnosticLogBuffer.dumpText().contains("Error importing selected files"))
    }

    @Test
    fun importFolderHandlesEmptySupportedFilesAndValidImport() = runTest(testDispatcher) {
        val vm = LibraryViewModel(bookDao, chapterDao)
        val treeUri = mockk<Uri>(relaxed = true)

        vm.folderUriCollector = { _, _ -> emptyList() }
        vm.importFolder(context, treeUri)
        advanceUntilIdle()

        assertEquals("No supported ebooks or audiobooks found in selected folder.", vm.errorMessage.value)
        assertTrue(DiagnosticLogBuffer.dumpText().contains("No supported files found in selected folder"))

        val childUri = mockk<Uri>(relaxed = true)
        vm.folderUriCollector = { _, _ -> listOf(childUri) }
        vm.uriImporterOverride = { _, uris -> listOf("imported_${uris.size}") }

        vm.importFolder(context, treeUri)
        advanceUntilIdle()

        assertNull(vm.errorMessage.value)
        assertTrue(DiagnosticLogBuffer.dumpText().contains("Imported 1 book(s) from folder"))
    }

    @Test
    fun importFolderEmptyImportResultSetsError() = runTest(testDispatcher) {
        val vm = LibraryViewModel(bookDao, chapterDao)
        val treeUri = mockk<Uri>(relaxed = true)
        vm.folderUriCollector = { _, _ -> listOf(mockk(relaxed = true)) }
        vm.uriImporterOverride = { _, _ -> emptyList() }

        vm.importFolder(context, treeUri)
        advanceUntilIdle()

        assertEquals("Could not import supported files from the selected folder.", vm.errorMessage.value)
        assertTrue(DiagnosticLogBuffer.dumpText().contains("Folder import returned 0 items"))
    }

    @Test
    fun deleteBookDeletesOrRecordsFailure() = runTest(testDispatcher) {
        val vm = LibraryViewModel(bookDao, chapterDao)
        vm.deleteBook("book_1")
        advanceUntilIdle()
        assertEquals(listOf("book_1"), bookDao.deletedIds)
        assertNull(vm.errorMessage.value)

        bookDao.throwOnDelete = RuntimeException("DB locked")
        vm.deleteBook("book_2")
        advanceUntilIdle()
        assertEquals("DB locked", vm.errorMessage.value)
        assertTrue(DiagnosticLogBuffer.dumpText().contains("Error deleting book id=book_2"))
    }
}
