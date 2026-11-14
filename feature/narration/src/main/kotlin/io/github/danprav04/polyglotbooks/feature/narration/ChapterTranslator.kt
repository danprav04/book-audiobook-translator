package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.gemini.BatchBudget
import io.github.danprav04.polyglotbooks.core.gemini.GeminiQuotaTracker
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
 * into a target language using quota-aware adaptive batching and source-text deduplication.
 */
class ChapterTranslator(
    private val textModelClient: TextModelClient? = null,
    private val apiKeyProvider: (suspend () -> String?)? = null,
    private val modelProvider: (suspend () -> String)? = null,
    private val quotaTracker: GeminiQuotaTracker? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun translateChapter(
        paragraphs: Map<String, String>,
        lines: List<NarrationLine>,
        targetLanguageCode: String,
        onBatchTranslated: (suspend (Map<String, String>) -> Unit)? = null
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
            onBatchTranslated?.invoke(translatedParas)
            return TranslationResult(
                bilingualParagraphs = translatedParas,
                translatedLines = translatedLines
            )
        }

        val language = LanguageCode.fromCode(targetLanguageCode)
        val languageLabel = "${language.displayName} (${language.code})"
        val primaryModel = modelProvider?.invoke()?.trim()?.takeIf { it.isNotEmpty() }
            ?: DefaultModels.get(ModelRole.TEXT_TRANSLATION)

        val systemInstruction = "You are a literary translator. Translate each string in the input " +
            "JSON array into $languageLabel, preserving literary tone, punctuation, and meaning. " +
            "Return ONLY a JSON array of translated strings matching the exact length and order of the input array."

        val translatedParas = LinkedHashMap<String, String>(paragraphs.size)
        val uniqueGroups = LinkedHashMap<String, MutableList<String>>()
        val blankBatch = LinkedHashMap<String, String>()

        for ((key, rawText) in paragraphs) {
            if (rawText.isBlank()) {
                translatedParas[key] = rawText
                blankBatch[key] = rawText
            } else {
                uniqueGroups.getOrPut(rawText) { mutableListOf() }.add(key)
            }
        }
        if (blankBatch.isNotEmpty()) {
            onBatchTranslated?.invoke(blankBatch)
        }

        val uniqueSources = uniqueGroups.keys.toList()
        var cursor = 0
        while (cursor < uniqueSources.size) {
            val budget = currentBatchBudget(primaryModel)
            val batchSources = takeAdaptiveBatch(uniqueSources, cursor, budget)
            cursor += batchSources.size

            val translatedBatch = translateBatchWithSplitFallback(
                apiKey = apiKey,
                sourceTexts = batchSources,
                languageLabel = languageLabel,
                systemInstruction = systemInstruction,
                model = primaryModel
            )

            val batchResult = LinkedHashMap<String, String>(batchSources.size)
            for (i in batchSources.indices) {
                val src = batchSources[i]
                val translated = translatedBatch.getOrNull(i)?.takeIf { it.isNotBlank() } ?: src
                val keysForSource = uniqueGroups[src].orEmpty()
                for (key in keysForSource) {
                    translatedParas[key] = translated
                    batchResult[key] = translated
                }
            }
            if (batchResult.isNotEmpty()) {
                onBatchTranslated?.invoke(batchResult)
            }
        }

        return TranslationResult(
            bilingualParagraphs = translatedParas,
            translatedLines = translatedLines
        )
    }

    private fun currentBatchBudget(primaryModel: String): BatchBudget {
        if (quotaTracker == null) {
            return BatchBudget(
                maxChars = DEFAULT_BATCH_CHARS,
                maxItems = DEFAULT_BATCH_ITEMS,
                targetModel = primaryModel
            )
        }
        val candidates = quotaTracker.textCandidatePool(primaryModel)
        return quotaTracker.recommendedBatchBudget(candidates)
    }

    private fun takeAdaptiveBatch(
        sources: List<String>,
        startIndex: Int,
        budget: BatchBudget
    ): List<String> {
        val batch = mutableListOf<String>()
        var chars = 0
        var idx = startIndex
        while (idx < sources.size && batch.size < budget.maxItems) {
            val next = sources[idx]
            if (batch.isNotEmpty() && chars + next.length > budget.maxChars) {
                break
            }
            batch.add(next)
            chars += next.length
            idx++
        }
        return batch
    }

    private suspend fun translateBatchWithSplitFallback(
        apiKey: String,
        sourceTexts: List<String>,
        languageLabel: String,
        systemInstruction: String,
        model: String
    ): List<String> {
        val prompt = "Translate the following JSON array of ${sourceTexts.size} paragraphs into $languageLabel:\n" +
            json.encodeToString(sourceTexts)

        val rawResponse = textModelClient!!.generateContent(
            apiKey = apiKey,
            prompt = prompt,
            systemInstruction = systemInstruction,
            responseJsonSchema = TRANSLATION_ARRAY_SCHEMA,
            model = model
        )

        val parsed = runCatching { parseTranslatedArray(rawResponse) }.getOrNull()
        if (parsed != null && parsed.size >= sourceTexts.size) {
            return parsed.take(sourceTexts.size)
        }

        if (sourceTexts.size > SPLIT_THRESHOLD) {
            val mid = sourceTexts.size / 2
            val left = translateBatchWithSplitFallback(
                apiKey = apiKey,
                sourceTexts = sourceTexts.subList(0, mid),
                languageLabel = languageLabel,
                systemInstruction = systemInstruction,
                model = model
            )
            val right = translateBatchWithSplitFallback(
                apiKey = apiKey,
                sourceTexts = sourceTexts.subList(mid, sourceTexts.size),
                languageLabel = languageLabel,
                systemInstruction = systemInstruction,
                model = model
            )
            return left + right
        }

        return parsed ?: sourceTexts
    }

    private fun parseTranslatedArray(rawResponse: String): List<String> {
        val cleaned = rawResponse.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val element = try {
            json.parseToJsonElement(cleaned)
        } catch (e: Exception) {
            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            if (start >= 0 && end > start) {
                json.parseToJsonElement(cleaned.substring(start, end + 1))
            } else {
                throw e
            }
        }

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
        const val DEFAULT_BATCH_CHARS = 12_000
        const val DEFAULT_BATCH_ITEMS = 80
        const val SPLIT_THRESHOLD = 12
        const val TRANSLATION_ARRAY_SCHEMA = """{"type":"array","items":{"type":"string"}}"""
    }
}
