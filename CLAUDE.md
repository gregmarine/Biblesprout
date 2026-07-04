# Biblesprout

A Bible study app in Flutter, targeting e-ink devices (Onyx BOOX primary, Supernote later).
Current dev/test device is the **BOOX Go 6** (`DAF86F61`); the Go 10.3 (`b7a46e13`) is in use
for another project — don't target it. Shares the "feel like real paper, stay human"
philosophy of Notesprout and Paintsprout. Handwritten notes are a future phase and will reuse
Notesprout lessons.

## Reader (complete)

Display Berean Standard Bible text on e-ink with page-flipping and a table of contents.

- **Library** (`lib/screens/library_screen.dart`) — 66 books grouped OT/NT + "Continue reading".
- **Chapters** (`lib/screens/chapters_screen.dart`) — chapter-number grid per book.
- **Reader** (`lib/reader/reader_screen.dart`) — paginated chapter text; each chapter starts
  on a fresh page with a book/chapter heading; paragraph flow with superscript verse numbers;
  page turns via swipe **and** left/right tap thirds (center third opens Contents); flows
  across chapter/book boundaries; instant swaps with a black full-refresh flash every 6 turns
  to clear e-ink ghosting; remembers last position. Optional `startVerse` opens on the page
  containing a given verse (used by search). Top bar has a back arrow (pop one) and "Contents"
  (jump to library).
- **Find** (`lib/screens/find_screen.dart`) — one smart input reached from the library header.
  One or more references (`John 3:16`; `Gen 1:5-10`; `John 3:14-17, Acts 1:3`) open the
  **Passage** view (`ReferenceParser.parseAll` splits comma/semicolon-separated refs across
  books, resolved in typed order); anything else runs FTS5 search, shown as a paginated list of
  rows that open the reader on that verse's page.
- **Passage** (`lib/screens/passage_screen.dart`) — jump-to-passage rendered like the chapter
  reader (not a list): a "John 3" heading, superscript verse numbers, verses flowing together;
  a fresh heading is inserted inline where the passage crosses a chapter/book; paginates if
  long. `lib/reader/passage_paginator.dart` packs heading + text blocks into pages, reusing
  `Paginator.fitCount`/`measureHeight` so heights match the reader.

## Data layer (SQLite)

Content lives in **per-source, read-only** databases (one file per work): Bibles as `*.bible`
(e.g. `bsb.bible`), commentaries as `*.commentary` (e.g. `mhcc.commentary`). User-generated
data and the source registry live in a single read-write **global index**, `biblesprout.db`.
All use `sqflite_common_ffi` + `sqlite3_flutter_libs` (a bundled SQLite so **FTS5 is always
available** — the Android system library often lacks it).

Everything cross-references scripture by a **canonical verse key**: an integer
`ordinal*1_000_000 + chapter*1_000 + verse` (canon ordinal 1..66). Keys sort in reading order,
so any chapter/verse range is one contiguous `BETWEEN`, and a note in the index points at the
same integer a source does. Books are keyed by **USFM code** (`GEN`…`REV`).

## Architecture

- `lib/models/bible.dart` — `Bible → Book → Chapter → Verse`; `ChapterRef` + `next/previous`
  for continuous reading across book boundaries. Still the in-memory shape the reader consumes.
- `lib/data/canon.dart` — the 66-book canon table (USFM ↔ ordinal ↔ name ↔ aliases). Single
  source of truth for ordering and for resolving typed/source book names ("Ps", "1 Cor",
  "Song of Songs", "Psalm"). Used by the build tool, the parser, and position migration.
- `lib/models/reference.dart` — `VerseKey` (pack/unpack), `VerseRange`, `Passage`, and
  `ReferenceParser`. Parses `John 3:14-16,18`, `Gen 1:5-2:3`, whole chapters, abbreviations,
  Roman numerals; `Passage.format()` renders back a tidy canonical string.
- `lib/data/bible_database.dart` — read-only accessor for a `*.bible` source: rebuilds the
  in-memory `Bible`, plus `search()` (FTS5), `versesInRange()` and `versesForPassage()`.
- `lib/data/commentary_database.dart` — read-only accessor for a `*.commentary` source
  (verse-range comment entries): `entriesForVerse()`/`entriesForRange()` by verse-key
  containment + FTS5 `search()`. **Built but not yet wired into the app/bootstrap** — a source
  DB + accessor + tests exist; showing commentary in the UI is a follow-up.
