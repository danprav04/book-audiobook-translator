package io.github.danprav04.polyglotbooks.core.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.danprav04.polyglotbooks.core.data.CoverExtractor
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterEntity
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateDao
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.model.Chapter
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Complete audiobook playback & live translation state exposed by [AudiobookSessionManager]
 * and [PolyglotPlaybackService].
 */
data class AudiobookServiceState(
    val bookId: String = "",
    val bookTitle: String = "",
    val author: String = "",
    val bookFormat: String = "",
    val coverUri: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val isLiveTranslationActive: Boolean = true,
    val isDuckingEnabled: Boolean = false,
    val targetLanguage: LanguageCode = LanguageCode.RUSSIAN,
    val sourceSubtitle: String = "",
    val targetSubtitle: String = "",
    val bufferAheadMs: Long = 0L,
    val sleepTimerMinutesRemaining: Int = 0,
    val errorMessage: String? = null,
    val isLoading: Boolean = false,
    val isPrepared: Boolean = false
) {
    val translatedSubtitle: String
        get() = targetSubtitle

    val sleepTimerRemainingMs: Long
        get() = sleepTimerMinutesRemaining * 60_000L
}

/**
 * Singleton session coordinator bridging [PolyglotPlaybackService] and `AudiobookPlayerViewModel`.
 *
 * Owns the canonical [AudiobookServiceState], orchestrates [LiveTranslationBackend], [SleepTimer],
 * volume ducking, reactive settings updates, pre-seek `0L` overwrite protection, 2-second pause
 * rewind (> 60s explicit pause), periodic 2000ms checkpoints, and non-cancellable flushes.
 */
