import 'dart:io';

import 'package:flutter/services.dart' show rootBundle;
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../models/bible.dart';
import '../services/reading_position.dart';
import 'app_database.dart';
import 'bible_database.dart';

/// Everything the UI needs, assembled once at startup: the in-memory [Bible],
/// the open source and index databases, and the reading-position store.
///
/// [bootstrap] initialises the bundled SQLite engine, installs the read-only
/// `bsb.bible` source into writable storage, opens the global `biblesprout.db`
/// index, registers the source, and migrates any pre-database reading position.
class AppServices {
  AppServices({
    required this.bible,
    required this.bibleDb,
    required this.appDb,
    required this.positionStore,
    required this.initialPosition,
  });

  final Bible bible;
  final BibleDatabase bibleDb;
  final AppDatabase appDb;
  final ReadingPositionStore positionStore;
  final ReadingPosition? initialPosition;

  static const _bibleAsset = 'assets/bible/bsb.bible';

  static Future<AppServices> bootstrap() async {
    // Use the bundled SQLite (sqlite3_flutter_libs), which includes FTS5 —
    // the system library on Android often does not.
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;

    final dir = await getApplicationSupportDirectory();
    final biblePath = p.join(dir.path, 'bsb.bible');
    await _installAsset(_bibleAsset, biblePath);

    final bibleDb = await BibleDatabase.openFile(biblePath);
    final bible = await bibleDb.loadBible();

    final appDb = await AppDatabase.openFile(p.join(dir.path, 'biblesprout.db'));
    await appDb.registerSource(SourceRecord(
      id: bibleDb.id,
      type: bibleDb.metadata['type'] ?? 'bible',
      title: bibleDb.title,
      abbreviation: bibleDb.metadata['abbreviation'] ?? '',
      language: bibleDb.metadata['language'] ?? '',
      fileName: p.basename(biblePath),
      versification: bibleDb.metadata['versification'] ?? 'english',
    ));

    final store = ReadingPositionStore(appDb, bibleDb.id);
    await _migrateLegacyPosition(store);

    return AppServices(
      bible: bible,
      bibleDb: bibleDb,
      appDb: appDb,
      positionStore: store,
      initialPosition: await store.load(),
    );
  }

  /// Copies a bundled DB asset to writable storage, overwriting when the
  /// bundled file differs in size (e.g. after re-running the build tool).
  static Future<void> _installAsset(String asset, String dest) async {
    final data = await rootBundle.load(asset);
    final bytes =
        data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes);
    final file = File(dest);
    if (await file.exists() && await file.length() == bytes.length) return;
    await file.create(recursive: true);
    await file.writeAsBytes(bytes, flush: true);
  }

  /// One-time import of the pre-database reading position from
  /// shared_preferences, so upgrading users keep their place.
  static Future<void> _migrateLegacyPosition(ReadingPositionStore store) async {
    if (await store.load() != null) return; // DB already has progress
    final prefs = await SharedPreferences.getInstance();
    if (!prefs.containsKey('pos.bookIndex')) return;
    await store.save(ReadingPosition(
      bookIndex: prefs.getInt('pos.bookIndex') ?? 0,
      chapterNumber: prefs.getInt('pos.chapterNumber') ?? 1,
      page: prefs.getInt('pos.page') ?? 0,
    ));
  }
}
