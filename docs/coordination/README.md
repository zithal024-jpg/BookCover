# BookCover coordination

Current work follows the [autonomous working agreement](../../CLAUDE.md#autonomous-working-agreement).
Implementers use the existing [completion tracker](../review/EXPERT-PANEL-REVIEW.md)
and the supported helpers in the [task-resource guide](../BC-25-TASK-RESOURCES.md).
Check actual conflicts and preserve uncommitted work. No routine PM assignment,
path-claim ceremony, duplicate status log or sole tracker writer is required.

The records below describe an earlier setup. Their commit, file and resource
snapshots do not establish current ownership or availability. Explicit pauses and
the data, QA and resource safeguards still apply; use current helpers for commands.

<details>
<summary>Superseded PM setup, handoff procedures and command examples (historical)</summary>

> **Historical setup record.** Current work follows the
> [Autonomous working agreement](../../CLAUDE.md#autonomous-working-agreement),
> which supersedes PM-only ownership, routine assignments/path claims, manual
> approval/reservation ceremonies, repeated status logs and former component-only
> limits below. Evidence remains historical; data/QA/resource safeguards and
> explicit pauses still apply. Use current helper guidance rather than the old
> command examples below.

Prepared for the owner's project-manager setup request. The current-state section distinguishes worker acknowledgments, user-provided reports and independently inspected evidence. Read [AGENTS.md](../../AGENTS.md), [CLAUDE.md](../../CLAUDE.md), and the [completion tracker](../review/EXPERT-PANEL-REVIEW.md) first. Those instructions remain authoritative.

## Current evidence and boundaries

Current status consolidated 2026-10-27 01:30:05 UTC. Reservations are manual agreements; recorded evidence does not imply enforced locks.

| Source | Current evidence and limits |
| --- | --- |
| Approved read-only Git inspection, latest pass | `main` HEAD **`b3a5d75284ff140adb0b841b17acab78e23afc76`**, two ahead of `origin/main`. Reported BC-10 commit resolves to that HEAD. No modified tracked files; only `app/src/main/java/com/bookcover/diagnostic/ui/SettingsRows.kt` and these three documents are untracked |
| BC-10 local thread, directly read | Validation final: 91 unit tests, 29/29 phone tests, zero lint errors; manual TalkBack/Accessibility Scanner remain outstanding; tracker partial. It explicitly states **"Phone, build, and source-file reservations released."** This explicit release, not commit/idle status, establishes the handoff |
| Later BC-10 user-requested install turn, directly read | Worker reports the tested BC-10 build installed/opened as a data-preserving update; turn completed and thread idle. PM performed no installation. Earlier release is recorded; any future phone action still needs a fresh reservation and owner availability |
| User-provided Opus component report + filesystem metadata | One BC-23 component completed with no build/test/stage/commit reported. PM independently verified `ui/SettingsRows.kt` exists, 2,586 bytes, untracked; its contents/behavior were not reviewed or tested by this task. Opus retains this new-file work until handoff |
| Parent-verified Opus delivery | Delivered once to fresh Claude session `local_131ea898-851a-4b64-88ac-19c49639d5e9`, **BC-23 settings/navigation UI plan + one new component**. Delivery was not independently inspected/sent by this agent. BC-24 was never dispatched |
| User-provided Opus acknowledgment, 01:14 UTC | Before BC-23, Opus reported no retained paths/edits/processes, released BUILD/PHONE `RFGL710TARJ` to Codex and accepted PM as sole tracker/integration writer. BC-20 reported 91 JVM tests, zero lint errors, QA 60/0/0 and no main install; `verify-bc20` worktree removed, `verify-bc20-output` reports retained. PM did not rerun/remove anything |

The BC-10 checkpoint `8cd3a88` and final `b3a5d75` filename lists were inspected read-only to identify the released paths, including **`app/src/main/java/com/bookcover/diagnostic/library/LibraryScreen.kt` and `app/src/main/java/com/bookcover/diagnostic/MainActivity.kt`**. These paths fall under the worker's explicit all-source release. No new Codex retention was reported; core leadership alone is not an active reservation.

Approved native Git inspection used the supported `exec_command` escalation, `--no-optional-locks` and `-C`; no security/configuration change or installation was needed. The earlier cwd-permission error remains undiagnosed. The previously listed `bc08-reading-comfort` worktree was left untouched; it is distinct from the user-reported removed `verify-bc20` worktree.

PM changed only these coordination documents, performed approved read-only inspections and sent the earlier authorized Codex PM note once. No new handoff request was necessary because explicit release was present. PM ran no app edits, shared tracker edits, tests, builds, device actions, Git mutations, pushes, Claude UI actions or chat resets.

## Ownership and dependencies

PM is the designated single integration/tracker writer. Opus accepts; Codex has completed its existing BC-10 task and explicitly released reservations. Workers provide concise path/evidence handoffs and proposed tracker text; acceptance changes accompany associated integration work in the same commit per CLAUDE.md. This setup authorizes no commit/push.

| Work | Current owner/status | Boundary |
| --- | --- | --- |
| BC-10 | Codex validated/committed; reservations explicitly released | Manual TalkBack/Scanner still pending; no new overlapping issue reported |
| BC-23 UI/UX | Opus lead; isolated `ui/SettingsRows.kt` completion reported and untracked presence verified | Retain that uncommitted component; no new scope inferred from completion |
| Library settings integration | Next bounded Opus slice; ownership dependency now released | `library/LibraryScreen.kt` and `MainActivity.kt` are available for PM's exact assignment. Existing-file wiring still needs that separate scoped task; isolated-component permission alone does not authorize it |
| Reader/backend core | Codex lead, no current retained reservation | New core/reader work needs an exact claim; coordinate UI interfaces with Opus |
| BC-24 / bounded isolation-CI | Deferred/unassigned | BC-24 never sent; AGP migration remains separate |

**Authorized isolated-component policy:** Opus may design and implement one self-contained NEW UI component after verifying its exact chosen path absent/unclaimed and briefly recording the claim. No extra per-step PM sign-off is required. The settings-path examples are nonexclusive; another safe new UI path is allowed. The completed `ui/SettingsRows.kt` is the reported result of that bounded permission.

That initial slice excludes Codex-held/shared files, existing-file wiring, backend changes, build scripts, tests, tracker/integration edits, build/test execution, phone use, commits and pushes. Shared wiring was blocked until exact handoff; BC-10's explicit blanket source release plus inspected commit paths now supplies that ownership handoff for LibraryScreen/MainActivity. PM can assign a small integration slice without treating unrelated reader files as assigned to Opus.

Issue IDs do not establish disjointness. Preserve untracked Opus work and the coordination files; do not move, stage broadly or transfer them automatically. PM alone edits this folder. No new implementation/integration task is sent by this status update.

## Cadence and truthful status

Use one small acceptance slice, usually 20-40 minutes of focused work. Check in at start, handoff, pause, scope expansion and resource request/release; avoid per-command coordination. If a slice takes longer, report the remaining boundary at about 45 minutes. Silence never releases files or resources.

- **Proposed:** candidate only; no assignment or editing permission.
- **Claimed:** named owner and exact paths recorded. For Opus's explicitly authorized new-component slice, worker verification of absent/unclaimed paths plus a brief recorded claim suffices; no additional per-step PM sign-off is needed. Other scope expansion requires coordination.
- **Active:** acknowledged work has actually started, with checkout/base and start time.
- **Verified:** specific checks passed for a named source revision/snapshot and artifact; list omissions and physical observations separately.
- **Paused/blocked:** work retained by the owner until an explicit handoff/release. **Released** records paths/resources freed, not issue completion.

PM records claims, status changes and reservations here once acknowledged. The canonical issue completion status stays in the existing tracker. BC-23 component completion is user-reported and its untracked path verified above. BC-10's explicit release is recorded separately; no new resource reservation or implementation task is created here.

## Shared-resource protocol (manual, not a lock)

| Resource | Current evidence | Rule |
| --- | --- | --- |
| BUILD | BC-10 explicitly released BUILD; no new holder recorded | Fresh acknowledged reservation before another build |
| PHONE `RFGL710TARJ` / QA apps | BC-10 explicitly released; later owner-requested installation completed | Fresh reservation plus owner availability; do not assume manual phone testing has ended |
| Source/test paths | BC-10 explicitly released all its reservations; Opus retains untracked `ui/SettingsRows.kt` | PM assigns exact next integration paths; no overlapping new issue |
| Tracker/integration | PM sole writer; current BC-10 commit already completed | Workers submit evidence/proposed wording; no competing edits |

Request a reservation with `owner | issue | checkout/base | exact build/cache/APK paths | device serial if relevant | operation | start UTC | expected finish`. PM checks the previous holder's explicit release, records the grant, and the worker acknowledges before running. Acquire BUILD before PHONE; phone tests build too. Release PHONE then BUILD on completion or failure, reporting exit status, artifact/report locations and remaining activity. A failed test is not a release until the worker confirms its own process ended. Never kill someone else's process or steal an expired/silent reservation; PM resolves uncertainty first. Owner phone use also needs a clear available window.

Packaging additionally needs an agreed source snapshot; do not package while source is changing. The scripts contain no reservation enforcement. Until a later isolated build experiment proves otherwise, serialize all builds and phone tests even in worktrees. Shared SDK/JDK, Gradle user cache/daemons, debug signing identity and the same phone QA package remain outside the per-lane output/cache arguments; do not change or clean those resources during another operation.

## Commands checked against scripts (not run)

After permission, ownership and reservation gates are satisfied, run from the chosen checkout. Proposed future lane names are `ui` for Opus UI/UX and `core` for Codex reader/backend; `bc10` describes the current owner's existing task. These names alone do not reserve resources; the separately authorized Opus component scope is described above. Use distinct artifact paths; issue labels alone are insufficient.

```powershell
$laneName = 'ui' # use 'core' or 'bc10' for that lane instead
$laneRoot = Join-Path $env:LOCALAPPDATA "BookCoverBuild\lanes\$laneName"
$apkName = "BookCover-$laneName-coordination.apk"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build.ps1 `
  -BuildRoot "$laneRoot\project-output" `
  -ProjectCache "$laneRoot\project-cache" -ApkName $apkName
```

`Build.ps1` runs exactly `assembleDebug testQaUnitTest lintDebug lintQa`; it writes checkout `local.properties`, copies the debug APK to `output\$apkName` and writes its `.sha256`. Keep those local files out of commits. Defaults are shared `%LOCALAPPDATA%\BookCoverBuild\project-output`, `project-cache`, and `output\BookCover-prototype.apk`; always pass all three arguments for lane work. `Build BookCover.cmd` pauses; use the script directly.

```powershell
# Both BUILD and PHONE reservations required; substitute the confirmed serial.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 `
  -Serial 'REPLACE_WITH_CONFIRMED_SERIAL' `
  -BuildRoot "$laneRoot\project-output" `
  -ProjectCache "$laneRoot\project-cache" `
  -TestClass 'com.bookcover.diagnostic.testing.LibraryAccessibilityTest,com.bookcover.diagnostic.testing.ReadingKeysAccessibilityTest'
```

The class filter above is the BC-10 example, not the whole suite. Omit `-TestClass` for the full QA suite; use exact applicable class names for other slices. This helper has **no `-ApkName` argument**: it builds/runs `connectedQaAndroidTest` and stores reports under the supplied build root. It checks `testBuildType = "qa"` and `.qa` suffix; preserve both. Never run instrumentation against `com.bookcover.diagnostic`, uninstall/clear its data, or bypass the helper. Phone must be unlocked; reader/layout checks need it unfolded, screen on and untouched. Record environmental failures and skipped tests honestly.

Packaging's supported form is:

```powershell
# FUTURE ONLY: new destination and verified matching inputs required.
python scripts\Package-Review.py --destination 'NEW_REVIEW_DESTINATION' `
  --tool-root 'MATCHING_TOOL_ROOT'
# Optional repeatable argument: --folded-results 'EXACT_RESULTS_DIRECTORY'
```

There is **no packaging APK, build-root or project-cache argument**. It reads checkout `output/BookCover-prototype.apk`, `<tool-root>/project-output/app` reports, the fixed baseline and `docs/review/RESULTS.md`; a new destination alone does not align isolated artifacts. It also invokes `git status` and `git log`, so do not run it under the present Git restriction. Do not copy a lane APK over the default to disguise this mismatch. A future bounded packaging change must add explicit inputs or review a correct staging layout, match source/APK/checksum/reports, and use a fresh destination. Scripts remain unchanged here.

## Validation before a lane can be called verified

Record checkout, source revision (or an explicitly uncommitted snapshot), exact paths, command, time, exit status and report/APK checksum. Baseline and each meaningful code slice require the build/unit/lint checks above; relevant regression tests and the integration check must use the same source. Prior tracker counts are historical evidence, not current passes. Read JUnit/lint outcomes; compilation alone does not prove QA passed. BC-10 additionally needs applicable QA tests, TalkBack import/open/page-turn/bookmark walkthrough, Accessibility Scanner and large-text checks; report pending parts separately. Reader changes need lifecycle/position and unfolded-layout regression checks. Only the owner physically observing the phone can verify outside-cover behavior; API callbacks are insufficient. No tests or device installs run during this setup.

## Future work-area preparation: blocked, not executed

The supported read-only inspection now verifies base `b3a5d75284ff140adb0b841b17acab78e23afc76` and the explicit BC-10 release is recorded. The checkout still contains Opus's untracked component and these untracked documents; a worktree at that commit would omit them. Do not copy/move/stage that work automatically or treat the entire checkout as clean. PM must agree its preservation/integration handoff and the base needed for the next task, then verify no new claims/operations before any worktree creation. No automatic worktree mutation is performed by this setup.

Only after the untracked-work handoff, agreed committed base and lack of conflicting operations are verified:

1. Inspect status including untracked files, branch/HEAD and `git worktree list --porcelain`. Record the approved full base revision. Confirm proposed branch names and target directories are absent; if occupied, stop and choose/review a new name.
2. Confirm filesystem permission for the proposed non-OneDrive parent `C:\Users\Frank\Documents\BookCover-Workareas`; create it only then. Do not change the existing checkout.
3. From an authorized shell, substitute the verified revision and create fresh branches/worktrees:

```powershell
$repoPath = 'C:\Users\Frank\OneDrive\Documents\ChatGPT\Book Cover - App'
$areasPath = 'C:\Users\Frank\Documents\BookCover-Workareas'
$verifiedBase = 'REPLACE_WITH_APPROVED_FULL_COMMIT'
git -C $repoPath worktree add -b 'pm/ui-bc23' "$areasPath\ui" $verifiedBase
git -C $repoPath worktree add -b 'pm/core-reader' "$areasPath\core" $verifiedBase
```

4. Verify both new worktrees' HEAD/status and the original checkout is unchanged. Do not copy `local.properties`, keys, output or personal books. Define each lane's output/cache/APK paths as above. Perform a separately authorized, serialized baseline build and compare artifact/report locations before considering concurrency.
5. Supply the handoff documents to workers only when messaging is authorized; record their acknowledgment and chosen lane. Creating worktrees alone does not mean current sessions moved or adopted this protocol.

## Handoff format

`UTC | worker | issue/slice | status | checkout + branch/base | exact changed/claimed paths | evidence (commands, exit, reports, APK SHA-256) | pending/owner observations | proposed tracker row | resource grants/releases | next dependency/owner`

Use [Codex handoff](CODEX-HANDOFF.md) and [Opus handoff](OPUS-HANDOFF.md). Keep handoffs concise and update before a pause. A reviewer can inspect another lane read-only; only its owner edits until release.

## Context and token management

Keep one PM coordination chat and one worker chat per bounded lane. Preserve current active chats until an acceptance checkpoint or explicit pause; do not reset, clear, compact or interrupt them as part of setup. Prefer a fresh worker chat at a completed issue/slice boundary or when the next task has a different file scope. A new chat must receive a durable handoff first; starting it does not transfer ownership or release reservations.

Use a compact kickoff packet: repository/worktree and verified base or blocker; links to AGENTS.md, CLAUDE.md and these three coordination files; one acceptance slice; exact owned/forbidden paths; decisions and ruled-out attempts; dependency and reservation state; latest evidence paths and next action. Have the receiving worker acknowledge paths and summarize the next step. Keep full logs and large diffs in files; quote only the relevant failure lines. Reuse the handoff format instead of pasting chat transcripts or repeatedly reading the whole repository. Delegate only independent, bounded investigations and request concise evidence/path summaries to avoid duplicated discovery and conflicting edits.

Claude Code guidance from PM's official-documentation review: keep a chat for a coherent task; use a fresh chat for unrelated work, a new bounded milestone or repeated failed corrections. For the same task with bloated context, `/compact` can preserve explicitly named constraints/state. For a new task, first save the distilled handoff, then `/clear`. These are Claude Code commands, not ordinary Claude chat commands, and are not actions to run on a live worker now. Subagents isolate context but consume the same allowance; they do not guarantee token savings. A fresh chat does not reset account limits. Sources: [best practices](https://code.claude.com/docs/en/best-practices), [costs](https://code.claude.com/docs/en/costs), [subagents](https://code.claude.com/docs/en/sub-agents).

OpenAI guidance from PM's official review likewise favors one coherent issue/outcome per chat: continue the same problem, and start fresh at an independent task boundary. A fork retains history; it is not a context reset. CLI compaction exists, while UI controls vary; do not assume a command applies to this interface. No universal reset percentage, token limit or savings guarantee is prescribed. Keep the existing AGENTS.md-to-CLAUDE.md pointer unchanged; stable rules stay concise, details behind relevant pointers, and task state stays in these handoffs rather than an ever-growing AGENTS activity log. Sources: [best practices](https://learn.chatgpt.com/guides/best-practices), [AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md), [worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees).

For future chat titles, use issue plus outcome and retain pending verification in the title when relevant. Renaming existing chats is handled separately by PM; this setup does not rename them.

</details>
