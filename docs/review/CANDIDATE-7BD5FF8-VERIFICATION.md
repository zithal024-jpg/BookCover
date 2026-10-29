# Candidate 7bd5ff8 — independent verification

2026-10-29, Codex. **Candidate remains unaccepted and not approved for installation. R1 remains open.**

This verifies the handoff in `CANDIDATE-7BD5FF8-HANDOFF.md` at `474db75`, not the older candidate by assumption. Opus remains the integration editor. This record adds no product or test change and does not change the integration branch or shared tracker. Proposed corrections to existing records are below.

## Exact sources and fresh builds

| Item | Frozen candidate | Previous candidate | Installed-baseline source |
|---|---|---|---|
| Exact tested Git revision | `7bd5ff84344d6ceff512ebb344118bb527732c82` | `6b092955d8293e93de59d3b7820f09ae41505e57` | `87f282ede300ecf42f6f52db15fced2683696e70` |
| Source snapshot | `8a0ca51b67e55eda4d68fc1e9e97f861f43acec5c845a6aee9a1d64a23aa87c9` | `145b98757469bbb534ce43000e91a7f6df2a50b9e991fc4df1a6996c2af674e1` | `dc863ca250f13bc843ac00f1061edd61710752a605ea08922eac3948e6590f2a` |
| Guarded task key | `verify7b-candidate-06d9dca39156e422` | `verify7b-previous-06d9dca39156e422` | `verify7b-baseline-72081f2fe7a81c72` |
| Unit tests | 273/273 pass | 262/262 pass | 248/248 pass |
| assembleDebug / lintDebug / lintQa | pass / zero errors / zero errors | pass / zero errors / zero errors | pass / zero errors / zero errors |
| Lint warnings: debug / QA | 26 / 30 | 26 / 30 | 25 / 29 |

Fresh debug APK SHA-256:

- Candidate: `3f195138a3cce557cef43300f7d7163c24ec024feeb470248bb232c3a639d546`.
- Previous candidate: `7c5b54c4bfa5514f3224cb24ae845abb585f3f176c668499e955b27dca83d243`.
- Baseline: `17ea4c8fcd953fbcceb7dd31b9ba10e205f30bda62faf0fb016d92b19915e400`.

All three match their retained identities. The new candidate signature verifies; certificate SHA-256 is `c4014c59942495ab82332093df8eae893655182ac31e7fe5bba84293205f3929`. Its package is `com.bookcover.diagnostic`, versionCode 6, version `0.4.0-prototype`, with no INTERNET permission. The debug APK was never installed on a phone.

The candidate's 156 raw source inputs match the supplied snapshot. Initial checkout CRLF endings differed in the two R2 files; their content was verified equal and their bytes aligned to the frozen LF form before building/testing. No semantic source edit was made. Candidate inputs equal `ce845cf`; later integration commits through clean `474db75` are documentation only. Since `6b09295`, only EpubSession, the added ReaderContinuity test, ReadingPace and its unit test differ among product/test inputs. R2 matches `6b6c317`; BC-20 matches reviewed `0f7c4b5`. MainActivity, Library, F3 cleanup and BC-22 source remain unchanged.

Candidate units include **19 ReadingPaceTest and 14 InnerScreenAwakeTest passes**. The retained Opus lint reports have 28/32 warnings; the fresh reports omit exactly two dependency-version warnings in each variant (fragment-ktx and org.json). All other warning IDs/messages/locations match. No cause for that warning variation is claimed; no dependency was changed or fetched.

## Workflow and device identity

Windows PowerShell 5.1 ran the supported Build.ps1 and Test-Device.ps1 helpers, with task-private offline Gradle resolution, supported leases and explicit preceding release evidence. Every device invocation targeted only `com.bookcover.diagnostic.qa` on released **emulator-5584 / BookCoverInnerQA**. The main app was never instrumented, uninstalled or cleared. No owner-phone operation, Claude UI control, push, merge or release occurred. Only generated disposable fixtures were used. The existing emulator was borrowed under the helper gate and was not stopped.

Matched pre/post configuration: Android 16/API 36; 1768×2208; 420 dpi; font scale 1.0; LatinIME; gesture navigation mode 2; WebView `133.0.6943.137` / `694313707`; only device state 0. Fingerprint: `Android/sdk_phone64_x86_64/emu64x:16/BE2A.250530.026.D1/13818094:userdebug/test-keys`. Unsupported fold states explain only checks which actually request them.

## Fresh run ledger

Archives are under `%LOCALAPPDATA%\BookCoverBuild\tasks\<task key>\receipts\<archive>`.

