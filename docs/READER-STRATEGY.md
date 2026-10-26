# BookCover Reader: revised technical strategy

Research date: October 26, 2026. This document changes the product direction; it does not announce a completed reader.

**Implementation update:** the native 0.2 sample reader has been built and tested on the user's phone. The user confirmed exterior-cover persistence during swipes and settings; logs show 39 page changes and a full three-minute presentation ending at its deliberate timeout. See [the test result](READER-TEST-RESULT.md) for the remaining continuous-reader/manual-Stop check before the EPUB gate.

## What the phone test established

The user observed the chosen cover on the physical exterior display while BookCover's diagnostic controls remained functional on the unfolded interior display. When switching to Kindle, the exterior cover did not continue displaying. That is meaningful partial success: concurrent physical panels work for the tested BookCover session on this phone/firmware. Sustained reading, different firmware, folding, locking, and recovery remain unverified.

The failed handoff is consistent with Android's documented presentation lifecycle: leaving the primary app can end a dual-screen session. It does not establish that Amazon deliberately blocked BookCover or that Moon+ will behave differently. [Android foldable display modes](https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes).

## Moon+ integration: possible vendor feature, unproven add-on route

Moon+'s official homepage and FAQ describe reading features and file opening, but our review found no documented public SDK, embeddable reader component, or cover-display plugin API that would let BookCover keep its foreground host while Moon+ supplies the interior reader. This is a bounded documentation finding, not proof that no private integration exists. Ordinary file-opening intents launch another app; they do not embed that app's reader or transfer ownership of its foreground activity. [Moon+ homepage](https://www.moondownload.com/index.html), [Moon+ FAQ](https://www.moondownload.com/faq.html).

A Moon+ vendor implementation of the exterior presentation inside its own foreground reader is technically plausible because the basic same-app display capability worked. It would require the developer's cooperation and another device test; no cooperation or API availability is assumed. No vendor messages have been sent. Building an independent app gives us direct control and is the recommended primary route.

## Recommended foundation

Build a small, open-source **BookCover Reader** with original Kotlin/Compose controls and Readium's EPUB rendering engine. Use Moon+ as inspiration for adjustable typography, spacing, themes, unobtrusive controls, contents, and bookmarks. Do not assume its code or proprietary assets are available for reuse.

Readium Kotlin **3.4.0** is the current official stable release reviewed. It supports EPUB 2/3, navigation and reading-location models. Its BSD 3-Clause license permits reuse subject to its notices and conditions; retain the required third-party license material. It is a toolkit, so our app still needs its own library, controls, storage and display-session logic. [Official release](https://github.com/readium/kotlin-toolkit/releases/tag/3.4.0), [license](https://raw.githubusercontent.com/readium/kotlin-toolkit/3.4.0/LICENSE).

Prefer the established `EpubNavigatorFragment` with Compose app controls. The native Compose navigators are explicitly experimental, so they should not be essential to the first release. One foreground host activity should contain the reader and its navigation; keep font settings, contents and other controls in that activity using fragments or Compose sheets. The same activity owns the separate exterior presentation. [Navigator guide](https://readium.org/kotlin-toolkit/3.4.0/guides/navigator/navigator/), [Compose interoperability](https://developer.android.com/develop/ui/compose/migrate/interoperability-apis/views-in-compose).

The user's selected reading interaction is **immersive by default**: horizontal touch swipes turn pages, and only the book's page content plus a small completion percentage at the **bottom left** remain visible. The top app/book/status section and bottom page-arrow toolbar are hidden while reading. A center tap temporarily reveals controls; another center tap or **Resume reading** hides them. Keep page arrows in the revealed controls for secondary accessible/keyboard navigation. This interaction must stay inside the presentation's foreground host activity. For EPUBs, compute the percentage from the engine's whole-book progression rather than chapter progress or printed-page counts; label sample-text progress accordingly. Do not add other gestures or persistent toolbars without a user request.

Readium 3.4.0 lists minimum Android API 24 and compile SDK 37. Our diagnostic uses compile SDK 35, so integration requires a build-compatibility spike. The README's Kotlin 2.4.20/Gradle 9.7.0 requirements are footnoted for source/submodule builds; prefer Maven Central binaries and inspect their dependency metadata before choosing app build-tool versions. Enable required core-library desugaring. Do not claim this is already a drop-in dependency. [Pinned setup requirements](https://raw.githubusercontent.com/readium/kotlin-toolkit/3.4.0/README.md).

FolioReader's official latest release is 0.5.4 from January 2019 and its setup still references JCenter; avoid this older baseline. KOReader is maintained but its Lua/native architecture and AGPL whole-app fork would be a larger undertaking. MuPDF adds native integration and licensing decisions without helping the first EPUB milestone. [FolioReader](https://github.com/FolioReader/FolioReader-Android), [KOReader](https://github.com/koreader/koreader), [MuPDF](https://mupdf.readthedocs.io/en/latest/index.html).

## Four development milestones

1. **Same-activity reading proof.** Replace the inner diagnostic panel with bundled sample text, horizontal swipe page turns, and the immersive reading interaction above, keeping the existing presentation host. Import the cover before starting. **Pass:** the user observes uninterrupted exterior cover while swiping interior pages, revealing controls by center tap, and returning to the page-only view with its small bottom-left percentage. Secondary page arrows remain available when controls are revealed. No activity handoff occurs during the defined short test. Log timeout and system closures explicitly.
2. **One real EPUB.** Resolve Readium/toolchain compatibility, import a local DRM-free reflowable EPUB through Android's picker, and navigate chapters/pages in the same host activity. **Pass:** the APK builds, one sample publication renders correctly, and a repeated physical reading test preserves the exterior cover. Preserve the diagnostic baseline separately.
3. **Focused reading MVP.** Add a small local library, title/author, remembered locator and book, contents, bookmarks, font/spacing/margin controls, light/sepia/dark reading themes, manual cover override, and clear cover-on/Stop controls in the revealed interface. Retain swipe-first immersive reading and an engine-derived whole-book completion percentage at bottom left. **Pass:** position survives reopening; controls reveal/hide without interrupting the exterior cover; the page-only default is restored when reading resumes; unsupported states are explained accurately.
4. **Device refinement.** Test folding, lock/unlock, rotation/recreation, battery saver, heat and interrupted sessions. Add supported brightness and Fit/Fill controls only where verified. **Pass:** no orphan presentation, understandable recovery, preserved reading position, and recorded battery/memory/APK-size measurements across repeat tests.

## Scope and limits

Start with DRM-free EPUB; exclude Kindle DRM, AZW/MOBI compatibility promises, stores, cloud sync, PDF, audio and advanced page curls. Reflowed positions are not guaranteed printed-page numbers. “Lightweight” means focused scope initially; measure APK size, memory and battery rather than promise a tiny binary or low two-screen power usage.

Block automatic remote resources/external navigation and reject publication-supplied scripts where supported. Readium uses its own JavaScript, so globally disabling WebView JavaScript may break reading. Verify filtering and resource controls during integration, and add no unnecessary native bridges. [Readium WebView source](https://raw.githubusercontent.com/readium/kotlin-toolkit/3.4.0/readium/navigator/src/main/java/org/readium/r2/navigator/R2BasicWebView.kt), [Android WebView guidance](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion).
