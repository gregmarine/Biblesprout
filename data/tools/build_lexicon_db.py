#!/usr/bin/env python3
"""Build the read-only `strongs.lexicon` SQLite source from the Strong's dictionaries.

Strong's *Exhaustive Concordance* (1890) is public domain; these are the Open
Scriptures transcriptions of its Hebrew/Aramaic and Greek dictionaries.

Sources (place both under `data/lexicons/`, see the URLs below):
  HebrewStrong.xml   openscriptures/HebrewLexicon — Strong's Hebrew & Aramaic.
                     Markup CC-BY 4.0; the dictionary text itself is public domain.
  strongsgreek.xml   openscriptures/strongs — Strong's Greek, Ulrik Petersen's
                     XML edition v1.4 (unzip StrongsGreekDictionaryXML_1.4.zip).

Run from the repo root:

    python3 data/tools/build_lexicon_db.py

Output: `data/lexicons/strongs.lexicon`, a per-source read-only DB in the same
shape as the `.bible` / `.commentary` files.

Keys are the same Strong's identifiers the BSB word layer stores in
`bsb.bible`'s `word.strongs` — 'H7225', 'G976' — so a tapped word joins straight
to its entry with no mapping table in between.
"""

import os
import re
import sqlite3
import sys
import xml.etree.ElementTree as ET

# --- paths ------------------------------------------------------------------
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, os.pardir, os.pardir))
sys.path.insert(0, HERE)
from build_bible_db import fix_spacing  # noqa: E402  (shared spacing normalizer)

LEX_DIR = os.path.join(ROOT, "data", "lexicons")
HEBREW_PATH = os.path.join(LEX_DIR, "HebrewStrong.xml")
GREEK_PATH = os.path.join(LEX_DIR, "strongsgreek.xml")
OUT_PATH = os.path.join(LEX_DIR, "strongs.lexicon")
SCHEMA_VERSION = 1

HEBREW_URL = ("https://raw.githubusercontent.com/openscriptures/HebrewLexicon/"
              "master/HebrewStrong.xml")
GREEK_URL = ("https://github.com/openscriptures/strongs/raw/master/greek/"
             "StrongsGreekDictionaryXML_1.4.zip")

# `xml:lang` on the Hebrew headword distinguishes the two OT languages.
_LANGUAGE_BY_TAG = {"heb": "Hebrew", "arc": "Aramaic"}

_ws = re.compile(r"\s+")


def clean(s):
    """Collapse whitespace, fix stray spacing; return None for anything empty.

    The dictionary sources carry the same loose spacing the USFM does — H7231's
    meaning is marked up as `to <def>cast together</def> , i.e.`, which reads as
    "to cast together , i.e." — so the Bible builder's normalizer is reused here.
    """
    if not s:
        return None
    s = fix_spacing(_ws.sub(" ", s)).strip()
    return s or None


def strip_ns(tag):
    return tag.rsplit("}", 1)[-1]


def flatten(el):
    """The readable text of an element, including EMPTY reference elements.

    The Greek dictionary carries both inline Greek words (`<greek unicode="ἄν"/>`)
    and cross-references to other entries (`<strongsref language="HEBREW"
    strongs="0175"/>`) as attributes of *empty* elements, so itertext() alone drops
    them — leaving prose like "of Hebrew origin ();" with a hole where the number
    should be. Both are rendered back into the text.
    """
    out = []
    if el.text:
        out.append(el.text)
    for child in el:
        tag = strip_ns(child.tag)
        if tag == "greek":
            out.append(child.get("unicode", ""))
        elif tag == "strongsref":
            out.append(strongsref_id(child))
        elif tag == "w" and child.get("src"):
            # The Hebrew lexicon's own cross-reference: <w src="H24">24</w> reads as
            # a bare "24". Render the full identifier so it matches the Greek side
            # (and the numbers the word layer stores).
            out.append(normalize(child.get("src")))
        else:
            out.append(flatten(child))
        if child.tail:
            out.append(child.tail)
    return "".join(out)


def strongsref_id(el):
    """'<strongsref language="HEBREW" strongs="0175"/>' -> 'H175'."""
    number = el.get("strongs")
    if not number:
        return ""
    prefix = "H" if (el.get("language") or "").upper() == "HEBREW" else "G"
    return normalize(f"{prefix}{number}")


