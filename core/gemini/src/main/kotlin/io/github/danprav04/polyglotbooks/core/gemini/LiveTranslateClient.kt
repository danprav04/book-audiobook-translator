package io.github.danprav04.polyglotbooks.core.gemini

import android.util.Log
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

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
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    private val modelCatalogService: ModelCatalogService? = null,
    private val quotaTracker: GeminiQuotaTracker? = null
) : LiveTranslateClient {

    private enum class SetupMode {
        TRANSLATE_CONFIG,
        LIVE_WITH_SYSTEM_INSTRUCTION,
        MINIMAL_LIVE
    }

    private class AttemptContext(
        val generation: Long,
        val apiKey: String,
        val targetLanguageCode: String,
        val echoTargetLanguage: Boolean,
        var currentModel: String,
        var setupMode: SetupMode,
        val triedModels: MutableSet<String> = linkedSetOf(),
        val attemptedConfigs: MutableSet<Pair<String, SetupMode>> = linkedSetOf(),
        var minimalFallbackAttempted: Boolean = false,
        var cachedCatalogModels: List<ModelItem>? = null,
        @Volatile var hasReceivedContent: Boolean = false
    )

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val stateLock = Any()
    private val sessionGeneration = AtomicLong(0L)
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
        val gen: Long
        val oldJob: Job?
        val oldSession: LiveSession?
        synchronized(stateLock) {
            gen = sessionGeneration.incrementAndGet()
            isRunning = false
            hasReceivedContent = false
            oldJob = collectorJob
            collectorJob = null
            oldSession = liveSession
            liveSession = null
            _translationStream.resetReplayCache()
        }
        oldJob?.cancel()
        try {
            oldSession?.disconnect()
        } catch (_: Throwable) {
        }
        if (sessionGeneration.get() != gen) return

        val normalizedModel = normalizeModelName(model)
        val isTranslateModel = normalizedModel.contains("translate", ignoreCase = true)

        usingTranslationConfig = isTranslateModel
        fallbackAttempted = !isTranslateModel

        val ctx = AttemptContext(
            generation = gen,
            apiKey = apiKey,
            targetLanguageCode = targetLanguageCode,
            echoTargetLanguage = echoTargetLanguage,
            currentModel = normalizedModel,
            setupMode = if (isTranslateModel) {
                SetupMode.TRANSLATE_CONFIG
            } else {
                SetupMode.LIVE_WITH_SYSTEM_INSTRUCTION
            }
        )

        launchAttempt(ctx)
    }

    private suspend fun launchAttempt(ctx: AttemptContext) {
        if (sessionGeneration.get() != ctx.generation) return

        val setup = buildSetupForMode(
            mode = ctx.setupMode,
            normalizedModel = ctx.currentModel,
            targetLanguageCode = ctx.targetLanguageCode,
            echoTargetLanguage = ctx.echoTargetLanguage
        )
        ctx.triedModels.add(ctx.currentModel)
        ctx.attemptedConfigs.add(ctx.currentModel to ctx.setupMode)
        usingTranslationConfig = (ctx.setupMode == SetupMode.TRANSLATE_CONFIG)
        DiagnosticLogBuffer.record(
            TAG,
            "Starting live translation model=${ctx.currentModel} mode=${ctx.setupMode} targetLang=${ctx.targetLanguageCode}"
        )

        val currentCallerJob = coroutineContext[Job]
        val session = sessionFactory.create()
        val failureHandled = AtomicBoolean(false)

        val oldJob: Job?
        val oldSession: LiveSession?
        synchronized(stateLock) {
            if (sessionGeneration.get() != ctx.generation) return
            oldJob = collectorJob
            collectorJob = null
            oldSession = liveSession
            liveSession = session
        }

        if (oldJob != null && oldJob !== currentCallerJob) {
            oldJob.cancel()
        }
        try {
            oldSession?.disconnect()
        } catch (_: Throwable) {
        }
        if (sessionGeneration.get() != ctx.generation) {
            try {
                session.disconnect()
            } catch (_: Throwable) {
            }
            return
        }

        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                session.incomingMessages.collect { serverMsg ->
                    if (sessionGeneration.get() != ctx.generation || liveSession !== session) {
                        throw CancellationException("Session superseded")
                    }
                    when (serverMsg) {
                        is LiveServerMessage.ErrorMessage -> {
                            if (!failureHandled.compareAndSet(false, true)) return@collect
                            handleAttemptFailure(
                                ctx = ctx,
                                failedSession = session,
                                serverError = serverMsg.error,
                                throwable = null
                            )
                            throw CancellationException("Session ended on server error")
                        }
                        else -> {
                            val output = parseServerMessage(serverMsg)
                            if (output != null) {
                                if (!ctx.hasReceivedContent) {
                                    quotaTracker?.recordSuccess(ctx.currentModel)
                                }
                                ctx.hasReceivedContent = true
                                hasReceivedContent = true
                                _translationStream.emit(output)
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (sessionGeneration.get() != ctx.generation || liveSession !== session) return@launch
                if (!failureHandled.compareAndSet(false, true)) return@launch
                logE("Live session incoming messages failed", e)
                handleAttemptFailure(
                    ctx = ctx,
                    failedSession = session,
                    serverError = null,
                    throwable = e
                )
            }
        }

        val shouldConnect: Boolean
        synchronized(stateLock) {
            if (sessionGeneration.get() == ctx.generation && liveSession === session && !job.isCompleted) {
                collectorJob = job
                shouldConnect = true
            } else {
                shouldConnect = false
            }
        }
        if (!shouldConnect) {
            if (sessionGeneration.get() != ctx.generation || liveSession !== session) {
                job.cancel()
                try {
                    session.disconnect()
                } catch (_: Throwable) {
                }
            }
            return
        }

        try {
            quotaTracker?.recordRequestStarted(ctx.currentModel)
            session.connect(ctx.apiKey, setup)
            synchronized(stateLock) {
                if (sessionGeneration.get() == ctx.generation && liveSession === session) {
                    isRunning = true
                }
            }
            if (sessionGeneration.get() != ctx.generation) {
                try {
                    session.disconnect()
                } catch (_: Throwable) {
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            if (sessionGeneration.get() != ctx.generation || liveSession !== session) return
            if (!failureHandled.compareAndSet(false, true)) return
            logE("Live session connect failed", e)
            handleAttemptFailure(
                ctx = ctx,
                failedSession = session,
                serverError = null,
                throwable = e
            )
        }
    }

    private suspend fun handleAttemptFailure(
        ctx: AttemptContext,
        failedSession: LiveSession,
        serverError: LiveErrorData?,
        throwable: Throwable?
    ) {
        if (sessionGeneration.get() != ctx.generation) return

        val errCode = serverError?.code ?: when {
            isModelUnsupportedOrNotFound(serverError, throwable) -> 404
            isAuthOrQuotaError(serverError, throwable) -> 429
            isInvalidArgumentOrSetupError(serverError, throwable) -> 400
            else -> 503
        }
        DiagnosticLogBuffer.record(
            TAG,
            "Live attempt failed model=${ctx.currentModel} mode=${ctx.setupMode} code=$errCode receivedContent=${ctx.hasReceivedContent}",
            level = "WARN",
            throwable = throwable
        )
        quotaTracker?.recordHttpError(
            modelId = ctx.currentModel,
            httpCode = errCode,
            responseBody = serverError?.message ?: throwable?.message.orEmpty()
        )

        if (!ctx.hasReceivedContent) {
            val isModelError = isModelUnsupportedOrNotFound(serverError, throwable)
            val isSetupArgError = isInvalidArgumentOrSetupError(serverError, throwable)
            val isAuthOrQuota = isAuthOrQuotaError(serverError, throwable)

            // a) Model not found / unsupported for bidiGenerateContent -> pick alternate model with systemInstruction
            if (isModelError && ctx.triedModels.size < MAX_MODEL_ATTEMPTS) {
                val nextModel = selectAlternateLiveModel(ctx)
                if (sessionGeneration.get() != ctx.generation) return
                if (nextModel != null &&
                    (nextModel to SetupMode.LIVE_WITH_SYSTEM_INSTRUCTION) !in ctx.attemptedConfigs
                ) {
                    ctx.currentModel = nextModel
                    ctx.setupMode = SetupMode.LIVE_WITH_SYSTEM_INSTRUCTION
                    fallbackAttempted = true
                    launchAttempt(ctx)
                    return
                }
            }

            // b) translationConfig rejected pre-content -> fall back to systemInstruction on same model
            if (ctx.setupMode == SetupMode.TRANSLATE_CONFIG && !isModelError && !isAuthOrQuota &&
                (ctx.currentModel to SetupMode.LIVE_WITH_SYSTEM_INSTRUCTION) !in ctx.attemptedConfigs
            ) {
                ctx.setupMode = SetupMode.LIVE_WITH_SYSTEM_INSTRUCTION
                fallbackAttempted = true
                launchAttempt(ctx)
                return
            }

            // c) Optional setup fields rejected with 1007 / INVALID_ARGUMENT -> try minimal setup at most once
            if (isSetupArgError && !ctx.minimalFallbackAttempted &&
                (ctx.currentModel to SetupMode.MINIMAL_LIVE) !in ctx.attemptedConfigs
            ) {
                ctx.minimalFallbackAttempted = true
                ctx.setupMode = SetupMode.MINIMAL_LIVE
                fallbackAttempted = true
                launchAttempt(ctx)
                return
            }
        }

        val currentCallerJob = coroutineContext[Job]
        val jobToCancel: Job?
        synchronized(stateLock) {
            if (sessionGeneration.get() != ctx.generation || liveSession !== failedSession) return
            isRunning = false
            jobToCancel = collectorJob
            collectorJob = null
            liveSession = null
        }
        if (jobToCancel != null && jobToCancel !== currentCallerJob) {
            jobToCancel.cancel()
        }
        try {
            failedSession.disconnect()
        } catch (_: Throwable) {
        }

        val errText = when {
            serverError != null -> formatServerError(serverError)
            else -> throwable?.message?.takeIf { it.isNotBlank() } ?: "Live translation connection failed"
        }
        _translationStream.emit(TranslationOutput(errorMessage = errText))
    }

    private suspend fun selectAlternateLiveModel(ctx: AttemptContext): String? {
        val catalogModels = ctx.cachedCatalogModels ?: run {
            val fetched = try {
                modelCatalogService?.listModels(ctx.apiKey)?.models
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                null
            }
            val resolved = fetched ?: emptyList()
            ctx.cachedCatalogModels = resolved
            resolved
        }

        val candidates = linkedSetOf<String>()
        val defaultNarration = normalizeModelName(DefaultModels.get(ModelRole.LIVE_NARRATION))

        if (catalogModels.isNotEmpty()) {
            val narrationCatalog = ModelCatalog.filterModelsForRole(catalogModels, ModelRole.LIVE_NARRATION)
            val nonTranscribe = narrationCatalog.filterNot {
                it.name.contains("transcribe", ignoreCase = true)
            }
            nonTranscribe
                .firstOrNull { normalizeModelName(it.name) == defaultNarration }
                ?.let { candidates.add(normalizeModelName(it.name)) }
            nonTranscribe.forEach { candidates.add(normalizeModelName(it.name)) }
        }

        if (candidates.isEmpty()) {
            candidates.add(defaultNarration)
        }

        return candidates.firstOrNull { it !in ctx.triedModels }
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
        val oldJob: Job?
        val oldSession: LiveSession?
        synchronized(stateLock) {
            sessionGeneration.incrementAndGet()
            isRunning = false
            usingTranslationConfig = false
            fallbackAttempted = false
            hasReceivedContent = false
            oldJob = collectorJob
            collectorJob = null
            oldSession = liveSession
            liveSession = null
        }
        oldJob?.cancel()
        try {
            oldSession?.disconnect()
        } catch (_: Throwable) {
        }
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

    private fun buildSetupForMode(
        mode: SetupMode,
        normalizedModel: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean
    ): SetupConfig = when (mode) {
        SetupMode.TRANSLATE_CONFIG -> buildTranslateSetup(
            normalizedModel = normalizedModel,
            targetLanguageCode = targetLanguageCode,
            echoTargetLanguage = echoTargetLanguage
        )
        SetupMode.LIVE_WITH_SYSTEM_INSTRUCTION -> buildFallbackLiveSetup(
            normalizedModel = normalizedModel,
            targetLanguageCode = targetLanguageCode,
            echoTargetLanguage = echoTargetLanguage
        )
        SetupMode.MINIMAL_LIVE -> buildMinimalLiveSetup(
            normalizedModel = normalizedModel,
            targetLanguageCode = targetLanguageCode,
            echoTargetLanguage = echoTargetLanguage
        )
    }

    private fun buildTranslateSetup(
        normalizedModel: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean
    ): SetupConfig = SetupConfig(
        model = normalizedModel,
        generationConfig = GenerationConfig(
            responseModalities = listOf("AUDIO"),
            translationConfig = TranslationConfig(
                targetLanguageCode = targetLanguageCode,
                echoTargetLanguage = echoTargetLanguage
            )
        ),
        inputAudioTranscription = JsonObject(emptyMap()),
        outputAudioTranscription = JsonObject(emptyMap()),
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
            responseModalities = listOf("AUDIO")
        ),
        systemInstruction = buildTranslationSystemInstruction(targetLanguageCode, echoTargetLanguage),
        inputAudioTranscription = JsonObject(emptyMap()),
        outputAudioTranscription = JsonObject(emptyMap()),
        sessionResumption = SessionResumptionConfig(),
        contextWindowCompression = ContextWindowCompressionConfig(
            slidingWindow = JsonObject(emptyMap())
        )
    )

    private fun buildMinimalLiveSetup(
        normalizedModel: String,
        targetLanguageCode: String,
        echoTargetLanguage: Boolean
    ): SetupConfig = SetupConfig(
        model = normalizedModel,
        generationConfig = GenerationConfig(
            responseModalities = listOf("AUDIO")
        ),
        systemInstruction = buildTranslationSystemInstruction(targetLanguageCode, echoTargetLanguage)
    )

    private fun isModelUnsupportedOrNotFound(error: LiveErrorData?, throwable: Throwable?): Boolean {
        if (error != null) {
            if (error.code == 404 || error.code == 1008) return true
            if (error.status.equals("NOT_FOUND", ignoreCase = true)) return true
        }
        val msgLower = listOfNotNull(error?.message, throwable?.message)
            .joinToString(" ")
            .lowercase()
        if (msgLower.isBlank()) return false
        return MODEL_NOT_FOUND_CODE_REGEX.containsMatchIn(msgLower) ||
            msgLower.contains("not found") ||
            msgLower.contains("not supported") ||
            msgLower.contains("unsupported model")
    }

    private fun isInvalidArgumentOrSetupError(error: LiveErrorData?, throwable: Throwable?): Boolean {
        if (error != null) {
            if (error.code == 400 || error.code == 1007) return true
            if (error.status.equals("INVALID_ARGUMENT", ignoreCase = true)) return true
        }
        val msgLower = listOfNotNull(error?.message, throwable?.message)
            .joinToString(" ")
            .lowercase()
        if (msgLower.isBlank()) return false
        return INVALID_SETUP_CODE_REGEX.containsMatchIn(msgLower) ||
            msgLower.contains("invalid_argument") ||
            msgLower.contains("invalid argument") ||
            msgLower.contains("invalid json") ||
            msgLower.contains("unknown name") ||
            msgLower.contains("cannot find field") ||
            msgLower.contains("translationconfig") ||
            msgLower.contains("inputaudiotranscription") ||
            msgLower.contains("outputaudiotranscription") ||
            msgLower.contains("sessionresumption") ||
            msgLower.contains("contextwindowcompression")
    }

    private fun isAuthOrQuotaError(error: LiveErrorData?, throwable: Throwable?): Boolean {
        if (error != null) {
            if (error.code in setOf(401, 403, 429)) return true
            if (error.status.equals("UNAUTHENTICATED", ignoreCase = true) ||
                error.status.equals("PERMISSION_DENIED", ignoreCase = true) ||
                error.status.equals("RESOURCE_EXHAUSTED", ignoreCase = true)
            ) {
                return true
            }
        }
        val msgLower = listOfNotNull(error?.message, throwable?.message)
            .joinToString(" ")
            .lowercase()
        if (msgLower.isBlank()) return false
        return AUTH_OR_QUOTA_REGEX.containsMatchIn(msgLower) ||
            msgLower.contains("api key not valid") ||
            msgLower.contains("quota exceeded") ||
            msgLower.contains("permission_denied") ||
            msgLower.contains("unauthenticated") ||
            msgLower.contains("resource_exhausted")
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
        DiagnosticLogBuffer.record(TAG, msg, level = "ERROR", throwable = t)
        try {
            Log.e(TAG, msg, t)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "LiveTranslateClient"
        private const val MAX_MODEL_ATTEMPTS = 4
        const val DEFAULT_OUTPUT_SAMPLE_RATE = 24000

        private val RATE_REGEX = Regex("""rate\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val CHANNELS_REGEX = Regex("""channels\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        private val MODEL_NOT_FOUND_CODE_REGEX = Regex("""\bcode\s*[=:]\s*1008\b|\b(?:http[\s/]*|was\s*['"]?)404\b""")
        private val INVALID_SETUP_CODE_REGEX = Regex("""\bcode\s*[=:]\s*1007\b|\b(?:http[\s/]*|was\s*['"]?)400\b""")
        private val AUTH_OR_QUOTA_REGEX = Regex("""\bcode\s*[=:]\s*(?:4401|4403)\b|\b(?:http[\s/]*|was\s*['"]?)(?:401|403|429)\b""")

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
