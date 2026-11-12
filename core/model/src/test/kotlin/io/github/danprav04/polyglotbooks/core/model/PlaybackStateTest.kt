package io.github.danprav04.polyglotbooks.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateTest {

    @Test
    fun testDefaultValues() {
        val state = PlaybackState(bookId = "book-1")
        assertEquals("book-1", state.bookId)
        assertEquals(0, state.chapterIndex)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertEquals(1.0f, state.playbackSpeed, 0.0001f)
        assertTrue(state.isLiveTranslationActive)
        assertFalse(state.isDuckingEnabled)
        assertNull(state.targetLanguageCode)
        assertNull(state.lastCfi)
        assertEquals(0f, state.readingProgress, 0.0001f)
        assertEquals(0L, state.lastPauseTimestamp)
        assertEquals(0L, state.updatedAt)
    }

    @Test
    fun testSerializationRoundTrip() {
        val original = PlaybackState(
            bookId = "book-42",
            chapterIndex = 3,
            positionMs = 123_456L,
            durationMs = 999_000L,
            playbackSpeed = 1.5f,
            isLiveTranslationActive = false,
            isDuckingEnabled = true,
            targetLanguageCode = "es",
            lastCfi = "epubcfi(/6/4!/2/1:0)",
            readingProgress = 0.42f,
            lastPauseTimestamp = 1_700_000_000L,
            updatedAt = 1_700_000_500L
        )
        val json = Json.encodeToString(original)
        val decoded = Json.decodeFromString<PlaybackState>(json)
        assertEquals(original, decoded)
    }
}
