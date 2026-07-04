import 'dart:convert' show LineSplitter;

import 'package:flutter/services.dart' show rootBundle;

import '../models/bible.dart';

/// Loads and parses the Berean Standard Bible from the bundled asset.
///
/// The source file (`assets/bible/bsb.txt`) is tab-delimited:
///
///     Book Chapter:Verse<TAB>Verse text
///
/// preceded by two publisher metadata lines and one column-header line. Book
/// names may contain spaces and leading digits ("1 Samuel", "Song of
/// Solomon"), so the reference is parsed from the right.
class BibleRepository {
  BibleRepository._(this.bible);

  final Bible bible;

  static const _assetPath = 'assets/bible/bsb.txt';

  /// Number of books in the Old Testament; the rest are New Testament.
  static const _oldTestamentBookCount = 39;

  /// Cosmetic display-name fixups for the raw source labels.
  static const _displayNames = <String, String>{'Psalm': 'Psalms'};

  static final RegExp _refPattern = RegExp(r'^(.+) (\d+):(\d+)$');

  static Future<BibleRepository> load() async {
    final raw = await rootBundle.loadString(_assetPath);
    return BibleRepository._(_parse(raw));
  }

  static Bible _parse(String raw) {
    // Strip a leading UTF-8 BOM if present.
    if (raw.isNotEmpty && raw.codeUnitAt(0) == 0xFEFF) {
      raw = raw.substring(1);
    }

    final books = <Book>[];
    final chaptersByBook = <String, List<Chapter>>{};
    final bookOrder = <String>[];

    // Working accumulators for the chapter currently being read.
    String? currentBook;
    int? currentChapter;
    List<Verse> currentVerses = [];

    void flushChapter() {
      if (currentBook != null && currentChapter != null) {
        chaptersByBook
            .putIfAbsent(currentBook, () => [])
            .add(Chapter(currentChapter, currentVerses));
      }
      currentVerses = [];
    }

    for (final line in const LineSplitter().convert(raw)) {
      final tab = line.indexOf('\t');
      if (tab < 0) continue; // header/metadata lines have no reference+text pair
      final ref = line.substring(0, tab).trim();
      final text = line.substring(tab + 1).trim();

      final match = _refPattern.firstMatch(ref);
      if (match == null) continue; // e.g. the "Verse" column header row

      final bookName = match.group(1)!;
      final chapterNum = int.parse(match.group(2)!);
      final verseNum = int.parse(match.group(3)!);

      if (bookName != currentBook) {
        flushChapter();
        if (!chaptersByBook.containsKey(bookName)) {
          bookOrder.add(bookName);
        }
        currentBook = bookName;
        currentChapter = chapterNum;
      } else if (chapterNum != currentChapter) {
        flushChapter();
        currentChapter = chapterNum;
      }

      currentVerses.add(Verse(verseNum, text));
    }
    flushChapter();

    for (var i = 0; i < bookOrder.length; i++) {
      final sourceName = bookOrder[i];
      books.add(
        Book(
          index: i,
          name: _displayNames[sourceName] ?? sourceName,
          testament:
              i < _oldTestamentBookCount ? Testament.old : Testament.newT,
          chapters: chaptersByBook[sourceName]!,
        ),
      );
    }

    return Bible(books);
  }
}
