# Biblesprout — Native Android

Native Android port of Biblesprout. Replaces the Flutter implementation kept (frozen) at
`../biblesprout_flutter/`.

## Status

**Scaffold up and running on the BOOX Go 6.** Empty `MainActivity` builds, installs, launches
full-screen immersive, and a smoke test confirms SQLCipher's bundled **FTS5** works on-device.
Next: port the reader/library UI and wire the `../../data/` content DBs through Room.

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
