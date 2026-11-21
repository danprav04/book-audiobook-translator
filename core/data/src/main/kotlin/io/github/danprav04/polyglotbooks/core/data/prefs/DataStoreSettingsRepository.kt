package io.github.danprav04.polyglotbooks.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.GeminiStudioVoices
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Concrete Jetpack DataStore backed implementation of [SettingsRepository].
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val secureKeyStore: SecureKeyStore
) : SettingsRepository {

    private val apiKeyVersion = MutableStateFlow(0L)

    override val apiKey: Flow<String?> = apiKeyVersion
        .map { secureKeyStore.getApiKey() }
        .distinctUntilChanged()

    override val modelPerRole: Flow<Map<ModelRole, String>> = dataStore.data.map { preferences ->
        ModelRole.values().associateWith { role ->
            preferences[modelRoleKey(role)] ?: DefaultModels.get(role)
        }
    }

    override val targetLanguage: Flow<LanguageCode> = dataStore.data.map { preferences ->
        val code = preferences[KEY_TARGET_LANGUAGE] ?: LanguageCode.ENGLISH.code
        LanguageCode.fromCode(code)
    }

    override val cacheLimitBytes: Flow<Long> = dataStore.data.map { preferences ->
        preferences[KEY_CACHE_LIMIT_BYTES] ?: DEFAULT_CACHE_LIMIT_BYTES
    }

    override val duckLevel: Flow<Float> = dataStore.data.map { preferences ->
        preferences[KEY_DUCK_LEVEL] ?: DEFAULT_DUCK_LEVEL
    }

    override val echoTargetLanguage: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_ECHO_TARGET_LANGUAGE] ?: false
    }

    override val featureUsageStats: Flow<FeatureUsageStats> = dataStore.data.map { preferences ->
        FeatureUsageStats(
            liveTranslateMinutes = preferences[KEY_USAGE_LIVE_TRANSLATE] ?: 0.0,
            ttsCharactersOrMinutes = preferences[KEY_USAGE_TTS] ?: 0.0,
            liveNarrationMinutes = preferences[KEY_USAGE_LIVE_NARRATION] ?: 0.0,
            textAnalysisTokens = preferences[KEY_USAGE_TEXT_ANALYSIS] ?: 0L,
            textTranslationTokens = preferences[KEY_USAGE_TEXT_TRANSLATION] ?: 0L,
            isFreeTier = preferences[KEY_IS_FREE_TIER] ?: false
        )
    }

    override val lastOpenedBookId: Flow<String?> = dataStore.data.map { preferences ->
        preferences[KEY_LAST_OPENED_BOOK_ID]?.takeIf { it.isNotBlank() }
    }

    override val readerNarrationEngine: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_READER_NARRATION_ENGINE]?.takeIf { it.isNotBlank() } ?: DEFAULT_READER_NARRATION_ENGINE
    }

    override val readerReadTranslated: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[KEY_READER_READ_TRANSLATED] ?: false
    }

    override val readerNarrationSpeed: Flow<Float> = dataStore.data.map { preferences ->
        (preferences[KEY_READER_NARRATION_SPEED] ?: DEFAULT_READER_NARRATION_SPEED).coerceIn(0.5f, 2.5f)
    }

    override val readerTheme: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_READER_THEME]?.takeIf { it.isNotBlank() } ?: DEFAULT_READER_THEME
    }

    override val readerFontSizeSp: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[KEY_READER_FONT_SIZE_SP] ?: DEFAULT_READER_FONT_SIZE_SP).coerceIn(12, 32)
    }

    override val readerFontFamily: Flow<String> = dataStore.data.map { preferences ->
        preferences[KEY_READER_FONT_FAMILY]?.takeIf { it.isNotBlank() } ?: DEFAULT_READER_FONT_FAMILY
    }

    override val defaultNarratorVoiceId: Flow<String> = dataStore.data.map { preferences ->
        val stored = preferences[KEY_DEFAULT_NARRATOR_VOICE_ID]?.trim()?.takeIf { it.isNotEmpty() }
        if (stored == null) {
            DEFAULT_NARRATOR_VOICE_ID
        } else {
            GeminiStudioVoices.getVoice(stored)?.id ?: stored
        }
    }

    override val livePreloadChunks: Flow<Int> = dataStore.data.map { preferences ->
        (preferences[KEY_LIVE_PRELOAD_CHUNKS] ?: DEFAULT_LIVE_PRELOAD_CHUNKS)
            .coerceIn(MIN_LIVE_PRELOAD_CHUNKS, MAX_LIVE_PRELOAD_CHUNKS)
    }

    override suspend fun saveApiKey(key: String) {
        secureKeyStore.saveApiKey(key)
        apiKeyVersion.update { it + 1L }
    }

    override suspend fun clearApiKey() {
        secureKeyStore.deleteApiKey()
        apiKeyVersion.update { it + 1L }
    }

    override suspend fun setModel(role: ModelRole, modelId: String) {
        dataStore.edit { preferences ->
            preferences[modelRoleKey(role)] = modelId
        }
    }

    override suspend fun resetModelsToDefaults() {
        dataStore.edit { preferences ->
            for (role in ModelRole.values()) {
                preferences.remove(modelRoleKey(role))
            }
        }
    }

    override suspend fun setTargetLanguage(language: LanguageCode) {
        dataStore.edit { preferences ->
            preferences[KEY_TARGET_LANGUAGE] = language.code
        }
    }

    override suspend fun setCacheLimit(bytes: Long) {
        dataStore.edit { preferences ->
            preferences[KEY_CACHE_LIMIT_BYTES] = bytes
        }
    }

    override suspend fun setDuckLevel(level: Float) {
        dataStore.edit { preferences ->
            preferences[KEY_DUCK_LEVEL] = level.coerceIn(0.0f, 1.0f)
        }
    }

    override suspend fun setEchoTargetLanguage(echo: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_ECHO_TARGET_LANGUAGE] = echo
        }
    }

    override suspend fun setFreeTier(isFreeTier: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_IS_FREE_TIER] = isFreeTier
        }
    }

    override suspend fun setLastOpenedBookId(bookId: String?) {
        dataStore.edit { preferences ->
            if (bookId.isNullOrBlank()) {
                preferences.remove(KEY_LAST_OPENED_BOOK_ID)
            } else {
                preferences[KEY_LAST_OPENED_BOOK_ID] = bookId
            }
        }
    }

    override suspend fun recordUsage(
        liveTranslateMinutesDelta: Double,
        ttsMinutesDelta: Double,
        liveNarrationMinutesDelta: Double,
        textAnalysisTokensDelta: Long,
        textTranslationTokensDelta: Long
    ) {
        dataStore.edit { preferences ->
            val curLiveTranslate = preferences[KEY_USAGE_LIVE_TRANSLATE] ?: 0.0
            val curTts = preferences[KEY_USAGE_TTS] ?: 0.0
            val curLiveNarration = preferences[KEY_USAGE_LIVE_NARRATION] ?: 0.0
            val curAnalysis = preferences[KEY_USAGE_TEXT_ANALYSIS] ?: 0L
            val curTranslation = preferences[KEY_USAGE_TEXT_TRANSLATION] ?: 0L

            preferences[KEY_USAGE_LIVE_TRANSLATE] = curLiveTranslate + liveTranslateMinutesDelta
            preferences[KEY_USAGE_TTS] = curTts + ttsMinutesDelta
            preferences[KEY_USAGE_LIVE_NARRATION] = curLiveNarration + liveNarrationMinutesDelta
            preferences[KEY_USAGE_TEXT_ANALYSIS] = curAnalysis + textAnalysisTokensDelta
            preferences[KEY_USAGE_TEXT_TRANSLATION] = curTranslation + textTranslationTokensDelta
        }
    }

    override suspend fun resetUsageStats() {
        dataStore.edit { preferences ->
            preferences[KEY_USAGE_LIVE_TRANSLATE] = 0.0
            preferences[KEY_USAGE_TTS] = 0.0
            preferences[KEY_USAGE_LIVE_NARRATION] = 0.0
            preferences[KEY_USAGE_TEXT_ANALYSIS] = 0L
            preferences[KEY_USAGE_TEXT_TRANSLATION] = 0L
        }
    }

    override suspend fun setReaderNarrationEngine(engineName: String) {
        val trimmed = engineName.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { preferences ->
            preferences[KEY_READER_NARRATION_ENGINE] = trimmed
        }
    }

    override suspend fun setReaderReadTranslated(readTranslated: Boolean) {
        dataStore.edit { preferences ->
            preferences[KEY_READER_READ_TRANSLATED] = readTranslated
        }
    }

    override suspend fun setReaderNarrationSpeed(speed: Float) {
        dataStore.edit { preferences ->
            preferences[KEY_READER_NARRATION_SPEED] = speed.coerceIn(0.5f, 2.5f)
        }
    }

    override suspend fun setReaderTheme(themeName: String) {
        val trimmed = themeName.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { preferences ->
            preferences[KEY_READER_THEME] = trimmed
        }
    }

    override suspend fun setReaderFontSizeSp(sizeSp: Int) {
        dataStore.edit { preferences ->
            preferences[KEY_READER_FONT_SIZE_SP] = sizeSp.coerceIn(12, 32)
        }
    }

    override suspend fun setReaderFontFamily(family: String) {
        val trimmed = family.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { preferences ->
            preferences[KEY_READER_FONT_FAMILY] = trimmed
        }
    }

    override suspend fun setDefaultNarratorVoiceId(voiceId: String) {
        val trimmed = voiceId.trim()
        if (trimmed.isEmpty()) return
        val canonical = GeminiStudioVoices.getVoice(trimmed)?.id ?: trimmed
        dataStore.edit { preferences ->
            preferences[KEY_DEFAULT_NARRATOR_VOICE_ID] = canonical
        }
    }

    override suspend fun setLivePreloadChunks(chunks: Int) {
        val clamped = chunks.coerceIn(MIN_LIVE_PRELOAD_CHUNKS, MAX_LIVE_PRELOAD_CHUNKS)
        dataStore.edit { preferences ->
            preferences[KEY_LIVE_PRELOAD_CHUNKS] = clamped
        }
    }

    companion object {
        val KEY_TARGET_LANGUAGE = stringPreferencesKey("target_language_code")
        val KEY_CACHE_LIMIT_BYTES = longPreferencesKey("cache_limit_bytes")
        val KEY_DUCK_LEVEL = floatPreferencesKey("duck_level")
        val KEY_ECHO_TARGET_LANGUAGE = booleanPreferencesKey("echo_target_language")
        val KEY_IS_FREE_TIER = booleanPreferencesKey("is_free_tier")
        val KEY_LAST_OPENED_BOOK_ID = stringPreferencesKey("last_opened_book_id")

        val KEY_READER_NARRATION_ENGINE = stringPreferencesKey("reader_narration_engine")
        val KEY_READER_READ_TRANSLATED = booleanPreferencesKey("reader_read_translated")
        val KEY_READER_NARRATION_SPEED = floatPreferencesKey("reader_narration_speed")
        val KEY_READER_THEME = stringPreferencesKey("reader_theme")
        val KEY_READER_FONT_SIZE_SP = intPreferencesKey("reader_font_size_sp")
        val KEY_READER_FONT_FAMILY = stringPreferencesKey("reader_font_family")
        val KEY_DEFAULT_NARRATOR_VOICE_ID = stringPreferencesKey("default_narrator_voice_id")
        val KEY_LIVE_PRELOAD_CHUNKS = intPreferencesKey("live_preload_chunks")

        val KEY_USAGE_LIVE_TRANSLATE = doublePreferencesKey("usage_live_translate_minutes")
        val KEY_USAGE_TTS = doublePreferencesKey("usage_tts_minutes")
        val KEY_USAGE_LIVE_NARRATION = doublePreferencesKey("usage_live_narration_minutes")
        val KEY_USAGE_TEXT_ANALYSIS = longPreferencesKey("usage_text_analysis_tokens")
        val KEY_USAGE_TEXT_TRANSLATION = longPreferencesKey("usage_text_translation_tokens")

        const val DEFAULT_CACHE_LIMIT_BYTES: Long = 1024L * 1024L * 1024L // 1 GB
        const val DEFAULT_DUCK_LEVEL: Float = 0.15f // 15% volume
        const val DEFAULT_READER_NARRATION_ENGINE: String = "GEMINI_FLASH_TTS"
        const val DEFAULT_READER_NARRATION_SPEED: Float = 1.0f
        const val DEFAULT_READER_THEME: String = "LIGHT"
        const val DEFAULT_READER_FONT_SIZE_SP: Int = 18
        const val DEFAULT_READER_FONT_FAMILY: String = "Serif"
        const val DEFAULT_NARRATOR_VOICE_ID: String = GeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID
        const val MIN_LIVE_PRELOAD_CHUNKS: Int = 1
        const val MAX_LIVE_PRELOAD_CHUNKS: Int = 20
        const val DEFAULT_LIVE_PRELOAD_CHUNKS: Int = 6

        fun modelRoleKey(role: ModelRole) = stringPreferencesKey("model_role_${role.name}")
    }
}
