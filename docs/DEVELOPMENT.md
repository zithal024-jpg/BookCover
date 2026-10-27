# Building and maintaining BookCover

For current scope and safety rules, read [CLAUDE.md](../CLAUDE.md) and the
[completion tracker](review/EXPERT-PANEL-REVIEW.md). The
[task-resource guide](BC-25-TASK-RESOURCES.md) is the detailed reference for
guarded builds, QA tests and matching package evidence.

From the repository folder on this Windows PC:

```powershell
# Read-only: show the task-specific paths first.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build.ps1 -TaskId local -Plan
# Build the app, run JVM tests and lint, with the helper's BUILD lock.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build.ps1 -TaskId local
```

The build runs `assembleDebug testQaUnitTest lintDebug lintQa`. It uses the prepared
tools under `%LOCALAPPDATA%\BookCoverBuild` and prints exact task-specific APK,
checksum and source-evidence paths. Generated output and caches stay outside this
OneDrive folder. `Build BookCover.cmd` is the interactive wrapper and pauses at
the end; agents should call `scripts/Build.ps1` directly.

Phone tests run only through `scripts/Test-Device.ps1`, with the matching task ID,
an explicit device serial, recorded previous-holder release and owner availability.
It acquires BUILD then PHONE and targets `com.bookcover.diagnostic.qa`. Never run
connected tests against the owner's main app, uninstall it or clear its data.
Use the current helper examples in the task-resource guide; old output/cache
arguments below are historical. Only physical owner observation verifies the
outside cover. Ordinary work follows the [autonomous agreement](../CLAUDE.md#autonomous-working-agreement).

<details>
<summary>Earlier toolchain, rebuild and diagnostic architecture notes (historical)</summary>

# Building and maintaining BookCover Test

**Current 0.4.0 instructions:** use [the task-resource guide](BC-25-TASK-RESOURCES.md) for reservation setup, task-specific paths and exact commands. Build BookCover.cmd runs `assembleDebug testQaUnitTest lintDebug lintQa` and prints the APK/evidence paths. Native checks run only through `scripts/Test-Device.ps1` with explicit serial and PHONE availability, targeting the isolated `.qa` application. Earlier commands/version descriptions below describe history and must not be used to retarget tests at a user's main installation.

**Current build: 0.3.0 EPUB prototype.** Use [PHONE-TEST-EPUB.md](../PHONE-TEST-EPUB.md) and [EPUB-PROTOTYPE.md](EPUB-PROTOTYPE.md). Build BookCover.cmd / scripts/Build.ps1 now produces `output/BookCover-prototype.apk` plus SHA-256, using JDK 17, Gradle 9.7.0, AGP 9.3.3, Kotlin/Compose compiler 2.4.20, compile SDK 37, Build Tools 36.0.0 and desugar libraries 2.1.5. Target SDK remains 35; minimum remains 30. Generated output/cache paths stay outside OneDrive. The launcher is **BookCover**, package remains `com.bookcover.diagnostic`, signed with the same local debug key. Brightness controls are shelved; no AOD integration is present. The source sample reader and baseline APKs remain for reference. The following earlier development record describes the diagnostic toolchain and is historical.

You do not need any development software to install the prepared APK. See PHONE-TEST-READER.md for the current same-activity sample-reader phone test. START-HERE.md retains the original diagnostic/manual-install instructions.

## Tools prepared on this computer

The initial Windows inspection found no Java, Android SDK, Gradle, or adb on PATH and no Android SDK in its normal location. The build tools were downloaded into `%LOCALAPPDATA%\BookCoverBuild` without installing Android Studio or changing the permanent system PATH:

- Eclipse Temurin JDK 17 (download metadata and SHA-256 verified against Adoptium's API).
- Google's Android command-line tools, SDK Platform 35, Build Tools 35.0.0, and Platform Tools.
- Gradle 8.11.1 (SHA-256 verified against Gradle's published checksum).

Android SDK license acceptance is recorded locally by sdkmanager; license text is in the local tool folder's `sdk-licenses.log`. The SDK and JDK retain their own licenses. Download archives are retained there for this initial setup. The Git repository ignores generated build files and machine-specific `local.properties`.

An incremental rebuild encountered OneDrive's read-only/reparse attributes on generated resource folders. Windows builds now place generated outputs in `%LOCALAPPDATA%\BookCoverBuild\project-output` and the script puts its project cache in `project-cache` there. Source stays in your requested OneDrive folder. The final APK is still copied to the project's `output` folder. A `bookcover.buildRoot` Gradle property can override the generated-output location for another checkout.

The project pins Android Gradle Plugin 8.9.2, Kotlin/Compose compiler 2.1.20, Compose BOM 2025.04.01, and WindowManager 1.5.1. It compiles/targets Android API 35 and can install on Android 11/API 30 or later. Compile SDK is not a restriction to phones running exactly that Android version. The pinned stable WindowManager API uses WindowAreaInfo/Binder; newer alpha reference pages may show different interfaces. [Android build compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes), [WindowManager releases](https://developer.android.com/jetpack/androidx/releases/window).

## Simple rebuild on this computer

Double-click **Build BookCover.cmd** in the project folder. A console shows progress, runs the build, unit tests, and Android lint, and copies the debug APK into `output`. Keep the window open if it reports an error so its message can be shared. No phone is needed to compile.

The current output is `output/BookCover-reader-test.apk`, version 0.2.2-reader-test. The original `BookCover-diagnostic.apk`, `BookCover-reader-test-0.2.apk`, and `BookCover-reader-test-0.2.1.apk` remain available separately. All use package `com.bookcover.diagnostic` and the same local debug key for updates that preserve app preferences. The phone's label remains BookCover Test.

The project also includes standard Gradle wrapper scripts. Technical equivalent: `gradlew.bat assembleDebug testDebugUnitTest lintDebug` using JDK 17 and the installed Android SDK. `scripts/Build.ps1` sets these tool paths for its own process, leaving system settings unchanged.

The APK is automatically signed with the local Android debug certificate. It is intended for testing, not Play Store distribution. Keep the same debug key for in-place updates. If an update reports a signature mismatch, ask for help rather than deleting data first. [Android debug signing](https://developer.android.com/studio/publish/app-signing#debug-mode).

## Optional Android Studio installation

For visual editing later, download [Android Studio from Google](https://developer.android.com/studio), run its Windows installer with the standard options, and complete its setup wizard. Choose **Open**, select the **Book Cover - App** project folder (not the APK), and let Gradle sync finish. Set the Gradle JDK to the prepared JDK 17 if Studio requests a JDK. Studio may ask to install SDK 35 components. None of this is needed for the first phone experiment.

## Architectural decisions

- Compose handles the controls; a plain ImageView created with the presentation's own context renders the exterior image with aspect ratio preserved.
- The cover view fills its available parent and uses a color derived from sampled image edges behind the complete FIT_CENTER artwork. Edge sampling runs with image decoding off the main thread. Layout dimensions are logged; no chosen-image RGB data or URI is exported.
- The optional outer brightness override is applied only to the exterior window and persisted as a preference. Default -1 restores phone policy. There is no AOD API, global brightness setting or new permission in this build; independent hardware behavior must be observed.
- The native sample reader is another Compose route within the existing MainActivity. It does not start a second activity or another reading app. Reading mode hides system bars and keeps the inner screen awake while the route is visible; exiting to setup restores the bars and releases that inner-window flag.
- A SaveableStateHolder retains the sample-reader state while visiting setup. Activity recreation still ends the exterior session and requires a fresh public presentation request.
- Capability is checked at runtime rather than inferred from a Samsung model name or hard-coded display ID.
- Activity transfer is never requested because it switches the inner screen off.
- The standard Presentation path is manually chosen and excludes the primary/host display, private targets, non-presentation targets, and targets currently off. It cannot turn on a display hidden by Samsung.
- There is no service, wakelock, overlay, rooting, private API reflection, Kindle modification, or protected ebook access.
- onPause/onStop only log events. The system may end a session when Kindle takes focus. Stop, screen-off, timeout, and host destruction request cleanup explicitly and are identified in the log.
- Start requests remain locked while cancellation is pending. Late callbacks are closed so they cannot revive a cancelled request. Exact system termination causes may be unavailable.
- A handler requests closure after three minutes; this is a bounded foreground experiment, not a guarantee that an Android process suspended or killed in the background can execute code at an exact deadline.
- Local log retention is 128 KiB and no network permission is requested. Image decoding uses a background thread with a maximum edge of 2048 pixels; no image bytes or document URI are exported.

## Real-device diagnostics

The in-app Save diagnostic report is the preferred first method. If it is insufficient, adb is already available in `%LOCALAPPDATA%\BookCoverBuild\sdk\platform-tools`. USB debugging and Samsung USB drivers may be needed for adb, and require separate guided phone steps. We have not enabled Developer Options or changed phone settings. Do not interpret an empty adb device list as evidence of display support or failure.

</details>
