# Biblesprout (monorepo)

A Bible study app for e-ink devices (Onyx BOOX primary, Supernote later), sharing the
"feel like real paper, stay human" philosophy of Notesprout and Paintsprout. Handwritten
notes are a future phase and will reuse Notesprout lessons.

The app is being **ported from Flutter to native Android**. This repo is laid out as a
monorepo so the working Flutter version is preserved as a reference while the native app
is built alongside it.

## Layout

- `apps/biblesprout_flutter/` — the original Flutter app, **frozen** as a self-contained,
  buildable reference. See its own `CLAUDE.md` for the full architecture (reader, Find,
  Passage view, SQLite data layer, commentary, e-ink pagination). Not actively developed.
- `apps/biblesprout_android/` — the **native Android** port (in progress). Target dev
  device is the **BOOX Go 6** (`DAF86F61`); the Go 10.3 (`b7a46e13`) is used by another
  project — don't target it.
- `data/` — shared, canonical scripture and commentary content, consumed by the native app
  and the build tooling:
  - `data/bible/bsb_usfm/` — **source of truth**: the 66 official BSB USFM files (public
    domain, bereanbible.com `bsb_usfm.zip`, 3rd printing). Carries print formatting (poetry
    indent, paragraphs, headings), footnotes, cross-references and red-letter markup. To
    refresh, re-download the zip and unzip over this dir, then rebuild.
  - `data/bible/bsb_tables.tsv` — the **BSB translation tables** (public domain,
    bereanbible.com `bsb_tables.tsv`): a word-level reverse interlinear for the whole
    BSB — one row per original-language word with its Strong's number, transliteration
    and morphology, plus the English it was rendered as and that English's position in
    the verse. Source for the `.bible` **word layer**; required by the builder.
  - `data/bible/bsb.txt` — the older plain-text edition, kept only as a parity reference.
  - `data/bible/bsb.bible` — built read-only SQLite Bible DB (see the schema note below).
  - `data/tools/build_bible_db.py` — standalone builder: `bsb_usfm/` + `bsb_tables.tsv` → `bsb.bible`
    (`python3 data/tools/build_bible_db.py`; needs an FTS5-capable sqlite3 — stock macOS has
    it). Strips two known artifacts in the USFM export (`vvv`, `[’’]`) and normalizes a few
    source spacing typos; ~99.6% verse-text parity with `bsb.txt`, the rest being intentional
    3rd-printing edits (bracketed supplied words, `. . .` ellipses, spaced en-dashes).
    `fix_spacing()` normalizes the export's stray spacing ("Likewise , every") on each text
    run **before any offset is taken**, so the display blocks read like the plain text and every
    span stays consistent by construction — do **not** move it into `_bappend`/`append_body`,
    whose callers compute span ends from the string they passed in. It is shared with
    `build_lexicon_db.py`, whose sources have the same artifact.
  - `data/commentaries/*.xml` — CCEL ThML sources for Matthew Henry Concise (`mhcc`) and
    the six-volume Complete (`mhc1`…`mhc6`).
  - `data/commentaries/{mhcc,mhc}.commentary` — built read-only SQLite commentary DBs.
  - `data/lexicons/{HebrewStrong,strongsgreek}.xml` — Open Scriptures transcriptions of
    Strong's Hebrew/Aramaic and Greek dictionaries (1890, public domain).
  - `data/lexicons/strongs.lexicon` — built read-only SQLite lexicon DB, keyed by the same
    `H7225`/`G976` identifiers the `.bible` word layer stores, so a tapped word joins
    straight to its entry. Build with `python3 data/tools/build_lexicon_db.py`.
- `docs/` — **live**, repo-wide design docs. `docs/eink-constraints.md` (color, motion,
  refresh, dialogs, touch targets) applies to **every** Biblesprout UI including the native
  app — read it before building new UI. `docs/backlog.md` tracks direction. (A frozen copy
  of these as they stood at the split lives under `apps/biblesprout_flutter/docs/`.)

## Data model (carried over to native)

