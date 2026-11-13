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
import io.github.danprav04.polyglotbooks.core.audio.AudioTrackPcmPlayer
import io.github.danprav04.polyglotbooks.core.audio.Chunker
import io.github.danprav04.polyglotbooks.core.audio.DiskTranslatedCache
import io.github.danprav04.polyglotbooks.core.audio.MediaCodecSourceDecoder
import io.github.danprav04.polyglotbooks.core.audio.PcmPlayer
import io.github.danprav04.polyglotbooks.core.audio.Resampler
import io.github.danprav04.polyglotbooks.core.audio.SourceDecoder
import io.github.danprav04.polyglotbooks.core.audio.StreamingLinearResampler
import io.github.danprav04.polyglotbooks.core.audio.TranslatedCache
import io.github.danprav04.polyglotbooks.core.data.cache.DefaultTranslatedCacheIndex
import io.github.danprav04.polyglotbooks.core.data.cache.TranslatedCacheIndex
import io.github.danprav04.polyglotbooks.core.data.db.BookDao
import io.github.danprav04.polyglotbooks.core.data.db.BookmarkDao
import io.github.danprav04.polyglotbooks.core.data.db.ChapterDao
import io.github.danprav04.polyglotbooks.core.data.db.CharacterDao
import io.github.danprav04.polyglotbooks.core.data.db.NarrationDao
import io.github.danprav04.polyglotbooks.core.data.db.PlaybackStateDao
import io.github.danprav04.polyglotbooks.core.data.db.PolyglotDatabase
import io.github.danprav04.polyglotbooks.core.data.db.TranslationCacheDao
import io.github.danprav04.polyglotbooks.core.data.prefs.DataStoreSettingsRepository
import io.github.danprav04.polyglotbooks.core.data.prefs.SettingsRepository
import io.github.danprav04.polyglotbooks.core.data.security.SecureKeyStore
import io.github.danprav04.polyglotbooks.core.data.security.TinkSecureKeyStore
import io.github.danprav04.polyglotbooks.core.gemini.DefaultLiveSessionFactory
import io.github.danprav04.polyglotbooks.core.gemini.DefaultLiveTranslateClient
import io.github.danprav04.polyglotbooks.core.gemini.LiveSessionFactory
import io.github.danprav04.polyglotbooks.core.gemini.LiveTranslateClient
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalogService
import io.github.danprav04.polyglotbooks.core.gemini.OkHttpModelCatalogService
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.playback.ApplicationScope
import io.github.danprav04.polyglotbooks.core.playback.LiveTranslationBackend
import io.github.danprav04.polyglotbooks.core.playback.LiveTranslationBackendState
import io.github.danprav04.polyglotbooks.feature.translate.DefaultTimelineMapper
import io.github.danprav04.polyglotbooks.feature.translate.Pacer
import io.github.danprav04.polyglotbooks.feature.translate.TranslationPipeline
import io.github.danprav04.polyglotbooks.feature.translate.TranslationPipelineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "polyglot_settings")

