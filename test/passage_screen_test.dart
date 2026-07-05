import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:biblesprout/data/bible_database.dart';
import 'package:biblesprout/data/commentary_database.dart';
import 'package:biblesprout/screens/passage_screen.dart';

VerseHit _hit(String usfm, int chapter, int verse, String text) => VerseHit(
      verseKey: 0,
      usfm: usfm,
      chapter: chapter,
      verse: verse,
      text: text,
    );

void main() {
  testWidgets('renders a single-chapter passage with a heading', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: PassageScreen(
        title: 'John 3:14–16',
        verses: [
          _hit('JHN', 3, 14, 'Just as Moses lifted up the snake,'),
          _hit('JHN', 3, 15, 'that everyone who believes may have life.'),
          _hit('JHN', 3, 16, 'For God so loved the world.'),
        ],
      ),
    ));

    expect(find.text('John 3:14–16'), findsOneWidget); // top bar title
    expect(find.text('John 3'), findsOneWidget); // inline heading
  });

  testWidgets('inserts a fresh heading when the chapter changes', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: PassageScreen(
        title: 'John 3:36–4:1',
        verses: [
          _hit('JHN', 3, 36, 'Whoever believes has eternal life.'),
          _hit('JHN', 4, 1, 'Now Jesus learned that the Pharisees had heard.'),
        ],
      ),
    ));

    // Short enough to share one page, so both chapter headings are present.
    expect(find.text('John 3'), findsOneWidget);
    expect(find.text('John 4'), findsOneWidget);
  });

  testWidgets('renders multiple books, each with its own heading', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: PassageScreen(
        title: 'John 3:16; Acts 1:3',
        verses: [
          _hit('JHN', 3, 16, 'For God so loved the world.'),
          _hit('ACT', 1, 3, 'After His suffering He presented Himself.'),
        ],
      ),
    ));

    expect(find.text('John 3'), findsOneWidget);
    expect(find.text('Acts 1'), findsOneWidget);
  });

  testWidgets('no "Notes" affordance when no commentary is installed',
      (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: PassageScreen(
        title: 'John 3:16',
        verses: [_hit('JHN', 3, 16, 'For God so loved the world.')],
      ),
    ));

    expect(find.text('Notes'), findsNothing);
  });

  group('with a commentary installed', () {
    late CommentaryDatabase db;

    setUpAll(() async {
      sqfliteFfiInit();
      databaseFactory = databaseFactoryFfi;
      final file = File('assets/commentaries/mhcc.commentary').absolute;
      if (!file.existsSync()) {
        fail('Missing ${file.path} — run: '
            'dart run tool/build_commentary_db.dart mhcc');
      }
      db = await CommentaryDatabase.openFile(file.path);
    });

    tearDownAll(() => db.close());

    testWidgets('"Notes" opens the commentary for the passage span',
        (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: PassageScreen(
          title: 'John 3:16',
          verses: [_hit('JHN', 3, 16, 'For God so loved the world.')],
          commentaries: [db],
        ),
      ));

      expect(find.text('Notes'), findsOneWidget);

      // A single installed commentary skips the picker and opens directly. The
      // real (ffi) DB query resolves off the frame scheduler, so let it run
      // under runAsync before settling the pushed route.
      await tester.runAsync(() async {
        await tester.tap(find.text('Notes'));
        await Future<void>.delayed(const Duration(milliseconds: 200));
      });
      await tester.pumpAndSettle();

      // The commentary route's top bar carries "<abbr> · <reference>".
      expect(find.text('MHCC · John 3:16'), findsOneWidget);
    });
  });
}
