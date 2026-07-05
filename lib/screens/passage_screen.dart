import 'package:flutter/material.dart';

import '../data/bible_database.dart';
import '../data/canon.dart';
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
  });

  /// The canonical reference label, e.g. "John 3:14–16, 18".
  final String title;

  /// The passage's verses in reading order.
  final List<VerseHit> verses;

  @override
  Widget build(BuildContext context) {
    return FlowingDocument(title: title, blocks: _buildBlocks());
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
      atoms.add(NumberAtom(v.verse));
      for (final word in v.text.split(RegExp(r'\s+'))) {
        if (word.isNotEmpty) atoms.add(WordAtom(word));
      }
    }
    flush();
    return blocks;
  }
}
