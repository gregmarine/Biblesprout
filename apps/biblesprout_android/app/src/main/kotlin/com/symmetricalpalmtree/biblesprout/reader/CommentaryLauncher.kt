package com.symmetricalpalmtree.biblesprout.reader

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.biblesprout.R
import com.symmetricalpalmtree.biblesprout.data.AppServices
import com.symmetricalpalmtree.biblesprout.data.Canon
import com.symmetricalpalmtree.biblesprout.data.CommentaryDatabase
import com.symmetricalpalmtree.biblesprout.data.VerseKey
import com.symmetricalpalmtree.biblesprout.data.VerseRange
import com.symmetricalpalmtree.biblesprout.ui.CommentaryActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The shared entry point for opening commentary, anchored to a verse or a span of
 * verse-key [ranges]. With more than one commentary installed it opens the
 * last-used one directly, showing a bordered, scrimless picker only on first use.
 * Does nothing if no commentary is installed, the picker is dismissed, or the span
 * has no comments. Ported from the Flutter `commentary_launcher.dart`.
 */
object CommentaryLauncher {

    /** Opens commentary for a single verse; the label is derived from the key. */
    fun openVerse(activity: AppCompatActivity, verseKey: Int) {
        val book = Canon.byOrdinal(VerseKey.ordinalOf(verseKey))
        val reference = "${book.name} ${VerseKey.chapterOf(verseKey)}:${VerseKey.verseOf(verseKey)}"
        open(activity, listOf(VerseRange(verseKey, verseKey)), reference)
    }

    /**
     * Opens commentary over one or more inclusive verse-key [ranges] — a chapter
     * from the reader or a passage's (possibly multi-book) span. [reference] is the
     * screen's label, e.g. "John 3".
     */
    fun open(activity: AppCompatActivity, ranges: List<VerseRange>, reference: String) {
        val commentaries = AppServices.commentaries
        if (commentaries.isEmpty()) return
        activity.lifecycleScope.launch {
            val db = resolve(activity, commentaries) ?: return@launch
            // Don't push an empty screen: confirm there is something to show.
            val hasEntries = withContext(Dispatchers.IO) {
                ranges.any { db.entriesForRange(it.startKey, it.endKey).isNotEmpty() }
            }
            if (!hasEntries) return@launch
            activity.startActivity(
                CommentaryActivity.intent(
                    activity,
                    commentaryId = db.id,
                    reference = reference,
                    ranges = ranges,
                    allowChange = commentaries.size > 1,
                ),
            )
        }
    }

    /** The sole commentary, the remembered one if still installed, else a pick. */
    private suspend fun resolve(
        activity: AppCompatActivity,
        commentaries: List<CommentaryDatabase>,
    ): CommentaryDatabase? {
        if (commentaries.size == 1) return commentaries.first()
        AppServices.commentaryPrefs.lastId?.let { last ->
            commentaries.firstOrNull { it.id == last }?.let { return it }
        }
        val chosen = pick(activity, commentaries) ?: return null
        AppServices.commentaryPrefs.remember(chosen.id)
        return chosen
    }

    /**
     * Presents the installed commentaries as a hard-bordered, scrimless e-ink
     * panel and returns the chosen one (null if dismissed). Also used by the open
     * commentary screen's "Change" action.
     */
    suspend fun pick(
        activity: AppCompatActivity,
        options: List<CommentaryDatabase>,
    ): CommentaryDatabase? = suspendCancellableCoroutine { cont ->
        val black = ContextCompat.getColor(activity, R.color.eink_black)
        val rule = ContextCompat.getColor(activity, R.color.eink_rule)

        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.eink_white))
            // A 2px black border stands in for the missing scrim (no dim on e-ink).
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(activity, R.color.eink_white))
                setStroke(dp(2), black)
                cornerRadius = dp(4).toFloat()
            }
        }
        val dialog = Dialog(activity).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(
                panel,
                ViewGroup.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0f) // no scrim
        }

        panel.addView(
            TextView(activity).apply {
                text = activity.getString(R.string.commentary)
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(black)
                setPadding(dp(20), dp(18), dp(20), dp(12))
            },
        )
        for (db in options) {
            panel.addView(
                TextView(activity).apply {
                    text = db.title
                    textSize = 18f
                    setTextColor(black)
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                    // Hairline top rule separates the options.
                    background = android.graphics.drawable.LayerDrawable(
                        arrayOf(
                            ColorDrawable(rule),
                            android.graphics.drawable.InsetDrawable(
                                ColorDrawable(ContextCompat.getColor(activity, R.color.eink_white)),
                                0, dp(1), 0, 0,
                            ),
                        ),
                    )
                    setOnClickListener {
                        if (cont.isActive) cont.resume(db)
                        dialog.dismiss()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    gravity = Gravity.CENTER_VERTICAL
                },
            )
        }

        dialog.setOnDismissListener { if (cont.isActive) cont.resume(null) }
        cont.invokeOnCancellation { dialog.dismiss() }
        dialog.show()
    }
}
