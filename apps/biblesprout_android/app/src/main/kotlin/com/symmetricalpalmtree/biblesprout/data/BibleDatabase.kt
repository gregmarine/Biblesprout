package com.symmetricalpalmtree.biblesprout.data

import android.database.Cursor
import com.symmetricalpalmtree.biblesprout.model.Bible
import com.symmetricalpalmtree.biblesprout.model.Book
import com.symmetricalpalmtree.biblesprout.model.Chapter
import com.symmetricalpalmtree.biblesprout.model.Verse
import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * A single verse from a search or range query, carrying its canonical address so
 * callers can navigate to or cross-link it.
 */
data class VerseHit(
    val verseKey: Int,
    val usfm: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
) {
    /** Human label, e.g. "John 3:16". */
    val reference: String get() = "${Canon.byUsfm(usfm).name} $chapter:$verse"
}

/**
 * Read-only accessor for a Bible source database (`*.bible`). Opens the prebuilt
 * file plaintext through SQLCipher (its bundled SQLite has the FTS5 the reader's
 * search needs), rebuilds the in-memory [Bible] the reader consumes, and exposes
 * full-text search and verse-key range lookups. Ported from `bible_database.dart`.
 *
 * All methods are blocking; call them off the main thread (Dispatchers.IO).
 */
class BibleDatabase private constructor(
    private val db: SQLiteDatabase,
    /** The source's `metadata` table as a plain map (id, title, versification…). */
    val metadata: Map<String, String>,
) {
    val id: String get() = metadata["id"] ?: "unknown"
    val title: String get() = metadata["title"] ?: id

    fun close() = db.close()

    /**
     * Rebuilds the whole [Bible] object graph in canonical order — the shape the
     * reader, library and paginator expect.
     */
    fun loadBible(): Bible {
        // Verses are key-ordered, so chapters group in a single pass.
        val chaptersByBook = HashMap<String, MutableList<Chapter>>()
        var curUsfm: String? = null
        var curChapter = -1
        var curVerses = mutableListOf<Verse>()

        fun flush() {
            val u = curUsfm ?: return
            chaptersByBook.getOrPut(u) { mutableListOf() }.add(Chapter(curChapter, curVerses))
            curVerses = mutableListOf()
        }

        db.rawQuery(
            "SELECT usfm, chapter, verse, text FROM verse ORDER BY verse_key",
            null,
        ).use { c ->
            val iu = c.getColumnIndexOrThrow("usfm")
            val ich = c.getColumnIndexOrThrow("chapter")
            val iv = c.getColumnIndexOrThrow("verse")
            val it = c.getColumnIndexOrThrow("text")
            while (c.moveToNext()) {
                val usfm = c.getString(iu)
                val chapter = c.getInt(ich)
                if (usfm != curUsfm || chapter != curChapter) {
                    flush()
                    curUsfm = usfm
                    curChapter = chapter
                }
                curVerses.add(Verse(c.getInt(iv), c.getString(it)))
            }
        }
        flush()

        val books = ArrayList<Book>()
        db.rawQuery(
            "SELECT usfm, name, testament FROM book ORDER BY ordinal",
            null,
        ).use { c ->
            val iu = c.getColumnIndexOrThrow("usfm")
            val inm = c.getColumnIndexOrThrow("name")
            val it = c.getColumnIndexOrThrow("testament")
            var index = 0
            while (c.moveToNext()) {
                val usfm = c.getString(iu)
                books.add(
                    Book(
                        index = index++,
                        name = c.getString(inm),
                        testament = if (c.getString(it) == "OT") Testament.OLD else Testament.NEW,
                        chapters = chaptersByBook[usfm] ?: emptyList(),
                    ),
                )
            }
        }
        return Bible(books)
    }

    /** Full-text search over verse text (FTS5), best matches first. */
    fun search(query: String, limit: Int = 100): List<VerseHit> {
        val match = Fts.matchExpression(query) ?: return emptyList()
        val out = ArrayList<VerseHit>()
        db.rawQuery(
            """
            SELECT v.verse_key, v.usfm, v.chapter, v.verse, v.text
            FROM verse_fts f
            JOIN verse v ON v.verse_key = f.rowid
            WHERE verse_fts MATCH ?
            ORDER BY rank
            LIMIT $limit
            """.trimIndent(),
            arrayOf(match),
        ).use { c -> while (c.moveToNext()) out.add(c.toHit()) }
        return out
    }

    /** All verses inside an inclusive key range, in reading order. */
    fun versesInRange(startKey: Int, endKey: Int): List<VerseHit> {
        val out = ArrayList<VerseHit>()
        // Keys are app-controlled integers, so inlining them is injection-safe.
        db.rawQuery(
            """
            SELECT verse_key, usfm, chapter, verse, text
            FROM verse
            WHERE verse_key BETWEEN $startKey AND $endKey
            ORDER BY verse_key
            """.trimIndent(),
            null,
        ).use { c -> while (c.moveToNext()) out.add(c.toHit()) }
        return out
    }

    /** Resolves an ordered set of ranges to their verses, in reading order. */
    fun versesForRanges(ranges: List<VerseRange>): List<VerseHit> =
        ranges.flatMap { versesInRange(it.startKey, it.endKey) }

    private fun Cursor.toHit(): VerseHit = VerseHit(
        verseKey = getInt(getColumnIndexOrThrow("verse_key")),
        usfm = getString(getColumnIndexOrThrow("usfm")),
        chapter = getInt(getColumnIndexOrThrow("chapter")),
        verse = getInt(getColumnIndexOrThrow("verse")),
        text = getString(getColumnIndexOrThrow("text")),
    )

    companion object {
        /** Opens an existing `.bible` file plaintext (empty SQLCipher password). */
        fun openFile(path: String): BibleDatabase {
            val db = SQLiteDatabase.openOrCreateDatabase(path, "", null, null)
            return BibleDatabase(db, readMetadata(db))
        }
    }
}

/** Reads a source database's `metadata` key/value table into a map. */
internal fun readMetadata(db: SQLiteDatabase): Map<String, String> {
    val meta = LinkedHashMap<String, String>()
    db.rawQuery("SELECT key, value FROM metadata", null).use { c ->
        while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
    }
    return meta
}
