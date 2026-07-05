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
 * the whole app.
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

    /** Every installed commentary, opened read-only. Empty if none is installed. */
    lateinit var commentaries: List<CommentaryDatabase>
        private set
    lateinit var commentaryPrefs: CommentaryPreferences
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
                val installer = ContentInstaller(context.applicationContext)
                val file = installer.ensureInstalled("content/bsb.bible", "bsb.bible")
                bibleDb = BibleDatabase.openFile(file.absolutePath)
                bible = bibleDb.loadBible()

                index = AppIndexDatabase.open(context)
                registerBibleSource(file.name)
                readingPosition = ReadingPositionStore(index.progress(), bibleDb.id)

                commentaries = installCommentaries(installer)
                commentaries.forEach { registerCommentarySource(it) }
                commentaryPrefs = CommentaryPreferences.load(index.settings())
            }
            ready = true
        }
    }

    /**
     * Installs and opens the bundled commentaries. Only the small Concise edition
     * ships in the APK for now; the six-volume Complete waits for the download
     * model (the same [ContentInstaller] seam). A missing or unreadable file is
     * skipped so the reader still works without commentary.
     */
    private fun installCommentaries(installer: ContentInstaller): List<CommentaryDatabase> =
        BUNDLED_COMMENTARIES.mapNotNull { name ->
            try {
                val file = installer.ensureInstalled("content/$name", name)
                CommentaryDatabase.openFile(file.absolutePath)
            } catch (_: Exception) {
                null
            }
        }

    /** Records a commentary in the source registry (idempotent). */
    private suspend fun registerCommentarySource(db: CommentaryDatabase) {
        val m = db.metadata
        index.sources().register(
            Source(
                id = db.id,
                type = m["type"] ?: "commentary",
                title = db.title,
                abbreviation = m["abbreviation"] ?: db.id.uppercase(),
                language = m["language"] ?: "en",
                fileName = "${db.id}.commentary",
                versification = m["versification"] ?: "english",
                isReadonly = true,
                installedAt = System.currentTimeMillis(),
            ),
        )
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

    private val BUNDLED_COMMENTARIES = listOf("mhcc.commentary")
}
