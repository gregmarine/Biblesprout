import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'canon.dart';

/// One commentary comment covering an inclusive verse-key range. A verse-range
/// comment carries Matthew Henry's own [heading] (e.g. "Verses 1–8"); a
/// whole-chapter comment has none.
class CommentaryEntry {
  const CommentaryEntry({
    required this.id,
    required this.usfm,
    required this.chapter,
    required this.startVerse,
    required this.startKey,
    required this.endKey,
    required this.heading,
    required this.body,
  });

  final int id;
  final String usfm;
  final int chapter;
  final int startVerse;
  final int startKey;
  final int endKey;
  final String? heading;
  final String body;

  String get bookName => Canon.byUsfm(usfm).name;

  /// A display label, e.g. "John 3 — Verses 1–8" or "Isaiah 53".
  String get reference =>
      heading == null ? '$bookName $chapter' : '$bookName $chapter — $heading';
}

/// Read-only accessor for a commentary source database (`*.commentary`).
///
/// Comments are addressed by the same canonical verse keys the Bible uses, so
/// [entriesForVerse] answers "what does this commentary say about verse K" with
/// a simple range containment, and [entriesForRange] overlaps a whole passage.
class CommentaryDatabase {
  CommentaryDatabase._(this._db, this.metadata);

  final Database _db;

  /// The source's `metadata` table as a plain map (id, title, versification…).
  final Map<String, String> metadata;

  String get id => metadata['id'] ?? 'unknown';
  String get title => metadata['title'] ?? id;

  static Future<CommentaryDatabase> openFile(String path) async {
    final db = await databaseFactory.openDatabase(
      path,
      options: OpenDatabaseOptions(readOnly: true),
    );
    final rows = await db.query('metadata');
    final meta = {
      for (final r in rows) r['key'] as String: r['value'] as String,
    };
    return CommentaryDatabase._(db, meta);
  }

  Future<void> close() => _db.close();

  /// The comment(s) covering a single verse (usually one).
  Future<List<CommentaryEntry>> entriesForVerse(int verseKey) async {
    final rows = await _db.query(
      'entry',
      where: 'start_key <= ? AND end_key >= ?',
      whereArgs: [verseKey, verseKey],
      orderBy: 'start_key',
    );
    return rows.map(_toEntry).toList(growable: false);
  }

  /// All comments overlapping an inclusive verse-key range, in reading order.
  Future<List<CommentaryEntry>> entriesForRange(int startKey, int endKey) async {
    final rows = await _db.query(
      'entry',
      where: 'start_key <= ? AND end_key >= ?',
      whereArgs: [endKey, startKey],
      orderBy: 'start_key',
    );
    return rows.map(_toEntry).toList(growable: false);
  }

  /// Full-text search over commentary body text (FTS5), best matches first.
  /// The query is reduced to word tokens and rebuilt as a safe prefix-AND
  /// expression, matching the Bible search's behaviour.
  Future<List<CommentaryEntry>> search(String query, {int limit = 100}) async {
    final tokens = RegExp(r'[\p{L}\p{N}]+', unicode: true)
        .allMatches(query.toLowerCase())
        .map((m) => m[0]!)
        .toList();
    if (tokens.isEmpty) return const [];
    final match = tokens.map((t) => '"$t"*').join(' ');
    final rows = await _db.rawQuery(
      '''
      SELECT e.* FROM entry_fts f
      JOIN entry e ON e.id = f.rowid
      WHERE entry_fts MATCH ?
      ORDER BY rank
      LIMIT ?
      ''',
      [match, limit],
    );
    return rows.map(_toEntry).toList(growable: false);
  }

  CommentaryEntry _toEntry(Map<String, Object?> r) => CommentaryEntry(
        id: r['id'] as int,
        usfm: r['usfm'] as String,
        chapter: r['chapter'] as int,
        startVerse: r['start_verse'] as int,
        startKey: r['start_key'] as int,
        endKey: r['end_key'] as int,
        heading: r['heading'] as String?,
        body: r['body'] as String,
      );
}
