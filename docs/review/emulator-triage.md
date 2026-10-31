# Emulator QA triage: 10 failures in the 63/73 run

This note only classifies failures. It does not change the app or the tests.

The last commit on `claude/reader-test-fixes`, `1231b98` ("Backlog: R2 cause, echo fix 46c401e, Fold 44/44 and emulator 63/73"), records one emulator QA run of that source: **73 tests run, 63 passed, 10 failed, 0 errors, 0 skipped**. The backlog names those 10 as the four fold-state checks and the six `NativeReaderTest` swipe checks. The run is task `r2fix6`, device operation `6d2dc9514c4f40bd9250a68021dd2071`. The JUnit XML and per-test logcats for that run are not in this git repository (they were archived on the owner's Windows machine). The messages quoted below are from a new emulator rerun of the same commit, which reproduced the same 10 names.

On the owner's Fold, this same source already passed the reader cohort **44/44**, including all four fold checks and all 13 `NativeReaderTest` checks. That phone result is from the backlog (device operation `b95afbdff7bb48249bd034a6148b1f78`). It was not repeated here.

## Counts

| Classification | Count | Tests |
|---|---|---|
| Real app bug | 0 | — |
| Environment (emulator device config) | 4 | the four fold-state checks |
| Flaky (injected swipe on this emulator) | 5 | five of the six `NativeReaderTest` swipe checks |
| Unresolved, needs Fold run | 1 | `NativeReaderTest.explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions` |

## What was rerun

A new Android emulator was started on this machine. It is not the owner's `BookCoverInnerQA` process. It was set up to match the recorded task-owned emulator as closely as this machine allows:

- Android 16, API 36, Google APIs x86_64 image
- Screen 1768×2208 at 420 dpi
- Gesture navigation (`settings secure navigation_mode` = 2)
- One-time full-screen prompt marked confirmed (`immersive_mode_confirmations` = `confirmed`)
- `cmd device_state print-state` answered `0`, and `print-states` listed only `identifier=0` (`DEFAULT`)
- WebView `133.0.6943.137` (versionCode `694313738`). The backlog's recorded WebView is the same `133.0.6943.137` with versionCode `694313707`

Only the QA app was installed: `com.bookcover.diagnostic.qa` and its test package. The main app `com.bookcover.diagnostic` was not installed. The APKs came from `bash ./gradlew assembleQa assembleQaAndroidTest` with JDK 17 and `-Pbookcover.buildRoot=/tmp/bookcover-build`. Tests were started with `adb shell am instrument` and `AndroidJUnitRunner`. The Windows helper `scripts/Test-Device.ps1` was not used.

Commands, by result file:

1. Whole `NativeReaderTest` class, 13 tests (`/tmp/qa-native-run1.txt`): **8 passed, 5 failed**. Every test that does not inject a swipe passed.
2. The six swipe methods again (`/tmp/qa-swipe-run2.txt`): **2 passed, 4 failed**.
3. The six swipe methods a third time, with logcat (`/tmp/qa-swipe-run3.txt`, `/tmp/qa-swipe-run3-logcat.txt`): **2 passed, 4 failed**. Which two passed changed.
4. The two right-to-left swipe methods again (`/tmp/qa-swipe-rtl-run4.txt`): one passed, one failed.
5. `explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions` once more (`/tmp/qa-swipe-rtl-run5.txt`): failed.
6. The four fold methods (`/tmp/qa-fold-run1.txt` and `/tmp/qa-fold-run2.txt`): **4 failed, then 4 failed**, with the same message both times.

The other 63 tests from the original 73 were not rerun. This session does not re-prove those passes.

## Summary

| # | Test | Classification | Failure seen in the rerun |
|---|---|---|---|
| 1 | `ReaderContinuityTest.tenFoldCyclesKeepOneReaderAndTheVisiblePassage` | Environment | `Needs device state 3 of a physical Fold` at `ReaderContinuityTest.kt:250`, called from line 86 |
| 2 | `ReaderContinuityTest.rtlMangaKeepsItsResourceAndNativeNavigation` | Environment | Same message at `ReaderContinuityTest.kt:250`, called from line 207 |
| 3 | `ReaderLifecycleTest.foldingAndUnfoldingKeepTheBookAtTheSamePlace` | Environment | Same message at `ReaderLifecycleTest.kt:78` |
| 4 | `ScreenIdleTest.recreationAndFoldingKeepTheOriginalDeadline` | Environment | Same message at `ScreenIdleTest.kt:101` |
| 5 | `NativeReaderTest.optionalEdgeTapsPreserveSwipesAndFollowLinksInsteadOfTurningPages` | Flaky | `Timed out waiting for a normal swipe advances a page` (`NativeReaderTest.kt:126`), and once `Timed out waiting for swipes still work after toggling edge taps` (`NativeReaderTest.kt:150`) |
| 6 | `NativeReaderTest.explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions` | Unresolved, needs Fold run | `Timed out waiting for native swipe advances in book direction` (`NativeReaderTest.kt:195`) on all 5 attempts |
| 7 | `NativeReaderTest.languageInferredRtlUsesTheSameEdgeDirectionAsReadium` | Flaky | Same message at line 195 on 3 of 4 attempts; the fourth attempt passed |
| 8 | `NativeReaderTest.explicitLtrDirectionOverridesAnRtlLanguageForEdgeTurns` | Flaky | Same message at line 195 on 1 of 3 attempts; the other two passed |
| 9 | `NativeReaderTest.rtlImagePagesAcceptEdgeTapsOnArtwork` | Flaky | `Timed out waiting for native swipe still advances an RTL image page` (`NativeReaderTest.kt:273`) on 2 of 3 attempts; the third passed |
| 10 | `NativeReaderTest.rtlImagePagesWithInertKindlePanelOverlaysAcceptEdgeTaps` | Flaky | Same message at line 273 on 1 of 3 attempts; the other two passed |

## How a fold check fails

Each fold test asks Android which hinge states the device offers. On a phone emulator that is not a foldable, the only state is `0`. The test then stops on purpose, before it asks the screen to change size:

```245:254:app/src/androidTest/java/com/bookcover/diagnostic/testing/ReaderContinuityTest.kt
    private fun switchScreen() {
        val width = onActivity { it.resources.configuration.screenWidthDp }
        val other = if (shell("cmd device_state print-state").trim() == "0") "3" else "0"
        val states = shell("cmd device_state print-states")
        // Fail at once, not as a later display-width timeout, where the device lacks the Fold's states.
        assertTrue("Needs device state $other of a physical Fold; this device offers: ${states.trim()}", "identifier=$other," in states)
        shell("cmd device_state state $other")
        waitFor("display width changed", 20_000) { onActivity { it.resources.configuration.screenWidthDp } != width }
        SystemClock.sleep(600) // Require the settled page, not a briefly visible pre-reflow viewport.
    }
```

`ReaderLifecycleTest` (line 78) and `ScreenIdleTest` (line 101) use the same check. The message from both reruns was:

```text
java.lang.AssertionError: Needs device state 3 of a physical Fold; this device offers: Supported states: [
  DeviceState{identifier=0, name='DEFAULT', app_accessible=true, cancel_when_requester_not_on_top=false},
]
```

`identifier=3,` is absent, so the assertion fails immediately. Both reruns failed all four tests. Nothing in the message or the device-state list changed between runs, so these four are not flaky on this emulator. The app's fold behaviour is not what failed: the test returns before it sends `cmd device_state state 3`. The backlog already records these four passing on the physical Fold.

## How a swipe check fails

The six swipe tests inject a finger-drag with `Instrumentation.sendPointerSync`. Twelve move steps are spaced 20 milliseconds apart, from 80% of the page width to 20%, or the reverse for a right-to-left book. The test then waits up to 15 seconds for `window.scrollX` to change by more than 50 pixels (`NativeReaderTest.kt` lines 484–491 and 529–546).

BookCover does not turn pages on that drag. Its Readium listener watches the drag and returns false, which leaves the turn to Readium's WebView:

```181:192:app/src/main/java/com/bookcover/diagnostic/reader/EpubSession.kt
        fragment.addInputListener(object : InputListener {
            override fun onDrag(event: DragEvent): Boolean {
                // Observe intent only: native Readium/ViewPager remains the sole swipe handler.
                // A user who navigates during reflow must not be pulled back by an old anchor.
                if (event.type == DragEvent.Type.Start) {
                    readerActive()
                    invalidateViewportAnchor()
                    pendingContentsIndex = null
                    if (typographyUpdating) typographyAnchorInvalidated = true
                }
                return false
            }
```

The window touch wrapper in `ReadingKeys.kt` (lines 20–22) records a touch-down and then forwards the event. In Readium 3.4.0, `gestures.js` only cancels the WebView's own scroll when that Android callback returns true. With `false`, the page is supposed to move because the WebView scrolls.

On this emulator the injected drag sometimes does not move the page. Logcat during the third swipe run recorded 25 lines of this form, including during tests that passed:

```text
InputDispatcher: Dropping injected event from a766e57 Taskbar: Injected event targeted at uid 10216 would be dispatched to window 'a766e57 Taskbar' owned by uid 10181.
```

The test app's uid was 10216. The system threw the event away because it thought the large-screen Taskbar window should receive it. The same drop also happened inside `explicitLtrDirectionOverridesAnRtlLanguageForEdgeTurns` while that test passed (20:55:06–20:55:18 in `/tmp/qa-swipe-run3-logcat.txt`). A dropped event is therefore part of the emulator input path, and it is not, by itself, a complete explanation of every miss. The earlier backlog note that switching the injection to `UiAutomation` did not clear these failures was not repeated here.

The pass/fail table from this session:

| Test | Attempts | Passed | What the failures said |
|---|---|---|---|
| `optionalEdgeTapsPreserveSwipesAndFollowLinksInsteadOfTurningPages` | 3 | 0 complete passes | First swipe timed out twice. On the middle run the first swipe moved the page, and the later swipe (line 150) timed out |
| `explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions` | 5 | 0 | First swipe timed out every time |
| `languageInferredRtlUsesTheSameEdgeDirectionAsReadium` | 4 | 1 | First swipe timed out three times |
| `explicitLtrDirectionOverridesAnRtlLanguageForEdgeTurns` | 3 | 2 | First swipe timed out once |
| `rtlImagePagesAcceptEdgeTapsOnArtwork` | 3 | 1 | Final swipe timed out twice, after the artwork taps had already turned pages |
| `rtlImagePagesWithInertKindlePanelOverlaysAcceptEdgeTaps` | 3 | 2 | Final swipe timed out once |

The seven `NativeReaderTest` methods that never call `swipeViewport` all passed in the full class run: font size, font family, selection dismissal, fixed layout, publisher styles, text size with publisher styles, and comfort controls. Edge taps inside the two image-page tests also completed before a failing swipe wait. The reader can open, reflow, tap, and follow links on this emulator. Five of the six swipe checks varied from run to run. `explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions` did not: it failed all five attempts.

## 1. `tenFoldCyclesKeepOneReaderAndTheVisiblePassage`

- **Classification:** environment.
- **Test:** `ReaderContinuityTest.tenFoldCyclesKeepOneReaderAndTheVisiblePassage`.
- **Message:** `Needs device state 3 of a physical Fold; this device offers: Supported states: [ DeviceState{identifier=0, name='DEFAULT', ...} ]`.
- **Where:** `ReaderContinuityTest.kt:250`, reached from the first `switchScreen()` at line 86.
- **Why:** The test had already opened a generated book, applied type and theme, and jumped to the search passage. Both reruns then stopped on the device-state check. The ten fold cycles, the passage checks, and the reading-time checks after the loop were not reached. Repeating the four fold tests produced the same four failures. The physical Fold run recorded in the backlog passed this test.

## 2. `rtlMangaKeepsItsResourceAndNativeNavigation`

- **Classification:** environment.
- **Test:** `ReaderContinuityTest.rtlMangaKeepsItsResourceAndNativeNavigation`.
- **Message:** the same `Needs device state 3 of a physical Fold` assertion.
- **Where:** `ReaderContinuityTest.kt:250`, reached from `switchScreen()` at line 207.
- **Why:** The stack is after `current.next()` and the wait for the second image page (lines 203–204). Programmatic page movement had worked. The test then asked for a hinge state this emulator does not have. Both reruns failed at that line. No display-width timeout occurred.

## 3. `foldingAndUnfoldingKeepTheBookAtTheSamePlace`

- **Classification:** environment.
- **Test:** `ReaderLifecycleTest.foldingAndUnfoldingKeepTheBookAtTheSamePlace`.
- **Message:** the same `Needs device state 3 of a physical Fold` assertion.
- **Where:** `ReaderLifecycleTest.kt:78`.
- **Why:** The check is the method's own copy of the fold precondition, placed before `cmd device_state state 3`. The book had been opened and a progression had been read (lines 72–74). The later "same place after fold" waits were not reached. Both reruns failed here with the state-0 list. This is the emulator's device configuration, and it did not vary between runs.

## 4. `recreationAndFoldingKeepTheOriginalDeadline`

- **Classification:** environment.
- **Test:** `ScreenIdleTest.recreationAndFoldingKeepTheOriginalDeadline`.
- **Message:** the same `Needs device state 3 of a physical Fold` assertion.
- **Where:** `ScreenIdleTest.kt:101`.
- **Why:** Lines 93–96 require that recreating the activity restores the original idle deadline and still keeps the screen on. The failure stack is line 101, so those recreation checks had already succeeded. The fold loop (lines 102–111) and the deadline-expiry check after it were not reached. Both reruns failed at line 101 with only device state 0 listed.

## 5. `optionalEdgeTapsPreserveSwipesAndFollowLinksInsteadOfTurningPages`

- **Classification:** flaky.
- **Test:** `NativeReaderTest.optionalEdgeTapsPreserveSwipesAndFollowLinksInsteadOfTurningPages`.
- **Messages:**
  - Runs 1 and 3: `Timed out waiting for a normal swipe advances a page` at `NativeReaderTest.kt:126`.
  - Run 2: `Timed out waiting for swipes still work after toggling edge taps` at `NativeReaderTest.kt:150`.
- **Where:** the injected drag is `swipeViewport` at lines 529–546. The first wait is line 126. The second is line 150, after edge taps were turned on, used, and turned off again.
- **Why:** There was no complete pass in three runs, but the failing line moved. Reaching line 150 means the disabled-edge tap did not turn the page and the first swipe did. The same method then missed a later swipe. That is an unreliable injected drag, not a stable reader defect. The backlog's Fold result includes this method in `NativeReaderTest` 13/13.

## 6. `explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions`

- **Classification:** unresolved, needs Fold run. This is not a confirmed app bug, and it is not flaky.
- **Test:** `NativeReaderTest.explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions` (`NativeReaderTest.kt:165`), which calls `checkDirectionalEdgeTurns(rightToLeft = true)`.
- **Message:** `Timed out waiting for native swipe advances in book direction` at `NativeReaderTest.kt:195`.
- **Where:** `swipeViewport(0.20, 0.80, 0.60)` at line 194, then the wait at line 195. The disabled-edge tap assertion just above it had passed, because the timeout is the swipe wait.
- **Why:** The same method failed 5 of 5 attempts in this session (the full class run, two runs of the six swipe methods, the paired right-to-left rerun, and one more isolated run). A pass was not observed, so there is no local evidence that this check flips. That is why it is not labeled flaky. It is also not a confirmed bug. It uses the same helper and the same wait as `languageInferredRtlUsesTheSameEdgeDirectionAsReadium`, which passed once, and as `explicitLtrDirectionOverridesAnRtlLanguageForEdgeTurns`, which passed twice. The drag listener in `EpubSession.kt` returns false, so BookCover does not consume the gesture. The backlog's Fold cohort passed this method inside `NativeReaderTest` 13/13. Those two facts keep an app-bug label from being settled. The next check is the Fold command in `docs/review/fold-phone-checklist.md`.

## 7. `languageInferredRtlUsesTheSameEdgeDirectionAsReadium`

- **Classification:** flaky.
- **Test:** `NativeReaderTest.languageInferredRtlUsesTheSameEdgeDirectionAsReadium` (`NativeReaderTest.kt:170`). The book is Hebrew with no explicit progression, and the test requires Readium to infer right-to-left before the swipe.
- **Message:** `Timed out waiting for native swipe advances in book direction` at `NativeReaderTest.kt:195` on the three failures.
- **Where:** the same `checkDirectionalEdgeTurns` swipe as the previous test.
- **Why:** Three failures and one pass, with no code change between them. The pass shows the inferred right-to-left reader can be swiped on this emulator. The failures show that the injected drag does not do it every time.

## 8. `explicitLtrDirectionOverridesAnRtlLanguageForEdgeTurns`

- **Classification:** flaky.
- **Test:** `NativeReaderTest.explicitLtrDirectionOverridesAnRtlLanguageForEdgeTurns` (`NativeReaderTest.kt:176`). Arabic language with an explicit left-to-right progression.
- **Message:** `Timed out waiting for native swipe advances in book direction` at `NativeReaderTest.kt:195` on the one failure.
- **Where:** `checkDirectionalEdgeTurns(rightToLeft = false)`, so the drag runs from 80% of the width to 20% (line 194).
- **Why:** Failed once and passed twice in three runs. The passing runs got through the swipe and the edge-tap checks that follow it. The same assertion is therefore not a fixed property of this build.

## 9. `rtlImagePagesAcceptEdgeTapsOnArtwork`

- **Classification:** flaky.
- **Test:** `NativeReaderTest.rtlImagePagesAcceptEdgeTapsOnArtwork` (`NativeReaderTest.kt:240`), body `checkImagePageEdgeTurns(legacyOverlay = false)`.
- **Message:** `Timed out waiting for native swipe still advances an RTL image page` at `NativeReaderTest.kt:273` on the two failures.
- **Where:** the swipe is the last step (lines 272–275), after taps have moved to the next image page and back, and after an image link has been reported.
- **Why:** The failure line is after those tap waits, so a failing run has already turned pages by tapping artwork. One of three runs passed, including that final swipe. The image reader and the edge-tap code completed; the injected drag was the step that varied.

## 10. `rtlImagePagesWithInertKindlePanelOverlaysAcceptEdgeTaps`

- **Classification:** flaky.
- **Test:** `NativeReaderTest.rtlImagePagesWithInertKindlePanelOverlaysAcceptEdgeTaps` (`NativeReaderTest.kt:242`), body `checkImagePageEdgeTurns(legacyOverlay = true)`.
- **Message:** `Timed out waiting for native swipe still advances an RTL image page` at `NativeReaderTest.kt:273` on the one failure.
- **Where:** same final swipe as the artwork test. The extra overlay assertion at lines 250–251 passed whenever the run reached the swipe, including the failing run, because the timeout is line 273.
- **Why:** Passed twice and failed once. The Kindle-overlay fixture is not a stable failure. Together with test 9, both image-page swipe waits flipped during this session.

## What this session could not settle

- The original `r2fix6` JUnit XML is not in the repository, so the word-for-word assertion text of that run was not re-read. The backlog names the 10 tests. The messages in this document are from the reruns above. Those reruns failed the same four fold tests. Five swipe checks both failed and passed. `explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions` only failed.
- `explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions` is unresolved and needs a Fold run. It failed 5 of 5 times here, so it is not flaky. The shared swipe helper did pass for the Hebrew and left-to-right tests, and the recorded Fold cohort passed this method, so it is not a confirmed bug either.
- Taskbar `InputDispatcher` drops were captured, including during a passing test. This session did not turn the Taskbar off and rerun, and it did not repeat the older `UiAutomation` injection trial. Which individual pointer event was discarded on each failed swipe was not isolated.
- The other 63 tests in the 73-test run were not executed again. The full `NativeReaderTest` class was executed once: its seven non-swipe methods passed.
- This was not the owner's emulator process and not the owner's Fold. WebView versionCode differed (`694313738` here, `694313707` in the backlog's description of the task-owned emulator). The screen size, density, API level, gesture navigation, and "device state 0 only" list matched.
