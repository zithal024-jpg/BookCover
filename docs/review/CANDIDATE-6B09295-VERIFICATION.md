# Independent verification of candidate 6b09295

2026-10-27, Codex. **Identity/build and BC-26 verified. Candidate NOT accepted and NOT approved for installation.**

This is a verification handoff. No product or test source was changed. No main-app or owner-phone installation, push, release, Claude UI control, personal-book use or dependency addition was performed. Only the generated-fixture QA app was instrumented, through scripts/Test-Device.ps1.

## Exact build identity

| Item | Independently checked value |
|---|---|
| Candidate tested | 6b092955d8293e93de59d3b7820f09ae41505e57 |
| Candidate branch | claude/bookcover-consolidated-build-98bfcb |
| Baseline tested | 87f282ede300ecf42f6f52db15fced2683696e70 |
| Candidate source snapshot | 145b98757469bbb534ce43000e91a7f6df2a50b9e991fc4df1a6996c2af674e1 |
| Baseline source snapshot | dc863ca250f13bc843ac00f1061edd61710752a605ea08922eac3948e6590f2a |
| Supplied AND independently rebuilt APK SHA-256 | 7c5b54c4bfa5514f3224cb24ae845abb585f3f176c668499e955b27dca83d243 |
| Signing certificate SHA-256 | c4014c59942495ab82332093df8eae893655182ac31e7fe5bba84293205f3929 |

The supplied APK verifies with apksigner (v2 signature); its package is com.bookcover.diagnostic, version 0.4.0-prototype, code 6, without INTERNET permission. That main-app APK was inspected as a file, not installed. Instrumentation targeted com.bookcover.diagnostic.qa.

Baseline 87f282e and latest F3 fix db379d4 are ancestors of the candidate. Candidate app/build inputs match integration 092f6e8. Record commits 6ae955d and d7aa7cd modify only ACTIVE-BACKLOG.md; neither they nor this documentation commit are the tested APK source identity.

Fresh prescribed Windows PowerShell 5.1 guarded builds:

- Baseline, TaskId verify-baseline: assembleDebug, 248/248 unit tests, lintDebug and lintQa passed.
- Candidate, TaskId verify-candidate: assembleDebug, 262/262 unit tests, lintDebug and lintQa passed; zero lint errors, 28 debug / 32 QA warnings.
- Source fingerprints remained identical before and independently after every device invocation. Failed device receipts remain Failed; the independent after-run fingerprint does not turn them into sealed passes.

## Actual emulator configurations

| Configuration | Captured settings |
|---|---|
| Foldable, task-owned emulator-5612 | API 36; 1768×2208 at 420 dpi; font scale 1.0; LatinIME; navigation mode 0; supported states 1 CLOSED, 2 HALF_OPENED, 3 OPENED; no state 0 |
| Narrow disposable instance, task-owned emulator-5614 | API 36; physical 1600×2000, inherited override 720×1600, 320 dpi; font scale 2.0; LatinIME; navigation mode 0; only state 0 DEFAULT |
| Earlier second emulator, 127.0.0.1:5601 | API 36; 1600×2000 at 320 dpi; font scale 2.0; LatinIME; navigation mode 0; only state 0 DEFAULT. Its process ended during the fresh baseline attempt; that comparison is incomplete. |

The task-owned instances used read-only AVD sessions and software graphics. No AVD configuration or display override was edited. The narrow instance is not equivalent to the earlier 1600×2000 instance. One task-owned emulator was kept active at a time after host memory pressure was observed. Only the task's verified emulator PIDs were stopped; other agents' emulators were left alone.

## Fresh focused results

These are aggregates of separate guarded invocations, not a newly passing full application suite. The seven shared classes contain 39 cases; candidate ScreenIdle adds four and BC-26 adds two. Each fraction below is passed / executed, with zero skips and zero JUnit errors. Failures remain failures.

