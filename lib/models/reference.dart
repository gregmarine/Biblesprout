/// Scripture reference model and the parser that turns human-typed references
/// such as `John 3:14-16,18` or `Gen 1:5-2:3` into queryable key ranges.
///
/// Every place in scripture is packed into a single **verse key**: an integer
/// `ordinal * 1_000_000 + chapter * 1_000 + verse` (canon ordinal 1..66). Keys
/// sort in canonical reading order, and — because a chapter's verses share the
/// same `ordinal*1e6 + chapter*1e3` base — any chapter or verse range is a
/// contiguous `BETWEEN` on this one column. The same integers are used by
/// cross-links in the global index, so a commentary and a bookmark point at
/// exactly the same address regardless of which database they live in.
library;

import '../data/canon.dart';

/// Packs/unpacks the canonical verse-key integer.
class VerseKey {
  VerseKey._();

  static const int bookFactor = 1000000;
  static const int chapterFactor = 1000;

  /// Highest verse sentinel used for "the rest of this chapter" bounds. Real
  /// verse numbers never approach it, so a whole-chapter range is simply
  /// `[base .. base + maxVerse]`.
  static const int maxVerse = 999;

  static int encode(int ordinal, int chapter, int verse) =>
      ordinal * bookFactor + chapter * chapterFactor + verse;

  static int ordinalOf(int key) => key ~/ bookFactor;
  static int chapterOf(int key) => (key % bookFactor) ~/ chapterFactor;
  static int verseOf(int key) => key % chapterFactor;

  /// Inclusive key bounds covering an entire chapter, verse number aside.
  static (int, int) chapterBounds(int ordinal, int chapter) => (
        encode(ordinal, chapter, 0),
        encode(ordinal, chapter, maxVerse),
      );
}

/// A single, contiguous span of verses expressed as inclusive key bounds.
/// A one-verse reference has `startKey == endKey`; a whole chapter spans the
/// full [VerseKey.maxVerse] band.
class VerseRange {
  const VerseRange(this.startKey, this.endKey)
      : assert(startKey <= endKey);

  final int startKey;
  final int endKey;

  factory VerseRange.verse(int ordinal, int chapter, int verse) {
    final k = VerseKey.encode(ordinal, chapter, verse);
    return VerseRange(k, k);
  }

  factory VerseRange.verses(
    int ordinal,
    int chapter,
    int firstVerse,
    int lastVerse,
  ) =>
      VerseRange(
        VerseKey.encode(ordinal, chapter, firstVerse),
        VerseKey.encode(ordinal, chapter, lastVerse),
      );

  /// A whole chapter (or a span of whole chapters, `first..last`).
  factory VerseRange.chapters(int ordinal, int firstChapter, int lastChapter) =>
      VerseRange(
        VerseKey.chapterBounds(ordinal, firstChapter).$1,
        VerseKey.chapterBounds(ordinal, lastChapter).$2,
      );

  bool contains(int key) => key >= startKey && key <= endKey;

  @override
  bool operator ==(Object other) =>
      other is VerseRange &&
      other.startKey == startKey &&
      other.endKey == endKey;

  @override
  int get hashCode => Object.hash(startKey, endKey);

  @override
  String toString() => 'VerseRange($startKey..$endKey)';
}

/// A parsed reference: an ordered set of [VerseRange]s that all belong to one
/// book. `John 3:14-16,18` becomes two ranges; `Genesis 1` becomes one
/// whole-chapter range.
class Passage {
  const Passage({
    required this.book,
    required this.ranges,
    this.rawText,
  });

  final CanonBook book;
  final List<VerseRange> ranges;

  /// The original typed text, kept for round-tripping/debugging.
  final String? rawText;

  int get startKey => ranges.first.startKey;
  int get endKey => ranges.last.endKey;

  /// A tidy canonical rendering, e.g. `John 3:14–16, 18`. Chapter prefixes are
  /// omitted once a chapter is already established, matching how these are
  /// written by hand.
  String format() {
    final parts = <String>[];
    int? shownChapter;
    for (final r in ranges) {
      final sc = VerseKey.chapterOf(r.startKey);
      final sv = VerseKey.verseOf(r.startKey);
      final ec = VerseKey.chapterOf(r.endKey);
      final ev = VerseKey.verseOf(r.endKey);

      // Whole-chapter (or whole-chapter span): verse band is the full 0..max.
      if (sv == 0 && ev == VerseKey.maxVerse) {
        parts.add(sc == ec ? '$sc' : '$sc–$ec');
        shownChapter = null;
        continue;
      }
      if (sc == ec) {
        final prefix = shownChapter == sc ? '' : '$sc:';
        parts.add(sv == ev ? '$prefix$sv' : '$prefix$sv–$ev');
        shownChapter = sc;
      } else {
        parts.add('$sc:$sv–$ec:$ev');
        shownChapter = ec;
      }
    }
    return '${book.name} ${parts.join(', ')}';
  }

