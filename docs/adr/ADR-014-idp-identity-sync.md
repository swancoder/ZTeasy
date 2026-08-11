# ADR-014: IdP Identity Sync and URN-Based Policy Matching

**Status:** Accepted
**Date:** 2026-08-11
**Deciders:** ZTE-Lightweight Architects

---

## Context

`users2service` policy rules referenced callers by bare Keycloak realm-role
name only (`source: ADMIN`). This adds a richer IdP-backed identity model —
Users, Groups, Roles, addressable by URN (`user:<name>`, `group:<name>`,
`role:<name>`) — backed by a local Postgres cache of Keycloak's identity
data, synced periodically rather than queried on-demand per request, via an
`IdpClient` adapter interface designed for a future non-Keycloak provider
(Azure Entra ID, AWS IAM). Also adds orphaned-rule detection: a rule whose
`source` doesn't resolve to any identity in the cache logs a warning, but is
never rejected or deleted.

---

## Decision

### Schema and caching, not on-demand lookup

`gateway-service/src/main/resources/db/migration/V5__create_idp_identities.sql`
creates `idp_identities` (`id`, `type` — `VARCHAR(10)` + `CHECK`, not a
native Postgres enum, matching `RuleEffect`'s precedent that a plain
string-backed shape avoids needing an R2DBC enum codec registrar —
`external_id`, `name`, `display_name`, `last_synced`, `UNIQUE (type,
external_id)`). Deliberately **only** id/type/name — no sensitive IdP data
(passwords, secrets, tokens) ever lands in this table, matching the Self-
Criticism instruction this task shipped with.

Caching locally (rather than calling Keycloak's Admin REST API on every
policy decision) keeps the request-serving path's zero-I/O property intact
— `PolicyMatcher.evaluate()` and `ZteAuthorizationFilter`'s hot path stay
exactly as fast and dependency-free as before this feature. The tradeoff is
staleness: a newly created Keycloak user isn't policy-addressable by
`user:<name>` until the next sync (15 min default) or a manual
`POST /api/v1/admin/identities/sync`.

### Adapter pattern for future non-Keycloak IdPs

`com.zte.gateway.identity.IdpClient` (`fetchUsers()`/`fetchGroups()`/`fetchRoles()`,
each `Flux<IdpIdentity>`) is the only interface `IdentitySyncService`
depends on. `KeycloakIdpAdapter implements IdpClient`,
`@ConditionalOnProperty(zte.idp.provider=keycloak, matchIfMissing=true)`, is
the only implementation today — a future Azure Entra ID or AWS IAM adapter
drops in behind the same interface with no changes to the sync engine,
repository, orphan checker, or policy matching.

`KeycloakIdpAdapter` reuses `zte-gateway`'s existing service account (already
`serviceAccountsEnabled: true` in `realm-export.json`, secret already known)
rather than a new dedicated Keycloak client — one fewer secret to manage.
`keycloak/realm-export.json` grants that service account
`realm-management`'s `view-users`/`view-realm` client roles. Constructor-
injects `WebClient.Builder` exactly like `McpBackendClient` does; obtains a
fresh client-credentials token per `fetchX()` call rather than caching one —
Keycloak's default 300s access-token lifespan is shorter than the 15-min
sync interval anyway, so cross-sync caching buys nothing, and reusing one
token across the 3 calls within a single sync isn't worth the added state
for 3 cheap extra token requests per cycle.

A `oidc-group-membership-mapper` (claim `groups`, `full.path: false`) was
added to `zte-gateway`'s protocol mappers — Keycloak only emits claims an
explicit mapper requests (the existing `realm_access.roles`/
`preferred_username` mappers already established this), so `group:` rules
would otherwise be silently inert. `zte-realm` has no groups defined yet, so
this capability is implemented but currently unexercised — named explicitly
here, not left as a silent gap or faked with an invented demo group this
task didn't ask for.

### Sync engine — real UPSERT, non-blocking by construction

`IdentitySyncService.syncNow(): Mono<Integer>` fetches all three identity
kinds and upserts each via `IdpIdentityRepository.upsert(...)` — a real
Postgres `INSERT ... ON CONFLICT (type, external_id) DO UPDATE`, not
`ReactiveCrudRepository.save()`. `save()`'s "null id = new entity"
convention (the same one `RequestLog`, ADR-013, established) would attempt a
fresh INSERT every sync cycle and violate the `UNIQUE (type, external_id)`
constraint on the second cycle for the exact same Keycloak identity.

