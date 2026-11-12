package io.github.danprav04.polyglotbooks.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmKeyManager
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookmarkDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.CharacterDao
import io.github.danprav04.polyglotbooks.core.data.db.NarrationDao
import io.github.danprav04.polyglotbooks.core.data.db.PolyglotDatabase
import io.github.danprav04.polyglotbooks.core.data.db.TranslationCacheDao
import io.github.danprav04.polyglotbooks.core.data.prefs.DataStoreSettingsRepository
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.data.security.TinkSecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalogService
import io.github.danprav04.polyglotbooks.core.gemini.OkHttpModelCatalogService
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "polyglot_settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val KEYSET_NAME = "polyglot_keyset"
    private const val PREF_FILE_NAME = "polyglot_key_prefs"
    private const val MASTER_KEY_URI = "android-keystore://polyglot_master_key"

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideModelCatalogService(okHttpClient: OkHttpClient): ModelCatalogService {
        return OkHttpModelCatalogService(okHttpClient)
    }

    @Provides
    @Singleton
    fun providePolyglotDatabase(@ApplicationContext context: Context): PolyglotDatabase {
        return Room.databaseBuilder(
            context,
            PolyglotDatabase::class.java,
            "polyglot_database"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    fun provideBookDao(database: PolyglotDatabase): BookDao = database.bookDao()

    @Provides
    fun provideChapterDao(database: PolyglotDatabase): ChapterDao = database.chapterDao()

    @Provides
    fun provideBookmarkDao(database: PolyglotDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    fun provideCharacterDao(database: PolyglotDatabase): CharacterDao = database.characterDao()

    @Provides
    fun provideNarrationDao(database: PolyglotDatabase): NarrationDao = database.narrationDao()

    @Provides
    fun provideTranslationCacheDao(database: PolyglotDatabase): TranslationCacheDao = database.translationCacheDao()

    @Provides
    @Singleton
    fun provideTinkAead(@ApplicationContext context: Context): Aead {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
            .withKeyTemplate(AesGcmKeyManager.aes256GcmTemplate())
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        return keysetHandle.getPrimitive(Aead::class.java)
    }

    @Provides
    @Singleton
    fun provideSecureKeyStore(
        @ApplicationContext context: Context,
        aead: Aead
    ): SecureKeyStore {
        return TinkSecureKeyStore(context, aead)
    }

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        dataStore: DataStore<Preferences>,
        secureKeyStore: SecureKeyStore
    ): SettingsRepository {
        return DataStoreSettingsRepository(dataStore, secureKeyStore)
    }
}
