package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.gemini.ClientContentData
import io.github.danprav04.polyglotbooks.core.gemini.ContentTurn
import io.github.danprav04.polyglotbooks.core.gemini.ContextWindowCompressionConfig
import io.github.danprav04.polyglotbooks.core.gemini.DefaultLiveTranslateClient
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
import io.github.danprav04.polyglotbooks.core.gemini.GenerationConfig
import io.github.danprav04.polyglotbooks.core.gemini.LiveClientMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveServerMessage
import io.github.danprav04.polyglotbooks.core.gemini.LiveSession
import io.github.danprav04.polyglotbooks.core.gemini.ModelCatalogService
import io.github.danprav04.polyglotbooks.core.gemini.Part
import io.github.danprav04.polyglotbooks.core.gemini.PrebuiltVoiceConfig
import io.github.danprav04.polyglotbooks.core.gemini.SessionResumptionConfig
import io.github.danprav04.polyglotbooks.core.gemini.SetupConfig
import io.github.danprav04.polyglotbooks.core.gemini.SpeechConfig
import io.github.danprav04.polyglotbooks.core.gemini.VoiceConfig
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.core.model.PunctuationDialogueHelper
import io.github.danprav04.polyglotbooks.core.model.SpeechTextNormalizer
import kotlin.math.abs
import kotlin.math.roundToInt
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
        supportsMultiVoice = false,
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
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        bufferAndVerifyTurns: Boolean = false
    ): LiveNarrationSession = LiveNarrationSession(
        maxTurnsPerSession = maxTurnsPerSession.coerceAtLeast(1),
        maxCharsPerSession = maxCharsPerSession.coerceAtLeast(1),
        dispatcher = dispatcher,
        bufferAndVerifyTurns = bufferAndVerifyTurns
    )

    override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
        val resolved = PunctuationDialogueHelper.resolve(request.text, request.style)
        when (resolved.kind) {
            PunctuationDialogueHelper.PunctuationKind.EMPTY -> return@flow
            PunctuationDialogueHelper.PunctuationKind.SILENT_DIVIDER -> {
                emit(PcmChunk(data = ByteArray(4800) { 0 }, sampleRate = 24000, channels = 1))
                return@flow
            }
            else -> Unit
        }
        val effectiveRequest = buildEffectiveSpeechRequest(
            request = request,
            isExpressivePunctuation = resolved.kind == PunctuationDialogueHelper.PunctuationKind.EXPRESSIVE_DIALOGUE,
            spokenText = resolved.spokenText,
            styleCue = resolved.styleCue
        )

        if (liveSessionFactory == null) {
            try {
                val outputText = transcriptProvider(effectiveRequest)
                val isExpressive = resolved.kind == PunctuationDialogueHelper.PunctuationKind.EXPRESSIVE_DIALOGUE
                val similarity = calculateWordOverlapSimilarity(effectiveRequest.text, outputText)
                val sourceWordCount = countWords(effectiveRequest.text)
                val outputWordCount = countWords(outputText)
                val excessiveExpansion = !isExpressive && isExcessiveWordExpansion(sourceWordCount, outputWordCount)
                val repeatedPhrase = !isExpressive && outputText.isNotBlank() &&
                    hasRepeatedSentenceOrPhrase(effectiveRequest.text, outputText)

                if (similarity >= SIMILARITY_THRESHOLD && !excessiveExpansion && !repeatedPhrase) {
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

        val session = openStreamingSession(
            maxTurnsPerSession = 1,
            bufferAndVerifyTurns = true
        )
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
     * 1-paragraph turns with automatic session rotation and immediate or verified-turn PCM emission.
     */
    inner class LiveNarrationSession internal constructor(
        private val maxTurnsPerSession: Int,
        private val maxCharsPerSession: Int,
        dispatcher: CoroutineDispatcher,
        private val bufferAndVerifyTurns: Boolean = false
    ) {
        private val sessionScope = CoroutineScope(SupervisorJob() + dispatcher)
        private val turnMutex = Mutex()
        private val activeChannelRef = AtomicReference<Channel<TurnEvent>?>(null)
        private val needsRotationFlag = AtomicBoolean(false)

        private var liveSession: LiveSession? = null
        private var readerJob: Job? = null
        private var setupKey: SessionSetupKey? = null
        private var currentModel: String? = null
        private var lastVerifiedModel: String? = null
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
         * [PcmChunk]s either immediately as frames arrive or once a full complete turn is verified
         * when `bufferAndVerifyTurns` is enabled.
         *
         * Rotates to a fresh Live connection beforehand if the active session is disconnected,
         * encountered an error/interruption/GoAway, changed voice/style/language setup, or reached
         * [maxTurnsPerSession] / [maxCharsPerSession]. Falls back to [fallbackEngine] if no Live
         * candidate produces a valid turn.
         */
        fun synthesizeTurnStreaming(request: SynthesisRequest): Flow<PcmChunk> = flow {
            val resolved = PunctuationDialogueHelper.resolve(request.text, request.style)
            when (resolved.kind) {
                PunctuationDialogueHelper.PunctuationKind.EMPTY -> return@flow
                PunctuationDialogueHelper.PunctuationKind.SILENT_DIVIDER -> {
                    emit(PcmChunk(data = ByteArray(4800) { 0 }, sampleRate = 24000, channels = 1))
                    return@flow
                }
                else -> Unit
            }
            val isExpressivePunctuation = resolved.kind == PunctuationDialogueHelper.PunctuationKind.EXPRESSIVE_DIALOGUE
            val effectiveRequest = buildEffectiveSpeechRequest(
                request = request,
                isExpressivePunctuation = isExpressivePunctuation,
                spokenText = resolved.spokenText,
                styleCue = resolved.styleCue
            )

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
                val requestKey = SessionSetupKey.from(effectiveRequest)
                val primaryModel = resolvePrimaryModel()
                val candidates = candidateModelsFor(primaryModel)
                val estimatedTokens = estimateTokens(effectiveRequest.text)
                val timeoutMs = turnTimeoutMsProvider(effectiveRequest.text).coerceAtLeast(1_000L)

                var audioProduced = false
                var failedReusableModel: String? = null
                var reusableModelHardError = false

                // 1. Try reusing the currently open session if compatible and within rotation limits.
                val reusableSession = liveSession
                val reusableModel = currentModel
                if (reusableSession != null && reusableModel != null && canReuseCurrentSession(reusableSession, requestKey, effectiveRequest.text.length)) {
                    val outcome = executeTurnOnCurrentSession(
                        session = reusableSession,
                        cleanModel = reusableModel,
                        request = effectiveRequest,
                        estimatedTokens = estimatedTokens,
                        timeoutMs = timeoutMs,
                        isExpressivePunctuation = isExpressivePunctuation,
                        emitChunk = { chunk -> emit(chunk) }
                    )
                    audioProduced = outcome.emittedAnyAudio
                    if (audioProduced) {
                        turnsInSession++
                        charsInSession += effectiveRequest.text.length
                        if (outcome.rotateAfterTurn) {
                            needsRotationFlag.set(true)
                        }
                        return@withLock
                    }
                    failedReusableModel = reusableModel
                    reusableModelHardError = outcome.hardModelError
                    closeCurrentLiveSessionLocked()
                } else if (reusableSession != null || setupKey != null) {
                    closeCurrentLiveSessionLocked()
                }

                // 2. Open a fresh Live session, prioritizing a verified working model first unless it hit a hard error.
                val preferredModel = lastVerifiedModel?.takeIf { verified ->
                    verified in candidates && !(failedReusableModel == verified && reusableModelHardError)
                }
                val orderedCandidates = buildList {
                    if (preferredModel != null) {
                        add(preferredModel)
                    }
                    for (candidateModel in candidates) {
                        if (candidateModel != preferredModel && candidateModel != failedReusableModel) {
                            add(candidateModel)
                        }
                    }
                    if (failedReusableModel != null &&
                        !reusableModelHardError &&
                        failedReusableModel != preferredModel &&
                        failedReusableModel in candidates
                    ) {
                        add(failedReusableModel)
                    }
                }
                val countingRotation = hasOpenedAnySession
                var rotatedThisTurn = false
                for (candidateModel in orderedCandidates) {
                    if (quotaTracker != null && !quotaTracker.isModelAvailable(candidateModel)) {
                        continue
                    }

                    val opened = openCandidateSessionLocked(
                        liveSessionFactory = factory,
                        apiKey = apiKey,
                        candidateModel = candidateModel,
                        request = effectiveRequest,
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
                        request = effectiveRequest,
                        estimatedTokens = estimatedTokens,
                        timeoutMs = timeoutMs,
                        isExpressivePunctuation = isExpressivePunctuation,
                        emitChunk = { chunk -> emit(chunk) }
                    )
                    if (outcome.emittedAnyAudio) {
                        turnsInSession++
                        charsInSession += effectiveRequest.text.length
                        if (outcome.rotateAfterTurn) {
                            needsRotationFlag.set(true)
                        }
                        return@withLock
                    }

                    closeCurrentLiveSessionLocked()
                }

                // 3. No Live candidate emitted usable audio -> fall back cleanly to TTS engine.
                if (!audioProduced) {
                    DiagnosticLogBuffer.record(
                        "GeminiLiveEngine",
                        "No Live audio emitted (${request.text.length} chars, voice=${request.voiceId}); falling back to TTS",
                        level = "WARN"
                    )
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
                        // Lower sampling randomness reduces stray audio tokens (clipped/stuttering words).
                        temperature = LIVE_NARRATION_TEMPERATURE,
                        speechConfig = request.voiceId.takeIf { it.isNotBlank() }?.let { voice ->
                            SpeechConfig(VoiceConfig(PrebuiltVoiceConfig(voice)))
                        }
                    ),
                    systemInstruction = ContentTurn(
                        parts = listOf(
                            Part(text = buildSystemInstruction(request))
                        )
                    ),
                    outputAudioTranscription = JsonObject(emptyMap()),
                    sessionResumption = SessionResumptionConfig(),
                    contextWindowCompression = ContextWindowCompressionConfig(
                        slidingWindow = JsonObject(emptyMap())
                    )
                )

                needsRotationFlag.set(false)
                candidateReader = launchSessionReader(session, cleanModel)
                session.connect(apiKey, setup)

                DiagnosticLogBuffer.record(
                    "GeminiLiveEngine",
                    "Opened Live narration session (model=$cleanModel, voice=${request.voiceId})"
                )
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
                DiagnosticLogBuffer.record(
                    "GeminiLiveEngine",
                    "Failed to open Live narration session (model=$cleanModel)",
                    level = "WARN",
                    throwable = e
                )
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
                        DiagnosticLogBuffer.record(
                            "GeminiLiveEngine",
                            "Live narration incoming stream failed (model=$cleanModel)",
                            level = "ERROR",
                            throwable = error
                        )
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
                                content.outputTranscription?.text?.takeIf { it.isNotEmpty() }?.let { text ->
                                    turnChannel.trySend(TurnEvent.Transcript(text))
                                }
                                if (content.generationComplete == true) {
                                    turnChannel.trySend(TurnEvent.GenerationComplete)
                                }
                                if (content.interrupted == true) {
                                    DiagnosticLogBuffer.record(
                                        "GeminiLiveEngine",
                                        "Live turn interrupted by server on model=$cleanModel; rotating session",
                                        level = "WARN"
                                    )
                                    needsRotationFlag.set(true)
                                    turnChannel.trySend(TurnEvent.TurnInterrupted)
                                } else if (content.turnComplete == true) {
                                    turnChannel.trySend(TurnEvent.TurnComplete)
                                }
                            }
                            is LiveServerMessage.ErrorMessage -> {
                                val errCode = msg.error.code ?: 500
                                val errMsg = msg.error.message ?: "Gemini Live server error"
                                DiagnosticLogBuffer.record(
                                    "GeminiLiveEngine",
                                    "Live narration server error code=$errCode model=$cleanModel: $errMsg",
                                    level = "ERROR"
                                )
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
                                DiagnosticLogBuffer.record(
                                    "GeminiLiveEngine",
                                    "GoAway received on model=$cleanModel (timeLeft=${msg.goAway.timeLeft ?: "none"}); rotating after turn",
                                    level = "WARN"
                                )
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
            isExpressivePunctuation: Boolean = false,
            emitChunk: suspend (PcmChunk) -> Unit
        ): TurnOutcome {
            val turnChannel = Channel<TurnEvent>(Channel.UNLIMITED)
            activeChannelRef.set(turnChannel)
            val transcriptBuilder = StringBuilder()
            val bufferedChunks = if (bufferAndVerifyTurns) mutableListOf<PcmChunk>() else null
            var collectedAnyAudio = false
            var emittedAnyAudio = false
            var completedTurn = false
            var sawGenerationComplete = false
            var idleTimedOutAfterAudio = false
            var interrupted = false
            var failed = false
            var timedOut = false
            var hardModelError = false

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

                while (true) {
                    val stepTimeoutMs = if (!collectedAnyAudio) {
                        timeoutMs
                    } else if (sawGenerationComplete) {
                        minOf(timeoutMs, POST_GENERATION_IDLE_TIMEOUT_MS)
                    } else {
                        minOf(timeoutMs, POST_AUDIO_IDLE_TIMEOUT_MS)
                    }
                    val event = withTimeoutOrNull(stepTimeoutMs) {
                        turnChannel.receive()
                    }
                    if (event == null) {
                        if (!collectedAnyAudio) {
                            timedOut = true
                            DiagnosticLogBuffer.record(
                                "GeminiLiveEngine",
                                "Live turn timed out after ${timeoutMs}ms (model=$cleanModel, chars=${request.text.length}, collectedAudio=false)",
                                level = "WARN"
                            )
                        } else {
                            idleTimedOutAfterAudio = true
                            DiagnosticLogBuffer.record(
                                "GeminiLiveEngine",
                                "Live turn audio stream went idle after ${stepTimeoutMs}ms without turnComplete (model=$cleanModel, genComplete=$sawGenerationComplete, chars=${request.text.length})",
                                level = "WARN"
                            )
                        }
                        break
                    }
                    when (event) {
                        is TurnEvent.AudioChunk -> {
                            collectedAnyAudio = true
                            if (bufferedChunks != null) {
                                bufferedChunks.add(event.chunk)
                            } else {
                                emittedAnyAudio = true
                                emitChunk(event.chunk)
                            }
                        }
                        is TurnEvent.Transcript -> {
                            appendTranscriptFragment(transcriptBuilder, event.text)
                        }
                        is TurnEvent.GenerationComplete -> {
                            sawGenerationComplete = true
                        }
                        is TurnEvent.TurnComplete -> {
                            completedTurn = true
                            break
                        }
                        is TurnEvent.TurnInterrupted -> {
                            interrupted = true
                            failed = true
                            break
                        }
                        is TurnEvent.ServerError -> {
                            if (event.code in setOf(401, 403, 404, 429, 1007, 1008)) {
                                hardModelError = true
                            }
                            failed = true
                            break
                        }
                        is TurnEvent.StreamFailed -> {
                            inferErrorCode(event.error)?.let { code ->
                                if (code in setOf(400, 401, 403, 404, 429)) {
                                    hardModelError = true
                                }
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
            } catch (e: Exception) {
                if (e is CancellationException) {
                    withContext(NonCancellable) {
                        closeCurrentLiveSessionLocked()
                    }
                    throw e
                }
                DiagnosticLogBuffer.record(
                    "GeminiLiveEngine",
                    "Live turn threw exception (model=$cleanModel)",
                    level = "ERROR",
                    throwable = e
                )
                inferErrorCode(e)?.let { code ->
                    if (code in setOf(400, 401, 403, 404, 429)) {
                        hardModelError = true
                    }
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
            val similarity = if (outputTranscript.isEmpty()) 1.0 else calculateWordOverlapSimilarity(request.text, outputTranscript)
            val sourceWordCount = countWords(request.text)
            val outputWordCount = countWords(outputTranscript)
            val excessiveExpansion = !isExpressivePunctuation &&
                isExcessiveWordExpansion(sourceWordCount, outputWordCount)
            val repeatedPhrase = !isExpressivePunctuation &&
                outputTranscript.isNotEmpty() &&
                hasRepeatedSentenceOrPhrase(request.text, outputTranscript)
            val expressiveTranscriptAcceptable = isExpressivePunctuation &&
                outputWordCount <= 4
            // The set-based similarity above cannot see a single swallowed/clipped word, so also
            // require an ordered alignment with no missing source words.
            val swallowedWords = if (isExpressivePunctuation || outputTranscript.isEmpty()) {
                0
            } else {
                countSwallowedSourceWords(request.text, outputTranscript)
            }
            val similarityOk = outputTranscript.isEmpty() ||
                expressiveTranscriptAcceptable ||
                (similarity >= SIMILARITY_THRESHOLD && !excessiveExpansion && !repeatedPhrase &&
                    swallowedWords <= allowedSwallowedWords(sourceWordCount))
            if (!similarityOk) {
                DiagnosticLogBuffer.record(
                    "GeminiLiveEngine",
                    "Transcript verification failed (similarity=${"%.2f".format(similarity)}, words=$outputWordCount/$sourceWordCount, repeated=$repeatedPhrase, swallowed=$swallowedWords) on model=$cleanModel; rotating session",
                    level = "WARN"
                )
            }

            if (idleTimedOutAfterAudio && !failed && !interrupted) {
                if (sawGenerationComplete || outputTranscript.isEmpty() || similarityOk) {
                    // Server finished streaming audio (and matched transcript/generation) but omitted turnComplete.
                    completedTurn = true
                    needsRotationFlag.set(true)
                } else {
                    timedOut = true
                }
            }

            val audioGlitch = bufferedChunks != null && collectedAnyAudio && !isExpressivePunctuation &&
                completedTurn && !failed &&
                hasPcmGlitch(bufferedChunks)
            if (audioGlitch) {
                DiagnosticLogBuffer.record(
                    "GeminiLiveEngine",
                    "Detected mid-speech audio glitch (dropout/click) in Live turn on model=$cleanModel; regenerating",
                    level = "WARN"
                )
            }
            val turnVerifiedComplete = collectedAnyAudio && completedTurn && !failed && !timedOut && !interrupted && similarityOk && !audioGlitch
            if (bufferedChunks != null && turnVerifiedComplete) {
                val chunksToEmit = if (isExpressivePunctuation) {
                    bufferedChunks
                } else {
                    trimExcessiveEdgeSilence(bufferedChunks)
                }
                for (chunk in chunksToEmit) {
                    emitChunk(chunk)
                }
                emittedAnyAudio = true
            } else if (bufferedChunks != null && collectedAnyAudio && !turnVerifiedComplete) {
                DiagnosticLogBuffer.record(
                    "GeminiLiveEngine",
                    "Discarding unverified partial Live turn on model=$cleanModel (completed=$completedTurn, failed=$failed, timedOut=$timedOut, interrupted=$interrupted, similarityOk=$similarityOk)",
                    level = "WARN"
                )
            }

            if (turnVerifiedComplete || (bufferedChunks == null && emittedAnyAudio && !failed && !timedOut && !interrupted && similarityOk)) {
                lastVerifiedModel = cleanModel
                quotaTracker?.recordSuccess(cleanModel, estimatedTokens)
            }

            val rotateAfterTurn = audioGlitch || failed || timedOut || interrupted || idleTimedOutAfterAudio || !similarityOk || !completedTurn || needsRotationFlag.get()
            return TurnOutcome(
                emittedAnyAudio = emittedAnyAudio,
                rotateAfterTurn = rotateAfterTurn,
                hardModelError = hardModelError
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
        data object GenerationComplete : TurnEvent
        data object TurnComplete : TurnEvent
        data object TurnInterrupted : TurnEvent
        data class ServerError(val code: Int, val message: String) : TurnEvent
        data class StreamFailed(val error: Throwable) : TurnEvent
    }

    private data class TurnOutcome(
        val emittedAnyAudio: Boolean,
        val rotateAfterTurn: Boolean,
        val hardModelError: Boolean = false
    )

    private data class SessionSetupKey(
        val voiceId: String,
        val languageCode: String?
    ) {
        companion object {
            // Key only on narrator voice and language so the Live WebSocket stays open smoothly
            // across character dialogue turns without reconnecting every paragraph.
            fun from(request: SynthesisRequest): SessionSetupKey = SessionSetupKey(
                voiceId = request.voiceId.trim(),
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
            // A dropped/expired resumption session (WebSocket 1008) says nothing about the model;
            // never map it to "model not found", which would permanently disable the model.
            msg.contains("session not found") || msg.contains("expired") -> null
            msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted") -> 429
            msg.contains("404") || msg.contains("1008") || msg.contains("not found") -> 404
            msg.contains("400") || msg.contains("1007") -> 400
            msg.contains("503") || msg.contains("500") -> 503
            else -> null
        }
    }

    fun calculateWordOverlapSimilarity(source: String, output: String): Double {
        if (source.isBlank() && output.isBlank()) return 1.0
        val sourceWords = tokenizeForSimilarity(source)
        val outputWords = tokenizeForSimilarity(output)
        if (sourceWords.isEmpty()) return if (outputWords.isEmpty()) 1.0 else 0.0
        val intersection = sourceWords.intersect(outputWords).size
        return intersection.toDouble() / sourceWords.size.toDouble()
    }

    private fun tokenizeForSimilarity(text: String): Set<String> {
        val ordered = tokenizeWordsOrdered(text)
        if (ordered.isNotEmpty()) return ordered.toSet()
        return text.lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { token ->
                val stripped = token.trim { !it.isLetterOrDigit() }
                stripped.ifEmpty { token }
            }
            .toSet()
    }

    private fun countWords(text: String): Int {
        val ordered = tokenizeWordsOrdered(text)
        return if (ordered.isNotEmpty()) {
            ordered.size
        } else {
            text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        }
    }

    companion object {
        const val DEFAULT_MAX_TURNS_PER_SESSION = 6
        const val DEFAULT_MAX_CHARS_PER_SESSION = 4_000
        private const val SIMILARITY_THRESHOLD = 0.75
        private const val POST_GENERATION_IDLE_TIMEOUT_MS = 1_500L
        private const val POST_AUDIO_IDLE_TIMEOUT_MS = 1_500L
        private const val MIN_TRIM_DURATION_MS = 300L
        private const val LEAD_SILENCE_THRESHOLD_MS = 180L
        private const val LEAD_KEEP_MS = 60L
        private const val TAIL_SILENCE_THRESHOLD_MS = 300L
        private const val TAIL_KEEP_MS = 150L
        private const val EDGE_FADE_IN_MS = 4L
        private const val EDGE_FADE_OUT_MS = 8L
        private const val SILENCE_AMPLITUDE_THRESHOLD = 300
        private const val LIVE_NARRATION_TEMPERATURE = 0.4f
        private const val GLITCH_JUMP_THRESHOLD = 16_000
        private const val GLITCH_EDGE_AMPLITUDE = 1_500
        private val TOKEN_SPLIT_REGEX = Regex("[\\s\\u2014\\u2013\\u2026]+")
        private val SENTENCE_SPLIT_REGEX = Regex("[.!?\\n]+")

        /**
         * Splits [text] into lowercased word tokens in appearance order while preserving internal
         * apostrophes in contractions/possessives (e.g. `don't`, `Dokja's`) and normalizing
         * lookalike apostrophe glyphs.
         */
        internal fun tokenizeWordsOrdered(text: String): List<String> {
            if (text.isBlank()) return emptyList()
            val normalizedApostrophes = buildString(text.length) {
                for (ch in text) {
                    when (ch) {
                        '\u2018', '\u2019', '\u02BC', '\u02BB', '\u02BD', '\uFF07', '\u2032' -> append('\'')
                        else -> append(ch)
                    }
                }
            }
            return normalizedApostrophes
                .lowercase()
                .split(TOKEN_SPLIT_REGEX)
                .mapNotNull { raw ->
                    val trimmed = raw.trim { !it.isLetterOrDigit() }
                    trimmed.takeIf { it.isNotEmpty() }
                }
        }

        private val WORD_PART_SPLIT_REGEX = Regex("[\\s\\u2014\\u2013\\u2026\\-/]+")

        /** Number of swallowed source words tolerated for a turn of [sourceWordCount] words. */
        internal fun allowedSwallowedWords(sourceWordCount: Int): Int =
            if (sourceWordCount <= 30) 0 else sourceWordCount / 30

        private fun tokenizeForAlignment(text: String): List<String> =
            tokenizeWordsOrdered(text).flatMap { tok ->
                tok.split(WORD_PART_SPLIT_REGEX).mapNotNull { part ->
                    part.trim { !it.isLetterOrDigit() }.takeIf { it.isNotEmpty() }
                }
            }

        private fun wordsMatch(a: String, b: String): Boolean {
            if (a == b) return true
            val sa = a.replace("'", "")
            val sb = b.replace("'", "")
            if (sa == sb) return true
            val maxLen = maxOf(sa.length, sb.length)
            if (maxLen < 4) return false
            if (sa[0] != sb[0]) return false
            if (kotlin.math.abs(sa.length - sb.length) > 2) return false
            // Tolerate small ASR spelling differences (names, rare words) but not clipped fragments.
            return editDistance(sa, sb) <= maxOf(1, maxLen / 4)
        }

        private fun editDistance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }

        /**
         * Counts source words that have no in-order counterpart in the spoken [output] transcript
         * (an ordered alignment, so a single swallowed or clipped word is detected even when the
         * word appears elsewhere). The last two source words are ignored because the transcript
         * can still be arriving when the turn completes.
         */
        internal fun countSwallowedSourceWords(source: String, output: String): Int {
            val src = tokenizeForAlignment(source)
            val out = tokenizeForAlignment(output)
            if (src.isEmpty()) return 0
            // Fast path: most turns are an exact in-order subsequence of the transcript.
            var si = 0
            for (tok in out) {
                if (si < src.size && tok == src[si]) si++
            }
            if (si == src.size) return 0
            val n = src.size
            val m = out.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in 1..n) {
                for (j in 1..m) {
                    dp[i][j] = if (wordsMatch(src[i - 1], out[j - 1])) {
                        dp[i - 1][j - 1] + 1
                    } else {
                        maxOf(dp[i - 1][j], dp[i][j - 1])
                    }
                }
            }
            // Backtrack to find which source words were matched.
            val matched = BooleanArray(n)
            var i = n
            var j = m
            while (i > 0 && j > 0) {
                if (wordsMatch(src[i - 1], out[j - 1]) && dp[i][j] == dp[i - 1][j - 1] + 1) {
                    matched[i - 1] = true
                    i--; j--
                } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                    i--
                } else {
                    j--
                }
            }
            val considered = (n - 2).coerceAtLeast(0)
            var missing = 0
            for (k in 0 until considered) if (!matched[k]) missing++
            return missing
        }

        /**
         * Detects model-side audio defects inside a buffered 16-bit PCM turn: abrupt waveform
         * discontinuities (clicks from dropped samples) and short digital-silence dropouts that
         * begin and end abruptly in loud speech (the "word cut in half / stutter" artifact).
         * Natural pauses decay smoothly, so they are not flagged.
         */
        internal fun hasPcmGlitch(chunks: List<PcmChunk>): Boolean {
            if (chunks.isEmpty()) return false
            val channels = chunks.first().channels.coerceAtLeast(1)
            val sampleRate = chunks.first().sampleRate.takeIf { it > 0 } ?: 24000
            val bytesPerFrame = channels * 2
            val total = chunks.sumOf { it.data.size }
            val frames = total / bytesPerFrame
            if (frames < 2) return false
            val pcm = ByteArray(total)
            var off = 0
            for (c in chunks) { c.data.copyInto(pcm, off); off += c.data.size }
            fun at(f: Int): Int {
                val i = f * bytesPerFrame
                return (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
            }
            val minDropout = (sampleRate * 3 / 1000).coerceAtLeast(2)
            val maxDropout = sampleRate * 150 / 1000
            var f = 1
            while (f < frames) {
                val prev = at(f - 1)
                val cur = at(f)
                if (abs(cur - prev) > GLITCH_JUMP_THRESHOLD) return true
                if (cur == 0 && abs(prev) > GLITCH_EDGE_AMPLITUDE) {
                    var end = f
                    while (end < frames && at(end) == 0) end++
                    val run = end - f
                    if (run in minDropout..maxDropout && end < frames && abs(at(end)) > GLITCH_EDGE_AMPLITUDE) {
                        return true
                    }
                    f = end
                    continue
                }
                f++
            }
            return false
        }

        internal fun isExcessiveWordExpansion(sourceWordCount: Int, outputWordCount: Int): Boolean =
            when {
                sourceWordCount <= 0 -> false
                sourceWordCount < 4 -> outputWordCount > (sourceWordCount * 2 + 1)
                else -> outputWordCount > ((sourceWordCount * 1.35).toInt() + 2)
            }

        /**
         * Detects when a model transcript echoes/repeats the final sentence, clause, or phrase
         * beyond what is already present in [source], including multi-sentence turns whose total
         * word count stays below [isExcessiveWordExpansion].
         */
        internal fun hasRepeatedSentenceOrPhrase(source: String, output: String): Boolean {
            val sourceTokens = tokenizeWordsOrdered(source)
            val outputTokens = tokenizeWordsOrdered(output)
            if (sourceTokens.isEmpty() || outputTokens.size < 2) return false

            val sourceSentences = splitSentencesIntoTokens(source)
            val outputSentences = splitSentencesIntoTokens(output)
            val sourceAlreadyEndsRepeated = endsWithRepeatedSegment(sourceTokens, sourceSentences)

            // 1. Trailing tandem repeat (... + A + A) at the end of outputTokens.
            val maxBlock = outputTokens.size / 2
            for (k in maxBlock downTo 1) {
                if (k == 1) {
                    if (sourceTokens.size > 2 || outputTokens.size <= sourceTokens.size) continue
                    val tok = outputTokens.last()
                    if (outputTokens[outputTokens.size - 2] != tok) continue
                    val hyphenExpanded = sourceTokens.any { it.contains('-') && it.split('-') == listOf(tok, tok) }
                    if (hyphenExpanded) continue
                    val srcRepeatedSingle = sourceTokens.size >= 2 &&
                        sourceTokens[sourceTokens.size - 1] == tok &&
                        sourceTokens[sourceTokens.size - 2] == tok
                    if (!srcRepeatedSingle) return true
                    continue
                }

                val tail = outputTokens.subList(outputTokens.size - k, outputTokens.size)
                val prev = outputTokens.subList(outputTokens.size - 2 * k, outputTokens.size - k)
                if (prev == tail) {
                    val sourceHasExactTandem = sourceTokens.size >= 2 * k &&
                        sourceTokens.subList(sourceTokens.size - 2 * k, sourceTokens.size) == prev + tail
                    val outputRepeatCount = countTrailingRepeats(outputTokens, tail)
                    if (sourceHasExactTandem) {
                        val sourceRepeatCount = countTrailingRepeats(sourceTokens, tail)
                        if (outputRepeatCount > sourceRepeatCount && outputRepeatCount >= 2 && outputTokens.size > sourceTokens.size) {
                            return true
                        }
                    } else if (!sourceAlreadyEndsRepeated || outputSentences.size > sourceSentences.size) {
                        return true
                    } else {
                        val sourceTrailingCopies = countMaxTrailingCopies(sourceTokens, sourceSentences)
                        if (outputRepeatCount > sourceTrailingCopies && outputTokens.size > sourceTokens.size) {
                            return true
                        }
                    }
                }
            }

            // 2. Extra trailing sentence matching the penultimate output sentence or last source sentence
            // (catches slight wording variations or partial restarts of the final sentence).
            if (outputTokens.size > sourceTokens.size &&
                outputSentences.size > sourceSentences.size &&
                outputSentences.size >= 2
            ) {
                val lastOut = outputSentences.last()
                val prevOut = outputSentences[outputSentences.size - 2]
                val lastSrc = sourceSentences.lastOrNull()
                if (lastOut.size >= 2 && (lastOut == prevOut || (lastSrc != null && lastOut == lastSrc))) {
                    return true
                }
                if (lastOut.size >= 3) {
                    val prevContainment = multisetIntersectionSize(lastOut, prevOut).toDouble() / lastOut.size.toDouble()
                    val srcSymmetricOverlap = if (lastSrc != null && lastSrc.size >= 3) {
                        multisetIntersectionSize(lastOut, lastSrc).toDouble() / maxOf(lastOut.size, lastSrc.size).toDouble()
                    } else {
                        0.0
                    }
                    if (prevContainment >= 0.75 || srcSymmetricOverlap >= 0.70) {
                        return true
                    }
                }
            }

            // 3. Non-tandem repeated trailing n-gram (catches echoed clauses when sentence punctuation is omitted).
            if (outputTokens.size >= sourceTokens.size + 2 &&
                !(sourceAlreadyEndsRepeated && outputSentences.size <= sourceSentences.size)
            ) {
                val lastSrc = sourceSentences.lastOrNull() ?: sourceTokens
                val tailWindowStart = (outputTokens.size - maxOf(lastSrc.size + 6, 12)).coerceAtLeast(0)
                if (outputTokens.size >= 6) {
                    for (i in tailWindowStart..(outputTokens.size - 3)) {
                        val trigram = outputTokens.subList(i, i + 3)
                        val outCount = countNonOverlappingSublist(outputTokens, trigram)
                        if (outCount >= 2 && outCount > countNonOverlappingSublist(sourceTokens, trigram)) {
                            return true
                        }
                    }
                }
                if (lastSrc.size == 2) {
                    val outCount = countNonOverlappingSublist(outputTokens, lastSrc)
                    if (outCount >= 2 && outCount > countNonOverlappingSublist(sourceTokens, lastSrc)) {
                        return true
                    }
                }
            }

            return false
        }

        private fun splitSentencesIntoTokens(text: String): List<List<String>> =
            text.split(SENTENCE_SPLIT_REGEX)
                .map(::tokenizeWordsOrdered)
                .filter { it.isNotEmpty() }

        private fun endsWithRepeatedSegment(tokens: List<String>, sentences: List<List<String>>): Boolean {
            if (sentences.size >= 2 && sentences.last().size >= 2 && sentences.last() == sentences[sentences.size - 2]) {
                return true
            }
            val maxBlock = tokens.size / 2
            for (k in 2..maxBlock) {
                if (tokens.subList(tokens.size - k, tokens.size) == tokens.subList(tokens.size - 2 * k, tokens.size - k)) {
                    return true
                }
            }
            return false
        }

        private fun countTrailingRepeats(tokens: List<String>, block: List<String>): Int {
            val k = block.size
            if (k == 0 || tokens.size < k) return 0
            var count = 0
            var end = tokens.size
            while (end >= k && tokens.subList(end - k, end) == block) {
                count++
                end -= k
            }
            return count
        }

        private fun countMaxTrailingCopies(tokens: List<String>, sentences: List<List<String>>): Int {
            var maxCopies = 1
            if (sentences.size >= 2) {
                val lastSent = sentences.last()
                if (lastSent.size >= 2) {
                    var sentCopies = 1
                    var idx = sentences.lastIndex - 1
                    while (idx >= 0 && sentences[idx] == lastSent) {
                        sentCopies++
                        idx--
                    }
                    if (sentCopies > maxCopies) maxCopies = sentCopies
                }
            }
            val maxBlock = tokens.size / 2
            for (k in 2..maxBlock) {
                val tail = tokens.subList(tokens.size - k, tokens.size)
                val copies = countTrailingRepeats(tokens, tail)
                if (copies > maxCopies) maxCopies = copies
            }
            return maxCopies
        }

        private fun multisetIntersectionSize(a: List<String>, b: List<String>): Int {
            if (a.isEmpty() || b.isEmpty()) return 0
            val counts = HashMap<String, Int>(b.size)
            for (tok in b) {
                counts[tok] = (counts[tok] ?: 0) + 1
            }
            var hits = 0
            for (tok in a) {
                val rem = counts[tok] ?: 0
                if (rem > 0) {
                    hits++
                    counts[tok] = rem - 1
                }
            }
            return hits
        }

        private fun countNonOverlappingSublist(tokens: List<String>, pattern: List<String>): Int {
            val n = pattern.size
            if (n == 0 || tokens.size < n) return 0
            var count = 0
            var i = 0
            while (i <= tokens.size - n) {
                if (tokens.subList(i, i + n) == pattern) {
                    count++
                    i += n
                } else {
                    i++
                }
            }
            return count
        }

        private fun buildEffectiveSpeechRequest(
            request: SynthesisRequest,
            isExpressivePunctuation: Boolean,
            spokenText: String,
            styleCue: String?
        ): SynthesisRequest {
            val rawText = if (isExpressivePunctuation) spokenText else request.text
            val normalizedText = SpeechTextNormalizer.normalizeForSpeech(rawText)
            return if (isExpressivePunctuation) {
                request.copy(text = normalizedText, style = styleCue)
            } else if (normalizedText != request.text) {
                request.copy(text = normalizedText)
            } else {
                request
            }
        }

        /**
         * Trims excessive dead-air padding generated at the edges of verified Live turns (>= 300 ms)
         * so paragraph highlights and timers advance into speech promptly without clipping breathing
         * room or introducing clicks.
         */
        internal fun trimExcessiveEdgeSilence(chunks: List<PcmChunk>): List<PcmChunk> {
            if (chunks.isEmpty()) return chunks
            val sampleRate = chunks.first().sampleRate
            val channels = chunks.first().channels
            if (sampleRate <= 0 || channels <= 0) return chunks
            val bytesPerFrame = channels * 2
            if (chunks.any {
                    it.sampleRate != sampleRate ||
                        it.channels != channels ||
                        it.data.size % bytesPerFrame != 0
                }
            ) {
                return chunks
            }

            val totalFrames = chunks.sumOf { it.data.size.toLong() / bytesPerFrame }
            val minTotalFrames = (sampleRate.toLong() * MIN_TRIM_DURATION_MS) / 1000L
            if (totalFrames < minTotalFrames) return chunks

            val firstActiveFrame = findFirstActiveFrame(chunks, channels, bytesPerFrame)
            if (firstActiveFrame < 0L) return chunks
            val lastActiveFrame = findLastActiveFrame(chunks, totalFrames, firstActiveFrame, channels, bytesPerFrame)

            val leadSilenceFrames = firstActiveFrame
            val leadThresholdFrames = (sampleRate.toLong() * LEAD_SILENCE_THRESHOLD_MS) / 1000L
            val leadKeepFrames = (sampleRate.toLong() * LEAD_KEEP_MS) / 1000L

            val tailSilenceFrames = (totalFrames - 1L) - lastActiveFrame
            val tailThresholdFrames = (sampleRate.toLong() * TAIL_SILENCE_THRESHOLD_MS) / 1000L
            val tailKeepFrames = (sampleRate.toLong() * TAIL_KEEP_MS) / 1000L

            val trimStartFrame = if (leadSilenceFrames > leadThresholdFrames) {
                (firstActiveFrame - leadKeepFrames).coerceAtLeast(0L)
            } else {
                0L
            }
            val trimEndExclusiveFrame = if (tailSilenceFrames > tailThresholdFrames) {
                (lastActiveFrame + 1L + tailKeepFrames).coerceAtMost(totalFrames)
            } else {
                totalFrames
            }
            if (trimStartFrame == 0L && trimEndExclusiveFrame == totalFrames) return chunks

            val outBytes = sliceFrameRange(chunks, bytesPerFrame, trimStartFrame, trimEndExclusiveFrame)
            val outFrames = outBytes.size / bytesPerFrame
            if (trimStartFrame > 0L) {
                val fadeInFrames = ((sampleRate.toLong() * EDGE_FADE_IN_MS) / 1000L).toInt().coerceIn(0, outFrames)
                applyLinearFade(outBytes, startFrame = 0, frameCount = fadeInFrames, channels = channels, fadeIn = true)
            }
            if (trimEndExclusiveFrame < totalFrames) {
                val fadeOutFrames = ((sampleRate.toLong() * EDGE_FADE_OUT_MS) / 1000L).toInt().coerceIn(0, outFrames)
                applyLinearFade(
                    outBytes,
                    startFrame = outFrames - fadeOutFrames,
                    frameCount = fadeOutFrames,
                    channels = channels,
                    fadeIn = false
                )
            }
            return listOf(PcmChunk(data = outBytes, sampleRate = sampleRate, channels = channels))
        }

        private fun isFrameActive(data: ByteArray, byteOffset: Int, channels: Int): Boolean {
            for (ch in 0 until channels) {
                val idx = byteOffset + ch * 2
                val sample = (data[idx + 1].toInt() shl 8) or (data[idx].toInt() and 0xFF)
                if (abs(sample) > SILENCE_AMPLITUDE_THRESHOLD) return true
            }
            return false
        }

        private fun findFirstActiveFrame(chunks: List<PcmChunk>, channels: Int, bytesPerFrame: Int): Long {
            var globalFrame = 0L
            for (chunk in chunks) {
                val d = chunk.data
                var off = 0
                while (off + bytesPerFrame <= d.size) {
                    if (isFrameActive(d, off, channels)) return globalFrame
                    off += bytesPerFrame
                    globalFrame++
                }
            }
            return -1L
        }

        private fun findLastActiveFrame(
            chunks: List<PcmChunk>,
            totalFrames: Long,
            firstActiveFrame: Long,
            channels: Int,
            bytesPerFrame: Int
        ): Long {
            var chunkEndFrame = totalFrames
            for (ci in chunks.lastIndex downTo 0) {
                val d = chunks[ci].data
                val chunkFrames = d.size.toLong() / bytesPerFrame
                val chunkStartFrame = chunkEndFrame - chunkFrames
                if (chunkEndFrame - 1L <= firstActiveFrame) break

                var frameInChunk = (d.size / bytesPerFrame) - 1
                while (frameInChunk >= 0) {
                    val candidateGlobal = chunkStartFrame + frameInChunk
                    if (candidateGlobal <= firstActiveFrame) return firstActiveFrame
                    if (isFrameActive(d, frameInChunk * bytesPerFrame, channels)) return candidateGlobal
                    frameInChunk--
                }
                chunkEndFrame = chunkStartFrame
            }
            return firstActiveFrame
        }

        private fun sliceFrameRange(
            chunks: List<PcmChunk>,
            bytesPerFrame: Int,
            startFrame: Long,
            endExclusiveFrame: Long
        ): ByteArray {
            val outBytes = ByteArray(((endExclusiveFrame - startFrame) * bytesPerFrame).toInt())
            var cursorFrame = 0L
            var outOffset = 0
            for (chunk in chunks) {
                val d = chunk.data
                val chunkFrames = d.size.toLong() / bytesPerFrame
                val chunkEnd = cursorFrame + chunkFrames
                val copyStart = maxOf(cursorFrame, startFrame)
                val copyEnd = minOf(chunkEnd, endExclusiveFrame)
                if (copyEnd > copyStart) {
                    val srcOffset = ((copyStart - cursorFrame) * bytesPerFrame).toInt()
                    val length = ((copyEnd - copyStart) * bytesPerFrame).toInt()
                    System.arraycopy(d, srcOffset, outBytes, outOffset, length)
                    outOffset += length
                }
                cursorFrame = chunkEnd
            }
            return outBytes
        }

        private fun applyLinearFade(
            pcm: ByteArray,
            startFrame: Int,
            frameCount: Int,
            channels: Int,
            fadeIn: Boolean
        ) {
            if (frameCount <= 0) return
            val bytesPerFrame = channels * 2
            for (i in 0 until frameCount) {
                val gain = if (fadeIn) {
                    i.toFloat() / frameCount.toFloat()
                } else {
                    (frameCount - 1 - i).toFloat() / frameCount.toFloat()
                }
                val frameByteOffset = (startFrame + i) * bytesPerFrame
                for (ch in 0 until channels) {
                    val idx = frameByteOffset + ch * 2
                    val sample = (pcm[idx + 1].toInt() shl 8) or (pcm[idx].toInt() and 0xFF)
                    val scaled = (sample * gain).roundToInt().coerceIn(-32768, 32767)
                    pcm[idx] = (scaled and 0xFF).toByte()
                    pcm[idx + 1] = ((scaled ushr 8) and 0xFF).toByte()
                }
            }
        }

        /**
         * Joins streaming BPE outputTranscription fragments without inserting artificial spaces
         * mid-word (e.g. "Attr" + "ibute") while keeping space separation after sentence punctuation
         * when unspaced chunks concatenate across turns.
         */
        internal fun appendTranscriptFragment(builder: StringBuilder, rawFragment: String) {
            if (rawFragment.isEmpty()) return
            if (builder.isEmpty()) {
                builder.append(rawFragment)
                return
            }
            val prevLast = builder[builder.length - 1]
            val nextFirst = rawFragment[0]
            val needSpace = !prevLast.isWhitespace() &&
                !nextFirst.isWhitespace() &&
                prevLast in setOf('.', ',', ';', ':', '!', '?') &&
                nextFirst.isLetterOrDigit()
            if (needSpace) {
                builder.append(' ')
            }
            builder.append(rawFragment)
        }

        /**
         * Scales turn timeout linearly with paragraph length between 20 s and 90 s so longer
         * paragraphs are not cut off prematurely while short turns fail over promptly.
         */
        fun computeTurnTimeoutMs(textLength: Int): Long =
            (15_000L + textLength.coerceAtLeast(0).toLong() * 140L).coerceIn(20_000L, 90_000L)

        internal fun buildSystemInstruction(request: SynthesisRequest): String = buildString {
            append("You are an accomplished audiobook narrator and voice actor bringing a story to life. ")
            append("Read the user's text aloud strictly verbatim, word for word, without adding, omitting, ")
            append("paraphrasing, replying conversationally, or repeating these instructions. ")
            append("Read each phrase once and only once—never repeat, echo, or restart a sentence. ")
            append("Stop cleanly if a line ends mid-thought with a trailing dash (—) or ellipsis (...) without guessing unfinished words, while pronouncing hyphenated compounds smoothly. ")
            append("Pronounce contractions, possessives, and apostrophe-containing words (such as don't, it's, or Kim's) smoothly as single continuous words without pausing or breaking before an apostrophe. ")
            append("Never speak stage directions, speaker labels, or delivery metadata aloud. ")
            append("Perform with natural human warmth, expressive cadence, emotional presence, vivid intonation, ")
            append("and authentic pauses at punctuation (dashes, ellipses, questions, and exclamations) rather than a flat monotone. ")
            append("When bracketed system prompts or status messages appear (e.g. [text in brackets]), read their exact words clearly in a crisp, composed system-voice cadence.")
            val speakerName = request.speakerName?.trim()?.takeIf { it.isNotEmpty() }
            if (speakerName != null) {
                append(" Speaking role: $speakerName.")
            }
            val personality = request.characterPersonality?.trim()?.takeIf { it.isNotEmpty() }
            if (personality != null) {
                append(" Character personality & temperament: $personality — let this shape your vocal attitude and subtext.")
            }
            val storySummary = request.storySummary?.trim()?.takeIf { it.isNotEmpty() }
            if (storySummary != null) {
                append(" Story progression context: $storySummary — honor the stakes and emotional arc of this moment.")
            }
            val style = request.style?.trim()?.takeIf { it.isNotEmpty() && !it.equals("neutral", ignoreCase = true) }
            if (style != null) {
                append(" Performance direction for these lines (perform, do not speak aloud): $style.")
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
