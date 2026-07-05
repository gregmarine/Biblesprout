import 'package:flutter/material.dart';

import '../data/commentary_database.dart';
import '../models/bible.dart';
import '../models/reference.dart';
import '../screens/commentary_screen.dart';
import '../services/reading_position.dart';
import '../theme/eink_theme.dart';
import 'paginator.dart';

/// Full-screen paginated reader. Each chapter starts on a fresh page; swiping
/// (or tapping the left/right thirds) turns pages and flows across chapter and
/// book boundaries. Every [_fullRefreshEvery] turns a black flash forces the
/// E Ink panel to do a full refresh, clearing accumulated ghosting.
class ReaderScreen extends StatefulWidget {
  const ReaderScreen({
    super.key,
    required this.bible,
    required this.store,
    required this.start,
    this.commentaries = const [],
    this.startPage = 0,
    this.startVerse,
  });

  final Bible bible;
  final ReadingPositionStore store;

  /// The installed commentaries. When non-empty, the reader offers a "Notes"
  /// affordance opening the chapter's commentary; with more than one, tapping
  /// it first shows a picker.
  final List<CommentaryDatabase> commentaries;

  final ChapterRef start;
  final int startPage;

  /// If set, open on whichever page of the start chapter contains this verse
  /// (used by search / jump-to-passage). Takes precedence over [startPage].
  final int? startVerse;

  @override
  State<ReaderScreen> createState() => _ReaderScreenState();
}

class _ReaderScreenState extends State<ReaderScreen> {
  /// Full-screen refresh cadence, in page turns.
  static const int _fullRefreshEvery = 6;
  static const double _hPadding = 44;
  static const double _vPadding = 10;
  static const double _headerHeight = 56;
  static const double _footerHeight = 44;

  /// A little slack kept at the bottom of every page so sub-pixel rounding of
  /// line heights can never spill past the reading area into an overflow.
  static const double _safetyPad = 48;

  late ChapterRef _ref;
  int _page = 0;

  /// Set when navigating backwards into a chapter: after (re)pagination we jump
  /// to that chapter's last page.
  bool _pendingLastPage = false;

  /// Set when opening via search/jump: after pagination we jump to the page
  /// containing this verse number.
  int? _pendingVerse;

  int _turnsSinceRefresh = 0;
  bool _flashing = false;

  // Pagination cache, valid for one (chapter, width, height, scale) combination.
  String? _cacheKey;
  List<Atom> _atoms = const [];
  List<List<Atom>> _pages = const [];

  // The device's text scale (BOOX applies 0.85). Measurement must match the
  // rendered text, so this is fed into every TextPainter below.
  TextScaler _textScaler = TextScaler.noScaling;

  late final TextStyle _bodyStyle;
  late final TextStyle _numberStyle;
  late final TextStyle _bookTitleStyle;
  late final TextStyle _chapterNumberStyle;

  @override
  void initState() {
    super.initState();
    _ref = widget.start;
    _page = widget.startPage;
    _pendingVerse = widget.startVerse;

    const family = Eink.fontFamily;
    _bodyStyle = const TextStyle(
      fontFamily: family,
      fontSize: Eink.readingFontSize,
      height: Eink.readingLineHeight,
      color: Eink.black,
    );
    _numberStyle = const TextStyle(
      fontFamily: family,
      fontSize: Eink.readingFontSize * 0.62,
      fontWeight: FontWeight.bold,
      height: 1.0,
      color: Eink.black,
    );
    _bookTitleStyle = const TextStyle(
      fontFamily: family,
      fontSize: Eink.readingFontSize * 0.85,
      fontWeight: FontWeight.bold,
      letterSpacing: 2,
      color: Eink.black,
    );
    _chapterNumberStyle = const TextStyle(
      fontFamily: family,
      fontSize: Eink.readingFontSize * 2.1,
      fontWeight: FontWeight.bold,
      height: 1.0,
      color: Eink.black,
    );

    // Record the opened location immediately so "Continue reading" works even
    // if the reader is closed without turning a page.
    _persist();
  }

  Book get _book => widget.bible.bookAt(_ref.bookIndex);
  Chapter get _chapter =>
      widget.bible.chapter(_ref.bookIndex, _ref.chapterNumber);

  // --- Pagination -----------------------------------------------------------

