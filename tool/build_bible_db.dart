// Dev-time tool: convert the plain-text Berean Standard Bible into the
// read-only `bsb.bible` SQLite source database that the app ships and copies to
// device storage on first launch.
//
// Run from the project root with the Flutter SDK's Dart on PATH:
//
//     dart run tool/build_bible_db.dart
//
// The source of truth stays `assets/bible/bsb.txt`; re-run this whenever it is
// refreshed from https://bereanbible.com/bsb.txt. Uses the `sqlite3` dev
// dependency, which compiles its own SQLite (with FTS5) via build hooks.

import 'dart:io';

import 'package:sqlite3/sqlite3.dart';

import 'package:biblesprout/data/canon.dart';
import 'package:biblesprout/models/bible.dart' show Testament;
import 'package:biblesprout/models/reference.dart';

const _sourcePath = 'assets/bible/bsb.txt';
const _outPath = 'assets/bible/bsb.bible';
const _schemaVersion = 1;

final RegExp _refPattern = RegExp(r'^(.+) (\d+):(\d+)$');

void main() {
  final source = File(_sourcePath);
  if (!source.existsSync()) {
    stderr.writeln('Cannot find $_sourcePath — run from the project root.');
    exit(1);
  }

  final rows = _parse(source.readAsStringSync());
  stdout.writeln('Parsed ${rows.length} verses.');

  final out = File(_outPath);
  if (out.existsSync()) out.deleteSync();

  final db = sqlite3.open(_outPath);
  try {
    _createSchema(db);
    _populate(db, rows);
    _buildFts(db);
    db.execute('VACUUM;');
  } finally {
    db.close();
  }

  final kb = (out.lengthSync() / 1024).round();
  stdout.writeln('Wrote $_outPath (${kb}KB).');
}

/// One parsed verse row, already resolved to canonical USFM + verse key.
class _Row {
  _Row(this.book, this.chapter, this.verse, this.text)
      : key = VerseKey.encode(book.ordinal, chapter, verse);
  final CanonBook book;
  final int chapter;
  final int verse;
  final String text;
  final int key;
}

List<_Row> _parse(String raw) {
  if (raw.isNotEmpty && raw.codeUnitAt(0) == 0xFEFF) {
    raw = raw.substring(1); // strip UTF-8 BOM
  }
  final rows = <_Row>[];
  final unknownBooks = <String>{};

  for (final line in raw.split('\n')) {
    final tab = line.indexOf('\t');
    if (tab < 0) continue; // metadata/header lines
    final ref = line.substring(0, tab).trim();
    final text = line.substring(tab + 1).trim();

    final match = _refPattern.firstMatch(ref);
    if (match == null) continue; // "Verse" column header

    final bookName = match.group(1)!;
    final book = Canon.lookup(bookName);
    if (book == null) {
      unknownBooks.add(bookName);
      continue;
    }
    rows.add(_Row(
      book,
      int.parse(match.group(2)!),
      int.parse(match.group(3)!),
      text,
    ));
  }

  if (unknownBooks.isNotEmpty) {
    stderr.writeln('WARNING unmapped book names: ${unknownBooks.join(', ')}');
    exit(1);
  }
  return rows;
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
      usfm          TEXT PRIMARY KEY,
      ordinal       INTEGER NOT NULL,
      name          TEXT NOT NULL,
      testament     TEXT NOT NULL,
      chapter_count INTEGER NOT NULL
    );

    CREATE TABLE verse (
      verse_key INTEGER PRIMARY KEY,
      usfm      TEXT NOT NULL,
      chapter   INTEGER NOT NULL,
      verse     INTEGER NOT NULL,
      text      TEXT NOT NULL
    );
  ''');
}

void _populate(Database db, List<_Row> rows) {
  final meta = {
    'id': 'bsb',
    'title': 'Berean Standard Bible',
    'abbreviation': 'BSB',
    'type': 'bible',
    'language': 'en',
    'versification': 'english',
    'license': 'Public Domain',
    'source_url': 'https://bereanbible.com/bsb.txt',
    'schema_version': '$_schemaVersion',
  };

  // chapter_count per book, in canonical order.
  final maxChapter = <String, int>{};
  for (final r in rows) {
    final c = maxChapter[r.book.usfm] ?? 0;
    if (r.chapter > c) maxChapter[r.book.usfm] = r.chapter;
  }

  db.execute('BEGIN;');

  final metaStmt = db.prepare('INSERT INTO metadata(key, value) VALUES (?, ?)');
  meta.forEach((k, v) => metaStmt.execute([k, v]));
  metaStmt.close();

  final bookStmt = db.prepare(
    'INSERT INTO book(usfm, ordinal, name, testament, chapter_count) '
    'VALUES (?, ?, ?, ?, ?)',
  );
  for (final usfm in maxChapter.keys.toList()
    ..sort((a, b) => Canon.byUsfm(a).ordinal.compareTo(Canon.byUsfm(b).ordinal))) {
    final b = Canon.byUsfm(usfm);
    bookStmt.execute([
      b.usfm,
      b.ordinal,
      b.name,
      b.testament == Testament.old ? 'OT' : 'NT',
      maxChapter[usfm],
    ]);
  }
  bookStmt.close();

  final verseStmt = db.prepare(
    'INSERT INTO verse(verse_key, usfm, chapter, verse, text) '
    'VALUES (?, ?, ?, ?, ?)',
  );
  for (final r in rows) {
    verseStmt.execute([r.key, r.book.usfm, r.chapter, r.verse, r.text]);
  }
  verseStmt.close();

  db.execute('COMMIT;');
}

/// External-content FTS5 index over verse text, keyed to verse_key. The source
/// DB is read-only on device, so no sync triggers are needed — we populate once.
void _buildFts(Database db) {
  db.execute('''
    CREATE VIRTUAL TABLE verse_fts USING fts5(
      text,
      content='verse',
      content_rowid='verse_key'
    );
    INSERT INTO verse_fts(rowid, text) SELECT verse_key, text FROM verse;
    INSERT INTO verse_fts(verse_fts) VALUES ('optimize');
  ''');
}
