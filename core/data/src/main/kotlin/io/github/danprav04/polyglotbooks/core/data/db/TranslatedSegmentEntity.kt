package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "translated_segments",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["bookId", "targetLang", "sourceStartMs"])
    ]
)
data class TranslatedSegmentEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val targetLang: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val aacFilePath: String,
    val fileSizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)
