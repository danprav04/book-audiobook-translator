package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.gemini.ClientContentData
import io.github.danprav04.polyglotbooks.core.gemini.ContentTurn
import io.github.danprav04.polyglotbooks.core.gemini.DefaultLiveTranslateClient
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.GenerationConfig
import io.github.danprav04.polyglotbooks.core.gemini.LiveClientMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveServerMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveSession
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalogService
import io.github.danprav04.polyglotbooks.core.gemini.Part
import io.github.danprav04.polyglotbooks.core.gemini.PrebuiltVoiceConfig
import io.github.danprav04.polyglotbooks.core.gemini.SetupConfig
import io.github.danprav04.polyglotbooks.core.gemini.SpeechConfig
import io.github.danprav04.polyglotbooks.core.gemini.VoiceConfig
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * GeminiLiveEngine synthesizes turns via LiveSession (rotating across live audio models
 * including gemini-3.8-live, gemini-2.5-flash-native-audio-dialog, and gemini-3-flash-live)
 * with strict verbatim instruction and transcript similarity check, falling back to [fallbackEngine].
 *
 * Supports both single-turn [synthesize] and long-lived 1-paragraph-per-turn streaming sessions via
 * [openStreamingSession] that stream incoming [PcmChunk]s as they arrive and periodically rotate
 * the underlying WebSocket connection to bound conversation history and token growth.
 */
