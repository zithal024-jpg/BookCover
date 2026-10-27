# BookCover — local EPUB reader prototype

**Current version on `main`: 0.4.0-prototype (`versionCode` 6, `com.bookcover.diagnostic`).** BookCover is an offline, local DRM-free EPUB 2/3 reader for Samsung Galaxy Z Fold devices that displays the current book's cover on the exterior screen via Jetpack WindowManager `WindowAreaController` while you read on the inner display.

- **Single active work queue:** [docs/review/ACTIVE-BACKLOG.md](docs/review/ACTIVE-BACKLOG.md)
- **Detailed BC-01–BC-29 & RS-1 evidence history:** [docs/review/EXPERT-PANEL-REVIEW.md](docs/review/EXPERT-PANEL-REVIEW.md)
- **Quick start & developer guides:** [START-HERE.md](START-HERE.md), [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md), and [docs/BC-25-TASK-RESOURCES.md](docs/BC-25-TASK-RESOURCES.md)
- **Historical 2026-10-26 review package docs:** [OVERVIEW.md](docs/review/OVERVIEW.md), [BUILD-AND-INSTALL.md](docs/review/BUILD-AND-INSTALL.md), [KNOWN-LIMITATIONS.md](docs/review/KNOWN-LIMITATIONS.md), and [RESULTS.md](docs/review/RESULTS.md)

---

## Build, Unit Test, Lint, and Isolated-QA Instructions

All builds and tests use task-scoped outputs outside OneDrive (`%LOCALAPPDATA%\BookCoverBuild\tasks\<task-key>`) and coordinate shared machine/device access through `%USERPROFILE%\.bookcover-coordination\resources.json`. See [docs/BC-25-TASK-RESOURCES.md](docs/BC-25-TASK-RESOURCES.md) for full details.

### 1. Build, JVM unit tests, and Android lint (`scripts/Build.ps1`)

From PowerShell in the repository root:

```powershell
# 1. Read-only check: print task key, output paths, and current BUILD/PHONE lock status
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build.ps1 -TaskId local -Plan

# 2. Acquire BUILD lock, run assembleDebug testQaUnitTest lintDebug lintQa, and emit receipts
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build.ps1 -TaskId local
```

- **Output APK and receipts:** `Build.ps1` prints the exact task-specific APK (`output\BookCover-<task>-<hash>.apk`), `.sha256`, and schema-v2 evidence manifest (`%LOCALAPPDATA%\BookCoverBuild\tasks\<task-key>\receipts\<snapshot>-<id>.json`). It no longer overwrites the historical `output\BookCover-prototype.apk`.
- **Interactive wrapper:** `Build BookCover.cmd` invokes `scripts\Build.ps1` and ends with `pause` for double-click desktop use; automated scripts and agents should call `scripts\Build.ps1` directly.
- **Packaged-terminal tool root (`-ToolRoot`):** If JDK 17 (`java\bin\java.exe`) and the Android SDK (`sdk`) were installed from a packaged Windows app whose `%LOCALAPPDATA%` was virtualized under `%LOCALAPPDATA%\Packages\<PackageFamily>\LocalCache\Local\BookCoverBuild`, pass `-ToolRoot "<full-path-to-BookCoverBuild>"` to `scripts\Build.ps1` and `scripts\Test-Device.ps1`.

### 2. Isolated-QA device/emulator instrumentation (`scripts/Test-Device.ps1`)

Connected tests run **only** against the isolated QA package (`com.bookcover.diagnostic.qa`, build type `qa`). Never retarget instrumentation to `com.bookcover.diagnostic`.

```powershell
# Run full isolated QA suite on an explicitly selected, unlocked device or emulator
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 `
  -TaskId local `
  -Serial <device-serial> `
  -PhoneReleaseEvidence "Prior holder released PHONE; device unlocked and available"

# Optional: run one or more specific QA test classes
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 `
  -TaskId local `
  -Serial <device-serial> `
  -TestClass com.bookcover.diagnostic.qa.BookLibraryBackupSafetyTest `
  -PhoneReleaseEvidence "Focused QA verification"
