package io.github.danprav04.polyglotbooks

import io.github.danprav04.polyglotbooks.nav.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationTest {

    @Test
    fun testScreenRoutesAndTitles() {
        assertEquals("library", Screen.Library.route)
        assertEquals("Library", Screen.Library.title)
        assertNotNull(Screen.Library.icon)

        assertEquals("settings", Screen.Settings.route)
        assertEquals("Settings", Screen.Settings.title)
        assertNotNull(Screen.Settings.icon)

        assertEquals("onboarding", Screen.Onboarding.route)
        assertEquals("Welcome", Screen.Onboarding.title)

        assertEquals("audiobook/{bookId}", Screen.AudiobookPlayer.route)
        assertEquals("audiobook/book-123", Screen.AudiobookPlayer.createRoute("book-123"))

        assertEquals("reader/{bookId}", Screen.Reader.route)
        assertEquals("reader/book-456", Screen.Reader.createRoute("book-456"))
    }

    @Test
    fun testBottomNavScreensContainsLibraryAndSettings() {
        val bottomScreens = Screen.bottomNavScreens
        assertEquals(2, bottomScreens.size)
        assertTrue(bottomScreens.contains(Screen.Library))
        assertTrue(bottomScreens.contains(Screen.Settings))
    }
}
