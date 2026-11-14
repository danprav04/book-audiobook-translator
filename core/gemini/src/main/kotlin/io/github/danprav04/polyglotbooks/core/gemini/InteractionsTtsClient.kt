package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
    val model: String = DefaultModels.get(ModelRole.TTS)
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
 * `generateContent` endpoint with audio response modality and automatic model fallback.
 */
class OkHttpInteractionsTtsClient(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://generativelanguage.googleapis.com"
) : InteractionsTtsClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun synthesizeUnary(apiKey: String, request: TtsRequest): ByteArray =
        withContext(Dispatchers.IO) {
            val trimmedKey = apiKey.trim()
            require(trimmedKey.isNotEmpty()) { "API key must not be blank" }

            val primaryModel = request.model
                .trim()
                .removePrefix("models/")
                .ifEmpty { DefaultModels.get(ModelRole.TTS) }

            val modelsToTry = listOf(
                primaryModel,
                "gemini-2.5-flash-preview-tts",
                "gemini-2.0-flash"
            ).distinct()

            val payload = buildRequestJson(request)
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
                        return@withContext extractAudioBytes(responseBody)
                    }
                    val err = IOException(
                        "Gemini TTS API error (HTTP ${response.code}) for model $modelId: $responseBody"
                    )
                    if (response.code == 404 || response.code == 400) {
                        lastFallbackError = err
                    } else {
                        throw err
                    }
                }
            }

            throw lastFallbackError ?: IOException("Gemini TTS API failed for all candidate models")
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

    private fun buildRequestJson(request: TtsRequest): String {
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

    private fun buildPromptText(request: TtsRequest): String {
        val style = request.style?.trim()?.takeIf { it.isNotEmpty() }
        val speaker = request.speaker?.trim()?.takeIf { it.isNotEmpty() }
        return if (style != null || speaker != null) {
            buildString {
                append("Say")
                if (speaker != null) append(" as $speaker")
                if (style != null) append(" in a $style tone")
                append(": ")
                append(request.text)
            }
        } else {
            request.text
        }
    }

    private fun extractAudioBytes(responseBody: String): ByteArray {
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
        return bytes
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
    }
}