```

- `Test-Device.ps1` verifies `applicationIdSuffix ".qa"` and `testBuildType "qa"`, acquires `BUILD` then `PHONE`, checks that the selected serial is online and unlocked, and runs `connectedQaAndroidTest`.
- **Testing incident history:** An early 2026-10-26 instrumentation run targeted the main package before `.qa` isolation existed, causing AGP's test runner to uninstall the primary app after testing. Moving the harness to `src/qa` (`com.bookcover.diagnostic.qa`) permanently isolated device tests from the user's personal reader installation.

### 3. Validate task evidence or assemble a review archive (`scripts/Package-Review.py`)

```powershell
python scripts\Package-Review.py --evidence-manifest "<path-to-manifest.json>" --validate-only
```

---

## Tester Installation and Safe In-Place Update Guide

The primary reader app uses application ID `com.bookcover.diagnostic` (launcher name **BookCover**) and stores imported EPUBs, extracted/custom cover images, `books.json` (reading positions, bookmarks, collections), and `reading-days.json` (Reading Streak history) in private application storage.

### Updating an existing installation (preserving library data)

1. **Optional precaution before updating:** Open **BookCover → Settings → Backup → Export backup** to save a JSON copy of your reading positions, bookmarks, and collections to Downloads or Documents.
2. **Install via ADB (recommended for developers/testers):** Use `adb install -r` with the exact APK path printed by `Build.ps1`:
   ```powershell
   adb -s <device-serial> install -r "<repo>\output\BookCover-<task>-<hash>.apk"
   ```
3. **Install manually on the phone:** Copy the exact task APK to the phone's `Downloads` folder, open **Files** (or **My Files**), tap the `.apk`, and choose **Update** when prompted.

### What to do if an update fails (Never uninstall as the default fix)

- **Do NOT uninstall BookCover and do NOT run `adb shell pm clear com.bookcover.diagnostic` or clear app storage in Android Settings.** Uninstalling or clearing data permanently deletes all privately stored EPUB files, custom covers, unexported reading positions/bookmarks, and Reading Streak history.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE` / "App not installed as package conflicts with an existing package" (signature mismatch):**
  - The new APK was signed with a different debug/release key than the currently installed `com.bookcover.diagnostic`.
  - **Fix:** Stop and rebuild the APK using the same `~/.android/debug.keystore` (or signing key) that signed the installed app, or ask the build owner for an APK signed with the matching key.
  - Check the installed vs. candidate signing certificate before retrying:
    ```powershell
    adb -s <device-serial> shell dumpsys package com.bookcover.diagnostic | Select-String "Signatures|versionCode"
    ```
- **`INSTALL_FAILED_VERSION_DOWNGRADE`:**
  - The installed app has a higher `versionCode` than the APK being installed. Build from current `main` (`versionCode` 6) rather than an older archive.
- **If a signing-key migration is ever intentionally chosen by the owner:** First export a metadata backup via **Settings → Backup → Export backup**, confirm you still have the original `.epub` files in `Downloads` or external storage, and note that Reading Streak history (`library/reading-days.json`) is not included in the metadata backup.

---

## Backup, Restore, and Storage Behavior & Known Limitations

### Library metadata backup and restore (`Settings → Backup`)

- **What is backed up:** **Export backup** writes a JSON file (`format: "bookcover-library-backup"`, `version: 1`) containing each book's metadata, SHA-256 `id`, collection name, named bookmarks, `lastReadAt` timestamp, whole-book `progression`, and Readium `locator`.
- **What is NOT in the backup:**
  - **EPUB book files and custom cover images** are not stored inside the JSON backup file (keeps backups small and free of copyrighted book payloads).
  - **Reading Streak history (`library/reading-days.json`)** is stored locally on the device and is not included in library metadata backups.
