# Validation results — BookCover 0.4.0 prototype

Review date: October 26, 2026, America/New_York. Version code **6**, application ID `com.bookcover.diagnostic`. This document separates measured results, human observation, and remaining coverage. The preserved baseline is 0.3.0 / code 5.

## Completed checks

| Check | Result | Evidence |
|---|---|---|
| Preserved baseline | Source/assets/tests/configuration/docs and prior APK captured before implementation; source SHA inventory and ZIP retained | `baseline/BookCover-baseline-0.3.0.zip` in review package |
| Baseline build | PASS: original build/JVM tests/lint | `baseline-0.3.0-review-build.log` |
| Typography-stage build | PASS after correcting a Kotlin property-setter naming clash | `typography-stage-build.log` |
| Navigation/theme-stage build | PASS | `navigation-theme-stage-build.log` |
| Final normal APK + QA tests | PASS: `assembleDebug testQaUnitTest lintDebug lintQa` | `final-review-build.log` |
| JVM tests | **24 tests, zero failures/errors** | Final `testQaUnitTest` JUnit XML |
| Lint | **Zero errors**; 22 debug / 23 QA warnings | Debug/QA lint reports |
| Native folded phone | **5/5 PASS**, including actual loaded OpenDyslexic face and visible-controls tap suppression | `native-reader-folded-tests.log`, archived folded JUnit XML |
| Native unfolded phone | **5/5 PASS** with the same final test source | `native-reader-device-tests.log`, QA JUnit XML |
| Main-app library themes | PASS: automatic cover changes; manual Midnight color; persistence through cold restart; manual override while recent book changes; return to Automatic | `library-theme-phone-tests.txt` |
| APK signature/alignment | PASS: Android v2 debug signature; 16 KiB zipalign check | `apk-verification.log` |
| Physical exterior continuity | **User confirmed: “Yes, everything stays visible and works”** while changing size/family and using swipes/optional edge taps in the unfolded reader | Human report in this development session; `physical-confirmation.txt` |

Native device: Samsung SM-F976U, Android 17/API 37. Folded and unfolded tests use a distinct **`com.bookcover.diagnostic.qa`** installation, temporary test libraries, and separate test preferences. Native results do not rely on an emulator. The normal debug APK excludes the QA harness.

## What the tests establish

- The rendering test uses original long EPUB prose with an authored `p {font-size:18px}` rule. Increasing size changes computed paragraph glyph size and line-fragment count; viewport width, visual viewport scale, image width and Android `WebSettings.textZoom=100` remain unchanged. Italic emphasis, heavier strong text and positive paragraph separation remain. The test starts mid-chapter and verifies that the same approximate paragraph is retained.
- Font family/size are persisted and reappear in a new native session. OpenDyslexic is checked as an actual loaded FontFace, not merely a CSS family string or an assumed fallback.
- With edge taps disabled, a physical edge tap is inert. Normal native swipes advance. When enabled, one right/left completed tap moves exactly one viewport page, measured after the native animation settles. Disabling taps restores inert edges; swipes still work. A real edge-positioned internal hyperlink follows its chapter target, and an edge tap while controls are visible does not turn a page.
- A real DOM text selection exercises Readium's selection guard and dismissal behavior. Native long-press handles and complex footnotes remain a separate human/corpus test; this distinction is deliberate.
- A separate fixed-layout EPUB is recognized and refuses typography changes without entering an applying state. It retains publisher layout; no claim of reflowing fixed-position content is made.
- JVM tests cover size bounds/invalid preferences, font identifiers, narrow edge bands/guards/coordinates, cover color extraction and manual override, and contrast across representative/random color seeds. Existing metadata/locator/bookmark, markup, sample-pagination, display policy and exterior color tests remain passing.
- Main-app theme colors were sampled on the device: automatic Lantern background RGB (248,249,249), River (249,249,250), manual Midnight (9,11,15). Manual selection persisted over a cold restart and ignored subsequent book changes; Automatic resumed artwork-derived changes when explicitly selected. The reader/outside artwork stayed independent.

