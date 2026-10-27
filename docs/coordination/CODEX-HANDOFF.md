# Codex handoff — BC-10 release established

> **Historical setup record.** Current work follows the
> [Autonomous working agreement](../../CLAUDE.md#autonomous-working-agreement),
> which supersedes PM-only ownership, routine assignments/path claims, manual
> approval/reservation ceremonies, repeated status logs and former component-only
> limits below. Evidence remains historical; data/QA/resource safeguards and
> explicit pauses still apply. Use current helper guidance rather than the old
> command examples below.

Current status is in the [completion tracker](../review/EXPERT-PANEL-REVIEW.md);
current commands are in the [task-resource guide](../BC-25-TASK-RESOURCES.md).
The path and resource snapshots below are historical, not current grants.

<details>
<summary>BC-10 and BC-23 setup handoff (historical)</summary>

Updated 2026-10-27 01:30:05 UTC. Direct read of local thread `01a17ac6-eb08-7392-8b71-c5e4f2232047`, **BookCover BC-10 — Accessibility and key turns; QA pending**, found the completed validation final:

> Commit: `b3a5d75`. Phone, build, and source-file reservations released.

The same final reports 91 unit tests, 29/29 phone checks and zero lint errors. Manual TalkBack/Accessibility Scanner remain outstanding; tracker partial. A later owner-requested install turn reports the tested build installed/opened with a data-preserving update and is completed. No fresh resource hold is recorded. PM ran no installation and did not rerun tests.

Approved read-only Git verifies HEAD `b3a5d75284ff140adb0b841b17acab78e23afc76`, `main` two ahead of origin, with no modified tracked files. Only Opus's `app/src/main/java/com/bookcover/diagnostic/ui/SettingsRows.kt` and coordination documents are untracked. No new coordination prompt was sent: release was explicit, rather than inferred from the commit or idle status.

The worker released all BC-10 source/test reservations. Filename inventories from checkpoint `8cd3a88` and final `b3a5d75` identify these exact affected paths:

- `app/src/main/java/com/bookcover/diagnostic/MainActivity.kt`
- `app/src/main/java/com/bookcover/diagnostic/library/LibraryScreen.kt`
- `app/src/main/java/com/bookcover/diagnostic/reader/EpubReaderScreen.kt`
- `app/src/main/java/com/bookcover/diagnostic/reader/EpubSession.kt`
- `app/src/main/java/com/bookcover/diagnostic/reader/ReadingKeys.kt`
- `app/src/androidTest/java/com/bookcover/diagnostic/testing/LibraryAccessibilityTest.kt`
- `app/src/androidTest/java/com/bookcover/diagnostic/testing/NativeReaderTest.kt`
- `app/src/androidTest/java/com/bookcover/diagnostic/testing/ReadingKeysAccessibilityTest.kt`
- `app/src/qa/AndroidManifest.xml`
- `app/src/qa/java/com/bookcover/diagnostic/testing/LibraryAccessibilityActivity.kt`
- `app/src/qa/java/com/bookcover/diagnostic/testing/ReaderTestActivity.kt`
- `app/src/test/java/com/bookcover/diagnostic/reader/ReadingKeysTest.kt`
- `scripts/Build.ps1`
- `docs/review/EXPERT-PANEL-REVIEW.md`

In particular, **LibraryScreen.kt and MainActivity.kt are no longer Codex-held** and are available for PM's next bounded Opus library-settings integration assignment. Reader/backend leadership remains Codex's direction, not a current reservation; no new overlapping issue is assigned here. PM remains sole future integration/tracker writer.

Opus's BC-23 design plus one self-contained NEW UI component was authorized after absent/unclaimed-path verification, without additional per-step sign-off; `ui/SettingsRows.kt` is reported complete and is untracked. Its initial scope excluded existing-file wiring, builds/tests/phone/commits/pushes. A subsequent integration task needs exact assigned paths and its own applicable validation/resource scope.

Read [coordination](README.md), [AGENTS.md](../../AGENTS.md), [CLAUDE.md](../../CLAUDE.md) and the [tracker](../review/EXPERT-PANEL-REVIEW.md). Keep handoffs concise: source/base, exact retained/released paths, checks and pending manual evidence, resources and next dependency. Do not start a new overlapping issue before that handoff. No worktree or push is authorized by this file.

</details>
