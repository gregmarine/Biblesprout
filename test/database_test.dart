import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:biblesprout/data/app_database.dart';
import 'package:biblesprout/data/bible_database.dart';
import 'package:biblesprout/models/reference.dart';

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });

  group('BibleDatabase (bsb.bible)', () {
    late BibleDatabase db;

    setUpAll(() async {
      // Absolute path: sqflite_common_ffi resolves relative paths against its
      // own default databases directory, not the project root.
      final file = File('assets/bible/bsb.bible').absolute;
      if (!file.existsSync()) {
        fail('Missing ${file.path} — run: dart run tool/build_bible_db.dart');
      }
      db = await BibleDatabase.openFile(file.path);
    });

    tearDownAll(() => db.close());

    test('metadata identifies the BSB', () {
      expect(db.id, 'bsb');
      expect(db.title, 'Berean Standard Bible');
      expect(db.metadata['versification'], 'english');
    });

    test('loadBible rebuilds all 66 books in order', () async {
      final bible = await db.loadBible();
      expect(bible.books, hasLength(66));
      expect(bible.books.first.name, 'Genesis');
      expect(bible.books.last.name, 'Revelation');
      // Genesis 1:1 round-trips through the object graph.
      expect(bible.chapter(0, 1).verses.first.text,
          startsWith('In the beginning'));
    });

    test('versesForPassage resolves John 3:14-16,18 and skips 17', () async {
      final passage = ReferenceParser.parse('John 3:14-16,18')!;
      final hits = await db.versesForPassage(passage);
      expect(hits.map((h) => h.verse), [14, 15, 16, 18]);
      expect(hits.first.reference, 'John 3:14');
      expect(hits[2].text, contains('loved the world'));
    });

    test('whole-chapter range returns every verse of Psalm 23', () async {
      final passage = ReferenceParser.parse('Psalm 23')!;
      final hits = await db.versesForPassage(passage);
      expect(hits.first.verse, 1);
      expect(hits.length, greaterThan(1));
      expect(hits.every((h) => h.chapter == 23), isTrue);
    });

    test('FTS5 search finds verses by word', () async {
      List<VerseHit> hits;
      try {
        hits = await db.search('shepherd');
      } on DatabaseException catch (e) {
        // The host SQLite may lack FTS5; the on-device engine bundles it.
        if (e.toString().contains('fts5')) {
          markTestSkipped('FTS5 unavailable in this test SQLite: $e');
          return;
        }
        rethrow;
      }
      expect(hits, isNotEmpty);
      expect(hits.first.text.toLowerCase(), contains('shepherd'));
      // Psalm 23:1 is the canonical hit and should rank highly.
      expect(hits.any((h) => h.reference == 'Psalms 23:1'), isTrue);
    });
  });

  group('AppDatabase (global index)', () {
    late Directory tmp;
    late AppDatabase db;

    setUp(() async {
      tmp = await Directory.systemTemp.createTemp('biblesprout_test');
      db = await AppDatabase.openFile('${tmp.path}/biblesprout.db');
    });

    tearDown(() async {
      await db.close();
      await tmp.delete(recursive: true);
    });

    test('registers a source and lists it', () async {
      await db.registerSource(const SourceRecord(
        id: 'bsb',
        type: 'bible',
        title: 'Berean Standard Bible',
        abbreviation: 'BSB',
        language: 'en',
        fileName: 'bsb.bible',
        versification: 'english',
      ));
      final sources = await db.installedSources();
      expect(sources.single.id, 'bsb');
      expect(sources.single.isReadonly, isTrue);
    });

    test('reading progress round-trips and latest wins', () async {
      await db.saveProgress(
          sourceId: 'bsb', bookUsfm: 'GEN', chapter: 1, page: 0);
      await db.saveProgress(
          sourceId: 'bsb', bookUsfm: 'JHN', chapter: 3, page: 2);
      final p = await db.progressForSource('bsb');
      expect(p!.bookUsfm, 'JHN'); // upsert replaced the row
      expect(p.chapter, 3);
      expect(p.page, 2);

      final latest = await db.latestProgress();
      expect(latest!.sourceId, 'bsb');
    });

    test('latestProgress picks the most recently updated source', () async {
      await db.saveProgress(
          sourceId: 'bsb', bookUsfm: 'GEN', chapter: 1, page: 0);
      await Future<void>.delayed(const Duration(milliseconds: 5));
      await db.saveProgress(
          sourceId: 'mhc', bookUsfm: 'ROM', chapter: 8, page: 1);
      final latest = await db.latestProgress();
      expect(latest!.sourceId, 'mhc');
    });
  });
}
