package io.github.danprav04.polyglotbooks.core.data.security

import android.content.Context
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/**
 * Google Tink AEAD implementation of [SecureKeyStore].
 *
 * Encrypts sensitive keys with an injected [Aead] instance (backed by Android Keystore in production,
 * or in-memory AEAD in JVM tests) and stores the encrypted ciphertext in private app storage.
 */
class TinkSecureKeyStore(
    private val context: Context,
    private val aead: Aead
) : SecureKeyStore {

    private val keyFile: File
        get() = File(context.filesDir, KEY_FILE_NAME)

    override suspend fun getApiKey(): String? = withContext(Dispatchers.IO) {
        if (!keyFile.exists()) return@withContext null
        try {
            val encodedCiphertext = keyFile.readText(Charsets.UTF_8).trim()
            if (encodedCiphertext.isEmpty()) return@withContext null
            val ciphertext = Base64.getDecoder().decode(encodedCiphertext)
            val decryptedBytes = aead.decrypt(ciphertext, ASSOCIATED_DATA)
            val key = String(decryptedBytes, Charsets.UTF_8).trim()
            if (key.isBlank()) null else key
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun saveApiKey(key: String): Unit = withContext(Dispatchers.IO) {
        require(key.isNotBlank()) { "API key must not be blank" }
        val plaintextBytes = key.toByteArray(Charsets.UTF_8)
        val ciphertext = aead.encrypt(plaintextBytes, ASSOCIATED_DATA)
        val encodedCiphertext = Base64.getEncoder().encodeToString(ciphertext)
        keyFile.writeText(encodedCiphertext, Charsets.UTF_8)
    }

    override suspend fun deleteApiKey(): Unit = withContext(Dispatchers.IO) {
        if (keyFile.exists()) {
            keyFile.delete()
        }
    }

    companion object {
        private const val KEY_FILE_NAME = "secure_gemini_api_key.enc"
        private val ASSOCIATED_DATA = "polyglot_books_gemini_key".toByteArray(Charsets.UTF_8)
    }
}
