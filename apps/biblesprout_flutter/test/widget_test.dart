import 'package:flutter_test/flutter_test.dart';

import 'package:biblesprout/models/bible.dart';

Bible _tinyBible() {
  Book book(int index, String name, Testament t, int chapters) => Book(
        index: index,
        name: name,
        testament: t,
        chapters: [
          for (var c = 1; c <= chapters; c++)
            Chapter(c, const [Verse(1, 'x')]),
        ],
      );
  return Bible([
    book(0, 'Genesis', Testament.old, 50),
    book(1, 'Exodus', Testament.old, 40),
    book(2, 'Revelation', Testament.newT, 22),
  ]);
}

void main() {
  test('next() advances within a book', () {
    final bible = _tinyBible();
    final n = bible.next(const ChapterRef(0, 1));
    expect(n!.bookIndex, 0);
    expect(n.chapterNumber, 2);
  });

  test('next() crosses into the following book', () {
    final bible = _tinyBible();
    final n = bible.next(const ChapterRef(0, 50));
    expect(n!.bookIndex, 1);
    expect(n.chapterNumber, 1);
  });

  test('next() returns null at the very end', () {
    final bible = _tinyBible();
    expect(bible.next(const ChapterRef(2, 22)), isNull);
  });

  test('previous() crosses back to the prior book last chapter', () {
    final bible = _tinyBible();
    final p = bible.previous(const ChapterRef(1, 1));
    expect(p!.bookIndex, 0);
    expect(p.chapterNumber, 50);
  });

  test('previous() returns null at Genesis 1', () {
    final bible = _tinyBible();
    expect(bible.previous(const ChapterRef(0, 1)), isNull);
  });
}
