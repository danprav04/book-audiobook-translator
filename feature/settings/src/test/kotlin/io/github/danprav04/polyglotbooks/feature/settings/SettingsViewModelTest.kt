package io.github.danprav04.polyglotbooks.feature.settings

import app.cash.turbine.test
import io.github.danprav04.polyglotbooks.core.data.prefs.FeatureUsageStats
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalogService
import io.github.danprav04.polyglotbooks.core.gemini.ModelItem
import io.github.danprav04.polyglotbooks.core.gemini.ModelListResponse
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var secureKeyStore: SecureKeyStore
    private lateinit var modelCatalogService: ModelCatalogService

    private val apiKeyFlow = MutableStateFlow<String?>(null)
    private val modelPerRoleFlow = MutableStateFlow(DefaultModels.map)
    private val targetLanguageFlow = MutableStateFlow(LanguageCode.ENGLISH)
    private val cacheLimitBytesFlow = MutableStateFlow(1024L * 1024L * 1024L)
    private val duckLevelFlow = MutableStateFlow(0.15f)
    private val echoTargetLanguageFlow = MutableStateFlow(false)
    private val featureUsageStatsFlow = MutableStateFlow(FeatureUsageStats())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        settingsRepository = mockk(relaxed = true) {
            coEvery { apiKey } returns apiKeyFlow
            coEvery { modelPerRole } returns modelPerRoleFlow
            coEvery { targetLanguage } returns targetLanguageFlow
            coEvery { cacheLimitBytes } returns cacheLimitBytesFlow
            coEvery { duckLevel } returns duckLevelFlow
            coEvery { echoTargetLanguage } returns echoTargetLanguageFlow
            coEvery { featureUsageStats } returns featureUsageStatsFlow
        }

        secureKeyStore = mockk(relaxed = true) {
            coEvery { getApiKey() } returns null
        }

        modelCatalogService = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(
            settingsRepository = settingsRepository,
            secureKeyStore = secureKeyStore,
            modelCatalogService = modelCatalogService
        )
    }

    @Test
    fun testInitialUiState() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("", state.apiKeyInput)
        assertFalse(state.isApiKeyVisible)
        assertNull(state.savedApiKey)
        assertEquals(TestKeyStatus.Idle, state.testKeyStatus)
        assertEquals(0.15f, state.duckLevel, 0.001f)
        assertEquals(1024L * 1024L * 1024L, state.cacheLimitBytes)
        assertFalse(state.echoTargetLanguage)
        assertEquals(DefaultModels.map, state.modelPerRole)
    }

    @Test
    fun testApiKeyInputAndVisibilityToggle() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onApiKeyInputChanged("AIzaSyTest123")
        advanceUntilIdle()
        assertEquals("AIzaSyTest123", viewModel.uiState.value.apiKeyInput)

        viewModel.toggleApiKeyVisibility()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isApiKeyVisible)

        viewModel.toggleApiKeyVisibility()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isApiKeyVisible)
    }

    @Test
    fun testSaveApiKey() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onApiKeyInputChanged("AIzaSyNewKey")
        viewModel.saveApiKey()
        advanceUntilIdle()

        coVerify { secureKeyStore.saveApiKey("AIzaSyNewKey") }
        coVerify { settingsRepository.saveApiKey("AIzaSyNewKey") }
        assertEquals("Gemini API key saved securely", viewModel.uiState.value.userMessage)
    }

    @Test
    fun testDeleteApiKey() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onApiKeyInputChanged("AIzaSyToDelete")
        viewModel.deleteApiKey()
        advanceUntilIdle()

        coVerify { secureKeyStore.deleteApiKey() }
        coVerify { settingsRepository.clearApiKey() }
        assertEquals("", viewModel.uiState.value.apiKeyInput)
        assertEquals("Gemini API key deleted", viewModel.uiState.value.userMessage)
    }

    @Test
    fun testTestKeyFlowSuccess() = runTest {
        val mockModels = listOf(
            ModelItem(
                name = "models/gemini-3.5-live-translate-preview",
                displayName = "Live Translate",
                supportedGenerationMethods = listOf("bidiGenerateContent")
            ),
            ModelItem(
                name = "models/gemini-3.8-flash-tts",
                displayName = "Flash TTS",
                supportedGenerationMethods = listOf("interactions")
            )
        )
        coEvery { modelCatalogService.listModels("AIzaSyValidKey") } returns ModelListResponse(models = mockModels)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onApiKeyInputChanged("AIzaSyValidKey")
        viewModel.testApiKey()

        // Before advance, state should be testing
        // Advance dispatcher to complete coroutine
        advanceUntilIdle()

        val status = viewModel.uiState.value.testKeyStatus
        assertTrue(status is TestKeyStatus.Success)
        val success = status as TestKeyStatus.Success
        assertEquals(2, success.modelCount)
        assertEquals(2, viewModel.uiState.value.availableModels.size)
    }

    @Test
    fun testTestKeyFlowError() = runTest {
        coEvery { modelCatalogService.listModels("InvalidKey") } throws IOException("HTTP 400 Bad API Key")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onApiKeyInputChanged("InvalidKey")
        viewModel.testApiKey()
        advanceUntilIdle()

        val status = viewModel.uiState.value.testKeyStatus
        assertTrue(status is TestKeyStatus.Error)
        val error = status as TestKeyStatus.Error
        assertTrue(error.errorMessage.contains("HTTP 400"))
    }

    @Test
    fun testResetModelsToDefaults() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.resetModelsToDefaults()
        advanceUntilIdle()

        coVerify { settingsRepository.resetModelsToDefaults() }
        assertEquals("Models reset to defaults", viewModel.uiState.value.userMessage)
    }

    @Test
    fun testSetAudioAndStorageSettings() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setDuckLevel(0.20f)
        advanceUntilIdle()
        coVerify { settingsRepository.setDuckLevel(0.20f) }

        viewModel.setCacheLimitBytes(2L * 1024L * 1024L * 1024L)
        advanceUntilIdle()
        coVerify { settingsRepository.setCacheLimit(2L * 1024L * 1024L * 1024L) }

        viewModel.setEchoTargetLanguage(true)
        advanceUntilIdle()
        coVerify { settingsRepository.setEchoTargetLanguage(true) }
    }
}
