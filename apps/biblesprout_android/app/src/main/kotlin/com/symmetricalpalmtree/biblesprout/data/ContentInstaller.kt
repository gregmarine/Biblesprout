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
     * as [destName], copying it if missing or if the bundled asset has changed
     * since the installed copy was written. Returns the installed file.
     */
    fun ensureInstalled(assetPath: String, destName: String): File {
        val dest = File(contentDir, destName)
        val stampFile = File(contentDir, "$destName.stamp")
        val stamp = bundledStamp(assetPath)
        val installed = runCatching { stampFile.readText() }.getOrNull()
        if (dest.exists() && installed == stamp) return dest

        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        runCatching { stampFile.writeText(stamp) }
        return dest
    }

    /**
     * Identity of the bundled asset: when this APK was installed, plus the asset's
     * size.
     *
     * Size alone is **not** enough. Rebuilding a database can change its contents
     * without changing its length — SQLite pads to whole pages, so a fix worth a
     * few dozen characters lands on exactly the same byte count — and the stale
     * copy then survives on device, silently serving old content. The install time
     * changes on every update, which is the only way bundled content can change.
     */
    private fun bundledStamp(assetPath: String): String {
        val updated = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Exception) {
            0L
        }
        return "$updated:${bundledSize(assetPath)}"
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
