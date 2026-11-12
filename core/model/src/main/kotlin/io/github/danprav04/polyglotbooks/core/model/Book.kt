package io.github.danprav04.polyglotbooks.core.model

import kotlinx.serialization.Serializable

/**
 * Metadata representation of a book (ebook or audiobook) in the library.
 */
@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String,
    val format: String,
    val filePath: String,
    val coverUri: String? = null,
    val addedDate: Long = System.currentTimeMillis(),
    val lastReadDate: Long = System.currentTimeMillis(),
    val isAudiobook: Boolean = false
)
