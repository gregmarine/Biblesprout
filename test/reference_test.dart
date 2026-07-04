import 'package:flutter_test/flutter_test.dart';

import 'package:biblesprout/data/canon.dart';
import 'package:biblesprout/models/reference.dart';

void main() {
  group('Canon', () {
    test('has 66 books in canonical order with unique USFM codes', () {
      expect(Canon.books, hasLength(66));
      expect(Canon.books.first.usfm, 'GEN');
      expect(Canon.books.last.usfm, 'REV');
      final codes = Canon.books.map((b) => b.usfm).toSet();
      expect(codes, hasLength(66));
      for (var i = 0; i < Canon.books.length; i++) {
        expect(Canon.books[i].ordinal, i + 1);
      }
    });

    test('resolves names, abbreviations, punctuation and Roman numerals', () {
      expect(Canon.lookup('Genesis')?.usfm, 'GEN');
      expect(Canon.lookup('gen')?.usfm, 'GEN');
      expect(Canon.lookup('Ps')?.usfm, 'PSA');
      expect(Canon.lookup('Psalm')?.usfm, 'PSA'); // source-file singular
      expect(Canon.lookup('1 Cor')?.usfm, '1CO');
      expect(Canon.lookup('1cor')?.usfm, '1CO');
      expect(Canon.lookup('I Corinthians')?.usfm, '1CO');
      expect(Canon.lookup('Song of Songs')?.usfm, 'SNG');
      expect(Canon.lookup('nonsense'), isNull);
    });
  });

  group('VerseKey', () {
    test('packs and unpacks a verse address', () {
      final k = VerseKey.encode(43, 3, 16); // John 3:16
      expect(k, 43003016);
      expect(VerseKey.ordinalOf(k), 43);
      expect(VerseKey.chapterOf(k), 3);
      expect(VerseKey.verseOf(k), 16);
    });

    test('keys sort in canonical reading order', () {
      final john316 = VerseKey.encode(43, 3, 16);
      final john317 = VerseKey.encode(43, 3, 17);
      final acts11 = VerseKey.encode(44, 1, 1);
      expect(john316 < john317, isTrue);
      expect(john317 < acts11, isTrue);
    });
  });

  group('ReferenceParser', () {
    test('single verse', () {
      final p = ReferenceParser.parse('John 3:16')!;
      expect(p.book.usfm, 'JHN');
      expect(p.ranges, [VerseRange.verse(43, 3, 16)]);
    });

    test('simple range Gen 1:5-10', () {
      final p = ReferenceParser.parse('Gen 1:5-10')!;
      expect(p.ranges, [VerseRange.verses(1, 1, 5, 10)]);
    });

    test('range with a gap: John 3:14-16,18 skips 17', () {
      final p = ReferenceParser.parse('John 3:14-16,18')!;
      expect(p.ranges, [
        VerseRange.verses(43, 3, 14, 16),
        VerseRange.verse(43, 3, 18),
      ]);
      // 17 is not covered by any range.
      final v17 = VerseKey.encode(43, 3, 17);
      expect(p.ranges.any((r) => r.contains(v17)), isFalse);
    });

    test('comma carries chapter but a colon changes it', () {
      final p = ReferenceParser.parse('John 3:16,4:1')!;
      expect(p.ranges, [
        VerseRange.verse(43, 3, 16),
        VerseRange.verse(43, 4, 1),
      ]);
    });

    test('whole chapter', () {
      final p = ReferenceParser.parse('Psalm 23')!;
      expect(p.book.usfm, 'PSA');
      expect(p.ranges.single, VerseRange.chapters(19, 23, 23));
      // The whole-chapter band contains any verse of chapter 23.
      expect(p.ranges.single.contains(VerseKey.encode(19, 23, 6)), isTrue);
    });

    test('cross-chapter verse span Gen 1:5-2:3', () {
      final p = ReferenceParser.parse('Gen 1:5-2:3')!;
      expect(p.startKey, VerseKey.encode(1, 1, 5));
      expect(p.endKey, VerseKey.encode(1, 2, 3));
    });

    test('spaceless and abbreviated: 1cor13:4-7', () {
      final p = ReferenceParser.parse('1cor13:4-7')!;
      expect(p.book.usfm, '1CO');
      expect(p.ranges.single, VerseRange.verses(46, 13, 4, 7));
    });

    test('formats back to a tidy canonical string', () {
      expect(ReferenceParser.parse('john 3:14-16,18')!.format(),
          'John 3:14–16, 18');
      expect(ReferenceParser.parse('ps 23')!.format(), 'Psalms 23');
    });

    test('rejects unknown books and malformed input', () {
      expect(ReferenceParser.parse('Hesitations 1:1'), isNull);
      expect(ReferenceParser.parse('John'), isNull);
    });
  });
}
