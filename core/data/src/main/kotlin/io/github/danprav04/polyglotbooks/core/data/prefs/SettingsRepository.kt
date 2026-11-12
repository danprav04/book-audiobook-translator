package io.github.danprav04.polyglotbooks.core.data.prefs

import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.flow.Flow

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

    suspend fun saveApiKey(key: String)
    suspend fun clearApiKey()
    suspend fun setModel(role: ModelRole, modelId: String)
    suspend fun resetModelsToDefaults()
    suspend fun setTargetLanguage(language: LanguageCode)
    suspend fun setCacheLimit(bytes: Long)
    suspend fun setDuckLevel(level: Float)
    suspend fun setEchoTargetLanguage(echo: Boolean)
}
