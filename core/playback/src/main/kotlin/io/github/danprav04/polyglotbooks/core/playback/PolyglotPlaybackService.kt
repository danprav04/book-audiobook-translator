package io.github.danprav04.polyglotbooks.core.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
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
import kotlinx.coroutines.flow.StateFlow
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

    private var fallbackSessionManager: AudiobookSessionManager? = null

    private val activeSessionManager: AudiobookSessionManager
        get() = if (::sessionManager.isInitialized) {
            sessionManager
        } else {
            fallbackSessionManager ?: AudiobookSessionManager().also { fallbackSessionManager = it }
        }

    var mediaSession: MediaSession? = null
        internal set

    var exoPlayer: ExoPlayer? = null
        internal set

    var notificationManager: PlaybackNotificationManager? = null
        internal set

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

        val sessionPlayer = SessionForwardingPlayer(newPlayer)
        val sessionBuilder = MediaSession.Builder(this, sessionPlayer)
        PlaybackNotificationManager.createSessionActivityPendingIntent(this)?.let { pendingIntent ->
            sessionBuilder.setSessionActivity(pendingIntent)
        }
        mediaSession = sessionBuilder.build()

        activeSessionManager.attachService(this)
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
            val isCurrentlyPlaying = try {
                exoPlayer?.isPlaying == true || activeSessionManager.state.value.isPlaying
            } catch (_: Throwable) {
                false
            }
            if (!isCurrentlyPlaying) {
                stopSelf()
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
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
                        player.release()
                    } catch (_: Throwable) {
                    }
                    try {
                        release()
                    } catch (_: Throwable) {
                    }
                    mediaSession = null
                }
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
        activeSessionManager.onAudioBecomingNoisy()
        releaseStreamingLocks()
    }

    /**
     * Synchronizes partial CPU [PowerManager.WakeLock] and high-perf [WifiManager.WifiLock]
     * with active playback + live AI translation.
     */
    fun updateStreamingLocks() {
        val currentState = activeSessionManager.state.value
        val backendRunning = if (::liveTranslationBackend.isInitialized) {
            liveTranslationBackend.state.value.isRunning
        } else {
            false
        }
        val shouldHoldLocks = currentState.isPlaying && (currentState.isLiveTranslationActive || backendRunning)
        if (shouldHoldLocks) {
            acquireStreamingLocks()
        } else {
            releaseStreamingLocks()
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
        const val WAKE_LOCK_TAG = "PolyglotBooks:LiveTranslateWakeLock"
        const val WIFI_LOCK_TAG = "PolyglotBooks:LiveTranslateWifiLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60L * 60L * 1000L // 4 hours max safety timeout
        private const val NOTIFICATION_SEEK_DELTA_MS = 15_000L
    }
}
