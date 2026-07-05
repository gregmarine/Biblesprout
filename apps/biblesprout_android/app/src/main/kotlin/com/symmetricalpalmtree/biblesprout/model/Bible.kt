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

/**
 * A flattened reference to a single chapter within the whole Bible, used to walk
 * continuously forward/backward across book boundaries in the reader.
 */
data class ChapterRef(val bookIndex: Int, val chapterNumber: Int)

class Bible(val books: List<Book>) {
    val oldTestament: List<Book> get() = books.filter { it.testament == Testament.OLD }
    val newTestament: List<Book> get() = books.filter { it.testament == Testament.NEW }

    fun bookAt(index: Int): Book = books[index]

    fun chapter(bookIndex: Int, chapterNumber: Int): Chapter =
        books[bookIndex].chapters[chapterNumber - 1]

    /** The chapter after [ref], or null at the very end of Revelation. */
    fun next(ref: ChapterRef): ChapterRef? {
        val book = books[ref.bookIndex]
        return when {
            ref.chapterNumber < book.chapters.size ->
                ChapterRef(ref.bookIndex, ref.chapterNumber + 1)
            ref.bookIndex < books.size - 1 -> ChapterRef(ref.bookIndex + 1, 1)
            else -> null
        }
    }

    /** The chapter before [ref], or null at Genesis 1. */
    fun previous(ref: ChapterRef): ChapterRef? = when {
        ref.chapterNumber > 1 -> ChapterRef(ref.bookIndex, ref.chapterNumber - 1)
        ref.bookIndex > 0 -> ChapterRef(ref.bookIndex - 1, books[ref.bookIndex - 1].chapters.size)
        else -> null
    }
}
