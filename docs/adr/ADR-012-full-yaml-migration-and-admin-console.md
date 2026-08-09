# ADR-012: Full YAML Policy Migration and React Admin Console

**Status:** Accepted
**Date:** 2026-08-09
**Deciders:** ZTE-Lightweight Architects

---

## Context

Two requests arrived together, and the second depends on the first:

1. Close ADR-011's own "Future Migration Path" backlog item: retire the
   DB-backed `access_policies`/`PolicyService` fallback for `users2service`,
   making the YAML `PolicyDefinitionStore` the sole source of truth — matching
   what `service2service` and `agentMcpToolCalls` already do.
2. Give operators a UI to see and reload the now-authoritative YAML policy
   set, instead of hand-editing the file and curling
   `/api/v1/internal/policies/reload` blind.

The spec for (2) asked for a React/Vite/TypeScript SPA, served statically by
the gateway at `/admin/`, authenticated against Keycloak, calling a new
ADMIN-only REST API.

---

## Decision

### Part 1 — users2service becomes YAML-only

- Deleted `PolicyService`, `AccessPolicy`, `AccessPolicyRepository` outright
  — not "refactored to read YAML." `ZteAuthorizationFilter` already calls
  `PolicyMatcher`/`PolicyDefinitionStore` directly for YAML evaluation; a
  YAML-backed `PolicyService` would just be the same logic under a second
  name. ADR-011's Future Migration Path says outright: *"retiring
  `access_policies` and `PolicyService`."* Deleting is what actually closes
  that item.
- `ZteAuthorizationFilter`'s `NO_MATCH` branch (previously: fall back to
  `PolicyService.isAllowed(...)`) now denies directly, with the same
  `writeForbidden`/audit-log shape the `DENIED` branch already used.
- New Flyway migration `V3__drop_access_policies.sql` — `DROP TABLE IF
  EXISTS access_policies`. V1's `gateway_audit_log` table is untouched.
- `zte-policies.yaml`'s `users2service` list is populated with the exact
  rule the old V2 seed encoded (`ADMIN` → `/api/v1/service-a/**`,
  `GET,POST`) — a faithful migration, not a behavior change — plus one new
  rule for the admin API (Part 2).
- `InternalPolicyController` (`GET /api/v1/internal/policies`) now returns
  `PolicyDefinitionStore.current().users2service()` instead of querying the
  now-deleted repository. This wasn't in the original task list, but
  `zt-agents`' `GatewayClient.fetchPolicies()` calls this endpoint and
  deserializes into a DTO shaped exactly like the old DB row — left alone,
  it would 404/silently break the Policy Auditor agent. `zt-agents`'
  `PolicyDto` was updated to the `PolicyRule` shape; `PolicyAuditorService`
  itself needed no change (it only calls `.toAuditLine()`/`.isEmpty()`/`.size`).
- R2DBC (`spring-r2dbc`, `r2dbc-postgresql`) removed from
  `gateway-service/build.gradle.kts` and the version catalog — it was used
  exclusively by the now-deleted `AccessPolicyRepository`. JDBC/Flyway are
  untouched (Flyway still needs a JDBC `DataSource`).

### Part 2 — Admin Console

- **`AdminPolicyController`** (`gateway-service/.../admin`) — `GET
  /api/v1/admin/policies` returns the full `PolicyDocument` (all three
  categories — this is the operator's dashboard view, broader than the
  `zt-agents`-facing internal endpoint which stays users2service-only).
  `POST /api/v1/admin/policies/reload` does the same reload
  `PolicyReloadController` does; both now call a single shared
  `PolicyReloadResult.toResponseEntity()` so the two render identically.
  Deliberately coexists with the internal endpoints rather than replacing
  them — `/api/v1/internal/**` stays unauthenticated/network-perimeter for
  `zt-agents` and ops scripts; `/api/v1/admin/**` is ADMIN-JWT-gated for the
  human operator via the SPA. Different trust models, same underlying data.
- **`AdminAuthorizationFilter`** (see Self-Critique — this is the one
  genuinely non-obvious piece of this ADR) enforces a YAML `users2service`
  rule (`u2s-admin-console-api`: `ADMIN` → target `admin`, path
  `/api/v1/admin/**`) against the new API.
