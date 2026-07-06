# Biblesprout — Native Android

Native Android port of Biblesprout. Replaces the Flutter implementation kept (frozen) at
`../biblesprout_flutter/`.

## Status

**Reader + Find + Commentary + Bookmarks + Highlights working end-to-end on the BOOX Go 6.**
Library → chapters grid → paginated reader, Find → passage/search, commentary (chapter, passage,
and verse-anchored), bookmarks, and highlights all run and verified on-device. The library
(`MainActivity`, `ui/SwipePager`, `data/AppServices`) paginates the 66 books OT/NT with a "Continue
reading" banner from the index; `ui/ChaptersActivity` is the paginated chapter-number grid;
`ui/ReaderActivity` + the `reader/` package are the paginated reader — superscript verse numbers, a
book/chapter heading on each chapter's first page, tap-thirds / swipe page turns that flow across
chapter and book boundaries, position persistence, and a black full-refresh flash every 6 turns.
Both Matthew Henry commentaries (Concise + the six-volume Complete) are bundled in the APK —
sideload-only for now, so the downloadable-sources model is deferred. Next: notes.

### Commentary — `ui/CommentaryActivity`, `reader/CommentaryLauncher`, `data/CommentaryDatabase`

Both Matthew Henry commentaries ship in the APK — the Concise (`mhcc`, ~6 MB) and the six-volume
Complete (`mhc`, ~50 MB); while the app is sideload-only the large DB is fine bundled. A "Notes"
affordance in the reader and passage top bars opens commentary for the chapter / passage in view,
and a **long-press** on any verse opens commentary for just that verse (the superscript number is
too small a tap target on e-ink, and tap/swipe are already page turns). With both installed, the
launcher shows a scrimless bordered picker on first use and remembers the choice; the commentary
screen offers "Change".

- `data/CommentaryDatabase` addresses comments by the same canonical verse keys as the Bible, so
  `entriesForRange()` answers "what covers this span" with an overlap test. `AppServices` installs
  and opens every bundled commentary, registers each as a source, and exposes them plus
  `CommentaryPreferences` (last-used id, persisted in the index's key/value store).
- `reader/CommentaryLauncher` is the shared entry point: with >1 commentary installed it opens the
  last-used one directly and shows a hard-bordered, scrimless picker only on first use (with a
  single commentary the picker and "Change" action never appear). It gathers de-duplicated entries
  over the given ranges and starts `ui/CommentaryActivity`, which re-resolves and renders them
  through the same `PassagePaginator` + `FlowingView` as the passage view (a comment's section
  heading — "Verses 1–5" — introduces its prose).
- Verse anchoring: a press is hit-tested against the drawn `StaticLayout`
  (`getOffsetForHorizontal`) and mapped back to a verse key by `ReaderTypography.verseKeyAtOffset`,
  which retraces the exact character offsets the reader's spannable lays down. The passage view's
  `FlowingView` first finds which stacked text element the press fell in.

### Find — `ui/FindActivity`, `ui/PassageActivity`, `data/Reference.kt`

One smart input reached from the library header. On submit the text is parsed as one or more
scripture references (`ReferenceParser.parseAll` — `John 3:16`, `Gen 1:5-10`, `Psalm 23`,
`John 3:14-17, Acts 1:3`, spaceless/abbreviated/roman-numeral forms); if it resolves to real
verses it opens the flowing **passage view**, otherwise it runs FTS5 search shown as a paginated
list of verse rows that open the reader on that verse's page (`ReaderActivity` gained an
`EXTRA_START_VERSE` that lands on the page containing the verse).

- `data/Reference.kt` — `Passage` (book + verse ranges, `startKey`/`endKey`/`format()`) and
  `ReferenceParser` (`parse`/`parseAll`/`parseSpec`); a bare number after a reference continues
  the prior book. Unit-tested in `ReferenceParserTest.kt`.
- `ui/PassageActivity` — renders a passage like the reader (not a list): a "John 3" heading,
  superscript verse numbers, verses flowing together, a fresh inline heading where the passage
  crosses a chapter or book. Paginates if long; same tap-thirds / swipe / flash affordances.
- `reader/PassagePaginator` + `reader/FlowingView` — pack heading + text blocks into pages
  (heading kept with its first text block) and draw the stacked flow. Because native measures
  and draws the same `StaticLayout`, no per-block line reserve is needed (unlike Flutter's
  `passage_paginator.dart`, which reserved a line per block to bound `RenderParagraph` drift).

### Reader — `reader/` package

The paginator's correctness trick differs from Flutter's (which forced a `StrutStyle` so its
separate `TextPainter` measurement and `RenderParagraph` render agreed): here the paginator
measures and `ReaderView` draws the **same `StaticLayout`**, so they're identical by
construction — a page that measures as fitting always renders without overflow.
`LineHeightSpan.Standard` pins every line to one body line-height (so a superscript number never
grows its line), and sizes are in **sp** so the BOOX's 0.85 font scale is honoured automatically.

- `Atom` / `ChapterPaginator` — flatten a chapter to verse-number + word atoms, binary-search
  the most atoms that fit each page (first page reserves the heading).
- `ReaderTypography` — fonts, sizes, spannable building (superscript spans), StaticLayout config.
- `ReaderView` — draws a `ReaderPage` (optional heading + body) with the reader's padding.
- Pagination runs off the main thread (`Dispatchers.Default`); a long-press opens verse-anchored
  commentary (see the Commentary section).

### Bookmarks — `ui/BookmarksActivity`, `data/index` `Bookmark`

