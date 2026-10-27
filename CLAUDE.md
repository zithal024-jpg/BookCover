# BookCover — instructions for Claude Code

BookCover is an Android EPUB reader for a Samsung Galaxy Z Fold. While you read
on the unfolded inner screen, it shows the current book's cover on the outer
screen. Version 0.4.0-prototype (versionCode 6), Kotlin + Jetpack Compose +
Readium Kotlin Toolkit 3.4.0. Application ID `com.bookcover.diagnostic`.

## Working with the owner

- The owner is not a programmer. Explain plans and results in plain English,
  in short numbered steps, and define any technical term you use.
- Before each change, say what you will do and why. After it, say what changed
  and how it was checked.
- Ask before anything that can't be undone, and before decisions that belong to
  the owner: the project licence, public vs. private release, the final app name
  and application ID, deleting user data, publishing anything.
- Separate what you verified (a build, a test, a log) from what you assume.
  The outer screen can only be confirmed by the owner looking at the phone, so
  ask them to look. Never claim the cover display works because an API call
  returned successfully.

## Autonomous working agreement

- Opus and Codex independently choose tasks within the owner's authorized scope,
  priorities and dependencies, and implement, validate and integrate locally
  without routine PM assignment or approval. Independent work may run in parallel
  when it does not conflict on shared files, devices or other resources.
- Check for actual conflicts before changing shared files or using shared
  resources. Routine path claims and PM request/grant/acknowledgment loops are
  unnecessary. Resolve uncertain ownership or active conflicts before proceeding;
  preserve others' uncommitted work and never infer release from silence.
- Keep supported build/device helpers, their locks and required release evidence,
  and owner phone availability. Never bypass safeguards, steal a resource or stop
  another worker's process. Registry initialization still requires prior release.
- The implementer updates their row in `docs/review/ACTIVE-BACKLOG.md` at completion
  or a material status change/blocker, with the commit and verification. Record new
  review findings there before implementation prompts. Keep original BC evidence in
  its existing completion row. Keep a short handoff only for transfer or resumption;
  no duplicate PM logs or timed status reports are needed.
- Preserve the data, privacy, security and isolated-QA rules below. Report checks
  accurately; only the owner's physical observation verifies the outside cover.
- This agreement supersedes conflicting PM-only writer roles, assignment/path
  ceremonies, manual approval/reservation paperwork, recurring status logs and
  former component-only limits in coordination documents. It does not lift an
  explicit pause or authorize push, publication, release, destructive actions or
  decisions reserved to the owner; required safety confirmations remain.

## Build and test (Windows, this PC)

