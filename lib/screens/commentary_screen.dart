import 'package:flutter/material.dart';

import '../data/commentary_database.dart';
import '../reader/flowing_document.dart';
import '../reader/paginator.dart';
import '../reader/passage_paginator.dart';

/// A chapter's commentary rendered as flowing, paginated text — the same
/// reading surface as the passage view. Each comment's section heading (Matthew
/// Henry's "Verses 1–8") introduces its prose; whole-chapter comments flow with
/// no heading. Opened from the reader's "Notes" affordance for the chapter in
/// view.
class CommentaryScreen extends StatelessWidget {
  const CommentaryScreen({
    super.key,
    required this.title,
    required this.entries,
  });

  /// Top-bar label, e.g. "MHCC · John 3".
  final String title;

  /// The chapter's comments, in reading order.
  final List<CommentaryEntry> entries;

  @override
  Widget build(BuildContext context) {
    return FlowingDocument(title: title, blocks: _buildBlocks());
  }

  List<PassageItem> _buildBlocks() {
    final blocks = <PassageItem>[];
    for (final entry in entries) {
      final heading = entry.heading;
      if (heading != null) blocks.add(HeadingItem(heading));

      // Commentary prose has no verse numbers, so the body is a flow of words.
      final atoms = <Atom>[
        for (final word in entry.body.split(RegExp(r'\s+')))
          if (word.isNotEmpty) WordAtom(word),
      ];
      if (atoms.isNotEmpty) blocks.add(TextItem(atoms));
    }
    return blocks;
  }
}
