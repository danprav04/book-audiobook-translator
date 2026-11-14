package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import java.io.IOException

/**
 * Client for structured text analysis and text translation using Gemini models.
 */
interface TextModelClient {
    suspend fun generateContent(
        apiKey: String,
        prompt: String,
        systemInstruction: String? = null,
        responseJsonSchema: String? = null,
        model: String = DefaultModels.get(ModelRole.TEXT_ANALYSIS)
    ): String
}

/**
 * Stub implementation of [TextModelClient].
 */
class StubTextModelClient : TextModelClient {
    override suspend fun generateContent(
        apiKey: String,
        prompt: String,
        systemInstruction: String?,
        responseJsonSchema: String?,
        model: String
    ): String {
        return """{"result": "stub_response"}"""
    }
}

/**
 * Production OkHttp implementation of [TextModelClient] calling the Gemini REST
 * `generateContent` endpoint with quota-aware model rotation (Flash Lite -> Gemma 4)
 * and automatic formatting adjustment for Gemma models.
 */
class OkHttpTextModelClient(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val quotaTracker: GeminiQuotaTracker = GeminiQuotaTracker(),
    private val modelCatalogService: ModelCatalogService? = null,
    private val maxCooldownWaitMs: Long = 8_000L
) : TextModelClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generateContent(
        apiKey: String,
        prompt: String,
        systemInstruction: String?,
        responseJsonSchema: String?,
        model: String
    ): String = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        require(trimmedKey.isNotEmpty()) { "API key must not be blank" }

        ensureCatalogLoaded(trimmedKey)

        val primaryModel = model
            .trim()
            .removePrefix("models/")
            .ifEmpty { DefaultModels.get(ModelRole.TEXT_ANALYSIS) }

        val candidates = quotaTracker.textCandidatePool(primaryModel)
        val estimatedTokens = ((prompt.length + (systemInstruction?.length ?: 0)) / 4).coerceAtLeast(100)
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
                    delay(shortestCooldown + 100L)
                    triedThisPass.clear()
                    continue
                }
                val failed = if (attemptedModels.isNotEmpty()) attemptedModels else candidates
                throw QuotaExhaustedException(
                    message = quotaTracker.formatConciseError(failed, shortestCooldown, lastErrorBody),
                    retryAfterMs = shortestCooldown,
                    attemptedModels = failed.distinct()
                )
            }

            triedThisPass.add(modelId)
            attemptedModels.add(modelId)

            val payload = buildRequestJsonForModel(
                modelId = modelId,
                prompt = prompt,
                systemInstruction = systemInstruction,
                responseJsonSchema = responseJsonSchema
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
                    val (text, totalTokens) = extractResponseTextAndTokens(responseBody)
                    quotaTracker.recordSuccess(modelId, totalTokens)
                    return@withContext text
                }

                if (response.code == 401 || response.code == 403) {
                    throw IOException(
                        "Gemini API authentication error (HTTP ${response.code}). Check your API key in Settings."
                    )
                }

                lastErrorBody = responseBody
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

    private fun buildRequestJsonForModel(
        modelId: String,
        prompt: String,
        systemInstruction: String?,
        responseJsonSchema: String?
    ): String {
        val profile = quotaTracker.profileFor(modelId)
        val useSystemField = profile.supportsSystemInstruction && !systemInstruction.isNullOrBlank()
        val effectivePrompt = if (!profile.supportsSystemInstruction && !systemInstruction.isNullOrBlank()) {
            "${systemInstruction.trim()}\n\n$prompt"
        } else {
            prompt
        }
        val useJsonMime = profile.supportsJsonMimeType && responseJsonSchema != null

        return buildJsonObject {
            if (useSystemField) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", systemInstruction!!)
                        }
                    }
                }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", effectivePrompt)
                        }
                    }
                }
            }
            if (useJsonMime) {
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                }
            }
        }.toString()
    }

    private fun extractResponseTextAndTokens(responseBody: String): Pair<String, Int> {
        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (e: Exception) {
            throw IOException("Invalid JSON response from Gemini Text API", e)
        }

        val parts = root["candidates"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray
            ?: throw IOException("Missing candidates/content/parts in Gemini Text response")

        val text = parts.mapNotNull { part ->
            part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("")

        if (text.isEmpty()) {
            throw IOException("Empty text content returned from Gemini Text API")
        }

        val totalTokens = root["usageMetadata"]
            ?.jsonObject
            ?.get("totalTokenCount")
            ?.jsonPrimitive
            ?.intOrNull
            ?: 0

        return text to totalTokens
    }
}
