package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.DiagnosticLogBuffer
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.core.model.PunctuationDialogueHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64

/**
 * Request payload for text-to-speech synthesis via the Interactions API.
 */
/**
 * One segment within a batched [TtsRequest] sharing the same narrator voice while
 * applying segment-specific acting cues (`speechMetadata.style`).
 */
data class TtsSegment(
    val text: String,
    val style: String? = null,
    val speaker: String? = null,
    val personality: String? = null
)

data class TtsRequest(
    val text: String,
    val voiceName: String? = null,
    val speaker: String? = null,
    val style: String? = null,
    val model: String = DefaultModels.get(ModelRole.TTS),
    val languageCode: String? = null,
    val personality: String? = null,
    val storyContext: String? = null,
    val segments: List<TtsSegment> = emptyList()
)

/**
 * Client interface for Gemini Flash TTS (Interactions API / generateContent).
 */
interface InteractionsTtsClient {
    suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray
    fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk>
}

/**
 * Stub implementation of [InteractionsTtsClient].
 */
class StubInteractionsTtsClient : InteractionsTtsClient {
    override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray {
        // Return 100ms of silence (3200 bytes of 16-bit PCM) as stub response
        return ByteArray(3200)
    }

    override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> {
        return emptyFlow()
    }
}

/**
 * Production OkHttp implementation of [InteractionsTtsClient] calling the Gemini REST
 * `generateContent` endpoint with quota-aware TTS model rotation.
 */
