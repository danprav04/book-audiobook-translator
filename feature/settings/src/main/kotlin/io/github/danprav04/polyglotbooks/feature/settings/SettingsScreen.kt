package io.github.danprav04.polyglotbooks.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissUserMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 1: API Key Section
            ApiKeySection(
                uiState = uiState,
                onKeyChange = viewModel::onApiKeyInputChanged,
                onToggleVisibility = viewModel::toggleApiKeyVisibility,
                onTestKey = viewModel::testApiKey,
                onSaveKey = viewModel::saveApiKey,
                onDeleteKey = viewModel::deleteApiKey
            )

            HorizontalDivider()

            // Section 2: Model per Role Configuration
            ModelConfigurationSection(
                uiState = uiState,
                viewModel = viewModel
            )

            HorizontalDivider()

            // Section 3: Audio & Storage Section
            AudioAndStorageSection(
                uiState = uiState,
                onDuckLevelChange = viewModel::setDuckLevel,
                onCacheLimitChange = viewModel::setCacheLimitBytes,
                onEchoChange = viewModel::setEchoTargetLanguage
            )

            HorizontalDivider()

            // Section 4: Privacy & Disclosure Notice
            PrivacyAndDisclosureSection(
                onOpenUrl = { url ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    try {
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // Handle no browser available
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ApiKeySection(
    uiState: SettingsUiState,
    onKeyChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onTestKey: () -> Unit,
    onSaveKey: () -> Unit,
    onDeleteKey: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Gemini API Key (BYOK)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "Polyglot Books communicates directly with Google AI Studio. Your key is encrypted with Google Tink and stored strictly on-device in Android Keystore.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = uiState.apiKeyInput,
                onValueChange = onKeyChange,
                label = { Text("Gemini API Key") },
                placeholder = { Text("AIzaSy...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (uiState.isApiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                trailingIcon = {
                    IconButton(onClick = onToggleVisibility) {
                        Icon(
                            imageVector = if (uiState.isApiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (uiState.isApiKeyVisible) "Hide API key" else "Show API key"
                        )
                    }
                }
            )

            // Test Key Status banner
            when (val status = uiState.testKeyStatus) {
                is TestKeyStatus.Idle -> { /* Nothing to show */ }
                is TestKeyStatus.Testing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Testing API key with Gemini API...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                is TestKeyStatus.Success -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${status.message} (${status.modelCount} models cataloged)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                is TestKeyStatus.Error -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = status.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        onTestKey()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = uiState.testKeyStatus !is TestKeyStatus.Testing && uiState.apiKeyInput.isNotBlank()
                ) {
                    Text("Test key")
                }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onSaveKey()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = uiState.apiKeyInput.isNotBlank()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save")
                }

                if (!uiState.savedApiKey.isNullOrBlank()) {
                    IconButton(
                        onClick = onDeleteKey,
                        colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete key")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelConfigurationSection(
    uiState: SettingsUiState,
    viewModel: SettingsViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Model per Role",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                TextButton(
                    onClick = { viewModel.resetModelsToDefaults() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reset defaults", maxLines = 1)
                }
            }

            Text(
                text = "Assign a specific Gemini model to each pipeline role. Choose from detected models or enter a custom ID.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Iterate over all 6 roles
            for (role in ModelRole.entries) {
                val currentModel = uiState.modelPerRole[role] ?: DefaultModels.get(role)
                val catalogModels = viewModel.getCatalogModelsForRole(role)

                RoleModelSelector(
                    role = role,
                    currentModel = currentModel,
                    catalogModels = catalogModels,
                    onModelSelected = { newModel -> viewModel.setModelForRole(role, newModel) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleModelSelector(
    role: ModelRole,
    currentModel: String,
    catalogModels: List<io.github.danprav04.polyglotbooks.core.gemini.ModelItem>,
    onModelSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var customInput by remember(currentModel) { mutableStateOf(currentModel) }

    val roleTitle = when (role) {
        ModelRole.LIVE_TRANSLATE -> "Live Translate (Speech-to-Speech)"
        ModelRole.TTS -> "TTS Engine (Chapter Audio)"
        ModelRole.LIVE_NARRATION -> "Live Narration (Interactive Reading)"
        ModelRole.TEXT_ANALYSIS -> "Text Analysis (Character & Cast Detection)"
        ModelRole.TEXT_TRANSLATION -> "Text Translation (Ebook Chapter Translation)"
        ModelRole.LIVE_TRANSCRIBE -> "Live Transcribe (Dual Subtitles)"
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = roleTitle,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = customInput,
                onValueChange = {
                    customInput = it
                    onModelSelected(it)
                },
                label = { Text("Model ID") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                singleLine = true,
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true)
                    .fillMaxWidth()
            )

            val suggestedModels = buildList {
                // Add default
                add(DefaultModels.get(role))
                // Add catalog models matching role
                for (item in catalogModels) {
                    if (!contains(item.id)) add(item.id)
                }
            }

            if (suggestedModels.isNotEmpty()) {
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    suggestedModels.forEach { modelId ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(text = modelId, fontWeight = FontWeight.Medium)
                                    if (modelId == DefaultModels.get(role)) {
                                        Text(
                                            text = "Default",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                }
                            },
                            onClick = {
                                customInput = modelId
                                onModelSelected(modelId)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioAndStorageSection(
    uiState: SettingsUiState,
    onDuckLevelChange: (Float) -> Unit,
    onCacheLimitChange: (Long) -> Unit,
    onEchoChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Audio & Storage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Ducking volume slider (0% to 30%)
            Column {
                val percentage = (uiState.duckLevel * 100f).roundToInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Original Audio Ducking Level",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "$percentage%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = uiState.duckLevel,
                    onValueChange = onDuckLevelChange,
                    valueRange = 0.0f..0.30f,
                    steps = 5
                )
                Text(
                    text = if (percentage == 0) "Original audio completely muted during live translation" else "Original audio ducked to $percentage% volume underneath translated voice",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Cache quota slider (500 MB to 10 GB)
            Column {
                val minBytes = 500L * 1024L * 1024L
                val maxBytes = 10L * 1024L * 1024L * 1024L
                val currentGb = uiState.cacheLimitBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Audio Cache Quota",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = String.format(Locale.US, "%.1f GB", currentGb),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = uiState.cacheLimitBytes.toFloat(),
                    onValueChange = { onCacheLimitChange(it.toLong()) },
                    valueRange = minBytes.toFloat()..maxBytes.toFloat(),
                    steps = 18
                )
                Text(
                    text = "Translated audio is cached locally for instant back-seeks. Oldest segments are evicted when limit is reached.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Echo target language toggle switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Echo Target Language",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Live translation model repeats speech in translated voice for confirmation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = uiState.echoTargetLanguage,
                    onCheckedChange = onEchoChange
                )
            }
        }
    }
}

@Composable
private fun PrivacyAndDisclosureSection(
    onOpenUrl: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Privacy & AI Model Disclosures",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "Important Gemini API Tier Notice:",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "• Free Tier: If you use a free Gemini API key from Google AI Studio, Google's Terms of Service state that prompt and audio inputs may be reviewed by human reviewers and used to train Google products.\n" +
                    "• Paid Tier (Pay-As-You-Go): Data submitted under Google AI Studio paid tier is NOT used to train Google models, and remains strictly confidential.\n" +
                    "• Zero App Telemetry: Polyglot Books has NO intermediary backend, NO tracking SDKs, and NO analytics. All traffic goes directly to Google API endpoints.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onOpenUrl("https://ai.google.dev/gemini-api/terms") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("API Terms", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = { onOpenUrl("https://github.com/danprav04/book-audiobook-translator/blob/main/PRIVACY.md") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Privacy Policy", style = MaterialTheme.typography.labelSmall)
                }
            }

            Button(
                onClick = { onOpenUrl("https://github.com/danprav04/book-audiobook-translator/issues/new?template=ai_output_report.yml") },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Report AI Output Issue")
            }
        }
    }
}