| Class | Baseline foldable | Candidate foldable | Baseline narrow | Candidate narrow |
|---|---:|---:|---:|---:|
| NativeReaderTest | 7/13 | 7/13 | 7/13 | 6/13 |
| BookSearchTest | 5/6 | 5/6 | 4/6 | 5/6 |
| ReaderBottomSpaceTest | 0/2 | 0/2 | 2/2 | 2/2 |
| ReaderContinuityTest | 0/3 | 0/3 | 0/3 | 0/3 |
| ReaderLifecycleTest | 3/4 | 3/4 | 2/4 | 3/4 |
| ReadingKeysAccessibilityTest | 3/6 | 3/6 | 6/6 | 6/6 |
| ReadingNavigationTest | 3/5 | 3/5 | 5/5 | 5/5 |
| ScreenIdleTest | N/A | 2/4 | N/A | 3/4 |
| FriendlyErrorActivityTest | not selected | 2/2 | 2/2 | 2/2 |

The original shared-class cohorts are baseline foldable 21/39 and narrow 26/39; candidate existing classes 21/39 and 27/39. Including ScreenIdle and BC-26, candidate results are 25/45 foldable and 32/45 narrow. These totals do not replace the additional/incomplete runs below.

### Failure dispositions

1. **Native input — matched failures; cause unresolved.** All six previously reported swipe/edge methods fail on both foldable sources. Five initial baseline failures match the candidate's swipe timeouts. The sixth, optionalEdgeTapsPreserveSwipesAndFollowLinksInsteadOfTurningPages, crashed the initial baseline process with Chromium JniAndroid / IllegalStateException: zip file closed, from Readium FileZipContainer.Entry.stream/readRange. Only 13 of the requested 39 cases ran in that attempt; the other 26 were subsequently run separately. A single isolated baseline rerun reaches the ordinary "a normal swipe advances a page" timeout, matching the candidate. The original crash remains unresolved evidence, not an emulator limitation or a passing rerun.

   The other five methods are explicitLtrDirectionOverridesAnRtlLanguageForEdgeTurns, explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions, languageInferredRtlUsesTheSameEdgeDirectionAsReadium, rtlImagePagesAcceptEdgeTapsOnArtwork and rtlImagePagesWithInertKindlePanelOverlaysAcceptEdgeTaps. System Taskbar routing drops occur in failing AND passing cases; they establish interference, not the cause of every assertion or a physical-Fold requirement. Assertions after the initial swipe failure remain unexercised.

   Narrow runs additionally fail comfortControlsChangeRealLayoutAndPreserveMidChapterPosition on both versions at line 423: wide.textWidth < before.textWidth. Padding and anchor checks before it pass; numeric widths are not logged, so the cause remains unresolved. Narrow LTR initially passed baseline and failed candidate. A bounded isolated pair after the same generated QA-library reset reproduces the identical LTR timeout on BOTH revisions. No reproducible candidate-only existing-case failure remained.

2. **Search — keyboard observation fails; timing varies.** Both revisions fail dismissingSearchPreservesVisibleAndSavedLocationAndInvalidatesResults while waiting to observe a TYPE_INPUT_METHOD accessibility window. Both narrow logs contain ImeTracker onShown; this does not establish that the keyboard failed to appear or that the emulator lacks IME support. No onShown match was found in these foldable logs; their cause remains unresolved. Foldable cold/warm search timing passes at baseline 2462/1417 ms and candidate 2004/1360 ms. Narrow baseline fails the cold target at 3140 ms (warm 1285); candidate passes at 1474/788. Uncontrolled OS caches and host load prevent attributing that difference to a code improvement.

3. **Bottom space — matched geometry failure on foldable; both pass narrow.** Both pageFillsTheOtherScreenToo and pageFillsTheScreenWithOneBottomMargin fail identically on foldable sources. Window 2208 minus navigation inset 147 gives a 2061 px safe bound; text-area bottom is 2084, last-line bottom 2068 and cropped-line count 0. Thus a possible 7 px ink overlap is not cleared by the zero-cropped count. Both cases pass on both narrow sources. Neither result establishes an emulator inset defect.

