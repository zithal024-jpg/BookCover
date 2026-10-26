# Revised mission prompt — BookCover Reader

## Current continuation brief — EPUB milestone achieved, October 26, 2026

Continue from **0.3.0-prototype / version code 5**, installed on the user's phone. Do not restart from the sample-only phase. The implemented baseline is a native cover grid, collections/search/sorting, local DRM-free EPUB import with private copies and duplicate detection, Readium 3.4.0 EPUB navigation, hidden center-tap controls, swipe page turns, small bottom-left percentage, text size/themes, contents, bookmarks and saved positions. Readium runs inside the same foreground activity as the public exterior presentation. Complete artwork uses the matching-color matte. APK: `output/BookCover-prototype.apk`; source/build/test guide: `docs/EPUB-PROTOTYPE.md`, `docs/VALIDATION.md`, and `PHONE-TEST-EPUB.md`.

Build, 14 JVM tests, lint, signing/alignment and phone swipe/bookmark/reopen/cold-restart checks passed. Two user-selected EPUBs imported and opened. The user separately confirmed that the **actual outside cover remains visible while swiping inside EPUB pages**. Preserve that successful route and the immersive reading requirements. Keep outer brightness/dimming and AOD shelved: the brightness request did not work on the phone. Anna's Archive is only an optional clearly labeled browser search shortcut; no supported catalog API or download integration has been established. Continue with user-requested refinements and broader real-book testing; do not claim production readiness, cloud sync, DRM support or Moon+ integration.

The original pivot brief below remains as architectural background; the milestone status above supersedes its earlier implementation and validation status.

Use this brief to continue this project here or in a future coding session. It replaces the original requirement to preserve an exterior presentation while an unrelated reader owns the inside display. The original brief remains in `docs/PROJECT-BRIEF.md` as history.

---

You are helping a complete beginner build **BookCover Reader**, a lightweight, open-source Android e-reader for a Samsung foldable. Handle the development work and explain required phone/computer actions in simple numbered steps. The working Windows project is `C:\Users\Frank\OneDrive\Documents\ChatGPT\Book Cover - App`.

## Established physical-device evidence

- The diagnostic APK installed and launched successfully on Samsung **SM-F976U**, Android **17/API 37**, vendor WindowManager extension **10**.
- The user reports seeing the selected cover on the **physical exterior screen** while the diagnostic controls operated on the unfolded interior screen. This is a real partial success, not an emulator simulation.
- The cover did **not** persist when switching to Kindle. This is consistent with the Android presentation host losing foreground eligibility; it does not establish an Amazon-specific block or explain every underlying system event.
- Sustained reading inside BookCover, Moon+ handoff, repeated sessions, and lock/fold behavior have not been validated. Do not mark them proven.

Read `docs/VALIDATION.md`, `docs/READER-STRATEGY.md`, and the existing Kotlin code before making changes. Preserve the functioning public display controller and diagnostic logging. Do not restart the project from scratch or replace it with a web-only reader.

## Revised product goal

BookCover itself owns the reading view across the full unfolded interior display and the cover presentation on the physically separate exterior display. Use one foreground **host activity** for both the reader and its presentation session. Keep library, reading settings, contents, and book navigation inside that host activity using Compose screens/sheets or an embedded reader fragment. Being in the same application package alone is insufficient if a new activity replaces the presentation host.

Aim for Moon+-style practicality: clean typography, comfortable margins, page/scroll navigation, accessible controls, reading-position memory, and a small local library. Use original branding and UI. Do not promise feature parity with Moon+ or copy its code/assets without a license.

## Reading interaction requirements

- **Horizontal touch swipes are the primary page-turn mechanism.** Swipe left to advance and right to return for the initial left-to-right reading scope.
- The default reading view shows **only the book's page content and a small completion percentage at the bottom left**. Hide the app header, book/session status, settings, page arrows, and other navigation chrome while reading.
- Paragraphs must flow continuously through the available page area before a page turn. Do not force each short sample paragraph onto its own page. The final page can naturally be shorter; font/viewport changes should reflow content and preserve a global character anchor.
- A **center tap reveals the controls temporarily**. Another center tap or a clearly labeled **Resume reading** action hides them again. Keep the page-arrow buttons available only in this revealed state as secondary accessible/keyboard navigation.
- Library, appearance controls, exterior-cover controls, and returning to reading must stay within the same foreground host activity so revealing or hiding them does not itself hand off the display session.
- For EPUBs, derive the completion percentage from the engine's **whole-book progression**, not chapter-only progress or a fixed printed-page count. Clearly distinguish sample-text progress before EPUB integration.

These are the user's selected defaults, not optional visual alternatives. Do not add extra gestures or persistent reader toolbars unless requested.

## First implementation milestone: isolate the lifecycle question

**Current status:** version 0.2 implements this sample-reader milestone and passed its initial phone proof: user-confirmed outer cover during reading gestures/settings, 39 page-change events, and a three-minute session ended by the programmed timeout. Version 0.2.1 fixes full-page pagination, and the user has confirmed both full-page text and manual Stop. Exact two-minute continuous reading duration remains unconfirmed, and the outside-image aspect-ratio/short-height observation is under investigation. Read `docs/VALIDATION.md` and `docs/READER-TEST-RESULT.md` before repeating or extending this work.

