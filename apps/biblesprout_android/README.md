# Biblesprout — Native Android

Native Android port of Biblesprout. Replaces the Flutter implementation kept (frozen) at
`../biblesprout_flutter/`.

## Status

Scaffolding not yet started. Decisions to make before generating the project:

- **UI toolkit** — Jetpack Compose vs. Views.
- **Package id** — the Flutter app uses `com.symmetricalpalmtree.biblesprout`. Two apps
  can't share that id on one device at once, so during parallel install this app may need a
  temporary suffix (e.g. `.native`).
- **Min SDK** — driven by the BOOX Go 6's Android version.
- **SQLite / FTS5** — the reader relies on FTS5, which the Android system SQLite often
  lacks. Bundle an engine that includes it (e.g. `requery/sqlite-android`) rather than
  `android.database.sqlite`.
- **Content bundling** — consume the prebuilt read-only DBs in `../../data/` (`bsb.bible`,
  `mhcc.commentary`, `mhc.commentary`); the global read-write index (`biblesprout.db`) is
  created on device.

See the root `CLAUDE.md` for the shared data model and BOOX device gotchas, and
`../../docs/eink-constraints.md` for the e-ink UI rules that apply here too.
