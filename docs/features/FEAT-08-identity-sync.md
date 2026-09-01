# FEAT-08 — IdP Identity Sync

**Maturity:** Production-ready
**Depends on:** Keycloak (via an adapter interface)
**Feeds:** FEAT-02 (URN matching, orphan detection), FEAT-13 (Identities tab)
**Detail:** [SPECS §5.6](../SPECS.md) · [ADR-014](../adr/ADR-014-idp-identity-sync.md), [ADR-015](../adr/ADR-015-machine-identities-and-urn-unification.md), [ADR-030](../adr/ADR-030-credential-hygiene-and-identity-reconciliation.md)

## What it does

Keeps a local mirror of who exists in the identity provider — people, groups,
roles and machine clients — plus their memberships and role assignments. No
secrets are ever copied: identifiers, names and relationships only. Policy
evaluation then reads this mirror instead of calling the IdP, and rules can
name identities precisely (`user:`, `group:`, `role:`, `client:`).

## Why it matters

Two things depend on it. First, **speed and independence**: an access
decision must not make a network call to Keycloak, and must keep working if
Keycloak is briefly unavailable. Second, **honesty about policy**: knowing
which identities exist is what makes it possible to say "this rule refers to
someone who no longer exists" — otherwise stale rules accumulate invisibly.

## Behaviour

**Given** the scheduled interval elapses (or an operator triggers a sync),
**when** it runs, **then** users, groups, roles and clients are fetched and
upserted, along with their relationships, in one cycle.

**Given** an identity that disappeared from the IdP, **when** the next sync
runs, **then** it is removed from the mirror — including the case where an
identity was deleted and recreated with a new id, which is what a realm
re-import does.

**Given** a type whose fetch returned nothing, **when** reconciliation runs,
**then** that type is skipped entirely — a failed or empty call must never be
read as "the IdP has no users" and empty the cache.

**Given** a policy rule naming an identity absent from the mirror, **when**
policy loads or reloads, **then** the rule is flagged as orphaned in logs and
in the console, and still applied — never auto-removed.

**Given** the IdP's built-in system clients, **when** clients are fetched,
**then** they are excluded, so the console shows business identities only.

**Given** a request for an identity's roles and groups, **when** it is served,
**then** it is answered entirely from the local mirror — no live IdP call on
that path, ever.

## Limits

- The mirror can be up to one sync interval stale (15 minutes by default); a
  manual sync is the immediate override.
- Relationship fetching costs two calls per user or client — fine at demo
  realm size, N+1-shaped at directory scale.
- Reconciliation covers identities, not relationships between identities that
  both still exist.
- User URNs match on username; a rename looks like a delete plus a create.
- Only a Keycloak adapter exists today, though the interface exists precisely
  so a second one (Entra ID, AWS IAM) can be added without touching policy.
