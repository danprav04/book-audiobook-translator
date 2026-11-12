package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.danprav04.polyglotbooks.core.model.NarrationLine

@Entity(
    tableName = "narration_lines",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["chapterId"])]
)
data class NarrationLineEntity(
    @PrimaryKey val id: String,
    val chapterId: String,
    val paraIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val speakerId: String,
    val style: String?,
    val cfi: String?
) {
    fun toDomain(): NarrationLine = NarrationLine(
        id = id,
        chapterId = chapterId,
        paraIndex = paraIndex,
        startOffset = startOffset,
        endOffset = endOffset,
        speakerId = speakerId,
        style = style,
        cfi = cfi
    )

    companion object {
        fun fromDomain(line: NarrationLine): NarrationLineEntity = NarrationLineEntity(
            id = line.id,
            chapterId = line.chapterId,
            paraIndex = line.paraIndex,
            startOffset = line.startOffset,
            endOffset = line.endOffset,
            speakerId = line.speakerId,
            style = line.style,
            cfi = line.cfi
        )
    }
}
