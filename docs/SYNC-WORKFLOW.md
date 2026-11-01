# BookCover: shared development and release workflow

Owner-approved sync instructions, 2026-11-01. Use these with [CLAUDE.md](../CLAUDE.md).
This is a private development workflow, not approval to publish a repository,
release or APK. Public release remains blocked on the owner's signing decision
and the separately tracked dependency obligations.

## 1. One integration branch and one integration/install owner

- `origin/main` is BookCover's shared integration branch on the existing private
  development remote. Feature branches/worktrees are components, not alternative
  release branches. Do not infer completeness or acceptance from a branch name.
- The owner designates **one current integration and phone-install owner** in the
  existing [active backlog](review/ACTIVE-BACKLOG.md). Record that person's/agent's
  name, integration branch and accepted baseline/package there, not in another
  tracker. If the role is unclear, independent work can continue, but do not assume
  permission to merge integration or install. A previous component owner is not
  automatically the current integration owner.
- Component agents independently implement, validate and push their scoped feature
  branches. Only the designated integration owner promotes shared `main` and
  performs authorized owner-phone installations. No routine PM grant loop is needed.
- These approved rules authorize normal, tested pushes to the existing **private**
  development remote within assigned scope. They supersede older blanket no-push
  or independent-integration wording only for this sync workflow. A narrower task
  restriction still controls. All data/device/privacy safeguards remain in force.
- Keep BookCover and other apps in their own repositories. Share this workflow's
  Git principles; use each app's actual build/test commands and integration owner.

## 2. Fetch and inspect at the start

Run from the repository root. Check current instructions, backlog, worktrees and
actual conflicts before editing. Never clean, stash, reset or overwrite another
worker's changes to obtain a clean checkout.

```powershell
git status --short
git worktree list --porcelain
git fetch origin
if ($LASTEXITCODE -ne 0) { throw 'Fetch failed; remote state is unverified' }
git log --left-right --oneline HEAD...origin/main
git diff --stat HEAD...origin/main
```

Start a new component from the fetched remote integration ref in a new worktree;
choose an unused task name/path. This leaves a dirty or ahead integration checkout
untouched. Example:

```powershell
$branch = 'codex/example-task'
$worktree = Join-Path $env:USERPROFILE 'BookCover-Workareas\example-task'
git worktree add -b $branch $worktree origin/main
if ($LASTEXITCODE -ne 0) { throw 'Worktree not created; inspect existing state' }
```

If remote integration is missing an accepted baseline, tell the integration owner
and reconcile before app integration or packaging. Do not silently select an older
source, push unrelated unpublished app commits, or force local work to match remote.
Keep independent documentation work moving. Never use `git reset --hard`, blanket
conflict choices, `git clean`, or another worker's stash as a synchronization method.

## 3. Small tested commits and verified private pushes

After a completed change and before handing work off:

1. Run checks appropriate to the change. Android/build changes use the guarded
   helpers below; a documentation-only change needs link/command/whitespace checks,
   not a fabricated Android test pass. Record exact checks and unrun cases.
2. Inspect the diff, stage only owned paths, and make a small descriptive commit.
3. Fetch/check again before handoff, push the feature branch normally, and verify
   its remote SHA. Report base, commit, changed paths, evidence and any divergence.

```powershell
git diff --check
if ($LASTEXITCODE -ne 0) { throw 'Diff/whitespace check failed' }
git diff --name-only
# Replace these with only the paths belonging to the completed change.
git add -- docs/EXACT-FILE.md
if ($LASTEXITCODE -ne 0) { throw 'Staging failed' }
git diff --cached --check
if ($LASTEXITCODE -ne 0) { throw 'Staged diff/whitespace check failed' }
git diff --cached
git commit -m 'Describe the completed change'
if ($LASTEXITCODE -ne 0) { throw 'Commit failed; do not report it complete' }
git fetch origin
if ($LASTEXITCODE -ne 0) { throw 'Fetch failed' }
git log --left-right --oneline HEAD...origin/main
$branch = (git branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or -not $branch -or $branch -eq 'main') {
    throw 'Use an owned feature branch; main promotion belongs to the integration owner'
}
git push --set-upstream origin "HEAD:refs/heads/$branch"
if ($LASTEXITCODE -ne 0) { throw 'Push failed; inspect before retrying' }
$local = (git rev-parse HEAD).Trim()
$remote = git ls-remote origin "refs/heads/$branch"
if ($LASTEXITCODE -ne 0 -or -not $remote -or
    ($remote -split '\s+')[0] -ne $local) { throw 'Remote SHA not verified' }
```

A component push does not mean integration or release acceptance. If integration
advanced, report that fact and reconcile on the component branch or with the
integration owner before promotion. Never force-push, delete remote work, or
replace a non-fast-forward rejection with a force operation. Inspect an uncertain
push outcome with `ls-remote` before retrying. This workflow does not authorize
changing visibility, credentials, keys or repository security settings.

## 4. Integrate once; preserve accepted behavior

