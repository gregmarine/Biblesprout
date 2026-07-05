import '../data/app_database.dart';
import '../data/canon.dart';

/// The reader's last location, persisted so the app reopens where it left off.
///
/// The reader speaks in `bookIndex` (0-based canonical order); the global index
/// stores the canonical USFM book code. This store translates between the two,
/// so the reader/library/chapters screens are unchanged by the move to SQLite.
class ReadingPosition {
  const ReadingPosition({
    required this.bookIndex,
    required this.chapterNumber,
    required this.page,
  });

  final int bookIndex;
  final int chapterNumber;

  /// Zero-based page index within the chapter.
  final int page;
}

class ReadingPositionStore {
  ReadingPositionStore(this._db, this._sourceId);

  final AppDatabase _db;

  /// Which source this position belongs to (e.g. the BSB's `bsb`).
  final String _sourceId;

  Future<ReadingPosition?> load() async {
    final p = await _db.progressForSource(_sourceId);
    if (p == null) return null;
    final book = Canon.tryUsfm(p.bookUsfm);
    if (book == null) return null;
    return ReadingPosition(
      bookIndex: book.ordinal - 1,
      chapterNumber: p.chapter,
      page: p.page,
    );
  }

  Future<void> save(ReadingPosition position) async {
    final usfm = Canon.byOrdinal(position.bookIndex + 1).usfm;
    await _db.saveProgress(
      sourceId: _sourceId,
      bookUsfm: usfm,
      chapter: position.chapterNumber,
      page: position.page,
    );
  }
}
