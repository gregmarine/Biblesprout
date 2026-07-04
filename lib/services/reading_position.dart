import 'package:shared_preferences/shared_preferences.dart';

/// The reader's last location, persisted so the app reopens where it left off.
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
  static const _kBook = 'pos.bookIndex';
  static const _kChapter = 'pos.chapterNumber';
  static const _kPage = 'pos.page';

  Future<ReadingPosition?> load() async {
    final prefs = await SharedPreferences.getInstance();
    if (!prefs.containsKey(_kBook)) return null;
    return ReadingPosition(
      bookIndex: prefs.getInt(_kBook) ?? 0,
      chapterNumber: prefs.getInt(_kChapter) ?? 1,
      page: prefs.getInt(_kPage) ?? 0,
    );
  }

  Future<void> save(ReadingPosition position) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_kBook, position.bookIndex);
    await prefs.setInt(_kChapter, position.chapterNumber);
    await prefs.setInt(_kPage, position.page);
  }
}
