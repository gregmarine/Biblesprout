import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../models/bible.dart';
import '../reader/reader_screen.dart';
import '../services/reading_position.dart';
import '../theme/eink_theme.dart';
import '../widgets/paged_view.dart';

/// Chapter picker for a single book: a grid of chapter numbers that paginates
/// (no scrolling). Swipe or use the footer arrows to turn pages.
class ChaptersScreen extends StatelessWidget {
  const ChaptersScreen({
    super.key,
    required this.bible,
    required this.store,
    required this.book,
  });

  final Bible bible;
  final ReadingPositionStore store;
  final Book book;

  static const double _pad = 20;
  static const double _spacing = 14;
  static const double _targetCell = 100;

  void _openChapter(BuildContext context, int chapterNumber) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ReaderScreen(
          bible: bible,
          store: store,
          start: ChapterRef(book.index, chapterNumber),
        ),
      ),
    );
  }

  List<Widget> _buildPages(BuildContext context, double width, double height) {
    final available = width - _pad * 2;
    final cols =
        math.max(1, ((available + _spacing) / (_targetCell + _spacing)).floor());
    final cellSize = (available - (cols - 1) * _spacing) / cols;
    final rowHeight = cellSize + _spacing;
    // Conservative row count so a page column never exceeds the content area.
    final rowsPerPage = math.max(1, ((height - _pad) / rowHeight).floor());
    final perPage = cols * rowsPerPage;

    final pages = <Widget>[];
    for (var start = 0; start < book.chapterCount; start += perPage) {
      final end = math.min(start + perPage, book.chapterCount);
      pages.add(
        _gridPage(
          context,
          firstNumber: start + 1,
          count: end - start,
          cols: cols,
          cellSize: cellSize,
        ),
      );
    }
    return pages;
  }

  Widget _gridPage(
    BuildContext context, {
    required int firstNumber,
    required int count,
    required int cols,
    required double cellSize,
  }) {
    final rows = <Widget>[];
    for (var i = 0; i < count; i += cols) {
      final cells = <Widget>[];
      for (var c = 0; c < cols; c++) {
        final index = i + c;
        final number = firstNumber + index;
        cells.add(
          Padding(
            padding: EdgeInsets.only(right: c < cols - 1 ? _spacing : 0),
            child: SizedBox(
              width: cellSize,
              height: cellSize,
              child: index < count
                  ? _ChapterCell(
                      number: number,
                      onTap: () => _openChapter(context, number),
                    )
                  : null,
            ),
          ),
        );
      }
      rows.add(
        Padding(
          padding: const EdgeInsets.only(bottom: _spacing),
          child: Row(children: cells),
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(_pad, _pad, _pad, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: rows,
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
            _Header(
              title: book.name,
              onBack: () => Navigator.of(context).pop(),
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

class _Header extends StatelessWidget {
  const _Header({required this.title, required this.onBack});

  final String title;
  final VoidCallback onBack;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: Eink.black, width: 2)),
      ),
      child: Row(
        children: [
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onBack,
            child: const Padding(
              padding: EdgeInsets.all(4),
              child: Icon(Icons.arrow_back, size: 26, color: Eink.black),
            ),
          ),
          const SizedBox(width: 8),
          Text(
            title,
            style: const TextStyle(
              fontFamily: Eink.fontFamily,
              fontSize: 26,
              fontWeight: FontWeight.bold,
              color: Eink.black,
            ),
          ),
        ],
      ),
    );
  }
}

class _ChapterCell extends StatelessWidget {
  const _ChapterCell({required this.number, required this.onTap});

  final int number;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Container(
        alignment: Alignment.center,
        decoration: BoxDecoration(
          border: Border.all(color: Eink.black, width: 1.5),
          borderRadius: BorderRadius.circular(4),
        ),
        child: Text(
          '$number',
          style: const TextStyle(
            fontFamily: Eink.fontFamily,
            fontSize: 24,
            color: Eink.black,
          ),
        ),
      ),
    );
  }
}
