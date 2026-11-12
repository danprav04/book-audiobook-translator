package io.github.danprav04.polyglotbooks.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Library.route
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Show bottom bar only on primary top-level tabs
    val showBottomBar = currentRoute in Screen.bottomNavScreens.map { it.route }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
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
