# ADR-024: Untracking Internal Engineering Notes (`CLAUDE.md`, `prompts-hist/`) from the Public Repo

## Status
Accepted

## Context

Since Stage 1 this repo has used a four-home documentation system (formalised
in ADR-006 and the `/pre-commit-docs` command): ADRs own *why*,
`docs/SPECS.md` owns *what exists now*, `prompts-hist/` owns *what was
literally asked* of each AI-assisted session, and `CLAUDE.md` owns *where to
look*. All four homes were tracked in git.

Two of those homes are internal working material rather than product
documentation: `CLAUDE.md` is an orientation file for Claude Code sessions,
and `prompts-hist/` is the verbatim log of every task prompt (plus
`SESSION_STATUS.txt`, a session hand-off scratch file). Neither is needed by
a reader of the public repo — README and `docs/` carry everything
user-facing and technical — and the prompt log in particular is engineering
process detail, not part of the system being demonstrated.

## Decision

Remove `CLAUDE.md` and `prompts-hist/` from git tracking (`git rm --cached`;
the working-tree copies remain) and add both to `.gitignore` under
"Internal engineering notes (not for public repo history)".

The documentation *system* is unchanged — both files continue to exist and
continue to be maintained locally exactly as before. What changes is
visibility: the four homes split into two public ones (`README.md`,
`docs/` — ADRs and SPECS.md) and two maintainer-local ones (`CLAUDE.md`,
`prompts-hist/`).

## Consequences

- **ADR-006 is amended, not superseded.** The `/pre-commit-docs` checklist
  still covers all four homes, but updates to `CLAUDE.md` and
  `prompts-hist/` are now local-only — they are gitignored and must not be
  `git add`-ed. The command file notes this.
- **Living reference docs no longer point public readers at local files.**
  `docs/SPECS.md` (intro and footer) now labels `prompts-hist/` and
  `CLAUDE.md` as maintainer-local. Mentions inside *older* ADRs are left
  as-is: ADRs are historical records, and rewriting them to hide a
  then-true reference would falsify the record.
- **History is not scrubbed.** Untracking removes these files only from
  future snapshots. Every commit up to and including `a5ee97e` still
  contains their full contents, so anyone cloning the repo can read the
  entire prompt log from history. This is accepted here — the goal is to
  stop *carrying them forward*, not to redact them. If true redaction is
  ever required, that is a separate, deliberate step (history rewrite via
  `git filter-repo` plus force-push, invalidating all existing clones) and
  should get its own decision.
- **New-clone onboarding note:** a fresh clone has no `CLAUDE.md`. Claude
  Code sessions on such a clone fall back to README + `docs/SPECS.md` §8
  (conventions), which were always the substantive references; `CLAUDE.md`
  was an index over them.