- **How restore works:**
  - **Restore backup** accepts either an exported `bookcover-library-backup` JSON file or a raw `books.json` index file.
  - Books are matched by their **SHA-256 file hash (`id`)** against books currently present in the library.
  - For each matched book, BookCover restores its collection, merges bookmarks (`SavedBookmarks.merge`), and updates the reading position if the backup's `lastReadAt` is newer than the local copy.
  - Books currently in the library that are absent from the backup are left untouched.
  - Books listed in the backup whose EPUB files are not yet in the library are reported as **missing** in the status message—import those `.epub` files first and run **Restore backup** again to reattach their positions, bookmarks, and collections.
- **Safety guards and known limits (`BC-05`, `F1/F4`, `F2`, `BC-27`):**
  - **Unloaded/damaged index guard (`F1/F4`, `db27ced`):** Both export and restore refuse to run unless the library index has completed a healthy load (`loadSucceeded`). Export serializes and validates the complete UTF-8 backup bytes in memory *before* opening the destination document URI, so a readiness failure never truncates an existing backup file. Valid empty backups are allowed when a healthy loaded library has zero books.
  - **SAF provider write limit (`F1/F4`):** Android's Storage Access Framework (`ContentResolver.openOutputStream(uri, "wt")`) truncates the selected destination URI upon stream open. While pre-open preparation is atomic in memory, an I/O failure inside the storage provider *after* opening the output stream can still leave a partially written destination file.
  - **Picker recreation survival (`F4`, `db27ced`):** Active document-picker request routing (`import`, `cover`, `export`, `restore`) and the pending custom-cover target book ID are persisted in `savedInstanceState` across Activity recreation.
  - **Batch import & interrupted-import cleanup (`BC-27` `74ed155`, `F2` `ea697d2`/`094811a`):** Multi-select EPUB import saves each book as soon as it finishes and supports cancellation (**Stop importing**). During import, BookCover records a synced ownership receipt before creating new book/cover/thumbnail files and cleans only proven abandoned assets on failure, cancellation, or next startup after process death (`SIGKILL`), never deleting unmarked pre-existing files or active imports.

---

## Release-Readiness Summary (`main`)

This summary reflects the committed state of `main` on 2026-10-27 (`0.4.0-prototype`, `versionCode` 6) and separates owner-validated features, automated-tested features, unmerged worktree work, and open items tracked in [docs/review/ACTIVE-BACKLOG.md](docs/review/ACTIVE-BACKLOG.md).

### 1. Implemented and Owner-Validated on `main`

- **Local DRM-free EPUB import & library management (`BC-02`, `BC-04`, `BC-06`, `BC-13`, `BC-27`):** Single and multi-select batch EPUB import via SAF (`OpenMultipleDocuments`), 200 MB file / 100 MB entry / 500 MB uncompressed / 20,000-entry archive bounds, SHA-256 deduplication, embedded/generated/custom covers, memory + disk thumbnail cache, collections, search/sorting, atomic `books.json` index replacement with `.bak` recovery, and two bundled CC0 sample books.
- **Dual-screen exterior cover presentation (`0.3.0`, `BC-07`):** Public `WindowAreaController` rear-display presentation in the same `MainActivity` as the inner `EpubNavigatorFragment`, with FIT_CENTER cover rendering, dominant-edge-color matte, automatic or manual start, 30-min / 60-min / Manual session timer, and in-place fold/unfold, orientation, and display-size continuity (`configChanges` handling without restarting the outer cover session).
- **Reflowable reading comfort & typography (`BC-08`, `C1`, `BC-23`):** Publisher styles on by default (preserving centring, verse, scene breaks, and tables) while CSS text size (`80%–220%` in 10% steps) still applies; instant CSS-variable live preview (`--USER__fontSize`) before single Readium preference commit; Serif, Sans serif, and bundled OpenDyslexic font families; line spacing, page margins, and Publisher / Justify / Left alignment; fixed-layout detection; and full-height reader layout with bottom bar overlaid cleanly.
- **Navigation, RTL edge taps, volume keys & bookmarks (`BC-10`, `BC-14`, `BC-17`, `BC-20`):** Native Readium swipes, optional narrow edge taps (respecting `LayoutDirection.Rtl`), optional volume-key page turns, 48 dp touch targets and 200% font-scale layout adaptations, chapter progress bar (`Page X of Y in chapter · Z% of book`), TOC current-chapter marker, named bookmarks with optional notes, and debounced locator persistence (750 ms + immediate pause/stop/navigation flush).
- **Unified theme system, split Settings & backup/restore (`BC-05`, `BC-12`, `BC-21`, `BC-23`):** Automatic cover-derived light/dark palettes following system dark mode, 5 base presets + 11 colour themes + custom cover RGB swatches, reader page themes (Paper, Sepia, Dawn, Dusk, Night, Moss), separate Reader vs. Library Settings sheets, hidden developer diagnostics (revealed by 5 taps on version footer), bundled third-party licence viewer, and JSON library metadata backup/restore.
- **Scope & licensing cleanup (`BC-01`, `BC-03`):** Removal of the external browser-search shortcut and addition of root `LICENSE` (all rights reserved) + `NOTICE`.

