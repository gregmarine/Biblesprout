package com.symmetricalpalmtree.biblesprout.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.biblesprout.R
import com.symmetricalpalmtree.biblesprout.data.AppServices
import com.symmetricalpalmtree.biblesprout.databinding.ActivityChaptersBinding
import com.symmetricalpalmtree.biblesprout.model.Book
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Chapter picker for a single book: a grid of chapter numbers that paginates
 * (no scrolling). Swipe or use the footer arrows to turn pages. Ported from the
 * Flutter `chapters_screen.dart`.
 */
class ChaptersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChaptersBinding
    private lateinit var book: Book

    private var cols = 1
    private var cellSize = 0
    private var pages: List<IntRange> = emptyList() // chapter-number ranges per page
    private var current = 0
    private var lastWidth = 0
    private var lastHeight = 0

    private val pad by lazy { dp(20) }
    private val spacing by lazy { dp(14) }
    private val targetCell by lazy { dp(100) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChaptersBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        binding.back.setOnClickListener { finish() }
        binding.pager.onSwipeLeft = { showPage(current + 1) }
        binding.pager.onSwipeRight = { showPage(current - 1) }
        binding.prev.setOnClickListener { showPage(current - 1) }
        binding.next.setOnClickListener { showPage(current + 1) }

        binding.pager.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w > 0 && h > 0 && (w != lastWidth || h != lastHeight)) {
                lastWidth = w
                lastHeight = h
                binding.pager.post { buildAndRender() }
            }
        }

        val bookIndex = intent.getIntExtra(EXTRA_BOOK_INDEX, 0)
        lifecycleScope.launch {
            AppServices.bootstrap(applicationContext)
            book = AppServices.bible.bookAt(bookIndex)
            binding.title.text = book.name
            binding.pager.post { buildAndRender() }
        }
    }

    private fun buildAndRender() {
        val width = binding.pager.width
        val height = binding.pager.height
        if (width <= 0 || height <= 0 || !::book.isInitialized) return

        val available = width - pad * 2
        cols = max(1, (available + spacing) / (targetCell + spacing))
        cellSize = (available - (cols - 1) * spacing) / cols
        val rowHeight = cellSize + spacing
        val rowsPerPage = max(1, (height - pad) / rowHeight)
        val perPage = cols * rowsPerPage

        val count = book.chapters.size
        val packed = ArrayList<IntRange>()
        var start = 1
        while (start <= count) {
            val end = minOf(start + perPage - 1, count)
            packed.add(start..end)
            start = end + 1
        }
        pages = packed
        showPage(current)
    }

    private fun showPage(index: Int) {
        if (pages.isEmpty()) return
        current = index.coerceIn(0, pages.size - 1)

        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val numbers = pages[current].toList()
        var i = 0
        while (i < numbers.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val cell = if (i + c < numbers.size) chapterCell(numbers[i + c]) else spacerCell()
                (cell.layoutParams as LinearLayout.LayoutParams).marginEnd =
                    if (c < cols - 1) spacing else 0
                row.addView(cell)
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = spacing },
            )
            i += cols
        }

        binding.pager.removeAllViews()
        binding.pager.addView(grid)
        binding.pageIndicator.text = getString(R.string.page_indicator, current + 1, pages.size)
        setArrow(binding.prev, current > 0)
        setArrow(binding.next, current < pages.size - 1)
    }

    private fun chapterCell(number: Int): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
        gravity = Gravity.CENTER
        text = number.toString()
        textSize = 24f
        setTextColor(ContextCompat.getColor(this@ChaptersActivity, R.color.eink_black))
        background = ContextCompat.getDrawable(this@ChaptersActivity, R.drawable.chapter_cell)
        setOnClickListener { openChapter(number) }
    }

    private fun spacerCell(): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(cellSize, cellSize)
    }

    private fun openChapter(number: Int) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_INDEX, book.index)
                .putExtra(ReaderActivity.EXTRA_CHAPTER, number),
        )
    }

    private fun setArrow(view: android.widget.ImageView, enabled: Boolean) {
        view.isEnabled = enabled
        view.setColorFilter(
            ContextCompat.getColor(this, if (enabled) R.color.eink_black else R.color.eink_disabled),
        )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun goFullScreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_BOOK_INDEX = "book_index"
    }
}
