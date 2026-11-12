package io.github.danprav04.polyglotbooks.feature.audiobook

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danprav04.polyglotbooks.core.data.CoverExtractor
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
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
    val bookId: String = "",
    val bookTitle: String = "The Power of Vulnerability",
    val author: String = "Brené Brown, PhD",
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 23466000L,
    val bufferAheadMs: Long = 15000L,
    val playbackSpeed: Float = 1.0f,
    val sourceLanguage: LanguageCode = LanguageCode.ENGLISH,
    val targetLanguage: LanguageCode = LanguageCode.RUSSIAN,
    val selectedLanguage: LanguageCode = LanguageCode.RUSSIAN,
    val isDuckingEnabled: Boolean = false,
    val duckLevel: Float = 0.15f,
    val sourceSubtitle: String = "Welcome to The Power of Vulnerability: Teachings on Authenticity, Connection, and Courage.",
    val translatedSubtitle: String = "Добро пожаловать в «Силу уязвимости: Уроки подлинности, связи и мужества».",
    val chapters: List<Chapter> = listOf(
        Chapter(0, "The Power of Vulnerability", 23466000L, 0L)
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
    val showLanguageDropdown: Boolean = false,
    val coverBitmap: android.graphics.Bitmap? = null,
    val coverUri: String? = null,
    val newBookmarkNote: String = ""
)