  @override
  String toString() => format();
}

/// Turns free-typed references into [Passage]s. Tolerant of case, punctuation,
/// spacing, en-dashes and common book abbreviations (via [Canon]).
class ReferenceParser {
  ReferenceParser._();

  /// Splits a leading book name from a trailing numeric spec. The book part
  /// must end in a letter or period; the spec starts at the first digit, so
  /// numbered books ("1 Cor 13:4") and spaceless input ("Ps23") both work.
  static final RegExp _split = RegExp(r'^\s*(.*?[A-Za-z.])\s*([0-9].*)$');

  static final RegExp _crossChapterSpan =
      RegExp(r'^(\d+):(\d+)-(\d+):(\d+)$');

  /// Parses a single reference, or null if the book is unknown or the numeric
  /// spec is malformed. Whitespace and en-dashes are normalised first.
  static Passage? parse(String input) {
    final m = _split.firstMatch(input.trim());
    if (m == null) return null;

    final book = Canon.lookup(m.group(1)!);
    if (book == null) return null;

    final spec = m.group(2)!.replaceAll('–', '-').replaceAll(' ', '');
    final ranges = _parseSpec(book.ordinal, spec);
    if (ranges == null || ranges.isEmpty) return null;

    return Passage(book: book, ranges: ranges, rawText: input.trim());
  }

  static List<VerseRange>? _parseSpec(int ordinal, String spec) {
    // No colon anywhere → chapter-level ("3", "1-2", "1,3,5").
    if (!spec.contains(':')) {
      final ranges = <VerseRange>[];
      for (final seg in spec.split(',')) {
        if (seg.isEmpty) continue;
        final dash = seg.indexOf('-');
        if (dash < 0) {
          final c = int.tryParse(seg);
          if (c == null) return null;
          ranges.add(VerseRange.chapters(ordinal, c, c));
        } else {
          final a = int.tryParse(seg.substring(0, dash));
          final b = int.tryParse(seg.substring(dash + 1));
          if (a == null || b == null || b < a) return null;
          ranges.add(VerseRange.chapters(ordinal, a, b));
        }
      }
      return ranges.isEmpty ? null : ranges;
    }

    // Whole-spec cross-chapter verse span, e.g. "1:5-2:3".
    final span = _crossChapterSpan.firstMatch(spec);
    if (span != null) {
      final c1 = int.parse(span.group(1)!);
      final v1 = int.parse(span.group(2)!);
      final c2 = int.parse(span.group(3)!);
      final v2 = int.parse(span.group(4)!);
      final start = VerseKey.encode(ordinal, c1, v1);
      final end = VerseKey.encode(ordinal, c2, v2);
      if (end < start) return null;
      return [VerseRange(start, end)];
    }

    // Verse-level, comma-separated, carrying the chapter forward across
    // segments: "3:14-16,18" and "3:16,4:1" both work.
    final ranges = <VerseRange>[];
    int? chapter;
    for (final seg in spec.split(',')) {
      if (seg.isEmpty) continue;
      var vspec = seg;
      final colon = seg.indexOf(':');
      if (colon >= 0) {
        chapter = int.tryParse(seg.substring(0, colon));
        vspec = seg.substring(colon + 1);
      }
      if (chapter == null) return null; // a bare verse with no chapter context

      final dash = vspec.indexOf('-');
      if (dash < 0) {
        final v = int.tryParse(vspec);
        if (v == null) return null;
        ranges.add(VerseRange.verse(ordinal, chapter, v));
      } else {
        final a = int.tryParse(vspec.substring(0, dash));
        final b = int.tryParse(vspec.substring(dash + 1));
        if (a == null || b == null || b < a) return null;
        ranges.add(VerseRange.verses(ordinal, chapter, a, b));
      }
    }
    return ranges.isEmpty ? null : ranges;
  }
}