1. Extend the current diagnostic into a minimal **sample-text reader in the existing host activity**. Keep the diagnostic route accessible.
2. Add several pages of clearly labeled original sample text with horizontal swipe page turns and the immersive default described above. A center tap reveals secondary previous/next controls, font size, and light/dark appearance; another center tap or Resume reading hides them. All controls must operate without launching another activity.
3. Retain manual JPG/PNG cover selection and existing exterior presentation support. Select files before starting the session because Android's external picker may interrupt it.
4. Provide an obvious exterior-cover on/off action, accurate session status, and a bounded test timeout. Keep the existing three-minute limit for the first experiment unless a deliberate, clearly labeled change is required.
5. Build an APK, run appropriate checks, and record exactly what was verified. If the user has authorized USB installation and the intended device is connected, install and launch it; guide any phone-side confirmation.
6. Physical acceptance test: while the actual outside cover remains visible, turn at least ten sample-text pages by swiping, reveal controls with a center tap, change font/theme within BookCover, and hide the controls again. Confirm that the reading state shows only page content and the small bottom-left completion percentage. Continue for at least two minutes within the three-minute session. Return explicit results for both screens and logs. No handoff to Kindle/Moon+ is part of this new path.

Do not describe an interactive concept as a functioning Android reader. Gate EPUB integration on this physical test, or clearly document why a narrow integration spike is useful before the test can occur.

## EPUB integration milestone

Use a maintained open-source EPUB engine rather than writing an EPUB parser/layout engine from scratch. **Readium Kotlin is the current preferred candidate.** Research found stable 3.4.0, BSD 3-Clause licensing, minimum API 24, and compile SDK 37 requirements as of October 26, 2026; verify pinned artifact metadata and toolchain compatibility during implementation. Our diagnostic currently compiles against SDK 35, so integration requires a deliberate build-tool spike. Prefer Maven binaries and stable `EpubNavigatorFragment` hosted in a FragmentActivity with Compose controls; experimental Compose navigator APIs are optional.

Start with **one local DRM-free reflowable EPUB**, imported through Android's document picker. Verify actual pagination/scrolling, contents navigation, restoring a Readium locator, and both physical displays. Prefer the EPUB's embedded cover with the existing manual image selection as an override. Retain aspect ratio. Reject unsupported/protected books with a clear message; do not extract or remove Kindle DRM.

Keep publication resources local by default and verify the reader engine's handling of publication-supplied scripts, external links, and remote resources. Do not disable engine-required JavaScript blindly. Record dependency licenses and retain required notices. Do not publish the source or APK externally without a user request.

## Focused MVP after the gates pass

- Import/remove local DRM-free EPUBs; display title, author, and cover in a small library.
- Remember the last selected book and reading location.
- Table of contents and bookmarks.
- Font size/typeface, spacing/margins, and light/sepia/dark themes supported by the chosen renderer.
- Primary horizontal swipe page turns, immersive reading with only a small bottom-left whole-book completion percentage, and center-tap controls with secondary accessible page arrows. Use actual reflow positions rather than claiming fixed printed-page counts.
- Exterior cover toggle/stop, Fit/Fill behavior, and manual cover override.
- The selected exterior treatment preserves the complete artwork against a matching-color background that fills the tall screen. Avoid stretching or cropping title/artwork by default. An optional per-window outer brightness request is available from 0.2.2, but independent response/power savings require phone validation. See `docs/AOD-AND-POWER.md`: no supported public true-AOD integration has been established for concurrent inside reading.
- Graceful lock, folding, activity recreation, and system session termination. Preserve reading position; request a fresh presentation when necessary rather than promising uninterrupted activity in unsupported states.
- Clear status when exterior mode is unavailable. The reader should still work inside.

Defer PDF, proprietary formats, TTS, cloud sync, stores, automatic downloads, dictionaries, advanced annotations, and page-curl effects. Measure APK size, load time, memory, and battery behavior before calling the implementation lightweight. Do not guarantee a tiny APK or indefinite static-screen operation.

## Moon+ investigation remains an alternative

The reviewed official Moon+ site and FAQ document no public embeddable engine, plugin/SDK route, or open-source code license. Launching unmodified Moon+ from BookCover may repeat the foreground-session failure; that has not been tested. A native Moon+ implementation or an officially supported integration supplied by its developer is a viable alternative to explore. `docs/MOONPLUS-FEATURE-REQUEST.md` contains a draft only. Do not contact anyone without explicit authorization.

## Working conventions

- Use Kotlin, standard Android tools, and Compose for straightforward app controls.
- Keep the current public WindowArea/Presentation access; no rooting, protected-system edits, private API bypass, overlays, or accessibility tricks to force background display ownership.
- Never substitute an interior split-screen cover for the physically separate exterior display.
- Source stays in the OneDrive project folder. Generated Windows output is redirected to `%LOCALAPPDATA%\BookCoverBuild` to avoid the observed OneDrive resource-merger access error. Use the existing build script.
- Keep a dated validation record distinguishing user-reported physical observations, captured logs, automated tests, and interface concepts.
- Show realistic, buildable interfaces and keep the scope incremental. Build and diagnose autonomously; ask only for actions or information that genuinely require the user.

Begin by checking the existing implementation and planning the smallest same-activity sample-reader change. Carry out that first milestone when implementation is requested; do not attempt the whole reader at once.

---

References: [Android display modes](https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes), [Readium Kotlin](https://github.com/readium/kotlin-toolkit), [Readium 3.4.0](https://github.com/readium/kotlin-toolkit/releases/tag/3.4.0), [Moon+ official site](https://www.moondownload.com/), [Moon+ FAQ](https://www.moondownload.com/faq.html).
