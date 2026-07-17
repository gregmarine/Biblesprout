package com.symmetricalpalmtree.biblesprout.reader

/**
 * The smallest unit the paginator moves around: a raised verse number or a
 * single word of body text. Pages are always cut on atom boundaries, so words
 * are never split and no fragile character-offset math is needed. Ported from
 * the Flutter `paginator.dart`.
 */
sealed interface Atom

/**
 * A verse number. [verseKey] is the canonical key it heads, for future verse-
 * anchored actions (e.g. opening commentary on a pressed verse).
 */
data class NumberAtom(val number: Int, val verseKey: Int) : Atom

/**
 * A single word of body text. In commentary bodies a word that falls inside a
 * scripture-reference span carries that reference's target verse-key range
 * ([linkStartKey] >= 0), so it renders underlined and taps navigate to it; Bible
 * body words leave it unset.
 *
 * Scripture words also remember where they came from — block [blockId], chars
 * [blockStart] until [blockEnd] — which is the address the word layer is keyed by,
 * so a long-press resolves to the original-language word behind this English.
 * Unset (-1) for commentary words and for text with no block behind it.
 */
data class WordAtom(
    val word: String,
    val linkStartKey: Int = -1,
    val linkEndKey: Int = -1,
    val blockId: Int = -1,
    val blockStart: Int = -1,
    val blockEnd: Int = -1,
) : Atom {
    val isLink: Boolean get() = linkStartKey >= 0

    /** Whether this word can be traced back to the word layer. */
    val hasSource: Boolean get() = blockId >= 0
}

/**
 * How a [BreakAtom] starts a new line — the print structure carried over from the
 * USFM block `kind`. PARAGRAPH is prose (first-line indent); POETRY levels are
 * indented, wrapped-line-hanging poetry; STANZA is a blank separator line.
 */
enum class Flow { PARAGRAPH, POETRY1, POETRY2, POETRY_REFRAIN, LIST1, LIST2, STANZA }

/** Forces a new line in the flow, styled by [flow] (poetry indent, paragraph…). */
data class BreakAtom(val flow: Flow) : Atom

/**
 * A tappable cross-reference span inside a [HeadingAtom]'s text: [start] until [end]
 * are char offsets into the heading text, and [targetStartKey]..[targetEndKey] is the
 * inclusive verse-key range it points at.
 */
data class XrefLink(val start: Int, val end: Int, val targetStartKey: Int, val targetEndKey: Int)

/**
 * A centered section heading rendered inline in the flow (e.g. "The Creation"). A
 * `\r` parallel-passage line carries [links] — the tappable reference spans within
 * its text (e.g. "John 1:1–5" in "(John 1:1–5; Hebrews 11:1–3)").
 */
data class HeadingAtom(
    val text: String,
    val minor: Boolean,
    val links: List<XrefLink> = emptyList(),
) : Atom

/** A tappable footnote caller anchored between words; [id] resolves the popup body. */
data class FootnoteAtom(val id: Int) : Atom

/**
 * A single word's address for highlight selection: which verse it belongs to and
 * its 0-based position among that verse's words (the verse number aside). Stable
 * across pagination, so a highlight stored as a word span underlines the same
 * words however the text repaginates.
 */
data class WordRef(val verseKey: Int, val wordIndex: Int)
