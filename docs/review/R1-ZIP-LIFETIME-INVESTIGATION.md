# R1 — bounded ZIP-resource lifetime investigation

2026-10-28, Codex. **R1 remains open: observed once on baseline, not reproduced in this bounded investigation. No fix or candidate acceptance is claimed.**

Opus owns applying integration changes. This handoff changes no product or test source and does not edit Opus's integration branch, F3, R2 or BC-26.

## Revisions and fresh checks

| Item | Baseline | Frozen candidate |
|---|---|---|
| Exact revision tested | 87f282ede300ecf42f6f52db15fced2683696e70 | 6b092955d8293e93de59d3b7820f09ae41505e57 |
| Source snapshot | dc863ca250f13bc843ac00f1061edd61710752a605ea08922eac3948e6590f2a | 145b98757469bbb534ce43000e91a7f6df2a50b9e991fc4df1a6996c2af674e1 |
| Guarded build task | r1-baseline-06d9dca39156e422 | r1-candidate-06d9dca39156e422 |
| Unit tests | 248/248 pass | 262/262 pass |
| assembleDebug / lintDebug / lintQa | pass; zero lint errors | pass; zero lint errors |

The candidate rebuild is byte-identical to the previously verified APK: SHA-256 7c5b54c4bfa5514f3224cb24ae845abb585f3f176c668499e955b27dca83d243. The new R1 task directories preserve the earlier verification artifacts.

All device work used scripts/Test-Device.ps1 and com.bookcover.diagnostic.qa on task-owned read-only emulator-5612. Original matching fold configuration: API 36 / Android 16, 1768×2208, 420 dpi, font scale 1.0, LatinIME, navigation mode 0, advertised states 1/2/3. Captured WebView is com.android.webview 133.0.6943.137, versionCode 694313707, matching the original crash's Chromium build identifier. Fingerprint: Android/sdk_phone64_x86_64/emu64x:16/BE2A.250530.026.D1/13818094:userdebug/test-keys.

Repository build/resource helpers were retained. Each operation waited for an explicit helper release and acquired the supported gate. A task-private Gradle init file set offline resolution; no dependency was added. Only the task-owned emulator was stopped afterward. No owner-phone operation, Claude UI control, push, merge or release occurred.

## Original fatal event

Original evidence is under verify-baseline-06d9dca39156e422/receipts/baseline-fold beneath the external BookCoverBuild tasks directory. The original source was 87f282e.

| Original log event, 2026-10-27 EDT | Time |
|---|---|
| Previous fixed-layout ReaderTestActivity @459ae0 reports DESTROYED | 18:01:10.302 |
| fixedLayoutDisablesTypographyAndKeepsPublisherLayout finishes, passing | 18:01:10.328 |
| optionalEdgeTapsPreserveSwipesAndFollowLinksInsteadOfTurningPages starts | 18:01:10.333 |
| Chromium first uncaught-exception report | 18:01:10.354 |
| AndroidRuntime fatal exception | 18:01:10.460 |

The exception appears 52 ms after the preceding host's DESTROYED signal and 21 ms after the next test starts. The next test's thread dump is still in ActivityScenario.launchInternal / Instrumentation.waitForIdleSync, with no new ReaderTestActivity PRE_ON_CREATE in its log. It has not reached reader readiness or gesture injection. Its XML duration is 0.067 seconds.

The fatal chain is Chromium JniAndroid$UncaughtExceptionException, caused by IllegalStateException: zip file closed from ZipFile.ensureOpen/getInputStream → Readium FileZipContainer.Entry.stream/readRange on a coroutine worker. This establishes a read attempt against a closed Java ZIP handle. It does not identify the archive, closing caller, outstanding request or deletion timing.

The preceding sequence is selectionDismissalDoesNotTriggerAnEdgePageTurn → fixedLayoutDisablesTypographyAndKeepsPublisherLayout → optionalEdgeTapsPreserveSwipesAndFollowLinksInsteadOfTurningPages. All four previously retained Native13 runs used that ending order; only the original baseline run had this crash. Renderer termination messages also occur in non-crashing runs and do not establish emulator causation.

## Bounded fresh reproduction

