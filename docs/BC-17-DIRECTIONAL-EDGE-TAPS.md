# BC-17: edge taps follow the book's direction

Implemented October 26, 2026 on `codex/bc17-rtl-edge-taps`, based on `e2f0d27`. Version remains 0.4.0-prototype / code 6.

## Behavior and implementation

When optional edge taps are enabled, an LTR book advances at the right edge and returns at the left edge. An RTL book advances at the left edge and returns at the right edge. The application reads `fragment.settings.value.readingProgression` for each completed tap. This is Readium 3.4.0's resolved direction, which respects an explicit publication direction and otherwise infers direction from the book's language. It does not use the phone's interface language or guess direction from individual paragraphs.

`ReadingTapPolicy.kt` applies this direction only after its existing tap checks. The edge bands remain capped at 32 dp and 6% of the viewport width. Navigation stays disabled by default, with the existing controls, typography-update, interactive-element and vertical-inset guards. `EpubSession.kt` still calls Readium's `goForward`/`goBackward`; Readium owns swipes, selection and links. No raw touch handler or navigation-preference override was added. The edge-tap setting now explains that the edges follow book direction, and diagnostic messages describe forward/backward rather than incorrectly naming a physical edge.

Production changes are limited to `reader/ReadingTapPolicy.kt`, `reader/EpubSession.kt` and `reader/EpubReaderScreen.kt`. The QA host and generated fixture accept optional publication-direction and language inputs; all existing callers retain their defaults. BC-18's sanitizer and corpus files are outside this change.

## Acceptance checks and results

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
