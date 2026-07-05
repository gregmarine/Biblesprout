package com.symmetricalpalmtree.biblesprout.data

/** Shared FTS5 query-building for the content databases. */
internal object Fts {
    private val token = Regex("[\\p{L}\\p{N}]+")

    /**
     * Reduces a user query to word tokens and rebuilds it as a safe prefix-AND
     * FTS5 MATCH expression — so punctuation can't cause a syntax error, and
     * searching "love" also finds "loved" / "lovely". Returns null when the
     * query has no searchable tokens.
     */
    fun matchExpression(query: String): String? {
        val tokens = token.findAll(query.lowercase()).map { it.value }.toList()
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "\"$it\"*" }
    }
}
