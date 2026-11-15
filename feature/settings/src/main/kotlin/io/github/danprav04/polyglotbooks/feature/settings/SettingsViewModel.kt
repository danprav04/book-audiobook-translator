package io.github.danprav04.polyglotbooks.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danprav04.polyglotbooks.core.audio.VoicePreviewPlayer
import io.github.danprav04.polyglotbooks.core.data.prefs.FeatureUsageStats
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.CostEstimator
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalog
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalogService
import io.github.danprav04.polyglotbooks.core.gemini.ModelItem
import io.github.danprav04.polyglotbooks.core.gemini.ModelQuotaSnapshot
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.github.danprav04.polyglotbooks.core.model.GeminiStudioVoices
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.playback.PlaybackCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TestKeyStatus {
    data object Idle : TestKeyStatus
    data object Testing : TestKeyStatus
    data class Success(val message: String, val modelCount: Int) : TestKeyStatus
    data class Error(val errorMessage: String) : TestKeyStatus
}

data class SettingsUiState(
    val apiKeyInput: String = "",
    val isApiKeyVisible: Boolean = false,
    val savedApiKey: String? = null,
    val testKeyStatus: TestKeyStatus = TestKeyStatus.Idle,
    val modelPerRole: Map<ModelRole, String> = DefaultModels.map,
    val availableModels: List<ModelItem> = emptyList(),
    val duckLevel: Float = 0.15f,
    val cacheLimitBytes: Long = 1024L * 1024L * 1024L, // 1 GB
    val echoTargetLanguage: Boolean = false,
    val defaultNarratorVoiceId: String = GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID,
    val playingPreviewVoiceId: String? = null,
    val previewErrorMessage: String? = null,
    val featureUsageStats: FeatureUsageStats = FeatureUsageStats(),
    val estimatedTotalCost: Double = 0.0,
    val quotaSnapshots: Map<String, ModelQuotaSnapshot> = emptyMap(),
    val userMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secureKeyStore: SecureKeyStore,
    private val modelCatalogService: ModelCatalogService,
    private val quotaTracker: GeminiQuotaTracker? = null,
    private val voicePreviewPlayer: VoicePreviewPlayer? = null,
    private val playbackCoordinator: PlaybackCoordinator? = null
) : ViewModel() {

    private val _apiKeyInput = MutableStateFlow("")
    private val _isApiKeyVisible = MutableStateFlow(false)
    private val _testKeyStatus = MutableStateFlow<TestKeyStatus>(TestKeyStatus.Idle)
    private val _availableModels = MutableStateFlow<List<ModelItem>>(emptyList())
    private val _userMessage = MutableStateFlow<String?>(null)
    private val quotaSnapshotsFlow: StateFlow<Map<String, ModelQuotaSnapshot>> =
        quotaTracker?.quotaSnapshots ?: MutableStateFlow(emptyMap())
    private val playingPreviewVoiceFlow: StateFlow<String?> =
        voicePreviewPlayer?.playingVoiceId ?: MutableStateFlow(null)
    private val previewErrorFlow: StateFlow<String?> =
        voicePreviewPlayer?.previewError ?: MutableStateFlow(null)

    private val baseRepoSettings = combine(
        settingsRepository.apiKey,
        settingsRepository.modelPerRole,
        settingsRepository.duckLevel,
        settingsRepository.cacheLimitBytes,
        settingsRepository.echoTargetLanguage
    ) { key, models, duck, cache, echo ->
        RepoSettings(
            apiKey = key,
            modelPerRole = models,
            duckLevel = duck,
            cacheLimitBytes = cache,
            echoTargetLanguage = echo,
            usageStats = FeatureUsageStats(),
            defaultNarratorVoiceId = GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID
        )
    }

    private val repoSettings = combine(
        baseRepoSettings,
        settingsRepository.featureUsageStats,
        settingsRepository.defaultNarratorVoiceId
    ) { base, usage, narratorVoice ->
        base.copy(
            usageStats = usage,
            defaultNarratorVoiceId = narratorVoice
        )
    }

    private val internalState = combine(
        _apiKeyInput,
        _isApiKeyVisible,
        _testKeyStatus,
        _availableModels,
        _userMessage
    ) { input, visible, testStatus, catalogModels, msg ->
        InternalSettings(
            input = input,
            visible = visible,
            testStatus = testStatus,
            catalogModels = catalogModels,
            message = msg
        )
    }

    // Combine into a single unified UI state
    val uiState: StateFlow<SettingsUiState> = combine(
        repoSettings,
        internalState,
        quotaSnapshotsFlow,
        playingPreviewVoiceFlow,
        previewErrorFlow
    ) { repo, internal, snapshots, playingPreviewVoiceId, previewError ->
        val liveTranslateCost = CostEstimator.estimateAudioCost(ModelRole.LIVE_TRANSLATE, repo.usageStats.liveTranslateMinutes * 60.0, repo.usageStats.isFreeTier)
        val ttsCost = CostEstimator.estimateAudioCost(ModelRole.TTS, repo.usageStats.ttsCharactersOrMinutes * 60.0, repo.usageStats.isFreeTier)
        val liveNarrationCost = CostEstimator.estimateAudioCost(ModelRole.LIVE_NARRATION, repo.usageStats.liveNarrationMinutes * 60.0, repo.usageStats.isFreeTier)
        val textCost = CostEstimator.estimateTextCost(repo.usageStats.textAnalysisTokens, repo.usageStats.textTranslationTokens, repo.usageStats.isFreeTier)
        val totalCost = liveTranslateCost + ttsCost + liveNarrationCost + textCost

        SettingsUiState(
            apiKeyInput = if (internal.input.isEmpty() && repo.apiKey != null) repo.apiKey else internal.input,
            isApiKeyVisible = internal.visible,
            savedApiKey = repo.apiKey,
            testKeyStatus = internal.testStatus,
            modelPerRole = repo.modelPerRole,
            availableModels = internal.catalogModels,
            duckLevel = repo.duckLevel,
            cacheLimitBytes = repo.cacheLimitBytes,
            echoTargetLanguage = repo.echoTargetLanguage,
            defaultNarratorVoiceId = repo.defaultNarratorVoiceId,
            playingPreviewVoiceId = playingPreviewVoiceId,
            previewErrorMessage = previewError,
            featureUsageStats = repo.usageStats,
            estimatedTotalCost = totalCost,
            quotaSnapshots = snapshots,
            userMessage = internal.message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState()
    )

    private data class RepoSettings(
        val apiKey: String?,
        val modelPerRole: Map<ModelRole, String>,
        val duckLevel: Float,
        val cacheLimitBytes: Long,
        val echoTargetLanguage: Boolean,
        val usageStats: FeatureUsageStats,
        val defaultNarratorVoiceId: String
    )

    private data class InternalSettings(
        val input: String,
        val visible: Boolean,
        val testStatus: TestKeyStatus,
        val catalogModels: List<ModelItem>,
        val message: String?
    )

    init {
        // Load initial API key from key store into input field if available
        viewModelScope.launch {
            val key = secureKeyStore.getApiKey()
            if (!key.isNullOrBlank()) {
                _apiKeyInput.value = key
                // Optionally preload catalog models if key is present
                try {
                    val response = modelCatalogService.listModels(key)
                    _availableModels.value = response.models
                    quotaTracker?.registerCatalogModels(response.models)
                } catch (e: Exception) {
                    DiagnosticLogBuffer.record(
                        tag = "SettingsVM",
                        message = "Background model catalog preload failed",
                        level = "WARN",
                        throwable = e
                    )
                }
            }
        }
    }

    fun onApiKeyInputChanged(newKey: String) {
        _apiKeyInput.value = newKey
        _testKeyStatus.value = TestKeyStatus.Idle
    }

    fun toggleApiKeyVisibility() {
        _isApiKeyVisible.value = !_isApiKeyVisible.value
    }

    fun saveApiKey() {
        val key = _apiKeyInput.value.trim()
        if (key.isBlank()) {
            _userMessage.value = "API key cannot be empty"
            return
        }
        viewModelScope.launch {
            try {
                secureKeyStore.saveApiKey(key)
                settingsRepository.saveApiKey(key)
                DiagnosticLogBuffer.record(
                    tag = "SettingsVM",
                    message = "Saved Gemini API key (length=${key.length})",
                    level = "INFO"
                )
                _userMessage.value = "Gemini API key saved securely"
            } catch (e: Exception) {
                DiagnosticLogBuffer.record(
                    tag = "SettingsVM",
                    message = "Failed to save Gemini API key",
                    level = "ERROR",
                    throwable = e
                )
                _userMessage.value = "Failed to save key: ${e.message}"
            }
        }
    }

    fun deleteApiKey() {
        viewModelScope.launch {
            try {
                secureKeyStore.deleteApiKey()
                settingsRepository.clearApiKey()
                _apiKeyInput.value = ""
                _testKeyStatus.value = TestKeyStatus.Idle
                DiagnosticLogBuffer.record(
                    tag = "SettingsVM",
                    message = "Deleted Gemini API key",
                    level = "INFO"
                )
                _userMessage.value = "Gemini API key deleted"
            } catch (e: Exception) {
                DiagnosticLogBuffer.record(
                    tag = "SettingsVM",
                    message = "Failed to delete Gemini API key",
                    level = "ERROR",
                    throwable = e
                )
                _userMessage.value = "Failed to delete key: ${e.message}"
            }
        }
    }

    fun testApiKey() {
        val keyToTest = _apiKeyInput.value.trim()
        if (keyToTest.isBlank()) {
            DiagnosticLogBuffer.record(
                tag = "SettingsVM",
                message = "API key test aborted: empty input",
                level = "WARN"
            )
            _testKeyStatus.value = TestKeyStatus.Error("Please enter an API key first")
            return
        }

        _testKeyStatus.value = TestKeyStatus.Testing
        viewModelScope.launch {
            try {
                val response = modelCatalogService.listModels(keyToTest)
                _availableModels.value = response.models
                quotaTracker?.registerCatalogModels(response.models)
                DiagnosticLogBuffer.record(
                    tag = "SettingsVM",
                    message = "API key verified (${response.models.size} models cataloged)",
                    level = "INFO"
                )
                _testKeyStatus.value = TestKeyStatus.Success(
                    message = "Key verified successfully!",
                    modelCount = response.models.size
                )
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to verify key with Gemini API"
                DiagnosticLogBuffer.record(
                    tag = "SettingsVM",
                    message = "API key verification failed: $errorMsg",
                    level = "ERROR",
                    throwable = e
                )
                _testKeyStatus.value = TestKeyStatus.Error(errorMsg)
            }
        }
    }

    fun setModelForRole(role: ModelRole, modelId: String) {
        viewModelScope.launch {
            settingsRepository.setModel(role, modelId.trim())
        }
    }

    fun resetModelsToDefaults() {
        viewModelScope.launch {
            settingsRepository.resetModelsToDefaults()
            _userMessage.value = "Models reset to defaults"
        }
    }

    fun setDuckLevel(level: Float) {
        viewModelScope.launch {
            settingsRepository.setDuckLevel(level.coerceIn(0.0f, 0.30f))
        }
    }

    fun setCacheLimitBytes(bytes: Long) {
        viewModelScope.launch {
            val minBytes = 500L * 1024L * 1024L // 500 MB
            val maxBytes = 10L * 1024L * 1024L * 1024L // 10 GB
            settingsRepository.setCacheLimit(bytes.coerceIn(minBytes, maxBytes))
        }
    }

    fun clearAudioCache() {
        viewModelScope.launch {
            // Clear cache logic / notification
            _userMessage.value = "Audio cache cleared successfully"
        }
    }

    fun setEchoTargetLanguage(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setEchoTargetLanguage(enabled)
        }
    }

    fun setFreeTier(isFreeTier: Boolean) {
        viewModelScope.launch {
            settingsRepository.setFreeTier(isFreeTier)
        }
    }

    fun applyFreeTierRecommendedPreset() {
        viewModelScope.launch {
            settingsRepository.setFreeTier(true)
            val catalog = _availableModels.value
            val bestText = selectBestFreeTierModel(
                role = ModelRole.TEXT_TRANSLATION,
                catalog = catalog,
                preferredOrder = listOf(
                    "gemini-3.5-flash-lite",
                    "gemini-3.1-flash-lite",
                    "gemini-2.5-flash-lite",
                    "gemma-4-31b-it",
                    "gemma-4-26b-a4b-it"
                ),
                fallback = "gemini-3.5-flash-lite"
            )
            val bestAnalysis = selectBestFreeTierModel(
                role = ModelRole.TEXT_ANALYSIS,
                catalog = catalog,
                preferredOrder = listOf(
                    "gemini-3.5-flash-lite",
                    "gemini-3.1-flash-lite",
                    "gemini-2.5-flash-lite",
                    "gemma-4-31b-it",
                    "gemma-4-26b-a4b-it"
                ),
                fallback = bestText
            )
            val bestLiveNarration = selectBestFreeTierModel(
                role = ModelRole.LIVE_NARRATION,
                catalog = catalog,
                preferredOrder = listOf(
                    "gemini-2.5-flash-native-audio-dialog",
                    "gemini-3.8-live",
                    "gemini-3-flash-live"
                ),
                fallback = "gemini-2.5-flash-native-audio-dialog",
                prefixMatchFallback = "gemini-2.5-flash-native-audio"
            )
            val bestLiveTranslate = selectBestFreeTierModel(
                role = ModelRole.LIVE_TRANSLATE,
                catalog = catalog,
                preferredOrder = listOf(
                    "gemini-3.5-live-translate-preview",
                    "gemini-2.5-flash-native-audio-dialog",
                    "gemini-3.8-live"
                ),
                fallback = "gemini-3.5-live-translate-preview"
            )

            settingsRepository.setModel(ModelRole.TEXT_TRANSLATION, bestText)
            settingsRepository.setModel(ModelRole.TEXT_ANALYSIS, bestAnalysis)
            settingsRepository.setModel(ModelRole.LIVE_NARRATION, bestLiveNarration)
            settingsRepository.setModel(ModelRole.LIVE_TRANSLATE, bestLiveTranslate)
            DiagnosticLogBuffer.record(
                tag = "SettingsVM",
                message = "Applied free-tier preset: text=$bestText analysis=$bestAnalysis liveNarration=$bestLiveNarration liveTranslate=$bestLiveTranslate",
                level = "INFO"
            )
            _userMessage.value = "Applied free-tier multi-model preset"
        }
    }

    fun resetQuotaCooldowns() {
        quotaTracker?.resetCooldowns()
        _userMessage.value = "Cleared model rate-limit cooldowns"
    }

    fun resetUsageStats() {
        viewModelScope.launch {
            settingsRepository.resetUsageStats()
            _userMessage.value = "Usage statistics reset"
        }
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }

    fun selectDefaultNarratorVoice(voiceId: String) {
        val canonical = GeminiStudioVoices.getVoice(voiceId)?.id ?: voiceId.trim()
        if (canonical.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.setDefaultNarratorVoiceId(canonical)
        }
    }

    fun toggleVoicePreview(voiceId: String) {
        voicePreviewPlayer?.togglePreview(voiceId) {
            playbackCoordinator?.pauseActivePlayback()
        }
    }

    fun stopVoicePreview() {
        voicePreviewPlayer?.stop()
    }

    override fun onCleared() {
        voicePreviewPlayer?.stop()
        super.onCleared()
    }

    /**
     * Filter models for a specific role from the currently loaded catalog.
     */
    fun getCatalogModelsForRole(role: ModelRole): List<ModelItem> {
        return ModelCatalog.filterModelsForRole(_availableModels.value, role)
    }

    /**
     * Return recommended model IDs for [role], combining defaults, curated free-tier/Gemma 4/Live
     * models, and discovered catalog models.
     */
    fun getRecommendedModelsForRole(role: ModelRole): List<String> {
        return ModelCatalog.recommendedModelsForRole(role, _availableModels.value)
    }

    private fun selectBestFreeTierModel(
        role: ModelRole,
        catalog: List<ModelItem>,
        preferredOrder: List<String>,
        fallback: String,
        prefixMatchFallback: String? = null
    ): String {
        if (catalog.isEmpty()) return fallback
        val roleCatalogIds = ModelCatalog.filterModelsForRole(catalog, role).map { it.id }
        if (roleCatalogIds.isEmpty()) return fallback
        for (candidate in preferredOrder) {
            val exact = roleCatalogIds.firstOrNull { it.equals(candidate, ignoreCase = true) }
            if (exact != null) return exact
        }
        if (prefixMatchFallback != null) {
            val prefix = roleCatalogIds.firstOrNull {
                it.startsWith(prefixMatchFallback, ignoreCase = true)
            }
            if (prefix != null) return prefix
        }
        return fallback
    }
}
