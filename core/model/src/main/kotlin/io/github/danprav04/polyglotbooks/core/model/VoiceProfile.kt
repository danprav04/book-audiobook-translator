package io.github.danprav04.polyglotbooks.core.model

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Voice profile metadata for a prebuilt Gemini studio voice.
 */
@Serializable
data class VoiceProfile(
    val id: String,
    val name: String,
    val gender: String, // "male", "female", "neutral"
    val age: String,    // "young", "adult", "mature"
    val traits: List<String>
)

/**
 * Shared catalog of all 30 prebuilt Gemini studio voices.
 */
object GeminiStudioVoices {
    const val DEFAULT_NARRATOR_VOICE_ID: String = "Charon"

    val catalog: List<VoiceProfile> = listOf(
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

    fun getVoice(id: String): VoiceProfile? {
        val needle = id.trim()
        if (needle.isEmpty()) return null
        return catalog.find { it.id.equals(needle, ignoreCase = true) }
    }

    fun previewAssetPath(voiceId: String): String {
        val normalized = getVoice(voiceId)?.id ?: voiceId.trim()
        return "voice_previews/${normalized.lowercase(Locale.ROOT)}.m4a"
    }
}