Verse-level bookmarks, the first annotation feature. A ribbon toggle in the reader top bar
bookmarks the current page's **anchor (top) verse** — its first verse number, or the verse carried
over when a page opens mid-verse (`ReaderActivity.computeAnchors`). The ribbon shows filled vs.
outline for the current page's state. A ribbon icon in the library header opens
`ui/BookmarksActivity`: a paginated list (reference + verse snippet, derived from the key against
the open Bible so nothing translation-specific is stored) in canonical order; tapping a row opens
the reader on that verse's page (reusing `EXTRA_START_VERSE`), and a trailing ✕ removes it.

- `Bookmark` entity (`bookmark` table, unique index on `verse_key` so toggling is idempotent) +
  `BookmarkDao`. Adding it bumped `AppIndexDatabase` to **v2** with a hand-written `MIGRATION_1_2`
  that creates the table (its CREATE must match Room's generated schema exactly) — the reading
  position and settings survive the upgrade rather than being wiped.

### Highlights — `ui/ReaderActivity` (highlight mode), `data/index` `Highlight`

Word/phrase highlights within a verse, drawn as **heavy underlines** (no color exists on e-ink —
`ReaderView` draws them under the body layout's line geometry). A pencil in the reader top bar
enters **highlight mode**; long-press stays commentary. In mode a bottom bar guides "tap the first
word, then the last" — tap-to-select rather than drag handles, since dragging smears on an EPD and
the constraints doc bans motion. The selected span previews live; Save persists it, Done exits; a
tap inside an existing highlight offers Remove. Highlights persist across process death (loaded per
chapter on open) and survive repagination.

- `Highlight` entity stores `verse_key` + inclusive `start_word..end_word` (0-based over the verse's
  words); the word span is pagination-independent, so a highlight underlines the same words however
  the text re-wraps. `AppIndexDatabase` is at **v3** (`MIGRATION_2_3` adds the `highlight` table).
- The mapping between a tap / a stored word span and the drawn `StaticLayout` lives in
  `ReaderTypography` (`wordAtOffset`, `highlightRanges`) — both retrace the exact character offsets
  `spannable` lays down, seeded per page by `ReaderActivity.computeSeeds` so a verse split across
  pages keeps counting its words.

### Global index — `data/index/` (Room)

The read-write `biblesprout.db` via **Room over the framework SQLite** (plaintext; the index
needs no FTS5 and holds no sensitive data — the read-only content DBs use SQLCipher instead).

- Entities `AppSetting`, `Source`, `ReadingProgress`, `Bookmark`, `Highlight`; DAOs for each;
  `AppIndexDatabase` (schema **v3**, with per-feature migrations that each add one annotation table).
- `ReadingPositionStore` — translates the reader's 0-based `bookIndex` ↔ canonical USFM.
- Wired through `AppServices` (opens the index, registers the BSB + commentary sources, exposes
  `readingPosition`). Verified: a saved position survives process death and lights the banner.
- The remaining annotation tables (note/cross_link) are deferred until the feature that writes
  them, so their columns settle then.

### `data/` package

- `VerseKey` / `VerseRange` — canonical verse-key packing (`ordinal*1e6 + chapter*1e3 + verse`).
- `Canon` / `CanonBook` / `Testament` — the 66-book table, USFM/alias resolution.
- `BibleDatabase` — opens a `.bible` plaintext; `loadBible()`, `search()` (FTS5),
  `versesInRange()`, `versesForRanges()`. Returns `VerseHit`s.
- `CommentaryDatabase` — opens a `.commentary` plaintext; `entriesForVerse()`,
  `entriesForRange()`, `search()`. Returns `CommentaryEntry`s. Exercised on-device via the two
  bundled Matthew Henry commentaries (see the Commentary section).
- `ContentInstaller` — copies bundled asset DBs into writable storage (the seam the future
  downloadable-sources model plugs into).
- Content DBs use **raw SQLCipher, not Room**: Room validates its schema against the file and
  chokes on the prebuilt FTS5 shadow tables. Room is for the global read-write index only.
- Pure-Kotlin unit tests: `app/src/test/.../DataLayerTest.kt` (`./gradlew test`).

## Stack (mirrors `../notesprout_android`)

- Kotlin 2.2.20 · AGP 8.11.1 · JDK 17 · minSdk 29 / targetSdk 35 / compileSdk 35 · arm64 only.
- **Views + ViewBinding** (not Compose).
- **Room** (2.7, KSP) over **SQLCipher** (`net.zetetic:sqlcipher-android`). Read-only Bible/
  commentary DBs are opened **plaintext** (empty password); SQLCipher's SQLite includes FTS5,
  which the reader's search needs and the BOOX system library can't be assumed to provide.
  `BiblesproutApplication` loads the `sqlcipher` native lib at startup.
- `applicationId` `com.symmetricalpalmtree.biblesprout` (replaces the Flutter app); debug adds
  `.dev`. The Onyx SDK is not yet a dependency — add `onyxsdk-device` for e-ink refresh control.

## Content

Consume the prebuilt read-only DBs in `../../data/` (`bible/bsb.bible`,
`commentaries/mhcc.commentary`, `commentaries/mhc.commentary`); the global read-write index
(`biblesprout.db`) is created on device.

## Build / run

See the root `CLAUDE.md` for the full build/install/launch recipe and BOOX gotchas (notably:
`am start` is broken here — launch with `monkey`; a fresh install needs a one-time
`pm enable`). E-ink UI rules that apply here too live in `../../docs/eink-constraints.md`.
