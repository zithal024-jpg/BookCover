# Test BookCover's built-in sample reader

Use this guide with **version 0.2.2-reader-test**, installed from **BookCover-reader-test.apk**. This version reads bundled sample text inside BookCover. Importing your own EPUB books comes after this display test passes.

In 0.2.1, the sample paragraphs flow continuously to fill the available reading area. Page count depends on display size and font size; it is no longer fixed at 12 short pages. The final page can naturally be shorter. When updating from 0.2, sample reading position/appearance can start fresh once, while the selected-cover preference is preserved.

In 0.2.2, the outside screen shows the complete cover against a matching background color. Check that the color fills the unused top/bottom area and no artwork is cut off. The new Outer cover brightness slider is in setup and the revealed reader controls. It defaults to Phone setting. Try a lower request and confirm whether only the outside screen dims, then choose **Use phone setting** to restore normal policy. The phone may ignore independent requests; report what you actually see. This is not an AOD mode or a measured battery-saving result.

The question for this test is: **does the physical outside cover stay visible while you read, swipe pages, and change settings inside BookCover?** Keep BookCover open throughout the test.

## 1. Let Codex install the update

1. Connect the phone to this computer using the same USB data cable as before.
2. Unlock the phone. Leave USB debugging enabled. If **Allow USB debugging?** appears, tap **Allow**.
3. Tell me **“connected”** in this chat. I can check the connection, install the new APK, and launch it.

The update uses the same app as the earlier diagnostic, **BookCover Test**. You should not need to uninstall anything. Your selected cover should reappear if its image is still available and Android's saved file access remains valid.

If you prefer to install it yourself, the file is in this computer's project folder under **output → BookCover-reader-test.apk**. Follow the USB copy and My Files installation steps in [START-HERE.md](START-HERE.md), using this new filename instead of the earlier diagnostic APK. No Android Studio or other development software is needed.

## 2. Prepare the phone

1. Fully unfold the phone and keep it unfolded for this first test.
2. Open **BookCover Test** if it is not already visible.
3. If the cover preview is missing, tap **Select JPG or PNG** and choose a cover image. Do this before starting the exterior display session. Image selection is disabled while a session is active.
4. For a simple baseline, turn off Power saving mode temporarily and avoid testing when the phone is unusually hot. Disconnect external monitors and screen casting so an external display cannot be mistaken for the phone's outside panel.

You may tap **Read sample without cover** to practise the gestures first. That checks the reader controls only; it does not establish that both physical screens work together.

## 3. Start the two-screen reading test

1. On the setup screen, tap **Start cover + read sample**. Accept Android's display confirmation if one appears.
2. Expect the sample reading page on the inside display. While reading, only the book content and a small completion percentage at the **bottom left** should remain visible.
3. Look at the actual outside screen, or have someone else check it. Your selected cover must be visible there. A status message alone is not proof.
4. Note the time when the cover appears. The test session lasts at most **three minutes from its start request**.

If the Start button is unavailable, stay fully unfolded and check the display status on the setup screen. Tell me its wording. Do not switch to a different display route just to make the button active.

## 4. Read and exercise the controls

Keep the actual outer cover under observation during these actions:

1. **Swipe left** across the reading page to advance. **Swipe right** to go back. Make at least **ten page turns** in total; you can alternate forward and backward.
2. Check that the small percentage at the bottom left changes with your sample-text position. It describes this bundled sample, not progress in an imported EPUB.
3. **Tap the center of the page** to reveal the controls. The top controls and secondary page arrows should appear.
4. Change the text size and try a reading theme: **Paper**, **Sepia**, or **Night**. The exterior cover should remain visible while you use these controls.
5. Tap the page center again or choose **Resume reading**. The controls should tuck away, leaving only the page content and bottom-left percentage.
6. Continue reading and swiping for **at least two minutes** while the exterior cover remains visible.

**Stay inside BookCover.** Do not open Kindle, Moon+, another app, or the image picker during this test. We are testing the new reader that owns both displays within the same activity.

## 5. End the test and collect the result

After at least two minutes, before the three-minute limit if possible:

1. Tap the page center to reveal the controls.
2. Stop the exterior cover using its stop control. Check that the outside image disappears.
3. Tap **Setup**. Keep the phone connected to USB.
4. Tell me **“reader test finished”** and describe what you observed. I can retrieve BookCover's diagnostic log directly over USB.

If USB is unavailable, return to setup after the session ends and use **Save diagnostic report**. Save the text file in Downloads, then attach it in this chat. The report contains display and app events, not your cover image or its file location.

## How to report success or failure

**Pass:** for at least two minutes, the actual exterior cover stays visible while the inside reader remains usable, at least ten swipe page turns work, and revealing/changing/hiding reading controls does not interrupt the cover. The default reading view contains only book content and the small bottom-left percentage. The stop control then ends the cover session.

**Report a problem** if the outer image disappears early, the reader freezes, swipes do not turn pages, controls cannot be revealed or hidden, or you see an error. Tell me what action immediately preceded it and approximately how long the cover had been visible. Do not guess the cause; the log may help distinguish it.

**The three-minute cover timeout is expected.** It is not itself a failure. If you did not finish the two-minute exercise before that timeout, return to setup and start another short test. Locking, folding, or leaving BookCover can also end the session; test those separately after the basic reading test passes.

With your result, please include:

- Did the image remain on the **physical outside screen** throughout the two-minute reading test?
- Did swipe turns and the bottom-left percentage work?
- Did the cover survive opening the controls and changing font size/theme?
- Did the controls tuck away again, and did the exterior stop control work?
- If anything failed, what happened immediately before it?

No build, log message, or preview can replace your visual confirmation of the two physical displays. Passing this test is the gate for adding a real EPUB engine and book library.
