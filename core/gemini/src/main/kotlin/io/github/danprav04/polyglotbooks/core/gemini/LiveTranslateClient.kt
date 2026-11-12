package io.github.danprav04.polyglotbooks.core.gemini

import android.util.Log
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * Result data from a live translation turn.
 */
data class TranslationOutput(
    val audioChunk: PcmChunk? = null,
    val sourceTranscript: String? = null,
    val targetTranscript: String? = null,
    val isTurnComplete: Boolean = false,
    val errorMessage: String? = null
)

/**
 * High-level client for live speech-to-speech translation streams.
 */
interface LiveTranslateClient {
    val translationStream: Flow<TranslationOutput>
    suspend fun start(
        apiKey: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean = false,
        model: String = DefaultModels.get(ModelRole.LIVE_TRANSLATE)
    )
    suspend fun sendAudio(chunk: PcmChunk)
    suspend fun stop()
}

/**
 * Production implementation of [LiveTranslateClient] wrapping [LiveSession] and [LiveSessionFactory].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultLiveTranslateClient(
    private val sessionFactory: LiveSessionFactory = DefaultLiveSessionFactory(),
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
) : LiveTranslateClient {

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var liveSession: LiveSession? = null
    private var collectorJob: Job? = null

    private val _translationStream = MutableSharedFlow<TranslationOutput>(
        replay = 16,
        extraBufferCapacity = 64
    )
    override val translationStream: Flow<TranslationOutput> = _translationStream.asSharedFlow()

    @Volatile
    private var isRunning = false

    @Volatile
    private var usingTranslationConfig = false

    @Volatile
    private var fallbackAttempted = false

    @Volatile
    private var hasReceivedContent = false

    override suspend fun start(
        apiKey: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean,
        model: String
    ) {
        stopInternal()
        _translationStream.resetReplayCache()

        val normalizedModel = normalizeModelName(model)
        val isTranslateModel = normalizedModel.contains("translate", ignoreCase = true)

        usingTranslationConfig = isTranslateModel
        fallbackAttempted = !isTranslateModel
        hasReceivedContent = false

        val initialSetup = if (isTranslateModel) {
            buildTranslateSetup(normalizedModel, targetLanguageCode, echoTargetLanguage)
        } else {
            buildFallbackLiveSetup(normalizedModel, targetLanguageCode, echoTargetLanguage)
        }

        connectAndCollect(
            apiKey = apiKey,
            targetLanguageCode = targetLanguageCode,
            echoTargetLanguage = echoTargetLanguage,
            normalizedModel = normalizedModel,
            setup = initialSetup
        )
    }

    private suspend fun connectAndCollect(
        apiKey: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean,
        normalizedModel: String,
        setup: SetupConfig
    ) {
        val currentJob = kotlin.coroutines.coroutineContext[Job]
        val oldJob = collectorJob
        if (oldJob != null && oldJob !== currentJob) {
            oldJob.cancel()
        }
        val oldSession = liveSession
        liveSession = null
        oldSession?.disconnect()

        val session = sessionFactory.create()
        liveSession = session

        lateinit var thisJob: Job
        thisJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                session.incomingMessages.collect { serverMsg ->
                    if (liveSession !== session) return@collect
                    when (serverMsg) {
                        is LiveServerMessage.ErrorMessage -> {
                            if (usingTranslationConfig && !fallbackAttempted && !hasReceivedContent) {
                                fallbackAttempted = true
                                usingTranslationConfig = false
                                val fallbackModel = selectFallbackModel(normalizedModel, serverMsg.error)
                                val fallbackSetup = buildFallbackLiveSetup(
                                    fallbackModel,
                                    targetLanguageCode,
                                    echoTargetLanguage
                                )
                                connectAndCollect(
                                    apiKey = apiKey,
                                    targetLanguageCode = targetLanguageCode,
                                    echoTargetLanguage = echoTargetLanguage,
                                    normalizedModel = fallbackModel,
                                    setup = fallbackSetup
                                )
                                thisJob.cancel()
                                return@collect
                            }
                            val errText = formatServerError(serverMsg.error)
                            _translationStream.emit(TranslationOutput(errorMessage = errText))
                        }
                        else -> {
                            val output = parseServerMessage(serverMsg)
                            if (output != null) {
                                hasReceivedContent = true
                                _translationStream.emit(output)
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (liveSession !== session) return@launch
                logE("Live session incoming messages failed", e)
                if (usingTranslationConfig && !fallbackAttempted && !hasReceivedContent) {
                    fallbackAttempted = true
                    usingTranslationConfig = false
                    try {
                        val fallbackSetup = buildFallbackLiveSetup(
                            normalizedModel,
                            targetLanguageCode,
                            echoTargetLanguage
                        )
                        connectAndCollect(
                            apiKey = apiKey,
                            targetLanguageCode = targetLanguageCode,
                            echoTargetLanguage = echoTargetLanguage,
                            normalizedModel = normalizedModel,
                            setup = fallbackSetup
                        )
                        return@launch
                    } catch (fallbackError: Throwable) {
                        logE("Fallback live session connection also failed", fallbackError)
                    }
                }
                isRunning = false
                _translationStream.emit(
                    TranslationOutput(errorMessage = e.message ?: "Live translation connection failed")
                )
            }
        }
        collectorJob = thisJob

        try {
            session.connect(apiKey, setup)
            isRunning = true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            if (usingTranslationConfig && !fallbackAttempted) {
                fallbackAttempted = true
                usingTranslationConfig = false
                val fallbackSetup = buildFallbackLiveSetup(
                    normalizedModel,
                    targetLanguageCode,
                    echoTargetLanguage
                )
                connectAndCollect(
                    apiKey = apiKey,
                    targetLanguageCode = targetLanguageCode,
                    echoTargetLanguage = echoTargetLanguage,
                    normalizedModel = normalizedModel,
                    setup = fallbackSetup
                )
            } else {
                isRunning = false
                _translationStream.emit(
                    TranslationOutput(errorMessage = e.message ?: "Unable to connect to Gemini Live")
                )
                throw e
            }
        }
    }

    override suspend fun sendAudio(chunk: PcmChunk) {
        if (chunk.data.isEmpty()) return
        val session = liveSession ?: return
        if (!session.isConnected) return

        val base64 = Base64.getEncoder().encodeToString(chunk.data)
        val msg = LiveClientMessage.RealtimeInputMessage(
            RealtimeInputData(
                audio = Blob(
                    mimeType = "audio/pcm;rate=${chunk.sampleRate}",
                    data = base64
                )
            )
        )
        session.send(msg)
    }

    override suspend fun stop() {
        stopInternal()
    }

    private suspend fun stopInternal() {
        isRunning = false
        collectorJob?.cancel()
        collectorJob = null
        val session = liveSession
        liveSession = null
        session?.disconnect()
    }

    internal fun parseServerMessage(serverMsg: LiveServerMessage): TranslationOutput? {
        return when (serverMsg) {
            is LiveServerMessage.ServerContentMessage -> {
                val content = serverMsg.serverContent
                var pcmChunk: PcmChunk? = null

                content.modelTurn?.parts?.forEach { part ->
                    part.inlineData?.let { blob ->
                        if (blob.mimeType.startsWith("audio/", ignoreCase = true)) {
                            try {
                                val decodedBytes = Base64.getMimeDecoder().decode(blob.data)
                                val sampleRate = parseSampleRate(blob.mimeType, DEFAULT_OUTPUT_SAMPLE_RATE)
                                val channels = parseChannels(blob.mimeType, 1)
                                pcmChunk = PcmChunk(
                                    data = decodedBytes,
                                    sampleRate = sampleRate,
                                    channels = channels
                                )
                            } catch (e: Exception) {
                                logE("Failed to decode inline audio blob", e)
                            }
                        }
                    }
                }

                val sourceText = content.inputTranscription?.text ?: content.interimInputTranscription?.text
                val targetText = content.outputTranscription?.text
                val isComplete = content.turnComplete == true || content.generationComplete == true

                if (pcmChunk != null || sourceText != null || targetText != null || isComplete) {
                    TranslationOutput(
                        audioChunk = pcmChunk,
                        sourceTranscript = sourceText,
                        targetTranscript = targetText,
                        isTurnComplete = isComplete
                    )
                } else {
                    null
                }
            }
            is LiveServerMessage.ErrorMessage -> {
                TranslationOutput(errorMessage = formatServerError(serverMsg.error))
            }
            else -> null
        }
    }

    private fun buildTranslateSetup(
        normalizedModel: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean
    ): SetupConfig = SetupConfig(
        model = normalizedModel,
        generationConfig = GenerationConfig(
            responseModalities = listOf("AUDIO"),
            inputAudioTranscription = JsonObject(emptyMap()),
            outputAudioTranscription = JsonObject(emptyMap()),
            translationConfig = TranslationConfig(
                targetLanguageCode = targetLanguageCode,
                echoTargetLanguage = echoTargetLanguage
            )
        ),
        sessionResumption = SessionResumptionConfig(),
        contextWindowCompression = ContextWindowCompressionConfig(
            slidingWindow = JsonObject(emptyMap())
        )
    )

    private fun buildFallbackLiveSetup(
        normalizedModel: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean
    ): SetupConfig = SetupConfig(
        model = normalizedModel,
        generationConfig = GenerationConfig(
            responseModalities = listOf("AUDIO"),
            inputAudioTranscription = JsonObject(emptyMap()),
            outputAudioTranscription = JsonObject(emptyMap())
        ),
        systemInstruction = buildTranslationSystemInstruction(targetLanguageCode, echoTargetLanguage),
        sessionResumption = SessionResumptionConfig(),
        contextWindowCompression = ContextWindowCompressionConfig(
            slidingWindow = JsonObject(emptyMap())
        )
    )

    private fun selectFallbackModel(currentModel: String, error: LiveErrorData): String {
        val isModelNotFound = error.code == 404 ||
            error.status.equals("NOT_FOUND", ignoreCase = true) ||
            (error.message?.contains("not found", ignoreCase = true) == true &&
                error.message.contains("model", ignoreCase = true))
        return if (isModelNotFound) {
            normalizeModelName(DefaultModels.get(ModelRole.LIVE_NARRATION))
        } else {
            currentModel
        }
    }

    private fun formatServerError(error: LiveErrorData): String {
        val base = error.message?.takeIf { it.isNotBlank() }
            ?: error.status?.takeIf { it.isNotBlank() }
            ?: "Gemini Live server error"
        return if (error.code != null && !base.contains(error.code.toString())) {
            "$base (code ${error.code})"
        } else {
            base
        }
    }

    private fun logE(msg: String, t: Throwable) {
        try {
            Log.e(TAG, msg, t)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "LiveTranslateClient"
        const val DEFAULT_OUTPUT_SAMPLE_RATE = 24000

        private val RATE_REGEX = Regex("""rate\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val CHANNELS_REGEX = Regex("""channels\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)

        fun normalizeModelName(model: String): String {
            val trimmed = model.trim().ifEmpty { DefaultModels.get(ModelRole.LIVE_TRANSLATE) }
            return if (trimmed.startsWith("models/")) trimmed else "models/$trimmed"
        }

        fun parseSampleRate(mimeType: String, defaultRate: Int = DEFAULT_OUTPUT_SAMPLE_RATE): Int {
            val match = RATE_REGEX.find(mimeType) ?: return defaultRate
            return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: defaultRate
        }

        fun parseChannels(mimeType: String, defaultChannels: Int = 1): Int {
            val match = CHANNELS_REGEX.find(mimeType) ?: return defaultChannels
            return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: defaultChannels
        }

        fun buildTranslationSystemInstruction(
            targetLanguageCode: String,
            echoTargetLanguage: Boolean = false
        ): ContentTurn {
            val language = LanguageCode.fromCode(targetLanguageCode)
            val languageLabel = "${language.displayName} (${language.code})"
            val echoInstruction = if (echoTargetLanguage) {
                "If the spoken input is already in $languageLabel, repeat it clearly in $languageLabel."
            } else {
                "Always speak only in $languageLabel."
            }
            return ContentTurn(
                parts = listOf(
                    Part(
                        text = "You are a simultaneous speech-to-speech audiobook translator. " +
                            "Listen to the incoming audio stream and immediately speak a natural, faithful " +
                            "translation in $languageLabel, matching the narrator's tone and pacing. " +
                            "Do not add commentary or explanations. $echoInstruction"
                    )
                )
            )
        }
    }
}
