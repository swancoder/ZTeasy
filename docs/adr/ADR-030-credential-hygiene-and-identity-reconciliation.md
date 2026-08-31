# ADR-030: Credential Hygiene and Identity-Cache Reconciliation

**Status:** Accepted
**Date:** 2026-08-31
**Stage:** 30

## Context

Two problems surfaced once the demo became publicly reachable
(`demo.zteasy.tech`, ADR-028):

1. **Passwords lived in a public repository.** No password *hashes* were ever
   committed — `keycloak/realm-export.json` carries `credentials: []` for
   every user — but the plaintext values were: as defaults in
   `make-cloud-realm.py` and `set-keycloak-password.sh`, and as
   copy-pasteable examples in `README.md` and `docker-compose.cloud.yml`.
   The OIDC client secrets were committed outright. Anyone reading the repo
   could sign into the live deployment as `zte-admin`.
2. **The identity cache duplicated users.** `demo.zteasy.tech` listed
   `zte-admin` and `zte-test-user` four times each. `idp_identities` was
   append-only: it upserts on `(type, external_id)`, and a Keycloak realm
   re-import — which the cloud does on every restart by design — recreates
   every user with a *new* id, so each cycle added a row and orphaned the
   previous one. Named as a known gap in SPECS §10.

### THOUGHTS

- These are the same failure in two shapes: state that only ever accumulates
  (stale identities), and secrets that only ever spread (committed
  defaults). Both need a mechanism, not a one-off cleanup — otherwise they
  come back on the next re-import or the next `git add`.
- Local development must keep working from a fresh clone with no setup, so
  the answer isn't "no credentials anywhere". It's that repository
  credentials must be obviously-local and never the ones a reachable
  deployment uses.
- Deleting stale identities is destructive on a code path that runs
  unattended every 15 minutes. The dangerous case is an IdP call that
  fails or returns empty being read as "the IdP has nothing".

## Decision

**Credential hygiene.** Cloud credentials live only in
`deploy/azure/out/cloud-credentials.env` — gitignored, `0600`, generated
random. `make-cloud-realm.py` now *requires* them via env
(`ZTE_PW_*` / `ZTE_SECRET_*`) and exits with an explicit message if one is
missing: no defaults, so a forgotten variable fails loudly instead of
quietly shipping a public password. It also rewrites the OIDC client secrets
for the cloud, so the repo's `-dev-only` secrets are localhost-only in fact,
not just in name. `set-keycloak-password.sh` defaults to obviously-local
values (`localdev-admin`/`localdev-user`), README and compose comments point
at the credentials file instead of quoting passwords, and the integration
test's fixture password was renamed so a grep for real passwords stays empty.

**Identity reconciliation.** `IdpIdentityRepository.deleteMissing(type,
keepExternalIds)` plus a `reconcile()` step in `IdentitySyncService`: after
fetching, each *type* is reconciled against exactly the external ids that
type's fetch returned. A type whose fetch came back empty is skipped
entirely — a failed or empty call can never empty the cache — and removals
are logged.

## Alternatives considered

- **Rewriting git history** to purge the passwords. Rejected as the primary
  fix: rotation is what actually matters (the old values are compromised
  whether or not the text is scrubbed), and rewriting a published history
  breaks every clone. The old passwords are now dead, which is the real
  control.
- **Encrypted secrets in the repo** (SOPS/age). Better long-term, but it
  needs key distribution the project doesn't have yet; a gitignored file is
  the honest scope for a demo.
- **A `TRUNCATE`-then-reinsert sync.** Simplest reconciliation, rejected:
  it makes every sync a window where the cache is empty, and one failed
  fetch wipes real data.
- **Deduplicating by name instead of external id.** Would have hidden the
  symptom while leaving the cache append-only, and would break the moment
  two identities legitimately share a name across types.

### CRITIQUE

- The old passwords remain in git history and in this repo's published
  commits forever. Rotation covers the live system; anyone reading history
  still learns what the *previous* demo passwords were, which is only
  acceptable because they no longer open anything.
- `cloud-credentials.env` is a single plaintext file on one workstation —
  better than a public repo, worse than a secret manager. If that machine is
  lost, the deployment's credentials are lost with it (recoverable only by
  regenerating and re-deploying).
- Reconciliation deletes on a scheduled path. The empty-fetch guard covers
  the failure mode actually seen, but a *partial* fetch (IdP returns some
  users) would still delete the ones it omitted; that is the same assumption
  the upsert path already makes, and closing it properly needs the adapter
  to distinguish "complete list" from "page".
- `deleteMissing` uses `NOT IN (:ids)` — fine at demo scale, linear in the
  realm's size, and would want batching for a large directory.
- Relations are not reconciled: a deleted identity's rows are removed by
  the existing `ON DELETE CASCADE`, but relations whose subject and target
  both still exist are never re-checked.

## Consequences

- The published repository no longer contains any credential that opens the
  live deployment; the demo's real passwords exist in one local file.
- Old credentials were rotated and verified dead against the live domain.
- The identity cache converges: one row per identity per sync, stale rows
  removed automatically instead of accumulating on every realm re-import.
