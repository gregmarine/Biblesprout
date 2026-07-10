#!/usr/bin/env python3
"""Build a read-only `*.commentary` SQLite source from CCEL ThML.

Standalone builder (the native counterpart of the frozen Flutter
`tool/build_commentary_db.dart`). Run from anywhere with a Python 3 whose
sqlite3 is FTS5-capable (stock macOS is):

    python3 data/tools/build_commentary_db.py jfb     # Jamieson-Fausset-Brown
    python3 data/tools/build_commentary_db.py mhcc    # Matthew Henry Concise
    python3 data/tools/build_commentary_db.py mhc     # Matthew Henry Complete (6 vols)

Input: CCEL ThML (public domain), e.g. `data/commentaries/jfb.xml`.
Output: `data/commentaries/<id>.commentary`.

The canon (USFM <-> ordinal <-> name) and the verse-key encoding are reused from
`build_bible_db.py` so every source keys scripture the same way: an integer
`ordinal*1_000_000 + chapter*1_000 + verse`. A commentary entry therefore
cross-links to scripture by integer bounds [start_key, end_key].

Parser
------
A document-order state machine over the ThML. Comments are delimited by
`<scripCom type="Commentary">` markers; each entry covers from its marker's
verse up to just before the next marker (the marker's osisRef *range* is often
narrower than the prose that follows, so we trust only the start and let
coverage run to the next marker -- gap-free, so e.g. John 3:16 resolves to the
block that expounds it). Known source shapes handled:

  * Variable div nesting. Matthew Henry puts the book at <div1> and the chapter
    at <div2>; JFB nests <div1>=testament, <div2>=book, <div3>=chapter. So the
    *book* division is whichever one whose title resolves in the canon and the
    *chapter* division is whichever "Chapter N" title -- not a fixed level.
  * osisRef mis-tags the book (CCEL's auto-tagger codes e.g. Jude -> "Judg"),
    so the division title is the book of record; osisRef supplies only the
    reliable chapter/verse numbers.
  * Book-internal sections that aren't chapters ("Introduction", "Chapter 5-8")
    stay inside the current book; back-matter divisions at or above book level
    ("Index of Scripture References") clear the book so their prose is dropped.
  * Quoted-scripture / navigation / running-header paragraphs are skipped by
    class so the Bible text isn't duplicated into commentary bodies.
  * A heading comes from a following <h3>/<h4> (Matthew Henry's "Verses 1-8")
    when present, else from the marker's own passage label (JFB has no h-tags,
    so its per-verse entries are headed "Ge 1:1").
"""

import os
import re
import sqlite3
import sys
import xml.etree.ElementTree as ET

# Reuse the canon + verse-key encoding as the single source of truth.
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir))
sys.path.insert(0, HERE)
from build_bible_db import CANON, ORDINAL, NAME, TESTAMENT, MAX_VERSE, encode, clean_ws

SCHEMA_VERSION = 1


# --- build configuration per commentary id ----------------------------------
CONFIGS = {
    "jfb": {
        "id": "jfb",
        "title": "Jamieson-Fausset-Brown Bible Commentary",
        "abbreviation": "JFB",
        "inputs": ["data/commentaries/jfb.xml"],
        "out": "data/commentaries/jfb.commentary",
        "source_url": "https://ccel.org/ccel/jamieson/jfb.xml",
    },
    "mhcc": {
        "id": "mhcc",
        "title": "Matthew Henry's Concise Commentary",
        "abbreviation": "MHCC",
        "inputs": ["data/commentaries/mhcc.xml"],
        "out": "data/commentaries/mhcc.commentary",
        "source_url": "https://ccel.org/ccel/henry/mhcc.xml",
    },
    "mhc": {
        "id": "mhc",
        "title": "Matthew Henry's Complete Commentary",
        "abbreviation": "MHC",
        # The unabridged commentary ships as six volumes; concatenated in canon
        # order they cover Genesis through Revelation.
        "inputs": [
            "data/commentaries/mhc1.xml",  # Genesis-Deuteronomy
            "data/commentaries/mhc2.xml",  # Joshua-Esther
            "data/commentaries/mhc3.xml",  # Job-Song of Solomon
            "data/commentaries/mhc4.xml",  # Isaiah-Malachi
            "data/commentaries/mhc5.xml",  # Matthew-John
            "data/commentaries/mhc6.xml",  # Acts-Revelation
        ],
        "out": "data/commentaries/mhc.commentary",
        "source_url": "https://ccel.org/ccel/henry/mhc1.xml",
    },
}


