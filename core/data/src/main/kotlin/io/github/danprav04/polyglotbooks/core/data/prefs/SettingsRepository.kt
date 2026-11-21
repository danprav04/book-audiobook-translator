package io.github.danprav04.polyglotbooks.core.data.prefs

import io.github.danprav04.polyglotbooks.core.model.GeminiStudioVoices
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Usage stats data model for feature tracking.
 */
data class FeatureUsageStats(
    val liveTranslateMinutes: Double = 0.0,
    val ttsCharactersOrMinutes: Double = 0.0,
    val liveNarrationMinutes: Double = 0.0,
    val textAnalysisTokens: Long = 0L,
    val textTranslationTokens: Long = 0L,
    val isFreeTier: Boolean = false
)

/**
 * Repository interface for application settings, backed by Jetpack DataStore and SecureKeyStore.
 */
interface SettingsRepository {
    val apiKey: Flow<String?>
    val modelPerRole: Flow<Map<ModelRole, String>>
    val targetLanguage: Flow<LanguageCode>
    val cacheLimitBytes: Flow<Long>
    val duckLevel: Flow<Float>
    val echoTargetLanguage: Flow<Boolean>
    val featureUsageStats: Flow<FeatureUsageStats>
    val lastOpenedBookId: Flow<String?>

    val readerNarrationEngine: Flow<String>
        get() = flowOf("GEMINI_FLASH_TTS")
    val readerReadTranslated: Flow<Boolean>
        get() = flowOf(false)
    val readerNarrationSpeed: Flow<Float>
        get() = flowOf(1.0f)
    val readerTheme: Flow<String>
        get() = flowOf("LIGHT")
    val readerFontSizeSp: Flow<Int>
        get() = flowOf(18)
    val readerFontFamily: Flow<String>
        get() = flowOf("Serif")
    val defaultNarratorVoiceId: Flow<String>
        get() = flowOf(GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID)
    val livePreloadChunks: Flow<Int>
        get() = flowOf(DataStoreSettingsRepository.DEFAULT_LIVE_PRELOAD_CHUNKS)

    suspend fun saveApiKey(key: String)
    suspend fun clearApiKey()
    suspend fun setModel(role: ModelRole, modelId: String)
    suspend fun resetModelsToDefaults()
    suspend fun setTargetLanguage(language: LanguageCode)
    suspend fun setCacheLimit(bytes: Long)
    suspend fun setDuckLevel(level: Float)
    suspend fun setEchoTargetLanguage(echo: Boolean)
    suspend fun setFreeTier(isFreeTier: Boolean)
    suspend fun setLastOpenedBookId(bookId: String?)
    suspend fun recordUsage(
        liveTranslateMinutesDelta: Double = 0.0,
        ttsMinutesDelta: Double = 0.0,
        liveNarrationMinutesDelta: Double = 0.0,
        textAnalysisTokensDelta: Long = 0L,
        textTranslationTokensDelta: Long = 0L
    )
    suspend fun resetUsageStats()

    suspend fun setReaderNarrationEngine(engineName: String) {}
    suspend fun setReaderReadTranslated(readTranslated: Boolean) {}
    suspend fun setReaderNarrationSpeed(speed: Float) {}
    suspend fun setReaderTheme(themeName: String) {}
    suspend fun setReaderFontSizeSp(sizeSp: Int) {}
    suspend fun setReaderFontFamily(family: String) {}
    suspend fun setDefaultNarratorVoiceId(voiceId: String) {}
    suspend fun setLivePreloadChunks(chunks: Int) {}
}

