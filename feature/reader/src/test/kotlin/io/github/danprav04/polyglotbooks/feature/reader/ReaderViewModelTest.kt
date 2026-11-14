package io.github.danprav04.polyglotbooks.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.github.danprav04.polyglotbooks.core.audio.VoicePreviewPlayer
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.BookEntity
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.db.CharacterDao
import io.github.danprav04.polyglotbooks.core.data.db.CharacterEntity
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateDao
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateEntity
import io.github.danprav04.polyglotbooks.core.data.prefs.FeatureUsageStats
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.TextModelClient
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.GeminiStudioVoices
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.playback.ActivePlaybackSource
import io.github.danprav04.polyglotbooks.core.playback.PlaybackCoordinator
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.danprav04.polyglotbooks.core.gemini.Blob
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.InteractionsTtsClient
import io.github.danprav04.polyglotbooks.core.gemini.LiveClientMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveServerMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveSession
import io.github.danprav04.polyglotbooks.core.gemini.LiveSessionFactory
import io.github.danprav04.polyglotbooks.core.gemini.ModelTurn
import io.github.danprav04.polyglotbooks.core.gemini.Part
import io.github.danprav04.polyglotbooks.core.gemini.ServerContentData
import io.github.danprav04.polyglotbooks.core.gemini.TtsRequest
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.feature.narration.SystemTtsEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

