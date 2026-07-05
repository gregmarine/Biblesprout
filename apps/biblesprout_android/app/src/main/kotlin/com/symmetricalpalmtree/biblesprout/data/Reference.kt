package com.symmetricalpalmtree.biblesprout.data

/**
 * A parsed reference: an ordered set of [VerseRange]s that all belong to one
 * book. `John 3:14-16,18` becomes two ranges; `Genesis 1` becomes one whole-
 * chapter range. Ported from the Flutter `reference.dart`.
 */
data class Passage(
    val book: CanonBook,
    val ranges: List<VerseRange>,
    val rawText: String? = null,
) {
    val startKey: Int get() = ranges.first().startKey
    val endKey: Int get() = ranges.last().endKey

    /**
     * A tidy canonical rendering, e.g. `John 3:14–16, 18`. Chapter prefixes are
     * omitted once a chapter is already established, matching how these are
     * written by hand.
     */
    fun format(): String {
        val parts = ArrayList<String>()
        var shownChapter: Int? = null
        for (r in ranges) {
            val sc = VerseKey.chapterOf(r.startKey)
            val sv = VerseKey.verseOf(r.startKey)
            val ec = VerseKey.chapterOf(r.endKey)
            val ev = VerseKey.verseOf(r.endKey)

            if (sv == 0 && ev == VerseKey.MAX_VERSE) {
                parts.add(if (sc == ec) "$sc" else "$sc–$ec")
                shownChapter = null
                continue
            }
            if (sc == ec) {
                val prefix = if (shownChapter == sc) "" else "$sc:"
                parts.add(if (sv == ev) "$prefix$sv" else "$prefix$sv–$ev")
                shownChapter = sc
            } else {
                parts.add("$sc:$sv–$ec:$ev")
                shownChapter = ec
            }
        }
        return "${book.name} ${parts.joinToString(", ")}"
    }
}

/**
 * Turns free-typed references into [Passage]s. Tolerant of case, punctuation,
 * spacing, en-dashes and common book abbreviations (via [Canon]).
 */
object ReferenceParser {
    // Book part must end in a letter/period; the spec starts at the first digit,
    // so "1 Cor 13:4" and spaceless "Ps23" both work.
    private val split = Regex("^\\s*(.*?[A-Za-z.])\\s*([0-9].*)$")
    private val crossChapterSpan = Regex("^(\\d+):(\\d+)-(\\d+):(\\d+)$")
    private val hasLetter = Regex("[A-Za-z]")

    /** Parses a single reference, or null if the book/spec is unknown or malformed. */
    fun parse(input: String): Passage? {
        val m = split.find(input.trim()) ?: return null
        val book = Canon.lookup(m.groupValues[1]) ?: return null
        val spec = m.groupValues[2].replace("–", "-").replace(" ", "")
        val ranges = parseSpec(book.ordinal, spec) ?: return null
        if (ranges.isEmpty()) return null
        return Passage(book, ranges, input.trim())
    }

    /**
     * Parses one or more references separated by commas/semicolons, e.g.
     * `John 3:14-17, Acts 1:3` → two passages. A bare number continues the
     * previous book (`John 3:16, 18`). Returns an empty list when the input isn't
     * a valid reference list, so callers can fall back to full-text search.
     */
    fun parseAll(input: String): List<Passage> {
        val chunks = ArrayList<String>()
        val buffer = ArrayList<String>()
        fun flush() {
            if (buffer.isNotEmpty()) {
                chunks.add(buffer.joinToString(", "))
                buffer.clear()
            }
        }

        for (part in input.split(Regex("[;,]"))) {
            val seg = part.trim()
            if (seg.isEmpty()) continue
            if (hasLetter.containsMatchIn(seg)) {
                flush() // a book name begins a new reference
                buffer.add(seg)
            } else {
                if (buffer.isEmpty()) return emptyList() // a number can't lead
                buffer.add(seg)
            }
        }
        flush()
        if (chunks.isEmpty()) return emptyList()

        val passages = ArrayList<Passage>()
        for (chunk in chunks) {
            val passage = parse(chunk) ?: return emptyList() // any bad chunk ⇒ search
            passages.add(passage)
        }
        return passages
    }

    private fun parseSpec(ordinal: Int, spec: String): List<VerseRange>? {
        // No colon → chapter-level ("3", "1-2", "1,3,5").
        if (!spec.contains(':')) {
            val ranges = ArrayList<VerseRange>()
            for (seg in spec.split(',')) {
                if (seg.isEmpty()) continue
                val dash = seg.indexOf('-')
                if (dash < 0) {
                    val c = seg.toIntOrNull() ?: return null
                    ranges.add(VerseRange.chapters(ordinal, c, c))
                } else {
                    val a = seg.substring(0, dash).toIntOrNull()
                    val b = seg.substring(dash + 1).toIntOrNull()
                    if (a == null || b == null || b < a) return null
                    ranges.add(VerseRange.chapters(ordinal, a, b))
                }
            }
            return ranges.ifEmpty { null }
        }

        // Whole-spec cross-chapter verse span, e.g. "1:5-2:3".
        crossChapterSpan.find(spec)?.let { span ->
            val start = VerseKey.encode(ordinal, span.groupValues[1].toInt(), span.groupValues[2].toInt())
            val end = VerseKey.encode(ordinal, span.groupValues[3].toInt(), span.groupValues[4].toInt())
            if (end < start) return null
            return listOf(VerseRange(start, end))
        }

        // Verse-level, comma-separated, carrying the chapter forward: "3:14-16,18".
        val ranges = ArrayList<VerseRange>()
        var chapter: Int? = null
        for (seg in spec.split(',')) {
            if (seg.isEmpty()) continue
            var vspec = seg
            val colon = seg.indexOf(':')
            if (colon >= 0) {
                chapter = seg.substring(0, colon).toIntOrNull()
                vspec = seg.substring(colon + 1)
            }
            val ch = chapter ?: return null // a bare verse with no chapter context
            val dash = vspec.indexOf('-')
            if (dash < 0) {
                val v = vspec.toIntOrNull() ?: return null
                ranges.add(VerseRange.verse(ordinal, ch, v))
            } else {
                val a = vspec.substring(0, dash).toIntOrNull()
                val b = vspec.substring(dash + 1).toIntOrNull()
                if (a == null || b == null || b < a) return null
                ranges.add(VerseRange.verses(ordinal, ch, a, b))
            }
        }
        return ranges.ifEmpty { null }
    }
}
