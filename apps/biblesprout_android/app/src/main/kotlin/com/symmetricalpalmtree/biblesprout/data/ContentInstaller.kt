package com.symmetricalpalmtree.biblesprout.data

import android.content.Context
import java.io.File

/**
 * Copies bundled read-only content databases out of the APK's assets into
 * writable app storage, where SQLite can open them by path. (An asset inside the
 * APK has no real file path, so it must be materialised first.) Mirrors the
 * Flutter app's one-time copy of `bsb.bible`.
 *
 * This is the seam the future downloadable-sources model plugs into: instead of
 * copying from assets, sources will be fetched into the same [contentDir].
 */
class ContentInstaller(private val context: Context) {

    /** Where installed source databases live; created on first use. */
    val contentDir: File by lazy {
        File(context.filesDir, "content").apply { mkdirs() }
    }

    /**
     * Ensures [assetPath] (e.g. `content/bsb.bible`) is present in [contentDir]
     * as [destName], copying it if missing or if the installed size differs from
     * the bundled asset. Returns the installed file.
     */
    fun ensureInstalled(assetPath: String, destName: String): File {
        val dest = File(contentDir, destName)
        val assetSize = bundledSize(assetPath)
        if (dest.exists() && (assetSize < 0 || dest.length() == assetSize)) {
            return dest
        }
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        return dest
    }

    /** Uncompressed size of a bundled asset, or -1 if it can't be determined. */
    private fun bundledSize(assetPath: String): Long =
        try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (_: Exception) {
            // Thrown when the asset is stored compressed; fall back to
            // copy-if-missing (see build.gradle noCompress for the DB extensions).
            -1L
        }
}
