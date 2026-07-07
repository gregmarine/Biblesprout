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

/** A superscript verse-number span within a [RenderBlock]'s [content]. */
data class VerseMark(val start: Int, val end: Int, val verseKey: Int, val number: Int)

/**
 * One display block from the rich layer: a paragraph, a poetry line, a section
 * heading or a stanza break, in document order. [content] is the display text
 * (verse-number digits inlined; [verses] locates them); [kind] is the USFM marker
 * (`p`, `pmo`, `q1`, `q2`, `b`, `s1`, `s2`, `d`, `r`, `li1`, …).
 */
data class RenderBlock(
    val id: Int,
    val kind: String,
    val startKey: Int?,
    val content: String,
    val verses: List<VerseMark>,
)

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

    /**
     * The chapter's display blocks (paragraphs, poetry lines, headings, stanza
     * breaks) in reading order, each carrying its verse-number spans — the rich
     * layer the formatted reader renders. `usfm` is the book code (e.g. "PSA").
     */
    fun blocksForChapter(usfm: String, chapter: Int): List<RenderBlock> {
        val markers = HashMap<Int, MutableList<VerseMark>>()
        db.rawQuery(
            """
            SELECT vm.block_id, vm.start, vm.end, vm.verse_key, vm.number
            FROM verse_marker vm JOIN block b ON b.id = vm.block_id
            WHERE b.usfm = ? AND b.chapter = ?
            """.trimIndent(),
            arrayOf(usfm, chapter.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                markers.getOrPut(c.getInt(0)) { mutableListOf() }
                    .add(VerseMark(c.getInt(1), c.getInt(2), c.getInt(3), c.getInt(4)))
            }
        }
        val out = ArrayList<RenderBlock>()
        db.rawQuery(
            "SELECT id, kind, start_key, content FROM block WHERE usfm = ? AND chapter = ? ORDER BY id",
            arrayOf(usfm, chapter.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getInt(0)
                out.add(
                    RenderBlock(
                        id = id,
                        kind = c.getString(1),
                        startKey = if (c.isNull(2)) null else c.getInt(2),
                        content = c.getString(3),
                        verses = markers[id]?.sortedBy { it.start } ?: emptyList(),
                    ),
                )
            }
        }
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
