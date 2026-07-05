package com.symmetricalpalmtree.biblesprout.data.index

import com.symmetricalpalmtree.biblesprout.data.Canon

/**
 * The reader's last location, persisted so the app reopens where it left off.
 *
 * The reader speaks in `bookIndex` (0-based canonical order); the index stores
 * the canonical USFM book code. This store translates between the two. Ported
 * from the Flutter `reading_position.dart`.
 */
data class ReadingPosition(
    val bookIndex: Int,
    val chapterNumber: Int,
    /** Zero-based page index within the chapter. */
    val page: Int,
)

class ReadingPositionStore(
    private val progress: ProgressDao,
    /** Which source this position belongs to (e.g. the BSB's `bsb`). */
    private val sourceId: String,
) {
    suspend fun load(): ReadingPosition? = progress.forSource(sourceId).toPosition()

    /** The most recently read position across all sources. */
    suspend fun latest(): ReadingPosition? = progress.latest().toPosition()

    suspend fun save(position: ReadingPosition) {
        progress.save(
            ReadingProgress(
                sourceId = sourceId,
                bookUsfm = Canon.byOrdinal(position.bookIndex + 1).usfm,
                chapter = position.chapterNumber,
                page = position.page,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun ReadingProgress?.toPosition(): ReadingPosition? {
        val p = this ?: return null
        val book = Canon.tryUsfm(p.bookUsfm) ?: return null
        return ReadingPosition(book.ordinal - 1, p.chapter, p.page)
    }
}