# --- book-title resolution ---------------------------------------------------
# The division title is the book of record (osisRef mis-tags the book). Titles
# arrive in several shapes: canonical ("1 Samuel"), word-ordinal ("First
# Samuel", JFB), or Roman ("I Samuel"). Normalize the leading ordinal token to a
# digit and match against the canon names.
_ORD_WORD = {
    "first": "1", "second": "2", "third": "3",
    "i": "1", "ii": "2", "iii": "3",
    "1st": "1", "2nd": "2", "3rd": "3",
}


def _norm_name(title):
    t = re.sub(r"\s+", " ", title.strip().lower()).rstrip(".")
    parts = t.split(" ")
    if parts and parts[0] in _ORD_WORD:
        parts[0] = _ORD_WORD[parts[0]]
    return " ".join(parts)


NAME_TO_USFM = {_norm_name(name): usfm for usfm, _, name, _ in CANON}
# A few equivalent titles used by these sources.
for _alias, _usfm in {
    "song of songs": "SNG", "canticles": "SNG", "psalm": "PSA",
}.items():
    NAME_TO_USFM[_norm_name(_alias)] = _usfm


def lookup_book(title):
    """USFM code for a division title, or None if it isn't a canon book."""
    return NAME_TO_USFM.get(_norm_name(title))


# --- chapter-title resolution ------------------------------------------------
_ROMAN = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100, "D": 500, "M": 1000}


def _from_roman(s):
    total = prev = 0
    for ch in reversed(s):
        v = _ROMAN.get(ch)
        if v is None:
            return 0
        if v < prev:
            total -= v
        else:
            total += v
            prev = v
    return total


def chapter_num(title):
    """First chapter number in a title. The Concise numbers in Arabic
    ("Chapter 23"), the Complete in Roman ("Chapter XXIII"); a range title
    ("Chapter 5-8") yields its first number."""
    m = re.search(r"\d+", title)
    if m:
        return int(m.group())
    m = re.search(r"\b([ivxlcdmIVXLCDM]+)\b", title)
    return _from_roman(m.group(1).upper()) if m else 0


def is_chapter_title(title):
    return title.strip().lower().startswith("chapter") and chapter_num(title) > 0


# --- osisRef -----------------------------------------------------------------
def parse_chapter_verse(osis_ref):
    """START chapter/verse of an osisRef; the book code is ignored (see above).
    Verse is 0 for a whole-chapter ref like `Bible:Isa.1`."""
    s = osis_ref[6:] if osis_ref.startswith("Bible:") else osis_ref
    parts = s.split("-")[0].split(".")
    if len(parts) < 2:
        return None
    try:
        chapter = int(parts[1])
    except ValueError:
        return None
    verse = 0
    if len(parts) >= 3:
        try:
            verse = int(parts[2])
        except ValueError:
            verse = 0
    return chapter, verse


# --- parsing -----------------------------------------------------------------
DIV_TAGS = ("div1", "div2", "div3", "div4")

# `<p class>` values that are quoted scripture, navigation, running headers or
# section/edition banners -- not commentary prose. Excluding them keeps the
# Bible text out of the bodies (and the file to a sane size).
SKIP_PARA_CLASSES = {
    "passage", "bbook", "bref", "pages",   # quoted scripture + page nav
    "Center", "big", "bigc1",              # "CHAPTER N" / "INTRODUCTION" banners
}


class Entry:
    __slots__ = ("usfm", "ordinal", "chapter", "start_verse", "heading",
                 "body", "start_key", "end_key")

    def __init__(self, usfm, chapter, start_verse, heading, body):
        self.usfm = usfm
        self.ordinal = ORDINAL[usfm]
        self.chapter = chapter
        self.start_verse = start_verse
        self.heading = heading
        self.body = body
        self.start_key = 0
        self.end_key = 0