@Singleton
@OptIn(UnstableApi::class)
class AudiobookSessionManager @Inject constructor(
    private val bookDao: BookDao?,
    private val chapterDao: ChapterDao?,
    private val playbackStateDao: PlaybackStateDao?,
    private val settingsRepository: SettingsRepository?,
    private val liveTranslationBackend: LiveTranslationBackend,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @ApplicationContext private val context: Context?,
    private val playbackCoordinator: PlaybackCoordinator? = null
) {
    constructor(
        bookDao: BookDao? = null,
        chapterDao: ChapterDao? = null,
        playbackStateDao: PlaybackStateDao? = null,
        settingsRepository: SettingsRepository? = null,
        liveTranslationBackend: LiveTranslationBackend = NoOpLiveTranslationBackend(),
        applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    ) : this(
        bookDao = bookDao,
        chapterDao = chapterDao,
        playbackStateDao = playbackStateDao,
        settingsRepository = settingsRepository,
        liveTranslationBackend = liveTranslationBackend,
        applicationScope = applicationScope,
        context = null,
        playbackCoordinator = null
    )
    private val _state = MutableStateFlow(AudiobookServiceState())
    val state: StateFlow<AudiobookServiceState> = _state.asStateFlow()

    val sleepTimer = SleepTimer(applicationScope) {
        pause()
    }

    var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var attachedService: PolyglotPlaybackService? = null

    @Volatile
    private var exoPlayer: ExoPlayer? = null

    @Volatile
    private var pendingBookId: String? = null

    @Volatile
    private var playerPreparedForBookId: String? = null

    @Volatile
    var currentFilePath: String = ""
        private set

    @Volatile
    var isInitialSeekPending: Boolean = false
        private set

    @Volatile
    var pendingRestorePositionMs: Long = 0L
        private set

    @Volatile
    var lastPauseTimestamp: Long = 0L
        internal set

    @Volatile
    private var currentDuckLevel: Float = DEFAULT_DUCK_LEVEL

    @Volatile
    private var currentApiKey: String? = null

    @Volatile
    private var currentLiveTranslateModel: String = DefaultModels.get(ModelRole.LIVE_TRANSLATE)

    @Volatile
    private var currentEchoTargetLanguage: Boolean = false

    @Volatile
    private var hasCollectedApiKey: Boolean = false

    @Volatile
    private var lastBackendErrorMessage: String? = null

    @Volatile
    private var internalSeekTargetMs: Long = -1L

    @Volatile
    private var internalSeekTimestampMs: Long = 0L

    @Volatile
    private var pendingUserSeekTargetMs: Long = -1L

    @Volatile
    private var pendingUserSeekUntilMs: Long = 0L

    private data class AudioProgressSnapshot(
        val bookId: String,
        val chapterIndex: Int,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long,
        val pauseTimestamp: Long? = null
    )

    private data class AudioSettingsSnapshot(
        val bookId: String,
        val chapterIndex: Int,
        val positionMs: Long,
        val durationMs: Long,
        val playbackSpeed: Float,
        val isLiveTranslationActive: Boolean,
        val isDuckingEnabled: Boolean,
        val targetLanguageCode: String,
        val updatedAt: Long
    )

    @Volatile
    private var isBookHandoffInProgress: Boolean = false

    private val pendingPersistJobs = ConcurrentHashMap<String, Job>()

    private var loadBookJob: Job? = null
    private var chaptersObserverJob: Job? = null
    private var positionPollingJob: Job? = null
    private var checkpointJob: Job? = null
    private var seekRestartJob: Job? = null

    private fun markInternalSeek(targetPosMs: Long) {
        internalSeekTargetMs = targetPosMs
        internalSeekTimestampMs = clock()
    }

    private fun isRecentInternalSeek(posMs: Long): Boolean {
        val target = internalSeekTargetMs
        if (target < 0L) return false
        val ageMs = kotlin.math.abs(clock() - internalSeekTimestampMs)
        return ageMs <= INTERNAL_SEEK_WINDOW_MS && kotlin.math.abs(posMs - target) <= SEEK_TOLERANCE_MS
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isBookHandoffInProgress) {
                if (!isPlaying && _state.value.isPlaying) {
                    _state.update { it.copy(isPlaying = false) }
                }
                attachedService?.updateStreamingLocks()
                return
            }

            val prevPlaying = _state.value.isPlaying
            _state.update { it.copy(isPlaying = isPlaying) }
            attachedService?.updateStreamingLocks()

            if (isPlaying) {
                val now = clock()
                lastPauseTimestamp = 0L
                val bookId = _state.value.bookId
                if (bookId.isNotBlank()) {
                    enqueueBookPersist(bookId) {
                        try {
                            playbackStateDao?.saveLastPauseTimestamp(bookId, 0L, now)
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (!prevPlaying) {
                    syncLiveTranslationOnPlay()
                    startPositionPolling()
                    startPeriodicCheckpointing()
                }
            } else {
                stopPeriodicCheckpointing()
                if (liveTranslationBackend.state.value.isRunning) {
                    liveTranslationBackend.pauseAudio()
                }
                if (prevPlaying) {
                    val now = clock()
                    if (lastPauseTimestamp == 0L) {
                        lastPauseTimestamp = now
                    }
                    flushProgressNow(pauseTimestamp = lastPauseTimestamp)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isBookHandoffInProgress) return
            val player = exoPlayer ?: return
            when (playbackState) {
                Player.STATE_READY -> {
                    val rawDuration = try {
                        player.duration
                    } catch (_: Throwable) {
                        C.TIME_UNSET
                    }
                    if (rawDuration != C.TIME_UNSET && rawDuration > 0L) {
                        var updatedLastChapter: Chapter? = null
                        var bookIdToPersist = ""
                        var prevDuration = 0L
                        _state.update { prev ->
                            prevDuration = prev.durationMs
                            bookIdToPersist = prev.bookId
                            val updatedChapters = if (prev.chapters.isNotEmpty()) {
                                val last = prev.chapters.last()
                                if (prev.chapters.size == 1 || (last.startMs < rawDuration && last.endMs != rawDuration)) {
                                    val fixedLast = last.copy(endMs = rawDuration)
                                    updatedLastChapter = fixedLast
                                    prev.chapters.dropLast(1) + fixedLast
                                } else {
                                    prev.chapters
                                }
                            } else {
                                prev.chapters
                            }
                            prev.copy(
                                durationMs = rawDuration,
                                chapters = updatedChapters
                            )
                        }
                        if (bookIdToPersist.isNotBlank() && (updatedLastChapter != null || prevDuration != rawDuration)) {
                            val snapshot = _state.value
                            val chapterEntity = updatedLastChapter?.let { ChapterEntity.fromDomain(it) }
                            val now = clock()
                            enqueueBookPersist(bookIdToPersist) {
                                if (chapterEntity != null) {
                                    try {
                                        chapterDao?.insertChapters(listOf(chapterEntity))
                                    } catch (_: Throwable) {
                                    }
                                }
                                try {
                                    playbackStateDao?.saveAudioProgress(
                                        bookId = bookIdToPersist,
                                        chapterIndex = snapshot.currentChapterIndex,
                                        positionMs = snapshot.currentPositionMs,
                                        durationMs = rawDuration,
                                        updatedAt = now
                                    )
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }

                    if (isInitialSeekPending) {
                        val playerPos = try {
                            player.currentPosition.coerceAtLeast(0L)
                        } catch (_: Throwable) {
                            0L
                        }
                        if (pendingRestorePositionMs > 0L && playerPos < pendingRestorePositionMs - SEEK_TOLERANCE_MS) {
                            try {
                                markInternalSeek(pendingRestorePositionMs)
                                player.seekTo(pendingRestorePositionMs)
                            } catch (_: Throwable) {
                            }
                        } else {
                            isInitialSeekPending = false
                        }
                    }

                    _state.update { it.copy(isPrepared = true, isLoading = false) }
                    pollPlayerPositionOnce()
                }

                Player.STATE_ENDED -> {
                    stopPeriodicCheckpointing()
                    _state.update { it.copy(isPlaying = false) }
                    if (liveTranslationBackend.state.value.isRunning) {
                        liveTranslationBackend.pauseAudio()
                    }
                    attachedService?.updateStreamingLocks()
                    flushProgressNow()
                }

                else -> Unit
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (isBookHandoffInProgress) return
            val newPosMs = newPosition.positionMs.coerceAtLeast(0L)
            if (isInitialSeekPending && pendingRestorePositionMs > 0L && newPosMs < pendingRestorePositionMs - SEEK_TOLERANCE_MS) {
                // Guard against pre-seek 0L discontinuity before initial restore seek finishes
                return
            }
            if (isInitialSeekPending && newPosMs >= pendingRestorePositionMs - SEEK_TOLERANCE_MS) {
                isInitialSeekPending = false
            }

            val prevChapterIdx = _state.value.currentChapterIndex
            val newChapterIdx = findChapterIndex(_state.value.chapters, newPosMs)
            val isExternalSeek = (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) &&
                !isRecentInternalSeek(newPosMs)

            _state.update { prev ->
                val dur = prev.durationMs
                prev.copy(
                    currentPositionMs = if (dur > 0L) newPosMs.coerceAtMost(dur) else newPosMs,
                    currentChapterIndex = newChapterIdx,
                    sourceSubtitle = if (isExternalSeek) "" else prev.sourceSubtitle,
                    targetSubtitle = if (isExternalSeek) "" else prev.targetSubtitle,
                    bufferAheadMs = if (isExternalSeek) 0L else prev.bufferAheadMs
                )
            }
            if (isExternalSeek && _state.value.isLiveTranslationActive) {
                restartLiveTranslationOnSeek(newPosMs)
            } else {
                liveTranslationBackend.updatePlaybackPosition(newPosMs)
            }
            if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT ||
                reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION ||
                newChapterIdx != prevChapterIdx
            ) {
                flushProgressNow()
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (isBookHandoffInProgress) return
            val clamped = playbackParameters.speed.coerceIn(MIN_SPEED, MAX_SPEED)
            if (_state.value.playbackSpeed != clamped) {
                _state.update { it.copy(playbackSpeed = clamped) }
                liveTranslationBackend.setPlaybackSpeed(clamped)
                flushSettingsAndProgressNow()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isBookHandoffInProgress) return
            stopPeriodicCheckpointing()
            val errorText = error.message ?: "Playback error: ${error.errorCodeName}"
            DiagnosticLogBuffer.record(
                tag = "AudiobookSession",
                message = "ExoPlayer error (${error.errorCodeName}): $errorText",
                level = "ERROR",
                throwable = error
            )
            _state.update {
                it.copy(
                    isPlaying = false,
                    isLoading = false,
                    errorMessage = errorText
                )
            }
            if (liveTranslationBackend.state.value.isRunning) {
                liveTranslationBackend.pauseAudio()
            }
            attachedService?.updateStreamingLocks()
            flushProgressNow()
        }
    }

    init {
        playbackCoordinator?.onPauseAudiobook = {
            val isExoPlaying = try {
                exoPlayer?.isPlaying == true
            } catch (_: Throwable) {
                false
            }
            if (_state.value.isPlaying || isExoPlaying) {
                pause()
            }
        }
        observeBackendState()
        observeSleepTimer()
        observeSettings()
    }

    private fun observeBackendState() {
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var lastLoggedBackendError: String? = null
            liveTranslationBackend.state.collect { backendState ->
                val backendErr = backendState.errorMessage
                if (backendErr != null && backendErr != lastLoggedBackendError) {
                    lastLoggedBackendError = backendErr
                    DiagnosticLogBuffer.record(
                        tag = "AudiobookSession",
                        message = "Live translation backend error: $backendErr",
                        level = "WARN"
                    )
                } else if (backendErr == null) {
                    lastLoggedBackendError = null
                }
                var shouldPauseForBackendError = false
                _state.update { prev ->
                    val nextError = when {
                        backendErr != null -> {
                            lastBackendErrorMessage = backendErr
                            backendErr
                        }
                        backendState.isRunning || prev.errorMessage == lastBackendErrorMessage -> {
                            lastBackendErrorMessage = null
                            null
                        }
                        else -> prev.errorMessage
                    }
                    if (settingsRepository != null &&
                        backendErr != null &&
                        !backendState.isRunning &&
                        prev.isPlaying &&
                        prev.isLiveTranslationActive
                    ) {
                        shouldPauseForBackendError = true
                    }
                    prev.copy(
                        bufferAheadMs = backendState.bufferAheadMs,
                        sourceSubtitle = if (backendState.currentSourceSubtitle.isNotBlank()) {
                            backendState.currentSourceSubtitle
                        } else {
                            prev.sourceSubtitle
                        },
                        targetSubtitle = if (backendState.currentTranslatedSubtitle.isNotBlank()) {
                            backendState.currentTranslatedSubtitle
                        } else {
                            prev.targetSubtitle
                        },
                        errorMessage = nextError,
                        isPlaying = if (shouldPauseForBackendError) false else prev.isPlaying
                    )
                }
                if (shouldPauseForBackendError) {
                    stopPeriodicCheckpointing()
                    postToPlayerThread { it.pause() }
                    flushProgressNow()
                }
                attachedService?.updateStreamingLocks()
            }
        }
    }

    private fun observeSleepTimer() {
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sleepTimer.remainingTimeMs.collect { remainingMs ->
                val isRunning = sleepTimer.isTimerRunning.value
                val minutes = if (!isRunning || remainingMs <= 0L) {
                    0
                } else {
                    ((remainingMs + 59_999L) / 60_000L).toInt()
                }
                _state.update { it.copy(sleepTimerMinutesRemaining = minutes) }
            }
        }
    }

    private fun observeSettings() {
        val repo = settingsRepository ?: return

        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repo.duckLevel.collect { level ->
                val clamped = level.coerceIn(0f, 1f)
                currentDuckLevel = clamped
                applyVolumeDucking()
            }
        }

        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repo.apiKey.collect { key ->
                hasCollectedApiKey = true
                val prevKey = currentApiKey
                currentApiKey = key
                if (!key.isNullOrBlank()) {
                    val curErr = _state.value.errorMessage
                    if (curErr == NoOpLiveTranslationBackend.MISSING_API_KEY_MESSAGE ||
                        curErr == lastBackendErrorMessage
                    ) {
                        lastBackendErrorMessage = null
                        _state.update { it.copy(errorMessage = null) }
                    }
                }
                if (prevKey != key && !key.isNullOrBlank()) {
                    val s = _state.value
                    if (s.isPlaying && s.isLiveTranslationActive && currentFilePath.isNotBlank()) {
                        val pos = withPlayerThread { getEffectivePositionMs() }
                        startLiveTranslationPipeline(pos)
                    }
                }
            }
        }

        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repo.modelPerRole.collect { models ->
                val newModel = models[ModelRole.LIVE_TRANSLATE] ?: DefaultModels.get(ModelRole.LIVE_TRANSLATE)
                val changed = currentLiveTranslateModel != newModel
                currentLiveTranslateModel = newModel
                if (changed && liveTranslationBackend.state.value.isRunning) {
                    val s = _state.value
                    if (s.isPlaying && s.isLiveTranslationActive && currentFilePath.isNotBlank()) {
                        val pos = withPlayerThread { getEffectivePositionMs() }
                        startLiveTranslationPipeline(pos)
                    }
                }
            }
        }

        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repo.echoTargetLanguage.collect { echo ->
                val changed = currentEchoTargetLanguage != echo
                currentEchoTargetLanguage = echo
                if (changed && liveTranslationBackend.state.value.isRunning) {
                    val s = _state.value
                    if (s.isPlaying && s.isLiveTranslationActive && currentFilePath.isNotBlank()) {
                        val pos = withPlayerThread { getEffectivePositionMs() }
                        startLiveTranslationPipeline(pos)
                    }
                }
            }
        }

        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repo.targetLanguage.collect { lang ->
                if (_state.value.bookId.isBlank()) {
                    _state.update { it.copy(targetLanguage = lang) }
                }
            }
        }
    }

    /**
     * Attaches the active [PolyglotPlaybackService] instance and its [ExoPlayer].
     * If a `loadBook(bookId)` call was queued while the service was starting/binding,
     * it is executed or prepared immediately.
     */
    fun attachService(service: PolyglotPlaybackService) {
        attachedService = service
        service.exoPlayer?.let { attachPlayer(it) }

        val queuedBookId = pendingBookId
        if (!queuedBookId.isNullOrBlank()) {
            pendingBookId = null
            val current = _state.value
            if (current.bookId == queuedBookId && !current.isLoading && currentFilePath.isNotBlank() && exoPlayer != null) {
                preparePlayerForCurrentBook(exoPlayer!!)
            } else {
                loadBook(queuedBookId)
            }
        } else {
            val current = _state.value
            if (current.bookId.isNotBlank() && playerPreparedForBookId != current.bookId && currentFilePath.isNotBlank() && exoPlayer != null) {
                preparePlayerForCurrentBook(exoPlayer!!)
            }
        }
        service.updateStreamingLocks()
    }

    /**
     * Detaches the [PolyglotPlaybackService] when destroyed.
     */
    fun detachService(service: PolyglotPlaybackService) {
        if (attachedService === service) {
            service.exoPlayer?.let { detachPlayer(it) }
            attachedService = null
        }
    }

    /**
     * Attaches an [ExoPlayer] directly (called by [attachService] or in unit tests).
     */
    fun attachPlayer(player: ExoPlayer) {
        if (exoPlayer === player) return
        exoPlayer?.let { old ->
            postToPlayerThread(old) { it.removeListener(playerListener) }
        }
        exoPlayer = player
        postToPlayerThread(player) { it.addListener(playerListener) }
        applyVolumeDucking()

        val queuedBookId = pendingBookId
        if (!queuedBookId.isNullOrBlank() && _state.value.bookId == queuedBookId && !_state.value.isLoading && currentFilePath.isNotBlank()) {
            pendingBookId = null
            postToPlayerThread(player) { p -> preparePlayerForCurrentBook(p) }
        }
    }

    /**
     * Detaches the current [ExoPlayer].
     */
    fun detachPlayer(player: ExoPlayer? = null) {
        val target = player ?: exoPlayer ?: return
        if (exoPlayer === target) {
            postToPlayerThread(target) { it.removeListener(playerListener) }
            exoPlayer = null
            playerPreparedForBookId = null
            isBookHandoffInProgress = false
            _state.update { it.copy(isPrepared = false, isPlaying = false) }
        }
    }

    /**
     * Loads the given audiobook by [bookId] and restores its persisted position, chapter,
     * speed, live translation toggle, ducking toggle, and target language before playback starts.
     *
     * Performs a NO-OP if [bookId] is already loaded and prepared in the service.
     */
    fun loadBook(bookId: String) {
        if (bookId.isBlank()) return

        val current = _state.value
        if (current.bookId == bookId && current.isPrepared) {
            // NO-OP reset if bookId is already loaded and prepared!
            return
        }

        // If switching away from an already loaded book, synchronously snapshot & persist its
        // progress BEFORE _state.update replaces bookId, and suppress stale ExoPlayer callbacks
        // during the handoff before the new book's MediaItem is set.
        if (current.bookId.isNotBlank() && current.bookId != bookId) {
            cancelPendingSeekRestart()

            val shouldPersistOldBook = !current.isLoading || current.isPrepared
            val oldSnapshot = if (shouldPersistOldBook) {
                val now = clock()
                val snapshotPosMs = getEffectivePositionMs()
                val snapshotChapterIdx = findChapterIndex(current.chapters, snapshotPosMs)
                val snapshotPauseTimestamp = if (current.isPlaying) {
                    now
                } else {
                    lastPauseTimestamp.takeIf { it > 0L }
                }
                AudioProgressSnapshot(
                    bookId = current.bookId,
                    chapterIndex = snapshotChapterIdx,
                    positionMs = snapshotPosMs,
                    durationMs = current.durationMs,
                    updatedAt = now,
                    pauseTimestamp = snapshotPauseTimestamp
                )
            } else {
                null
            }

            isBookHandoffInProgress = true
            lastPauseTimestamp = 0L
            isInitialSeekPending = false
            pendingRestorePositionMs = 0L
            pendingUserSeekTargetMs = -1L
            internalSeekTargetMs = -1L
            playerPreparedForBookId = null
            currentFilePath = ""

            liveTranslationBackend.stop()
            stopPositionPolling()
            stopPeriodicCheckpointing()
            oldSnapshot?.let { persistAudioSnapshot(it) }
            postToPlayerThread { it.pause() }
        }

        if (attachedService == null && exoPlayer == null) {
            pendingBookId = bookId
            startPlaybackServiceIfPossible()
            if (bookDao == null) {
                isBookHandoffInProgress = false
                _state.update {
                    if (it.bookId != bookId) {
                        it.copy(
                            bookId = bookId,
                            chapters = emptyList(),
                            currentChapterIndex = 0,
                            currentPositionMs = 0L,
                            durationMs = 0L,
                            isPlaying = false,
                            sourceSubtitle = "",
                            targetSubtitle = "",
                            bufferAheadMs = 0L,
                            isLoading = true,
                            isPrepared = false,
                            errorMessage = null
                        )
                    } else {
                        it.copy(
                            bookId = bookId,
                            isPlaying = false,
                            isLoading = true,
                            isPrepared = false,
                            errorMessage = null
                        )
                    }
                }
                attachedService?.updateStreamingLocks()
                return
            }
        }

        loadBookJob?.cancel()
        loadBookJob = applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            _state.update {
                if (it.bookId != bookId) {
                    it.copy(
                        bookId = bookId,
                        chapters = emptyList(),
                        currentChapterIndex = 0,
                        currentPositionMs = 0L,
                        durationMs = 0L,
                        isPlaying = false,
                        sourceSubtitle = "",
                        targetSubtitle = "",
                        bufferAheadMs = 0L,
                        isLoading = true,
                        isPrepared = false,
                        errorMessage = null
                    )
                } else {
                    it.copy(
                        bookId = bookId,
                        isPlaying = false,
                        isLoading = true,
                        isPrepared = false,
                        errorMessage = null
                    )
                }
            }
            attachedService?.updateStreamingLocks()

            val book = try {
                bookDao?.getBookById(bookId)
            } catch (e: Throwable) {
                DiagnosticLogBuffer.record(
                    tag = "AudiobookSession",
                    message = "Failed querying book id=$bookId",
                    level = "ERROR",
                    throwable = e
                )
                null
            }

            if (bookDao != null && book == null) {
                if (exoPlayer != null) {
                    postToPlayerThread { isBookHandoffInProgress = false }
                } else {
                    isBookHandoffInProgress = false
                }
                DiagnosticLogBuffer.record(
                    tag = "AudiobookSession",
                    message = "Book not found id=$bookId",
                    level = "WARN"
                )
                _state.update {
                    it.copy(
                        bookId = bookId,
                        isPlaying = false,
                        isLoading = false,
                        isPrepared = false,
                        errorMessage = "Book not found: $bookId"
                    )
                }
                return@launch
            }

            val filePath = book?.filePath ?: ""
            currentFilePath = filePath

            val dbChapters = try {
                chapterDao?.getChaptersForBook(bookId)?.firstOrNull().orEmpty()
            } catch (_: Throwable) {
                emptyList()
            }

            val mappedChapters = if (dbChapters.isNotEmpty()) {
                dbChapters.map { it.toDomain() }
            } else if (book != null) {
                listOf(
                    Chapter(
                        id = "${book.id}_ch_0",
                        bookId = book.id,
                        index = 0,
                        title = book.title,
                        startMs = 0L,
                        endMs = DEFAULT_DURATION_MS
                    )
                )
            } else {
                emptyList()
            }

            var coverPath = book?.coverUri
            if (coverPath.isNullOrBlank() && context != null && filePath.isNotBlank() && book != null) {
                try {
                    coverPath = CoverExtractor.extractCover(context, Uri.parse(filePath), book.id)
                    if (!coverPath.isNullOrBlank()) {
                        bookDao?.insertBook(book.copy(coverUri = coverPath))
                    }
                } catch (_: Throwable) {
                }
            }
            if (coverPath.isNullOrBlank() && context != null && book != null) {
                val defaultCover = File(context.filesDir, "covers/${book.id}.jpg")
                if (defaultCover.exists()) {
                    coverPath = defaultCover.absolutePath
                }
            }

            awaitPendingPersist(bookId)
            val savedState = try {
                playbackStateDao?.getPlaybackState(bookId)
            } catch (_: Throwable) {
                null
            }

            val now = clock()
            val shouldRewindFromExplicitPause = savedState != null &&
                savedState.lastPauseTimestamp > 0L &&
                (now - savedState.lastPauseTimestamp) > AUTO_REWIND_THRESHOLD_MS

            val restoredPositionMs = when {
                savedState == null -> 0L
                shouldRewindFromExplicitPause -> (savedState.positionMs - AUTO_REWIND_MS).coerceAtLeast(0L)
                else -> savedState.positionMs.coerceAtLeast(0L)
            }

            lastPauseTimestamp = if (shouldRewindFromExplicitPause) {
                0L
            } else {
                savedState?.lastPauseTimestamp ?: 0L
            }

            val restoredSpeed = (savedState?.playbackSpeed ?: 1.0f).coerceIn(MIN_SPEED, MAX_SPEED)
            val restoredLiveTranslation = savedState?.isLiveTranslationActive ?: true
            val restoredDucking = savedState?.isDuckingEnabled ?: false

            val defaultTargetLang = try {
                settingsRepository?.targetLanguage?.firstOrNull()
            } catch (_: Throwable) {
                null
            } ?: _state.value.targetLanguage

            val restoredTargetLanguage = if (!savedState?.targetLanguageCode.isNullOrBlank()) {
                LanguageCode.fromCode(savedState!!.targetLanguageCode!!)
            } else {
                defaultTargetLang
            }

            val fallbackDuration = mappedChapters.maxOfOrNull { it.endMs }?.takeIf { it > 0L } ?: DEFAULT_DURATION_MS
            val restoredDurationMs = if (savedState != null && savedState.durationMs > 0L) {
                savedState.durationMs
            } else {
                fallbackDuration
            }

            val restoredChapterIndex = when {
                mappedChapters.isEmpty() -> savedState?.chapterIndex?.coerceAtLeast(0) ?: 0
                savedState != null && restoredPositionMs == 0L && savedState.chapterIndex in mappedChapters.indices -> {
                    savedState.chapterIndex
                }
                else -> findChapterIndex(mappedChapters, restoredPositionMs)
            }

            // Activate pre-seek guard BEFORE touching ExoPlayer or starting polling!
            pendingRestorePositionMs = restoredPositionMs
            isInitialSeekPending = restoredPositionMs > 0L

            val player = exoPlayer
            val canPrepareNow = player != null || context == null

            _state.update {
                it.copy(
                    bookId = bookId,
                    bookTitle = book?.title ?: it.bookTitle,
                    author = book?.author ?: it.author,
                    bookFormat = book?.format.orEmpty(),
                    coverUri = coverPath,
                    chapters = mappedChapters,
                    currentChapterIndex = restoredChapterIndex,
                    currentPositionMs = restoredPositionMs,
                    durationMs = restoredDurationMs,
                    isPlaying = false,
                    playbackSpeed = restoredSpeed,
                    isLiveTranslationActive = restoredLiveTranslation,
                    isDuckingEnabled = restoredDucking,
                    targetLanguage = restoredTargetLanguage,
                    sourceSubtitle = "",
                    targetSubtitle = "",
                    bufferAheadMs = 0L,
                    errorMessage = null,
                    isLoading = false,
                    isPrepared = canPrepareNow
                )
            }

            withContext(NonCancellable) {
                try {
                    settingsRepository?.setLastOpenedBookId(bookId)
                } catch (_: Throwable) {
                }
                try {
                    bookDao?.updateLastReadDate(bookId, now)
                } catch (_: Throwable) {
                }
                if (shouldRewindFromExplicitPause) {
                    try {
                        playbackStateDao?.saveAudioProgress(
                            bookId = bookId,
                            chapterIndex = restoredChapterIndex,
                            positionMs = restoredPositionMs,
                            durationMs = restoredDurationMs,
                            updatedAt = now
                        )
                        playbackStateDao?.saveLastPauseTimestamp(bookId, 0L, now)
                    } catch (_: Throwable) {
                    }
                }
            }

            if (player != null) {
                pendingBookId = null
                withPlayerThread(player) {
                    preparePlayerForCurrentBook(player)
                }
            } else {
                isBookHandoffInProgress = false
            }

            observeChaptersForBook(bookId)
        }
    }

    private fun preparePlayerForCurrentBook(player: ExoPlayer) {
        val s = _state.value
        val targetPos = s.currentPositionMs.coerceAtLeast(0L)
        pendingRestorePositionMs = targetPos
        isInitialSeekPending = targetPos > 0L
        markInternalSeek(targetPos)

        postToPlayerThread(player) { p ->
            try {
                p.playbackParameters = PlaybackParameters(s.playbackSpeed.coerceIn(MIN_SPEED, MAX_SPEED))
            } catch (_: Throwable) {
            }
            p.volume = computeTargetVolume(
                isLiveTranslationActive = s.isLiveTranslationActive,
                isDuckingEnabled = s.isDuckingEnabled,
                duckLevel = currentDuckLevel
            )

            if (currentFilePath.isNotBlank()) {
                try {
                    val metadataBuilder = MediaMetadata.Builder()
                        .setTitle(s.bookTitle)
                        .setArtist(s.author)
                    if (!s.coverUri.isNullOrBlank()) {
                        metadataBuilder.setArtworkUri(Uri.parse(s.coverUri))
                    }
                    val mediaItem = MediaItem.Builder()
                        .setMediaId(s.bookId)
                        .setUri(Uri.parse(currentFilePath))
                        .setMediaMetadata(metadataBuilder.build())
                        .build()

                    p.setMediaItem(mediaItem, targetPos)
                    if (targetPos > 0L) {
                        p.seekTo(targetPos)
                    }
                    isBookHandoffInProgress = false
                    p.prepare()
                } catch (t: Throwable) {
                    isBookHandoffInProgress = false
                    DiagnosticLogBuffer.record(
                        tag = "AudiobookSession",
                        message = "ExoPlayer prepare failed (format=${s.bookFormat.ifBlank { "unknown" }})",
                        level = "ERROR",
                        throwable = t
                    )
                    try {
                        if (targetPos > 0L) {
                            p.seekTo(targetPos)
                        }
                        p.prepare()
                    } catch (fallbackErr: Throwable) {
                        DiagnosticLogBuffer.record(
                            tag = "AudiobookSession",
                            message = "ExoPlayer fallback prepare failed",
                            level = "ERROR",
                            throwable = fallbackErr
                        )
                    }
                }
            } else {
                isBookHandoffInProgress = false
            }
        }

        playerPreparedForBookId = s.bookId
        _state.update { it.copy(isPrepared = true, isLoading = false) }
        liveTranslationBackend.setPlaybackSpeed(s.playbackSpeed)
        liveTranslationBackend.updatePlaybackPosition(targetPos)
        startPositionPolling()
    }

    private fun observeChaptersForBook(bookId: String) {
        chaptersObserverJob?.cancel()
        val dao = chapterDao ?: return
        chaptersObserverJob = applicationScope.launch {
            try {
                dao.getChaptersForBook(bookId).collect { entities ->
                    if (entities.isNotEmpty() && _state.value.bookId == bookId) {
                        val domainChapters = entities.map { it.toDomain() }
                        val pos = withPlayerThread { getEffectivePositionMs() }
                        val chIdx = findChapterIndex(domainChapters, pos)
                        _state.update {
                            it.copy(
                                chapters = domainChapters,
                                currentChapterIndex = chIdx
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun play() {
        playbackCoordinator?.activateAudiobook()
        cancelPendingSeekRestart()
        val s = _state.value
        if (s.isLiveTranslationActive && settingsRepository != null && currentApiKey.isNullOrBlank()) {
            if (hasCollectedApiKey) {
                failPlayForMissingApiKey()
                return
            }
            applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val resolvedKey = try {
                    settingsRepository.apiKey.firstOrNull()
                } catch (_: Throwable) {
                    null
                }
                hasCollectedApiKey = true
                currentApiKey = resolvedKey
                if (resolvedKey.isNullOrBlank()) {
                    failPlayForMissingApiKey()
                } else {
                    withPlayerThread {
                        startPlaybackSynchronously()
                    }
                }
            }
            return
        }

        val targetLooper = resolvePlayerLooper()
        val currentLooper = if (targetLooper != null) {
            try { Looper.myLooper() } catch (_: Throwable) { null }
        } else {
            null
        }
        if (targetLooper != null && currentLooper !== targetLooper) {
            applicationScope.launch {
                withPlayerThread {
                    startPlaybackSynchronously()
                }
            }
        } else {
            startPlaybackSynchronously()
        }
    }

    private fun failPlayForMissingApiKey() {
        DiagnosticLogBuffer.record(
            tag = "AudiobookSession",
            message = "Live translation playback blocked: missing Gemini API key",
            level = "WARN"
        )
        lastBackendErrorMessage = NoOpLiveTranslationBackend.MISSING_API_KEY_MESSAGE
        stopPeriodicCheckpointing()
        _state.update {
            it.copy(
                isPlaying = false,
                errorMessage = NoOpLiveTranslationBackend.MISSING_API_KEY_MESSAGE
            )
        }
        postToPlayerThread { it.pause() }
        attachedService?.updateStreamingLocks()
    }

    private fun startPlaybackSynchronously() {
        val now = clock()
        var currentPos = getEffectivePositionMs()
        val didRewind = lastPauseTimestamp > 0L && (now - lastPauseTimestamp) > AUTO_REWIND_THRESHOLD_MS
        if (didRewind) {
            currentPos = (currentPos - AUTO_REWIND_MS).coerceAtLeast(0L)
            pendingRestorePositionMs = currentPos
            markInternalSeek(currentPos)
            val chIdx = findChapterIndex(_state.value.chapters, currentPos)
            _state.update {
                it.copy(
                    currentPositionMs = currentPos,
                    currentChapterIndex = chIdx,
                    sourceSubtitle = "",
                    targetSubtitle = "",
                    bufferAheadMs = 0L
                )
            }
        }
        lastPauseTimestamp = 0L
        lastBackendErrorMessage = null

        _state.update { it.copy(isPlaying = true, errorMessage = null) }
        val targetVolume = computeTargetVolume(
            isLiveTranslationActive = _state.value.isLiveTranslationActive,
            isDuckingEnabled = _state.value.isDuckingEnabled,
            duckLevel = currentDuckLevel
        )
        postToPlayerThread { p ->
            if (didRewind) {
                p.seekTo(currentPos)
            }
            p.volume = targetVolume
            p.play()
        }

        syncLiveTranslationOnPlay(forceRestart = didRewind, startPosOverrideMs = currentPos)
        attachedService?.updateStreamingLocks()
        startPositionPolling()
        startPeriodicCheckpointing()

        val bookId = _state.value.bookId
        if (bookId.isNotBlank()) {
            val s = _state.value
            enqueueBookPersist(bookId) {
                try {
                    playbackStateDao?.saveLastPauseTimestamp(bookId, 0L, now)
                } catch (_: Throwable) {
                }
                try {
                    playbackStateDao?.saveAudioProgress(
                        bookId = bookId,
                        chapterIndex = s.currentChapterIndex,
                        positionMs = currentPos,
                        durationMs = s.durationMs,
                        updatedAt = now
                    )
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun pause() {
        cancelPendingSeekRestart()
        val now = clock()
        lastPauseTimestamp = now
        stopPeriodicCheckpointing()
        _state.update { it.copy(isPlaying = false) }
        postToPlayerThread { it.pause() }
        liveTranslationBackend.pauseAudio()
        attachedService?.updateStreamingLocks()
        flushProgressNow(pauseTimestamp = now)
    }

    fun togglePlayPause() {
        if (_state.value.isPlaying) {
            pause()
        } else {
            play()
        }
    }

    fun seekTo(positionMs: Long) {
        val dur = _state.value.durationMs
        val clamped = if (dur > 0L) positionMs.coerceIn(0L, dur) else positionMs.coerceAtLeast(0L)
        performSeekTo(clamped)
    }

    fun seekBy(deltaMs: Long) {
        val basePos = getEffectivePositionMs()
        seekTo(basePos + deltaMs)
    }

    fun selectChapter(index: Int) {
        val chapters = _state.value.chapters
        val chapter = chapters.getOrNull(index) ?: return
        val targetPos = chapter.startMs.coerceAtLeast(0L)
        performSeekTo(targetPos, explicitChapterIndex = index)
    }

    private fun performSeekTo(targetPosMs: Long, explicitChapterIndex: Int? = null) {
        pendingRestorePositionMs = targetPosMs
        isInitialSeekPending = false
        pendingUserSeekTargetMs = targetPosMs
        pendingUserSeekUntilMs = clock() + USER_SEEK_SETTLE_WINDOW_MS
        markInternalSeek(targetPosMs)

        val newChapterIdx = explicitChapterIndex ?: findChapterIndex(_state.value.chapters, targetPosMs)
        val liveActive = _state.value.isLiveTranslationActive
        _state.update { prev ->
            prev.copy(
                currentPositionMs = targetPosMs,
                currentChapterIndex = newChapterIdx,
                sourceSubtitle = if (liveActive) "" else prev.sourceSubtitle,
                targetSubtitle = if (liveActive) "" else prev.targetSubtitle,
                bufferAheadMs = if (liveActive) 0L else prev.bufferAheadMs
            )
        }
        postToPlayerThread { it.seekTo(targetPosMs) }
        if (liveActive) {
            restartLiveTranslationOnSeek(targetPosMs)
        } else {
            liveTranslationBackend.updatePlaybackPosition(targetPosMs)
        }
        flushProgressNow()
    }

    fun nextChapter() {
        val s = _state.value
        if (s.chapters.isEmpty()) return
        val nextIndex = (s.currentChapterIndex + 1).coerceAtMost(s.chapters.size - 1)
        selectChapter(nextIndex)
    }

    fun previousChapter() {
        val s = _state.value
        if (s.chapters.isEmpty()) return
        val prevIndex = (s.currentChapterIndex - 1).coerceAtLeast(0)
        selectChapter(prevIndex)
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        _state.update { it.copy(playbackSpeed = clamped) }
        postToPlayerThread { it.playbackParameters = PlaybackParameters(clamped) }
        liveTranslationBackend.setPlaybackSpeed(clamped)
        flushSettingsAndProgressNow()
    }

    fun toggleLiveTranslation() {
        cancelPendingSeekRestart()
        val newActive = !_state.value.isLiveTranslationActive
        lastBackendErrorMessage = null
        _state.update {
            it.copy(
                isLiveTranslationActive = newActive,
                errorMessage = null,
                bufferAheadMs = if (newActive) it.bufferAheadMs else 0L,
                sourceSubtitle = if (newActive) it.sourceSubtitle else "",
                targetSubtitle = if (newActive) it.targetSubtitle else ""
            )
        }
        applyVolumeDucking()

        if (newActive) {
            if (_state.value.isPlaying) {
                val pos = getEffectivePositionMs()
                startLiveTranslationPipeline(pos)
            }
        } else {
            liveTranslationBackend.stop()
        }
        attachedService?.updateStreamingLocks()
        flushSettingsAndProgressNow()
    }

    fun toggleDucking() {
        val newDucking = !_state.value.isDuckingEnabled
        _state.update { it.copy(isDuckingEnabled = newDucking) }
        applyVolumeDucking()
        flushSettingsAndProgressNow()
    }

    fun setTargetLanguage(language: LanguageCode) {
        val prevLang = _state.value.targetLanguage
        _state.update {
            it.copy(
                targetLanguage = language,
                sourceSubtitle = if (prevLang != language && it.isLiveTranslationActive) "" else it.sourceSubtitle,
                targetSubtitle = if (prevLang != language && it.isLiveTranslationActive) "" else it.targetSubtitle,
                bufferAheadMs = if (prevLang != language && it.isLiveTranslationActive) 0L else it.bufferAheadMs
            )
        }
        applicationScope.launch(NonCancellable) {
            try {
                settingsRepository?.setTargetLanguage(language)
            } catch (_: Throwable) {
            }
        }
        if (prevLang != language && _state.value.isLiveTranslationActive) {
            if (_state.value.isPlaying || liveTranslationBackend.state.value.isRunning) {
                if (liveTranslationBackend.state.value.isRunning) {
                    liveTranslationBackend.stop()
                }
                if (_state.value.isPlaying) {
                    startLiveTranslationPipeline(getEffectivePositionMs())
                }
            }
        }
        flushSettingsAndProgressNow()
    }

    fun setSleepTimer(minutes: Int) {
        when {
            minutes == 0 -> cancelSleepTimer()
            minutes == -1 -> {
                // End of current chapter
                val s = _state.value
                val currentChapter = s.chapters.getOrNull(s.currentChapterIndex)
                val chapterEndMs = currentChapter?.endMs?.takeIf { it > s.currentPositionMs } ?: s.durationMs
                val remainingMs = (chapterEndMs - getEffectivePositionMs()).coerceAtLeast(1_000L)
                val roundedMinutes = ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
                _state.update { it.copy(sleepTimerMinutesRemaining = roundedMinutes) }
                sleepTimer.start(remainingMs)
            }
            minutes > 0 -> {
                _state.update { it.copy(sleepTimerMinutesRemaining = minutes) }
                sleepTimer.start(minutes * 60_000L)
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimer.cancel()
        _state.update { it.copy(sleepTimerMinutesRemaining = 0) }
    }

    fun clearError() {
        lastBackendErrorMessage = null
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * Immediately flushes current playback progress and settings using [applicationScope]
     * + [NonCancellable] so abrupt shutdowns, pauses, seeks, and task removals never lose state.
     */
    fun flushProgressNow(pauseTimestamp: Long? = null) {
        val s = _state.value
        val bookId = s.bookId
        if (bookId.isBlank()) return

        val initialPosMs = getEffectivePositionMs()
        val initialChapterIdx = findChapterIndex(s.chapters, initialPosMs)
        val snapshot = AudioProgressSnapshot(
            bookId = bookId,
            chapterIndex = initialChapterIdx,
            positionMs = initialPosMs,
            durationMs = s.durationMs,
            updatedAt = clock(),
            pauseTimestamp = pauseTimestamp
        )

        if (initialPosMs != s.currentPositionMs || initialChapterIdx != s.currentChapterIndex) {
            _state.update {
                if (it.bookId == bookId) {
                    it.copy(
                        currentPositionMs = initialPosMs,
                        currentChapterIndex = initialChapterIdx
                    )
                } else {
                    it
                }
            }
        }

        persistAudioSnapshot(snapshot, refreshFromPlayerIfCurrent = true)
    }

    private suspend fun awaitPendingPersist(bookId: String) {
        val job = pendingPersistJobs[bookId]
        if (job != null && job.isActive) {
            try {
                job.join()
            } catch (_: Throwable) {
            }
        }
    }

    private fun enqueueBookPersist(bookId: String, block: suspend () -> Unit) {
        val prevJob = pendingPersistJobs[bookId]
        val job = applicationScope.launch(NonCancellable, start = CoroutineStart.LAZY) {
            val selfJob = coroutineContext[Job]
            try {
                if (prevJob != null && prevJob.isActive) {
                    try {
                        prevJob.join()
                    } catch (_: Throwable) {
                    }
                }
                block()
            } finally {
                if (selfJob != null) {
                    pendingPersistJobs.remove(bookId, selfJob)
                }
            }
        }
        pendingPersistJobs[bookId] = job
        job.start()
    }

    private suspend fun resolveLatestProgressIfCurrent(
        bookId: String,
        fallbackChapterIdx: Int,
        fallbackPosMs: Long,
        fallbackDurationMs: Long
    ): Triple<Long, Int, Long> {
        if (_state.value.bookId != bookId || isBookHandoffInProgress) {
            return Triple(fallbackPosMs, fallbackChapterIdx, fallbackDurationMs)
        }
        val refreshed = try {
            withPlayerThread {
                if (_state.value.bookId == bookId && !isBookHandoffInProgress) {
                    val st = _state.value
                    val pos = getEffectivePositionMs()
                    Triple(pos, findChapterIndex(st.chapters, pos), st.durationMs)
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
        return refreshed ?: Triple(fallbackPosMs, fallbackChapterIdx, fallbackDurationMs)
    }

    private fun persistAudioSnapshot(
        snapshot: AudioProgressSnapshot,
        refreshFromPlayerIfCurrent: Boolean = false
    ) {
        enqueueBookPersist(snapshot.bookId) {
            val (effectivePosMs, effectiveChapterIdx, effectiveDurationMs) = if (refreshFromPlayerIfCurrent) {
                resolveLatestProgressIfCurrent(
                    bookId = snapshot.bookId,
                    fallbackChapterIdx = snapshot.chapterIndex,
                    fallbackPosMs = snapshot.positionMs,
                    fallbackDurationMs = snapshot.durationMs
                )
            } else {
                Triple(snapshot.positionMs, snapshot.chapterIndex, snapshot.durationMs)
            }

            try {
                playbackStateDao?.saveAudioProgress(
                    bookId = snapshot.bookId,
                    chapterIndex = effectiveChapterIdx,
                    positionMs = effectivePosMs,
                    durationMs = effectiveDurationMs,
                    updatedAt = snapshot.updatedAt
                )
            } catch (_: Throwable) {
            }
            val pauseTs = snapshot.pauseTimestamp
            if (pauseTs != null) {
                try {
                    playbackStateDao?.saveLastPauseTimestamp(
                        bookId = snapshot.bookId,
                        timestamp = pauseTs,
                        updatedAt = snapshot.updatedAt
                    )
                } catch (_: Throwable) {
                }
            }
            try {
                bookDao?.updateLastReadDate(snapshot.bookId, snapshot.updatedAt)
            } catch (_: Throwable) {
            }
        }
    }

    private fun flushSettingsAndProgressNow() {
        val s = _state.value
        val bookId = s.bookId
        if (bookId.isBlank()) return

        val initialPosMs = getEffectivePositionMs()
        val snapshot = AudioSettingsSnapshot(
            bookId = bookId,
            chapterIndex = findChapterIndex(s.chapters, initialPosMs),
            positionMs = initialPosMs,
            durationMs = s.durationMs,
            playbackSpeed = s.playbackSpeed,
            isLiveTranslationActive = s.isLiveTranslationActive,
            isDuckingEnabled = s.isDuckingEnabled,
            targetLanguageCode = s.targetLanguage.code,
            updatedAt = clock()
        )

        enqueueBookPersist(bookId) {
            val (effectivePosMs, effectiveChapterIdx, effectiveDurationMs) = resolveLatestProgressIfCurrent(
                bookId = snapshot.bookId,
                fallbackChapterIdx = snapshot.chapterIndex,
                fallbackPosMs = snapshot.positionMs,
                fallbackDurationMs = snapshot.durationMs
            )

            try {
                playbackStateDao?.savePlaybackSettings(
                    bookId = snapshot.bookId,
                    playbackSpeed = snapshot.playbackSpeed,
                    isLiveTranslationActive = snapshot.isLiveTranslationActive,
                    isDuckingEnabled = snapshot.isDuckingEnabled,
                    targetLanguageCode = snapshot.targetLanguageCode,
                    updatedAt = snapshot.updatedAt
                )
            } catch (_: Throwable) {
            }
            try {
                playbackStateDao?.saveAudioProgress(
                    bookId = snapshot.bookId,
                    chapterIndex = effectiveChapterIdx,
                    positionMs = effectivePosMs,
                    durationMs = effectiveDurationMs,
                    updatedAt = snapshot.updatedAt
                )
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Handles `AudioManager.ACTION_AUDIO_BECOMING_NOISY` (headphone / Bluetooth disconnect)
     * by immediately pausing both translated PCM output and source playback.
     */
    fun onAudioBecomingNoisy() {
        liveTranslationBackend.pauseAudio()
        pause()
    }

    /**
     * Handles `Service.onTaskRemoved` by immediately flushing progress non-cancellably.
     */
    fun onTaskRemoved() {
        flushProgressNow()
    }

    /**
     * Computes and applies the target volume on [exoPlayer]:
     * - `0f` when `isLiveTranslationActive && !isDuckingEnabled`
     * - `duckLevel` when `isLiveTranslationActive && isDuckingEnabled`
     * - `1f` when `!isLiveTranslationActive`
     */
    fun applyVolumeDucking(): Float {
        val s = _state.value
        val targetVolume = computeTargetVolume(
            isLiveTranslationActive = s.isLiveTranslationActive,
            isDuckingEnabled = s.isDuckingEnabled,
            duckLevel = currentDuckLevel
        )
        postToPlayerThread { it.volume = targetVolume }
        return targetVolume
    }

    /**
     * Returns the current playback position while guarding against pre-seek `0L` overwrites
     * before [ExoPlayer] has completed its initial restoration seek.
     * Must read [exoPlayer] only when invoked on the player thread (or JVM test without Looper).
     */
    fun getEffectivePositionMs(): Long {
        val statePos = _state.value.currentPositionMs.coerceAtLeast(0L)
        val player = exoPlayer ?: return statePos

        val targetLooper = resolvePlayerLooper(player)
        if (targetLooper != null) {
            val currentLooper = try { Looper.myLooper() } catch (_: Throwable) { null }
            if (currentLooper !== targetLooper) {
                return statePos
            }
        }

        val rawPlayerPos = try {
            player.currentPosition
        } catch (_: Throwable) {
            return statePos
        }
        if (rawPlayerPos < 0L) return statePos

        if (isInitialSeekPending) {
            if (pendingRestorePositionMs > 0L && rawPlayerPos < pendingRestorePositionMs - SEEK_TOLERANCE_MS) {
                return pendingRestorePositionMs
            }
            isInitialSeekPending = false
        }

        val userSeekTarget = pendingUserSeekTargetMs
        if (userSeekTarget >= 0L) {
            if (kotlin.math.abs(rawPlayerPos - userSeekTarget) <= SEEK_TOLERANCE_MS) {
                pendingUserSeekTargetMs = -1L
            } else if (clock() < pendingUserSeekUntilMs) {
                return userSeekTarget
            } else {
                pendingUserSeekTargetMs = -1L
            }
        }

        // Also guard if player is still IDLE and reports 0L while state holds a non-zero position
        val playbackState = try {
            player.playbackState
        } catch (_: Throwable) {
            Player.STATE_IDLE
        }
        if (playbackState == Player.STATE_IDLE && rawPlayerPos == 0L && statePos > 0L) {
            return statePos
        }

        return rawPlayerPos
    }

    /**
     * Polls the attached [ExoPlayer] once and updates state if initial seek has completed.
     */
    fun pollPlayerPositionOnce() {
        val player = exoPlayer ?: return
        val targetLooper = resolvePlayerLooper(player)
        if (targetLooper != null) {
            val currentLooper = try { Looper.myLooper() } catch (_: Throwable) { null }
            if (currentLooper !== targetLooper) {
                postToPlayerThread(player) { pollPlayerPositionOnceOnPlayerThread(it) }
                return
            }
        }
        pollPlayerPositionOnceOnPlayerThread(player)
    }

    private fun pollPlayerPositionOnceOnPlayerThread(player: ExoPlayer) {
        if (isBookHandoffInProgress || _state.value.isLoading) return
        val pos = getEffectivePositionMs()
        val rawDuration = try {
            player.duration
        } catch (_: Throwable) {
            C.TIME_UNSET
        }
        val dur = if (rawDuration != C.TIME_UNSET && rawDuration > 0L) rawDuration else _state.value.durationMs
        val clampedPos = if (dur > 0L) pos.coerceAtMost(dur) else pos
        val prevChapterIdx = _state.value.currentChapterIndex
        val newChapterIdx = findChapterIndex(_state.value.chapters, clampedPos)

        if (clampedPos != _state.value.currentPositionMs ||
            dur != _state.value.durationMs ||
            newChapterIdx != prevChapterIdx
        ) {
            _state.update {
                it.copy(
                    currentPositionMs = clampedPos,
                    durationMs = dur,
                    currentChapterIndex = newChapterIdx
                )
            }
        }
        if (_state.value.isPlaying && _state.value.isLiveTranslationActive) {
            liveTranslationBackend.updatePlaybackPosition(clampedPos)
        }
        if (newChapterIdx != prevChapterIdx) {
            flushProgressNow()
        }
    }

    private fun startPositionPolling() {
        positionPollingJob?.cancel()
        if (exoPlayer == null) return
        positionPollingJob = applicationScope.launch {
            while (isActive) {
                val player = exoPlayer ?: break
                try {
                    withPlayerThread(player) {
                        pollPlayerPositionOnceOnPlayerThread(player)
                    }
                } catch (_: Throwable) {
                }
                delay(POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = null
    }

    private fun startPeriodicCheckpointing() {
        checkpointJob?.cancel()
        checkpointJob = applicationScope.launch {
            while (isActive && _state.value.isPlaying) {
                delay(CHECKPOINT_INTERVAL_MS)
                if (!isBookHandoffInProgress && _state.value.isPlaying && _state.value.bookId.isNotBlank()) {
                    val snapshot = try {
                        withPlayerThread {
                            if (isBookHandoffInProgress || !_state.value.isPlaying) {
                                null
                            } else {
                                exoPlayer?.let { pollPlayerPositionOnceOnPlayerThread(it) }
                                val st = _state.value
                                val pos = getEffectivePositionMs()
                                if (st.bookId.isNotBlank()) {
                                    AudioProgressSnapshot(
                                        bookId = st.bookId,
                                        chapterIndex = findChapterIndex(st.chapters, pos),
                                        positionMs = pos,
                                        durationMs = st.durationMs,
                                        updatedAt = clock()
                                    )
                                } else {
                                    null
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        val st = _state.value
                        if (!isBookHandoffInProgress && st.isPlaying && st.bookId.isNotBlank()) {
                            val pos = st.currentPositionMs
                            AudioProgressSnapshot(
                                bookId = st.bookId,
                                chapterIndex = findChapterIndex(st.chapters, pos),
                                positionMs = pos,
                                durationMs = st.durationMs,
                                updatedAt = clock()
                            )
                        } else {
                            null
                        }
                    }
                    if (snapshot != null) {
                        persistAudioSnapshot(snapshot, refreshFromPlayerIfCurrent = false)
                    }
                }
            }
        }
    }

    private fun stopPeriodicCheckpointing() {
        checkpointJob?.cancel()
        checkpointJob = null
    }

    private fun cancelPendingSeekRestart() {
        seekRestartJob?.cancel()
        seekRestartJob = null
    }

    private fun restartLiveTranslationOnSeek(targetPosMs: Long) {
        cancelPendingSeekRestart()
        val wasRunning = liveTranslationBackend.state.value.isRunning
        if (wasRunning) {
            liveTranslationBackend.stop()
        } else {
            liveTranslationBackend.updatePlaybackPosition(targetPosMs)
        }
        if (_state.value.isPlaying && _state.value.isLiveTranslationActive) {
            val debounce = seekRestartDebounceMs
            if (debounce <= 0L) {
                startLiveTranslationPipeline(targetPosMs)
            } else {
                seekRestartJob = applicationScope.launch {
                    delay(debounce)
                    if (isActive && _state.value.isPlaying && _state.value.isLiveTranslationActive) {
                        startLiveTranslationPipeline(targetPosMs)
                    }
                }
            }
        }
    }

    private fun syncLiveTranslationOnPlay(
        forceRestart: Boolean = false,
        startPosOverrideMs: Long? = null
    ) {
        val s = _state.value
        if (!s.isLiveTranslationActive) return

        val isRunning = liveTranslationBackend.state.value.isRunning
        if (forceRestart && isRunning) {
            liveTranslationBackend.stop()
        }
        if (!forceRestart && isRunning) {
            liveTranslationBackend.resumeAudio()
        } else {
            val startPos = startPosOverrideMs ?: getEffectivePositionMs()
            startLiveTranslationPipeline(startPos)
        }
    }

    private fun startLiveTranslationPipeline(startPositionMs: Long) {
        val s = _state.value
        if (s.bookId.isBlank()) return

        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val apiKey = currentApiKey?.takeIf { it.isNotBlank() } ?: try {
                settingsRepository?.apiKey?.firstOrNull()?.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            }
            if (apiKey != null) {
                currentApiKey = apiKey
            }

            val modelMap = try {
                settingsRepository?.modelPerRole?.firstOrNull()
            } catch (_: Throwable) {
                null
            }
            val modelId = modelMap?.get(ModelRole.LIVE_TRANSLATE) ?: currentLiveTranslateModel
            currentLiveTranslateModel = modelId

            val echo = try {
                settingsRepository?.echoTargetLanguage?.firstOrNull()
            } catch (_: Throwable) {
                null
            } ?: currentEchoTargetLanguage
            currentEchoTargetLanguage = echo

            lastBackendErrorMessage = null
            _state.update { it.copy(errorMessage = null) }

            liveTranslationBackend.start(
                scope = applicationScope,
                bookId = s.bookId,
                filePath = currentFilePath,
                targetLanguage = s.targetLanguage,
                apiKey = apiKey,
                modelId = modelId,
                echoTargetLanguage = echo,
                startPositionMs = startPositionMs.coerceAtLeast(0L),
                playbackSpeed = s.playbackSpeed
            )
        }
    }

    private fun resolvePlayerLooper(player: ExoPlayer? = exoPlayer): Looper? {
        val mainLooper = try {
            Looper.getMainLooper()
        } catch (_: Throwable) {
            null
        } ?: return null
        val playerLooper = try {
            player?.applicationLooper
        } catch (_: Throwable) {
            null
        }
        val playerLooperThread: Thread? = try {
            playerLooper?.thread
        } catch (_: Throwable) {
            null
        }
        return if (playerLooper != null && playerLooperThread != null) {
            playerLooper
        } else {
            mainLooper
        }
    }

    private inline fun postToPlayerThread(
        player: ExoPlayer? = exoPlayer,
        crossinline block: (ExoPlayer) -> Unit
    ) {
        if (player == null) return
        val targetLooper = resolvePlayerLooper(player)
        val currentLooper = if (targetLooper != null) {
            try { Looper.myLooper() } catch (_: Throwable) { null }
        } else {
            null
        }
        if (targetLooper == null || currentLooper === targetLooper) {
            try {
                block(player)
            } catch (_: Throwable) {
            }
        } else {
            Handler(targetLooper).post {
                try {
                    block(player)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private suspend inline fun <T> withPlayerThread(
        player: ExoPlayer? = exoPlayer,
        crossinline block: () -> T
    ): T {
        val targetLooper = resolvePlayerLooper(player)
        val currentLooper = if (targetLooper != null) {
            try { Looper.myLooper() } catch (_: Throwable) { null }
        } else {
            null
        }
        if (targetLooper == null || currentLooper === targetLooper) {
            return block()
        }
        return suspendCancellableCoroutine { cont ->
            val runnable = Runnable {
                if (cont.isActive) {
                    try {
                        cont.resume(block())
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                }
            }
            val handler = Handler(targetLooper)
            if (!handler.post(runnable)) {
                if (cont.isActive) {
                    try {
                        cont.resume(block())
                    } catch (t: Throwable) {
                        cont.resumeWithException(t)
                    }
                }
            } else {
                cont.invokeOnCancellation { handler.removeCallbacks(runnable) }
            }
        }
    }

    private fun startPlaybackServiceIfPossible() {
        val ctx = context ?: return
        try {
            val intent = Intent(ctx, PolyglotPlaybackService::class.java)
            ctx.startService(intent)
        } catch (_: Throwable) {
        }
    }

    private fun findChapterIndex(chapters: List<Chapter>, positionMs: Long): Int {
        if (chapters.isEmpty()) return 0
        val idx = chapters.indexOfLast { it.startMs <= positionMs }
        return if (idx >= 0) idx.coerceIn(0, chapters.size - 1) else 0
    }

    @Volatile
    var seekRestartDebounceMs: Long = DEFAULT_SEEK_RESTART_DEBOUNCE_MS
        internal set

    companion object {
        const val CHECKPOINT_INTERVAL_MS = 2_000L
        const val POSITION_POLL_INTERVAL_MS = 250L
        const val AUTO_REWIND_THRESHOLD_MS = 60_000L
        const val AUTO_REWIND_MS = 2_000L
        const val DEFAULT_DURATION_MS = 3_600_000L
        const val DEFAULT_DUCK_LEVEL = 0.15f
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        const val DEFAULT_SEEK_RESTART_DEBOUNCE_MS = 150L
        private const val SEEK_TOLERANCE_MS = 1_000L
        private const val INTERNAL_SEEK_WINDOW_MS = 1_500L
        private const val USER_SEEK_SETTLE_WINDOW_MS = 2_000L

        fun computeTargetVolume(
            isLiveTranslationActive: Boolean,
            isDuckingEnabled: Boolean,
            duckLevel: Float
        ): Float {
            return when {
                !isLiveTranslationActive -> 1.0f
                isDuckingEnabled -> duckLevel.coerceIn(0f, 1f)
                else -> 0.0f
            }
        }
    }
}
