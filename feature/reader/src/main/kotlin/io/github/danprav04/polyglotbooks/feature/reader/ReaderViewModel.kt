package io.github.danprav04.polyglotbooks.feature.reader

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danprav04.polyglotbooks.core.audio.AudioTrackPcmPlayer
import io.github.danprav04.polyglotbooks.core.audio.PcmPlayer
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.db.CharacterDao
import io.github.danprav04.polyglotbooks.core.data.db.CharacterEntity
import io.github.danprav04.polyglotbooks.core.data.db.NarrationDao
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateDao
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.InteractionsTtsClient
import io.github.danprav04.polyglotbooks.core.gemini.LiveSessionFactory
import io.github.danprav04.polyglotbooks.core.gemini.TextModelClient
import io.github.danprav04.polyglotbooks.core.model.Character
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.feature.narration.CastingService
import io.github.danprav04.polyglotbooks.feature.narration.ChapterTranslator
import io.github.danprav04.polyglotbooks.feature.narration.GeminiLiveEngine
import io.github.danprav04.polyglotbooks.feature.narration.GeminiStudioVoices
import io.github.danprav04.polyglotbooks.feature.narration.GeminiTtsEngine
import io.github.danprav04.polyglotbooks.feature.narration.SynthesisRequest
import io.github.danprav04.polyglotbooks.feature.narration.SystemTtsEngine
import io.github.danprav04.polyglotbooks.feature.narration.VoiceCaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import io.github.danprav04.polyglotbooks.feature.narration.NarrationEngine as FeatureNarrationEngine

enum class ReaderTheme { LIGHT, SEPIA, DARK }
enum class NarrationEngine { GEMINI_FLASH_TTS, GEMINI_LIVE, SYSTEM_TTS }

data class SearchResultItem(
    val cfi: String,
    val excerpt: String
)

data class ReaderParagraph(
    val index: Int,
    val id: String,
    val text: String,
    val cfi: String
)

