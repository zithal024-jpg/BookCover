# BC-17: edge taps follow the book's direction

Implemented October 26, 2026 on `codex/bc17-rtl-edge-taps`, based on `e2f0d27`. Version remains 0.4.0-prototype / code 6.

## Behavior and implementation

When optional edge taps are enabled, an LTR book advances at the right edge and returns at the left edge. An RTL book advances at the left edge and returns at the right edge. The application reads `fragment.settings.value.readingProgression` for each completed tap. This is Readium 3.4.0's resolved direction, which respects an explicit publication direction and otherwise infers direction from the book's language. It does not use the phone's interface language or guess direction from individual paragraphs.

`ReadingTapPolicy.kt` applies this direction only after its existing tap checks. The edge bands remain capped at 32 dp and 6% of the viewport width. Navigation stays disabled by default, with the existing controls, typography-update, interactive-element and vertical-inset guards. `EpubSession.kt` still calls Readium's `goForward`/`goBackward`; Readium owns swipes, selection and links. No raw touch handler or navigation-preference override was added. The edge-tap setting now explains that the edges follow book direction, and diagnostic messages describe forward/backward rather than incorrectly naming a physical edge.

Production changes are limited to `reader/ReadingTapPolicy.kt`, `reader/EpubSession.kt` and `reader/EpubReaderScreen.kt`. The QA host and generated fixture accept optional publication-direction and language inputs; all existing callers retain their defaults. BC-18's sanitizer and corpus files are outside this change.

## Initial acceptance checks and results

| Check | Result |
| --- | --- |
| RTL left edge advances, right edge returns exactly one page | Explicit RTL fixture passed on phone |
| Direction omitted from spine, inferred by Readium from Hebrew language | Native check passed with metadata direction absent and resolved direction RTL |
| Explicit LTR takes precedence over Arabic language | Native check passed, right edge advances |
| Both directions preserve swipes, disabled-by-default edges, visible-control suppression, selection dismissal and edge hyperlinks | Three directional native tests passed |
| Both directions cross chapter boundaries forward and backward | Three directional native tests passed |
| Narrow and wide viewports retain narrow edges and tap safeguards | Six `ReadingTapPolicyTest` unit tests passed |
| Previous typography, font loading/persistence, LTR edge navigation and fixed-layout typography restrictions | All ten `NativeReaderTest` cases passed together |
| Debug APK, QA test APK, all JVM tests and lint | Builds passed; 53 JVM tests, zero failures; lint zero errors (22 debug / 23 QA warnings) |

The phone run used an unlocked, unfolded Samsung SM-F976U on Android 17, exclusively through `scripts/Test-Device.ps1` and the separate `com.bookcover.diagnostic.qa` app. The main app's personal library was never an instrumentation target. A targeted RTL run passed first, followed by the complete ten-case reader class with zero failures or skips.

The fixtures are generated original documents with explicit HTML direction and language metadata, using the existing long prose and internal hyperlink. They are deterministic navigation checks, not a survey of published Arabic/Hebrew typography. No new folded-screen hardware run, vertical-writing run or fixed-layout RTL navigation run was performed for BC-17. Fixed-layout typography restrictions remain covered by the existing native case. Outside-cover behavior was not measured by these tests; its controller was not changed.

## Reproduction

Use `scripts/Build.ps1` for the normal build/unit-test/lint workflow. Run device tests only through the QA safeguard:

```powershell
.\scripts\Test-Device.ps1 -TestClass 'com.bookcover.diagnostic.testing.NativeReaderTest'
```

Separate checkouts may pass `-BuildRoot` and `-ProjectCache` to that script to avoid overlapping generated files. Build logs and JUnit results are saved locally under ignored `output/BC17-validation/`; APKs and device logs are not committed.

For manual evaluation, enable **Edge-tap page turns** in reading settings, close the settings, and test an RTL book: left edge advances, right edge returns. Repeat with an LTR book and check that ordinary swipes, link activation and selection still behave normally. Edge taps should be inert with the setting off. Owner confirmation of this build is not yet recorded.

## Image-page follow-up after owner testing

The owner reported manga taps failing after the initial build. Read-only checks confirmed that the installed APK matched the BC-17 artifact, edge taps were enabled, and the book explicitly declared RTL. Only structural metadata was inspected locally in memory; no personal book, artwork or page text was saved in the repository or captured in screenshots.

Two missing cases were identified: the application rejected every non-null tap target, including passive images, and legacy Kindle panel overlays used href-less `a.app-amzn-magnify[data-app-amzn-magnify]` elements that Readium treats as interactive. Generated graphic-only fixtures reproduce these structures without using the owner's book. Both new navigation tests failed before the fix. After the image-target change, the plain-artwork test passed, while the overlay test still failed because the existing resource filter was not being applied by Readium. That wiring correction is part of Claude Code's concurrent BC-18 work.

The reader now allows passive bitmap/SVG image targets while retaining the safeguard for other recognized target types. Readium still filters real links, selections and interactive elements before the app's listener. `ReadingImageMarkup.kt` changes only the specific href-less Kindle panel anchors into spans, preserving their content, classes, IDs and layout attributes. Actual href links, unrelated anchors and named anchors remain intact. Its call in `PublicationMarkup.kt` shares the existing document parse; original EPUB files are not modified and reimport is unnecessary.

Two new markup unit tests pass, including real-image-link preservation and idempotence. With BC-18's pending resource-filter wiring present in the isolated validation checkout, both image regressions pass and the full `NativeReaderTest` class passes 12/12 with zero failures or skips on the unfolded phone. The new cases verify default-off taps, RTL forward/backward artwork turns, native swipes and real image hyperlinks. The app's website-link handler is asserted without making any network request. Local before/after logs are retained under ignored `output/BC17-validation/image-followup/`.

BC-17 stays **Partial — owner retest pending** until the owner confirms the real manga book. The overlay behavior also depends on BC-18's resource-filter correction; it must accompany any build intended to contain this fix. The pending BC-18 source used for the validation build is retained with the local evidence so the APK's inputs remain reviewable without attributing that work to BC-17.

Final combined validation: BC-17 commit `3a56e91` plus the five pending BC-18 source files recorded in `output/BC17-validation/image-followup/bc18-validation-inputs/`. Debug and QA APK compilation passed; all 61 JVM tests passed, followed by all 12 native reader checks with no failures or skips. Lint has zero errors (22 debug / 23 QA warnings). The installable artifact is `output/BookCover-BC17-image-fix.apk`, SHA-256 `6f192c2d6ffdbabbf2ccc7c7181d0d805ded0df007d597eedc990c74cfa1b7eb`. This combined build was installed in place; the app version label remains 0.4.0-prototype. This does not claim that the separate BC-18 corpus acceptance run has completed.