@HiltViewModel
class AudiobookPlayerViewModel @Inject constructor(
    private val bookDao: BookDao?,
    private val chapterDao: ChapterDao?,
    private val settingsRepository: SettingsRepository?,
    @ApplicationContext private val context: Context?
) : ViewModel() {
    constructor() : this(null, null, null, null)

    private val _uiState = MutableStateFlow(AudiobookPlayerUiState())
    val uiState: StateFlow<AudiobookPlayerUiState> = _uiState.asStateFlow()

    private var exoPlayer: ExoPlayer? = null
    private var positionJob: Job? = null
    private var translationJob: Job? = null
    private var chaptersJob: Job? = null
    private var loadedBookId: String? = null
    private var lastSegmentIndex: Int = -1

    var tts: TextToSpeech? = null
        internal set
    private var isTtsSpeaking: Boolean = false

    private val quotes = listOf(
        Pair(0L, "Welcome to The Power of Vulnerability: Teachings on Authenticity, Connection, and Courage."),
        Pair(15000L, "Good evening everyone. I want to start by asking: what does it mean to be truly vulnerable in our lives?"),
        Pair(60000L, "Vulnerability is not winning or losing; it is having the courage to show up and be seen when we have no control over the outcome."),
        Pair(180000L, "When we numb our vulnerability, we inevitably numb our capacity for joy, gratitude, belonging, and empathy."),
        Pair(300000L, "Authenticity is the daily practice of letting go of who we think we're supposed to be and embracing who we are."),
        Pair(600000L, "Courage starts with showing up and letting ourselves be seen without masks or armor.")
    )

    private val fallbackTranslationsRussian = mapOf(
        0L to "Добро пожаловать в «Силу уязвимости: Уроки подлинности, связи и мужества».",
        15000L to "Добрый вечер всем. Я хочу начать с вопроса: что на самом деле значит быть по-настоящему уязвимым в нашей жизни?",
        60000L to "Уязвимость — это не победа и не поражение; это мужество предстать и быть увиденным, когда мы не властны над исходом.",
        180000L to "Заглушая свою уязвимость, мы неизбежно заглушаем способность испытывать радость, благодарность, единение и сопереживание.",
        300000L to "Подлинность — это ежедневная практика отказа от того, кем мы должны быть по мнению других, в пользу того, кто мы есть на самом деле.",
        600000L to "Мужество начинается с того, чтобы открыться миру и позволить увидеть себя настоящего без масок и брони."
    )

    init {
        viewModelScope.launch {
            settingsRepository?.targetLanguage?.collect { lang ->
                if (_uiState.value.targetLanguage != lang) {
                    setTargetLanguage(lang)
                }
            }
        }
        context?.let { ctx ->
            initTts(ctx)
        }
    }

    fun initTts(ctx: Context) {
        if (tts != null) return
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val ruRu = Locale("ru", "RU")
                    val res = tts?.setLanguage(ruRu)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale("ru"))
                    }
                }
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == "trans_utterance") {
                        isTtsSpeaking = true
                        applyAudioDucking(true)
                    }
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "trans_utterance") {
                        isTtsSpeaking = false
                        applyAudioDucking(false)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == "trans_utterance") {
                        isTtsSpeaking = false
                        applyAudioDucking(false)
                    }
                }
            })
        } catch (_: Exception) {}
    }

    fun speakRussian(text: String) {
        if (!_uiState.value.isLiveTranslationActive) return
        if (_uiState.value.targetLanguage.code != "ru") return
        if (text.isBlank()) return
        isTtsSpeaking = true
        applyAudioDucking(true)
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "trans_utterance")
        } catch (_: Exception) {
            isTtsSpeaking = false
            applyAudioDucking(false)
        }
    }

    fun stopTts() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
        isTtsSpeaking = false
        applyAudioDucking(false)
    }

    fun applyAudioDucking(isSpeaking: Boolean = isTtsSpeaking) {
        val player = exoPlayer ?: return
        val state = _uiState.value
        if (!state.isLiveTranslationActive) {
            player.volume = 1.0f
            return
        }
        if (isSpeaking) {
            player.volume = if (state.isDuckingEnabled) state.duckLevel else 0f
        } else {
            if (state.isDuckingEnabled) {
                player.volume = 1.0f
            } else {
                player.volume = 0f
            }
        }
    }

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

            var coverPath = book.coverUri
            if (coverPath.isNullOrBlank() && context != null && book.filePath.isNotBlank()) {
                coverPath = CoverExtractor.extractCover(
                    context, Uri.parse(book.filePath), book.id
                )
                if (!coverPath.isNullOrBlank()) {
                    bookDao.insertBook(book.copy(coverUri = coverPath))
                }
            }
            if (coverPath.isNullOrBlank() && context != null) {
                val defaultCover = File(context.filesDir, "covers/${book.id}.jpg")
                if (defaultCover.exists()) {
                    coverPath = defaultCover.absolutePath
                }
            }
            val coverBmp = if (!coverPath.isNullOrBlank()) {
                try {
                    BitmapFactory.decodeFile(coverPath)
                } catch (_: Exception) {
                    null
                }
            } else null

            _uiState.value = _uiState.value.copy(
                bookId = bookId,
                bookTitle = book.title,
                author = book.author,
                chapters = mappedChapters,
                currentChapterIndex = 0,
                durationMs = mappedChapters.firstOrNull()?.durationMs ?: 3600000L,
                currentPositionMs = 0L,
                coverBitmap = coverBmp,
                coverUri = coverPath
            )
            updateSubtitlesForPosition(0L)
            setupPlayer(book.filePath)

            // Continuously observe chapter updates in database
            chaptersJob?.cancel()
            chaptersJob = viewModelScope.launch {
                chapterDao?.getChaptersForBook(bookId)?.collect { dbList ->
                    if (dbList.isNotEmpty()) {
                        val updated = dbList.map { ch ->
                            Chapter(
                                index = ch.index,
                                title = ch.title,
                                durationMs = (ch.endMs - ch.startMs).coerceAtLeast(1000L),
                                startMs = ch.startMs
                            )
                        }
                        val curPos = _uiState.value.currentPositionMs
                        val chIdx = updated.indexOfLast { it.startMs <= curPos }.coerceIn(0, updated.size - 1)
                        _uiState.value = _uiState.value.copy(
                            chapters = updated,
                            currentChapterIndex = chIdx
                        )
                    }
                }
            }
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
                            val currentChapters = _uiState.value.chapters
                            val updatedChapters = if (currentChapters.size <= 1) {
                                currentChapters.map { it.copy(durationMs = realDuration) }
                            } else {
                                currentChapters
                            }
                            _uiState.value = _uiState.value.copy(
                                durationMs = realDuration,
                                chapters = updatedChapters
                            )
                        }
                    } else if (playbackState == Player.STATE_ENDED) {
                        _uiState.value = _uiState.value.copy(isPlaying = false)
                        stopTts()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
                    if (!isPlaying) {
                        stopTts()
                    }
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.parse(filePath)))
            player.prepare()
            exoPlayer = player
            applyAudioDucking(isTtsSpeaking)
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
                    val chapters = _uiState.value.chapters
                    val chIdx = if (chapters.isNotEmpty()) {
                        chapters.indexOfLast { it.startMs <= pos }.coerceIn(0, chapters.size - 1)
                    } else {
                        0
                    }
                    if (pos != _uiState.value.currentPositionMs || dur != _uiState.value.durationMs || chIdx != _uiState.value.currentChapterIndex) {
                        _uiState.value = _uiState.value.copy(
                            currentPositionMs = pos.coerceAtMost(dur),
                            durationMs = dur,
                            currentChapterIndex = chIdx
                        )
                    }
                    updateSubtitlesForPosition(pos)
                }
                delay(500L)
            }
        }
    }

    private fun updateChapterForPosition(pos: Long) {
        val chapters = _uiState.value.chapters
        if (chapters.isNotEmpty()) {
            val chIdx = chapters.indexOfLast { it.startMs <= pos }.coerceIn(0, chapters.size - 1)
            if (chIdx != _uiState.value.currentChapterIndex) {
                _uiState.value = _uiState.value.copy(currentChapterIndex = chIdx)
            }
        }
    }

    private fun updateSubtitlesForPosition(pos: Long) {
        val segmentIndex = quotes.indexOfLast { it.first <= pos }.coerceAtLeast(0)
        val segment = quotes[segmentIndex]
        val targetLang = _uiState.value.targetLanguage

        if (segmentIndex != lastSegmentIndex || _uiState.value.sourceSubtitle != segment.second) {
            lastSegmentIndex = segmentIndex
            val sourceText = segment.second
            val fallback = if (targetLang.code == "ru") {
                fallbackTranslationsRussian[segment.first] ?: sourceText
            } else {
                sourceText
            }
            _uiState.value = _uiState.value.copy(
                sourceSubtitle = sourceText,
                translatedSubtitle = fallback
            )
            if (_uiState.value.isPlaying && _uiState.value.isLiveTranslationActive && targetLang.code == "ru") {
                speakRussian(fallback)
            }
            translateWithGemini(sourceText, targetLang)
        }
    }

    private fun translateWithGemini(sourceText: String, targetLang: LanguageCode) {
        translationJob?.cancel()
        translationJob = viewModelScope.launch(Dispatchers.IO) {
            val key = settingsRepository?.apiKey?.firstOrNull()?.trim()
            if (!key.isNullOrBlank()) {
                try {
                    val prompt = "Translate the following audiobook quote into ${targetLang.displayName}. Return ONLY the direct translation with no preamble, quotes, or markdown:\n\n$sourceText"
                    val jsonBody = JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("text", prompt)
                                    })
                                })
                            })
                        })
                    }.toString()

                    val model = settingsRepository?.modelPerRole?.firstOrNull()?.get(ModelRole.TEXT_TRANSLATION)
                        ?: DefaultModels.get(ModelRole.TEXT_TRANSLATION)
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        doOutput = true
                        connectTimeout = 10000
                        readTimeout = 10000
                    }

                    conn.outputStream.use { os ->
                        os.write(jsonBody.toByteArray(Charsets.UTF_8))
                    }

                    if (conn.responseCode in 200..299) {
                        val respString = conn.inputStream.bufferedReader().use { it.readText() }
                        val respJson = JSONObject(respString)
                        val candidates = respJson.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val text = candidates.getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text")
                                .trim()
                                .removeSurrounding("\"")
                                .removeSurrounding("**")
                            if (text.isNotBlank()) {
                                _uiState.value = _uiState.value.copy(translatedSubtitle = text)
                                if (_uiState.value.isPlaying && _uiState.value.isLiveTranslationActive && targetLang.code == "ru") {
                                    speakRussian(text)
                                }
                            }
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    // Fallback remains active
                }
            }
        }
    }

    fun togglePlayPause() {
        val player = exoPlayer
        if (player != null) {
            if (player.isPlaying) {
                player.pause()
                stopTts()
            } else {
                player.play()
                if (_uiState.value.isLiveTranslationActive && _uiState.value.targetLanguage.code == "ru") {
                    speakRussian(_uiState.value.translatedSubtitle)
                }
            }
        } else {
            val willPlay = !_uiState.value.isPlaying
            _uiState.value = _uiState.value.copy(isPlaying = willPlay)
            if (!willPlay) {
                stopTts()
            } else {
                if (_uiState.value.isLiveTranslationActive && _uiState.value.targetLanguage.code == "ru") {
                    speakRussian(_uiState.value.translatedSubtitle)
                }
            }
        }
    }

    fun seekBy(deltaMs: Long) {
        val newPos = (_uiState.value.currentPositionMs + deltaMs).coerceIn(0L, _uiState.value.durationMs)
        exoPlayer?.seekTo(newPos)
        _uiState.value = _uiState.value.copy(currentPositionMs = newPos)
        updateChapterForPosition(newPos)
        updateSubtitlesForPosition(newPos)
    }

    fun seekTo(positionMs: Long) {
        val newPos = positionMs.coerceIn(0L, _uiState.value.durationMs)
        exoPlayer?.seekTo(newPos)
        _uiState.value = _uiState.value.copy(currentPositionMs = newPos)
        updateChapterForPosition(newPos)
        updateSubtitlesForPosition(newPos)
    }

    fun previousChapter() {
        val current = _uiState.value
        val chapters = current.chapters
        if (chapters.isEmpty()) return
        val prevIndex = (current.currentChapterIndex - 1).coerceAtLeast(0)
        selectChapter(prevIndex)
    }

    fun nextChapter() {
        val current = _uiState.value
        val chapters = current.chapters
        if (chapters.isEmpty()) return
        val nextIndex = (current.currentChapterIndex + 1).coerceAtMost(chapters.size - 1)
        selectChapter(nextIndex)
    }

    fun setLanguage(language: LanguageCode) {
        setTargetLanguage(language)
    }

    fun setTargetLanguage(language: LanguageCode) {
        _uiState.value = _uiState.value.copy(
            targetLanguage = language,
            selectedLanguage = language,
            showLanguageDropdown = false
        )
        viewModelScope.launch {
            settingsRepository?.setTargetLanguage(language)
        }
        val currentQuote = _uiState.value.sourceSubtitle
        if (currentQuote.isNotBlank()) {
            val fallback = if (language.code == "ru") {
                val currentSeg = quotes.firstOrNull { it.second == currentQuote }
                fallbackTranslationsRussian[currentSeg?.first ?: 0L] ?: currentQuote
            } else {
                currentQuote
            }
            _uiState.value = _uiState.value.copy(translatedSubtitle = fallback)
            if (_uiState.value.isPlaying && _uiState.value.isLiveTranslationActive && language.code == "ru") {
                speakRussian(fallback)
            }
            translateWithGemini(currentQuote, language)
        }
    }

    fun setLanguageDropdownVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(showLanguageDropdown = visible)
    }

    fun toggleDucking() {
        _uiState.value = _uiState.value.copy(isDuckingEnabled = !_uiState.value.isDuckingEnabled)
        applyAudioDucking(isTtsSpeaking)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        exoPlayer?.playbackParameters = PlaybackParameters(clamped)
        _uiState.value = _uiState.value.copy(playbackSpeed = clamped)
    }

    fun toggleLiveTranslation() {
        val newLiveTranslation = !_uiState.value.isLiveTranslationActive
        _uiState.value = _uiState.value.copy(isLiveTranslationActive = newLiveTranslation)
        if (!newLiveTranslation) {
            stopTts()
            exoPlayer?.volume = 1.0f
        } else {
            applyAudioDucking(isTtsSpeaking)
            if (_uiState.value.isPlaying && _uiState.value.targetLanguage.code == "ru") {
                speakRussian(_uiState.value.translatedSubtitle)
            }
        }
    }

    fun setChapterListSheetVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(showChapterListSheet = visible)
    }

    fun setSleepTimerDialogVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(showSleepTimerDialog = visible)
    }

    fun setBookmarksDialogVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(showBookmarksDialog = visible)
    }

    fun setAiReportDialogVisible(visible: Boolean) {
        _uiState.value = _uiState.value.copy(showAiReportDialog = visible)
    }

    fun setAddBookmarkDialogVisible(visible: Boolean) {
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
                currentPositionMs = chapter.startMs,
                showChapterListSheet = false
            )
        }
        updateSubtitlesForPosition(chapter.startMs)
    }

    override fun onCleared() {
        positionJob?.cancel()
        translationJob?.cancel()
        chaptersJob?.cancel()
        stopTts()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
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
