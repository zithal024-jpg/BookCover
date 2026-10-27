# BC-24: permissions and exported-component audit

Audited 2026-10-27, with AGP 9.3.3 and the existing dependency versions. Scope:
the current owner/shipping debug variant, unsigned release, isolated QA app and
its test APK. No UI, reading-session, lifecycle, timer, dependency, build-system,
application-ID or signing changes.

## Findings and exact changes

The source manifest alone was insufficient: the previous debug APK really
requested `ACCESS_NETWORK_STATE` and `WAKE_LOCK` and exported a Media3 Bluetooth
activity. The merger reports attribute these to Readium navigator 3.4.0's runtime
dependencies, Media3 1.11.0. Three named `tools:node="remove"` rules now remove
only those unused declarations, in all app variants.

| Declaration | Origin and purpose | Decision and evidence |
| --- | --- | --- |
| `android.permission.ACCESS_NETWORK_STATE` | `media3-exoplayer` and `media3-common`: Media3's `NetworkTypeObserver` observes connectivity for playback | Removed. No player or connectivity observer is created by BookCover's local EPUB path. This permission reads network state; it does **not** grant internet access. |
| `android.permission.WAKE_LOCK` | `media3-exoplayer`: playback's `WakeLockManager` can keep the CPU awake | Removed. No player, playback wake-lock manager or `PowerManager` use. The existing reader and outside cover use `FLAG_KEEP_SCREEN_ON` on their windows, which does not require this permission. |
| `androidx.media3.session.BluetoothValidationActivity` | `media3-session`: Bluetooth AVRCP audio-player discovery workaround on API 36/37 | Removed. BookCover has no audio session. Previously exported, protected by `BLUETOOTH_PRIVILEGED`, with `VIEW`, `content://media/internal/audio/media/`, `audio/*`, and no `DEFAULT` category. Its implementation just finishes. This was protected optional playback plumbing, not evidence of an unprotected book-data entry point. |
| `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (declaration **and** request) | AndroidX Core 1.19.0, via the AndroidX activity/fragment dependency tree; `signature` protection | Retained. `ExteriorCoverController` registers `ACTION_SCREEN_OFF` with `ContextCompat.RECEIVER_NOT_EXPORTED`. Core's API 26 compatibility branch requires this permission on supported Android 11/12 devices (API 30–32); removing it can throw during registration. QA gets its own `.qa` permission name. |

Dependency inspection used the resolved POMs, AAR manifests and bytecode in the
local Gradle cache. Readium shared (952 classes), streamer (125) and navigator
(433) contain no class-byte references to Media3, `NetworkTypeObserver`,
`WakeLockManager`, `ConnectivityManager` or `PowerManager`. Navigator's POM still
declares Media3 runtime dependencies. App usage selects `EpubNavigatorFactory` /
`EpubNavigatorFragment`, not audio playback. This supports narrow declaration
removals without changing dependencies. The activity removal has a node-local
`MissingClass` lint exception because lint cannot resolve the runtime-only class
in the removal rule; no general lint suppression was added.

## Complete final app component inventory

Debug and release have exactly one activity, one receiver and one provider; no
services or activity aliases. QA adds only the two private test-host activities.

| Component | Source/dependency | Access, purpose and decision |
| --- | --- | --- |
| `com.bookcover.diagnostic.MainActivity` | Main source manifest and activity | Retained, exported without a permission so the launcher can open the reader. Its only filter is `MAIN` + `LAUNCHER`; no data URI, `VIEW`, `SEND`, import or deep-link filters. The activity has no incoming-URI/extra or `onNewIntent` handler. External callers can open the normal screen, not request book/backup operations by intent. |
| `androidx.profileinstaller.ProfileInstallReceiver` | Compose UI 1.12.1 → profileinstaller 1.4.0 | Retained, exported and enabled, protected by `android.permission.DUMP`. Four actions: `INSTALL_PROFILE`, `SKIP_FILE`, `SAVE_PROFILE`, `BENCHMARK_OPERATION`, all under `androidx.profileinstaller.action`. Intended privileged profile-installation, profile-saving, skip-file and benchmark-cache tooling. These are not QA instrumentation or book-file APIs. `DUMP` restricts the caller; BookCover does **not** request it. Ordinary third-party apps lack that permission. |
| `androidx.startup.InitializationProvider` | startup-runtime 1.1.1; metadata contributed by emoji2 1.4.0, lifecycle-process 2.11.0 and profileinstaller 1.4.0 | Retained, explicitly **not exported**, no filters, read/write permissions, path permissions or URI grants. `${applicationId}.androidx-startup` authority. Initializes `EmojiCompatInitializer`, `ProcessLifecycleInitializer` and `ProfileInstallerInitializer`; preserves emoji support, process lifecycle and baseline-profile installation. Emoji arrives through navigator → appcompat; profileinstaller through Compose UI. |
| `com.bookcover.diagnostic.testing.ReaderTestActivity` and `LibraryAccessibilityActivity` | `app/src/qa/AndroidManifest.xml` and QA source set | QA only, explicitly **not exported**, no filters or URI grants. Absent from shipping debug/release manifests **and DEX class definitions**. |

No component has URI-grant declarations. The optional, non-required
`androidx.window.extensions` and `androidx.window.sidecar` libraries remain for
foldable/window-area support; these are library declarations, not exported
components or requested permissions. Core's `appComponentFactory` is retained.

EPUB import uses `OpenMultipleDocuments`, replacement covers and backup restore
use `OpenDocument`, and backup export uses `CreateDocument`. Android's picker
grants access to the selected URI; `BookLibrary` reads/copies or writes through
`ContentResolver`. No broad storage permission, `FileProvider`, exported file
provider or outgoing book-data URI grant is needed. No picker or import code was
changed. `allowBackup=false`, `fullBackupContent=false` and the existing
cloud-backup/device-transfer exclusion rules remain in every app variant.

`INTERNET` is absent from all inspected artifacts, before and after. There is no
rule silently suppressing future `INTERNET` additions. `ReadiumEngine` continues
to supply the rejecting `HttpClient` to the asset retriever/parser. Offline
search still reads local publication resources. No network client was enabled.

## QA test APK

The separate test APK is `com.bookcover.diagnostic.qa.test`, with exactly one
`AndroidJUnitRunner` instrumentation declaration targeting only
`com.bookcover.diagnostic.qa`. AndroidX test core 1.7.0 contributes
`REORDER_TASKS`, its three exported `InstrumentationActivityInvoker` bootstrap /
empty / empty-floating activities (test launch helpers with launcher-category
filters at priority -100, no data filters or URI grants). Test runner 1.7.0
contributes the queries for AndroidX test orchestrator/services. The instrumentation
entry is generated from `defaultConfig.testInstrumentationRunner` and the QA test
build type. These exist only in the test APK. Neither
shipping variant contains instrumentation, test-runner classes or QA hosts.
No app/test manifest uses `sharedUserId`; QA has its own package, private data,
startup authority and receiver permission. Sharing the development signing key
does not share the owner app's UID or private files. The `.qa` suffix and
`testBuildType="qa"` safeguards are unchanged.

## Verification and local evidence

- `scripts/Build.ps1 -TaskId bc24 -Owner Codex`: passes `assembleDebug`,
  `testQaUnitTest`, `lintDebug`, `lintQa`. 173 JVM tests, zero failures/errors/skips;
  zero lint errors, existing 24 debug / 25 QA warnings. Initial lint found the
  removal-rule `MissingClass` false positive; the narrow exception above resolved
  it. The final prescribed build passes.
- A separate BUILD reservation, using `Task-Operations.ps1` environment and
  `Task-Resources.ps1` locking/source fingerprints, ran `assembleRelease`,
  `assembleQa`, `assembleQaAndroidTest`, `lintRelease`: all pass. Release lint:
  zero errors, 24 warnings. Release is unsigned; no credentials were created.
- `apkanalyzer manifest print` decoded each actual APK, and `aapt2 dump
  permissions` independently enumerated its permissions. All three app variants
  request **only** their app-scoped signature permission. Assertions compare all
  app/permission/component attributes and filters in merged vs packaged XML,
  resolving AAPT resource IDs and enum encodings; exact component/permission
  inventories, protections, no URI grants, backup flags, release non-debuggability
  and the isolated test target all pass. DEX checks find no QA hosts or test runner
  in shipping variants.

Both builds used the unchanged source fingerprint
`fb2ec6253ed34a5c6840ffc2db54c73d15d08f3563413633a83d5b5d5977d91f`.
Generated evidence is outside Git under
`%LOCALAPPDATA%\BookCoverBuild\tasks\bc24-64b5cc9ab46fe34a\`:

- `receipts/build.json` and `evidence/fb2ec6253ed34a5c6840ffc2db54c73d15d08f3563413633a83d5b5d5977d91f-7e45e63d51a54bd0921486a109ae6da0.json`
- `project-output/app/outputs/logs/manifest-merger-{debug,release,qa}-report.txt`
  and the corresponding merged manifests and lint/unit XML reports
- `audit/inspection.json`, `supplemental-build.log`, `*-merged.xml`,
  `*-packaged.xml`, `*-permissions.txt`, resource tables,
  `readium-reference-check.json` and `verify_manifests.py`

| Artifact | SHA-256 |
| --- | --- |
| Shipping debug | `7b497614984cb5c088972504cdd4bc6c609bcdcaf0fc14a5db3af35a7ea4d682` |
| Unsigned release | `871d8287709d6566f5b97e45e386fdd80497aa9a470a7c96867bd80cf4d5d099` |
| Isolated QA app | `6d5a3cc0e4ca2c0c15e04c680d91dea69c4edbcf5cdb6ee15bacb921af89ba08` |
| QA test APK | `b0fb2003a49599aa66d22a31b17e815881b43c8a24f51765e782950a942a2d91` |

No device commands, installs, uninstall/data-clear operations or instrumentation
runs were performed. Fresh phone behavior and physical outside-cover observation
are **not verified** by this audit. No PHONE release/owner-availability evidence
was supplied for this task. All required static/build/artifact checks pass;
runtime code is unchanged. Future audio/background-playback support or dependency
changes must revisit these named removals and recheck the packaged inventories.
An optional `Package-Review.py --validate-only` attempt stopped at resource
preflight because Reading Streak held BUILD; that review-package check did not
run. The independent packaged-manifest checks above passed; no review archive
was requested or created.

Reference semantics: [Android permissions](https://developer.android.com/reference/android/Manifest.permission),
[ContextCompat receiver flags](https://developer.android.com/reference/androidx/core/content/ContextCompat#RECEIVER_NOT_EXPORTED),
[BluetoothValidationActivity](https://developer.android.com/reference/androidx/media3/session/BluetoothValidationActivity),
[ProfileInstallReceiver](https://developer.android.com/reference/androidx/profileinstaller/ProfileInstallReceiver).
