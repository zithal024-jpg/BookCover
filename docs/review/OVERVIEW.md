# BookCover product and architecture overview

Review target: **0.4.0**, October 26, 2026. The baseline is **0.3.0-prototype / version code 5**. This document explains the product and code; [RESULTS.md](RESULTS.md) is the authority for the final build, test counts, hardware observations and any incomplete acceptance criteria. Do not infer validation from a feature description alone.

## Start here

1. Read the review package's root README, this overview and [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md).
2. Follow [BUILD-AND-INSTALL.md](BUILD-AND-INSTALL.md) to build from the included `source/` directory or install the supplied test APK.
3. Compare [ACCEPTANCE-AND-TEST-PLAN.md](ACCEPTANCE-AND-TEST-PLAN.md) with the final results and execute the relevant manual tests.
4. Inspect the baseline archive and the package's source comparison/change inventory to see exactly what changed. Git had no commits at the beginning of this cycle; there is no implied commit history.

The package logically separates the complete project in `source/`, review documentation in `docs/`, APKs/checksums in `builds/`, test evidence in `tests/`, and the initial source/APK snapshot in `baseline/`. Source manifests and change inventories accompany these materials. The root README identifies the exact final locations.

## Product purpose

BookCover is a native Android EPUB reader for a foldable phone. It keeps an immersive reading view on the inside display while requesting a public Android WindowArea presentation of the current book's cover on the physically separate outside display. Both are hosted by the same foreground Activity. This avoids the earlier failed handoff to a separate reading application.

The reader also works on a compatible Android device without an available outside presentation. In that case the outside-cover controls report availability and the local library/reading functions remain usable. Device model names are not a substitute for runtime capability checks or physical observation.

## Feature inventory and validation boundaries

| Area | Current scope | Evidence to consult |
|---|---|---|
| Local books | DRM-free EPUB import through Android's document picker; private file copies; SHA-256 duplicate detection; metadata and cover extraction; generated fallback cover | Baseline had two original samples plus successful imports/opening of user-selected EPUBs; rerun regression checks listed in final results |
| Library | Cover grid, search by title/author, Recent/Title/Author sorting, named collections, continue reading, confirmed removal of the app's copy | Existing native Compose implementation; results distinguish automated and manual checks |
| Reader | Readium EPUB rendering, native swipes, center-tap hidden controls, bottom-left whole-book completion, Paper/Sepia/Night reading themes, contents, bookmarks and saved locators | 0.3.0 was exercised on the phone, including restart and bookmark return; new-build regression evidence is separate |
| Outside cover | Complete artwork with matching-color matte; Start/Stop; runtime availability; optional automatic start; 30/60-minute or Manual session setting | User separately confirmed 0.4.0 outside continuity while changing typography and using swipes/optional edge taps; see final results |
| Typography changes in this cycle | Actual document font sizing/reflow, serif/sans-serif/OpenDyslexic choices, incremental and slider sizing, persisted choices, approximate semantic reading-position preservation | Final native tests passed folded and unfolded; see RESULTS.md for measured glyph/line/viewport/image checks. Fixed-layout pages disable reflow controls |
| Optional edge taps in this cycle | Default off; narrow left/right tap regions; native swipe recognizers retained; guard against interactive content, selection and visible controls | Gesture unit tests and on-device folded/unfolded checks must be read separately |
| Library themes in this cycle | Automatic cover-derived light palette; manual Paper/Forest light and Midnight/Charcoal dark palettes; persisted selection independent of reader/outside cover | Pure color tests cover extraction/contrast; real Compose/device behavior is reported separately |
| Optional web shortcut | Explicit user-triggered browser search containing “Anna's Archive” and a query | No archive API, embedded catalog, download integration or credential handling |

## Directory map within `source/`

```text
app/
  build.gradle.kts                 Android module, SDKs, dependency versions
  src/main/AndroidManifest.xml     Single exported launcher Activity
  src/main/java/com/bookcover/diagnostic/
    MainActivity.kt               Library/reader routes and lifecycle coordination
    DiagnosticLog.kt              Bounded private event log
    cover/                        Public outside-session controller and color matching
    library/                      Private EPUB library, parsing, import and UI theme code
    reader/                       EPUB session, Compose controls and reading policies
  src/main/assets/                Included samples, licenses and reading assets
  src/main/res/                   Native resources, theme and icon
  src/test/                       JVM tests for pure policies, storage records and markup
  src/androidTest/                Android/device tests, if present in final source
  src/qa/                         Isolated QA-only test host; excluded from the normal APK
docs/                             Current review documents and historical development records
scripts/                          Windows build and reproducible sample/packaging helpers
gradle/wrapper/                   Pinned Gradle wrapper JAR/configuration
build.gradle.kts                  Plugin versions and generated-output relocation
settings.gradle.kts               Google/Maven Central repositories; app module
gradle.properties                 Gradle/Android/Kotlin configuration
gradlew, gradlew.bat               Unix and Windows wrapper launchers
```

Historical sample-reader files and `docs/baselines/` are retained for comparison; their existence does not mean the application routes through them. The package's source manifest is the definitive file inventory.

## Major components

