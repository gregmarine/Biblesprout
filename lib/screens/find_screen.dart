import 'package:flutter/material.dart';

import '../data/bible_database.dart';
import '../data/canon.dart';
import '../models/bible.dart';
import '../models/reference.dart';
import '../reader/reader_screen.dart';
import '../services/reading_position.dart';
import '../theme/eink_theme.dart';
import '../widgets/paged_view.dart';

/// One smart input for both jumping and searching.
///
/// On submit the text is first parsed as a scripture reference (`John 3:16`,
/// `Gen 1:5-10`, `Psalm 23`, abbreviations and all). If it resolves to real
/// verses, those verses are listed — a jump-to-passage. Otherwise the text runs
/// as a full-text search. Either way, results are tappable verse rows that open
/// the reader on that verse's page. Results paginate (no scrolling).
class FindScreen extends StatefulWidget {
  const FindScreen({
    super.key,
    required this.bible,
    required this.bibleDb,
    required this.store,
  });

  final Bible bible;
  final BibleDatabase bibleDb;
  final ReadingPositionStore store;

  @override
  State<FindScreen> createState() => _FindScreenState();
}

class _FindScreenState extends State<FindScreen> {
  final TextEditingController _controller = TextEditingController();

  bool _searched = false;
  bool _searching = false;
  String _submitted = '';

  /// Non-null when the query parsed as a reference (drives the results header).
  Passage? _passage;
  List<VerseHit> _results = const [];

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _run(String raw) async {
    final query = raw.trim();
    if (query.isEmpty) return;
    setState(() {
      _searched = true;
      _searching = true;
      _submitted = query;
    });

    Passage? passage = ReferenceParser.parse(query);
    List<VerseHit> results;
    if (passage != null) {
      results = await widget.bibleDb.versesForPassage(passage);
      if (results.isEmpty) {
        // Parsed but nothing there (e.g. John 99:1) — fall back to search.
        passage = null;
        results = await widget.bibleDb.search(query);
      }
    } else {
      results = await widget.bibleDb.search(query);
    }

    if (!mounted) return;
    setState(() {
      _passage = passage;
      _results = results;
      _searching = false;
    });
  }

  void _open(VerseHit hit) {
    final book = Canon.byUsfm(hit.usfm);
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ReaderScreen(
          bible: widget.bible,
          store: widget.store,
          start: ChapterRef(book.ordinal - 1, hit.chapter),
          startVerse: hit.verse,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _SearchBar(
              controller: _controller,
              onBack: () => Navigator.of(context).pop(),
              onSubmit: _run,
            ),
            Expanded(child: _buildBody()),
          ],
        ),
      ),
    );
  }

  Widget _buildBody() {
    if (_searching) return const _Centered('Searching…');
    if (!_searched) return const _Help();
    if (_results.isEmpty) {
      return _Centered('No results for “$_submitted”.');
    }
    // Fresh PagedView per query so its size-keyed page cache is rebuilt.
    return PagedView(
      key: ValueKey(_submitted),
      buildPages: _buildResultPages,
    );
  }

  List<Widget> _buildResultPages(double width, double height) {
    const headerHeight = 52.0;
    const rowHeight = 120.0;

    final entries = <_Entry>[
      _Entry(headerHeight, _ResultsHeader(_summary())),
      for (final hit in _results)
        _Entry(rowHeight, _ResultRow(hit: hit, onTap: () => _open(hit))),
    ];

    final pages = <List<_Entry>>[];
    var current = <_Entry>[];
    var used = 0.0;
    for (final e in entries) {
      if (current.isNotEmpty && used + e.height > height) {
        pages.add(current);
        current = [];
        used = 0;
      }
      current.add(e);
      used += e.height;
    }
    if (current.isNotEmpty) pages.add(current);

    return [
      for (final page in pages)
        Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            for (final e in page) SizedBox(height: e.height, child: e.widget),
          ],
        ),
    ];
  }

  String _summary() {
    if (_passage != null) return _passage!.format();
    final n = _results.length;
    return '$n ${n == 1 ? 'result' : 'results'} for “$_submitted”';
  }
}

class _Entry {
  const _Entry(this.height, this.widget);
  final double height;
  final Widget widget;
}