/**
 * Adapter bridging [TranslationPipeline] (`:feature:translate`) to [LiveTranslationBackend] (`:core:playback`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationPipelineBackend(
    private val pipeline: TranslationPipeline,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
) : LiveTranslationBackend {

    private val _state = MutableStateFlow(pipeline.state.value.toBackendState())
    override val state: StateFlow<LiveTranslationBackendState> = _state.asStateFlow()

    init {
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            pipeline.state.collect { pipelineState ->
                _state.value = pipelineState.toBackendState()
            }
        }
    }

    override fun start(
        scope: CoroutineScope,
        bookId: String,
        filePath: String,
        targetLanguage: LanguageCode,
        apiKey: String?,
        modelId: String,
        echoTargetLanguage: Boolean,
        startPositionMs: Long,
        playbackSpeed: Float
    ) {
        pipeline.start(
            scope = scope,
            bookId = bookId,
            filePath = filePath,
            targetLanguage = targetLanguage,
            apiKey = apiKey,
            modelId = modelId,
            echoTargetLanguage = echoTargetLanguage,
            startPositionMs = startPositionMs,
            playbackSpeed = playbackSpeed
        )
        _state.value = pipeline.state.value.toBackendState()
    }

    override fun updatePlaybackPosition(positionMs: Long) {
        pipeline.updatePlaybackPosition(positionMs)
        _state.value = pipeline.state.value.toBackendState()
    }

    override fun setPlaybackSpeed(speed: Float) {
        pipeline.setPlaybackSpeed(speed)
        _state.value = pipeline.state.value.toBackendState()
    }

    override fun pauseAudio() {
        pipeline.pauseAudio()
        _state.value = pipeline.state.value.toBackendState()
    }

    override fun resumeAudio() {
        pipeline.resumeAudio()
        _state.value = pipeline.state.value.toBackendState()
    }

    override fun stop() {
        pipeline.stop()
        _state.value = pipeline.state.value.toBackendState()
    }

    private fun TranslationPipelineState.toBackendState(): LiveTranslationBackendState =
        LiveTranslationBackendState(
            isRunning = isRunning,
            sourcePositionMs = sourcePositionMs,
            bufferAheadMs = bufferAheadMs,
            speedMultiplier = speedMultiplier,
            currentSourceSubtitle = currentSourceSubtitle,
            currentTranslatedSubtitle = currentTranslatedSubtitle,
            errorMessage = errorMessage
        )
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private const val KEYSET_NAME = "polyglot_keyset"
    private const val PREF_FILE_NAME = "polyglot_key_prefs"
    private const val MASTER_KEY_URI = "android-keystore://polyglot_master_key"

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideModelCatalogService(okHttpClient: OkHttpClient): ModelCatalogService {
        return OkHttpModelCatalogService(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideLiveSessionFactory(okHttpClient: OkHttpClient): LiveSessionFactory {
        return DefaultLiveSessionFactory(okHttpClient)
    }

    @Provides
    @Singleton
    fun provideLiveTranslateClient(
        liveSessionFactory: LiveSessionFactory,
        modelCatalogService: ModelCatalogService
    ): LiveTranslateClient {
        return DefaultLiveTranslateClient(
            sessionFactory = liveSessionFactory,
            modelCatalogService = modelCatalogService
        )
    }

    @Provides
    @Singleton
    fun provideSourceDecoder(@ApplicationContext context: Context): SourceDecoder {
        return MediaCodecSourceDecoder(context)
    }

    @Provides
    @Singleton
    fun provideResampler(): Resampler {
        return StreamingLinearResampler()
    }

    @Provides
    @Singleton
    fun provideChunker(): Chunker {
        return Chunker(TranslationPipeline.CHUNK_SIZE_BYTES)
    }

    @Provides
    @Singleton
    fun providePcmPlayer(): PcmPlayer {
        return AudioTrackPcmPlayer()
    }

    @Provides
    @Singleton
    fun provideTranslatedCache(
        @ApplicationContext context: Context,
        translationCacheDao: TranslationCacheDao
    ): TranslatedCache {
        return DiskTranslatedCache(
            cacheDir = File(context.cacheDir, "translated_audio"),
            cacheDao = translationCacheDao
        )
    }

    @Provides
    @Singleton
    fun provideTranslatedCacheIndex(
        translationCacheDao: TranslationCacheDao
    ): TranslatedCacheIndex {
        return DefaultTranslatedCacheIndex(translationCacheDao)
    }

    @Provides
    @Singleton
    fun provideTranslationPipeline(
        sourceDecoder: SourceDecoder,
        resampler: Resampler,
        chunker: Chunker,
        liveTranslateClient: LiveTranslateClient,
        pcmPlayer: PcmPlayer,
        translatedCache: TranslatedCache,
        translatedCacheIndex: TranslatedCacheIndex
    ): TranslationPipeline {
        return TranslationPipeline(
            sourceDecoder = sourceDecoder,
            resampler = resampler,
            chunker = chunker,
            pacer = Pacer(
                minBufferAheadMs = 4_000L,
                targetBufferAheadMs = 12_000L,
                maxSendSpeed = 1.25f,
                enableBackpressure = true
            ),
            timelineMapper = DefaultTimelineMapper(),
            liveTranslateClient = liveTranslateClient,
            pcmPlayer = pcmPlayer,
            translatedCache = translatedCache,
            translatedCacheIndex = translatedCacheIndex
        )
    }

    @Provides
    @Singleton
    fun provideLiveTranslationBackend(
        translationPipeline: TranslationPipeline,
        @ApplicationScope applicationScope: CoroutineScope
    ): LiveTranslationBackend {
        return TranslationPipelineBackend(translationPipeline, applicationScope)
    }

    @Provides
    @Singleton
    fun providePolyglotDatabase(@ApplicationContext context: Context): PolyglotDatabase {
        return Room.databaseBuilder(
            context,
            PolyglotDatabase::class.java,
            "polyglot_database"
        ).addMigrations(PolyglotDatabase.MIGRATION_1_2).build()
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
    fun providePlaybackStateDao(database: PolyglotDatabase): PlaybackStateDao = database.playbackStateDao()

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