### 2. Implemented and Automated-Tested on `main` (Unit / Isolated-QA Verified; Owner Check or Physical Observation Pending)

- **In-book full-text search (`BC-29`, `5eed90b`):** Offline search via Readium `StringSearchService` with ICU case/accent-insensitive matching, chapter snippets, result navigation, and bounded memory/chapter reads. Verified by 173 JVM tests and 34/34 isolated-QA device tests (< 0.6 s on 500-page / 150k-word EPUB on SM-F976U). *Pending:* formal TalkBack walkthrough and physical outside-cover observation during search on the main app.
- **Reading Streak (`RS-1`, `aa43dc2` / `8095c76`):** Local reading-time tracker (5 cumulative active reading minutes per local calendar date, 10-minute inactivity cutoff, midnight split, stored in `library/reading-days.json`), Library streak card, and monthly calendar bottom sheet. Verified by 195 JVM tests and 33/33 isolated-QA tests; installed in place on the owner's phone (`8095c76`), with owner confirmation recorded on the streak branch (`911beba`).
- **Backup & picker recreation safety (`F1/F4`, `db27ced`):** Requires loaded library before export/restore, prepares complete backup bytes before opening the output stream, rechecks readiness before restore merge/save, and preserves picker request codes and custom-cover book ID across Activity recreation. Verified by 217 JVM tests and 10/10 isolated-QA device tests (`BookLibraryBackupSafetyTest`, `BackupPickerRecreationTest`).
- **Abandoned import asset cleanup across failure & process death (`F2`, `ea697d2` / `094811a`):** Synced ownership receipts and safe startup cleanup of interrupted imports without touching legacy unmarked files or active imports. Verified by 245 JVM tests and 34/34 isolated-QA tests including real multi-process `SIGKILL` recovery (`BookImportProcessDeathTest`).
- **Failed or cancelled book-open resource cleanup (`F3`, `5b083e3` on local `main`; follow-up `db379d4` / `2f8df76` on `origin/main`):** `openBookWithCleanup` (`BookOpenLifecycle.kt`) and `ReadiumEngine.open` close `EpubSession` (`readingLifecycle` observer, `BookSearchController`, and `Publication`) or pre-session `Publication` on any failure or coroutine cancellation before handoff to `MainActivity.session`. Verified on local `main` by 245/245 JVM unit tests (`BookOpenLifecycleTest` 7/7) and `assembleDebug` / `lintDebug` / `lintQa`. *(Note: `origin/main` / `claude/argon-commit-review-69dce1` adds `db379d4` to assign `MainActivity.session` as the first statement of `commit` and records in-place installation + owner confirmation on `RFGL710TARJ` in `2f8df76`; local `main` has not yet merged `origin/main`.)*
- **Friendly user-facing error mapping (`BC-26`, `e3826ed`):** `ReaderMessages` maps storage, archive, DRM, size-limit, and backup exceptions to plain-language messages without leaking file paths or stack traces. Verified by 9 JVM unit tests (`ReaderMessagesTest`). *Pending:* isolated-QA / phone verification of displayed UI banners for corrupt EPUB import and non-backup restore (`ACTIVE-BACKLOG.md` row `BC-26`).
- **Manifest permission audit & CI/Gradle modernization (`BC-24` `fc3bdbf`, `BC-25` `d1faaec` / `32a78cf`):** Merged-manifest regression test verifying zero `INTERNET` or broad storage permissions across debug/QA/unsigned-release manifests; migration to AGP 9 built-in Kotlin and `com.android.application` modern DSL; task-scoped build/device locks and evidence manifests.

