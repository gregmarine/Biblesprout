package com.symmetricalpalmtree.biblesprout.data.index

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SettingDao {
    @Query("SELECT value FROM app_setting WHERE key = :key LIMIT 1")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSetting)
}

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun register(source: Source)

    @Query("SELECT * FROM source ORDER BY installed_at")
    suspend fun installed(): List<Source>
}

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(progress: ReadingProgress)

    @Query("SELECT * FROM reading_progress WHERE source_id = :sourceId LIMIT 1")
    suspend fun forSource(sourceId: String): ReadingProgress?

    /** The most recently read position across all sources ("continue reading"). */
    @Query("SELECT * FROM reading_progress ORDER BY updated_at DESC LIMIT 1")
    suspend fun latest(): ReadingProgress?
}

@Dao
interface BookmarkDao {
    /** All bookmarks in canonical reading order (verse keys sort that way). */
    @Query("SELECT * FROM bookmark ORDER BY verse_key")
    suspend fun all(): List<Bookmark>

    /** Just the bookmarked verse keys, for the reader's toggle state. */
    @Query("SELECT verse_key FROM bookmark")
    suspend fun keys(): List<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(bookmark: Bookmark)

    @Query("DELETE FROM bookmark WHERE verse_key = :verseKey")
    suspend fun removeByKey(verseKey: Int)

    @Delete
    suspend fun remove(bookmark: Bookmark)
}

@Dao
interface HighlightDao {
    /** Highlights whose verse falls in an inclusive key range (e.g. one chapter). */
    @Query("SELECT * FROM highlight WHERE verse_key BETWEEN :lo AND :hi ORDER BY verse_key, start_word")
    suspend fun inRange(lo: Int, hi: Int): List<Highlight>

    @Insert
    suspend fun add(highlight: Highlight): Long

    @Query("DELETE FROM highlight WHERE id = :id")
    suspend fun removeById(id: Long)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM note_notebook WHERE start_key = :startKey AND end_key = :endKey LIMIT 1")
    suspend fun findNotebook(startKey: Int, endKey: Int): NoteNotebook?

    @Insert
    suspend fun insertNotebook(notebook: NoteNotebook): Long

    @Query("UPDATE note_notebook SET updated_at = :time WHERE id = :id")
    suspend fun touchNotebook(id: Long, time: Long)

    @Query("SELECT * FROM note_page WHERE notebook_id = :notebookId ORDER BY page_index")
    suspend fun pages(notebookId: Long): List<NotePage>

    @Insert
    suspend fun insertPage(page: NotePage): Long

    @Query("SELECT * FROM note_stroke WHERE page_id = :pageId ORDER BY id")
    suspend fun strokes(pageId: Long): List<NoteStroke>

    @Insert
    suspend fun insertStroke(stroke: NoteStroke): Long

    @Query("DELETE FROM note_stroke WHERE id IN (:ids)")
    suspend fun deleteStrokes(ids: List<Long>)

    /** Clears a page's ink (keeps the page). */
    @Query("DELETE FROM note_stroke WHERE page_id = :pageId")
    suspend fun clearPage(pageId: Long)

    /** Removes a page row; call [clearPage] first to drop its strokes. */
    @Query("DELETE FROM note_page WHERE id = :pageId")
    suspend fun deletePage(pageId: Long)
}
