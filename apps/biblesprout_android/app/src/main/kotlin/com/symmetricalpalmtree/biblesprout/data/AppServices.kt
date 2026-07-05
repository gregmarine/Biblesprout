package com.symmetricalpalmtree.biblesprout.data

import android.content.Context
import com.symmetricalpalmtree.biblesprout.data.index.AppIndexDatabase
import com.symmetricalpalmtree.biblesprout.data.index.ReadingPositionStore
import com.symmetricalpalmtree.biblesprout.data.index.Source
import com.symmetricalpalmtree.biblesprout.model.Bible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide handle on the opened sources and the global index. Installs and
 * opens the bundled Bible, opens the read-write index (`biblesprout.db`), and
 * holds the in-memory [Bible] the library and reader consume. Mirrors the
 * Flutter `app_services.dart` bootstrap.
 *
 * The content databases are read-only, so a single shared instance is safe for
 * the whole app. Commentaries will be added here.
 */
object AppServices {
    lateinit var bibleDb: BibleDatabase
        private set
    lateinit var bible: Bible
        private set
    lateinit var index: AppIndexDatabase
        private set
    lateinit var readingPosition: ReadingPositionStore
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

                index = AppIndexDatabase.open(context)
                registerBibleSource(file.name)
                readingPosition = ReadingPositionStore(index.progress(), bibleDb.id)
            }
            ready = true
        }
    }

    /** Records the bundled Bible in the source registry (idempotent). */
    private suspend fun registerBibleSource(fileName: String) {
        val m = bibleDb.metadata
        index.sources().register(
            Source(
                id = bibleDb.id,
                type = m["type"] ?: "bible",
                title = bibleDb.title,
                abbreviation = m["abbreviation"] ?: bibleDb.id.uppercase(),
                language = m["language"] ?: "en",
                fileName = fileName,
                versification = m["versification"] ?: "english",
                isReadonly = true,
                installedAt = System.currentTimeMillis(),
            ),
        )
    }
}