## Instrumentation cleanup incident and recovery limits

The first instrumentation run mistakenly targeted the existing primary reader package. AGP's connected-test cleanup uninstalled that target and removed its private application data. This was a test-deployment mistake, not a user-requested reset. The user was informed during the work. Original EPUB files in Downloads were unaffected.

The primary app was reinstalled. Its two original sample books and both previously imported books, embedded covers and known collections were restored from the original files. EPUB/cover byte hashes were verified. Exact deleted prior reading locators and bookmarks were **not recoverable** and were not invented. A private recovery backup is now held outside the project/review archive; it contains personal book files and is intentionally excluded from deliverables. Later app interactions may establish new progress/preferences normally.

To prevent recurrence, the test harness moved to `src/qa`, with a separate `.qa` application ID and `testBuildType="qa"`. `Test-Device.ps1` checks those settings, requires an unlocked selected device, and invokes only `connectedQaAndroidTest`. Subsequent folded/unfolded runs preserved the primary app's restored library. Reviewers must use this QA route and must never change the test target to a user's primary installation. Early failed/mid-animation or locked-phone runs are historical failures, not final feature passes; final QA reports and this table govern the outcome.

## Artifact and reproduction

Final normal debug APK: **56,681,414 bytes**, SHA-256 **`2fbb52f978a290de175325051838b1bc916a7b823e29ef1684496eaaab3e9a98`**. It was built with the existing local debug key, which is excluded from review materials. New reviewers use their own signing identity and a separate device/installation when necessary.

The package includes source/whole-package inventories, a baseline comparison and ZIP integrity checks. Clean extracted-source reproduction is recorded below when completed. External JDK/SDK/Gradle distribution/Maven artifacts are deliberately not bundled; a clean extraction test on this workstation uses its existing separately installed tools and cached dependencies. It is not proof of an air-gapped build, fresh-machine network resolution, Linux/macOS execution or binary reproducibility under another signing key.

**Clean extraction build: PASS.** A candidate review ZIP was extracted to a separate local directory, with fresh project/build output caches. `assembleDebug testQaUnitTest lintDebug lintQa` completed successfully in 1m 3s (74 tasks executed), producing a normal APK, 24 passing unit tests and zero-error lint for both variants. It used the separately installed JDK/SDK and previously resolved Gradle/Maven artifacts. See `review-extracted-build.log`. The final package updates review documentation after this check; its build-relevant source is compared byte-for-byte with the tested extraction before delivery. The installed primary APK's SHA-256 was also verified against the final artifact, and its restored four-book library remained present after the isolated QA runs.

The clean APK has a different archive hash/size because the incremental and clean builds order/package the DEX entries differently; their DEX payload CRC/size multisets are identical. The delivered/installed APK is the explicitly checksummed artifact above. No byte-for-byte APK reproducibility claim is made.

## Outstanding validation and next steps

1. Broader EPUB 2/3 corpus, embedded fonts, RTL/vertical writing, mixed/fixed-layout interactive regions, nested contents, unusual CSS and malformed/oversized files. The automated fixed-layout check covers typography restrictions, not every FXL gesture/link case.
2. Native long-press selection handles/dragging and complex footnote/media interactions; repeated rapid gestures during typography changes. Existing native swipe recognizers remain, but those exhaustive combinations are not claimed complete.
3. Sustained physical dual-panel reading, power/thermal measurements, fold/lock lifecycle permutations and process death while a file picker is open. Dimming/AOD remain shelved.
4. Persistent-library backup/export, index recovery, low-storage/cancellation handling and orphan-file cleanup. The testing incident reinforces the need for user-data backup before wider deployment.
5. Production release signing, explicit project source license, dependency/license review, accessibility evaluation, AGP legacy DSL migration and target-SDK review. This is an installable prototype, not a production-release claim.

Primary reference for the added AndroidX test dependencies: [AndroidX Test stable artifacts](https://developer.android.com/jetpack/androidx/releases/test). Readium API behavior was inspected against the exact local 3.4.0 sources; see IMPLEMENTATION-REVIEW.md and the pinned dependency graph.