4. **Continuity/lifecycle — separate fold preconditions, fixture state and reader exit.** On the foldable, both folding continuity methods time out waiting for width change because their 0/3 driver requests absent state 0. Lifecycle's foldingAndUnfoldingKeepTheBookAtTheSamePlace fails at the same state-0 width wait on both sources. On narrow, the RTL folding case and lifecycle fold wait request absent state 3.

   backgroundReturnAndRecreationRestoreTheSemanticPassage never requests folding. On the foldable it fails "library after reader exit" on BOTH revisions; this remains unresolved acceptance. On narrow, it and tenFoldCyclesKeepOneReaderAndTheVisiblePassage initially stop at "rendered generated book". Logs load chapter1 while readiness requires paragraph-0 in chapter0. BottomSpace's deterministic fixture is content-identical; import deduplication preserves its saved position. After the existing BC-26 setup resets only generated QA data, the unmodified background/recreation case passes in isolation on BOTH sources (baseline 9.087 s, candidate 8.923 s). This supports fixture/order contamination and does not clear the foldable exit failure or the fold cases. Narrow baseline rotation initially fails its reopened-position wait while candidate passes; retain both observations without claiming a fix.

5. **Keys — matched configuration-dependent failures.** On foldable, controlsModifiersReflowAndTextSelectionDoNotTurnThePage, rtlArrowsFollowPhysicalDirectionWhilePageKeysFollowBookOrder and keyboardAndOptionalVolumeTurnsUseWholePressesAndPersistTheSetting all fail "Key down is captured by the reader" on both sources. Both narrow sources pass all six. Cause remains unresolved; this is not a general keyboard acceptance pass.

6. **Navigation — matched configuration-dependent failures.** Foldable nestedRtlContentsIdentifyAndRevealTheCurrentChapter and fragmentContentsResolveAfterEnteringAnotherResource expect TOC index 61 but observe 59, with identical geometry on both sources. Both narrow sources pass all five. No candidate-only failure was reproduced; the foldable assertions remain failed.

7. **ScreenIdle — new feature, acceptance incomplete (baseline N/A).** Foldable pauseReleasesAndResumeKeepsTheDeadline and alwaysAndTheSavedChoiceSurviveRecreation pass. onlyGenuineInputRenewsTheReadingScreen fails "A key renews"; its subsequent touch-renewal assertions are NOT reached. recreationAndFoldingKeepTheOriginalDeadline fails at absent state 0. Narrow input/key/touch renewal, pause/resume and Always/persistence cases pass; folding fails at absent state 3. Synthetic-clock expiry checks do not prove real elapsed-time screen-off, physical cover/sleep behavior or battery savings. Earlier BC-22 phone results remain evidence for that original source, not fresh physical validation of this candidate.

## BC-26: rendered errors and preservation

Both candidate configurations pass the two existing tests, with no new test patch:

- corruptEpubResultShowsUsefulErrorPreservesLibraryAndAllowsValidImport: generated EPUB truncated to 60 bytes, delivered through the registered OpenMultipleDocuments import-result contract/callback.
- nonBackupResultShowsUsefulErrorPreservesLibraryAndAllowsValidRestore: wrong-format generated JSON delivered through the registered OpenDocument restore-result contract/callback.

The expected complete rendered messages are:

> This file isn't a readable EPUB. It may be damaged, not fully downloaded, or a different kind of file. Try downloading it again. Your library is unchanged.

> That file isn't a BookCover backup. Choose a file made with Save backup. Your library is unchanged.