Content lives in **per-source, read-only** SQLite databases (one file per work): Bibles as
`*.bible`, commentaries as `*.commentary`. User-generated data + the source registry live in
a single read-write **global index** (`biblesprout.db` in the Flutter app).

Everything cross-references scripture by a **canonical verse key**: an integer
`ordinal*1_000_000 + chapter*1_000 + verse` (canon ordinal 1..66). Keys sort in reading
order, so any chapter/verse range is one contiguous `BETWEEN`. Books are keyed by **USFM
code** (`GEN`…`REV`). The 66-book canon (USFM ↔ ordinal ↔ name ↔ aliases) is the single
source of truth for ordering and name resolution. FTS5 is required for search, so the
engine must bundle it (the Android system SQLite often lacks FTS5).

The built `.bible`/`.commentary` DBs in `data/` are the same artifacts the Flutter app
bundles; the native app can consume them directly rather than rebuilding from source.

### `.bible` schema (v3 — rich display layer + word layer)

`bsb.bible` keeps the original **plain layer** verbatim so existing consumers are unaffected:
`metadata`, `book`, `verse` (`verse_key`, `usfm`, `chapter`, `verse`, `text` — clean,
markup-free), and `verse_fts` (FTS5 external-content over `verse.text`). Search runs on the
clean text, so it never trips on formatting, footnote text or reference labels.

Alongside it a **display layer** encodes print formatting and interactivity, addressed by
**character offsets into block text** (the Kotlin reader turns these straight into
`StaticLayout` spans; offsets are code-point indices == UTF-16 since the text is all BMP):
- `block` — the chapter's render stream in document order (`id` == reading order): one row
  per paragraph / poetry line / heading / stanza break. `kind` is the USFM marker (`p`,
  `pmo`, `q1`, `q2`, `b`, `s1`, `d`, `r`, `li1`, …); `content` is the display text (verse
  numbers inlined, footnote callers not); `start_key` is the first verse whose text appears.
- `verse_marker` (block_id, start, end, verse_key, number) — the superscript verse-number
  span within a block.
- `redletter` (block_id, start, end) — words-of-Jesus (`\wj`) spans (monochrome on e-ink, so
  a style choice, not color).
- `footnote` (id, block_id, offset, verse_key, label, text) — caller insertion point in a
  block + the popup body (clean text). **Tap → popup.**
- `xref` (source_kind `block|note`, source_id, start, end, target_start_key, target_end_key)
  — a cross-reference link span (in a block, or inside a footnote body) with its resolved
  target verse-key range. **Tap → navigate.**

The **word layer** (v3, from `bsb_tables.tsv`) sits in the same address space, so an
English word maps to the original behind it. **Long-press → word study.**
- `word` (verse_key, sort, block_id, start, end, strongs, form_id, morph_id) — one row per
  original-language word, `sort` being its place in the verse's English order. The span is
  the English it was rendered as; `strongs` (`H7225`/`G976`) joins `strongs.lexicon`.
  `block_id` is **NULL** when the BSB renders the word with no English of its own (the
  Hebrew direct-object marker, say) — such a word is untappable but still carried, since an
  interlinear view wants it. ~444k words; 386,043 of 386,062 glossed words are placed.
  The 19 that aren't, plus the handful of verses with no rows (`NEH 7:68`), are real
  textual divergences where the BSB follows a DSS/LXX reading the tables' WLC base lacks
  (`DEU 32:43`, `PSA 145:13`) — not alignment bugs.
- `form` (id, original, translit, language) and `morphology` (id, code, text) — interned:
  ~3.8k parsings cover ~444k words, so sharing them keeps `bsb.bible` at ~56MB instead of
  ~86MB. Join through `word.form_id` / `word.morph_id`.

Psalm superscriptions are `\d \v 1 ...` — verse 1 in the original, and glossed by the
tables, but deliberately **outside** `BODY_KINDS` so they never reach `verse.text`/FTS.
The builder tracks them in a separate `wparts` stream (`WORD_KINDS`) so their Hebrew is
still tappable; that distinction is why `verse.text` and the word layer disagree on what
a verse contains.

