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

data class WordAtom(val word: String) : Atom

/**
 * A single word's address for highlight selection: which verse it belongs to and
 * its 0-based position among that verse's words (the verse number aside). Stable
 * across pagination, so a highlight stored as a word span underlines the same
 * words however the text repaginates.
 */
data class WordRef(val verseKey: Int, val wordIndex: Int)