### 3. Branch / Worktree Reconciliation (Not Yet Unified into a Single Local `main` Checkout)

Local `main` (at `cfc9549`) and `origin/main` (at `af0695b`) have diverged and must be merged before building a single unified APK that contains both streams:

- **On local `main` (`094811a`, `cfc9549`), not yet on `origin/main`:**
  - `F2` actual multi-process `SIGKILL` recovery QA test (`BookImportProcessDeathTest` in `094811a`, 34/34 isolated-QA tests passing) and its backlog evidence (`cfc9549`).
- **On `origin/main` (`af0695b`) and worktree branches, not yet merged into local `main`:**
  - **`S2 / UX §1` — Library Home redesign (`224b907`, `6f374c0`, `1cfc2cd`, merged to `origin/main` in `d046d67`):** Redesigns Library Home around Continue Reading (with cover, progress, chapter, and saved-place reassurance), compact header/search/sort, and a compact Streak row; verified by JVM + emulator screenshot QA and installed/owner-confirmed on the phone (`1cfc2cd`), but **not present in local `main` builds until `origin/main` is merged locally**. (`ACTIVE-BACKLOG.md` row `S2` also still awaits status update after branch integration.)
  - **`F3` commit-block ordering follow-up & owner confirmation (`db379d4`, `849dbf7`, `2f8df76`, merged to `origin/main` in `af0695b`):** Moves `session = current` to the first line of the `openBookWithCleanup` commit block (`db379d4`) and records in-place installation and owner confirmation (`2f8df76`).
- **Separate unmerged branches / worktrees (not in local `main` or `origin/main`):**
  - **Unreviewed `h3` worktree (`C:\Users\Frank\Documents\BookCover-Workareas\h3` on detached `a8f6d9e`):** Contains uncommitted reader edits (`EpubSession.kt`, `EpubReaderScreen.kt`, `ReadingComfortPreferences.kt`, and two device tests) noted in `ACTIVE-BACKLOG.md`; not part of `main`.
  - **Streak branch documentation commit (`911beba` on `claude/reading-streak-feature-d82445`):** One-line tracker update in `EXPERT-PANEL-REVIEW.md` recording owner confirmation of the installed `RS-1` build; reconciled in `ACTIVE-BACKLOG.md`.

### 4. Still Open / Pending Items (Tracked in `docs/review/ACTIVE-BACKLOG.md`)

- **`UX §2`:** Verify fixed-pixel reflowable publisher CSS vs. relative-size text with publisher styles on/off across narrow/wide and 100%/200% text; document recovery or close without code changes if resizing works as expected.
- **`UX validation` (`BC-10` follow-up):** Consolidated TalkBack, 200% system text, 48 × 48 dp touch-target, and WCAG contrast verification pass once `S2` and reader surfaces settle.
- **`BC-22 / S1`:** Configurable inner-screen idle timeout (replacing whole-session `FLAG_KEEP_SCREEN_ON`) and 60-minute unfolded battery drain measurement on physical hardware.
- **`BC-26` acceptance reconciliation:** Confirm visible friendly error messages on device/QA for corrupt EPUB import and non-backup restore.
- **`BC-15`:** Incremental extraction of remaining `MainActivity`-owned session/operation state into a ViewModel or state holder.
- **`BC-19 / S3`:** Owner observation of sustained unfolded reading to decide whether an optional two-page spread mode (`ColumnCount`) is warranted.
- **`BC-20` (calibration only):** Natural-reading observation of reading-speed / chapter time-left estimates.
- **`BC-11` (Owner release decisions):** Final application name, production `applicationId` (replacing `com.bookcover.diagnostic`), release keystore/signing setup, and R8 release build verification.

---

## Historical Milestones

