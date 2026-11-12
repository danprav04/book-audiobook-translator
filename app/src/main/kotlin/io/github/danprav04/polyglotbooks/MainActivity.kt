package io.github.danprav04.polyglotbooks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.danprav04.polyglotbooks.nav.AppNavHost
import io.github.danprav04.polyglotbooks.ui.theme.PolyglotTheme

/**
 * Main and only Activity hosting the edge-to-edge Jetpack Compose UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PolyglotTheme {
                AppNavHost()
            }
        }
    }
}
