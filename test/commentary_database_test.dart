import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:biblesprout/data/commentary_database.dart';
import 'package:biblesprout/models/reference.dart';

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });

  group('CommentaryDatabase (mhcc.commentary)', () {
    late CommentaryDatabase db;

    setUpAll(() async {
      final file = File('assets/commentaries/mhcc.commentary').absolute;
      if (!file.existsSync()) {
        fail('Missing ${file.path} — run: '
            'dart run tool/build_commentary_db.dart mhcc');
      }
      db = await CommentaryDatabase.openFile(file.path);
    });

    tearDownAll(() => db.close());

    test('metadata identifies Matthew Henry Concise', () {
      expect(db.id, 'mhcc');
      expect(db.title, "Matthew Henry's Concise Commentary");
      expect(db.metadata['type'], 'commentary');
    });

    int key(int ordinal, int chapter, int verse) =>
        VerseKey.encode(ordinal, chapter, verse);

    test('John 3:16 resolves to the comment that expounds it', () async {
      final hits = await db.entriesForVerse(key(43, 3, 16));
      expect(hits, isNotEmpty);
      expect(hits.first.usfm, 'JHN');
      expect(hits.first.chapter, 3);
      // The famous verse's block (labelled 1-8 in the source) actually runs
      // through it — verify the text is really about John 3:16.
      expect(hits.first.body, contains('God so loved the world'));
    });

    test('a whole-chapter book (Psalm 23) is covered', () async {
      final hits = await db.entriesForVerse(key(19, 23, 1));
      expect(hits, isNotEmpty);
      expect(hits.first.body.toLowerCase(), contains('shepherd'));
    });

    test('Jude is present and not misfiled under Judges', () async {
      final jude = await db.entriesForVerse(key(65, 1, 3));
      expect(jude, isNotEmpty);
      expect(jude.first.usfm, 'JUD');

      final judges = await db.entriesForVerse(key(7, 1, 1));
      expect(judges, isNotEmpty);
      expect(judges.first.usfm, 'JDG');
      expect(judges.first.body, isNot(contains('epistle')));
    });

    test('entriesForRange overlaps a passage', () async {
      // Matthew 5 (the Sermon on the Mount) spans several comment blocks.
      final hits = await db.entriesForRange(key(40, 5, 1), key(40, 5, 12));
      expect(hits.length, greaterThan(1));
      expect(hits.every((e) => e.usfm == 'MAT' && e.chapter == 5), isTrue);
    });

    test('FTS5 search finds comments by word', () async {
      List<CommentaryEntry> hits;
      try {
        hits = await db.search('shepherd');
      } on DatabaseException catch (e) {
        if (e.toString().contains('fts5')) {
          markTestSkipped('FTS5 unavailable in this test SQLite: $e');
          return;
        }
        rethrow;
      }
      expect(hits, isNotEmpty);
      expect(hits.first.body.toLowerCase(), contains('shepherd'));
    });
  });

  group('CommentaryDatabase (mhc.commentary — Complete)', () {
    late CommentaryDatabase db;

    setUpAll(() async {
      final file = File('assets/commentaries/mhc.commentary').absolute;
      if (!file.existsSync()) {
        fail('Missing ${file.path} — run: '
            'dart run tool/build_commentary_db.dart mhc');
      }
      db = await CommentaryDatabase.openFile(file.path);
    });

    tearDownAll(() => db.close());

    int key(int ordinal, int chapter, int verse) =>
        VerseKey.encode(ordinal, chapter, verse);

    test('metadata identifies Matthew Henry Complete', () {
      expect(db.id, 'mhc');
      expect(db.title, "Matthew Henry's Complete Commentary");
      expect(db.metadata['type'], 'commentary');
    });

    test('covers a verse from the first and last volume', () async {
      // Genesis 1 (vol. 1) and Revelation 22 (vol. 6) both resolve.
      expect(await db.entriesForVerse(key(1, 1, 1)), isNotEmpty);
      expect(await db.entriesForVerse(key(66, 22, 1)), isNotEmpty);
    });

    test('John 15 has descriptive section headings', () async {
      final hits = await db.entriesForRange(key(43, 15, 1), key(43, 15, 27));
      expect(hits, isNotEmpty);
      expect(hits.any((e) => e.heading == 'Christ the True Vine.'), isTrue);
    });

    test('Roman-numeral chapters resolve (Psalm 119 = chapter CXIX)', () async {
      final hits = await db.entriesForVerse(key(19, 119, 1));
      expect(hits, isNotEmpty);
      expect(hits.first.usfm, 'PSA');
      expect(hits.first.chapter, 119);
    });

    test('body holds exposition, not the quoted scripture text', () async {
      final hits = await db.entriesForVerse(key(43, 15, 1));
      expect(hits, isNotEmpty);
      // The KJV wording of John 15:1 is embedded in the source as a <p
      // class="passage"> block that we deliberately drop; only Henry's
      // exposition should remain.
      expect(
        hits.first.body,
        isNot(contains('I am the true vine, and my Father is the husbandman')),
      );
    });
  });
}