The reader renders from the `block` layer: `BibleDatabase.blocksForChapter()` →
`ChapterPaginator.atomsForBlocks()` produces the atom stream, with `BreakAtom`/`HeadingAtom`
carrying print structure (poetry indent, paragraph breaks, stanza breaks, centered section
headings). `ReaderTypography.build()` is the single source of truth mapping atoms → the drawn
`SpannableStringBuilder` **and** the char `Mark`s that hit-testing/highlighting retrace — so
selection survives the formatting. **Paragraph spans (alignment, `LeadingMarginSpan`) must be
`EXCLUSIVE_EXCLUSIVE`**, or an inclusive end at the buffer tail grows into every later append.
Footnotes are wired: `atomsForBlocks` splices a `FootnoteAtom` (superscript `*` caller) at each
`footnote.offset`; a tap is hit-tested via `footnoteAtOffset` and shown by `FootnotePopup` (a
hard-bordered, scrimless, immersive-preserving panel — kept full-screen + `FLAG_NOT_FOCUSABLE`
so taps don't leak and the BOOX system bars stay hidden). Cross-references are wired too
(`xrefsForChapter()`): `source_kind='block'` spans (in `\r` parallel-passage headings) become
underlined links carried on `HeadingAtom.links`, marked by `build()` and hit-tested via
`xrefAtOffset`; `source_kind='note'` spans (citations inside a footnote body) render as
underlined `ClickableSpan`s in `FootnotePopup`. Tapping either opens the referenced
`target_start_key..target_end_key` range in the **passage view** (`PassageActivity.intent(startKey,
endKey)`) — shown like a search result, not jumped straight into the reader. `PassageActivity`
(reached from a typed reference in Find, or a tapped cross-reference) carries a **"Full chapter"**
toolbar action that opens the reader at the passage's start verse, landing on that verse's page
via `EXTRA_START_VERSE`. Rebuild the DB with `data/tools/build_bible_db.py` after editing
`bsb_usfm/`.

**Word study** is the reader's **long-press** (it used to open verse commentary; a word sits in a
verse, so `WordPopup` offers "Commentary on this verse" as an action instead — nothing was lost,
and the toolbar still opens the whole chapter's commentary). `WordAtom` carries its own
`blockId`/`blockStart`/`blockEnd`, which is how a press reaches the word layer: hit-test the drawn
layout (`ReaderTypography.wordAtomAtOffset`, an exact hit — **no** nearest-word fallback, so a
press on whitespace does nothing), then find the `word` row whose span contains the atom's
`blockStart`. `BibleDatabase.wordsForChapter()` preloads the chapter's words (~550 rows, ~1.5ms)
and `substr`s each span's English out of `block.content`, so the panel's header shows the whole
English one original word became ("In the beginning"), not just the word pressed. The words carry
**no decoration** — the affordance is invisible until asked for, which is what keeps the page
looking like print (see `docs/eink-constraints.md`).

`WordPopup` **must** pin `textDirection = TEXT_DIRECTION_LTR` and wrap Hebrew/Aramaic runs in
`BidiFormatter.unicodeWrap` (`bidi()`). Without both, Android reads the line's first strong
character, lays the whole paragraph out RTL, and drags neighbouring punctuation to the wrong end
("ray-sheeth'" renders as "('ray-sheeth").

The **passage view** long-presses the same way: `BibleDatabase.verseSlicesForRange()` returns each
verse as the block spans its text occupies, so `PassageActivity.buildBlocks` tokenizes words that
carry a block address (it used to split flat `verse.text`, which had none). It still renders as
flowing prose — no poetry indent — because it emits only number/word atoms. That slicing mirrors
the builder: **a `\v` marker moves the current verse in every block kind, but only `BODY_KINDS`
contribute text**. Psalms are why — the superscription is a `\d` block holding verse 1's marker
whose text is deliberately absent from `verse.text`, so skipping the block wholesale would leave
the psalm's first poetry line attributed to the last verse of the *previous* psalm.

## Native Android (`apps/biblesprout_android/`)

Stack mirrors `apps/notesprout_android` so the two build identically: Kotlin 2.2.20, AGP
8.11.1, **Views + ViewBinding** (not Compose), **minSdk 29 / targetSdk 35 / compileSdk 35**,
JDK 17 (temurin), arm64-v8a only. Data layer is **Room** (2.7, KSP) over **SQLCipher**
(`net.zetetic:sqlcipher-android`) — the same engine as Notesprout. The read-only Bible/
commentary DBs are opened **plaintext** (empty password); SQLCipher's amalgamation bundles
**FTS5**, which the BOOX's system SQLite can't be assumed to have. `BiblesproutApplication`
`System.loadLibrary("sqlcipher")` once at startup. (FTS5-on-device is verified — a scaffold
smoke test in `MainActivity` runs an in-memory FTS5 query.) The Onyx BOOX SDK is **not** a
dependency yet; add `onyxsdk-device` when wiring e-ink refresh control.

`ContentInstaller` copies the bundled DBs into app storage on first run, keyed by a `<name>.stamp`
holding the APK's `lastUpdateTime` + asset size. **Size alone can't detect a content change** —
SQLite pads to whole pages, so rebuilding a DB with a small fix lands on the identical byte count
and the old copy silently survives on device. If a `data/` rebuild seems not to reach the BOOX,
that's the first thing to suspect (`adb shell run-as <pkg> ls -l files/content/`).

`applicationId` is `com.symmetricalpalmtree.biblesprout` (replaces the Flutter app); the
**debug** build appends `.dev`. The whole app runs full-screen immersive
(`WindowInsetsControllerCompat.hide(systemBars)`), so there is no system Back — every screen
must carry its own on-screen back control (carry this constraint forward from Flutter).

**Build / install / launch on the BOOX Go 6 (`DAF86F61`):**
```sh
export PATH="$PATH:/Users/gregmarine/development/android-sdk/platform-tools"
cd apps/biblesprout_android
./gradlew :app:assembleDebug
adb -s DAF86F61 install -r app/build/outputs/apk/debug/app-debug.apk
# A FRESH install lands disabled/stopped on this BOOX (enabled=3); enable it ONCE:
adb -s DAF86F61 shell pm enable com.symmetricalpalmtree.biblesprout.dev
adb -s DAF86F61 shell monkey -p com.symmetricalpalmtree.biblesprout.dev 1   # launch
```
- `am start -n <pkg>/<cls>` fails with a **bogus "class does not exist"** on this BOOX (same
  quirk the Flutter app hit) — use `monkey` to launch, never `am start`.
- Fresh installs need the one-time `pm enable <pkg>` above or `monkey`/`am` report "No
  activities found to run". Component-level `pm enable <pkg>/<cls>` throws SecurityException
  (shell can't set component state) — the package-level enable is what's needed.
- Screenshot: `adb -s DAF86F61 shell screencap -p /sdcard/s.png && adb -s DAF86F61 pull /sdcard/s.png s.png`
  (piping `screencap` over `exec-out` can emit an error string on BOOX).

## BOOX device gotchas (device, not framework)

- `adb` lives at `/Users/gregmarine/development/android-sdk/platform-tools` (not on PATH).
- **Launch:** `am start -n <pkg>/.MainActivity` fails with a bogus "class does not exist"
  on this BOOX. Use `adb -s DAF86F61 shell monkey -p <pkg> 1` **without** `-c LAUNCHER`.
- **screencap:** `screencap -p` over `exec-out` can emit an error string on BOOX; capture
  to a file then pull:
  `adb -s DAF86F61 shell screencap -p /sdcard/shot.png && adb -s DAF86F61 pull /sdcard/shot.png shot.png`
- **font_scale = 0.85:** the BOOX applies a system text scale of 0.85; any manual text
  layout math must account for it.
- The second connected adb device (Wacom `DTHA116`) is a pen display, not a target.
