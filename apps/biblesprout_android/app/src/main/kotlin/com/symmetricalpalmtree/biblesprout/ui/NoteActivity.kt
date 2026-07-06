package com.symmetricalpalmtree.biblesprout.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.biblesprout.R
import com.symmetricalpalmtree.biblesprout.data.AppServices
import com.symmetricalpalmtree.biblesprout.data.index.NoteNotebook
import com.symmetricalpalmtree.biblesprout.data.index.NotePage
import com.symmetricalpalmtree.biblesprout.data.index.NoteStroke
import com.symmetricalpalmtree.biblesprout.databinding.ActivityNoteBinding
import com.symmetricalpalmtree.biblesprout.notes.InkStroke
import com.symmetricalpalmtree.biblesprout.notes.NoteCanvasView
import com.symmetricalpalmtree.biblesprout.notes.StrokeCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A handwritten notebook anchored to a scripture span (chapter from the reader,
 * passage from the passage view). Full-screen pages the user flips through with a
 * simple pen and whole-stroke eraser; swiping left past the last page adds a new
 * one. Strokes are captured by the Onyx BOOX pen pipeline ([NoteCanvasView]) and
 * persisted incrementally, so there is no explicit save.
 */
class NoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteBinding

    private var startKey = 0
    private var endKey = 0
    private var notebookId = 0L
    private val pages = ArrayList<NotePage>()
    private var currentIndex = 0
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        startKey = intent.getIntExtra(EXTRA_START_KEY, 0)
        endKey = intent.getIntExtra(EXTRA_END_KEY, 0)
        binding.title.text = getString(R.string.note_title, intent.getStringExtra(EXTRA_LABEL).orEmpty())

        binding.canvas.topInsetPx = dp(56)
        binding.canvas.bottomInsetPx = dp(48)
        binding.canvas.onStrokeAdded = { persistStroke(it) }
        binding.canvas.onStrokesErased = { eraseStrokes(it) }

        binding.back.setOnClickListener { finish() }
        binding.pen.setOnClickListener { selectTool(NoteCanvasView.Tool.PEN) }
        binding.eraser.setOnClickListener { selectTool(NoteCanvasView.Tool.ERASER) }
        binding.prev.setOnClickListener { goToPage(currentIndex - 1) }
        binding.next.setOnClickListener { nextPageOrAdd() }
        selectTool(NoteCanvasView.Tool.PEN)
        attachSwipe()

        lifecycleScope.launch {
            AppServices.bootstrap(applicationContext)
            loadNotebook()
        }
    }

    // ── Notebook / pages ──────────────────────────────────────────────────────

    private suspend fun loadNotebook() {
        val dao = AppServices.index.notes()
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val existing = dao.findNotebook(startKey, endKey)
            notebookId = existing?.id ?: dao.insertNotebook(
                NoteNotebook(startKey = startKey, endKey = endKey, createdAt = now, updatedAt = now),
            )
            var loaded = dao.pages(notebookId)
            if (loaded.isEmpty()) {
                val pageId = dao.insertPage(NotePage(notebookId = notebookId, pageIndex = 0))
                loaded = listOf(NotePage(id = pageId, notebookId = notebookId, pageIndex = 0))
            }
            pages.clear()
            pages.addAll(loaded)
        }
        ready = true
        currentIndex = 0
        showPage()
    }

    private fun showPage() {
        val page = pages.getOrNull(currentIndex) ?: return
        lifecycleScope.launch {
            val strokes = withContext(Dispatchers.IO) {
                AppServices.index.notes().strokes(page.id).map { it.toInk() }
            }
            binding.canvas.setStrokes(strokes)
            binding.pageIndicator.text =
                getString(R.string.page_indicator, currentIndex + 1, pages.size)
            setArrow(binding.prev, currentIndex > 0)
            // The next arrow is always live: on the last page it adds a new page.
            setArrow(binding.next, true)
        }
    }

    private fun goToPage(index: Int) {
        if (!ready || index < 0 || index >= pages.size || index == currentIndex) return
        currentIndex = index
        showPage()
    }

    private fun nextPageOrAdd() {
        if (!ready) return
        if (currentIndex < pages.size - 1) {
            goToPage(currentIndex + 1)
        } else {
            addPage()
        }
    }

    private fun addPage() {
        lifecycleScope.launch {
            val newIndex = pages.size
            val pageId = withContext(Dispatchers.IO) {
                AppServices.index.notes()
                    .insertPage(NotePage(notebookId = notebookId, pageIndex = newIndex))
            }
            pages.add(NotePage(id = pageId, notebookId = notebookId, pageIndex = newIndex))
            currentIndex = newIndex
            showPage()
        }
    }

    // ── Persistence (incremental) ─────────────────────────────────────────────

    private fun persistStroke(stroke: InkStroke) {
        val page = pages.getOrNull(currentIndex) ?: return
        lifecycleScope.launch {
            val id = withContext(Dispatchers.IO) {
                val dao = AppServices.index.notes()
                val rowId = dao.insertStroke(
                    NoteStroke(
                        pageId = page.id,
                        points = StrokeCodec.pack(stroke.points),
                        width = stroke.width,
                        color = stroke.color,
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                dao.touchNotebook(notebookId, System.currentTimeMillis())
                rowId
            }
            stroke.id = id
        }
    }

    private fun eraseStrokes(removed: List<InkStroke>) {
        val ids = removed.mapNotNull { it.id.takeIf { id -> id > 0 } }
        if (ids.isEmpty()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppServices.index.notes().deleteStrokes(ids)
                AppServices.index.notes().touchNotebook(notebookId, System.currentTimeMillis())
            }
        }
    }

    private fun NoteStroke.toInk(): InkStroke =
        InkStroke(id = id, points = StrokeCodec.unpack(points), width = width, color = color)

    // ── Tools & gestures ──────────────────────────────────────────────────────

    private fun selectTool(tool: NoteCanvasView.Tool) {
        binding.canvas.setTool(tool)
        tintTool(binding.pen, tool == NoteCanvasView.Tool.PEN)
        tintTool(binding.eraser, tool == NoteCanvasView.Tool.ERASER)
    }

    private fun tintTool(view: ImageView, active: Boolean) {
        view.setColorFilter(
            ContextCompat.getColor(this, if (active) R.color.eink_black else R.color.eink_rule),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipe() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // Only finger flips pages; the stylus is for writing.
                if (e1 == null || e1.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) return false
                if (abs(velocityX) <= abs(velocityY)) return false
                if (velocityX < 0) nextPageOrAdd() else goToPage(currentIndex - 1)
                return true
            }
        })
        // The stylus is handled by the Onyx raw pipeline, not View touch — leave it
        // alone. Consume finger events so the detector sees the whole fling (returning
        // false would drop everything after the DOWN and no swipe would register).
        binding.canvas.setOnTouchListener { _, event ->
            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                false
            } else {
                detector.onTouchEvent(event)
                true
            }
        }
    }

    private fun setArrow(view: ImageView, enabled: Boolean) {
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

    companion object {
        private const val EXTRA_START_KEY = "start_key"
        private const val EXTRA_END_KEY = "end_key"
        private const val EXTRA_LABEL = "label"

        fun intent(context: Context, startKey: Int, endKey: Int, label: String): Intent =
            Intent(context, NoteActivity::class.java)
                .putExtra(EXTRA_START_KEY, startKey)
                .putExtra(EXTRA_END_KEY, endKey)
                .putExtra(EXTRA_LABEL, label)
    }
}
