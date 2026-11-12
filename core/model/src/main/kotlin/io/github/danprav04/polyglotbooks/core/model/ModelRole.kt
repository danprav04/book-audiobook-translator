package io.github.danprav04.polyglotbooks.core.model

import kotlinx.serialization.Serializable

/**
 * Roles that Gemini or other AI models play across the app.
 */
@Serializable
enum class ModelRole {
    LIVE_TRANSLATE,
    TTS,
    LIVE_NARRATION,
    TEXT_ANALYSIS,
    TEXT_TRANSLATION,
    LIVE_TRANSCRIBE
}
