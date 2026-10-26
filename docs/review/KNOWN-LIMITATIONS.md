# Known limitations, open issues and next priorities

Review target: 0.4.0. Read [RESULTS.md](RESULTS.md) for the final pass/fail/pending matrix. Final native checks passed folded and unfolded, and the user separately confirmed physical outside continuity in this version. This document records remaining design limits, debt and coverage beyond those specific passes.

## Supported content and typography

- Local **DRM-free EPUB** is the supported import format. PDF, Kindle/AZW/KFX/MOBI, DRM-protected books and cloud catalogs are not implemented. A browser search shortcut is not an ebook download service.
- Reflowable EPUB text can change font size/family and repaginate; a fixed-layout EPUB uses publisher-positioned pages and does not acquire real reflow from a size preference. Typography controls must be disabled/explained for fixed layout while native reading navigation remains available.
- The 0.4.0 typography work targets actual document font sizing and semantic position preservation. Confirm the final fixed-pixel-style fixture and relative-style fixture results before declaring it robust across EPUBs. Publisher CSS, inline styles, tables, SVG, images, vertical writing and mixed-layout books are broader compatibility cases.
- System Serif and Sans can use different physical font files on different devices. OpenDyslexic is intended as a bundled option with its license, not a downloaded font. Language/script glyph coverage and individual reading preference vary; no medical or accessibility efficacy is claimed for a font choice.
- Changing typography changes pagination. The app preserves an approximate chapter/text anchor, not an invariant page number, line number or pixel coordinate. A large size change may move the previous anchor onto an adjacent page. Reflow time and selection state require device testing.
- “Whole-book percentage” is based on Readium locator progression. It is approximate and may not reach a displayed 100% on the start of the final page. Fixed-layout/very short books can produce coarse steps.

## Input and layout reliability

- Native swiping remains the default. Optional edge taps start disabled. The narrow bands are a deliberate compromise: wider zones are easier to hit but more likely to conflict with reading content.
- Link interaction, text selection/dragging, double taps, long presses, controls and swipes need separate regression tests. Passing a pure tap-region unit test does not establish recognizer behavior on real WebView content. Results must identify folded versus unfolded coverage explicitly.
- System edge-back gestures, accessibility touch exploration, stylus/mouse input, alternative WebView versions, landscape, right-to-left progression and unusual fixed-layout EPUBs are not automatically proven by one Samsung phone test. Follow-up coverage is recommended rather than replacing native gestures with a broad touch overlay.
- The UI is a functional prototype. Large font-scale accessibility, display-size changes, TalkBack, keyboard navigation and small landscape heights need dedicated usability/accessibility review.

## Outside display and power

- The outside cover depends on runtime public WindowArea presentation availability. It is not guaranteed on every foldable, firmware, orientation or device state. A successful API callback does not independently prove a visible physical outside panel.
- The user physically confirmed outside-cover continuity in **0.3.0** and separately in **0.4.0** while changing typography and using swipes/optional edge taps. This does not establish long-duration operation or every device/lifecycle case; consult RESULTS.md for the scope of the actual observations.
- Keeping BookCover foreground is part of the design. Switching to Kindle or another reader previously ended the cover session. A foreground service does not grant a missing display capability, and no integration with Moon+ is implemented.
- Activity recreation closes the prior presentation and requires a new request. The book reopens from its saved locator; presentation availability/consent can change. Lock/screen-off, explicit Stop, library return, timer or system policy can end a session.
- Exterior brightness control had no effect on the user's phone and is shelved. AOD/dimming and power optimization are not part of this cycle. A static outside image is a normal active presentation, not a claim of Samsung low-power AOD. Battery consumption, long-duration behavior and panel retention/burn-in mitigation are not measured or certified.

## Library themes

