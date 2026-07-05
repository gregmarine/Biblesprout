import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show RenderParagraph;

import '../theme/eink_theme.dart';
import 'paginator.dart';
import 'passage_paginator.dart';

/// A paginated, e-ink reading surface for an arbitrary flowing document — an
/// ordered list of heading/text [PassageItem] blocks with a title bar.
///
/// Used by both the jump-to-passage view (scripture) and the commentary view
/// (Matthew Henry's notes). Gestures mirror the chapter reader: tap or swipe the
/// left/right thirds to turn pages; a center tap or the on-screen back button
/// returns to the previous screen. A black frame flashes every [_fullRefreshEvery]
/// turns to clear E Ink ghosting.
class FlowingDocument extends StatefulWidget {
  const FlowingDocument({
    super.key,
    required this.title,
    required this.blocks,
    this.onNotes,
    this.onVerseTap,
  });

  /// Shown in the top bar, e.g. "John 3:14–16, 18" or "MHCC · John 3".
  final String title;

  /// The document, already grouped into heading/text blocks by the caller.
  final List<PassageItem> blocks;

  /// When non-null, a "Notes" affordance in the top bar opens commentary for
  /// the document (used by the passage view; the commentary view leaves it
  /// null).
  final VoidCallback? onNotes;

  /// When non-null, tapping a verse number (one carrying a key) invokes this
  /// with that verse's key — used by the passage view for verse-anchored
  /// commentary. The commentary view leaves it null.
  final void Function(int verseKey)? onVerseTap;

  @override
  State<FlowingDocument> createState() => _FlowingDocumentState();
}

class _FlowingDocumentState extends State<FlowingDocument> {
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
  List<List<PassageItem>> _pages = const [];

  /// One stable key per text block, so a long-press can hit-test the pressed
  /// verse against that block's rendered paragraph.
  final Map<TextItem, GlobalKey> _textKeys = {};

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
  }

  void _ensurePaginated(double width, double height) {
    final key = '${width.toStringAsFixed(1)}x${height.toStringAsFixed(1)}'
        '/${_textScaler.scale(100).toStringAsFixed(1)}';
    if (key == _cacheKey) return;
    _pages = PassagePaginator.paginate(
      blocks: widget.blocks,
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
                        onNotes: widget.onNotes,
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
            children: [
              if (_pages.isNotEmpty)
                for (final item in _pages[_page]) _item(item),
            ],
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
        final key = _textKeys.putIfAbsent(text, GlobalKey.new);
        final rich = Text.rich(
          key: key,
          TextSpan(
            style: _bodyStyle,
            children: Paginator.renderSpans(text.atoms, _numberStyle),
          ),
          strutStyle: Paginator.strutFor(_bodyStyle),
          textAlign: TextAlign.left,
        );
        final onVerseTap = widget.onVerseTap;
        if (onVerseTap == null) return rich;
        // Long-press anywhere in a verse opens its commentary. The number is a
        // small target, so we hit-test the whole block instead.
        return GestureDetector(
          behavior: HitTestBehavior.translucent,
          onLongPressStart: (details) {
            final ro = key.currentContext?.findRenderObject();
            if (ro is! RenderParagraph) return;
            final offset = ro
                .getPositionForOffset(ro.globalToLocal(details.globalPosition))
                .offset;
            final verseKey = Paginator.verseKeyAtOffset(text.atoms, offset);
            if (verseKey != null) onVerseTap(verseKey);
          },
          child: rich,
        );
    }
  }
}

class _TopBar extends StatelessWidget {
  const _TopBar({required this.title, required this.onBack, this.onNotes});

  final String title;
  final VoidCallback onBack;

  /// When non-null, a "Notes" affordance opens commentary for the document.
  final VoidCallback? onNotes;

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
