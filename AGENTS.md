# BookCover — instructions for coding agents (Codex and others)

Read **[CLAUDE.md](CLAUDE.md)** in full; it applies to every agent, not only Claude Code.
Also follow the owner-approved **[sync workflow](docs/SYNC-WORKFLOW.md)** for branches,
private pushes, integration, build identity and installation handoffs. Its specific sync
rules supersede older conflicting push/integration wording; unrelated instructions and
all data/device/privacy safeguards remain in force.

The parts that matter most:

- **Hard safety rules** (CLAUDE.md): never run connected or instrumented tests against the main
  app `com.bookcover.diagnostic`; device tests only through `scripts/Test-Device.ps1`, which uses
  the separate `.qa` app. Never uninstall or clear the main app's data. Never commit the owner's
  books, signing keys, `local.properties` or `output/`. No internet access in the app.
- **Shared active queue:** check [docs/review/ACTIVE-BACKLOG.md](docs/review/ACTIVE-BACKLOG.md)
  before starting. Record new review findings there before implementation prompts; independently
  update your relevant row on material status changes or completion. The
  [BC completion tracker](docs/review/EXPERT-PANEL-REVIEW.md#completion-tracker) retains detailed
  BC evidence/history; record commit and verification with the work, as CLAUDE.md explains.
- **Shared working copy:** other agents work in this folder too. Stage only the files you
  changed (no `git add -A`), and check `git log` afterwards to confirm your commit exists.
