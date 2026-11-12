package io.github.danprav04.polyglotbooks.feature.audiobook

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookPlayerScreen(
    bookId: String,
    onBackClick: () -> Unit = {},
    viewModel: AudiobookPlayerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }
    LaunchedEffect(context) {
        viewModel.initTts(context)
    }
    val state by viewModel.uiState.collectAsState()

    // 1. Cover Art in Player: Load it via remember(state.coverUri, state.bookId) from state.coverUri or context.filesDir/covers/{bookId}.jpg using BitmapFactory.decodeFile
    val coverBitmap = remember(state.coverUri, state.bookId) {
        val bId = state.bookId.ifBlank { bookId }
        val uriFile = state.coverUri?.let { File(it) }
        val defaultCoverFile = if (bId.isNotBlank()) File(context.filesDir, "covers/$bId.jpg") else null
        val targetPath = when {
            uriFile != null && uriFile.exists() -> uriFile.absolutePath
            defaultCoverFile != null && defaultCoverFile.exists() -> defaultCoverFile.absolutePath
            !state.coverUri.isNullOrBlank() -> state.coverUri
            defaultCoverFile != null -> defaultCoverFile.absolutePath
            else -> null
        }
        targetPath?.let { path ->
            try {
                BitmapFactory.decodeFile(path)
            } catch (_: Exception) {
                null
            }
        } ?: state.coverBitmap
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                        Icon(Icons.Default.Menu, contentDescription = "Chapters")
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
                if (coverBitmap != null) {
                    Image(
                        bitmap = coverBitmap.asImageBitmap(),
                        contentDescription = state.bookTitle,
                        modifier = Modifier
                            .size(130.dp)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Headphones,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Audiobook",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.bookTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ORIGINAL (${state.sourceLanguage.displayName})",
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "TRANSLATED (${state.targetLanguage.displayName})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                        Box {
                            TextButton(
                                onClick = { viewModel.setLanguageDropdownVisible(true) },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = state.targetLanguage.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select Target Language",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = state.showLanguageDropdown,
                                onDismissRequest = { viewModel.setLanguageDropdownVisible(false) }
                            ) {
                                listOf(
                                    LanguageCode.RUSSIAN,
                                    LanguageCode.ENGLISH,
                                    LanguageCode.HEBREW,
                                    LanguageCode.SPANISH,
                                    LanguageCode.FRENCH,
                                    LanguageCode.GERMAN,
                                    LanguageCode.JAPANESE,
                                    LanguageCode.CHINESE_SIMPLIFIED
                                ).forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang.displayName) },
                                        onClick = {
                                            viewModel.setTargetLanguage(lang)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = if (state.isLiveTranslationActive) state.translatedSubtitle else "(Translation Muted)",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Chapter UI & Scrubber (Requirement 4)
            val currentChapter = state.chapters.getOrNull(state.currentChapterIndex)
            val chapterTitle = currentChapter?.title ?: "Chapter ${state.currentChapterIndex + 1}"
            val chapterCount = state.chapters.size.coerceAtLeast(1)
            val chapterNumText = "Chapter ${state.currentChapterIndex + 1} of $chapterCount"
            val chapterStartMs = currentChapter?.startMs ?: 0L
            val chapterDurationMs = (currentChapter?.durationMs ?: state.durationMs).coerceAtLeast(1000L)
            val elapsedInChapterMs = (state.currentPositionMs - chapterStartMs).coerceIn(0L, chapterDurationMs)

            Column(modifier = Modifier.fillMaxWidth()) {
                // Chapter Title & "Chapter X of Y"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setChapterListSheetVisible(true) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = chapterNumText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Chapter progress slider
                Slider(
                    value = elapsedInChapterMs.toFloat(),
                    onValueChange = { newElapsed ->
                        viewModel.seekTo(chapterStartMs + newElapsed.toLong())
                    },
                    valueRange = 0f..chapterDurationMs.toFloat()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(elapsedInChapterMs),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Speed: ${state.playbackSpeed}x",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = formatTime(chapterDurationMs),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Total book progress secondary text / indicator
                Spacer(modifier = Modifier.height(4.dp))
                val totalProgress = if (state.durationMs > 0L) {
                    (state.currentPositionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { totalProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Total Book: ${formatTime(state.currentPositionMs)} / ${formatTime(state.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${(totalProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // Transport Controls (Previous Chapter, Rewind 15s, Play/Pause, Forward 15s, Next Chapter)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous Chapter button
                IconButton(onClick = { viewModel.previousChapter() }) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Chapter")
                }
                // Rewind 15s
                IconButton(onClick = { viewModel.seekBy(-15000L) }) {
                    Icon(Icons.Default.FastRewind, contentDescription = "Rewind 15s")
                }
                // Play / Pause
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
                // Forward 15s
                IconButton(onClick = { viewModel.seekBy(15000L) }) {
                    Icon(Icons.Default.FastForward, contentDescription = "Forward 15s")
                }
                // Next Chapter button
                IconButton(onClick = { viewModel.nextChapter() }) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next Chapter")
                }
            }
        }

        // Dialogs & Sheets
        if (state.showChapterListSheet) {
            ModalBottomSheet(onDismissRequest = { viewModel.setChapterListSheetVisible(false) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text("Chapters", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                    ) {
                        items(state.chapters) { chapter ->
                            val isSelected = chapter.index == state.currentChapterIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable { viewModel.selectChapter(chapter.index) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Chapter ${chapter.index + 1}: ${chapter.title}",
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Starts at ${formatTime(chapter.startMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = formatTime(chapter.durationMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
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
