import 'package:flutter/material.dart';

import '../data/bible_database.dart';
import '../data/canon.dart';
import '../reader/paginator.dart';
import '../reader/passage_paginator.dart';
import '../theme/eink_theme.dart';

/// A jump-to-passage view: the selected verses rendered as flowing, paginated
/// text — just like the chapter reader, not a list. A "John 3" heading sits at
/// the top, verse numbers are superscript, and if the passage crosses a chapter
/// or book boundary a fresh heading is inserted inline. Long passages paginate.
///
/// Gestures mirror the reader: tap or swipe the left/right thirds to turn pages;
/// the on-screen back button (or a center tap) returns to the search screen.
class PassageScreen extends StatefulWidget {
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
  State<PassageScreen> createState() => _PassageScreenState();
}

class _PassageScreenState extends State<PassageScreen> {
  static const int _fullRefreshEvery = 6;
  static const double _hPadding = 44;
  static const double _vPadding = 10;
  static const double _headerHeight = 56;
  static const double _footerHeight = 44;
  static const double _safetyPad = 48;

  int _page = 0;
  int _turnsSinceRefresh = 0;
  bool _flashing = false;

  String? _cacheKey;
  List<PassageItem> _blocks = const [];
  List<List<PassageItem>> _pages = const [];

  TextScaler _textScaler = TextScaler.noScaling;

  late final TextStyle _bodyStyle;
  late final TextStyle _numberStyle;
  late final TextStyle _headingStyle;

  @override
  void initState() {
    super.initState();
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
    _headingStyle = const TextStyle(
      fontFamily: family,
      fontSize: Eink.readingFontSize * 0.9,
      fontWeight: FontWeight.bold,
      letterSpacing: 1,
      height: 1.1,
      color: Eink.black,
    );
    _blocks = _buildBlocks();
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

    for (final v in widget.verses) {
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

  void _ensurePaginated(double width, double height) {
    final key = '${width.toStringAsFixed(1)}x${height.toStringAsFixed(1)}'
        '/${_textScaler.scale(100).toStringAsFixed(1)}';
    if (key == _cacheKey) return;
    _pages = PassagePaginator.paginate(
      blocks: _blocks,
      width: width,
      pageHeight: height,
      bodyStyle: _bodyStyle,
      numberStyle: _numberStyle,
      headingStyle: _headingStyle,
      textScaler: _textScaler,
    );
    _cacheKey = key;
    if (_page >= _pages.length) _page = _pages.length - 1;
    if (_page < 0) _page = 0;
  }

  void _turn(int delta) {
    final target = _page + delta;
    if (target < 0 || target >= _pages.length) return;
    _turnsSinceRefresh++;
    final flash = _turnsSinceRefresh >= _fullRefreshEvery;
    setState(() {
      _page = target;
      if (flash) {
        _flashing = true;
        _turnsSinceRefresh = 0;
      }
    });
    if (flash) {
      Future.delayed(const Duration(milliseconds: 90), () {
        if (mounted) setState(() => _flashing = false);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    _textScaler = MediaQuery.textScalerOf(context);
    return Scaffold(
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            final width = constraints.maxWidth - _hPadding * 2;
            final readingHeight = constraints.maxHeight -
                _headerHeight -
                _footerHeight -
                _vPadding * 2;
            _ensurePaginated(width, readingHeight - _safetyPad);

            return Stack(
              children: [
                Column(
                  children: [
                    SizedBox(
                      height: _headerHeight,
                      child: _TopBar(
                        title: widget.title,
                        onBack: () => Navigator.of(context).pop(),
                      ),
                    ),
                    SizedBox(
                      height: readingHeight + _vPadding * 2,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: _hPadding,
                          vertical: _vPadding,
                        ),
                        child: _buildGestureArea(width),
                      ),
                    ),
                    SizedBox(
                      height: _footerHeight,
                      child: _Footer(page: _page + 1, total: _pages.length),
                    ),
                  ],
                ),
                if (_flashing)
                  const Positioned.fill(child: ColoredBox(color: Eink.black)),
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
          _turn(-1);
        } else if (dx > third * 2) {
          _turn(1);
        } else {
          Navigator.of(context).pop();
        }
      },
      onHorizontalDragEnd: (details) {
        final v = details.primaryVelocity ?? 0;
        if (v < -120) {
          _turn(1);
        } else if (v > 120) {
          _turn(-1);
        }
      },
      child: SizedBox(
        width: double.infinity,
        child: Align(
          alignment: Alignment.topLeft,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            mainAxisSize: MainAxisSize.min,
            children: [for (final item in _pages[_page]) _item(item)],
          ),
        ),
      ),
    );
  }

  Widget _item(PassageItem item) {
    switch (item) {
      case HeadingItem heading:
        return Padding(
          padding: const EdgeInsets.only(
            top: PassagePaginator.headingTopPad,
            bottom: PassagePaginator.headingBottomPad,
          ),
          child: Text(
            heading.text,
            textAlign: TextAlign.center,
            style: _headingStyle,
          ),
        );
      case TextItem text:
        return Text.rich(
          TextSpan(
            style: _bodyStyle,
            children: Paginator.renderSpans(text.atoms, _numberStyle),
          ),
          strutStyle: Paginator.strutFor(_bodyStyle),
          textAlign: TextAlign.left,
        );
    }
  }
}

class _TopBar extends StatelessWidget {
  const _TopBar({required this.title, required this.onBack});

  final String title;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
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
          const SizedBox(width: 6),
          Expanded(
            child: Text(
              title,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                fontFamily: Eink.fontFamily,
                fontSize: 20,
                fontWeight: FontWeight.bold,
                color: Eink.black,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Footer extends StatelessWidget {
  const _Footer({required this.page, required this.total});

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
        total > 1 ? '$page / $total' : '',
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
