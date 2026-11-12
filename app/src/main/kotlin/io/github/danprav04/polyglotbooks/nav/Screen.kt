package io.github.danprav04.polyglotbooks.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Destinations across Polyglot Books.
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    data object Library : Screen("library", "Library", Icons.AutoMirrored.Filled.MenuBook)
    data object AudiobookPlayer : Screen("audiobook/{bookId}", "Audiobook", Icons.Default.Headphones) {
        fun createRoute(bookId: String): String = "audiobook/$bookId"
    }
    data object Reader : Screen("reader/{bookId}", "Reader", Icons.Default.Book) {
        fun createRoute(bookId: String): String = "reader/$bookId"
    }
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object Onboarding : Screen("onboarding", "Welcome", Icons.Default.Info)

    companion object {
        val bottomNavScreens = listOf(
            Library,
            Settings
        )
    }
}
