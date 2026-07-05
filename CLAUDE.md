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
  - `data/bible/bsb.txt` — human-editable source of truth (public domain, bereanbible.com).
  - `data/bible/bsb.bible` — built read-only SQLite Bible DB.
  - `data/commentaries/*.xml` — CCEL ThML sources for Matthew Henry Concise (`mhcc`) and
    the six-volume Complete (`mhc1`…`mhc6`).
  - `data/commentaries/{mhcc,mhc}.commentary` — built read-only SQLite commentary DBs.
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

## Native Android notes

_TODO: fill in as the native app takes shape — package id, min SDK, UI toolkit, how the
prebuilt `data/` DBs are bundled/opened, and the BOOX build/install/screenshot recipe._

The whole app runs full-screen immersive with no system Back on the BOOX, so every screen
must provide its own on-screen back control (a constraint the Flutter version already
follows; carry it forward).

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
