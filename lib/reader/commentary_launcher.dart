import 'package:flutter/material.dart';

import '../data/canon.dart';
import '../data/commentary_database.dart';
import '../models/reference.dart';
import '../screens/commentary_screen.dart';
import '../services/commentary_preferences.dart';
import '../theme/eink_theme.dart';

/// Opens commentary anchored to a single verse [verseKey] (last-used commentary,
/// or a picker when more than one is installed and none has been used yet). The
/// reference label is derived from the key, e.g. "John 3:16".
Future<void> openVerseCommentary({
  required BuildContext context,
  required List<CommentaryDatabase> commentaries,
  required int verseKey,
  CommentaryPreferences? prefs,
}) {
  final name = Canon.byOrdinal(VerseKey.ordinalOf(verseKey)).name;
  final reference =
      '$name ${VerseKey.chapterOf(verseKey)}:${VerseKey.verseOf(verseKey)}';
  return openCommentary(
    context: context,
    commentaries: commentaries,
    ranges: [(verseKey, verseKey)],
    reference: reference,
    prefs: prefs,
  );
}

/// Opens a commentary over one or more inclusive verse-key [ranges] — a single
/// chapter from the reader, or the (possibly multi-book) span of a passage.
///
/// With more than one commentary installed it opens the last-used one directly;
/// if none has been used yet it first shows a picker (and remembers the choice).
/// The opened screen offers a "Change" affordance to switch. Entries are
/// gathered in the order the ranges are given and de-duplicated (a comment block
/// that overlaps two adjacent ranges appears once). Does nothing if no
/// commentary is installed, the picker is dismissed, or the span has no comments.
Future<void> openCommentary({
  required BuildContext context,
  required List<CommentaryDatabase> commentaries,
  required List<(int, int)> ranges,
  required String reference,
  CommentaryPreferences? prefs,
}) async {
  if (commentaries.isEmpty) return;
  final db = await _resolve(context, commentaries, prefs);
  if (db == null || !context.mounted) return;
  await _show(
    context: context,
    commentaries: commentaries,
    ranges: ranges,
    reference: reference,
    prefs: prefs,
    db: db,
    replace: false,
  );
}

/// Picks the commentary to open: the sole one, the remembered one if it is still
/// installed, or the user's choice from the picker (which is then remembered).
Future<CommentaryDatabase?> _resolve(
  BuildContext context,
  List<CommentaryDatabase> commentaries,
  CommentaryPreferences? prefs,
) async {
  if (commentaries.length == 1) return commentaries.first;
  final lastId = prefs?.lastId;
  if (lastId != null) {
    for (final db in commentaries) {
      if (db.id == lastId) return db;
    }
  }
  final chosen = await pickCommentary(context, commentaries);
  if (chosen != null) await prefs?.remember(chosen.id);
  return chosen;
}

/// Gathers [db]'s entries over [ranges] and pushes (or replaces the top route
/// with) the commentary screen. A no-op if nothing was found.
Future<void> _show({
  required BuildContext context,
  required List<CommentaryDatabase> commentaries,
  required List<(int, int)> ranges,
  required String reference,
  required CommentaryPreferences? prefs,
  required CommentaryDatabase db,
  required bool replace,
}) async {
  final seen = <int>{};
  final entries = <CommentaryEntry>[];
  for (final (start, end) in ranges) {
    for (final entry in await db.entriesForRange(start, end)) {
      if (seen.add(entry.id)) entries.add(entry);
    }
  }
  if (!context.mounted || entries.isEmpty) return;

  final abbr = db.metadata['abbreviation'] ?? 'Notes';
  final route = MaterialPageRoute<void>(
    builder: (_) => CommentaryScreen(
      title: '$abbr · $reference',
      entries: entries,
      // Only offer switching when there is something to switch to.
      onChange: commentaries.length > 1
          ? () => _change(
                context: context,
                commentaries: commentaries,
                ranges: ranges,
                reference: reference,
                prefs: prefs,
              )
          : null,
    ),
  );
  final navigator = Navigator.of(context);
  if (replace) {
    await navigator.pushReplacement(route);
  } else {
    await navigator.push(route);
  }
}

/// Re-opens the picker from an already-open commentary screen and, if a
/// commentary is chosen, remembers it and replaces the screen with its notes.
Future<void> _change({
  required BuildContext context,
  required List<CommentaryDatabase> commentaries,
  required List<(int, int)> ranges,
  required String reference,
  required CommentaryPreferences? prefs,
}) async {
  final chosen = await pickCommentary(context, commentaries);
  if (chosen == null || !context.mounted) return;
  await prefs?.remember(chosen.id);
  if (!context.mounted) return;
  await _show(
    context: context,
    commentaries: commentaries,
    ranges: ranges,
    reference: reference,
    prefs: prefs,
    db: chosen,
    replace: true,
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
