package com.symmetricalpalmtree.biblesprout.reader

import com.symmetricalpalmtree.biblesprout.data.Footnote
import com.symmetricalpalmtree.biblesprout.data.RenderBlock
import com.symmetricalpalmtree.biblesprout.data.VerseKey
import com.symmetricalpalmtree.biblesprout.data.Xref
import com.symmetricalpalmtree.biblesprout.model.Chapter

/**
 * Splits a chapter's text into screen-sized pages by binary-searching, via
 * [ReaderTypography], the most atoms that fit each page's height. Ported from
 * the Flutter `paginator.dart`.
 */
object ChapterPaginator {

    /** Flattens a chapter into its atom stream (verse number, then its words). */
    fun atomsFor(chapter: Chapter, ordinal: Int): List<Atom> {
        val atoms = ArrayList<Atom>()
        for (verse in chapter.verses) {
            atoms.add(NumberAtom(verse.number, VerseKey.encode(ordinal, chapter.number, verse.number)))
            for (word in verse.text.split(WHITESPACE)) {
                if (word.isNotEmpty()) atoms.add(WordAtom(word))
            }
        }
        return atoms
    }

    /**
     * Flattens the rich block layer into an atom stream carrying print structure:
     * each block contributes a leading [BreakAtom] (poetry/paragraph/stanza) or a
     * [HeadingAtom], then its verse numbers and words. Verse-number spans in a
     * block's content are lifted out as [NumberAtom]s; the rest tokenizes to words.
     */
    fun atomsForBlocks(
        blocks: List<RenderBlock>,
        footnotes: List<Footnote>,
        xrefs: List<Xref> = emptyList(),
    ): List<Atom> {
        val notesByBlock = footnotes.groupBy { it.blockId }
        val blockXrefs = xrefs.filter { it.sourceKind == "block" }.groupBy { it.sourceId }
        val atoms = ArrayList<Atom>()
        for (block in blocks) {
            val heading = headingFor(block, blockXrefs[block.id].orEmpty())
            if (heading != null) {
                atoms.add(heading)
                continue
            }
            if (block.kind == "b") {
                atoms.add(BreakAtom(Flow.STANZA))
                continue
            }
            atoms.add(BreakAtom(flowFor(block.kind)))
            tokenize(block, notesByBlock[block.id].orEmpty(), atoms)
        }
        return atoms
    }

    /**
     * Emits the block's content as NumberAtoms (at verse-marker spans) + WordAtoms,
     * splicing a [FootnoteAtom] wherever a footnote caller is anchored ([Footnote.offset]).
     */
    private fun tokenize(block: RenderBlock, notes: List<Footnote>, out: ArrayList<Atom>) {
        val content = block.content
        val marks = block.verses // sorted by start
        val callers = notes.sortedBy { it.offset }
        var i = 0
        var mi = 0
        var ni = 0
        while (i < content.length) {
            // A footnote caller can sit between any two characters (usually right
            // after a word/punctuation), so check it before words and numbers.
            if (ni < callers.size && i == callers[ni].offset) {
                out.add(FootnoteAtom(callers[ni].id))
                ni++
                continue
            }
            if (mi < marks.size && i == marks[mi].start) {
                val m = marks[mi]
                out.add(NumberAtom(m.number, m.verseKey))
                i = m.end
                mi++
                continue
            }
            if (content[i] == ' ') { i++; continue }
            val bound = minOf(
                if (mi < marks.size) marks[mi].start else content.length,
                if (ni < callers.size) callers[ni].offset else content.length,
            )
            var j = i
            while (j < bound && content[j] != ' ') j++
            if (j > i) {
                // Carry the word's own block span: the word layer is addressed by it.
                out.add(
                    WordAtom(
                        word = content.substring(i, j),
                        blockId = block.id,
                        blockStart = i,
                        blockEnd = j,
                    ),
                )
                i = j
            } else {
                i++
            }
        }
        // A caller anchored at the very end of the block's content.
        while (ni < callers.size && callers[ni].offset >= content.length) {
            out.add(FootnoteAtom(callers[ni].id)); ni++
        }
    }

