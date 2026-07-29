# ADR-008 — `.env`-Based Configuration Management for `zt-agents`

**Status:** Accepted
**Date:** 2026-05-22

---

## Context

`zt-agents` requires `ANTHROPIC_API_KEY` at startup and supports optional overrides for model,
timeout, max-tokens, and the gateway URI. Previously, developers had to `export` these values
manually in every terminal session or add them to shell profile files.

There was no canonical template showing which variables exist, and no mechanism to load them
automatically — making the setup step fragile and error-prone, especially in WSL2 environments
where environment inheritance between shells is inconsistent.

## Decision

Add `spring-dotenv` (`me.paulschwarz:spring-dotenv:4.0.0`) as a `zt-agents` runtime dependency.
At startup Spring Boot will look for a `.env` file in the working directory and merge its
key-value pairs into the Spring `Environment` before any other property source is resolved.

Provide `.env.example` committed to the repository root as a developer-facing template. The
actual `.env` is gitignored.

## Consequences

**Positive:**
- Developers copy `.env.example` to `.env` once; no shell exports or profile edits needed.
- All configurable keys are documented in one place — the template is the source of truth.
- Works consistently inside WSL2 terminals and IDE run configurations.
- `.env` values are still overridable by real environment variables (Spring property source
  precedence: env vars beat `.env` entries, so CI/CD pipelines are unaffected).

**Negative / Risks:**
- `spring-dotenv` is a third-party library not managed by Spring Boot's BOM; version must be
  kept in sync manually.
- If a developer accidentally places sensitive keys in `.env` and removes the gitignore entry,
  secrets could be committed. Mitigated by keeping `.env` in `.gitignore` and documenting the
  risk in `.env.example`.
- Adds one more abstraction to the configuration chain; debugging config precedence requires
  knowing that `spring-dotenv` inserts values at the `systemEnvironment` level.
