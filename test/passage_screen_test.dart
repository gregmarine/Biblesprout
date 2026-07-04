import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:biblesprout/data/bible_database.dart';
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
}
