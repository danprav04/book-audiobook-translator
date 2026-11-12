package io.github.danprav04.polyglotbooks.core.data

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.subtle.AesGcmJce
import io.github.danprav04.polyglotbooks.core.data.security.TinkSecureKeyStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom

class SecureKeyStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockContext: Context
    private lateinit var fakeAead: Aead
    private lateinit var keyStore: TinkSecureKeyStore

    @Before
    fun setUp() {
        mockContext = mockk()
        val filesDir = tempFolder.newFolder("app_files")
        every { mockContext.filesDir } returns filesDir

        // Real in-memory AEAD using standard 256-bit AES-GCM (pure JVM, zero Android Keystore daemon needed)
        val key = ByteArray(32)
        SecureRandom().nextBytes(key)
        fakeAead = AesGcmJce(key)

        keyStore = TinkSecureKeyStore(mockContext, fakeAead)
    }

    @Test
    fun testGetApiKeyWhenEmptyReturnsNull() = runTest {
        assertNull(keyStore.getApiKey())
    }

    @Test
    fun testSaveAndGetApiKeyRoundTrip() = runTest {
        val testApiKey = "AIzaSyFakeKeyForPolyglotBooks12345"
        keyStore.saveApiKey(testApiKey)

        val retrievedKey = keyStore.getApiKey()
        assertEquals(testApiKey, retrievedKey)
    }

    @Test
    fun testDeleteApiKey() = runTest {
        val testApiKey = "AIzaSyDeleteMeKey"
        keyStore.saveApiKey(testApiKey)
        assertEquals(testApiKey, keyStore.getApiKey())

        keyStore.deleteApiKey()
        assertNull(keyStore.getApiKey())
    }

    @Test
    fun testCorruptedKeyFileReturnsNull() = runTest {
        val testApiKey = "AIzaSyKey"
        keyStore.saveApiKey(testApiKey)

        // Corrupt file contents
        val file = File(mockContext.filesDir, "secure_gemini_api_key.enc")
        file.writeText("CorruptedBase64CiphertextThatFailsDecryption")

        assertNull(keyStore.getApiKey())
    }
}
