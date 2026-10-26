# Validation record

## Current: 0.4.0 typography, navigation and themes

The authoritative [review results](review/RESULTS.md) record 24 passing JVM tests, five passing native tests in each folded/unfolded layout, zero lint errors, actual library theme/persistence checks and the user's separate physical outside-cover confirmation. They also document the test-cleanup data-reset incident and limited recovery. Main APK: `output/BookCover-prototype.apk`; all subsequent native testing uses `com.bookcover.diagnostic.qa`. Baseline 0.3.0 and final product-review materials are under `review-deliverables/2026-10-26/`. The earlier records below remain history.

## Current: 0.3.0 EPUB prototype (version code 5)

**Physical phone result: user confirmed “Yes, the cover stays visible” while swiping the inside EPUB pages.** This is the user-observed dual-screen result for this prototype, in addition to the API logs and screenshots below. Dimming/AOD remain shelved.

October 26, 2026: Gradle assembleDebug, all 14 JVM tests, and lintDebug passed. Lint has zero errors; remaining warnings concern pinned dependency updates, target SDK 35, optional KTX helpers, and a transitive Timber style rule. The Windows local.properties writer now escapes the drive colon for the newer lint check. APK installed in place on SM-F976U / Android 17 and launched at 13:57 America/New_York. The library imported its two generated original EPUBs, extracted their covers and showed both records. A user-selected local EPUB then imported and opened successfully; its title/author/cover and first reading locator persisted. No document URI or book text is included here.

Unfolding while a sample EPUB was open recreated the host, reopened the book and started a new public exterior presentation. Logs report `Exterior cover view=1080x2520`, `visible=true`, and `onSessionStarted`. Text size/theme controls and Library return were exercised; Library return produced `onSessionEnded`. API events establish the route but do not independently verify the visible outside panel. Full EPUB gesture/bookmark/progress and physical cover observations are being checked against [the EPUB checklist](../PHONE-TEST-EPUB.md).

New tests cover metadata/collection/locator/bookmark JSON round trips, backwards-compatible defaults, invalid library identifiers, and stripping publication active content while preserving text, styles, images, SVG and chapter anchors. Existing nine pagination/display/color tests remain passing. Build log: `%LOCALAPPDATA%\BookCoverBuild\epub-prototype-build.log`. Output: `output/BookCover-prototype.apk` with adjacent SHA-256 file.

At 13:59–14:01, an ADB-driven test of **The Lantern Keeper** exercised a real left swipe (0% → 33%), center-tap controls, saving a bookmark, a Contents jump to 66%, return through the 33% bookmark, Library return, and reopening at 33%. Force-stopping and cold-launching preserved all four library entries then present, the sample's 33% position, and its bookmark. Reopening started a fresh outside session. The sample EPUB's chapter text and hidden-chrome reading layout were visually inspected from the actual inner-display screenshot; an exterior screenshot shows its complete cover and matching matte at 1080 × 2520. No AndroidRuntime crash appeared during this check. Two user-selected local EPUBs imported with metadata and covers and opened; their text is not reproduced in the validation notes. Long-duration reading, unusual/fixed-layout EPUBs, corrupted inputs through the picker, and physical screen observation remain broader testing work.

APK verification: v2 signature valid with the same local Android debug certificate, zipalign 16 KiB check exit 0. Size 50,490,262 bytes. SHA-256 `7d88ddf4f216ded9b6e73e80f65f9a1ba78553ddf7cf7ec60bbdf6ad0eeccf4c`.

0.2.2 user feedback: all other functions behaved as expected, but outer brightness control had no effect. Dimming/AOD are now shelved and their controls are absent from the EPUB prototype.

## Historical sample-reader validation

**Latest build: 0.2.2-reader-test (version code 4).** Complete FIT_CENTER artwork now sits on a dominant edge-color background in a match-parent view. An optional exterior-window brightness request is available; default is normal phone policy. Compilation, 9 unit tests, Android lint, signature and alignment checks passed. The new background's appearance and independent brightness response have not been observed on the phone yet. No true AOD mode or measured energy saving is claimed. See [AOD/power findings](AOD-AND-POWER.md).

After the user reconnected the phone, version 0.2.2 was installed successfully and cold-launched at 13:24:58 America/New_York on October 26, 2026. The app log confirms version code 4, foreground window focus, and successful reload of the existing 429 × 652 cover with its matching background prepared. Actual exterior view bounds/color appearance and independent brightness response await the user-observed unfolded test.

