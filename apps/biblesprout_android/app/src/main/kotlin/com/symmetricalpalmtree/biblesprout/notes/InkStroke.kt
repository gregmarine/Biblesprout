package com.symmetricalpalmtree.biblesprout.notes

/**
 * One pen stroke on a note page: a polyline of `(x, y)` samples in canvas pixels,
 * plus its width and color. [id] is the database row id, or 0 until the stroke has
 * been persisted. Whole-stroke erase removes the entire object, so no sub-stroke
 * geometry is stored.
 */
class InkStroke(
    var id: Long,
    val points: FloatArray, // x0, y0, x1, y1, …
    val width: Float,
    val color: Int,
)
