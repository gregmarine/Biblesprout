package com.symmetricalpalmtree.biblesprout.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerseKeyTest {
    @Test fun `encodes John 3-16`() {
        assertEquals(43_003_016, VerseKey.encode(43, 3, 16))
    }

    @Test fun `round-trips ordinal chapter verse`() {
        val k = VerseKey.encode(43, 3, 16)
        assertEquals(43, VerseKey.ordinalOf(k))
        assertEquals(3, VerseKey.chapterOf(k))
        assertEquals(16, VerseKey.verseOf(k))
    }

    @Test fun `keys sort in reading order`() {
        val gen = VerseKey.encode(1, 1, 1)
        val john = VerseKey.encode(43, 3, 16)
        val rev = VerseKey.encode(66, 22, 21)
        assertTrue(gen < john && john < rev)
    }

    @Test fun `chapter bounds cover the whole chapter`() {
        val (lo, hi) = VerseKey.chapterBounds(43, 3)
        assertEquals(43_003_000, lo)
        assertEquals(43_003_999, hi)
        assertTrue(VerseKey.encode(43, 3, 16) in lo..hi)
    }

    @Test fun `largest key fits in Int`() {
        // Revelation 22:21 — highest real address; must not overflow Int.
        assertTrue(VerseKey.encode(66, 22, 21) < Int.MAX_VALUE)
    }
}

class VerseRangeTest {
    @Test fun `chapters range spans whole chapters`() {
        val r = VerseRange.chapters(1, 1, 2)
        assertTrue(r.contains(VerseKey.encode(1, 1, 1)))
        assertTrue(r.contains(VerseKey.encode(1, 2, 25)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects inverted range`() {
        VerseRange(100, 1)
    }
}

class CanonTest {
    @Test fun `all 66 books present and ordered`() {
        assertEquals(66, Canon.books.size)
        assertEquals("GEN", Canon.byOrdinal(1).usfm)
        assertEquals("REV", Canon.byOrdinal(66).usfm)
        Canon.books.forEachIndexed { i, b -> assertEquals(i + 1, b.ordinal) }
    }

    @Test fun `resolves names aliases usfm and roman numerals`() {
        assertEquals("PSA", Canon.lookup("Ps")?.usfm)
        assertEquals("PSA", Canon.lookup("psalm")?.usfm)
        assertEquals("1CO", Canon.lookup("1 Cor")?.usfm)
        assertEquals("1CO", Canon.lookup("I Corinthians")?.usfm)
        assertEquals("SNG", Canon.lookup("Song of Songs")?.usfm)
        assertEquals("JHN", Canon.lookup("JHN")?.usfm)
        assertNull(Canon.lookup("Nephi"))
    }
}

class FtsTest {
    @Test fun `builds prefix-AND expression`() {
        assertEquals("\"faith\"*", Fts.matchExpression("faith"))
        assertEquals("\"the\"* \"lord\"*", Fts.matchExpression("The Lord!"))
    }

    @Test fun `punctuation-only query has no tokens`() {
        assertNull(Fts.matchExpression("  ?? -- "))
    }
}
