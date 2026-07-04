// Dev-time tool: convert a CCEL ThML commentary (Matthew Henry) into a
// read-only `*.commentary` SQLite source database for Biblesprout.
//
// Run from the project root with the Flutter SDK's Dart on PATH:
//
//     dart run tool/build_commentary_db.dart mhcc      # Concise
//     dart run tool/build_commentary_db.dart mhc       # Complete (later)
//
// Each commentary block in the ThML is a self-describing
//   <div class="Commentary" id="Bible:Gen.1.1-Gen.1.2"> … </div>
// whose OSIS id gives the exact verse range it covers. We map that range onto
// the same canonical verse keys the Bible uses (ordinal*1e6 + chapter*1e3 +
// verse), so a commentary entry cross-links to scripture by integer bounds.
//
// Source text is public domain (CCEL, https://ccel.org/ccel/henry/). Uses the
// `sqlite3` and `xml` dev dependencies.

import 'dart:io';

import 'package:sqlite3/sqlite3.dart';
import 'package:xml/xml.dart';

import 'package:biblesprout/data/canon.dart';
import 'package:biblesprout/models/bible.dart' show Testament;
import 'package:biblesprout/models/reference.dart';

const _schemaVersion = 1;

/// Build configuration per commentary id.
class _Config {
  const _Config({
    required this.id,
    required this.title,
    required this.abbreviation,
    required this.inputs,
    required this.out,
  });
  final String id;
  final String title;
  final String abbreviation;
  final List<String> inputs;
  final String out;
}

const _configs = <String, _Config>{
  'mhcc': _Config(
    id: 'mhcc',
    title: "Matthew Henry's Concise Commentary",
    abbreviation: 'MHCC',
    inputs: ['assets/commentaries/mhcc.xml'],
    out: 'assets/commentaries/mhcc.commentary',
  ),
  // 'mhc' (Complete, six volumes) can be added here later with the same parser.
};

void main(List<String> args) {
  final id = args.isNotEmpty ? args.first : 'mhcc';
  final cfg = _configs[id];
  if (cfg == null) {
    stderr.writeln('Unknown commentary "$id". Known: ${_configs.keys.join(', ')}');
    exit(1);
  }

  final entries = <_Entry>[];
  for (final path in cfg.inputs) {
    final file = File(path);
    if (!file.existsSync()) {
      stderr.writeln('Missing input $path — run from the project root.');
      exit(1);
    }
    entries.addAll(_parseThml(file.readAsStringSync()));
  }
  stdout.writeln('Parsed ${entries.length} commentary entries.');

  final out = File(cfg.out);
  if (out.existsSync()) out.deleteSync();
  final db = sqlite3.open(cfg.out);
  try {
    _createSchema(db);
    _populate(db, cfg, entries);
    _buildFts(db);
    db.execute('VACUUM;');
  } finally {
    db.close();
  }

  final kb = (out.lengthSync() / 1024).round();
  stdout.writeln('Wrote ${cfg.out} (${kb}KB).');
}

/// One parsed commentary entry. [startKey]/[endKey] are the inclusive canonical
/// verse-key bounds the comment *covers* (see below); [startVerse] is the verse
/// the section is nominally headed at (0 = a whole-chapter comment).
class _Entry {
  _Entry({
    required this.usfm,
    required this.ordinal,
    required this.chapter,
    required this.startVerse,
    required this.heading,
    required this.body,
  });
  final String usfm;
  final int ordinal;
  final int chapter;
  final int startVerse;
  final String? heading;
  final String body;

  late int startKey;
  late int endKey;
}