The plan was fixed before execution: three guarded invocations per revision, using unchanged stock test methods and assertions. The observed order matched each selection.

| Revision / archive | Selection | Executed | Pass | Fail | Fatal ZIP signature |
|---|---|---:|---:|---:|---|
| Baseline / baseline-pair | fixed-layout → optional-edge | 2 | 1 | 1 | absent |
| Baseline / baseline-isolated-retry | optional-edge alone | 1 | 0 | 1 | absent |
| Baseline / baseline-sequence | selection-dismissal → fixed-layout → optional-edge | 3 | 2 | 1 | absent |
| Candidate / candidate-pair | fixed-layout → optional-edge | 2 | 1 | 1 | absent |
| Candidate / candidate-isolated | optional-edge alone | 1 | 0 | 1 | absent |
| Candidate / candidate-sequence | selection-dismissal → fixed-layout → optional-edge | 3 | 2 | 1 | absent |

**12 executed cases: six pass, six fail; zero JUnit errors/skips.** Each invocation's one failure is the identical pre-existing "Timed out waiting for a normal swipe advances a page" assertion at NativeReaderTest.kt:126. Every optional-edge run reaches a new activity's PRE_ON_CREATE. These are failed QA invocations, not passing reader acceptance.

All six actual invocations have distinct operation IDs and fresh XML within their receipt intervals. Source-before and independently recorded source-after hashes match the table above. Searches of their XML/log/text evidence found no closed-ZIP, Chromium uncaught-exception, Android fatal-exception or process-crashed signature.

One attempted baseline-isolated control was rejected by the resource transaction guard before a new operation began. The first external archiver copied the previous pair's receipt/XML; those attached stale files are explicitly marked invalid and excluded. Its corrected summary/classification records zero executed cases; original copied summaries are retained for audit. The external archiver was then tightened to require a fresh operation ID and fresh XML. No repository helper or product/test source changed.

## Source evidence and limits of attribution

The relevant EpubSession, ReadiumEngine, ReaderTestActivity and NativeReaderTest sources are identical between baseline and frozen candidate.

- NativeReaderTest closes its ActivityScenario after each method. ReaderTestActivity.onDestroy calls reader.close(), then super.onDestroy(), then deletes its unique generated fixture directory.
- EpubSession.close cancels its jobs, removes its lifecycle observer, attempts synchronous navigator removal and then closes the publication. It has no explicit barrier waiting for every handed-out resource stream to drain. A fragment-removal exception is swallowed by runCatching, but the original logs do not establish that such an exception happened.
- Normal MainActivity close/Library paths use the same EpubSession.close. Therefore a test-host-only cause is not established.
- Cached Readium source shows WebViewServer handing a lazy resource InputStream to Chromium. The adapter can later call Resource.read(range). FileZipContainer.close schedules archive.close on GlobalScope/Dispatchers.IO; Entry.stream calls archive.getInputStream. Entry.read catches ZipException and IOException, not the observed IllegalStateException.
- R2EpubPageFragment removes its listener in onDestroyView and destroys its WebView in onDetach. No request/stream-drain barrier was identified in the inspected path.

These facts support a plausible lazy-stream/publication-close lifetime hazard. Original timing is consistent with a late read around preceding-host teardown; it does not prove that the stream belonged to that host or that teardown caused the crash.

Fixture deletion follows session close. Deleting a file is not itself a Java ZipFile.close call, so deletion is not shown to be the sole cause. Its timing contribution remains unknown. Emulator behavior may affect timing, but an emulator-specific cause was not established.

The QA reader host directly opens its publication/session; it bypasses MainActivity's F3 open/handoff helper. This event does not demonstrate an F3 ownership-transfer defect or justify weakening its failure/cancellation cleanup. No F3 or reader correction is proposed without stronger evidence.

### Cached dependency provenance

No network lookup was used. Source archive: BookCoverBuild/readium-inspection/readium-source.zip, SHA-256 bc78c2cf11a3f79b00f351d9ac527346fc0190dc2831cef4f31322031634e2fc. Relevant extracted files byte-match their archive entries under kotlin-toolkit-3.4.0/readium:

