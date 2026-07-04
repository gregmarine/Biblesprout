# E-Ink Design Constraints

The rules every screen, dialog, button, and widget in Biblesprout must follow so
the app "feels like real paper" on a reflective E Ink panel (EPD). Primary target
is the **BOOX Go 6**; the same rules keep us portable to Supernote later.

An EPD is not an LCD. It has no backlight, a slow full refresh (~hundreds of ms
with a black flash), a faster but ghost-prone partial refresh, four "real" gray
levels in practice, and no usable frame rate. Design as if you were laying out
ink on paper that can be reprinted, not painting pixels on a screen.

Most of these rules are already enforced in code by `lib/theme/eink_theme.dart`
(the `Eink` class + `Eink.theme()`). This doc is the *why* and the checklist for
everything the theme can't enforce on its own — especially dialogs, buttons, and
any new widget.

---

## 1. Color: black on white, one gray

- **Body text is pure black `Eink.black` (`0xFF000000`) on pure white
  `Eink.white` (`0xFFFFFFFF`).** Never anti-aliased gray, never off-white.
- **One mid-gray is allowed: `Eink.rule` (`0xFF555555`)** — and only for hairline
  rules, secondary chrome, and de-emphasized metadata (page counts, "Contents"
  label). Never for anything the user needs to *read* as content.
- **No other colors.** No brand accent, no blue links, no colored states. Meaning
  is carried by weight, size, rules, and borders — not hue.
- **Disabled ≠ faint gray.** A disabled control that's just light-gray reads as an
  artifact on e-ink and ghosts. Prefer hiding the control, or use a clearly
  bordered "off" treatment. (The one existing exception — the `0xFFCCCCCC`
  disabled arrow in `paged_view.dart` — is borderline; don't propagate that
  pattern to new widgets. If we keep it, promote it to a named `Eink` constant.)

## 2. No motion, ever

- **No animations, transitions, ripples, or spinners.** Motion on an EPD ghosts
  and blurs. `Eink.theme()` already disables Material ripple/hover/highlight and
  replaces all route transitions with instant swaps (`_NoTransitionsBuilder`).
- **No route or dialog that animates in.** Screens and dialogs appear instantly.
- **Never use** `CircularProgressIndicator`, `LinearProgressIndicator`,
  animated switches, `AnimatedFoo`, `Hero`, implicit-animation widgets, or
  anything driven by a `Ticker`. For "working…" states, show static text
  ("Loading Genesis…"), not a spinner.
- **No continuously updating widgets** (clocks, live counters, streaming text).
  Each visible change is a screen redraw the panel has to pay for.

## 3. Refresh discipline

- **Redraw whole regions, not tiny areas repeatedly.** Frequent partial updates
  accumulate ghosting.
- **Full-refresh flash to clear ghosting.** The reader flashes a full black frame
  every `_fullRefreshEvery = 6` page turns, held ~90ms, then repaints
  (`reader_screen.dart`). Any other long-lived paging surface should adopt the
  same "flash every N turns" pattern rather than inventing its own cadence.
- **Prefer paging over scrolling.** Momentum scrolling is a smear of partial
  refreshes. Use discrete pages (`PagedView`, the reader) instead of
  `ListView`/`SingleChildScrollView` for content the user reads. Short,
  bounded lists (a chapter grid) are fine if they don't animate.

## 4. Typography

- **Serif for reading:** `Eink.fontFamily` = `NotoSerif`, bundled (no network
  fonts). Reading size `Eink.readingFontSize` (30) at line height
  `Eink.readingLineHeight` (1.5). Generous leading — cramped text mushes on EPD.
- **Left-aligned body text**, not justified (justification opens rivers that read
  worse without sub-pixel AA). Headings may be centered.
- **`font_scale = 0.85`:** the BOOX applies a system text scale. Any `TextPainter`
  used for layout math **must** be passed `MediaQuery.textScalerOf(context)`, or
  measured heights won't match rendered text. See `_textScaler` in the reader and
  the note in `CLAUDE.md`. Keep a small `_safetyPad` when paginating to absorb
  sub-pixel rounding.

## 5. Touch, targets, and gestures

- **Big, unambiguous hit targets.** The footer arrows are 72px wide
  (`paged_view.dart`); treat that as roughly the floor for a primary control. EPD
  users can't see hover/press feedback, so targets must be forgiving.
- **No feedback-dependent affordances.** Because there's no ripple/press state,
  make the tap *result* obvious (page changes, screen swaps). Don't rely on a
  pressed-state color.
- **Established reader gestures** (keep consistent app-wide): swipe left/right, or
  tap the left/right screen thirds, to turn pages; center third opens Contents;
  tapping the top bar opens Contents. New reading surfaces should reuse these,
  not introduce competing gestures.

## 6. Dialogs & overlays

E-ink has no cheap scrim or blur, so the LCD-style floating modal is wrong here.

- **Prefer a full-screen route or a hard-bordered panel** over a floating
  `AlertDialog` with a translucent barrier. If you must use a dialog, give it a
  solid white background and a `Eink.black` border — no shadow, no dim scrim, no
  rounded-blur.
- **No shadows / elevation.** Set `elevation: 0`; separate surfaces with a
  `BorderSide(color: Eink.rule)` (see `_TopBar` / `_BottomBar`), not a drop
  shadow.
- **Appear and dismiss instantly** (covered by the theme's no-transition rule).

## 7. Images & grayscale (future phases)

- Assume ~4 usable gray levels. Design icons/diagrams as line art or 1-bit where
  possible.
- Dither photographic content deliberately (don't ship raw grayscale JPEGs and
  hope). Revisit this doc before adding the first image-bearing feature.

---

## Checklist for any new screen / widget

- [ ] Only `Eink.black`, `Eink.white`, `Eink.rule` — no other colors?
- [ ] Zero animation/transition/spinner/ripple? No `Ticker`-driven widget?
- [ ] Surfaces separated by borders, not shadows (`elevation: 0`)?
- [ ] Reading content is paged, not momentum-scrolled?
- [ ] Any `TextPainter` layout math passes the device `TextScaler`?
- [ ] Touch targets large (~72px for primary actions) and result-obvious?
- [ ] Dialogs are full-screen/bordered panels, not floating scrimmed modals?
- [ ] Long-lived paging surfaces flash a full refresh every N turns?

When in doubt: **would this look right printed on paper, and can the panel draw it
in one clean refresh?** If not, redesign it.
