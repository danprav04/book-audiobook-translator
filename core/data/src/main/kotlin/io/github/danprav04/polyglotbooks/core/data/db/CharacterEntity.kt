package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.danprav04.polyglotbooks.core.model.Character

@Entity(
    tableName = "characters",
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
data class CharacterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val name: String,
    val aliases: String, // Comma or newline separated aliases
    val gender: String,
    val age: String,
    val voiceTraits: String,
    val assignedVoiceId: String?
) {
    fun toDomain(): Character = Character(
        id = id,
        bookId = bookId,
        name = name,
        aliases = if (aliases.isBlank()) emptyList() else aliases.split(",").map { it.trim() },
        gender = gender,
        age = age,
        voiceTraits = voiceTraits,
        assignedVoiceId = assignedVoiceId
    )

    companion object {
        fun fromDomain(character: Character): CharacterEntity = CharacterEntity(
            id = character.id,
            bookId = character.bookId,
            name = character.name,
            aliases = character.aliases.joinToString(","),
            gender = character.gender,
            age = character.age,
            voiceTraits = character.voiceTraits,
            assignedVoiceId = character.assignedVoiceId
        )
    }
}
