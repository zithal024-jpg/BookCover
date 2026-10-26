# BookCover — instructions for coding agents (Codex and others)

All project instructions live in **[CLAUDE.md](CLAUDE.md)**. Read it in full before doing
anything and follow it exactly; it applies to every agent, not only Claude Code. This file adds
no rules of its own, so the two can never disagree.

The parts that matter most:

- **Hard safety rules** (CLAUDE.md): never run connected or instrumented tests against the main
  app `com.bookcover.diagnostic`; device tests only through `scripts/Test-Device.ps1`, which uses
  the separate `.qa` app. Never uninstall or clear the main app's data. Never commit the owner's
  books, signing keys, `local.properties` or `output/`. No internet access in the app.
- **Shared status list:** check the Completion tracker at the top of
  [docs/review/EXPERT-PANEL-REVIEW.md](docs/review/EXPERT-PANEL-REVIEW.md) before starting, since
  another agent may already be on an issue. When you finish or pause, update that issue's row
  (status, commit, how it was verified) in the same commit as the work.
- **Shared working copy:** other agents work in this folder too. Stage only the files you
  changed (no `git add -A`), and check `git log` afterwards to confirm your commit exists.
