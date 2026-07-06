package com.symmetricalpalmtree.biblesprout.reader

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LineHeightSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.SuperscriptSpan
import android.util.TypedValue
import androidx.core.content.res.ResourcesCompat
import com.symmetricalpalmtree.biblesprout.R
import kotlin.math.roundToInt

/**
 * The reader's fonts, sizes, and text-layout building. The key correctness trick
 * for e-ink pagination: the paginator measures a page and the view draws it with
 * the *same* [StaticLayout] configuration, so measured height and rendered height
 * are identical by construction — a page that measures as fitting always renders
 * without overflow.
 *
 * `LineHeightSpan.Standard` forces every line to one body line-height (the strut
 * equivalent), so a superscript verse number never makes its line taller. Sizes
 * are in sp, so the BOOX's 0.85 system font scale is honoured automatically and
 * measurement matches rendering.
 */
class ReaderTypography(context: Context) {

    private val metrics = context.resources.displayMetrics
    private fun sp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)
    fun dp(value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics).roundToInt()

    private val serif = ResourcesCompat.getFont(context, R.font.noto_serif) ?: Typeface.SERIF
    private val serifBold = Typeface.create(serif, Typeface.BOLD)

    // Reading typography, mirroring the Flutter Eink constants (readingFontSize 30,
    // lineHeight 1.5) and the reader's derived heading/number sizes.
    private val bodySizePx = sp(30f)
    val lineHeightPx = (bodySizePx * 1.5f).roundToInt()
    val gap1 = dp(4f)
    val gap2 = dp(22f)

    val body = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        typeface = serif
        textSize = bodySizePx
        color = BLACK
    }
    private val bookTitle = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        typeface = serifBold
        textSize = sp(30f * 0.85f)
        color = BLACK
        letterSpacing = 0.08f
    }
    private val chapterNumber = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        typeface = serifBold
        textSize = sp(30f * 2.1f)
        color = BLACK
    }

    // Inline heading for the flowing passage view ("John 3"), centered.
    private val passageHeading = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        typeface = serifBold
        textSize = sp(30f * 0.9f)
        color = BLACK
        letterSpacing = 0.037f
    }

    /** The rendered/measured spans for a run of atoms (identical for both uses). */
    private fun spannable(atoms: List<Atom>): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        atoms.forEachIndexed { i, atom ->
            when (atom) {
                is NumberAtom -> {
                    if (i > 0) sb.append(' ')
                    val start = sb.length
                    sb.append(atom.number.toString())
                    sb.setSpan(RelativeSizeSpan(0.62f), start, sb.length, EXCL)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, EXCL)
                    sb.setSpan(SuperscriptSpan(), start, sb.length, EXCL)
                }
                is WordAtom -> sb.append(if (i > 0) " ${atom.word}" else atom.word)
            }
        }
        if (sb.isNotEmpty()) {
            sb.setSpan(LineHeightSpan.Standard(lineHeightPx), 0, sb.length, INCL)
        }
        return sb
    }

    private fun layout(text: CharSequence, paint: TextPaint, width: Int, align: Layout.Alignment) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(align)
            .setLineSpacing(0f, 1f)
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    fun bodyLayout(atoms: List<Atom>, width: Int): StaticLayout =
        layout(spannable(atoms), body, width, Layout.Alignment.ALIGN_NORMAL)

    /** Rendered height of atoms [start, start+count), measured exactly as drawn. */
    fun measureBody(atoms: List<Atom>, start: Int, count: Int, width: Int): Int =
        bodyLayout(atoms.subList(start, start + count), width).height

    fun headingLayouts(bookName: String, chapter: Int, width: Int): Pair<StaticLayout, StaticLayout> =
        layout(bookName.uppercase(), bookTitle, width, Layout.Alignment.ALIGN_CENTER) to
            layout(chapter.toString(), chapterNumber, width, Layout.Alignment.ALIGN_CENTER)

    /** Total height a first-page heading (book title + big chapter number) needs. */
    fun headingHeight(bookName: String, chapter: Int, width: Int): Int {
        val (title, number) = headingLayouts(bookName, chapter, width)
        return title.height + gap1 + number.height + gap2
    }

    /** A centered inline heading for the flowing passage view ("John 3"). */
    fun passageHeadingLayout(text: String, width: Int): StaticLayout =
        layout(text, passageHeading, width, Layout.Alignment.ALIGN_CENTER)

    /**
     * The verse key covering character [offset] in [atoms]' rendered body — the
     * key of the most recent [NumberAtom] at or before that character. Retraces
     * exactly the offsets [spannable] lays down (a space before every atom but the
     * first, then the number/word), so a press hit-tested against the drawn
     * [StaticLayout] maps back to the verse it fell in. Null if none precedes it.
     */
    fun verseKeyAtOffset(atoms: List<Atom>, offset: Int): Int? {
        var pos = 0
        var key: Int? = null
        atoms.forEachIndexed { i, atom ->
            when (atom) {
                is NumberAtom -> {
                    if (i > 0) pos += 1 // the joining space
                    if (pos <= offset) key = atom.verseKey
                    pos += atom.number.toString().length
                }
                is WordAtom -> {
                    if (i > 0) pos += 1
                    pos += atom.word.length
                }
            }
        }
        return key
    }

    /**
     * The word (verse + word index) at character [offset] on a page. [seed] gives
     * the verse and word count carried into the page's first atom, so a verse split
     * across pages keeps counting its words. Returns the exact word hit, else the
     * nearest word starting at or before [offset] (so tapping a gap still targets a
     * word), or null before the first word.
     */
    fun wordAtOffset(atoms: List<Atom>, seed: WordRef, offset: Int): WordRef? {
        var pos = 0
        var verse = seed.verseKey
        var wordIdx = seed.wordIndex
        var nearest: WordRef? = null
        for (i in atoms.indices) {
            when (val atom = atoms[i]) {
                is NumberAtom -> {
                    if (i > 0) pos += 1
                    pos += atom.number.toString().length
                    verse = atom.verseKey
                    wordIdx = 0
                }
                is WordAtom -> {
                    if (i > 0) pos += 1
                    val start = pos
                    pos += atom.word.length
                    if (offset in start until pos) return WordRef(verse, wordIdx)
                    if (start <= offset) nearest = WordRef(verse, wordIdx)
                    wordIdx += 1
                }
            }
        }
        return nearest
    }

    /**
     * The character ranges (as `start until end`) to underline on a page, for the
     * given highlighted word spans per verse ([spansByVerse] maps a verse key to
     * its inclusive `startWord..endWord` runs). [seed] carries the verse/word count
     * into the page. Adjacent highlighted words are merged into one continuous
     * underline (the joining space included). Retraces [spannable]'s char offsets.
     */
    fun highlightRanges(
        atoms: List<Atom>,
        seed: WordRef,
        spansByVerse: Map<Int, List<IntRange>>,
    ): List<IntRange> {
        if (spansByVerse.isEmpty()) return emptyList()
        var pos = 0
        var verse = seed.verseKey
        var wordIdx = seed.wordIndex
        val words = ArrayList<IntRange>() // per highlighted word, start until end
        for (i in atoms.indices) {
            when (val atom = atoms[i]) {
                is NumberAtom -> {
                    if (i > 0) pos += 1
                    pos += atom.number.toString().length
                    verse = atom.verseKey
                    wordIdx = 0
                }
                is WordAtom -> {
                    if (i > 0) pos += 1
                    val start = pos
                    pos += atom.word.length
                    if (spansByVerse[verse]?.any { wordIdx in it } == true) {
                        words.add(start until pos)
                    }
                    wordIdx += 1
                }
            }
        }
        // Merge words separated only by their joining space into one underline.
        val merged = ArrayList<IntRange>()
        var curStart = -1
        var curEnd = -1
        for (r in words) {
            if (curStart < 0) {
                curStart = r.first; curEnd = r.last + 1
            } else if (r.first <= curEnd + 1) {
                curEnd = maxOf(curEnd, r.last + 1)
            } else {
                merged.add(curStart until curEnd)
                curStart = r.first; curEnd = r.last + 1
            }
        }
        if (curStart >= 0) merged.add(curStart until curEnd)
        return merged
    }

    companion object {
        const val BLACK = 0xFF000000.toInt()
        private const val EXCL = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        private const val INCL = Spanned.SPAN_INCLUSIVE_INCLUSIVE
    }
}
