import 'package:flutter/material.dart';

/// Design constants and the high-contrast theme tuned for monochrome e-ink.
///
/// Everything here optimises for a reflective E Ink panel: pure black on pure
/// white, no anti-aliased grays for primary text, and no motion. Material's
/// ripples, highlights and page transitions are all disabled so the display
/// never tries to animate (which ghosts and blurs on EPD hardware).
class Eink {
  const Eink._();

  static const Color black = Color(0xFF000000);
  static const Color white = Color(0xFFFFFFFF);

  // A single mid-gray is permitted for hairline rules and secondary chrome
  // only — never for body text.
  static const Color rule = Color(0xFF555555);

  static const String fontFamily = 'NotoSerif';

  /// Reading typography, generous for a 10.3" 300dpi panel. Tunable live.
  static const double readingFontSize = 30;
  static const double readingLineHeight = 1.5;

  static ThemeData theme() {
    const scheme = ColorScheme.light(
      primary: black,
      onPrimary: white,
      surface: white,
      onSurface: black,
    );

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: white,
      fontFamily: fontFamily,
      splashFactory: NoSplash.splashFactory,
      highlightColor: Colors.transparent,
      splashColor: Colors.transparent,
      hoverColor: Colors.transparent,
      // Keep text input monochrome: black caret/handles, a faint gray
      // selection band instead of the default blue.
      textSelectionTheme: const TextSelectionThemeData(
        cursorColor: black,
        selectionColor: Color(0x33000000),
        selectionHandleColor: black,
      ),
      // No motion anywhere: every route swaps instantly.
      pageTransitionsTheme: const PageTransitionsTheme(
        builders: {
          TargetPlatform.android: _NoTransitionsBuilder(),
          TargetPlatform.iOS: _NoTransitionsBuilder(),
        },
      ),
    );
  }
}

class _NoTransitionsBuilder extends PageTransitionsBuilder {
  const _NoTransitionsBuilder();

  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) {
    return child;
  }
}
