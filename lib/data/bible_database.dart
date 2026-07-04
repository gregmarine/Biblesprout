import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../models/bible.dart';
import '../models/reference.dart';
import 'canon.dart';

/// A single verse produced by a search or range query, carrying its canonical
/// address so callers can navigate to or cross-link it.
class VerseHit {
  const VerseHit({
    required this.verseKey,
    required this.usfm,
    required this.chapter,
    required this.verse,
    required this.text,
  });

  final int verseKey;
  final String usfm;
  final int chapter;
  final int verse;
  final String text;

  /// Human label, e.g. "John 3:16".
  String get reference => '${Canon.byUsfm(usfm).name} $chapter:$verse';
}

/// Read-only accessor for a Bible source database (`*.bible`). Opens the file,
/// rebuilds the in-memory [Bible] the reader/paginator consume, and exposes
/// full-text search and verse-key range lookups for passages and cross-links.
class BibleDatabase {
  BibleDatabase._(this._db, this.metadata);

  final Database _db;

  /// The source's `metadata` table as a plain map (id, title, versification…).
  final Map<String, String> metadata;

  String get id => metadata['id'] ?? 'unknown';
  String get title => metadata['title'] ?? id;

  /// Opens an existing `.bible` file read-only.
  static Future<BibleDatabase> openFile(String path) async {
    final db = await databaseFactory.openDatabase(
      path,
      options: OpenDatabaseOptions(readOnly: true),
    );
    final rows = await db.query('metadata');
    final meta = {
      for (final r in rows) r['key'] as String: r['value'] as String,
    };
    return BibleDatabase._(db, meta);
  }

  Future<void> close() => _db.close();

  /// Rebuilds the whole [Bible] object graph from the database, in canonical
  /// order — the shape the existing reader, library and paginator expect.
  Future<Bible> loadBible() async {
    final bookRows = await _db.query('book', orderBy: 'ordinal');
    final verseRows = await _db.query(
      'verse',
      columns: ['usfm', 'chapter', 'verse', 'text'],
      orderBy: 'verse_key',
    );

    // Group verses → chapters per book in one pass (rows are key-ordered).
    final chaptersByBook = <String, List<Chapter>>{};
    String? curUsfm;
    int? curChapter;
    List<Verse> curVerses = [];

    void flush() {
      if (curUsfm != null && curChapter != null) {
        chaptersByBook
            .putIfAbsent(curUsfm, () => [])
            .add(Chapter(curChapter, curVerses));
      }
      curVerses = [];
    }

    for (final r in verseRows) {
      final usfm = r['usfm'] as String;
      final chapter = r['chapter'] as int;
      if (usfm != curUsfm || chapter != curChapter) {
        flush();
        curUsfm = usfm;
        curChapter = chapter;
      }
      curVerses.add(Verse(r['verse'] as int, r['text'] as String));
    }
    flush();

    final books = <Book>[];
    for (var i = 0; i < bookRows.length; i++) {
      final row = bookRows[i];
      final usfm = row['usfm'] as String;
      books.add(Book(
        index: i,
        name: row['name'] as String,
        testament:
            row['testament'] == 'OT' ? Testament.old : Testament.newT,
        chapters: chaptersByBook[usfm] ?? const [],
      ));
    }
    return Bible(books);
  }

  /// Full-text search over verse text (FTS5), best matches first.
  ///
  /// The query is reduced to word tokens and rebuilt as a safe prefix-AND FTS
  /// expression — so user punctuation can't cause a syntax error, and searching
  /// "love" also finds "loved" / "lovely".
  Future<List<VerseHit>> search(String query, {int limit = 100}) async {
    final tokens = RegExp(r'[\p{L}\p{N}]+', unicode: true)
        .allMatches(query.toLowerCase())
        .map((m) => m[0]!)
        .toList();
    if (tokens.isEmpty) return const [];
    final match = tokens.map((t) => '"$t"*').join(' ');
    final rows = await _db.rawQuery(
      '''
      SELECT v.verse_key, v.usfm, v.chapter, v.verse, v.text
      FROM verse_fts f
      JOIN verse v ON v.verse_key = f.rowid
      WHERE verse_fts MATCH ?
      ORDER BY rank
      LIMIT ?
      ''',
      [match, limit],
    );
    return rows.map(_toHit).toList(growable: false);
  }

  /// All verses inside an inclusive key range (one [VerseRange] of a passage).
  Future<List<VerseHit>> versesInRange(int startKey, int endKey) async {
    final rows = await _db.query(
      'verse',
      columns: ['verse_key', 'usfm', 'chapter', 'verse', 'text'],
      where: 'verse_key BETWEEN ? AND ?',
      whereArgs: [startKey, endKey],
      orderBy: 'verse_key',
    );
    return rows.map(_toHit).toList(growable: false);
  }

  /// Resolves a parsed [Passage] to its verses, in reading order.
  Future<List<VerseHit>> versesForPassage(Passage passage) async {
    final out = <VerseHit>[];
    for (final range in passage.ranges) {
      out.addAll(await versesInRange(range.startKey, range.endKey));
    }
    return out;
  }

  VerseHit _toHit(Map<String, Object?> r) => VerseHit(
        verseKey: r['verse_key'] as int,
        usfm: r['usfm'] as String,
        chapter: r['chapter'] as int,
        verse: r['verse'] as int,
        text: r['text'] as String,
      );
}
