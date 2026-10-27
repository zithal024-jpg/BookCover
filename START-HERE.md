# Start with BookCover

BookCover is now a local EPUB reader, version 0.4.0-prototype (`versionCode` 6). The original
diagnostic and Kindle-handoff instructions are preserved below as history.

1. Read [the project overview](README.md), the
   [active work queue](docs/review/ACTIVE-BACKLOG.md), and the
   [completion tracker](docs/review/EXPERT-PANEL-REVIEW.md) for current status.
2. For a fresh build, use [the development guide](docs/DEVELOPMENT.md) and its
   supported build helper (`scripts/Build.ps1`). Use the exact APK (`output\BookCover-<task>-<hash>.apk`)
   path printed by the successful build; old filenames under `output/` are historical artifacts.
3. To update the owner's or a tester's app (`com.bookcover.diagnostic`), install in place over
   the existing app (`adb install -r "<EXACT_APK_PATH>"` or **Update** from the phone's Files app)
   using the same signing key. Keep its library and settings: **never uninstall it or clear its data**
   as a troubleshooting step, because doing so permanently deletes privately stored EPUBs, covers,
   unexported reading positions/bookmarks, and Reading Streak history. If Android reports a
   signature mismatch (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) or failed update, stop and check the
   signing certificate—see [Tester Installation and Safe In-Place Update Guide](README.md#tester-installation-and-safe-in-place-update-guide)
   and [CLAUDE.md](CLAUDE.md#build-and-test-windows-this-pc).
4. Use **Settings → Backup → Export backup** to save a JSON backup of reading positions,
   named bookmarks, and collections matched by SHA-256 book ID. Note that EPUB book files,
   custom cover images, and Reading Streak history (`library/reading-days.json`) are not stored
   inside the JSON backup; see [Backup, Restore, and Storage Behavior & Known Limitations](README.md#backup-restore-and-storage-behavior--known-limitations).
5. Automated device testing requires owner availability (for physical phone runs) and targets
   only the isolated QA app (`com.bookcover.diagnostic.qa`) through `scripts/Test-Device.ps1`;
   see [the task-resource guide](docs/BC-25-TASK-RESOURCES.md). Only the owner looking at the
   physical outside screen verifies exterior cover display.

<details>
<summary>Original diagnostic and Kindle-handoff guide (historical)</summary>

# Put BookCover Test on your phone

**This guide describes the original diagnostic APK and provides the manual APK-install steps.** For the current 0.2 sample-reader test, use [PHONE-TEST-READER.md](PHONE-TEST-READER.md) and the filename **BookCover-reader-test.apk**. The original Kindle-handoff test failed in the user's test; the new test stays inside BookCover.

This is a diagnostic app, not a finished reading app. We need a real-phone result before building the library features.

## 1. Copy the app to your phone

1. Connect the unlocked phone to the computer with a USB cable. If a USB notification appears on the phone, tap it and choose **File transfer / Android Auto** (wording may vary). Allow file access if asked. Keep the phone unlocked.
2. On Windows, open **File Explorer** (the yellow folder icon). Open this folder:

   `C:\Users\Frank\OneDrive\Documents\ChatGPT\Book Cover - App\output`

3. Find **BookCover-diagnostic.apk**. An APK is an Android app installer. Right-click it and choose **Copy**.
4. In File Explorer, choose **This PC**, open your phone, open **Internal storage**, then **Download**. Right-click an empty space and choose **Paste**. You should see the APK on the phone. If the phone is absent from This PC, try another USB port or a data-capable cable.
5. If needed, copy a JPG or PNG book-cover image to the same Download folder. A modest image is sufficient; the app scales large images down.

## 2. Install it

1. On the phone, open **My Files**, then **Downloads**.
2. Tap **BookCover-diagnostic.apk**, then **Install**.
3. If Android asks whether **My Files** may install unknown apps, use the Settings link in that prompt, allow this source, go back, and tap Install again. You can turn that permission off after installation.
4. Open **BookCover Test**. You should see a device-test screen with **Select JPG or PNG**.

Samsung labels and security prompts vary by One UI version. If installation is blocked without an ordinary permission prompt, stop and tell me the exact message. You do not need to change Developer Options for this method.

## 3. Test public dual-screen support

1. Fully unfold the phone. Keep it in this position during the first test. Disconnect external displays and turn off screen casting so they cannot be mistaken for the exterior panel. For a baseline, turn off Power saving mode temporarily and avoid testing when the phone is unusually hot.
2. Open BookCover Test on the inner display.
3. Tap **1. Select JPG or PNG**. Pick your image in Android's file picker. Expect a small preview in BookCover.
4. Read **Public dual-screen capability**. If the Start button is available, tap **2. Start exterior-screen test** and accept Android's confirmation dialog if it appears.
5. Look at the physical outside display, or have someone look at it. The image should appear there while BookCover's controls remain on the inside. **A session-started message does not prove this.**
6. If both screens behave correctly, tap **3. Open Kindle**. If Kindle is not found, use Home or Recent apps to open your normal e-reader manually.
7. Open a book in Kindle, keep Kindle filling the unfolded inside display, and turn several pages for **at least 60 seconds**. The cover must remain visibly present on the separate outside screen. Complete this before the three-minute test timeout.
8. Use Recent apps to return to BookCover. Tap **Stop** if still enabled. Wait for the session-ended status.

If the outer image disappears when Kindle opens, remember when it happened. That is a failed Kindle coexistence test even if the initial dual-screen test worked. The app deliberately leaves its session alone in onPause/onStop; Android may end it independently.

If Start is unavailable, tap **Check displays again** once while fully unfolded. If it stays unavailable, proceed to the report steps. If an optional **Test display …** button appears, you may try that public standard Presentation route. Repeat steps 5–8 and identify the actual physical screen. This optional target may be an external or virtual display, which does not satisfy the goal. No such button means no eligible secondary target was exposed in the current state.

If you press Stop while Android is still asking permission, dismiss that system dialog. BookCover waits for the pending system request to end before it allows another start. If it never responds, remove BookCover from Recent apps and reopen it.

## 4. Save and return the result

1. With the session ended, tap **Save diagnostic report**. If the session ended itself, no Stop is needed.
2. Choose **Downloads** in Android's save dialog, then tap **Save**. Keep the filename **BookCover-diagnostics.txt**.
3. Copy that text file back to the computer through File Explorer, or attach it in this chat from your phone.
4. Tell me these four observations with the file:

   - Did the selected cover appear on the actual physical outside screen?
   - Did the inside remain usable across the full screen?
   - Did the cover stay visible through at least 60 seconds of Kindle page turning?
   - If it stopped, did it stop when Kindle opened, when the phone moved/folded/locked, or after the test timeout?

The report includes the phone's model and software build automatically. You may inspect it before sharing. It contains no cover image or selected-image URI. Some system session endings provide no exact reason; the event timing helps us diagnose them without guessing.

## 5. Only after the first test passes

Repeat once to establish that success is repeatable. Then run separate short tests for lock/unlock, fold/unfold, and Power saving mode. BookCover intentionally stops for screen-off and for host-activity destruction/recreation. Return to it and start a fresh session after those events; automatic resumption is not part of this prototype.

If public support is absent or the Kindle handoff fails, we will investigate the report before adding features. No emulator result can establish that the Samsung physical exterior panel works.

</details>