The integration owner uses their integration checkout, fetches again and compares
local `main`, remote `main`, the component and the current accepted/installed
baseline. Preserve unpublished commits; resolve genuine divergence deliberately.
Merge/reconcile conflicts without dropping either accepted behavior or newer work,
then validate the resulting combined source before the normal integration push.
Do not integrate from someone else's dirty checkout.

Record the full accepted baseline SHA **and package** in the existing backlog.
Check ancestry against the frozen candidate; exit 1 is a stop, not a pass:

```powershell
$accepted = 'REPLACE_WITH_FULL_ACCEPTED_BASELINE_SHA'
$candidate = (git rev-parse HEAD).Trim()
git merge-base --is-ancestor $accepted $candidate
if ($LASTEXITCODE -ne 0) { throw 'Accepted baseline missing; reconcile first' }
git merge-base --is-ancestor origin/main $candidate
if ($LASTEXITCODE -ne 0) { throw 'Fetched integration missing; reconcile first' }
```

Ancestry is necessary but cannot prove features survived a later revert. Inspect
the actual diff and run the affected regression checks, including these accepted
journeys. Use generated/nonpersonal books only:

| Area | Required evidence and boundary |
| --- | --- |
| Library Home | Import/duplicate handling, Continue reading, collections/search/sort, settings and existing data remain usable; retain accepted layout and base Streak behavior. |
| Reading and reopen | Native LTR/RTL and available paper turns, bookmarks/search/settings, background/recreation and Back → Library → reopen retain the actual sentence/quote, not just a percentage. Include keyboard-dismiss/save/reopen when affected and existing PDF modes when present. |
| Cover controls | Auto/manual Start, Stop and exit behavior preserve settings/timers. API status and screenshots of the inner screen do not prove the physical outside cover. |
| Fold continuity | Check the same passage and one reader across real fold/unfold/rotation when affected. Emulator geometry/device-state overrides are not physical hinge or outside-cover evidence. |
| Data/accessibility/errors | Retain import/backup/lifecycle guards, useful error recovery, reachable controls and relevant large-text checks. Do not erase personal data to make a test pass. |

Classify failures as candidate-only, baseline-reproduced or unresolved. New
regressions block promotion; baseline failures need an explicit evidence-based
disposition. Keep failed attempts and unrun cases; never rerun until green and
discard the failures or combine different snapshots into one claimed suite pass.
Deferred features are not added or used as gates by this workflow.

Keep QA fixtures isolated too. Global QA book resets use
[the fixture helper](../app/src/androidTest/java/com/bookcover/diagnostic/testing/QaBookFixtures.kt)
and preserve the shared history/relic stores; use `BookLibrary.forApp` for the
global application library. Corrupt-storage checks use private fixture contexts.
Retain combined-process checks across reader and relic flows. Separate-process
passes are separate evidence, not a full-suite pass. Record unsupported Fold
checks as blocked; do not replace them with an emulator success claim.

## 5. Freeze identity, test and package matching bytes

Fetch/check again before integration and packaging. Pre-integration QA can run on
the proposed combined branch. After the integration owner validates and normally
pushes that result, final release candidates come
from a clean, tested integration commit containing the accepted baseline, with
local/remote integration reconciled. For final packaging/install, require published
`main` and check tracked and untracked changes:

```powershell
git fetch origin
if ($LASTEXITCODE -ne 0) { throw 'Fetch failed' }
$changes = git status --porcelain
if ($LASTEXITCODE -ne 0) { throw 'Git status failed; cleanliness is unknown' }
if ($changes) { throw 'Candidate checkout is dirty; preserve and reconcile' }
$branch = (git branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or $branch -ne 'main') { throw 'Final candidate must use main' }
$codeCommit = (git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Candidate SHA unavailable' }
$published = (git rev-parse origin/main).Trim()
if ($LASTEXITCODE -ne 0 -or $codeCommit -ne $published) {
    throw 'Integration is not synchronized with published main; reconcile first'
}
$accepted = 'REPLACE_WITH_FULL_ACCEPTED_BASELINE_SHA'
git merge-base --is-ancestor $accepted $codeCommit
if ($LASTEXITCODE -ne 0) { throw 'Accepted baseline missing; reconcile first' }
```