Artifact: `output/BookCover-reader-test.apk`, 24,411,389 bytes, SHA-256 `b58444a01706b09d381f53740dd1664964a19ebce54af5c61f8dbbee24a447fd`. The previous version is preserved at `output/BookCover-reader-test-0.2.1.apk`. Three new tests cover dominant matching color, transparent-edge handling/fallback, and preservation of the measured shade. The existing six pagination/display-policy tests still pass. Build log: `%LOCALAPPDATA%\BookCoverBuild\cover-background-build.log`.

**Previous build: 0.2.1-reader-test.** Continuous sample pagination was built, checked, installed and cold-launched on the phone. The user confirmed that the page fills properly and that Stop outer cover works. Exact two-minute continuous-reader duration remains unconfirmed for this build. The earlier 0.2 native sample reader was built, installed and exercised: the user confirmed the exterior cover stayed visible during swipes and appearance controls; logs recorded 39 page changes and the full programmed three-minute presentation. See [READER-TEST-RESULT.md](READER-TEST-RESULT.md) for that earlier evidence. The 0.1 baseline and earlier research notes below are retained as history.

## 0.2.1 pagination correction

The user observed only about four lines on each sample page. The cause was intentional diagnostic segmentation in 0.2: every short paragraph started a fresh page. Version 0.2.1 joins the sample paragraphs with their original paragraph breaks and measures a continuous publication against the whole available page area. Page ranges and reading position use global character offsets. Paragraph boundaries no longer force a page turn.

Compilation, packaging, six unit tests, and Android lint passed (0 lint errors; the same 5 nonblocking dependency/style warnings). Tests cover page-range continuity across paragraph breaks, exact-boundary/reflow anchors, percentage clamping, and Unicode/fallback progress, as well as the existing display-selection rules. The reader saved-state namespace changed once so old section-based slots cannot restore into the new global-offset/font fields. Cover preferences are unchanged.

Artifact: `output/BookCover-reader-test.apk`, version code 3 / 0.2.1-reader-test. SHA-256: `b502c12f53e3ad23ce70ee90ff21f61d07d896383977b4c1a3665a5213dc57bf`. Original 0.2 APK archived at `output/BookCover-reader-test-0.2.apk`. Build log: `%LOCALAPPDATA%\BookCoverBuild\reader-pagination-build.log`.

The phone was not attached during the build, then reconnected. At 13:05:14 America/New_York on October 26, USB installation returned Success and cold launch returned Status: ok. The app log confirms version 0.2.1-reader-test (3), window focus, and successful reload of the existing selected cover. The user subsequently reported “Page fills page properly” and “Stop outer cover works as well.” These confirm the visual layout correction and manual Stop on the physical device. Reflow under changed font sizes and exact two-minute sustained exterior persistence in this build still need explicit confirmation. A short final page is expected.

## Outer-cover sizing observation

The user reports that the outside image looks cropped/short on the tall narrow screen. The current renderer uses ImageView FIT_CENTER on black and has no fixed short image height; it preserves aspect ratio rather than deliberately cropping the bitmap. Last captured diagnostics show a decoded image of 429 × 652 and an outer display of 1080 × 2520. If the presentation view fills that display, fitting this image to its width yields roughly 1080 × 1641, about 65% of the screen height, with unused space above/below. This is a calculated explanation for letterboxing, not yet an inspection of the actual outer view bounds or proof that no image content is clipped.

Next distinguish blank bands around a complete cover from actual missing artwork. Full-screen fill would require cropping the sides for this aspect-ratio mismatch, stretching would distort the cover, and a tall-screen composition/background could fill the display while keeping the complete cover. No rendering change has been made during this investigation.

Date: October 26, 2026. Version: **0.1-diagnostic**, application ID `com.bookcover.diagnostic`.

## Completed on this Windows computer

| Check | Result |
|---|---|
| Kotlin, Compose and Android resource compilation; `assembleDebug` | Passed |
| `testDebugUnitTest` | 2 tests passed; 0 failures/errors |
| `lintDebug` | Passed with 0 errors and 5 warnings |
| Debug APK signature, `apksigner verify` | Passed; APK Signature Scheme v2, local Android Debug certificate |
| ZIP/native library alignment, `zipalign -c -P 16 4` | Passed |
| Packaged manifest inspected with aapt2 | Correct app ID, label, minimum SDK 30 and target SDK 35 |
| Packaged permissions inspected with aapt2 | Only AndroidX's app-local signature permission for non-exported receivers; no internet, photo-library, overlay, or foreground-service permission |
| Initial adb device inventory | No attached/authorized devices listed at build time; phone subsequently connected (see below) |

