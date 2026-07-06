package com.symmetricalpalmtree.biblesprout.ui

import android.annotation.SuppressLint
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
import com.symmetricalpalmtree.biblesprout.data.Canon
import com.symmetricalpalmtree.biblesprout.data.Passage
import com.symmetricalpalmtree.biblesprout.data.ReferenceParser
import com.symmetricalpalmtree.biblesprout.data.VerseHit
import com.symmetricalpalmtree.biblesprout.data.VerseKey
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

        binding.notes.setOnClickListener { openPassageCommentary() }
        binding.notebook.setOnClickListener { openPassageNotebook() }

        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty()
        lifecycleScope.launch {
            AppServices.bootstrap(applicationContext)
            passages = ReferenceParser.parseAll(query)
            binding.title.text = passages.joinToString("; ") { it.format() }
            binding.notes.visibility =
                if (AppServices.commentaries.isNotEmpty()) View.VISIBLE else View.GONE
            val verses = withContext(Dispatchers.IO) {
                passages.flatMap { AppServices.bibleDb.versesForRanges(it.ranges) }
            }
            blocks = buildBlocks(verses)
            binding.flow.doOnLayout { repaginate() }
        }
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

    /** Opens commentary anchored to the verse under a long-press, if any. */
    private fun openVerseCommentary(x: Float, y: Float) {
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
                val item = items.getOrNull(i)
                if (item is TextItem) {
                    val line = element.layout.getLineForVertical(localY - top)
                    val offset = element.layout.getOffsetForHorizontal(line, localX.toFloat())
                    typo.verseKeyAtOffset(item.atoms, offset)?.let {
                        CommentaryLauncher.openVerse(this, it)
                    }
                }
                return
            }
            top += h + element.bottomPad
        }
    }

    /** Groups verses into heading + text blocks, a new heading per book/chapter. */
    private fun buildBlocks(verses: List<VerseHit>): List<PassageItem> {
        val blocks = ArrayList<PassageItem>()
        var currentKey: String? = null
        var atoms = ArrayList<Atom>()
        fun flush() {
            if (atoms.isNotEmpty()) {
                blocks.add(TextItem(atoms))
                atoms = ArrayList()
            }
        }
        for (v in verses) {
            val key = "${v.usfm}.${v.chapter}"
            if (key != currentKey) {
                flush()
                blocks.add(HeadingItem("${Canon.byUsfm(v.usfm).name} ${v.chapter}"))
                currentKey = key
            }
            val ordinal = Canon.byUsfm(v.usfm).ordinal
            atoms.add(NumberAtom(v.verse, VerseKey.encode(ordinal, v.chapter, v.verse)))
            for (word in v.text.split(Regex("\\s+"))) {
                if (word.isNotEmpty()) atoms.add(WordAtom(word))
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
                openVerseCommentary(e.x, e.y)
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
    }
}
