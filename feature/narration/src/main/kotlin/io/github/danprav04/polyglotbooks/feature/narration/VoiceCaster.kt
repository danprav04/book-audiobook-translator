package io.github.danprav04.polyglotbooks.feature.narration

import io.github.danprav04.polyglotbooks.core.model.Character

/**
 * VoiceCaster manages character-to-voice assignments and casting preferences.
 */
class VoiceCaster(
    private val engine: NarrationEngine = GeminiTtsEngine()
) {
    private val assignments = mutableMapOf<String, String>()

    suspend fun autoAssignVoices(characters: List<Character>): Map<String, String> {
        val availableVoices = engine.voices()
        if (availableVoices.isEmpty()) return emptyMap()

        var voiceIndex = 0
        for (character in characters) {
            val assigned = availableVoices[voiceIndex % availableVoices.size].id
            assignments[character.id] = assigned
            voiceIndex++
        }
        return assignments.toMap()
    }

    fun setVoice(characterId: String, voiceId: String) {
        assignments[characterId] = voiceId
    }

    fun getVoice(characterId: String): String? = assignments[characterId]
}