    private fun flowFor(kind: String): Flow = when (kind) {
        "q1" -> Flow.POETRY1
        "q2", "q3" -> Flow.POETRY2
        "qr" -> Flow.POETRY_REFRAIN
        "li1" -> Flow.LIST1
        "li2" -> Flow.LIST2
        else -> Flow.PARAGRAPH // p, pmo, pc, pm, mi, nb, …
    }

    private fun headingFor(block: RenderBlock, xrefs: List<Xref>): HeadingAtom? {
        val links = xrefs.map { XrefLink(it.start, it.end, it.targetStartKey, it.targetEndKey) }
        return when (block.kind) {
            "s1", "ms", "ms1" -> HeadingAtom(block.content, minor = false, links = links)
            "s2", "s3", "mr", "r", "d", "qa", "sr", "sp" ->
                HeadingAtom(block.content, minor = true, links = links)
            else -> null
        }
    }

    /**
     * Splits [atoms] into pages that each fit the given heights. [firstPageHeight]
     * is usually smaller than [otherPageHeight] because the first page also carries
     * the book/chapter heading. Always places at least one atom per page.
     */
    fun paginate(
        atoms: List<Atom>,
        typo: ReaderTypography,
        width: Int,
        firstPageHeight: Int,
        otherPageHeight: Int,
    ): List<List<Atom>> {
        val pages = ArrayList<List<Atom>>()
        var start = 0
        while (start < atoms.size) {
            val maxHeight = if (pages.isEmpty()) firstPageHeight else otherPageHeight
            // Always place at least one atom to guarantee progress.
            val fitted = fitCount(atoms, start, maxHeight, typo, width).coerceAtLeast(1)
            val count = trimDanglingOpeners(atoms, start, fitted)
            pages.add(ArrayList(atoms.subList(start, start + count)))
            start += count
        }
        return pages
    }

    /**
     * Never end a page on the atoms that *open* the next verse — a verse [NumberAtom],
     * or the [BreakAtom]/[HeadingAtom]s that precede it — when their text spilled onto
     * the following page. Backs the page's cut off to the last real content atom (a
     * word, or a footnote caller attached to one) so a verse number always stays on
     * the page with its text. If the fitted page has no content atom at all (e.g. a
     * lone oversized heading), it is left as-is so pagination still makes progress.
     */
    private fun trimDanglingOpeners(atoms: List<Atom>, start: Int, count: Int): Int {
        var lastContent = -1
        for (i in start until start + count) {
            when (atoms[i]) {
                is WordAtom, is FootnoteAtom -> lastContent = i
                else -> {}
            }
        }
        if (lastContent < 0) return count
        val trimmed = lastContent - start + 1
        return if (trimmed < count) trimmed else count
    }

    /**
     * The most atoms from [start] whose rendered height fits [maxHeight], or 0 if
     * not even one fits. Binary search over [ReaderTypography.measureBody].
     */
    fun fitCount(
        atoms: List<Atom>,
        start: Int,
        maxHeight: Int,
        typo: ReaderTypography,
        width: Int,
    ): Int {
        val remaining = atoms.size - start
        if (remaining <= 0) return 0
        fun heightOf(count: Int) = typo.measureBody(atoms, start, count, width)
        if (heightOf(1) > maxHeight) return 0

        var lo = 1
        var hi = 1
        while (hi < remaining && heightOf(hi) <= maxHeight) {
            lo = hi
            hi *= 2
        }
        if (hi > remaining) hi = remaining

        var best = lo
        var low = lo
        var high = hi
        while (low <= high) {
            val mid = (low + high) / 2
            if (heightOf(mid) <= maxHeight) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    private val WHITESPACE = Regex("\\s+")
}
