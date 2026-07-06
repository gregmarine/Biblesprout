package com.symmetricalpalmtree.biblesprout.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Packs/unpacks a stroke's `x, y` float samples to/from the `note_stroke` BLOB. */
object StrokeCodec {

    fun pack(points: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(points.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(points)
        return buffer.array()
    }

    fun unpack(bytes: ByteArray): FloatArray {
        val floats = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return floats
    }
}