`@Scheduled(fixedDelayString = "${zte.idp.sync-interval-ms:900000}")`
(`refresh()`) is invoked by Spring's own `TaskScheduler` thread, never the
Netty event loop, and the reactive chain never calls `.block()` — "don't
block the reactive event loop" holds by construction, not by careful
discipline, which is worth stating explicitly since a stray `.block()` is an
easy accidental violation. `POST /api/v1/admin/identities/sync`
(`AdminIdentitySyncController`) and `GET /api/v1/admin/identities/search`
(`AdminIdentitySearchController`) need no new security wiring — both are
covered by the existing `u2s-admin-console-api` YAML rule and
`AdminAuthorizationFilter`'s generic `/api/v1/admin/**` path check, the same
way `AdminAuditLogController` needed none in ADR-013.

### URN-based policy matching — zero changes to `PolicyMatcher`

`PolicyMatcher` already does generic `AntPathMatcher`-based string-list
matching over whatever `sources` list is passed in — that's exactly what
makes URN support possible without touching it at all. The actual work is
in the **callers**: `com.zte.gateway.identity.IdentitySources.enrich(roles,
jwtAuth)` builds an enriched sources list — bare role names (unchanged,
backward-compatible with every existing bare-role YAML rule) plus
`role:<r>` for each realm role, `user:<preferred_username>`, and `group:<g>`
for each Keycloak group claim — and both `ZteAuthorizationFilter` and
`AdminAuthorizationFilter` pass *that* to `policyMatcher.evaluate(...)`
instead of the bare roles list. The bare `roles` list itself is kept
unchanged for `ZteAuthorizationFilter`'s
`roles.isEmpty() && isServicePrincipal(...)` service2service dispatch check
and both filters' audit logging — only the match call gets the enriched
list. Scoped to `users2service` only — `ServiceToServiceAuthorizationFilter`/
`YamlMcpPolicyEngine` use client-credential identity (`azp`), a different
model this feature doesn't cover.

