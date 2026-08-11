# ADR-015: Machine Identities (OIDC Clients) and URN Unification

**Status:** Accepted
**Date:** 2026-08-11
**Deciders:** ZTE-Lightweight Architects

---

## Context

ADR-014 synced Keycloak's Users, Groups, and Roles into a local
`idp_identities` cache and introduced `user:`/`group:`/`role:` URN sources
for `users2service` rules. Machine identities — Agent A, Agent B, and
`zte-gateway` itself, all OIDC confidential clients authenticating via
Client Credentials — were entirely absent from that cache: `service2service`
and `agentMcpToolCalls` rules still matched a caller's JWT `azp` claim
against a bare, unvalidated string, with no way to see "which clients
exist," detect a typo'd or stale client id in a policy rule, or browse
machine identities in the Admin Console the way users/groups/roles already
could be. This closes that gap: OIDC clients become a fourth
`idp_identities` type (`CLIENT`), synced the same way, and `service2service`/
`agentMcpToolCalls` sources gain the same `client:<clientId>` URN form and
orphaned-rule detection `users2service` already had.

---

## Decision

### Clients are fetched, not filtered

`IdpClient.fetchClients()` (`KeycloakIdpAdapter`'s implementation) calls
Keycloak's `GET /admin/realms/{realm}/clients` and syncs **every** client in
the realm — not just ones with `serviceAccountsEnabled: true`. A realm this
size has a handful of Keycloak-builtin clients (`account`,
`account-console`, `admin-cli`, `broker`, `realm-management`,
`security-admin-console`) alongside the actual actors
(`zte-gateway`/`agent-a`/`agent-b`/`zte-admin-ui`) — fetching all of them is
simpler than filtering, and the "noise" is harmless: those built-in clients
simply never appear as a rule's `source` and sit unused in the Identities
tab. A `serviceAccountsEnabled`-only filter is a legitimate future
tightening (Future Migration Path) but not required for correctness today —
this task's own Self-Criticism explicitly accepted "fetch all clients" as
sufficient for MVP.

`external_id` = the client's internal Keycloak UUID (`id`), `name` = the
human-facing `clientId` (`agent-a`) — the same field every existing
`service2service`/`agentMcpToolCalls` rule's `source` already contains, so
existing bare-form rules resolve against the cache with zero rule changes.
`display_name` = the client's `name` attribute, falling back to
`description`, falling back to `clientId` — mirrors `KeycloakUser`'s/
`KeycloakRole`'s existing fallback-chain pattern in the same adapter class.

### Schema: widen the existing CHECK constraint, not a new column or table

`V6__add_client_identity_type.sql` drops and recreates
`idp_identities_type_check` to add `'CLIENT'` to the allowed values — `type`
is `VARCHAR(10)`+`CHECK`, not a native Postgres enum (V5's own reasoning:
avoids needing an R2DBC enum codec registrar), so this is a single ALTER
TABLE, not a data migration. No new column, no new table — `CLIENT` is a
peer of `USER`/`GROUP`/`ROLE` in the exact same cache, keeping the "IdP
identity" concept unified rather than splitting machine identities into a
parallel structure.

### `IdentityUrn.parse` gains a category-aware default type

This is the one genuinely new piece of design, not just "add a fourth
prefix": ADR-014's `IdentityUrn.parse(source)` hardcoded a bare (no-prefix)
source to mean `ROLE` — correct for `users2service`, where that was the
only pre-existing convention. But `service2service`/`agentMcpToolCalls`
sources were *never* role names — every existing rule was already a bare
OAuth2 client id (`source: agent-a`). Reusing the ROLE-default parser for
those two categories would make every single existing rule "orphaned"
(client ids don't exist as ROLE-type cache entries) the moment orphan
checking was extended to them — clearly wrong.

The fix: `IdentityUrn.parse(String source, IdentityType defaultType)` — the
caller supplies what a bare source implies. `users2service` call sites keep
calling the original one-argument `parse(source)`, now a thin delegate to
`parse(source, IdentityType.ROLE)` (verified byte-for-byte behaviorally
identical via `oneArgOverload_isEquivalentToRoleDefault`).
`service2service`/`agentMcpToolCalls` orphan-checking calls
`parse(source, IdentityType.CLIENT)`. An explicit prefix (`role:`, `user:`,
`group:`, `client:`) always wins over the default regardless of category —
only the *bare* form is category-sensitive.

### Policy matching: the same enrich-don't-replace pattern as ADR-014

`PolicyMatcher` needed zero changes — same as ADR-014, it's still pure
generic string-list matching. `IdentitySources.enrichClient(clientId)`
(mirroring `enrich(roles, jwtAuth)`) returns `[clientId, "client:" +
clientId]` — both `ServiceToServiceAuthorizationFilter` and
`YamlMcpPolicyEngine` now pass this enriched list to
`policyMatcher.evaluate(...)` instead of the single bare `clientId`/`agentId`
string. Every existing bare-form rule (`source: agent-a`) keeps matching
unchanged — proven by running `ServiceToServiceAuthorizationFilterTest`/
`YamlMcpPolicyEngineTest`'s existing suites unmodified, plus one new
`client:`-prefixed test added to each.

### Orphaned-rule checking extends to all three categories uniformly

`OrphanedRuleChecker.check(document)` now runs three `Flux`s (one per
category, `Flux.merge`d) instead of one — `users2service` rules parsed with
the `ROLE` default, `service2service`/`agentMcpToolCalls` with `CLIENT`.
Each category's per-rule check already had its own `onErrorResume`
(ADR-014's live-tested fix for the Flyway/R2DBC startup race), so merging
three category streams doesn't reintroduce that failure mode — a query
failure in one category's rule still can't silently drop another
category's result via `flatMap`'s single-error propagation, because the
resilience is per-rule, not per-category-stream.

### Shipped YAML migrated to the new syntax; old syntax still works

`zte-policies.yaml`'s two `agentMcpToolCalls` ALLOW rules
(`mcp-allow-agent-a-get-deals`, `mcp-allow-agent-b-update-deal-stage`) now
use `source: "client:agent-a"`/`"client:agent-b"` — the task's literal ask.
`docs/examples/zte-policies-example.yaml`'s `service2service` and
`agentMcpToolCalls` examples were updated the same way, for consistency.
This is **not** a breaking change for any *other* deployment's existing
bare-form rules — `enrichClient`'s backward-compatible enrichment means a
pre-ADR-015 `source: agent-a` rule keeps matching identically; the shipped
file was updated purely to demonstrate and default to the clearer,
now-validated URN form going forward.

### UI: no new components, the existing ones already generalize

`Identities.tsx` renders whatever `type` the API returns with no hardcoded
type list — `CLIENT` rows appear automatically once `types.ts`'s
`IdpIdentityEntry.type` union includes it. `PolicyDashboard.tsx`'s orphan
highlighting previously only ran for the `users2service` table
(`identitySet={category.key === 'users2service' ? identitySet : undefined}`)
— now every category gets the identity set, and a new
per-`Category.defaultSourceType` field (`ROLE` for `users2service`,
`CLIENT` for the other two) drives the client-side `parseUrn` port, mirroring
the backend's `IdentityUrn.parse(source, defaultType)` split exactly.

---

## Alternatives Considered

### Filter `fetchClients()` to `serviceAccountsEnabled` clients only (rejected for now)

Would keep the cache free of Keycloak's built-in clients (`account`,
`broker`, etc.), which can never legitimately appear in a policy rule.

- **Pros:** A cleaner Identities tab, no noise to scroll past.
- **Cons:** Keycloak's Admin REST API doesn't support filtering
  `serviceAccountsEnabled` server-side on the `/clients` list endpoint — it
  would require fetching every client anyway and filtering client-side,
  adding code for a purely cosmetic improvement. The task's own
  Self-Criticism explicitly green-lit "fetch all clients... for simplicity
  in MVP."
- **Verdict:** Rejected for this task; listed in Future Migration Path as a
  legitimate later tightening once/if the noise becomes a real usability
  problem in the Identities tab.

### A separate `client_identities` table instead of widening `idp_identities` (rejected)

- **Pros:** Avoids an `ALTER TABLE` on a table other features
  (`OrphanedRuleChecker`, the search endpoint) already depend on.
- **Cons:** Directly contradicts this task's own stated goal — "unify our
  identity model" — by creating a second, parallel cache with its own
  query/search/UI code path. `OrphanedRuleChecker`, `AdminIdentitySearchController`,
  and `Identities.tsx` would all need type-specific branching instead of
  treating every identity kind uniformly.
- **Verdict:** Rejected — a widened CHECK constraint is a one-line schema
  change; a second table is a permanent structural fork for no functional
  gain.

---

## Self-Critique

| Risk | Severity | Mitigation |
|---|---|---|
| Every Keycloak built-in client (`account`, `broker`, `realm-management`, `security-admin-console`, `admin-cli`) is synced and shown in the Identities tab alongside real actors, with no visual distinction | Low | Deliberate MVP simplification (see Alternatives Considered); harmless — these clients simply never appear as a rule `source`, so orphan-checking and policy matching are unaffected. Purely a UI browsability nit. |
| `IdentityUrn.parse`'s category-aware default type is a real behavioral fork in a previously single-purpose parser — a future third category with yet another default-type convention would need a third call-site decision, not a config value | Low | Only two default types exist today (`ROLE`, `CLIENT`), both hardcoded at their respective call sites (`OrphanedRuleChecker`, matching the categories' own historical conventions) rather than made configurable — no evidence yet that a third convention is needed, and adding indirection for a hypothetical one would be premature. |
| `KeycloakIdpAdapter.fetchClients()` has no dedicated mocked-`WebClient` unit test, same as `fetchUsers`/`fetchGroups`/`fetchRoles` before it | Low | Consistent with existing precedent (ADR-014) — this adapter's correctness is proven by `IdentitySyncIT` against a real Testcontainers Keycloak, not mocked HTTP. Extending that IT (`manualSync_populatesClients`) is the actual verification here, not a new gap. |
| A client with an empty/blank `name` **and** `description` falls back to its own `clientId` for `displayName` — functionally identical to `USER`'s username-fallback, but means "display name" is sometimes literally the same string as "name" for clients, more often than for users/roles | Low | Not a functional problem — the Identities tab already renders `name`/`displayName` as separate columns regardless of whether they happen to be equal; no code assumes they differ. |

---

## Consequences

- **Positive:** `service2service`/`agentMcpToolCalls` rules can now be
  validated the same way `users2service` rules already are — a typo'd or
  stale client id in a policy rule is now visible via the same
  `ORPHANED RULE` warning and Admin Console highlight, closing a real gap
  (this ADR's original motivation — see the "explain me why I don't see
  agents identities" conversation that prompted it).
- **Positive:** `IdentityUrn`/`IdentitySources`/`OrphanedRuleChecker`
  required only additive changes (a new overload, a new method, a
  `Flux.merge`) — no existing `users2service` behavior changed, verified by
  every ADR-014 test passing unmodified.
- **Positive:** The Admin Console's Identities tab now shows the complete
  picture of every identity kind this system's policies can reference —
  users, groups, roles, *and* machine identities — in one place.
- **Negative:** The Identities tab now shows several Keycloak built-in
  clients that are never policy-relevant (see Self-Critique) — a minor,
  accepted browsability cost.
- **Negative:** `idp_identities.type`'s `CHECK` constraint has now been
  altered twice (V5 introduced it, V6 widens it) — a real, if small,
  reminder that a `VARCHAR`+`CHECK` "enum" needs a migration for every new
  value, unlike some other approaches (e.g. no constraint at all). Accepted
  because the alternative (no CHECK) trades away a real safety net for
  a savings that will basically never matter at this identity-kind
  cardinality.

---

## Future Migration Path

- **Filter `fetchClients()` to `serviceAccountsEnabled` clients**, once/if
  the built-in-client noise in the Identities tab becomes a real usability
  complaint (see Alternatives Considered).
- **A visual distinction in the Identities tab** between "actor" clients
  (referenced by at least one policy rule) and unused/built-in ones, without
  filtering them out of the sync entirely.
- **Extend `IdentityUrn`'s default-type convention to a config-driven
  mapping** if a third category with its own default-type convention is
  ever added — not attempted here since only two conventions exist and both
  are hardcoded at their historically-established call sites (see
  Self-Critique).