Use the same task ID and checkout for matching build/device evidence. Substitute
an exact existing class/method selection and actual resource/availability evidence;
these examples are not authorization to operate a phone:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Build.ps1 -TaskId candidate -Owner INTEGRATION_OWNER
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\Test-Device.ps1 -TaskId candidate -Owner INTEGRATION_OWNER -Serial CONFIRMED_SERIAL -PhoneReleaseEvidence 'ACTUAL PRIOR RELEASE AND OWNER AVAILABILITY' -TestClass 'EXACT_EXISTING_CLASS_OR_METHOD_LIST'
python scripts\Package-Review.py --evidence-manifest 'EXACT_MANIFEST_PRINTED_BY_HELPER.json' --validate-only
```

See [guarded resources and evidence](BC-25-TASK-RESOURCES.md). `-Plan` prints
context/paths, not lock ownership. Builds/tests reserve shared resources; device
tests use only `.qa`. Ordinary apps, including `com.bookcover.diagnostic` and
`com.frankrodriguez.bookcover`, must never be instrumentation targets. Never
uninstall/clear user data. Keep phone approval, release evidence and availability;
one owner's approval is not permission for overlapping device work.

Archive the exact receipts, console, XML/lint reports, manifest and APK/checksum
before rerunning the same task: latest outputs are replaced. Inputs must keep the
same fingerprint before/after. Scripts, tests and `.github` count as inputs; docs
are excluded. If source changes, validate the new source; do not relabel old QA.

Keep the schema-2 helper manifest plus a compact candidate identity record beside
the artifact, outside Git/under ignored output. This is release evidence, not a
new work tracker. Record:

- Full Git code SHA, integration branch/ref and accepted baseline; capture SHA
  before/after checks. Later docs-only commits get a separate identifier and must
  not be presented as the code revision built into the APK.
- Exact APK filename, byte count and SHA-256; package, version name/code and signer
  certificate from that APK. Do not trust a filename or hardcoded About label.
- Build-source fingerprint, matching manifest path/hash, test selections/counts,
  failures/skips/unrun cases and environment; actual physical owner observations
  with their limits. Link preserved artifacts, not overwritten latest reports.

```powershell
$apk = 'REPLACE_WITH_EXACT_APK_PATH'
$buildTools = 'REPLACE_WITH_INSTALLED_SDK_BUILD_TOOLS_DIRECTORY'
# Use the actual JDK/SDK selected by the build helper; do not guess paths/versions.
Get-FileHash -LiteralPath $apk -Algorithm SHA256
& (Join-Path $buildTools 'aapt2.exe') dump badging $apk
& (Join-Path $buildTools 'aapt2.exe') dump permissions $apk
& (Join-Path $buildTools 'apksigner.bat') verify --verbose --print-certs $apk
```

The install owner checks package/signing/version compatibility and retains the
previous verified APK/evidence first. A different application ID is a separate
app, not an in-place migration. Do not infer that either installed package can be
replaced, or choose a new signing identity, from this document.

Only with current authorization and BUILD then PHONE reservations, perform the
authorized installation and verify installed bytes. The resource APIs are in
`scripts/Task-Resources.ps1`; release only your leases after calls are known ended.
There is no ordinary-install helper here: `Test-Device.ps1` is not an installer.
For a single-APK install, use the exact `base.apk` path returned by `pm path`:

```powershell
$adb = 'REPLACE_WITH_ACTUAL_SDK_PLATFORM_TOOLS_ADB_EXE'
$serial = 'CONFIRMED_SERIAL'
$packageId = 'EXACT_APPROVED_APPLICATION_ID'
& $adb -s $serial shell pm path $packageId
$installedBasePath = 'EXACT_BASE_APK_PATH_RETURNED_ABOVE'
& $adb -s $serial shell sha256sum $installedBasePath
& $adb -s $serial shell dumpsys package $packageId | Select-String 'versionCode|versionName|firstInstallTime|lastUpdateTime'
```

Require the installed hash to equal the verified APK hash, plus matching package,
version and signer-bound bytes. Stop if the installation is split or otherwise
not the expected single APK. Installation-time continuity is useful evidence but
does not prove every datum survived. Physical acceptance still needs the owner's
actual Library/reading/reopen/cover/fold observations, never an API-success claim.

## 6. Retain rollback materials; never force a release

Release only the frozen, clean, tested integration source and its matching APK.
Keep the previous exact APK, package/version/signer, source, checksum and evidence
in a private, clearly labelled rollback location outside Git. Preserve original
EPUB/PDF files and the supported backup. Current backups include book metadata,
reading history, relic progress and saved relic appearances; they exclude book
files, artwork, general reader/Library/outside-cover preferences and temporary
testing access/appearances. Older backups lacking the newer fields restore book
metadata only. Do not call it a full device/library backup.

Retaining an APK does not authorize a rollback: version, signature and data-schema
compatibility still need checking and owner permission. A verified forward fix may
be safer. Never force-push, force-overwrite work, uninstall, clear data or force a
downgrade to make an old build install. Keep keys, personal books, backups and APKs
out of commits. Public publication remains a separate owner decision.

## What is enforced today

| Check | Existing enforcement versus policy |
| --- | --- |
| Reservations, `.qa` isolation, completed operations, equal source fingerprints, report/skip accounting and artifact hashes | Existing build/device/resource helpers and schema-2 packaging checks enforce their documented relationships. Packaging validation reserves BUILD even with `--validate-only`. |
| CI | Builds debug/QA, runs unit/lint/host checks and validates the expected QA target. It does not run device tests, install ordinary apps or retain rollback APKs; report retention is limited. |
| Clean Git state, latest remote, accepted ancestry, feature preservation, one integration/install owner, signing/installed identity, physical acceptance and rollback compatibility | Required policy/manual checks above. The helpers accept dirty source and do not inspect Git or certify these conditions. |

No branch protection, push/install hook, signing service or security setting is
created by this Markdown file. Follow the checks; do not claim that documentation
alone prevents a conflicting push, regression, wrong APK or unsafe installation.
