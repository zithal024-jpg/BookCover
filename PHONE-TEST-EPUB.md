# BookCover 0.4.0 prototype phone test

Current-cycle checks and results are in [the acceptance plan](docs/review/ACCEPTANCE-AND-TEST-PLAN.md) and [review results](docs/review/RESULTS.md). Use the actual **BookCover** app for personal reading; automated tests use a separate **.qa** installation through `scripts/Test-Device.ps1`. Do not retarget connected instrumentation to the main app.

New controls: center tap → **Typography**, then A−/A+, 80–220% slider or Serif/Sans serif/OpenDyslexic. Text reflows and returns near the previous paragraph; fixed-layout books explain why these controls are disabled. **Edge-tap page turns** is optional and initially off. Its narrow left/right bands supplement native swipes; links and selection retain their own behavior. Library → Settings offers Automatic (Book Cover), Paper, Forest, Midnight and Charcoal, independently of page colors. Manual library themes remain until Automatic is selected again.

The user confirmed this version keeps the actual outside cover visible while changing typography and using swipes/optional edge taps. Native tests passed folded and unfolded. An earlier test-runner cleanup reset the app's private data; books/covers were restored from original files, but old exact locations/bookmarks were unavailable. The separate QA package prevents that cleanup from targeting the reader again; see the explicit incident record in review results.

The original EPUB milestone procedure below remains useful for regression checks.

This version replaces the sample-only screen with a local EPUB library and a Readium reader. It updates the same installed package; its launcher label is now **BookCover**. Brightness controls and AOD are shelved after the phone's brightness test failed. The earlier diagnostic APKs remain in `output`.

1. Fully unfold and unlock the phone. Open **BookCover**. Two original books appear in **Samples** with cover thumbnails.
2. Tap **The Lantern Keeper**. If Android offers its exterior-display consent dialog, accept it. The inside should show the EPUB text and a small completion percentage at bottom left. The outside should show the complete cover on a matching-color background.
3. Swipe left at least five times, then right twice. Confirm text fills each page. Tap the middle to reveal controls; change text size and theme, then **Resume reading**. Confirm the physical outside cover persists throughout. Arrows appear only inside the hidden controls.
4. Tap the middle, **Bookmark**, then **Contents**. Jump to another chapter and return using the saved bookmark. Tap **Library**, reopen the book, and check the last reading position. Restarting the app should retain progress, bookmarks, covers and collections.
5. From **Library**, choose **Import EPUB**. Use Android's file picker to select a DRM-free `.epub` from Downloads or another document provider. BookCover keeps a private copy. Title, author, embedded cover and chapters should appear; a missing cover gets a generated text cover. Import the same file twice to check duplicate detection.
6. Use a book's **⋮** menu to move it to a named collection. Search by title/author and try Title/Author/Recent sorting. Removing a local copy asks for confirmation and leaves the original file alone.
7. In reading controls, test **Stop outer cover**, then **Start outer cover**. Returning to Library, locking the phone, the selected timer, or activity recreation ends/requests closure of the outside session. Reading itself remains available without outside-screen support.

Library **Settings** offers automatic outside-cover start and 30-minute, 60-minute, or Manual cover sessions. Manual lasts until stopped or the system ends the session. Activity recreation reopens the book at its saved location and makes a fresh outside-display request when enabled and available. Android/Samsung still controls availability; a callback is not proof of a visible physical panel.

**Optional search:** Library → Settings → **Anna's Archive · browser search** opens a Google query containing that name and your book query. No archive API, embedded catalog or downloading is implemented. Import a local EPUB through Files afterward.

This prototype supports local DRM-free EPUBs, including Readium's reflow rendering, table of contents and native swipe navigation. PDF, Kindle formats, DRM, cloud sync, annotations and text-to-speech are outside this milestone. Fixed-layout EPUBs may use the publisher's layout rather than obey all text settings.
