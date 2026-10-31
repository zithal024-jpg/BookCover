# Galaxy Z Fold checklist

One sitting on the owner's Fold. These runs use the separate QA app, `com.bookcover.diagnostic.qa`. They do not install or test the main app `com.bookcover.diagnostic`.

Do the steps in order. Each test names the branch to check out. Stay with that branch until its command finishes, then check out the next branch.

## Before you start

1. On the phone, turn on USB debugging and tap Allow on the computer prompt. Connect the USB cable.
2. Unlock the phone, unfold it fully, and leave it open on the table. Set the screen timeout to 10 minutes so it does not lock during a run. Do not touch the screen while a command is running.
3. In PowerShell, from the BookCover folder, find the phone. The serial is the first column. Earlier notes for this Fold used `RFGL710TARJ`; use the serial this command prints.

```powershell
& "$env:LOCALAPPDATA\BookCoverBuild\sdk\platform-tools\adb.exe" devices -l
& "$env:LOCALAPPDATA\BookCoverBuild\sdk\platform-tools\adb.exe" -s DEVICE_SERIAL shell getprop ro.product.model
& "$env:LOCALAPPDATA\BookCoverBuild\sdk\platform-tools\adb.exe" -s DEVICE_SERIAL shell cmd device_state print-state
& "$env:LOCALAPPDATA\BookCoverBuild\sdk\platform-tools\adb.exe" -s DEVICE_SERIAL shell cmd device_state print-states
```

Replace `DEVICE_SERIAL` in every command below. The model should be the Fold. `print-states` must list more than one state, including `identifier=3`, while the phone is unfolded. Write both state lines in the results table.

4. Confirm the main app is still installed, then leave it alone. Do not run `adb uninstall` or `pm clear` for `com.bookcover.diagnostic`.

```powershell
& "$env:LOCALAPPDATA\BookCoverBuild\sdk\platform-tools\adb.exe" -s DEVICE_SERIAL shell pm path com.bookcover.diagnostic
```

A path means the main app is present. The test command installs only `com.bookcover.diagnostic.qa`. Gradle may remove that QA app when the run ends. That is expected.

5. Each run below is `scripts\Test-Device.ps1`. That script runs Gradle `connectedQaAndroidTest` with `testBuildType` `qa` (`app/build.gradle.kts`). The phone filter is `com.bookcover.diagnostic.qa.test` / `androidx.test.runner.AndroidJUnitRunner`, and its target package is `com.bookcover.diagnostic.qa`. Do not run `connectedDebugAndroidTest`, and do not point a command at `com.bookcover.diagnostic`.

After each checkout, write down `git rev-parse --short HEAD`.

## 1. Closed-book read (`PublicationLifetimeTest`)

Branch: `cursor/r1-zip-lifetime-8665` (pull request 5).

Class `com.bookcover.diagnostic.testing.PublicationLifetimeTest`, method `lazyZipStreamAfterPublicationCloseReportsAnIoError`.

```powershell
git fetch origin
git checkout cursor/r1-zip-lifetime-8665
git rev-parse --short HEAD
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-r1 -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.PublicationLifetimeTest#lazyZipStreamAfterPublicationCloseReportsAnIoError'
```

The script's Gradle filter is:

```text
-Pandroid.testInstrumentationRunnerArguments.class=com.bookcover.diagnostic.testing.PublicationLifetimeTest#lazyZipStreamAfterPublicationCloseReportsAnIoError
```

The instrumentation command that filter becomes, aimed only at the QA app:

```text
adb -s DEVICE_SERIAL shell am instrument -w -r -e class com.bookcover.diagnostic.testing.PublicationLifetimeTest#lazyZipStreamAfterPublicationCloseReportsAnIoError com.bookcover.diagnostic.qa.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: one test passes. A late read of the closed sample book throws an ordinary IO error, and the sample file is still there. There is no `zip file closed` crash.

Record: commit, pass or fail, the `OK (1 test)` line or the assertion text, and the `QA receipt:` path the script prints.

## 2. Reopen while the bars hide

Branch: `cursor/r2-keep-opened-passage-01d2` (pull request 6).

Class `com.bookcover.diagnostic.testing.ReaderContinuityTest`, method `systemBarResizeDuringReopenPlacementKeepsTheSavedPassage`.

```powershell
git fetch origin
git checkout cursor/r2-keep-opened-passage-01d2
git rev-parse --short HEAD
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-r2 -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ReaderContinuityTest#systemBarResizeDuringReopenPlacementKeepsTheSavedPassage'
```

Gradle filter:

```text
-Pandroid.testInstrumentationRunnerArguments.class=com.bookcover.diagnostic.testing.ReaderContinuityTest#systemBarResizeDuringReopenPlacementKeepsTheSavedPassage
```

QA instrumentation command:

```text
adb -s DEVICE_SERIAL shell am instrument -w -r -e class com.bookcover.diagnostic.testing.ReaderContinuityTest#systemBarResizeDuringReopenPlacementKeepsTheSavedPassage com.bookcover.diagnostic.qa.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: one test passes. After Back and reopen, the saved passage is still on screen when the system bars show and hide.

Record: commit, pass or fail, `OK (1 test)` or the `passage after resize during reopen placement` line, and the receipt path.

## 3. Four hinge checks

Branch: `claude/reader-test-fixes` (the source of the emulator triage). These four methods are the ones that stopped on `Needs device state 3 of a physical Fold` on the emulator.

