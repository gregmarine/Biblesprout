package com.symmetricalpalmtree.biblesprout.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.biblesprout.R
import com.symmetricalpalmtree.biblesprout.data.AppServices
import com.symmetricalpalmtree.biblesprout.data.BibleWord
import com.symmetricalpalmtree.biblesprout.data.Canon
import com.symmetricalpalmtree.biblesprout.data.Passage
import com.symmetricalpalmtree.biblesprout.data.ReferenceParser
import com.symmetricalpalmtree.biblesprout.data.VerseKey
import com.symmetricalpalmtree.biblesprout.data.VerseRange
import com.symmetricalpalmtree.biblesprout.data.VerseSlice
import com.symmetricalpalmtree.biblesprout.databinding.ActivityPassageBinding
import com.symmetricalpalmtree.biblesprout.reader.Atom
import com.symmetricalpalmtree.biblesprout.reader.CommentaryLauncher
import com.symmetricalpalmtree.biblesprout.reader.FlowElement
import com.symmetricalpalmtree.biblesprout.reader.HeadingItem
import com.symmetricalpalmtree.biblesprout.reader.NumberAtom
import com.symmetricalpalmtree.biblesprout.reader.PassageItem
import com.symmetricalpalmtree.biblesprout.reader.PassagePaginator
import com.symmetricalpalmtree.biblesprout.reader.ReaderTypography
import com.symmetricalpalmtree.biblesprout.reader.TextItem
import com.symmetricalpalmtree.biblesprout.reader.WordAtom
import com.symmetricalpalmtree.biblesprout.reader.WordPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A jump-to-passage view: the selected verses rendered as flowing, paginated
 * text — like the chapter reader, not a list. A "John 3" heading sits inline, a
 * fresh heading appears where the passage crosses a chapter/book, and long
 * passages paginate. Ported from the Flutter `passage_screen.dart` +
 * `flowing_document.dart`. Given the raw reference text, it re-resolves the
 * verses so nothing large is passed between screens.
 */
class PassageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPassageBinding
    private lateinit var typo: ReaderTypography

    private var passages: List<Passage> = emptyList()
    private var blocks: List<PassageItem> = emptyList()
    private var pages: List<List<PassageItem>> = emptyList()
    private var page = 0
    private var turnsSinceRefresh = 0
    private var paginateJob: Job? = null

    // The current page's drawn elements, parallel to pages[page], for hit-testing.
    private var currentElements: List<FlowElement> = emptyList()

    // The passage's original-language words grouped by block id, so a long-press
    // resolves to the Hebrew/Greek behind the pressed English (as in the reader).
    private var originalsByBlock: Map<Int, List<BibleWord>> = emptyMap()

    private val safetyPad by lazy { typo.dp(8f) }
    private val headingTopPad by lazy { typo.dp(18f) }
    private val headingBottomPad by lazy { typo.dp(14f) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPassageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        typo = ReaderTypography(this)
        binding.flow.horizontalPad = typo.dp(44f)
        binding.flow.verticalPad = typo.dp(10f)

        binding.back.setOnClickListener { finish() }
        attachGestures()

        binding.chapter.setOnClickListener { openChapter() }
        binding.notes.setOnClickListener { openPassageCommentary() }
        binding.notebook.setOnClickListener { openPassageNotebook() }

        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        val startKey = intent.getIntExtra(EXTRA_START_KEY, -1)
        val endKey = intent.getIntExtra(EXTRA_END_KEY, -1)
        lifecycleScope.launch {
            AppServices.bootstrap(applicationContext)
            // Launched either from a typed reference (a query to parse) or a resolved
            // verse-key range (a tapped cross-reference).
            passages = if (startKey > 0) {
                listOf(passageFromRange(startKey, if (endKey > 0) endKey else startKey))
            } else {
                ReferenceParser.parseAll(query)
            }
            binding.title.text = passages.joinToString("; ") { it.format() }
            binding.notes.visibility =
                if (AppServices.commentaries.isNotEmpty()) View.VISIBLE else View.GONE
            val slices = withContext(Dispatchers.IO) {
                passages.flatMap { p ->
                    p.ranges.flatMap { AppServices.bibleDb.verseSlicesForRange(it.startKey, it.endKey) }
                }
            }
            originalsByBlock = withContext(Dispatchers.IO) {
                passages.flatMap { p ->
                    p.ranges.flatMap { AppServices.bibleDb.wordsForRange(it.startKey, it.endKey) }
                }.groupBy { it.blockId }
            }
            blocks = buildBlocks(slices)
            binding.flow.doOnLayout { repaginate() }
        }
    }

    /** A single-range passage from a resolved verse-key span (a tapped cross-reference). */
    private fun passageFromRange(startKey: Int, endKey: Int): Passage {
        val book = Canon.byOrdinal(VerseKey.ordinalOf(startKey))
        return Passage(book, listOf(VerseRange(startKey, endKey)))
    }

    /**
     * Opens the full chapter in the reader, landing on the page where this passage
     * begins (its start verse). For a multi-reference view, the first passage wins.
     */
    private fun openChapter() {
        val key = passages.firstOrNull()?.startKey ?: return
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_INDEX, VerseKey.ordinalOf(key) - 1)
                .putExtra(ReaderActivity.EXTRA_CHAPTER, VerseKey.chapterOf(key))
                .putExtra(ReaderActivity.EXTRA_START_VERSE, VerseKey.verseOf(key)),
        )
    }

    /** Opens commentary over the passage's whole (possibly multi-book) span. */
    private fun openPassageCommentary() {
        val ranges = passages.flatMap { it.ranges }
        if (ranges.isEmpty()) return
        CommentaryLauncher.open(this, ranges, binding.title.text.toString())
    }

    /** Opens the handwritten notebook for this passage (its overall key span). */
    private fun openPassageNotebook() {
        if (passages.isEmpty()) return
        val lo = passages.minOf { it.startKey }
        val hi = passages.maxOf { it.endKey }
        startActivity(NoteActivity.intent(this, lo, hi, binding.title.text.toString()))
    }

    /**
     * Opens word study for the word under a long-press — the same gesture, panel and
     * "Commentary on this verse" action as the reader. Does nothing when the press
     * lands on a heading, a verse number or whitespace.
     */
    private fun openWordStudy(x: Float, y: Float) {
        if (pages.isEmpty()) return
        val items = pages[page]
        val localX = (x - binding.flow.horizontalPad).toInt()
        val localY = (y - binding.flow.verticalPad).toInt()
        var top = 0
        for (i in currentElements.indices) {
            val element = currentElements[i]
            top += element.topPad
            val h = element.layout.height
            if (localY in top..(top + h)) {
                val item = items.getOrNull(i) as? TextItem ?: return
                val line = element.layout.getLineForVertical(localY - top)
                val offset = element.layout.getOffsetForHorizontal(line, localX.toFloat())
                val atom = typo.wordAtomAtOffset(item.atoms, offset) ?: return
                if (!atom.hasSource) return
                val word = originalsByBlock[atom.blockId]
                    ?.firstOrNull { atom.blockStart >= it.start && atom.blockStart < it.end }
                    ?: return
                showWordStudy(word)
                return
            }
            top += h + element.bottomPad
        }
    }

    private fun showWordStudy(word: BibleWord) {
        lifecycleScope.launch {
            val entry = word.strongs?.let { s ->
                withContext(Dispatchers.IO) { AppServices.lexicon?.entryFor(s) }
            }
            WordPopup.show(
                activity = this@PassageActivity,
                word = word,
                entry = entry,
                onCommentary = if (AppServices.commentaries.isEmpty()) {
                    null
                } else {
                    { CommentaryLauncher.openVerse(this@PassageActivity, word.verseKey) }
                },
            )
        }
    }

    /**
     * Groups verses into heading + text blocks, a new heading per book/chapter.
     *
     * Words are tokenized out of each verse's block [VerseSpan]s rather than out of
     * flat text, so every [WordAtom] carries the block address a long-press needs to
     * reach the word layer. The rendering is unchanged: the passage view still flows
     * as prose (no poetry indent), and a span's text is the same text `verse.text`
     * holds.
     */
    private fun buildBlocks(slices: List<VerseSlice>): List<PassageItem> {
        val blocks = ArrayList<PassageItem>()
        var currentKey: String? = null
        var atoms = ArrayList<Atom>()
        fun flush() {
            if (atoms.isNotEmpty()) {
                blocks.add(TextItem(atoms))
                atoms = ArrayList()
            }
        }
        for (slice in slices) {
            val ordinal = VerseKey.ordinalOf(slice.verseKey)
            val book = Canon.byOrdinal(ordinal)
            val chapter = VerseKey.chapterOf(slice.verseKey)
            val key = "${book.usfm}.$chapter"
            if (key != currentKey) {
                flush()
                blocks.add(HeadingItem("${book.name} $chapter"))
                currentKey = key
            }
            atoms.add(NumberAtom(VerseKey.verseOf(slice.verseKey), slice.verseKey))
            for (span in slice.spans) {
                var i = 0
                val text = span.text
                while (i < text.length) {
                    if (text[i] == ' ') { i++; continue }
                    var j = i
                    while (j < text.length && text[j] != ' ') j++
                    atoms.add(
                        WordAtom(
                            word = text.substring(i, j),
                            blockId = span.blockId,
                            blockStart = span.start + i,
                            blockEnd = span.start + j,
                        ),
                    )
                    i = j
                }
            }
        }
        flush()
        return blocks
    }

    private fun repaginate() {
        val width = binding.flow.readingWidth()
        val height = binding.flow.readingHeight()
        if (width <= 0 || height <= 0) return
        paginateJob?.cancel()
        paginateJob = lifecycleScope.launch {
            val packed = withContext(Dispatchers.Default) {
                PassagePaginator.paginate(
                    blocks = blocks,
                    typo = typo,
                    width = width,
                    pageHeight = height - safetyPad,
                    headingTopPad = headingTopPad,
                    headingBottomPad = headingBottomPad,
                )
            }
            pages = packed
            page = page.coerceIn(0, pages.size - 1)
            renderCurrentPage()
        }
    }

    private fun renderCurrentPage() {
        if (pages.isEmpty()) return
        val width = binding.flow.readingWidth()
        currentElements = pages[page].map { item ->
            when (item) {
                is HeadingItem -> FlowElement(
                    typo.passageHeadingLayout(item.text, width),
                    headingTopPad,
                    headingBottomPad,
                )
                is TextItem -> FlowElement(typo.bodyLayout(item.atoms, width))
            }
        }
        binding.flow.elements = currentElements
        binding.pageIndicator.text =
            if (pages.size > 1) getString(R.string.page_indicator, page + 1, pages.size) else ""
    }

    private fun turn(delta: Int) {
        val target = page + delta
        if (target < 0 || target >= pages.size) return
        page = target
        turnsSinceRefresh++
        if (turnsSinceRefresh >= FULL_REFRESH_EVERY) {
            turnsSinceRefresh = 0
            flashFullRefresh()
        }
        renderCurrentPage()
    }

    private fun flashFullRefresh() {
        binding.flash.visibility = View.VISIBLE
        binding.flash.postDelayed({ binding.flash.visibility = View.GONE }, 90)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val third = binding.flow.width / 3f
                when {
                    e.x < third -> turn(-1)
                    e.x > third * 2 -> turn(1)
                    else -> finish()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                openWordStudy(e.x, e.y)
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (abs(velocityX) <= abs(velocityY)) return false
                if (velocityX < 0) turn(1) else turn(-1)
                return true
            }
        })
        binding.flow.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun goFullScreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val FULL_REFRESH_EVERY = 6
        const val EXTRA_QUERY = "query"
        const val EXTRA_START_KEY = "start_key"
        const val EXTRA_END_KEY = "end_key"

        /** Opens the passage view over a resolved verse-key range (e.g. a cross-reference). */
        fun intent(context: Context, startKey: Int, endKey: Int): Intent =
            Intent(context, PassageActivity::class.java)
                .putExtra(EXTRA_START_KEY, startKey)
                .putExtra(EXTRA_END_KEY, endKey)
    }
}
