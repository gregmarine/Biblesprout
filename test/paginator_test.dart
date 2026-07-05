import 'package:flutter_test/flutter_test.dart';

import 'package:biblesprout/reader/paginator.dart';

void main() {
  group('Paginator.verseKeyAtOffset', () {
    // Two verses: "14 Just as | 15 that" — offsets mirror how renderSpans
    // concatenates atoms (number = 1 placeholder char; a leading space before
    // every atom after the first).
    final atoms = <Atom>[
      const NumberAtom(14, verseKey: 43003014), // [0,1)
      const WordAtom('Just'), //                   [1,6)
      const WordAtom('as'), //                     [6,9)
      const NumberAtom(15, verseKey: 43003015), // [9,11)
      const WordAtom('that'), //                   [11,16)
    ];

    test('the number and its words resolve to that verse', () {
      expect(Paginator.verseKeyAtOffset(atoms, 0), 43003014); // the "14"
      expect(Paginator.verseKeyAtOffset(atoms, 3), 43003014); // in "Just"
      expect(Paginator.verseKeyAtOffset(atoms, 7), 43003014); // in "as"
    });

    test('crossing the next number switches verse', () {
      expect(Paginator.verseKeyAtOffset(atoms, 9), 43003015); // the "15"
      expect(Paginator.verseKeyAtOffset(atoms, 13), 43003015); // in "that"
    });

    test('an offset past the end clamps to the last verse', () {
      expect(Paginator.verseKeyAtOffset(atoms, 999), 43003015);
    });

    test('untagged atoms (no keys) yield null', () {
      final untagged = <Atom>[const NumberAtom(1), const WordAtom('word')];
      expect(Paginator.verseKeyAtOffset(untagged, 0), isNull);
    });
  });
}
