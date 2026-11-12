package io.github.danprav04.polyglotbooks.core.model

import kotlinx.serialization.Serializable

/**
 * Character entity discovered during casting/analysis of an ebook.
 */
@Serializable
data class Character(
    val id: String,
    val bookId: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val gender: String = "unknown",
    val age: String = "unknown",
    val voiceTraits: String = "",
    val assignedVoiceId: String? = null
)
