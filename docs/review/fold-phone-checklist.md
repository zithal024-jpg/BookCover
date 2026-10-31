# Galaxy Z Fold checklist

One sitting on the owner's Fold. These runs use the separate QA app, `com.bookcover.diagnostic.qa`. They do not install or test the main app `com.bookcover.diagnostic`.

Do the steps in order. Each test names the branch to check out. Stay with that branch until its command finishes, then check out the next branch.

## Before you start

1. On the phone, turn on USB debugging and tap Allow on the computer prompt. Connect the USB cable.
2. Unlock the phone, unfold it fully, and leave it open on the table. Write down the phone's current screen timeout first (Settings, Display, Screen timeout). Then set it to 10 minutes so it does not lock during a run. The last step of this checklist puts that written-down value back. Do not touch the screen while a command is running.
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

## 3. Three hinge checks

Branch: `main`. These three methods are already on `main`. Pull requests 5, 6, and 9 do not change them. Pull request 6 only adds the reopen test in section 2. Do not check out `claude/reader-test-fixes`.

| Test | Class and method |
|---|---|
| Ten fold cycles | `com.bookcover.diagnostic.testing.ReaderContinuityTest#tenFoldCyclesKeepOneReaderAndTheVisiblePassage` |
| RTL manga fold | `com.bookcover.diagnostic.testing.ReaderContinuityTest#rtlMangaKeepsItsResourceAndNativeNavigation` |
| Same place after fold | `com.bookcover.diagnostic.testing.ReaderLifecycleTest#foldingAndUnfoldingKeepTheBookAtTheSamePlace` |

Check out once, then run all three in one command so the phone stays in one build:

```powershell
git fetch origin
git checkout main
git rev-parse --short HEAD
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-hinge -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ReaderContinuityTest#tenFoldCyclesKeepOneReaderAndTheVisiblePassage,com.bookcover.diagnostic.testing.ReaderContinuityTest#rtlMangaKeepsItsResourceAndNativeNavigation,com.bookcover.diagnostic.testing.ReaderLifecycleTest#foldingAndUnfoldingKeepTheBookAtTheSamePlace'
```

Gradle filter (same three methods, comma-separated):

```text
-Pandroid.testInstrumentationRunnerArguments.class=com.bookcover.diagnostic.testing.ReaderContinuityTest#tenFoldCyclesKeepOneReaderAndTheVisiblePassage,com.bookcover.diagnostic.testing.ReaderContinuityTest#rtlMangaKeepsItsResourceAndNativeNavigation,com.bookcover.diagnostic.testing.ReaderLifecycleTest#foldingAndUnfoldingKeepTheBookAtTheSamePlace
```

QA instrumentation command:

```text
adb -s DEVICE_SERIAL shell am instrument -w -r -e class com.bookcover.diagnostic.testing.ReaderContinuityTest#tenFoldCyclesKeepOneReaderAndTheVisiblePassage,com.bookcover.diagnostic.testing.ReaderContinuityTest#rtlMangaKeepsItsResourceAndNativeNavigation,com.bookcover.diagnostic.testing.ReaderLifecycleTest#foldingAndUnfoldingKeepTheBookAtTheSamePlace com.bookcover.diagnostic.qa.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: three tests pass. Folding and unfolding keeps one reader on the same passage, and the manga page still moves with the reader's own previous-page control. On `main` these tests wait for the screen to change size. They do not print `Needs device state 3 of a physical Fold`.

Record: commit, pass or fail for each of the three names, any `display width changed` or `the app moves to the other screen` timeout, `print-state` and `print-states` from setup, and the receipt path.

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

## 4. Right-to-left swipe

Same branch as the hinge checks: `main`. This method is unchanged on pull requests 5, 6, and 9. Run this after section 3, or add the method onto that `-TestClass` list with a comma if you have not started section 3 yet.

Class `com.bookcover.diagnostic.testing.NativeReaderTest`, method `explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions`.

```powershell
git checkout main
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

## 5. Inner-screen timeout (BC-22)

Branch: `cursor/bc22-inner-screen-timeout-652d` (pull request 9).

This branch adds one phone test class, `com.bookcover.diagnostic.testing.ScreenIdleTest`, with four methods. `InnerScreenAwakeTest` is a computer test and is not part of this sitting. The phone tests move a clock forward. They do not wait out a real minute, so the manual steps after them are the check that the screen actually sleeps.

| Test | Class and method |
|---|---|
| Touch and page key renew | `com.bookcover.diagnostic.testing.ScreenIdleTest#onlyGenuineInputRenewsTheReadingScreen` |
| Fold keeps the deadline | `com.bookcover.diagnostic.testing.ScreenIdleTest#recreationAndFoldingKeepTheOriginalDeadline` |
| Leaving does not renew | `com.bookcover.diagnostic.testing.ScreenIdleTest#pauseReleasesAndResumeKeepsTheDeadline` |
| Always is remembered | `com.bookcover.diagnostic.testing.ScreenIdleTest#alwaysAndTheSavedChoiceSurviveRecreation` |

