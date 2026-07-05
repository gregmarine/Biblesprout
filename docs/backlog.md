# Backlog

Deferred work and known follow-ups, so nothing gets lost between sessions. Not a
committed plan or priority order — just a parking lot.

## Distribution

- **Downloadable resources, nothing bundled.** Every source (the Bible and all
  commentaries) will be fetched on demand rather than shipped inside the APK.
  Today `bsb.bible`, `mhcc.commentary`, and `mhc.commentary` are all `flutter`
  assets copied to storage on first launch (see `AppServices._installAsset` /
  `_commentaryAssets` and `pubspec.yaml`), which is why the debug APK is ~208MB.
  The Complete commentary alone is 50MB. Move to a download model:
  - A resource catalog/manifest (id, title, size, URL, checksum, version) — the
    global index already has a `source` registry to build on.
  - In-app "Library / Manage sources" UI to download, show progress, and remove.
  - Install downloaded `*.bible` / `*.commentary` files into the same writable
    dir the bootstrap already opens from; `AppServices.commentaries` becomes
    "whatever is installed" instead of "whatever is bundled".
  - Drop the DBs from `pubspec.yaml` assets once fetch-on-demand works. Keep the
    source XML/txt and the build tools in the repo (build → host artifacts).
  - Decide hosting + integrity (bereanbible.com for BSB; CCEL for Matthew Henry —
    both public domain).

## Reader / commentary

- ~~Surface commentary from the **Passage view**.~~ Done — the passage view's
  "Notes" opens commentary over the passage's span (shared
  `lib/reader/commentary_launcher.dart`).
- **Verse-anchored commentary**: tap a single verse → its comment, rather than
  the whole chapter/passage span.
- **Remember the last-used commentary** so the picker can default to it (or skip
  straight to it) instead of always prompting.

## Annotations (schema exists, no code paths)

- `biblesprout.db` has `bookmark`, `highlight`, `note`, `cross_link` tables ready.
  Build the capture + review UI, addressing scripture by verse-key spans.

## Future phases (from CLAUDE.md)

- Handwritten notes (reusing Notesprout lessons).
- Supernote as a second e-ink target.
