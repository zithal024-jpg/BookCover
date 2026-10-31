# PT-1 — Paper-like page turns: implementation prompt

Drafted 2026-10-30 from the owner's request: "Page swipe animation isn't particularly
good; it should emulate a slight actual paper page-turn animation and feel. Add a toggle
in the control panel. Target smoothness and consistency in the page-turn experience.
The basic page turn can also use smoothing, so target a minor improvement in base
page-read behaviour."

Everything below the line is the prompt to hand to the implementing workflow. The
findings in "What we know" were read from the code and from the Readium 3.4.0 library
on this PC; nothing has been tried on the phone yet.

---

## Goal

Make every page turn in the BookCover reader feel like one consistent, smooth motion,
and offer an optional slight paper page-turn (a gentle curl from the edge, with a soft
shadow) that follows the finger during a swipe. Keep the existing reader behaviour,
saved positions, taps, keys and safety rules intact.

## What we know (verified by reading code, not on the phone)

1. **Three different motions today.** Within a chapter, a swipe is the WebView's own
   horizontal scroll, snapped to a page by Readium's script. Across a chapter boundary
   the swipe is handed to a ViewPager (`R2ViewPager`) with its own physics. Taps, the
   Previous/Next buttons, volume keys and keyboard call `goForward/goBackward(animated =
   true)`, which runs a 300 ms ease-in-out scroll in Readium's script. The owner's
   "inconsistent" feel is most likely these three motions disagreeing.
2. **The app can own the swipe.** Readium's injected script reports the first finger
   movement over 6 px to `InputListener.onDrag(DragEvent.Type.Start)`, then `Move` and
   `End`. If the listener returns `true`, the script calls `preventDefault()` and the
   WebView does **not** scroll, so the ViewPager never takes over either. The app's
   current listener in `EpubSession.kt` (around line 180) deliberately returns `false`
   ("native Readium remains the sole swipe handler"). `DragEvent` carries start,
   current and offset points in device pixels. Completed taps still arrive separately
   through `onTap`, so edge taps and the controls toggle are unaffected.
3. **Programmatic turns can be silent.** `fragment.goForward(animated = false)` and
   `fragment.go(locator, animated = false)` already exist and are used for restores.
   `goForward` returns `false` at the end of the book.
4. **Settings plumbing exists.** `EpubSession` keeps reader settings in the `"reader"`
   SharedPreferences (`volumeKeys`, `edgeTaps`, `theme`). The controls sheet in
   `EpubReaderScreen.kt` has a "Page turning" `SettingsSection` (around line 374) with
   the edge-tap and volume-key switches, using `SettingsSwitchRow` / the toggleable Row
   pattern from `ui/SettingsRows.kt`.
5. **Tests.** JVM tests live in `app/src/test/…/reader/`. On-phone tests use the isolated
   QA app via `ReaderTestActivity` and `NativeReaderTest`; `ReadingLayoutTest` needs the
   phone unfolded. Device tests run **only** through `scripts\Test-Device.ps1`.
6. The reader never uses Readium scroll mode (`scroll = false`), always one column.
   Fixed-layout books use a different page fragment (`R2FXLPageFragment`) whose swipe
   is not the reflowable script's drag path.

## Design to implement

Build one app-owned **page-turn engine** that handles all user-initiated turns, with two
visual styles chosen by a toggle:

- **Slide** (default when the paper toggle is off): the "smoothed basic turn". The
  outgoing page slides sideways with a consistent duration and easing (about 260 to 300
  ms, decelerating), with a faint edge shadow. Identical motion for swipe, tap, button,
  volume key and keyboard, and identical within a chapter and across chapter boundaries.
- **Paper** (toggle on): a *slight* paper curl. The outgoing page peels from the leading
  edge with a small cylindrical fold (radius roughly 60 to 100 dp, not a full dramatic
  curl), a soft shadow on the page underneath and a slightly lighter "back of the page"
  on the folded part. Finger-following during a swipe; release completes or cancels by
  distance and velocity.

How a turn works (both styles):