`recreationAndFoldingKeepTheOriginalDeadline` runs only here. It is the copy that pull request 9 adds. It is not part of section 3.

```powershell
git fetch origin
git checkout cursor/bc22-inner-screen-timeout-652d
git rev-parse --short HEAD
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-bc22 -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ScreenIdleTest#onlyGenuineInputRenewsTheReadingScreen,com.bookcover.diagnostic.testing.ScreenIdleTest#recreationAndFoldingKeepTheOriginalDeadline,com.bookcover.diagnostic.testing.ScreenIdleTest#pauseReleasesAndResumeKeepsTheDeadline,com.bookcover.diagnostic.testing.ScreenIdleTest#alwaysAndTheSavedChoiceSurviveRecreation'
```

Gradle filter:

```text
-Pandroid.testInstrumentationRunnerArguments.class=com.bookcover.diagnostic.testing.ScreenIdleTest#onlyGenuineInputRenewsTheReadingScreen,com.bookcover.diagnostic.testing.ScreenIdleTest#recreationAndFoldingKeepTheOriginalDeadline,com.bookcover.diagnostic.testing.ScreenIdleTest#pauseReleasesAndResumeKeepsTheDeadline,com.bookcover.diagnostic.testing.ScreenIdleTest#alwaysAndTheSavedChoiceSurviveRecreation
```

QA instrumentation command:

```text
adb -s DEVICE_SERIAL shell am instrument -w -r -e class com.bookcover.diagnostic.testing.ScreenIdleTest#onlyGenuineInputRenewsTheReadingScreen,com.bookcover.diagnostic.testing.ScreenIdleTest#recreationAndFoldingKeepTheOriginalDeadline,com.bookcover.diagnostic.testing.ScreenIdleTest#pauseReleasesAndResumeKeepsTheDeadline,com.bookcover.diagnostic.testing.ScreenIdleTest#alwaysAndTheSavedChoiceSurviveRecreation com.bookcover.diagnostic.qa.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: four tests pass. A touch and a page key start the hold again. Scrolling by code, rotating, folding, and coming back to the app do not. **Always** keeps the hold. Two minutes expires from the same last input.

Record: commit, pass or fail for each of the four names, any assertion line, and the receipt path.

The same checkout, one method at a time, if you need to repeat a single failure:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-input -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ScreenIdleTest#onlyGenuineInputRenewsTheReadingScreen'
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-bc22fold -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ScreenIdleTest#recreationAndFoldingKeepTheOriginalDeadline'
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-pause -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ScreenIdleTest#pauseReleasesAndResumeKeepsTheDeadline'
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId fold-sit-always -Serial DEVICE_SERIAL -PhoneReleaseEvidence 'Owner is here. Phone is free, unfolded, and unlocked.' -TestClass 'com.bookcover.diagnostic.testing.ScreenIdleTest#alwaysAndTheSavedChoiceSurviveRecreation'
```

### Look at the real screen

Do this in the QA app, `com.bookcover.diagnostic.qa`, after the command above. If that app is gone, install only its QA package from this task. Do not install `app-debug.apk` over the main BookCover app.

```powershell
& "$env:LOCALAPPDATA\BookCoverBuild\sdk\platform-tools\adb.exe" -s DEVICE_SERIAL shell pm path com.bookcover.diagnostic.qa
Get-ChildItem "$env:LOCALAPPDATA\BookCoverBuild\tasks\fold-sit-bc22-*\project-output\app\outputs\apk\qa\app-qa.apk"
& "$env:LOCALAPPDATA\BookCoverBuild\sdk\platform-tools\adb.exe" -s DEVICE_SERIAL install -r PATH_FROM_THE_PREVIOUS_LINE
& "$env:LOCALAPPDATA\BookCoverBuild\sdk\platform-tools\adb.exe" -s DEVICE_SERIAL shell am start -n com.bookcover.diagnostic.qa/com.bookcover.diagnostic.MainActivity
```

Skip `install` when `pm path` already prints a path. Write down what you see. These steps are your eyes, not a Gradle result.

1. Set the phone's own Display timeout to 1 minute and write that number down. Open a sample book. Tap the middle of the page, tap **More**, and under **Screen while reading** choose **2 min**. Leave the book open and do not touch it. Expected: the inner screen stays on for those 2 minutes, then the phone's own 1 minute runs, then the inner screen sleeps.
2. Wake the phone and set **Screen while reading** to **Always**. Leave it longer than 2 minutes plus the phone's timeout. Expected: the inner screen stays on.
3. Set **Screen while reading** back to **2 min**. Touch the page once. Later, press a page key once. Expected: each one starts the 2 minutes again from that moment. If the inner screen had already slept, that touch or page key wakes it and starts the time again.
4. With **2 min** still selected, note the time, then rotate the phone and fold and unfold it without touching the page. Expected: the remaining time keeps going. The 2 minutes do not start over. The inner screen still sleeps at the original deadline plus the phone's own timeout.
5. With a book open, look at the outside cover. Expected: its countdown still starts when the cover starts, and turning a page does not start that countdown over. **Stop** turns the cover off. Leaving the book, back to the library, also turns the cover off. That is the same cover behavior as before this change.

