package io.github.danprav04.polyglotbooks.core.model

import kotlinx.serialization.Serializable

/**
 * Detailed technical and playback metadata for an audiobook.
 */
@Serializable
data class Audiobook(
    val bookId: String,
    val durationMs: Long,
    val sampleRate: Int = 44100,
    val channels: Int = 2,
    val bitrate: Int = 0,
    val codec: String = ""
)
