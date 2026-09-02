# ADR-037 — No secrets in the repository, not even "dev-only" ones

**Status:** Accepted · 2026-09-02
**Context:** Stage 37 · reverses the "obviously-local dev values stay" position of ADR-030, ADR-002 and ADR-010

## Context

ADR-030 moved cloud credentials out of the repository and kept "obviously local"
dev values in it, so a fresh clone would run. The reasoning was that a value
guarding only a developer's laptop is not a credential.

That reasoning was wrong in a specific, checkable way. `deploy/azure/deploy.sh`
read `ZTE_OBO_SECRET` with the committed default as its fallback, so the
published string `zte-obo-dev-secret-change-in-production` became the HMAC key
signing on-behalf-of tokens **on a live, internet-facing deployment** — and the
same held for the Postgres password and the `zte-gateway` client secret. A
default that exists to make local development convenient will be inherited by
any deployment path that forgets to override it, and nothing warns you, because
a default is precisely the absence of a warning.

The distinction between "dev value" and "credential" is not a property of the
string. It is a property of what ends up using it, which changes over time.

## Decision

**Nothing secret-shaped stays in a tracked file.** Not as a default in
`application.yml`, not as a fallback in a shell script, not in the Keycloak realm
export, not in a compose file, not in a README command someone will paste.

- `scripts/generate-dev-secrets.sh` writes a gitignored `.env` with fresh random
  values. Spring reads it through `spring-dotenv` (the mechanism ADR-008 already
  chose for zt-agents) and `docker compose` substitutes from the same file, so
  one command makes a fresh clone runnable. It is idempotent: an existing key is
  never overwritten, because certificates and an imported realm both depend on
  values already issued.
- `keycloak/realm-export.json` becomes a **template** whose client secrets are
  placeholders. The realm that actually gets imported is generated —
  `make-cloud-realm.py --local` for a laptop, the same script for the cloud — and
  is gitignored. Two generators would drift; one, already exercised by the cloud
  path, does not.
- Every configuration property lost its default. Startup now fails with
  `Could not resolve placeholder` rather than silently using a published value.
  **That failure is the feature.**
- Integration tests generate their realm from the template with fixture values
  substituted at build time. Those fixtures authenticate to containers that are
  created and destroyed by the test run, and to nothing else — and, unlike the
  old dev defaults, no deployment path can reach them, because they exist only
  under `src/it`.

**And the published values were rotated.** `deploy/azure/rotate-secrets.sh`
mints new ones, reissues the certificates, rebuilds the realm, pushes everything
into Container Apps and restores the database from a dump taken before the
Postgres password changes. Removing a string from a public repository does not
un-publish it; only rotation does.

The `zte-gateway` client secret also moved from a plaintext environment variable
to a Container Apps secret reference, along with the database password — visible
in `az containerapp show` output was not much better than visible in git.

## Consequences

- A fresh clone needs one extra command before it runs, and says exactly which
  one when a value is missing.
- Every credential the repository ever published is dead: verified by presenting
  the old `zte-gateway` secret to the live token endpoint and getting `401`,
  while the new one issues a token.
- The demo state survived the rotation — 100 audit rows and 27 policy rules
  before and after, through a Postgres password change that recreates the
  container.
- ADR-002's and ADR-010's "acceptable dev-only" positions are reversed; both
  now point here, and their literal values have been removed from the text.

## Self-critique

- **History is not rewritten** (a deliberate choice, recorded here). The values
  remain in old commits, forks and GitHub's cache. This is only acceptable
  *because* they were rotated: the argument is "those strings no longer open
  anything", not "those strings are gone".
- **`.env` is plaintext on the developer's disk**, mode 600 and gitignored. A
  keychain or `sops`-encrypted file would be better and is not built.
- **The generator's first version corrupted the file it wrote** — a missing
  trailing newline glued the first key onto the previous line, silently damaging
  `ANTHROPIC_API_KEY`. Found immediately because the key list was verified rather
  than assumed. The guard is now in the script; the lesson is that a script that
  writes secrets must be checked by reading its output, not by its exit code.
- **Test fixtures are still values in git.** They protect ephemeral containers
  and nothing else, but the previous ADR said something similar about dev
  defaults, so the distinction is worth stating precisely: a dev default was
  read by a deployment script, a fixture under `src/it` cannot be — and if that
  ever stops being true, this decision needs revisiting.
- **Rotation is a script, not a schedule.** Nothing expires these values or
  reminds anyone to run it again.
