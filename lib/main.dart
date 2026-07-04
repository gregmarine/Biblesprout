import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Full-screen "like paper": hide the Android status and navigation bars.
  // Sticky so they stay hidden (a swipe from the edge reveals them briefly);
  // on-screen back buttons handle navigation in their absence.
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  // Bibles read in portrait; lock it so the reader layout stays stable.
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);
  runApp(const BiblesproutApp());
}
