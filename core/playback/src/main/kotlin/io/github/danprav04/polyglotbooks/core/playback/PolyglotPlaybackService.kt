package io.github.danprav04.polyglotbooks.core.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateDao
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android [MediaSessionService] managing media notification, audio focus,
 * Bluetooth/lock-screen playback controls, WakeLock/WifiLock during live AI translation,
 * `ACTION_AUDIO_BECOMING_NOISY` handling, and abrupt-shutdown persistence for Polyglot Books.
 */
@UnstableApi
@AndroidEntryPoint
class PolyglotPlaybackService : MediaSessionService() {

    @Inject
    lateinit var sessionManager: AudiobookSessionManager

    @Inject
    lateinit var liveTranslationBackend: LiveTranslationBackend

    @Inject
    lateinit var bookDao: BookDao

    @Inject
    lateinit var chapterDao: ChapterDao

    @Inject
    lateinit var playbackStateDao: PlaybackStateDao

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var playbackCoordinator: PlaybackCoordinator

    private var fallbackSessionManager: AudiobookSessionManager? = null
    private var fallbackPlaybackCoordinator: PlaybackCoordinator? = null

    private val activeSessionManager: AudiobookSessionManager
        get() = if (::sessionManager.isInitialized) {
            sessionManager
        } else {
            fallbackSessionManager ?: AudiobookSessionManager().also { fallbackSessionManager = it }
        }

    internal val activePlaybackCoordinator: PlaybackCoordinator
        get() = if (::playbackCoordinator.isInitialized) {
            playbackCoordinator
        } else {
            fallbackPlaybackCoordinator ?: PlaybackCoordinator().also { fallbackPlaybackCoordinator = it }
        }

    var mediaSession: MediaSession? = null
        internal set

    var exoPlayer: ExoPlayer? = null
        internal set

    var externalNarrationPlayer: ExternalNarrationPlayer? = null
        internal set

    var notificationManager: PlaybackNotificationManager? = null
        internal set

    private var sessionForwardingPlayer: SessionForwardingPlayer? = null
    private var mainHandler: Handler? = null
    private var lastSessionActivityKey: String? = null
    private var audiobookObserverJob: Job? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile
    var isWakeLockHeld: Boolean = false
        private set

    @Volatile
    var isWifiLockHeld: Boolean = false
        private set

