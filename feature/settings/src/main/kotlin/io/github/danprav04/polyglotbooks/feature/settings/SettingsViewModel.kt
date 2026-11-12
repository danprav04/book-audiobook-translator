package io.github.danprav04.polyglotbooks.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalog
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalogService
import io.github.danprav04.polyglotbooks.core.gemini.ModelItem
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
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
    val userMessage: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val secureKeyStore: SecureKeyStore,
    private val modelCatalogService: ModelCatalogService
) : ViewModel() {

    private val _apiKeyInput = MutableStateFlow("")
    private val _isApiKeyVisible = MutableStateFlow(false)
    private val _testKeyStatus = MutableStateFlow<TestKeyStatus>(TestKeyStatus.Idle)
    private val _availableModels = MutableStateFlow<List<ModelItem>>(emptyList())
    private val _userMessage = MutableStateFlow<String?>(null)

    private val repoSettings = combine(
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
            echoTargetLanguage = echo
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
        internalState
    ) { repo, internal ->
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
        val echoTargetLanguage: Boolean
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
                } catch (_: Exception) {
                    // Ignore background catalog load failure on init
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
                _userMessage.value = "Gemini API key saved securely"
            } catch (e: Exception) {
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
                _userMessage.value = "Gemini API key deleted"
            } catch (e: Exception) {
                _userMessage.value = "Failed to delete key: ${e.message}"
            }
        }
    }

    fun testApiKey() {
        val keyToTest = _apiKeyInput.value.trim()
        if (keyToTest.isBlank()) {
            _testKeyStatus.value = TestKeyStatus.Error("Please enter an API key first")
            return
        }

        _testKeyStatus.value = TestKeyStatus.Testing
        viewModelScope.launch {
            try {
                val response = modelCatalogService.listModels(keyToTest)
                _availableModels.value = response.models
                _testKeyStatus.value = TestKeyStatus.Success(
                    message = "Key verified successfully!",
                    modelCount = response.models.size
                )
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Failed to verify key with Gemini API"
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

    fun setEchoTargetLanguage(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setEchoTargetLanguage(enabled)
        }
    }

    fun dismissUserMessage() {
        _userMessage.value = null
    }

    /**
     * Filter models for a specific role from the currently loaded catalog.
     */
    fun getCatalogModelsForRole(role: ModelRole): List<ModelItem> {
        return ModelCatalog.filterModelsForRole(_availableModels.value, role)
    }
}