Assertions use visible Android accessibility nodes with nonempty bounds wholly inside both the window and physical screen, followed by idle/recheck. They reject exception/path/URI/source-name leaks and false success. For both failures, full in-memory and freshly loaded index records, locators/progression/bookmarks, primary/last-good index bytes and EPUB/cover/thumbnail bytes remain intact; no temporary import residue remains. Busy clears (and import progress clears). Valid import visibly succeeds and persists the intended third book; valid restore visibly succeeds and persists the intended collection change while preserving the protected data.

Both PNG outputs exist for each candidate configuration: corrupt-import-error.png and non-backup-restore-error.png. This terminal-only pass did not visually inspect their pixels. Picker return is simulated by result Intents; actual picker launch, DocumentsUI, SAF-provider and permission interaction were not exercised. No manual provider or owner-phone validation is claimed. Stored positions/bookmarks are checked, not actual subsequent reader reopening at every stored locator. S2's separate repeated/dismissed-error work is not reopened.

## Evidence ledger and scope

All paths below are beneath C:\Users\Frank\AppData\Local\BookCoverBuild\tasks.

| Task / receipts subdirectory | Executed | Failed | Status / limit |
|---|---:|---:|---|
| verify-baseline-06d9dca39156e422 / baseline-fold | 13 of 39 selected | 6 | Process crashed; remaining classes unrun in this invocation |
| same / baseline-fold-remaining | 26 | 12 | Complete, Failed |
| same / baseline-fold-optional-isolated | 1 | 1 | Complete, Failed; original crash retained |
| same / baseline-second | 4 of 39 selected | 3 | Original 5601 emulator ended; incomplete |
| same / baseline-second-owned-remaining | 26 | 7 | Complete, Failed |
| same / baseline-second-owned-native | 13 | 6 | Complete, Failed |
| same / baseline-second-friendly | 2 | 0 | Complete, Passed |
| same / baseline-second-isolated | 2 | 1 | LTR fails; background/recreation passes |
| verify-candidate-06d9dca39156e422 / candidate-second-owned-remaining | 30 | 6 | Complete, Failed |
| same / candidate-second-owned-native | 13 | 7 | Complete, Failed |
| same / candidate-second-friendly | 2 | 0 | Complete, Passed |
| same / candidate-second-isolated | 2 | 1 | LTR fails; background/recreation passes |
| same / candidate-fold-native | 13 | 6 | Complete, Failed |
| same / candidate-fold-remaining | 30 | 14 | Complete, Failed |
| same / candidate-fold-friendly | 2 | 0 | Complete, Passed |

Each archived invocation retains device.json, original XML/logs, console transcript, source-after-independent.json and a summary. Extra PNG files are in the relevant additional-output subtree. Archives preserve failed/incomplete runs before the helper's current outputs are overwritten. Relocated archives are historical run evidence, not claims that an overwritten live manifest remains current.

Candidate's FINAL live combined manifest is evidence\145b98757469bbb534ce43000e91a7f6df2a50b9e991fc4df1a6996c2af674e1-661f993b9c274e0c8db364bdd2e46db4.json under verify-candidate-06d9dca39156e422. Get-BookCoverPackageInputs revalidated it under Windows PowerShell 5.1 and BUILD: **262 unit tests + ONLY the final two BC-26 device cases**, matching source/APK and lint. It does not cover a passing 43- or 45-case reader suite. The validation result is receipts/focused-evidence-validation.json. Identity/signature and configuration records are under consolidated-verification-06d9dca39156e422/receipts.

The earlier Opus 106/127 pass, 20 fail and one skip report is historical. Its original full-run XML/receipt pair was not recoverable for independent audit; retained later outputs describe focused runs. Broad import/library/corpus/process-death pass claims from that report were not independently repeated here.

**Handoff decision:** no reproducible new regression was found in the compared existing cases, but shared failures, the baseline crash, ScreenIdle's failed/unreached assertions and physical sleep/cover/battery observations remain explicit gaps. No source correction was made or installation authorized. Only this documentation/evidence delta should be applied by the integration owner; do not merge the older local-main review branch or duplicate the already integrated BC-26 tests.