class _SearchBar extends StatelessWidget {
  const _SearchBar({
    required this.controller,
    required this.onBack,
    required this.onSubmit,
  });

  final TextEditingController controller;
  final VoidCallback onBack;
  final ValueChanged<String> onSubmit;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: Eink.black, width: 2)),
      ),
      child: Row(
        children: [
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onBack,
            child: const Padding(
              padding: EdgeInsets.all(8),
              child: Icon(Icons.arrow_back, size: 26, color: Eink.black),
            ),
          ),
          const SizedBox(width: 4),
          Expanded(
            child: TextField(
              controller: controller,
              autofocus: true,
              textInputAction: TextInputAction.search,
              onSubmitted: onSubmit,
              cursorColor: Eink.black,
              style: const TextStyle(
                fontFamily: Eink.fontFamily,
                fontSize: 22,
                color: Eink.black,
              ),
              decoration: const InputDecoration(
                isDense: true,
                border: InputBorder.none,
                hintText: 'Reference or word',
                hintStyle: TextStyle(
                  fontFamily: Eink.fontFamily,
                  fontSize: 22,
                  color: Eink.rule,
                ),
              ),
            ),
          ),
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => onSubmit(controller.text),
            child: const Padding(
              padding: EdgeInsets.all(8),
              child: Icon(Icons.search, size: 28, color: Eink.black),
            ),
          ),
        ],
      ),
    );
  }
}

class _Help extends StatelessWidget {
  const _Help();

  @override
  Widget build(BuildContext context) {
    return const Padding(
      padding: EdgeInsets.fromLTRB(28, 40, 28, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Jump to a passage',
            style: TextStyle(
              fontFamily: Eink.fontFamily,
              fontSize: 20,
              fontWeight: FontWeight.bold,
              color: Eink.black,
            ),
          ),
          SizedBox(height: 6),
          Text(
            'John 3:16   ·   Gen 1:5-10   ·   Psalm 23\nJohn 3:14-16,18   ·   1 Cor 13',
            style: TextStyle(
              fontFamily: Eink.fontFamily,
              fontSize: 17,
              height: 1.5,
              color: Eink.rule,
            ),
          ),
          SizedBox(height: 28),
          Text(
            'Search the text',
            style: TextStyle(
              fontFamily: Eink.fontFamily,
              fontSize: 20,
              fontWeight: FontWeight.bold,
              color: Eink.black,
            ),
          ),
          SizedBox(height: 6),
          Text(
            'shepherd   ·   love your enemies   ·   living water',
            style: TextStyle(
              fontFamily: Eink.fontFamily,
              fontSize: 17,
              height: 1.5,
              color: Eink.rule,
            ),
          ),
        ],
      ),
    );
  }
}

class _Centered extends StatelessWidget {
  const _Centered(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(28),
      child: Align(
        alignment: Alignment.topCenter,
        child: Padding(
          padding: const EdgeInsets.only(top: 24),
          child: Text(
            text,
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontFamily: Eink.fontFamily,
              fontSize: 18,
              color: Eink.rule,
            ),
          ),
        ),
      ),
    );
  }
}

class _ResultsHeader extends StatelessWidget {
  const _ResultsHeader(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      alignment: Alignment.centerLeft,
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 10),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: Eink.rule)),
      ),
      child: Text(
        text.toUpperCase(),
        style: const TextStyle(
          fontFamily: Eink.fontFamily,
          fontSize: 14,
          fontWeight: FontWeight.bold,
          letterSpacing: 1.5,
          color: Eink.rule,
        ),
      ),
    );
  }
}

class _ResultRow extends StatelessWidget {
  const _ResultRow({required this.hit, required this.onTap});

  final VerseHit hit;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        decoration: const BoxDecoration(
          border: Border(bottom: BorderSide(color: Color(0xFFDDDDDD))),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              hit.reference,
              style: const TextStyle(
                fontFamily: Eink.fontFamily,
                fontSize: 18,
                fontWeight: FontWeight.bold,
                color: Eink.black,
              ),
            ),
            const SizedBox(height: 4),
            Expanded(
              child: Text(
                hit.text,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontFamily: Eink.fontFamily,
                  fontSize: 17,
                  height: 1.35,
                  color: Eink.black,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
