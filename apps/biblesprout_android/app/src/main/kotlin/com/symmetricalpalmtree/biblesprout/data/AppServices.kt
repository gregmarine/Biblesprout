package com.symmetricalpalmtree.biblesprout.data

import android.content.Context
import com.symmetricalpalmtree.biblesprout.model.Bible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide handle on the opened content sources. Installs and opens the
 * bundled Bible once, and holds the in-memory [Bible] the library and reader
 * consume. Mirrors the Flutter `app_services.dart` bootstrap.
 *
 * The content databases are read-only, so a single shared instance is safe for
 * the whole app. Commentaries and the global read-write index will be added here.
 */
object AppServices {
    lateinit var bibleDb: BibleDatabase
        private set
    lateinit var bible: Bible
        private set

    @Volatile
    private var ready = false
    private val mutex = Mutex()

    /** Idempotent; safe to call from any screen's entry point. */
    suspend fun bootstrap(context: Context) {
        if (ready) return
        mutex.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                val file = ContentInstaller(context.applicationContext)
                    .ensureInstalled("content/bsb.bible", "bsb.bible")
                bibleDb = BibleDatabase.openFile(file.absolutePath)
                bible = bibleDb.loadBible()
            }
            ready = true
        }
    }
}
