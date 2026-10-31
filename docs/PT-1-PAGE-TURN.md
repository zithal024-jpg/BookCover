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
   `dispatchTouchEvent` (children cannot disable it). A move past 6 dp that is more sideways than
   vertical (|dx| > |dy|; step 5 lowered this from 1.5·|dy|) is taken: the children get one
   `ACTION_CANCEL` before their own slops (Chromium 8 dp, Readium's pager/WebView 16 dp), so no
   native scroll, fling or page snap follows. A drag that starts steeper is still watched until it
   has moved 16 dp sideways (the WebView's paging slop) and is taken if it flattens out by then.
   The swipe follows the finger that started it; if that finger lifts first, the turn cancels.
   The snapshot follows the finger. Release commits past 30% of the width or on a 400 dp/s fling
   over at least 25 dp (velocity from an Android `VelocityTracker`); a fling back cancels. Cancel
   slides the page back flat, then undoes the silent turn exactly once while the snapshot still
   covers the page, and only after the first turn has landed.
6. **Passes through untouched:** taps (Readium `onTap`: edge taps, controls toggle), vertical
   drags, long presses and drags while the text-selection toolbar is open, multi-finger gestures,
   any swipe while a panel is over the page (controls, Contents or bookmark editor: it would be
   in the snapshot), and swipes within 650 ms of a native Readium turn (its settle scroll would
   be frozen half-way). **Exception:** while the snapshot hides a page Readium has already turned
   to, touches never reach that hidden page. Edge taps still turn (queued) and a swipe can still
   start; centre taps, links and long presses are ignored for those few hundred milliseconds.
7. **Book edges.** Reflowable `goForward/goBackward` never return false at the end of the book,
   so the controller checks first: the visible WebView cannot scroll further that way and the
   resource it shows is the first/last reading-order item. That resource is read from the
   visible WebView's Readium page fragment (its `link` argument), not from the locator, which
   trails each turn by 100 ms; the locator is only the fallback. Then the page rubber-bands, no turn.
8. **Did the page really move?** Readium also returns true and then quietly does nothing at the
   start or end of the book. A silent turn therefore counts as a move only when the visible page
   changed (another WebView, or a scroll of at least 40% of a page). The locator decides only when
   no page could be watched. A turn that did not move is never undone.

## Paper curl (step 3)

1. **Geometry** (`PageCurlGeometry.kt`, pure Kotlin). The outgoing page wraps around a vertical
   cylinder lying on the page, radius 11% of the page width kept between 60 and 100 dp (about
   80 dp on the inner screen). Points before the fold line stay flat; points on the cylinder are
   compressed toward its crest; past half a turn the paper lies back over the page, so the reader
   sees its reverse. Paper never stretches. RTL forward turns (and LTR backward turns) are the
   exact mirror image: the page peels from the left edge.
2. **Finger-following.** During a swipe the lifted page edge stays under the finger. Progress 1
   means the fold line has passed one radius beyond the far edge, so the whole page is off the
   page area. Commit/cancel uses the same rules as the slide (30% of the width, or a 400 dp/s
   fling over 25 dp; a fling back cancels). The full curl takes 340 ms (the slide 280 ms) because it
   travels about twice as far; settling after release is shortened for a fast finger.
3. **Drawing** (`PageTurnPainter.curl` in `PageTurnOverlay.kt`). A reusable `PageCurlMesh` of
   22 columns × 30 rows: one flat column, ten on the front of the cylinder, ten on its back and one
   for the paper lying back, so columns always sit exactly on the fold line and the crest. Drawn
   with `Canvas.drawVertices` and a `BitmapShader` of the snapshot (hardware accelerated from
   API 29; minSdk is 30). `drawBitmapMesh` was not used because it spaces texture coordinates
   evenly and cannot put columns on the fold. Order: a soft shadow cast on the revealed page just
   beyond the curl, the front of the page (darkening up to 22% toward the crest), a short shadow
   beyond the folded-back flap's edge (deeper as it rises), then the back of the page: the mirrored
   print washed with the page colour nudged 8% toward white at about 80% opacity, so it reads as
   slightly lighter paper in every theme, Night included. A hint of perspective lets the raised
   paper grow by at most 0.8% of the page height.
4. **Performance.** Paints, gradients, the mesh arrays and index buffers are allocated once; the
   `BitmapShader` is created once per snapshot bitmap (which is itself reused). Progress is read
   only in the draw phase, so frames redraw without recomposing.
5. **Frame-time counter** (`PageTurnFrameMeter`, debuggable builds only: debug and qa). Per turn
   it counts animation frames and those over budget (an interval more than 1.5× the display's
   refresh interval, i.e. at least one missed vsync: over 12.5 ms at 120 Hz), the worst frame, and
   the average and worst UI-thread draw time of the overlay. It is appended to the existing
   `PT-1 turn …` DiagnosticLog line. Render-thread GPU time is not visible to it.

## Fallbacks and limits

- **Remove animations** (`ValueAnimator.areAnimatorsEnabled()` false): turns are instant
  (`animated = false`), no overlay; swipes are still taken so nothing scrolls half-way.
- **Typography reflow or viewport restore in progress:** the previous behaviour (native swipes,
  Readium's animated turn).
- **Fixed-layout books stay entirely native** (no swipe takeover; taps, buttons and keys keep
  `animated = true`). No FXL fixture has been checked on the phone; revisit after that.
- **Fold, unfold or rotation mid-turn:** the overlay is dropped at once (layout-size change and
  `viewportChanging`), and BC-07 continuity restores the passage. A committed turn is kept. A
  swipe the user had not committed (still under the finger, cancelling, or being undone) is
  turned back. Continuity and closing the reader then use the locator from just before its
  silent turn, so the book never reopens on a page the user declined (step 5, D8).
- **A flick that commits before its page is drawn** keeps the full-length settle, and if the
  page is still not drawn the page colour fades off it (150 ms) instead of the text popping in.
- **Typography change:** any running turn is dropped before the reflow starts.
- **Turns that arrive while one is running:** a running animation jumps to its end and the new
  turn starts. If the old turn is still waiting for its page, or a finger still holds a swipe,
  new turns wait in order, up to 3 (`MAX_QUEUED_TURNS`), and each runs when the engine is idle
  again. Earlier builds kept one slot and aborted a held swipe for a non-engine turn, which could
  turn two pages.
- **Readium refuses a silent turn** (`goForward/goBackward` returns false or throws): the engine
  records it, never undoes it on cancel, and logs `PT-1 silent … turn refused by Readium`.

## Toggle

"Paper page turn" in More → Page turning (`testTag("paper-page-turn")`), saved as
`paperPageTurn` in the `"reader"` SharedPreferences. **Default: off** for the first build, so the
owner can compare both styles; off = smoothed slide, on = paper curl (step 3). A turn keeps the
style it started with.

## Step 4 review (2026-10-30, code reading of `19cd1f4..171d03e`, no device)

| # | Finding | Result |
|---|---------|--------|
| R1 | A cancelled swipe always undid its silent turn, even when Readium had refused that turn (`goForward/goBackward` returned false or threw). The undo would then move the reader one page the wrong way. | **Fixed.** `PageTurnEngine.pageReady(token, moved)` clears the "turned" state for a refused turn, so a cancel finishes without an undo. Three new `PageTurnEngineTest` cases. |
| R2 | A turn from a key or tap that arrived while a finger held a swipe used `abort()` when its path was not the engine (e.g. reflow had just started). That kept the swipe's silent turn and added a second turn on top. | **Fixed.** A turn that arrives while the engine is busy is always queued and re-decides its path when it runs. |
| R3 | The single queue slot was drained only when an overlay was removed. A turn queued behind an overlay-less turn (instant path, failed snapshot) could wait until the next turn. Quick repeated key presses also dropped all but the last. | **Fixed.** The queue holds up to 3 turns in order and is drained whenever the engine becomes idle. |
| R4 | A queued turn was posted to the main thread and could run after the reader closed. | **Fixed.** It is read at run time and dropped once the controller is closed or aborted. |
| R5 | A turn mid-flight was not dropped when a display change began (`viewportChanging`) or a typography reflow started. The layout listener caught most cases, but only after the resize. | **Fixed.** Both now call `pageTurns.abort()` first. |
| R6 | `PageTurnPainter` was created inside the "overlay visible" branch, so each turn allocated a new mesh (22×30 arrays) and `BitmapShader`. | **Fixed.** It is remembered above the early return and reused across turns. |
| R7 | Position saving: committed turns (silent `animated = false`) and undos both change `fragment.currentLocator`, which the unchanged collector in `EpubSession.attach` saves through `positions.submit` (LatestValueSaver). | No change needed (checked by reading; not exercised on the phone). |
| R8 | Edge taps and the controls tap: taps never cross the 6 dp decision slop, so they reach Readium's `onTap` unchanged. Edge-tap log lines are kept. A tap that drifts 6 to 8 dp sideways is now taken as a short swipe, which cancels, where Chromium alone might have counted it as a tap. | Kept as designed (step-1 spike). Device QA should watch for missed edge taps. |
| R9 | RTL: direction, screen sign and the book-edge `canScrollHorizontally` direction mirror correctly in unit tests. The RTL book-edge check on real Readium RTL pages is unverified. | Open: needs the RTL device test. |
| R10 | Hard rules: no manifest, `INTERNET`, network, `build.gradle.kts`, `testBuildType`/`applicationIdSuffix` or QA host changes in the diff. | No change needed. |
| R11 | Debuggable builds (including the owner's installed debug build) log one `PT-1 turn …` line per turn. The log is capped at 128 KB, so these lines can push out older ones. | Kept for QA as the prompt asks; quiet it to failures only after device QA. |
| R12 | Going backward across a chapter boundary: readiness fires once a different WebView is visible and has drawn (bounded at 150 ms). If Readium positions that chapter at its last page only after it loads, the reveal could briefly show its first page. | Open: unverified. Check on the phone (chapter boundary, both directions). |

## Step 5 deep review (2026-10-30, `19cd1f4..c459b9d`, code and Readium 3.4.0 bytecode, no device)

Each finding went through three independent attempts to refute it. CONFIRMED findings survived at
least two. Unit tests are JVM-only. A finding marked "wiring" changes Android touch or Compose code
that the JVM tests cannot run, so only the build and the phone check it.

| # | Finding (verdict) | Outcome |
|---|---|---|
| D1 | A silent turn that Readium quietly ignores counted as a real move. Reflowable `goForward/goBackward` return true whenever a WebView exists, even at the start or end of the book, so the R1 "refused turn" check almost never fired. If `atBookEdge` was wrong at a real edge, cancelling the swipe would "undo" a turn that never happened and move the reader one page the wrong way. (CONFIRMED, medium) | **Fixed.** `awaitPage` now returns whether the page moved. When a page could be watched, only a visible change counts: another WebView, or a scroll of 40% of a page or more (`PageTurnRules.pageChanged/turnLanded`). A locator change alone could be a stale locator catching up. The visible page is checked once more after the locator wait. A turn that did not move goes to `pageReady(moved = false)` and is never undone. Test `aSilentTurnThatChangedNothingOnScreenIsNotAMove`. |
| D2 | The book-edge check took the resource from Readium's locator, which is announced 100 ms after a turn. A quick reverse turn after leaving the first or last chapter was taken as the book's edge and lost. (CONFIRMED, low) | **Fixed.** `atBookEdge` reads the resource from the visible WebView's own page fragment: the innermost fragment whose view holds it, using its `link` argument. In Readium 3.4.0 `R2EpubPageFragment` is internal, so the code matches on the view and the argument, not the class. The locator is only the fallback. Test `bookEdgeNeedsAKnownFirstOrLastResource` covers the index rule. The fragment lookup is wiring. |
| D3 | Swipes that were mostly sideways but at an angle (ratio between 1:1 and 1.5:1) still turned the old Readium way. `R2WebView` pages on sideways travel alone and never checks dy. (CONFIRMED, medium) | **Fixed.** A drag is a turn when \|dx\| > \|dy\| (`HORIZONTAL_DOMINANCE` 1.5 → 1). A drag that starts steeper is watched (`Mode.WATCH`) until it has moved 16 dp sideways (the paging slop, `mayStillTakeOver`), and is taken if it flattens out by then. Tests: `verticalAndShortDragsPassThrough` (updated), `steepDragsAreWatched…`. Trade-off: a tap that drifts 6 dp at 45° is now taken as a short swipe (see R8). |
| D4 | A touch-down stops `R2WebView`'s settle scroll half-way. Only its own ACTION_UP snaps the page back, and the takeover's ACTION_CANCEL does not. A quick swipe during a native settle therefore left the snapshot (and at the book edge, the page) misaligned. (CONFIRMED, low) | **Fixed.** For `NATIVE_SETTLE_MILLIS` (650 ms) after a native Readium turn, swipes stay native and Readium's own ACTION_UP re-snaps the page. "Native turn" means Readium's animated turn, or a passed-through gesture that ended with at least 16 dp of sideways travel. Test: `canTakeOverSwipe(nativeSettling = true)` is false. The touch-layer trigger is wiring. |
| D5 | Taps and long presses during a turn reached the hidden page under the snapshot (links, controls toggle, text selection). (CONFIRMED, low) | **Fixed.** While the overlay shows a page other than the one underneath (`coversOtherPage`), the touch layer swallows the gesture (`Mode.BLOCKED`). An edge tap still turns (queued, through `EpubSession.blockedTap` → `turnPage`), and a horizontal swipe can still start. Centre taps, links and long presses are ignored for that moment. Test `touchesAreBlockedOnlyWhileTheSnapshotHidesAnotherPage`. The touch layer itself is wiring. |
| D6 | An owned swipe read pointer index 0. If the first finger lifted while a second stayed down, the swipe jumped to the other finger and could commit a turn nobody swiped through. (CONFIRMED, low) | **Fixed (wiring, no JVM test: `MotionEvent` needs a device).** The layer records the active pointer id, reads it with `findPointerIndex`, and takes velocity for that id. If that finger lifts first, the swipe cancels and the rest of the gesture is ignored. |
| D7 | A swipe cancelled while its snapshot was being taken still issued its silent Readium turn. An edge back gesture that closed the reader could then save the page next to the one being read. A cancel in the two frames after the overlay appeared did the same. (CONFIRMED, medium) | **Fixed.** `captured()` drops a swipe released without commit during capture, so no overlay and no turn. The silent turn is now issued through `engine.issueTurn(token)` after the overlay is on screen, and returns null when the swipe has been cancelled meanwhile. `turned`/`silentTurns` count only issued turns. Test `aSwipeCancelledBeforeItsSilentTurnNeverTurnsReadium`. |
| D8 | `close()`/`abort()` kept the silent turn of a swipe the user had cancelled. The book could reopen, or a fold restore, one page off. (CONFIRMED, medium; one verifier rated it near-theoretical) | **Fixed.** The engine reports `uncommittedTurnOutstanding`/`undoOwed`, meaning a swipe still under the finger, cancelling, or being undone. `abort()` turns Readium back when an undo is owed, and returns the locator from just before the silent turn. `close()` saves that locator instead of Readium's current one. `viewportChanging` hands it to BC-07 continuity. A committed turn is still kept. Test `anUncommittedSwipeTurnIsReportedUntilItsUndoLands`. Replaces the step-2 note "a turn already issued under a finger is kept". |
| D9 | Reading pace got two short samples for one page when a swipe was cancelled after its silent turn. (CONFIRMED, low) | **Fixed.** `refreshChapter` skips `readingPace.observe` while a swipe is uncommitted (`pageTurns.swipeUncommitted`). The undo then lands on the unchanged baseline, so the `onTurnUndone → readingPace.pause()` hook is no longer needed and was removed: it threw away the page's reading time. Engine state covered by the D8 test. The `EpubSession` call is wiring. |
| D10 | A quick flick that committed before its page was drawn slid the snapshot off over plain page colour, then the text popped in. (CONFIRMED, low; mainly at chapter boundaries) | **Fixed.** `Settle(1f, awaitingPage = true)` keeps the full-length settle, not shortened by finger speed. If the page is still not drawn when the settle ends, `HOLDING` → `FADING`: the page-colour cover fades off over 150 ms (`FadeCover`, `coverFade`), so the text no longer pops in. Test `committedSwipeHoldsPageColourUntilTheTurnLands` (updated). |
| D11 | The bottom clip followed the current controls state, not the state when the snapshot was taken. Opening the controls mid-turn drew the snapshot's copy of the progress row. (CONFIRMED, low; mostly hidden by the panel) | **Fixed (wiring).** The overlay reports the live clip to the controller (`liveBottomClip`). The capture latches it (`turnBottomClip`), and the painter uses the latched value for the whole turn. |
| D12 | Swipe takeover checked only `controlsVisible`. With Contents open after a centre tap had hidden the controls, a swipe was taken and the Contents panel was copied into the turning page. (CONFIRMED, low; the bookmark-editor half was refuted: it is a separate dialog window) | **Fixed (wiring).** `EpubReaderScreen` publishes its own panel condition (`controlsVisible \|\| contentsVisible \|\| bookmarkDraft != null`) as `EpubSession.panelsOpen`, and the controller's takeover check uses it. |
| D13 | Same root cause as D2, seen at a chapter boundary. Case 1: a quick second turn was wrongly treated as the book's edge. Case 2: an edge was missed, then a silent turn that did nothing, about 1 s of page colour, and a "turn" to the same page. (CONFIRMED, low) | **Fixed by D2** (resource from the visible page) and **D1** (a turn that did not move is not counted, and is never undone). |
| RF1 | A gesture whose UP/CANCEL was dropped would leave the engine stuck in DRAGGING. (REFUTED: `ViewGroup` sends ACTION_CANCEL to the old touch target before a new ACTION_DOWN, through Compose's interop, and detaching is handled) | Recorded only, no change. |

Still open after step 5: R9 (RTL book edges on real Readium pages) and R12 (backward
chapter-boundary reveal) from step 4. All of D1 to D13 still need the phone, especially:
- the D3 takeover angle and edge taps (R8);
- the D5 blocked taps;
- the D2 page-fragment lookup on Readium 3.4.0 (if it fails, the locator fallback is the old behaviour).

## Step 6 on-phone tests (2026-10-30, written and compiled, not yet run)

New `app/src/androidTest/.../testing/PageTurnTest.kt` (isolated `qa` app, `ReaderTestActivity`, the
generated `NativeEpubFixture` only; no new fixture was needed). Swipes are real touch events
injected through the window, so they reach `PageTurnTouchLayer`. Each case runs with the paper
toggle off and on:

- swipes: start-of-book backward swipe stays put; a slow swipe past 30% (stopped before release,
  so only distance decides) and a fast fling each turn exactly one page; a 12% stopped swipe
  cancels with the offset and locator unchanged; a swipe back returns exactly one page;
- chapter boundaries: forward lands on chapter 1's first page, backward on chapter 0's **last**
  page; while the snapshot is revealing, the visible WebView is sampled each frame and must already
  show that page (checks R12); a swipe at the end of the book leaves page and locator unchanged;
- edge taps (one page each way), the centre controls tap, and the Previous/Next page buttons
  (accessibility clicks) each move exactly one page;
- RTL: start-of-book backward swipe stays put (part of R9), then one forward/back pair per style;
- the "Paper page turn" row (`paper-page-turn`) turns on through accessibility, is saved, and is
  still on (and used) in a new session;
- ten swipes per style on the inner screen with frame totals; skipped when folded
  (smallest width under 600 dp), like `ReadingLayoutTest`.

Every engine turn waits for its `PT-1 turn …` DiagnosticLog line, asserts its style, source,
snapshot method/time and frame counts, and prints them to logcat with tag `PT1QA`. Engine tests are
skipped (not passed) if Android animations are off, since turns then take the instant path. A first
reader screen that never attaches ("navigator is attached") is reopened once.

Existing tests: `NativeReaderTest.swipeViewport` drags 60% of the width in 240 ms, so it still
commits by both rules. `settledPageOffset` (NativeReaderTest) and `settledOffset`
(ReadingKeysAccessibilityTest) now also wait for `PageTurnController.idle` (new read-only accessor):
during a turn's last ~300 ms touches are held back from the hidden page (D5), so a centre tap or link
tap right after a turn could otherwise be swallowed. Their timeout went from 5 to 8 s; no assertion
changed. `scripts\Build.ps1` does not compile androidTest; `compileQaAndroidTestKotlin` and
`assembleQaAndroidTest` were run separately with the same task environment and a BUILD reservation.

## Validation record

- Step 2 (2026-10-30): `scripts\Build.ps1 -TaskId pt1` — see the PT-1 backlog row for the commit,
  unit-test counts and APK evidence. JVM tests: `PageTurnEngineTest` (thresholds, RTL mapping,
  vertical passthrough, instant path, undo-once, book-edge rubber band, typography fallback,
  abort) and `PageTurnPreferencesTest` (default off, round trip).
- **Not yet verified:** nothing has run on the phone. PixelCopy speed on the inner screen, the
  readiness signal, Chromium's real touch slop, RTL edge detection and the feel all need the
  isolated QA run (step 4) and the owner's own observation. Debuggable builds log one
  `PT-1 turn …` line per turn (snapshot time and method, readiness path, frame-time counts).
- Step 3 (2026-10-30): paper curl, `PageCurlGeometryTest` (flat at progress 0, fully off the page
  at 1, bounds within the page plus the fold radius, each point moving one way only, mirror image
  for the other direction, no stretching, edge under the finger, crest/back shading, mesh arrays
  reused) and `PageTurnFrameMeterTest`. See the PT-1 backlog row for the commit and build.
  **Not yet verified on the phone:** how the curl looks and feels, `drawVertices` with a bitmap
  shader and per-vertex colours on the Fold's GPU, and the frame-time numbers for ten turns.
- Step 4 (2026-10-30): review fixes R1 to R6 above, plus 3 new `PageTurnEngineTest` cases (a
  refused silent turn is never undone, whether it lands before or after the cancel animation,
  and a refused undo is not counted as a refused turn). `scripts\Build.ps1 -TaskId pt1`:
  BUILD SUCCESSFUL, exit 0. assembleDebug, testQaUnitTest **293/293** (0 skipped, failed or
  errors; `PageTurnEngineTest` 17, `PageCurlGeometryTest` 12, `PageTurnFrameMeterTest` 3,
  `PageTurnPreferencesTest` 2), lintDebug and lintQa all passed. Source snapshot
  `57aff70f…913d`; evidence manifest
  `%LOCALAPPDATA%\BookCoverBuild\tasks\pt1-c273832966f5eaef\evidence\57aff70f5fd6d603462d7066d56a7321d7c3006ef3a67a94d6c6d0599c98913d-4bd87669e9074eafaca30959bd49a607.json`.
  The APK `output\BookCover-pt1-c273832966f5eaef.apk` is git-ignored and was **not installed**.

- Final check and device QA (2026-10-31): see "Step 7" below.

## Step 7 final check and device QA (2026-10-31)

Final independent check of the step-5/6 commits found two issues, fixed in `696dae8`:

| # | Finding | Outcome |
|---|---|---|
| F1 | A Previous/Next button turn latched the bottom clip before the controls closed, so the snapshot kept the progress row and slid it with the page. | Fixed: `turnBottomClip` is read after the wait frames, just before the snapshot. |
| F2 | The R12 reveal check could pass with zero samples. | Fixed: it samples during the drag as well and asserts at least one sample. |

`scripts\Build.ps1 -TaskId pt1 -Owner Claude`: BUILD SUCCESSFUL; assembleDebug, testQaUnitTest
299/299, lintDebug and lintQa; source snapshot `11a08d8d…93a2`; APK SHA-256 `e322cbc2…ba97`.

Device QA on the owner's Fold (`RFGL710TARJ`), isolated `com.bookcover.diagnostic.qa` app only,
through `scripts\Test-Device.ps1 -TaskId pt1`, phone unfolded and untouched:

- Before the run the phone had **Android animations off** (Remove animations on, all three
  animation scales 0). With that setting the engine deliberately takes its instant path, so the
  owner could not have seen any slide or curl. The owner turned animations back on.
- Run 1 (`PageTurnTest`, `NativeReaderTest`, `ReadingKeysAccessibilityTest`): 27/28. The failure
  was a test defect: the frame sample's paper turns continued from where the slide turns stopped
  and crossed into chapter 1 on the 19th turn. Fixed in `e0e0992` (each style starts at the
  chapter start).
- Run 2 (`PageTurnTest`): 9/9, 0 skipped.
- R12 reveal (backward chapter crossing): 36 to 42 samples per crossing, all showed the correct
  page. R9 (RTL start of book) passed.
- Frame times on the inner screen at 120 Hz, ten swipe turns each:

| Style | Frames | Over budget |
|---|---|---|
| Slide | 200 | 0 |
| Paper curl | 320 | 0 |

  The worst single frame in either style was 10.8 ms. Budget is a missed vsync (over 12.5 ms). PixelCopy succeeded on every turn, 6 to 43 ms.
- Installed in place with `adb install -r`; `firstInstallTime` unchanged, so app data was kept.

## What is built and what is NOT verified

**Verified:** JVM unit tests (299/299), lint, and the isolated on-phone `PageTurnTest` (both
styles: one-page swipes, cancel, chapter boundaries including the R12 reveal, book ends, edge and
centre taps, page buttons, RTL, toggle persistence, frame times) plus `NativeReaderTest` and
`ReadingKeysAccessibilityTest` on the owner's Fold.

**Not verified yet:**
- The owner's own observation of the feel of both styles, the curl radius, a cancelled half
  swipe, chapter boundaries, the Night theme and a rotation or fold mid-turn. Only the owner
  looking at the phone can confirm the feel.
- Fixed-layout books stay on Readium's native turns; no fixed-layout device check was run.
- The frame meter measures UI-thread frame intervals and overlay draw time, not GPU time.
- Debug builds still log one `PT-1 turn` line per turn; quiet it to failures only after the
  owner signs off.