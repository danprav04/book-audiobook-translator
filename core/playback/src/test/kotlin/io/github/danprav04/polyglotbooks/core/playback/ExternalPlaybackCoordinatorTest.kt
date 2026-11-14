package io.github.danprav04.polyglotbooks.core.playback

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalPlaybackCoordinatorTest {

    private class FakeExternalNarrationDelegate(
        initialState: ExternalNarrationState = ExternalNarrationState()
    ) : ExternalNarrationDelegate {
        private val _externalState = MutableStateFlow(initialState)
        override val externalState: StateFlow<ExternalNarrationState> = _externalState.asStateFlow()

        var playCalls = 0
        var pauseCalls = 0
        var stopCalls = 0
        var seekPosCalls = mutableListOf<Long>()
        var seekDeltaCalls = mutableListOf<Long>()
        var nextParaCalls = 0
        var prevParaCalls = 0
        var nextChapterCalls = 0
        var prevChapterCalls = 0
        var speedCalls = mutableListOf<Float>()
        var noisyCalls = 0

        fun emitState(state: ExternalNarrationState) {
            _externalState.value = state
        }

        override fun play() {
            playCalls++
            _externalState.value = _externalState.value.copy(isPlaying = true)
        }

        override fun pause() {
            pauseCalls++
            _externalState.value = _externalState.value.copy(isPlaying = false)
        }

        override fun stop() {
            stopCalls++
            _externalState.value = ExternalNarrationState()
        }

        override fun seekToChapterPositionMs(positionMs: Long) {
            seekPosCalls += positionMs
            _externalState.value = _externalState.value.copy(chapterPositionMs = positionMs)
        }

        override fun seekByDeltaMs(deltaMs: Long) {
            seekDeltaCalls += deltaMs
        }

        override fun nextParagraph() {
            nextParaCalls++
        }

        override fun previousParagraph() {
            prevParaCalls++
        }

        override fun nextChapter() {
            nextChapterCalls++
        }

        override fun previousChapter() {
            prevChapterCalls++
        }

        override fun setPlaybackSpeed(speed: Float) {
            speedCalls += speed
            _externalState.value = _externalState.value.copy(playbackSpeed = speed)
        }

        override fun onAudioBecomingNoisy() {
            noisyCalls++
            pause()
        }
    }

    @Test
    fun `coordinator tracks active source transitions and external state`() {
        val coordinator = PlaybackCoordinator()
        assertEquals(ActivePlaybackSource.NONE, coordinator.activeSource.value)
        assertFalse(coordinator.externalState.value.isActiveSession)
        assertNull(coordinator.getExternalDelegate())

        val commands = mutableListOf<String>()
        coordinator.onServiceCommand = { commands += it }

        var audiobookPaused = false
        coordinator.onPauseAudiobook = { audiobookPaused = true }

        val delegate = FakeExternalNarrationDelegate(
            ExternalNarrationState(
                isActiveSession = true,
                bookId = "ebook-1",
                bookTitle = "The Odyssey",
                isPlaying = false
            )
        )
        coordinator.registerExternalDelegate(delegate)
        assertSame(delegate, coordinator.getExternalDelegate())
        assertTrue(coordinator.externalState.value.isActiveSession)

        coordinator.activateExternalNarration(context = null)
        assertTrue(audiobookPaused)
        assertEquals(ActivePlaybackSource.EBOOK_NARRATION, coordinator.activeSource.value)
        assertTrue(commands.contains(PolyglotPlaybackService.ACTION_START_EXTERNAL_NARRATION))

        val playingState = delegate.externalState.value.copy(
            isPlaying = true,
            chapterIndex = 2,
            chapterTitle = "Book III",
            chapterPositionMs = 12_500L,
            chapterDurationMs = 60_000L
        )
        delegate.emitState(playingState)
        coordinator.updateExternalState(playingState)
        assertEquals("Book III", coordinator.externalState.value.chapterTitle)
        assertEquals(12_500L, coordinator.externalState.value.chapterPositionMs)
        assertTrue(commands.contains(PlaybackCoordinator.ACTION_EXTERNAL_STATE_UPDATED))

        // Activating audiobook pauses the playing external delegate
        coordinator.activateAudiobook()
        assertEquals(1, delegate.pauseCalls)
        assertEquals(ActivePlaybackSource.AUDIOBOOK, coordinator.activeSource.value)
        assertTrue(commands.contains(PlaybackCoordinator.ACTION_ACTIVATE_AUDIOBOOK))

        // Releasing external narration clears external state without clobbering AUDIOBOOK source
        coordinator.releaseExternalNarration()
        assertFalse(coordinator.externalState.value.isActiveSession)
        assertEquals(ActivePlaybackSource.AUDIOBOOK, coordinator.activeSource.value)
        assertTrue(commands.contains(PlaybackCoordinator.ACTION_RELEASE_EXTERNAL_NARRATION))
    }

    @Test
    fun `releaseExternalNarration resets activeSource to NONE when EBOOK_NARRATION is active`() {
        val coordinator = PlaybackCoordinator()
        coordinator.activateExternalNarration(null)
        coordinator.updateExternalState(ExternalNarrationState(isActiveSession = true, bookId = "b1"))
        assertEquals(ActivePlaybackSource.EBOOK_NARRATION, coordinator.activeSource.value)

        coordinator.releaseExternalNarration()
        assertEquals(ActivePlaybackSource.NONE, coordinator.activeSource.value)
        assertFalse(coordinator.externalState.value.isActiveSession)
        assertEquals("", coordinator.externalState.value.bookId)
    }

    @Test
    fun `audiobook and ebook mutual exclusion via AudiobookSessionManager and PlaybackCoordinator`() = runTest {
        val coordinator = PlaybackCoordinator()
        val delegate = FakeExternalNarrationDelegate(
            ExternalNarrationState(
                isActiveSession = true,
                bookId = "ebook-42",
                isPlaying = true
            )
        )
        coordinator.registerExternalDelegate(delegate)
        coordinator.updateExternalState(delegate.externalState.value)
        coordinator.activateExternalNarration(null)

        val sessionManager = AudiobookSessionManager(
            bookDao = null,
            chapterDao = null,
            playbackStateDao = null,
            settingsRepository = null,
            liveTranslationBackend = NoOpLiveTranslationBackend(),
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            context = null,
            playbackCoordinator = coordinator
        )

        // Playing audiobook pauses external ebook narration and switches active source
        sessionManager.play()
        assertEquals(1, delegate.pauseCalls)
        assertEquals(ActivePlaybackSource.AUDIOBOOK, coordinator.activeSource.value)
        assertTrue(sessionManager.state.value.isPlaying)

        // Activating external narration pauses playing audiobook
        coordinator.activateExternalNarration(null)
        assertFalse(sessionManager.state.value.isPlaying)
        assertEquals(ActivePlaybackSource.EBOOK_NARRATION, coordinator.activeSource.value)
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun `PolyglotPlaybackService handles streaming locks and noisy broadcast for EBOOK_NARRATION`() {
        val coordinator = PlaybackCoordinator()
        val delegate = FakeExternalNarrationDelegate(
            ExternalNarrationState(
                isActiveSession = true,
                bookId = "ebook-99",
                isPlaying = true
            )
        )
        coordinator.registerExternalDelegate(delegate)
        coordinator.updateExternalState(delegate.externalState.value)
        coordinator.activateExternalNarration(null)

        val service = PolyglotPlaybackService()
        service.playbackCoordinator = coordinator

        service.updateStreamingLocks()
        assertTrue(service.isWakeLockHeld)
        assertTrue(service.isWifiLockHeld)

        service.handleAudioBecomingNoisy()
        assertEquals(1, delegate.noisyCalls)
        assertFalse(delegate.externalState.value.isPlaying)
        assertFalse(service.isWakeLockHeld)
        assertFalse(service.isWifiLockHeld)

        coordinator.updateExternalState(delegate.externalState.value.copy(isPlaying = false))
        service.updateStreamingLocks()
        assertFalse(service.isWakeLockHeld)
        assertFalse(service.isWifiLockHeld)
    }

    @Test
    @androidx.annotation.OptIn(UnstableApi::class)
    fun `PlaybackNotificationManager builds pending intent safely and exposes nav extra keys`() {
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.packageName } returns "io.github.danprav04.polyglotbooks"
        every { context.packageManager?.getLaunchIntentForPackage(any()) } returns Intent()

        // Should not throw even in JVM test environment
        PlaybackNotificationManager.createSessionActivityPendingIntent(
            context = context,
            bookId = "book-123",
            isAudiobook = false
        )
        assertEquals("extra_nav_book_id", PlaybackNotificationManager.EXTRA_NAV_BOOK_ID)
        assertEquals("extra_nav_is_audiobook", PlaybackNotificationManager.EXTRA_NAV_IS_AUDIOBOOK)
    }

    @Test
    fun `pauseActivePlayback pauses active audiobook without pausing idle external delegate`() {
        val coordinator = PlaybackCoordinator()
        var audiobookPauseCalls = 0
        coordinator.onPauseAudiobook = { audiobookPauseCalls++ }

        val delegate = FakeExternalNarrationDelegate(
            ExternalNarrationState(
                isActiveSession = true,
                bookId = "ebook-1",
                isPlaying = false
            )
        )
        coordinator.registerExternalDelegate(delegate)
        coordinator.activateAudiobook()

        coordinator.pauseActivePlayback()

        assertEquals(1, audiobookPauseCalls)
        assertEquals(0, delegate.pauseCalls)
        assertEquals(ActivePlaybackSource.AUDIOBOOK, coordinator.activeSource.value)
    }

    @Test
    fun `pauseActivePlayback pauses active ebook narration without clearing activeSource or calling audiobook pause`() {
        val coordinator = PlaybackCoordinator()
        val delegate = FakeExternalNarrationDelegate(
            ExternalNarrationState(
                isActiveSession = true,
                bookId = "ebook-1",
                isPlaying = true
            )
        )
        coordinator.registerExternalDelegate(delegate)
        coordinator.updateExternalState(delegate.externalState.value)
        coordinator.activateExternalNarration(context = null)

        var audiobookPauseCalls = 0
        coordinator.onPauseAudiobook = { audiobookPauseCalls++ }

        coordinator.pauseActivePlayback()

        assertEquals(1, delegate.pauseCalls)
        assertEquals(0, audiobookPauseCalls)
        assertFalse(delegate.externalState.value.isPlaying)
        assertEquals(ActivePlaybackSource.EBOOK_NARRATION, coordinator.activeSource.value)
    }

    @Test
    fun `pauseActivePlayback pauses playing AudiobookSessionManager`() = runTest {
        val coordinator = PlaybackCoordinator()
        val sessionManager = AudiobookSessionManager(
            bookDao = null,
            chapterDao = null,
            playbackStateDao = null,
            settingsRepository = null,
            liveTranslationBackend = NoOpLiveTranslationBackend(),
            applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            context = null,
            playbackCoordinator = coordinator
        )

        sessionManager.play()
        assertTrue(sessionManager.state.value.isPlaying)
        assertEquals(ActivePlaybackSource.AUDIOBOOK, coordinator.activeSource.value)

        coordinator.pauseActivePlayback()

        assertFalse(sessionManager.state.value.isPlaying)
        assertEquals(ActivePlaybackSource.AUDIOBOOK, coordinator.activeSource.value)
    }
}
