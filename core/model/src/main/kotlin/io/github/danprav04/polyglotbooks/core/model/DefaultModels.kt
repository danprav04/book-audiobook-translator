package io.github.danprav04.polyglotbooks.core.model

/**
 * Default model mapping matching the approved project plan.
 */
object DefaultModels {
    val map: Map<ModelRole, String> = mapOf(
        ModelRole.LIVE_TRANSLATE to "gemini-3.5-live-translate-preview",
        ModelRole.TTS to "gemini-3.8-flash-tts",
        ModelRole.LIVE_NARRATION to "gemini-3.8-live",
        ModelRole.TEXT_ANALYSIS to "gemini-3.8-flash",
        ModelRole.TEXT_TRANSLATION to "gemini-3.8-flash",
        ModelRole.LIVE_TRANSCRIBE to "gemini-3.5-transcribe-live"
    )

    fun get(role: ModelRole): String = map[role] ?: "gemini-3.8-flash"
}
