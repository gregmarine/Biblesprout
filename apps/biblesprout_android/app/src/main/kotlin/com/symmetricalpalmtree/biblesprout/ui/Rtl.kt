package com.symmetricalpalmtree.biblesprout.ui

import androidx.core.text.BidiFormatter

/**
 * Isolates a right-to-left run (Hebrew, Aramaic) for display inside the app's
 * otherwise-English chrome.
 *
 * Without it, Android's bidi algorithm reads the line's first strong character —
 * the Hebrew — lays the whole paragraph out right-to-left, and drags neighbouring
 * punctuation to the wrong end: "ray-sheeth'" renders as "('ray-sheeth", and a
 * "רֵאשִׁית · H7225" title flips to "H7225 · רֵאשִׁית". Wrapping marks the run's
 * direction explicitly so the Hebrew reads RTL *within* a line that stays LTR.
 *
 * The view must also be pinned to LTR (`textDirection`), or its paragraph
 * direction is still resolved from the first strong character.
 */
fun isolateRtl(text: String): CharSequence = BidiFormatter.getInstance().unicodeWrap(text)
