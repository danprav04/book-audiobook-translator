package io.github.danprav04.polyglotbooks.core.gemini

import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.ModelRole
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

    private val EXCLUDED_TEXT_KEYWORDS = listOf(
        "deep-research",
        "antigravity",
        "embedding",
        "aqa",
        "imagen",
        "veo",
        "-pt",
        "computer-use",
        "robotics"
    )

    private val EXCLUDED_LIVE_NARRATION_KEYWORDS = listOf(
        "translate",
        "transcribe",
        "extended-thinking",
        "genie",
        "robotics"
    )

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
                methods.contains("bidiGenerateContent") &&
                    EXCLUDED_LIVE_NARRATION_KEYWORDS.none { keyword -> nameLower.contains(keyword) }
            }
            ModelRole.LIVE_TRANSCRIBE -> {
                methods.contains("bidiGenerateContent") && nameLower.contains("transcribe")
            }
            ModelRole.TEXT_ANALYSIS,
            ModelRole.TEXT_TRANSLATION -> {
                methods.contains("generateContent") &&
                    !nameLower.contains("tts") &&
                    EXCLUDED_TEXT_KEYWORDS.none { keyword -> nameLower.contains(keyword) }
            }
        }
    }

    /**
     * Returns a concise free-tier quota summary for the given model ID for UI dropdowns/cards.
     */
    fun freeTierHint(modelId: String): String {
        val id = modelId.trim().removePrefix("models/").lowercase()
        return when {
            id.startsWith("gemini-2.0-flash") -> "0/0 free-tier quota (avoid)"
            id.contains("tts") -> "3 RPM · 10K TPM · 10 RPD (scarce)"
            id.startsWith("gemini-2.5-flash-native-audio") -> "Unlimited RPM · 1M TPM · Unlimited RPD"
            id.startsWith("gemini-3.8-live") || id.startsWith("gemini-3-flash-live") ->
                "Unlimited RPM · 65K TPM · Unlimited RPD"
            id.startsWith("gemini-3.5-live-translate") || id.startsWith("gemini-3.5-transcribe-live") ->
                "Unlimited RPM · 20K TPM · Unlimited RPD"
            id.contains("live") -> "Unlimited RPM · 65K TPM · Unlimited RPD"
            id.startsWith("gemma-4") || id.startsWith("gemma-3") ->
                "30 RPM · 16K TPM · 14.4K RPD (fallback pool)"
            id.contains("flash-lite") -> "15 RPM · 250K TPM · 500 RPD"
            id.startsWith("gemini-2.5-flash") || id.startsWith("gemini-3-flash") ->
                "10 RPM · 250K TPM · 250 RPD"
            else -> "Standard Gemini model"
        }
    }

    /**
     * Returns recommended model IDs for [role], combining defaults, curated free-tier
     * Flash Lite / Gemma 4 / Live Audio models, and any matching discovered catalog models.
     */
    fun recommendedModelsForRole(
        role: ModelRole,
        catalogModels: List<ModelItem> = emptyList()
    ): List<String> {
        val defaultModel = DefaultModels.get(role)
        val curated = when (role) {
            ModelRole.TEXT_ANALYSIS,
            ModelRole.TEXT_TRANSLATION -> listOf(
                defaultModel,
                "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite",
                "gemini-2.5-flash-lite",
                "gemini-2.5-flash",
                "gemma-4-31b-it",
                "gemma-4-26b-a4b-it"
            )
            ModelRole.TTS -> listOf(
                defaultModel,
                "gemini-3.8-flash-tts",
                "gemini-2.5-flash-preview-tts"
            )
            ModelRole.LIVE_NARRATION -> listOf(
                defaultModel,
                "gemini-3.1-flash-live-preview",
                "gemini-2.5-flash-native-audio-latest",
                "gemini-3.8-live"
            )
            ModelRole.LIVE_TRANSLATE -> listOf(
                defaultModel,
                "gemini-3.5-live-translate-preview",
                "gemini-2.5-flash-native-audio-dialog",
                "gemini-3.8-live"
            )
            ModelRole.LIVE_TRANSCRIBE -> listOf(
                defaultModel,
                "gemini-3.5-transcribe-live"
            )
        }

        val discovered = filterModelsForRole(catalogModels, role)
            .map { it.id }
            .filterNot { id ->
                id.lowercase().startsWith("gemini-2.0-flash") ||
                    (role == ModelRole.LIVE_NARRATION && id.contains("transcribe", ignoreCase = true))
            }

        return (curated + discovered).distinct()
    }
}
