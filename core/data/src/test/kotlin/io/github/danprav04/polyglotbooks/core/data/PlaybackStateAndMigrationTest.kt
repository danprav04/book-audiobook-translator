package io.github.danprav04.polyglotbooks.core.data

import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.BookmarkDao
import io.github.danprav04.polyglotbooks.core.data.db.BookmarkEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateDao
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateEntity
import io.github.danprav04.polyglotbooks.core.data.db.PolyglotDatabase
import io.github.danprav04.polyglotbooks.core.model.PlaybackState
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateAndMigrationTest {

    @Test
    fun testPlaybackStateEntityDomainRoundTrip() {
        val domain = PlaybackState(
            bookId = "book-1",
            chapterIndex = 2,
            positionMs = 45_000L,
            durationMs = 120_000L,
            playbackSpeed = 1.25f,
            isLiveTranslationActive = false,
            isDuckingEnabled = true,
            targetLanguageCode = "he",
            lastCfi = "epubcfi(/6/2!/4)",
            readingProgress = 0.65f,
            lastPauseTimestamp = 10_000L,
            updatedAt = 20_000L
        )
        val entity = PlaybackStateEntity.fromDomain(domain)
        assertEquals(domain, entity.toDomain())
    }

    @Test
    fun testMigration1To2CreatesPlaybackStateWithoutCascadeFk() {
        val migration = PolyglotDatabase.MIGRATION_1_2
        assertEquals(1, migration.startVersion)
        assertEquals(2, migration.endVersion)

        val db = mockk<SupportSQLiteDatabase>(relaxed = true)
        val sqlSlot = slot<String>()
        migration.migrate(db)

        verify(exactly = 1) { db.execSQL(capture(sqlSlot)) }
        val sql = sqlSlot.captured
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `playback_state`"))
        assertTrue(sql.contains("`bookId` TEXT NOT NULL"))
        assertTrue(sql.contains("`chapterIndex` INTEGER NOT NULL"))
        assertTrue(sql.contains("`positionMs` INTEGER NOT NULL"))
        assertTrue(sql.contains("`durationMs` INTEGER NOT NULL"))
        assertTrue(sql.contains("`playbackSpeed` REAL NOT NULL"))
        assertTrue(sql.contains("`isLiveTranslationActive` INTEGER NOT NULL"))
        assertTrue(sql.contains("`isDuckingEnabled` INTEGER NOT NULL"))
        assertTrue(sql.contains("`targetLanguageCode` TEXT"))
        assertTrue(sql.contains("`lastCfi` TEXT"))
        assertTrue(sql.contains("`readingProgress` REAL NOT NULL"))
        assertTrue(sql.contains("`lastPauseTimestamp` INTEGER NOT NULL"))
        assertTrue(sql.contains("`updatedAt` INTEGER NOT NULL"))
        assertTrue(sql.contains("PRIMARY KEY(`bookId`)"))
        assertFalse(sql.contains("FOREIGN KEY", ignoreCase = true))
        assertFalse(sql.contains("ON DELETE CASCADE", ignoreCase = true))
    }

    @Test
    fun testPlaybackStateDaoSaveHelpersCreateAndPartiallyUpdate() = runTest {
        val dao = InMemoryPlaybackStateDao()

        // First audio save inserts row
        dao.saveAudioProgress(
            bookId = "b1",
            chapterIndex = 1,
            positionMs = 15_000L,
            durationMs = 90_000L,
            updatedAt = 100L
        )
        val afterAudio = dao.getPlaybackState("b1")
        assertNotNull(afterAudio)
        assertEquals(1, afterAudio!!.chapterIndex)
        assertEquals(15_000L, afterAudio.positionMs)
        assertEquals(90_000L, afterAudio.durationMs)
        assertEquals(1.0f, afterAudio.playbackSpeed, 0.001f)

        // Settings save preserves audio progress
        dao.savePlaybackSettings(
            bookId = "b1",
            playbackSpeed = 1.5f,
            isLiveTranslationActive = false,
            isDuckingEnabled = true,
            targetLanguageCode = "es",
            updatedAt = 200L
        )
        val afterSettings = dao.getPlaybackState("b1")!!
        assertEquals(1, afterSettings.chapterIndex)
        assertEquals(15_000L, afterSettings.positionMs)
        assertEquals(1.5f, afterSettings.playbackSpeed, 0.001f)
        assertFalse(afterSettings.isLiveTranslationActive)
        assertTrue(afterSettings.isDuckingEnabled)
        assertEquals("es", afterSettings.targetLanguageCode)

        // Pause timestamp save preserves audio & settings
        dao.saveLastPauseTimestamp(bookId = "b1", timestamp = 300L, updatedAt = 301L)
        val afterPause = dao.getPlaybackState("b1")!!
        assertEquals(300L, afterPause.lastPauseTimestamp)
        assertEquals(15_000L, afterPause.positionMs)
        assertEquals(1.5f, afterPause.playbackSpeed, 0.001f)

        // Reader save on a fresh book inserts row
        dao.saveReaderProgress(
            bookId = "b2",
            lastCfi = "epubcfi(/6/8!/2)",
            readingProgress = 0.75f,
            updatedAt = 400L
        )
        val readerState = dao.observePlaybackState("b2").first()!!
        assertEquals("epubcfi(/6/8!/2)", readerState.lastCfi)
        assertEquals(0.75f, readerState.readingProgress, 0.001f)

        dao.deletePlaybackState("b1")
        assertNull(dao.getPlaybackState("b1"))
    }

    @Test
    fun testBookDaoUpdateLastReadDateAndBookmarkObserveAlias() = runTest {
        val books = mutableMapOf(
            "b1" to BookEntity(
                id = "b1",
                title = "Book 1",
                author = "Author",
                format = "M4B",
                filePath = "file:///b1.m4b",
                coverUri = null,
                addedDate = 10L,
                lastReadDate = 20L,
                isAudiobook = true
            )
        )
        val bookDao = object : BookDao {
            override fun getAllBooks() = flowOf(books.values.sortedByDescending { it.lastReadDate })
            override suspend fun getBookById(id: String) = books[id]
            override fun getBooksByType(isAudiobook: Boolean) = flowOf(emptyList<BookEntity>())
            override suspend fun insertBook(book: BookEntity) { books[book.id] = book }
            override suspend fun insertBooks(booksList: List<BookEntity>) {}
            override suspend fun updateBook(book: BookEntity) { books[book.id] = book }
            override suspend fun deleteBook(book: BookEntity) {}
            override suspend fun deleteBookById(id: String) {}
            override suspend fun insertChapters(chapters: List<ChapterEntity>) {}
        }

        bookDao.updateLastReadDate("b1", 999L)
        assertEquals(999L, bookDao.getBookById("b1")?.lastReadDate)
        assertEquals("Book 1", bookDao.getBookById("b1")?.title)

        val bookmark = BookmarkEntity(
            id = "bm1",
            bookId = "b1",
            positionMs = 5_000L,
            cfi = null,
            createdAt = 500L,
            note = "Chapter 1"
        )
        val bookmarkDao = object : BookmarkDao {
            private val items = MutableStateFlow(listOf(bookmark))
            override fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>> =
                items.map { list -> list.filter { it.bookId == bookId } }
            override suspend fun getBookmarkById(id: String) = items.value.find { it.id == id }
            override suspend fun insertBookmark(bookmark: BookmarkEntity) {
                items.value = items.value + bookmark
            }
            override suspend fun deleteBookmark(bookmark: BookmarkEntity) {}
            override suspend fun deleteBookmarkById(id: String) {
                items.value = items.value.filterNot { it.id == id }
            }
        }

        assertEquals(listOf(bookmark), bookmarkDao.observeBookmarksForBook("b1").first())
    }

    private class InMemoryPlaybackStateDao : PlaybackStateDao {
        private val store = MutableStateFlow<Map<String, PlaybackStateEntity>>(emptyMap())

        override suspend fun getPlaybackState(bookId: String): PlaybackStateEntity? =
            store.value[bookId]

        override fun observePlaybackState(bookId: String): Flow<PlaybackStateEntity?> =
            store.map { it[bookId] }

        override fun observeAllPlaybackStates(): Flow<List<PlaybackStateEntity>> =
            store.map { it.values.toList() }

        override suspend fun upsertPlaybackState(state: PlaybackStateEntity) {
            store.value = store.value + (state.bookId to state)
        }

        override suspend fun updateAudioProgress(
            bookId: String,
            chapterIndex: Int,
            positionMs: Long,
            durationMs: Long,
            updatedAt: Long
        ): Int {
            val existing = store.value[bookId] ?: return 0
            store.value = store.value + (
                bookId to existing.copy(
                    chapterIndex = chapterIndex,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = updatedAt
                )
            )
            return 1
        }

        override suspend fun updateReaderProgress(
            bookId: String,
            lastCfi: String?,
            readingProgress: Float,
            updatedAt: Long
        ): Int {
            val existing = store.value[bookId] ?: return 0
            store.value = store.value + (
                bookId to existing.copy(
                    lastCfi = lastCfi,
                    readingProgress = readingProgress,
                    updatedAt = updatedAt
                )
            )
            return 1
        }

        override suspend fun updatePlaybackSettings(
            bookId: String,
            playbackSpeed: Float,
            isLiveTranslationActive: Boolean,
            isDuckingEnabled: Boolean,
            targetLanguageCode: String?,
            updatedAt: Long
        ): Int {
            val existing = store.value[bookId] ?: return 0
            store.value = store.value + (
                bookId to existing.copy(
                    playbackSpeed = playbackSpeed,
                    isLiveTranslationActive = isLiveTranslationActive,
                    isDuckingEnabled = isDuckingEnabled,
                    targetLanguageCode = targetLanguageCode,
                    updatedAt = updatedAt
                )
            )
            return 1
        }

        override suspend fun updateLastPauseTimestamp(
            bookId: String,
            timestamp: Long,
            updatedAt: Long
        ): Int {
            val existing = store.value[bookId] ?: return 0
            store.value = store.value + (
                bookId to existing.copy(
                    lastPauseTimestamp = timestamp,
                    updatedAt = updatedAt
                )
            )
            return 1
        }

        override suspend fun deletePlaybackState(bookId: String) {
            store.value = store.value - bookId
        }
    }
}