`com.zte.gateway.identity.IdentityUrn.parse(String source): Optional<IdentityUrn>`
handles the URN grammar: no prefix implies `ROLE` (backward compat); an
unrecognized prefix (e.g. a typo'd `rle:ADMIN`) is treated as a literal role
name rather than silently ignored — a rule author who mistypes a prefix gets
a rule that will legitimately show up as orphaned, not one that silently
never matches anything; any wildcard character (`*`/`?`) anywhere in the
source yields `Optional.empty()`, since a wildcard pattern isn't checkable
against a fixed identity list.

### Orphaned-rule checking — decoupled, observational, never blocking

Deliberately **not** wired into `PolicyValidator`/`PolicyMatcher` — ADR-009
§8.2 established that `PolicyMatcher.evaluate()` must stay synchronous and
zero-I/O (it's called inline from `McpPolicyEngine.evaluate()` too), and
this task's own instruction says orphan checking must never block or fail
loading. Introducing a DB-backed check there would compromise that
invariant for a check that doesn't need it.

Instead: `PolicyDefinitionStore` gains one new constructor parameter
(`ApplicationEventPublisher`) and publishes a new
`PolicyDocumentReloadedEvent` from `doReload()`'s success branch only — the
constructor's initial load is untouched, avoiding any publish-event-during-
construction lifecycle risk. `com.zte.gateway.identity.OrphanedRuleChecker`
listens via `@EventListener(PolicyDocumentReloadedEvent.class)` for reloads
and runs its own `@PostConstruct` check at startup (reading
`policyDefinitionStore.current()` directly, not waiting on the event) — for
each `users2service` rule, `IdentityUrn.parse(rule.source())` then
`repository.existsByTypeAndName(...)`, and an SLF4J `WARN`-level
`"ORPHANED RULE: ..."` line when no match is found. This is the closest
Java/SLF4J equivalent to the task's "severe warning" framing — SLF4J has no
distinct "severe" level. Never rejects, deletes, or blocks anything —
purely observational.

`OrphanedRuleChecker`'s `@PostConstruct` check and `IdentitySyncService`'s
own first `@Scheduled` run both fire near application startup, independently
of each other, with no guaranteed ordering. **Named race condition, not
silently accepted**: on a cold start, the orphan check can legitimately run
before the first sync has populated `idp_identities`, producing a transient
false-positive "orphaned" warning for every `users2service` rule that self-
corrects once sync completes — within the sync interval, or immediately
after a manual sync or a policy reload (which re-triggers the check).
Accepted for MVP rather than adding real complexity to sequence two
independent async initializers correctly.

### Finding: Flyway (JDBC) has no ordering relationship with R2DBC beans

Found live, restarting the gateway against a real Postgres already on Flyway
version 4: `OrphanedRuleChecker`'s `@PostConstruct` fired and queried
`idp_identities` via R2DBC *before* Flyway's `V5` migration (JDBC-based) had
run — `relation "idp_identities" does not exist`. Spring Boot's Flyway
autoconfiguration has no automatic ordering guarantee relative to beans
built on a completely separate `ConnectionFactory` (R2DBC); every prior
R2DBC consumer in this codebase (`RequestLogRepository`, etc.) is only
invoked reactively per-request, well after startup, so this gap was never
exercised until `OrphanedRuleChecker` became the first eager,
`@PostConstruct`-time R2DBC caller. Worse: because `checkRule`'s two
concurrent per-rule queries both failed, `flatMap`'s single-error
propagation surfaced the first as a normal stream error and silently
dropped the second as `onErrorDropped` — a noisy, confusing failure mode
for something that's supposed to be purely observational. Fixed by wrapping
each per-rule query in its own `onErrorResume` (degrade to an SLF4J warning
line, `Mono.empty()`) rather than letting a single query failure — schema
not yet migrated, a transient connection error, anything — take down the
whole check or produce a dropped-error stack trace. This is the same class
of "the mocked unit tests wouldn't have caught this" lesson ADR-012/ADR-013
already established for the `GlobalFilter`/`switchIfEmpty` bugs — found only
by running the real thing against a real, already-provisioned database.

### UI — client-side orphan cross-referencing, not a new backend field

`zt-admin-ui`'s new "Identities" tab (`Identities.tsx`) fetches
`GET /api/v1/admin/identities/search` (no filters = everything), renders a
plain MUI `Table` (same "no `@mui/x-data-grid`, unproven on this MUI
v9/React 19 combo" reasoning ADR-013 already established) with a "Sync Now"
button mirroring `PolicyDashboard`'s "Reload Policies" button +
`Snackbar` pattern.

`PolicyDashboard.tsx` independently fetches the same
`/api/v1/admin/identities/search` endpoint alongside its existing policies
fetch, builds a `Set<"TYPE:name">`, and applies a small (~10-line),
intentionally duplicated TypeScript port of `IdentityUrn.parse`'s logic to
flag `users2service` rows whose source isn't in the cache (yellow-tinted row
+ tooltip warning icon). Deliberately not a new field on the core
`PolicyRule`/`PolicyDocument` shape (shared by service2service/
agentMcpToolCalls too, where "orphaned" is meaningless) or a dedicated
"orphaned rule IDs" backend endpoint — keeps "Policies" and "Identities" as
independent, self-contained tab components, matching `App.tsx`'s existing
pattern, rather than lifting shared state up.

---

## Alternatives Considered

### On-demand IdP lookup per policy decision (rejected)

Calling Keycloak's Admin REST API synchronously inside
`ZteAuthorizationFilter`/`PolicyMatcher.evaluate()` would keep identity data
perfectly fresh with no sync lag.

- **Pros:** No staleness window; no new schema, sync engine, or scheduled
  job.
- **Cons:** Turns every authenticated request into a network round trip to
  Keycloak's Admin API, on the hot request-serving path — a latency and
  availability regression, and a direct violation of ADR-009 §8.2's
  zero-I/O `PolicyMatcher.evaluate()` contract (still called synchronously
  from `McpPolicyEngine`). Keycloak's Admin API also isn't designed for
  per-request query volume.
- **Verdict:** Rejected — the task's own framing ("cache IdP metadata in
  PostgreSQL") already rules this out, and the technical reasoning
  independently confirms it.

### Rejecting or auto-disabling orphaned rules (rejected)

A rule referencing a nonexistent identity could be treated as invalid at
load/reload time, the same way `PolicyValidator` already rejects structurally
invalid rules.

- **Pros:** Prevents a class of "silently dead rule" operator mistake from
  persisting unnoticed.
- **Cons:** The task explicitly says "DO NOT reject/delete." A rule can also
  legitimately reference an identity that exists in Keycloak but hasn't
  synced yet (the startup race named above) — rejecting on that basis would
  be a false positive with real availability impact (the whole policy
  document fails to load over a timing accident).
- **Verdict:** Rejected per explicit instruction; purely observational
  logging chosen instead.

---

## Self-Critique

| Risk | Severity | Mitigation |
|---|---|---|
| Cold-start race between `OrphanedRuleChecker`'s `@PostConstruct` check and `IdentitySyncService`'s first `@Scheduled` run can produce transient false-positive "orphaned" warnings | Low | Named explicitly above, not silently accepted. Self-corrects within one sync interval (15 min default) or immediately after a manual sync/reload; purely observational (SLF4J only), so it never affects actual request handling. |
| Identity data can be up to `zte.idp.sync-interval-ms` (15 min default) stale — a Keycloak user/role/group created or renamed after the last sync isn't policy-addressable by URN until the next sync | Medium | Deliberate tradeoff for keeping the request-serving path zero-I/O (see "Alternatives Considered"). `POST /api/v1/admin/identities/sync` gives an operator an immediate manual override when needed. |
| `KeycloakIdpAdapter` fetches a fresh client-credentials token on every one of the 3 `fetchX()` calls per sync cycle, rather than reusing one token across the cycle | Low | Deliberate, not an oversight — 3 extra token requests every 15 minutes is negligible load, and avoiding token-caching state matches this codebase's established "don't add machinery MVP scale doesn't need" bias (the same reasoning ADR-012 used to justify deleting `PolicyService`'s cache). |
| `IdentitySources.enrich(...)`'s `user:<name>` URN always uses `preferred_username`, with no support for matching by Keycloak's internal user UUID | Low | Matches the task's own literal URN examples (`user:<name>`); a UUID-based URN form isn't requested and would need a distinct prefix or disambiguation rule if ever added. |
| No test exercises `group:` rule matching end-to-end (no groups exist in `zte-realm` yet) | Low | The `groups-mapper` protocol mapper and `IdentitySources`'s group-claim handling are unit-tested in isolation (`IdentitySourcesTest`), but there's no integration-level proof a real Keycloak group claim flows through correctly — named as a gap for the Future Migration Path rather than fabricating a demo group this task didn't ask for. |

---

## Consequences

- **Positive:** `users2service` policy rules can now target a human user or
  a Keycloak group directly, not just a realm role — while every existing
  bare-role-name rule keeps working unmodified (`IdentitySources.enrich`
  is additive, not a replacement for the bare roles list).
- **Positive:** `PolicyMatcher` required zero code changes — the URN
  feature is entirely additive at the call sites, reinforcing that its
  original generic string-list design (ADR-011) was the right shape.
- **Positive:** A misconfigured or stale policy rule (referencing a deleted
  or renamed identity) is now visible via a log warning and a UI highlight,
  where previously it would silently and permanently never match anything.
- **Positive:** The `IdpClient` interface means a future Azure Entra ID or
  AWS IAM adapter is a drop-in implementation with no changes anywhere else
  in the sync/cache/matching pipeline.
- **Negative:** A new scheduled background job (`IdentitySyncService`) and
  a new external dependency on Keycloak's Admin REST API (beyond the
  existing JWT/JWKS dependency) — one more thing that can fail
  independently of request-serving; failures are caught and logged, never
  propagated to request handling.
- **Negative:** Identity data can be stale for up to the sync interval — a
  real, named tradeoff (see Self-Critique), not silently accepted.

---

## Future Migration Path

- **UUID-based user URNs**, if a need to disambiguate users by Keycloak
  internal id (not just username) ever emerges.
- **Filesystem-watch or webhook-driven sync**, replacing the fixed 15-min
  polling interval, to shrink the staleness window without manual
  `POST .../sync` calls — same future direction already named for
  `PolicyDefinitionStore`'s own reload mechanism (ADR-011/012's backlog).
- **A demo Keycloak group in `zte-realm`**, once a real use case for
  `group:`-scoped rules exists, to close the integration-level test gap
  named in Self-Critique.
- **A second `IdpClient` implementation** (Azure Entra ID or AWS IAM), the
  concrete reason the adapter interface exists — no changes needed to
  `IdentitySyncService`, `IdpIdentityRepository`, `IdentityUrn`,
  `IdentitySources`, or `OrphanedRuleChecker` when this happens.
- **Sequencing startup sync before the first orphan check**, if the
  transient cold-start false-positive (Self-Critique) ever proves
  disruptive enough to justify the added complexity of coordinating two
  independent async initializers.
