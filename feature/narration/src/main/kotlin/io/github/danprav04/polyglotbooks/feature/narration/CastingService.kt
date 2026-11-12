package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.Character
import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

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

data class CastingResult(
    val characters: List<Character>,
    val lines: List<NarrationLine>
)

/**
 * CastingService coordinates character analysis and span validation.
 */
class CastingService {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

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
        val raw = try {
            json.decodeFromString<RawCastingResponse>(jsonResponse)
        } catch (e: Exception) {
            RawCastingResponse()
        }

        val existingMap = existingCharacters.associateBy { it.name.lowercase() }
        val characters = raw.characters.map { rc ->
            val existing = existingMap[rc.name.lowercase()]
            Character(
                id = existing?.id ?: rc.id.ifEmpty { "char-${UUID.randomUUID()}" },
                bookId = bookId,
                name = rc.name,
                aliases = rc.aliases,
                gender = rc.gender,
                age = rc.age,
                voiceTraits = rc.voiceTraits,
                assignedVoiceId = existing?.assignedVoiceId
            )
        }

        val charIds = characters.map { it.id }.toSet() + "narrator"

        val validLines = mutableListOf<NarrationLine>()
        for (rl in raw.lines) {
            if (rl.para !in paragraphs.indices) continue
            val textLen = paragraphs[rl.para].length
            if (rl.start < 0 || rl.end > textLen || rl.start >= rl.end) continue

            // Ensure non-overlapping or validate span boundaries per paragraph
            val speaker = if (charIds.contains(rl.speaker)) rl.speaker else "narrator"

            validLines.add(
                NarrationLine(
                    id = "line-${UUID.randomUUID()}",
                    chapterId = chapterId,
                    paraIndex = rl.para,
                    startOffset = rl.start,
                    endOffset = rl.end,
                    speakerId = speaker,
                    style = rl.style,
                    cfi = "epubcfi(/6/4!/4/2/1:${rl.start})"
                )
            )
        }

        return CastingResult(characters, validLines)
    }
}