| Revision | Archive | Actual cases | Passed | Failed | Unrun |
|---|---|---:|---:|---:|---:|
| 7bd5ff8 | candidate-corpus1 | 28 | 28 | 0 | 0 |
| 87f282e | baseline87-corpus1 | 28 | 28 | 0 | 0 |
| 6b09295 | previous6b-corpus1 | 10 | 9 | 1 fatal | 18 |
| 7bd5ff8 | candidate-reader | 23 | 14 | 9 assertions | 0 |
| 6b09295 | previous6b-reader | 22 | 13 | 9 assertions | 0 |
| 7bd5ff8 | candidate-bc26-guards | 13 | 13 | 0 | 0 |

All actual cases have zero skips/JUnit errors. These are separate focused invocations, not a full-suite pass. The old corpus process crash left 18 parameters unexecuted; they are not counted as passes. No further corpus repeat was needed after the pre-R2/BC-20 source reproduced R1.

| Archive | Operation ID | UTC interval, 2026-10-29 |
|---|---|---|
| candidate-corpus1 | 12869133f3e44ab599740b91a36e8878 | 11:24:57–11:27:20 |
| candidate-reader | d24ae39302964ddbac57733fb9f9bb80 | 11:27:41–11:33:23 |
| baseline87-corpus1 | 9be0b98996034403b9dfdf269e648dd5 | 11:35:26–11:37:48 |
| previous6b-corpus1 | 17307a3ced8b498c882889ad65bdba6a | 11:40:21–11:41:59 |
| previous6b-reader | 4063c252ca76446fba23e64cac1db229 | 11:42:43–11:48:19 |
| candidate-bc26-guards | 216ba4d0562248329f924e769323aa8e | 11:48:34–11:49:23 |

Each archive retains its receipt, fresh XML, console, available per-test logcat/textproto records and an independent post-run source snapshot. Actual source-before/independent-after hashes match the revision table. Failed receipts correctly remain Failed and have a null receipt SourceAfterId; the independent snapshot is separate evidence. No failed invocation was promoted to passing package evidence. Metadata captures retain full buffers, which also contain earlier events; attribution uses the fresh per-test timestamps/PIDs.

## R1 disposition: reproduced on the previous candidate; still open

Unchanged stock EpubCorpusTest has 28 parameters and uses actual MainActivity, including recreation. It closes the scenario, deletes generated cache fixtures, and clears the QA library before the next parameter. This covers a different lifecycle from the earlier bounded NativeReader-only investigation in `f87ae94` (applied by Opus as `d4ba329`). That earlier evidence remains valid for its original revisions and cases.

On fresh **6b09295**, `corpusBook[mathml]` was recorded as a **0.072-second failed case** receiving Chromium JniAndroid's fatal exception. Its failure text is byte-identical to both retained candidate2 corpus crashes and the original baseline NativeReader fatal: IllegalStateException `zip file closed`, `ZipFile.ensureOpen/getInputStream` → Readium `FileZipContainer.Entry.stream/readRange`.

Fresh local chronology (EDT):

| Event | Time |
|---|---|
| Previous embedded-font case's recreated MainActivity @5e3a759 DESTROYED | 07:41:47.024 |
| Previous test finishes | .033 |
| MathML test starts | .047 |
| First Chromium uncaught-exception report | .072 |
| AndroidRuntime fatal, QA PID 7423 | .132 |

The first exception is **48 ms after the previous host's DESTROYED signal** and 25 ms after the next test starts. No MathML MainActivity was created; its test thread was still resolving ActivityScenario at EpubCorpusTest.kt:67. The named parameter therefore does not identify the responsible book. This repeats the original baseline's teardown-adjacent pattern (52 ms), but the archive, request URI, closing caller and actual cause are still unidentified. It does not prove fixture deletion, a font request, F3 or emulator causation.

Classification:

- **Previous-build reproduced:** the corpus fatal signature occurs on exact 6b09295, before R2/BC-20. It is not a signature introduced exclusively by 7bd5ff8's changes.
- **Installed-baseline source:** this fresh 87f282e corpus run passed 28/28. Its historical NativeReader fatal is separate valid evidence; a fresh baseline corpus reproduction is not claimed.
- **Candidate:** this fresh 7bd5ff8 corpus run passed 28/28. Opus's two retained exact-source corpus crashes remain valid. The passing rerun does not establish a fix or compare crash frequency.
- **Unresolved:** common cause, normal-app exposure, corrective change and residual lifetime risk. R1 remains open; preserve F3 failure/cancellation cleanup. No product/test correction is proposed by this verification.

## Reader / BC-20 / BC-22 comparison

The 22 shared cases have the same nine failed methods at the same assertions on 6b09295 and 7bd5ff8. No candidate-only failure was found in these compared cases; this is not a waiver of the shared failures or proof for untested flows. Baseline 87f282e was freshly compared for corpus, not this reader cohort.

