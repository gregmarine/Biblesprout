package com.symmetricalpalmtree.biblesprout.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
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

/**
 * One smart input for both jumping and searching. On submit the text is parsed
 * as a scripture reference; if it resolves to real verses it opens the flowing
 * passage view, otherwise it runs a full-text search shown as a paginated list of
 * verse rows that open the reader on that verse's page. Ported from the Flutter
 * `find_screen.dart`.
 */
class FindActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFindBinding
    private lateinit var list: VerseListPager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFindBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        list = VerseListPager(binding.results) { openReader(it) }

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
        binding.results.root.visibility = View.GONE
        binding.message.text = text
        binding.message.visibility = View.VISIBLE
    }

    private fun showResults() {
        binding.help.visibility = View.GONE
        binding.message.visibility = View.GONE
        binding.results.root.visibility = View.VISIBLE
        list.submit(summary(), results)
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

    private fun goFullScreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

}
