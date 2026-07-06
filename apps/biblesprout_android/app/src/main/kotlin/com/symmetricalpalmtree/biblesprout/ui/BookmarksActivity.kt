package com.symmetricalpalmtree.biblesprout.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
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
import com.symmetricalpalmtree.biblesprout.data.Canon
import com.symmetricalpalmtree.biblesprout.data.VerseKey
import com.symmetricalpalmtree.biblesprout.data.VerseRange
import com.symmetricalpalmtree.biblesprout.data.index.Bookmark
import com.symmetricalpalmtree.biblesprout.databinding.ActivityBookmarksBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The saved bookmarks, in canonical reading order, as a paginated list of verse
 * rows (reference + snippet). Tapping a row opens the reader on that verse's page;
 * the trailing ✕ removes the bookmark. Reached from the library header's ribbon
 * icon. A new native screen — the Flutter app only ever had the empty schema.
 */
class BookmarksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarksBinding

    private var rows: List<Row> = emptyList()
    private var pages: List<List<Row>> = emptyList()
    private var current = 0
    private var lastHeight = 0

    private val rowHeight by lazy { dp(112) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        binding.back.setOnClickListener { finish() }
        binding.pager.onSwipeLeft = { showPage(current + 1) }
        binding.pager.onSwipeRight = { showPage(current - 1) }
        binding.prev.setOnClickListener { showPage(current - 1) }
        binding.next.setOnClickListener { showPage(current + 1) }
        binding.pager.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
            val h = b - t
            if (h > 0 && h != lastHeight) {
                lastHeight = h
                binding.pager.post { buildPages() }
            }
        }

        lifecycleScope.launch {
            AppServices.bootstrap(applicationContext)
            load()
        }
    }

    /** Loads bookmarks and their verse text, then repaginates. */
    private suspend fun load() {
        val loaded = withContext(Dispatchers.IO) {
            val bookmarks = AppServices.index.bookmarks().all()
            val hits = AppServices.bibleDb
                .versesForRanges(bookmarks.map { VerseRange(it.verseKey, it.verseKey) })
                .associateBy { it.verseKey }
            bookmarks.map { bm ->
                val hit = hits[bm.verseKey]
                Row(bm, hit?.reference ?: labelFor(bm.verseKey), hit?.text.orEmpty())
            }
        }
        rows = loaded
        current = current.coerceAtMost(maxOf(0, loaded.size - 1))
        if (rows.isEmpty()) {
            binding.empty.visibility = View.VISIBLE
            binding.pager.visibility = View.GONE
            binding.footer.visibility = View.GONE
        } else {
            binding.empty.visibility = View.GONE
            binding.pager.visibility = View.VISIBLE
            binding.footer.visibility = View.VISIBLE
            binding.pager.post { buildPages() }
        }
    }

    private fun labelFor(key: Int): String {
        val book = Canon.byOrdinal(VerseKey.ordinalOf(key))
        return "${book.name} ${VerseKey.chapterOf(key)}:${VerseKey.verseOf(key)}"
    }

    private fun buildPages() {
        val height = binding.pager.height
        if (height <= 0 || rows.isEmpty()) return
        lastHeight = height
        val perPage = (height / rowHeight).coerceAtLeast(1)
        pages = rows.chunked(perPage)
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
        for (row in pages[current]) column.addView(rowView(row, column))
        binding.pager.removeAllViews()
        binding.pager.addView(column)

        binding.pageIndicator.text =
            if (pages.size > 1) getString(R.string.page_indicator, current + 1, pages.size) else ""
        setArrow(binding.prev, current > 0)
        setArrow(binding.next, current < pages.size - 1)
    }

    private fun rowView(row: Row, parent: LinearLayout): View =
        layoutInflater.inflate(R.layout.item_bookmark, parent, false).apply {
            findViewById<TextView>(R.id.reference).text = row.reference
            findViewById<TextView>(R.id.snippet).text = row.snippet
            findViewById<View>(R.id.openArea).setOnClickListener { openReader(row.bookmark) }
            findViewById<ImageView>(R.id.delete).setOnClickListener { remove(row.bookmark) }
        }

    private fun openReader(bookmark: Bookmark) {
        val key = bookmark.verseKey
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_INDEX, VerseKey.ordinalOf(key) - 1)
                .putExtra(ReaderActivity.EXTRA_CHAPTER, VerseKey.chapterOf(key))
                .putExtra(ReaderActivity.EXTRA_START_VERSE, VerseKey.verseOf(key)),
        )
    }

    private fun remove(bookmark: Bookmark) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { AppServices.index.bookmarks().remove(bookmark) }
            load()
        }
    }

    private fun setArrow(view: ImageView, enabled: Boolean) {
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

    private data class Row(val bookmark: Bookmark, val reference: String, val snippet: String)
}
