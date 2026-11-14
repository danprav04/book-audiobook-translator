package io.github.danprav04.polyglotbooks.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.danprav04.polyglotbooks.core.model.GeminiStudioVoices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays short bundled voice-sample previews for Gemini studio voices.
 */
interface VoicePreviewPlayer {
    /** Voice ID currently playing, or `null` when idle. */
    val playingVoiceId: StateFlow<String?>

    /** Human-readable error if the most recent preview attempt failed, or `null`. */
    val previewError: StateFlow<String?>

    /**
     * Toggles sample playback for [voiceId].
     *
     * If [voiceId] is already playing, stops playback without invoking [onBeforeStart].
     * Otherwise stops any prior clip, invokes [onBeforeStart] (for example to pause active book
     * or TTS narration), and starts the requested voice preview.
     */
    fun togglePreview(voiceId: String, onBeforeStart: (() -> Unit)? = null)

    /** Stops any active voice preview playback and clears transient preview errors. */
    fun stop()
}

internal interface VoicePreviewBackend {
    fun start()
    fun stopAndRelease()
}

internal fun interface VoicePreviewBackendFactory {
    fun create(
        voiceId: String,
        assetPath: String,
        onCompletion: () -> Unit,
        onError: (String) -> Unit
    ): VoicePreviewBackend
}

/**
 * Asset-backed [VoicePreviewPlayer] that streams bundled `.m4a` clips from
 * `assets/voice_previews/<voice>.m4a` via Android [MediaPlayer].
 */
@Singleton
class AssetVoicePreviewPlayer internal constructor(
    private val backendFactory: VoicePreviewBackendFactory
) : VoicePreviewPlayer {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        AndroidMediaPlayerBackendFactory(context.applicationContext)
    )

    private val lock = Any()
    private val _playingVoiceId = MutableStateFlow<String?>(null)
    override val playingVoiceId: StateFlow<String?> = _playingVoiceId.asStateFlow()

    private val _previewError = MutableStateFlow<String?>(null)
    override val previewError: StateFlow<String?> = _previewError.asStateFlow()

    private var activeBackend: VoicePreviewBackend? = null
    private var requestGeneration: Long = 0L

    override fun togglePreview(voiceId: String, onBeforeStart: (() -> Unit)?) {
        val trimmed = voiceId.trim()
        if (trimmed.isEmpty()) {
            val previousToRelease = synchronized(lock) {
                requestGeneration++
                _playingVoiceId.value = null
                _previewError.value = "No voice selected for preview."
                detachBackendLocked()
            }
            releaseSafely(previousToRelease)
            return
        }

        val canonicalVoiceId = GeminiStudioVoices.getVoice(trimmed)?.id ?: trimmed

        var sameVoiceStopped = false
        var startToken = 0L
        val previousToRelease = synchronized(lock) {
            val previous = detachBackendLocked()
            val currentlyPlaying = _playingVoiceId.value
            requestGeneration++
            startToken = requestGeneration
            _playingVoiceId.value = null
            _previewError.value = null
            if (currentlyPlaying != null && currentlyPlaying.equals(canonicalVoiceId, ignoreCase = true)) {
                sameVoiceStopped = true
            }
            previous
        }
        releaseSafely(previousToRelease)

        if (sameVoiceStopped) {
            return
        }

        onBeforeStart?.invoke()

        val stillCurrentBeforeCreate = synchronized(lock) {
            requestGeneration == startToken
        }
        if (!stillCurrentBeforeCreate) {
            return
        }

        val assetPath = GeminiStudioVoices.previewAssetPath(canonicalVoiceId)
        var createdBackend: VoicePreviewBackend? = null
        try {
            val backend = backendFactory.create(
                voiceId = canonicalVoiceId,
                assetPath = assetPath,
                onCompletion = {
                    val finished = synchronized(lock) {
                        if (requestGeneration == startToken && activeBackend != null) {
                            _playingVoiceId.value = null
                            detachBackendLocked()
                        } else {
                            null
                        }
                    }
                    releaseSafely(finished)
                },
                onError = { message ->
                    val failed = synchronized(lock) {
                        if (requestGeneration == startToken) {
                            _playingVoiceId.value = null
                            _previewError.value = message
                            detachBackendLocked()
                        } else {
                            null
                        }
                    }
                    releaseSafely(failed)
                }
            )
            createdBackend = backend

            val registered = synchronized(lock) {
                if (requestGeneration == startToken) {
                    activeBackend = backend
                    _playingVoiceId.value = canonicalVoiceId
                    _previewError.value = null
                    true
                } else {
                    false
                }
            }
            if (!registered) {
                releaseSafely(backend)
                return
            }

            backend.start()
        } catch (t: Throwable) {
            val cleanup = synchronized(lock) {
                val detached = if (requestGeneration == startToken) {
                    _playingVoiceId.value = null
                    _previewError.value = formatPreviewFailure(canonicalVoiceId, t)
                    detachBackendLocked()
                } else {
                    null
                }
                detached ?: createdBackend
            }
            releaseSafely(cleanup)
        }
    }

    override fun stop() {
        val toRelease = synchronized(lock) {
            requestGeneration++
            _playingVoiceId.value = null
            _previewError.value = null
            detachBackendLocked()
        }
        releaseSafely(toRelease)
    }

    private fun detachBackendLocked(): VoicePreviewBackend? {
        val current = activeBackend
        activeBackend = null
        return current
    }

    private fun releaseSafely(backend: VoicePreviewBackend?) {
        if (backend != null) {
            runCatching { backend.stopAndRelease() }
        }
    }

    private fun formatPreviewFailure(voiceId: String, throwable: Throwable): String {
        return when (throwable) {
            is FileNotFoundException -> "Preview audio not available for $voiceId."
            else -> {
                val detail = throwable.message?.trim().orEmpty()
                if (detail.isNotEmpty()) {
                    "Unable to play preview for $voiceId: $detail"
                } else {
                    "Unable to play preview for $voiceId."
                }
            }
        }
    }
}

private class AndroidMediaPlayerBackendFactory(
    private val context: Context
) : VoicePreviewBackendFactory {
    override fun create(
        voiceId: String,
        assetPath: String,
        onCompletion: () -> Unit,
        onError: (String) -> Unit
    ): VoicePreviewBackend {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            context.assets.openFd(assetPath).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.setOnCompletionListener { onCompletion() }
            player.setOnErrorListener { _, what, extra ->
                onError("Unable to play preview for $voiceId (error $what/$extra).")
                true
            }
            player.prepare()
            return AndroidMediaPlayerBackend(player)
        } catch (t: Throwable) {
            runCatching { player.release() }
            throw t
        }
    }
}

private class AndroidMediaPlayerBackend(
    private val player: MediaPlayer
) : VoicePreviewBackend {
    private val released = AtomicBoolean(false)

    override fun start() {
        if (!released.get()) {
            player.start()
        }
    }

    override fun stopAndRelease() {
        if (released.compareAndSet(false, true)) {
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
            }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VoicePreviewModule {
    @Binds
    @Singleton
    abstract fun bindVoicePreviewPlayer(impl: AssetVoicePreviewPlayer): VoicePreviewPlayer
}
