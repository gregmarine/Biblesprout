import 'package:flutter/material.dart';

import 'data/bible_repository.dart';
import 'screens/library_screen.dart';
import 'services/reading_position.dart';
import 'theme/eink_theme.dart';

/// Root widget. Loads the Bible and the saved reading position once at startup,
/// showing a minimal splash while parsing, then hands off to the library.
class BiblesproutApp extends StatefulWidget {
  const BiblesproutApp({super.key});

  @override
  State<BiblesproutApp> createState() => _BiblesproutAppState();
}

class _BiblesproutAppState extends State<BiblesproutApp> {
  final ReadingPositionStore _store = ReadingPositionStore();
  late final Future<_Startup> _startup = _load();

  Future<_Startup> _load() async {
    final repo = await BibleRepository.load();
    final position = await _store.load();
    return _Startup(repo, position);
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Biblesprout',
      debugShowCheckedModeBanner: false,
      theme: Eink.theme(),
      home: FutureBuilder<_Startup>(
        future: _startup,
        builder: (context, snapshot) {
          if (!snapshot.hasData) return const _Splash();
          final startup = snapshot.data!;
          return LibraryScreen(
            bible: startup.repo.bible,
            store: _store,
            lastPosition: startup.position,
          );
        },
      ),
    );
  }
}

class _Startup {
  const _Startup(this.repo, this.position);
  final BibleRepository repo;
  final ReadingPosition? position;
}

class _Splash extends StatelessWidget {
  const _Splash();

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(
        child: Text(
          'Biblesprout',
          style: TextStyle(
            fontFamily: Eink.fontFamily,
            fontSize: 28,
            fontWeight: FontWeight.bold,
            color: Eink.black,
          ),
        ),
      ),
    );
  }
}
