# Independent setup, build, installation and evaluation

These instructions start from the review archive, without the author's SDK, caches, phone files or signing key. Exact final build/test outcomes and APK checksums are in [RESULTS.md](RESULTS.md) and the package's `builds/` directory. The included APK is a debug development artifact, not a production release.

## What is included and omitted

Included: complete app/build source, resources/assets, original sample EPUBs, tests, Gradle wrapper and dependency declarations, build helpers, review documentation, baseline snapshot and any successfully generated final APK/reports.

Obtain separately: a JDK 17 distribution, Android SDK command-line tools or Android Studio with the SDK manager, SDK platform/build/platform tools, Internet access for the first Gradle/dependency resolution, and a compatible device or emulator. Tool binaries, downloaded SDK archives, Gradle distributions, Maven caches, `local.properties`, private signing keys and the author's phone library are intentionally omitted. The wrapper JAR does not contain the Gradle distribution. The checked-in sample EPUBs/assets are ready to build; Python/Pillow is only needed to regenerate those samples, not compile the app.

## Recommended clean setup

1. Extract the review ZIP to a normal writable directory. Open its `source/` folder as the Gradle project. Avoid a cloud-synchronized build directory if it causes Windows file-lock/reparse failures.
2. Install JDK 17. Set `JAVA_HOME` to its installation directory and add its `bin` directory to the shell's PATH.
3. Install Android SDK command-line tools or use Android Studio's SDK manager. Set `ANDROID_HOME` to the SDK root.
4. Install **platform-tools**, **platforms;android-37.0** and **build-tools;36.0.0**. Accept the applicable SDK licenses using `sdkmanager --licenses`. The platform package is named `android-37.0`; the project declares compile SDK 37. If your SDK manager presents the platform by a human-readable API 37 name, select the matching stable platform package.
5. Do not import the author's `local.properties`; it is omitted. Gradle can use ANDROID_HOME, or you may create a local SDK-path property using forward slashes and an escaped Windows drive colon, for example `sdk.dir=C\:/Android/Sdk`.
6. The included wrapper downloads Gradle 9.7.0 and verifies the checksum pinned in `gradle/wrapper/gradle-wrapper.properties`. Dependency repositories are Google Maven, Maven Central and the Gradle plugin portal. Keep the pinned versions for the reproduction run.

## Windows PowerShell example

Replace only the example installation paths. Run from the extracted `source/` directory:

```powershell
$env:JAVA_HOME = 'C:\Tools\jdk-17'
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" 'platform-tools' 'platforms;android-37.0' 'build-tools;36.0.0'
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
.\gradlew.bat assembleDebug testQaUnitTest lintDebug lintQa --console=plain
```

On the shared Windows development machine, use `scripts/Build.ps1` or `Build BookCover.cmd`. The helper requires tools under `%LOCALAPPDATA%\BookCoverBuild` (or an explicit `-ToolRoot`) and an initialized shared resource registry; see [the task-resource guide](../BC-25-TASK-RESOURCES.md). It reserves BUILD, isolates generated output/cache/Gradle home by checkout and task, and prints the exact APK/checksum/evidence paths. `-TaskId core -Plan` shows the paths without running tools. It no longer updates `BookCover-prototype.apk`; old arbitrary output/cache overrides are rejected.

For an isolated reviewer output/cache directory, pass explicit locations:

```powershell
.\gradlew.bat assembleDebug testQaUnitTest lintDebug lintQa --console=plain --project-cache-dir 'C:\BookCoverReview\cache' '-Pbookcover.buildRoot=C:\BookCoverReview\generated'
```

## Linux/macOS example