The sections below preserve earlier 0.3.0, 0.2.2, and 0.1 milestone records as project history.

<details>
<summary>Historical 0.3.0-prototype EPUB library & reader record</summary>

The following 0.3.0 record is retained as history. That version introduced the native cover library and Readium EPUB reader, with private file copies, collections, search/sorting and saved locations. Two original sample books are included.

Reading uses swipes, hidden center-tap controls and a small bottom-left completion percentage. Contents, bookmarks, text size and Paper/Sepia/Night themes stay in the same host activity as the exterior cover. The full cover uses the matching-color background; dimming and AOD are shelved after the phone brightness control did not work.

**Installable APK (historical 0.3.0 artifact):** `output/BookCover-prototype.apk`. It updated the existing package in place; the launcher label became **BookCover**. Build, 14 JVM tests and Android lint passed, as did APK signature/alignment checks. Installed on the Samsung phone: two local EPUB imports/opening, sample swipes, chapter/bookmark navigation, and saved progress across reopening and a cold restart were exercised. Actual inner/exterior screenshots were inspected. **The user confirmed that the physical outside cover stays visible while swiping inside EPUB pages.**

See [the EPUB phone test](PHONE-TEST-EPUB.md) and [0.3.0 architecture/scope](docs/EPUB-PROTOTYPE.md).

</details>

<details>
<summary>Historical sample-reader and Kindle investigation</summary>

**Historical milestone: 0.2.2-reader-test.** The native sample-text reader shares the same MainActivity as the exterior-cover presentation. It uses swipe page turns, center-tap controls, and a small bottom-left completion percentage. Sample paragraphs flow continuously to fill each page. The complete exterior artwork uses a matching-color background, with an experimental outer-window brightness request that the user subsequently reported did not work.

True Samsung Always On Display integration is not established through a supported public API for this two-screen use case. The brightness control is a normal-window request, not AOD, and needs a phone check. See [AOD and power findings](docs/AOD-AND-POWER.md).

**Phone results:** in 0.2, the user confirmed that the actual outer cover stayed visible during native page turns and settings changes; logs show 39 page changes and the expected three-minute timeout. In 0.2.1, the user confirmed that corrected text fills the page properly and manual Stop works. Exact continuous-reader duration and the reported outer-image sizing still need verification. See [the initial result](docs/READER-TEST-RESULT.md) and [current validation](docs/VALIDATION.md).

Use [PHONE-TEST-READER.md](PHONE-TEST-READER.md). The new artifact is **output/BookCover-reader-test.apk**. It updates the existing **BookCover Test** app in place. The original diagnostic APK remains separately at **output/BookCover-diagnostic.apk**.

The new test stays inside BookCover: select a cover, choose **Start cover + read sample**, make at least ten swipe page turns, reveal/change/hide the reading controls, and confirm the physical exterior cover remains visible for at least two minutes. Exterior sessions request closure after three minutes. Use **Read sample without cover** to practise separately.

The reader controls, appearance settings and setup navigation stay within one activity. Activity recreation still ends the exterior session and needs a fresh start. The display diagnostics and report export remain available on setup. The original controller source is preserved at `docs/baselines/MainActivity-0.1.kt` outside compiled app sources.

Double-click **Build BookCover.cmd** to rebuild and run checks. Source stays in this folder; generated Windows files use `%LOCALAPPDATA%\BookCoverBuild` to avoid the observed OneDrive resource-merger access problem. See [the validation record](docs/VALIDATION.md), [mission](docs/READER-MISSION.md), and [technical strategy](docs/READER-STRATEGY.md).

<details>
<summary>Original 0.1 diagnostic investigation and test</summary>

**Current direction: BookCover Reader.** The user confirmed that the exterior cover and interior diagnostic controls worked together on the physical phone, but the cover stopped when switching to Kindle. We are investigating a reader inside BookCover's own foreground activity, with Moon+ native integration as an alternative. Read the [revised mission prompt](docs/READER-MISSION.md) and [reader strategy](docs/READER-STRATEGY.md).

**Existing APK: diagnostic prototype.** A complete EPUB reader has not yet been implemented or tested. The original investigation and installation instructions below describe that diagnostic APK.

