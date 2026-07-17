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
 * A footnote: its caller sits at [offset] chars into block [blockId]'s content;
 * [text] is the popup body, [label] the origin reference (e.g. "1:6").
 */
data class Footnote(
    val id: Int,
    val blockId: Int,
    val offset: Int,
    val verseKey: Int?,
    val label: String?,
    val text: String,
)

/**
 * A tappable cross-reference span. [sourceKind] is `"block"` (the span sits in a
 * `\r` parallel-passage heading's content) or `"note"` (it sits in a footnote's
 * body text); [sourceId] is the block id or footnote id accordingly. [start]/[end]
 * are char offsets into that source's text, and [targetStartKey]..[targetEndKey]
 * is the inclusive verse-key range it points at.
 */
data class Xref(
    val sourceKind: String,
    val sourceId: Int,
    val start: Int,
    val end: Int,
    val targetStartKey: Int,
    val targetEndKey: Int,
)

/**
 * An original-language word behind the translation (the `.bible` word layer, v3).
 * [start] until [end] are char offsets into block [blockId]'s content — the span of
 * *English* this word was rendered as, which may be several English words ("In the
 * beginning") or, for a word the BSB leaves untranslated, none at all (those rows
 * are not returned by [wordsForChapter], having no place in the text to press).
 *
 * [original] is the inflected form as it stands in the verse; [strongs] joins
 * [LexiconDatabase] for the dictionary entry behind it.
 */
data class BibleWord(
    val verseKey: Int,
    val blockId: Int,
    val start: Int,
    val end: Int,
    /** The English this word was rendered as — the text of its own span. */
    val english: String,
    val strongs: String?,
    val original: String?,
    val translit: String?,
    val language: String?,
    val morphText: String?,
)

/** A run of one verse's text inside a block: [text] sits at [start] in block [blockId]. */
data class VerseSpan(val blockId: Int, val start: Int, val text: String)

/**
 * One verse addressed by the display layer: its text as the [spans] it occupies
 * across blocks (a verse continued on the next poetry line has more than one).
 * Carrying the block address is what lets a view outside the chapter reader — the
 * passage view — reach the word layer, which is keyed by exactly that address.
 */
data class VerseSlice(val verseKey: Int, val spans: List<VerseSpan>) {
    /** The verse's display text, blocks rejoined with a space, as `verse.text` reads. */
    val text: String get() = spans.joinToString(" ") { it.text.trim() }.trim()
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