| Shared failed cases | Exact failure / acceptance not reached |
|---|---|
| Continuity RTL manga and ten-fold | First display-width-change wait. Postfold passage/retention assertions not reached. |
| Continuity background/recreation | Background and recreation pass; global Back then times out at “library after reader exit,” before explicit reopen. |
| Lifecycle folding | First state-3 display-width wait. Postfold position not checked. |
| Search dismissal | Fails to observe TYPE_INPUT_METHOD accessibility window before deadline. Dismissal preservation/invalidation checks not reached. |
| Nested RTL contents and cross-resource fragment | Initial section-61 move resolves section 59. Contents/cross-resource assertions not reached. |
| Idle genuine input | “A key renews.” Programmatic no-renewal and synthetic expiry passed; subsequent touch assertions not reached. |
| Idle recreation/folding | Recreation/no-renewal passed; first state-3 display-width wait fails, before postfold/deadline checks. |

Per-class candidate passes: Continuity 1/4; Lifecycle 3/4; Search 5/6; Navigation 3/5; Idle 2/4. Previous candidate: identical results except Continuity 0/3, because the new resize case is absent. The new candidate resize case passes app Back/reopen and settled rendered-passage assertions; it does not assert that an actual viewport resize occurred and supplies no emulator before/after proof. Neither reader invocation has an R1 fatal signature.

Passing checks also include immediate position save, rotation, foreground reading-time credit, search recreation/book change, LTR contents, chapter progress, bookmarks and Always/saved-idle choice. BC-20 Android checks still do not establish a learned positive/rendered ETA or natural-reading usefulness. Synthetic unit/screen-clock tests do not establish real sleep, cover interaction or battery savings.

## BC-26 regression guards: 13/13 on exact candidate

FriendlyErrorActivityTest passes both actual registered result routes: corrupt generated EPUB import and non-backup restore. Android rendered-node assertions require the complete useful error text, nonempty visible bounds inside the physical screen, idle/recheck, no raw exception/path/source/success wording, and unchanged records, index/backup bytes, EPUB/cover/thumbnail assets, stored positions and bookmarks. Busy/progress clears and subsequent valid import/restore operations succeed. Existing picker recreation (4), backup safety (6) and batch import (1) guards also pass, in addition to the two visible-error cases.

These use generated fixtures and result Intents through registered contracts/callbacks. No manual DocumentsUI/SAF-provider walkthrough, personal-book reopening, owner-phone check or fresh screenshot pixel inspection was performed. BC-26 remains closed; these are regression checks, not reopened scope.

The final combined manifest was independently validated with Get-BookCoverPackageInputs in Windows PowerShell 5.1:

`verify7b-candidate-06d9dca39156e422/evidence/8a0ca51b67e55eda4d68fc1e9e97f861f43acec5c845a6aee9a1d64a23aa87c9-e872fb6de4a04ec8889c1f84174b96dc.json`

It covers 273 passing units and **only the final 13 device cases**, not the earlier corpus or failed reader cohorts. Separate archived receipts/logs preserve those results.

## Proposed existing-record corrections for Opus

No shared tracker or handoff text was overwritten. Apply these within the existing records:

1. **R1 status:** “Open; fresh closed-ZIP corpus reproduction on pre-R2/BC-20 6b09295; fresh 7bd5ff8 and 87f282e corpus runs passed. Resource-lifetime cause/correction unresolved.” Append the R1 comparison and evidence above, retaining the original baseline event and both candidate corpus crashes. Narrow the dependency claim to the signature predating R2/BC-20; do not infer all causes from it.
2. **R2 status:** “Included in frozen 7bd5ff8; independent emulator comparison recorded, with shared failures retained. Owner real-book reopen and candidate acceptance pending.” Preserve 44/44 owner-Fold QA on **6b6c317 only**; it is not exact combined-candidate phone evidence. The stale “not yet in a frozen candidate” phrase should be removed.
3. **Handoff remaining-run arithmetic:** 52 recorded cases = **35 passed, 16 failed, one skipped; 51 executed**, not 36 passed plus a skip. Use “Recorded cases” for the column if it includes the skipped record.
4. **ReadingLayout skip:** its CSS viewport did not exceed the line-length cap. The code precondition does not inherently require physical hardware.
5. **Failure descriptions:** keyboard evidence is an accessibility-window observation timeout, not proof the keyboard never appeared. A previously known failure category alone does not establish a baseline match; the nine reader matches in this report now have fresh 6b09295 controls.

Remaining blockers/gaps: R1 resource-lifetime risk; failed/unreached reader acceptance checks above; exact combined-candidate physical validation and owner real-book reopen; BC-20 natural-reading/positive-ETA validation; BC-22 real elapsed sleep, cover and battery observations. Opus's earlier 44/44 Fold result and all 6b09295 evidence remain tied to their original sources. **No installation approval or candidate acceptance follows from this report.**
