package com.symmetricalpalmtree.biblesprout.data.index

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entities of the global read-write index (`biblesprout.db`): the registry of
 * installed sources, an app-wide key/value store, and the reader's saved place.
 * User data addresses scripture by the same canonical verse keys the source
 * databases use. Ported from the Flutter `app_database.dart` schema.
 *
 * The annotation tables (bookmark/highlight/note/cross_link) from the Flutter
 * schema are intentionally not created yet — they'll be added with the feature
 * that writes them, so their columns can be settled then.
 */

/** App-wide preferences, e.g. the last-used commentary. Values are strings. */
@Entity(tableName = "app_setting")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String,
)

/** A source registered in the index (a Bible, a commentary, …). */
@Entity(tableName = "source")
data class Source(
    @PrimaryKey val id: String,
    val type: String,
    val title: String,
    val abbreviation: String,
    val language: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    val versification: String,
    @ColumnInfo(name = "is_readonly") val isReadonly: Boolean = true,
    @ColumnInfo(name = "installed_at") val installedAt: Long,
)

/**
 * One saved reading position per source; "continue reading" is the row with the
 * largest [updatedAt]. The book is stored as its canonical USFM code so it is
 * stable across translations.
 */
@Entity(tableName = "reading_progress", indices = [Index("updated_at")])
data class ReadingProgress(
    @PrimaryKey @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "book_usfm") val bookUsfm: String,
    val chapter: Int,
    val page: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
