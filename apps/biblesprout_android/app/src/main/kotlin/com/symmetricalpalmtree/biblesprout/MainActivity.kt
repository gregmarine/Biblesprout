package com.symmetricalpalmtree.biblesprout

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.biblesprout.data.AppServices
import com.symmetricalpalmtree.biblesprout.data.index.ReadingPosition
import com.symmetricalpalmtree.biblesprout.databinding.ActivityMainBinding
import com.symmetricalpalmtree.biblesprout.model.Bible
import com.symmetricalpalmtree.biblesprout.model.Book
import com.symmetricalpalmtree.biblesprout.ui.BookmarksActivity
import com.symmetricalpalmtree.biblesprout.ui.ChaptersActivity
import com.symmetricalpalmtree.biblesprout.ui.FindActivity
import com.symmetricalpalmtree.biblesprout.ui.ReaderActivity
import kotlinx.coroutines.launch

/**
 * Home screen: the table of contents. Lists all 66 books grouped by testament
 * with a search shortcut and a "Continue reading" banner (from the reading-
 * position index). Like a physical Bible's contents the list does not scroll —
 * it paginates (swipe or the footer arrows). Ported from `library_screen.dart`.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var entries: List<Toc> = emptyList()
    private var pages: List<List<Toc>> = emptyList()
    private var current = 0
    private var lastPaginatedHeight = 0
    private var booted = false
    private var continuePosition: ReadingPosition? = null

    private val labelHeightPx by lazy { dp(54) }
    private val bookHeightPx by lazy { dp(62) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        binding.pager.onSwipeLeft = { showPage(current + 1) }
        binding.pager.onSwipeRight = { showPage(current - 1) }
        binding.prev.setOnClickListener { showPage(current - 1) }
        binding.next.setOnClickListener { showPage(current + 1) }
        binding.search.setOnClickListener {
            startActivity(Intent(this, FindActivity::class.java))
        }
        binding.bookmarks.setOnClickListener {
            startActivity(Intent(this, BookmarksActivity::class.java))
        }
        binding.continueBanner.setOnClickListener {
            continuePosition?.let { startReader(it.bookIndex, it.chapterNumber, it.page) }
        }

        // Re-paginate whenever the pager's height changes (e.g. the continue
        // banner appearing shrinks it). Deferred with post() so we mutate the
        // view tree after this layout pass, not during it (else the new rows
        // never get measured).
        binding.pager.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            if (height > 0 && height != lastPaginatedHeight) {
                lastPaginatedHeight = height
                schedulePaginate()
            }
        }

        lifecycleScope.launch {
            AppServices.bootstrap(applicationContext)
            booted = true
            binding.translation.text = AppServices.bibleDb.title
            entries = buildToc(AppServices.bible)
            applyContinueBanner() // set before the first layout so it's accounted for
            schedulePaginate()
        }
    }

    override fun onResume() {
        super.onResume()
        // Reflect a position saved while away (e.g. after the reader lands).
        if (booted) refreshContinueBanner()
    }

    /** Repaginate after the current layout pass (never mutate the tree during it). */
    private fun schedulePaginate() = binding.pager.post { paginateAndRender() }

    private fun refreshContinueBanner() {
        lifecycleScope.launch { applyContinueBanner() }
    }

    private suspend fun applyContinueBanner() {
        val pos = AppServices.readingPosition.latest()
        continuePosition = pos
        if (pos == null) {
            binding.continueBanner.visibility = View.GONE
        } else {
            val book = AppServices.bible.books[pos.bookIndex]
            binding.continueLabel.text =
                getString(R.string.continue_label, book.name, pos.chapterNumber)
            binding.continueBanner.visibility = View.VISIBLE
        }
    }

    /** Old Testament heading + books, then New Testament heading + books. */
    private fun buildToc(bible: Bible): List<Toc> = buildList {
        add(Toc.Label(getString(R.string.old_testament).uppercase(), labelHeightPx))
        bible.oldTestament.forEach { add(Toc.BookRow(it, bookHeightPx)) }
        add(Toc.Label(getString(R.string.new_testament).uppercase(), labelHeightPx))
        bible.newTestament.forEach { add(Toc.BookRow(it, bookHeightPx)) }
    }

    /**
     * Packs entries into pages that fit the pager's height. A testament label is
     * never left as the last row of a page — it is only placed if the book that
     * follows it also fits, so headings always sit above their books.
     */
    private fun paginateAndRender() {
        val height = binding.pager.height
        if (height <= 0 || entries.isEmpty()) return
        lastPaginatedHeight = height

        val packed = ArrayList<List<Toc>>()
        var page = ArrayList<Toc>()
        var used = 0
        for (i in entries.indices) {
            val e = entries[i]
            var needed = e.heightPx
            if (e is Toc.Label && i + 1 < entries.size) {
                needed += entries[i + 1].heightPx // keep heading with its first book
            }
            if (page.isNotEmpty() && used + needed > height) {
                packed.add(page)
                page = ArrayList()
                used = 0
            }
            page.add(e)
            used += e.heightPx
        }
        if (page.isNotEmpty()) packed.add(page)

        pages = packed
        showPage(current)
    }

    private fun showPage(index: Int) {
        if (pages.isEmpty()) return
        current = index.coerceIn(0, pages.size - 1)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        for (entry in pages[current]) {
            column.addView(rowView(entry, column))
        }
        binding.pager.removeAllViews()
        binding.pager.addView(column)

        binding.pageIndicator.text = getString(R.string.page_indicator, current + 1, pages.size)
        setArrow(binding.prev, current > 0)
        setArrow(binding.next, current < pages.size - 1)
    }

    private fun rowView(entry: Toc, parent: LinearLayout) = when (entry) {
        is Toc.Label -> (layoutInflater.inflate(R.layout.item_testament, parent, false) as TextView)
            .apply { text = entry.text }

        is Toc.BookRow -> layoutInflater.inflate(R.layout.item_book, parent, false).apply {
            findViewById<TextView>(R.id.bookName).text = entry.book.name
            findViewById<TextView>(R.id.chapterCount).text =
                getString(R.string.chapter_count, entry.book.chapters.size)
            setOnClickListener { openBook(entry.book) }
        }
    }

    private fun openBook(book: Book) {
        startActivity(
            Intent(this, ChaptersActivity::class.java)
                .putExtra(ChaptersActivity.EXTRA_BOOK_INDEX, book.index),
        )
    }

    private fun startReader(bookIndex: Int, chapter: Int, page: Int) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_INDEX, bookIndex)
                .putExtra(ReaderActivity.EXTRA_CHAPTER, chapter)
                .putExtra(ReaderActivity.EXTRA_START_PAGE, page),
        )
    }

    private fun setArrow(view: android.widget.ImageView, enabled: Boolean) {
        view.isEnabled = enabled
        view.setColorFilter(
            ContextCompat.getColor(this, if (enabled) R.color.eink_black else R.color.eink_disabled),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun goFullScreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** One row of the table of contents: a testament heading or a book. */
    private sealed interface Toc {
        val heightPx: Int
        data class Label(val text: String, override val heightPx: Int) : Toc
        data class BookRow(val book: Book, override val heightPx: Int) : Toc
    }
}
