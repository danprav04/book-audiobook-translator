package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.Character
import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Raw data structures matching the LLM JSON schema.
 */
@Serializable
data class RawCastingResponse(
    val characters: List<RawCharacter> = emptyList(),
    val lines: List<RawLine> = emptyList()
)

@Serializable
data class RawCharacter(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val gender: String = "unknown",
    val age: String = "unknown",
    val voiceTraits: String = ""
)

@Serializable
data class RawLine(
    val para: Int,
    val start: Int,
    val end: Int,
    val speaker: String,
    val style: String? = null
)

/**
 * Normalized result of casting analysis.
 */
data class CastingResult(
    val characters: List<Character>,
    val lines: List<NarrationLine>
)

/**
 * CastingService produces the structured prompt schema for LLM character attribution
 * and validates/normalizes the JSON response against chapter paragraph texts.
 */
class CastingService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * Returns JSON schema describing expected character attribution output.
     */
    fun getPromptJsonSchema(): String {
        return """
        {
          "type": "object",
          "properties": {
            "characters": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "id": { "type": "string" },
                  "name": { "type": "string" },
                  "aliases": { "type": "array", "items": { "type": "string" } },
                  "gender": { "type": "string" },
                  "age": { "type": "string" },
                  "voiceTraits": { "type": "string" }
                },
                "required": ["id", "name", "gender", "age", "voiceTraits"]
              }
            },
            "lines": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "para": { "type": "integer" },
                  "start": { "type": "integer" },
                  "end": { "type": "integer" },
                  "speaker": { "type": "string" },
                  "style": { "type": "string" }
                },
                "required": ["para", "start", "end", "speaker"]
              }
            }
          },
          "required": ["characters", "lines"]
        }
        """.trimIndent()
    }

    fun parseAndValidateCasting(
        jsonResponse: String,
        paragraphs: List<String>,
        existingCharacters: List<Character> = emptyList(),
        bookId: String = "book-1",
        chapterId: String = "chap-1"
    ): CastingResult {
        val result = validateAndNormalize(
            rawJson = jsonResponse,
            paragraphTexts = paragraphs,
            bookId = bookId,
            chapterId = chapterId
        )
        if (existingCharacters.isEmpty()) return result
        val existingByName = existingCharacters.associateBy { it.name.lowercase() }
        val mergedChars = result.characters.map { c ->
            val existing = existingByName[c.name.lowercase()]
            if (existing != null) c.copy(assignedVoiceId = existing.assignedVoiceId) else c
        }
        return result.copy(characters = mergedChars)
    }

    /**
     * Parses rawJson and validates/normalizes it against chapter paragraphs.
     * Guarantees:
     * 1. 0 <= start < end <= paragraph.length
     * 2. Non-overlapping spans within paragraph
     * 3. Gaps between spans (and before first / after last) are auto-filled with speaker = "narrator"
     * 4. Unknown character IDs fall back to "narrator"
     * 5. If JSON is invalid or parsing fails, safe fallback: all paragraphs read by "narrator"
     */
    fun validateAndNormalize(
        rawJson: String,
        paragraphTexts: List<String>,
        bookId: String = "book-default",
        chapterId: String = "chap-default"
    ): CastingResult {
        val parsed = try {
            json.decodeFromString<RawCastingResponse>(rawJson)
        } catch (_: Exception) {
            return createFullNarratorFallback(paragraphTexts, bookId, chapterId)
        }

        val characterMap = parsed.characters.associateBy { it.id }
        val finalCharacters = parsed.characters.map { raw ->
            Character(
                id = raw.id,
                bookId = bookId,
                name = raw.name,
                aliases = raw.aliases,
                gender = raw.gender,
                age = raw.age,
                voiceTraits = raw.voiceTraits
            )
        }

        val normalizedLines = mutableListOf<NarrationLine>()

        // Group valid lines by paragraph index
        val linesByPara = parsed.lines
            .filter { it.para in paragraphTexts.indices }
            .groupBy { it.para }

        for (paraIndex in paragraphTexts.indices) {
            val text = paragraphTexts[paraIndex]
            val textLength = text.length
            if (textLength == 0) continue

            val rawLinesForPara = linesByPara[paraIndex] ?: emptyList()

            // Filter bounds: 0 <= start < end <= textLength
            val validSpans = rawLinesForPara
                .filter { it.start in 0 until textLength && it.end in (it.start + 1)..textLength }
                .sortedBy { it.start }

            // Resolve overlapping spans by advancing start or clipping
            val nonOverlappingSpans = mutableListOf<RawLine>()
            var currentPos = 0

            for (span in validSpans) {
                if (span.end <= currentPos) {
                    continue
                }
                val adjustedStart = maxOf(span.start, currentPos)
                if (adjustedStart < span.end) {
                    val resolvedSpeaker = if (span.speaker == "narrator" || characterMap.containsKey(span.speaker)) {
                        span.speaker
                    } else {
                        "narrator"
                    }
                    nonOverlappingSpans.add(
                        span.copy(
                            start = adjustedStart,
                            end = span.end,
                            speaker = resolvedSpeaker
                        )
                    )
                    currentPos = span.end
                }
            }

            // Fill gaps with narrator
            var cursor = 0
            for (span in nonOverlappingSpans) {
                if (span.start > cursor) {
                    normalizedLines.add(
                        NarrationLine(
                            id = UUID.randomUUID().toString(),
                            chapterId = chapterId,
                            paraIndex = paraIndex,
                            startOffset = cursor,
                            endOffset = span.start,
                            speakerId = "narrator",
                            style = null
                        )
                    )
                }
                normalizedLines.add(
                    NarrationLine(
                        id = UUID.randomUUID().toString(),
                        chapterId = chapterId,
                        paraIndex = paraIndex,
                        startOffset = span.start,
                        endOffset = span.end,
                        speakerId = span.speaker,
                        style = span.style
                    )
                )
                cursor = span.end
            }

            if (cursor < textLength) {
                normalizedLines.add(
                    NarrationLine(
                        id = UUID.randomUUID().toString(),
                        chapterId = chapterId,
                        paraIndex = paraIndex,
                        startOffset = cursor,
                        endOffset = textLength,
                        speakerId = "narrator",
                        style = null
                    )
                )
            }
        }

        return CastingResult(
            characters = finalCharacters,
            lines = normalizedLines
        )
    }

    private fun createFullNarratorFallback(
        paragraphTexts: List<String>,
        bookId: String,
        chapterId: String
    ): CastingResult {
        val lines = paragraphTexts.mapIndexedNotNull { index, text ->
            if (text.isEmpty()) null
            else NarrationLine(
                id = UUID.randomUUID().toString(),
                chapterId = chapterId,
                paraIndex = index,
                startOffset = 0,
                endOffset = text.length,
                speakerId = "narrator",
                style = null
            )
        }
        return CastingResult(characters = emptyList(), lines = lines)
    }
}
