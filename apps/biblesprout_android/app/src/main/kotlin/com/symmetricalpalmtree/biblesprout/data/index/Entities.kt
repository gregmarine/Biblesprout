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
 * The remaining annotation tables (note/cross_link) from the Flutter schema are
 * intentionally not created yet — they'll be added with the feature that writes
 * them, so their columns can be settled then.
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

/**
 * A saved place, anchored to a single canonical [verseKey] (the top verse of the
 * page it was made on). The reader reopens on the page containing that verse. The
 * unique index makes toggling a verse's bookmark idempotent. The reference label
 * and snippet shown in the list are derived from the key against the open Bible,
 * so nothing translation-specific is stored here.
 */
@Entity(tableName = "bookmark", indices = [Index("verse_key", unique = true)])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "verse_key") val verseKey: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A highlighted phrase within one verse: the run of words [startWord]..[endWord]
 * (inclusive, 0-based over the verse's words, verse number aside) of [verseKey].
 * A verse may hold several highlights, so the verse-key index is non-unique. The
 * reader underlines the run wherever the verse is drawn.
 */
@Entity(tableName = "highlight", indices = [Index("verse_key")])
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "verse_key") val verseKey: Int,
    @ColumnInfo(name = "start_word") val startWord: Int,
    @ColumnInfo(name = "end_word") val endWord: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * A handwritten notebook, anchored to a scripture span by canonical verse keys —
 * the reading context it was opened from (a chapter from the reader, a passage
 * from the passage view). Keyed by [startKey]/[endKey] like a commentary entry, so
 * the same span always reopens the same notebook. One notebook per distinct span
 * (the unique index). It owns ordered [NotePage]s.
 */
@Entity(tableName = "note_notebook", indices = [Index("start_key", "end_key", unique = true)])
data class NoteNotebook(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_key") val startKey: Int,
    @ColumnInfo(name = "end_key") val endKey: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** One page within a notebook, ordered by [pageIndex]. Holds [NoteStroke]s. */
@Entity(tableName = "note_page", indices = [Index("notebook_id")])
data class NotePage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "notebook_id") val notebookId: Long,
    @ColumnInfo(name = "page_index") val pageIndex: Int,
)

/**
 * One pen stroke on a page. [points] is a packed little-endian float BLOB of
 * `x, y` samples in canvas pixels (queried whole per page and drawn directly —
 * no per-point parsing). Whole-stroke erase deletes the row, so no sub-stroke
 * geometry is kept.
 */
@Entity(tableName = "note_stroke", indices = [Index("page_id")])
data class NoteStroke(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "page_id") val pageId: Long,
    val points: ByteArray,
    val width: Float,
    val color: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
