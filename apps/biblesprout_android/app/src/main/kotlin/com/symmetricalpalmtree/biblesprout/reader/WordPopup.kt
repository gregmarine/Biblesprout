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
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.BidiFormatter
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.symmetricalpalmtree.biblesprout.R
import com.symmetricalpalmtree.biblesprout.data.BibleWord
import com.symmetricalpalmtree.biblesprout.data.LexiconEntry

/**
 * Shows the original-language word behind a pressed English word — its form,
 * transliteration, parsing and Strong's entry — in the same hard-bordered,
 * scrimless e-ink panel the footnote popup uses (no dim, no motion). A full-screen
 * transparent root captures any tap to dismiss, so touches never leak to the reader
 * behind and the activity's immersive mode (no BOOX system bars) is preserved.
 *
 * The panel is the *only* home for verse-anchored commentary now that a long-press
 * opens word study instead: [onCommentary], when given, adds the action at the foot.
 */
object WordPopup {

    /**
     * Isolates a right-to-left run (Hebrew, Aramaic) inside this LTR panel.
     *
     * Without it Android's bidi algorithm reads the first strong character of the
     * line — the Hebrew — and lays the whole paragraph out right-to-left, so the
     * line hugs the right edge and neighbouring punctuation drifts to the wrong end
     * ("ray-sheeth'" renders as "('ray-sheeth"). Wrapping marks the run's direction
     * explicitly so the Hebrew reads RTL *within* a line that stays LTR.
     */
    private fun bidi(text: String): CharSequence =
        BidiFormatter.getInstance().unicodeWrap(text)

    fun show(
        activity: AppCompatActivity,
        word: BibleWord,
        entry: LexiconEntry?,
        onCommentary: (() -> Unit)? = null,
    ) {
        val black = ContextCompat.getColor(activity, R.color.eink_black)
        val white = ContextCompat.getColor(activity, R.color.eink_white)
        val rule = ContextCompat.getColor(activity, R.color.eink_rule)
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        var dialog: Dialog? = null

        fun divider() = View(activity).apply {
            setBackgroundColor(rule)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxOf(1, dp(1)),
            ).apply { marginStart = dp(12); marginEnd = dp(12) }
        }

        fun label(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 13f
            setTextColor(rule)
            letterSpacing = 0.08f
            setPadding(dp(20), dp(12), dp(20), dp(2))
        }

        fun body(text: String, size: Float = 17f, italic: Boolean = false) = TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(black)
            setLineSpacing(0f, 1.25f)
            if (italic) setTypeface(typeface, Typeface.ITALIC)
            setPadding(dp(20), 0, dp(20), dp(6))
        }

        // --- header: the pressed English, then the form actually printed ---------
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(activity).apply {
                    // The whole English this one original word became — often more than
                    // the single word pressed ("In the beginning" for בְּרֵאשִׁית).
                    text = word.english
                    textSize = 20f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(black)
                    setPadding(dp(20), dp(16), dp(20), dp(2))
                },
            )
            // The inflected form as it stands in this verse — with its parsing, this
            // is what distinguishes the word here from the dictionary headword.
            val original = word.original
            if (original != null) {
                addView(
                    TextView(activity).apply {
                        text = listOfNotNull(bidi(original), word.translit)
                            .joinToString("  ·  ")
                        textSize = 19f
                        setTextColor(black)
                        setPadding(dp(20), 0, dp(20), dp(14))
                    },
                )
            }
        }

        val details = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL

            word.morphText?.let {
                addView(label(activity.getString(R.string.word_parsing)))
                addView(body(it))
            }

            if (entry != null) {
                // Headword + pronunciation: the dictionary's citation form, which is
                // usually not the form printed above.
                val head = listOfNotNull(
                    entry.lemma?.let { bidi(it) },
                    entry.pronounce?.let { "($it)" },
                ).joinToString(" ")
                if (head.isNotBlank()) {
                    addView(label(activity.getString(R.string.word_root)))
                    addView(body(head, size = 19f))
                }
                entry.definition?.let {
                    addView(label(activity.getString(R.string.word_definition)))
                    addView(body(it))
                }
                entry.derivation?.let {
                    addView(label(activity.getString(R.string.word_derivation)))
                    addView(body(it, size = 15f))
                }
                entry.usage?.let {
                    addView(label(activity.getString(R.string.word_kjv_usage)))
                    addView(body(it, size = 15f, italic = true))
                }
            } else if (word.strongs != null) {
                // The word layer knows the number but no dictionary is installed.
                addView(body(activity.getString(R.string.word_no_lexicon), size = 15f, italic = true))
            }
            setPadding(0, 0, 0, dp(12))
        }

        // Strong's number sits at the foot of the details as the entry's identity.
        word.strongs?.let {
            details.addView(label(activity.getString(R.string.word_strongs)))
            details.addView(body(it))
        }

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // The panel is English chrome that quotes Hebrew/Greek, so pin it LTR and
            // let bidi() isolate the RTL runs. Children inherit this by default.
            textDirection = View.TEXT_DIRECTION_LTR
            background = GradientDrawable().apply {
                setColor(white)
                setStroke(maxOf(1, dp(1)), black) // thin hairline, like the app's rules
                cornerRadius = dp(4).toFloat()
            }
            addView(header)
            addView(divider())
            // Strong's definitions run long; scroll the detail body rather than
            // letting the panel grow past the screen. Weighted so it takes only the
            // space left over once the header and the action row are placed.
            addView(
                ScrollView(activity).apply {
                    isFillViewport = false
                    addView(details)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                    ).apply { weight = 1f }
                },
            )
            if (onCommentary != null) {
                addView(divider())
                addView(
                    TextView(activity).apply {
                        text = activity.getString(R.string.commentary_on_verse)
                        textSize = 17f
                        setTextColor(black)
                        setPadding(dp(20), dp(16), dp(20), dp(16))
                        gravity = Gravity.CENTER_VERTICAL
                        setOnClickListener {
                            dialog?.dismiss()
                            onCommentary()
                        }
                    },
                )
            }
        }

        // Full-screen transparent catcher: any outside tap dismisses, nothing leaks.
        val root = FrameLayout(activity)
        root.addView(
            panel,
            FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                // Never let a long entry push the panel off-screen.
                topMargin = dp(24)
                bottomMargin = dp(24)
            },
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
        d.show()
        d.window?.let { w ->
            WindowInsetsControllerCompat(w, w.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
