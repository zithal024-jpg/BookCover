# BookCover active backlog

This is the **single active work queue** for the owner, Opus and Codex. The
[BC completion tracker](EXPERT-PANEL-REVIEW.md#completion-tracker) retains detailed
implementation evidence/history; its existing summaries are not a second assignment
queue. Reconciled against `main` at `5eed90b` on 2026-10-27 and the owner updates
below. This document records work, not release readiness.

## Working agreement

- **Record new review findings here FIRST, before drafting or distributing
  implementation prompts.** Reuse overlapping tasks; preserve BC IDs and review
  labels. Keep one row per task with priority/status, actual owner when known,
  scope/acceptance, dependency and evidence.
- Agents independently choose authorized work and update their relevant row on a
  material status change, blocker or completion. No routine PM grants. A sent
  prompt is a proposed assignment until work is confirmed. Check shared files and
  resources; preserve concurrent changes under [CLAUDE.md](../../CLAUDE.md).
- Separate implementation from validation and code-supported risks from reproduced
  defects. Record completion commit and exact checks/limits with the work; retain
  detailed original-BC evidence in its existing completion row. Move finished queue
  rows to the brief completed section or link their evidence there. No daily logs
  or duplicate live statuses elsewhere.
- `Now`, `Next`, `Later` order this queue; original P1/P2/P3 and review severity stay
  visible. Dependencies and actual conflicts take precedence. Device validation
  uses isolated QA through the supported helper, required resource release evidence
  and owner availability.

## Active queue

F1/F4 and S2 can proceed in parallel in separate data/activity and Library UI files.
F3 and F2 follow F1/F4 to avoid competing edits. Unassigned work can proceed
independently where its dependencies and shared files permit.

| ID / source label | Priority / severity | Status | Owner | Scope and acceptance | Dependency | Evidence / source |
| --- | --- | --- | --- | --- | --- | --- |
| **F1 / F4** | Now; medium F1, low F4 | Implemented; focused QA passed 2026-10-27 | Codex | `db27ced`: successful load required; consistent backup bytes prepared before destination open; valid empty backups retained; restore readiness rechecked before merge/save. Picker routing and pending cover identity survive recreation; missing targets refuse. Counted work preserves busy state and meaningful results through overlap/cancellation/errors. | Original F1/F4 code risks at `5eed90b` confirmed in current code. No reader/UI redesign; Opus and h3 preserved. | Seven regression failures demonstrated before the guard; final `Build.ps1 -TaskId f1f4`: **217 unit tests passed**, `assembleDebug`, `lintDebug`, `lintQa` passed. `Test-Device.ps1 -TaskId f1f4 -Serial emulator-5592`: **10/10 passed** (`BookLibraryBackupSafetyTest`, `BackupPickerRecreationTest`), disposable QA files only; readiness/preparation failures preserve destination bytes. One initial test delivered its result before recreation; corrected timing passed. Controlled recreation with original request-code dispatch, **not** a real open-picker process kill or memory-pressure test. SAF providers untested; write failure after open can still leave a partial file (no atomic-replacement claim). Source-bound receipts: task `f1f4-64b5cc9ab46fe34a`, snapshot `95460fd2ed75bd0df997766483cf01afde43eadc097917e3e35a413f109ff01a`. |
| **S2 / UX §1** (BC-23 follow-up) | Now; substantial | Active; owner confirmed 17:16 UTC | Opus | One Library Home redesign: Continue near top, saved-position reassurance within it, compact count/sort, compact Streak summary opening existing details. Preserve search, filters, all sorts and data. At 360 × 800 dp / 100% system text, Continue plus one cover and complete title fit without scrolling. At 200%, allow scrolling and retain readable actions. Check matching Library states, narrow/wide and light/dark. | Keep existing S2 task; avoid concurrent Library UI edits. | UX images 01–03; proposed acceptance, not results. Existing [S2 brief](PRODUCT-PRIORITIES-2026-10-26.md#s2-library-home-that-gets-the-reader-back-into-the-book). Aesthetic release quality is the owner's goal; calm editorial styling was a suggestion, not a confirmed choice. |
| **F3** | Next; low | Implemented; unit validation passed, installed and owner-checked 2026-10-27 | Argon | `5b083e3`: `openBookWithCleanup` (`BookOpenLifecycle.kt`) and `ReadiumEngine.open` close the created `EpubSession` (including its `readingLifecycle` observer, `BookSearchController` and `Publication`) or the pre-session `Publication` on any `Throwable` / `CancellationException` before ownership transfers to `MainActivity.session`, including cancellation as `engine.open` returns across `withContext(Dispatchers.IO)` and failure/cancellation during `library.markOpened`. Preserves Codex's F1/F4 `OperationState` / `publishInitialMessage` changes and transferred sessions. Review follow-up `db379d4` (Opus): `session = current` is now the first statement of the `commit` block, so a later failure there cannot leave a transferred session unowned. | After F1/F4; shares `MainActivity.kt`. | `Build.ps1 -TaskId f3 -Owner Argon`: **245/245 unit tests passed** (`BookOpenLifecycleTest` 7/7 covering IO-to-Main dispatcher return cancellation, synchronous open cancellation, artwork failure, `markOpened` failure/cancellation, successful handoff, and post-transfer failure preservation), `assembleDebug`, `lintDebug`, `lintQa` passed. Task `f3-64b5cc9ab46fe34a`, source snapshot `273f9e56565002f858b54de6e8e3d97f5c7bbd860c5322e4208294864d23d86d`, evidence manifest `273f9e56565002f858b54de6e8e3d97f5c7bbd860c5322e4208294864d23d86d-d0c3ba621781415fb84987cd4db2cf56.json`. After `db379d4`: `Build.ps1 -TaskId argon-review` passed 2026-10-27 (**245/245 unit tests**, `BookOpenLifecycleTest` 7/7, `assembleDebug`, `lintDebug`, `lintQa`); task `argon-review-00f663db4a626e95`, source snapshot `6fd5f2ba7d04eba3af1677d8a3b375e20eff7e93607df5b2828aa9741162cd83`, evidence manifest `6fd5f2ba7d04eba3af1677d8a3b375e20eff7e93607df5b2828aa9741162cd83-5711e4978c744067a3c9595973d71ddf.json`. Installed in place 2026-10-27 14:51 with `adb install -r` on the owner's Fold (`RFGL710TARJ`), APK SHA-256 `769bc7cc875cc2d4bdfc620b81539545b935bb53ba51cf8c17ef18ab7726c622` matching the build receipt; the owner then reported normal open, book switch, rotate/fold and interrupted-open checks all clear. No isolated device QA run for F3. |
| **F2** | Next; low | Implemented; actual process-death QA follow-up active 2026-10-27 | Codex | `ea697d2`: synced ownership receipt before exclusively creating previously absent assets; completed/synced files before index save. Caught failure/cancellation cleans only proven owned assets; post-load recovery protects raw IDs in primary, backup, pending and damaged indexes. Lazy thumbnail writes respect the ownership guard; active operations, successful/duplicate imports and BC-27 temporary-copy cleanup are preserved. | F1/F4 `db27ced` / evidence `e2fdcf5` preserved. F3 and Library UI excluded from F2 commits. | **Before fix: 2/2 focused QA tests failed**, leaving EPUB/cover/thumbnail after forced index persistence IOException, immediately and after reload; existing index/files/position/bookmark checks passed. Red receipt/XML: task `f2-64b5cc9ab46fe34a`, `receipts/red-*`. **Final clean `ea697d2` checkout:** `Build.ps1 -TaskId f2-validation`: **238/238 unit tests** (21 import-finalization), `assembleDebug`, `lintDebug`, `lintQa` passed. `Test-Device.ps1 -TaskId f2-validation -Serial emulator-5592`: **33/33 passed**, zero skips (`BookImportFinalizationTest` 9, `BookImportBatchTest` 6, `BookLibraryTest` 11, `BookLibraryBackupSafetyTest` 7). Generated disposable QA fixtures verify existing data unchanged, failure/reload cleanup, duplicates/concurrency, mixed batches, active temp/asset ownership and thumbnail coordination. Task `f2-validation-aa702cc017097fcb`, source snapshot `b413aabd0bd00f0f48224d3c4ef11e924ea5bd467c85b70ae5c92c361155f3f3`; matching successful build/device receipts. Shared checkout's 245-test run retained one unrelated F3 lifecycle failure; its earlier 33-test QA pass was invalidated by a concurrent source edit and is not final evidence. Interruptions use synthetic restart residue / injected `Error`, not actual process kill; no power-loss, SAF-provider or physical-phone claim. Legacy unmarked files and ambiguous recovery evidence are retained; conflicting reimports are refused rather than overwriting them. Actual process-death follow-up requested 2026-10-27: real import blocked after final assets, SIGKILL only the dedicated QA child process, then fresh-process recovery; original evidence retained pending this check. |
| **UX §2** | Now to verify; conditional substantial | Verification pending; correction conditional | — | Compare fixed-pixel reflowable publisher text, relative-size text and generated sample with publisher styles on/off. Observe body enlargement, passage and existing bookmarks. If exception persists, explain recovery beside toggle; otherwise correct docs and close without product changes. Check narrow/wide, Paper/Night and 100%/200% system text. | Resolve old H3 uncertainty before related reader edits; avoid concurrent changes. | UI says “Text size still applies”; brief records a fixed-pixel exception. Screenshots do **not** reproduce failed resizing. Completed C1 latency fix stays closed. |
| **UX validation** (BC-10 follow-up) | Next; validation | Pending after changed surfaces settle | — | One consolidated TalkBack, 200% text, actual touch-target and contrast pass: resume/open, enlarge text, select search result, dismiss sheet, inspect Streak. Record measurements/failures before correction tasks. Targets: 48 × 48 dp; 4.5:1 small/regular text, 3:1 qualifying large text. | After S2 and any verified §2 correction; include other changed controls once. | UX acceptance targets, **not measured BookCover failures**; prior QA/formal-audit limits remain in BC-10/23/29. |
| **BC-22 / S1** | Next; original P2 | Open; implementation/measurement pending | — | Configurable inner-screen idle timeout; meaningful activity keeps reading awake. Record 60-minute battery drain and conditions. Preserve cover Stop/exit/timer behavior. Reconcile S1's proposed timer-semantics change before altering the confirmed countdown. | Reader/cover files; resolve H3 uncertainty before related edits. Measurements need owner availability. | [Original acceptance](EXPERT-PANEL-REVIEW.md#prioritized-issue-register), [S1 proposal](PRODUCT-PRIORITIES-2026-10-26.md#s1-session-aware-screen-timeout-and-cover-timer). At `5eed90b`, `setReadingWindow` keeps screen on for whole open-book session. No battery figure verified. |
| **BC-26** | Next; original P3 | Implementation done; acceptance unreconciled | — | Reconcile existing QA with visible friendly errors for corrupt EPUB import and non-backup restore. Add only missing isolated checks; preserve data and keep raw exception details out of messages. Record exact evidence or remaining gap. | F1/F4 for backup behavior; existing mapper stays in place. | [Completion evidence](EXPERT-PANEL-REVIEW.md#completion-tracker): mapper/wiring + nine JVM tests done (`e3826ed`); phone check recorded pending. Corpus rejection/logging alone does not prove displayed messages. |
| **BC-15** | Later; original P2 | Open; state extraction remains | — | Move remaining activity-owned state/logic into a testable holder/repository in bounded steps; preserve session, passage, cover and Streak behavior. Test concurrent operation logic; reuse F1/F4 rather than fixing twice. | After F1/F4 and F3 stabilize `MainActivity.kt`; resolve H3 uncertainty for reader files. | [Original scope/evidence](EXPERT-PANEL-REVIEW.md#completion-tracker). Activity still owns session/busy/message at `5eed90b`. Completed BC-07 continuity is not reopened by maintenance. |
| **BC-19 / S3** | Later; original P2 | Open enhancement; observation before implementation priority | — | Observe sustained long-real-book reading at preferred size/margins; use results to rank a spread option. If pursued, retain folded single-page reading, safe hinge layout and passage continuity. Do not force two pages to fill width. | Owner observation; H3 uncertainty before reader edits. | [Original spread scope](EXPERT-PANEL-REVIEW.md#prioritized-issue-register), UX “Later”; `ColumnCount.ONE` at `5eed90b`. Wide screenshots alone do not establish harm. |
| **BC-20** (calibration only) | Later; original P2 | Estimator calibrated; natural-reading owner validation pending | Argon | Calibrated `ReadingPace.kt` (Winsorized recency-weighted rate, `1e-6` float-jitter guard, `0.0002` min step, `1%/s` ceiling, multi-page skim-burst filter, backward-turn retrace high-water mark, `< 0.5 min` -> `0`, `> 600 min` -> `null`). Observe reading-speed/time-left estimates during natural reading; record usefulness or needed calibration, conditions and owner feedback. Contents, chapter progress and named bookmarks stay complete. | Owner reading observation; reader wiring untouched (`EpubSession.kt` untouched). | [BC-20 evidence](../BC-20-READING-NAVIGATION.md#validation-record): core owner-confirmed; `Build.ps1 -TaskId bc20-pace -Owner Argon` passed (**257/257 unit tests**, `ReadingPaceTest` 17/17 including side-by-side legacy vs. calibrated synthetic benchmarks, `assembleDebug`, `lintDebug`, `lintQa` zero errors); task `bc20-pace-8a60708d62ea442a`, snapshot `8304137d8a27ce5a99fa02a57753bacd9b2bb8820067962d950d8e1ab0ec6118`, evidence manifest `8304137d8a27ce5a99fa02a57753bacd9b2bb8820067962d950d8e1ab0ec6118-aef3403ff4c8423a9b599b49d6b9a4f2.json`. Natural-reading owner observation on phone remains explicitly pending. |
| **BC-28** | Later; original P3 | Partial; broader consolidation remains | — | Consolidate current README, architecture/contribution/change guidance and clearly archived history: a contributor finds one supported build/test route. Preserve history/safety; no second active tracker. | This queue/pointers complete a subset only. | [BC-28 evidence](EXPERT-PANEL-REVIEW.md#completion-tracker), `90e25bb`: entrypoints refreshed; overlapping historical docs remain. |
| **BC-11** | Owner decision; original P1 | Decisions pending; engineering unassigned | Owner for decisions | Final name/application ID, signing and release/distribution intent. After owner choices, audit existing release setup and define remaining signed-release acceptance. Keep keys out of Git and preserve installed library. | Owner choices, then relevant fix/validation evidence. Queue authorizes no publication/release. | [BC-11 scope](EXPERT-PANEL-REVIEW.md#prioritized-issue-register); BC-24 already records unsigned-release manifest checks, so original absence of release build cannot be assumed. |

## Shared-file caution and scope limits

Code review reported uncommitted work in
`C:\Users\Frank\Documents\BookCover-Workareas\h3` on `a8f6d9e`, touching
`EpubSession.kt`, `EpubReaderScreen.kt`, `ReadingComfortPreferences.kt` and two
device tests; it was not reviewed. This organizer's read-only status check was
**denied access**, so present dirty state/ownership is unknown. Preserve it and
resolve whether it is live before related reader edits; silence is not release.
Opus's active worktree already has Library QA/screenshot changes; leave them with S2.

Streak backup inclusion is an **owner scope choice**, not a defect or assigned
implementation. An optional owner-assisted continuous outside-cover demonstration
(auto/manual start, Stop, timer, exit, fold/passage/bookmark) would add evidence;
prior confirmed physical checks stay complete. No dashboard, Streak rewards,
Settings redesign, duplicate search highlighting, online services or PDF work.

## Completed work and evidence

At `5eed90b`, BC-01–29 have **23 Done, four Open and two Partial** summaries;
remaining portions are queued above, including BC-20 calibration. Keep the
[detailed history](EXPERT-PANEL-REVIEW.md#completion-tracker). BC-07
fold/process-death, C1 performance, bottom-gap fix, BC-24 and BC-29 stay closed.
Reading Streak is owner-confirmed: documentation-only commit `911beba` records it
on the Streak branch, not yet on this `main` snapshot. Stale “owner pending” notes
are not new tasks. Formal accessibility coverage is the single validation row above.

## Review sources

- **Code:** *BookCover release-risk review at 5eed90b*, Library file
  `Pasted text(20261027-171115).txt`, ID `libfile_ef7ea0fb4684819191a7248479e1b529`.
  Read-only source review; no builds/tests. F1–F4 are source findings, not reproduced
  results. Suggested mechanisms are not mandated implementations.
- **UX:** report beginning *No critical issue is established by this evidence*,
  Library file `Pasted text.md`, ID `libfile_3a3ef9e79e488191b5fa260c42193460`.
  Supplied brief and numbered generated-sample QA images, 800 × 1000 and
  360 × 800 dp; these do not demonstrate physical fold/cover behavior.
- [Review conversation and owner updates](https://chatgpt.com/c/6ae0d7f6-14f8-83e8-9785-4d7e298066c3).
  Both Library reports were read for this reconciliation. Owner updates establish
  S2 active at 17:16 UTC and F1/F4 prompt delivery at 17:17 UTC on 2026-10-27.
