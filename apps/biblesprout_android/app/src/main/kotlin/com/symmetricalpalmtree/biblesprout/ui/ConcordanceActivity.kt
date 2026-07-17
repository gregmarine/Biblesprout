package com.symmetricalpalmtree.biblesprout.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.biblesprout.R
import com.symmetricalpalmtree.biblesprout.data.AppServices
import com.symmetricalpalmtree.biblesprout.data.Canon
import com.symmetricalpalmtree.biblesprout.data.VerseHit
import com.symmetricalpalmtree.biblesprout.databinding.ActivityConcordanceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The reverse concordance: every verse whose original text uses one Strong's
 * number, reached by tapping that number in the word-study panel. Rows open the
 * reader on that verse's page, exactly as a search result does.
 *
 * A verse using the word twice appears once. The list is capped (see
 * [com.symmetricalpalmtree.biblesprout.data.BibleDatabase.concordance]) — a few
 * numbers run to thousands of verses — and the header says so when it is.
 */
class ConcordanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConcordanceBinding
    private lateinit var list: VerseListPager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConcordanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        list = VerseListPager(binding.results) { openReader(it) }
        binding.back.setOnClickListener { finish() }

        val strongs = intent.getStringExtra(EXTRA_STRONGS).orEmpty()
        val lemma = intent.getStringExtra(EXTRA_LEMMA)
        // The headword names the word better than its number does; keep the number
        // too, since that is what the reader tapped to get here. The headword can be
        // Hebrew, so isolate it (the view is pinned LTR in the layout).
        binding.title.text = if (lemma.isNullOrBlank()) {
            strongs
        } else {
            "${isolateRtl(lemma)} · $strongs"
        }

        if (strongs.isBlank()) {
            showMessage(getString(R.string.concordance_none, ""))
            return
        }

        lifecycleScope.launch {
            AppServices.bootstrap(applicationContext)
            showMessage(getString(R.string.searching))
            val found = withContext(Dispatchers.IO) { AppServices.bibleDb.concordance(strongs) }
            if (found.hits.isEmpty()) {
                showMessage(getString(R.string.concordance_none, strongs))
                return@launch
            }
            binding.message.visibility = View.GONE
            binding.results.root.visibility = View.VISIBLE
            val header = if (found.isCapped) {
                getString(R.string.concordance_capped, found.hits.size, found.totalVerses)
            } else {
                resources.getQuantityString(
                    R.plurals.concordance_verses, found.totalVerses, found.totalVerses,
                )
            }
            list.submit(header.uppercase(), found.hits)
        }
    }

    private fun showMessage(text: String) {
        binding.results.root.visibility = View.GONE
        binding.message.text = text
        binding.message.visibility = View.VISIBLE
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

    private fun goFullScreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val EXTRA_STRONGS = "strongs"
        private const val EXTRA_LEMMA = "lemma"

        /** Opens the concordance for a Strong's number; [lemma] titles the screen. */
        fun intent(context: Context, strongs: String, lemma: String?): Intent =
            Intent(context, ConcordanceActivity::class.java)
                .putExtra(EXTRA_STRONGS, strongs)
                .putExtra(EXTRA_LEMMA, lemma)
    }
}
