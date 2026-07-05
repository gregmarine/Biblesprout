# Biblesprout — Native Android

Native Android port of Biblesprout. Replaces the Flutter implementation kept (frozen) at
`../biblesprout_flutter/`.

## Status

**Data layer working on the BOOX Go 6.** The read-only content-DB layer is ported from Flutter
and verified end-to-end on-device: `bsb.bible` is bundled from `../../data`, installed to app
storage, opened plaintext through SQLCipher, and a verse-key range lookup (John 3:16) + FTS5
search both return correct results (see `MainActivity`'s smoke test). Next: the reader/library
UI, then the global read-write index (Room) for reading position and annotations.

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
