package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.danprav04.polyglotbooks.core.model.Bookmark

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId"])]
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val positionMs: Long,
    val cfi: String?,
    val createdAt: Long,
    val note: String?
) {
    fun toDomain(): Bookmark = Bookmark(
        id = id,
        bookId = bookId,
        positionMs = positionMs,
        cfi = cfi,
        createdAt = createdAt,
        note = note
    )

    companion object {
        fun fromDomain(bookmark: Bookmark): BookmarkEntity = BookmarkEntity(
            id = bookmark.id,
            bookId = bookmark.bookId,
            positionMs = bookmark.positionMs,
            cfi = bookmark.cfi,
            createdAt = bookmark.createdAt,
            note = bookmark.note
        )
    }
}
