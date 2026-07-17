package com.symmetricalpalmtree.biblesprout.ui

import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.symmetricalpalmtree.biblesprout.R
import com.symmetricalpalmtree.biblesprout.data.VerseHit
import com.symmetricalpalmtree.biblesprout.databinding.ViewVerseListBinding
import kotlin.math.roundToInt

/**
 * Drives a [view_verse_list] as pages of fixed-height verse rows under a header —
 * the shape both Find's search results and the word-study concordance need.
 *
 * Rows are packed by measured height rather than scrolled: on e-ink a page turn is
 * one refresh, where a scroll is a smear of them (see docs/eink-constraints.md).
 * Because every row is a fixed height, packing needs no layout pass.
 */
class VerseListPager(
    private val binding: ViewVerseListBinding,
    private val onOpen: (VerseHit) -> Unit,
) {
    private val context = binding.root.context
    private val inflater = LayoutInflater.from(context)

    private val headerHeight = dp(52)
    private val rowHeight = dp(120)

    private var header: String = ""
    private var hits: List<VerseHit> = emptyList()
    private var pages: List<List<Entry>> = emptyList()
    private var current = 0
    private var lastHeight = 0

    init {
        binding.pager.onSwipeLeft = { show(current + 1) }
        binding.pager.onSwipeRight = { show(current - 1) }
        binding.prev.setOnClickListener { show(current - 1) }
        binding.next.setOnClickListener { show(current + 1) }
        // The pager's height is only known after layout, and changes with the BOOX's
        // font scale, so repack whenever it settles on a new one.
        binding.pager.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            if (height > 0 && height != lastHeight) {
                lastHeight = height
                binding.pager.post { pack() }
            }
        }
    }

    /** Replaces the list; [header] labels it (e.g. "49 VERSES · H7225"). */
    fun submit(header: String, hits: List<VerseHit>) {
        this.header = header
        this.hits = hits
        current = 0
        binding.pager.post { pack() }
    }

    private fun pack() {
        val height = binding.pager.height
        if (height <= 0 || hits.isEmpty()) return
        lastHeight = height

        val entries = ArrayList<Entry>()
        entries.add(Entry.Header(header, headerHeight))
        hits.forEach { entries.add(Entry.Row(it, rowHeight)) }

        val packed = ArrayList<List<Entry>>()
        var page = ArrayList<Entry>()
        var used = 0
        for (entry in entries) {
            if (page.isNotEmpty() && used + entry.heightPx > height) {
                packed.add(page)
                page = ArrayList()
                used = 0
            }
            page.add(entry)
            used += entry.heightPx
        }
        if (page.isNotEmpty()) packed.add(page)
        pages = packed
        show(current)
    }

    private fun show(index: Int) {
        if (pages.isEmpty()) return
        current = index.coerceIn(0, pages.size - 1)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
        for (entry in pages[current]) column.addView(entryView(entry, column))
        binding.pager.removeAllViews()
        binding.pager.addView(column)

        binding.pageIndicator.text = if (pages.size > 1) {
            context.getString(R.string.page_indicator, current + 1, pages.size)
        } else {
            ""
        }
        setArrow(binding.prev, current > 0)
        setArrow(binding.next, current < pages.size - 1)
    }

    private fun entryView(entry: Entry, parent: LinearLayout): View = when (entry) {
        is Entry.Header -> TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, entry.heightPx,
            )
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(20), 0, dp(20), dp(10))
            text = entry.text
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.eink_rule))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.1f
        }

        is Entry.Row -> inflater.inflate(R.layout.item_result, parent, false).apply {
            findViewById<TextView>(R.id.reference).text = entry.hit.reference
            findViewById<TextView>(R.id.snippet).text = entry.hit.text
            setOnClickListener { onOpen(entry.hit) }
        }
    }

    private fun setArrow(view: ImageView, enabled: Boolean) {
        view.isEnabled = enabled
        view.setColorFilter(
            ContextCompat.getColor(
                context,
                if (enabled) R.color.eink_black else R.color.eink_disabled,
            ),
        )
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private sealed interface Entry {
        val heightPx: Int
        data class Header(val text: String, override val heightPx: Int) : Entry
        data class Row(val hit: VerseHit, override val heightPx: Int) : Entry
    }
}