| Test | Class and method |
|---|---|
| Ten fold cycles | `com.bookcover.diagnostic.testing.ReaderContinuityTest#tenFoldCyclesKeepOneReaderAndTheVisiblePassage` |
| RTL manga fold | `com.bookcover.diagnostic.testing.ReaderContinuityTest#rtlMangaKeepsItsResourceAndNativeNavigation` |
| Same place after fold | `com.bookcover.diagnostic.testing.ReaderLifecycleTest#foldingAndUnfoldingKeepTheBookAtTheSamePlace` |
| Idle deadline across fold | `com.bookcover.diagnostic.testing.ScreenIdleTest#recreationAndFoldingKeepTheOriginalDeadline` |

Check out once, then run all four in one command so the phone stays in one build:

```powershell
git fetch origin
git checkout claude/reader-test-fixes
git rev-parse --short HEAD
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-hinge -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ReaderContinuityTest#tenFoldCyclesKeepOneReaderAndTheVisiblePassage,com.bookcover.diagnostic.testing.ReaderContinuityTest#rtlMangaKeepsItsResourceAndNativeNavigation,com.bookcover.diagnostic.testing.ReaderLifecycleTest#foldingAndUnfoldingKeepTheBookAtTheSamePlace,com.bookcover.diagnostic.testing.ScreenIdleTest#recreationAndFoldingKeepTheOriginalDeadline'
```

Gradle filter (same four methods, comma-separated):

```text
-Pandroid.testInstrumentationRunnerArguments.class=com.bookcover.diagnostic.testing.ReaderContinuityTest#tenFoldCyclesKeepOneReaderAndTheVisiblePassage,com.bookcover.diagnostic.testing.ReaderContinuityTest#rtlMangaKeepsItsResourceAndNativeNavigation,com.bookcover.diagnostic.testing.ReaderLifecycleTest#foldingAndUnfoldingKeepTheBookAtTheSamePlace,com.bookcover.diagnostic.testing.ScreenIdleTest#recreationAndFoldingKeepTheOriginalDeadline
```

QA instrumentation command:

```text
adb -s DEVICE_SERIAL shell am instrument -w -r -e class com.bookcover.diagnostic.testing.ReaderContinuityTest#tenFoldCyclesKeepOneReaderAndTheVisiblePassage,com.bookcover.diagnostic.testing.ReaderContinuityTest#rtlMangaKeepsItsResourceAndNativeNavigation,com.bookcover.diagnostic.testing.ReaderLifecycleTest#foldingAndUnfoldingKeepTheBookAtTheSamePlace,com.bookcover.diagnostic.testing.ScreenIdleTest#recreationAndFoldingKeepTheOriginalDeadline com.bookcover.diagnostic.qa.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: four tests pass. Folding and unfolding keeps one reader on the same passage, the manga page still moves with the reader's own previous-page control, and folding does not reset the screen-awake deadline.

Record: commit, pass or fail for each of the four names, any `Timed out` or `Needs device state` line, `print-state` and `print-states` from setup, and the receipt path.

The same checkout, one method at a time, if you need to repeat a single failure:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-cycles -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ReaderContinuityTest#tenFoldCyclesKeepOneReaderAndTheVisiblePassage'
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-manga -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ReaderContinuityTest#rtlMangaKeepsItsResourceAndNativeNavigation'
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-place -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ReaderLifecycleTest#foldingAndUnfoldingKeepTheBookAtTheSamePlace'
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-idle -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ScreenIdleTest#recreationAndFoldingKeepTheOriginalDeadline'
```

## 4. Right-to-left swipe

Same branch as the hinge checks: `claude/reader-test-fixes`. Run this after section 3, or add the method onto that `-TestClass` list with a comma if you have not started section 3 yet.

Class `com.bookcover.diagnostic.testing.NativeReaderTest`, method `explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions`.

```powershell
git checkout claude/reader-test-fixes
git rev-parse --short HEAD
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-rtl -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.NativeReaderTest#explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions'
```

Gradle filter:

```text
-Pandroid.testInstrumentationRunnerArguments.class=com.bookcover.diagnostic.testing.NativeReaderTest#explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions
```

QA instrumentation command:

```text
adb -s DEVICE_SERIAL shell am instrument -w -r -e class com.bookcover.diagnostic.testing.NativeReaderTest#explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions com.bookcover.diagnostic.qa.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: one test passes. A swipe moves the page in the book's direction. On the emulator this wait often timed out (`Timed out waiting for native swipe advances in book direction`). A pass here means the swipe moved the page.

Record: commit, pass or fail, `OK (1 test)` or that timeout line, and the receipt path.

## Results

| # | Check | Branch | Commit | Pass or fail | What the run printed | Receipt path |
|---|---|---|---|---|---|---|
| 1 | Closed-book read | `cursor/r1-zip-lifetime-8665` | | | | |
| 2 | Reopen while the bars hide | `cursor/r2-keep-opened-passage-01d2` | | | | |
| 3 | Ten fold cycles | `claude/reader-test-fixes` | | | | |
| 4 | RTL manga fold | `claude/reader-test-fixes` | | | | |
| 5 | Same place after fold | `claude/reader-test-fixes` | | | | |
| 6 | Idle deadline across fold | `claude/reader-test-fixes` | | | | |
| 7 | Right-to-left swipe | `claude/reader-test-fixes` | | | | |

Device state at the start: `print-state` ________  `print-states` ________

If a run fails because the screen locked or the phone was folded, write "setup" in the result column and run that command again after unlocking and unfolding. Do not treat that as a pass.
