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
import com.symmetricalpalmtree.biblesprout.R
import com.symmetricalpalmtree.biblesprout.data.Canon
import com.symmetricalpalmtree.biblesprout.data.VerseKey

/**
 * Shows a footnote's text in a hard-bordered, scrimless e-ink panel (mirrors the
 * commentary picker's treatment — no dim, no motion). The header is the annotated
 * verse reference. A full-screen transparent root captures any tap to dismiss, so
 * touches never leak to the reader behind and the activity's immersive mode (no
 * BOOX system bars) is preserved.
 */
object FootnotePopup {

    fun show(activity: AppCompatActivity, verseKey: Int?, text: String) {
        val black = ContextCompat.getColor(activity, R.color.eink_black)
        val white = ContextCompat.getColor(activity, R.color.eink_white)
        val rule = ContextCompat.getColor(activity, R.color.eink_rule)
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        val heading = verseKey?.let {
            val book = Canon.byOrdinal(VerseKey.ordinalOf(it))
            "${book.name} ${VerseKey.chapterOf(it)}:${VerseKey.verseOf(it)}"
        } ?: activity.getString(R.string.footnote)

        // Heading + body stacked in a hard-bordered panel; a heavy black border
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
                    this.text = heading
                    textSize = 18f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(black)
                    setPadding(dp(20), dp(16), dp(20), dp(12))
                },
            )
            // Hairline divider, inset from the border so it never touches the stroke.
            addView(
                View(activity).apply {
                    setBackgroundColor(rule)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        maxOf(1, dp(1)),
                    ).apply { marginStart = dp(12); marginEnd = dp(12) }
                },
            )
            addView(
                TextView(activity).apply {
                    this.text = text
                    textSize = 18f
                    setTextColor(black)
                    setLineSpacing(0f, 1.25f)
                    setPadding(dp(20), dp(14), dp(20), dp(18))
                },
            )
        }

        // Full-screen transparent catcher: any tap dismisses, nothing leaks through.
        val root = FrameLayout(activity)
        root.addView(
            panel,
            FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.CENTER },
        )

        val dialog = Dialog(activity).apply {
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
        root.setOnClickListener { dialog.dismiss() }
        dialog.show()
        dialog.window?.let { w ->
            WindowInsetsControllerCompat(w, w.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
