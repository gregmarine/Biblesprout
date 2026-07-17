package com.symmetricalpalmtree.biblesprout.data

import net.zetetic.database.sqlcipher.SQLiteDatabase

/**
 * One Strong's dictionary entry. [strongs] is the identifier the Bible's word
 * layer stores (`H7225`, `G976`), so a tapped word joins straight to its entry.
 * [lemma] is the dictionary headword — the word's citation form, which differs
 * from the inflected form actually printed in the verse (that lives on
 * [BibleWord.original]).
 */
data class LexiconEntry(
    val strongs: String,
    val language: String,
    val lemma: String?,
    val translit: String?,
    val pronounce: String?,
    val pos: String?,
    val derivation: String?,
    val definition: String?,
    val usage: String?,
)

/**
 * Read-only accessor for a lexicon source database (`*.lexicon`) — Strong's
 * Hebrew/Aramaic and Greek dictionaries (1890, public domain). Opened plaintext
 * through SQLCipher like the other read-only sources.
 *
 * All methods are blocking; call them off the main thread (Dispatchers.IO).
 */
class LexiconDatabase private constructor(
    private val db: SQLiteDatabase,
    val metadata: Map<String, String>,
) {
    val id: String get() = metadata["id"] ?: "unknown"
    val title: String get() = metadata["title"] ?: id

    fun close() = db.close()

    /** The entry for a Strong's identifier (`H7225`), or null if unknown. */
    fun entryFor(strongs: String): LexiconEntry? =
        db.rawQuery(
            "SELECT strongs, language, lemma, translit, pronounce, pos, " +
                "derivation, definition, usage FROM entry WHERE strongs = ?",
            arrayOf(strongs),
        ).use { c ->
            if (!c.moveToNext()) return null
            LexiconEntry(
                strongs = c.getString(0),
                language = c.getString(1),
                lemma = c.getStringOrNull(2),
                translit = c.getStringOrNull(3),
                pronounce = c.getStringOrNull(4),
                pos = c.getStringOrNull(5),
                derivation = c.getStringOrNull(6),
                definition = c.getStringOrNull(7),
                usage = c.getStringOrNull(8),
            )
        }

    companion object {
        /** Opens an existing `.lexicon` file plaintext (empty SQLCipher password). */
        fun openFile(path: String): LexiconDatabase {
            val db = SQLiteDatabase.openOrCreateDatabase(path, "", null, null)
            return LexiconDatabase(db, readMetadata(db))
        }
    }
}

internal fun android.database.Cursor.getStringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)
