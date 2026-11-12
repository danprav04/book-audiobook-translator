package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.ModelRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * REST response from `GET https://generativelanguage.googleapis.com/v1beta/models`.
 */
@Serializable
data class ModelListResponse(
    val models: List<ModelItem> = emptyList(),
    val nextPageToken: String? = null
)

/**
 * Individual model entry returned by the models.list endpoint.
 */
@Serializable
data class ModelItem(
    val name: String,
    val baseModelId: String? = null,
    val version: String? = null,
    val displayName: String? = null,
    val description: String? = null,
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null,
    val supportedGenerationMethods: List<String> = emptyList(),
    val temperature: Float? = null,
    val maxTemperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null
) {
    /**
     * Clean model ID without "models/" prefix.
     */
    val id: String
        get() = name.removePrefix("models/")
}

/**
 * Parser and catalog query helpers for Gemini models.
 */
object ModelCatalog {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses the JSON payload returned by `GET /v1beta/models`.
     */
    fun parseModelsJson(jsonString: String): ModelListResponse {
        return json.decodeFromString(ModelListResponse.serializer(), jsonString)
    }

    /**
     * Filters a list of [ModelItem]s matching the capabilities required for a given [ModelRole].
     */
    fun filterModelsForRole(models: List<ModelItem>, role: ModelRole): List<ModelItem> {
        return models.filter { matchesRole(it, role) }
    }

    /**
     * Determines whether a given model item satisfies the method and naming requirements for a role.
     */
    fun matchesRole(model: ModelItem, role: ModelRole): Boolean {
        val methods = model.supportedGenerationMethods
        val nameLower = model.name.lowercase()

        return when (role) {
            ModelRole.LIVE_TRANSLATE -> {
                methods.contains("bidiGenerateContent") && nameLower.contains("translate")
            }
            ModelRole.TTS -> {
                methods.contains("interactions") ||
                    (methods.contains("generateContent") && nameLower.contains("tts"))
            }
            ModelRole.LIVE_NARRATION -> {
                methods.contains("bidiGenerateContent") && !nameLower.contains("translate")
            }
            ModelRole.LIVE_TRANSCRIBE -> {
                methods.contains("bidiGenerateContent") && nameLower.contains("transcribe")
            }
            ModelRole.TEXT_ANALYSIS,
            ModelRole.TEXT_TRANSLATION -> {
                methods.contains("generateContent") && !nameLower.contains("tts")
            }
        }
    }
}
