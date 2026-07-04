import 'package:flutter/material.dart';

import 'data/app_services.dart';
import 'screens/library_screen.dart';
import 'theme/eink_theme.dart';

/// Root widget. Boots the databases and loads the saved reading position once
/// at startup, showing a minimal splash while loading, then hands off to the
/// library.
class BiblesproutApp extends StatefulWidget {
  const BiblesproutApp({super.key});

  @override
  State<BiblesproutApp> createState() => _BiblesproutAppState();
}

class _BiblesproutAppState extends State<BiblesproutApp> {
  late final Future<AppServices> _startup = AppServices.bootstrap();

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Biblesprout',
      debugShowCheckedModeBanner: false,
      theme: Eink.theme(),
      home: FutureBuilder<AppServices>(
        future: _startup,
        builder: (context, snapshot) {
          if (!snapshot.hasData) return const _Splash();
          final services = snapshot.data!;
          return LibraryScreen(
            bible: services.bible,
            bibleDb: services.bibleDb,
            store: services.positionStore,
            lastPosition: services.initialPosition,
          );
        },
      ),
    );
  }
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
