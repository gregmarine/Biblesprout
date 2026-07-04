# Biblesprout

A Bible study app in Flutter, targeting e-ink devices (Onyx BOOX primary, Supernote later).
Current dev/test device is the **BOOX Go 6** (`DAF86F61`); the Go 10.3 (`b7a46e13`) is in use
for another project — don't target it. Shares the "feel like real paper, stay human"
philosophy of Notesprout and Paintsprout. Handwritten notes are a future phase and will reuse
Notesprout lessons.

## Current phase — Phase 0 PoC (complete)

Display Berean Standard Bible text on e-ink with page-flipping and a table of contents.

- **Library** (`lib/screens/library_screen.dart`) — 66 books grouped OT/NT + "Continue reading".
- **Chapters** (`lib/screens/chapters_screen.dart`) — chapter-number grid per book.
- **Reader** (`lib/reader/reader_screen.dart`) — paginated chapter text; each chapter starts
  on a fresh page with a book/chapter heading; paragraph flow with superscript verse numbers;
  page turns via swipe **and** left/right tap thirds (center third opens Contents); flows
  across chapter/book boundaries; instant swaps with a black full-refresh flash every 6 turns
  to clear e-ink ghosting; remembers last position.

## Architecture

- `lib/models/bible.dart` — `Bible → Book → Chapter → Verse`; `ChapterRef` + `next/previous`
  for continuous reading across book boundaries.
- `lib/data/bible_repository.dart` — parses the bundled `assets/bible/bsb.txt` at launch.
  Format is tab-delimited `Book Chapter:Verse<TAB>text` (2 metadata + 1 header line first,
  UTF-8 BOM on line 1). Reference parsed from the right to handle "1 Samuel", "Song of
  Solomon". First 39 books = OT. Source label "Psalm" is displayed as "Psalms".
- `lib/reader/paginator.dart` — the core. Splits a chapter into `Atom`s (verse number / word)
  and binary-searches, via `TextPainter`, the most atoms that fit each page. Measurement and
  rendering share one `StrutStyle(forceStrutHeight)` so heights agree.
- `lib/services/reading_position.dart` — last position via `shared_preferences`.
- `lib/theme/eink_theme.dart` — pure black on white, no ripples, no route transitions.

E-ink design rules for **all** screens/dialogs/buttons/widgets (color, motion,
refresh, dialogs, touch targets) live in `docs/eink-constraints.md`. Read it
before building new UI; the theme enforces only part of it.

The BSB text is public domain (bereanbible.com). Refresh it from https://bereanbible.com/bsb.txt.

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