    /** The chapter's footnotes, each anchored to a block + caller offset. */
    fun footnotesForChapter(usfm: String, chapter: Int): List<Footnote> {
        val out = ArrayList<Footnote>()
        db.rawQuery(
            """
            SELECT f.id, f.block_id, f.offset, f.verse_key, f.label, f.text
            FROM footnote f JOIN block b ON b.id = f.block_id
            WHERE b.usfm = ? AND b.chapter = ?
            ORDER BY f.block_id, f.offset
            """.trimIndent(),
            arrayOf(usfm, chapter.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Footnote(
                        id = c.getInt(0),
                        blockId = c.getInt(1),
                        offset = c.getInt(2),
                        verseKey = if (c.isNull(3)) null else c.getInt(3),
                        label = if (c.isNull(4)) null else c.getString(4),
                        text = c.getString(5),
                    ),
                )
            }
        }
        return out
    }

    /**
     * The chapter's cross-references — both those in `\r` parallel-passage headings
     * (`source_kind='block'`) and those inside footnote bodies (`source_kind='note'`),
     * each carrying the char span and the verse key it points to.
     */
    fun xrefsForChapter(usfm: String, chapter: Int): List<Xref> {
        val out = ArrayList<Xref>()
        db.rawQuery(
            """
            SELECT x.source_kind, x.source_id, x.start, x.end, x.target_start_key, x.target_end_key
            FROM xref x JOIN block b ON b.id = x.source_id
            WHERE x.source_kind = 'block' AND b.usfm = ? AND b.chapter = ?
            UNION ALL
            SELECT x.source_kind, x.source_id, x.start, x.end, x.target_start_key, x.target_end_key
            FROM xref x
              JOIN footnote f ON f.id = x.source_id
              JOIN block b ON b.id = f.block_id
            WHERE x.source_kind = 'note' AND b.usfm = ? AND b.chapter = ?
            """.trimIndent(),
            arrayOf(usfm, chapter.toString(), usfm, chapter.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Xref(
                        sourceKind = c.getString(0),
                        sourceId = c.getInt(1),
                        start = c.getInt(2),
                        end = c.getInt(3),
                        targetStartKey = c.getInt(4),
                        targetEndKey = c.getInt(5),
                    ),
                )
            }
        }
        return out
    }

    /** Whether this source carries the word layer (schema v3+); older builds lack it. */
    private val hasWords: Boolean by lazy {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='word'",
            null,
        ).use { it.moveToNext() }
    }

    /**
     * The chapter's original-language words that are visible in the text, each
     * carrying the block span of the English it was rendered as — what a long-press
     * hit-tests against. Words with no English rendering are skipped (nothing to
     * press). Empty for sources without the word layer.
     */
    fun wordsForChapter(usfm: String, chapter: Int): List<BibleWord> {
        if (!hasWords) return emptyList()
        val out = ArrayList<BibleWord>()
        db.rawQuery(
            """
            SELECT w.verse_key, w.block_id, w.start, w.end,
                   substr(b.content, w.start + 1, w.end - w.start) AS english,
                   w.strongs, f.original, f.translit, f.language, m.text
            FROM word w
              JOIN block b ON b.id = w.block_id
              LEFT JOIN form f ON f.id = w.form_id
              LEFT JOIN morphology m ON m.id = w.morph_id
            WHERE b.usfm = ? AND b.chapter = ? AND w.block_id IS NOT NULL
            ORDER BY w.block_id, w.start
            """.trimIndent(),
            arrayOf(usfm, chapter.toString()),
        ).use { c -> while (c.moveToNext()) out.add(c.toWord()) }
        return out
    }

    private fun Cursor.toWord(): BibleWord = BibleWord(
        verseKey = getInt(0),
        blockId = getInt(1),
        start = getInt(2),
        end = getInt(3),
        english = getString(4),
        strongs = getStringOrNull(5),
        original = getStringOrNull(6),
        translit = getStringOrNull(7),
        language = getStringOrNull(8),
        morphText = getStringOrNull(9),
    )

    /**
     * The verses in an inclusive key range, each carrying the block spans its text
     * occupies — the display-layer equivalent of [versesInRange], for views that
     * need to reach the word layer (which is addressed by block + offset).
     *
     * Walks each touched chapter's blocks in document order, mirroring how the
     * builder assigns text to verses: a `\v` marker moves the current verse **in
     * every block kind**, but only body prose contributes text. That distinction
     * matters for psalms — the superscription is a `\d` block holding verse 1's
     * marker, and its text is deliberately absent from `verse.text`; skipping the
     * block wholesale would leave the following poetry line attributed to the last
     * verse of the previous psalm.
     */
    fun verseSlicesForRange(startKey: Int, endKey: Int): List<VerseSlice> {
        val chapters = ArrayList<Pair<String, Int>>()
        // Keys are app-controlled integers, so inlining them is injection-safe.
        db.rawQuery(
            "SELECT DISTINCT usfm, chapter FROM verse " +
                "WHERE verse_key BETWEEN $startKey AND $endKey ORDER BY verse_key",
            null,
        ).use { c -> while (c.moveToNext()) chapters.add(c.getString(0) to c.getInt(1)) }

        val spans = LinkedHashMap<Int, MutableList<VerseSpan>>()
        for ((usfm, chapter) in chapters) {
            var current: Int? = null
            for (block in blocksForChapter(usfm, chapter)) {
                val isBody = block.kind in BODY_KINDS
                val content = block.content
                var pos = 0

                fun take(end: Int) {
                    val key = current ?: return
                    if (!isBody || end <= pos) return
                    if (key !in startKey..endKey) return
                    val text = content.substring(pos, end)
                    if (text.isBlank()) return
                    spans.getOrPut(key) { ArrayList() }.add(VerseSpan(block.id, pos, text))
                }

                for (mark in block.verses) {
                    take(mark.start)
                    current = mark.verseKey
                    pos = mark.end
                }
                take(content.length)
            }
        }
        return spans.map { (key, list) -> VerseSlice(key, list) }.sortedBy { it.verseKey }
    }

    /** The word layer over an inclusive key range — see [wordsForChapter]. */
    fun wordsForRange(startKey: Int, endKey: Int): List<BibleWord> {
        if (!hasWords) return emptyList()
        val out = ArrayList<BibleWord>()
        db.rawQuery(
            """
            SELECT w.verse_key, w.block_id, w.start, w.end,
                   substr(b.content, w.start + 1, w.end - w.start) AS english,
                   w.strongs, f.original, f.translit, f.language, m.text
            FROM word w
              JOIN block b ON b.id = w.block_id
              LEFT JOIN form f ON f.id = w.form_id
              LEFT JOIN morphology m ON m.id = w.morph_id
            WHERE w.verse_key BETWEEN $startKey AND $endKey AND w.block_id IS NOT NULL
            ORDER BY w.block_id, w.start
            """.trimIndent(),
            null,
        ).use { c -> while (c.moveToNext()) out.add(c.toWord()) }
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

        /**
         * Block kinds whose text is scripture prose belonging to a verse — the same
         * set `build_bible_db.py` uses to decide what reaches `verse.text`. Headings,
         * psalm superscriptions (`d`) and stanza breaks (`b`) are excluded.
         */
        private val BODY_KINDS = setOf(
            "p", "pmo", "pc", "pi", "pm", "mi", "nb",
            "q1", "q2", "q3", "qr", "qc", "li1", "li2", "li3",
        )
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
