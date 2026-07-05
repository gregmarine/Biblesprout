import '../data/app_database.dart';

/// Remembers which commentary the reader last opened, so that with more than one
/// installed the launcher can skip straight to it instead of prompting every
/// time. The choice is a source id, persisted in the global index and cached in
/// memory for synchronous reads.
///
/// Construct with [load] in the app; [CommentaryPreferences.memory] backs tests
/// and the not-yet-threaded paths with an in-memory-only store.
class CommentaryPreferences {
  CommentaryPreferences._(this._db, this._lastId);

  /// An in-memory-only store (no persistence), optionally seeded with a
  /// starting [lastId].
  factory CommentaryPreferences.memory([String? lastId]) =>
      CommentaryPreferences._(null, lastId);

  /// Loads the persisted choice from the global index.
  static Future<CommentaryPreferences> load(AppDatabase db) async =>
      CommentaryPreferences._(db, await db.getSetting(_key));

  static const _key = 'last_commentary_id';

  final AppDatabase? _db;
  String? _lastId;

  /// The source id of the last-opened commentary, or null if none chosen yet.
  String? get lastId => _lastId;

  /// Records [id] as the last-opened commentary (cached immediately; persisted
  /// when backed by a database).
  Future<void> remember(String id) async {
    _lastId = id;
    await _db?.setSetting(_key, id);
  }
}
