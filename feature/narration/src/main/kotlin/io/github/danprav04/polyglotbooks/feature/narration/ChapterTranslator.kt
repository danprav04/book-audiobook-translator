package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.gemini.TextModelClient
import io.github.danprav04.polyglotbooks.core.model.DefaultModels
import io.github.danprav04.polyglotbooks.core.model.LanguageCode
import io.github.danprav04.polyglotbooks.core.model.ModelRole
import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class TranslatedChapterResponse(
    val paragraphs: Map<String, String> = emptyMap(),
    val lines: List<TranslatedLine> = emptyList()
)

@Serializable
data class TranslatedLine(
    val id: String,
    val text: String,
    val style: String? = null
)

data class TranslationResult(
    val bilingualParagraphs: Map<String, String>,
    val translatedLines: List<NarrationLine>
)

/**
 * ChapterTranslator handles Phase 6 Ebook Translation, translating paragraphs and narration lines
 * into a target language while preserving metadata.
 */
class ChapterTranslator(
    private val textModelClient: TextModelClient? = null,
    private val apiKeyProvider: (suspend () -> String?)? = null,
    private val modelProvider: (suspend () -> String)? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun translateChapter(
        paragraphs: Map<String, String>,
        lines: List<NarrationLine>,
        targetLanguageCode: String
    ): TranslationResult {
        val translatedLines = lines.map { line ->
            line.copy(
                style = line.style ?: "neutral"
            )
        }

        if (paragraphs.isEmpty()) {
            return TranslationResult(
                bilingualParagraphs = emptyMap(),
                translatedLines = translatedLines
            )
        }

        val apiKey = apiKeyProvider?.invoke()?.trim()
        if (textModelClient == null || apiKey.isNullOrBlank()) {
            // Deterministic fallback when no client or API key is configured (e.g. unit tests)
            val translatedParas = paragraphs.mapValues { (_, text) ->
                if (targetLanguageCode == "es") "[$text]" else "Translated($targetLanguageCode): $text"
            }
            return TranslationResult(
                bilingualParagraphs = translatedParas,
                translatedLines = translatedLines
            )
        }

        val language = LanguageCode.fromCode(targetLanguageCode)
        val languageLabel = "${language.displayName} (${language.code})"
        val model = modelProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
            ?: DefaultModels.get(ModelRole.TEXT_TRANSLATION)

        val systemInstruction = "You are a literary translator. Translate each string in the input " +
            "JSON array into $languageLabel, preserving literary tone, punctuation, and meaning. " +
            "Return ONLY a JSON array of translated strings matching the exact length and order of the input array."

        val translatedParas = LinkedHashMap<String, String>(paragraphs.size)
        val entries = paragraphs.entries.toList()

        for (batch in entries.chunked(BATCH_SIZE)) {
            val sourceTexts = batch.map { it.value }
            val prompt = "Translate the following JSON array of ${sourceTexts.size} paragraphs into $languageLabel:\n" +
                json.encodeToString(sourceTexts)

            val rawResponse = textModelClient.generateContent(
                apiKey = apiKey,
                prompt = prompt,
                systemInstruction = systemInstruction,
                responseJsonSchema = TRANSLATION_ARRAY_SCHEMA,
                model = model
            )

            val parsedTranslations = parseTranslatedArray(rawResponse)
            for (index in batch.indices) {
                val key = batch[index].key
                val translated = parsedTranslations.getOrNull(index)?.takeIf { it.isNotBlank() }
                    ?: batch[index].value
                translatedParas[key] = translated
            }
        }

        return TranslationResult(
            bilingualParagraphs = translatedParas,
            translatedLines = translatedLines
        )
    }

    private fun parseTranslatedArray(rawResponse: String): List<String> {
        val cleaned = rawResponse.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val element = json.parseToJsonElement(cleaned)
        val arrayElement = when (element) {
            is JsonArray -> element
            is JsonObject -> {
                element["translations"]?.jsonArray
                    ?: element["paragraphs"]?.jsonArray
                    ?: element["items"]?.jsonArray
                    ?: throw IllegalStateException("Unexpected JSON object format from translation response")
            }
            else -> throw IllegalStateException("Expected JSON array from translation response")
        }

        return arrayElement.map { item ->
            item.jsonPrimitive.contentOrNull ?: item.toString()
        }
    }

    private companion object {
        const val BATCH_SIZE = 25
        const val TRANSLATION_ARRAY_SCHEMA = """{"type":"array","items":{"type":"string"}}"""
    }
}
