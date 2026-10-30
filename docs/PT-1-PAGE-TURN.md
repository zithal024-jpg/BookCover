# PT-1 — Page-turn engine: design notes and validation record

Owner request (2026-10-30): smoother, consistent page turns, plus an optional slight paper
page-turn with a toggle. Prompt: [review/PT-1-PAGE-TURN-PROMPT.md](review/PT-1-PAGE-TURN-PROMPT.md).

## How it works (step 2: engine + smoothed slide + toggle)

1. **One entry point.** Every user turn calls `EpubSession.turnPage(direction, source)`:
   edge taps, the Previous/Next page buttons, volume keys and keyboard keys. Swipes taken by the
   touch layer apply the same side effects through the same `noteUserTurn` (reading-time activity,
   `invalidateViewportAnchor()`, `pendingContentsIndex = null`, the typography-anchor guard, and
   closing the controls for button turns). Edge-tap `DiagnosticLog` lines are unchanged.
2. **Snapshot.** `PageTurnController` copies the reader's on-screen pixels with `PixelCopy`
   into one reusable mutable ARGB_8888 bitmap (PixelCopy cannot write HARDWARE bitmaps). If that
   fails or takes over 250 ms it tries `view.draw`; if both fail it uses Readium's own animated
   turn, so reading never breaks.
3. **Silent turn under the snapshot.** Once the overlay has reached the screen, Readium turns with
   `animated = false`. Readium announces the new locator only after a 100 ms debounce, so the
   controller watches the page instead: a different visible WebView (chapter boundary) or a
   page-width `scrollX` change, then one `postVisualStateCallback`. Bounded to 400 ms; the locator
   change (600 ms) is the fallback. Until the page is ready the uncovered area shows the theme's
   page colour, so a chapter boundary never flashes another page.
4. **Animation.** The snapshot slides away over 280 ms with a decelerating curve and a faint
   trailing-edge shadow. Identical for every source and across chapter boundaries. Drawing reads
   progress only in the draw phase (no recomposition per frame, no per-frame allocation).
5. **Swipes.** `PageTurnTouchLayer` wraps the `FragmentContainerView` and decides in
   `dispatchTouchEvent` (children cannot disable it). A move past 6 dp that is clearly horizontal
   (|dx| > 1.5·|dy|) is taken: the children get one `ACTION_CANCEL` before their own slops
   (Chromium 8 dp, Readium's pager/WebView 16 dp), so no native scroll, fling or page snap follows.
   The snapshot follows the finger. Release commits past 30% of the width or on a 400 dp/s fling
   over at least 25 dp (velocity from an Android `VelocityTracker`); a fling back cancels. Cancel
   slides the page back flat, then undoes the silent turn exactly once while the snapshot still
   covers the page, and only after the first turn has landed.
6. **Passes through untouched:** taps (Readium `onTap`: edge taps, controls toggle), vertical
   drags, long presses and drags while the text-selection toolbar is open, multi-finger gestures,
   and any swipe while the reading controls are open (their panels would be in the snapshot).
7. **Book edges.** Reflowable `goForward/goBackward` never return false at the end of the book,
   so the controller checks first: the visible WebView cannot scroll further that way and the
   current resource is the first/last reading-order item. Then the page rubber-bands, no turn.

## Fallbacks and limits

- **Remove animations** (`ValueAnimator.areAnimatorsEnabled()` false): turns are instant
  (`animated = false`), no overlay; swipes are still taken so nothing scrolls half-way.
- **Typography reflow or viewport restore in progress:** the previous behaviour (native swipes,
  Readium's animated turn).
- **Fixed-layout books stay entirely native** (no swipe takeover; taps, buttons and keys keep
  `animated = true`). No FXL fixture has been checked on the phone; revisit after that.
- **Fold, unfold or rotation mid-turn:** the overlay is dropped at once; BC-07 continuity restores
  the passage. A turn already issued under a finger is kept.
- A new turn during an animation jumps the old one to its end; a turn still waiting for its page
  is queued (one slot).

## Toggle

"Paper page turn" in More → Page turning (`testTag("paper-page-turn")`), saved as
`paperPageTurn` in the `"reader"` SharedPreferences. **Default: off** for the first build, so the
owner can compare both styles; off = smoothed slide. In step 2 the paper style still draws the
slide; step 3 adds the curl in `PageTurnOverlay.kt`.

## Validation record

- Step 2 (2026-10-30): `scripts\Build.ps1 -TaskId pt1` — see the PT-1 backlog row for the commit,
  unit-test counts and APK evidence. JVM tests: `PageTurnEngineTest` (thresholds, RTL mapping,
  vertical passthrough, instant path, undo-once, book-edge rubber band, typography fallback,
  abort) and `PageTurnPreferencesTest` (default off, round trip).
- **Not yet verified:** nothing has run on the phone. PixelCopy speed on the inner screen, the
  readiness signal, Chromium's real touch slop, RTL edge detection and the feel all need the
  isolated QA run (step 4) and the owner's own observation. Debuggable builds log one
  `PT-1 turn …` line per turn (snapshot time and method, readiness path, frames, slow frames).
