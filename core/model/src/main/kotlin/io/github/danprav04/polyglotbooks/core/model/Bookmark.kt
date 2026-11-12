package io.github.danprav04.polyglotbooks.core.model

import kotlinx.serialization.Serializable

/**
 * User bookmark or progress checkpoint.
 */
@Serializable
data class Bookmark(
    val id: String,
    val bookId: String,
    val positionMs: Long = 0L,
    val cfi: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val note: String? = null
)