The two unit tests check that ordinary Presentation targets exclude the default display, the host display, private displays, off displays, and displays lacking presentation eligibility. They do not exercise Samsung hardware or prove that any outer screen can be activated.

The five lint warnings are three notices about newer UI/lifecycle library versions and two optional Kotlin extension-style suggestions. The project deliberately pins its current dependency versions. The Android backup configuration warning encountered initially was resolved with explicit backup/transfer exclusion rules.

An initial incremental rebuild failed because OneDrive marked generated resource directories read-only/reparse points. Generated build output and the scripted project cache were redirected into `%LOCALAPPDATA%\BookCoverBuild`; the subsequent full compilation and checks passed. The source remains in the requested project folder. No source files were deleted or moved for this correction.

## Delivered artifact

- File: `output/BookCover-diagnostic.apk`
- Size: **24,263,805 bytes** (approximately 23.1 MiB).
- SHA-256: `5fdb165186a31e4eebe1724d90d4c1687086fc85e14d9a18c0ba2f811d257e5f`
- Copy of checksum: `output/BookCover-diagnostic.sha256`.
- Debug signed for direct device installation, not a production store release.

Build reports live in `%LOCALAPPDATA%\BookCoverBuild\project-output\app\reports`. The local build log is `%LOCALAPPDATA%\BookCoverBuild\final-build.log`. Rebuilding can change the APK checksum; scripts/Build.ps1 regenerates the checksum file alongside it.

## First physical-device connection

After the user connected and authorized USB debugging, adb identified a Samsung **SM-F976U**, running **Android 17 / API 37**. Installing the delivered APK returned **Success**, and a cold launch returned **Status: ok**. The process was running and its private diagnostic log was readable. No personal phone files were inspected.

The first startup log showed vendor WindowManager extension version **10**, host display **0**, and the display in **DOZE** state (1080 × 2520 at that moment). The rear-facing area's presentation capability changed through UNSUPPORTED, AVAILABLE, and UNAVAILABLE during startup. These are transient API observations while the phone was dozing; they do not confirm that the exterior panel can show content or that the phone was unfolded. No presentation session had been requested. A copy of that log is saved locally at `output/BookCover-device-startup.txt` (ignored by Git).

## User-reported physical test and mission change

On October 26, 2026, the user reported that the selected cover appeared on the **physical outer display** while BookCover's diagnostic UI remained functional on the unfolded inside display. The user also reported that the cover did **not** continue when switching to Kindle. This establishes partial physical-device feasibility and a failed cross-app handoff on the tested configuration. It does not establish an Amazon-specific restriction; Android documents foreground-session termination as a possible cause. Detailed end-callback logs for that test have not yet been captured here.

At the mission-change investigation stage, no reader APK had yet been built. Version 0.2 now implements and tests the same-activity sample-reader approach; its current evidence is in READER-TEST-RESULT.md. See `docs/READER-MISSION.md` and `docs/READER-STRATEGY.md` for the staged EPUB plan.

## Still unverified

No emulator or Samsung Remote Test Lab was used. These remain unverified:

- Sustained reading, page turning, and appearance controls inside BookCover while the physical outer cover remains visible.
- The precise system callback/end reason for the failed Kindle handoff; Moon+ handoff has not been tested.
- EPUB rendering, import, reading-position restoration, and all other new reader features.
- Real phone behavior under lock, folding, heat, power saver, process termination, and repeated/long sessions.

The original Kindle-coexistence requirement failed in the user's test. The same-activity sample reader has now passed its initial physical test; sustained reading and the remaining cases above are governed by the newer test record. It is not yet a complete EPUB product.

## Reader concept preview checks

The separate interactive interface concept was checked in headless Chrome at conversation widths 736, 580, 390 and 320 pixels. Page turns, sample-book selection, opening/closing the library, appearance controls, light/night appearance, text-size changes and the simulated exterior-cover toggle worked without JavaScript page errors or horizontal overflow. Both layout variants were exercised and desktop/mobile screenshots were visually inspected. This validates the concept preview only; it does not execute the proposed reader on Android or prove physical display persistence.

The subsequent selected-design revision uses one immersive reading view. Verified behavior: the app header and page arrows start hidden; only book content and a small bottom-left percentage remain. Center tap reveals/hides overlay controls without reflowing the page. Left/right mouse drags and an actual browser touch-event swipe turn the sample pages; a vertical drag does not. Resume reading tucks the appearance panel away, and selecting a book returns to immersive reading. The simulated outer cover stays visible. Desktop/mobile screenshots were inspected, and the 736/580/390/320 layouts had no horizontal overflow or script errors. These were preview-only checks completed before native version 0.2, whose actual phone evidence is recorded above.
