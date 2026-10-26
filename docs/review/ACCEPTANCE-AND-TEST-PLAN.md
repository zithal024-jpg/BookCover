# Acceptance criteria and validation plan — 0.4.0

Baseline: 0.3.0 / code 5, captured before feature implementation in `review-deliverables/2026-10-26/BookCover-baseline-0.3.0` and its ZIP. Git contains no commits; file hashes and the final source comparison provide the development record. Status below is a plan until recorded in RESULTS.md.

## Typography (first implementation stage)

1. Reflowable EPUB: changing text size changes CSS font size and line breaks, not WebView page scale, bitmap scale, or container zoom. Demonstrate a paragraph using fewer words per line and more lines at larger size, with the viewport width unchanged. Inspect a fixed-pixel-style fixture as well as a typical relative-size EPUB.
2. Offer distinct system serif and sans-serif families, explicit size increment/decrement buttons and a slider. Size bounds and increments are deterministic; preferences persist across reopen and cold restart. Do not add unlicensed downloadable fonts.
3. Preserve the current chapter and approximate text anchor during preference changes. Readium's own re-pagination/location restoration is preferred over arbitrary delayed navigation. Verify a mid-chapter location before/after a large size/family change.
4. Keep suitable margins, paragraph separation and line height. Heading/inline emphasis should survive. Images must retain their size relative to the viewport when the text changes.
5. Fixed-layout EPUB: identify it, explain why text typography cannot reflow a publisher-positioned page, and disable typography controls. Continue reading/swipes/contents/cover functionality. PDF/Kindle/DRM are not supported formats.

## Optional edge taps (second stage)

1. Default disabled. Existing native Readium swipe recognizers and center-tap controls remain unchanged. Turning the option off restores baseline tap behavior immediately and survives restart.
2. When enabled, a single tap in a narrow physical edge band turns exactly one page: left back, right forward. Width is bounded in density-independent units and relative screen width, adapting to folded/unfolded layouts. No large transparent overlay or raw touch consumer is introduced.
3. Taps over a link or other interactive content do not turn pages. Long press/text selection, dragging and swiping do not trigger edge taps. Taps while controls are open do not navigate. Test all these separately, including a link placed near an edge.
4. Check both left and right swipes before and after enabling the option, folded and unfolded. Confirm no double turns from one gesture and no response loss. Capability-dependent fixed-layout navigation must use the native navigator.

## Library themes (third stage)

1. Fresh preference defaults to Automatic (Book Cover), based on the most recently opened/read book's artwork, falling back to a neutral palette for an empty library. Palette extraction runs off the UI thread.
2. Opening a different book changes the automatic library palette when returning home; a replaced cover also invalidates the palette. Book progress updates do not trigger repeated image decoding.
3. Manual light/dark theme choices override automatic until Automatic is explicitly selected. Selection persists after process restart. Reading page themes and the exterior image are independent.
4. Automatically generated foreground/background text pairs reach WCAG contrast >=4.5:1 for normal text; key control boundaries reach >=3:1. Test representative light/dark/saturated/grayscale/transparent artwork colors and all predefined themes.

## Regression and review package

- Build and all JVM tests plus lint after each major stage; address new failures. Native UI tests should exercise real EPUB rendering, pagination, preference persistence and gesture routing on available hardware/emulator.
- Recheck EPUB import/deduplication, cover extraction, library/search/collection, contents/bookmarks, saved locator and restart behavior. Preserve the same Activity and public WindowArea controller. No dimming or AOD work.
- Observe the real outside panel during typography changes, swipes and optional taps. Record human confirmation separately from API callbacks. An emulator cannot validate Samsung's physical dual-panel behavior.
- Final package: root README, complete source/assets/tests/build config, architecture and implementation review, file changes against baseline, baseline ZIP, final APK+checksum, build/test/lint reports, reproducible instructions and known limitations. Exclude phone book files, personal screenshots, caches, local.properties, credentials and private signing keys. Validate ZIP contents and inventory hashes and build the extracted project with separately installed tools.
