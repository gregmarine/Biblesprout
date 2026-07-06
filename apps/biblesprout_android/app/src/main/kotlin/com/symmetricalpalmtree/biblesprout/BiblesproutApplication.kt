package com.symmetricalpalmtree.biblesprout

import android.app.Application
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Loads SQLCipher's native library once at startup. The read-only Bible and
 * commentary databases are opened plaintext (no key), but they still go through
 * SQLCipher's engine because it bundles an FTS5-capable SQLite build; the Android
 * system library often lacks FTS5, which the reader's full-text search needs.
 *
 * Also opens the hidden-API gate the Onyx BOOX SDK needs: its handwriting layer
 * reflects into hidden system APIs (VMRuntime, RawInputManager) that Android 14+
 * blocks, so this must run before any SDK code touches them.
 */
class BiblesproutApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("BiblesproutApplication", "SQLCipher native lib failed to load", e)
        }
        HiddenApiBypass.addHiddenApiExemptions("")
    }
}
