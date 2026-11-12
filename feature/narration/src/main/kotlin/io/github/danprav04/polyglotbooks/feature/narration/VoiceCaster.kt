package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.Character
import io.github.danprav04.polyglotbooks.core.model.NarrationLine
import io.github.danprav04.polyglotbooks.core.model.PcmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * Catalog of 30 prebuilt Gemini studio voices with gender and trait metadata.
 */
data class VoiceProfile(
    val id: String,
    val name: String,
    val gender: String, // "male", "female", "neutral"
    val age: String,   // "young", "adult", "mature"
    val traits: List<String>
)

object GeminiStudioVoices {
    val catalog = listOf(
        VoiceProfile("Zephyr", "Zephyr", "female", "adult", listOf("calm", "smooth", "warm")),
        VoiceProfile("Puck", "Puck", "male", "young", listOf("playful", "energetic", "bright")),
        VoiceProfile("Charon", "Charon", "male", "mature", listOf("deep", "resonant", "authoritative")),
        VoiceProfile("Kore", "Kore", "female", "adult", listOf("clear", "expressive", "balanced")),
        VoiceProfile("Fenrir", "Fenrir", "male", "adult", listOf("gritty", "strong", "intense")),
        VoiceProfile("Aoede", "Aoede", "female", "adult", listOf("melodic", "soothing", "graceful")),
        VoiceProfile("Leda", "Leda", "female", "mature", listOf("wise", "composed", "warm")),
        VoiceProfile("Orus", "Orus", "male", "adult", listOf("steady", "clear", "confident")),
        VoiceProfile("Perseus", "Perseus", "male", "adult", listOf("heroic", "bold", "dynamic")),
        VoiceProfile("Callirrhoe", "Callirrhoe", "female", "young", listOf("bright", "lively", "cheerful")),
        VoiceProfile("Autonoe", "Autonoe", "female", "adult", listOf("mysterious", "smooth", "cool")),
        VoiceProfile("Enceladus", "Enceladus", "male", "mature", listOf("deep", "commanding", "serious")),
        VoiceProfile("Iapetus", "Iapetus", "male", "adult", listOf("casual", "friendly", "natural")),
        VoiceProfile("Umbriel", "Umbriel", "male", "young", listOf("quirky", "fast", "light")),
        VoiceProfile("Algieba", "Algieba", "female", "adult", listOf("sharp", "articulate", "precise")),
        VoiceProfile("Despina", "Despina", "female", "young", listOf("soft", "gentle", "sweet")),
        VoiceProfile("Erinome", "Erinome", "female", "mature", listOf("rich", "narrative", "expressive")),
        VoiceProfile("Algenib", "Algenib", "male", "adult", listOf("resonant", "steady", "warm")),
        VoiceProfile("Rasalgethi", "Rasalgethi", "male", "mature", listOf("gravelly", "storyteller", "deep")),
        VoiceProfile("Laomedeia", "Laomedeia", "female", "young", listOf("bubbly", "energetic", "light")),
        VoiceProfile("Achernar", "Achernar", "male", "adult", listOf("crisp", "clean", "professional")),
        VoiceProfile("Alnilam", "Alnilam", "male", "adult", listOf("smooth", "deep", "calm")),
        VoiceProfile("Schedar", "Schedar", "male", "mature", listOf("wise", "measured", "deep")),
        VoiceProfile("Gacrux", "Gacrux", "male", "adult", listOf("bold", "resonant", "strong")),
        VoiceProfile("Pulcherrima", "Pulcherrima", "female", "adult", listOf("radiant", "clear", "warm")),
        VoiceProfile("Achird", "Achird", "male", "adult", listOf("friendly", "upbeat", "clear")),
        VoiceProfile("Zubenelgenubi", "Zubenelgenubi", "female", "mature", listOf("soothing", "deep", "calm")),
        VoiceProfile("Vindemiatrix", "Vindemiatrix", "female", "adult", listOf("elegant", "refined", "smooth")),
        VoiceProfile("Sadachbia", "Sadachbia", "male", "young", listOf("casual", "bright", "easy")),
        VoiceProfile("Sadaltager", "Sadaltager", "male", "mature", listOf("distinguished", "deep", "narrative"))
    )

    fun getVoice(id: String): VoiceProfile? = catalog.find { it.id.equals(id, ignoreCase = true) }
}

/**
 * VoiceCaster manages character-to-voice assignments, deterministic auto-casting,
 * voice reserves, custom voice designs, and user overrides.
 */
class VoiceCaster(
    private val engine: NarrationEngine = GeminiTtsEngine()
) {
    private val assignments = mutableMapOf<String, String>()
    private val customVoices = mutableMapOf<String, VoiceProfile>()
    private var narratorVoiceId: String = "Charon" // Reserved for narrator

    suspend fun autoAssignVoices(characters: List<Character>): Map<String, String> {
        val availableVoices = GeminiStudioVoices.catalog.toMutableList()
        // Remove narrator voice from pool
        availableVoices.removeAll { it.id.equals(narratorVoiceId, ignoreCase = true) }

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
                assignments[character.id] = "Zephyr" // fallback
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
        narratorVoiceId = voiceId
    }
}
