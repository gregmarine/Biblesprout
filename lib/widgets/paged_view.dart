import 'package:flutter/material.dart';

import '../theme/eink_theme.dart';

/// Builds the list of full-screen pages for the available [width] and [height]
/// of the content area (the footer is already excluded).
typedef PageBuilder = List<Widget> Function(double width, double height);

/// A non-scrolling, page-flipping container for e-ink.
///
/// Turns pages with a horizontal swipe — recognised by either drag distance or
/// fling velocity, so slow swipes register too — or the footer arrows. Page
/// turns swap instantly (no refresh flash). Taps inside a page (e.g. selecting
/// a book or chapter) still pass through to their targets.
class PagedView extends StatefulWidget {
  const PagedView({super.key, required this.buildPages});

  final PageBuilder buildPages;

  @override
  State<PagedView> createState() => _PagedViewState();
}

class _PagedViewState extends State<PagedView> {
  static const double _footerHeight = 44;
  static const double _swipeDistance = 40; // logical px of travel to turn
  static const double _swipeVelocity = 250; // or a faster fling

  int _page = 0;
  double _dragDx = 0;

  String? _cacheKey;
  List<Widget> _pages = const [];

  void _ensurePages(double width, double height) {
    final key = '${width.toStringAsFixed(1)}x${height.toStringAsFixed(1)}';
    if (key == _cacheKey) return;
    _pages = widget.buildPages(width, height);
    _cacheKey = key;
    if (_page >= _pages.length) _page = _pages.length - 1;
    if (_page < 0) _page = 0;
  }

  void _turnTo(int target) {
    if (target < 0 || target >= _pages.length || target == _page) return;
    setState(() => _page = target);
  }

  void _onDragEnd(DragEndDetails details) {
    final v = details.primaryVelocity ?? 0;
    if (_dragDx < -_swipeDistance || v < -_swipeVelocity) {
      _turnTo(_page + 1);
    } else if (_dragDx > _swipeDistance || v > _swipeVelocity) {
      _turnTo(_page - 1);
    }
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final contentHeight = constraints.maxHeight - _footerHeight;
        _ensurePages(constraints.maxWidth, contentHeight);
        if (_pages.isEmpty) return const SizedBox.shrink();

        return Column(
          children: [
            SizedBox(
              height: contentHeight,
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onHorizontalDragStart: (_) => _dragDx = 0,
                onHorizontalDragUpdate: (d) => _dragDx += d.delta.dx,
                onHorizontalDragEnd: _onDragEnd,
                child: _pages[_page],
              ),
            ),
            SizedBox(
              height: _footerHeight,
              child: _Footer(
                page: _page + 1,
                total: _pages.length,
                onPrev: _page > 0 ? () => _turnTo(_page - 1) : null,
                onNext:
                    _page < _pages.length - 1 ? () => _turnTo(_page + 1) : null,
              ),
            ),
          ],
        );
      },
    );
  }
}

class _Footer extends StatelessWidget {
  const _Footer({
    required this.page,
    required this.total,
    required this.onPrev,
    required this.onNext,
  });

  final int page;
  final int total;
  final VoidCallback? onPrev;
  final VoidCallback? onNext;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: Eink.rule)),
      ),
      child: Row(
        children: [
          _ArrowButton(icon: Icons.chevron_left, onTap: onPrev),
          Expanded(
            child: Text(
              '$page / $total',
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontFamily: Eink.fontFamily,
                fontSize: 13,
                color: Eink.rule,
              ),
            ),
          ),
          _ArrowButton(icon: Icons.chevron_right, onTap: onNext),
        ],
      ),
    );
  }
}

class _ArrowButton extends StatelessWidget {
  const _ArrowButton({required this.icon, required this.onTap});

  final IconData icon;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: SizedBox(
        width: 72,
        height: double.infinity,
        child: Icon(
          icon,
          size: 28,
          color: onTap == null ? const Color(0xFFCCCCCC) : Eink.black,
        ),
      ),
    );
  }
}
