package io.github.danprav04.polyglotbooks.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        CharacterEntity::class,
        NarrationLineEntity::class,
        TranslatedSegmentEntity::class,
        TtsSegmentEntity::class,
        PlaybackStateEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class PolyglotDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun characterDao(): CharacterDao
    abstract fun narrationDao(): NarrationDao
    abstract fun translationCacheDao(): TranslationCacheDao
    abstract fun playbackStateDao(): PlaybackStateDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playback_state` (`bookId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `playbackSpeed` REAL NOT NULL, `isLiveTranslationActive` INTEGER NOT NULL, `isDuckingEnabled` INTEGER NOT NULL, `targetLanguageCode` TEXT, `lastCfi` TEXT, `readingProgress` REAL NOT NULL, `lastPauseTimestamp` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`))"
                )
            }
        }
    }
}
