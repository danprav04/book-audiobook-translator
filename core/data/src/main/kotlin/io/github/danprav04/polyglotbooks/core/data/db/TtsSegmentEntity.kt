package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tts_segments",
    foreignKeys = [
        ForeignKey(
            entity = NarrationLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["lineId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["lineId"]),
        Index(value = ["lineId", "voiceId"])
    ]
)
data class TtsSegmentEntity(
    @PrimaryKey val id: String,
    val lineId: String,
    val voiceId: String,
    val aacFilePath: String,
    val fileSizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)