1. **Snapshot** the current reader view into a bitmap (use `PixelCopy` on the activity
   window for the fragment's view bounds; fall back to `view.draw(canvas)` if PixelCopy
   fails; if both fail, fall back to Readium's native `animated = true` turn so reading
   never breaks).
2. Show a Compose overlay above the `FragmentContainerView` holding the snapshot.
3. Call `goForward/goBackward(animated = false)` **under** the overlay. If it returns
   `false` (end or start of book), play a short rubber-band on the snapshot and remove
   the overlay; no turn.
4. Wait for the new page to be laid out (the fragment's `currentLocator` flow changes,
   or a short bounded delay of about two frames as the fallback) before the reveal
   shows what is underneath, so a chapter boundary never flashes a blank page. Until
   then the area being revealed shows the theme's page colour.
5. Animate the snapshot out (slide or curl). On completion remove the overlay.
6. For a **swipe**: take the snapshot on `DragEvent.Type.Start` and return `true` from
   `onDrag` from then on for this gesture; drive the animation from the finger offset on
   `Move`; on `End` decide commit (offset past about 30% of width, or a fling) or
   cancel. Cancel means: animate the snapshot back flat, then call
   `goBackward/goForward(animated = false)` to undo the silent turn **while the overlay
   still fully covers the page**, wait for layout, then remove the overlay.
   Vertical-dominant drags (text selection scroll, etc.) must return `false` and leave
   Readium alone, as today.
7. Keep every existing side effect of a turn: `invalidateViewportAnchor()`,
   `pendingContentsIndex = null`, `controlsVisible = false` for button turns, pace and
   streak bookkeeping, and `DiagnosticLog` records. Put these in one place
   (`EpubSession.turnPage(direction, source)`) so they cannot drift.

Rendering the curl: draw the snapshot with `Canvas.drawBitmapMesh` on a grid (about
20 × 30 cells) deformed along a cylinder whose axis moves with the fold position; draw
the shadow as a gradient under the fold line. Prefer a hardware `Bitmap` config and
reuse the bitmap between turns. Target 120 Hz on the Fold's inner screen: no allocations
per frame, no recomposition per frame (use `Animatable`/`withFrameNanos` and
`drawWithContent`/`Canvas`).

Direction and layout rules:

- Respect reading progression: in RTL books "forward" peels from the left edge. Use the
  same source of truth as today, `fragment.settings.value.readingProgression`.
- Respect the Android "Remove animations" accessibility setting
  (`ValueAnimator.areAnimatorsEnabled()` false): turn instantly, no overlay animation.
- Disable the engine while `typographyUpdating` is true or a viewport restore is in
  progress; fall back to current behaviour there.
- Fixed-layout books: keep native Readium swipes (do not take over the drag) but still
  use the engine for taps/buttons/keys **only if** the snapshot path is confirmed to
  work on an FXL fixture; otherwise leave FXL entirely native and say so.
- Fold/unfold or rotation in the middle of a turn: drop the overlay immediately and let
  the existing continuity logic (BC-07) handle the position.

## Toggle

- Add `paperPageTurn: Boolean` to the `"reader"` SharedPreferences via `EpubSession`
  with a `changePaperPageTurn(enabled)` setter, mirroring `changeVolumeKeys`.
- Add a row in the "Page turning" section of the controls sheet, after the volume-key
  switch, using the same toggleable Row pattern (label "Paper page turn", summary
  "Pages peel from the edge like paper and follow your finger. Off uses a plain slide.").
  Give it a `testTag` like the neighbouring rows.
- **Owner decision needed:** default on or off for existing users. Recommended default
  **off** for the first build so the owner can compare both styles on the phone, then
  flip the default once the paper feel is approved. State the chosen default in the
  evidence.

## Out of scope

No changes to Readium's own script or `readiumCssRsProperties`, no scroll mode, no
two-page spread, no sound, no haptics, no changes to taps, edge-tap policy, keys or
volume handling beyond routing them through the engine. No PDF, no network.

## Files expected to change

- `app/src/main/java/com/bookcover/diagnostic/reader/EpubSession.kt`: the single turn
  entry point, drag ownership, preference and setter.
