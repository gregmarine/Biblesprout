package com.symmetricalpalmtree.biblesprout.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceParserTest {

    private fun parse(s: String) = ReferenceParser.parse(s)

    @Test fun `single verse`() {
        val p = parse("John 3:16")!!
        assertEquals("JHN", p.book.usfm)
        assertEquals(VerseKey.encode(43, 3, 16), p.startKey)
        assertEquals(VerseKey.encode(43, 3, 16), p.endKey)
    }

    @Test fun `verse range`() {
        val p = parse("Gen 1:5-10")!!
        assertEquals(VerseKey.encode(1, 1, 5), p.startKey)
        assertEquals(VerseKey.encode(1, 1, 10), p.endKey)
    }

    @Test fun `whole chapter`() {
        val p = parse("Psalm 23")!!
        assertEquals("PSA", p.book.usfm)
        assertEquals(VerseKey.encode(19, 23, 0), p.startKey)
        assertEquals(VerseKey.encode(19, 23, VerseKey.MAX_VERSE), p.endKey)
    }

    @Test fun `spaceless and abbreviations`() {
        assertEquals("PSA", parse("Ps23")!!.book.usfm)
        assertEquals("1CO", parse("1 Cor 13")!!.book.usfm)
    }

    @Test fun `cross-chapter span`() {
        val p = parse("Gen 1:5-2:3")!!
        assertEquals(VerseKey.encode(1, 1, 5), p.startKey)
        assertEquals(VerseKey.encode(1, 2, 3), p.endKey)
    }

    @Test fun `comma-separated verses carry the chapter`() {
        val p = parse("John 3:14-16,18")!!
        assertEquals(2, p.ranges.size)
        assertEquals(VerseKey.encode(43, 3, 18), p.ranges[1].startKey)
    }

    @Test fun `rejects non-references`() {
        assertTrue(parse("living water") == null)
        assertTrue(parse("shepherd") == null)
    }

    @Test fun `parseAll splits across books`() {
        val ps = ReferenceParser.parseAll("John 3:14-17, Acts 1:3")
        assertEquals(2, ps.size)
        assertEquals("JHN", ps[0].book.usfm)
        assertEquals("ACT", ps[1].book.usfm)
    }

    @Test fun `parseAll continues a book across a bare number`() {
        val ps = ReferenceParser.parseAll("John 3:16, 18")
        assertEquals(1, ps.size)
        assertEquals(2, ps[0].ranges.size)
    }

    @Test fun `parseAll rejects a search phrase`() {
        assertTrue(ReferenceParser.parseAll("love your enemies").isEmpty())
    }

    @Test fun `format round-trips a tidy reference`() {
        assertEquals("John 3:14–16, 18", parse("John 3:14-16,18")!!.format())
        assertEquals("Psalms 23", parse("Psalm 23")!!.format())
    }
}