- `tool/build_commentary_db.dart` — dev-time builder for `*.commentary` from CCEL ThML
  (`dart run tool/build_commentary_db.dart mhcc`). `assets/commentaries/mhcc.xml` (Matthew
  Henry's Concise, public domain) → `assets/commentaries/mhcc.commentary`. The parser is a
  document-order state machine keyed on `<scripCom>` markers; it trusts the `<div1>` book
  heading over `osisRef` (which mis-tags e.g. Jude→Judg) and falls back to whole-chapter prose
  where a marker is missing (e.g. Psalm 23), giving full 1189/1189-chapter coverage. The
  Complete commentary (six volumes) can be added under the same tool later.
- `lib/data/app_database.dart` — the global index (`biblesprout.db`): source registry +
  reading progress (wired), and schema for bookmarks/highlights/notes/cross-links (not yet
  exercised). All annotations address scripture by verse-key spans (`start_key`/`end_key`).
- `lib/data/app_services.dart` — `bootstrap()`: inits the bundled SQLite, copies `bsb.bible`
  to writable storage, opens both DBs, registers the source, and does a one-time import of the
  old `shared_preferences` position into `biblesprout.db`.
- `tool/build_bible_db.dart` — dev-time `bsb.txt → assets/bible/bsb.bible` builder (run
  `dart run tool/build_bible_db.dart`). Uses the `sqlite3` dev dep (compiles its own FTS5).
- `lib/reader/paginator.dart` — the core. Splits a chapter into `Atom`s (verse number / word)
  and binary-searches, via `TextPainter`, the most atoms that fit each page. Measurement and
  rendering share one `StrutStyle(forceStrutHeight)` so heights agree.
- `lib/services/reading_position.dart` — the reader's last position, backed by
  `biblesprout.db` (translates the reader's `bookIndex` ↔ canonical USFM code).
- `lib/theme/eink_theme.dart` — pure black on white, no ripples, no route transitions.

The app runs **full-screen immersive** (`SystemUiMode.immersiveSticky` in `main.dart`): no
Android status or nav bar. Because there is no system Back, **every pushed screen must provide
its own on-screen back control** (see the reader/passage/chapters/find top bars).

E-ink design rules for **all** screens/dialogs/buttons/widgets (color, motion,
refresh, dialogs, touch targets) live in `docs/eink-constraints.md`. Read it
before building new UI; the theme enforces only part of it.

The BSB text is public domain (bereanbible.com). `assets/bible/bsb.txt` (tab-delimited
`Book Chapter:Verse<TAB>text`, 2 metadata + 1 header line, UTF-8 BOM) stays in the repo as the
human-editable source of truth but is **no longer bundled** — only the built `bsb.bible` is.
To refresh: replace `bsb.txt` from https://bereanbible.com/bsb.txt, then rebuild the DB.

## Running on the BOOX (device-specific gotchas)

Flutter is **not on PATH**; it lives at `/Users/gregmarine/development/flutter/bin`. `adb` is at
`/Users/gregmarine/development/android-sdk/platform-tools`.

```sh
export PATH="$PATH:/Users/gregmarine/development/flutter/bin:/Users/gregmarine/development/android-sdk/platform-tools"
flutter build apk --debug
adb -s DAF86F61 install -r build/app/outputs/flutter-apk/app-debug.apk   # DAF86F61 = BOOX Go 6
adb -s DAF86F61 shell monkey -p com.symmetricalpalmtree.biblesprout 1    # launch (flaky; see below)
# screencap over exec-out can emit an error string on BOOX; capture to file then pull:
adb -s DAF86F61 shell screencap -p /sdcard/shot.png && adb -s DAF86F61 pull /sdcard/shot.png shot.png
```

- **Launch:** `am start -n com.symmetricalpalmtree.biblesprout/.MainActivity` fails with a bogus "class does
  not exist" on this BOOX. Use `monkey -p com.symmetricalpalmtree.biblesprout 1` **without** a
  `-c LAUNCHER` category (adding the category makes monkey abort).
- **font_scale = 0.85:** the BOOX applies a system text scale of 0.85. Any `TextPainter` used
  for layout math **must** be passed `MediaQuery.textScalerOf(context)`, or measured heights
  won't match rendered text (pagination will under- or over-fill). See `_textScaler` in the
  reader. A `_safetyPad` at the bottom of each page absorbs residual sub-pixel rounding.
- The second connected adb device (Wacom `DTHA116`) is a pen display, not a target.

## Verify a change

`flutter analyze && flutter test`, then build/install/launch and screenshot as above.