- Automatic samples the latest-read cover and produces a light library scheme. It does not infer device dark mode or alternate automatically between light/dark based on cover luminance. Manual Midnight and Charcoal are the dark options.
- RGB histogram extraction is deterministic and bounded, not a semantic/artwork understanding model. White-margin filtering and a minimum-population rule reduce noise, but some multicolor or mostly neutral covers can yield an accent a reviewer would not choose by hand.
- Generated foreground/background roles are contrast-tested mathematically. This does not replace visual inspection of real Compose states, disabled controls, focus indicators, display calibration or accessibility testing. Manual selection and actual theme persistence must also be verified in the app.
- Replacing the cover or opening a different book should invalidate Automatic's seed; progress changes alone should not repeatedly decode covers. Results identify the paths actually exercised.

## Persistence, lifecycle and robustness debt

- **Recorded incident in this development cycle:** connected-debug instrumentation cleanup uninstalled the main app and reset its private library/preferences. The provider-held original EPUB files remained. The final build now isolates connected tests in `com.bookcover.diagnostic.qa` with QA-only host code, and `Test-Device.ps1` invokes `connectedQaAndroidTest`. Never run connected instrumentation against the user's primary package. See RESULTS.md for the precise recovery outcome; previous user locators that were not backed up cannot be reconstructed from a recreated library entry.

- Storage is private local files plus an atomic JSON index, not a database or cloud sync. Import limits currently bound compressed input to 200 MB, individual ZIP entries to 100 MB, expanded content to 500 MB and entry count to 20,000. Large book position generation/cover decoding can still be expensive.
- SHA-256 detects identical input bytes. Different EPUB editions, repackaged copies or changed metadata can import as separate entries even if the visible text is the same.
- Index corruption/recovery, low-storage handling, interrupted imports, orphan-file cleanup, storage migration, backup/export and large-library performance need further review. Atomic writes reduce a class of partial-write failures but do not provide all of these behaviors.
- Android's normal Fragment state restoration cannot construct Readium without its open Publication. The prototype deliberately rebuilds the navigator from an application-saved book ID/locator. Process death while Android's document/image picker is open and permission-result restoration deserve explicit testing.
- The current implementation saves locators frequently. Write debouncing and lifecycle/cancellation resource ownership should be reviewed before very large libraries or long-duration production use.
- Content sanitization removes publication-authored active elements before Readium injects its own scripts; this is not a formal security audit of Readium/WebView/EPUB parsing. The project does not download remote book resources or automatically open external book links.

## Build, release and review history

- Debug APKs are for evaluation. No production signing key, store release configuration, updater, crash-reporting service or distribution process is provided. A reviewer-generated key will differ from the original developer's key; use a separate device/variant rather than deleting existing user data to bypass a signature mismatch.
- Dependency versions are pinned for reproduction. AGP 9 compatibility flags retain the external Kotlin plugin/legacy DSL; migrate before AGP 10. Target SDK remains 35 even though compile SDK is 37; review target-SDK behavior and current store requirements before distribution.
- The archive omits SDK/JDK binaries, Gradle distributions, resolved Maven caches, machine-local SDK paths, private signing keys and the user's imported library. An online first build and separately installed tools are required. --offline only works after a reviewer populates their own caches.
- There were no Git commits at the start of this cycle. The preserved baseline source/APK ZIP, file hashes, source comparison and written records provide the available development history. Do not interpret a generated comparison as an earlier commit history.
- Historical diagnostic/sample-reader files and documents remain as evidence. Some historical instructions refer to older labels or artifacts; the review package root README and current review documents take precedence.

## Recommended next priorities

1. Complete any pending acceptance items in RESULTS.md, with particular attention to typography reflow on authored CSS, semantic anchor recovery, selection/link conflicts and folded/unfolded gesture regressions.
2. Run a broader DRM-free EPUB corpus: EPUB 2/3, relative/fixed-pixel CSS, embedded fonts/images, missing covers, nested contents, right-to-left scripts, fixed/mixed layout and malformed/oversized inputs. Keep book rights/private files out of review artifacts.
3. Observe sustained dual-panel reading and lifecycle transitions on the actual target phone; measure power before proposing AOD or further dimming work.
4. Improve persistence recovery/export and cancellation ownership, then profile library/position/cover processing with large collections. Preserve the existing same-Activity display route.
5. Perform accessibility/usability and dependency/license review, migrate the build compatibility flags, and establish a deliberate source-license, release-signing and distribution process before calling the app production-ready.
