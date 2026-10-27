# BC-25: guarded builds, QA tests and matching package evidence

This work integrates checkout-aware paths, resource reservations and source/output
receipts into `Build.ps1`, `Test-Device.ps1` and `Package-Review.py`, plus clean-run
CI and the modern AGP configuration described below. The earlier integration's
owner-confirmed acceptance is retained; it does not imply a separate manual
TalkBack/Accessibility Scanner audit of later changes.

## CI and AGP 10 readiness

`.github/workflows/android.yml` runs on pushes, pull requests and manual dispatch:

- An Ubuntu 24.04 runner uses JDK 17, SDK platform `android-37.0`, build tools
  36.0.0 and the checksum-pinned Gradle 9.7.0 wrapper. A clean, uncached build runs
  `assembleDebug testQaUnitTest lintDebug lintQa assembleQaAndroidTest`. The test
  APK's own manifest must identify `com.bookcover.diagnostic.qa.test` and target
  only `com.bookcover.diagnostic.qa`. CI never runs connected instrumentation.
- A Windows runner executes the resource and orchestration fixtures in clean
  Windows PowerShell 5.1 processes, plus the standard-library Python packaging
  tests. Native process exit codes are preserved when logs are captured.
- Action revisions are pinned to full commit hashes, repository permissions are
  read-only, and credentials are not persisted by checkout. Only generated test,
  lint and manifest reports are retained for seven days. APKs, signing keys, SDK
  files, dependency caches and the owner's phone data are not uploaded. Jobs use
  disposable paths; local BUILD/PHONE reservations remain a local-machine gate.

The project retains AGP 9.3.3 and Compose compiler 2.4.20. It now uses AGP's
built-in Kotlin and modern DSL defaults: `org.jetbrains.kotlin.android` is removed
from both build files, and `android.builtInKotlin=false` / `android.newDsl=false`
are removed from `gradle.properties`. The Compose plugin resolves Kotlin compiler
2.4.20 too; the configured Java/Kotlin target stays 17. QA package isolation and
`testBuildType = "qa"` are unchanged.

