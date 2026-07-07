package com.symmetricalpalmtree.biblesprout.reader

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
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

    // Poetry / list indent unit, and the prose paragraph first-line indent.
    private val indentUnit = dp(22f)
    private val paraIndent = dp(18f)

    /** The char span an atom's own glyphs occupy in a built layout. */
    private data class Mark(val atom: Atom, val start: Int, val end: Int)
    private class Built(val text: SpannableStringBuilder, val marks: List<Mark>)

    /**
     * The single source of truth for how an atom stream lays out: builds the
     * rendered [SpannableStringBuilder] and, in lockstep, the char span of every
     * word/number ([Mark]s) so hit-testing and highlighting retrace exactly what
     * was drawn. [BreakAtom]s start new lines (poetry indent / paragraph / stanza);
     * [HeadingAtom]s render as centered lines.
     */
    private fun build(atoms: List<Atom>): Built {
        val sb = SpannableStringBuilder()
        val marks = ArrayList<Mark>()
        var atLineStart = true
        var lineStart = 0
        var lineFlow = Flow.PARAGRAPH

        fun closeLine() {
            if (sb.length > lineStart) applyFlow(sb, lineStart, sb.length, lineFlow)
        }
        fun newline() {
            if (!atLineStart) {
                closeLine()
                sb.append('\n')
                atLineStart = true
                lineStart = sb.length
            }
        }

        for (atom in atoms) {
            when (atom) {
                is BreakAtom -> {
                    newline()
                    if (atom.flow == Flow.STANZA && sb.isNotEmpty()) {
                        sb.append('\n') // a blank separator line between stanzas
                        lineStart = sb.length
                    }
                    lineFlow = atom.flow
                }
                is HeadingAtom -> {
                    newline()
                    if (sb.isNotEmpty()) { sb.append('\n'); lineStart = sb.length } // gap above
                    val start = sb.length
                    sb.append(atom.text)
                    // Paragraph spans must be EXCLUSIVE at the end: an INCLUSIVE end
                    // sits at the buffer tail and would grow into every later append,
                    // bleeding the center alignment onto the whole page.
                    sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, sb.length, EXCL)
                    sb.setSpan(StyleSpan(if (atom.minor) Typeface.ITALIC else Typeface.BOLD), start, sb.length, EXCL)
                    sb.append('\n') // end the heading line; next line starts a gap-free body line
                    lineStart = sb.length
                    lineFlow = Flow.PARAGRAPH
                    atLineStart = true
                }
                is NumberAtom -> {
                    if (!atLineStart) sb.append(' ')
                    val start = sb.length
                    sb.append(atom.number.toString())
                    sb.setSpan(RelativeSizeSpan(0.62f), start, sb.length, EXCL)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, EXCL)
                    sb.setSpan(SuperscriptSpan(), start, sb.length, EXCL)
                    marks.add(Mark(atom, start, sb.length))
                    atLineStart = false
                }
                is WordAtom -> {
                    if (!atLineStart) sb.append(' ')
                    val start = sb.length
                    sb.append(atom.word)
                    marks.add(Mark(atom, start, sb.length))
                    atLineStart = false
                }
                is FootnoteAtom -> {
                    // The caller attaches to the preceding word — no leading space.
                    val start = sb.length
                    sb.append(CALLER)
                    sb.setSpan(RelativeSizeSpan(0.7f), start, sb.length, EXCL)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, EXCL)
                    sb.setSpan(SuperscriptSpan(), start, sb.length, EXCL)
                    marks.add(Mark(atom, start, sb.length))
                    atLineStart = false
                }
            }
        }
        closeLine()
        if (sb.isNotEmpty()) sb.setSpan(LineHeightSpan.Standard(lineHeightPx), 0, sb.length, INCL)
        return Built(sb, marks)
    }

    /** Applies a line's leading margin (poetry indent / paragraph first-line indent). */
    private fun applyFlow(sb: SpannableStringBuilder, start: Int, end: Int, flow: Flow) {
        val span = when (flow) {
            Flow.PARAGRAPH -> LeadingMarginSpan.Standard(paraIndent, 0)
            Flow.POETRY1 -> LeadingMarginSpan.Standard(indentUnit, indentUnit * 2)
            Flow.POETRY2 -> LeadingMarginSpan.Standard(indentUnit * 2, indentUnit * 3)
            Flow.POETRY_REFRAIN -> LeadingMarginSpan.Standard(indentUnit * 3, indentUnit * 3)
            Flow.LIST1 -> LeadingMarginSpan.Standard(indentUnit, indentUnit)
            Flow.LIST2 -> LeadingMarginSpan.Standard(indentUnit * 2, indentUnit * 2)
            Flow.STANZA -> return
        }
        // EXCLUSIVE end: an INCLUSIVE end would grow into later appends and apply
        // this line's indent to the rest of the page.
        sb.setSpan(span, start, end, EXCL)
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
        layout(build(atoms).text, body, width, Layout.Alignment.ALIGN_NORMAL)

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
     * key of the most recent [NumberAtom] at or before that character. Uses the
     * exact char [Mark]s [build] laid down, so a press hit-tested against the drawn
     * [StaticLayout] maps back to the verse it fell in. Null if none precedes it.
     */
    fun verseKeyAtOffset(atoms: List<Atom>, offset: Int): Int? {
        var key: Int? = null
        for (m in build(atoms).marks) {
            if (m.start > offset) break
            if (m.atom is NumberAtom) key = m.atom.verseKey
        }
        return key
    }

    /**
     * The id of the footnote whose caller sits at character [offset], or null. A
     * small char slop absorbs imprecise taps on the tiny superscript caller.
     */
    fun footnoteAtOffset(atoms: List<Atom>, offset: Int): Int? {
        for (m in build(atoms).marks) {
            if (m.atom is FootnoteAtom && offset in (m.start - 1)..m.end) return m.atom.id
        }
        return null
    }

    /**
     * The word (verse + word index) at character [offset] on a page. [seed] gives
     * the verse and word count carried into the page's first atom, so a verse split
     * across pages keeps counting its words. Returns the exact word hit, else the
     * nearest word starting at or before [offset] (so tapping a gap still targets a
     * word), or null before the first word.
     */
    fun wordAtOffset(atoms: List<Atom>, seed: WordRef, offset: Int): WordRef? {
        var verse = seed.verseKey
        var wordIdx = seed.wordIndex
        var nearest: WordRef? = null
        for (m in build(atoms).marks) {
            when (val atom = m.atom) {
                is NumberAtom -> { verse = atom.verseKey; wordIdx = 0 }
                is WordAtom -> {
                    if (offset in m.start until m.end) return WordRef(verse, wordIdx)
                    if (m.start <= offset) nearest = WordRef(verse, wordIdx)
                    wordIdx += 1
                }
                else -> {}
            }
        }
        return nearest
    }

    /**
     * The character ranges (as `start until end`) to underline on a page, for the
     * given highlighted word spans per verse ([spansByVerse] maps a verse key to
     * its inclusive `startWord..endWord` runs). [seed] carries the verse/word count
     * into the page. Adjacent highlighted words are merged into one continuous
     * underline (the joining space included). Uses [build]'s exact char marks.
     */
    fun highlightRanges(
        atoms: List<Atom>,
        seed: WordRef,
        spansByVerse: Map<Int, List<IntRange>>,
    ): List<IntRange> {
        if (spansByVerse.isEmpty()) return emptyList()
        var verse = seed.verseKey
        var wordIdx = seed.wordIndex
        val words = ArrayList<IntRange>() // per highlighted word, start until end
        for (m in build(atoms).marks) {
            when (val atom = m.atom) {
                is NumberAtom -> { verse = atom.verseKey; wordIdx = 0 }
                is WordAtom -> {
                    if (spansByVerse[verse]?.any { wordIdx in it } == true) {
                        words.add(m.start until m.end)
                    }
                    wordIdx += 1
                }
                else -> {}
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

        /** The footnote caller glyph rendered inline (superscript). */
        private const val CALLER = "*"
        private const val EXCL = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        private const val INCL = Spanned.SPAN_INCLUSIVE_INCLUSIVE
    }
}
