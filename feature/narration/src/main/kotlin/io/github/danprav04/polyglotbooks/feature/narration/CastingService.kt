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
    val storySummary: String = "",
    val characters: List<RawCharacter> = emptyList(),
    val lines: List<RawLine> = emptyList()
)

@Serializable
data class RawCharacter(
    val id: String = "",
    val name: String = "",
    val aliases: List<String> = emptyList(),
    val gender: String = "unknown",
    val age: String = "unknown",
    val personality: String = "",
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
 * Normalized result of casting analysis including accumulated story progression and character personalities.
 */
data class CastingResult(
    val characters: List<Character>,
    val lines: List<NarrationLine>,
    val storySummary: String = "",
    val characterPersonalities: Map<String, String> = emptyMap()
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
            "storySummary": { "type": "string" },
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
                  "personality": { "type": "string" },
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

    /**
     * Builds a context-aware casting and directing prompt for a bounded slice of chapter paragraphs,
     * carrying forward running story progression, character personalities & aliases, and preceding prose
     * so every line receives tailored acting directions grounded in the broader narrative context.
     */
    fun buildCastingPrompt(
        paragraphs: List<String>,
        existingCharacters: List<Character> = emptyList(),
        previousContextParagraphs: List<String> = emptyList(),
        chapterTitle: String? = null,
        previousStorySummary: String? = null,
        knownPersonalities: Map<String, String> = emptyMap()
    ): String = buildString {
        append("Analyze the book passage below as both casting director and audiobook performance director. ")
        append("Track the evolving story progression, deepen understanding of each character's personality, ")
        append("reconcile speakers with the existing cast, and generate tailored line-by-line narration instructions.\n")
        if (!chapterTitle.isNullOrBlank()) {
            append("Chapter title: ").append(chapterTitle.trim()).append('\n')
        }
        if (!previousStorySummary.isNullOrBlank()) {
            append("Story progression summary so far:\n")
            append(previousStorySummary.trim()).append("\n\n")
        } else {
            append("Story progression summary so far: (Opening / no prior summary recorded yet — begin summarizing from these paragraphs.)\n\n")
        }
        append(
            """
            Attribution and performance-direction rules:
            1. Write a concise, insightful 'storySummary' (2-4 sentences) updating the story progression so far through the end of this passage: plot developments, rising stakes, scene tension, character dynamics, and shifting emotional atmosphere.
            2. Always reuse the exact character 'id' from Known Cast when a known character appears or speaks (including via nicknames, titles, honorifics, or aliases). Record newly discovered nicknames in 'aliases', update 'personality' with their temperament/mindset/motivations (e.g. "guarded, dryly cynical yet deeply observant"), and capture vocal timbre in 'voiceTraits' (e.g. "steady low baritone, unhurried").
            3. Detect speakers beyond explicit "Name: Sentence" script format: infer speakers from quotation marks ("...", “...”, ‘...’, «...», 「...」, 『...』), dialogue dashes (— / –), surrounding narrative beats ("Alice froze. \"Enough!\""), pronoun/action cues ("he whispered", "she countered"), vocatives, and back-and-forth conversational alternation across paragraphs.
            4. Direct EVERY part of every target paragraph in 'lines' — both spoken character dialogue AND "speaker": "narrator" prose — with a tailored, concrete 'style' instruction on how to voice that specific line (for example: "quiet dread beneath outward calm", "wry, weary amusement", "sharp panic breaking through composure", or "crisp, detached system announcement" for bracketed status messages like [...]). Ground each 'style' cue in the broader story progression, the character's personality, and the emotional subtext of the actual words rather than generic "neutral".
            5. Emit exact [start, end) character offsets within each target paragraph 0..${paragraphs.lastIndex}. Leave 'lines' only for target paragraphs, NOT for the read-only context paragraphs.
            """.trimIndent()
        )
        append("\n\n")

        val nonNarratorCast = existingCharacters.filterNot { isNarratorDescriptor(it.id, it.name) }
        val existingNarrator = existingCharacters.firstOrNull { isNarratorDescriptor(it.id, it.name) }
        append("Known Cast:\n")
        if (existingNarrator != null) {
            val narratorPersonality = resolvePersonalityLookup(existingNarrator, knownPersonalities)
            append("- id=\"narrator\" (name=\"Narrator\")")
            if (!narratorPersonality.isNullOrBlank()) {
                append(", personality=\"$narratorPersonality\"")
            }
            if (existingNarrator.voiceTraits.isNotBlank()) {
                append(", voiceTraits=\"${existingNarrator.voiceTraits}\"")
            }
            append('\n')
        }
        if (nonNarratorCast.isEmpty()) {
            append("(No characters detected earlier in the book yet.)\n\n")
        } else {
            nonNarratorCast.forEach { c ->
                append("- id=\"${c.id}\", name=\"${c.name}\"")
                if (c.aliases.isNotEmpty()) {
                    append(", aliases=${c.aliases.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }}")
                }
                if (c.gender.isNotBlank() && !c.gender.equals("unknown", ignoreCase = true)) {
                    append(", gender=\"${c.gender}\"")
                }
                if (c.age.isNotBlank() && !c.age.equals("unknown", ignoreCase = true)) {
                    append(", age=\"${c.age}\"")
                }
                val personality = resolvePersonalityLookup(c, knownPersonalities)
                if (!personality.isNullOrBlank()) {
                    append(", personality=\"$personality\"")
                }
                if (c.voiceTraits.isNotBlank()) {
                    append(", voiceTraits=\"${c.voiceTraits}\"")
                }
                append('\n')
            }
            append('\n')
        }

        if (previousContextParagraphs.isNotEmpty()) {
            append("Preceding context paragraphs (read-only continuity — DO NOT emit 'lines' for these):\n")
            previousContextParagraphs.forEachIndexed { i, text ->
                append("[ctx-${previousContextParagraphs.size - i}] ").append(text).append('\n')
            }
            append('\n')
        }

        append("Target paragraphs (emit characters and complete 'lines' with 0-based para indices 0..${paragraphs.lastIndex}):\n")
        paragraphs.forEachIndexed { idx, text ->
            append("[$idx] ").append(text).append('\n')
        }
    }

    fun parseAndValidateCasting(
        jsonResponse: String,
        paragraphs: List<String>,
        existingCharacters: List<Character> = emptyList(),
        bookId: String = "book-1",
        chapterId: String = "chap-1",
        previousStorySummary: String = "",
        knownPersonalities: Map<String, String> = emptyMap()
    ): CastingResult {
        return validateAndNormalizeInternal(
            rawJson = jsonResponse,
            paragraphTexts = paragraphs,
            existingCharacters = existingCharacters,
            bookId = bookId,
            chapterId = chapterId,
            previousStorySummary = previousStorySummary,
            knownPersonalities = knownPersonalities
        )
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
        return validateAndNormalizeInternal(
            rawJson = rawJson,
            paragraphTexts = paragraphTexts,
            existingCharacters = emptyList(),
            bookId = bookId,
            chapterId = chapterId,
            previousStorySummary = "",
            knownPersonalities = emptyMap()
        )
    }

    private fun validateAndNormalizeInternal(
        rawJson: String,
        paragraphTexts: List<String>,
        existingCharacters: List<Character>,
        bookId: String,
        chapterId: String,
        previousStorySummary: String,
        knownPersonalities: Map<String, String>
    ): CastingResult {
        val cleaned = stripCodeFences(rawJson)
        val parsed = tryDecodeResponse(cleaned)
            ?: return createFullNarratorFallback(paragraphTexts, bookId, chapterId, previousStorySummary, knownPersonalities)

        val existingById = existingCharacters.associateBy { it.id.trim().lowercase() }
        val existingNarrator = existingCharacters.firstOrNull { isNarratorDescriptor(it.id, it.name) }

        val rawIdToCanonicalId = mutableMapOf<String, String>()
        val termToCanonicalId = mutableMapOf<String, String>()
        val usedIds = mutableSetOf<String>()
        val mergedCharacters = linkedMapOf<String, Character>()
        val mergedPersonalities = linkedMapOf<String, String>()

        existingCharacters.forEach { existing ->
            val canonId = existing.id.trim()
            if (canonId.isNotEmpty()) {
                usedIds.add(canonId.lowercase())
                val priorPersonality = resolvePersonalityLookup(existing, knownPersonalities)
                if (isNarratorDescriptor(canonId, existing.name)) {
                    rawIdToCanonicalId[canonId.lowercase()] = "narrator"
                    if (!priorPersonality.isNullOrBlank()) {
                        mergedPersonalities["narrator"] = priorPersonality
                    }
                } else {
                    rawIdToCanonicalId[canonId.lowercase()] = canonId
                    if (!priorPersonality.isNullOrBlank()) {
                        mergedPersonalities[canonId] = priorPersonality
                    }
                    val normName = normalizeTerm(existing.name)
                    if (normName.isNotEmpty()) {
                        termToCanonicalId.putIfAbsent(normName, canonId)
                    }
                    existing.aliases.forEach { alias ->
                        val normAlias = normalizeTerm(alias)
                        if (normAlias.isNotEmpty()) {
                            termToCanonicalId.putIfAbsent(normAlias, canonId)
                        }
                    }
                }
            }
        }

        parsed.characters.forEachIndexed { idx, raw ->
            val rawIdTrimmed = raw.id.trim()
            val rawNameTrimmed = raw.name.trim()
            if (rawIdTrimmed.isEmpty() && rawNameTrimmed.isEmpty()) return@forEachIndexed
            val rawPersonalityTrimmed = raw.personality.trim()

            if (isNarratorDescriptor(rawIdTrimmed, rawNameTrimmed)) {
                val canonId = existingNarrator?.id ?: rawIdTrimmed.ifBlank { "narrator" }
                if (rawIdTrimmed.isNotEmpty()) {
                    rawIdToCanonicalId[rawIdTrimmed.lowercase()] = "narrator"
                }
                val combinedPersonality = mergePersonalityDescriptions(
                    mergedPersonalities["narrator"] ?: existingNarrator?.let { resolvePersonalityLookup(it, knownPersonalities) },
                    rawPersonalityTrimmed
                )
                if (combinedPersonality.isNotBlank()) {
                    mergedPersonalities["narrator"] = combinedPersonality
                }
                mergedCharacters[canonId] = Character(
                    id = canonId,
                    bookId = bookId,
                    name = existingNarrator?.name ?: rawNameTrimmed.ifBlank { "Narrator" },
                    aliases = mergeAliases(
                        baseName = existingNarrator?.name ?: "Narrator",
                        first = existingNarrator?.aliases.orEmpty(),
                        second = raw.aliases
                    ),
                    gender = pickNonUnknown(existingNarrator?.gender, raw.gender),
                    age = pickNonUnknown(existingNarrator?.age, raw.age),
                    voiceTraits = existingNarrator?.voiceTraits?.takeIf { it.isNotBlank() } ?: raw.voiceTraits.trim(),
                    assignedVoiceId = existingNarrator?.assignedVoiceId
                )
                return@forEachIndexed
            }

            val candidateTerms = (listOf(rawNameTrimmed) + raw.aliases)
                .map(::normalizeTerm)
                .filter { it.isNotEmpty() }
                .distinct()

            val matchedExisting = existingById[rawIdTrimmed.lowercase()]?.takeUnless { isNarratorDescriptor(it.id, it.name) }
                ?: candidateTerms.firstNotNullOfOrNull { term ->
                    termToCanonicalId[term]?.let { cid ->
                        mergedCharacters[cid] ?: existingCharacters.firstOrNull { it.id == cid }
                    }
                }

            val canonicalCharacter = if (matchedExisting != null) {
                val cid = matchedExisting.id
                val extraAlias = if (
                    rawNameTrimmed.isNotEmpty() &&
                    !rawNameTrimmed.equals(matchedExisting.name, ignoreCase = true)
                ) {
                    listOf(rawNameTrimmed)
                } else {
                    emptyList()
                }
                val updated = matchedExisting.copy(
                    bookId = bookId,
                    aliases = mergeAliases(
                        baseName = matchedExisting.name,
                        first = matchedExisting.aliases,
                        second = extraAlias + raw.aliases
                    ),
                    gender = pickNonUnknown(matchedExisting.gender, raw.gender),
                    age = pickNonUnknown(matchedExisting.age, raw.age),
                    voiceTraits = matchedExisting.voiceTraits.takeIf { it.isNotBlank() } ?: raw.voiceTraits.trim(),
                    assignedVoiceId = matchedExisting.assignedVoiceId
                )
                val combinedPersonality = mergePersonalityDescriptions(
                    mergedPersonalities[cid] ?: resolvePersonalityLookup(matchedExisting, knownPersonalities),
                    rawPersonalityTrimmed
                )
                if (combinedPersonality.isNotBlank()) {
                    mergedPersonalities[cid] = combinedPersonality
                }
                mergedCharacters[cid] = updated
                usedIds.add(cid.lowercase())
                cid to updated
            } else {
                val preferredBase = rawIdTrimmed.ifBlank {
                    slugifyId(rawNameTrimmed.ifBlank { "char_${idx + 1}" })
                }
                val cid = ensureUniqueId(preferredBase, usedIds)
                usedIds.add(cid.lowercase())
                val resolvedName = rawNameTrimmed.ifBlank { cid }
                val created = Character(
                    id = cid,
                    bookId = bookId,
                    name = resolvedName,
                    aliases = cleanAliases(raw.aliases, resolvedName),
                    gender = raw.gender.trim().ifBlank { "unknown" },
                    age = raw.age.trim().ifBlank { "unknown" },
                    voiceTraits = raw.voiceTraits.trim(),
                    assignedVoiceId = null
                )
                val combinedPersonality = mergePersonalityDescriptions(
                    knownPersonalities[cid] ?: knownPersonalities[resolvedName.lowercase()],
                    rawPersonalityTrimmed
                )
                if (combinedPersonality.isNotBlank()) {
                    mergedPersonalities[cid] = combinedPersonality
                }
                mergedCharacters[cid] = created
                cid to created
            }

            val (canonId, canonChar) = canonicalCharacter
            if (rawIdTrimmed.isNotEmpty()) {
                rawIdToCanonicalId[rawIdTrimmed.lowercase()] = canonId
            }
            rawIdToCanonicalId[canonId.lowercase()] = canonId
            val normCanonName = normalizeTerm(canonChar.name)
            if (normCanonName.isNotEmpty()) {
                termToCanonicalId[normCanonName] = canonId
            }
            canonChar.aliases.forEach { alias ->
                val normAlias = normalizeTerm(alias)
                if (normAlias.isNotEmpty()) {
                    termToCanonicalId.putIfAbsent(normAlias, canonId)
                }
            }
            candidateTerms.forEach { term ->
                termToCanonicalId.putIfAbsent(term, canonId)
            }
        }

        fun resolveSpeaker(rawSpeaker: String): String {
            val trimmed = rawSpeaker.trim()
            if (trimmed.isEmpty() || trimmed.equals("narrator", ignoreCase = true)) {
                return "narrator"
            }
            rawIdToCanonicalId[trimmed.lowercase()]?.let { return it }
            val norm = normalizeTerm(trimmed)
            termToCanonicalId[norm]?.let { mappedId ->
                if (!mergedCharacters.containsKey(mappedId)) {
                    existingCharacters.firstOrNull { it.id == mappedId }?.let { existing ->
                        mergedCharacters[mappedId] = existing.copy(bookId = bookId)
                    }
                }
                return mappedId
            }
            return "narrator"
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
                    val resolvedSpeaker = resolveSpeaker(span.speaker)
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
                        style = span.style?.trim()?.ifBlank { null }
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

        val resolvedStorySummary = parsed.storySummary.trim().ifBlank { previousStorySummary.trim() }
        return CastingResult(
            characters = mergedCharacters.values.toList(),
            lines = normalizedLines,
            storySummary = resolvedStorySummary,
            characterPersonalities = mergedPersonalities
        )
    }

    private fun tryDecodeResponse(cleanedJson: String): RawCastingResponse? {
        if (cleanedJson.isBlank()) return null
        try {
            return json.decodeFromString<RawCastingResponse>(cleanedJson)
        } catch (_: Exception) {
        }
        val firstBrace = cleanedJson.indexOf('{')
        val lastBrace = cleanedJson.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            val sliced = cleanedJson.substring(firstBrace, lastBrace + 1)
            try {
                return json.decodeFromString<RawCastingResponse>(sliced)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun stripCodeFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val lines = trimmed.lines()
        val dropFirst = if (lines.firstOrNull()?.trimStart()?.startsWith("```") == true) lines.drop(1) else lines
        val dropLast = if (dropFirst.lastOrNull()?.trim()?.startsWith("```") == true) dropFirst.dropLast(1) else dropFirst
        return dropLast.joinToString("\n").trim()
    }

    private fun isNarratorDescriptor(id: String, name: String): Boolean {
        val cleanId = id.trim()
        val cleanName = name.trim()
        return cleanId.equals("narrator", ignoreCase = true) ||
            cleanId.endsWith("_narrator", ignoreCase = true) ||
            cleanName.equals("Narrator", ignoreCase = true)
    }

    private fun normalizeTerm(raw: String): String =
        raw.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun slugifyId(name: String): String {
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return slug.ifBlank { "char" }
    }

    private fun ensureUniqueId(base: String, usedLowerIds: Set<String>): String {
        if (base.lowercase() !in usedLowerIds) return base
        var suffix = 2
        while ("${base}_$suffix".lowercase() in usedLowerIds) {
            suffix++
        }
        return "${base}_$suffix"
    }

    private fun cleanAliases(aliases: List<String>, canonicalName: String): List<String> =
        aliases
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(canonicalName, ignoreCase = true) }
            .distinctBy { it.lowercase() }

    private fun mergeAliases(
        baseName: String,
        first: List<String>,
        second: List<String>
    ): List<String> = cleanAliases(first + second, baseName)

    private fun pickNonUnknown(primary: String?, secondary: String?): String {
        val first = primary?.trim().orEmpty()
        if (first.isNotEmpty() && !first.equals("unknown", ignoreCase = true)) return first
        val second = secondary?.trim().orEmpty()
        if (second.isNotEmpty() && !second.equals("unknown", ignoreCase = true)) return second
        return first.ifEmpty { second.ifEmpty { "unknown" } }
    }

    private fun resolvePersonalityLookup(
        character: Character,
        personalities: Map<String, String>
    ): String? {
        if (personalities.isEmpty()) return null
        personalities[character.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        personalities[character.id.lowercase()]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        personalities[character.name.trim()]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        personalities[character.name.trim().lowercase()]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    private fun mergePersonalityDescriptions(existing: String?, incoming: String?): String {
        val prev = existing?.trim().orEmpty()
        val next = incoming?.trim().orEmpty()
        if (prev.isEmpty()) return next
        if (next.isEmpty()) return prev
        if (prev.equals(next, ignoreCase = true) || prev.contains(next, ignoreCase = true)) return prev
        if (next.contains(prev, ignoreCase = true)) return next
        // Keep the cumulative personality insight concise (under 280 chars) so prompts stay focused.
        val combined = "$prev; $next"
        return if (combined.length <= 280) combined else next
    }

    private fun createFullNarratorFallback(
        paragraphTexts: List<String>,
        bookId: String,
        chapterId: String,
        previousStorySummary: String = "",
        knownPersonalities: Map<String, String> = emptyMap()
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
        return CastingResult(
            characters = emptyList(),
            lines = lines,
            storySummary = previousStorySummary.trim(),
            characterPersonalities = knownPersonalities
        )
    }

    companion object {
        const val CASTING_SYSTEM_INSTRUCTION: String =
            "You are a master audiobook casting and performance director. " +
                "Reuse known character IDs, collect an evolving understanding of each character's personality and vocal traits, " +
                "maintain an updated summary of the story progression, detect implied speakers beyond explicit 'Name: Sentence' lines, " +
                "direct every line of dialogue and narration with tailored delivery instructions rooted in the broader context, " +
                "and output strictly valid JSON matching the schema."
    }
}
