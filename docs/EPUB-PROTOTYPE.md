# EPUB prototype architecture and scope

Version 0.3.0, code 5. Local EPUB import, a persistent cover library and an actual EPUB reader replace the sample-only route. Readium Kotlin Toolkit 3.4.0 provides parsing, positions and navigation under BSD 3-Clause. The original sample reader/controller remain preserved in source and baselines. The full Readium license ships in app assets.

## Storage and reading

- Android OpenDocument supplies temporary read access; the importer copies bytes to private app storage immediately. No broad storage permission is used. Maximum input: 200 MB. SHA-256 identifies duplicate files.
- Atomic JSON index stores book metadata, collection, last-read time, total progression, Readium locator, cover revision and bookmarks. Book files and decoded cover thumbnails stay in private storage. Removing an entry does not modify the provider's original file.
- Embedded EPUB covers are extracted; missing artwork gets a native Canvas title/author cover. Overrides are local to each library entry. Complete exterior artwork uses FIT_CENTER with the existing dominant-edge-color matte.
- Readium's EpubNavigatorFragment runs inside the same FragmentActivity as Compose controls and the WindowArea presentation. The app never hands reading to another activity/app. Center taps toggle chrome; Readium handles swipes. No directional tap adapter is installed. Bottom-left percentage is whole-publication progression when supplied by Readium.
- Saved locators survive app exit. On activity recreation, MainActivity deliberately reopens the publication and constructs its fragment with the required publication factory instead of restoring an uninitialized navigator. It uses its own saved book ID and durable reading location. Exterior sessions need a new request on recreation.
- Publication-authored scripts and active embeds are stripped from markup before Readium adds its own rendering/navigation scripts. Remote resources are disabled through the injected HTTP client, and the app requests no INTERNET permission. External book links show a message. The app opens no web pages.
- Two original EPUB 3 sample books are generated reproducibly by `scripts/GenerateSamples.py` (Python/Pillow). Text/art are CC0. They use the same import path as selected local books.

## Exterior display

The previously successful public WindowArea presentation is retained in an isolated controller. Availability is detected at runtime, no hard-coded physical display ID is selected. Start/Stop, late callbacks, screen-off and host destruction are handled. The controller does not request activity transfer or system brightness changes. Defaults: automatically start on book opening when available; 30-minute cover timer. Settings also offer 60 minutes or Manual.

User confirmed earlier reader page filling, swipes/settings with outside cover visible, and manual Stop. In 0.2.2 the user reported all other functions as expected but outer brightness nonfunctional. Brightness and AOD are shelved. After the 0.3.0 EPUB prototype was installed and exercised, the user separately confirmed that the physical outside cover stays visible while swiping the inside EPUB pages. This is direct physical confirmation for the EPUB version.

## Toolchain

Readium 3.4.0 AAR metadata requires compile SDK 37 and core desugaring 2.1.5. This build uses AGP 9.3.3, Gradle 9.7.0, Kotlin/Compose compiler 2.4.20, Compose BOM 2026.09.00, JDK 17, min SDK 30 and target SDK 35. The supported AGP 9 opt-out flags retain the external Kotlin plugin; they must be migrated before AGP 10. Build outputs and project cache stay outside OneDrive. `scripts/Build.ps1` builds, runs JVM tests and lint, then copies `output/BookCover-prototype.apk` and its checksum.

## Book sources

BookCover reads only EPUBs the user imports from their own files. The earlier Anna's Archive browser-search shortcut was removed (BC-01).

## Upstream references

- [Readium Kotlin Toolkit and BSD license](https://github.com/readium/kotlin-toolkit)
- [Readium 3.4 getting started](https://github.com/readium/kotlin-toolkit/blob/3.4.0/docs/guides/getting-started.md)
- [Android WindowArea foldable display modes](https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes)
- [AGP 9.3 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
