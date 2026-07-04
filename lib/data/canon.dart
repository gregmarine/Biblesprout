import '../models/bible.dart' show Testament;

/// The canonical 66-book table shared across every source and the global index.
///
/// A [CanonBook] pins three things that must agree everywhere: the USFM book
/// code (the cross-source key, e.g. `GEN`, `1CO`), the 1-based canonical
/// [ordinal] (used both for ordering and to pack a verse address into a single
/// sortable integer), and a set of [aliases] the reference parser accepts when
/// a user types a book by hand ("Ps", "1 Cor", "Song of Songs").
///
/// This table is deliberately translation-independent: individual source
/// databases may label a book differently ("Psalm" vs "Psalms"), but they all
/// map back to the same USFM code here.
class CanonBook {
  const CanonBook({
    required this.usfm,
    required this.ordinal,
    required this.name,
    required this.testament,
    this.aliases = const [],
  });

  /// USFM book code, the canonical cross-source key (e.g. `GEN`, `PSA`, `1CO`).
  final String usfm;

  /// 1-based position in canonical order (Genesis = 1, Revelation = 66).
  final int ordinal;

  /// Preferred display name (e.g. "Genesis", "Psalms", "Song of Solomon").
  final String name;

  final Testament testament;

  /// Extra human forms the parser accepts, in addition to [name] and [usfm].
  /// Case/space/punctuation are normalised on lookup, so "1co" covers
  /// "1 Co" and "1.Co"; only genuinely different spellings need listing.
  final List<String> aliases;
}

/// The 66-book Protestant canon in order. USFM codes follow the Paratext /
/// unfoldingWord standard so third-party commentary and cross-reference data
/// can be imported without a translation table.
class Canon {
  Canon._();

