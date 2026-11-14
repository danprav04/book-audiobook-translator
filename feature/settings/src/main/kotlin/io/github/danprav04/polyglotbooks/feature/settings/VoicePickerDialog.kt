package io.github.danprav04.polyglotbooks.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.danprav04.polyglotbooks.core.model.GeminiStudioVoices
import io.github.danprav04.polyglotbooks.core.model.VoiceProfile
import java.util.Locale

private enum class VoiceGenderFilter(val label: String) {
    ALL("All"),
    FEMALE("Female"),
    MALE("Male")
}

/**
 * Reusable dialog displaying Gemini studio voices ([GeminiStudioVoices.catalog]) with gender/age
 * and trait badges, active selection indicator, inline play/stop audio preview controls, error
 * feedback banner, and automatic preview teardown on dismiss/dispose.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoicePickerDialog(
    selectedVoiceId: String,
    playingVoiceId: String?,
    previewErrorMessage: String? = null,
    onSelectVoice: (String) -> Unit,
    onTogglePreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Choose Voice",
    subtitle: String? = "Preview Gemini studio voices before selecting.",
    voices: List<VoiceProfile> = GeminiStudioVoices.catalog
) {
    DisposableEffect(Unit) {
        onDispose {
            onStopPreview()
        }
    }

    var genderFilter by remember { mutableStateOf(VoiceGenderFilter.ALL) }
    val filteredVoices = remember(voices, genderFilter) {
        when (genderFilter) {
            VoiceGenderFilter.ALL -> voices
            VoiceGenderFilter.FEMALE -> voices.filter { it.gender.equals("female", ignoreCase = true) }
            VoiceGenderFilter.MALE -> voices.filter { it.gender.equals("male", ignoreCase = true) }
        }
    }

    Dialog(
        onDismissRequest = {
            onStopPreview()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VoiceGenderFilter.entries.forEach { filter ->
                        val count = when (filter) {
                            VoiceGenderFilter.ALL -> voices.size
                            VoiceGenderFilter.FEMALE -> voices.count { it.gender.equals("female", ignoreCase = true) }
                            VoiceGenderFilter.MALE -> voices.count { it.gender.equals("male", ignoreCase = true) }
                        }
                        FilterChip(
                            selected = genderFilter == filter,
                            onClick = { genderFilter = filter },
                            label = {
                                Text(
                                    text = "${filter.label} ($count)",
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        )
                    }
                }

                if (!previewErrorMessage.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = previewErrorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 420.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = filteredVoices,
                        key = { it.id }
                    ) { voice ->
                        val isSelected = voice.id.equals(selectedVoiceId, ignoreCase = true)
                        val isPlaying = playingVoiceId != null &&
                            voice.id.equals(playingVoiceId, ignoreCase = true)
                        VoiceProfileRow(
                            voice = voice,
                            isSelected = isSelected,
                            isPlaying = isPlaying,
                            onSelect = { onSelectVoice(voice.id) },
                            onTogglePreview = { onTogglePreview(voice.id) }
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (playingVoiceId != null) {
                        val activeName = GeminiStudioVoices.getVoice(playingVoiceId)?.name ?: playingVoiceId
                        TextButton(onClick = onStopPreview) {
                            Text("Stop ($activeName)")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    TextButton(
                        onClick = {
                            onStopPreview()
                            onDismiss()
                        }
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VoiceProfileRow(
    voice: VoiceProfile,
    isSelected: Boolean,
    isPlaying: Boolean,
    onSelect: () -> Unit,
    onTogglePreview: () -> Unit
) {
    val border = if (isSelected) {
        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    } else {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = border
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = voice.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Selected voice",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    VoiceAttributeBadge(
                        text = "${capitalizeLabel(voice.gender)} • ${capitalizeLabel(voice.age)}",
                        emphasized = true
                    )
                    voice.traits.forEach { trait ->
                        VoiceAttributeBadge(
                            text = capitalizeLabel(trait),
                            emphasized = false
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            FilledTonalIconButton(
                onClick = onTogglePreview,
                colors = if (isPlaying) {
                    IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    IconButtonDefaults.filledTonalIconButtonColors()
                }
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) {
                        "Stop preview for ${voice.name}"
                    } else {
                        "Play preview for ${voice.name}"
                    }
                )
            }
        }
    }
}

@Composable
private fun VoiceAttributeBadge(
    text: String,
    emphasized: Boolean
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (emphasized) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun capitalizeLabel(raw: String): String =
    raw.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
    }
