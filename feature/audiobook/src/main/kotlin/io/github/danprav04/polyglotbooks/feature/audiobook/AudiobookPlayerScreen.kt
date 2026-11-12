package io.github.danprav04.polyglotbooks.feature.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookPlayerScreen(
    bookId: String,
    onBackClick: () -> Unit = {},
    viewModel: AudiobookPlayerViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.bookTitle) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Bookmarks button
                    IconButton(onClick = { viewModel.setBookmarksDialogVisible(true) }) {
                        Icon(Icons.Default.Bookmark, contentDescription = "Bookmarks")
                    }
                    // Chapters button
                    IconButton(onClick = { viewModel.setChapterListSheetVisible(true) }) {
                        Icon(Icons.Default.List, contentDescription = "Chapters")
                    }
                    // AI Report button
                    IconButton(onClick = { viewModel.setAiReportDialogVisible(true) }) {
                        Icon(Icons.Default.Info, contentDescription = "AI Output Report")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Book cover / Header area
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Audiobook",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.chapters.getOrNull(state.currentChapterIndex)?.title ?: state.bookTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = state.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Dual Subtitles Panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ORIGINAL (${state.selectedLanguage.displayName})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Live Translation",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Switch(
                                checked = state.isLiveTranslationActive,
                                onCheckedChange = { viewModel.toggleLiveTranslation() },
                                modifier = Modifier.scale(0.7f)
                            )
                        }
                    }
                    Text(
                        text = state.sourceSubtitle,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "TRANSLATED",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = if (state.isLiveTranslationActive) state.translatedSubtitle else "(Translation Muted)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Scrubber Slider
            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = state.currentPositionMs.toFloat(),
                    onValueChange = { viewModel.seekTo(it.toLong()) },
                    valueRange = 0f..state.durationMs.toFloat()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(state.currentPositionMs),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Speed: ${state.playbackSpeed}x",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = formatTime(state.durationMs),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Secondary Controls Row (Speed, Sleep Timer, Add Bookmark)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Speed Picker Button
                OutlinedButton(onClick = {
                    val nextSpeed = when (state.playbackSpeed) {
                        1.0f -> 1.25f
                        1.25f -> 1.5f
                        1.5f -> 2.0f
                        2.0f -> 0.5f
                        0.5f -> 0.75f
                        else -> 1.0f
                    }
                    viewModel.setPlaybackSpeed(nextSpeed)
                }) {
                    Text("${state.playbackSpeed}x")
                }

                // Sleep Timer Button
                OutlinedButton(onClick = { viewModel.setSleepTimerDialogVisible(true) }) {
                    Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (state.isSleepTimerRunning) "${state.sleepTimerOptionMinutes}m" else "Sleep")
                }

                // Add Bookmark Button
                OutlinedButton(onClick = { viewModel.setAddBookmarkDialogVisible(true) }) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Bookmark")
                }
            }

            // Ducking toggle row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (state.isDuckingEnabled) Icons.AutoMirrored.Filled.VolumeDown else Icons.AutoMirrored.Filled.VolumeMute,
                        contentDescription = null
                    )
                    Text(
                        text = if (state.isDuckingEnabled) "Original Ducked (15%)" else "Original Muted",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Switch(
                    checked = state.isDuckingEnabled,
                    onCheckedChange = { viewModel.toggleDucking() }
                )
            }

            // Transport Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.seekBy(-15000L) }) {
                    Icon(Icons.Default.FastRewind, contentDescription = "Rewind 15s")
                }
                IconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(32.dp))
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                IconButton(onClick = { viewModel.seekBy(15000L) }) {
                    Icon(Icons.Default.FastForward, contentDescription = "Forward 15s")
                }
            }
        }

        // Dialogs & Sheets
        if (state.showChapterListSheet) {
            ModalBottomSheet(onDismissRequest = { viewModel.setChapterListSheetVisible(false) }) {
                Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
                    Text("Chapters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.height(300.dp)) {
                        items(state.chapters) { chapter ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectChapter(chapter.index) }
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    chapter.title,
                                    fontWeight = if (chapter.index == state.currentChapterIndex) FontWeight.Bold else FontWeight.Normal,
                                    color = if (chapter.index == state.currentChapterIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(formatTime(chapter.durationMs), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        if (state.showBookmarksDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.setBookmarksDialogVisible(false) },
                title = { Text("Bookmarks") },
                text = {
                    if (state.bookmarks.isEmpty()) {
                        Text("No bookmarks saved yet.")
                    } else {
                        LazyColumn(modifier = Modifier.height(200.dp)) {
                            items(state.bookmarks) { bm ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.jumpToBookmark(bm.positionMs) }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(bm.title, fontWeight = FontWeight.SemiBold)
                                        Text(formatTime(bm.positionMs), style = MaterialTheme.typography.bodySmall)
                                    }
                                    IconButton(onClick = { viewModel.deleteBookmark(bm.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.setBookmarksDialogVisible(false) }) {
                        Text("Close")
                    }
                }
            )
        }

        if (state.showAddBookmarkDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.setAddBookmarkDialogVisible(false) },
                title = { Text("Add Bookmark") },
                text = {
                    Column {
                        Text("Save position: ${formatTime(state.currentPositionMs)}")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.newBookmarkNote,
                            onValueChange = { viewModel.updateNewBookmarkNote(it) },
                            label = { Text("Note / Title") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.addBookmark(state.newBookmarkNote) }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.setAddBookmarkDialogVisible(false) }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (state.showSleepTimerDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.setSleepTimerDialogVisible(false) },
                title = { Text("Sleep Timer") },
                text = {
                    Column {
                        val options = listOf(0, 15, 30, 45, 60)
                        options.forEach { mins ->
                            TextButton(
                                onClick = { viewModel.setSleepTimer(mins) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (mins == 0) "Off" else "$mins minutes")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.setSleepTimerDialogVisible(false) }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (state.showAiReportDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.setAiReportDialogVisible(false) },
                title = { Text("AI Output Report") },
                text = {
                    Column {
                        Text("Google Play AI-Content Compliance Info:", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("• Audio translation generated via Neural AI Speech synthesis.")
                        Text("• Dual subtitle transcript transcribed with automated speech recognition models.")
                        Text("• All AI content is reviewed and verified in accordance with platform policies.")
                    }
                },
                confirmButton = {
                    Button(onClick = { viewModel.setAiReportDialogVisible(false) }) {
                        Text("Acknowledge")
                    }
                }
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    val hours = millis / (1000 * 60 * 60)
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
