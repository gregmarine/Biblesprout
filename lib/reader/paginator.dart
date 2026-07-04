import 'package:flutter/material.dart';

import '../models/bible.dart';

/// The smallest unit the paginator moves around: either a raised verse number
/// or a single word of body text. Pages are always cut on atom boundaries, so
/// words are never split and no fragile character-offset math is needed.
sealed class Atom {
  const Atom();
}

class NumberAtom extends Atom {
  const NumberAtom(this.number);
  final int number;
}

class WordAtom extends Atom {
  const WordAtom(this.word);
  final String word;
}

/// Turns Bible text into screen-sized pages using [TextPainter] measurement.
///
/// The key trick for correctness: both measurement and rendering use the same
/// [StrutStyle] with `forceStrutHeight`, so every line is exactly one body
/// line-height tall regardless of the smaller verse-number runs. That makes
/// measured height and rendered height agree to the pixel, so a page that
/// measures as fitting always renders without overflow.
class Paginator {
  const Paginator._();

  /// Flattens a chapter into its atom stream (verse number, then its words).
  static List<Atom> atomsFor(Chapter chapter) {
    final atoms = <Atom>[];
    for (final verse in chapter.verses) {
      atoms.add(NumberAtom(verse.number));
      for (final word in verse.text.split(RegExp(r'\s+'))) {
        if (word.isNotEmpty) atoms.add(WordAtom(word));
      }
    }
    return atoms;
  }

  static StrutStyle strutFor(TextStyle bodyStyle) =>
      StrutStyle.fromTextStyle(bodyStyle, forceStrutHeight: true);

  /// Inline spans used only for measurement. The verse number is a small inline
  /// [TextSpan] of the same glyphs and size as the rendered raised number, so
  /// line-break positions match the rendered output.
  static List<InlineSpan> measureSpans(List<Atom> atoms, TextStyle numberStyle) {
    return _spans(atoms, (n) => TextSpan(text: '$n', style: numberStyle));
  }

  /// Inline spans used for rendering. The verse number is a top-aligned
  /// [WidgetSpan] so it sits raised like a superscript.
  static List<InlineSpan> renderSpans(List<Atom> atoms, TextStyle numberStyle) {
    return _spans(
      atoms,
      (n) => WidgetSpan(
        alignment: PlaceholderAlignment.top,
        child: Text('$n', style: numberStyle),
      ),
    );
  }

  static List<InlineSpan> _spans(
    List<Atom> atoms,
    InlineSpan Function(int number) numberSpan,
  ) {
    final spans = <InlineSpan>[];
    for (var i = 0; i < atoms.length; i++) {
      final atom = atoms[i];
      switch (atom) {
        case NumberAtom(:final number):
          if (i > 0) spans.add(const TextSpan(text: ' '));
          spans.add(numberSpan(number));
        case WordAtom(:final word):
          spans.add(TextSpan(text: i > 0 ? ' $word' : word));
      }
    }
    return spans;
  }

  /// Rendered height of [count] atoms starting at [start], measured exactly as
  /// they render (same strut + scaler) so callers can pack their own pages.
  static double measureHeight({
    required List<Atom> atoms,
    required int start,
    required int count,
    required TextStyle bodyStyle,
    required TextStyle numberStyle,
    required double width,
    required TextScaler textScaler,
  }) {
    final tp = TextPainter(
      text: TextSpan(
        style: bodyStyle,
        children: measureSpans(atoms.sublist(start, start + count), numberStyle),
      ),
      strutStyle: strutFor(bodyStyle),
      textScaler: textScaler,
      textDirection: TextDirection.ltr,
    )..layout(maxWidth: width);
    final h = tp.height;
    tp.dispose();
    return h;
  }

  /// The largest number of atoms from [start] whose height fits [maxHeight], or
  /// 0 if not even one atom fits (so a caller can move to a fresh page).
  static int fitCount({
    required List<Atom> atoms,
    required int start,
    required double maxHeight,
    required TextStyle bodyStyle,
    required TextStyle numberStyle,
    required double width,
    required TextScaler textScaler,
  }) {
    final remaining = atoms.length - start;
    if (remaining <= 0) return 0;
    double h(int c) => measureHeight(
          atoms: atoms,
          start: start,
          count: c,
          bodyStyle: bodyStyle,
          numberStyle: numberStyle,
          width: width,
          textScaler: textScaler,
        );
    if (h(1) > maxHeight) return 0;

    var lo = 1;
    var hi = 1;
    while (hi < remaining && h(hi) <= maxHeight) {
      lo = hi;
      hi *= 2;
    }
    if (hi > remaining) hi = remaining;

    var best = lo;
    var low = lo;
    var high = hi;
    while (low <= high) {
      final mid = (low + high) ~/ 2;
      if (h(mid) <= maxHeight) {
        best = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return best;
  }

  /// Splits [atoms] into pages that each fit within the given heights.
  ///
  /// [firstPageHeight] is usually smaller than [otherPageHeight] because the
  /// first page of a chapter also carries the book/chapter heading.
  static List<List<Atom>> paginate({
    required List<Atom> atoms,
    required TextStyle bodyStyle,
    required TextStyle numberStyle,
    required double width,
    required double firstPageHeight,
    required double otherPageHeight,
    required TextScaler textScaler,
  }) {
    final strut = strutFor(bodyStyle);
    final pages = <List<Atom>>[];
    var start = 0;

    double heightOf(int count) {
      final tp = TextPainter(
        text: TextSpan(
          style: bodyStyle,
          children: measureSpans(
            atoms.sublist(start, start + count),
            numberStyle,
          ),
        ),
        strutStyle: strut,
        textScaler: textScaler,
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: width);
      final h = tp.height;
      tp.dispose();
      return h;
    }

    while (start < atoms.length) {
      final maxHeight = pages.isEmpty ? firstPageHeight : otherPageHeight;
      final remaining = atoms.length - start;

      // Exponentially grow a lower bound that still fits, then binary search
      // up to the first count that overflows.
      var lo = 1; // always place at least one atom to guarantee progress
      var hi = 1;
      while (hi < remaining && heightOf(hi) <= maxHeight) {
        lo = hi;
        hi = hi * 2;
      }
      if (hi > remaining) hi = remaining;

      // Invariant: heightOf(lo) fits (or lo == 1). Find the largest fitting.
      var best = lo;
      var low = lo;
      var high = hi;
      while (low <= high) {
        final mid = (low + high) ~/ 2;
        if (heightOf(mid) <= maxHeight) {
          best = mid;
          low = mid + 1;
        } else {
          high = mid - 1;
        }
      }

      pages.add(atoms.sublist(start, start + best));
      start += best;
    }

    return pages;
  }
}