  static const List<CanonBook> books = [
    // --- Old Testament ---
    CanonBook(usfm: 'GEN', ordinal: 1, name: 'Genesis', testament: Testament.old, aliases: ['gn']),
    CanonBook(usfm: 'EXO', ordinal: 2, name: 'Exodus', testament: Testament.old, aliases: ['ex', 'exod']),
    CanonBook(usfm: 'LEV', ordinal: 3, name: 'Leviticus', testament: Testament.old, aliases: ['lv']),
    CanonBook(usfm: 'NUM', ordinal: 4, name: 'Numbers', testament: Testament.old, aliases: ['nm', 'nb']),
    CanonBook(usfm: 'DEU', ordinal: 5, name: 'Deuteronomy', testament: Testament.old, aliases: ['dt', 'deut']),
    CanonBook(usfm: 'JOS', ordinal: 6, name: 'Joshua', testament: Testament.old, aliases: ['jsh']),
    CanonBook(usfm: 'JDG', ordinal: 7, name: 'Judges', testament: Testament.old, aliases: ['jdgs']),
    CanonBook(usfm: 'RUT', ordinal: 8, name: 'Ruth', testament: Testament.old, aliases: ['rth']),
    CanonBook(usfm: '1SA', ordinal: 9, name: '1 Samuel', testament: Testament.old, aliases: ['1sam', '1sm']),
    CanonBook(usfm: '2SA', ordinal: 10, name: '2 Samuel', testament: Testament.old, aliases: ['2sam', '2sm']),
    CanonBook(usfm: '1KI', ordinal: 11, name: '1 Kings', testament: Testament.old, aliases: ['1kgs', '1kg']),
    CanonBook(usfm: '2KI', ordinal: 12, name: '2 Kings', testament: Testament.old, aliases: ['2kgs', '2kg']),
    CanonBook(usfm: '1CH', ordinal: 13, name: '1 Chronicles', testament: Testament.old, aliases: ['1chr', '1chron']),
    CanonBook(usfm: '2CH', ordinal: 14, name: '2 Chronicles', testament: Testament.old, aliases: ['2chr', '2chron']),
    CanonBook(usfm: 'EZR', ordinal: 15, name: 'Ezra', testament: Testament.old),
    CanonBook(usfm: 'NEH', ordinal: 16, name: 'Nehemiah', testament: Testament.old, aliases: ['ne']),
    CanonBook(usfm: 'EST', ordinal: 17, name: 'Esther', testament: Testament.old, aliases: ['esth']),
    CanonBook(usfm: 'JOB', ordinal: 18, name: 'Job', testament: Testament.old, aliases: ['jb']),
    CanonBook(usfm: 'PSA', ordinal: 19, name: 'Psalms', testament: Testament.old, aliases: ['ps', 'psalm', 'psm', 'pss']),
    CanonBook(usfm: 'PRO', ordinal: 20, name: 'Proverbs', testament: Testament.old, aliases: ['prov', 'prv']),
    CanonBook(usfm: 'ECC', ordinal: 21, name: 'Ecclesiastes', testament: Testament.old, aliases: ['eccl', 'qoh']),
    CanonBook(usfm: 'SNG', ordinal: 22, name: 'Song of Solomon', testament: Testament.old, aliases: ['song', 'songofsongs', 'sos', 'canticles', 'cant']),
    CanonBook(usfm: 'ISA', ordinal: 23, name: 'Isaiah', testament: Testament.old, aliases: ['is', 'isa']),
    CanonBook(usfm: 'JER', ordinal: 24, name: 'Jeremiah', testament: Testament.old, aliases: ['je', 'jer']),
    CanonBook(usfm: 'LAM', ordinal: 25, name: 'Lamentations', testament: Testament.old, aliases: ['la']),
    CanonBook(usfm: 'EZK', ordinal: 26, name: 'Ezekiel', testament: Testament.old, aliases: ['ez', 'ezek']),
    CanonBook(usfm: 'DAN', ordinal: 27, name: 'Daniel', testament: Testament.old, aliases: ['dn']),
    CanonBook(usfm: 'HOS', ordinal: 28, name: 'Hosea', testament: Testament.old, aliases: ['ho']),
    CanonBook(usfm: 'JOL', ordinal: 29, name: 'Joel', testament: Testament.old, aliases: ['jl']),
    CanonBook(usfm: 'AMO', ordinal: 30, name: 'Amos', testament: Testament.old, aliases: ['am']),
    CanonBook(usfm: 'OBA', ordinal: 31, name: 'Obadiah', testament: Testament.old, aliases: ['ob', 'obad']),
    CanonBook(usfm: 'JON', ordinal: 32, name: 'Jonah', testament: Testament.old, aliases: ['jnh']),
    CanonBook(usfm: 'MIC', ordinal: 33, name: 'Micah', testament: Testament.old, aliases: ['mc']),
    CanonBook(usfm: 'NAM', ordinal: 34, name: 'Nahum', testament: Testament.old, aliases: ['na', 'nah']),
    CanonBook(usfm: 'HAB', ordinal: 35, name: 'Habakkuk', testament: Testament.old, aliases: ['hb', 'hab']),
    CanonBook(usfm: 'ZEP', ordinal: 36, name: 'Zephaniah', testament: Testament.old, aliases: ['zph', 'zeph']),
    CanonBook(usfm: 'HAG', ordinal: 37, name: 'Haggai', testament: Testament.old, aliases: ['hg', 'hag']),
    CanonBook(usfm: 'ZEC', ordinal: 38, name: 'Zechariah', testament: Testament.old, aliases: ['zc', 'zech']),
    CanonBook(usfm: 'MAL', ordinal: 39, name: 'Malachi', testament: Testament.old, aliases: ['ml', 'mal']),
    // --- New Testament ---
    CanonBook(usfm: 'MAT', ordinal: 40, name: 'Matthew', testament: Testament.newT, aliases: ['mt', 'matt']),
    CanonBook(usfm: 'MRK', ordinal: 41, name: 'Mark', testament: Testament.newT, aliases: ['mk', 'mrk']),
    CanonBook(usfm: 'LUK', ordinal: 42, name: 'Luke', testament: Testament.newT, aliases: ['lk']),
    CanonBook(usfm: 'JHN', ordinal: 43, name: 'John', testament: Testament.newT, aliases: ['jn', 'jhn']),
    CanonBook(usfm: 'ACT', ordinal: 44, name: 'Acts', testament: Testament.newT, aliases: ['ac']),
    CanonBook(usfm: 'ROM', ordinal: 45, name: 'Romans', testament: Testament.newT, aliases: ['ro', 'rm']),
    CanonBook(usfm: '1CO', ordinal: 46, name: '1 Corinthians', testament: Testament.newT, aliases: ['1cor']),
    CanonBook(usfm: '2CO', ordinal: 47, name: '2 Corinthians', testament: Testament.newT, aliases: ['2cor']),
    CanonBook(usfm: 'GAL', ordinal: 48, name: 'Galatians', testament: Testament.newT, aliases: ['ga']),
    CanonBook(usfm: 'EPH', ordinal: 49, name: 'Ephesians', testament: Testament.newT, aliases: ['ep']),
    CanonBook(usfm: 'PHP', ordinal: 50, name: 'Philippians', testament: Testament.newT, aliases: ['php', 'phil', 'pp']),
    CanonBook(usfm: 'COL', ordinal: 51, name: 'Colossians', testament: Testament.newT, aliases: ['co']),
    CanonBook(usfm: '1TH', ordinal: 52, name: '1 Thessalonians', testament: Testament.newT, aliases: ['1thess', '1thes']),
    CanonBook(usfm: '2TH', ordinal: 53, name: '2 Thessalonians', testament: Testament.newT, aliases: ['2thess', '2thes']),
    CanonBook(usfm: '1TI', ordinal: 54, name: '1 Timothy', testament: Testament.newT, aliases: ['1tim']),
    CanonBook(usfm: '2TI', ordinal: 55, name: '2 Timothy', testament: Testament.newT, aliases: ['2tim']),
    CanonBook(usfm: 'TIT', ordinal: 56, name: 'Titus', testament: Testament.newT, aliases: ['ti']),
    CanonBook(usfm: 'PHM', ordinal: 57, name: 'Philemon', testament: Testament.newT, aliases: ['phm', 'phlm', 'philem']),
    CanonBook(usfm: 'HEB', ordinal: 58, name: 'Hebrews', testament: Testament.newT, aliases: ['he']),
    CanonBook(usfm: 'JAS', ordinal: 59, name: 'James', testament: Testament.newT, aliases: ['jm', 'jas']),
    CanonBook(usfm: '1PE', ordinal: 60, name: '1 Peter', testament: Testament.newT, aliases: ['1pet', '1pt']),
    CanonBook(usfm: '2PE', ordinal: 61, name: '2 Peter', testament: Testament.newT, aliases: ['2pet', '2pt']),
    CanonBook(usfm: '1JN', ordinal: 62, name: '1 John', testament: Testament.newT, aliases: ['1jn', '1jhn', '1jo']),
    CanonBook(usfm: '2JN', ordinal: 63, name: '2 John', testament: Testament.newT, aliases: ['2jn', '2jhn', '2jo']),
    CanonBook(usfm: '3JN', ordinal: 64, name: '3 John', testament: Testament.newT, aliases: ['3jn', '3jhn', '3jo']),
    CanonBook(usfm: 'JUD', ordinal: 65, name: 'Jude', testament: Testament.newT, aliases: ['jud', 'jd']),
    CanonBook(usfm: 'REV', ordinal: 66, name: 'Revelation', testament: Testament.newT, aliases: ['re', 'rev', 'apocalypse', 'apoc']),
  ];

