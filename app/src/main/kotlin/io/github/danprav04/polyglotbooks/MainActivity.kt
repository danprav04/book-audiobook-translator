package io.github.danprav04.polyglotbooks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.danprav04.polyglotbooks.core.playback.PlaybackNotificationManager
import androidx.media3.common.util.UnstableApi
import io.github.danprav04.polyglotbooks.nav.AppNavHost
import io.github.danprav04.polyglotbooks.nav.NotificationNavTarget
import io.github.danprav04.polyglotbooks.ui.theme.PolyglotTheme

/**
 * Main and only Activity hosting the edge-to-edge Jetpack Compose UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var pendingNavTarget by mutableStateOf<NotificationNavTarget?>(null)

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission result handled by OS / MediaSessionService notification provider
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractNotificationNavTarget(intent)?.let { pendingNavTarget = it }
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            PolyglotTheme {
                AppNavHost(
                    pendingNavTarget = pendingNavTarget,
                    onNotificationNavHandled = { pendingNavTarget = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractNotificationNavTarget(intent)?.let { pendingNavTarget = it }
    }

    @OptIn(UnstableApi::class)
    private fun extractNotificationNavTarget(intent: Intent?): NotificationNavTarget? {
        val bookId = intent?.getStringExtra(PlaybackNotificationManager.EXTRA_NAV_BOOK_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val isAudiobook = intent.getBooleanExtra(PlaybackNotificationManager.EXTRA_NAV_IS_AUDIOBOOK, true)
        return NotificationNavTarget(bookId = bookId, isAudiobook = isAudiobook)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
