package io.github.danprav04.polyglotbooks.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import io.github.danprav04.polyglotbooks.core.data.prefs.DataStoreSettingsRepository
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var secureKeyStore: SecureKeyStore
    private lateinit var repository: DataStoreSettingsRepository

    @Before
    fun setUp() {
        val testFile = tempFolder.newFile("test_settings.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { testFile }
        )
        secureKeyStore = mockk(relaxed = true)
        repository = DataStoreSettingsRepository(dataStore, secureKeyStore)
    }

    @Test
    fun testDefaultModelsMatchPlan() = runTest {
        repository.modelPerRole.test {
            val initial = awaitItem()
            for (role in ModelRole.values()) {
                assertEquals(DefaultModels.get(role), initial[role])
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testSetAndResetModelRoles() = runTest {
        repository.modelPerRole.test {
            val initial = awaitItem()
            assertEquals("gemini-3.5-live-translate-preview", initial[ModelRole.LIVE_TRANSLATE])

            repository.setModel(ModelRole.LIVE_TRANSLATE, "custom-model-id")
            val updated = awaitItem()
            assertEquals("custom-model-id", updated[ModelRole.LIVE_TRANSLATE])

            repository.resetModelsToDefaults()
            val reset = awaitItem()
            assertEquals("gemini-3.5-live-translate-preview", reset[ModelRole.LIVE_TRANSLATE])

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testTargetLanguageFlow() = runTest {
        repository.targetLanguage.test {
            val initial = awaitItem()
            assertEquals("en", initial.code)

            repository.setTargetLanguage(LanguageCode.HEBREW)
            val updated = awaitItem()
            assertEquals("he", updated.code)
            assertEquals("Hebrew", updated.displayName)
            assertTrue(updated.isLiveTranslateSupported)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testCacheLimitFlow() = runTest {
        repository.cacheLimitBytes.test {
            val initial = awaitItem()
            assertEquals(1024L * 1024L * 1024L, initial)

            val customLimit = 2L * 1024L * 1024L * 1024L // 2GB
            repository.setCacheLimit(customLimit)
            val updated = awaitItem()
            assertEquals(customLimit, updated)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testDuckLevelFlow() = runTest {
        repository.duckLevel.test {
            val initial = awaitItem()
            assertEquals(0.15f, initial, 0.001f)

            repository.setDuckLevel(0.25f)
            val updated = awaitItem()
            assertEquals(0.25f, updated, 0.001f)

            // Clamp check
            repository.setDuckLevel(1.5f)
            val clamped = awaitItem()
            assertEquals(1.0f, clamped, 0.001f)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testEchoTargetLanguageFlow() = runTest {
        repository.echoTargetLanguage.test {
            val initial = awaitItem()
            assertFalse(initial)

            repository.setEchoTargetLanguage(true)
            val updated = awaitItem()
            assertTrue(updated)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testApiKeyDelegation() = runTest {
        coEvery { secureKeyStore.getApiKey() } returns "my-api-key"

        repository.apiKey.test {
            val key = awaitItem()
            assertEquals("my-api-key", key)
            cancelAndIgnoreRemainingEvents()
        }

        repository.saveApiKey("new-key")
        coVerify { secureKeyStore.saveApiKey("new-key") }

        repository.clearApiKey()
        coVerify { secureKeyStore.deleteApiKey() }
    }

    @Test
    fun testApiKeyFlowReactsToSaveAndClear() = runTest {
        val backingStore = object : SecureKeyStore {
            private var stored: String? = null
            override suspend fun getApiKey(): String? = stored
            override suspend fun saveApiKey(key: String) {
                require(key.isNotBlank()) { "API key must not be blank" }
                stored = key.trim()
            }
            override suspend fun deleteApiKey() {
                stored = null
            }
        }
        val reactiveRepo = DataStoreSettingsRepository(dataStore, backingStore)

        reactiveRepo.apiKey.test {
            assertNull(awaitItem())

            reactiveRepo.saveApiKey("AIzaSyFirstKey")
            assertEquals("AIzaSyFirstKey", awaitItem())

            reactiveRepo.saveApiKey("AIzaSySecondKey")
            assertEquals("AIzaSySecondKey", awaitItem())

            reactiveRepo.clearApiKey()
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun testLastOpenedBookIdFlow() = runTest {
        repository.lastOpenedBookId.test {
            assertNull(awaitItem())

            repository.setLastOpenedBookId("book-123")
            assertEquals("book-123", awaitItem())

            repository.setLastOpenedBookId("book-456")
            assertEquals("book-456", awaitItem())

            repository.setLastOpenedBookId(null)
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }
}