class FakeBookDao : BookDao {
    private val defaultBook = BookEntity(
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
    private val books = linkedMapOf(defaultBook.id to defaultBook)

    var book: BookEntity = defaultBook
        set(value) {
            field = value
            books[value.id] = value
        }

    override fun getAllBooks(): Flow<List<BookEntity>> = flowOf(books.values.toList())
    override suspend fun getBookById(id: String): BookEntity? =
        books[id] ?: if (id == book.id) book else null
    override fun getBooksByType(isAudiobook: Boolean): Flow<List<BookEntity>> = flowOf(emptyList())
    override suspend fun insertBook(book: BookEntity) {
        books[book.id] = book
        this.book = book
    }
    override suspend fun insertBooks(books: List<BookEntity>) {
        books.forEach { this.books[it.id] = it }
    }
    override suspend fun updateBook(book: BookEntity) {
        books[book.id] = book
        if (this.book.id == book.id || books.size <= 1) {
            this.book = book
        }
    }
    override suspend fun deleteBook(book: BookEntity) {
        books.remove(book.id)
    }
    override suspend fun deleteBookById(id: String) {
        books.remove(id)
    }
    override suspend fun insertChapters(chapters: List<ChapterEntity>) {}
}

class FakeChapterDao(
    initialChapters: List<ChapterEntity> = listOf(
        ChapterEntity(id = "ch_1", bookId = "test_book", index = 0, title = "Chapter 1", startMs = 0, endMs = 1000, cfiRange = "cfi_1", textLength = 100),
        ChapterEntity(id = "ch_2", bookId = "test_book", index = 1, title = "Chapter 2", startMs = 1000, endMs = 2000, cfiRange = "cfi_2", textLength = 100)
    )
) : ChapterDao {
    private val chaptersFlow = MutableStateFlow(initialChapters)
    val chapters: List<ChapterEntity>
        get() = chaptersFlow.value

    override fun getChaptersForBook(bookId: String): Flow<List<ChapterEntity>> =
        chaptersFlow.map { list -> list.filter { it.bookId == bookId } }
    override suspend fun getChapterById(id: String): ChapterEntity? =
        chaptersFlow.value.find { it.id == id }
    override suspend fun getChapterByIndex(bookId: String, index: Int): ChapterEntity? =
        chaptersFlow.value.find { it.bookId == bookId && it.index == index }
    override suspend fun insertChapter(chapter: ChapterEntity) {
        chaptersFlow.value = chaptersFlow.value.filterNot { it.id == chapter.id } + chapter
    }
    override suspend fun insertChapters(chapters: List<ChapterEntity>) {
        val incomingIds = chapters.map { it.id }.toSet()
        chaptersFlow.value = (chaptersFlow.value.filterNot { it.id in incomingIds } + chapters)
            .sortedWith(compareBy({ it.bookId }, { it.index }))
    }
    override suspend fun updateChapter(chapter: ChapterEntity) {
        chaptersFlow.value = chaptersFlow.value.map { if (it.id == chapter.id) chapter else it }
    }
    override suspend fun deleteChapter(chapter: ChapterEntity) {
        chaptersFlow.value = chaptersFlow.value.filterNot { it.id == chapter.id }
    }
    override suspend fun deleteChaptersByBookId(bookId: String) {
        chaptersFlow.value = chaptersFlow.value.filterNot { it.bookId == bookId }
    }
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
    private val _targetLanguage = MutableStateFlow(LanguageCode.ENGLISH)
    private val _readerNarrationEngine = MutableStateFlow("GEMINI_FLASH_TTS")
    private val _defaultNarratorVoiceId = MutableStateFlow(GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID)
    private val _readerReadTranslated = MutableStateFlow(false)
    private val _readerNarrationSpeed = MutableStateFlow(1.0f)
    private val _readerTheme = MutableStateFlow("LIGHT")
    private val _readerFontSizeSp = MutableStateFlow(18)
    private val _readerFontFamily = MutableStateFlow("Sans-Serif")

    override val apiKey: Flow<String?> = flowOf(null)
    override val modelPerRole: Flow<Map<ModelRole, String>> = flowOf(DefaultModels.map)
    override val targetLanguage: Flow<LanguageCode> = _targetLanguage
    override val cacheLimitBytes: Flow<Long> = flowOf(1024L * 1024L * 1024L)
    override val duckLevel: Flow<Float> = flowOf(0.15f)
    override val echoTargetLanguage: Flow<Boolean> = flowOf(false)
    override val featureUsageStats: Flow<FeatureUsageStats> = flowOf(FeatureUsageStats())
    override val lastOpenedBookId: Flow<String?> = _lastOpenedBookId
    override val readerNarrationEngine: Flow<String> = _readerNarrationEngine
    override val defaultNarratorVoiceId: Flow<String> = _defaultNarratorVoiceId
    override val readerReadTranslated: Flow<Boolean> = _readerReadTranslated
    override val readerNarrationSpeed: Flow<Float> = _readerNarrationSpeed
    override val readerTheme: Flow<String> = _readerTheme
    override val readerFontSizeSp: Flow<Int> = _readerFontSizeSp
    override val readerFontFamily: Flow<String> = _readerFontFamily

    var lastRecordedBookId: String? = null

    override suspend fun saveApiKey(key: String) {}
    override suspend fun clearApiKey() {}
    override suspend fun setModel(role: ModelRole, modelId: String) {}
    override suspend fun resetModelsToDefaults() {}
    override suspend fun setTargetLanguage(language: LanguageCode) {
        _targetLanguage.value = language
    }
    override suspend fun setCacheLimit(bytes: Long) {}
    override suspend fun setDuckLevel(level: Float) {}
    override suspend fun setEchoTargetLanguage(echo: Boolean) {}
    override suspend fun setFreeTier(isFreeTier: Boolean) {}
    override suspend fun setLastOpenedBookId(bookId: String?) {
        lastRecordedBookId = bookId
        _lastOpenedBookId.value = bookId
    }
    override suspend fun setReaderNarrationEngine(engineName: String) {
        _readerNarrationEngine.value = engineName
    }
    override suspend fun setDefaultNarratorVoiceId(voiceId: String) {
        _defaultNarratorVoiceId.value = voiceId
    }
    override suspend fun setReaderReadTranslated(readTranslated: Boolean) {
        _readerReadTranslated.value = readTranslated
    }
    override suspend fun setReaderNarrationSpeed(speed: Float) {
        _readerNarrationSpeed.value = speed.coerceIn(0.5f, 2.5f)
    }
    override suspend fun setReaderTheme(themeName: String) {
        _readerTheme.value = themeName
    }
    override suspend fun setReaderFontSizeSp(sizeSp: Int) {
        _readerFontSizeSp.value = sizeSp.coerceIn(12, 32)
    }
    override suspend fun setReaderFontFamily(family: String) {
        _readerFontFamily.value = family
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

class FakeCharacterDao : CharacterDao {
    private val characters = MutableStateFlow<List<CharacterEntity>>(emptyList())

    override fun getCharactersForBook(bookId: String): Flow<List<CharacterEntity>> =
        characters.map { list -> list.filter { it.bookId == bookId } }

    override suspend fun getCharacterById(id: String): CharacterEntity? =
        characters.value.firstOrNull { it.id == id }

    override suspend fun insertCharacter(character: CharacterEntity) {
        characters.value = characters.value.filterNot { it.id == character.id } + character
    }

    override suspend fun insertCharacters(characters: List<CharacterEntity>) {
        val incomingIds = characters.map { it.id }.toSet()
        this.characters.value = this.characters.value.filterNot { it.id in incomingIds } + characters
    }

    override suspend fun updateCharacter(character: CharacterEntity) {
        characters.value = characters.value.map { if (it.id == character.id) character else it }
    }

    override suspend fun deleteCharacter(character: CharacterEntity) {
        characters.value = characters.value.filterNot { it.id == character.id }
    }

    override suspend fun deleteCharactersByBookId(bookId: String) {
        characters.value = characters.value.filterNot { it.bookId == bookId }
    }
}

class FakeSecureKeyStore(private var storedKey: String? = "test-api-key") : SecureKeyStore {
    override suspend fun getApiKey(): String? = storedKey
    override suspend fun saveApiKey(key: String) { storedKey = key }
    override suspend fun deleteApiKey() { storedKey = null }
}

class FakeTextModelClient(private val responder: (String) -> String) : TextModelClient {
    var lastPrompt: String? = null
    var lastModel: String? = null

    override suspend fun generateContent(
        apiKey: String,
        prompt: String,
        systemInstruction: String?,
        responseJsonSchema: String?,
        model: String
    ): String {
        lastPrompt = prompt
        lastModel = model
        return responder(prompt)
    }
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

    @Test
    fun testChapterNavigationSelectNextAndPrevUpdatesStateAndPersists() = runTest {
        val bookDao = FakeBookDao()
        val ch1 = ChapterEntity("ch_1", "test_book", 0, "Chapter 1", 0, 1000, "cfi_1", 100)
        val ch2 = ChapterEntity("ch_2", "test_book", 1, "Chapter 2", 1000, 2000, "cfi_2", 100)
        val ch3 = ChapterEntity("ch_3", "test_book", 2, "Chapter 3", 2000, 3000, "cfi_3", 100)
        val chapterDao = FakeChapterDao(listOf(ch1, ch2, ch3))
        val playbackStateDao = FakePlaybackStateDao()

        val viewModel = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book")),
            playbackStateDao = playbackStateDao
        )

        assertEquals(0, viewModel.uiState.value.currentChapterIndex)

        viewModel.nextChapter()
        assertEquals(1, viewModel.uiState.value.currentChapterIndex)
        assertEquals("cfi_2", viewModel.uiState.value.currentCfi)
        assertEquals(0.5f, viewModel.uiState.value.progress, 0.001f)
        assertEquals("cfi_2", playbackStateDao.getPlaybackState("test_book")?.lastCfi)

        viewModel.selectChapter(ch3)
        assertEquals(2, viewModel.uiState.value.currentChapterIndex)
        assertEquals("cfi_3", viewModel.uiState.value.currentCfi)
        assertEquals(1.0f, viewModel.uiState.value.progress, 0.001f)

        // Clamped at upper bound
        viewModel.nextChapter()
        assertEquals(2, viewModel.uiState.value.currentChapterIndex)

        viewModel.prevChapter()
        assertEquals(1, viewModel.uiState.value.currentChapterIndex)
        assertEquals("cfi_2", viewModel.uiState.value.currentCfi)

        viewModel.prevChapter()
        assertEquals(0, viewModel.uiState.value.currentChapterIndex)
        // Clamped at lower bound
        viewModel.prevChapter()
        assertEquals(0, viewModel.uiState.value.currentChapterIndex)
    }

    @Test
    fun testHeuristicCastAnalysisAndVoiceEditingPersistViaCharacterDao() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val characterDao = FakeCharacterDao()

        val viewModel = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book")),
            characterDao = characterDao
        )

        val paragraphsJson = """
            [
              {"id":"p0","text":"\"Hello there,\" said Alice cheerfully.","cfiRange":"epubcfi(/6/2!/4/2/1:0)"},
              {"id":"p1","text":"Bob: \"Good morning, Alice!\"","cfiRange":"epubcfi(/6/2!/4/2/2:0)"}
            ]
        """.trimIndent()
        viewModel.onParagraphsExtracted(0, paragraphsJson)

        viewModel.analyzeCastForCurrentChapter()

        val cast = viewModel.uiState.value.characters
        assertFalse(viewModel.uiState.value.isCastAnalyzing)
        assertTrue(cast.any { it.name == "Narrator" })
        assertTrue(cast.any { it.name == "Alice" })
        assertTrue(cast.any { it.name == "Bob" })

        val assignedVoices = cast.mapNotNull { it.assignedVoiceId }
        assertEquals(cast.size, assignedVoices.size)
        assertEquals(assignedVoices.size, assignedVoices.distinct().size)

        val alice = cast.first { it.name == "Alice" }
        viewModel.updateCharacterVoice(alice, "Zephyr", "warm, gentle")

        val updatedAlice = viewModel.uiState.value.characters.first { it.id == alice.id }
        assertEquals("Zephyr", updatedAlice.assignedVoiceId)
        assertEquals("warm, gentle", updatedAlice.voiceTraits)
        assertEquals("Zephyr", characterDao.getCharacterById(alice.id)?.assignedVoiceId)
    }

    @Test
    fun testBilingualTranslationUsesTextModelClientAndReTranslatesOnLanguageChange() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val characterDao = FakeCharacterDao()
        val keyStore = FakeSecureKeyStore("fake-gemini-key")
        val textClient = FakeTextModelClient { prompt ->
            if (prompt.contains("French")) {
                """["Bonjour le monde", "Bon matin"]"""
            } else {
                """["Hola mundo", "Buenos días"]"""
            }
        }

