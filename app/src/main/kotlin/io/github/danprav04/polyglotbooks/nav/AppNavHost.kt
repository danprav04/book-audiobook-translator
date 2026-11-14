package io.github.danprav04.polyglotbooks.nav

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.danprav04.polyglotbooks.feature.audiobook.AudiobookPlayerScreen
import io.github.danprav04.polyglotbooks.feature.library.LibraryScreen
import io.github.danprav04.polyglotbooks.feature.reader.ReaderScreen
import io.github.danprav04.polyglotbooks.feature.settings.SettingsScreen
import io.github.danprav04.polyglotbooks.feature.settings.SettingsViewModel
import java.io.File

/**
 * Pending notification deep-link target received by `MainActivity`.
 */
data class NotificationNavTarget(
    val bookId: String,
    val isAudiobook: Boolean,
    val requestId: Long = System.nanoTime()
)

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Library.route,
    pendingNavTarget: NotificationNavTarget? = null,
    onNotificationNavHandled: () -> Unit = {},
    nowPlayingViewModel: NowPlayingViewModel = hiltViewModel()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val nowPlayingState by nowPlayingViewModel.uiState.collectAsState()

    LaunchedEffect(pendingNavTarget) {
        val target = pendingNavTarget ?: return@LaunchedEffect
        if (target.bookId.isNotBlank()) {
            val route = if (target.isAudiobook) {
                Screen.AudiobookPlayer.createRoute(target.bookId)
            } else {
                Screen.Reader.createRoute(target.bookId)
            }
            navController.navigate(route) {
                launchSingleTop = true
            }
        }
        onNotificationNavHandled()
    }

    // Show bottom bar and global Mini-Player only on primary top-level tabs
    val showBottomBar = currentRoute in Screen.bottomNavScreens.map { it.route }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = nowPlayingState.isVisible,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut()
                    ) {
                        GlobalMiniPlayerBar(
                            state = nowPlayingState,
                            onClick = {
                                if (nowPlayingState.bookId.isNotBlank()) {
                                    val route = if (nowPlayingState.isAudiobook) {
                                        Screen.AudiobookPlayer.createRoute(nowPlayingState.bookId)
                                    } else {
                                        Screen.Reader.createRoute(nowPlayingState.bookId)
                                    }
                                    navController.navigate(route) {
                                        launchSingleTop = true
                                    }
                                }
                            },
                            onTogglePlayPause = { nowPlayingViewModel.togglePlayPause() },
                            onStop = { nowPlayingViewModel.stopAndDismiss() }
                        )
                    }
                    NavigationBar {
                        Screen.bottomNavScreens.forEach { screen ->
                            NavigationBarItem(
                                icon = {
                                    screen.icon?.let { icon ->
                                        Icon(imageVector = icon, contentDescription = screen.title)
                                    }
                                },
                                label = { Text(screen.title) },
                                selected = currentRoute == screen.route,
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Library.route) {
                val libraryViewModel: io.github.danprav04.polyglotbooks.feature.library.LibraryViewModel = hiltViewModel()
                val context = androidx.compose.ui.platform.LocalContext.current
                val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        libraryViewModel.importUris(context, uris)
                    }
                }

                val folderPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
                ) { treeUri ->
                    if (treeUri != null) {
                        libraryViewModel.importFolder(context, treeUri)
                    }
                }

                LibraryScreen(
                    viewModel = libraryViewModel,
                    onImportFileClick = {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onImportFolderClick = {
                        folderPickerLauncher.launch(null)
                    },
                    onOpenEbook = { bookId ->
                        navController.navigate(Screen.Reader.createRoute(bookId))
                    },
                    onOpenAudiobook = { bookId ->
                        navController.navigate(Screen.AudiobookPlayer.createRoute(bookId))
                    }
                )
            }

            composable(
                route = Screen.AudiobookPlayer.route,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
                AudiobookPlayerScreen(
                    bookId = bookId,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Reader.route,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
                ReaderScreen(
                    bookId = bookId,
                    onBackClick = { navController.popBackStack() }
                )
            }

            composable(Screen.Settings.route) {
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onBackClick = {
                        if (!navController.popBackStack()) {
                            navController.navigate(Screen.Library.route)
                        }
                    }
                )
            }

            composable(Screen.Onboarding.route) {
                LibraryScreen(
                    viewModel = hiltViewModel(),
                    onOpenEbook = { bookId ->
                        navController.navigate(Screen.Reader.createRoute(bookId))
                    },
                    onOpenAudiobook = { bookId ->
                        navController.navigate(Screen.AudiobookPlayer.createRoute(bookId))
                    }
                )
            }
        }
    }
}

@Composable
private fun GlobalMiniPlayerBar(
    state: NowPlayingUiState,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coverBitmap = remember(state.coverPath) {
        decodeCoverBitmap(state.coverPath)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { state.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (coverBitmap != null) {
                        Image(
                            bitmap = coverBitmap.asImageBitmap(),
                            contentDescription = state.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isAudiobook) {
                                Icons.Default.Headphones
                            } else {
                                Icons.AutoMirrored.Filled.MenuBook
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(2.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onTogglePlayPause) {
                    if (state.isBuffering && !state.isPlaying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play"
                        )
                    }
                }

                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Stop playback"
                    )
                }
            }
        }
    }
}

private fun decodeCoverBitmap(coverPath: String?): Bitmap? {
    val raw = coverPath?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val resolvedPath = when {
        raw.startsWith("file://", ignoreCase = true) -> raw.removePrefix("file://")
        raw.startsWith("file:", ignoreCase = true) -> raw.removePrefix("file:")
        else -> raw
    }
    return try {
        val file = File(resolvedPath)
        if (file.exists() && file.isFile) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }
}
