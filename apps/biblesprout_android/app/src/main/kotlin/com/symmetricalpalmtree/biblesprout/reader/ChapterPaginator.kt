package com.symmetricalpalmtree.biblesprout.reader

import com.symmetricalpalmtree.biblesprout.data.VerseKey
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
            val count = fitCount(atoms, start, maxHeight, typo, width).coerceAtLeast(1)
            pages.add(ArrayList(atoms.subList(start, start + count)))
            start += count
        }
        return pages
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
