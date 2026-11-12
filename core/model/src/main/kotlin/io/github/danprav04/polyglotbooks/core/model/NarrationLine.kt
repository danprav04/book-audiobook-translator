package io.github.danprav04.polyglotbooks.core.model

import kotlinx.serialization.Serializable

/**
 * Attributed narration line in an ebook chapter.
 */
@Serializable
data class NarrationLine(
    val id: String = "",
    val chapterId: String = "",
    val paraIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val speakerId: String,
    val style: String? = null,
    val cfi: String? = null
)
