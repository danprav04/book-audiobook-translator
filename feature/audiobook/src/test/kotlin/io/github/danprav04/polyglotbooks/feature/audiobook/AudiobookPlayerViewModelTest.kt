package io.github.danprav04.polyglotbooks.feature.audiobook

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudiobookPlayerViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    @Test
    fun testChapterNavigation() {
        val viewModel = AudiobookPlayerViewModel()
        // Default has 1 chapter: index 0
        assertEquals(0, viewModel.uiState.value.currentChapterIndex)

        // Previous on chapter 0 stays at 0
        viewModel.previousChapter()
        assertEquals(0, viewModel.uiState.value.currentChapterIndex)

        // Next on single chapter stays at 0
        viewModel.nextChapter()
        assertEquals(0, viewModel.uiState.value.currentChapterIndex)
    }

    @Test
    fun testDuckingToggle() {
        val viewModel = AudiobookPlayerViewModel()
        assertFalse(viewModel.uiState.value.isDuckingEnabled)

        viewModel.toggleDucking()
        assertTrue(viewModel.uiState.value.isDuckingEnabled)

        viewModel.toggleDucking()
        assertFalse(viewModel.uiState.value.isDuckingEnabled)
    }

    @Test
    fun testLiveTranslationToggle() {
        val viewModel = AudiobookPlayerViewModel()
        assertTrue(viewModel.uiState.value.isLiveTranslationActive)

        viewModel.toggleLiveTranslation()
        assertFalse(viewModel.uiState.value.isLiveTranslationActive)
    }
}
