package com.symmetricalpalmtree.biblesprout.reader

/** An item in a flowing passage: a book/chapter heading or a run of body atoms. */
sealed interface PassageItem
data class HeadingItem(val text: String) : PassageItem
data class TextItem(val atoms: List<Atom>) : PassageItem

/**
 * Paginates a passage — an ordered list of heading/text blocks that may span
 * chapters and books — into pages that each fit [pageHeight]. Text blocks flow
 * and split across pages; a heading is kept with at least the first line of the
 * text that follows, so it is never orphaned at a page bottom. Ported from the
 * Flutter `passage_paginator.dart`.
 *
 * Unlike Flutter, no per-block line reserve is needed: the paginator measures and
 * the view draws the same StaticLayout, so packed height equals rendered height.
 */
object PassagePaginator {

    fun paginate(
        blocks: List<PassageItem>,
        typo: ReaderTypography,
        width: Int,
        pageHeight: Int,
        headingTopPad: Int,
        headingBottomPad: Int,
    ): List<List<PassageItem>> {
        val pages = ArrayList<List<PassageItem>>()
        var current = ArrayList<PassageItem>()
        var remaining = pageHeight

        fun commit() {
            pages.add(current)
            current = ArrayList()
            remaining = pageHeight
        }

        val oneLine = typo.lineHeightPx // heading keep-with-next check

        for (block in blocks) {
            when (block) {
                is HeadingItem -> {
                    val hh = headingTopPad +
                        typo.passageHeadingLayout(block.text, width).height +
                        headingBottomPad
                    if (current.isNotEmpty() && remaining < hh + oneLine) commit()
                    current.add(block)
                    remaining -= hh
                }

                is TextItem -> {
                    val atoms = block.atoms
                    var idx = 0
                    while (idx < atoms.size) {
                        val count = ChapterPaginator.fitCount(atoms, idx, remaining, typo, width)
                        if (count == 0) {
                            if (current.isEmpty()) {
                                // A page can't fit even one atom; place one to progress.
                                current.add(TextItem(atoms.subList(idx, idx + 1)))
                                idx += 1
                            }
                            commit()
                            continue
                        }
                        val used = typo.measureBody(atoms, idx, count, width)
                        current.add(TextItem(atoms.subList(idx, idx + count)))
                        idx += count
                        remaining -= used
                        if (idx < atoms.size) commit()
                    }
                }
            }
        }
        if (current.isNotEmpty()) pages.add(current)
        return pages.ifEmpty { listOf(emptyList()) }
    }
}