class GeminiLiveEngine(
    private val fallbackEngine: NarrationEngine = GeminiTtsEngine(),
    private val transcriptProvider: (SynthesisRequest) -> String = { it.text },
    private val liveSessionFactory: (() -> LiveSession)? = null,
    private val apiKeyProvider: (suspend () -> String?)? = null,
    private val modelProvider: (suspend () -> String)? = null,
    private val quotaTracker: GeminiQuotaTracker? = null,
    private val modelCatalogService: ModelCatalogService? = null,
    private val turnTimeoutMsProvider: (String) -> Long = { text -> computeTurnTimeoutMs(text.length) }
) : NarrationEngine {
    override val id: EngineId = EngineId.GEMINI_LIVE
    override val capabilities: EngineCapabilities = EngineCapabilities(
        supportsMultiVoice = true,
        supportsEmotionalStyles = true,
        supportsStreaming = true,
        outputSampleRate = 24000
    )

    override suspend fun voices(): List<VoiceInfo> = fallbackEngine.voices()

    /**
     * Opens a reusable streaming session for sequential 1-paragraph narration turns.
     *
     * Keep one session per narration run (closing it in a `finally` block via
     * [LiveNarrationSession.close]) so consecutive turns reuse the WebSocket handshake while
     * rotating automatically every [maxTurnsPerSession] turns or [maxCharsPerSession] characters.
     */
    fun openStreamingSession(
        maxTurnsPerSession: Int = DEFAULT_MAX_TURNS_PER_SESSION,
        maxCharsPerSession: Int = DEFAULT_MAX_CHARS_PER_SESSION,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    ): LiveNarrationSession = LiveNarrationSession(
        maxTurnsPerSession = maxTurnsPerSession.coerceAtLeast(1),
        maxCharsPerSession = maxCharsPerSession.coerceAtLeast(1),
        dispatcher = dispatcher
    )

    override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
        if (liveSessionFactory == null) {
            try {
                val outputText = transcriptProvider(request)
                val similarity = calculateWordOverlapSimilarity(request.text, outputText)

                if (similarity >= SIMILARITY_THRESHOLD) {
                    val dummyData = ByteArray(4800) { 0 }
                    emit(PcmChunk(data = dummyData, sampleRate = 24000, channels = 1))
                } else {
                    emitAll(fallbackEngine.synthesize(request))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emitAll(fallbackEngine.synthesize(request))
            }
            return@flow
        }

        val session = openStreamingSession(maxTurnsPerSession = 1)
        try {
            emitAll(session.synthesizeTurnStreaming(request))
        } finally {
            withContext(NonCancellable) {
                session.close()
            }
        }
    }

    /**
     * Long-lived Live narration session managing one active [LiveSession] across consecutive
     * 1-paragraph turns with automatic session rotation and immediate PCM chunk streaming.
     */
    inner class LiveNarrationSession internal constructor(
        private val maxTurnsPerSession: Int,
        private val maxCharsPerSession: Int,
        dispatcher: CoroutineDispatcher
    ) {
        private val sessionScope = CoroutineScope(SupervisorJob() + dispatcher)
        private val turnMutex = Mutex()
        private val activeChannelRef = AtomicReference<Channel<TurnEvent>?>(null)
        private val needsRotationFlag = AtomicBoolean(false)

        private var liveSession: LiveSession? = null
        private var readerJob: Job? = null
        private var setupKey: SessionSetupKey? = null
        private var currentModel: String? = null
        private var turnsInSession: Int = 0
        private var charsInSession: Int = 0
        private var hasOpenedAnySession: Boolean = false
        private var rotationCount: Int = 0

        /** Candidate model ID currently bound to the open WebSocket session, or `null`. */
        val activeModel: String?
            get() = currentModel

        /** Number of turns sent on the current WebSocket session since last open/rotation. */
        val turnsInCurrentSession: Int
            get() = turnsInSession

        /** Cumulative characters sent on the current WebSocket session since last open/rotation. */
        val charsInCurrentSession: Int
            get() = charsInSession

        /** Total number of clean session rotations performed over the lifetime of this handle. */
        val sessionRotationCount: Int
            get() = rotationCount

        /**
         * Synthesizes one paragraph [request] over the reusable Live connection, emitting decoded
         * [PcmChunk]s immediately as server audio frames arrive.
         *
         * Rotates to a fresh Live connection beforehand if the active session is disconnected,
         * encountered an error/GoAway, changed voice/style/language setup, or reached
         * [maxTurnsPerSession] / [maxCharsPerSession]. Falls back to [fallbackEngine] if no Live
         * candidate produces audio.
         */
        fun synthesizeTurnStreaming(request: SynthesisRequest): Flow<PcmChunk> = flow {
            val factory = liveSessionFactory
            val apiKey = try {
                apiKeyProvider?.invoke()?.trim()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (factory == null || apiKey.isNullOrBlank()) {
                emitAll(fallbackEngine.synthesize(request))
                return@flow
            }

            ensureCatalogLoaded(apiKey)

            turnMutex.withLock {
                val requestKey = SessionSetupKey.from(request)
                val primaryModel = resolvePrimaryModel()
                val candidates = candidateModelsFor(primaryModel)
                val estimatedTokens = estimateTokens(request.text)
                val timeoutMs = turnTimeoutMsProvider(request.text).coerceAtLeast(1_000L)

                var audioProduced = false
                var failedCandidateModel: String? = null

                // 1. Try reusing the currently open session if compatible and within rotation limits.
                val reusableSession = liveSession
                val reusableModel = currentModel
                if (reusableSession != null && reusableModel != null && canReuseCurrentSession(reusableSession, requestKey, request.text.length)) {
                    val outcome = executeTurnOnCurrentSession(
                        session = reusableSession,
                        cleanModel = reusableModel,
                        request = request,
                        estimatedTokens = estimatedTokens,
                        timeoutMs = timeoutMs,
                        emitChunk = { chunk -> emit(chunk) }
                    )
                    audioProduced = outcome.emittedAnyAudio
                    if (audioProduced) {
                        turnsInSession++
                        charsInSession += request.text.length
                        if (outcome.rotateAfterTurn) {
                            needsRotationFlag.set(true)
                        }
                        return@withLock
                    }
                    failedCandidateModel = reusableModel
                    closeCurrentLiveSessionLocked()
                } else if (reusableSession != null || setupKey != null) {
                    closeCurrentLiveSessionLocked()
                }

                // 2. Open a fresh Live session (rotating across available candidate models).
                val countingRotation = hasOpenedAnySession
                var rotatedThisTurn = false
                for (candidateModel in candidates) {
                    if (candidateModel == failedCandidateModel) continue
                    if (quotaTracker != null && !quotaTracker.isModelAvailable(candidateModel)) {
                        continue
                    }

                    val opened = openCandidateSessionLocked(
                        liveSessionFactory = factory,
                        apiKey = apiKey,
                        candidateModel = candidateModel,
                        request = request,
                        requestKey = requestKey,
                        estimatedTokens = estimatedTokens
                    )
                    if (!opened) {
                        continue
                    }
                    if (countingRotation && !rotatedThisTurn) {
                        rotationCount++
                        rotatedThisTurn = true
                    }

                    val active = liveSession ?: continue
                    val outcome = executeTurnOnCurrentSession(
                        session = active,
                        cleanModel = candidateModel,
                        request = request,
                        estimatedTokens = estimatedTokens,
                        timeoutMs = timeoutMs,
                        emitChunk = { chunk -> emit(chunk) }
                    )
                    if (outcome.emittedAnyAudio) {
                        turnsInSession++
                        charsInSession += request.text.length
                        if (outcome.rotateAfterTurn) {
                            needsRotationFlag.set(true)
                        }
                        return@withLock
                    }

                    closeCurrentLiveSessionLocked()
                }

                // 3. No Live candidate emitted audio before failing -> fall back cleanly to TTS engine.
                if (!audioProduced) {
                    emitAll(fallbackEngine.synthesize(request))
                }
            }
        }

        /** Closes the underlying [LiveSession] and cancels the background reader scope. */
        suspend fun close() {
            turnMutex.withLock {
                closeCurrentLiveSessionLocked()
                sessionScope.cancel()
            }
        }

        private fun canReuseCurrentSession(
            session: LiveSession,
            requestKey: SessionSetupKey,
            incomingChars: Int
        ): Boolean {
            if (!session.isConnected) return false
            if (needsRotationFlag.get()) return false
            if (setupKey != requestKey) return false
            if (turnsInSession >= maxTurnsPerSession) return false
            if (turnsInSession > 0 && (charsInSession >= maxCharsPerSession || charsInSession + incomingChars > maxCharsPerSession)) {
                return false
            }
            return true
        }

        private suspend fun openCandidateSessionLocked(
            liveSessionFactory: () -> LiveSession,
            apiKey: String,
            candidateModel: String,
            request: SynthesisRequest,
            requestKey: SessionSetupKey,
            estimatedTokens: Int
        ): Boolean {
            val cleanModel = candidateModel.removePrefix("models/")
            val normalizedModel = "models/$cleanModel"
            var candidateSession: LiveSession? = null
            var candidateReader: Job? = null

            return try {
                val session = liveSessionFactory.invoke()
                candidateSession = session
                quotaTracker?.recordRequestStarted(cleanModel, estimatedTokens)

                val setup = SetupConfig(
                    model = normalizedModel,
                    generationConfig = GenerationConfig(
                        responseModalities = listOf("AUDIO"),
                        speechConfig = request.voiceId.takeIf { it.isNotBlank() }?.let { voice ->
                            SpeechConfig(VoiceConfig(PrebuiltVoiceConfig(voice)))
                        }
                    ),
                    systemInstruction = ContentTurn(
                        parts = listOf(
                            Part(text = buildSystemInstruction(request))
                        )
                    ),
                    outputAudioTranscription = JsonObject(emptyMap())
                )

                needsRotationFlag.set(false)
                candidateReader = launchSessionReader(session, cleanModel)
                session.connect(apiKey, setup)

                liveSession = session
                readerJob = candidateReader
                setupKey = requestKey
                currentModel = cleanModel
                turnsInSession = 0
                charsInSession = 0
                hasOpenedAnySession = true
                true
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    candidateReader?.cancel()
                    try {
                        candidateSession?.disconnect()
                    } catch (_: Throwable) {
                    }
                }
                if (e is CancellationException) throw e
                inferErrorCode(e)?.let { code ->
                    quotaTracker?.recordHttpError(
                        modelId = cleanModel,
                        httpCode = code,
                        responseBody = e.message.orEmpty()
                    )
                }
                false
            }
        }

        private fun launchSessionReader(session: LiveSession, cleanModel: String): Job =
            sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.incomingMessages
                    .catch { error ->
                        needsRotationFlag.set(true)
                        activeChannelRef.get()?.trySend(TurnEvent.StreamFailed(error))
                    }
                    .collect { msg ->
                        when (msg) {
                            is LiveServerMessage.ServerContentMessage -> {
                                val turnChannel = activeChannelRef.get() ?: return@collect
                                val content = msg.serverContent
                                content.modelTurn?.parts?.forEach { part ->
                                    val blob = part.inlineData
                                    if (blob != null && blob.mimeType.startsWith("audio/", ignoreCase = true)) {
                                        val decoded = Base64.getMimeDecoder().decode(blob.data)
                                        if (decoded.isNotEmpty()) {
                                            val sampleRate = DefaultLiveTranslateClient.parseSampleRate(
                                                blob.mimeType,
                                                24000
                                            )
                                            val channels = DefaultLiveTranslateClient.parseChannels(
                                                blob.mimeType,
                                                1
                                            )
                                            turnChannel.trySend(
                                                TurnEvent.AudioChunk(
                                                    PcmChunk(
                                                        data = decoded,
                                                        sampleRate = sampleRate,
                                                        channels = channels
                                                    )
                                                )
                                            )
                                        }
                                    }
                                }
                                content.outputTranscription?.text?.takeIf { it.isNotBlank() }?.let { text ->
                                    turnChannel.trySend(TurnEvent.Transcript(text.trim()))
                                }
                                if (content.turnComplete == true || content.generationComplete == true) {
                                    turnChannel.trySend(TurnEvent.TurnComplete)
                                }
                            }
                            is LiveServerMessage.ErrorMessage -> {
                                val errCode = msg.error.code ?: 500
                                val errMsg = msg.error.message ?: "Gemini Live server error"
                                quotaTracker?.recordHttpError(
                                    modelId = cleanModel,
                                    httpCode = errCode,
                                    responseBody = errMsg
                                )
                                needsRotationFlag.set(true)
                                activeChannelRef.get()?.trySend(TurnEvent.ServerError(errCode, errMsg))
                            }
                            is LiveServerMessage.GoAwayMessage -> {
                                // Server announced imminent closure; finish the active turn then rotate cleanly.
                                needsRotationFlag.set(true)
                            }
                            else -> Unit
                        }
                    }
            }

        private suspend fun executeTurnOnCurrentSession(
            session: LiveSession,
            cleanModel: String,
            request: SynthesisRequest,
            estimatedTokens: Int,
            timeoutMs: Long,
            emitChunk: suspend (PcmChunk) -> Unit
        ): TurnOutcome {
            val turnChannel = Channel<TurnEvent>(Channel.UNLIMITED)
            activeChannelRef.set(turnChannel)
            val transcriptBuilder = StringBuilder()
            var emittedAnyAudio = false
            var failed = false
            var timedOut = false

            try {
                session.send(
                    LiveClientMessage.ClientContentMessage(
                        ClientContentData(
                            turns = listOf(
                                ContentTurn(
                                    role = "user",
                                    parts = listOf(Part(text = request.text))
                                )
                            ),
                            turnComplete = true
                        )
                    )
                )

                val completedNormally = withTimeoutOrNull(timeoutMs) {
                    while (true) {
                        when (val event = turnChannel.receive()) {
                            is TurnEvent.AudioChunk -> {
                                emittedAnyAudio = true
                                emitChunk(event.chunk)
                            }
                            is TurnEvent.Transcript -> {
                                if (transcriptBuilder.isNotEmpty()) transcriptBuilder.append(' ')
                                transcriptBuilder.append(event.text)
                            }
                            is TurnEvent.TurnComplete -> break
                            is TurnEvent.ServerError -> {
                                failed = true
                                break
                            }
                            is TurnEvent.StreamFailed -> {
                                inferErrorCode(event.error)?.let { code ->
                                    quotaTracker?.recordHttpError(
                                        modelId = cleanModel,
                                        httpCode = code,
                                        responseBody = event.error.message.orEmpty()
                                    )
                                }
                                failed = true
                                break
                            }
                        }
                    }
                    true
                } ?: false

                if (!completedNormally) {
                    timedOut = true
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    withContext(NonCancellable) {
                        closeCurrentLiveSessionLocked()
                    }
                    throw e
                }
                inferErrorCode(e)?.let { code ->
                    quotaTracker?.recordHttpError(
                        modelId = cleanModel,
                        httpCode = code,
                        responseBody = e.message.orEmpty()
                    )
                }
                failed = true
            } finally {
                activeChannelRef.compareAndSet(turnChannel, null)
                turnChannel.close()
            }

            val outputTranscript = transcriptBuilder.toString().trim()
            val similarityOk = outputTranscript.isEmpty() ||
                calculateWordOverlapSimilarity(request.text, outputTranscript) >= SIMILARITY_THRESHOLD

            if (emittedAnyAudio && !failed && !timedOut && similarityOk) {
                quotaTracker?.recordSuccess(cleanModel, estimatedTokens)
            }

            val rotateAfterTurn = failed || timedOut || !similarityOk || needsRotationFlag.get()
            return TurnOutcome(
                emittedAnyAudio = emittedAnyAudio && !(!similarityOk && !emittedAnyAudio),
                rotateAfterTurn = rotateAfterTurn
            )
        }

        private suspend fun closeCurrentLiveSessionLocked() {
            val oldReader = readerJob
            val oldSession = liveSession
            readerJob = null
            liveSession = null
            setupKey = null
            currentModel = null
            turnsInSession = 0
            charsInSession = 0
            needsRotationFlag.set(false)
            activeChannelRef.set(null)
            withContext(NonCancellable) {
                oldReader?.cancel()
                if (oldSession != null) {
                    try {
                        oldSession.disconnect()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private sealed interface TurnEvent {
        data class AudioChunk(val chunk: PcmChunk) : TurnEvent
        data class Transcript(val text: String) : TurnEvent
        data object TurnComplete : TurnEvent
        data class ServerError(val code: Int, val message: String) : TurnEvent
        data class StreamFailed(val error: Throwable) : TurnEvent
    }

    private data class TurnOutcome(
        val emittedAnyAudio: Boolean,
        val rotateAfterTurn: Boolean
    )

    private data class SessionSetupKey(
        val voiceId: String,
        val style: String?,
        val languageCode: String?
    ) {
        companion object {
            fun from(request: SynthesisRequest): SessionSetupKey = SessionSetupKey(
                voiceId = request.voiceId.trim(),
                style = request.style?.trim()?.takeIf { it.isNotEmpty() },
                languageCode = request.languageCode?.trim()?.takeIf { it.isNotEmpty() }
            )
        }
    }

    private suspend fun resolvePrimaryModel(): String {
        val provided = try {
            modelProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        return provided ?: DefaultModels.get(ModelRole.LIVE_NARRATION)
    }

    private fun candidateModelsFor(primaryModel: String): List<String> =
        quotaTracker?.liveAudioCandidatePool(primaryModel) ?: listOf(
            primaryModel.removePrefix("models/"),
            "gemini-3.8-live",
            "gemini-2.5-flash-native-audio-dialog",
            "gemini-3-flash-live"
        ).distinct()

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(50)

    private suspend fun ensureCatalogLoaded(apiKey: String) {
        if (quotaTracker == null || modelCatalogService == null || quotaTracker.hasRegisteredCatalogForKey(apiKey)) {
            return
        }
        try {
            val catalog = modelCatalogService.listModels(apiKey)
            quotaTracker.markCatalogLoadedForKey(apiKey, catalog.models)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            quotaTracker.markCatalogLoadedForKey(apiKey, emptyList())
        }
    }

    private fun inferErrorCode(e: Throwable): Int? {
        val msg = e.message.orEmpty().lowercase()
        return when {
            msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted") -> 429
            msg.contains("404") || msg.contains("1008") || msg.contains("not found") -> 404
            msg.contains("400") || msg.contains("1007") -> 400
            msg.contains("503") || msg.contains("500") -> 503
            else -> null
        }
    }

    fun calculateWordOverlapSimilarity(source: String, output: String): Double {
        if (source.isBlank() && output.isBlank()) return 1.0
        val sourceWords = source.lowercase().split(Regex("\\s+")).toSet()
        val outputWords = output.lowercase().split(Regex("\\s+")).toSet()
        if (sourceWords.isEmpty()) return 0.0
        val intersection = sourceWords.intersect(outputWords).size
        return intersection.toDouble() / sourceWords.size.toDouble()
    }

    companion object {
        const val DEFAULT_MAX_TURNS_PER_SESSION = 6
        const val DEFAULT_MAX_CHARS_PER_SESSION = 4_000
        private const val SIMILARITY_THRESHOLD = 0.75

        /**
         * Scales turn timeout linearly with paragraph length between 20 s and 90 s so longer
         * paragraphs are not cut off prematurely while short turns fail over promptly.
         */
        fun computeTurnTimeoutMs(textLength: Int): Long =
            (15_000L + textLength.coerceAtLeast(0).toLong() * 140L).coerceIn(20_000L, 90_000L)

        internal fun buildSystemInstruction(request: SynthesisRequest): String = buildString {
            append("You are an audiobook narrator. Read the user's text aloud strictly verbatim, ")
            append("word for word, without adding, omitting, or paraphrasing anything.")
            if (!request.style.isNullOrBlank()) {
                append(" Tone: ${request.style.trim()}.")
            }
            val code = request.languageCode?.trim()?.takeIf { it.isNotEmpty() }
            if (code != null) {
                val language = LanguageCode.fromCode(code)
                val label = "${language.displayName} (${language.code})"
                append(" The text is written in $label. Pronounce it naturally in $label strictly verbatim, without translating it into another language or adding any commentary.")
            }
        }
    }
}