The goal is a book cover on the physical outside screen while Kindle uses the entire unfolded inside screen. This project does not replace Kindle or split the inside screen.

## Findings in plain English

1. **How likely is this to work?** Unlikely to work reliably through public APIs, based on the documentation. Your exact phone and firmware still need testing. No numerical probability is justified.
2. **Biggest obstacle:** Samsung must let an ordinary app keep both physical screens active, and Android must preserve that session when Kindle takes over the inside screen. Either restriction can block the idea.
3. **First test:** ask the phone which public display capabilities it exposes, then try a short exterior presentation and open Kindle. A successful API callback alone is not proof.
4. **What you need:** your phone, this Windows computer, a USB cable that transfers files, Kindle installed, and a JPG or PNG cover on the phone. You do not need Android Studio or Developer Options to try the prepared APK.
5. **What success means:** someone sees the cover on the outside while you turn pages normally in full-screen Kindle inside for at least 60 seconds. A dark outer screen, an external monitor, a virtual display, or the inside turning off does not pass.

Android distinguishes rear-display transfer (inside turns off) from simultaneous dual-screen presentation. Samsung documents the former; Android warns that leaving the host app can end the latter. See [the evidence and limitations](docs/FEASIBILITY.md), [Android's guide](https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes), and [Samsung's implementation guide](https://developer.samsung.com/sdp/blog/en/2024/09/26/unfold-the-potential-of-galaxy-fold-devices-with-windowmanagers-rear-display-mode).

## Install and run the experiment

Follow [START-HERE.md](START-HERE.md). The APK is `output/BookCover-diagnostic.apk`. The app appears on the phone as **BookCover Test**.

The app selects one JPG/PNG through Android's file picker, remembers it, checks WindowArea presentation capability, logs available displays, and attempts only public supported routes. Its control screen stays on the inner display. A separate optional standard Presentation test is offered only for eligible secondary displays; you must identify which physical screen it reaches.

The **Open Kindle** button intentionally does not stop the session when BookCover goes into the background. Android may still stop it. Sessions request closure after three minutes, on screen-off, or when their host activity is destroyed/recreated. This is a short experiment, not a continuous reading mode. Do not leave the phone unattended with a static image.

## What the files do

| File | Purpose |
|---|---|
| `app/src/main/java/com/bookcover/diagnostic/MainActivity.kt` | Control screen, image picker, two public presentation routes, Kindle handoff, and cleanup |
| `app/src/main/java/com/bookcover/diagnostic/DiagnosticLog.kt` | Small persistent log, device information, and exportable report |
| `app/src/main/java/com/bookcover/diagnostic/DisplayPolicy.kt` | Prevents offering the default, host, private, or inactive display as a standard secondary target |
| `app/src/test/…/DisplayPolicyTest.kt` | Checks those display-selection boundaries; does not test physical panels |
| `docs/FEASIBILITY.md` | Research, confirmed constraints, and questions for the physical phone |
| `docs/VALIDATION.md` | Exact checks performed on this computer and checks still outstanding |
| `docs/DEVELOPMENT.md` | Optional tools and rebuild instructions |

The app has no internet permission, no overlay/accessibility access, no foreground service, and no special display privilege. It does not read Kindle content. It uses a system-granted image URI; no broad photo-library permission is needed. Diagnostics include model, Android build/fingerprint, capabilities, display names and events; they omit the selected image and its URI. Logs stay locally until you explicitly save and share them.

## Later phases

**Gate:** implement the full MVP only after a real-phone test confirms both screens and the Kindle handoff. The feature backlog is a small local book library (title, author, imported cover), last-book memory, Select Book / Start Reading controls, Fit/Fill/Crop, permitted brightness controls, immersive cover rendering, folding/lock behavior, battery/burn-in management, and other e-reader checks. These remain planned features.

If the gate fails, use the exported report to distinguish missing Samsung capability from foreground-session termination. A foreground service or battery exemption does not grant an absent screen mode. We will not substitute an interior split-screen layout for your original goal.

</details>

</details>