        val viewModel = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book")),
            characterDao = characterDao,
            secureKeyStore = keyStore,
            textModelClient = textClient
        )

        viewModel.onParagraphsExtracted(
            0,
            """
            [
              {"id":"p0","text":"Hello world","cfiRange":"epubcfi(/6/2!/4/2/1:0)"},
              {"id":"p1","text":"Good morning","cfiRange":"epubcfi(/6/2!/4/2/2:0)"}
            ]
            """.trimIndent()
        )

        viewModel.setTargetLanguage(LanguageCode.SPANISH)
        viewModel.toggleBilingualMode()

        assertTrue(viewModel.uiState.value.isBilingualEnabled)
        assertEquals("Hola mundo", viewModel.uiState.value.bilingualTranslations["epubcfi(/6/2!/4/2/1:0)"])
        assertEquals("Buenos días", viewModel.uiState.value.bilingualTranslations["epubcfi(/6/2!/4/2/2:0)"])

        viewModel.setTargetLanguage(LanguageCode.FRENCH)
        assertEquals(LanguageCode.FRENCH, viewModel.uiState.value.targetLanguage)
        assertEquals("Bonjour le monde", viewModel.uiState.value.bilingualTranslations["epubcfi(/6/2!/4/2/1:0)"])
        assertEquals("Bon matin", viewModel.uiState.value.bilingualTranslations["epubcfi(/6/2!/4/2/2:0)"])

        viewModel.toggleBilingualMode()
        assertFalse(viewModel.uiState.value.isBilingualEnabled)
        assertTrue(viewModel.uiState.value.bilingualTranslations.isEmpty())
    }

    @Test
    fun testToggleNarrationAndBridgeErrorCallback() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val settingsRepo = FakeSettingsRepository().apply {
            setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
        }
        val gate = CompletableDeferred<Unit>()
        val viewModel = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "test_book")),
            settingsRepository = settingsRepo
        ).apply {
            sessionManager.ioDispatcher = UnconfinedTestDispatcher(testScheduler)
            sessionManager.systemTtsFactory = {
                object : io.github.danprav04.polyglotbooks.feature.narration.NarrationEngine {
                    override val id = io.github.danprav04.polyglotbooks.feature.narration.EngineId.SYSTEM_TTS
                    override val capabilities = io.github.danprav04.polyglotbooks.feature.narration.EngineCapabilities(
                        supportsMultiVoice = false,
                        supportsEmotionalStyles = false,
                        supportsStreaming = false,
                        outputSampleRate = 24000
                    )
                    override suspend fun voices() = emptyList<io.github.danprav04.polyglotbooks.feature.narration.VoiceInfo>()
                    override fun synthesize(request: io.github.danprav04.polyglotbooks.feature.narration.SynthesisRequest) = kotlinx.coroutines.flow.flow {
                        emit(io.github.danprav04.polyglotbooks.core.model.PcmChunk(ByteArray(800), 24000, 1))
                        gate.await()
                    }
                }
            }
        }
        val bridge = ReaderBridge(viewModel)
        runCurrent()
        viewModel.onParagraphsExtracted(
            0,
            """[{"index":0,"text":"Playable test paragraph.","cfi":"epubcfi(/6/2!/4/2/1:0)"}]"""
        )

        assertFalse(viewModel.uiState.value.isNarrationPlaying)
        viewModel.toggleNarration()
        runCurrent()
        assertTrue(viewModel.uiState.value.isNarrationPlaying)
        viewModel.toggleNarration()
        runCurrent()
        assertFalse(viewModel.uiState.value.isNarrationPlaying)
        gate.complete(Unit)
        viewModel.stopNarration()
        runCurrent()

        bridge.onReaderError("Failed to render chapter")
        assertEquals("Failed to render chapter", viewModel.uiState.value.errorMessage)
        val clearStore = ViewModelStore()
        clearStore.put("reader", viewModel)
        clearStore.clear()
    }

    @Test
    fun `translation disk cache hit prevents redundant TextModelClient calls`() = runTest {
        val tempDir = Files.createTempDirectory("reader-cache-hit-test").toFile()
        try {
            var apiCalls = 0
            val textClient = object : TextModelClient {
                override suspend fun generateContent(
                    apiKey: String,
                    prompt: String,
                    systemInstruction: String?,
                    responseJsonSchema: String?,
                    model: String
                ): String {
                    apiCalls++
                    val count = Regex("of (\\d+) paragraphs").find(prompt)?.groupValues?.get(1)?.toIntOrNull() ?: 2
                    return (0 until count).joinToString(prefix = "[", postfix = "]") { idx ->
                        "\"Hola $idx\""
                    }
                }
            }
            val keyStore = object : SecureKeyStore {
                override suspend fun getApiKey(): String = "test-api-key"
                override suspend fun saveApiKey(key: String) {}
                override suspend fun deleteApiKey() {}
            }
            val viewModel = ReaderViewModel(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-1")),
                settingsRepository = FakeSettingsRepository(),
                secureKeyStore = keyStore,
                textModelClient = textClient,
                quotaTracker = GeminiQuotaTracker()
            )
            val cache = TranslationDiskCache(tempDir)
            viewModel.testDiskCache = cache

            val chaptersJson = """
                [{"index":0,"title":"Chapter 1","href":"ch1.html"}]
            """.trimIndent()
            viewModel.onChaptersExtracted(chaptersJson)
            viewModel.onParagraphsExtracted(0, """["Hello world.","Good morning."]""")
            advanceUntilIdle()

            // First toggle translates via TextModelClient and populates disk cache
            viewModel.toggleBilingualMode()
            advanceUntilIdle()
            assertEquals(1, apiCalls)
            assertTrue(viewModel.uiState.value.isBilingualEnabled)
            assertEquals("Hola 0", viewModel.uiState.value.bilingualTranslations.values.firstOrNull())

            // Toggle off and back on: should hit disk cache with zero additional API calls
            viewModel.toggleBilingualMode()
            advanceUntilIdle()
            viewModel.toggleBilingualMode()
            advanceUntilIdle()

            assertEquals(1, apiCalls)
            assertTrue(viewModel.uiState.value.isTranslationCached)
            assertEquals(2, viewModel.uiState.value.bilingualTranslations.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `large chapter translation uses single optimal batch and persists incrementally`() = runTest {
        val tempDir = Files.createTempDirectory("reader-batch-cache-test").toFile()
        try {
            var apiCalls = 0
            val textClient = object : TextModelClient {
                override suspend fun generateContent(
                    apiKey: String,
                    prompt: String,
                    systemInstruction: String?,
                    responseJsonSchema: String?,
                    model: String
                ): String {
                    apiCalls++
                    val count = Regex("of (\\d+) paragraphs").find(prompt)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    return (0 until count).joinToString(prefix = "[", postfix = "]") { idx ->
                        "\"Trad $idx\""
                    }
                }
            }
            val keyStore = object : SecureKeyStore {
                override suspend fun getApiKey(): String = "test-api-key"
                override suspend fun saveApiKey(key: String) {}
                override suspend fun deleteApiKey() {}
            }
            val viewModel = ReaderViewModel(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-batch")),
                settingsRepository = FakeSettingsRepository(),
                secureKeyStore = keyStore,
                textModelClient = textClient,
                quotaTracker = GeminiQuotaTracker()
            )
            val cache = TranslationDiskCache(tempDir)
            viewModel.testDiskCache = cache

            val paragraphsJson = (0 until 35).joinToString(",") { "\"Paragraph number $it unique text\"" }
            viewModel.onChaptersExtracted("""[{"index":0,"title":"Ch 1","href":"c1.html"}]""")
            viewModel.onParagraphsExtracted(0, "[$paragraphsJson]")
            advanceUntilIdle()

            viewModel.toggleBilingualMode()
            advanceUntilIdle()

            assertEquals("Expected 35 paragraphs in one optimal batch, not 25-item bursts", 1, apiCalls)
            assertEquals(35, viewModel.uiState.value.bilingualTranslations.size)

            val persisted = cache.loadTranslations(
                bookId = "book-batch",
                chapterIndex = 0,
                targetLang = viewModel.uiState.value.targetLanguage.code,
                currentParagraphsByCfi = viewModel.uiState.value.paragraphs.associate { it.cfi to it.text }
            )
            assertEquals(35, persisted.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `buildSpeechChunks groups same speaker and honors engine limits`() {
        val viewModel = ReaderViewModel(
            bookDao = FakeBookDao(),
            chapterDao = FakeChapterDao(),
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-1"))
        )
        val characters = listOf(
            CharacterEntity(
                id = "c1",
                bookId = "book-1",
                name = "Alice",
                aliases = "",
                gender = "female",
                age = "adult",
                voiceTraits = "warm",
                assignedVoiceId = "Kore"
            )
        )
        val longText = "A".repeat(950)
        val paragraphs = listOf(
            ReaderParagraph(0, "p-0", "Narrator sentence one.", "cfi-0"),
            ReaderParagraph(1, "p-1", "Narrator sentence two.", "cfi-1"),
            ReaderParagraph(2, "p-2", "Alice said, \"Hello there!\"", "cfi-2"),
            ReaderParagraph(3, "p-3", longText, "cfi-3")
        )

        val ttsChunks = viewModel.buildSpeechChunks(paragraphs, characters, NarrationEngine.GEMINI_FLASH_TTS)
        assertEquals(3, ttsChunks.size)
        assertEquals(listOf("cfi-0", "cfi-1"), ttsChunks[0].allCfis)
        assertEquals(listOf("cfi-2"), ttsChunks[1].allCfis)
        assertEquals("Kore", ttsChunks[1].voiceId)
        assertEquals(listOf("cfi-3"), ttsChunks[2].allCfis)

        val sysChunks = viewModel.buildSpeechChunks(paragraphs, characters, NarrationEngine.SYSTEM_TTS)
        assertEquals(4, sysChunks.size)
    }

    @Test
    fun `synthesizeSpeechChunk replays cached audio and falls back TTS to Live to System`() = runTest {
        val tempDir = Files.createTempDirectory("reader-audio-cache-test").toFile()
        try {
            val cache = TranslationDiskCache(tempDir)
            var ttsCalls = 0
            val failingTtsClient = object : InteractionsTtsClient {
                override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray {
                    ttsCalls++
                    throw IllegalStateException("429 quota exhausted")
                }

                override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> = emptyFlow()
            }

            val incoming = MutableSharedFlow<LiveServerMessage>(replay = 4)
            var liveConnectCount = 0
            var failLive = false
            val liveSessionFactory = object : LiveSessionFactory {
                override fun create(): LiveSession = object : LiveSession {
                    override val isConnected: Boolean = true
                    override val incomingMessages: Flow<LiveServerMessage> = incoming.asSharedFlow()

                    override suspend fun connect(apiKey: String, config: io.github.danprav04.polyglotbooks.core.gemini.SetupConfig) {
                        liveConnectCount++
                        if (failLive) throw IllegalStateException("Live unavailable")
                    }

                    override suspend fun send(message: LiveClientMessage) {
                        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(10, 20, 30, 40))
                        incoming.tryEmit(
                            LiveServerMessage.ServerContentMessage(
                                ServerContentData(
                                    modelTurn = ModelTurn(parts = listOf(Part(inlineData = Blob("audio/pcm", b64)))),
                                    turnComplete = true
                                )
                            )
                        )
                    }

                    override suspend fun disconnect() {}
                }
            }

            val keyStore = object : SecureKeyStore {
                override suspend fun getApiKey(): String = "test-api-key"
                override suspend fun saveApiKey(key: String) {}
                override suspend fun deleteApiKey() {}
            }
            val quotaTracker = GeminiQuotaTracker().apply {
                recordHttpError("gemini-3.8-flash-tts", 429, "ResourceExhausted", "60")
            }
            val viewModel = ReaderViewModel(
                bookDao = FakeBookDao(),
                chapterDao = FakeChapterDao(),
                savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-1")),
                settingsRepository = FakeSettingsRepository(),
                secureKeyStore = keyStore,
                interactionsTtsClient = failingTtsClient,
                liveSessionFactory = liveSessionFactory,
                quotaTracker = quotaTracker
            )

            val chunk = viewModel.buildSpeechChunks(
                paragraphs = listOf(ReaderParagraph(0, "p-0", "Test audio fallback.", "cfi-0")),
                characters = emptyList(),
                engine = NarrationEngine.GEMINI_FLASH_TTS
            ).single()
            val systemFallback = SystemTtsEngine(null)

            // 1. Pre-exhausted TTS quota skips TTS call and falls back cleanly to Live
            val collectedLive = mutableListOf<ByteArray>()
            var ttsFailedSignaled = false
            viewModel.synthesizeSpeechChunk(
                chunk = chunk,
                engineChoice = NarrationEngine.GEMINI_FLASH_TTS,
                systemFallback = systemFallback,
                diskCache = cache,
                maxCacheBytes = 1024L * 1024L,
                ttsAlreadyFailed = false,
                onTtsFailed = { ttsFailedSignaled = true },
                onStatusUpdate = {},
                onPcmChunk = { collectedLive.add(it.data) }
            )
            assertEquals(0, ttsCalls)
            assertTrue(ttsFailedSignaled)
            assertEquals(1, liveConnectCount)
            assertTrue(collectedLive.any { it.contentEquals(byteArrayOf(10, 20, 30, 40)) })

            // 2. Subsequent call for the same chunk replays cached Live PCM with 0 network calls
            val collectedCached = mutableListOf<ByteArray>()
            viewModel.synthesizeSpeechChunk(
                chunk = chunk,
                engineChoice = NarrationEngine.GEMINI_LIVE,
                systemFallback = systemFallback,
                diskCache = cache,
                maxCacheBytes = 1024L * 1024L,
                ttsAlreadyFailed = true,
                onTtsFailed = {},
                onStatusUpdate = {},
                onPcmChunk = { collectedCached.add(it.data) }
            )
            assertEquals(1, liveConnectCount)
            assertTrue(collectedCached.any { it.contentEquals(byteArrayOf(10, 20, 30, 40)) })

            // 3. When Live also fails on uncached text, falls back to SystemTtsEngine
            failLive = true
            val uncachedChunk = viewModel.buildSpeechChunks(
                paragraphs = listOf(ReaderParagraph(1, "p-1", "Offline fallback text.", "cfi-1")),
                characters = emptyList(),
                engine = NarrationEngine.GEMINI_FLASH_TTS
            ).single()
            val collectedSystem = mutableListOf<ByteArray>()
            viewModel.synthesizeSpeechChunk(
                chunk = uncachedChunk,
                engineChoice = NarrationEngine.GEMINI_FLASH_TTS,
                systemFallback = systemFallback,
                diskCache = cache,
                maxCacheBytes = 1024L * 1024L,
                ttsAlreadyFailed = true,
                onTtsFailed = {},
                onStatusUpdate = {},
                onPcmChunk = { collectedSystem.add(it.data) }
            )
            assertTrue(collectedSystem.sumOf { it.size } > 0)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `reader appearance and narration preferences persist via SettingsRepository`() = runTest {
        val settingsRepo = FakeSettingsRepository()
        val viewModel = ReaderViewModel(
            bookDao = FakeBookDao(),
            chapterDao = FakeChapterDao(),
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-prefs")),
            settingsRepository = settingsRepo
        )
        advanceUntilIdle()

        viewModel.setTheme(ReaderTheme.DARK)
        viewModel.setFontSize(22)
        viewModel.setFontFamily("Serif")
        viewModel.setNarrationEngine(NarrationEngine.GEMINI_LIVE)
        viewModel.setNarrateTranslatedText(true)
        viewModel.setPlaybackSpeed(1.5f)
        advanceUntilIdle()

        assertEquals("DARK", settingsRepo.readerTheme.first())
        assertEquals(22, settingsRepo.readerFontSizeSp.first())
        assertEquals("Serif", settingsRepo.readerFontFamily.first())
        assertEquals("GEMINI_LIVE", settingsRepo.readerNarrationEngine.first())
        assertEquals(true, settingsRepo.readerReadTranslated.first())
        assertEquals(1.5f, settingsRepo.readerNarrationSpeed.first())
    }

    @Test
    fun `reopening book reattaches to active background session and onCleared keeps playing`() = runTest {
        val bookDao = FakeBookDao()
        val chapterDao = FakeChapterDao()
        val settingsRepo = FakeSettingsRepository()
        bookDao.insertBook(
            BookEntity(
                id = "book-bg",
                title = "Background Book",
                author = "Tester",
                format = "EPUB",
                filePath = "/tmp/bg.epub",
                coverUri = null,
                addedDate = 1L,
                lastReadDate = 1L,
                isAudiobook = false
            )
        )
        val chapters = listOf(
            ChapterEntity("c0", "book-bg", 0, "Ch 1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 100),
            ChapterEntity("c1", "book-bg", 1, "Ch 2", 1000L, 2000L, "epubcfi(/6/4!/4/2/1:0)", 100)
        )
        chapterDao.insertChapters(chapters)

        val sharedSession = ReaderNarrationSessionManager(
            bookDao = bookDao,
            chapterDao = chapterDao,
            settingsRepository = settingsRepo,
            applicationScope = backgroundScope
        ).apply {
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
            systemTtsFactory = {
                object : io.github.danprav04.polyglotbooks.feature.narration.NarrationEngine {
                    override val id = io.github.danprav04.polyglotbooks.feature.narration.EngineId.SYSTEM_TTS
                    override val capabilities = io.github.danprav04.polyglotbooks.feature.narration.EngineCapabilities(
                        supportsMultiVoice = false,
                        supportsEmotionalStyles = false,
                        supportsStreaming = false,
                        outputSampleRate = 24000
                    )
                    override suspend fun voices() = emptyList<io.github.danprav04.polyglotbooks.feature.narration.VoiceInfo>()
                    override fun synthesize(request: io.github.danprav04.polyglotbooks.feature.narration.SynthesisRequest) =
                        kotlinx.coroutines.flow.flow {
                            emit(PcmChunk(ByteArray(1600), 24000, 1))
                            CompletableDeferred<Unit>().await()
                        }
                }
            }
        }
        sharedSession.updateBookContext(
            bookId = "book-bg",
            bookTitle = "Background Book",
            chapters = chapters,
            currentChapterIndex = 1,
            paragraphs = listOf(
                ReaderParagraph(0, "p0", "Ch2 Para 0", "epubcfi(/6/4!/4/2/1:0)"),
                ReaderParagraph(1, "p1", "Ch2 Para 1", "epubcfi(/6/4!/4/2/2:0)")
            ),
            activeParagraphIndex = 1,
            forceWhenDifferentBook = true
        )
        sharedSession.seekToParagraph(1)
        // Simulate active background session on chapter 1 paragraph 1
        sharedSession.startOrResume(
            bookId = "book-bg",
            bookTitle = "Background Book",
            chapters = chapters,
            currentChapterIndex = 1,
            paragraphs = emptyList(),
            activeParagraphIndex = 0
        )

        val vm1 = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-bg")),
            settingsRepository = settingsRepo,
            injectedSessionManager = sharedSession
        )
        advanceUntilIdle()

        assertTrue(vm1.uiState.value.isNarrationPlaying)
        assertEquals(1, vm1.uiState.value.currentChapterIndex)

        // Clearing ViewModel simulates navigating back to Library; background session must keep running!
        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(vm1)

        assertTrue(sharedSession.sessionState.value.isActiveSession)
        assertTrue(sharedSession.sessionState.value.isPlaying)
    }

    private class FakeVoicePreviewPlayer : VoicePreviewPlayer {
        private val _playing = MutableStateFlow<String?>(null)
        private val _error = MutableStateFlow<String?>(null)
        override val playingVoiceId: StateFlow<String?> = _playing.asStateFlow()
        override val previewError: StateFlow<String?> = _error.asStateFlow()
        var stopCount = 0

        override fun togglePreview(voiceId: String, onBeforeStart: (() -> Unit)?) {
            if (_playing.value == voiceId) {
                stop()
            } else {
                onBeforeStart?.invoke()
                _error.value = null
                _playing.value = voiceId
            }
        }

        override fun stop() {
            stopCount++
            _playing.value = null
        }
    }

    private fun createTestBook(id: String, title: String) = BookEntity(
        id = id,
        title = title,
        author = "Author",
        format = "EPUB",
        filePath = "",
        coverUri = null,
        addedDate = 0L,
        lastReadDate = 0L,
        isAudiobook = false
    )

    @Test
    fun `default narrator voice updates settings and replaces Charon in cast analysis`() = runTest {
        val bookDao = FakeBookDao().apply {
            insertBook(createTestBook("book-cast", "Cast Novel"))
        }
        val chapterDao = FakeChapterDao().apply {
            insertChapters(
                listOf(ChapterEntity("c0", "book-cast", 0, "Ch 1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 50))
            )
        }
        val characterDao = FakeCharacterDao()
        val settingsRepo = FakeSettingsRepository()
        val viewModel = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-cast")),
            settingsRepository = settingsRepo,
            characterDao = characterDao,
            textModelClient = FakeTextModelClient { """{"characters":[{"name":"Narrator"},{"name":"Alice","gender":"FEMALE"}]}""" }
        )
        advanceUntilIdle()

        viewModel.selectDefaultNarratorVoice("kore")
        advanceUntilIdle()
        assertEquals("Kore", viewModel.uiState.value.defaultNarratorVoiceId)
        assertEquals("Kore", settingsRepo.defaultNarratorVoiceId.first())

        viewModel.onParagraphsExtracted(
            0,
            """[{"index":0,"text":"\"Hello,\" Alice whispered softly."}]"""
        )
        viewModel.analyzeCastForCurrentChapter()
        advanceUntilIdle()

        val savedChars = characterDao.getCharactersForBook("book-cast").first()
        val narrator = savedChars.firstOrNull { it.name.equals("Narrator", ignoreCase = true) }
        assertNotNull(narrator)
        assertEquals("Kore", narrator?.assignedVoiceId)
        assertTrue(savedChars.none { it.assignedVoiceId == "Charon" && it.name.equals("Narrator", ignoreCase = true) })
    }

    @Test
    fun `toggleVoicePreview pauses active PlaybackCoordinator and onCleared stops preview`() = runTest {
        val bookDao = FakeBookDao().apply {
            insertBook(createTestBook("book-prev", "Preview Book"))
        }
        val chapterDao = FakeChapterDao()
        val coordinator = PlaybackCoordinator()
        val previewPlayer = FakeVoicePreviewPlayer()
        val viewModel = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-prev")),
            voicePreviewPlayer = previewPlayer,
            playbackCoordinator = coordinator
        )
        advanceUntilIdle()

        var delegatePaused = false
        coordinator.registerExternalDelegate(
            object : io.github.danprav04.polyglotbooks.core.playback.ExternalNarrationDelegate {
                private val _ext = MutableStateFlow(
                    io.github.danprav04.polyglotbooks.core.playback.ExternalNarrationState(
                        bookId = "other",
                        bookTitle = "Other",
                        isActiveSession = true,
                        isPlaying = true
                    )
                )
                override val externalState = _ext.asStateFlow()
                override fun play() {}
                override fun pause() {
                    delegatePaused = true
                    _ext.value = _ext.value.copy(isPlaying = false)
                }
                override fun stop() {}
                override fun seekToChapterPositionMs(positionMs: Long) {}
                override fun seekByDeltaMs(deltaMs: Long) {}
                override fun nextParagraph() {}
                override fun previousParagraph() {}
                override fun nextChapter() {}
                override fun previousChapter() {}
                override fun setPlaybackSpeed(speed: Float) {}
                override fun onAudioBecomingNoisy() {}
            }
        )
        coordinator.updateExternalState(
            io.github.danprav04.polyglotbooks.core.playback.ExternalNarrationState(
                bookId = "other",
                bookTitle = "Other",
                isActiveSession = true,
                isPlaying = true
            )
        )

        viewModel.toggleVoicePreview("Aoede")
        advanceUntilIdle()
        assertTrue("Active PlaybackCoordinator must be paused before preview starts", delegatePaused)
        assertEquals("Aoede", viewModel.uiState.value.playingPreviewVoiceId)

        val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onClearedMethod.isAccessible = true
        onClearedMethod.invoke(viewModel)
        assertTrue(previewPlayer.stopCount >= 1)
        assertNull(previewPlayer.playingVoiceId.value)
    }

    @Test
    fun `duplicate chapter titles resolve exact EPUB spine chapter on page turn and cold reopen even with stale chapterIndex`() = runTest {
        val bookDao = FakeBookDao().apply {
            insertBook(createTestBook("book-dup", "Anthology"))
        }
        val duplicateChapters = listOf(
            ChapterEntity("c0", "book-dup", 0, "Story", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 100),
            ChapterEntity("c1", "book-dup", 1, "Story", 1000L, 2000L, "epubcfi(/6/4!/4/2/1:0)", 100),
            ChapterEntity("c2", "book-dup", 2, "Story", 2000L, 3000L, "epubcfi(/6/6!/4/2/1:0)", 100)
        )
        val chapterDao = FakeChapterDao().apply { insertChapters(duplicateChapters) }
        val playbackDao = FakePlaybackStateDao().apply {
            // Simulate stale saved chapterIndex = 0 alongside newer chapter-2 paragraph-3 CFI
            upsertPlaybackState(
                PlaybackStateEntity(
                    bookId = "book-dup",
                    lastCfi = "epubcfi(/6/6!/4/2/3:0)",
                    chapterIndex = 0,
                    readingProgress = 0.72f
                )
            )
        }

        val viewModel = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-dup")),
            playbackStateDao = playbackDao
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isStateRestored)
        assertEquals(2, viewModel.uiState.value.currentChapterIndex)
        assertEquals("epubcfi(/6/6!/4/2/3:0)", viewModel.uiState.value.currentCfi)

        // Paragraph extraction inside chapter 2 must derive paragraph index 2 (from /4/2/3:0), not reset to 0
        viewModel.onParagraphsExtracted(
            2,
            """[{"index":0,"text":"P0","cfi":"epubcfi(/6/6!/4/2/1:0)"},{"index":1,"text":"P1","cfi":"epubcfi(/6/6!/4/2/2:0)"},{"index":2,"text":"P2","cfi":"epubcfi(/6/6!/4/2/3:0)"}]"""
        )
        assertEquals(2, viewModel.uiState.value.activeParagraphIndex)

        // Page turn with explicit chapterIndex=1 and duplicate title "Story" resolves to chapter 1 (not 0),
        // and does NOT increment chapterNavigationNonce (so WebView isn't re-loaded in a feedback loop)
        val bridge = ReaderBridge(viewModel)
        val nonceBeforeScroll = viewModel.uiState.value.chapterNavigationNonce
        bridge.onPageTurn("epubcfi(/6/4!/4/2/2:0)", 0.45f, 1, "Story")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.currentChapterIndex)
        assertEquals(nonceBeforeScroll, viewModel.uiState.value.chapterNavigationNonce)

        // Legacy 3-arg ReaderBridge page turn without explicit chapterIndex still resolves chapter 2 via /6/6 spine parse
        bridge.onPageTurn("epubcfi(/6/6!/4/2/2:0)", 0.7f, "Story")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.currentChapterIndex)

        // Explicit chapter jump increments chapterNavigationNonce and updates saved chapterIndex
        viewModel.prevChapter()
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.currentChapterIndex)
        assertTrue(viewModel.uiState.value.chapterNavigationNonce > nonceBeforeScroll)
        assertEquals(1, playbackDao.getPlaybackState("book-dup")?.chapterIndex)
    }

    @Test
    fun `multi-book cold reopen awaits chapters and restores independent chapterIndex and paragraph per book`() = runTest {
        val bookDao = FakeBookDao().apply {
            insertBook(createTestBook("mb-1", "Book Alpha"))
            insertBook(createTestBook("mb-2", "Book Beta"))
        }
        val chapterDao = FakeChapterDao().apply {
            insertChapters(
                listOf(
                    ChapterEntity("m1_0", "mb-1", 0, "Alpha 1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 50),
                    ChapterEntity("m1_1", "mb-1", 1, "Alpha 2", 1000L, 2000L, "epubcfi(/6/4!/4/2/1:0)", 50),
                    ChapterEntity("m2_0", "mb-2", 0, "Beta 1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 50),
                    ChapterEntity("m2_1", "mb-2", 1, "Beta 2", 1000L, 2000L, "epubcfi(/6/4!/4/2/1:0)", 50),
                    ChapterEntity("m2_2", "mb-2", 2, "Beta 3", 2000L, 3000L, "epubcfi(/6/6!/4/2/1:0)", 50)
                )
            )
        }
        val playbackDao = FakePlaybackStateDao()

        val vmBook1 = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "mb-1")),
            playbackStateDao = playbackDao
        )
        advanceUntilIdle()
        vmBook1.onPageTurned("epubcfi(/6/4!/4/2/2:0)", 0.65f, 1, "Alpha 2")
        vmBook1.flushProgress()
        advanceUntilIdle()

        val vmBook2 = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "mb-2")),
            playbackStateDao = playbackDao
        )
        advanceUntilIdle()
        vmBook2.onPageTurned("epubcfi(/6/6!/4/2/3:0)", 0.9f, 2, "Beta 3")
        vmBook2.flushProgress()
        advanceUntilIdle()

        // Cold reopen Book 1 -> must restore chapter 1, CFI epubcfi(/6/4!/4/2/2:0), and paragraph index 1
        val reopenedBook1 = ReaderViewModel(
            bookDao = bookDao,
            chapterDao = chapterDao,
            savedStateHandle = SavedStateHandle(mapOf("bookId" to "mb-1")),
            playbackStateDao = playbackDao
        )
        advanceUntilIdle()
        assertEquals(1, reopenedBook1.uiState.value.currentChapterIndex)
        assertEquals("epubcfi(/6/4!/4/2/2:0)", reopenedBook1.uiState.value.currentCfi)
        reopenedBook1.onParagraphsExtracted(
            1,
            """[{"index":0,"text":"P0","cfi":"epubcfi(/6/4!/4/2/1:0)"},{"index":1,"text":"P1","cfi":"epubcfi(/6/4!/4/2/2:0)"}]"""
        )
        assertEquals(1, reopenedBook1.uiState.value.activeParagraphIndex)
    }

    @Test
    fun `onPageTurned ignored during active follow-highlight narration and flushProgress uses active TTS position`() = runTest {
        val tempDir = Files.createTempDirectory("reader-vm-guard").toFile()
        try {
            val bookDao = FakeBookDao().apply {
                insertBook(createTestBook("book-guard", "Sync Guard"))
            }
            val chapters = listOf(
                ChapterEntity("c0", "book-guard", 0, "Ch 1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 100),
                ChapterEntity("c1", "book-guard", 1, "Ch 2", 1000L, 2000L, "epubcfi(/6/4!/4/2/1:0)", 100)
            )
            val chapterDao = FakeChapterDao().apply { insertChapters(chapters) }
            val playbackDao = FakePlaybackStateDao()
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
            }
            val holdSpeech = CompletableDeferred<Unit>()
            val sharedSession = ReaderNarrationSessionManager(
                bookDao = bookDao,
                chapterDao = chapterDao,
                playbackStateDao = playbackDao,
                settingsRepository = settingsRepo,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                systemTtsFactory = {
                    object : io.github.danprav04.polyglotbooks.feature.narration.NarrationEngine {
                        override val id = io.github.danprav04.polyglotbooks.feature.narration.EngineId.SYSTEM_TTS
                        override val capabilities = io.github.danprav04.polyglotbooks.feature.narration.EngineCapabilities(
                            supportsMultiVoice = false,
                            supportsEmotionalStyles = false,
                            supportsStreaming = false,
                            outputSampleRate = 24000
                        )
                        override suspend fun voices() = emptyList<io.github.danprav04.polyglotbooks.feature.narration.VoiceInfo>()
                        override fun synthesize(request: io.github.danprav04.polyglotbooks.feature.narration.SynthesisRequest) = kotlinx.coroutines.flow.flow {
                            emit(io.github.danprav04.polyglotbooks.core.model.PcmChunk(ByteArray(1600), 24000, 1))
                            holdSpeech.await()
                        }
                    }
                }
            }
            val ch1Paras = listOf(
                ReaderParagraph(0, "p0", "Active line zero.", "epubcfi(/6/4!/4/2/1:0)"),
                ReaderParagraph(1, "p1", "Active line one.", "epubcfi(/6/4!/4/2/2:0)")
            )
            sharedSession.updateBookContext(
                bookId = "book-guard",
                bookTitle = "Sync Guard",
                chapters = chapters,
                currentChapterIndex = 1,
                paragraphs = ch1Paras,
                activeParagraphIndex = 1,
                forceWhenDifferentBook = true
            )
            sharedSession.startOrResume(
                bookId = "book-guard",
                bookTitle = "Sync Guard",
                chapters = chapters,
                currentChapterIndex = 1,
                paragraphs = ch1Paras,
                activeParagraphIndex = 1
            )

            val viewModel = ReaderViewModel(
                bookDao = bookDao,
                chapterDao = chapterDao,
                savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-guard")),
                playbackStateDao = playbackDao,
                settingsRepository = settingsRepo,
                injectedSessionManager = sharedSession
            )
            runCurrent()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.isStateRestored)

            // Stale WebView scroll from chapter 0 smooth scroll must NOT clobber active TTS chapter 1 / para 1
            viewModel.onPageTurned("epubcfi(/6/2!/4/2/1:0)", 0.05f, 0, "Ch 1")
            // Stale DOM extraction from chapter 0 must also be ignored
            viewModel.onParagraphsExtracted(0, """[{"index":0,"text":"Wrong chapter","cfi":"epubcfi(/6/2!/4/2/1:0)"}]""")
            // Active-playback DOM extraction for same chapter must also be ignored
            viewModel.onParagraphsExtracted(1, """[{"index":0,"text":"Truncated","cfi":"epubcfi(/6/4!/4/2/1:0)"}]""")

            assertEquals(1, viewModel.uiState.value.currentChapterIndex)
            assertEquals(2, viewModel.uiState.value.paragraphs.size)
            assertEquals("epubcfi(/6/4!/4/2/2:0)", viewModel.uiState.value.currentCfi)

            // flushProgress during active playback must preserve active TTS CFI and chapterIndex
            viewModel.flushProgress()
            runCurrent()
            advanceUntilIdle()
            assertEquals("epubcfi(/6/4!/4/2/2:0)", playbackDao.getPlaybackState("book-guard")?.lastCfi)
            assertEquals(1, playbackDao.getPlaybackState("book-guard")?.chapterIndex)

            holdSpeech.complete(Unit)
            sharedSession.pause()
            runCurrent()
            advanceUntilIdle()

            // When paused, empty DOM extraction for current chapter clears stale paragraphs
            viewModel.onParagraphsExtracted(1, "[]")
            assertTrue(viewModel.uiState.value.paragraphs.isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `clearError clears session errorMessage and statusMessage without re-emitting stale message`() = runTest {
        val tempDir = Files.createTempDirectory("reader-clear-err").toFile()
        try {
            val bookDao = FakeBookDao().apply {
                insertBook(createTestBook("book-err", "Empty Book"))
            }
            val chapterDao = FakeChapterDao().apply {
                insertChapters(listOf(ChapterEntity("c0", "book-err", 0, "Cover", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 0)))
            }
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
            }
            val session = ReaderNarrationSessionManager(
                bookDao = bookDao,
                chapterDao = chapterDao,
                settingsRepository = settingsRepo,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = UnconfinedTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
            }
            val viewModel = ReaderViewModel(
                bookDao = bookDao,
                chapterDao = chapterDao,
                savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-err")),
                settingsRepository = settingsRepo,
                injectedSessionManager = session
            )
            runCurrent()
            advanceUntilIdle()

            viewModel.startNarration()
            runCurrent()
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.errorMessage)

            viewModel.clearError()
            runCurrent()
            advanceUntilIdle()
            assertNull(viewModel.uiState.value.errorMessage)
            assertNull(session.sessionState.value.errorMessage)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `paused narration chapter switch and reopen reattach restores new chapter without stale prior chapter highlight`() = runTest {
        val tempDir = Files.createTempDirectory("reader-reattach-paused").toFile()
        try {
            val bookDao = FakeBookDao().apply {
                insertBook(createTestBook("book-reattach", "Reattach Novel"))
            }
            val chapters = listOf(
                ChapterEntity("c0", "book-reattach", 0, "Ch 1", 0L, 1000L, "epubcfi(/6/2!/4/2/1:0)", 100),
                ChapterEntity("c1", "book-reattach", 1, "Ch 2", 1000L, 2000L, "epubcfi(/6/4!/4/2/1:0)", 100),
                ChapterEntity("c2", "book-reattach", 2, "Ch 3", 2000L, 3000L, "epubcfi(/6/6!/4/2/1:0)", 100),
                ChapterEntity("c3", "book-reattach", 3, "Ch 4", 3000L, 4000L, "epubcfi(/6/8!/4/2/1:0)", 100)
            )
            val chapterDao = FakeChapterDao().apply { insertChapters(chapters) }
            val playbackDao = FakePlaybackStateDao()
            val settingsRepo = FakeSettingsRepository().apply {
                setReaderNarrationEngine(NarrationEngine.SYSTEM_TTS.name)
            }
            val holdSpeech = CompletableDeferred<Unit>()
            val extractedBook = EbookContentExtractor.ExtractedBook(
                format = "EPUB",
                title = "Reattach Novel",
                author = "Author",
                pageCount = 4,
                chapters = listOf(
                    EbookContentExtractor.ExtractedChapter(0, "Ch 1", "ch0.xhtml", "", listOf("Ch1 P0")),
                    EbookContentExtractor.ExtractedChapter(1, "Ch 2", "ch1.xhtml", "", listOf("Ch2 P0")),
                    EbookContentExtractor.ExtractedChapter(2, "Ch 3", "ch2.xhtml", "", listOf("Ch3 P0", "Ch3 P1")),
                    EbookContentExtractor.ExtractedChapter(3, "Ch 4", "ch3.xhtml", "", listOf("Ch4 P0", "Ch4 P1"))
                )
            )
            val sharedSession = ReaderNarrationSessionManager(
                bookDao = bookDao,
                chapterDao = chapterDao,
                playbackStateDao = playbackDao,
                settingsRepository = settingsRepo,
                applicationScope = backgroundScope
            ).apply {
                ioDispatcher = UnconfinedTestDispatcher(testScheduler)
                testDiskCache = TranslationDiskCache(tempDir)
                systemTtsFactory = {
                    object : io.github.danprav04.polyglotbooks.feature.narration.NarrationEngine {
                        override val id = io.github.danprav04.polyglotbooks.feature.narration.EngineId.SYSTEM_TTS
                        override val capabilities = io.github.danprav04.polyglotbooks.feature.narration.EngineCapabilities(
                            supportsMultiVoice = false,
                            supportsEmotionalStyles = false,
                            supportsStreaming = false,
                            outputSampleRate = 24000
                        )
                        override suspend fun voices() = emptyList<io.github.danprav04.polyglotbooks.feature.narration.VoiceInfo>()
                        override fun synthesize(request: io.github.danprav04.polyglotbooks.feature.narration.SynthesisRequest) = kotlinx.coroutines.flow.flow {
                            emit(PcmChunk(ByteArray(1600), 24000, 1))
                            holdSpeech.await()
                        }
                    }
                }
            }

            val ch2Paras = sharedSession.buildParagraphsForChapter(extractedBook, 2, "EPUB")
            sharedSession.updateBookContext(
                bookId = "book-reattach",
                bookTitle = "Reattach Novel",
                extractedBook = extractedBook,
                chapters = chapters,
                currentChapterIndex = 2,
                paragraphs = ch2Paras,
                activeParagraphIndex = 1,
                forceWhenDifferentBook = true
            )
            sharedSession.startOrResume(
                bookId = "book-reattach",
                bookTitle = "Reattach Novel",
                extractedBook = extractedBook,
                chapters = chapters,
                currentChapterIndex = 2,
                paragraphs = ch2Paras,
                activeParagraphIndex = 1
            )

            val vm1 = ReaderViewModel(
                bookDao = bookDao,
                chapterDao = chapterDao,
                savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-reattach")),
                playbackStateDao = playbackDao,
                settingsRepository = settingsRepo,
                injectedSessionManager = sharedSession
            )
            runCurrent()
            advanceUntilIdle()

            // 1. Verify active TTS on Chapter 2 (index 2), paragraph 1 -> pause playback
            assertEquals("epubcfi(/6/6!/4/2/2:0)", vm1.uiState.value.highlightedCfi)

            vm1.pauseNarration()
            runCurrent()
            advanceUntilIdle()
            assertFalse(vm1.uiState.value.isNarrationPlaying)
            assertTrue(sharedSession.sessionState.value.isActiveSession)
            assertEquals("epubcfi(/6/6!/4/2/2:0)", vm1.uiState.value.highlightedCfi)

            // Reattaching while still on Chapter 2 preserves the paused Chapter 2 highlight
            val vmSameChapter = ReaderViewModel(
                bookDao = bookDao,
                chapterDao = chapterDao,
                savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-reattach")),
                playbackStateDao = playbackDao,
                settingsRepository = settingsRepo,
                injectedSessionManager = sharedSession
            )
            runCurrent()
            advanceUntilIdle()
            assertEquals(2, vmSameChapter.uiState.value.currentChapterIndex)
            assertEquals("epubcfi(/6/6!/4/2/2:0)", vmSameChapter.uiState.value.currentCfi)
            assertEquals("epubcfi(/6/6!/4/2/2:0)", vmSameChapter.uiState.value.highlightedCfi)

            // 2. While paused, open TOC and jump to Chapter 3 (index 3)
            vmSameChapter.selectChapter(chapters[3])
            runCurrent()
            advanceUntilIdle()

            assertEquals(3, vmSameChapter.uiState.value.currentChapterIndex)
            assertNull("Jumping to Chapter 3 while paused must clear Chapter 2 highlight in UI state", vmSameChapter.uiState.value.highlightedCfi)
            assertNull("Jumping to Chapter 3 while paused must clear Chapter 2 highlight in session state", sharedSession.sessionState.value.highlightedCfi)
            assertEquals(3, sharedSession.sessionState.value.currentChapterIndex)
            assertEquals("epubcfi(/6/8!/4/2/1:0)", vmSameChapter.uiState.value.currentCfi)

            // 3. Navigate Back to Library and reopen Book A while paused session is still active
            vmSameChapter.flushProgress()
            val onClearedMethod = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
            onClearedMethod.isAccessible = true
            onClearedMethod.invoke(vmSameChapter)
            runCurrent()
            advanceUntilIdle()

            val vmReopened = ReaderViewModel(
                bookDao = bookDao,
                chapterDao = chapterDao,
                savedStateHandle = SavedStateHandle(mapOf("bookId" to "book-reattach")),
                playbackStateDao = playbackDao,
                settingsRepository = settingsRepo,
                injectedSessionManager = sharedSession
            )
            runCurrent()
            advanceUntilIdle()

            assertTrue(vmReopened.uiState.value.isStateRestored)
            assertEquals(3, vmReopened.uiState.value.currentChapterIndex)
            assertEquals("epubcfi(/6/8!/4/2/1:0)", vmReopened.uiState.value.currentCfi)
            assertNull("Reopened book on Chapter 3 must not have stale Chapter 2 highlight", vmReopened.uiState.value.highlightedCfi)
            assertEquals("Ch4 P0", vmReopened.uiState.value.paragraphs.firstOrNull()?.text)

            holdSpeech.complete(Unit)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
