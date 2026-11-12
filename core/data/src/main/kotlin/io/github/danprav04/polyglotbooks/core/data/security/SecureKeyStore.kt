package io.github.danprav04.polyglotbooks.core.data.security

/**
 * Interface for securely storing, retrieving, and clearing sensitive credentials (e.g. Gemini API key).
 */
interface SecureKeyStore {
    suspend fun getApiKey(): String?
    suspend fun saveApiKey(key: String)
    suspend fun deleteApiKey()
}
