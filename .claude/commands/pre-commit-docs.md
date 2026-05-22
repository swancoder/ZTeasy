# Pre-Commit Documentation Check

You are a documentation guardian for the ZTeasy project. Before every commit, check and update documentation to reflect the actual state of the code.

## What to do

### Step 1: Understand what changed

Run `git diff --staged --stat` to see which files are staged for commit.
Run `git diff --staged` to read the actual diff.

Identify the nature of the changes:
- New feature or module added?
- Existing behaviour changed?
- Security model or trust chain modified?
- New agent, service, or component introduced?
- Configuration or setup changed?
- Bug fix that changes expected behaviour?

### Step 2: Check each documentation location

For each location below, read the current content and compare against the staged diff.

**./README.md**
- Does the architecture diagram / module map still reflect reality?
- Are Quick Start instructions still accurate?
- Is the Stage Progress table up to date?
- Are new modules, agents, or services listed?
- Are new environment variables or setup steps documented?

**./CLAUDE.md**
- Does it accurately describe the project structure for Claude Code?
- Are new modules and their responsibilities listed?
- Are build commands, test commands, and run commands still correct?
- Are coding conventions and patterns up to date?

**./docs/adr/**
- Does the staged change introduce an architectural decision that needs a new ADR?
  Triggers: new security mechanism, new integration pattern, new framework or library, significant design tradeoff made.
- Do any existing ADRs need to be updated to "Superseded" or "Amended"?
- Follow the existing ADR format: number, title, status, context, decision, consequences.

**./prompts-hist/**
- If the staged change was significantly driven by AI-assisted development (Claude Code sessions), add a brief entry noting what was built and the key prompt patterns used.
- Format: `YYYY-MM-DD-<short-description>.md`
- Keep it lightweight — 5-10 lines is enough.

### Step 3: Make updates

For each location that needs updating:
1. Edit the file directly.
2. Stage the updated documentation file with `git add <file>`.
3. Report what you changed and why.

If no updates are needed, say so explicitly — "Documentation is up to date, nothing to change."

### Step 4: Report

After completing all checks, output a short summary:

```
Documentation check complete:
✅ README.md — <updated / no changes needed>
✅ CLAUDE.md — <updated / no changes needed>  
✅ docs/adr/ — <new ADR-00X added / no new ADR needed / ADR-00X updated>
✅ prompts-hist/ — <entry added / no entry needed>

Ready to commit.
```

## Rules

- Do not modify source code — documentation only.
- Do not invent features that aren't in the diff — describe what is actually there.
- If you're unsure whether a change warrants a new ADR, err on the side of creating one. ADRs are cheap; undocumented decisions are expensive.
- Keep README and CLAUDE.md factual and concise — no marketing language.
- If a staged change is purely cosmetic (formatting, typo fix, comment cleanup), skip prompts-hist.
