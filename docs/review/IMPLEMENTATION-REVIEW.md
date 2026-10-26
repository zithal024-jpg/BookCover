# Implementation review — baseline 0.3.0 to 0.4.0

## Baseline and scope

Before feature implementation, the complete source/build/assets/tests/documentation and the previously validated 0.3.0 APK were captured in a baseline folder and ZIP. `SOURCE-MANIFEST.json` records the baseline file hashes. The final package includes that ZIP and an automatically generated path-by-path change inventory plus a unified text diff. Git had no commits and all project files were untracked; those generated records are the available development history, not a fabricated commit sequence.

The existing FragmentActivity + Readium fragment + WindowArea presentation architecture remains. The public exterior controller, cover fitting/matte, library storage/import, bookmarks/contents, passive-markup handling and sample content were preserved. No AOD/dimming implementation was resumed, and no new supported ebook format was claimed.

## Typography investigation and implementation

The baseline already submitted `EpubPreferences.fontSize` through Readium CSS, whose default `useReadiumCssFontSize` is true. It was not proven to use bitmap or visual viewport magnification. The new code explicitly selects the CSS route and makes actual reflow testable rather than claiming to replace a nonexistent bitmap-zoom implementation. Readium's advanced stylesheet (`publisherStyles=false`) normalizes ordinary paragraph font sizes, including authored fixed-pixel paragraph styles; we retain it. Accessibility `textNormalization=true` was deliberately avoided because it removes emphasis/weight distinctions.

`ReadingTypography.kt` defines safe, persisted font identifiers and bounded 10% text-size steps from 80% to 220%. Serif and sans serif use system generic fonts. OpenDyslexic uses the font already bundled/declared by Readium. Values are percentages, not invented physical point sizes. `EpubReaderScreen.kt` adds A−/A+ controls, a stepped slider applied on release, font selectors and an applying-state indicator. `EpubSession.kt` maps these to Readium font family/size and explicit line height/paragraph spacing/indentation preferences.

Before repagination, the session captures Readium's public first-visible-block Locator, with the current Locator as fallback. It coalesces rapid changes, submits CSS preferences, waits for matching settings and a bounded public read-only JavaScript check of applied CSS/font loading, then restores the block. Intermediate repagination locations are not persisted. A non-consuming drag observation invalidates an old anchor if the user begins swiping; a changed chapter also prevents an old restoration. Explicit chapter/page/bookmark controls are disabled during typography application. No custom JavaScript bridge, raw touch recognizer or WebView zoom is added. Recovery is approximate to a text block, not an exact word/pixel guarantee.

Typography is enabled only for reflowable EPUB metadata. Fixed-layout publisher-positioned documents continue to render/navigate, while size/family controls are disabled with an explanation. Ordinary EPUB 2/3 reflow is the supported text path; pathological CSS, mixed-layout, RTL/vertical writing and a larger corpus still need testing.

## Edge taps and gesture preservation

`ReadingTapPolicy.kt` is a pure classifier called only from the existing Readium `InputListener.onTap`. It never receives raw swipe events. The SDK's interaction pipeline filters links/interactive elements, prevented clicks and selections before this callback. Recognized images/SVG also suppress edge navigation. A center tap retains the existing control toggle.

Edge navigation starts disabled and persists only after the user enables it. Left/right bands are the smaller of 32 dp or 6% of the current navigator width, excluding the top/bottom 10%. They are calculated from the current view dimensions, so folding does not leave stale coordinates. A completed left tap invokes native `goBackward`, and right invokes `goForward`. Visible controls and ongoing typography application suppress edge page turns. No overlay or replacement swipe handler is installed. The optional drag observer always returns false and only suppresses stale typography-anchor restoration.

## Library themes

`CoverPalette.kt` provides platform-independent palette extraction, mixing, luminance/contrast calculations and role generation. `LibraryTheme.kt` samples an evenly spaced bitmap grid and maps ARGB roles into a complete Material3 color scheme. Opaque, common chromatic colors are preferred over white margins/tiny accents; transparent/empty cases have a fallback. Accent/foreground/border roles are adjusted for contrast rather than assuming a sampled color is readable.

`MainActivity.kt` derives a seed on an IO dispatcher from the latest-read book, keyed only by book ID and cover revision. Normal page-progress updates therefore do not repeatedly decode the image. Automatic is a restrained light cover-tinted scheme. Paper and Forest are manual light options; Midnight and Charcoal are dark. Manual selection ignores the book seed until Automatic is reselected. Preferences are stored independently from the page theme/exterior cover. Library settings use scrollable radio choices so all options remain reachable folded.

## Test and build changes

New JVM tests cover typography bounds/defaults, narrow tap boundaries and guards, palette extraction/manual override and many generated foreground/background contrast pairs. Native instrumentation uses original generated reflowable/fixed-layout EPUBs, actual Readium/WebView rendering, measured CSS glyph/line fragments, unchanged image/viewport/textZoom, semantic anchors, loaded font faces, real injected touch events, hyperlink routing, selection guard and fixed-layout no-ops. The harness has a temporary private library and isolated preferences.

After an initial test cleanup incident, instrumentation was moved into build type **qa**, application ID `com.bookcover.diagnostic.qa`; the non-exported harness is only in `src/qa`. The normal `assembleDebug` APK does not contain it. `Test-Device.ps1` refuses configurations without the QA suffix/testBuildType, checks an unlocked selected device, and runs `connectedQaAndroidTest`. With this AGP configuration, unit tests use `testQaUnitTest`; the app build also runs lint for debug and QA.

`Package-Review.py` creates an allowlisted complete source copy, review docs, APK/checksum, baseline ZIP, source differences and build/test materials. It excludes caches, machine-local SDK settings, imported private books/recovery copies, keys/tokens and personal screenshots; scans included text for credential patterns; generates inventories; and checks ZIP member safety/CRC. Tools/Maven caches must be obtained separately.

See RESULTS.md for actual outcomes and the incident recovery limits. A feature description here is not a claim that every EPUB, selection handle, fixed-layout interaction or physical lifecycle case has passed.