  static final Map<String, CanonBook> _byUsfm = {
    for (final b in books) b.usfm: b,
  };

  /// Normalised alias → book, built once from names, USFM codes and [aliases].
  static final Map<String, CanonBook> _byAlias = _buildAliasIndex();

  static Map<String, CanonBook> _buildAliasIndex() {
    final map = <String, CanonBook>{};
    void add(String key, CanonBook b) {
      final n = _normalize(key);
      if (n.isNotEmpty) map[n] = b;
    }

    for (final b in books) {
      add(b.usfm, b);
      add(b.name, b);
      for (final a in b.aliases) {
        add(a, b);
      }
    }
    return map;
  }

  /// Lowercases and strips whitespace/punctuation so "1 Cor.", "1cor" and
  /// "I Corinthians" collapse toward a common key. Leading Roman numerals for
  /// the numbered books (I/II/III) are converted to digits first.
  static String _normalize(String raw) {
    var s = raw.trim().toLowerCase();
    s = s.replaceFirstMapped(
      RegExp(r'^(iii|ii|i)\s+'),
      (m) => switch (m.group(1)) { 'iii' => '3', 'ii' => '2', _ => '1' },
    );
    s = s.replaceFirstMapped(
      RegExp(r'^(1st|2nd|3rd|first|second|third)\s+'),
      (m) => switch (m.group(1)) {
        '2nd' || 'second' => '2',
        '3rd' || 'third' => '3',
        _ => '1',
      },
    );
    return s.replaceAll(RegExp(r'[\s.]+'), '');
  }

  static CanonBook byUsfm(String usfm) => _byUsfm[usfm.toUpperCase()]!;

  static CanonBook? tryUsfm(String usfm) => _byUsfm[usfm.toUpperCase()];

  static CanonBook byOrdinal(int ordinal) => books[ordinal - 1];

  /// Resolves a human-typed or source-file book name to its canon entry, or
  /// null if unrecognised. Handles case, punctuation and Roman numerals.
  static CanonBook? lookup(String name) => _byAlias[_normalize(name)];
}
