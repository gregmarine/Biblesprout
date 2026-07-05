package com.symmetricalpalmtree.biblesprout.data

import android.database.Cursor
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * One commentary comment covering an inclusive verse-key range. A verse-range
 * comment carries the commentator's own [heading] (e.g. "Verses 1–8"); a
 * whole-chapter comment has none.
 */
data class CommentaryEntry(
    val id: Int,
    val usfm: String,
    val chapter: Int,
    val startVerse: Int,
    val startKey: Int,
    val endKey: Int,
    val heading: String?,
    val body: String,
) {
    val bookName: String get() = Canon.byUsfm(usfm).name

    /** A display label, e.g. "John 3 — Verses 1–8" or "Isaiah 53". */
    val reference: String
        get() = if (heading == null) "$bookName $chapter" else "$bookName $chapter — $heading"
}

/**
 * Read-only accessor for a commentary source database (`*.commentary`). Comments
 * are addressed by the same canonical verse keys the Bible uses, so
 * [entriesForVerse] answers "what does this commentary say about verse K" with a
 * range-containment test, and [entriesForRange] overlaps a whole passage. Ported
 * from `commentary_database.dart`.
 *
 * All methods are blocking; call them off the main thread (Dispatchers.IO).
 */
class CommentaryDatabase private constructor(
    private val db: SQLiteDatabase,
    val metadata: Map<String, String>,
) {
    val id: String get() = metadata["id"] ?: "unknown"
    val title: String get() = metadata["title"] ?: id

    fun close() = db.close()

    /** The comment(s) covering a single verse (usually one). */
    fun entriesForVerse(verseKey: Int): List<CommentaryEntry> =
        query("start_key <= $verseKey AND end_key >= $verseKey")

    /** All comments overlapping an inclusive verse-key range, in reading order. */
    fun entriesForRange(startKey: Int, endKey: Int): List<CommentaryEntry> =
        query("start_key <= $endKey AND end_key >= $startKey")

    private fun query(where: String): List<CommentaryEntry> {
        val out = ArrayList<CommentaryEntry>()
        db.rawQuery(
            "SELECT * FROM entry WHERE $where ORDER BY start_key",
            null,
        ).use { c -> while (c.moveToNext()) out.add(c.toEntry()) }
        return out
    }

    /** Full-text search over commentary body text (FTS5), best matches first. */
    fun search(query: String, limit: Int = 100): List<CommentaryEntry> {
        val match = Fts.matchExpression(query) ?: return emptyList()
        val out = ArrayList<CommentaryEntry>()
        db.rawQuery(
            """
            SELECT e.* FROM entry_fts f
            JOIN entry e ON e.id = f.rowid
            WHERE entry_fts MATCH ?
            ORDER BY rank
            LIMIT $limit
            """.trimIndent(),
            arrayOf(match),
        ).use { c -> while (c.moveToNext()) out.add(c.toEntry()) }
        return out
    }

    private fun Cursor.toEntry(): CommentaryEntry {
        val ih = getColumnIndexOrThrow("heading")
        return CommentaryEntry(
            id = getInt(getColumnIndexOrThrow("id")),
            usfm = getString(getColumnIndexOrThrow("usfm")),
            chapter = getInt(getColumnIndexOrThrow("chapter")),
            startVerse = getInt(getColumnIndexOrThrow("start_verse")),
            startKey = getInt(getColumnIndexOrThrow("start_key")),
            endKey = getInt(getColumnIndexOrThrow("end_key")),
            heading = if (isNull(ih)) null else getString(ih),
            body = getString(getColumnIndexOrThrow("body")),
        )
    }

    companion object {
        /** Opens an existing `.commentary` file plaintext (empty SQLCipher password). */
        fun openFile(path: String): CommentaryDatabase {
            val db = SQLiteDatabase.openOrCreateDatabase(path, "", null, null)
            return CommentaryDatabase(db, readMetadata(db))
        }
    }
}
