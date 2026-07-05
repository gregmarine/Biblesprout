import 'package:flutter/material.dart';

import '../data/bible_database.dart';
import '../data/canon.dart';
import '../data/commentary_database.dart';
import '../models/reference.dart';
import '../reader/commentary_launcher.dart';
import '../reader/flowing_document.dart';
import '../reader/paginator.dart';
import '../reader/passage_paginator.dart';

/// A jump-to-passage view: the selected verses rendered as flowing, paginated
/// text — just like the chapter reader, not a list. A "John 3" heading sits at
/// the top, verse numbers are superscript, and if the passage crosses a chapter
/// or book boundary a fresh heading is inserted inline. Long passages paginate.
class PassageScreen extends StatelessWidget {
  const PassageScreen({
    super.key,
    required this.title,
    required this.verses,
    this.commentaries = const [],
  });

  /// The canonical reference label, e.g. "John 3:14–16, 18".
  final String title;

  /// The passage's verses in reading order.
  final List<VerseHit> verses;

  /// Installed commentaries; when non-empty the top bar offers a "Notes"
  /// affordance opening commentary for the passage's span.
  final List<CommentaryDatabase> commentaries;

  @override
  Widget build(BuildContext context) {
    return FlowingDocument(
      title: title,
      blocks: _buildBlocks(),
      onNotes: commentaries.isEmpty
          ? null
          : () => openCommentary(
                context: context,
                commentaries: commentaries,
                ranges: _commentaryRanges(),
                reference: title,
              ),
      onVerseTap: commentaries.isEmpty
          ? null
          : (verseKey) => openVerseCommentary(
                context: context,
                commentaries: commentaries,
                verseKey: verseKey,
              ),
    );
  }

  /// The passage's verses collapsed into one inclusive verse-key range per
  /// (book, chapter), in first-seen order — the spans to gather commentary over.
  List<(int, int)> _commentaryRanges() {
    // Keyed by "usfm.chapter"; records the ordinal and the min/max verse seen.
    final groups = <String, List<int>>{}; // [ordinal, chapter, minVerse, maxVerse]
    for (final v in verses) {
      final key = '${v.usfm}.${v.chapter}';
      final g = groups[key];
      if (g == null) {
        final ordinal = Canon.byUsfm(v.usfm).ordinal;
        groups[key] = [ordinal, v.chapter, v.verse, v.verse];
      } else {
        if (v.verse < g[2]) g[2] = v.verse;
        if (v.verse > g[3]) g[3] = v.verse;
      }
    }
    return [
      for (final g in groups.values)
        (VerseKey.encode(g[0], g[1], g[2]), VerseKey.encode(g[0], g[1], g[3])),
    ];
  }

  /// Groups the verses into heading + text blocks, opening a new heading each
  /// time the book or chapter changes.
  List<PassageItem> _buildBlocks() {
    final blocks = <PassageItem>[];
    String? currentKey;
    var atoms = <Atom>[];

    void flush() {
      if (atoms.isNotEmpty) {
        blocks.add(TextItem(atoms));
        atoms = <Atom>[];
      }
    }

    for (final v in verses) {
      final key = '${v.usfm}.${v.chapter}';
      if (key != currentKey) {
        flush();
        blocks.add(HeadingItem('${Canon.byUsfm(v.usfm).name} ${v.chapter}'));
        currentKey = key;
      }
      atoms.add(NumberAtom(
        v.verse,
        verseKey: VerseKey.encode(Canon.byUsfm(v.usfm).ordinal, v.chapter, v.verse),
      ));
      for (final word in v.text.split(RegExp(r'\s+'))) {
        if (word.isNotEmpty) atoms.add(WordAtom(word));
      }
    }
    flush();
    return blocks;
  }
}