# --- Hebrew -----------------------------------------------------------------
def parse_hebrew(path):
    rows = []
    root = ET.parse(path).getroot()
    for entry in root:
        if strip_ns(entry.tag) != "entry":
            continue
        key = entry.get("id")
        if not key:
            continue
        parts = {strip_ns(c.tag): c for c in entry}
        w = parts.get("w")
        lang = "Hebrew"
        lemma = translit = pron = pos = None
        if w is not None:
            lemma = clean(w.text)
            translit = clean(w.get("xlit"))
            pron = clean(w.get("pron"))
            pos = clean(w.get("pos"))
            tag = w.get("{http://www.w3.org/XML/1998/namespace}lang")
            lang = _LANGUAGE_BY_TAG.get(tag, "Hebrew")
        rows.append((
            normalize(key), lang, lemma, translit, pron, pos,
            clean(flatten(parts["source"])) if "source" in parts else None,
            clean(flatten(parts["meaning"])) if "meaning" in parts else None,
            clean(flatten(parts["usage"])) if "usage" in parts else None,
        ))
    return rows


# --- Greek ------------------------------------------------------------------
# Strong's prefixes its KJV usage with ":--" or "--"; drop the punctuation lead-in.
_kjv_lead = re.compile(r"^[:\-—\s]+")


def parse_greek(path):
    rows = []
    root = ET.parse(path).getroot()
    entries = root.find("entries")
    for entry in (entries if entries is not None else []):
        if strip_ns(entry.tag) != "entry":
            continue
        key = entry.get("strongs")
        if not key:
            continue
        parts = {}
        for child in entry:
            parts.setdefault(strip_ns(child.tag), child)
        g = parts.get("greek")
        lemma = clean(g.get("unicode")) if g is not None else None
        translit = clean(g.get("translit")) if g is not None else None
        p = parts.get("pronunciation")
        pron = clean(p.get("strongs")) if p is not None else None
        usage = None
        if "kjv_def" in parts:
            usage = clean(_kjv_lead.sub("", flatten(parts["kjv_def"])))
        rows.append((
            normalize("G" + key), "Greek", lemma, translit, pron, None,
            clean(flatten(parts["strongs_derivation"])) if "strongs_derivation" in parts else None,
            clean(flatten(parts["strongs_def"])) if "strongs_def" in parts else None,
            usage,
        ))
    return rows


def normalize(key):
    """'H0001'/'G00001' -> 'H1'; keeps any augment suffix ('H1234a')."""
    m = re.match(r"^([HG])0*(\d+)([a-z]?)$", key.strip())
    if not m:
        return key.strip()
    return f"{m.group(1)}{m.group(2)}{m.group(3)}"


SCHEMA_SQL = """
PRAGMA journal_mode = OFF;
PRAGMA synchronous = OFF;

CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT);

CREATE TABLE entry (
  strongs    TEXT PRIMARY KEY,   -- 'H7225' | 'G976' — joins bsb.bible word.strongs
  language   TEXT NOT NULL,      -- 'Hebrew' | 'Aramaic' | 'Greek'
  lemma      TEXT,               -- the dictionary headword
  translit   TEXT,
  pronounce  TEXT,
  pos        TEXT,               -- part of speech (Hebrew entries only)
  derivation TEXT,               -- where the word comes from
  definition TEXT,               -- Strong's definition
  usage      TEXT                -- how the KJV renders it
);
CREATE INDEX entry_language ON entry(language);
"""


def main():
    missing = [(p, u) for p, u in ((HEBREW_PATH, HEBREW_URL), (GREEK_PATH, GREEK_URL))
               if not os.path.exists(p)]
    if missing:
        lines = "\n".join(f"  {p}\n    from {u}" for p, u in missing)
        sys.exit(f"Missing lexicon source(s):\n{lines}")

    rows = parse_hebrew(HEBREW_PATH) + parse_greek(GREEK_PATH)

    seen = {}
    for r in rows:
        if r[0] in seen:
            print(f"  warning: duplicate entry {r[0]}")
        seen[r[0]] = r

    if os.path.exists(OUT_PATH):
        os.remove(OUT_PATH)
    db = sqlite3.connect(OUT_PATH)
    try:
        db.executescript(SCHEMA_SQL)
        db.executemany("INSERT INTO metadata(key, value) VALUES (?, ?)", [
            ("id", "strongs"), ("title", "Strong's Hebrew & Greek Dictionaries"),
            ("abbreviation", "Strong's"), ("type", "lexicon"), ("language", "en"),
            ("license", "Public Domain (1890); markup CC-BY 4.0 Open Scriptures"),
            ("source_url", HEBREW_URL), ("schema_version", str(SCHEMA_VERSION)),
        ])
        db.executemany(
            "INSERT INTO entry(strongs, language, lemma, translit, pronounce, pos, "
            "derivation, definition, usage) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            list(seen.values()))
        db.commit()
        db.execute("VACUUM")
    finally:
        db.close()

    by_lang = {}
    for r in seen.values():
        by_lang[r[1]] = by_lang.get(r[1], 0) + 1
    kb = round(os.path.getsize(OUT_PATH) / 1024)
    print("Entries: " + ", ".join(f"{n} {lang}" for lang, n in sorted(by_lang.items())))
    print(f"Wrote {OUT_PATH} ({kb}KB).")


if __name__ == "__main__":
    main()