def parse_thml(raw):
    """Parse a commentary ThML document into coverage-resolved entries."""
    # Drop the DOCTYPE so the parser never tries to resolve the external DTD;
    # the body uses only the predefined &amp; entity.
    raw = re.sub(r"<!DOCTYPE[^>]*>", "", raw, count=1)
    root = ET.fromstring(raw)

    # `<p>` runs inside a table are chapter-outline scaffolding, not prose.
    table_ps = {p for table in root.iter("table") for p in table.iter("p")}

    entries = []

    # Context (the book/chapter we are reading within).
    current_book = None    # USFM code
    current_chapter = 0
    book_level = None      # div depth at which book titles sit (1 for MH, 2 JFB)

    # Accumulators for the comment currently being read.
    book = None            # USFM code of the entry being accumulated
    chapter = 0
    start_verse = 0
    heading = None         # from a following <h3>/<h4>
    marker_passage = None  # the scripCom's own passage label (heading fallback)
    paras = []

    def flush():
        nonlocal book, heading, marker_passage, paras
        if book is not None and paras:
            entries.append(Entry(
                usfm=book,
                chapter=chapter,
                start_verse=start_verse,
                heading=heading or marker_passage,
                body="\n\n".join(paras),
            ))
        book = None
        heading = None
        marker_passage = None
        paras = []

    for el in root.iter():
        tag = el.tag
        if tag in DIV_TAGS:
            title = el.get("title", "") or ""
            usfm = lookup_book(title)
            if usfm is not None:
                flush()
                current_book = usfm
                current_chapter = 0
                book_level = int(tag[-1])
            elif is_chapter_title(title):
                flush()
                current_chapter = chapter_num(title)  # keep current_book
            else:
                # Front/back matter, testament grouping, or an in-book section
                # ("Introduction"). A division at or above book level clears the
                # book context; a deeper one is a section within the book.
                flush()
                level = int(tag[-1])
                if book_level is not None and level <= book_level:
                    current_book = None
                    current_chapter = 0

        elif tag == "scripCom":
            if el.get("type") != "Commentary":
                continue
            flush()
            if current_book is None:
                continue  # front/back matter
            cv = parse_chapter_verse(el.get("osisRef", "") or "")
            if cv is None:
                continue
            book = current_book
            chapter, start_verse = cv
            marker_passage = (el.get("passage") or "").strip() or None

        elif tag in ("h3", "h4"):
            # Take the first heading that follows a marker as the section's own.
            if book is not None and heading is None:
                h = clean_ws("".join(el.itertext()))
                if h:
                    heading = h

        elif tag == "p":
            if el in table_ps:
                continue
            if el.get("class") in SKIP_PARA_CLASSES:
                continue
            text = clean_ws("".join(el.itertext()))
            if not text:
                continue
            # Prose with no preceding marker (a chapter whose scripCom is missing,
            # or a book "Introduction") becomes a whole-chapter comment.
            if book is None:
                if current_book is None:
                    continue  # front/back matter
                book = current_book
                chapter = current_chapter if current_chapter != 0 else 1
                start_verse = 0
            paras.append(text)

    flush()
    resolve_coverage(entries)
    return entries


def resolve_coverage(entries):
    """Assign each entry gap-free bounds: a comment starts at the top of its
    chapter (if it's the first there) or at its own verse, and runs to just
    before the next comment, clamped to its own chapter's end."""
    entries.sort(key=lambda e: encode(e.ordinal, e.chapter, e.start_verse))

    for i, e in enumerate(entries):
        first_of_chapter = (
            i == 0
            or entries[i - 1].ordinal != e.ordinal
            or entries[i - 1].chapter != e.chapter
        )
        e.start_key = encode(e.ordinal, e.chapter, 0 if first_of_chapter else e.start_verse)

    for i, e in enumerate(entries):
        chapter_end = encode(e.ordinal, e.chapter, MAX_VERSE)
        if i < len(entries) - 1:
            e.end_key = min(entries[i + 1].start_key - 1, chapter_end)
        else:
            e.end_key = chapter_end