Use command-line tools built for your host OS. Adjust the two environment paths:

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platform-tools' 'platforms;android-37.0' 'build-tools;36.0.0'
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
sh ./gradlew assembleDebug testQaUnitTest lintDebug lintQa --console=plain
```

Using `sh ./gradlew` avoids relying on ZIP preservation of the executable bit. The PowerShell build helper is unnecessary on Unix. With no LOCALAPPDATA and no `bookcover.buildRoot` override, conventional project-local `app/build/` and `build/` directories are used. An explicit override is also supported:

```sh
sh ./gradlew assembleDebug testQaUnitTest lintDebug lintQa --console=plain --project-cache-dir /tmp/bookcover-review/cache -Pbookcover.buildRoot=/tmp/bookcover-review/generated
```

These are cross-platform Gradle instructions; the recorded validation platform is identified in RESULTS.md. Do not treat the existence of a Unix command example as proof of a Linux/macOS build run.

## Locate artifacts and reports

Without an output override on Unix, the APK is `app/build/outputs/apk/debug/app-debug.apk`. With `bookcover.buildRoot=/some/path`, it is `/some/path/app/outputs/apk/debug/app-debug.apk`. The Windows helper prints its checkout/task-specific APK and report paths; use those exact paths.

JUnit XML appears under the module build directory's `test-results/testQaUnitTest/`; the readable report is under `reports/tests/testQaUnitTest/`. The QA unit task exercises the same production source plus the isolated QA source set; selecting `testBuildType="qa"` means `testDebugUnitTest` is not an available task in this configuration. Android lint HTML/XML reports appear under `reports/`; text reports can also be found under `intermediates/lint_intermediate_text_report/{debug,qa}/lintReport{Debug,Qa}/`. The package includes both debug and QA lint reports. Consult Gradle output for actual paths. Lint and JVM tests do not establish physical display behavior or all touch interactions.

After the first successful online build, `--offline` can use the same locally populated wrapper/dependency caches. This package is **not an air-gapped build bundle**: --offline fails if required artifacts are absent. Do not remove version pins or substitute new dependency versions to disguise a missing-cache failure.

## Run native tests on the isolated QA application

The final source defines `testBuildType = "qa"` and an application-ID suffix `.qa`. The native test target is **com.bookcover.diagnostic.qa**, separate from the user's normal **com.bookcover.diagnostic** library. Test-only host code is under `src/qa/` and does not enter the ordinary debug APK.

Use only the protected Windows device-test script. Keep the explicitly available phone unlocked, unfolded and untouched; replace the example serial and release evidence with the actual values:

```powershell
.\scripts\Test-Device.ps1 -TaskId core -Serial DEVICE_SERIAL `
    -PhoneReleaseEvidence 'RECORDED PREVIOUS-HOLDER RELEASE AND OWNER AVAILABILITY'
```

Use the same task ID as the matching build. The script reserves BUILD then PHONE and enforces the isolated QA target before any device operation. Do not bypass it with a direct connected-test Gradle command on another host.

**Never retarget connected instrumentation at the user's main app/package.** An earlier connected-debug run in this cycle triggered AGP uninstall cleanup and reset the main app's private data; the original provider-held EPUB files were not removed. See RESULTS.md for the actual incident/recovery record and any lost historical locators. The final QA variant is designed to prevent repeating that target mix-up. Connected test cleanup may remove the isolated QA app and its disposable data.

QA native XML reports are under the module output's `outputs/androidTest-results/connected/qa/`. Historical debug-target reports, when included, are labeled separately. Locked-screen or wrong-layout failures must be distinguished from an application assertion failure; do not silently classify an unexecuted/blocked test as passed.

## Install and launch

BookCover requires Android 11/API 30 or later. A phone/emulator without an available WindowArea rear presentation can evaluate the library and EPUB reader, but cannot establish the Samsung two-panel result.

1. Enable USB debugging on a test device, connect it and authorize the computer. Device setup is a human action; do not assume ADB availability grants access to unrelated phone content.
2. Check the connection, install the supplied or rebuilt APK, then launch:

```sh
adb devices -l
adb -s DEVICE_SERIAL install -r /path/to/BookCover-prototype.apk
adb -s DEVICE_SERIAL shell am start -n com.bookcover.diagnostic/.MainActivity
```

The installed package name remains `com.bookcover.diagnostic`; its launcher label is **BookCover**. Existing compatible app data should remain when a same-key upgrade is installed with `-r`.

**Signing difference:** private signing keys are intentionally absent. A reviewer rebuild generates/uses the reviewer's own local Android debug key, which normally differs from the key used for the packaged APK or the user's existing installation. Android rejects an update signed with a different key. Do not uninstall the user's app or delete its books/progress to force an update. Use a separate emulator/device, or build a deliberately separate review application ID in a separate copy of the project. Retaining a single signing identity for later updates is a release-management task outside this archive.

Manual installation is also possible by copying an APK to a test device, opening it from Files and allowing that installer source as needed. No Play Store publishing is performed by these instructions.

## Evaluate the application

Open a bundled original sample from the library, then import a local DRM-free EPUB through **Import EPUB**. The original selected file stays with its provider while BookCover stores its own copy. Use the final acceptance plan to test size/family reflow, position recovery, optional edge taps, native swipes, selection/links, fixed-layout behavior, automatic/manual library themes, persistence, contents/bookmarks and cover Start/Stop.

For the exterior-screen test, fully unfold a compatible phone and physically observe both panels while reading and changing settings. Android/Samsung may offer a consent dialog. Keep BookCover foreground. Capture human observation separately from session callbacks. An emulator or a screenshot alone cannot prove the user's physical panel stayed visible throughout an interaction sequence.

The earlier 0.3.0 user-confirmed result is documented as history. For this cycle, the user separately confirmed 0.4.0 outside continuity while changing typography and using swipes/optional edge taps. Final native tests passed in both folded and unfolded layouts. Read RESULTS.md for exact evidence, residual coverage and the instrumentation data-reset incident.
