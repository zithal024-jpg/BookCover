# Combined candidate 7bd5ff8: handoff for independent verification

2026-10-29, Opus (integration owner). **Prepared and frozen for Codex verification. NOT accepted, NOT approved for installation.** No owner-phone install, push, merge or release.

## Exact identity

| Item | Value |
|---|---|
| Frozen commit (built) | `7bd5ff84344d6ceff512ebb344118bb527732c82` on `claude/bookcover-consolidated-build-98bfcb` |
| Product/test inputs identical to | `ce845cf4e42030bef01a523eef62887bda675de9` (`7bd5ff8` adds only an ACTIVE-BACKLOG.md edit) |
| Source snapshot | `8a0ca51b67e55eda4d68fc1e9e97f861f43acec5c845a6aee9a1d64a23aa87c9` |
| Guarded build | `Build.ps1 -TaskId candidate2`, task `candidate2-4b1bfccc9643b36b` |
| APK | `output\BookCover-candidate2-4b1bfccc9643b36b.apk`, SHA-256 `3f195138a3cce557cef43300f7d7163c24ec024feeb470248bb232c3a639d546` |
| Package / version | `com.bookcover.diagnostic`, `0.4.0-prototype`, versionCode 6 (unchanged); no INTERNET permission |
| Signing certificate SHA-256 | `c4014c59942495ab82332093df8eae893655182ac31e7fe5bba84293205f3929` (same as installed line and `6b09295`, so update-compatible) |
| Previous candidate | `6b09295` and its evidence stay tied to that revision; nothing here re-labels it |

Later commits on the branch (this handoff, the R1 doc `f87ae94` applied as a cherry-pick, backlog records) are documentation only and are not part of the build identity.

## What changed since 6b09295

1. **R2 fix** (`0f7728b`, `ef0d3a2`, `6b6c317`, `EpubSession.kt` only): a reopened book (one with a saved locator) holds its opened passage across reader size changes from non-zero size until it navigates or reports any other passage; a held raw Readium locator is re-described from the visible page after restore. New test `ReaderContinuityTest.systemBarResizeDuringReopenPlacementKeepsTheSavedPassage`. Cause, rejected first versions and Fold evidence: ACTIVE-BACKLOG R2 row.
2. **BC-20 calibration** from PR #4 head `0f7c4b5`: cherry-picked `f99b298` → `beabced` and `0f7c4b5` → `ce845cf` (`ReadingPace.kt`, `ReadingPaceTest.kt`, BC-20 docs). The PR's older local-main commits were already ancestors; no merge of the PR branch, no duplicate BC-26/F2/BC-28 change. `EpubSession` pace calls unchanged.

S2, F1/F4, F2, F3 (`session = current` first in `commit`), BC-26, Streak and BC-22 code are untouched apart from the R2 lines in `EpubSession.kt`.

## Results on the frozen source

**Build:** assembleDebug, **273/273 unit tests**, lintDebug and lintQa passed; zero lint errors (28 debug / 32 QA warnings).

**Emulator** (task-owned BookCoverInnerQA: API 36, 1768×2208, 420 dpi, gesture navigation, software GPU; `emulator-5620`, restarted as `emulator-5584` after an adb-server restart; archives under `tasks\candidate2-4b1bfccc9643b36b\archive\`):

| Archive | Executed | Passed | Notes |
|---|---:|---:|---|
| `emu5620-full` | 53 | 51 | Process crashed in `EpubCorpusTest.corpusBook[footnotes]` (R1 signature); remaining classes unrun. `BookSearchTest.dismissingSearch…` soft-keyboard observation failed |
| `emu5620-corpus-rerun` | 12 | 11 | `corpusBook[scripts-and-forms]` crashed with the same R1 signature |
| `emu5620-remaining` (13 classes) | 52 | 36 (+1 skip) | Known failures only (below). `ReadingLayoutTest` skipped (needs physical unfold). `ScreenIdleTest` recorded only 1 case |
| `emu-continuity-screenidle-r3` (fresh QA data) | 8 | 3 | New R2 reopen-resize test **passed**; others are known emulator dispositions |

Failures matching Codex's recorded 6b09295 emulator dispositions (no new kind seen): NativeReader swipe/edge timeouts (5); ReaderContinuity "display width changed" (absent fold state), "library after reader exit" (emulator does not deliver GLOBAL_ACTION_BACK), "rendered generated book" in long runs (shared generated fixture saved in chapter 1); ReaderLifecycle fold state 3 and rotation; ReadingKeys "Key down is captured by the reader" (3); ReadingNavigation TOC 61 vs 59 (2); ScreenIdle "A key renews" and fold state.

**Owner's Fold** (isolated QA app only, owner-approved, 2026-10-28) on `6b6c317`, which equals this candidate except for the BC-20 `ReadingPace` commits: **44/44** across ReaderContinuity 4/4 (including the R2 test `backgroundReturnAndRecreationRestoreTheSemanticPassage`), ReaderLifecycle 4/4, BookSearch 6/6, ScreenIdle 4/4, NativeReader 13/13, ReadingKeysAccessibility 6/6, ReadingNavigation 5/5, ReaderBottomSpace 2/2 (archives `tasks\r2fix-4b1bfccc9643b36b\archive\fold-r2fix4-*`). Not repeated on the exact candidate APK.

## Unresolved, explicitly

1. **R1 closed-ZIP fatal reproduced twice on this candidate** (`EpubCorpusTest`, two different corpus books, same `FileZipContainer.Entry.stream` → `ZipFile.ensureOpen` chain). Codex's bounded investigation (`R1-ZIP-LIFETIME-INVESTIGATION.md`) did not reproduce it in 12 NativeReader cases. Whether `EpubCorpusTest` also crashes on baseline `87f282e` / `6b09295` on the same configuration is **not established**; until it is, classify as unresolved, not candidate-only and not baseline-only.
2. R2 is verified on the Fold for `6b6c317` only; the new emulator test passes with and without the fix on emulators (not a before/after proof).
3. Owner checks still pending: reopen-after-Back on a real book, BC-20 natural-reading usefulness, BC-22 real sleep/cover/battery, and whole-app check after any approved install.

## Requested independent verification (Codex)

On exactly `7bd5ff8` (or any later docs-only commit; check the snapshot equals `8a0ca51b…`): rebuild and compare the APK hash and certificate; run R2 continuity (`ReaderContinuityTest`), BC-26 (`FriendlyErrorActivityTest` plus backup/import guards), BC-20 (`ReadingPaceTest` in units, `ReadingNavigationTest`), BC-22 (`ScreenIdleTest`); and, for R1 classification, `EpubCorpusTest` on candidate and baseline in the same configuration. Classify each failure as baseline-reproduced, candidate-only or unresolved.

## Corrections (2026-10-29, after Codex's verification and the RT work)

The text above is kept as first written. These corrections apply:

1. `emu5620-remaining` recorded 52 cases: **35 passed, 16 failed, 1 skipped (51 executed)**, not "36 (+1 skip)".
2. `ReadingLayoutTest` skipped because its CSS viewport did not exceed the line-length cap. That precondition does not inherently require physical hardware.
3. "Soft-keyboard observation failed" was an accessibility-window observation timeout, not proof that no keyboard appeared. "Emulator does not deliver GLOBAL_ACTION_BACK" was wrong as a cause: Android's one-time full-screen confirmation held input focus on that emulator (ACTIVE-BACKLOG RT row, G1).
4. Matching a previously recorded failure category did not by itself establish a baseline match. Codex's fresh `6b09295` controls now establish the nine shared reader failures; their causes and dispositions are in the RT row.
