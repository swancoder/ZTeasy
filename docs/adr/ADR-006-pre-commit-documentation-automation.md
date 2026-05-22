# ADR-006 — Pre-Commit Documentation Automation via Claude Code Slash Command

**Status:** Accepted
**Date:** 2026-05-22

---

## Context

As the ZTE project grows through successive stages, documentation (README.md, CLAUDE.md, docs/adr/) has historically been updated manually after implementation work. This creates a gap where staged code changes can be committed without corresponding documentation updates, making the project state harder to understand for both human developers and future AI-assisted sessions.

The project already uses Claude Code as its primary engineering agent. Claude Code supports custom slash commands defined as Markdown files in `.claude/commands/`. A git hook mechanism exists (`scripts/install-hooks.sh`) to invoke a pre-commit check automatically.

---

## Decision

Introduce a `/pre-commit-docs` slash command (`.claude/commands/pre-commit-docs.md`) that acts as a documentation guardian. Before each commit the developer runs `/pre-commit-docs`, which:

1. Reads `git diff --staged` to understand the nature of staged changes.
2. Checks README.md, CLAUDE.md, docs/adr/, and prompts-hist/ for staleness relative to the diff.
3. Edits any out-of-date documentation in place and stages the updated files.
4. Reports what was changed (or confirms everything was already current).

A companion script `scripts/install-hooks.sh` is provided to wire a `.githooks/pre-commit` entry point that invokes the slash command automatically on every `git commit`. The `.githooks/pre-commit` hook itself is not yet implemented (deferred to a future stage).

---

## Consequences

**Positive:**
- Documentation drift is caught at commit time rather than discovered later.
- The slash command is self-describing — a developer reading `.claude/commands/pre-commit-docs.md` understands the full protocol without needing separate documentation.
- ADR creation is explicitly part of the checklist, reinforcing the ADR-per-decision protocol from CLAUDE.md.

**Negative / Risks:**
- The automation relies on the developer remembering to run `/pre-commit-docs` (or having the hook installed). If the hook is skipped via `git commit --no-verify`, docs may still drift.
- The `.githooks/pre-commit` file does not yet exist; `install-hooks.sh` will fail until it is created (tracked in Stage 7+ backlog).
- AI-generated documentation edits must be reviewed — the command can only infer intent from the diff, not from the developer's full mental model.

---

## Alternatives Considered

- **Linting-only CI check:** Would catch missing docs in CI but not help write them. Rejected as too punitive without being constructive.
- **Manual discipline alone:** Insufficient — has already led to documentation lag across Stages 1–6.
- **Separate documentation PR after each feature:** Adds churn and context-switching. Rejected in favour of inline, commit-time updates.
