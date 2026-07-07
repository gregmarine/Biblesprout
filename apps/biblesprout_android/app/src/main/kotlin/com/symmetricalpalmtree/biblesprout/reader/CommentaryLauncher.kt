package com.symmetricalpalmtree.biblesprout.reader

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
     * Presents the installed commentaries as a hard-bordered, scrimless e-ink panel
     * (matching the footnote popup's treatment) and returns the chosen one, or null
     * if dismissed. A full-screen transparent root captures any outside tap to
     * dismiss, so touches never leak and the activity's immersive mode (no BOOX
     * system bars) is preserved. Also used by the commentary screen's "Change" action.
     */
    suspend fun pick(
        activity: AppCompatActivity,
        options: List<CommentaryDatabase>,
    ): CommentaryDatabase? = suspendCancellableCoroutine { cont ->
        val black = ContextCompat.getColor(activity, R.color.eink_black)
        val white = ContextCompat.getColor(activity, R.color.eink_white)
        val rule = ContextCompat.getColor(activity, R.color.eink_rule)

        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        // A hairline divider inset from the border so it never touches the stroke.
        fun divider() = View(activity).apply {
            setBackgroundColor(rule)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxOf(1, dp(1)),
            ).apply { marginStart = dp(12); marginEnd = dp(12) }
        }

        var dialog: Dialog? = null

        // Header + options stacked in a hard-bordered panel; a thin black border
        // stands in for the missing scrim (no dim on e-ink). Children carry no
        // background of their own — one would paint over the panel's border stroke.
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(white)
                setStroke(maxOf(1, dp(1)), black) // thin hairline, like the app's rules
                cornerRadius = dp(4).toFloat()
            }
            addView(
                TextView(activity).apply {
                    text = activity.getString(R.string.commentary)
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(black)
                    setPadding(dp(20), dp(16), dp(20), dp(12))
                },
            )
            for (db in options) {
                addView(divider())
                addView(
                    TextView(activity).apply {
                        text = db.title
                        textSize = 18f
                        setTextColor(black)
                        setPadding(dp(20), dp(16), dp(20), dp(16))
                        setOnClickListener {
                            if (cont.isActive) cont.resume(db)
                            dialog?.dismiss()
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        gravity = Gravity.CENTER_VERTICAL
                    },
                )
            }
        }

        // Full-screen transparent catcher: any outside tap dismisses, nothing leaks.
        val root = FrameLayout(activity)
        root.addView(
            panel,
            FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER },
        )

        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setDimAmount(0f) // no scrim
            // Fill the screen so the transparent root catches every tap.
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Not-focusable keeps the activity's immersive mode (no BOOX system bars);
            // the full-screen root still receives taps because they land inside it.
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }

        val d = dialog!!
        root.setOnClickListener { d.dismiss() }
        d.setOnDismissListener { if (cont.isActive) cont.resume(null) }
        cont.invokeOnCancellation { d.dismiss() }
        d.show()
        d.window?.let { w ->
            WindowInsetsControllerCompat(w, w.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