  double _headingHeight(double width) {
    double lineHeight(String text, TextStyle style) {
      final tp = TextPainter(
        text: TextSpan(text: text, style: style),
        textAlign: TextAlign.center,
        textScaler: _textScaler,
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: width);
      final h = tp.height;
      tp.dispose();
      return h;
    }

    return lineHeight(_book.name.toUpperCase(), _bookTitleStyle) +
        _headingGap1 +
        lineHeight('${_chapter.number}', _chapterNumberStyle) +
        _headingGap2;
  }

  static const double _headingGap1 = 4;
  static const double _headingGap2 = 22;

  void _ensurePaginated(double width, double height) {
    final key = '${_ref.bookIndex}.${_ref.chapterNumber}'
        '@${width.toStringAsFixed(1)}x${height.toStringAsFixed(1)}'
        '/${_textScaler.scale(100).toStringAsFixed(1)}';
    if (key == _cacheKey) return;

    final headingHeight = _headingHeight(width);
    _atoms = Paginator.atomsFor(_chapter);
    _pages = Paginator.paginate(
      atoms: _atoms,
      bodyStyle: _bodyStyle,
      numberStyle: _numberStyle,
      width: width,
      firstPageHeight: height - headingHeight - _safetyPad,
      otherPageHeight: height - _safetyPad,
      textScaler: _textScaler,
    );
    _cacheKey = key;

    if (_pendingLastPage) {
      _page = _pages.length - 1;
      _pendingLastPage = false;
    }
    if (_pendingVerse != null) {
      final target = _pendingVerse!;
      for (var i = 0; i < _pages.length; i++) {
        if (_pages[i]
            .any((a) => a is NumberAtom && a.number == target)) {
          _page = i;
          break;
        }
      }
      _pendingVerse = null;
    }
    if (_page >= _pages.length) _page = _pages.length - 1;
    if (_page < 0) _page = 0;
  }

  // --- Navigation -----------------------------------------------------------

  void _turnTo(VoidCallback change) {
    _turnsSinceRefresh++;
    final flash = _turnsSinceRefresh >= _fullRefreshEvery;
    setState(() {
      change();
      if (flash) {
        _flashing = true;
        _turnsSinceRefresh = 0;
      }
    });
    _persist();
    if (flash) {
      // Hold the black frame briefly so the EPD registers a full refresh.
      Future.delayed(const Duration(milliseconds: 90), () {
        if (mounted) setState(() => _flashing = false);
      });
    }
  }

  void _nextPage() {
    if (_page < _pages.length - 1) {
      _turnTo(() => _page++);
      return;
    }
    final next = widget.bible.next(_ref);
    if (next == null) return; // end of Revelation
    _turnTo(() {
      _ref = next;
      _page = 0;
      _cacheKey = null;
    });
  }

  void _prevPage() {
    if (_page > 0) {
      _turnTo(() => _page--);
      return;
    }
    final prev = widget.bible.previous(_ref);
    if (prev == null) return; // start of Genesis
    _turnTo(() {
      _ref = prev;
      _pendingLastPage = true;
      _cacheKey = null;
    });
  }

  void _persist() {
    widget.store.save(
      ReadingPosition(
        bookIndex: _ref.bookIndex,
        chapterNumber: _ref.chapterNumber,
        page: _page,
      ),
    );
  }

  void _openToc() => Navigator.of(context).popUntil((r) => r.isFirst);

