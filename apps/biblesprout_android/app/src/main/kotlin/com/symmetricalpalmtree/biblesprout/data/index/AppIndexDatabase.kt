package com.symmetricalpalmtree.biblesprout.data.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The global read-write index, `biblesprout.db`. Room over the framework SQLite
 * (plaintext — the index needs no FTS5 and holds no sensitive data, matching
 * Notesprout's non-encrypted path). The read-only content databases, by
 * contrast, go through raw SQLCipher for their bundled FTS5.
 */
@Database(
    entities = [AppSetting::class, Source::class, ReadingProgress::class],
    version = 1,
    exportSchema = false,
)
abstract class AppIndexDatabase : RoomDatabase() {
    abstract fun settings(): SettingDao
    abstract fun sources(): SourceDao
    abstract fun progress(): ProgressDao

    companion object {
        fun open(context: Context): AppIndexDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppIndexDatabase::class.java,
                "biblesprout.db",
            ).build()
    }
}
