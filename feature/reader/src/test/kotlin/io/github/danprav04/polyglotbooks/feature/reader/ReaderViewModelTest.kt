package io.github.danprav04.polyglotbooks.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateDao
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateEntity
import io.github.danprav04.polyglotbooks.core.data.prefs.FeatureUsageStats
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
    override suspend fun insertChapters(chapters: List<ChapterEntity>) {}
}

class FakeChapterDao(
    initialChapters: List<ChapterEntity> = listOf(
        ChapterEntity(id = "ch_1", bookId = "test_book", index = 0, title = "Chapter 1", startMs = 0, endMs = 1000, cfiRange = "cfi_1", textLength = 100),
        ChapterEntity(id = "ch_2", bookId = "test_book", index = 1, title = "Chapter 2", startMs = 1000, endMs = 2000, cfiRange = "cfi_2", textLength = 100)
    )
) : ChapterDao {
    val chapters = initialChapters

    override fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>> = flowOf(chapters)
    override suspend fun getChapterById(id: String): ChapterEntity? = chapters.find { it.id == id }
    override suspend fun getChapterByIndex(bookId: String, index: Int): ChapterEntity? = chapters.find { it.index == index }
    override suspend fun insertChapter(chapter: ChapterEntity) {}
    override suspend fun insertChapters(chapters: List<ChapterEntity>) {}
    override suspend fun updateChapter(chapter: ChapterEntity) {}
    override suspend fun deleteChapter(chapter: ChapterEntity) {}
    override suspend fun deleteChaptersByBookId(bookId: String) {}
}

class FakePlaybackStateDao : PlaybackStateDao {
    private val states = MutableStateFlow<Map<String, PlaybackStateEntity>>(emptyMap())
    var saveGate: CompletableDeferred<Unit>? = null
    var saveCount: Int = 0

    override suspend fun getPlaybackState(bookId: String): PlaybackStateEntity? = states.value[bookId]

    override fun observePlaybackState(bookId: String): Flow<PlaybackStateEntity?> =
        states.map { it[bookId] }

    override fun observeAllPlaybackStates(): Flow<List<PlaybackStateEntity>> =
        states.map { it.values.toList() }

    override suspend fun upsertPlaybackState(state: PlaybackStateEntity) {
        saveGate?.await()
        saveCount++
        states.value = states.value + (state.bookId to state)
    }

    override suspend fun updateAudioProgress(
        bookId: String,
        chapterIndex: Int,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long
    ): Int {
        val current = states.value[bookId] ?: return 0
        states.value = states.value + (bookId to current.copy(
            chapterIndex = chapterIndex,
            positionMs = positionMs,
            durationMs = durationMs,
            updatedAt = updatedAt
        ))
        return 1
    }

    override suspend fun updateReaderProgress(
        bookId: String,
        lastCfi: String?,
        readingProgress: Float,
        updatedAt: Long
    ): Int {
        saveGate?.await()
        val current = states.value[bookId] ?: return 0
        saveCount++
        states.value = states.value + (bookId to current.copy(
            lastCfi = lastCfi,
            readingProgress = readingProgress,
            updatedAt = updatedAt
        ))
        return 1
    }

    override suspend fun updatePlaybackSettings(
        bookId: String,
        playbackSpeed: Float,
        isLiveTranslationActive: Boolean,
        isDuckingEnabled: Boolean,
        targetLanguageCode: String?,
        updatedAt: Long
    ): Int = 0

    override suspend fun updateLastPauseTimestamp(
        bookId: String,
        timestamp: Long,
        updatedAt: Long
    ): Int = 0

    override suspend fun deletePlaybackState(bookId: String) {
        states.value = states.value - bookId
    }
}

class FakeSettingsRepository : SettingsRepository {
    private val _lastOpenedBookId = MutableStateFlow<String?>(null)
    override val apiKey: Flow<String?> = flowOf(null)
    override val modelPerRole: Flow<Map<ModelRole, String>> = flowOf(DefaultModels.map)
    override val targetLanguage: Flow<LanguageCode> = flowOf(LanguageCode.ENGLISH)
    override val cacheLimitBytes: Flow<Long> = flowOf(1024L * 1024L * 1024L)
    override val duckLevel: Flow<Float> = flowOf(0.15f)
    override val echoTargetLanguage: Flow<Boolean> = flowOf(false)
    override val featureUsageStats: Flow<FeatureUsageStats> = flowOf(FeatureUsageStats())
    override val lastOpenedBookId: Flow<String?> = _lastOpenedBookId

    var lastRecordedBookId: String? = null

    override suspend fun saveApiKey(key: String) {}
    override suspend fun clearApiKey() {}
    override suspend fun setModel(role: ModelRole, modelId: String) {}
    override suspend fun resetModelsToDefaults() {}
    override suspend fun setTargetLanguage(language: LanguageCode) {}
    override suspend fun setCacheLimit(bytes: Long) {}
    override suspend fun setDuckLevel(level: Float) {}
    override suspend fun setEchoTargetLanguage(echo: Boolean) {}
    override suspend fun setFreeTier(isFreeTier: Boolean) {}
    override suspend fun setLastOpenedBookId(bookId: String?) {
        lastRecordedBookId = bookId
        _lastOpenedBookId.value = bookId
    }

    override suspend fun recordUsage(
        liveTranslateMinutesDelta: Double,
        ttsMinutesDelta: Double,
        liveNarrationMinutesDelta: Double,
        textAnalysisTokensDelta: Long,
        textTranslationTokensDelta: Long
    ) {}

