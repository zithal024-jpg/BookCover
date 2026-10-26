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

## Build and test (Windows, this PC)

Build tools live in `%LOCALAPPDATA%\BookCoverBuild` (JDK in `java\`, Android
SDK in `sdk\`). Build output goes there too, not into this OneDrive folder.
That is deliberate: OneDrive breaks Android's resource merger.

- **Build + unit tests + lint:** run the script directly, not the .cmd file
  (`Build BookCover.cmd` ends with `pause` and will hang):
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build.ps1`
  This runs `assembleDebug testQaUnitTest lintDebug lintQa` and copies the APK
  to `output\BookCover-prototype.apk`.
- **On-phone tests:** only through
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1`
  The phone must be connected by USB, unlocked, with USB debugging on.
  Ask the owner to keep it unfolded with the screen on and untouched: folded,
  the tap-injecting `NativeReaderTest` can hang with no focused window, and
  `ReadingLayoutTest` is skipped. Known flakiness: the first reader screen of a
  run, or one right after a fold switch, sometimes times out ("navigator is
  attached"); rerunning that class passes. Hardening the tests is still to do.
- **Installing the owner's app:** `adb install -r output\BookCover-prototype.apk`
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
- `app/src/test/`: JVM unit tests. `app/src/androidTest/` + `app/src/qa/`:
  on-phone tests and their isolated host activity.
- `scripts/GenerateSamples.py` rebuilds the two sample EPUBs; "The Lantern
  Keeper" text is in `scripts/lantern-keeper.txt`. (The old sample reader and
  other dead code were removed in BC-16.)

## Current plan

The full review is the Expert Panel Review: read
`docs/review/EXPERT-PANEL-REVIEW.md` first (Markdown converted from the owner's
`Expert Panel Review.docx`, which stays unchanged beside it). Its issue IDs
(BC-01 … BC-29) are used below. The **Completion tracker** at the top of that
file is the shared status list for Claude Code, Codex and the owner: check it
before starting (another agent may already be on an issue) and update its row,
with the commit and how it was verified, in the same commit as the work. Work in
stage order, one issue at a time, building and testing after each.

**Stage 1: Stabilization (do this first)**
- BC-04: the `.git` folder exists but has zero commits. Commit the current
  state first, then push to a private remote. Move baselines and APKs out of Git.
- BC-02: `BookLibrary.load()` throws on a bad index or one bad record; the list
  stays empty, and the next save overwrites `books.json`, erasing the library.
  Refuse writes until a successful load, skip bad records individually, and keep
  a last-good backup. Write the failing unit test first.
- BC-01: remove the Anna's Archive search shortcut (`LibraryScreen.kt`,
  `MainActivity.kt` `onSearchWeb`).
- BC-03 / BC-12: no project LICENSE (the owner chooses it); the bundled
  OpenDyslexic font ships without its OFL licence text.
- BC-05: add export/import of the library index and bookmarks via the system
  file picker.
- BC-09: unit tests for `BookLibrary` (import, duplicate, remove, corrupt index,
  cover replace); lifecycle tests (rotation, fold, process death) in the QA app.
- BC-16: delete the dead code listed above. BC-14: debounce position saves and
  move `DiagnosticLog` writes off the main thread.
- Gate: no known data-loss path, export round-trips, all tests pass.
- Status 2026-10-26: all Stage 1 items done and the gate met (43 JVM tests, 15
  on-phone QA tests, backup round trip and position survival confirmed on the
  owner's phone). Private repo: github.com/zithal024-jpg/BookCover.

**Next stages (summary):** 2, core reading (publisher-styles toggle, line
spacing/margins, EPUB test corpus, chapter progress, RTL-aware taps, volume-key
page turns). Fixed 2026-10-26: text size used to look like zooming on the inner
screen because Readium CSS caps lines at `40rem`, which grows with
`--USER__fontSize`. `EpubSession` now overrides `--RS__maxLineLength` to `800px`
(owner found 640px left too much empty space; `RsProperties.maxLineLength`
accepts only rem). Guarded by `ReadingLayoutTest`,
which needs the phone physically unfolded and is skipped otherwise. 3, UX refinement (slim reader bar, split settings, cover
thumbnails, one theme that follows dark mode, accessibility). Owner-reported
(2026-10-26): rotating while reading briefly flashes the library before the page
returns. Cause: recreation starts with no session, so `MainActivity` draws
`LibraryScreen` until the saved book reopens. Quick mitigation done the same day
(`reopeningBookId` in `MainActivity` shows a reader-coloured page; guarded by
`ReaderLifecycleTest`); the full fix, no reload at all, is the Stage 4 item below. 4, foldable
features (move state out of `MainActivity`, survive fold/unfold without reload,
two-page spread when unfolded, battery measurement, in-book search). 5,
release (final app ID, release signing, R8, current targetSdk, CI, docs).

Do not start PDF, cloud sync, online catalogs, or brightness/AOD experiments
unless the owner asks.

## Things that look wrong but are deliberate

- `super.onCreate(null)` in `MainActivity` and `ReaderTestActivity`: Readium's
  navigator can't be restored by Android's fragment restorer, so the app reopens
  the book from its own saved locator instead.
- Build directory redirected to `%LOCALAPPDATA%` (`build.gradle.kts`): OneDrive
  workaround.
- `android.builtInKotlin=false` and `android.newDsl=false` in
  `gradle.properties`: AGP 9 compatibility flags; migrate before AGP 10.
