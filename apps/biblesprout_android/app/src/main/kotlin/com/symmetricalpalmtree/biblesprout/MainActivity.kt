package com.symmetricalpalmtree.biblesprout

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.symmetricalpalmtree.biblesprout.databinding.ActivityMainBinding
import net.zetetic.database.sqlcipher.SQLiteDatabase

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        // Scaffold smoke test: prove SQLCipher's bundled SQLite offers FTS5 on this
        // device, opened plaintext (empty password). This is the whole reason the
        // native app bundles an engine instead of using the framework's SQLite.
        binding.status.text = "Biblesprout\n${fts5Check()}"
    }

    private fun fts5Check(): String = try {
        // Empty password = SQLCipher plaintext mode; matches how the read-only
        // content DBs (built unencrypted) will be opened.
        val db = SQLiteDatabase.openOrCreateDatabase(":memory:", "", null, null)
        db.execSQL("CREATE VIRTUAL TABLE t USING fts5(x)")
        db.execSQL("INSERT INTO t(x) VALUES ('in the beginning God created')")
        val c = db.rawQuery("SELECT count(*) FROM t WHERE t MATCH 'created'", null)
        val hits = if (c.moveToFirst()) c.getInt(0) else -1
        c.close()
        db.close()
        "FTS5 OK (matches: $hits)"
    } catch (e: Exception) {
        "FTS5 FAILED: ${e.message}"
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