### Optional 60-minute battery reading

Skip this if you do not have the hour. It is not pass or fail. Write the conditions before you start, then read the battery at the start and after 60 minutes.

```powershell
& "$env:LOCALAPPDATA\BookCoverBuild\sdk\platform-tools\adb.exe" -s DEVICE_SERIAL shell dumpsys battery
```

Write down: QA package `com.bookcover.diagnostic.qa`, **Screen while reading** choice, phone Display timeout, cover on or off and its timer, brightness, airplane mode on or off, plugged in or not, clock time, and the battery `level` from that command. A comparable reading uses the QA app, charge above 80 percent, then unplugged, brightness left fixed, airplane mode on, **Screen while reading** at **5 min**, and the cover on with its 60-minute timer. Do not touch the page during the hour. Run the battery command again after 60 minutes and write the new time and level.

## Results

| # | Check | Branch | Commit | Pass or fail | What the run printed | Receipt path |
|---|---|---|---|---|---|---|
| 1 | Closed-book read | `cursor/r1-zip-lifetime-8665` | | | | |
| 2 | Reopen while the bars hide | `cursor/r2-keep-opened-passage-01d2` | | | | |
| 3 | Ten fold cycles | `main` | | | | |
| 4 | RTL manga fold | `main` | | | | |
| 5 | Same place after fold | `main` | | | | |
| 6 | Right-to-left swipe | `main` | | | | |
| 7 | Touch and page key renew | `cursor/bc22-inner-screen-timeout-652d` | | | | |
| 8 | Fold keeps the deadline | `cursor/bc22-inner-screen-timeout-652d` | | | | |
| 9 | Leaving does not renew | `cursor/bc22-inner-screen-timeout-652d` | | | | |
| 10 | Always is remembered | `cursor/bc22-inner-screen-timeout-652d` | | | | |
| 11 | Manual: untouched book sleeps | `cursor/bc22-inner-screen-timeout-652d` | | | what you saw | |
| 12 | Manual: Always stays on | `cursor/bc22-inner-screen-timeout-652d` | | | what you saw | |
| 13 | Manual: touch or page key restarts | `cursor/bc22-inner-screen-timeout-652d` | | | what you saw | |
| 14 | Manual: rotate or fold continues | `cursor/bc22-inner-screen-timeout-652d` | | | what you saw | |
| 15 | Manual: cover Stop, leave, countdown | `cursor/bc22-inner-screen-timeout-652d` | | | what you saw | |
| 16 | Optional 60-minute battery | `cursor/bc22-inner-screen-timeout-652d` | | | start level / end level | |

Device state at the start: `print-state` ________  `print-states` ________

If a run fails because the screen locked or the phone was folded, write "setup" in the result column and run that command again after unlocking and unfolding. Do not treat that as a pass.

When you are finished, set the phone's Display timeout back to the value it had before this sitting. Sections 1 through 4 use 10 minutes so the phone does not lock during a test. Section 5 sets it to 1 minute for the sleep check.

## Not shipping

`claude/reader-test-fixes` will not be merged. None of the hinge or swipe methods exist only there.

| Method | Where the command points | Only on `claude/reader-test-fixes`? |
|---|---|---|
| `ReaderContinuityTest#tenFoldCyclesKeepOneReaderAndTheVisiblePassage` | `main` (same method on pull requests 5, 6, and 9) | No. Keep it. |
| `ReaderContinuityTest#rtlMangaKeepsItsResourceAndNativeNavigation` | `main` (same method on pull requests 5, 6, and 9) | No. Keep it. |
| `ReaderLifecycleTest#foldingAndUnfoldingKeepTheBookAtTheSamePlace` | `main` (same method on pull requests 5, 6, and 9) | No. Keep it. |
| `NativeReaderTest#explicitRtlEdgesFollowBookDirectionAndPreserveNativeInteractions` | `main` (same method on pull requests 5, 6, and 9) | No. Keep it. |
| `ScreenIdleTest#recreationAndFoldingKeepTheOriginalDeadline` | Pull request 9, section 5 | The method ships with pull request 9. The old section 3 copy is dropped so it is not run twice. |

One extra assert, `Needs device state 3 of a physical Fold`, is only on `claude/reader-test-fixes`. It sits in `switchScreen`, in `foldingAndUnfoldingKeepTheBookAtTheSamePlace`, and in that branch's copy of `recreationAndFoldingKeepTheOriginalDeadline`. It makes an emulator fail immediately. The Fold still runs the real hinge checks on `main`, and the idle fold check on pull request 9, without that sentence. Port the assert later if emulator runs should fail fast. Do not drop the tests for lack of it.
