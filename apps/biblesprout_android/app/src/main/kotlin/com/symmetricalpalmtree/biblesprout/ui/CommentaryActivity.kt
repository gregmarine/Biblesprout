package com.symmetricalpalmtree.biblesprout.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Layout
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.biblesprout.R
import com.symmetricalpalmtree.biblesprout.data.AppServices
import com.symmetricalpalmtree.biblesprout.data.CommentaryDatabase
import com.symmetricalpalmtree.biblesprout.data.CommentaryEntry
import com.symmetricalpalmtree.biblesprout.data.CommentaryXref
import com.symmetricalpalmtree.biblesprout.data.VerseRange
import com.symmetricalpalmtree.biblesprout.databinding.ActivityCommentaryBinding
import com.symmetricalpalmtree.biblesprout.reader.Atom
import com.symmetricalpalmtree.biblesprout.reader.CommentaryLauncher
import com.symmetricalpalmtree.biblesprout.reader.FlowElement
import com.symmetricalpalmtree.biblesprout.reader.HeadingItem
import com.symmetricalpalmtree.biblesprout.reader.PassageItem
import com.symmetricalpalmtree.biblesprout.reader.PassagePaginator
import com.symmetricalpalmtree.biblesprout.reader.ReaderTypography
import com.symmetricalpalmtree.biblesprout.reader.TextItem
import com.symmetricalpalmtree.biblesprout.reader.WordAtom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * A commentary rendered as flowing, paginated text — the same reading surface as
 * the passage view. Each comment's section heading (Matthew Henry's "Verses 1–8")
 * introduces its prose; whole-chapter comments flow with no heading. Given the
 * chosen commentary's id and the verse-key ranges, it re-resolves the entries so
 * nothing large is passed between screens. Ported from the Flutter
 * `commentary_screen.dart`.
 */
class CommentaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommentaryBinding
    private lateinit var typo: ReaderTypography

    private lateinit var ranges: List<VerseRange>
    private lateinit var reference: String
    private var allowChange = false

    private var db: CommentaryDatabase? = null
    private var blocks: List<PassageItem> = emptyList()
    private var pages: List<List<PassageItem>> = emptyList()
    private var page = 0
    private var turnsSinceRefresh = 0
    private var paginateJob: Job? = null
    private var hits: List<Hit> = emptyList()

    private val safetyPad by lazy { typo.dp(8f) }
    private val headingTopPad by lazy { typo.dp(18f) }
    private val headingBottomPad by lazy { typo.dp(14f) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommentaryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        typo = ReaderTypography(this)
        binding.flow.horizontalPad = typo.dp(44f)
        binding.flow.verticalPad = typo.dp(10f)

        ranges = readRanges(intent.getIntArrayExtra(EXTRA_RANGES) ?: IntArray(0))
        reference = intent.getStringExtra(EXTRA_REFERENCE).orEmpty()
        allowChange = intent.getBooleanExtra(EXTRA_ALLOW_CHANGE, false)
        val startId = intent.getStringExtra(EXTRA_COMMENTARY_ID)

        binding.back.setOnClickListener { finish() }
        binding.change.visibility = if (allowChange) View.VISIBLE else View.GONE
        binding.change.setOnClickListener { changeCommentary() }
        attachGestures()

        lifecycleScope.launch {
            AppServices.bootstrap(applicationContext)
            loadCommentary(startId)
        }
    }

    /** Resolves the commentary by id, gathers its entries, and lays them out. */
    private suspend fun loadCommentary(id: String?) {
        val chosen = AppServices.commentaries.firstOrNull { it.id == id }
            ?: AppServices.commentaries.firstOrNull()
            ?: run { finish(); return }
        db = chosen

        val entries = withContext(Dispatchers.IO) { gather(chosen) }
        val xrefs = withContext(Dispatchers.IO) {
            chosen.xrefsForEntries(entries.map { it.id })
        }
        blocks = buildBlocks(entries, xrefs)
        page = 0

        val abbr = chosen.metadata["abbreviation"] ?: getString(R.string.notes)
        binding.title.text = getString(R.string.commentary_title, abbr, reference)
        // doOnLayout runs now if already laid out (e.g. after a "Change"), else next pass.
        binding.flow.doOnLayout { repaginate() }
    }

    /** Entries over every range, in order, de-duplicated (a block spanning two
     *  adjacent ranges appears once). Mirrors the Flutter launcher's gathering. */
    private fun gather(source: CommentaryDatabase): List<CommentaryEntry> {
        val seen = HashSet<Int>()
        val out = ArrayList<CommentaryEntry>()
        for (r in ranges) {
            for (entry in source.entriesForRange(r.startKey, r.endKey)) {
                if (seen.add(entry.id)) out.add(entry)
            }
        }
        return out
    }

    /**
     * A heading (the comment's own section title) then its prose as word atoms.
     * A word overlapping one of the entry's [CommentaryXref] spans carries that
     * reference's target range, so it renders as a tappable link.
     */
    private fun buildBlocks(
        entries: List<CommentaryEntry>,
        xrefsByEntry: Map<Int, List<CommentaryXref>>,
    ): List<PassageItem> {
        val blocks = ArrayList<PassageItem>()
        for (entry in entries) {
            entry.heading?.let { blocks.add(HeadingItem(it)) }
            val xrefs = xrefsByEntry[entry.id].orEmpty()
            val atoms = ArrayList<Atom>()
            // Split into words keeping each word's offset in body, so a word can be
            // matched against the reference spans (which are body char offsets).
            for (m in NONSPACE.findAll(entry.body)) {
                val ws = m.range.first
                val we = m.range.last + 1
                val link = xrefs.firstOrNull { it.start < we && it.end > ws }
                atoms.add(
                    if (link != null) WordAtom(m.value, link.targetStartKey, link.targetEndKey)
                    else WordAtom(m.value),
                )
            }
            if (atoms.isNotEmpty()) blocks.add(TextItem(atoms))
        }
        return blocks
    }

    /** Re-opens the picker and, if a commentary is chosen, reloads with it. */
    private fun changeCommentary() {
        lifecycleScope.launch {
            val chosen = CommentaryLauncher.pick(this@CommentaryActivity, AppServices.commentaries)
                ?: return@launch
            AppServices.commentaryPrefs.remember(chosen.id)
            loadCommentary(chosen.id)
        }
    }

    private fun repaginate() {
        val width = binding.flow.readingWidth()
        val height = binding.flow.readingHeight()
        if (width <= 0 || height <= 0) return
        paginateJob?.cancel()
        paginateJob = lifecycleScope.launch {
            val packed = withContext(Dispatchers.Default) {
                PassagePaginator.paginate(
                    blocks = blocks,
                    typo = typo,
                    width = width,
                    pageHeight = height - safetyPad,
                    headingTopPad = headingTopPad,
                    headingBottomPad = headingBottomPad,
                )
            }
            pages = packed
            page = page.coerceIn(0, pages.size - 1)
            renderCurrentPage()
        }
    }

    private fun renderCurrentPage() {
        if (pages.isEmpty()) return
        val width = binding.flow.readingWidth()
        val els = ArrayList<FlowElement>()
        val hitList = ArrayList<Hit>()
        var y = 0
        for (item in pages[page]) {
            val fe: FlowElement
            val atoms: List<Atom>?
            when (item) {
                is HeadingItem -> {
                    fe = FlowElement(
                        typo.passageHeadingLayout(item.text, width),
                        headingTopPad,
                        headingBottomPad,
                    )
                    atoms = null
                }
                is TextItem -> {
                    fe = FlowElement(typo.bodyLayout(item.atoms, width))
                    atoms = item.atoms
                }
            }
            // Mirror FlowingView's stacking so a tap's y maps back to an element.
            y += fe.topPad
            hitList.add(Hit(y, fe.layout, atoms))
            y += fe.layout.height + fe.bottomPad
            els.add(fe)
        }
        binding.flow.elements = els
        hits = hitList
        binding.pageIndicator.text =
            if (pages.size > 1) getString(R.string.page_indicator, page + 1, pages.size) else ""
    }

    /** One stacked element's vertical placement on the page + its atoms (null for a
     *  heading), so a tap can be hit-tested to a cross-reference. */
    private class Hit(val top: Int, val layout: Layout, val atoms: List<Atom>?)

    /** The cross-reference target range tapped at (x, y) in the flow view, if any. */
    private fun xrefAt(x: Float, y: Float): Pair<Int, Int>? {
        val localX = (x - binding.flow.horizontalPad).toInt()
        val contentY = y - binding.flow.verticalPad
        for (h in hits) {
            val atoms = h.atoms ?: continue
            if (contentY < h.top || contentY > h.top + h.layout.height) continue
            val line = h.layout.getLineForVertical((contentY - h.top).toInt())
            val offset = h.layout.getOffsetForHorizontal(line, localX.toFloat())
            return typo.xrefAtOffset(atoms, offset)
        }
        return null
    }

    private fun openPassage(startKey: Int, endKey: Int) {
        startActivity(PassageActivity.intent(this, startKey, endKey))
    }

    private fun turn(delta: Int) {
        val target = page + delta
        if (target < 0 || target >= pages.size) return
        page = target
        turnsSinceRefresh++
        if (turnsSinceRefresh >= FULL_REFRESH_EVERY) {
            turnsSinceRefresh = 0
            flashFullRefresh()
        }
        renderCurrentPage()
    }

    private fun flashFullRefresh() {
        binding.flash.visibility = View.VISIBLE
        binding.flash.postDelayed({ binding.flash.visibility = View.GONE }, 90)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // A tapped cross-reference wins over the page-turn zones.
                xrefAt(e.x, e.y)?.let { (startKey, endKey) ->
                    openPassage(startKey, endKey)
                    return true
                }
                val third = binding.flow.width / 3f
                when {
                    e.x < third -> turn(-1)
                    e.x > third * 2 -> turn(1)
                    else -> finish()
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
                if (velocityX < 0) turn(1) else turn(-1)
                return true
            }
        })
        binding.flow.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun goFullScreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun readRanges(flat: IntArray): List<VerseRange> =
        (flat.indices step 2)
            .filter { it + 1 < flat.size }
            .map { VerseRange(flat[it], flat[it + 1]) }

    companion object {
        private const val FULL_REFRESH_EVERY = 6
        private val NONSPACE = Regex("\\S+")
        private const val EXTRA_COMMENTARY_ID = "commentary_id"
        private const val EXTRA_REFERENCE = "reference"
        private const val EXTRA_RANGES = "ranges"
        private const val EXTRA_ALLOW_CHANGE = "allow_change"

        fun intent(
            context: Context,
            commentaryId: String,
            reference: String,
            ranges: List<VerseRange>,
            allowChange: Boolean,
        ): Intent {
            val flat = IntArray(ranges.size * 2)
            ranges.forEachIndexed { i, r ->
                flat[i * 2] = r.startKey
                flat[i * 2 + 1] = r.endKey
            }
            return Intent(context, CommentaryActivity::class.java)
                .putExtra(EXTRA_COMMENTARY_ID, commentaryId)
                .putExtra(EXTRA_REFERENCE, reference)
                .putExtra(EXTRA_RANGES, flat)
                .putExtra(EXTRA_ALLOW_CHANGE, allowChange)
        }
    }
}
