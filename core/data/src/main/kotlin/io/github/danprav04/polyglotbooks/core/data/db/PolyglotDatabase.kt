package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        CharacterEntity::class,
        NarrationLineEntity::class,
        TranslatedSegmentEntity::class,
        TtsSegmentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PolyglotDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun characterDao(): CharacterDao
    abstract fun narrationDao(): NarrationDao
    abstract fun translationCacheDao(): TranslationCacheDao
}
