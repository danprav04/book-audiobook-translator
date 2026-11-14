package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.Character
import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import io.github.danprav04.polyglotbooks.core.model.GeminiStudioVoices as CoreGeminiStudioVoices
import io.github.danprav04.polyglotbooks.core.model.VoiceProfile as CoreVoiceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Shared alias preserving source compatibility for callers importing from `feature.narration`.
 * New callers should import [io.github.danprav04.polyglotbooks.core.model.VoiceProfile].
 */
typealias VoiceProfile = CoreVoiceProfile

/**
 * Forwarding wrapper preserving source compatibility for callers importing from `feature.narration`.
 * The single source of truth lives in [io.github.danprav04.polyglotbooks.core.model.GeminiStudioVoices].
 */
object GeminiStudioVoices {
    const val DEFAULT_NARRATOR_VOICE_ID: String = CoreGeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID

    val catalog: List<VoiceProfile>
        get() = CoreGeminiStudioVoices.catalog

    fun getVoice(id: String): VoiceProfile? = CoreGeminiStudioVoices.getVoice(id)

    fun previewAssetPath(voiceId: String): String = CoreGeminiStudioVoices.previewAssetPath(voiceId)
}

/**
 * VoiceCaster manages character-to-voice assignments, deterministic auto-casting,
 * voice reserves, custom voice designs, and user overrides.
 */
class VoiceCaster(
    private val engine: NarrationEngine = GeminiTtsEngine(),
    defaultNarratorVoiceId: String = CoreGeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID
) {
    private val assignments = mutableMapOf<String, String>()
    private val customVoices = mutableMapOf<String, VoiceProfile>()
    private var narratorVoiceId: String = resolveVoiceId(defaultNarratorVoiceId)

    suspend fun autoAssignVoices(characters: List<Character>): Map<String, String> {
        val availableVoices = GeminiStudioVoices.catalog.toMutableList()
        // Remove narrator voice from pool
        availableVoices.removeAll { it.id.equals(narratorVoiceId, ignoreCase = true) }
        val nonNarratorFallbackVoiceId = GeminiStudioVoices.catalog
            .firstOrNull { !it.id.equals(narratorVoiceId, ignoreCase = true) }
            ?.id ?: narratorVoiceId

        // Sort / match by gender and age traits if possible
        for (character in characters) {
            if (character.id == "narrator" || character.name.equals("narrator", true)) {
                assignments[character.id] = narratorVoiceId
                continue
            }

            // Find best matching voice profile
            val matched = availableVoices.firstOrNull { voice ->
                val genderMatch = character.gender.equals("unknown", true) ||
                        voice.gender.equals(character.gender, true)
                val ageMatch = character.age.equals("unknown", true) ||
                        voice.age.equals(character.age, true)
                genderMatch && ageMatch
            } ?: availableVoices.firstOrNull { voice ->
                character.gender.equals("unknown", true) || voice.gender.equals(character.gender, true)
            } ?: availableVoices.firstOrNull()

            if (matched != null) {
                assignments[character.id] = matched.id
                availableVoices.remove(matched)
            } else if (availableVoices.isNotEmpty()) {
                assignments[character.id] = availableVoices.removeAt(0).id
            } else {
                assignments[character.id] = nonNarratorFallbackVoiceId
            }
        }
        return assignments.toMap()
    }

    fun setVoice(characterId: String, voiceId: String) {
        assignments[characterId] = voiceId
    }

    fun getVoice(characterId: String): String? {
        if (characterId == "narrator") return narratorVoiceId
        return assignments[characterId]
    }

    fun reassignVoice(characterId: String, newVoiceId: String) {
        assignments[characterId] = newVoiceId
    }

    fun mergeCharacters(sourceCharacterId: String, targetCharacterId: String) {
        assignments.remove(sourceCharacterId)
        // Keep target assignment
    }

    fun assignCustomDesignedVoice(characterId: String, name: String, gender: String, age: String, traits: List<String>): VoiceProfile {
        val profile = VoiceProfile(id = "custom-${UUID.randomUUID()}", name = name, gender = gender, age = age, traits = traits)
        customVoices[characterId] = profile
        assignments[characterId] = profile.id
        return profile
    }

    fun getNarratorVoice(): String = narratorVoiceId
    fun setNarratorVoice(voiceId: String) {
        narratorVoiceId = resolveVoiceId(voiceId, fallback = narratorVoiceId)
    }

    private fun resolveVoiceId(
        voiceId: String,
        fallback: String = CoreGeminiStudioVoices.DEFAULT_NARRATOR_VOICE_ID
    ): String {
        val trimmed = voiceId.trim()
        if (trimmed.isEmpty()) return fallback
        return CoreGeminiStudioVoices.getVoice(trimmed)?.id ?: trimmed
    }
}
