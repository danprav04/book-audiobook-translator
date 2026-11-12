package io.github.danprav04.polyglotbooks.core.model

import kotlinx.serialization.Serializable

/**
 * Chapter metadata for navigation across ebooks and audiobooks.
 */
@Serializable
data class Chapter(
    val id: String,
    val bookId: String,
    val index: Int,
    val title: String,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val cfiRange: String? = null,
    val textLength: Int = 0
)
