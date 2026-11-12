package io.github.danprav04.polyglotbooks.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackComponentsTest {

    @Test
    fun testStubTranslatedPlayerLifecycle() {
        val player = StubTranslatedPlayer()
        assertFalse(player.isPlaying)
        assertEquals(0L, player.currentPositionMs)

        player.prepare(bookId = 42L, targetLanguageCode = "es")
        assertEquals(3600_000L, player.durationMs)
        assertEquals(30_000L, player.bufferedPositionMs)

        player.play()
        assertTrue(player.isPlaying)

        player.seekTo(120_000L)
        assertEquals(120_000L, player.currentPositionMs)

        player.pause()
        assertFalse(player.isPlaying)

        player.release()
        assertFalse(player.isPlaying)
    }

    @Test
    fun testStubNarrationPlayerLifecycle() {
        val player = StubNarrationPlayer()
        assertFalse(player.isPlaying)
        assertEquals(0, player.currentParagraphIndex)

        player.prepare(bookId = 10L, chapterIndex = 2)
        player.play()
        assertTrue(player.isPlaying)

        player.seekToParagraph(5)
        assertEquals(5, player.currentParagraphIndex)
        assertNotNull(player.currentCfi)
        assertTrue(player.currentCfi?.contains("5") == true)

        player.pause()
        assertFalse(player.isPlaying)

        player.release()
        assertFalse(player.isPlaying)
    }
}