As checked on 2026-10-26, the [official Google Maven index](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml)
has no AGP 10 artifact. This completes the compatibility migration that Google's
[AGP roadmap](https://developer.android.com/build/releases/gradle-plugin-roadmap)
and [built-in Kotlin guide](https://developer.android.com/build/migrate-to-built-in-kotlin)
prescribe on AGP 9.x; it does not claim a build with an unreleased AGP 10 version.
The current [AGP 9.3 tool requirements](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
are compatible with the retained JDK, SDK and Gradle versions.

Local build, QA-APK assembly and workflow checks are recorded in the completion
tracker. A hosted CI pass must be verified after the workflow commit is pushed;
local checks and static workflow validation do not prove a GitHub-hosted run.

## One-time adoption and ordinary builds

The reservation store defaults to `%USERPROFILE%\.bookcover-coordination`, outside
OneDrive and packaged-app LocalAppData views. All workers must agree on this same
physical store. Bootstrap is explicit, only after previous holders release; no
helper initializes, clears, expires or steals uncertain state automatically.

```powershell
# Only after the coordinator has established the previous holders' release:
. .\scripts\Task-Resources.ps1
$context = Get-BookCoverTaskContext -CheckoutRoot (Get-Location).Path -TaskId 'core'
Initialize-BookCoverResources -StateRoot $context.StateRoot -Coordinator 'PM' `
    -PreviousReleaseEvidence 'Exact recorded previous-holder release reference'
```

Initialization refuses existing state. The owner explicitly released Opus's
BUILD reservation before authorizing this integration. That is not a PHONE grant.
A new deployment must establish its own known state; do not copy this machine's
registry as authorization elsewhere.

```powershell
# Read-only: prints selected paths, without creating state or taking resources.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build.ps1 -TaskId core -Plan
# Actual build; automatically reserves/releases BUILD around the operation.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build.ps1 -TaskId core -Owner Codex
```

Default task ID is `local`. Task ID plus normalized absolute Windows checkout
path selects distinct generated output, project cache, Gradle user home, APK and
checksum names. The successful command prints the actual APK and evidence paths;
it does not overwrite `BookCover-prototype.apk`. Different worktrees/tasks do not
silently use the same paths. Old `-BuildRoot`, `-ProjectCache` and `-ApkName`
arguments are accepted only when they match that computed context; arbitrary
legacy values fail with migration guidance. Use `-ToolRoot` for an agreed SDK/JDK
and task-output root, and `-StateRoot` for an agreed shared registry location.

The build runs `assembleDebug testQaUnitTest lintDebug lintQa`. Its Gradle daemon
registry is isolated via `GRADLE_USER_HOME` and a single-use daemon. Existing
wrapper/dependency caches may be reused through junctions; no existing cache is
removed or process stopped. Caller environment and working directory are restored.
Native batch paths with shell-expansion characters are rejected before tools run.

## PHONE is explicit and QA-only

```powershell
# Replace both values with the actual reservation and owner's availability.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 `
    -TaskId core -Owner Codex -Serial 'CONFIRMED_DEVICE_SERIAL' `
    -PhoneReleaseEvidence 'Exact previous-holder release and owner availability' `
    -TestClass 'com.bookcover.diagnostic.testing.NativeReaderTest'
```

No serial, no PHONE release evidence, an existing reservation, a locked/unknown
lock-screen state, or missing QA isolation stops the operation. BUILD is acquired
before PHONE, both before any adb probe or Gradle call. The helper requires one
literal `testBuildType = "qa"` and a literal `.qa` suffix inside the actual QA
build-type block, disregards comments, and rejects unfamiliar DSL rather than
guessing. It runs only `connectedQaAndroidTest`.
There is no install, uninstall or clear-data operation. The owner's main app is
never an instrumentation target. Keep the phone unlocked, unfolded, screen on and
untouched for reader/layout tests. PHONE is released before BUILD.

## Known completion, not guessed completion

A short exclusive `CreateNew` guard protects registry transactions; active leases
persist without holding a global source-editing mutex. Wrong owner, checkout,
task, lease, malformed state or stale guard fails closed. Explicit release needs
an ended operation plus recorded evidence. No process liveness guess, timeout,
chat closure or silence releases a resource. A native adapter that throws without
a known exit retains its Running receipt and reservations for coordinator
investigation; the helper never kills a process to make a reservation available.
These are cooperative local checks, not OS authentication or protection against
someone deliberately bypassing the scripts.

## Automatic receipts and packaging

Before each operation, the helper fingerprints build/tool input bytes in `app/`,
`gradle/`, `scripts/`, `.github/` and root Gradle/wrapper files. It includes uncommitted source
inputs, excludes output/cache/docs/local configuration, rejects input symlinks
and private key files, and permits only the two generated sample EPUBs. Git and
an SDK are not needed to fingerprint a fixture. Documentation may change without
pretending that it changes the APK; packaging labels current prose separately.

Each task has latest `receipts/build.json` and `receipts/device.json`. A new run
first records Running, invalidating old evidence. Successful completion requires
exit 0, equal before/after source digests, actual passing report XML and exact
artifact hashes. Preflight/native/test/source-change failures produce Failed
receipts when completion is known. Unit counts and skip counters must match the
XML test cases; entirely skipped runs fail. XML DTD/entity processing is disabled.

Schema-2 evidence additionally binds the copied APK to that task's generated
APK, its named checksum, successful receipt hashes, both lint reports and unit
results. Optional device evidence requires a successful QA receipt for the same
source. Missing phone evidence is recorded as not run. Legacy caller-asserted
schema-1 evidence is rejected; it cannot be promoted without a real guarded run.
This proves the recorded execution/file relationships, not reproducible binary
builds, APK signing identity, manual observations or app-store readiness.

```powershell
# Use the exact evidence path printed by the completed build/test command.
python scripts\Package-Review.py --evidence-manifest 'EXACT_EVIDENCE.json' --validate-only
# Only when a review archive is actually requested; destination must be new.
python scripts\Package-Review.py --evidence-manifest 'EXACT_EVIDENCE.json' `
    --destination 'NEW_REVIEW_FOLDER'
```

Packaging reserves BUILD, verifies source/receipts/artifacts before and after
copying, and releases only its own lease after the synchronous operation ends.
No default APK/report, historical debug test or folded-folder discovery remains.
Frozen source files come from the verified list; current allowed documentation
is identified separately. Private keys, configuration, personal books and unsafe
archive members are refused. `--validate-only` creates no review materials.

## Host checks

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\Test-TaskResources.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\tests\Test-TaskIntegration.ps1
python -B -m unittest discover -s scripts/tests -p test_package_review.py
```

The PowerShell suites use GUID temp fixtures, fake APK/XML and intercepted native
callbacks. They do not execute Java, Gradle, adb or a phone. A two-child-process
race verifies exclusive acquisition; interrupted-callback tests verify retained
reservations. Cleanup checks the resolved temp target before recursive removal.
Python tests cover explicit selection, changed evidence/source, lease cleanup,
privacy failures and complete ZIP creation from fake fixtures. Real Android
build and packaging validation results are recorded in the completion tracker;
mock device orchestration is not a claim that live phone checks ran.
