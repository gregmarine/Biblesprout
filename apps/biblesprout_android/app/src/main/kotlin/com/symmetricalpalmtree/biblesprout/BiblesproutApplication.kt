package com.symmetricalpalmtree.biblesprout

import android.app.Application
import android.util.Log

/**
 * Loads SQLCipher's native library once at startup. The read-only Bible and
 * commentary databases are opened plaintext (no key), but they still go through
 * SQLCipher's engine because it bundles an FTS5-capable SQLite build; the Android
 * system library often lacks FTS5, which the reader's full-text search needs.
 */
class BiblesproutApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("BiblesproutApplication", "SQLCipher native lib failed to load", e)
        }
    }
}
