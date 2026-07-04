import 'package:flutter/material.dart';

import '../data/bible_database.dart';
import '../models/bible.dart';
import '../reader/reader_screen.dart';
import '../services/reading_position.dart';
import '../theme/eink_theme.dart';
import '../widgets/paged_view.dart';
import 'chapters_screen.dart';
import 'find_screen.dart';

/// Home screen: the table of contents. Lists all 66 books grouped by
/// testament, with a "Continue reading" shortcut to the last-read position.
///
/// Like a physical Bible's contents, the list does not scroll — it paginates.
/// Swipe left/right (or use the footer arrows) to turn pages; tap a book to
/// open it.
class LibraryScreen extends StatefulWidget {
  const LibraryScreen({
    super.key,
    required this.bible,
    required this.bibleDb,
    required this.store,
    this.lastPosition,
  });

  final Bible bible;
  final BibleDatabase bibleDb;
  final ReadingPositionStore store;
  final ReadingPosition? lastPosition;

  @override
  State<LibraryScreen> createState() => _LibraryScreenState();
}

class _LibraryScreenState extends State<LibraryScreen> {
  ReadingPosition? _position;

  @override
  void initState() {
    super.initState();
    _position = widget.lastPosition;
  }

  /// Re-reads the saved position from disk. Called after returning from the
  /// reader so the "Continue reading" shortcut reflects the latest location.
  Future<void> _refreshPosition() async {
    final pos = await widget.store.load();
    if (mounted) setState(() => _position = pos);
  }

  Future<void> _openChapters(BuildContext context, Book book) async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ChaptersScreen(
          bible: widget.bible,
          store: widget.store,
          book: book,
        ),
      ),
    );
    await _refreshPosition();
  }

  Future<void> _openFind(BuildContext context) async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => FindScreen(
          bible: widget.bible,
          bibleDb: widget.bibleDb,
          store: widget.store,
        ),
      ),
    );
    await _refreshPosition();
  }

  Future<void> _continueReading(
    BuildContext context,
    ReadingPosition pos,
  ) async {
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ReaderScreen(
          bible: widget.bible,
          store: widget.store,
          start: ChapterRef(pos.bookIndex, pos.chapterNumber),
          startPage: pos.page,
        ),
      ),
    );
    await _refreshPosition();
  }

  List<_TocEntry> _entries() {
    return [
      const _TocEntry.label('Old Testament'),
      for (final b in widget.bible.oldTestament) _TocEntry.book(b),
      const _TocEntry.label('New Testament'),
      for (final b in widget.bible.newTestament) _TocEntry.book(b),
    ];
  }

  /// Packs entries into pages that fit [height]. A testament label is never
  /// left as the last row of a page — it is only placed if the book that
  /// follows it also fits, so headings always sit above their books.
  List<Widget> _buildPages(BuildContext context, double width, double height) {
    final entries = _entries();
    final pages = <List<_TocEntry>>[];
    var current = <_TocEntry>[];
    var used = 0.0;

    for (var i = 0; i < entries.length; i++) {
      final entry = entries[i];
      var needed = entry.height;
      if (entry.isLabel && i + 1 < entries.length) {
        needed += entries[i + 1].height; // keep heading with its first book
      }
      if (current.isNotEmpty && used + needed > height) {
        pages.add(current);
        current = [];
        used = 0;
      }
      current.add(entry);
      used += entry.height;
    }
    if (current.isNotEmpty) pages.add(current);

    return [for (final page in pages) _pageColumn(context, page)];
  }

  Widget _pageColumn(BuildContext context, List<_TocEntry> page) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final entry in page)
          SizedBox(
            height: entry.height,
            child: entry.isLabel
                ? _TestamentLabel(entry.label!)
                : _BookTile(
                    book: entry.book!,
                    onTap: () => _openChapters(context, entry.book!),
                  ),
          ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final pos = _position;
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _Header(onSearch: () => _openFind(context)),
            if (pos != null)
              _ContinueBanner(
                label:
                    '${widget.bible.bookAt(pos.bookIndex).name} ${pos.chapterNumber}',
                onTap: () => _continueReading(context, pos),
              ),
            Expanded(
              child: PagedView(
                buildPages: (w, h) => _buildPages(context, w, h),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// One row of the table of contents: either a testament heading or a book.
class _TocEntry {
  const _TocEntry.label(this.label)
      : book = null,
        height = 54;
  const _TocEntry.book(Book this.book)
      : label = null,
        height = 62;

  final String? label;
  final Book? book;
  final double height;

  bool get isLabel => label != null;
}

class _Header extends StatelessWidget {
  const _Header({required this.onSearch});

  final VoidCallback onSearch;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 14, 8, 12),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: Eink.black, width: 2)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Biblesprout',
                  style: TextStyle(
                    fontFamily: Eink.fontFamily,
                    fontSize: 30,
                    fontWeight: FontWeight.bold,
                    color: Eink.black,
                  ),
                ),
                SizedBox(height: 2),
                Text(
                  'Berean Standard Bible',
                  style: TextStyle(
                    fontFamily: Eink.fontFamily,
                    fontSize: 15,
                    color: Eink.rule,
                  ),
                ),
              ],
            ),
          ),
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onSearch,
            child: const SizedBox(
              width: 56,
              height: 56,
              child: Icon(Icons.search, size: 30, color: Eink.black),
            ),
          ),
        ],
      ),
    );
  }
}

class _ContinueBanner extends StatelessWidget {
  const _ContinueBanner({required this.label, required this.onTap});

  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: Eink.rule)),
        ),
        child: Row(
          children: [
            const Icon(Icons.bookmark_outline, size: 22, color: Eink.black),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Continue reading',
                    style: TextStyle(
                      fontFamily: Eink.fontFamily,
                      fontSize: 13,
                      color: Eink.rule,
                    ),
                  ),
                  Text(
                    label,
                    style: const TextStyle(
                      fontFamily: Eink.fontFamily,
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                      color: Eink.black,
                    ),
                  ),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, size: 26, color: Eink.black),
          ],
        ),
      ),
    );
  }
}

class _TestamentLabel extends StatelessWidget {
  const _TestamentLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      alignment: Alignment.bottomLeft,
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
      child: Text(
        text.toUpperCase(),
        style: const TextStyle(
          fontFamily: Eink.fontFamily,
          fontSize: 14,
          fontWeight: FontWeight.bold,
          letterSpacing: 2,
          color: Eink.rule,
        ),
      ),
    );
  }
}

class _BookTile extends StatelessWidget {
  const _BookTile({required this.book, required this.onTap});

  final Book book;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 20),
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: Color(0xFFDDDDDD))),
        ),
        child: Row(
          children: [
            Expanded(
              child: Text(
                book.name,
                style: const TextStyle(
                  fontFamily: Eink.fontFamily,
                  fontSize: 20,
                  color: Eink.black,
                ),
              ),
            ),
            Text(
              '${book.chapterCount} ch',
              style: const TextStyle(
                fontFamily: Eink.fontFamily,
                fontSize: 14,
                color: Eink.rule,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
