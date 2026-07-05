package com.symmetricalpalmtree.biblesprout

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.biblesprout.data.BibleDatabase
import com.symmetricalpalmtree.biblesprout.data.ContentInstaller
import com.symmetricalpalmtree.biblesprout.data.VerseKey
import com.symmetricalpalmtree.biblesprout.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        // Data-layer smoke test: install the bundled bsb.bible, open it plaintext
        // through SQLCipher, and exercise a range lookup + FTS5 search off the
        // main thread — the read path the reader and Find screen will use.
        binding.status.text = "Biblesprout\nLoading…"
        lifecycleScope.launch {
            binding.status.text = withContext(Dispatchers.IO) { runBibleSmokeTest() }
        }
    }

    private fun runBibleSmokeTest(): String = try {
        val file = ContentInstaller(this).ensureInstalled("content/bsb.bible", "bsb.bible")
        val bible = BibleDatabase.openFile(file.absolutePath)
        try {
            val jhn316 = VerseKey.encode(43, 3, 16)
            val verse = bible.versesInRange(jhn316, jhn316).firstOrNull()
            val faithHits = bible.search("faith").size
            buildString {
                append(bible.title).append('\n')
                append(verse?.reference).append(' ')
                append(verse?.text?.take(48)).append("…\n")
                append("search \"faith\": ").append(faithHits).append(" hits")
            }
        } finally {
            bible.close()
        }
    } catch (e: Exception) {
        "Data layer FAILED:\n${e.message}"
    }

    private fun goFullScreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
