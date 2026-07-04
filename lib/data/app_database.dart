import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// A source registered in the global index (a Bible, a commentary, …).
class SourceRecord {
  const SourceRecord({
    required this.id,
    required this.type,
    required this.title,
    required this.abbreviation,
    required this.language,
    required this.fileName,
    required this.versification,
    this.isReadonly = true,
  });

  final String id;
  final String type;
  final String title;
  final String abbreviation;
  final String language;
  final String fileName;
  final String versification;
  final bool isReadonly;
}

/// The reader's saved place within a source.
class ProgressRecord {
  const ProgressRecord({
    required this.sourceId,
    required this.bookUsfm,
    required this.chapter,
    required this.page,
    required this.updatedAt,
  });

  final String sourceId;
  final String bookUsfm;
  final int chapter;
  final int page;
  final int updatedAt;
}

/// The read-write global index (`biblesprout.db`): the registry of installed
/// sources plus everything user-generated (reading progress, bookmarks,
/// highlights, notes) and cross-source links. User data addresses scripture by
/// the same canonical verse keys the source databases use, so a note or link
/// resolves against any source regardless of which file it lives in.
///
/// This round wires up the source registry and reading progress; the remaining
/// tables are created and indexed but not yet exercised by the UI.
class AppDatabase {
  AppDatabase._(this._db);

  final Database _db;

  static const int _schemaVersion = 1;

  static Future<AppDatabase> openFile(String path) async {
    final db = await databaseFactory.openDatabase(
      path,
      options: OpenDatabaseOptions(
        version: _schemaVersion,
        onConfigure: (db) => db.execute('PRAGMA foreign_keys = ON'),
        onCreate: _createSchema,
      ),
    );
    return AppDatabase._(db);
  }

  Future<void> close() => _db.close();

  static Future<void> _createSchema(Database db, int version) async {
    final batch = db.batch();

    // Registry of installed source databases.
    batch.execute('''
      CREATE TABLE source (
        id            TEXT PRIMARY KEY,
        type          TEXT NOT NULL,
        title         TEXT NOT NULL,
        abbreviation  TEXT NOT NULL,
        language      TEXT NOT NULL,
        file_name     TEXT NOT NULL,
        versification TEXT NOT NULL,
        is_readonly   INTEGER NOT NULL DEFAULT 1,
        installed_at  INTEGER NOT NULL
      )
    ''');

    // One saved reading position per source; "continue reading" is the row
    // with the largest updated_at.
    batch.execute('''
      CREATE TABLE reading_progress (
        source_id  TEXT PRIMARY KEY,
        book_usfm  TEXT NOT NULL,
        chapter    INTEGER NOT NULL,
        page       INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      )
    ''');
    batch.execute(
      'CREATE INDEX ix_progress_updated ON reading_progress(updated_at)',
    );

    // User-generated annotations, all addressing an inclusive verse-key span
    // (start_key == end_key for a single verse). Schema only for now.
    batch.execute('''
      CREATE TABLE bookmark (
        id         INTEGER PRIMARY KEY AUTOINCREMENT,
        source_id  TEXT NOT NULL,
        start_key  INTEGER NOT NULL,
        end_key    INTEGER NOT NULL,
        label      TEXT,
        created_at INTEGER NOT NULL
      )
    ''');
    batch.execute('CREATE INDEX ix_bookmark_start ON bookmark(start_key)');

    batch.execute('''
      CREATE TABLE highlight (
        id         INTEGER PRIMARY KEY AUTOINCREMENT,
        source_id  TEXT NOT NULL,
        start_key  INTEGER NOT NULL,
        end_key    INTEGER NOT NULL,
        style      TEXT,
        created_at INTEGER NOT NULL
      )
    ''');
    batch.execute('CREATE INDEX ix_highlight_start ON highlight(start_key)');

    batch.execute('''
      CREATE TABLE note (
        id         INTEGER PRIMARY KEY AUTOINCREMENT,
        source_id  TEXT,
        start_key  INTEGER NOT NULL,
        end_key    INTEGER NOT NULL,
        body       TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      )
    ''');
    batch.execute('CREATE INDEX ix_note_start ON note(start_key)');

    // A directed link from one place/material to another (e.g. a verse to a
    // commentary entry, or a verse to a cross-referenced verse).
    batch.execute('''
      CREATE TABLE cross_link (
        id             INTEGER PRIMARY KEY AUTOINCREMENT,
        from_source_id TEXT NOT NULL,
        from_start_key INTEGER NOT NULL,
        from_end_key   INTEGER NOT NULL,
        to_source_id   TEXT NOT NULL,
        to_start_key   INTEGER NOT NULL,
        to_end_key     INTEGER NOT NULL,
        kind           TEXT,
        created_at     INTEGER NOT NULL
      )
    ''');
    batch.execute(
      'CREATE INDEX ix_crosslink_from ON cross_link(from_start_key)',
    );
    batch.execute('CREATE INDEX ix_crosslink_to ON cross_link(to_start_key)');

    await batch.commit(noResult: true);
  }

  // --- Source registry ------------------------------------------------------

  Future<void> registerSource(SourceRecord s) async {
    await _db.insert(
      'source',
      {
        'id': s.id,
        'type': s.type,
        'title': s.title,
        'abbreviation': s.abbreviation,
        'language': s.language,
        'file_name': s.fileName,
        'versification': s.versification,
        'is_readonly': s.isReadonly ? 1 : 0,
        'installed_at': DateTime.now().millisecondsSinceEpoch,
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<List<SourceRecord>> installedSources() async {
    final rows = await _db.query('source', orderBy: 'installed_at');
    return rows
        .map((r) => SourceRecord(
              id: r['id'] as String,
              type: r['type'] as String,
              title: r['title'] as String,
              abbreviation: r['abbreviation'] as String,
              language: r['language'] as String,
              fileName: r['file_name'] as String,
              versification: r['versification'] as String,
              isReadonly: (r['is_readonly'] as int) != 0,
            ))
        .toList(growable: false);
  }

  // --- Reading progress -----------------------------------------------------

  Future<void> saveProgress({
    required String sourceId,
    required String bookUsfm,
    required int chapter,
    required int page,
  }) async {
    await _db.insert(
      'reading_progress',
      {
        'source_id': sourceId,
        'book_usfm': bookUsfm,
        'chapter': chapter,
        'page': page,
        'updated_at': DateTime.now().millisecondsSinceEpoch,
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<ProgressRecord?> progressForSource(String sourceId) =>
      _firstProgress('source_id = ?', [sourceId]);

  /// The most recently read position across all sources ("continue reading").
  Future<ProgressRecord?> latestProgress() =>
      _firstProgress(null, null, orderBy: 'updated_at DESC');

  Future<ProgressRecord?> _firstProgress(
    String? where,
    List<Object?>? whereArgs, {
    String? orderBy,
  }) async {
    final rows = await _db.query(
      'reading_progress',
      where: where,
      whereArgs: whereArgs,
      orderBy: orderBy,
      limit: 1,
    );
    if (rows.isEmpty) return null;
    final r = rows.first;
    return ProgressRecord(
      sourceId: r['source_id'] as String,
      bookUsfm: r['book_usfm'] as String,
      chapter: r['chapter'] as int,
      page: r['page'] as int,
      updatedAt: r['updated_at'] as int,
    );
  }
}
