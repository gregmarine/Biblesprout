package com.symmetricalpalmtree.biblesprout.data

import com.symmetricalpalmtree.biblesprout.data.index.AppSetting
import com.symmetricalpalmtree.biblesprout.data.index.SettingDao

/**
 * Remembers which commentary the reader last opened, so that with more than one
 * installed the launcher can skip straight to it instead of prompting every
 * time. The choice is a source id, persisted in the global index's key/value
 * store and cached in memory for synchronous reads. Ported from the Flutter
 * `commentary_preferences.dart`.
 */
class CommentaryPreferences private constructor(
    private val dao: SettingDao?,
    @Volatile private var cached: String?,
) {
    /** The source id of the last-opened commentary, or null if none chosen yet. */
    val lastId: String? get() = cached

    /** Records [id] as the last-opened commentary (cached now; persisted if backed). */
    suspend fun remember(id: String) {
        cached = id
        dao?.put(AppSetting(KEY, id))
    }

    companion object {
        private const val KEY = "last_commentary_id"

        /** Loads the persisted choice from the global index. */
        suspend fun load(dao: SettingDao): CommentaryPreferences =
            CommentaryPreferences(dao, dao.get(KEY))

        /** An in-memory-only store (no persistence), for tests. */
        fun memory(lastId: String? = null): CommentaryPreferences =
            CommentaryPreferences(null, lastId)
    }
}
