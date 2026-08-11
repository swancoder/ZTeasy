# ADR: Identities UI Refactor (Actors vs. Access Containers) and Relational Caching

**Status:** Accepted
**Date:** 2026-08-11
**Deciders:** ZTE-Lightweight Architects

**Filename note:** deliberately not numbered `ADR-016-...` per this task's
explicit instruction ("do not hardcode a specific ADR number in the
filename; use a descriptive slug") — a one-off deviation from CLAUDE.md's
otherwise-consistent `ADR-XXX-name.md` convention (ADR-001 through
ADR-015), made because the instruction was explicit and unambiguous rather
than silently ignored. This is Stage 15 in `CLAUDE.md`'s stage numbering
and the ADR Index tables regardless — the slug filename doesn't change
where it sits in the project's chronology, only what the file is called.

---

## Context

The Identities tab (ADR-014, extended by ADR-015) was a single flat table
of every synced identity — Users, Groups, Roles, and (since ADR-015)
Clients — with no way to see *why* a given rule matched an identity (which
groups does this user belong to? which roles does this client have?)
without leaving the Admin Console and querying Keycloak or Postgres
directly. As the identity count grows (even today, after ADR-016's
system-client filtering, 4+ business clients plus every real user/group/
role), a flat list stops being a usable inventory. This reorganizes the UI
around the two semantically distinct roles every identity plays — an
"Actor" that *initiates* access (User, Client) or an "Access Container"
that *grants* it (Group, Role) — and adds a local relational cache
(`idp_identity_relations`) so an Actor's roles/groups are visible with zero
additional Keycloak calls.

---

## Decision

### System clients are filtered at the source, not hidden in the UI

`KeycloakIdpAdapter.fetchClients()` now excludes Keycloak's realm-builtin
clients — `account`, `broker`, `realm-management`, `admin-cli`,
`security-admin-console`, and any client whose id starts with `account-`
or `broker-` (their satellite clients, e.g. `account-console`) — via a
`.filter(...)` on the reactive stream, before mapping to `IdpIdentity` at
all. These clients are never synced, never cached, never returned by
`GET /api/v1/admin/identities/search`. This differs from ADR-015's original
"fetch all clients" MVP decision — that ADR's own Self-Critique already
named this as a legitimate future tightening once the noise became a real
usability problem; this task is that tightening, now that the Identities UI
is being redesigned around a semantic split anyway. `isSystemClient(...)`
is `static` and package-visible specifically so it has a direct unit test
(`KeycloakIdpAdapterTest`) — the rest of this adapter has never had one,
proven only by `IdentitySyncIT` against a real Keycloak (ADR-014's
established precedent, unchanged here).

### Relations are a real many-to-many cache, synced once per cycle, never queried live

`idp_identity_relations` (Flyway `V7`) stores `(subject_id, target_id,
relation_type)` triples where both ids are `idp_identities.id` **internal**
PKs (not Keycloak external ids) — `UNIQUE (subject_id, target_id,
relation_type)`, `ON DELETE CASCADE` so a hypothetically-deleted identity
row (never happens today — identities are upsert-only) can't leave an
orphaned relation FK. `relation_type` is `VARCHAR(20)`+`CHECK`, the same
"not a native Postgres enum" choice `idp_identities.type` already made
(ADR-014) — consistency, and no new R2DBC enum codec registrar needed.

`IdpClient.fetchRelations(): Flux<IdpRelation>` is a new interface method
(`IdpRelation` — subject/target keyed by their *Keycloak external ids*, not
yet resolved to internal PKs). `KeycloakIdpAdapter`'s implementation:
group memberships and realm-role assignments for every user
(`GET /users/{id}/groups`, `GET /users/{id}/role-mappings/realm`); for
every non-system client, its service-account user's realm-role assignments
(`GET /clients/{id}/service-account-user` → `GET
/users/{serviceAccountUserId}/role-mappings/realm`) — a client's roles live
on a *separate* Keycloak entity (its service account user), which
`idp_identities` deliberately never caches as its own `USER` row (Keycloak's
own `/users` endpoint already excludes service-account users, confirmed
live in the ADR-014 session) — so `fetchRelations()` resolves the extra hop
internally and reports the relation against the *client's* external id, not
the service-account user's. A client without `serviceAccountsEnabled` 404s
on that lookup — caught with `onErrorResume(ex -> Flux.empty())` per
client, not failing the whole sync (the same per-item resilience posture
`OrphanedRuleChecker`'s per-rule `onErrorResume` established, ADR-014's
live-tested fix).

`IdentitySyncService.syncNow()` now does two passes in the same cycle:
`syncIdentities()` upserts every identity **and** collects a `Map<String
externalId, UUID internalId>` from `IdpIdentityRepository.upsert`'s new
`RETURNING id` clause (a schema-compatible change to that existing
`@Query` — no new column, just capturing what Postgres already computes);
`syncRelations(map)` then resolves each `IdpRelation`'s subject/target
external ids against that map and upserts into
`idp_identity_relations` — **zero** extra DB round trips to resolve ids,
because every relation names an entity this same cycle's identity fetch
already named. A relation that fails to resolve (shouldn't happen, by the
above invariant) is logged and skipped, not treated as a sync failure.

### The read endpoint is local-cache-only, by construction

`GET /api/v1/admin/identities/{id}/relations`
(`AdminIdentityRelationsController`) queries
`IdpIdentityRelationRepository.findBySubjectId(id)` then resolves each
relation's `target_id` via `IdpIdentityRepository.findById(...)` — both
Postgres reads, R2DBC, no `WebClient`/Keycloak dependency anywhere in this
class. This isn't a performance optimization dressed up as a security
property — it's the same Zero Trust reliability constraint the task's own
Self-Criticism named explicitly: an admin-facing detail view must not
introduce a live, synchronous dependency on Keycloak's availability into a
request path that previously had none (every other `/api/v1/admin/**` read
endpoint — policies, audit logs, identity search — already reads a local
cache only).

### UI: Actors vs. Access Containers, MUI Accordions, client-side quick search

`Identities.tsx` splits into two `Stack`s — "Actors" (`USER`, `CLIENT`
accordions) and "Access Containers" (`GROUP`, `ROLE` accordions) — each
type rendered as an `Accordion` (`defaultExpanded={identities.length > 0}`,
per the task's literal ask), containing the same per-row `Table` the flat
list used before. A single `TextField` "Quick search" filters the full
identity list by `name` (case-insensitive substring) client-side, before
the per-type grouping — so a search narrows every accordion at once, not
one at a time. `USER`/`CLIENT` rows get an "info" `IconButton` (a plain
emoji glyph, not `@mui/icons-material` — this codebase has consistently
avoided that dependency, e.g. `PolicyDashboard`'s orphan-warning icon is
also a bare emoji) that opens an MUI `Drawer` fetching `GET
/api/v1/admin/identities/{id}/relations` and rendering two `List`s (Roles,
Groups). `GROUP`/`ROLE` accordions get no info button — they're targets of
relations, not subjects; nothing to show relations *about* them yet (see
Future Migration Path).

---

## Alternatives Considered

### Denormalized relation columns directly on `idp_identities` (e.g. a `roles` JSON array) instead of a join table (rejected)

- **Pros:** One query for "give me this identity plus its relations,"
  no join.
- **Cons:** A user can belong to an unbounded number of groups/roles — a
  JSON/array column doesn't get the `UNIQUE`/foreign-key integrity a
  proper join table gets for free, and querying "which users have role X"
  (a real, if not-yet-built, future need) would mean scanning/unpacking
  every row instead of an indexed join. The task's own instruction
  literally specifies a relations table with `subject_id`/`target_id`/
  `relation_type` columns.
- **Verdict:** Rejected — a real relational table is both what was asked
  for and the technically correct shape for genuine many-to-many data.

### Fetching relations on-demand per "info" click, live from Keycloak (rejected)

- **Pros:** Always perfectly fresh, no sync-lag window, no new schema.
- **Cons:** Directly contradicts the task's own Self-Criticism instruction
  ("ensure the backend reads from the local PostgreSQL cache, not from
  Keycloak, to maintain Zero Trust reliability constraints") — every click
  would add a live external dependency to a previously self-contained admin
  read path, the exact pattern ADR-014 avoided for the identity cache
  itself.
- **Verdict:** Rejected per explicit instruction; the local cache is
  populated once per sync cycle instead (same staleness tradeoff
  `idp_identities` itself already accepted in ADR-014, not a new one).

---

## Self-Criticism

| Risk | Severity | Mitigation |
|---|---|---|
| Relation data can be as stale as identity data — up to `zte.idp.sync-interval-ms` (15 min default) — a role granted to a user in Keycloak after the last sync doesn't appear in the "info" drawer until the next sync | Medium | Same accepted tradeoff `idp_identities` itself already has (ADR-014); `POST /api/v1/admin/identities/sync` gives an immediate manual override, same as for identities themselves. |
| `fetchRelations()` makes 1 (groups) + 1 (roles) HTTP calls per **user**, plus 1 (service-account lookup) + 1 (roles) per **non-system client** — a real multiplier on sync duration/Keycloak load as the realm grows, beyond the flat per-kind calls `fetchUsers`/`fetchGroups`/`fetchRoles`/`fetchClients` made before | Medium | Accepted for this realm's current scale (a handful of users/clients); Keycloak's Admin API has no batch "give me every user's role mappings in one call," so avoiding the N+1 shape entirely isn't possible without a fundamentally different (and heavier) sync strategy. Named as a real backlog item, not silently absorbed. |
| `KeycloakIdpAdapter.fetchRelations()`'s HTTP calls have no dedicated mocked-`WebClient` unit test, same as every other `fetchX()` method on this adapter | Low | Consistent, not a new gap — this adapter's correctness has always been proven by `IdentitySyncIT` against a real Testcontainers Keycloak (ADR-014 precedent), not mocked HTTP; `manualSync_thenRelationsEndpoint_reflectsRoleAssignment` extends that IT for this feature specifically. |
| `GROUP`/`ROLE` rows have no "info" affordance — no way to see "which users/clients have this role" from the UI, only the reverse direction (Actor → its roles/groups) | Low | Deliberate MVP scope: the task's own Task 4 only asks for an info icon on "Users and Clients" rows. The reverse query (`findByTargetId`) is a natural, low-effort extension once/if that direction is needed — see Future Migration Path. |
| The ADR filename deviates from this repo's otherwise-universal `ADR-XXX-name.md` numbering convention | Low | Explicit, literal task instruction, not an oversight — documented at the top of this file and in `prompts-hist`. |
| `fetchClients()`'s new system-client filter only stops *future* syncs from writing those rows — sync is upsert-only (`IdpIdentityRepository.upsert`'s `ON CONFLICT ... DO UPDATE`) and never deletes, so a Postgres instance that already cached system clients under the pre-Stage-15 `fetchClients()` keeps them indefinitely, with a `last_synced` timestamp that stops advancing but a row that never disappears | Medium | Found live, restarting the gateway against this session's own dev database: `account`/`broker`/etc. were still returned by `GET .../identities/search?type=CLIENT` after a fresh sync, confirmed via `last_synced` to be stale pre-fix rows, not a broken filter. Cleaned up once via a manual `DELETE FROM idp_identities WHERE type='CLIENT' AND name IN (...)` for this dev environment — the same one-off remediation pattern the ADR-013 session used for stale `request_logs` rows after a similar filter-only-affects-future-writes fix. Not solved in code (a delete-on-sync/reconciliation pass is a real design change, out of scope for this task); named here rather than silently left as a surprise for the next person who wonders why filtered clients are still visible.

---

## Consequences

- **Positive:** The Identities tab now groups ~10+ identities into a
  navigable, semantically split structure instead of one flat table —
  scales better as the realm's real users/groups/roles/clients grow.
- **Positive:** An operator can answer "what can this user/client actually
  do" (its roles, its groups) without leaving the Admin Console or running
  a manual `kcadm.sh`/`psql` query — closing a real operational gap this
  session's own investigation (the "why don't I see agent identities"
  conversation, and this task's own motivation) surfaced.
- **Positive:** `idp_identities`' Keycloak-builtin-client noise (named as
  an accepted MVP simplification in ADR-015) is now actually filtered, not
  just documented as a known limitation.
- **Positive:** The relations read path is Keycloak-independent by
  construction — an admin viewing identity relations never adds load to,
  or takes a live dependency on, the IdP.
- **Negative:** `fetchRelations()`'s N+1-shaped HTTP call pattern is a real
  new source of sync-duration growth as the realm scales (see
  Self-Critique) — not solved here, named as a backlog item.
- **Negative:** `IdpIdentityRepository.upsert`'s return type changed from
  `Mono<Void>` to `Mono<UUID>` (via `RETURNING id`) — a source-compatible
  change (every existing caller already discarded the `Mono<Void>`'s value,
  they now just also discard `Mono<UUID>`'s, except `IdentitySyncService`
  which is the one caller that needed the new behavior) but still a
  interface-shape change to an existing repository method, not a purely
  additive one.

---

## Future Migration Path

- **A reconciliation/delete pass in `IdentitySyncService`** — today's sync is
  strictly upsert-only for both `idp_identities` and `idp_identity_relations`;
  an identity or relation that stops being returned by the IdP (deleted
  user, revoked role, or — as found live in this session — newly filtered
  system client) is never removed from the cache automatically. A real fix
  would diff each sync's fetched set against the previous cache and delete
  what's no longer present; not attempted here (a bigger design change than
  this task's scope), worked around with a one-off manual cleanup for this
  dev environment instead (see Self-Critique).
- **Reduce `fetchRelations()`'s per-user/per-client HTTP call count**, if
  sync duration ever becomes a real problem at a larger realm scale (see
  Self-Critique) — no known Keycloak Admin API batch endpoint for this
  today, so this would likely mean a different sync strategy (e.g.
  Keycloak's admin event stream) rather than a small tweak.
- **`findByTargetId` + a reverse "info" affordance on Group/Role rows** —
  "which Actors have this Group/Role" — the natural complement to today's
  Actor→Container direction, not built because the task's own scope didn't
  ask for it yet.
- **A `condition`/attribute-level relation** (e.g. group membership with an
  expiry, or a role assignment scoped to a specific client) if Keycloak's
  own relation model ever needs richer metadata than a bare
  `(subject, target, type)` triple — today's schema deliberately doesn't
  anticipate this.