- **`AdminUiConfig`** — a `@Order(-90)` `SecurityWebFilterChain` permitAll
  for `/admin/**` only (not `/api/v1/admin/**` — different prefix, stays
  behind the default JWT-required chain), plus a `WebFluxConfigurer`
  resource-handler mapping `/admin/**` → `classpath:/static/admin/`. The
  SPA's OIDC `redirect_uri` points at the exact file `/admin/index.html`,
  not a bare `/admin/` directory path — Spring Boot's automatic
  index.html-at-welcome-page resolution only applies at the context root,
  not nested static paths, and building a custom fallback resolver for the
  bare-directory case turned out both non-trivial (see Self-Critique) and
  unnecessary once the literal target URL was re-read.
- **`zte-admin-ui/`** — Vite + React + TypeScript, MUI, `react-oidc-context`.
  `base: '/admin/'`; build output is the Vite default `dist/` (already
  covered by the scaffold's own `.gitignore` — no need to relocate it under
  `build/` to piggyback on the root `.gitignore`'s pattern, which was the
  original plan before actually looking at what `npm create vite` produces).
  A plain directory, **not** a Gradle subproject — see Self-Critique.
- **Gradle**: `com.github.node-gradle.node` applied directly in
  `gateway-service/build.gradle.kts`, `nodeProjectDir` pointed at
  `../zt-admin-ui`. A `buildAdminUi` `NpmTask` (`npm run build`, with
  `inputs`/`outputs` declared for up-to-date checking) feeds
  `processResources`, which copies `zt-admin-ui/dist` into
  `static/admin` — packaged into the jar, served at
  `http://localhost:8080/admin/index.html`. `./gradlew build -x
  :gateway-service:buildAdminUi` skips it, mirroring the existing
  `-x :zt-agents:compileKotlin` escape hatch for the no-API-key case.
- **Keycloak**: new public client `zte-admin-ui` (`realm-export.json`) —
  authorization code + PKCE (`pkce.code.challenge.method: S256`), no
  service account, redirect URIs `http://localhost:8080/admin/*`, web
  origins `+`. Same `realm-roles-mapper`/`username-mapper` protocol mappers
  as `zte-gateway`, so `realm_access.roles` lands in the JWT the same way.

---

## Alternatives Considered

### users2service: keep `access_policies` orphaned instead of dropping it (rejected)
The prior "Option A" pattern from ADR-011's own self-critique table (safer
rollback story, avoids a migration).

- **Pros:** Lower-risk if a rollback is ever needed.
- **Cons:** The task explicitly asked for a `V3__drop_access_policies.sql`
  migration; an orphaned unused table is exactly the kind of stale artifact
  this migration exists to remove.
- **Verdict:** Rejected — dropped as asked. A rollback path still exists via
  Flyway's migration history / a manual re-create if ever genuinely needed.

### Admin API auth: broad `/assets/**` permitAll instead of scoped `/admin/**` (rejected)
Simpler-looking single wildcard for "anything the SPA needs."

- **Pros:** One fewer thing to get right if more static content is added later.
- **Cons:** Vite's `base: '/admin/'` already nests every emitted asset under
  `/admin/`, so `/admin/**` alone is sufficient — a separate `/assets/**`
  would unauthenticated-expose any *other* future top-level static content
  that happens to live under that path, for no benefit here.
- **Verdict:** Rejected. Scoped matcher only.

### Admin API auth: `@PreAuthorize("hasRole('ADMIN')")` instead of a policy-engine check (rejected)
Spring Security's native RBAC annotation — the obvious first instinct for
"gate this endpoint by role."

- **Pros:** Idiomatic Spring Security, one line, no custom filter.
- **Cons:** Every other role check in this codebase is policy-engine-driven
  (YAML or, formerly, DB) and audit-logged through `ZteAuditLogger.policyDecision`
  — an annotation-based check would be a second, parallel, un-audited
  authorization mechanism, inconsistent with the codebase's own established
  convention (confirmed: no `@PreAuthorize` exists anywhere else in this
  repo).
