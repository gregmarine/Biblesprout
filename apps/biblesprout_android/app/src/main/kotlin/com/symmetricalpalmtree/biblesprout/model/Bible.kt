package com.symmetricalpalmtree.biblesprout.model

import com.symmetricalpalmtree.biblesprout.data.Testament

/**
 * In-memory Bible object graph the reader and library consume, rebuilt from a
 * `.bible` source database in canonical order. Ported from the Flutter
 * `bible.dart`; navigation helpers (continuous cross-book reading) will be added
 * when the reader is built.
 */
data class Verse(val number: Int, val text: String)

data class Chapter(val number: Int, val verses: List<Verse>)

data class Book(
    /** 0-based position within [Bible.books] (canonical order). */
    val index: Int,
    val name: String,
    val testament: Testament,
    val chapters: List<Chapter>,
)

class Bible(val books: List<Book>) {
    val oldTestament: List<Book> get() = books.filter { it.testament == Testament.OLD }
    val newTestament: List<Book> get() = books.filter { it.testament == Testament.NEW }
}