data class ReaderUiState(
    val bookId: String = "",
    val bookTitle: String = "Reader",
    val bookFormat: String = "EPUB",
    val bookFilePath: String = "",
    val chapters: List<ChapterEntity> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentCfi: String = "",
    val progress: Float = 0f,
    val isStateRestored: Boolean = false,
    val isBookReady: Boolean = false,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontSizeSp: Int = 18,
    val fontFamily: String = "Sans-Serif",
    val isTocOpen: Boolean = false,
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SearchResultItem> = emptyList(),
    val isNarrationPlaying: Boolean = false,
    val narrationEngine: NarrationEngine = NarrationEngine.GEMINI_FLASH_TTS,
    val isCastEditorOpen: Boolean = false,
    val isBilingualEnabled: Boolean = false,
    val targetLanguage: LanguageCode = LanguageCode.SPANISH,
    val bilingualTranslations: Map<String, String> = emptyMap(),
    val isReportDialogOpen: Boolean = false,
    val highlightedCfi: String? = null,
    val errorMessage: String? = null,
    val characters: List<CharacterEntity> = emptyList(),
    val isCastAnalyzing: Boolean = false,
    val isTranslating: Boolean = false,
    val extractedBook: EbookContentExtractor.ExtractedBook? = null,
    val activeParagraphIndex: Int = 0,
    val paragraphs: List<ReaderParagraph> = emptyList(),
    val narrationStatusMessage: String? = null
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    savedStateHandle: SavedStateHandle,
    private val playbackStateDao: PlaybackStateDao? = null,
    private val settingsRepository: SettingsRepository? = null,
    @ApplicationContext private val appContext: Context? = null,
    private val characterDao: CharacterDao? = null,
    private val narrationDao: NarrationDao? = null,
    private val secureKeyStore: SecureKeyStore? = null,
    private val interactionsTtsClient: InteractionsTtsClient? = null,
    private val textModelClient: TextModelClient? = null,
    private val liveSessionFactory: LiveSessionFactory? = null
) : ViewModel(), ReaderBridgeListener {

    private var bookId: String = savedStateHandle.get<String>("bookId") ?: "sample_book_id"
    private var loadJob: Job? = null
    private var charactersJob: Job? = null
    private var settingsJob: Job? = null
    private var narrationJob: Job? = null
    private var translationJob: Job? = null
    private var castJob: Job? = null
    private var activePcmPlayer: PcmPlayer? = null
    private val saveMutex = Mutex()

    private val _uiState = MutableStateFlow(ReaderUiState(bookId = bookId))
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
        loadBookAndChapters(bookId)
    }

    private fun observeSettings() {
        settingsJob?.cancel()
        val repo = settingsRepository ?: return
        settingsJob = viewModelScope.launch {
            repo.targetLanguage.collect { lang ->
                _uiState.update { it.copy(targetLanguage = lang) }
            }
        }
    }

    fun loadBookAndChapters(targetBookId: String = bookId) {
        if (targetBookId.isBlank()) return
        val switchingBook = _uiState.value.bookId != targetBookId
        bookId = targetBookId
        loadJob?.cancel()
        charactersJob?.cancel()
        if (switchingBook) {
            stopNarration()
            translationJob?.cancel()
            castJob?.cancel()
        }

        _uiState.update { state ->
            if (switchingBook) {
                state.copy(
                    bookId = targetBookId,
                    bookTitle = "Reader",
                    bookFormat = "EPUB",
                    bookFilePath = "",
                    chapters = emptyList(),
                    currentChapterIndex = 0,
                    currentCfi = "",
                    progress = 0f,
                    isStateRestored = false,
                    isBookReady = false,
                    characters = emptyList(),
                    extractedBook = null,
                    activeParagraphIndex = 0,
                    paragraphs = emptyList(),
                    bilingualTranslations = emptyMap(),
                    errorMessage = null,
                    narrationStatusMessage = null
                )
            } else {
                state.copy(isStateRestored = false)
            }
        }

        characterDao?.let { dao ->
            charactersJob = viewModelScope.launch {
                dao.getCharactersForBook(targetBookId).collect { chars ->
                    _uiState.update { it.copy(characters = chars) }
                }
            }
        }

        loadJob = viewModelScope.launch {
            try {
                val book = bookDao.getBookById(targetBookId)
                if (book != null) {
                    val savedState = playbackStateDao?.getPlaybackState(targetBookId)
                    val restoredCfi = savedState?.lastCfi.orEmpty()
                    val restoredProgress = (savedState?.readingProgress ?: 0f).coerceIn(0f, 1f)
                    val existingChapters = _uiState.value.chapters
                    val restoredChapterIndex = resolveChapterIndex(
                        chapters = existingChapters,
                        cfi = restoredCfi,
                        progress = restoredProgress,
                        fallbackIndex = savedState?.chapterIndex ?: 0
                    )
                    _uiState.update {
                        it.copy(
                            bookId = targetBookId,
                            bookTitle = book.title,
                            bookFormat = book.format,
                            bookFilePath = book.filePath,
                            currentCfi = restoredCfi,
                            progress = restoredProgress,
                            currentChapterIndex = restoredChapterIndex,
                            isStateRestored = true,
                            errorMessage = null
                        )
                    }
                    withContext(NonCancellable) {
                        settingsRepository?.setLastOpenedBookId(targetBookId)
                        bookDao.updateLastReadDate(targetBookId, System.currentTimeMillis())
                    }

                    if (appContext != null && book.filePath.isNotBlank()) {
                        val extracted = try {
                            withContext(Dispatchers.IO) {
                                EbookContentExtractor.extractBook(appContext, book.filePath, book.format)
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            null
                        }

                        if (extracted != null) {
                            val isPdf = extracted.format.equals("PDF", ignoreCase = true) ||
                                book.format.equals("PDF", ignoreCase = true)
                            val dbChapters = chapterDao.getChaptersForBook(targetBookId).firstOrNull().orEmpty()
                            if (dbChapters.isEmpty() && extracted.chapters.isNotEmpty()) {
                                val chapterEntities = extracted.chapters.map { ch ->
                                    ChapterEntity(
                                        id = "${targetBookId}_chap_${ch.index}",
                                        bookId = targetBookId,
                                        index = ch.index,
                                        title = ch.title.ifBlank {
                                            if (isPdf) "Page ${ch.index + 1}" else "Chapter ${ch.index + 1}"
                                        },
                                        startMs = ch.index * 1000L,
                                        endMs = (ch.index + 1) * 1000L,
                                        cfiRange = defaultChapterCfi(ch.index, isPdf),
                                        textLength = ch.paragraphs.sumOf { it.length }
                                    )
                                }
                                chapterDao.insertChapters(chapterEntities)
                            }
                            _uiState.update { state ->
                                val idx = state.currentChapterIndex.coerceIn(0, maxOf(0, extracted.chapters.lastIndex))
                                val initialParagraphs = if (state.paragraphs.isEmpty()) {
                                    buildParagraphsForChapter(extracted, idx, book.format)
                                } else {
                                    state.paragraphs
                                }
                                state.copy(
                                    extractedBook = extracted,
                                    paragraphs = initialParagraphs
                                )
                            }
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            bookId = targetBookId,
                            isStateRestored = true,
                            errorMessage = "Book not found or unreadable: $targetBookId"
                        )
                    }
                }

                chapterDao.getChaptersForBook(targetBookId).collect { chapters ->
                    _uiState.update { state ->
                        val resolvedChapter = resolveChapterIndex(
                            chapters = chapters,
                            cfi = state.currentCfi,
                            progress = state.progress,
                            fallbackIndex = state.currentChapterIndex
                        )
                        val refreshedParagraphs = if (state.paragraphs.isEmpty() && state.extractedBook != null) {
                            buildParagraphsForChapter(state.extractedBook, resolvedChapter, state.bookFormat)
                        } else {
                            state.paragraphs
                        }
                        state.copy(
                            chapters = chapters,
                            currentChapterIndex = resolvedChapter,
                            paragraphs = refreshedParagraphs
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isStateRestored = true,
                        errorMessage = e.message ?: "Failed to load book: $targetBookId"
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null, narrationStatusMessage = null) }
    }

    fun setTheme(theme: ReaderTheme) {
        _uiState.update { it.copy(theme = theme) }
    }

    fun setFontSize(size: Int) {
        val clamped = size.coerceIn(12, 32)
        _uiState.update { it.copy(fontSizeSp = clamped) }
    }

    fun setFontFamily(family: String) {
        _uiState.update { it.copy(fontFamily = family) }
    }

    fun setTocOpen(open: Boolean) {
        _uiState.update { it.copy(isTocOpen = open) }
    }

    fun setSearchOpen(open: Boolean) {
        _uiState.update {
            it.copy(
                isSearchOpen = open,
                searchQuery = if (!open) "" else it.searchQuery,
                searchResults = if (!open) emptyList() else it.searchResults
            )
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun selectChapter(chapter: ChapterEntity) {
        val chapters = _uiState.value.chapters
        val index = chapters.indexOfFirst { it.id == chapter.id }.takeIf { it >= 0 } ?: chapter.index
        selectChapterByIndex(index)
    }

    fun nextChapter() {
        val state = _uiState.value
        val total = maxOf(state.chapters.size, state.extractedBook?.chapters?.size ?: 0)
        if (total <= 0) return
        val next = (state.currentChapterIndex + 1).coerceAtMost(total - 1)
        if (next != state.currentChapterIndex) {
            selectChapterByIndex(next)
        }
    }

    fun prevChapter() {
        val state = _uiState.value
        val total = maxOf(state.chapters.size, state.extractedBook?.chapters?.size ?: 0)
        if (total <= 0) return
        val prev = (state.currentChapterIndex - 1).coerceAtLeast(0)
        if (prev != state.currentChapterIndex) {
            selectChapterByIndex(prev)
        }
    }

    private fun selectChapterByIndex(index: Int) {
        val state = _uiState.value
        val wasPlaying = state.isNarrationPlaying
        if (wasPlaying) {
            stopNarration()
        }
        val chapters = state.chapters
        val extracted = state.extractedBook
        val total = maxOf(chapters.size, extracted?.chapters?.size ?: 0)
        val clampedIndex = if (total > 0) index.coerceIn(0, total - 1) else index.coerceAtLeast(0)
        val chapter = chapters.getOrNull(clampedIndex)
        val isPdf = state.bookFormat.equals("PDF", ignoreCase = true) ||
            extracted?.format.equals("PDF", ignoreCase = true)
        val cfi = chapter?.cfiRange?.takeIf { it.isNotBlank() } ?: defaultChapterCfi(clampedIndex, isPdf)
        val progress = if (total <= 1) 0f else (clampedIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
        val newParagraphs = if (extracted != null) {
            buildParagraphsForChapter(extracted, clampedIndex, state.bookFormat)
        } else {
            emptyList()
        }

        _uiState.update {
            it.copy(
                currentChapterIndex = clampedIndex,
                currentCfi = cfi,
                progress = progress,
                activeParagraphIndex = 0,
                paragraphs = newParagraphs,
                highlightedCfi = null,
                bilingualTranslations = if (it.isBilingualEnabled) emptyMap() else it.bilingualTranslations
            )
        }

        if (bookId.isNotBlank()) {
            persistProgress(bookId, cfi, progress)
        }
        if (_uiState.value.isBilingualEnabled) {
            translateCurrentChapter()
        }
    }

    fun toggleNarration() {
        if (_uiState.value.isNarrationPlaying) {
            stopNarration()
        } else {
            startNarration()
        }
    }

    fun startNarration() {
        if (appContext == null) {
            _uiState.update { it.copy(isNarrationPlaying = true) }
            return
        }

        val state = _uiState.value
        val activeParagraphs = state.paragraphs.ifEmpty {
            state.extractedBook?.let { buildParagraphsForChapter(it, state.currentChapterIndex, state.bookFormat) }
                .orEmpty()
        }
        if (activeParagraphs.isEmpty()) {
            _uiState.update {
                it.copy(
                    isNarrationPlaying = false,
                    errorMessage = "No readable text found in this chapter to narrate."
                )
            }
            return
        }

        narrationJob?.cancel()
        releaseActivePlayer()
        _uiState.update {
            it.copy(
                isNarrationPlaying = true,
                paragraphs = activeParagraphs,
                narrationStatusMessage = null
            )
        }

        val startIndex = state.activeParagraphIndex.coerceIn(0, activeParagraphs.lastIndex)
        val engineChoice = state.narrationEngine
        val charactersSnapshot = state.characters

        narrationJob = viewModelScope.launch(Dispatchers.IO) {
            val player = AudioTrackPcmPlayer()
            activePcmPlayer = player
            var configuredSampleRate = 24000
            var configuredChannels = 1
            try {
                player.init(sampleRate = configuredSampleRate, channels = configuredChannels)
                player.play()

                val narratorVoice = charactersSnapshot
                    .firstOrNull { it.name.equals("Narrator", ignoreCase = true) }
                    ?.assignedVoiceId ?: "Charon"
                val systemFallback = SystemTtsEngine(appContext)

                for (pIdx in startIndex until activeParagraphs.size) {
                    ensureActive()
                    val para = activeParagraphs[pIdx]
                    if (para.text.isBlank()) continue

                    _uiState.update {
                        it.copy(
                            activeParagraphIndex = pIdx,
                            highlightedCfi = para.cfi
                        )
                    }

                    val (voiceId, style) = resolveSpeakerVoiceAndStyle(
                        paragraphText = para.text,
                        characters = charactersSnapshot,
                        defaultNarratorVoice = narratorVoice
                    )
                    val request = SynthesisRequest(
                        text = para.text,
                        voiceId = voiceId,
                        style = style
                    )

                    val selectedEngine: FeatureNarrationEngine = when (engineChoice) {
                        NarrationEngine.GEMINI_FLASH_TTS -> createGeminiTtsEngine()
                        NarrationEngine.GEMINI_LIVE -> GeminiLiveEngine(
                            fallbackEngine = createGeminiTtsEngine(),
                            liveSessionFactory = liveSessionFactory?.let { factory -> { factory.create() } },
                            apiKeyProvider = { resolveApiKey() },
                            modelProvider = { resolveModel(ModelRole.LIVE_NARRATION) }
                        )
                        NarrationEngine.SYSTEM_TTS -> systemFallback
                    }

                    var emittedAudio = false
                    try {
                        selectedEngine.synthesize(request).collect { chunk ->
                            ensureActive()
                            if (chunk.data.isNotEmpty()) {
                                emittedAudio = true
                                if (chunk.sampleRate != configuredSampleRate || chunk.channels != configuredChannels) {
                                    configuredSampleRate = chunk.sampleRate
                                    configuredChannels = chunk.channels
                                    player.init(configuredSampleRate, configuredChannels)
                                    player.play()
                                }
                                player.write(chunk)
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (engineChoice != NarrationEngine.SYSTEM_TTS) {
                            _uiState.update {
                                it.copy(
                                    narrationStatusMessage = "Using device TTS fallback (${e.message ?: "Gemini unavailable"})"
                                )
                            }
                            systemFallback.synthesize(request).collect { chunk ->
                                ensureActive()
                                if (chunk.data.isNotEmpty()) {
                                    emittedAudio = true
                                    if (chunk.sampleRate != configuredSampleRate || chunk.channels != configuredChannels) {
                                        configuredSampleRate = chunk.sampleRate
                                        configuredChannels = chunk.channels
                                        player.init(configuredSampleRate, configuredChannels)
                                        player.play()
                                    }
                                    player.write(chunk)
                                }
                            }
                        } else {
                            throw e
                        }
                    }

                    if (!emittedAudio && engineChoice != NarrationEngine.SYSTEM_TTS) {
                        _uiState.update {
                            it.copy(narrationStatusMessage = "Using device TTS fallback (empty Gemini audio)")
                        }
                        systemFallback.synthesize(request).collect { chunk ->
                            ensureActive()
                            if (chunk.data.isNotEmpty()) {
                                if (chunk.sampleRate != configuredSampleRate || chunk.channels != configuredChannels) {
                                    configuredSampleRate = chunk.sampleRate
                                    configuredChannels = chunk.channels
                                    player.init(configuredSampleRate, configuredChannels)
                                    player.play()
                                }
                                player.write(chunk)
                            }
                        }
                    }
                }

                // Allow hardware audio buffer to finish draining cleanly.
                var drainWaitMs = 0L
                while (player.bufferedMs > 0L && drainWaitMs < 3000L) {
                    ensureActive()
                    delay(100L)
                    drainWaitMs += 100L
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(errorMessage = e.message ?: "Narration failed")
                }
            } finally {
                try {
                    player.stop()
                    player.release()
                } catch (_: Throwable) {
                }
                if (activePcmPlayer === player) {
                    activePcmPlayer = null
                }
                _uiState.update { it.copy(isNarrationPlaying = false) }
            }
        }
    }

    fun stopNarration() {
        narrationJob?.cancel()
        narrationJob = null
        releaseActivePlayer()
        _uiState.update {
            it.copy(
                isNarrationPlaying = false,
                highlightedCfi = null,
                narrationStatusMessage = null
            )
        }
    }

    private fun releaseActivePlayer() {
        val player = activePcmPlayer
        activePcmPlayer = null
        if (player != null) {
            try {
                player.stop()
                player.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun createGeminiTtsEngine(): GeminiTtsEngine {
        return GeminiTtsEngine(
            ttsClient = interactionsTtsClient,
            apiKeyProvider = { resolveApiKey() },
            modelProvider = { resolveModel(ModelRole.TTS) }
        )
    }

    fun setNarrationEngine(engine: NarrationEngine) {
        val wasPlaying = _uiState.value.isNarrationPlaying
        if (wasPlaying) {
            stopNarration()
        }
        _uiState.update { it.copy(narrationEngine = engine) }
    }

    fun setCastEditorOpen(open: Boolean) {
        _uiState.update { it.copy(isCastEditorOpen = open) }
    }

    fun analyzeCastForCurrentChapter() {
        castJob?.cancel()
        castJob = viewModelScope.launch {
            _uiState.update { it.copy(isCastAnalyzing = true) }
            try {
                val state = _uiState.value
                val chapterParagraphs = state.paragraphs.ifEmpty {
                    state.extractedBook?.let { buildParagraphsForChapter(it, state.currentChapterIndex, state.bookFormat) }
                        .orEmpty()
                }
                val paragraphTexts = chapterParagraphs.map { it.text }.filter { it.isNotBlank() }
                val existing = state.characters
                val apiKey = resolveApiKey()
                val castingService = CastingService()

                var detectedDomainChars: List<Character> = emptyList()
                if (textModelClient != null && !apiKey.isNullOrBlank() && paragraphTexts.isNotEmpty()) {
                    try {
                        val prompt = buildString {
                            append("Identify all speaking characters and the narrator in these book paragraphs. ")
                            append("Return JSON matching the schema.\n\n")
                            paragraphTexts.take(35).forEachIndexed { idx, text ->
                                append("[$idx] $text\n")
                            }
                        }
                        val model = resolveModel(ModelRole.TEXT_ANALYSIS)
                        val responseJson = withContext(Dispatchers.IO) {
                            textModelClient.generateContent(
                                apiKey = apiKey,
                                prompt = prompt,
                                systemInstruction = "Extract characters and dialogue attribution accurately in JSON.",
                                responseJsonSchema = castingService.getPromptJsonSchema(),
                                model = model
                            )
                        }
                        val chapterId = state.chapters.getOrNull(state.currentChapterIndex)?.id ?: "${bookId}_chap_${state.currentChapterIndex}"
                        val result = castingService.parseAndValidateCasting(
                            jsonResponse = responseJson,
                            paragraphs = paragraphTexts,
                            existingCharacters = existing.map { it.toDomain() },
                            bookId = bookId,
                            chapterId = chapterId
                        )
                        detectedDomainChars = result.characters
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                }

                if (detectedDomainChars.isEmpty()) {
                    detectedDomainChars = detectSpeakersHeuristically(paragraphTexts, bookId)
                }

                // Always include Narrator
                val hasNarrator = detectedDomainChars.any {
                    it.id == "narrator" || it.name.equals("Narrator", ignoreCase = true)
                }
                if (!hasNarrator) {
                    detectedDomainChars = listOf(
                        Character(
                            id = "${bookId}_narrator",
                            bookId = bookId,
                            name = "Narrator",
                            gender = "neutral",
                            age = "mature",
                            voiceTraits = "clear, storytelling"
                        )
                    ) + detectedDomainChars
                }

                // Merge with existing characters and assign distinct Gemini studio voices
                val existingByName = existing.associateBy { it.name.lowercase() }
                val mergedDomain = mutableListOf<Character>()
                for (existingChar in existing) {
                    mergedDomain.add(existingChar.toDomain())
                }
                for (candidate in detectedDomainChars) {
                    val key = candidate.name.lowercase()
                    if (!existingByName.containsKey(key)) {
                        mergedDomain.add(
                            candidate.copy(
                                id = if (candidate.id.startsWith("${bookId}_")) candidate.id else "${bookId}_${candidate.id}"
                            )
                        )
                    }
                }

                val caster = VoiceCaster()
                val usedVoices = mergedDomain.mapNotNull { it.assignedVoiceId }.toMutableSet()
                val autoAssigned = caster.autoAssignVoices(mergedDomain)
                val availablePool = GeminiStudioVoices.catalog.map { it.id }.toMutableList()
                availablePool.removeAll(usedVoices)

                val finalEntities = mergedDomain.map { ch ->
                    val existingVoice = ch.assignedVoiceId?.takeIf { it.isNotBlank() }
                    val voice = if (existingVoice != null) {
                        existingVoice
                    } else if (ch.name.equals("Narrator", ignoreCase = true)) {
                        "Charon"
                    } else {
                        val preferred = autoAssigned[ch.id]
                        if (preferred != null && preferred !in usedVoices) {
                            usedVoices.add(preferred)
                            availablePool.remove(preferred)
                            preferred
                        } else {
                            val nextUnused = availablePool.removeFirstOrNull() ?: "Zephyr"
                            usedVoices.add(nextUnused)
                            nextUnused
                        }
                    }
                    CharacterEntity.fromDomain(ch.copy(bookId = bookId, assignedVoiceId = voice))
                }

                characterDao?.insertCharacters(finalEntities)
                _uiState.update { it.copy(characters = finalEntities, isCastAnalyzing = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isCastAnalyzing = false,
                        errorMessage = e.message ?: "Failed to analyze character cast"
                    )
                }
            }
        }
    }

    fun updateCharacterVoice(character: CharacterEntity, newVoiceId: String, newStyle: String?) {
        val updated = character.copy(
            assignedVoiceId = newVoiceId,
            voiceTraits = newStyle?.trim() ?: character.voiceTraits
        )
        _uiState.update { state ->
            state.copy(
                characters = state.characters.map { if (it.id == updated.id) updated else it }
            )
        }
        viewModelScope.launch {
            characterDao?.updateCharacter(updated)
        }
    }

    fun toggleBilingualMode() {
        val newState = !_uiState.value.isBilingualEnabled
        _uiState.update { it.copy(isBilingualEnabled = newState) }
        if (newState) {
            translateCurrentChapter()
        } else {
            translationJob?.cancel()
            _uiState.update { it.copy(bilingualTranslations = emptyMap(), isTranslating = false) }
        }
    }

    fun setTargetLanguage(lang: LanguageCode) {
        _uiState.update { it.copy(targetLanguage = lang) }
        viewModelScope.launch {
            settingsRepository?.setTargetLanguage(lang)
        }
        if (_uiState.value.isBilingualEnabled) {
            translateCurrentChapter()
        }
    }

    fun setTargetLanguage(lang: String) {
        setTargetLanguage(LanguageCode.fromCode(lang))
    }

    private fun translateCurrentChapter() {
        translationJob?.cancel()
        val state = _uiState.value
        val activeParagraphs = state.paragraphs.ifEmpty {
            state.extractedBook?.let { buildParagraphsForChapter(it, state.currentChapterIndex, state.bookFormat) }
                .orEmpty()
        }
        val targetLangCode = state.targetLanguage.code

        val paragraphMap: Map<String, String> = if (activeParagraphs.isNotEmpty()) {
            activeParagraphs.filter { it.text.isNotBlank() }.associate { it.cfi to it.text }
        } else {
            mapOf(
                "epubcfi(/6/1!/4/2/1:0)" to "Welcome to Polyglot Books. This is an immersive ebook reader.",
                "epubcfi(/6/1!/4/2/1:1)" to "With advanced AI narration engines like Gemini Flash TTS.",
                "epubcfi(/6/1!/4/2/1:2)" to "Switch between Light, Sepia, and Dark themes and adjust font sizes."
            )
        }

        val translator = ChapterTranslator(
            textModelClient = textModelClient,
            apiKeyProvider = { resolveApiKey() },
            modelProvider = { resolveModel(ModelRole.TEXT_TRANSLATION) }
        )

        translationJob = viewModelScope.launch {
            _uiState.update { it.copy(isTranslating = true) }
            try {
                val merged = linkedMapOf<String, String>()
                val entries = paragraphMap.entries.toList()
                val batchSize = 25
                for (start in entries.indices step batchSize) {
                    ensureActive()
                    val chunk = entries.subList(start, minOf(start + batchSize, entries.size))
                        .associate { it.key to it.value }
                    val result = translator.translateChapter(
                        paragraphs = chunk,
                        lines = emptyList(),
                        targetLanguageCode = targetLangCode
                    )
                    merged.putAll(result.bilingualParagraphs)
                    _uiState.update { it.copy(bilingualTranslations = merged.toMap()) }
                }
                _uiState.update { it.copy(isTranslating = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update {
                    it.copy(
                        isTranslating = false,
                        errorMessage = e.message ?: "Translation failed"
                    )
                }
            }
        }
    }

    fun setBilingualTranslations(translations: Map<String, String>) {
        _uiState.update { it.copy(bilingualTranslations = translations) }
    }

    fun setReportDialogOpen(open: Boolean) {
        _uiState.update { it.copy(isReportDialogOpen = open) }
    }

    fun highlightCfi(cfi: String) {
        _uiState.update { it.copy(highlightedCfi = cfi) }
    }

    fun saveProgress(cfi: String, progress: Float) {
        saveProgressInternal(cfi, progress, chapterTitle = "")
    }

    /**
     * Flushes the currently restored/active reading position to durable storage.
     * Skips writes if initial state has not finished loading or no position is set yet.
     */
    fun flushProgress() {
        val state = _uiState.value
        if (!state.isStateRestored || state.bookId.isBlank()) return
        if (state.currentCfi.isBlank() && state.progress <= 0f) return
        persistProgress(state.bookId, state.currentCfi, state.progress)
    }

    fun resetBookReady() {
        _uiState.update { it.copy(isBookReady = false) }
    }

    private fun saveProgressInternal(cfi: String, progress: Float, chapterTitle: String) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val targetBookId = bookId
        _uiState.update { state ->
            val chapterIndex = resolveChapterIndex(
                chapters = state.chapters,
                cfi = cfi,
                progress = clampedProgress,
                chapterTitle = chapterTitle,
                fallbackIndex = state.currentChapterIndex
            )
            state.copy(
                currentCfi = cfi,
                progress = clampedProgress,
                currentChapterIndex = chapterIndex
            )
        }
        if (targetBookId.isNotBlank()) {
            persistProgress(targetBookId, cfi, clampedProgress)
        }
    }

    private fun persistProgress(targetBookId: String, cfi: String, progress: Float) {
        // Detach parent Job via NonCancellable so abrupt UI/ViewModel teardown does not abort SQLite writes.
        viewModelScope.launch(NonCancellable) {
            try {
                saveMutex.withLock {
                    val latestState = _uiState.value
                    val cfiToSave = if (latestState.bookId == targetBookId) latestState.currentCfi else cfi
                    val progressToSave = if (latestState.bookId == targetBookId) latestState.progress else progress
                    val now = System.currentTimeMillis()
                    playbackStateDao?.saveReaderProgress(
                        bookId = targetBookId,
                        lastCfi = cfiToSave.ifBlank { null },
                        readingProgress = progressToSave,
                        updatedAt = now
                    )
                    bookDao.updateLastReadDate(targetBookId, now)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    private fun resolveChapterIndex(
        chapters: List<ChapterEntity>,
        cfi: String,
        progress: Float,
        chapterTitle: String = "",
        fallbackIndex: Int = 0
    ): Int {
        if (chapters.isEmpty()) return fallbackIndex.coerceAtLeast(0)
        if (cfi.isNotBlank()) {
            val exactIndex = chapters.indexOfFirst { !it.cfiRange.isNullOrBlank() && it.cfiRange == cfi }
            if (exactIndex >= 0) return exactIndex

            val prefixMatch = chapters.mapIndexedNotNull { index, chapter ->
                val range = chapter.cfiRange?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                val base = range.removeSuffix(")")
                if (cfi.startsWith("$base!") || cfi.startsWith("$base/") || cfi.startsWith("$range:")) {
                    index to range.length
                } else {
                    null
                }
            }.maxByOrNull { it.second }
            if (prefixMatch != null) return prefixMatch.first
        }
        if (chapterTitle.isNotBlank()) {
            val titleIndex = chapters.indexOfFirst { it.title.equals(chapterTitle.trim(), ignoreCase = true) }
            if (titleIndex >= 0) return titleIndex
        }
        if (progress > 0f) {
            return (progress * chapters.size).toInt().coerceIn(0, chapters.size - 1)
        }
        return fallbackIndex.coerceIn(0, chapters.size - 1)
    }

    override fun onCleared() {
        stopNarration()
        flushProgress()
        super.onCleared()
    }

    // ReaderBridgeListener callbacks
    override fun onParagraphsExtracted(chapterIndex: Int, paragraphsJson: String) {
        val state = _uiState.value
        val resolvedChapter = if (chapterIndex >= 0) chapterIndex else state.currentChapterIndex
        val isPdf = state.bookFormat.equals("PDF", ignoreCase = true) ||
            state.extractedBook?.format.equals("PDF", ignoreCase = true)
        val parsed = parseParagraphsJson(paragraphsJson, resolvedChapter, isPdf)
        if (parsed.isNotEmpty()) {
            val changed = parsed != state.paragraphs
            _uiState.update {
                it.copy(
                    paragraphs = parsed,
                    activeParagraphIndex = if (changed) 0 else it.activeParagraphIndex
                )
            }
            if (_uiState.value.isBilingualEnabled && (changed || state.bilingualTranslations.isEmpty())) {
                translateCurrentChapter()
            }
        }
    }

    override fun onChaptersExtracted(chaptersJson: String) {
        val currentBookId = bookId
        if (currentBookId.isBlank()) return
        try {
            val root = JsonMiniParser.parse(chaptersJson)
            val array: List<*> = when (root) {
                is Map<*, *> -> (root["chapters"] as? List<*>) ?: emptyList<Any?>()
                is List<*> -> root
                else -> emptyList<Any?>()
            }
            if (array.isEmpty()) return
            val existing = _uiState.value.chapters
            if (existing.isNotEmpty()) return
            val isPdf = _uiState.value.bookFormat.equals("PDF", ignoreCase = true)
            val entities = buildList {
                array.forEachIndexed { i, item ->
                    val obj = item as? Map<*, *> ?: return@forEachIndexed
                    val idx = (obj["index"] as? Number)?.toInt() ?: i
                    val title = (obj["title"] as? String)?.takeIf { it.isNotBlank() } ?: "Chapter ${idx + 1}"
                    val cfi = (obj["cfi"] as? String)?.takeIf { it.isNotBlank() }
                        ?: (obj["href"] as? String)?.takeIf { it.startsWith("pdf:page:") || it.startsWith("epubcfi(") }
                        ?: defaultChapterCfi(idx, isPdf)
                    add(
                        ChapterEntity(
                            id = "${currentBookId}_chap_$idx",
                            bookId = currentBookId,
                            index = idx,
                            title = title,
                            startMs = idx * 1000L,
                            endMs = (idx + 1) * 1000L,
                            cfiRange = cfi,
                            textLength = 0
                        )
                    )
                }
            }
            if (entities.isNotEmpty()) {
                viewModelScope.launch {
                    chapterDao.insertChapters(entities)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onReaderError(message: String) {
        if (message.isNotBlank()) {
            _uiState.update { it.copy(errorMessage = message) }
        }
    }

    override fun onPageTurned(cfi: String, fraction: Float, chapterTitle: String) {
        saveProgressInternal(cfi, fraction, chapterTitle)
    }

    override fun onBookReady() {
        _uiState.update { it.copy(isBookReady = true) }
    }

    override fun onSelectionChanged(cfiRange: String, selectedText: String) {}

    override fun onSearchResults(resultsJson: String) {
        val list = try {
            val arr = JsonMiniParser.parse(resultsJson) as? List<*> ?: emptyList<Any?>()
            arr.mapNotNull { item ->
                val obj = item as? Map<*, *> ?: return@mapNotNull null
                val cfi = obj["cfi"] as? String ?: return@mapNotNull null
                if (cfi.isBlank()) return@mapNotNull null
                val excerpt = obj["excerpt"] as? String ?: ""
                SearchResultItem(cfi = cfi, excerpt = excerpt)
            }
        } catch (_: Exception) {
            emptyList()
        }
        _uiState.update { it.copy(searchResults = list) }
    }

    private suspend fun resolveApiKey(): String? {
        val fromStore = try {
            secureKeyStore?.getApiKey()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
        if (fromStore != null) return fromStore
        return try {
            settingsRepository?.apiKey?.firstOrNull()?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun resolveModel(role: ModelRole): String {
        return try {
            settingsRepository?.modelPerRole?.firstOrNull()?.get(role) ?: DefaultModels.get(role)
        } catch (_: Throwable) {
            DefaultModels.get(role)
        }
    }

    private fun buildParagraphsForChapter(
        extracted: EbookContentExtractor.ExtractedBook,
        chapterIndex: Int,
        formatHint: String
    ): List<ReaderParagraph> {
        val chapter = extracted.chapters.getOrNull(chapterIndex) ?: return emptyList()
        val isPdf = extracted.format.equals("PDF", ignoreCase = true) ||
            formatHint.equals("PDF", ignoreCase = true)
        return chapter.paragraphs.mapIndexedNotNull { pIndex, text ->
            val trimmed = text.trim()
            if (trimmed.isEmpty()) null
            else ReaderParagraph(
                index = pIndex,
                id = "p_${chapterIndex}_$pIndex",
                text = trimmed,
                cfi = defaultParagraphCfi(chapterIndex, pIndex, isPdf)
            )
        }
    }

    private fun parseParagraphsJson(
        paragraphsJson: String,
        chapterIndex: Int,
        isPdf: Boolean
    ): List<ReaderParagraph> {
        return try {
            val arr = JsonMiniParser.parse(paragraphsJson) as? List<*> ?: return emptyList()
            buildList {
                arr.forEachIndexed { i, item ->
                    when (item) {
                        is Map<*, *> -> {
                            val text = (item["text"] as? String).orEmpty().trim()
                            if (text.isNotEmpty()) {
                                val cfi = (item["cfiRange"] as? String).orEmpty()
                                    .ifBlank { (item["cfi"] as? String).orEmpty() }
                                    .ifBlank { defaultParagraphCfi(chapterIndex, i, isPdf) }
                                val id = (item["id"] as? String)?.takeIf { it.isNotBlank() }
                                    ?: "p_${chapterIndex}_$i"
                                add(ReaderParagraph(index = i, id = id, text = text, cfi = cfi))
                            }
                        }
                        is String -> {
                            val text = item.trim()
                            if (text.isNotEmpty()) {
                                add(
                                    ReaderParagraph(
                                        index = i,
                                        id = "p_${chapterIndex}_$i",
                                        text = text,
                                        cfi = defaultParagraphCfi(chapterIndex, i, isPdf)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun defaultChapterCfi(chapterIndex: Int, isPdf: Boolean): String {
        return if (isPdf) {
            "pdf:page:$chapterIndex"
        } else {
            "epubcfi(/6/${(chapterIndex + 1) * 2}!/4/2/1:0)"
        }
    }

    private fun defaultParagraphCfi(chapterIndex: Int, paragraphIndex: Int, isPdf: Boolean): String {
        return if (isPdf) {
            "pdf:page:$chapterIndex:p:$paragraphIndex"
        } else {
            "epubcfi(/6/${(chapterIndex + 1) * 2}!/4/2/${paragraphIndex + 1}:0)"
        }
    }

    private fun resolveSpeakerVoiceAndStyle(
        paragraphText: String,
        characters: List<CharacterEntity>,
        defaultNarratorVoice: String
    ): Pair<String, String?> {
        if (characters.isEmpty()) return defaultNarratorVoice to null
        val colonPrefix = COLON_SPEAKER_REGEX.find(paragraphText)?.groupValues?.get(1)?.trim()
        if (!colonPrefix.isNullOrBlank()) {
            val match = characters.firstOrNull {
                it.name.equals(colonPrefix, ignoreCase = true) ||
                    it.aliases.split(",").any { alias -> alias.trim().equals(colonPrefix, ignoreCase = true) }
            }
            val matchVoice = match?.assignedVoiceId?.takeIf { it.isNotBlank() }
            if (match != null && matchVoice != null) {
                return matchVoice to match.voiceTraits.ifBlank { null }
            }
        }

        val hasQuote = paragraphText.contains('"') || paragraphText.contains('“')
        if (hasQuote) {
            val nonNarrator = characters.firstOrNull { char ->
                !char.name.equals("Narrator", ignoreCase = true) &&
                    (paragraphText.contains(char.name, ignoreCase = true) ||
                        char.aliases.split(",").any { alias ->
                            val trimmed = alias.trim()
                            trimmed.isNotEmpty() && paragraphText.contains(trimmed, ignoreCase = true)
                        })
            }
            val nonNarratorVoice = nonNarrator?.assignedVoiceId?.takeIf { it.isNotBlank() }
            if (nonNarrator != null && nonNarratorVoice != null) {
                return nonNarratorVoice to nonNarrator.voiceTraits.ifBlank { null }
            }
        }
        return defaultNarratorVoice to null
    }

    private fun detectSpeakersHeuristically(paragraphs: List<String>, targetBookId: String): List<Character> {
        val discovered = linkedMapOf<String, Character>()
        for (para in paragraphs) {
            val colonName = COLON_SPEAKER_REGEX.find(para)?.groupValues?.get(1)?.trim()
            if (!colonName.isNullOrBlank() && colonName !in STOPWORD_SPEAKERS) {
                discovered.putIfAbsent(
                    colonName.lowercase(),
                    Character(
                        id = "${targetBookId}_${colonName.lowercase().replace(Regex("[^a-z0-9]"), "_")}",
                        bookId = targetBookId,
                        name = colonName,
                        voiceTraits = "conversational"
                    )
                )
            }
            for (match in AFTER_QUOTE_SPEAKER_REGEX.findAll(para)) {
                val name = match.groupValues[1].trim()
                if (name.isNotBlank() && name !in STOPWORD_SPEAKERS) {
                    discovered.putIfAbsent(
                        name.lowercase(),
                        Character(
                            id = "${targetBookId}_${name.lowercase().replace(Regex("[^a-z0-9]"), "_")}",
                            bookId = targetBookId,
                            name = name,
                            voiceTraits = "expressive"
                        )
                    )
                }
            }
            for (match in BEFORE_QUOTE_SPEAKER_REGEX.findAll(para)) {
                val name = match.groupValues[1].trim()
                if (name.isNotBlank() && name !in STOPWORD_SPEAKERS) {
                    discovered.putIfAbsent(
                        name.lowercase(),
                        Character(
                            id = "${targetBookId}_${name.lowercase().replace(Regex("[^a-z0-9]"), "_")}",
                            bookId = targetBookId,
                            name = name,
                            voiceTraits = "expressive"
                        )
                    )
                }
            }
        }
        return discovered.values.toList()
    }

    companion object {
        private val COLON_SPEAKER_REGEX = Regex("""^([A-Z][a-zA-Z]{1,24}(?:\s+[A-Z][a-zA-Z]{1,20})?):\s+\S""")
        private val AFTER_QUOTE_SPEAKER_REGEX = Regex(
            """["“”][^"“”]{2,}["””]\s*,?\s*(?:said|asked|replied|whispered|shouted|murmured|exclaimed|answered|cried|added|noted|observed|muttered|called)\s+([A-Z][a-zA-Z]{1,24})\b"""
        )
        private val BEFORE_QUOTE_SPEAKER_REGEX = Regex(
            """\b([A-Z][a-zA-Z]{1,24})\s+(?:said|asked|replied|whispered|shouted|murmured|exclaimed|answered|cried|added|noted|observed|muttered|called)\s*[,:]\s*["“]"""
        )
        private val STOPWORD_SPEAKERS = setOf(
            "The", "And", "But", "She", "He", "They", "It", "We", "You", "Chapter", "Page", "Narrator", "This", "That"
        )
    }
}

private object JsonMiniParser {
    fun parse(input: String): Any? {
        val parser = Cursor(input)
        parser.skipWhitespace()
        return if (parser.hasNext()) parser.readValue() else null
    }

    private class Cursor(private val s: String) {
        private var pos = 0

        fun hasNext(): Boolean = pos < s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (!hasNext()) return null
            return when (val ch = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected JSON char '$ch' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (hasNext() && s[pos] == '}') {
                pos++
                return map
            }
            while (hasNext()) {
                skipWhitespace()
                if (s[pos] == '}') {
                    pos++
                    break
                }
                val key = readString()
                skipWhitespace()
                if (!hasNext() || s[pos] != ':') throw IllegalArgumentException("Expected ':' at $pos")
                pos++
                val value = readValue()
                map[key] = value
                skipWhitespace()
                if (hasNext() && s[pos] == ',') {
                    pos++
                } else if (hasNext() && s[pos] == '}') {
                    pos++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
            return map
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (hasNext() && s[pos] == ']') {
                pos++
                return list
            }
            while (hasNext()) {
                skipWhitespace()
                if (s[pos] == ']') {
                    pos++
                    break
                }
                list.add(readValue())
                skipWhitespace()
                if (hasNext() && s[pos] == ',') {
                    pos++
                } else if (hasNext() && s[pos] == ']') {
                    pos++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
            return list
        }

        private fun readString(): String {
            if (!hasNext() || s[pos] != '"') throw IllegalArgumentException("Expected '\"' at $pos")
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) break
                        when (val esc = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 <= s.length) {
                                    val hex = s.substring(pos, pos + 4)
                                    sb.append(hex.toInt(16).toChar())
                                    pos += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun readBoolean(): Boolean {
            return if (s.startsWith("true", pos)) {
                pos += 4
                true
            } else if (s.startsWith("false", pos)) {
                pos += 5
                false
            } else {
                throw IllegalArgumentException("Invalid boolean at $pos")
            }
        }

        private fun readNull(): Any? {
            if (s.startsWith("null", pos)) {
                pos += 4
                return null
            }
            throw IllegalArgumentException("Invalid null at $pos")
        }

        private fun readNumber(): Number {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')) {
                pos++
            }
            val raw = s.substring(start, pos)
            return if (raw.contains('.') || raw.contains('e') || raw.contains('E')) {
                raw.toDouble()
            } else {
                raw.toLong()
            }
        }
    }
}