class OkHttpInteractionsTtsClient(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val quotaTracker: GeminiQuotaTracker = GeminiQuotaTracker(),
    private val modelCatalogService: ModelCatalogService? = null,
    private val maxCooldownWaitMs: Long = 8_000L
) : InteractionsTtsClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray =
        withContext(Dispatchers.IO) {
            val trimmedKey = apiKey.trim()
            require(trimmedKey.isNotEmpty()) { "API key must not be blank" }

            ensureCatalogLoaded(trimmedKey)

            val primaryModel = request.model
                .trim()
                .removePrefix("models/")
                .ifEmpty { DefaultModels.get(ModelRole.TTS) }

            val candidates = quotaTracker.ttsCandidatePool(primaryModel)
            val resolvedParts = resolveParts(request)
            val totalChars = resolvedParts.sumOf { it.text.length }.coerceAtLeast(request.text.length)
            val estimatedTokens = (totalChars / 4).coerceAtLeast(50)
            val cleanBase = baseUrl.trimEnd('/')
            val mediaType = "application/json; charset=utf-8".toMediaType()

            val attemptedModels = mutableListOf<String>()
            val triedThisPass = mutableSetOf<String>()
            var didCooldownWait = false
            var lastErrorBody = ""
            var stripMetadataForCurrentModel = false

            while (true) {
                val remainingCandidates = candidates.filterNot { it in triedThisPass }
                val modelId = quotaTracker.selectNextReadyModel(remainingCandidates, estimatedTokens)

                if (modelId == null) {
                    val shortestCooldown = quotaTracker.shortestCooldownMs(candidates)
                    if (!didCooldownWait && shortestCooldown != null && shortestCooldown <= maxCooldownWaitMs) {
                        didCooldownWait = true
                        DiagnosticLogBuffer.record(
                            "InteractionsTtsClient",
                            "Waiting ${shortestCooldown + 100L}ms for TTS quota cooldown",
                            level = "WARN"
                        )
                        delay(shortestCooldown + 100L)
                        triedThisPass.clear()
                        continue
                    }
                    val failed = if (attemptedModels.isNotEmpty()) attemptedModels else candidates
                    val errMsg = quotaTracker.formatConciseError(failed, shortestCooldown, lastErrorBody)
                    DiagnosticLogBuffer.record(
                        "InteractionsTtsClient",
                        "TTS quota exhausted across ${failed.distinct().joinToString()}",
                        level = "ERROR"
                    )
                    throw QuotaExhaustedException(
                        message = errMsg,
                        retryAfterMs = shortestCooldown,
                        attemptedModels = failed.distinct()
                    )
                }

                val useSpeechMetadata = supportsSpeechMetadata(modelId) &&
                    !stripMetadataForCurrentModel &&
                    resolvedParts.any { !it.styleCue.isNullOrBlank() }
                val payload = buildRequestJson(
                    request = request,
                    modelId = modelId,
                    includeSpeechMetadata = useSpeechMetadata
                )

                val url = "$cleanBase/v1beta/models/$modelId:generateContent"
                val httpRequest = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", trimmedKey)
                    .post(payload.toRequestBody(mediaType))
                    .build()

                quotaTracker.recordRequestStarted(modelId, estimatedTokens)
                okHttpClient.newCall(httpRequest).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        stripMetadataForCurrentModel = false
                        val (audioBytes, totalTokens) = extractAudioBytesAndTokens(responseBody)
                        quotaTracker.recordSuccess(modelId, totalTokens)
                        return@withContext audioBytes
                    }

                    // If an older/gateway endpoint rejects speechMetadata with HTTP 400, retry the
                    // same model once with pure verbatim text parts before consuming fallback quota.
                    if (response.code == 400 && useSpeechMetadata) {
                        stripMetadataForCurrentModel = true
                        DiagnosticLogBuffer.record(
                            "InteractionsTtsClient",
                            "TTS HTTP 400 with speechMetadata on $modelId; retrying plain verbatim text",
                            level = "WARN"
                        )
                        return@use
                    }

                    stripMetadataForCurrentModel = false
                    triedThisPass.add(modelId)
                    attemptedModels.add(modelId)

                    if (response.code == 401 || response.code == 403) {
                        DiagnosticLogBuffer.record(
                            "InteractionsTtsClient",
                            "TTS authentication failed HTTP ${response.code} (model=$modelId)",
                            level = "ERROR"
                        )
                        throw IOException(
                            "Gemini API authentication error (HTTP ${response.code}). Check your API key in Settings."
                        )
                    }

                    lastErrorBody = responseBody
                    DiagnosticLogBuffer.record(
                        "InteractionsTtsClient",
                        "TTS request failed HTTP ${response.code} (model=$modelId)",
                        level = "WARN"
                    )
                    quotaTracker.recordHttpError(
                        modelId = modelId,
                        httpCode = response.code,
                        responseBody = responseBody,
                        retryAfterHeader = response.header("Retry-After")
                    )
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("Unreachable")
        }

    override fun synthesizeStream(apiKey: String, request: TtsRequest): Flow<PcmChunk> = flow {
        val pcmBytes = synthesizeUnary(apiKey, request)
        var offset = 0
        while (offset < pcmBytes.size) {
            val end = minOf(offset + STREAM_CHUNK_BYTES, pcmBytes.size)
            emit(
                PcmChunk(
                    data = pcmBytes.copyOfRange(offset, end),
                    sampleRate = OUTPUT_SAMPLE_RATE,
                    channels = 1
                )
            )
            offset = end
        }
    }

    private suspend fun ensureCatalogLoaded(apiKey: String) {
        if (modelCatalogService == null || quotaTracker.hasRegisteredCatalogForKey(apiKey)) return
        try {
            val catalog = modelCatalogService.listModels(apiKey)
            quotaTracker.markCatalogLoadedForKey(apiKey, catalog.models)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            quotaTracker.markCatalogLoadedForKey(apiKey, emptyList())
        }
    }

    private data class ResolvedTtsPart(
        val text: String,
        val styleCue: String?
    )

    internal fun supportsSpeechMetadata(modelId: String): Boolean =
        !modelId.contains("2.5", ignoreCase = true)

    internal fun buildRequestJson(
        request: TtsRequest,
        modelId: String = request.model,
        includeSpeechMetadata: Boolean = supportsSpeechMetadata(modelId)
    ): String {
        val parts = resolveParts(request)
        val voiceName = request.voiceName?.trim()?.takeIf { it.isNotEmpty() } ?: "Puck"

        return buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        for (part in parts) {
                            addJsonObject {
                                put("text", part.text)
                                val cue = part.styleCue
                                if (includeSpeechMetadata && !cue.isNullOrBlank()) {
                                    putJsonObject("speechMetadata") {
                                        put("style", cue)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") {
                    add(JsonPrimitive("AUDIO"))
                }
                putJsonObject("speechConfig") {
                    putJsonObject("voiceConfig") {
                        putJsonObject("prebuiltVoiceConfig") {
                            put("voiceName", voiceName)
                        }
                    }
                }
            }
        }.toString()
    }

    /**
     * Returns strictly verbatim book text for TTS input parts so Gemini TTS never speaks
     * inline directives (such as `"Say as narrator..."`) aloud.
     */
    internal fun buildPromptText(request: TtsRequest): String {
        val resolved = PunctuationDialogueHelper.resolve(request.text, request.style)
        if (resolved.kind == PunctuationDialogueHelper.PunctuationKind.EMPTY ||
            resolved.kind == PunctuationDialogueHelper.PunctuationKind.SILENT_DIVIDER
        ) {
            return request.text
        }
        return resolved.spokenText
    }

    /**
     * Builds the non-spoken `speechMetadata.style` directing cue for a single expressive narrator
     * performing character-aware intonations without switching voices.
     */
    internal fun buildMetadataStyleCue(
        speaker: String?,
        style: String?,
        personality: String?,
        storyContext: String?
    ): String? {
        val cleanSpeaker = sanitizeDirectiveValue(speaker)
        val cleanPersonality = sanitizeDirectiveValue(personality)?.take(100)
        val cleanStyle = sanitizeStyle(style)?.take(80)
        val cleanStory = sanitizeDirectiveValue(storyContext)?.take(120)

        val parts = mutableListOf<String>()
        if (cleanStyle != null) {
            parts.add(cleanStyle)
        }
        when {
            cleanSpeaker != null && !cleanSpeaker.equals("Narrator", ignoreCase = true) -> {
                val portrayal = if (cleanPersonality != null) {
                    "portraying $cleanSpeaker ($cleanPersonality)"
                } else {
                    "portraying $cleanSpeaker"
                }
                parts.add(portrayal)
            }
            cleanPersonality != null -> parts.add("narrator demeanor ($cleanPersonality)")
        }
        if (cleanStory != null) {
            parts.add("scene ($cleanStory)")
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(", ").take(260)
    }

    private fun resolveParts(request: TtsRequest): List<ResolvedTtsPart> {
        if (request.segments.isEmpty()) {
            val resolved = PunctuationDialogueHelper.resolve(request.text, request.style)
            if (resolved.kind == PunctuationDialogueHelper.PunctuationKind.EMPTY ||
                resolved.kind == PunctuationDialogueHelper.PunctuationKind.SILENT_DIVIDER
            ) {
                return listOf(ResolvedTtsPart(text = request.text, styleCue = null))
            }
            val cue = buildMetadataStyleCue(
                speaker = request.speaker,
                style = resolved.styleCue ?: request.style,
                personality = request.personality,
                storyContext = request.storyContext
            )
            return listOf(ResolvedTtsPart(text = resolved.spokenText, styleCue = cue))
        }

        // First merge adjacent segments sharing the same base acting cue (before attaching
        // scene context to the leading part) so contiguous prose paragraphs stay one smooth part.
        val grouped = mutableListOf<Pair<String, String?>>()
        for (segment in request.segments) {
            val resolved = PunctuationDialogueHelper.resolve(segment.text, segment.style)
            if (resolved.kind == PunctuationDialogueHelper.PunctuationKind.EMPTY ||
                resolved.kind == PunctuationDialogueHelper.PunctuationKind.SILENT_DIVIDER
            ) {
                continue
            }
            val baseCue = buildMetadataStyleCue(
                speaker = segment.speaker ?: request.speaker,
                style = resolved.styleCue ?: segment.style ?: request.style,
                personality = segment.personality ?: request.personality,
                storyContext = null
            )
            val previous = grouped.lastOrNull()
            if (previous != null && previous.second == baseCue) {
                grouped[grouped.lastIndex] = "${previous.first}\n\n${resolved.spokenText}" to baseCue
            } else {
                grouped.add(resolved.spokenText to baseCue)
            }
        }
        if (grouped.isEmpty()) {
            return listOf(ResolvedTtsPart(text = request.text, styleCue = null))
        }
        val cleanStory = sanitizeDirectiveValue(request.storyContext)?.take(120)
        return grouped.mapIndexed { index, (text, baseCue) ->
            val cueWithScene = if (index == 0 && cleanStory != null) {
                if (baseCue.isNullOrBlank()) "scene ($cleanStory)" else "$baseCue, scene ($cleanStory)".take(260)
            } else {
                baseCue
            }
            ResolvedTtsPart(text = text, styleCue = cueWithScene)
        }
    }

    private fun sanitizeStyle(raw: String?): String? {
        val cleaned = sanitizeDirectiveValue(raw) ?: return null
        return if (cleaned.equals("neutral", ignoreCase = true)) null else cleaned
    }

    private fun sanitizeDirectiveValue(raw: String?): String? {
        val cleaned = raw
            ?.replace(Regex("""[\r\n:]+"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return if (META_INSTRUCTION_PATTERN.containsMatchIn(cleaned)) null else cleaned
    }

    private fun extractAudioBytesAndTokens(responseBody: String): Pair<ByteArray, Int> {
        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (e: Exception) {
            throw IOException("Invalid JSON response from Gemini TTS API", e)
        }

        val parts = root["candidates"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray
            ?: throw IOException("Missing candidates/content/parts in Gemini TTS response")

        val output = ByteArrayOutputStream()
        for (part in parts) {
            val dataBase64 = part.jsonObject["inlineData"]
                ?.jsonObject
                ?.get("data")
                ?.jsonPrimitive
                ?.contentOrNull
                ?: continue
            if (dataBase64.isNotBlank()) {
                val decoded = Base64.getMimeDecoder().decode(dataBase64)
                output.write(stripWavHeaderIfPresent(decoded))
            }
        }

        val bytes = output.toByteArray()
        if (bytes.isEmpty()) {
            throw IOException("No inline audio data returned from Gemini TTS API")
        }
        val totalTokens = root["usageMetadata"]
            ?.jsonObject
            ?.get("totalTokenCount")
            ?.jsonPrimitive
            ?.intOrNull
            ?: 0

        return bytes to totalTokens
    }

    private fun stripWavHeaderIfPresent(bytes: ByteArray): ByteArray {
        if (bytes.size < 44) return bytes
        val isRiff = bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        val isWave = bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'A'.code.toByte() &&
            bytes[10] == 'V'.code.toByte() &&
            bytes[11] == 'E'.code.toByte()
        if (!isRiff || !isWave) return bytes

        var offset = 12
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = ((bytes[offset + 4].toInt() and 0xFF)) or
                ((bytes[offset + 5].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 6].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 7].toInt() and 0xFF) shl 24)
            val dataStart = offset + 8
            if (chunkId == "data" && chunkSize >= 0 && dataStart <= bytes.size) {
                val dataEnd = minOf(dataStart + chunkSize, bytes.size)
                return bytes.copyOfRange(dataStart, dataEnd)
            }
            if (chunkSize < 0) break
            offset = dataStart + chunkSize + (chunkSize and 1)
        }
        return bytes.copyOfRange(44, bytes.size)
    }

    private companion object {
        const val STREAM_CHUNK_BYTES = 8192
        const val OUTPUT_SAMPLE_RATE = 24000
        val META_INSTRUCTION_PATTERN = Regex(
            """(?i)\b(?:read\s+aloud|without\s+translat\w*|without\s+(?:adding\s+)?commentary|no\s+commentary|do\s+not\s+(?:translate|add\s+commentary)|verbatim)\b"""
        )
    }
}
