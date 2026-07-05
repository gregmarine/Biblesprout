# Biblesprout — Native Android

Native Android port of Biblesprout. Replaces the Flutter implementation kept (frozen) at
`../biblesprout_flutter/`.

## Status

**Library + reading-position index working on the BOOX Go 6.** The data layer (below), the
paginated table-of-contents home screen, and the global read-write index are all up and verified.
The library (`MainActivity`, `ui/SwipePager`, `data/AppServices`) paginates the 66 books OT/NT —
swipe or footer arrows turn pages — in the e-ink black/white/serif style, with a "Continue
reading" banner fed by the index. Next: chapters + reader (which will replace the interim
book-tap-saves-position behavior with real navigation). Search is still a placeholder toast.

### Global index — `data/index/` (Room)

The read-write `biblesprout.db` via **Room over the framework SQLite** (plaintext; the index
needs no FTS5 and holds no sensitive data — the read-only content DBs use SQLCipher instead).

- Entities `AppSetting`, `Source`, `ReadingProgress`; DAOs for each; `AppIndexDatabase`.
- `ReadingPositionStore` — translates the reader's 0-based `bookIndex` ↔ canonical USFM.
- Wired through `AppServices` (opens the index, registers the BSB source, exposes
  `readingPosition`). Verified: a saved position survives process death and lights the banner.
- Annotation tables (bookmark/highlight/note/cross_link) are deferred until the feature that
  writes them, so their columns settle then.

### `data/` package

- `VerseKey` / `VerseRange` — canonical verse-key packing (`ordinal*1e6 + chapter*1e3 + verse`).
- `Canon` / `CanonBook` / `Testament` — the 66-book table, USFM/alias resolution.
- `BibleDatabase` — opens a `.bible` plaintext; `loadBible()`, `search()` (FTS5),
  `versesInRange()`, `versesForRanges()`. Returns `VerseHit`s.
- `CommentaryDatabase` — opens a `.commentary` plaintext; `entriesForVerse()`,
  `entriesForRange()`, `search()`. Returns `CommentaryEntry`s. (Written; not yet exercised
  on-device — no commentary is bundled while those large DBs await the download model.)
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
