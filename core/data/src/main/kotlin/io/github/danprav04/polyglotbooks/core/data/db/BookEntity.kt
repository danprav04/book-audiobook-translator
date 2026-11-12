package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.danprav04.polyglotbooks.core.model.Book

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val format: String,
    val filePath: String,
    val coverUri: String?,
    val addedDate: Long,
    val lastReadDate: Long,
    val isAudiobook: Boolean
) {
    fun toDomain(): Book = Book(
        id = id,
        title = title,
        author = author,
        format = format,
        filePath = filePath,
        coverUri = coverUri,
        addedDate = addedDate,
        lastReadDate = lastReadDate,
        isAudiobook = isAudiobook
    )

    companion object {
        fun fromDomain(book: Book): BookEntity = BookEntity(
            id = book.id,
            title = book.title,
            author = book.author,
            format = book.format,
            filePath = book.filePath,
            coverUri = book.coverUri,
            addedDate = book.addedDate,
            lastReadDate = book.lastReadDate,
            isAudiobook = book.isAudiobook
        )
    }
}
