package io.github.danprav04.polyglotbooks.core.data.prefs

import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.flow.Flow

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
}