    override suspend fun resetUsageStats() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    @Test
    fun testInvalidOrUnreadableBookSurfacesErrorState() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        // Non-existent book ID
        val savedStateHandle = SavedStateHandle(mapOf("bookId" to "missing_book_999"))

        val viewModel = ReaderViewModel(bookDao, chapterDao, savedStateHandle)
        val state = viewModel.uiState.value
        org.junit.Assert.assertNotNull(state.errorMessage)
        assertTrue(state.errorMessage!!.contains("missing_book_999"))

        viewModel.clearError()
        org.junit.Assert.assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun testSaveProgressPersistsCfiProgressAndUpdatesBookAndSettings() = runTest {
        val bookDao = FakeBookDao().apply {
            book = book.copy(filePath = "file:///books/test.epub", lastReadDate = 100L)
        }
        val chapterDao = FakeChapterDao()
        val playbackStateDao = FakePlaybackStateDao()
        val settingsRepository = FakeSettingsRepository()
        val savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book"))

        val viewModel = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = savedStateHandle,
            playbackStateDao = playbackStateDao,
            settingsRepository = settingsRepository
        )

        assertTrue(viewModel.uiState.value.isStateRestored)
        assertEquals("file:///books/test.epub", viewModel.uiState.value.bookFilePath)
        assertEquals("test_book", settingsRepository.lastRecordedBookId)
        assertTrue(bookDao.book.lastReadDate > 100L)

        viewModel.onPageTurned("cfi_2", 0.65f, "Chapter 2")

        val persisted = playbackStateDao.getPlaybackState("test_book")
        assertNotNull(persisted)
        assertEquals("cfi_2", persisted?.lastCfi)
        assertEquals(0.65f, persisted?.readingProgress)
        assertEquals(1, viewModel.uiState.value.currentChapterIndex)
    }

    @Test
    fun testRestoresCfiProgressAndMatchedChapterOnReload() = runTest {
        val bookDao = FakeBookDao().apply {
            book = book.copy(filePath = "file:///books/test.epub")
        }
        val chapterDao = FakeChapterDao()
        val playbackStateDao = FakePlaybackStateDao()
        val settingsRepository = FakeSettingsRepository()

        // First session writes reading position and shuts down abruptly.
        val firstSessionVm = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book")),
            playbackStateDao = playbackStateDao,
            settingsRepository = settingsRepository
        )
        firstSessionVm.saveProgress("cfi_2", 0.72f)

        // Second session recreates ViewModel from scratch using persisted Room state.
        val reloadedVm = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book")),
            playbackStateDao = playbackStateDao,
            settingsRepository = settingsRepository
        )

        val state = reloadedVm.uiState.value
        assertTrue(state.isStateRestored)
        assertEquals("cfi_2", state.currentCfi)
        assertEquals(0.72f, state.progress)
        assertEquals(1, state.currentChapterIndex)
        assertEquals("file:///books/test.epub", state.bookFilePath)
    }

    @Test
    fun testEpubCfiPrefixMatchingRestoresCorrectChapter() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao(
            initialChapters = listOf(
                ChapterEntity(id = "ch_1", bookId = "test_book", index = 0, title = "Intro", startMs = 0, endMs = 0, cfiRange = "epubcfi(/6/2)", textLength = 500),
                ChapterEntity(id = "ch_2", bookId = "test_book", index = 1, title = "Body", startMs = 0, endMs = 0, cfiRange = "epubcfi(/6/4)", textLength = 500)
            )
        )
        val playbackStateDao = FakePlaybackStateDao()
        playbackStateDao.saveReaderProgress("test_book", "epubcfi(/6/4!/4/2/1:3)", 0.58f)

        val reloadedVm = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book")),
            playbackStateDao = playbackStateDao
        )

        assertEquals("epubcfi(/6/4!/4/2/1:3)", reloadedVm.uiState.value.currentCfi)
        assertEquals(0.58f, reloadedVm.uiState.value.progress)
        assertEquals(1, reloadedVm.uiState.value.currentChapterIndex)
    }

    @Test
    fun testFlushProgressUsesNonCancellableWhenViewModelCleared() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val playbackStateDao = FakePlaybackStateDao()
        val viewModel = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book")),
            playbackStateDao = playbackStateDao
        )

        viewModel.saveProgress("cfi_1", 0.33f)
        val countBeforeClear = playbackStateDao.saveCount

        // Trigger onCleared() via ViewModelStore, cancelling viewModelScope while flushing.
        val store = ViewModelStore()
        store.put("reader", viewModel)
        store.clear()

        assertTrue(playbackStateDao.saveCount > countBeforeClear)
        assertEquals("cfi_1", playbackStateDao.getPlaybackState("test_book")?.lastCfi)
        assertEquals(0.33f, playbackStateDao.getPlaybackState("test_book")?.readingProgress)
    }

    @Test
    fun testReaderBridgeOnBookReadyAndResetUpdatesState() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val viewModel = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book"))
        )
        val bridge = ReaderBridge(viewModel)

        assertFalse(viewModel.uiState.value.isBookReady)
        bridge.onBookReady()
        assertTrue(viewModel.uiState.value.isBookReady)

        viewModel.resetBookReady()
        assertFalse(viewModel.uiState.value.isBookReady)
    }
}
