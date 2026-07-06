package com.symmetricalpalmtree.biblesprout.data.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The global read-write index, `biblesprout.db`. Room over the framework SQLite
 * (plaintext — the index needs no FTS5 and holds no sensitive data, matching
 * Notesprout's non-encrypted path). The read-only content databases, by
 * contrast, go through raw SQLCipher for their bundled FTS5.
 */
@Database(
    entities = [
        AppSetting::class, Source::class, ReadingProgress::class,
        Bookmark::class, Highlight::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppIndexDatabase : RoomDatabase() {
    abstract fun settings(): SettingDao
    abstract fun sources(): SourceDao
    abstract fun progress(): ProgressDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun highlights(): HighlightDao

    companion object {
        // Each migration adds one annotation table and preserves existing rows
        // rather than wiping the index. The CREATE statements must match what Room
        // generates for the entity exactly (column order, types, index names).
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmark` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`verse_key` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_bookmark_verse_key` ON `bookmark` (`verse_key`)",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `highlight` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`verse_key` INTEGER NOT NULL, " +
                        "`start_word` INTEGER NOT NULL, " +
                        "`end_word` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_highlight_verse_key` ON `highlight` (`verse_key`)",
                )
            }
        }

        fun open(context: Context): AppIndexDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppIndexDatabase::class.java,
                "biblesprout.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}