# --- database ----------------------------------------------------------------
def build_db(cfg, entries):
    out = os.path.join(ROOT, cfg["out"])
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    try:
        db.executescript(
            """
            PRAGMA journal_mode = OFF;
            PRAGMA synchronous = OFF;

            CREATE TABLE metadata (
              key   TEXT PRIMARY KEY,
              value TEXT
            );

            CREATE TABLE book (
              usfm        TEXT PRIMARY KEY,
              ordinal     INTEGER NOT NULL,
              name        TEXT NOT NULL,
              testament   TEXT NOT NULL,
              entry_count INTEGER NOT NULL
            );

            -- Each row is one comment covering an inclusive verse-key range
            -- [start_key, end_key]. start_verse is the nominal heading verse
            -- (0 = a whole-chapter comment); heading is the section label.
            CREATE TABLE entry (
              id          INTEGER PRIMARY KEY,
              usfm        TEXT NOT NULL,
              chapter     INTEGER NOT NULL,
              start_verse INTEGER NOT NULL,
              start_key   INTEGER NOT NULL,
              end_key     INTEGER NOT NULL,
              heading     TEXT,
              body        TEXT NOT NULL
            );
            CREATE INDEX ix_entry_start ON entry(start_key);
            CREATE INDEX ix_entry_book_chapter ON entry(usfm, chapter);
            """
        )

        meta = {
            "id": cfg["id"],
            "title": cfg["title"],
            "abbreviation": cfg["abbreviation"],
            "type": "commentary",
            "language": "en",
            "versification": "english",
            "license": "Public Domain",
            "source_url": cfg["source_url"],
            "schema_version": str(SCHEMA_VERSION),
        }
        db.executemany("INSERT INTO metadata(key, value) VALUES (?, ?)", meta.items())

        entry_count = {}
        for e in entries:
            entry_count[e.usfm] = entry_count.get(e.usfm, 0) + 1
        book_rows = [
            (usfm, ORDINAL[usfm], NAME[usfm], TESTAMENT[usfm], entry_count[usfm])
            for usfm in sorted(entry_count, key=lambda u: ORDINAL[u])
        ]
        db.executemany(
            "INSERT INTO book(usfm, ordinal, name, testament, entry_count) "
            "VALUES (?, ?, ?, ?, ?)",
            book_rows,
        )

        db.executemany(
            "INSERT INTO entry(id, usfm, chapter, start_verse, start_key, "
            "end_key, heading, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            [
                (i + 1, e.usfm, e.chapter, e.start_verse,
                 e.start_key, e.end_key, e.heading, e.body)
                for i, e in enumerate(entries)  # id == canonical (reading) order
            ],
        )

        # External-content FTS5 index over commentary body text, keyed to id.
        db.executescript(
            """
            CREATE VIRTUAL TABLE entry_fts USING fts5(
              body,
              content='entry',
              content_rowid='id'
            );
            INSERT INTO entry_fts(rowid, body) SELECT id, body FROM entry;
            INSERT INTO entry_fts(entry_fts) VALUES ('optimize');
            """
        )
        db.commit()
        db.execute("VACUUM")
        db.commit()
    finally:
        db.close()
    return out


def main(argv):
    cid = argv[1] if len(argv) > 1 else "jfb"
    cfg = CONFIGS.get(cid)
    if cfg is None:
        sys.exit(f'Unknown commentary "{cid}". Known: {", ".join(CONFIGS)}')

    entries = []
    for rel in cfg["inputs"]:
        path = os.path.join(ROOT, rel)
        if not os.path.exists(path):
            sys.exit(f"Missing input {rel}")
        with open(path, encoding="utf-8") as f:
            entries.extend(parse_thml(f.read()))
    print(f"Parsed {len(entries)} commentary entries.")

    out = build_db(cfg, entries)
    kb = round(os.path.getsize(out) / 1024)
    print(f"Wrote {cfg['out']} ({kb}KB).")


if __name__ == "__main__":
    main(sys.argv)