**MainActivity** owns the Compose library route and embeds Readium's Fragment in the reading route. It launches Android's file/image pickers, loads publications off the UI thread, coordinates preferences and outside-cover requests, and changes system-bar/keep-screen-on behavior while reading. It does not launch another reading app.

**BookLibrary and BookRecord** maintain private copies under the application's files directory. An atomic JSON index records title/author, collection, last-read time, locator, progression, cover revision and bookmarks. Cover overrides belong to the local entry. Removing a book removes this private copy and its associated data, leaving the file selected from the original provider alone.

**ReadiumEngine and PublicationMarkup** open EPUB containers through Readium. A failing injected HTTP client and the absence of the app's INTERNET permission keep publication resources local. Publication-authored scripts, active embeds and event attributes are stripped before Readium adds its own rendering/navigation scripts. External book links do not automatically open a browser. This is a modest content boundary, not a claim of comprehensive hostile-document certification.

**EpubSession** binds an open Publication to an `EpubNavigatorFragment`, supplies preferences/input listeners, observes current locators and writes reading progress. Typography changes operate on document text/layout rather than magnifying a bitmap or scaling the whole view. Reflow changes preserve an approximate text/chapter anchor after layout settles; an identical page number cannot be preserved when pagination changes. Fixed-layout EPUB is handled as a separate capability.

**EpubReaderScreen** embeds the native Fragment container and adds Compose overlays for controls/contents/bookmarks. With controls hidden, the page and small completion marker remain visible. Optional tap navigation is implemented through reader input callbacks, not a full-screen transparent touch overlay, so Readium continues owning swipes and selection.

**ExteriorCoverController** owns the public WindowArea presentation, request/session state, cancellation and cleanup. An ImageView fitted to the presentation preserves the full cover aspect ratio, with a background sampled from image edges. No brightness override, AOD, display-transfer request, service or private vendor API is introduced in this development cycle.

**CoverPalette and LibraryTheme** sample a bounded grid of opaque cover pixels, group nearby RGB colors and prefer sufficiently common artwork colors over white margins or isolated accents. They generate gently tinted surfaces and contrast-adjusted text/control roles. Automatic deliberately produces a predictable light library palette; dark library choices are manual. Pure color mathematics is tested independently from Compose rendering. Reader page themes and outside artwork are unaffected by the library scheme.

## Important technical decisions

- Keep one foreground host Activity for the reader and outside presentation. Same-package separate Activities do not guarantee preservation of presentation eligibility.
- Delegate EPUB pagination and native gesture recognition to Readium instead of implementing a second paginator or replacing swipe listeners.
- Distinguish reflowable content from fixed-layout pages. A publisher-positioned page cannot become reflowable by changing a font preference.
- Save semantic EPUB locators, not screen coordinates or transient page numbers. On Activity recreation, reopen the publication and reconstruct its navigator rather than restore a Fragment without its required Publication factory.
- Use private local book copies and atomic metadata writes; do not require broad storage access or depend on a document provider keeping a temporary URI readable.
- Generate complete library color roles, rather than tint only the header; preserve manual preference until Automatic is explicitly selected.
- Keep generated output and build caches outside OneDrive on the author's Windows machine because generated resource reparse/read-only behavior previously broke incremental cleanup.

## Tools and dependencies

| Component | Pinned version or requirement |
|---|---|
| JDK | 17 |
| Gradle wrapper | 9.7.0, distribution SHA-256 pinned |
| Android Gradle Plugin | 9.3.3 |
| Kotlin and Compose compiler plugin | 2.4.20 |
| Compile / minimum / target Android SDK | 37 / 30 / 35 |
| Installed build tools used for this project | 36.0.0 |
| Compose BOM | 2026.09.00 |
| Activity Compose / Lifecycle / Fragment | 1.13.0 / 2.11.0 / 1.9.0 |
| AndroidX Window | 1.5.1 |
| Readium shared, streamer, navigator | 3.4.0, BSD 3-Clause |
| Jsoup | 1.23.2 |
| Core library desugaring | 2.1.5 |
| JVM tests | JUnit 4.13.2; org.json 20250517 |
| Android instrumentation tests | AndroidX Test core/runner 1.7.0; ext:junit 1.3.0 |

Read `app/build.gradle.kts` and the final dependency declarations for any test-only additions made during validation. The two AGP 9 compatibility flags keep the external Kotlin plugin/legacy DSL working and require migration before AGP 10. OpenDyslexic font assets must retain their included license; no reviewer needs to download a font to run a correctly packaged final APK.

## Test isolation and the recorded data-reset incident

The final build defines a `qa` variant with application ID `com.bookcover.diagnostic.qa` and `testBuildType = "qa"`. Its additional host code lives only in `src/qa/`; it is not shipped in the regular debug app. Use `connectedQaAndroidTest` or `scripts/Test-Device.ps1` for connected instrumentation.

During this cycle, an earlier connected-debug instrumentation run caused AGP test cleanup to uninstall the main package, resetting its private library/preferences. Original EPUB files held by Android Files/document providers were unaffected. The separate QA application ID addresses that test-target isolation problem. Recovery details and the final extent of restored data, including unrecoverable previous locators, belong in RESULTS.md. This incident must not be described as a harmless test pass or a proven absence of data loss.
