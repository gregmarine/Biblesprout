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
}