    private var isNoisyReceiverRegistered = false

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                handleAudioBecomingNoisy()
            }
        }
    }

    val state: StateFlow<AudiobookServiceState>
        get() = activeSessionManager.state

    override fun onCreate() {
        super.onCreate()

        mainHandler = try {
            Looper.getMainLooper()?.let { Handler(it) }
        } catch (_: Throwable) {
            null
        }

        initStreamingLocks()
        registerBecomingNoisyReceiver()

        val notifMgr = PlaybackNotificationManager(this)
        notificationManager = notifMgr
        try {
            setMediaNotificationProvider(notifMgr.createMediaNotificationProvider())
        } catch (_: Throwable) {
        }

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val newPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exoPlayer = newPlayer

        val forwardingPlayer = SessionForwardingPlayer(newPlayer)
        sessionForwardingPlayer = forwardingPlayer

        val coordinator = activePlaybackCoordinator
        val extPlayer = try {
            Looper.getMainLooper()?.let { ExternalNarrationPlayer(it, coordinator) }
        } catch (_: Throwable) {
            null
        }
        externalNarrationPlayer = extPlayer

        val initialPlayer: Player =
            if (coordinator.activeSource.value == ActivePlaybackSource.EBOOK_NARRATION && extPlayer != null) {
                extPlayer
            } else {
                forwardingPlayer
            }

        val sessionBuilder = MediaSession.Builder(this, initialPlayer)
        PlaybackNotificationManager.createSessionActivityPendingIntent(this)?.let { pendingIntent ->
            sessionBuilder.setSessionActivity(pendingIntent)
        }
        mediaSession = sessionBuilder.build()

        coordinator.onServiceCommand = { action -> handleCoordinatorCommand(action) }
        activeSessionManager.attachService(this)
        observeAudiobookSessionUpdates()
        updateSessionActivity()
    }

    private inner class SessionForwardingPlayer(
        wrappedPlayer: ExoPlayer
    ) : ForwardingPlayer(wrappedPlayer) {

        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands().buildUpon().addAll(
                COMMAND_SEEK_BACK,
                COMMAND_SEEK_FORWARD,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
            ).build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                COMMAND_SEEK_BACK,
                COMMAND_SEEK_FORWARD,
                COMMAND_SEEK_TO_PREVIOUS,
                COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                COMMAND_SEEK_TO_NEXT,
                COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
                else -> super.isCommandAvailable(command)
            }
        }

        override fun play() {
            activeSessionManager.play()
        }

        override fun pause() {
            activeSessionManager.pause()
        }

        override fun seekTo(positionMs: Long) {
            activeSessionManager.seekTo(positionMs)
        }

        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (mediaItemIndex == C.INDEX_UNSET || mediaItemIndex == currentMediaItemIndex) {
                activeSessionManager.seekTo(positionMs)
            } else {
                super.seekTo(mediaItemIndex, positionMs)
            }
        }

        override fun seekBack() {
            activeSessionManager.seekBy(-NOTIFICATION_SEEK_DELTA_MS)
        }

        override fun seekForward() {
            activeSessionManager.seekBy(NOTIFICATION_SEEK_DELTA_MS)
        }

        override fun seekToNext() {
            activeSessionManager.nextChapter()
        }

        override fun seekToNextMediaItem() {
            activeSessionManager.nextChapter()
        }

        override fun seekToPrevious() {
            activeSessionManager.previousChapter()
        }

        override fun seekToPreviousMediaItem() {
            activeSessionManager.previousChapter()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_EXTERNAL_NARRATION) {
            ensureForegroundForExternalNarration()
            postToMainThread {
                switchToExternalNarrationPlayer()
                externalNarrationPlayer?.notifyStateChanged()
                updateSessionActivity()
                updateStreamingLocks()
            }
        }
        intent?.getStringExtra(EXTRA_BOOK_ID)?.takeIf { it.isNotBlank() }?.let { bookId ->
            activeSessionManager.loadBook(bookId)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            activeSessionManager.onTaskRemoved()
        } finally {
            val isAudiobookPlaying = try {
                exoPlayer?.isPlaying == true || activeSessionManager.state.value.isPlaying
            } catch (_: Throwable) {
                false
            }
            val isExternalPlaying =
                activePlaybackCoordinator.activeSource.value == ActivePlaybackSource.EBOOK_NARRATION &&
                    activePlaybackCoordinator.externalState.value.isPlaying
            if (!isAudiobookPlaying && !isExternalPlaying) {
                stopSelf()
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        audiobookObserverJob?.cancel()
        audiobookObserverJob = null
        activePlaybackCoordinator.onServiceCommand = null
        try {
            activeSessionManager.flushProgressNow()
            if (::liveTranslationBackend.isInitialized) {
                try {
                    liveTranslationBackend.stop()
                } catch (_: Throwable) {
                }
            }
        } finally {
            try {
                releaseStreamingLocks()
            } finally {
                unregisterBecomingNoisyReceiver()
                activeSessionManager.detachService(this)
                mediaSession?.run {
                    try {
                        release()
                    } catch (_: Throwable) {
                    }
                    mediaSession = null
                }
                try {
                    externalNarrationPlayer?.release()
                } catch (_: Throwable) {
                }
                externalNarrationPlayer = null
                try {
                    exoPlayer?.release()
                } catch (_: Throwable) {
                }
                sessionForwardingPlayer = null
                exoPlayer = null
                super.onDestroy()
            }
        }
    }

    /**
     * Immediately pauses translated PCM and source playback when headphones or Bluetooth
     * audio output disconnects (`AudioManager.ACTION_AUDIO_BECOMING_NOISY`).
     */
    fun handleAudioBecomingNoisy() {
        if (::liveTranslationBackend.isInitialized) {
            try {
                liveTranslationBackend.pauseAudio()
            } catch (_: Throwable) {
            }
        }
        val activeSource = activePlaybackCoordinator.activeSource.value
        if (activeSource == ActivePlaybackSource.EBOOK_NARRATION) {
            activePlaybackCoordinator.getExternalDelegate()?.onAudioBecomingNoisy()
        }
        if (activeSource != ActivePlaybackSource.EBOOK_NARRATION || activeSessionManager.state.value.isPlaying) {
            activeSessionManager.onAudioBecomingNoisy()
        }
        releaseStreamingLocks()
    }

    /**
     * Synchronizes partial CPU [PowerManager.WakeLock] and high-perf [WifiManager.WifiLock]
     * with active audiobook live AI translation or active background ebook narration.
     */
    fun updateStreamingLocks() {
        val currentState = activeSessionManager.state.value
        val backendRunning = if (::liveTranslationBackend.isInitialized) {
            liveTranslationBackend.state.value.isRunning
        } else {
            false
        }
        val audiobookNeedsLocks = currentState.isPlaying && (currentState.isLiveTranslationActive || backendRunning)
        val extState = activePlaybackCoordinator.externalState.value
        val externalNeedsLocks =
            activePlaybackCoordinator.activeSource.value == ActivePlaybackSource.EBOOK_NARRATION &&
                extState.isActiveSession &&
                extState.isPlaying
        if (audiobookNeedsLocks || externalNeedsLocks) {
            acquireStreamingLocks()
        } else {
            releaseStreamingLocks()
        }
    }

    private fun observeAudiobookSessionUpdates() {
        if (!::applicationScope.isInitialized) return
        audiobookObserverJob?.cancel()
        audiobookObserverJob = applicationScope.launch {
            activeSessionManager.state
                .map { it.bookId }
                .distinctUntilChanged()
                .collect {
                    postToMainThread {
                        updateSessionActivity()
                    }
                }
        }
    }

    private fun handleCoordinatorCommand(action: String) {
        postToMainThread {
            when (action) {
                PlaybackCoordinator.ACTION_ACTIVATE_AUDIOBOOK -> {
                    switchToAudiobookPlayer()
                    updateSessionActivity()
                    updateStreamingLocks()
                }
                ACTION_START_EXTERNAL_NARRATION -> {
                    switchToExternalNarrationPlayer()
                    externalNarrationPlayer?.notifyStateChanged()
                    updateSessionActivity()
                    updateStreamingLocks()
                }
                PlaybackCoordinator.ACTION_EXTERNAL_STATE_UPDATED -> {
                    if (activePlaybackCoordinator.activeSource.value == ActivePlaybackSource.EBOOK_NARRATION) {
                        switchToExternalNarrationPlayer()
                        externalNarrationPlayer?.notifyStateChanged()
                        updateSessionActivity()
                        updateStreamingLocks()
                    }
                }
                PlaybackCoordinator.ACTION_RELEASE_EXTERNAL_NARRATION -> {
                    externalNarrationPlayer?.notifyStateChanged()
                    if (activePlaybackCoordinator.activeSource.value != ActivePlaybackSource.EBOOK_NARRATION) {
                        switchToAudiobookPlayer()
                    }
                    updateSessionActivity()
                    updateStreamingLocks()
                }
            }
        }
    }

    private fun switchToExternalNarrationPlayer() {
        val session = mediaSession ?: return
        val extPlayer = externalNarrationPlayer ?: return
        if (session.player !== extPlayer) {
            try {
                session.setPlayer(extPlayer)
            } catch (_: Throwable) {
            }
        }
    }

    private fun switchToAudiobookPlayer() {
        val session = mediaSession ?: return
        val abPlayer = sessionForwardingPlayer ?: return
        if (session.player !== abPlayer) {
            try {
                session.setPlayer(abPlayer)
            } catch (_: Throwable) {
            }
        }
    }

    private fun updateSessionActivity() {
        val session = mediaSession ?: return
        val extState = activePlaybackCoordinator.externalState.value
        val isExternal = activePlaybackCoordinator.activeSource.value == ActivePlaybackSource.EBOOK_NARRATION &&
            extState.bookId.isNotBlank()
        val bookId: String?
        val isAudiobook: Boolean
        if (isExternal) {
            bookId = extState.bookId
            isAudiobook = false
        } else {
            val abBookId = activeSessionManager.state.value.bookId
            bookId = abBookId.takeIf { it.isNotBlank() }
            isAudiobook = true
        }
        val key = "$isAudiobook:${bookId.orEmpty()}"
        if (key == lastSessionActivityKey) return
        lastSessionActivityKey = key
        PlaybackNotificationManager.createSessionActivityPendingIntent(
            context = this,
            bookId = bookId,
            isAudiobook = isAudiobook
        )?.let { pendingIntent ->
            try {
                session.setSessionActivity(pendingIntent)
            } catch (_: Throwable) {
            }
        }
    }

    private fun ensureForegroundForExternalNarration() {
        val notifMgr = notificationManager ?: return
        val ext = activePlaybackCoordinator.externalState.value
        val title = ext.bookTitle.ifBlank { "Ebook Narration" }
        val subtitle = ext.chapterTitle.takeIf { it.isNotBlank() }
        try {
            val notification = notifMgr.buildNotification(
                title = title,
                author = ext.author.ifBlank { "Polyglot Books" },
                isPlaying = ext.isPlaying || ext.isBuffering,
                subtitleText = subtitle,
                bookId = ext.bookId.takeIf { it.isNotBlank() },
                isAudiobook = false
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    PlaybackNotificationManager.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(PlaybackNotificationManager.NOTIFICATION_ID, notification)
            }
        } catch (_: Throwable) {
        }
    }

    private fun postToMainThread(block: () -> Unit) {
        val handler = mainHandler
        val currentLooper = try {
            Looper.myLooper()
        } catch (_: Throwable) {
            null
        }
        if (handler == null || currentLooper === handler.looper) {
            try {
                block()
            } catch (_: Throwable) {
            }
        } else {
            handler.post {
                try {
                    block()
                } catch (_: Throwable) {
                }
            }
        }
    }

    internal fun acquireStreamingLocks() {
        try {
            val wl = wakeLock
            if (wl != null && !wl.isHeld) {
                wl.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            isWakeLockHeld = wl?.isHeld ?: true
        } catch (_: Throwable) {
            isWakeLockHeld = true
        }

        try {
            val wfl = wifiLock
            if (wfl != null && !wfl.isHeld) {
                wfl.acquire()
            }
            isWifiLockHeld = wfl?.isHeld ?: true
        } catch (_: Throwable) {
            isWifiLockHeld = true
        }
    }

    internal fun releaseStreamingLocks() {
        try {
            val wl = wakeLock
            if (wl != null && wl.isHeld) {
                wl.release()
            }
        } catch (_: Throwable) {
        } finally {
            isWakeLockHeld = false
        }

        try {
            val wfl = wifiLock
            if (wfl != null && wfl.isHeld) {
                wfl.release()
            }
        } catch (_: Throwable) {
        } finally {
            isWifiLockHeld = false
        }
    }

    private fun initStreamingLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            )?.apply {
                setReferenceCounted(false)
            }
        } catch (_: Throwable) {
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(lockMode, WIFI_LOCK_TAG)?.apply {
                setReferenceCounted(false)
            }
        } catch (_: Throwable) {
        }
    }

    private fun registerBecomingNoisyReceiver() {
        if (isNoisyReceiverRegistered) return
        try {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            registerReceiver(becomingNoisyReceiver, filter)
            isNoisyReceiverRegistered = true
        } catch (_: Throwable) {
        }
    }

    private fun unregisterBecomingNoisyReceiver() {
        if (!isNoisyReceiverRegistered) return
        try {
            unregisterReceiver(becomingNoisyReceiver)
        } catch (_: Throwable) {
        } finally {
            isNoisyReceiverRegistered = false
        }
    }

    // Convenience delegations to AudiobookSessionManager
    fun loadBook(bookId: String) = activeSessionManager.loadBook(bookId)
    fun play() = activeSessionManager.play()
    fun pause() = activeSessionManager.pause()
    fun togglePlayPause() = activeSessionManager.togglePlayPause()
    fun seekTo(positionMs: Long) = activeSessionManager.seekTo(positionMs)
    fun seekBy(deltaMs: Long) = activeSessionManager.seekBy(deltaMs)
    fun selectChapter(index: Int) = activeSessionManager.selectChapter(index)
    fun nextChapter() = activeSessionManager.nextChapter()
    fun previousChapter() = activeSessionManager.previousChapter()
    fun setPlaybackSpeed(speed: Float) = activeSessionManager.setPlaybackSpeed(speed)
    fun toggleLiveTranslation() = activeSessionManager.toggleLiveTranslation()
    fun toggleDucking() = activeSessionManager.toggleDucking()
    fun setTargetLanguage(language: LanguageCode) = activeSessionManager.setTargetLanguage(language)
    fun setSleepTimer(minutes: Int) = activeSessionManager.setSleepTimer(minutes)
    fun cancelSleepTimer() = activeSessionManager.cancelSleepTimer()
    fun clearError() = activeSessionManager.clearError()
    fun flushProgressNow() = activeSessionManager.flushProgressNow()

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_NAV_BOOK_ID = PlaybackNotificationManager.EXTRA_NAV_BOOK_ID
        const val EXTRA_NAV_IS_AUDIOBOOK = PlaybackNotificationManager.EXTRA_NAV_IS_AUDIOBOOK
        const val ACTION_START_EXTERNAL_NARRATION =
            "io.github.danprav04.polyglotbooks.action.START_EXTERNAL_NARRATION"
        const val WAKE_LOCK_TAG = "PolyglotBooks:LiveTranslateWakeLock"
        const val WIFI_LOCK_TAG = "PolyglotBooks:LiveTranslateWifiLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60L * 60L * 1000L // 4 hours max safety timeout
        private const val NOTIFICATION_SEEK_DELTA_MS = 15_000L
    }
}