Build tools live in `%LOCALAPPDATA%\BookCoverBuild` (JDK in `java\`, Android
SDK in `sdk\`). Build output goes there too, not into this OneDrive folder.
That is deliberate: OneDrive breaks Android's resource merger.

- **Build + unit tests + lint:** run the script directly, not the .cmd file
  (`Build BookCover.cmd` ends with `pause` and will hang):
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build.ps1`
  This runs `assembleDebug testQaUnitTest lintDebug lintQa` with a BUILD reservation.
  Generated output/cache/Gradle home and APK names are checkout/task-specific;
  use `-TaskId core` (default `local`) and `-Plan` to see the paths. The command
  prints the exact APK and source-bound evidence paths; it no longer overwrites
  `output\BookCover-prototype.apk`. A working agent may explicitly initialize the
  shared resource registry only after previous holders release: see
  `docs/BC-25-TASK-RESOURCES.md`. Missing/uncertain ownership fails closed.
- **On-phone tests:** only through
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId core -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'RECORDED RELEASE AND OWNER AVAILABILITY'`
  Use the same task ID as the matching build. An explicit serial and PHONE release
  evidence are required; the script acquires BUILD then PHONE before any tool call.
  The phone must be connected by USB, unlocked, with USB debugging on.
  Ask the owner to keep it unfolded with the screen on and untouched: folded,
  the tap-injecting `NativeReaderTest` can hang with no focused window, and
  `ReadingLayoutTest` is skipped. Known flakiness: the first reader screen of a
  run, or one right after a fold switch, sometimes times out ("navigator is
  attached"); rerunning that class passes. Hardening the tests is still to do.
- **Installing the owner's app:** `adb install -r EXACT_APK_PATH_PRINTED_BY_BUILD`
  updates it in place and keeps the library. adb is at
  `%LOCALAPPDATA%\BookCoverBuild\sdk\platform-tools\adb.exe`.

## Hard safety rules

1. **Never run connected/instrumented tests against the main app
   `com.bookcover.diagnostic`.** Gradle's test cleanup uninstalls its target. This
   already happened once and permanently erased the owner's reading positions
   and bookmarks. Device tests must use the separate `qa` build type
   (`com.bookcover.diagnostic.qa`), which `Test-Device.ps1` enforces. Do not change
   `testBuildType = "qa"` or `applicationIdSuffix = ".qa"` in `app/build.gradle.kts`.
2. Never `adb uninstall`, `pm clear`, or otherwise wipe the main app's data.
3. Never commit, upload or screenshot the owner's personal books. Only the two
   generated samples in `app/src/main/assets/samples/` belong in the repo.
4. Never commit signing keys, `local.properties`, or anything from `output/`.
5. Keep the app free of internet access: no `INTERNET` permission, no network
   client. Remote book resources stay blocked (`library/ReadiumEngine.kt`).

## Where things are

- `app/src/main/java/com/bookcover/diagnostic/`
  - `MainActivity.kt`: the single screen; currently holds all app state
  - `library/`: `BookLibrary.kt` (private copies + `books.json` index),
    `BookRecord.kt`, `LibraryScreen.kt`, `CoverPalette.kt` / `LibraryTheme.kt`
    (theme from the latest cover), `ReadiumEngine.kt` (opens EPUBs),
    `PublicationMarkup.kt` (strips scripts from book HTML)
  - `reader/`: `EpubSession.kt` (Readium navigator, typography, position saving),
    `EpubReaderScreen.kt` (controls), `ReadingTapPolicy.kt`, `ReadingTypography.kt`
  - `cover/ExteriorCoverController.kt`: outer-screen cover via Jetpack
    WindowManager `WindowAreaController` (an experimental API)
  - `streak/`: reading streak. `ReadingStreak.kt` (5-minute goal, streak rules),
    `ReadingTimeTracker.kt` (foreground clock, 10-minute idle limit; driven by
    `EpubSession`), `ReadingDayStore.kt` (`library/reading-days.json`, dated
    totals only, stored as local dates so a timezone change keeps earned days),
    `ReadingStreakViews.kt` (Library card under Continue, detail sheet)
- `app/src/test/`: JVM unit tests. `app/src/androidTest/` + `app/src/qa/`:
  on-phone tests and their isolated host activity.
- `scripts/GenerateSamples.py` rebuilds the two sample EPUBs; "The Lantern
  Keeper" text is in `scripts/lantern-keeper.txt`. (The old sample reader and
  other dead code were removed in BC-16.)

## Current plan

Start with the [active backlog](docs/review/ACTIVE-BACKLOG.md), the single shared
work queue for Claude Code, Codex and the owner. Check it before starting, record
new findings before implementation prompts, and update your own relevant row on
material status changes/completion. Work in its priority and dependency order;
independent tasks may proceed in parallel under the agreement above.

The [Expert Panel Review](docs/review/EXPERT-PANEL-REVIEW.md#completion-tracker)
retains the original BC-01 through BC-29 review and detailed completion evidence/history
(converted from the owner's unchanged `Expert Panel Review.docx`). Add original
BC completion evidence there with the work; live priorities and ownership belong
in the active backlog rather than a parallel status list.

**Current status (2026-10-27):** stabilization is complete, including protected
library-index loading, backup export/import, the removed web-search shortcut,
library/lifecycle tests, dead-code removal and background saves/logging. Git
history and the private remote are established at
https://github.com/zithal024-jpg/BookCover; build output and personal data stay
outside Git. The owner chose all rights reserved (`LICENSE`); bundled font and
library notices are recorded under BC-12 (OpenDyslexic uses CC BY 3.0 over
Bitstream Vera, not OFL).

Publisher styles, spacing/margins, the generated EPUB corpus, chapter navigation,
RTL taps, volume-key turns, themes, accessibility controls and grouped settings
are implemented. C1 text-size and H3 compact-controls follow-ups have recorded QA
and an in-place installation; pending owner observations remain in the tracker.
BC-29 offline in-book search is QA-verified. CI and AGP readiness work is recorded
under BC-25. Read each tracker row for its exact evidence and remaining limits;
these summaries do not establish a new build, phone observation or resource release.

**Remaining work:** see the [active backlog](docs/review/ACTIVE-BACKLOG.md) for
the combined code/UX findings and genuinely remaining original scope. BC-07,
BC-24 and other completed stages are not new assignments; the evidence history
may retain older pending notes that later owner confirmation supersedes.

**Reader constraints to preserve:** text size used to look like zooming on the inner
screen because Readium CSS caps lines at `40rem`, which grows with
`--USER__fontSize`. `EpubSession` now overrides `--RS__maxLineLength` to `800px`
(owner found 640px left too much empty space; `RsProperties.maxLineLength`
accepts only rem). Guarded by `ReadingLayoutTest`,
which needs the phone physically unfolded and is skipped otherwise. Owner-reported
(2026-10-26): rotating while reading briefly flashes the library before the page
returns. Cause: recreation starts with no session, so `MainActivity` draws
`LibraryScreen` until the saved book reopens. Quick mitigation done the same day
(`reopeningBookId` in `MainActivity` shows a reader-coloured page; guarded by
`ReaderLifecycleTest`); BC-07 later completed fold/recreation continuity. Its
evidence stays in the completion tracker; remaining state extraction is BC-15.

Do not start PDF, cloud sync, online catalogs, or brightness/AOD experiments
unless the owner asks.

## Things that look wrong but are deliberate

- `ReadiumEngine` installs the markup filter (script stripping, BC-18 encoding fix)
  through the `onCreatePublication` argument of each `opener.open(...)` call, with a
  once-only guard, not through the `PublicationOpener` constructor. Readium 3.4.0
  never calls the constructor's hook and calls the per-open one twice; until
  2026-10-26 book scripts therefore ran on the phone. `EpubCorpusTest`'s
  `scripts-and-forms` book fails if this regresses.

- `super.onCreate(null)` in `MainActivity` and `ReaderTestActivity`: Readium's
  navigator can't be restored by Android's fragment restorer, so the app reopens
  the book from its own saved locator instead.
- Build directory redirected to `%LOCALAPPDATA%` (`build.gradle.kts`): OneDrive
  workaround.
- AGP 9.3.3 uses built-in Kotlin and the modern Android DSL; the legacy Kotlin
  Android plugin and both opt-out flags have been removed for AGP 10 readiness.
  The Compose plugin keeps Kotlin/Compose compiler versions at 2.4.20. AGP 10
  itself is not yet published in Google Maven. `.github/workflows/android.yml`
  checks a clean Linux build, unit tests, lint and the isolated QA test APK;
  a separate Windows job checks the host scripts. See `docs/BC-25-TASK-RESOURCES.md`.
