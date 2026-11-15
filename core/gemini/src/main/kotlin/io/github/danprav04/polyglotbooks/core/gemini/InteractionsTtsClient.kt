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
data class TtsRequest(
    val text: String,
    val voiceName: String? = null,
    val speaker: String? = null,
    val style: String? = null,
    val model: String = DefaultModels.get(ModelRole.TTS),
    val languageCode: String? = null
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
            val estimatedTokens = (request.text.length / 4).coerceAtLeast(50)
            val payload = buildRequestJson(request)
            val cleanBase = baseUrl.trimEnd('/')
            val mediaType = "application/json; charset=utf-8".toMediaType()

            val attemptedModels = mutableListOf<String>()
            val triedThisPass = mutableSetOf<String>()
            var didCooldownWait = false
            var lastErrorBody = ""

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

                triedThisPass.add(modelId)
                attemptedModels.add(modelId)

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
                        val (audioBytes, totalTokens) = extractAudioBytesAndTokens(responseBody)
                        quotaTracker.recordSuccess(modelId, totalTokens)
                        return@withContext audioBytes
                    }

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

    internal fun buildRequestJson(request: TtsRequest): String {
        val promptText = buildPromptText(request)
        val voiceName = request.voiceName?.trim()?.takeIf { it.isNotEmpty() } ?: "Puck"

        return buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", promptText)
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

    internal fun buildPromptText(request: TtsRequest): String {
        val resolved = PunctuationDialogueHelper.resolve(request.text, request.style)
        if (resolved.kind == PunctuationDialogueHelper.PunctuationKind.EMPTY ||
            resolved.kind == PunctuationDialogueHelper.PunctuationKind.SILENT_DIVIDER
        ) {
            return request.text
        }
        val speaker = sanitizeDirectiveValue(request.speaker)
        val style = sanitizeStyle(resolved.styleCue)
        if (speaker == null && style == null) {
            return resolved.spokenText
        }
        return buildString {
            append("Say")
            if (speaker != null) append(" as $speaker")
            if (style != null) {
                if (style.endsWith("tone", ignoreCase = true)) {
                    append(" in a $style")
                } else {
                    append(" in a $style tone")
                }
            }
            append(": ")
            append(resolved.spokenText)
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
