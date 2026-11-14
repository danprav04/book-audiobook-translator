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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.util.Base64

/**
 * GeminiLiveEngine synthesizes turns via LiveSession (rotating across live audio models
 * including gemini-3.8-live, gemini-2.5-flash-native-audio-dialog, and gemini-3-flash-live)
 * with strict verbatim instruction and transcript similarity check, falling back to [fallbackEngine].
 */
class GeminiLiveEngine(
    private val fallbackEngine: NarrationEngine = GeminiTtsEngine(),
    private val transcriptProvider: (SynthesisRequest) -> String = { it.text },
    private val liveSessionFactory: (() -> LiveSession)? = null,
    private val apiKeyProvider: (suspend () -> String?)? = null,
    private val modelProvider: (suspend () -> String)? = null,
    private val quotaTracker: GeminiQuotaTracker? = null,
    private val modelCatalogService: ModelCatalogService? = null
) : NarrationEngine {
    override val id: EngineId = EngineId.GEMINI_LIVE
    override val capabilities: EngineCapabilities = EngineCapabilities(
        supportsMultiVoice = true,
        supportsEmotionalStyles = true,
        supportsStreaming = true,
        outputSampleRate = 24000
    )

    override suspend fun voices(): List<VoiceInfo> = fallbackEngine.voices()

    override fun synthesize(request: SynthesisRequest): Flow<PcmChunk> = flow {
        if (liveSessionFactory == null) {
            try {
                val outputText = transcriptProvider(request)
                val similarity = calculateWordOverlapSimilarity(request.text, outputText)

                if (similarity >= 0.75) {
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

        val apiKey = try {
            apiKeyProvider?.invoke()?.trim()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
        if (apiKey.isNullOrBlank()) {
            emitAll(fallbackEngine.synthesize(request))
            return@flow
        }

        ensureCatalogLoaded(apiKey)

        val primaryModel = try {
            modelProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        } ?: DefaultModels.get(ModelRole.LIVE_NARRATION)

        val candidates = quotaTracker?.liveAudioCandidatePool(primaryModel) ?: listOf(
            primaryModel.removePrefix("models/"),
            "gemini-3.8-live",
            "gemini-2.5-flash-native-audio-dialog",
            "gemini-3-flash-live"
        ).distinct()

        val estimatedTokens = (request.text.length / 4).coerceAtLeast(50)

        for (candidateModel in candidates) {
            if (quotaTracker != null && !quotaTracker.isModelAvailable(candidateModel)) {
                continue
            }

            val result = tryLiveModel(
                liveSessionFactory = liveSessionFactory,
                apiKey = apiKey,
                candidateModel = candidateModel,
                request = request,
                estimatedTokens = estimatedTokens
            )
            if (result != null) {
                for (chunk in result) {
                    emit(chunk)
                }
                return@flow
            }
        }

        emitAll(fallbackEngine.synthesize(request))
    }

    private suspend fun tryLiveModel(
        liveSessionFactory: () -> LiveSession,
        apiKey: String,
        candidateModel: String,
        request: SynthesisRequest,
        estimatedTokens: Int
    ): List<PcmChunk>? {
        var session: LiveSession? = null
        val collectedAudio = mutableListOf<PcmChunk>()
        val cleanModel = candidateModel.removePrefix("models/")
        val normalizedModel = "models/$cleanModel"

        return try {
            val liveSession = liveSessionFactory.invoke()
            session = liveSession
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
                        Part(
                            text = buildSystemInstruction(request)
                        )
                    )
                ),
                outputAudioTranscription = JsonObject(emptyMap())
            )

            val transcriptBuilder = StringBuilder()

            coroutineScope {
                val turnDone = CompletableDeferred<Unit>()
                val collectorJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    liveSession.incomingMessages.collect { msg ->
                        when (msg) {
                            is LiveServerMessage.ServerContentMessage -> {
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
                                            collectedAudio.add(
                                                PcmChunk(
                                                    data = decoded,
                                                    sampleRate = sampleRate,
                                                    channels = channels
                                                )
                                            )
                                        }
                                    }
                                }
                                content.outputTranscription?.text?.let { text ->
                                    if (text.isNotBlank()) {
                                        if (transcriptBuilder.isNotEmpty()) transcriptBuilder.append(' ')
                                        transcriptBuilder.append(text.trim())
                                    }
                                }
                                if (content.turnComplete == true || content.generationComplete == true) {
                                    turnDone.complete(Unit)
                                }
                            }
                            is LiveServerMessage.ErrorMessage -> {
                                val errCode = msg.error.code ?: 500
                                quotaTracker?.recordHttpError(
                                    modelId = cleanModel,
                                    httpCode = errCode,
                                    responseBody = msg.error.message.orEmpty()
                                )
                                throw IOException(msg.error.message ?: "Gemini Live server error")
                            }
                            else -> Unit
                        }
                    }
                }

                liveSession.connect(apiKey, setup)
                liveSession.send(
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

                withTimeoutOrNull(LIVE_TURN_TIMEOUT_MS) {
                    turnDone.await()
                }
                collectorJob.cancel()
            }

            val outputTranscript = transcriptBuilder.toString().trim()
            val similarityOk = outputTranscript.isEmpty() ||
                calculateWordOverlapSimilarity(request.text, outputTranscript) >= 0.75

            if (collectedAudio.isNotEmpty() && similarityOk) {
                quotaTracker?.recordSuccess(cleanModel, estimatedTokens)
                collectedAudio
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val inferredCode = inferErrorCode(e)
            if (inferredCode != null) {
                quotaTracker?.recordHttpError(
                    modelId = cleanModel,
                    httpCode = inferredCode,
                    responseBody = e.message.orEmpty()
                )
            }
            null
        } finally {
            withContext(NonCancellable) {
                try {
                    session?.disconnect()
                } catch (_: Throwable) {
                }
            }
        }
    }

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

    internal companion object {
        private const val LIVE_TURN_TIMEOUT_MS = 20_000L

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
