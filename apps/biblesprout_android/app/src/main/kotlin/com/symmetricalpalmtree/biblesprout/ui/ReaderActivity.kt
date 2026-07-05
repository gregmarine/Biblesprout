package com.symmetricalpalmtree.biblesprout.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.biblesprout.MainActivity
import com.symmetricalpalmtree.biblesprout.R
import com.symmetricalpalmtree.biblesprout.data.AppServices
import com.symmetricalpalmtree.biblesprout.data.index.ReadingPosition
import com.symmetricalpalmtree.biblesprout.databinding.ActivityReaderBinding
import com.symmetricalpalmtree.biblesprout.model.ChapterRef
import com.symmetricalpalmtree.biblesprout.reader.Atom
import com.symmetricalpalmtree.biblesprout.reader.ChapterPaginator
import com.symmetricalpalmtree.biblesprout.reader.NumberAtom
import com.symmetricalpalmtree.biblesprout.reader.ReaderPage
import com.symmetricalpalmtree.biblesprout.reader.ReaderTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Full-screen paginated reader. Each chapter starts on a fresh page; tapping the
 * left/right thirds (or swiping) turns pages and flows across chapter and book
 * boundaries. The center third and the title open Contents. Every
 * [FULL_REFRESH_EVERY] turns a black flash forces the E Ink panel to full-refresh,
 * clearing ghosting. Ported from the Flutter `reader_screen.dart`.
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var typo: ReaderTypography

    private lateinit var ref: ChapterRef
    private var page = 0
    private var pages: List<List<Atom>> = emptyList()

    private var pendingLastPage = false
    private var pendingVerse = -1
    private var turnsSinceRefresh = 0
    private var paginateJob: Job? = null

    private val safetyPad by lazy { typo.dp(8f) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        typo = ReaderTypography(this)
        binding.reader.horizontalPad = typo.dp(44f)
        binding.reader.verticalPad = typo.dp(10f)
        binding.reader.gap1 = typo.gap1
        binding.reader.gap2 = typo.gap2

        ref = ChapterRef(
            intent.getIntExtra(EXTRA_BOOK_INDEX, 0),
            intent.getIntExtra(EXTRA_CHAPTER, 1),
        )
        page = intent.getIntExtra(EXTRA_START_PAGE, 0)
        pendingVerse = intent.getIntExtra(EXTRA_START_VERSE, -1)

        binding.back.setOnClickListener { finish() }
        binding.title.setOnClickListener { openContents() }
        binding.contents.setOnClickListener { openContents() }
        attachGestures()

        lifecycleScope.launch {
            AppServices.bootstrap(applicationContext)
            persist() // record the opened location immediately
            binding.reader.doOnLayout { repaginate() }
        }
    }

    // --- Navigation -----------------------------------------------------------

    private fun nextPage() {
        if (page < pages.size - 1) {
            page++
            commitTurn(repaginate = false)
        } else {
            val next = AppServices.bible.next(ref) ?: return
            ref = next
            page = 0
            commitTurn(repaginate = true)
        }
    }

    private fun prevPage() {
        if (page > 0) {
            page--
            commitTurn(repaginate = false)
        } else {
            val previous = AppServices.bible.previous(ref) ?: return
            ref = previous
            pendingLastPage = true
            commitTurn(repaginate = true)
        }
    }

    private fun commitTurn(repaginate: Boolean) {
        turnsSinceRefresh++
        val flash = turnsSinceRefresh >= FULL_REFRESH_EVERY
        if (flash) turnsSinceRefresh = 0
        if (repaginate) repaginate() else renderCurrentPage()
        persist()
        if (flash) flashFullRefresh()
    }

    private fun openContents() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun persist() {
        lifecycleScope.launch {
            AppServices.readingPosition.save(
                ReadingPosition(ref.bookIndex, ref.chapterNumber, page),
            )
        }
    }

    /** Hold a black frame briefly so the EPD registers a full refresh. */
    private fun flashFullRefresh() {
        binding.flash.visibility = android.view.View.VISIBLE
        binding.flash.postDelayed({ binding.flash.visibility = android.view.View.GONE }, 90)
    }

    // --- Pagination -----------------------------------------------------------

    private fun repaginate() {
        val width = binding.reader.readingWidth()
        val height = binding.reader.readingHeight()
        if (width <= 0 || height <= 0) return
        val book = AppServices.bible.bookAt(ref.bookIndex)
        val chapter = AppServices.bible.chapter(ref.bookIndex, ref.chapterNumber)

        paginateJob?.cancel()
        paginateJob = lifecycleScope.launch {
            val packed = withContext(Dispatchers.Default) {
                val atoms = ChapterPaginator.atomsFor(chapter, ref.bookIndex + 1)
                val headingHeight = typo.headingHeight(book.name, chapter.number, width)
                ChapterPaginator.paginate(
                    atoms = atoms,
                    typo = typo,
                    width = width,
                    firstPageHeight = height - headingHeight - safetyPad,
                    otherPageHeight = height - safetyPad,
                )
            }
            pages = packed
            if (pendingLastPage) {
                page = pages.size - 1
                pendingLastPage = false
            }
            if (pendingVerse > 0) {
                val idx = pages.indexOfFirst { atoms ->
                    atoms.any { it is NumberAtom && it.number == pendingVerse }
                }
                if (idx >= 0) page = idx
                pendingVerse = -1
            }
            page = page.coerceIn(0, pages.size - 1)
            renderCurrentPage()
        }
    }

    private fun renderCurrentPage() {
        if (pages.isEmpty()) return
        val width = binding.reader.readingWidth()
        val book = AppServices.bible.bookAt(ref.bookIndex)
        val chapter = AppServices.bible.chapter(ref.bookIndex, ref.chapterNumber)
        val body = typo.bodyLayout(pages[page], width)
        binding.reader.page = if (page == 0) {
            val (title, number) = typo.headingLayouts(book.name, chapter.number, width)
            ReaderPage(body, title, number)
        } else {
            ReaderPage(body)
        }
        binding.title.text = getString(R.string.reader_title, book.name, chapter.number)
        binding.pageIndicator.text = getString(R.string.page_indicator, page + 1, pages.size)
    }

    // --- Gestures -------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val third = binding.reader.width / 3f
                when {
                    e.x < third -> prevPage()
                    e.x > third * 2 -> nextPage()
                    else -> openContents()
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (abs(velocityX) <= abs(velocityY)) return false
                if (velocityX < 0) nextPage() else prevPage()
                return true
            }
        })
        binding.reader.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
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
        const val EXTRA_BOOK_INDEX = "book_index"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_START_PAGE = "start_page"
        const val EXTRA_START_VERSE = "start_verse"
    }
}