- **Verdict:** Rejected. `AdminAuthorizationFilter` reuses the same
  `PolicyMatcher`/`PolicyDefinitionStore`/audit-log path as everything else.

---

## Chain of Thought (CoT)

1. **Deleting `PolicyService` beats refactoring it.** The task's literal
   wording said "refactor `PolicyService` to strictly use the YAML loader,"
   which reads as "keep the class." But `ZteAuthorizationFilter` already
   evaluates YAML directly via `PolicyMatcher` — a YAML-backed `PolicyService`
   would be a second implementation of the same evaluation, called nowhere.
   ADR-011's own text ("retiring `access_policies` and `PolicyService`")
   settles this in favor of deletion.
2. **`InternalPolicyController` had a hidden dependent.** Nothing in the
   task list mentioned `zt-agents`, but tracing `GET
   /api/v1/internal/policies`'s only caller (`GatewayClient.fetchPolicies()`)
   before deleting anything revealed a DTO shaped exactly like the row being
   removed. Fixed as part of the same change rather than discovered later as
   a break.
3. **`ZteAuthorizationFilter` doesn't run for local controllers — found by
   testing, not by reading.** The plan (written before any code ran) assumed
   `ZteAuthorizationFilter`'s existing Javadoc claim — "runs as a global
   filter on every request... no route entry needed for a local
   `@RestController`" — was accurate, since it matched how the pre-existing
   `/api/v1/internal/**` endpoints were described. It's wrong for anything
   that actually *needs* the filter to fire: `GlobalFilter` (Spring Cloud
   Gateway's type, not plain WebFlux's `WebFilter`) is only invoked by
   `FilteringWebHandler`, which only runs for requests
   `RoutePredicateHandlerMapping` matches to a configured route. The
   internal endpoints never needed the filter to fire (they're permitAll,
   unconditionally), so the claim was never actually exercised. It surfaced
   the moment an endpoint needed real enforcement: a manual curl with a
   USER-role JWT against the new admin API returned `200` — the filter
   simply never ran, silently. Caught during this session's own manual
   verification, before it ever reached a commit. See Self-Critique for the
   full mechanism and the fix.
4. **The fix reuses evaluation logic, not the filter type.** Rather than
   changing `ZteAuthorizationFilter`'s type from `GlobalFilter` to `WebFilter`
   (which would also require re-deriving its `@Order` value relative to
   Spring Security's chain — a change with blast radius across every
   existing routed path and its 50+ tests, for a bug that only actually
   affects the one new endpoint), `AdminAuthorizationFilter` is a small,
   separate, `/api/v1/admin/**`-scoped `WebFilter` that calls the same
   `PolicyMatcher`/`PolicyDefinitionStore`. Minimal blast radius, same
   evaluation semantics.
5. **The redirect URI is `/admin/index.html`, matching the task's own
   literal spec** ("served ... at `http://localhost:8080/admin/index.html`"),
   not a bare `/admin/`. An initial attempt built a custom
   `PathResourceResolver` to make the bare directory path resolve to
   `index.html` — it didn't work (still 404'd; Spring's `ResourceWebHandler`
   doesn't invoke resolvers the same way for an empty relative path as it
   does for a real filename) and, once re-reading the task, wasn't even the
   asked-for URL. Removed in favor of pointing the SPA's `redirect_uri`
   at the literal file, which already worked with zero custom resolver code.

---

## Self-Critique

