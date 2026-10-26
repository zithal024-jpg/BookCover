# BC-08 reading comfort controls

Implemented October 26, 2026 in `codex/bc08-reading-comfort`, based on `f0f9bfe` including BC-06. The application version stays 0.4.0-prototype / code 6. This is a focused reader change; library thumbnail/import/backup code and the exterior-display controller are unchanged.

## Behavior

- Publisher styles restore the book's own fonts, sizes and paragraph formatting. Enabled by default for fresh reader preferences. Existing users who explicitly saved a font/size retain custom mode on upgrade; this prevents a silent change to their current reading appearance.
- Turning book formatting off makes the saved custom controls available: the existing text size/fonts, line spacing 1.2/1.5/1.8/2.0, Narrow/Standard/Wide margins and Start/Justified alignment. Start respects the document direction rather than hard-coding left alignment.
- Custom values persist while publisher styles are on and return when it is switched off. Book mode does not submit custom font, line-height, indentation or alignment overrides to Readium. Themes, contents, bookmarks and native swipe/optional edge-tap navigation stay in place.
- Standard margins retain the owner's previous width. The 800px fixed line-length cap is unchanged; margin presets use Readium's supported 0.5/1.0/2.0 factors. Font resizing does not change the selected side padding.
- All new formatting controls are disabled for fixed-layout EPUBs. No new ebook format is introduced.

## Position preservation

Initial Android testing found that alignment changes could move back three paragraphs even though a block locator was restored. Readium's first-visible-block locator may describe a paragraph beginning on the previous page. The new `VisibleReadingAnchor.kt` refines it with a short quote from the text actually visible on the current page. It uses a bounded read-only DOM range query through the public navigator API; it never changes selection, scrolling, or touch handling. Readium resolves that locator after reflow. If text ranges are unavailable, the existing block locator remains the fallback. Book text is not written to diagnostic logs.

The existing coalescing, CSS-settle check, drag cancellation guard, chapter guard and debounced position saving remain. Preference selections are saved immediately rather than after the reflow delay, so leaving the reader during application does not discard the selected settings.

## Acceptance criteria and results

| Criterion | Result |
|---|---|
| Fresh preferences enable publisher styles; existing custom font preferences survive upgrade | JVM policy tests and native fresh-default test pass |
| Publisher toggle restores a poetry fixture's authored indentation, centering and monospace font | Android `publisherStylesDefaultOnAndRestorePoetryWithoutLosingCustomPreferences` passes |
| Line spacing, margins, alignment and size affect real layout and preserve nearby paragraph | Android `comfortControlsChangeRealLayoutAndPreserveMidChapterPosition` passes; tolerance remains 2 paragraphs |
| Custom choices and publisher toggle persist across new reading sessions | Native reopen checks pass |
| Previous fixed-margin and 800px-cap behavior survives font changes | Native comfort test and separate unfolded `ReadingLayoutTest` pass |
| Existing font loading, reflow, links/selection, optional edge turns and fixed-layout restrictions | All seven `NativeReaderTest` cases pass |
| Build, JVM tests, lint and QA test compilation | Debug APK and QA test APK built; 51 JVM tests pass; debug/QA lint have zero errors |

Native checks ran on the owner's unfolded Samsung SM-F976U, Android 17, using only `com.bookcover.diagnostic.qa`. The installed main app and its personal library were not used as the instrumentation target. The first broad run found the corrected position defect and the already-documented QA screen-attachment timeout; subsequent targeted reader and layout runs passed. This does not claim that the initial full suite passed or that every real-world EPUB has been tested.

Owner confirmation, October 26, 2026: after installing the BC-08 update and receiving the manual test steps, Frank reported "it works" and requested that BC-08 be marked as confirmed by him. This records his acceptance of the installed change alongside the automated results above; it is not an additional automated test run.

A supplementary desktop Chromium check with the exact Readium 3.4 CSS passed at 411px and 859px viewports: poetry restores, spacing/alignment change, margins change, and margins remain fixed while font size reflows. This supplements, rather than substitutes for, the Android results above.

## Files and reproduction

Production: `reader/ReadingComfort.kt` (pure settings/defaults), `ReadingComfortPreferences.kt` (persistence/Readium mapping), `VisibleReadingAnchor.kt` (visible quote), `EpubSession.kt` (application/preservation) and `EpubReaderScreen.kt` (controls).

Tests: `ReadingComfortTest.kt`, the original generated QA EPUB fixture, `NativeReaderTest.kt` and the existing `ReadingLayoutTest.kt`. The legacy custom-font regression probes explicitly opt into custom mode so that they continue testing their intended behavior after the new publisher default.

`scripts/Test-Device.ps1` additionally accepts optional `-BuildRoot` and `-ProjectCache` paths for isolated checkouts. Its defaults and strict QA-only/unlocked-device safeguards remain unchanged.

Build the normal way with `scripts/Build.ps1`. When separate agents are building different checkouts, use separate generated-output and project-cache locations. Device checks must go through the protected script, for example:

```powershell
.\scripts\Test-Device.ps1 -TestClass 'com.bookcover.diagnostic.testing.NativeReaderTest' -BuildRoot "$PWD/.tools/bc08-output" -ProjectCache "$PWD/.tools/bc08-cache"
.\scripts\Test-Device.ps1 -TestClass 'com.bookcover.diagnostic.testing.ReadingLayoutTest' -BuildRoot "$PWD/.tools/bc08-output" -ProjectCache "$PWD/.tools/bc08-cache"
```

Keep the phone unlocked and unfolded. The layout regression test is skipped on a narrow screen and is not then a pass. Build output, test logs, APKs, machine-local SDK paths, personal books and signing keys are excluded from Git. The completion tracker row is updated with this implementation commit; `git blame` on that row identifies it without a self-referential commit hash.

## Remaining scope

Broader poetry/embedded-font/RTL/vertical and mixed-layout corpus testing remains BC-13/BC-18 work. The existing long controls panel remains BC-23 work. No claim is made that publisher rules using extreme `!important` CSS, unusual markup or fixed-layout content can all be overridden. This change does not implement seamless fold/unfold (BC-07), two-page spreads (BC-19), or dimming/AOD. A fresh owner-observed exterior-cover check is separate from the native reader tests; no exterior-controller behavior was changed here.
