import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

import 'app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Bibles read in portrait; lock it so the reader layout stays stable.
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);
  runApp(const BiblesproutApp());
}
