package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.danprav04.polyglotbooks.core.model.Chapter

@Entity(
    tableName = "chapters",
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
data class ChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val cfiRange: String?,
    val textLength: Int
) {
    fun toDomain(): Chapter = Chapter(
        id = id,
        bookId = bookId,
        index = index,
        title = title,
        startMs = startMs,
        endMs = endMs,
        cfiRange = cfiRange,
        textLength = textLength
    )

    companion object {
        fun fromDomain(chapter: Chapter): ChapterEntity = ChapterEntity(
            id = chapter.id,
            bookId = chapter.bookId,
            index = chapter.index,
            title = chapter.title,
            startMs = chapter.startMs,
            endMs = chapter.endMs,
            cfiRange = chapter.cfiRange,
            textLength = chapter.textLength
        )
    }
}
