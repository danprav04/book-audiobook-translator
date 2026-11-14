package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
 * `generateContent` endpoint with automatic model fallback on HTTP 404/400.
 */
class OkHttpTextModelClient(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com"
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

        val primaryModel = model
            .trim()
            .removePrefix("models/")
            .ifEmpty { DefaultModels.get(ModelRole.TEXT_ANALYSIS) }

        val modelsToTry = listOf(
            primaryModel,
            "gemini-2.5-flash",
            "gemini-2.0-flash"
        ).distinct()

        val payload = buildRequestJson(prompt, systemInstruction, responseJsonSchema)
        val cleanBase = baseUrl.trimEnd('/')
        val mediaType = "application/json; charset=utf-8".toMediaType()
        var lastFallbackError: IOException? = null

        for (modelId in modelsToTry) {
            val url = "$cleanBase/v1beta/models/$modelId:generateContent"
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", trimmedKey)
                .post(payload.toRequestBody(mediaType))
                .build()

            okHttpClient.newCall(httpRequest).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    return@withContext extractResponseText(responseBody)
                }
                val err = IOException(
                    "Gemini Text API error (HTTP ${response.code}) for model $modelId: $responseBody"
                )
                if (response.code == 404 || response.code == 400) {
                    lastFallbackError = err
                } else {
                    throw err
                }
            }
        }

        throw lastFallbackError ?: IOException("Gemini Text API failed for all candidate models")
    }

    private fun buildRequestJson(
        prompt: String,
        systemInstruction: String?,
        responseJsonSchema: String?
    ): String {
        return buildJsonObject {
            if (!systemInstruction.isNullOrBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", systemInstruction)
                        }
                    }
                }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", prompt)
                        }
                    }
                }
            }
            if (responseJsonSchema != null) {
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                }
            }
        }.toString()
    }

    private fun extractResponseText(responseBody: String): String {
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
        return text
    }
}
