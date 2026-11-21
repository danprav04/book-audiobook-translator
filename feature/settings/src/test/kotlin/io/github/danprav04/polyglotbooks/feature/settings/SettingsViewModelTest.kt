package io.github.danprav04.polyglotbooks.feature.settings

import app.cash.turbine.test
import io.github.danprav04.polyglotbooks.core.audio.VoicePreviewPlayer
import io.github.danprav04.polyglotbooks.core.data.prefs.DataStoreSettingsRepository
import io.github.danprav04.polyglotbooks.core.data.prefs.FeatureUsageStats
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalogService
import io.github.danprav04.polyglotbooks.core.gemini.ModelItem
import io.github.danprav04.polyglotbooks.core.gemini.ModelListResponse
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.playback.PlaybackCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val defaultNarratorVoiceIdFlow = MutableStateFlow("Charon")
    private val livePreloadChunksFlow = MutableStateFlow(DataStoreSettingsRepository.DEFAULT_LIVE_PRELOAD_CHUNKS)
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
            coEvery { defaultNarratorVoiceId } returns defaultNarratorVoiceIdFlow
            coEvery { livePreloadChunks } returns livePreloadChunksFlow
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

    private fun createViewModel(
        quotaTracker: GeminiQuotaTracker? = null,
        voicePreviewPlayer: VoicePreviewPlayer? = null,
        playbackCoordinator: PlaybackCoordinator? = null
    ): SettingsViewModel {
        return SettingsViewModel(
            settingsRepository = settingsRepository,
            secureKeyStore = secureKeyStore,
            modelCatalogService = modelCatalogService,
            quotaTracker = quotaTracker,
            voicePreviewPlayer = voicePreviewPlayer,
            playbackCoordinator = playbackCoordinator
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
        assertEquals("Charon", state.defaultNarratorVoiceId)
        assertEquals(DataStoreSettingsRepository.DEFAULT_LIVE_PRELOAD_CHUNKS, state.livePreloadChunks)
        assertNull(state.playingPreviewVoiceId)
        assertNull(state.previewErrorMessage)
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

    @Test
    fun testSetFreeTierUpdatesRepositoryAndUiState() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setFreeTier(true)
        advanceUntilIdle()
        coVerify { settingsRepository.setFreeTier(true) }

        featureUsageStatsFlow.value = FeatureUsageStats(isFreeTier = true)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.featureUsageStats.isFreeTier)

        viewModel.setFreeTier(false)
        advanceUntilIdle()
        coVerify { settingsRepository.setFreeTier(false) }
    }

    @Test
    fun testCatalogRegistrationInQuotaTrackerOnInitAndTestKey() = runTest {
        val quotaTracker = GeminiQuotaTracker(clockMs = { 1_000_000L })
        val initModels = listOf(
            ModelItem(
                name = "models/gemini-3.5-flash-lite",
                supportedGenerationMethods = listOf("generateContent")
            ),
            ModelItem(
                name = "models/gemini-2.0-flash",
                supportedGenerationMethods = listOf("generateContent")
            )
        )
        coEvery { secureKeyStore.getApiKey() } returns "AIzaSySavedKey"
        coEvery { modelCatalogService.listModels("AIzaSySavedKey") } returns ModelListResponse(models = initModels)

        val viewModel = createViewModel(quotaTracker = quotaTracker)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.quotaSnapshots.containsKey("gemini-3.5-flash-lite"))
        val zeroQuotaSnapshot = viewModel.uiState.value.quotaSnapshots["gemini-2.0-flash"]
        assertTrue(zeroQuotaSnapshot?.permanentlyUnavailable == true)

        val testedModels = initModels + ModelItem(
            name = "models/gemini-2.5-flash-native-audio-dialog",
            supportedGenerationMethods = listOf("bidiGenerateContent")
        )
        coEvery { modelCatalogService.listModels("AIzaSyNewTestedKey") } returns ModelListResponse(models = testedModels)

        viewModel.onApiKeyInputChanged("AIzaSyNewTestedKey")
        viewModel.testApiKey()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.quotaSnapshots.containsKey("gemini-2.5-flash-native-audio-dialog"))
    }

    @Test
    fun testApplyFreeTierRecommendedPresetDefaultAndCataloged() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.applyFreeTierRecommendedPreset()
        advanceUntilIdle()

        coVerify { settingsRepository.setFreeTier(true) }
        coVerify { settingsRepository.setModel(ModelRole.TEXT_TRANSLATION, "gemini-3.5-flash-lite") }
        coVerify { settingsRepository.setModel(ModelRole.TEXT_ANALYSIS, "gemini-3.5-flash-lite") }
        coVerify { settingsRepository.setModel(ModelRole.LIVE_NARRATION, "gemini-3.1-flash-live-preview") }
        coVerify { settingsRepository.setModel(ModelRole.LIVE_TRANSLATE, "gemini-3.5-live-translate-preview") }
        assertEquals("Applied free-tier multi-model preset", viewModel.uiState.value.userMessage)

        val catalogWithoutNativeAudio = listOf(
            ModelItem(
                name = "models/gemini-3.1-flash-lite",
                supportedGenerationMethods = listOf("generateContent")
            ),
            ModelItem(
                name = "models/gemini-3.8-live",
                supportedGenerationMethods = listOf("bidiGenerateContent")
            ),
            ModelItem(
                name = "models/gemini-3.5-live-translate-preview",
                supportedGenerationMethods = listOf("bidiGenerateContent")
            )
        )
        coEvery { modelCatalogService.listModels("AIzaSyCatalogKey") } returns ModelListResponse(models = catalogWithoutNativeAudio)

        viewModel.onApiKeyInputChanged("AIzaSyCatalogKey")
        viewModel.testApiKey()
        advanceUntilIdle()

        viewModel.applyFreeTierRecommendedPreset()
        advanceUntilIdle()

        coVerify { settingsRepository.setModel(ModelRole.TEXT_TRANSLATION, "gemini-3.1-flash-lite") }
        coVerify { settingsRepository.setModel(ModelRole.TEXT_ANALYSIS, "gemini-3.1-flash-lite") }
        coVerify { settingsRepository.setModel(ModelRole.LIVE_NARRATION, "gemini-3.8-live") }
        coVerify { settingsRepository.setModel(ModelRole.LIVE_TRANSLATE, "gemini-3.5-live-translate-preview") }
    }

    @Test
    fun testResetQuotaCooldownsClearsRateLimitAndShowsMessage() = runTest {
        var nowMs = 1_000_000L
        val quotaTracker = GeminiQuotaTracker(clockMs = { nowMs })
        quotaTracker.recordHttpError(
            modelId = "gemini-3.5-flash-lite",
            httpCode = 429,
            responseBody = """{"error":{"message":"Please retry in 30s."}}"""
        )

        val viewModel = createViewModel(quotaTracker = quotaTracker)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.quotaSnapshots["gemini-3.5-flash-lite"]!!.cooldownRemainingMs > 0L)

        viewModel.resetQuotaCooldowns()
        advanceUntilIdle()

        val clearedSnapshot = viewModel.uiState.value.quotaSnapshots["gemini-3.5-flash-lite"]!!
        assertEquals(0L, clearedSnapshot.cooldownRemainingMs)
        assertFalse(clearedSnapshot.exhaustedForDay)
        assertEquals("Cleared model rate-limit cooldowns", viewModel.uiState.value.userMessage)
    }

    @Test
    fun testGetRecommendedModelsForRoleIncludesCuratedAndCatalogModels() = runTest {
        val catalogModels = listOf(
            ModelItem(
                name = "models/gemma-4-31b-it",
                supportedGenerationMethods = listOf("generateContent")
            ),
            ModelItem(
                name = "models/gemini-2.0-flash",
                supportedGenerationMethods = listOf("generateContent")
            ),
            ModelItem(
                name = "models/gemini-custom-live-audio",
                supportedGenerationMethods = listOf("bidiGenerateContent")
            )
        )
        coEvery { modelCatalogService.listModels("AIzaSyRecKey") } returns ModelListResponse(models = catalogModels)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onApiKeyInputChanged("AIzaSyRecKey")
        viewModel.testApiKey()
        advanceUntilIdle()

        val textModels = viewModel.getRecommendedModelsForRole(ModelRole.TEXT_TRANSLATION)
        assertTrue(textModels.contains("gemini-3.5-flash-lite"))
        assertTrue(textModels.contains("gemma-4-31b-it"))
        assertFalse(textModels.contains("gemini-2.0-flash"))

        val liveModels = viewModel.getRecommendedModelsForRole(ModelRole.LIVE_NARRATION)
        assertTrue(liveModels.contains("gemini-3.1-flash-live-preview"))
        assertTrue(liveModels.contains("gemini-2.5-flash-native-audio-latest"))
        assertTrue(liveModels.contains("gemini-custom-live-audio"))
    }

    @Test
    fun testSelectDefaultNarratorVoicePersistsCanonicalVoiceAndIgnoresBlank() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.selectDefaultNarratorVoice("   ")
        advanceUntilIdle()
        coVerify(exactly = 0) { settingsRepository.setDefaultNarratorVoiceId(any()) }

        viewModel.selectDefaultNarratorVoice("kore")
        advanceUntilIdle()
        coVerify { settingsRepository.setDefaultNarratorVoiceId("Kore") }

        defaultNarratorVoiceIdFlow.value = "Kore"
        advanceUntilIdle()
        assertEquals("Kore", viewModel.uiState.value.defaultNarratorVoiceId)
    }

    @Test
    fun testToggleVoicePreviewPausesActivePlaybackExposesStateAndStopsCleanly() = runTest {
        val fakePreviewPlayer = object : VoicePreviewPlayer {
            private val _playing = MutableStateFlow<String?>(null)
            override val playingVoiceId: StateFlow<String?> = _playing.asStateFlow()

            private val _error = MutableStateFlow<String?>(null)
            override val previewError: StateFlow<String?> = _error.asStateFlow()

            var stopCount = 0

            override fun togglePreview(voiceId: String, onBeforeStart: (() -> Unit)?) {
                if (_playing.value == voiceId) {
                    _playing.value = null
                } else {
                    onBeforeStart?.invoke()
                    _error.value = null
                    _playing.value = voiceId
                }
            }

            override fun stop() {
                stopCount++
                _playing.value = null
                _error.value = null
            }

            fun emitError(msg: String) {
                _playing.value = null
                _error.value = msg
            }
        }
        val coordinator = mockk<PlaybackCoordinator>(relaxed = true)

        val viewModel = createViewModel(
            voicePreviewPlayer = fakePreviewPlayer,
            playbackCoordinator = coordinator
        )
        advanceUntilIdle()

        viewModel.toggleVoicePreview("Aoede")
        advanceUntilIdle()
        verify(exactly = 1) { coordinator.pauseActivePlayback() }
        assertEquals("Aoede", viewModel.uiState.value.playingPreviewVoiceId)
        assertNull(viewModel.uiState.value.previewErrorMessage)

        fakePreviewPlayer.emitError("Missing voice asset")
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.playingPreviewVoiceId)
        assertEquals("Missing voice asset", viewModel.uiState.value.previewErrorMessage)

        viewModel.toggleVoicePreview("Charon")
        advanceUntilIdle()
        verify(exactly = 2) { coordinator.pauseActivePlayback() }
        assertEquals("Charon", viewModel.uiState.value.playingPreviewVoiceId)

        viewModel.stopVoicePreview()
        advanceUntilIdle()
        assertEquals(1, fakePreviewPlayer.stopCount)
        assertNull(viewModel.uiState.value.playingPreviewVoiceId)
    }

    @Test
    fun testSetLivePreloadChunksClampsAndPersists() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals(6, viewModel.uiState.value.livePreloadChunks)

        viewModel.setLivePreloadChunks(0)
        advanceUntilIdle()
        coVerify { settingsRepository.setLivePreloadChunks(1) }

        viewModel.setLivePreloadChunks(12)
        advanceUntilIdle()
        coVerify { settingsRepository.setLivePreloadChunks(12) }

        viewModel.setLivePreloadChunks(25)
        advanceUntilIdle()
        coVerify { settingsRepository.setLivePreloadChunks(20) }

        livePreloadChunksFlow.value = 15
        advanceUntilIdle()
        assertEquals(15, viewModel.uiState.value.livePreloadChunks)
    }
}
