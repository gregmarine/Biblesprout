package com.symmetricalpalmtree.biblesprout.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import com.symmetricalpalmtree.biblesprout.data.ReferenceParser
import com.symmetricalpalmtree.biblesprout.data.VerseHit
import com.symmetricalpalmtree.biblesprout.databinding.ActivityFindBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * One smart input for both jumping and searching. On submit the text is parsed
 * as a scripture reference; if it resolves to real verses it opens the flowing
 * passage view, otherwise it runs a full-text search shown as a paginated list of
 * verse rows that open the reader on that verse's page. Ported from the Flutter
 * `find_screen.dart`.
 */
class FindActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFindBinding

    private var resultPages: List<List<ResultEntry>> = emptyList()
    private var current = 0
    private var lastHeight = 0

    private val headerHeight by lazy { dp(52) }
    private val rowHeight by lazy { dp(120) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFindBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        binding.back.setOnClickListener { finish() }
        binding.search.setOnClickListener { run(binding.input.text.toString()) }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                run(binding.input.text.toString())
                true
            } else {
                false
            }
        }
        binding.pager.onSwipeLeft = { showResultPage(current + 1) }
        binding.pager.onSwipeRight = { showResultPage(current - 1) }
        binding.prev.setOnClickListener { showResultPage(current - 1) }
        binding.next.setOnClickListener { showResultPage(current + 1) }
        binding.pager.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
            val h = b - t
            if (h > 0 && h != lastHeight) {
                lastHeight = h
                binding.pager.post { buildResultPages() }
            }
        }

        binding.input.requestFocus()
        binding.input.postDelayed({
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(binding.input, 0)
        }, 120)
    }

    private var results: List<VerseHit> = emptyList()
    private var submitted = ""

    private fun run(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) return
        lifecycleScope.launch {
            AppServices.bootstrap(applicationContext)

            // One or more parseable references jump to the flowing passage view;
            // anything else is a full-text search list.
            val passages = ReferenceParser.parseAll(query)
            if (passages.isNotEmpty()) {
                val hasVerses = withContext(Dispatchers.IO) {
                    passages.any { AppServices.bibleDb.versesForRanges(it.ranges).isNotEmpty() }
                }
                if (hasVerses) {
                    startActivity(
                        Intent(this@FindActivity, PassageActivity::class.java)
                            .putExtra(PassageActivity.EXTRA_QUERY, query),
                    )
                    return@launch
                }
            }

            showMessage(getString(R.string.searching))
            val found = withContext(Dispatchers.IO) { AppServices.bibleDb.search(query) }
            submitted = query
            results = found
            if (found.isEmpty()) {
                showMessage(getString(R.string.no_results, query))
            } else {
                showResults()
            }
        }
    }

    private fun showMessage(text: String) {
        binding.help.visibility = View.GONE
        binding.results.visibility = View.GONE
        binding.message.text = text
        binding.message.visibility = View.VISIBLE
    }

    private fun showResults() {
        binding.help.visibility = View.GONE
        binding.message.visibility = View.GONE
        binding.results.visibility = View.VISIBLE
        current = 0
        binding.pager.post { buildResultPages() }
    }

    private fun buildResultPages() {
        val height = binding.pager.height
        if (height <= 0 || results.isEmpty()) return
        lastHeight = height

        val entries = ArrayList<ResultEntry>()
        entries.add(ResultEntry.Header(summary(), headerHeight))
        results.forEach { entries.add(ResultEntry.Row(it, rowHeight)) }

        val packed = ArrayList<List<ResultEntry>>()
        var page = ArrayList<ResultEntry>()
        var used = 0
        for (e in entries) {
            if (page.isNotEmpty() && used + e.heightPx > height) {
                packed.add(page)
                page = ArrayList()
                used = 0
            }
            page.add(e)
            used += e.heightPx
        }
        if (page.isNotEmpty()) packed.add(page)
        resultPages = packed
        showResultPage(current)
    }

    private fun showResultPage(index: Int) {
        if (resultPages.isEmpty()) return
        current = index.coerceIn(0, resultPages.size - 1)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        for (entry in resultPages[current]) {
            column.addView(entryView(entry, column))
        }
        binding.pager.removeAllViews()
        binding.pager.addView(column)

        binding.pageIndicator.text =
            if (resultPages.size > 1) {
                getString(R.string.page_indicator, current + 1, resultPages.size)
            } else {
                ""
            }
        setArrow(binding.prev, current > 0)
        setArrow(binding.next, current < resultPages.size - 1)
    }

    private fun entryView(entry: ResultEntry, parent: LinearLayout): View = when (entry) {
        is ResultEntry.Header -> TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, entry.heightPx,
            )
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(20), 0, dp(20), dp(10))
            text = entry.text
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@FindActivity, R.color.eink_rule))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.1f
        }

        is ResultEntry.Row -> layoutInflater.inflate(R.layout.item_result, parent, false).apply {
            findViewById<TextView>(R.id.reference).text = entry.hit.reference
            findViewById<TextView>(R.id.snippet).text = entry.hit.text
            setOnClickListener { openReader(entry.hit) }
        }
    }

    private fun openReader(hit: VerseHit) {
        val book = Canon.byUsfm(hit.usfm)
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_INDEX, book.ordinal - 1)
                .putExtra(ReaderActivity.EXTRA_CHAPTER, hit.chapter)
                .putExtra(ReaderActivity.EXTRA_START_VERSE, hit.verse),
        )
    }

    private fun summary(): String =
        getString(R.string.results_header, results.size, submitted).uppercase()

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

    private sealed interface ResultEntry {
        val heightPx: Int
        data class Header(val text: String, override val heightPx: Int) : ResultEntry
        data class Row(val hit: VerseHit, override val heightPx: Int) : ResultEntry
    }
}