  /// Opens a commentary for the chapter currently in view. With more than one
  /// installed, first shows a picker; with one, opens it directly.
  Future<void> _openCommentary() async {
    final available = widget.commentaries;
    if (available.isEmpty) return;
    final db = available.length == 1
        ? available.first
        : await _pickCommentary(available);
    if (db == null || !mounted) return;

    final ordinal = _ref.bookIndex + 1;
    final (start, end) = VerseKey.chapterBounds(ordinal, _ref.chapterNumber);
    final entries = await db.entriesForRange(start, end);
    if (!mounted) return;
    final abbr = db.metadata['abbreviation'] ?? 'Notes';
    await Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => CommentaryScreen(
          title: '$abbr · ${_book.name} ${_chapter.number}',
          entries: entries,
        ),
      ),
    );
  }

  /// Presents the installed commentaries as a full-refresh list dialog and
  /// returns the chosen one (null if dismissed).
  Future<CommentaryDatabase?> _pickCommentary(
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

  // --- Build ----------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    _textScaler = MediaQuery.textScalerOf(context);
    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final readingWidth = constraints.maxWidth - _hPadding * 2;
            final readingHeight = constraints.maxHeight -
                _headerHeight -
                _footerHeight -
                _vPadding * 2;
            // Paginate synchronously here so the page and footer built below
            // in this same pass reflect the real page list.
            _ensurePaginated(readingWidth, readingHeight);

            return Stack(
              children: [
                Column(
                  children: [
                    SizedBox(
                      height: _headerHeight,
                      child: _TopBar(
                        title: '${_book.name} ${_chapter.number}',
                        onBack: () => Navigator.of(context).pop(),
                        onContents: _openToc,
                        onNotes: widget.commentaries.isNotEmpty
                            ? _openCommentary
                            : null,
                      ),
                    ),
                    SizedBox(
                      height: readingHeight + _vPadding * 2,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: _hPadding,
                          vertical: _vPadding,
                        ),
                        child: _buildGestureArea(readingWidth),
                      ),
                    ),
                    SizedBox(
                      height: _footerHeight,
                      child: _BottomBar(page: _page + 1, total: _pages.length),
                    ),
                  ],
                ),
                if (_flashing)
                  const Positioned.fill(
                    child: ColoredBox(color: Eink.black),
                  ),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _buildGestureArea(double width) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapUp: (details) {
        final third = width / 3;
        final dx = details.localPosition.dx;
        if (dx < third) {
          _prevPage();
        } else if (dx > third * 2) {
          _nextPage();
        } else {
          _openToc();
        }
      },
      onHorizontalDragEnd: (details) {
        final v = details.primaryVelocity ?? 0;
        if (v < -120) {
          _nextPage();
        } else if (v > 120) {
          _prevPage();
        }
      },
      child: _buildPage(),
    );
  }

  Widget _buildPage() {
    final isFirstPage = _page == 0;
    final text = Text.rich(
      TextSpan(
        style: _bodyStyle,
        children: Paginator.renderSpans(_pages[_page], _numberStyle),
      ),
      strutStyle: Paginator.strutFor(_bodyStyle),
      textAlign: TextAlign.left,
    );

    if (!isFirstPage) {
      return SizedBox(
        width: double.infinity,
        child: Align(alignment: Alignment.topLeft, child: text),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          _book.name.toUpperCase(),
          textAlign: TextAlign.center,
          style: _bookTitleStyle,
        ),
        const SizedBox(height: _headingGap1),
        Text(
          '${_chapter.number}',
          textAlign: TextAlign.center,
          style: _chapterNumberStyle,
        ),
        const SizedBox(height: _headingGap2),
        Align(alignment: Alignment.topLeft, child: text),
      ],
    );
  }
}

class _TopBar extends StatelessWidget {
  const _TopBar({
    required this.title,
    required this.onBack,
    required this.onContents,
    this.onNotes,
  });

  final String title;
  final VoidCallback onBack;
  final VoidCallback onContents;

  /// When non-null, a "Notes" affordance opens the chapter's commentary.
  final VoidCallback? onNotes;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: Eink.rule)),
      ),
      child: Row(
        children: [
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onBack,
            child: const Padding(
              padding: EdgeInsets.all(8),
              child: Icon(Icons.arrow_back, size: 24, color: Eink.black),
            ),
          ),
          const SizedBox(width: 6),
          Expanded(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: onContents,
              child: Text(
                title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontFamily: Eink.fontFamily,
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                  color: Eink.black,
                ),
              ),
            ),
          ),
          if (onNotes != null)
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: onNotes,
              child: const Padding(
                padding: EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                child: Text(
                  'Notes',
                  style: TextStyle(
                    fontFamily: Eink.fontFamily,
                    fontSize: 14,
                    color: Eink.rule,
                  ),
                ),
              ),
            ),
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onContents,
            child: const Padding(
              padding: EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              child: Text(
                'Contents',
                style: TextStyle(
                  fontFamily: Eink.fontFamily,
                  fontSize: 14,
                  color: Eink.rule,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _BottomBar extends StatelessWidget {
  const _BottomBar({required this.page, required this.total});

  final int page;
  final int total;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(vertical: 8),
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: Eink.rule)),
      ),
      child: Text(
        '$page / $total',
        textAlign: TextAlign.center,
        style: const TextStyle(
          fontFamily: Eink.fontFamily,
          fontSize: 13,
          color: Eink.rule,
        ),
      ),
    );
  }
}