| Risk | Severity | Mitigation |
|---|---|---|
| `GlobalFilter` vs `WebFilter`: any *future* gateway-local `@RestController` will have the same silent-bypass failure mode unless its author knows this distinction | **High** | Documented prominently in `AdminAuthorizationFilter`'s Javadoc (which explains *why* `ZteAuthorizationFilter` doesn't apply) and here. No generic guard exists to catch a *future* instance of this mistake automatically — a real gap, not fully closed by this ADR. |
| `switchIfEmpty` on a `Mono<Void>`-typed chain double-invokes the fallback — a *successful* `Mono<Void>` completion is indistinguishable from an empty one, since neither emits a value | Medium | Found via `AdminAuthorizationFilterTest` (new) before ever running against real Keycloak — the DENY-path tests failed with `UnsupportedOperationException` from a second write to an already-committed mock response. Fixed by mirroring `ZteAuthorizationFilter`'s own proven pattern (`defaultIfEmpty(new SecurityContextImpl())` + a plain `instanceof` check inside `flatMap`, no `switchIfEmpty` at all) instead of `.cast(...).switchIfEmpty(...)`. This is the same *class* of Reactor pitfall Stage 5 (CLAUDE.md) already fixed once in `ZteAuthorizationFilter` itself — worth naming explicitly so it's recognizable next time, since apparently one fix didn't fully generalize the lesson. |
| Two REST controllers now expose the same `PolicyDocument`/reload capability under different auth models (`/api/v1/internal/**` vs `/api/v1/admin/**`) | Low | Deliberate, not accidental — documented in Decision above. Both share `PolicyReloadResult.toResponseEntity()` so they can't silently drift in response shape. |
| `AdminAuthorizationFilter` duplicates `ZteAuthorizationFilter`'s YAML-evaluation shape (extract roles → resolve target → match → respond) rather than sharing a single evaluation method | Low | The role-extraction half was actually shared (new `RealmRoles.extract(...)` utility, used by both). The response-writing/logging half stayed separate because the two filters have different types (`GatewayFilterChain` vs `WebFilterChain`) with no common supertype to unify against without new abstraction — judged not worth it for ~15 lines. |
| Vite/Node is now a hard build-time dependency for anyone building the full project | Low | `./gradlew build -x :gateway-service:buildAdminUi` documented as the skip path (mirrors the existing `zt-agents` API-key escape hatch); README updated with the npm/Node prerequisite. |
| `zte-admin-ui`'s `redirect_uri`/Keycloak client id/gateway URL are hardcoded in `main.tsx`, not environment-configurable | Low | Matches this MVP's existing posture everywhere else (hardcoded `localhost` ports throughout `application.yml` defaults, `docker-compose.yml`, README examples) — consistent, not a new gap. |

---

## Consequences

- **Positive:** users2service, service2service, and agentMcpToolCalls are now
  governed identically — one YAML file, one evaluation engine
  (`PolicyMatcher`), one audit-log shape. ADR-011's two-sources-of-truth risk
  for users2service is fully retired, not just mitigated.
- **Positive:** Operators have a real UI for the policy set instead of
  `curl`+`python3 -m json.tool` and hand-edited YAML.
- **Positive:** The `GlobalFilter`-vs-`WebFilter` distinction is now
  documented in code (not just tribal knowledge), and pinned down by a test
  that would fail loudly if it regressed.
- **Negative:** `access_policies` and its Flyway history (V1's audit table
  aside) is gone — a rollback to DB-backed users2service would mean writing
  a new migration + repository from scratch, not just reverting a commit
  cleanly (the V3 drop is itself irreversible without a backup).
- **Negative:** an operator now has two policy consoles to be aware of
  (`/api/v1/internal/**` for tooling, `/api/v1/admin/**` + the SPA for
  humans) — documented, but more surface than one.
- **Negative:** Build complexity increased — Gradle now orchestrates an npm
  build as part of `gateway-service`'s own build, a genuinely different kind
  of dependency than every other module in this repo has needed so far.

---

## Future Migration Path

- Filesystem-watch auto-reload (still on ADR-011's list, unchanged by this ADR).
- Per-category `default-effect` overrides (still on ADR-011's list, unchanged).
- A generic mechanism (custom Gateway `RouteLocator` entries that route
  *through* `FilteringWebHandler` even for "local" handlers, or converting
  `ZteAuthorizationFilter`/`ServiceToServiceAuthorizationFilter` to
  `WebFilter`s with a carefully-chosen order) so any future gateway-local
  endpoint gets users2service enforcement "for free" instead of needing its
  own `AdminAuthorizationFilter`-style filter — noted as a real gap in
  Self-Critique above, deliberately not attempted here to keep this
  change's blast radius to the one new endpoint.
- Environment-configurable Keycloak/gateway URLs in `zt-admin-ui` (currently
  hardcoded, consistent with the rest of this MVP — see Self-Critique).
