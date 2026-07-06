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
        NoteNotebook::class, NotePage::class, NoteStroke::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppIndexDatabase : RoomDatabase() {
    abstract fun settings(): SettingDao
    abstract fun sources(): SourceDao
    abstract fun progress(): ProgressDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun highlights(): HighlightDao
    abstract fun notes(): NoteDao

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

        // v4 adds the handwritten-notes tables (notebook → pages → strokes).
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_notebook` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`start_key` INTEGER NOT NULL, " +
                        "`end_key` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_note_notebook_start_key_end_key` ON `note_notebook` (`start_key`, `end_key`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_page` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`notebook_id` INTEGER NOT NULL, " +
                        "`page_index` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_note_page_notebook_id` ON `note_page` (`notebook_id`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_stroke` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`page_id` INTEGER NOT NULL, " +
                        "`points` BLOB NOT NULL, " +
                        "`width` REAL NOT NULL, " +
                        "`color` INTEGER NOT NULL, " +
                        "`created_at` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_note_stroke_page_id` ON `note_stroke` (`page_id`)",
                )
            }
        }

        fun open(context: Context): AppIndexDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppIndexDatabase::class.java,
                "biblesprout.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
