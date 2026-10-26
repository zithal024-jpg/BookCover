# BookCover — local EPUB reader prototype

**Current version: 0.4.0-prototype (code 6).** Local DRM-free EPUB reading now offers Serif, Sans serif and bundled OpenDyslexic fonts, 80–220% CSS text size with 10% steps and slider, and approximate semantic-position recovery after reflow. Fixed-layout EPUBs keep their publisher layout and disable typography controls. Swipes remain primary; optional narrow edge taps are off by default. The library defaults to a palette derived from the most recently read cover, with persisted manual light/dark themes.

Begin with [the independent review overview](docs/review/OVERVIEW.md), [build/install guide](docs/review/BUILD-AND-INSTALL.md), [validation results](docs/review/RESULTS.md), and [known limitations](docs/review/KNOWN-LIMITATIONS.md). The preserved pre-change baseline and final review folder/ZIP are under `review-deliverables/2026-10-26/`. Current APK: `output/BookCover-prototype.apk`. Run **Build BookCover.cmd** for the app, 24 JVM tests and lint; native device tests use the separate `.qa` application through `scripts/Test-Device.ps1`.

**Testing incident:** an initial connected test targeted the main package and its runner uninstalled it during cleanup. Original EPUBs were unaffected; the two imported books/covers and two samples were restored. Deleted exact prior reading locators/bookmarks were unavailable. This is documented in the review results; all subsequent connected tests target an isolated QA app. Private recovery files and keys are excluded from review archives.

The following 0.3.0 record is retained as history. That version introduced the native cover library and Readium EPUB reader, with private file copies, collections, search/sorting and saved locations. Two original sample books are included.

Reading uses swipes, hidden center-tap controls and a small bottom-left completion percentage. Contents, bookmarks, text size and Paper/Sepia/Night themes stay in the same host activity as the exterior cover. The full cover uses the matching-color background; dimming and AOD are shelved after the phone brightness control did not work.

**Installable APK:** `output/BookCover-prototype.apk`. It updates the existing package in place; the launcher now says **BookCover**. Build, 14 JVM tests and Android lint pass, as do APK signature/alignment checks. Installed on the Samsung phone: two local EPUB imports/opening, sample swipes, chapter/bookmark navigation, and saved progress across reopening and a cold restart were exercised. Actual inner/exterior screenshots were inspected. **The user confirmed that the physical outside cover stays visible while swiping inside EPUB pages.**

Start with [the EPUB phone test](PHONE-TEST-EPUB.md) and [architecture/scope](docs/EPUB-PROTOTYPE.md). Double-click **Build BookCover.cmd** to rebuild and run checks. Older APKs remain in `output`.

The sections below describe earlier diagnostic versions and are retained as history.

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