- New `reader/PageTurnEngine.kt` (state machine: idle, dragging, animating, cancelling;
  pure Kotlin so it can be unit tested) and new `reader/PageTurnOverlay.kt` (Compose
  drawing of slide and curl from a snapshot plus a progress value).
- `reader/EpubReaderScreen.kt`: overlay host above the fragment container; the toggle
  row.
- Tests (below), `docs/review/ACTIVE-BACKLOG.md` row **PT-1**, and a short
  `docs/PT-1-PAGE-TURN.md` with the design notes and validation record.

## Tests and evidence

JVM unit tests (run by `scripts\Build.ps1`):

- `PageTurnEngineTest`: commit vs. cancel thresholds (distance and velocity), RTL
  direction mapping, end-of-book rubber-band, vertical drag passthrough, "remove
  animations" instant path, cancel-after-silent-turn always undoes exactly once,
  no turn while typography is updating.
- `PageCurlGeometryTest`: mesh deformation is monotonic and bounded (no vertex outside
  the page bounds plus the fold radius), progress 0 is the flat page, progress 1 is
  fully off-screen, for both directions.
- A `ReadingComfortPreferencesTest`-style check that the new preference round-trips and
  defaults as decided.

On-phone QA (isolated `qa` build only, through `Test-Device.ps1`):

- Extend `NativeReaderTest`: with the engine on, a swipe turns exactly one page and the
  reading position advances by one page position; a short swipe cancels and the
  position is unchanged; edge taps and the controls tap still work; the end-of-book
  swipe leaves the locator unchanged. Repeat one forward/back pair for an RTL fixture.
- Record whether `PixelCopy` succeeds on the Fold's inner screen and how long a snapshot
  takes (log it once per turn in `DiagnosticLog` during QA, then quiet it to failures
  only).
- Smoothness cannot be asserted by a test. Add a debug-only frame-time log during the
  turn animation (count of frames over 8.3 ms at 120 Hz / 16.7 ms at 60 Hz) and report
  the numbers for ten turns in the evidence.

Owner observation (the only thing that confirms "feel"): install in place with
`adb install -r` on the exact APK the build printed, then ask the owner to try, with
the toggle in each position: ten swipes forward, five back, a cancelled half swipe, a
chapter boundary in both directions, taps and volume keys, a Night-theme book, and one
rotation mid-turn. Record their words in the backlog row; do not describe the feel as
verified from logs.

## Constraints that must hold

- CLAUDE.md hard rules: never test against `com.bookcover.diagnostic`; never wipe its
  data; no personal books in the repo; no internet access; build through
  `scripts\Build.ps1` with a `-TaskId pt1`, device tests through `Test-Device.ps1` with
  explicit serial and release evidence.
- Keep `super.onCreate(null)` behaviour, the `--RS__maxLineLength` override and the
  `onCreatePublication` markup filter untouched.
- Saved reading positions must be at least as reliable as before: every committed turn
  must still reach `LatestValueSaver` through the existing `currentLocator` path.
- Preserve others' uncommitted work; record the PT-1 row before starting and update it
  at completion with commit, unit counts, QA results and the pending owner observation.

## Suggested workflow phases

1. **Spike (one agent, no UI):** prove on the QA build that returning `true` from
   `onDrag` stops the WebView scroll and that `PixelCopy` of the reader view yields a
   correct bitmap, including across a chapter boundary. Stop and report if either fails;
   the design changes in that case (fallback: engine for taps/buttons/keys only, native
   swipe kept).
2. **Engine + slide style + toggle + unit tests** (one agent). Slide first, because it
   delivers the "minor improvement in base behaviour" on its own and exercises the full
   snapshot/silent-turn/reveal path.
3. **Paper curl rendering** (one agent, depends on 2): mesh deformation, shadow, finger
   following, cancel physics, performance pass.
4. **QA + review** (one agent): device tests through the helper, frame-time numbers,
   `/code-review`, backlog update, in-place install for the owner.

Report in plain English at the end of each phase: what changed, what the build and
tests showed, and exactly what still needs the owner's eyes on the phone.
