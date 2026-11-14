package io.github.danprav04.polyglotbooks.core.playback

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * Media3 [SimpleBasePlayer] backed by [PlaybackCoordinator.externalState] and
 * [ExternalNarrationDelegate] so background ebook narration integrates natively with
 * [androidx.media3.session.MediaSession], lock-screen controls, Bluetooth buttons, and notifications.
 */
@UnstableApi
class ExternalNarrationPlayer(
    looper: Looper,
    private val coordinator: PlaybackCoordinator
) : SimpleBasePlayer(looper) {

    private val mainHandler = Handler(looper)

    private val speechAudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .setUsage(C.USAGE_MEDIA)
        .build()

    private val availableCommands: Player.Commands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE
        )
        .build()

    /**
     * Requests Media3 [SimpleBasePlayer] to re-evaluate [getState] on the application looper.
     */
    fun notifyStateChanged() {
        val currentLooper = try {
            Looper.myLooper()
        } catch (_: Throwable) {
            null
        }
        if (currentLooper === applicationLooper) {
            try {
                invalidateState()
            } catch (_: Throwable) {
            }
        } else {
            mainHandler.post {
                try {
                    invalidateState()
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun getState(): State {
        val ext = coordinator.externalState.value
        val isActive = ext.isActiveSession && ext.bookId.isNotBlank()
        val speed = ext.playbackSpeed.coerceIn(MIN_SPEED, MAX_SPEED)
        val durationMs = ext.chapterDurationMs.coerceAtLeast(0L)
        val rawPosMs = ext.chapterPositionMs.coerceAtLeast(0L)
        val clampedPosMs = if (durationMs > 0L) rawPosMs.coerceAtMost(durationMs) else rawPosMs

        val playbackState = when {
            !isActive -> Player.STATE_IDLE
            ext.isBuffering -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }

        val positionSupplier = if (isActive && ext.isPlaying && !ext.isBuffering) {
            PositionSupplier.getExtrapolating(clampedPosMs, speed)
        } else {
            PositionSupplier.getConstant(clampedPosMs)
        }

        val builder = State.Builder()
            .setAvailableCommands(availableCommands)
            .setAudioAttributes(speechAudioAttributes)
            .setPlayWhenReady(
                isActive && ext.isPlaying,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            )
            .setPlaybackState(playbackState)
            .setIsLoading(isActive && ext.isBuffering)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setSeekBackIncrementMs(SEEK_DELTA_MS)
            .setSeekForwardIncrementMs(SEEK_DELTA_MS)
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE / 2L)
            .setContentPositionMs(positionSupplier)

        if (isActive) {
            val metadata = buildMediaMetadata(ext)
            val currentMediaItem = MediaItem.Builder()
                .setMediaId("${ext.bookId}:ch${ext.chapterIndex}")
                .setMediaMetadata(metadata)
                .build()
            val currentItemData = MediaItemData.Builder("current:${ext.bookId}:${ext.chapterIndex}")
                .setMediaItem(currentMediaItem)
                .setMediaMetadata(metadata)
                .setIsSeekable(durationMs > 0L)
                .setDurationUs(if (durationMs > 0L) durationMs * 1_000L else C.TIME_UNSET)
                .build()

            val hasPrev = ext.paragraphIndex > 0 || ext.chapterIndex > 0
            val hasNext = (ext.paragraphCount > 0 && ext.paragraphIndex + 1 < ext.paragraphCount) ||
                (ext.chapterCount > 0 && ext.chapterIndex + 1 < ext.chapterCount)

            val playlist = buildList {
                if (hasPrev) {
                    add(
                        MediaItemData.Builder("prev:${ext.bookId}")
                            .setMediaItem(
                                MediaItem.Builder()
                                    .setMediaId("${ext.bookId}:prev")
                                    .setMediaMetadata(metadata)
                                    .build()
                            )
                            .setMediaMetadata(metadata)
                            .setIsPlaceholder(true)
                            .build()
                    )
                }
                add(currentItemData)
                if (hasNext) {
                    add(
                        MediaItemData.Builder("next:${ext.bookId}")
                            .setMediaItem(
                                MediaItem.Builder()
                                    .setMediaId("${ext.bookId}:next")
                                    .setMediaMetadata(metadata)
                                    .build()
                            )
                            .setMediaMetadata(metadata)
                            .setIsPlaceholder(true)
                            .build()
                    )
                }
            }
            val currentIndex = if (hasPrev) 1 else 0
            builder.setPlaylist(playlist)
            builder.setCurrentMediaItemIndex(currentIndex)
            builder.setPlaylistMetadata(metadata)
        } else {
            builder.setPlaylist(emptyList())
            builder.setCurrentMediaItemIndex(C.INDEX_UNSET)
        }

        return builder.build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val delegate = coordinator.getExternalDelegate()
        if (playWhenReady) {
            delegate?.play()
        } else {
            delegate?.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int
    ): ListenableFuture<*> {
        val delegate = coordinator.getExternalDelegate() ?: return Futures.immediateVoidFuture()
        when (seekCommand) {
            Player.COMMAND_SEEK_BACK -> delegate.seekByDeltaMs(-SEEK_DELTA_MS)
            Player.COMMAND_SEEK_FORWARD -> delegate.seekByDeltaMs(SEEK_DELTA_MS)
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> delegate.nextParagraph()
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> delegate.previousParagraph()
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> {
                val currentIdx = currentMediaItemIndex
                when {
                    currentIdx != C.INDEX_UNSET && mediaItemIndex < currentIdx -> delegate.previousParagraph()
                    currentIdx != C.INDEX_UNSET && mediaItemIndex > currentIdx -> delegate.nextParagraph()
                    positionMs != C.TIME_UNSET -> delegate.seekToChapterPositionMs(positionMs.coerceAtLeast(0L))
                }
            }
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> {
                val targetMs = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
                delegate.seekToChapterPositionMs(targetMs)
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        val clamped = playbackParameters.speed.coerceIn(MIN_SPEED, MAX_SPEED)
        coordinator.getExternalDelegate()?.setPlaybackSpeed(clamped)
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        coordinator.getExternalDelegate()?.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        return Futures.immediateVoidFuture()
    }

    private fun buildMediaMetadata(ext: ExternalNarrationState): MediaMetadata {
        val title = ext.bookTitle.ifBlank { "Ebook Narration" }
        val chapterText = ext.chapterTitle.takeIf { it.isNotBlank() }
        val artistText = when {
            chapterText != null && ext.author.isNotBlank() -> "$chapterText • ${ext.author}"
            chapterText != null -> chapterText
            ext.author.isNotBlank() -> ext.author
            else -> "Ebook Narration"
        }
        val modeSubtitle = buildString {
            if (ext.readTranslated && ext.spokenLanguageCode.isNotBlank()) {
                append("Translated (")
                append(ext.spokenLanguageCode.uppercase())
                append(")")
            } else {
                append("Original")
            }
            if (ext.narrationEngineName.isNotBlank()) {
                append(" • ")
                append(ext.narrationEngineName)
            }
        }
        val extras = Bundle().apply {
            putString(EXTRA_PLAYBACK_MODE, MODE_EBOOK_NARRATION)
            putString(EXTRA_BOOK_ID, ext.bookId)
            putInt(EXTRA_CHAPTER_INDEX, ext.chapterIndex)
            putInt(EXTRA_PARAGRAPH_INDEX, ext.paragraphIndex)
            putBoolean(EXTRA_READ_TRANSLATED, ext.readTranslated)
            putString(EXTRA_SPOKEN_LANGUAGE, ext.spokenLanguageCode)
        }
        val builder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistText)
            .setAlbumTitle(chapterText)
            .setSubtitle(modeSubtitle)
            .setExtras(extras)

        val artworkUri = resolveArtworkUri(ext.coverPath)
        if (artworkUri != null) {
            builder.setArtworkUri(artworkUri)
        }
        return builder.build()
    }

    private fun resolveArtworkUri(coverPath: String?): Uri? {
        val trimmed = coverPath?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            if (trimmed.startsWith("/")) {
                Uri.fromFile(File(trimmed))
            } else {
                Uri.parse(trimmed)
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val SEEK_DELTA_MS = 15_000L
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        const val EXTRA_PLAYBACK_MODE = "polyglot_playback_mode"
        const val MODE_EBOOK_NARRATION = "ebook_narration"
        const val EXTRA_BOOK_ID = "polyglot_book_id"
        const val EXTRA_CHAPTER_INDEX = "polyglot_chapter_index"
        const val EXTRA_PARAGRAPH_INDEX = "polyglot_paragraph_index"
        const val EXTRA_READ_TRANSLATED = "polyglot_read_translated"
        const val EXTRA_SPOKEN_LANGUAGE = "polyglot_spoken_language"
    }
}
