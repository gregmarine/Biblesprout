import 'package:flutter/material.dart';

import 'paginator.dart';

/// An item on a rendered passage page.
sealed class PassageItem {
  const PassageItem();
}

/// A book/chapter heading line inserted into the flow, e.g. "John 3".
class HeadingItem extends PassageItem {
  const HeadingItem(this.text);
  final String text;
}

/// A run of body atoms (verse numbers + words) rendered as one flowing block.
class TextItem extends PassageItem {
  const TextItem(this.atoms);
  final List<Atom> atoms;
}

/// Paginates a passage — an ordered list of heading/text [PassageItem] blocks
/// that may span chapters and books — into pages that each fit [pageHeight].
///
/// Text blocks flow and split across pages; a heading is kept with at least the
/// first line of the text that follows it, so it is never orphaned at the very
/// bottom of a page. Measurement reuses [Paginator], so line heights match the
/// reader exactly.
class PassagePaginator {
  const PassagePaginator._();

  static const double headingTopPad = 18;
  static const double headingBottomPad = 14;

  static List<List<PassageItem>> paginate({
    required List<PassageItem> blocks,
    required double width,
    required double pageHeight,
    required TextStyle bodyStyle,
    required TextStyle numberStyle,
    required TextStyle headingStyle,
    required TextScaler textScaler,
  }) {
    final pages = <List<PassageItem>>[];
    var current = <PassageItem>[];
    var remaining = pageHeight;

    void commit() {
      pages.add(current);
      current = <PassageItem>[];
      remaining = pageHeight;
    }

    // A single body line, used for the heading keep-with-next check and as the
    // per-block rendering reserve below.
    final lineHeight = Paginator.measureHeight(
      atoms: const [WordAtom('Ag')],
      start: 0,
      count: 1,
      bodyStyle: bodyStyle,
      numberStyle: numberStyle,
      width: width,
      textScaler: textScaler,
    );

    for (final block in blocks) {
      switch (block) {
        case HeadingItem heading:
          final hh = headingTopPad +
              _headingTextHeight(heading.text, headingStyle, width, textScaler) +
              headingBottomPad;
          if (current.isNotEmpty && remaining < hh + lineHeight) commit();
          current.add(heading);
          remaining -= hh;

        case TextItem text:
          final atoms = text.atoms;
          var idx = 0;
          while (idx < atoms.length) {
            // Each rendered text block can wrap to one line more than
            // TextPainter measures (RenderParagraph vs TextPainter diverge by up
            // to a line at the device's pixel ratio). Reserve a line per block
            // so the packed height stays an upper bound of what renders — this
            // is what keeps heading pages, which carry two text blocks, from
            // overflowing.
            final count = Paginator.fitCount(
              atoms: atoms,
              start: idx,
              maxHeight: remaining - lineHeight,
              bodyStyle: bodyStyle,
              numberStyle: numberStyle,
              width: width,
              textScaler: textScaler,
            );
            if (count == 0) {
              if (current.isEmpty) {
                // A full page can't fit even one atom; place one to progress.
                current.add(TextItem(atoms.sublist(idx, idx + 1)));
                idx += 1;
              }
              commit();
              continue;
            }
            final used = Paginator.measureHeight(
              atoms: atoms,
              start: idx,
              count: count,
              bodyStyle: bodyStyle,
              numberStyle: numberStyle,
              width: width,
              textScaler: textScaler,
            );
            current.add(TextItem(atoms.sublist(idx, idx + count)));
            idx += count;
            remaining -= used + lineHeight;
            if (idx < atoms.length) commit();
          }
      }
    }
    if (current.isNotEmpty) pages.add(current);
    return pages.isEmpty ? [const <PassageItem>[]] : pages;
  }

  static double _headingTextHeight(
    String text,
    TextStyle style,
    double width,
    TextScaler scaler,
  ) {
    final tp = TextPainter(
      text: TextSpan(text: text, style: style),
      textAlign: TextAlign.center,
      textScaler: scaler,
      textDirection: TextDirection.ltr,
    )..layout(maxWidth: width);
    final h = tp.height;
    tp.dispose();
    return h;
  }
}
