/// Core domain model for the Bible text.
///
/// The hierarchy is intentionally simple and immutable: a [Bible] owns an
/// ordered list of [Book]s, each [Book] owns [Chapter]s, and each [Chapter]
/// owns [Verse]s. Order is significant everywhere and mirrors the source file.
library;

enum Testament { old, newT }

class Verse {
  const Verse(this.number, this.text);

  final int number;
  final String text;
}

class Chapter {
  Chapter(this.number, this.verses);

  final int number;
  final List<Verse> verses;
}

class Book {
  Book({
    required this.index,
    required this.name,
    required this.testament,
    required this.chapters,
  });

  /// Zero-based position in canonical order (0 = Genesis, 65 = Revelation).
  final int index;

  /// Display name, e.g. "Genesis", "1 Samuel", "Psalms".
  final String name;
  final Testament testament;
  final List<Chapter> chapters;

  int get chapterCount => chapters.length;
}

/// A flattened reference to a single chapter within the whole Bible, used to
/// walk continuously forward/backward across book boundaries.
class ChapterRef {
  const ChapterRef(this.bookIndex, this.chapterNumber);

  final int bookIndex;
  final int chapterNumber;
}

class Bible {
  Bible(this.books);

  final List<Book> books;

  List<Book> get oldTestament =>
      books.where((b) => b.testament == Testament.old).toList(growable: false);

  List<Book> get newTestament =>
      books.where((b) => b.testament == Testament.newT).toList(growable: false);

  Book bookAt(int index) => books[index];

  Chapter chapter(int bookIndex, int chapterNumber) =>
      books[bookIndex].chapters[chapterNumber - 1];

  /// The chapter immediately after the given ref, or null if this is the very
  /// last chapter of Revelation.
  ChapterRef? next(ChapterRef ref) {
    final book = books[ref.bookIndex];
    if (ref.chapterNumber < book.chapterCount) {
      return ChapterRef(ref.bookIndex, ref.chapterNumber + 1);
    }
    if (ref.bookIndex < books.length - 1) {
      return ChapterRef(ref.bookIndex + 1, 1);
    }
    return null;
  }

  /// The chapter immediately before the given ref, or null if this is
  /// Genesis 1.
  ChapterRef? previous(ChapterRef ref) {
    if (ref.chapterNumber > 1) {
      return ChapterRef(ref.bookIndex, ref.chapterNumber - 1);
    }
    if (ref.bookIndex > 0) {
      final prevBook = books[ref.bookIndex - 1];
      return ChapterRef(ref.bookIndex - 1, prevBook.chapterCount);
    }
    return null;
  }
}