| Source | Relevant lines |
|---|---|
| shared/.../util/data/InputStream.kt | 19–35, 88–106, 130–145 |
| navigator/.../epub/WebViewServer.kt | 167–178, 198–233 |
| shared/.../util/zip/FileZipContainer.kt | 78–93, 116–125, 157–165 |
| navigator/.../pager/R2EpubPageFragment.kt | 315–332 |
| shared/.../publication/Publication.kt | 173–176 |

The cached archive's remote provenance was not reverified. It supplies code-supported hypotheses, not proof of the original request/close identities.

## Evidence locations

Under %LOCALAPPDATA%\BookCoverBuild\tasks:

- r1-baseline-06d9dca39156e422/receipts: baseline-pair, baseline-isolated-retry, baseline-sequence; rejected baseline-isolated is excluded.
- r1-candidate-06d9dca39156e422/receipts: candidate-pair, candidate-isolated, candidate-sequence.
- Each real archive retains original device.json, device-results XML/logs, console.txt, summary.json, source-after-independent.json and r1-classification.json.
- r1-emulator-06d9dca39156e422/receipts: startup/configuration, baseline-webview-config, candidate-webview-config and task-owned shutdown record.
- Offline init configuration and the predeclared bounded plan are external, under the two task Gradle homes and %LOCALAPPDATA%\Temp\CodexR1ZipInvestigation respectively.

Failed receipts remain Failed and have not been promoted to sealed device passes. Before/after source equality is separately recorded; absence of a crash in this sample does not prove it cannot recur. The new runs do not reproduce the entire original 13-test history, a stress workload or a MainActivity close/reopen loop, and no in-flight request/archive identities were instrumented.

## Proposed wording for Opus's existing R1 item

> Codex bounded investigation, 2026-10-28: original baseline fatal observed once; not reproduced in 12 fresh stock cases (six on exact 87f282e, six on exact 6b09295) on matching fold emulator/WebView. Pair, isolated and original-ending-sequence runs each reach the known ordinary swipe timeout; all six failed QA receipts and the original fatal are retained. Original fatal begins 52 ms after the preceding fixed-layout host's DESTROYED signal, before the next reader activity is created. Source review identifies a plausible Readium lazy-stream/close hazard shared by normal app and QA session close, but original archive/request/closer identities and app-versus-fixture-versus-emulator causation remain unresolved. No F3 defect or justified correction established; no product/test patch, no fix claim. R1 stays open. Evidence: R1-ZIP-LIFETIME-INVESTIGATION.md and external r1-baseline/r1-candidate task receipts.

Suggested status: **Bounded investigation complete; original fatal unreproduced; residual lifetime risk unresolved.** Opus should apply this wording to the existing R1 row, preserving its original event and F3 cleanup requirement.

## R2 / BC-20 handoff boundary

At the final status read, integration HEAD remained 7d8cffc118dd1d810058c557f62093ed32ed8d93 with Opus's uncommitted EpubSession.kt and ReaderContinuityTest.kt R2 work. Those changes are not a supplied frozen candidate and were not edited or validated here.

BC-20 is no longer merely uncommitted WIP: Argon f99b298 and reviewed 0f7c4b5 are separate committed work. The review is not an ancestor of that integration HEAD. Their source changes are ReadingPace.kt / ReadingPaceTest.kt plus documentation, with no new Android QA or EpubSession wiring. Natural-reading usefulness remains pending. This corrects a stale handoff description, not an integration claim.

A new clean frozen candidate is required before the requested independent R2 / BC-26 / affected BC-20 and BC-22 validation. Existing focused selectors include ReaderContinuityTest.backgroundReturnAndRecreationRestoreTheSemanticPassage, FriendlyErrorActivityTest, ReadingNavigationTest and ScreenIdleTest. The build should include the reviewed 19 ReadingPaceTest cases and 14 InnerScreenAwakeTest cases when those implementations are integrated. Current Android pace checks cover rapid-jump null estimates and chapter progress, not a learned positive ETA or natural-reading usefulness.

**No new frozen candidate was supplied during this investigation. No fresh R2-fix, integrated BC-20 or post-fix BC-26/BC-22 acceptance is claimed. BC-26 remains closed; its existing cases are future regression guards. The candidate remains unaccepted and unapproved for installation.**
