package com.symmetricalpalmtree.biblesprout.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.biblesprout.MainActivity
import com.symmetricalpalmtree.biblesprout.R
import com.symmetricalpalmtree.biblesprout.data.AppServices
import com.symmetricalpalmtree.biblesprout.data.Canon
import com.symmetricalpalmtree.biblesprout.data.VerseKey
import com.symmetricalpalmtree.biblesprout.data.VerseRange
import com.symmetricalpalmtree.biblesprout.data.index.Bookmark
import com.symmetricalpalmtree.biblesprout.data.index.Highlight
import com.symmetricalpalmtree.biblesprout.data.index.ReadingPosition
import com.symmetricalpalmtree.biblesprout.databinding.ActivityReaderBinding
import com.symmetricalpalmtree.biblesprout.model.ChapterRef
import com.symmetricalpalmtree.biblesprout.reader.Atom
import com.symmetricalpalmtree.biblesprout.reader.ChapterPaginator
import com.symmetricalpalmtree.biblesprout.reader.CommentaryLauncher
import com.symmetricalpalmtree.biblesprout.reader.FootnotePopup
import com.symmetricalpalmtree.biblesprout.reader.NumberAtom
import com.symmetricalpalmtree.biblesprout.reader.ReaderPage
import com.symmetricalpalmtree.biblesprout.reader.ReaderTypography
import com.symmetricalpalmtree.biblesprout.reader.WordAtom
import com.symmetricalpalmtree.biblesprout.reader.WordRef
import android.text.StaticLayout
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Full-screen paginated reader. Each chapter starts on a fresh page; tapping the
 * left/right thirds (or swiping) turns pages and flows across chapter and book
 * boundaries. The center third and the title open Contents. Every
 * [FULL_REFRESH_EVERY] turns a black flash forces the E Ink panel to full-refresh,
 * clearing ghosting. Ported from the Flutter `reader_screen.dart`.
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var typo: ReaderTypography

    private lateinit var ref: ChapterRef
    private var page = 0
    private var pages: List<List<Atom>> = emptyList()

    private var pendingLastPage = false
    private var pendingVerse = -1
    private var turnsSinceRefresh = 0
    private var paginateJob: Job? = null

    // The current page's body layout and its y-origin within the reader view, so a
    // long-press can be hit-tested back to the verse it fell in.
    private var currentBody: StaticLayout? = null
    private var bodyTopPx = 0

    // The anchor (top) verse key of each page, and the set of bookmarked keys, for
    // the top-bar bookmark toggle.
    private var pageAnchors: List<Int> = emptyList()
    private var bookmarkedKeys: Set<Int> = emptySet()

    // The current chapter's footnotes by id, for the tap-to-open caller popup.
    private var chapterFootnotes: Map<Int, com.symmetricalpalmtree.biblesprout.data.Footnote> = emptyMap()

    // The current chapter's footnote-body cross-references, grouped by footnote id, so
    // a reference cited in a footnote is tappable in its popup.
    private var noteXrefs: Map<Int, List<com.symmetricalpalmtree.biblesprout.data.Xref>> = emptyMap()

    // Highlights: the current chapter's saved highlights, the per-page word seed
    // (verse + word count carried into each page), and the in-progress selection.
    private var pageSeeds: List<WordRef> = emptyList()
    private var chapterHighlights: List<Highlight> = emptyList()
    private var highlightMode = false
    private var selAnchor: WordRef? = null
    // A selection can span verses/paragraphs (and pages), so it is a list of per-verse rows.
    private var pendingCreates: List<Highlight> = emptyList()
    private var pendingRemove: Highlight? = null
    // The whole chapter's words in reading order — the selection is defined against these
    // (page-independent) so it can span pages and survives repagination/font changes. The
    // current page's words are a subset, used to find the boundary word when auto-turning.
    private var chapterWords: List<WordRef> = emptyList()
    private var pageWordList: List<WordRef> = emptyList()
    // Stylus drag-select: whether the pen has crossed off its start word and the last word
    // it settled on (so we only redraw on a change).
    private var dragMoved = false
    private var lastDragEnd: WordRef? = null
    // Edge dwell for cross-page turns: the page turns only when the pen *holds still* at an
    // edge (so moving to select near an edge never turns). Tracks the held direction, when
    // the hold began, and where — a real move resets it.
    private var edgeDwellDir = 0
    private var edgeDwellStart = 0L
    private var edgeAnchorX = 0f
    private var edgeAnchorY = 0f
    // Once a drag turns a page, it can only keep turning the same way — so after going to
    // the next page you can hold near its top (to grab the first line) without flipping back.
    private var turnLockDir = 0

    private val safetyPad by lazy { typo.dp(8f) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullScreenImmersive()

        typo = ReaderTypography(this)
        binding.reader.horizontalPad = typo.dp(44f)
        binding.reader.verticalPad = typo.dp(10f)
        binding.reader.gap1 = typo.gap1
        binding.reader.gap2 = typo.gap2

        ref = ChapterRef(
            intent.getIntExtra(EXTRA_BOOK_INDEX, 0),
            intent.getIntExtra(EXTRA_CHAPTER, 1),
        )
        page = intent.getIntExtra(EXTRA_START_PAGE, 0)
        pendingVerse = intent.getIntExtra(EXTRA_START_VERSE, -1)

        binding.back.setOnClickListener { finish() }
        binding.title.setOnClickListener { openContents() }
        binding.contents.setOnClickListener { openContents() }
        binding.notes.setOnClickListener { openChapterCommentary() }
        binding.notebook.setOnClickListener { openChapterNotebook() }
        binding.bookmark.setOnClickListener { toggleBookmark() }
        binding.highlight.setOnClickListener { toggleHighlightMode() }
        binding.highlightAction.setOnClickListener { commitHighlightAction() }
        binding.highlightDone.setOnClickListener { exitHighlightMode() }
        attachGestures()

        lifecycleScope.launch {
            AppServices.bootstrap(applicationContext)
            // Offer the Notes affordance only when a commentary is installed.
            binding.notes.visibility =
                if (AppServices.commentaries.isNotEmpty()) View.VISIBLE else View.GONE
            bookmarkedKeys = withContext(Dispatchers.IO) {
                AppServices.index.bookmarks().keys()
            }.toSet()
            persist() // record the opened location immediately
            binding.reader.doOnLayout { repaginate() }
        }
    }

    // --- Bookmarks ------------------------------------------------------------

    /** Toggles a bookmark on the current page's anchor (top) verse. */
    private fun toggleBookmark() {
        val key = pageAnchors.getOrNull(page) ?: return
        lifecycleScope.launch {
            val dao = AppServices.index.bookmarks()
            bookmarkedKeys = if (key in bookmarkedKeys) {
                withContext(Dispatchers.IO) { dao.removeByKey(key) }
                bookmarkedKeys - key
            } else {
                withContext(Dispatchers.IO) {
                    dao.add(Bookmark(verseKey = key, createdAt = System.currentTimeMillis()))
                }
                bookmarkedKeys + key
            }
            updateBookmarkIcon()
        }
    }

    private fun updateBookmarkIcon() {
        val marked = pageAnchors.getOrNull(page)?.let { it in bookmarkedKeys } ?: false
        binding.bookmark.setImageResource(
            if (marked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark,
        )
        binding.bookmark.contentDescription =
            getString(if (marked) R.string.remove_bookmark else R.string.add_bookmark)
    }

    /** The anchor (top) verse of each page: its first verse number, or — when a
     *  page opens mid-verse — the verse carried over from the previous page. */
    private fun computeAnchors(pages: List<List<Atom>>, ordinal: Int, chapter: Int): List<Int> {
        var lastKey = VerseKey.encode(ordinal, chapter, 1)
        val anchors = ArrayList<Int>(pages.size)
        for (atoms in pages) {
            // The anchor is the page's first verse number (ignoring leading breaks/
            // headings); if the page opens mid-verse, the verse carried from above.
            val firstContent = atoms.firstOrNull { it is NumberAtom || it is WordAtom }
            anchors.add(if (firstContent is NumberAtom) firstContent.verseKey else lastKey)
            (atoms.lastOrNull { it is NumberAtom } as? NumberAtom)?.let { lastKey = it.verseKey }
        }
        return anchors
    }

    // --- Highlights -----------------------------------------------------------

    /** The word (verse + word count) carried into the start of each page, so a
     *  highlight's word span underlines the same words however the text paginates. */
    private fun computeSeeds(pages: List<List<Atom>>, ordinal: Int, chapter: Int): List<WordRef> {
        var verse = VerseKey.encode(ordinal, chapter, 1)
        var words = 0
        val seeds = ArrayList<WordRef>(pages.size)
        for (atoms in pages) {
            seeds.add(WordRef(verse, words))
            for (a in atoms) when (a) {
                is NumberAtom -> { verse = a.verseKey; words = 0 }
                is WordAtom -> words += 1
                else -> {} // breaks/headings carry no words
            }
        }
        return seeds
    }

    private suspend fun reloadChapterHighlights() {
        val ordinal = ref.bookIndex + 1
        val (lo, hi) = VerseKey.chapterBounds(ordinal, ref.chapterNumber)
        chapterHighlights = withContext(Dispatchers.IO) {
            AppServices.index.highlights().inRange(lo, hi)
        }
    }

    /** Underline ranges for the current page: saved highlights plus any in-progress
     *  selection preview (the anchor word, or the full pending span). */
    private fun highlightRangesForPage(): List<IntRange> {
        if (pages.isEmpty()) return emptyList()
        val preview: List<Highlight> = when {
            pendingCreates.isNotEmpty() -> pendingCreates
            selAnchor != null -> listOf(
                Highlight(
                    verseKey = selAnchor!!.verseKey,
                    startWord = selAnchor!!.wordIndex,
                    endWord = selAnchor!!.wordIndex,
                    createdAt = 0,
                ),
            )
            else -> emptyList()
        }
        val effective = chapterHighlights + preview
        val spans = effective.groupBy { it.verseKey }
            .mapValues { (_, list) -> list.map { it.startWord..it.endWord } }
        val seed = pageSeeds.getOrElse(page) {
            WordRef(VerseKey.encode(ref.bookIndex + 1, ref.chapterNumber, 1), 0)
        }
        return typo.highlightRanges(pages[page], seed, spans)
    }

    private fun toggleHighlightMode() {
        if (highlightMode) exitHighlightMode() else enterHighlightMode()
    }

    private fun enterHighlightMode() {
        highlightMode = true
        selAnchor = null; pendingCreates = emptyList(); pendingRemove = null
        dragMoved = false; lastDragEnd = null
        // The mode bar at the bottom signals that highlight mode is active.
        binding.pageIndicator.visibility = View.GONE
        binding.highlightBar.visibility = View.VISIBLE
        updateHighlightBar()
        renderCurrentPage()
    }

    private fun exitHighlightMode() {
        highlightMode = false
        selAnchor = null; pendingCreates = emptyList(); pendingRemove = null
        dragMoved = false; lastDragEnd = null
        binding.highlightBar.visibility = View.GONE
        binding.pageIndicator.visibility = View.VISIBLE
        renderCurrentPage()
    }

    /** In highlight mode, a finger tap picks words: the first, then the last of a
     *  phrase (which may span verses); a tap inside an existing highlight offers to
     *  remove it. The stylus drag-selects instead (see [handleHighlightStylus]). */
    private fun handleHighlightTap(x: Float, y: Float) {
        val ref = wordRefAt(x, y) ?: return
        val existing = chapterHighlights.firstOrNull {
            it.verseKey == ref.verseKey && ref.wordIndex in it.startWord..it.endWord
        }
        val anchor = selAnchor
        when {
            anchor == null && existing != null -> {
                pendingRemove = existing; pendingCreates = emptyList()
            }
            anchor == null -> {
                selAnchor = ref; pendingCreates = emptyList(); pendingRemove = null
            }
            else -> {
                pendingCreates = buildSelection(anchor, ref)
                pendingRemove = null; selAnchor = null
            }
        }
        updateHighlightBar()
        renderCurrentPage()
    }

    private fun wordRefAt(x: Float, y: Float): WordRef? {
        val body = currentBody ?: return null
        if (pages.isEmpty()) return null
        val localX = (x - binding.reader.horizontalPad).toInt()
        val localY = (y - binding.reader.verticalPad - bodyTopPx).toInt()
        if (localY < 0 || localY > body.height) return null
        val line = body.getLineForVertical(localY)
        val offset = body.getOffsetForHorizontal(line, localX.toFloat())
        val seed = pageSeeds.getOrElse(page) {
            WordRef(VerseKey.encode(ref.bookIndex + 1, ref.chapterNumber, 1), 0)
        }
        return typo.wordAtOffset(pages[page], seed, offset)
    }

    /**
     * Stylus phrase selection: press on the first word and drag to the last, with every
     * word between underlined in real time — across lines, verses and paragraphs (drag
     * straight down the left edge to grab whole sentences). Releasing shows Save. A
     * press-release with no drag falls back to single-word select, or offers Remove on
     * an existing highlight. Runs only for the stylus (finger keeps the two-tap flow).
     */
    private fun handleHighlightStylus(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val ref = wordRefAt(event.x, event.y)
                selAnchor = ref
                pendingCreates = emptyList()
                pendingRemove = null
                dragMoved = false
                lastDragEnd = ref
                edgeDwellDir = 0
                turnLockDir = 0
                updateHighlightBar()
                refreshHighlightPreview()
            }
            MotionEvent.ACTION_MOVE -> {
                val anchor = selAnchor ?: return true
                // Holding the pen still at the top/bottom edge turns the page, so the gesture
                // can continue the selection across a page break. A dwell (not mere presence)
                // is required so that *moving* to select near an edge never turns the page.
                val edge = typo.dp(52f)
                val zone = when {
                    event.y >= binding.reader.height - edge -> +1
                    event.y <= edge -> -1
                    else -> 0
                }
                if (zone != 0 && (page + zone) in pages.indices) {
                    val thresh = typo.dp(16f)
                    val movedFar = (event.x - edgeAnchorX).let { it * it } +
                        (event.y - edgeAnchorY).let { it * it } > thresh * thresh
                    if (zone != edgeDwellDir || movedFar) {
                        edgeDwellDir = zone
                        edgeDwellStart = System.currentTimeMillis()
                        edgeAnchorX = event.x; edgeAnchorY = event.y
                    } else if (System.currentTimeMillis() - edgeDwellStart >= EDGE_DWELL_MS &&
                        (turnLockDir == 0 || zone == turnLockDir)
                    ) {
                        // A drag turns pages one way only: once we've gone (say) forward, a hold
                        // at the top edge to grab the new page's first line won't flip back.
                        turnLockDir = zone
                        autoTurn(zone)
                        edgeDwellStart = System.currentTimeMillis() // require another hold to turn again
                        return true
                    }
                } else {
                    edgeDwellDir = 0
                }
                val cur = wordRefAt(event.x, event.y) ?: return true
                if (cur == lastDragEnd) return true // same word — no redraw
                lastDragEnd = cur
                if (cur != anchor) dragMoved = true
                pendingCreates = buildSelection(anchor, cur)
                pendingRemove = null
                updateHighlightBar()
                refreshHighlightPreview()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val anchor = selAnchor
                if (anchor != null && !dragMoved) {
                    // A tap, not a drag: remove if inside an existing highlight, else one word.
                    val existing = chapterHighlights.firstOrNull {
                        it.verseKey == anchor.verseKey && anchor.wordIndex in it.startWord..it.endWord
                    }
                    if (existing != null) {
                        pendingRemove = existing; pendingCreates = emptyList()
                    } else {
                        pendingCreates = buildSelection(anchor, anchor)
                    }
                }
                selAnchor = null
                updateHighlightBar()
                renderCurrentPage()
                persist() // the drag may have auto-turned pages; save where we ended
            }
        }
        return true
    }

    /**
     * During a stylus selection drag, turn the page (keeping the anchor) so the selection
     * continues onto the new page. Gated by an edge dwell in the caller. The selection
     * extends to the new page's boundary word so the preview reflects the turn at once;
     * continued dragging on the new page refines it.
     */
    private fun autoTurn(dir: Int) {
        val target = page + dir
        if (target < 0 || target >= pages.size) return
        page = target
        renderCurrentPage() // rebuilds pageWordList for the new page; chapterWords is unchanged
        val anchor = selAnchor
        val boundary = if (dir > 0) pageWordList.firstOrNull() else pageWordList.lastOrNull()
        if (anchor != null && boundary != null) {
            lastDragEnd = boundary
            if (boundary != anchor) dragMoved = true
            pendingCreates = buildSelection(anchor, boundary)
            updateHighlightBar()
            refreshHighlightPreview()
        }
    }

    /**
     * The per-verse highlight rows covering every word between two words in the chapter,
     * in reading order — one row per verse the span touches (partial at the ends, whole
     * for verses in the middle). Chapter-wide, so the span may cross pages. Empty if
     * either word isn't in the chapter.
     */
    private fun buildSelection(a: WordRef, b: WordRef): List<Highlight> {
        val words = chapterWords
        val ia = words.indexOf(a)
        val ib = words.indexOf(b)
        if (ia < 0 || ib < 0) return emptyList()
        val lo = minOf(ia, ib)
        val hi = maxOf(ia, ib)
        val now = System.currentTimeMillis()
        val rows = ArrayList<Highlight>()
        var vKey = words[lo].verseKey
        var vMin = words[lo].wordIndex
        var vMax = words[lo].wordIndex
        for (k in lo + 1..hi) {
            val w = words[k]
            if (w.verseKey == vKey) {
                vMax = w.wordIndex // reading order → word index increases within a verse
            } else {
                rows.add(Highlight(verseKey = vKey, startWord = vMin, endWord = vMax, createdAt = now))
                vKey = w.verseKey; vMin = w.wordIndex; vMax = w.wordIndex
            }
        }
        rows.add(Highlight(verseKey = vKey, startWord = vMin, endWord = vMax, createdAt = now))
        return rows
    }

    /** One page's words in reading order (verse + word index); used for auto-turn boundaries. */
    private fun pageWords(pageIndex: Int): List<WordRef> {
        val atoms = pages.getOrNull(pageIndex) ?: return emptyList()
        val seed = pageSeeds.getOrElse(pageIndex) {
            WordRef(VerseKey.encode(ref.bookIndex + 1, ref.chapterNumber, 1), 0)
        }
        var verse = seed.verseKey
        var wordIdx = seed.wordIndex
        val out = ArrayList<WordRef>()
        for (atom in atoms) when (atom) {
            is NumberAtom -> { verse = atom.verseKey; wordIdx = 0 }
            is WordAtom -> { out.add(WordRef(verse, wordIdx)); wordIdx += 1 }
            else -> {} // breaks/headings carry no words
        }
        return out
    }

    /** The whole chapter's words in reading order — the pages concatenated. */
    private fun buildChapterWords(): List<WordRef> =
        pages.indices.flatMap { pageWords(it) }

    /** Repaints just the preview underlines (no relayout) — cheap enough per drag word. */
    private fun refreshHighlightPreview() {
        if (pages.isEmpty()) return
        binding.reader.bodyHighlights = highlightRangesForPage()
    }

    private fun updateHighlightBar() {
        when {
            pendingRemove != null -> {
                binding.highlightHint.text = getString(R.string.highlight_hint_existing)
                showHighlightAction(getString(R.string.remove))
            }
            pendingCreates.isNotEmpty() -> {
                val first = pendingCreates.first().verseKey
                val last = pendingCreates.last().verseKey
                binding.highlightHint.text = if (first == last) {
                    verseLabel(first)
                } else {
                    "${verseLabel(first)}–${VerseKey.verseOf(last)}"
                }
                showHighlightAction(getString(R.string.save))
            }
            selAnchor != null -> {
                binding.highlightHint.text = getString(R.string.highlight_hint_last)
                binding.highlightAction.visibility = View.GONE
            }
            else -> {
                binding.highlightHint.text = getString(R.string.highlight_hint_first)
                binding.highlightAction.visibility = View.GONE
            }
        }
    }

    private fun showHighlightAction(label: String) {
        binding.highlightAction.text = label
        binding.highlightAction.visibility = View.VISIBLE
    }

    private fun verseLabel(verseKey: Int): String {
        val book = Canon.byOrdinal(VerseKey.ordinalOf(verseKey))
        return "${book.name} ${VerseKey.chapterOf(verseKey)}:${VerseKey.verseOf(verseKey)}"
    }

    /** Persists the pending highlight(s) or removal, then stays in mode for more. */
    private fun commitHighlightAction() {
        val creates = pendingCreates
        val remove = pendingRemove
        lifecycleScope.launch {
            val dao = AppServices.index.highlights()
            when {
                remove != null -> withContext(Dispatchers.IO) { dao.removeById(remove.id) }
                creates.isNotEmpty() -> withContext(Dispatchers.IO) { creates.forEach { dao.add(it) } }
                else -> return@launch
            }
            selAnchor = null; pendingCreates = emptyList(); pendingRemove = null
            dragMoved = false; lastDragEnd = null
            reloadChapterHighlights()
            updateHighlightBar()
            renderCurrentPage()
        }
    }

    /** Opens commentary for the whole chapter in view. */
    private fun openChapterCommentary() {
        val ordinal = ref.bookIndex + 1
        val (lo, hi) = VerseKey.chapterBounds(ordinal, ref.chapterNumber)
        val book = AppServices.bible.bookAt(ref.bookIndex)
        CommentaryLauncher.open(
            this,
            listOf(VerseRange(lo, hi)),
            "${book.name} ${ref.chapterNumber}",
        )
    }

    /** Opens the handwritten notebook for the whole chapter in view. */
    private fun openChapterNotebook() {
        val ordinal = ref.bookIndex + 1
        val (lo, hi) = VerseKey.chapterBounds(ordinal, ref.chapterNumber)
        val book = AppServices.bible.bookAt(ref.bookIndex)
        startActivity(NoteActivity.intent(this, lo, hi, "${book.name} ${ref.chapterNumber}"))
    }

    /** The footnote whose caller was tapped at (x, y), if any. */
    private fun footnoteAt(x: Float, y: Float): com.symmetricalpalmtree.biblesprout.data.Footnote? {
        val body = currentBody ?: return null
        if (pages.isEmpty()) return null
        val localX = (x - binding.reader.horizontalPad).toInt()
        val localY = (y - binding.reader.verticalPad - bodyTopPx).toInt()
        if (localY < 0 || localY > body.height) return null
        val line = body.getLineForVertical(localY)
        val offset = body.getOffsetForHorizontal(line, localX.toFloat())
        val id = typo.footnoteAtOffset(pages[page], offset) ?: return null
        return chapterFootnotes[id]
    }

    /** The cross-reference target range (`startKey to endKey`) tapped at (x, y), if any. */
    private fun xrefAt(x: Float, y: Float): Pair<Int, Int>? {
        val body = currentBody ?: return null
        if (pages.isEmpty()) return null
        val localX = (x - binding.reader.horizontalPad).toInt()
        val localY = (y - binding.reader.verticalPad - bodyTopPx).toInt()
        if (localY < 0 || localY > body.height) return null
        val line = body.getLineForVertical(localY)
        val offset = body.getOffsetForHorizontal(line, localX.toFloat())
        return typo.xrefAtOffset(pages[page], offset)
    }

    /**
     * Opens a cross-referenced range in the passage view — shown like a search
     * result, from where its "Full chapter" action jumps into the reader.
     */
    private fun openPassage(startKey: Int, endKey: Int) {
        startActivity(PassageActivity.intent(this, startKey, endKey))
    }

    /** Opens commentary anchored to the verse under a long-press, if any. */
    private fun openVerseCommentary(x: Float, y: Float) {
        val body = currentBody ?: return
        if (pages.isEmpty()) return
        val localX = (x - binding.reader.horizontalPad).toInt()
        val localY = (y - binding.reader.verticalPad - bodyTopPx).toInt()
        if (localY < 0 || localY > body.height) return
        val line = body.getLineForVertical(localY)
        val offset = body.getOffsetForHorizontal(line, localX.toFloat())
        val key = typo.verseKeyAtOffset(pages[page], offset) ?: return
        CommentaryLauncher.openVerse(this, key)
    }

    // --- Navigation -----------------------------------------------------------

    private fun nextPage() {
        if (page < pages.size - 1) {
            page++
            commitTurn(repaginate = false)
        } else {
            val next = AppServices.bible.next(ref) ?: return
            ref = next
            page = 0
            commitTurn(repaginate = true)
        }
    }

    private fun prevPage() {
        if (page > 0) {
            page--
            commitTurn(repaginate = false)
        } else {
            val previous = AppServices.bible.previous(ref) ?: return
            ref = previous
            pendingLastPage = true
            commitTurn(repaginate = true)
        }
    }

    private fun commitTurn(repaginate: Boolean) {
        turnsSinceRefresh++
        val flash = turnsSinceRefresh >= FULL_REFRESH_EVERY
        if (flash) turnsSinceRefresh = 0
        if (repaginate) repaginate() else renderCurrentPage()
        persist()
        if (flash) flashFullRefresh()
    }

    private fun openContents() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun persist() {
        lifecycleScope.launch {
            AppServices.readingPosition.save(
                ReadingPosition(ref.bookIndex, ref.chapterNumber, page),
            )
        }
    }

    /** Hold a black frame briefly so the EPD registers a full refresh. */
    private fun flashFullRefresh() {
        binding.flash.visibility = android.view.View.VISIBLE
        binding.flash.postDelayed({ binding.flash.visibility = android.view.View.GONE }, 90)
    }

    // --- Pagination -----------------------------------------------------------

    private fun repaginate() {
        val width = binding.reader.readingWidth()
        val height = binding.reader.readingHeight()
        if (width <= 0 || height <= 0) return
        val book = AppServices.bible.bookAt(ref.bookIndex)
        val chapter = AppServices.bible.chapter(ref.bookIndex, ref.chapterNumber)
        val usfm = Canon.byOrdinal(ref.bookIndex + 1).usfm

        paginateJob?.cancel()
        paginateJob = lifecycleScope.launch {
            val blocks = withContext(Dispatchers.IO) {
                AppServices.bibleDb.blocksForChapter(usfm, chapter.number)
            }
            val footnotes = withContext(Dispatchers.IO) {
                AppServices.bibleDb.footnotesForChapter(usfm, chapter.number)
            }
            val xrefs = withContext(Dispatchers.IO) {
                AppServices.bibleDb.xrefsForChapter(usfm, chapter.number)
            }
            chapterFootnotes = footnotes.associateBy { it.id }
            noteXrefs = xrefs.filter { it.sourceKind == "note" }.groupBy { it.sourceId }
            val packed = withContext(Dispatchers.Default) {
                val atoms = ChapterPaginator.atomsForBlocks(blocks, footnotes, xrefs)
                val headingHeight = typo.headingHeight(book.name, chapter.number, width)
                ChapterPaginator.paginate(
                    atoms = atoms,
                    typo = typo,
                    width = width,
                    firstPageHeight = height - headingHeight - safetyPad,
                    otherPageHeight = height - safetyPad,
                )
            }
            pages = packed
            pageAnchors = computeAnchors(packed, ref.bookIndex + 1, ref.chapterNumber)
            pageSeeds = computeSeeds(packed, ref.bookIndex + 1, ref.chapterNumber)
            chapterWords = buildChapterWords()
            reloadChapterHighlights()
            if (pendingLastPage) {
                page = pages.size - 1
                pendingLastPage = false
            }
            if (pendingVerse > 0) {
                val idx = pages.indexOfFirst { atoms ->
                    atoms.any { it is NumberAtom && it.number == pendingVerse }
                }
                if (idx >= 0) page = idx
                pendingVerse = -1
            }
            page = page.coerceIn(0, pages.size - 1)
            renderCurrentPage()
        }
    }

    private fun renderCurrentPage() {
        if (pages.isEmpty()) return
        val width = binding.reader.readingWidth()
        val book = AppServices.bible.bookAt(ref.bookIndex)
        val chapter = AppServices.bible.chapter(ref.bookIndex, ref.chapterNumber)
        val body = typo.bodyLayout(pages[page], width)
        binding.reader.page = if (page == 0) {
            val (title, number) = typo.headingLayouts(book.name, chapter.number, width)
            // The heading pushes the body down on a chapter's first page.
            bodyTopPx = title.height + typo.gap1 + number.height + typo.gap2
            ReaderPage(body, title, number)
        } else {
            bodyTopPx = 0
            ReaderPage(body)
        }
        currentBody = body
        pageWordList = pageWords(page)
        binding.reader.bodyHighlights = highlightRangesForPage()
        binding.title.text = getString(R.string.reader_title, book.name, chapter.number)
        binding.pageIndicator.text = getString(R.string.page_indicator, page + 1, pages.size)
        updateBookmarkIcon()
    }

    // --- Gestures -------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (highlightMode) {
                    handleHighlightTap(e.x, e.y)
                    return true
                }
                // A tap on a cross-reference (a \r parallel-passage link) opens the
                // referenced passage instead of turning the page.
                xrefAt(e.x, e.y)?.let { (startKey, endKey) ->
                    openPassage(startKey, endKey)
                    return true
                }
                // A tap on a footnote caller opens its popup instead of turning the page.
                footnoteAt(e.x, e.y)?.let { note ->
                    val links = noteXrefs[note.id].orEmpty()
                        .map { FootnotePopup.Link(it.start, it.end, it.targetStartKey, it.targetEndKey) }
                    FootnotePopup.show(this@ReaderActivity, note.verseKey, note.text, links) { s, e2 ->
                        openPassage(s, e2)
                    }
                    return true
                }
                val third = binding.reader.width / 3f
                when {
                    e.x < third -> prevPage()
                    e.x > third * 2 -> nextPage()
                    else -> openContents()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // The superscript number is too small a tap target on e-ink, and
                // tap/swipe already turn pages — so a long-press opens commentary
                // for the pressed verse. (Suspended in highlight mode.)
                if (!highlightMode) openVerseCommentary(e.x, e.y)
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (abs(velocityX) <= abs(velocityY)) return false
                if (velocityX < 0) nextPage() else prevPage()
                return true
            }
        })
        binding.reader.setOnTouchListener { _, event ->
            // In highlight mode the stylus drag-selects a phrase; everything else
            // (finger taps/flings, and all touches outside highlight mode) goes to the
            // gesture detector — so finger two-tap selection and page turns are unchanged.
            if (highlightMode && event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                handleHighlightStylus(event)
            } else {
                detector.onTouchEvent(event)
            }
        }
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
        private const val FULL_REFRESH_EVERY = 6
        private const val EDGE_DWELL_MS = 400L
        const val EXTRA_BOOK_INDEX = "book_index"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_START_PAGE = "start_page"
        const val EXTRA_START_VERSE = "start_verse"
    }
}
