# Native sample-reader result — 0.2-reader-test

Date: October 26, 2026. Device: Samsung SM-F976U, Android 17/API 37. App label: BookCover Test. Package: `com.bookcover.diagnostic`.

## Delivered and installed

- Artifact archived as: `output/BookCover-reader-test-0.2.apk`, version code 2. The generic `BookCover-reader-test.apk` now points to the newer 0.2.1 build; this record describes the original 0.2 phone test.
- Size: 24,394,943 bytes.
- SHA-256: `a94dab43991e71384eb42cfc8b2b0e103dd80dc9830cfe40415cd7c9b6c1c691`.
- Compilation and `assembleDebug`: passed.
- Unit tests: 4 passed, 0 failures/errors. They cover standard-display selection boundaries and sample reading-position/progression behavior.
- Android lint: 0 errors, 5 warnings (3 newer dependency notices, 2 Kotlin extension-style suggestions).
- APK signature and 16 KiB native/ZIP alignment checks: passed. The debug certificate matches the original APK, allowing an update that preserves app preferences.
- USB install returned Success; cold launch returned Status: ok. The previously selected cover loaded successfully.

The original version 0.1 APK remains at `output/BookCover-diagnostic.apk`; its original MainActivity is preserved in `docs/baselines/MainActivity-0.1.kt`.

## Physical-device observations

The user unlocked/unfolded the device and exercised the new native reader. The inside display reported 2256 × 2504 pixels, and the exterior display reported 1080 × 2520 pixels while the presentation was active.

When asked whether the selected cover stayed visible on the actual outside screen while swiping pages and changing reading settings, the user answered **“Yes, cover stays visible.”** This is direct user confirmation of the key same-activity reading behavior. It is separate from the API/log observations below.

I inspected BookCover's UI hierarchy and its private diagnostic log. The hierarchy showed the native reader, its sample text and controls, and an active exterior-session status. The user operated the phone; no automated phone gestures were injected for this run.

## Captured event sequence

Phone times below are America/New_York (UTC−04:00):

- **12:51:31.516:** public WindowArea presentation requested.
- **12:51:31.568:** onSessionStarted delivered.
- **12:51:31.574:** reader opened inside the same MainActivity.
- **39 page-change events** recorded with `coverActive=true`; three completed font-change events and three theme changes (Paper, Sepia, Night) were also recorded.
- Controls were revealed and hidden repeatedly. The user visited setup and returned to the reader without an activity handoff or session end.
- **12:52:59.276:** user returned to setup, leaving the exterior session active. This means the first run did not document a full two-minute continuous stay on the reader route.
- **12:54:31.519:** BookCover explicitly requested closure for its three-minute test timeout.
- **12:54:31.558:** onSessionEnded delivered. Its Throwable was null, but the preceding app stop event identifies the deliberate timeout request. No earlier session-end event was recorded in this run.

The presentation therefore survived its full three-minute programmed session while the reader and in-app controls were exercised. Do not confuse the expected timeout with the previous Kindle-handoff failure.

Captured log: `output/BookCover-reader-device-log.txt` (local, ignored by Git). Build log: `%LOCALAPPDATA%\BookCoverBuild\reader-build.log`.

## Result and remaining gate

**Initial same-activity phone proof: passed.** The exterior cover visibly persisted through native reading gestures and settings, supported by the user report and a three-minute presentation lifecycle.

Before treating the sample-reader milestone as fully validated, run one fresh session with at least two minutes continuously on the reader route and explicitly press **Stop outer cover** to confirm the image disappears. Verify the immersive page-only view and bottom-left percentage during that run. Manual Stop was not recorded in the first run; the timeout handled cleanup.

EPUB import, a book library, long reading sessions, and folding/locking/recovery remain separate unimplemented or unverified milestones. No emulator or simulated dual-display result is presented as physical proof.