/// Parses a commentary ThML document into coverage-resolved entries.
///
/// Comments are delimited by `<scripCom type="Commentary">` markers (present for
/// both verse-range books and whole-chapter books). The `osisRef` range on a
/// marker is often *narrower* than the text that follows it (e.g. John 3:1-8's
/// block actually runs through v21), so we trust only the marker's **start** and
/// let each comment cover up to the next marker — giving gap-free coverage where
/// John 3:16 correctly resolves to the block that discusses it.
List<_Entry> _parseThml(String raw) {
  // Drop the DOCTYPE so the parser never tries to resolve the external DTD;
  // the body uses only the predefined &amp; entity.
  raw = raw.replaceFirst(RegExp(r'<!DOCTYPE[^>]*>'), '');
  final doc = XmlDocument.parse(raw);

  final entries = <_Entry>[];

  // The book of record comes from the <div1 title="…"> heading, not osisRef:
  // CCEL's auto-tagger sometimes mis-codes the book (e.g. Jude → "Judg"), but
  // the div1 title and the osisRef chapter/verse numbers are reliable.
  CanonBook? currentBook;
  int currentChapter = 0;

  // Accumulators for the comment currently being read.
  CanonBook? book;
  int chapter = 0;
  int startVerse = 0;
  String? heading;
  var paras = <String>[];

  void flush() {
    if (book != null && paras.isNotEmpty) {
      entries.add(_Entry(
        usfm: book!.usfm,
        ordinal: book!.ordinal,
        chapter: chapter,
        startVerse: startVerse,
        heading: heading,
        body: paras.join('\n\n'),
      ));
    }
    book = null;
    heading = null;
    paras = <String>[];
  }

  // Walk elements in document order as a state machine. A book/chapter boundary
  // or a new marker flushes the current comment; body text is the <p> runs that
  // follow a marker (skipping the chapter-outline table).
  for (final el in doc.descendants.whereType<XmlElement>()) {
    switch (el.name.local) {
      case 'div1':
        flush();
        currentBook = Canon.lookup(el.getAttribute('title') ?? '');
        currentChapter = 0;
      case 'div2':
        flush();
        currentChapter = _chapterNum(el.getAttribute('title') ?? '');
      case 'scripCom':
        if (el.getAttribute('type') != 'Commentary') break;
        flush();
        if (currentBook == null) break; // front/back matter
        final cv = _parseChapterVerse(el.getAttribute('osisRef') ?? '');
        if (cv == null) break;
        book = currentBook;
        chapter = currentChapter != 0 ? currentChapter : cv.$1;
        startVerse = cv.$2;
      case 'h3':
        if (book != null && heading == null) {
          final h = _clean(el.innerText);
          if (h.isNotEmpty) heading = h;
        }
      case 'p':
        if (el.ancestors.whereType<XmlElement>().any((a) => a.name.local == 'table')) {
          break; // chapter-outline table, not commentary prose
        }
        final t = _clean(el.innerText);
        if (t.isEmpty) break;
        // Prose with no preceding marker (e.g. Psalm 23, whose scripCom is
        // missing in the source) becomes a whole-chapter comment. Prose before
        // a book's first chapter heading belongs to chapter 1 (2 Chronicles 1
        // sits there); where chapter 1 also has its own comments this entry
        // simply collapses to an empty range in _resolveCoverage.
        if (book == null) {
          if (currentBook == null) break; // front/back matter
          book = currentBook;
          chapter = currentChapter != 0 ? currentChapter : 1;
          startVerse = 0;
        }
        paras.add(t);
    }
  }
  flush();

  _resolveCoverage(entries);
  return entries;
}

/// Assigns each entry gap-free coverage bounds: a comment starts at the top of
/// its chapter (if it's the first there) or at its own verse, and runs to just
/// before the next comment, clamped to its own chapter's end.
void _resolveCoverage(List<_Entry> entries) {
  entries.sort((a, b) {
    final ak = VerseKey.encode(a.ordinal, a.chapter, a.startVerse);
    final bk = VerseKey.encode(b.ordinal, b.chapter, b.startVerse);
    return ak.compareTo(bk);
  });

  for (var i = 0; i < entries.length; i++) {
    final e = entries[i];
    final firstOfChapter = i == 0 ||
        entries[i - 1].ordinal != e.ordinal ||
        entries[i - 1].chapter != e.chapter;
    e.startKey = firstOfChapter
        ? VerseKey.encode(e.ordinal, e.chapter, 0)
        : VerseKey.encode(e.ordinal, e.chapter, e.startVerse);
  }
  for (var i = 0; i < entries.length; i++) {
    final e = entries[i];
    final chapterEnd = VerseKey.encode(e.ordinal, e.chapter, VerseKey.maxVerse);
    e.endKey = i < entries.length - 1
        ? (entries[i + 1].startKey - 1 < chapterEnd
            ? entries[i + 1].startKey - 1
            : chapterEnd)
        : chapterEnd;
  }
}

/// Parses the START chapter/verse from an osisRef (the book code is ignored —
/// see the div1 note). Verse is 0 for a whole-chapter ref like `Bible:Isa.1`.
(int, int)? _parseChapterVerse(String osisRef) {
  final s = osisRef.startsWith('Bible:') ? osisRef.substring(6) : osisRef;
  final parts = s.split('-').first.split('.');
  if (parts.length < 2) return null;
  final chapter = int.tryParse(parts[1]);
  if (chapter == null) return null;
  final verse = parts.length >= 3 ? (int.tryParse(parts[2]) ?? 0) : 0;
  return (chapter, verse);
}

