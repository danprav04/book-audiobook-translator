package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
class ChapterTranslator {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun translateChapter(
        paragraphs: Map<String, String>,
        lines: List<NarrationLine>,
        targetLanguageCode: String
    ): TranslationResult {
        // Simulate translation preserving paragraph IDs and speaker attributions
        val translatedParas = paragraphs.mapValues { (_, text) ->
            if (targetLanguageCode == "es") "[$text]" else text
        }

        val translatedLines = lines.map { line ->
            line.copy(
                style = line.style ?: "neutral"
            )
        }

        return TranslationResult(
            bilingualParagraphs = translatedParas,
            translatedLines = translatedLines
        )
    }
}
