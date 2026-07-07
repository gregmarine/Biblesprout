package com.symmetricalpalmtree.biblesprout.reader

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // A 2px black border stands in for the missing scrim (no dim on e-ink).
            background = GradientDrawable().apply {
                setColor(white)
                setStroke(dp(2), black)
                cornerRadius = dp(4).toFloat()
            }
        }

        val heading = verseKey?.let {
            val book = Canon.byOrdinal(VerseKey.ordinalOf(it))
            "${book.name} ${VerseKey.chapterOf(it)}:${VerseKey.verseOf(it)}"
        } ?: activity.getString(R.string.footnote)

        panel.addView(
            TextView(activity).apply {
                this.text = heading
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(black)
                setPadding(dp(20), dp(16), dp(20), dp(12))
            },
        )
        val body = TextView(activity).apply {
            this.text = text
            textSize = 18f
            setTextColor(black)
            setLineSpacing(0f, 1.25f)
            setPadding(dp(20), dp(14), dp(20), dp(18))
            // Hairline top rule between heading and body.
            background = LayerDrawable(
                arrayOf(ColorDrawable(rule), InsetDrawable(ColorDrawable(white), 0, dp(1), 0, 0)),
            )
        }
        val scroll = ScrollView(activity).apply {
            addView(body)
            isVerticalScrollBarEnabled = false
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Full-screen transparent catcher: any tap dismisses, nothing leaks through.
        val root = FrameLayout(activity)
        val width = dp(340)
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.7f).toInt()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.addView(
            panel,
            FrameLayout.LayoutParams(
                width,
                if (panel.measuredHeight > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
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