/// Collapses ThML whitespace (line wraps, tabs) into single spaces.
String _clean(String s) => s.replaceAll(RegExp(r'\s+'), ' ').trim();

/// Extracts the chapter number from a div2 title like "Chapter 23".
int _chapterNum(String title) {
  final m = RegExp(r'(\d+)').firstMatch(title);
  return m == null ? 0 : int.parse(m.group(1)!);
}

void _createSchema(Database db) {
  db.execute('''
    PRAGMA journal_mode = OFF;
    PRAGMA synchronous = OFF;

    CREATE TABLE metadata (
      key   TEXT PRIMARY KEY,
      value TEXT
    );

    CREATE TABLE book (
      usfm        TEXT PRIMARY KEY,
      ordinal     INTEGER NOT NULL,
      name        TEXT NOT NULL,
      testament   TEXT NOT NULL,
      entry_count INTEGER NOT NULL
    );

    -- Each row is one comment covering an inclusive verse-key range
    -- [start_key, end_key]. start_verse is the nominal heading verse (0 = a
    -- whole-chapter comment); heading is Matthew Henry's own section label.
    CREATE TABLE entry (
      id          INTEGER PRIMARY KEY,
      usfm        TEXT NOT NULL,
      chapter     INTEGER NOT NULL,
      start_verse INTEGER NOT NULL,
      start_key   INTEGER NOT NULL,
      end_key     INTEGER NOT NULL,
      heading     TEXT,
      body        TEXT NOT NULL
    );
    CREATE INDEX ix_entry_start ON entry(start_key);
    CREATE INDEX ix_entry_book_chapter ON entry(usfm, chapter);
  ''');
}

void _populate(Database db, _Config cfg, List<_Entry> entries) {
  final meta = {
    'id': cfg.id,
    'title': cfg.title,
    'abbreviation': cfg.abbreviation,
    'type': 'commentary',
    'language': 'en',
    'versification': 'english',
    'license': 'Public Domain',
    'source_url': 'https://ccel.org/ccel/henry/${cfg.id}.xml',
    'schema_version': '$_schemaVersion',
  };

  final entryCount = <String, int>{};
  for (final e in entries) {
    entryCount[e.usfm] = (entryCount[e.usfm] ?? 0) + 1;
  }

  db.execute('BEGIN;');

  final metaStmt = db.prepare('INSERT INTO metadata(key, value) VALUES (?, ?)');
  meta.forEach((k, v) => metaStmt.execute([k, v]));
  metaStmt.close();

  final bookStmt = db.prepare(
    'INSERT INTO book(usfm, ordinal, name, testament, entry_count) '
    'VALUES (?, ?, ?, ?, ?)',
  );
  for (final usfm in entryCount.keys.toList()
    ..sort((a, b) => Canon.byUsfm(a).ordinal.compareTo(Canon.byUsfm(b).ordinal))) {
    final b = Canon.byUsfm(usfm);
    bookStmt.execute([
      b.usfm,
      b.ordinal,
      b.name,
      b.testament == Testament.old ? 'OT' : 'NT',
      entryCount[usfm],
    ]);
  }
  bookStmt.close();

  final entryStmt = db.prepare(
    'INSERT INTO entry(id, usfm, chapter, start_verse, '
    'start_key, end_key, heading, body) VALUES (?, ?, ?, ?, ?, ?, ?, ?)',
  );
  for (var i = 0; i < entries.length; i++) {
    final e = entries[i];
    entryStmt.execute([
      i + 1, // id = canonical (reading) order
      e.usfm,
      e.chapter,
      e.startVerse,
      e.startKey,
      e.endKey,
      e.heading,
      e.body,
    ]);
  }
  entryStmt.close();

  db.execute('COMMIT;');
}

/// External-content FTS5 index over commentary body text, keyed to entry.id.
void _buildFts(Database db) {
  db.execute('''
    CREATE VIRTUAL TABLE entry_fts USING fts5(
      body,
      content='entry',
      content_rowid='id'
    );
    INSERT INTO entry_fts(rowid, body) SELECT id, body FROM entry;
    INSERT INTO entry_fts(entry_fts) VALUES ('optimize');
  ''');
}
