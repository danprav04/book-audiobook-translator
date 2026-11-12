package io.github.danprav04.polyglotbooks.feature.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookPlayerViewModelTest {

    @Test
    fun testPlaybackSpeedClampingAndToggle() {
        val viewModel = AudiobookPlayerViewModel()
        assertEquals(1.0f, viewModel.uiState.value.playbackSpeed)

        viewModel.setPlaybackSpeed(2.5f)
        assertEquals(2.5f, viewModel.uiState.value.playbackSpeed)

        viewModel.setPlaybackSpeed(5.0f) // Should clamp to 3.0f
        assertEquals(3.0f, viewModel.uiState.value.playbackSpeed)
    }

    @Test
    fun testBookmarkCreationAndDeletion() {
        val viewModel = AudiobookPlayerViewModel()
        assertTrue(viewModel.uiState.value.bookmarks.isEmpty())

        viewModel.addBookmark("Test Bookmark")
        assertEquals(1, viewModel.uiState.value.bookmarks.size)
        assertEquals("Test Bookmark", viewModel.uiState.value.bookmarks[0].title)

        val bmId = viewModel.uiState.value.bookmarks[0].id
        viewModel.deleteBookmark(bmId)
        assertTrue(viewModel.uiState.value.bookmarks.isEmpty())
    }
}
