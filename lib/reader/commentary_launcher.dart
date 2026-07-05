import 'package:flutter/material.dart';

import '../data/commentary_database.dart';
import '../screens/commentary_screen.dart';
import '../theme/eink_theme.dart';

/// Opens a commentary over one or more inclusive verse-key [ranges] — a single
/// chapter from the reader, or the (possibly multi-book) span of a passage.
///
/// With more than one commentary installed it first shows a picker; with one it
/// opens directly. Entries are gathered in the order the ranges are given and
/// de-duplicated (a comment block that overlaps two adjacent ranges appears
/// once). Does nothing if no commentary is installed, the picker is dismissed,
/// or the span has no comments.
Future<void> openCommentary({
  required BuildContext context,
  required List<CommentaryDatabase> commentaries,
  required List<(int, int)> ranges,
  required String reference,
}) async {
  if (commentaries.isEmpty) return;
  final db = commentaries.length == 1
      ? commentaries.first
      : await pickCommentary(context, commentaries);
  if (db == null || !context.mounted) return;

  final seen = <int>{};
  final entries = <CommentaryEntry>[];
  for (final (start, end) in ranges) {
    for (final entry in await db.entriesForRange(start, end)) {
      if (seen.add(entry.id)) entries.add(entry);
    }
  }
  if (!context.mounted || entries.isEmpty) return;

  final abbr = db.metadata['abbreviation'] ?? 'Notes';
  await Navigator.of(context).push(
    MaterialPageRoute(
      builder: (_) => CommentaryScreen(
        title: '$abbr · $reference',
        entries: entries,
      ),
    ),
  );
}

/// Presents the installed commentaries as a bordered, scrimless e-ink panel and
/// returns the chosen one (null if dismissed).
Future<CommentaryDatabase?> pickCommentary(
  BuildContext context,
  List<CommentaryDatabase> options,
) {
  return showDialog<CommentaryDatabase>(
    context: context,
    // No dim scrim on e-ink; the hard black border delineates the panel.
    barrierColor: Colors.transparent,
    builder: (context) => Dialog(
      backgroundColor: Eink.white,
      elevation: 0,
      shape: RoundedRectangleBorder(
        side: const BorderSide(color: Eink.black, width: 2),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(20, 18, 20, 12),
            child: Text(
              'Commentary',
              style: TextStyle(
                fontFamily: Eink.fontFamily,
                fontSize: 20,
                fontWeight: FontWeight.bold,
                color: Eink.black,
              ),
            ),
          ),
          for (final db in options)
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => Navigator.of(context).pop(db),
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 16,
                ),
                decoration: const BoxDecoration(
                  border: Border(top: BorderSide(color: Eink.rule)),
                ),
                child: Text(
                  db.title,
                  style: const TextStyle(
                    fontFamily: Eink.fontFamily,
                    fontSize: 18,
                    color: Eink.black,
                  ),
                ),
              ),
            ),
        ],
      ),
    ),
  );
}
