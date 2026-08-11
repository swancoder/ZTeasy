# ADR-016: APIM Inventory Registry — Auto-Discovery and Health Telemetry

**Status:** Accepted
**Date:** 2026-08-11
**Deciders:** ZTE-Lightweight Architects

---

## Context

`service-a`/`service-b` (and any MCP agent this gateway fronts) have existed
so far only as hardcoded `GatewayRouteConfig` routes and `zte-policies.yaml`
rule sources — there was no operator-facing inventory of what's actually
registered, no automated check that a newly onboarded service is reachable
and speaks the protocol it claims to, and no visibility into whether routed
traffic is actually succeeding over time. This adds a central APIM registry
(`inventory_services`), an auto-discovery worker that probes a service's
schema/tool list right after onboarding, a periodic health-ping job, and
passive `last_successful_call` tracking fed by real routed traffic — plus a
new "Registry" tab in the Admin Console.

---

## Decision

### Java + Reactor, not Kotlin — module language convention holds

The task's own Chain-of-Thought framing called for "an async Kotlin/Reactor
process." `gateway-service` is, and has been since Stage 1, a pure Java 21
module — `zt-agents` is the only Kotlin module in this repo, a deliberate,
narrow choice (a separate AI-copilot service, not part of the gateway's
core request path). Introducing Kotlin into `gateway-service` for one
worker class would be a real, disruptive architectural change for a single
feature, breaking a convention every other async component in this module
(`IdentitySyncService`, `KeycloakIdpAdapter`, `McpBackendClient`,
`HealthPollingService` itself) already follows. `AutoDiscoveryWorker` is
Java, using Project Reactor (`WebClient`/`Mono`) exactly like every sibling
component — "Reactor" from the task's own phrasing is honored; "Kotlin"
isn't, since the two aren't actually coupled requirements in this codebase.

### `health_metrics` is one current-state row per service, not a time series

`UNIQUE (service_id)`, upserted in place by both the health-poll job and
the passive traffic hook. The task's own column list (`last_ping_ms`,
`actuator_status`, `last_successful_call`, `updated_at` — all singular,
present-tense fields) already implies "current state," not a log; a
history table is a legitimate future extension (Future Migration Path) but
adds real schema/query complexity ("chart ping latency over time") the
task didn't ask for.

### Auto-discovery: `GET {base_url}/v3/api-docs` (REST), `POST {base_url}/message` `tools/list` (MCP)

The REST probe matches the task literally. The MCP probe is a real
interpretation call the task left open: "send a JSON-RPC `tools/list`
request" — but to what URL? This gateway's own MCP proxy uses a stateful
`GET /sse` handshake before any `POST /message` call
(`McpProxyHandler`/`McpSessionManager`). Requiring that same handshake for
a one-shot discovery probe would mean either faking a session or teaching
`AutoDiscoveryWorker` the full MCP session protocol for a single
call — real complexity discovery doesn't need. Decision: `AutoDiscoveryWorker`
POSTs directly to `{base_url}/message` with a bare `tools/list` JSON-RPC
envelope, on the assumption that schema discovery is stateless (matching
the URL shape `McpBackendClient` already uses for its own downstream call).
Named explicitly as an assumption, not a spec fact — MCP agents that
strictly require the session handshake even for `tools/list` would need a
different discovery strategy (Future Migration Path).

### `InventoryStatus`: `WARNING` is sticky; only `ACTIVE`↔`DOWN` self-heal

The task defines four statuses but only wires two transitions explicitly:
`PENDING`→`ACTIVE`/`WARNING` (discovery outcome). What resolves `DOWN`, or
un-sticks `WARNING`, was left unspecified — leaving `DOWN` as a defined but
unreachable enum value felt worse than a small, deliberate extension:
`HealthPollingService`'s periodic ping now also toggles `ACTIVE`↔`DOWN`
(reachable ping ⇒ `ACTIVE`, unreachable ⇒ `DOWN`), self-healing without any
operator action. `WARNING` is different in kind — it means the schema/tool
discovery itself failed or couldn't be confirmed, "a degraded state where
manual routing is required" in the task's own words — so a successful raw
health ping must **not** silently clear it; that would hide the real
problem (the API contract is still unconfirmed) behind an unrelated
green signal. `WARNING` only clears via a fresh discovery (re-onboarding,
or the planned "retry discovery" action — Future Migration Path).

### Health polling covers `ACTIVE`, `WARNING`, and `DOWN` — never `PENDING`

The task says "polls active/warning services"; `DOWN` is included too so a
downed service can recover automatically (see above) rather than being a
permanent dead end. `PENDING` is excluded — its `base_url` hasn't even
passed `AutoDiscoveryWorker`'s first probe yet, so pinging it is premature
noise, not a meaningful signal.

### `last_successful_call`: async fire-and-forget, resolved by service *name*, not gated by the audit exclusion list

Directly answers the task's own Self-Criticism: `HealthTelemetryService`
mirrors `RequestLogAuditService`'s exact architecture (`Sinks.Many` +
single `Schedulers.boundedElastic()` subscriber) — `RequestAuditFilter`'s
hot path does one non-blocking `tryEmitNext`, never awaits the DB write.
The upsert resolves `inventory_services.id` from the request's
`RequestTargetResolver`-derived target name via a `SELECT` subquery in the
same statement (`INSERT ... SELECT id FROM inventory_services WHERE
name = :name ... ON CONFLICT`) — one round trip, and a target name with no
matching registry row is a harmless no-op (not every routed path is
expected to be in the inventory). Deliberately **not** gated by
`AuditExclusionProperties` (the `request_logs` exclusion list, ADR-013) —
inventory health freshness is a different concern with its own no-op
safety net, and doesn't need or want that list's semantics.

### `InventoryService.list()` joins identities and health in memory, not via a native projected query

Spring Data R2DBC can, in principle, project a native `@Query` onto an
unannotated DTO record — but this project has no prior precedent using
that mechanism reliably, and getting it wrong is exactly the kind of
subtle, hard-to-unit-test failure mode this codebase has repeatedly hit
with R2DBC (`Mono<Void>` pitfalls, `@Modifying` vs. `RETURNING`, both
found live in prior sessions). `list()` instead fetches both repositories'
`findAll()`s and joins by `service_id` in a `Map`, in application code —
simpler, provably correct, and negligible cost at this project's MVP scale
(a handful of registered services), matching the same "don't add machinery
MVP scale doesn't need" bias `PolicyMatcher`'s full linear scan already
established.

### Plain MUI `Table`, not `@mui/x-data-grid`

The task's Task 5 literally says "MUI DataGrid." This repeatedly-reaffirmed
project decision (ADR-013's original call, reaffirmed in ADR-014/015/the
Identities UI ADR) rejects `@mui/x-data-grid` as an unproven dependency on
this MUI v9/React 19 combination, in favor of the same plain `Table`
pattern every other Admin Console tab already uses. Read literally, the
task asks for a data *grid* (rows and columns) — a plain `Table` satisfies
that functionally, at zero new dependency risk.

### `update()` always resets to `PENDING` and re-triggers discovery

The task didn't specify conditional re-discovery ("only if `base_url`
changed"). Always resetting is the simpler, provably-correct choice: it
can never leave a stale `ACTIVE` status pointing at a since-changed URL,
at the cost of one redundant discovery probe on a same-URL rename.

---

## Findings from live testing

### `InventoryService.update()`'s original `save()`-based implementation violated `created_at NOT NULL`

Found running this feature's own new integration test (`InventoryRegistryIT`'s
CRUD scenario), not caught by any mocked unit test: constructing a full
replacement `InventoryEntry` with `createdAt = null` for an update, then
calling `save()`, issues a plain `UPDATE ... SET created_at = NULL` (a
`save()` on a non-null-id entity is an UPDATE, not an INSERT, so the
column's `DEFAULT NOW()` never applies) — a `NOT NULL` constraint
violation, surfaced to the caller as an opaque `500`. Fixed with a scoped
`InventoryRepository.updateFields(...)` `@Query` touching only the
operator-editable columns, leaving `created_at` untouched — the same "don't
`save()` a partial/reconstructed entity, use a scoped `@Query` instead"
lesson `updateStatus` already applied for a different reason (avoiding a
read-then-write race), now shown to matter for column-completeness too.

### `Iterable` isn't a valid Spring Data R2DBC derived-query parameter type

`findByServiceIdIn(Iterable<UUID>)` failed application-context startup —
`IN` derived queries require `Collection`, not the more general
`Iterable`, even though `List` satisfies both. Fixed by narrowing the
parameter type. A real, if narrow, Spring Data R2DBC API constraint worth
naming since it's not obvious from the method-naming convention alone.

---

## Alternatives Considered

### On-demand schema re-fetch per Admin Console page load, instead of caching `status` (rejected)

- **Pros:** Always current, no stale-status window.
- **Cons:** Turns viewing the registry into a live dependency on every
  registered service's availability — the exact anti-pattern ADR-014
  already rejected for the identity cache, for the same Zero Trust
  reliability reasoning.
- **Verdict:** Rejected — `status` is a cached, periodically-refreshed
  field by design, same as `idp_identities`.

---

## Self-Criticism

| Risk | Severity | Mitigation |
|---|---|---|
| `AutoDiscoveryWorker`'s MCP `tools/list` probe assumes a stateless `POST {base_url}/message` call — an agent that strictly requires the `GET /sse` session handshake even for discovery will always land in `WARNING`, not because it's actually broken | Medium | Named explicitly as an assumption, not a spec fact (see Decision). No MCP agent in this repo's own fleet (Agent A/B via `hubspot-mcp`) is registered through this new onboarding flow yet, so it's untested against a real stateful-only agent. |
| `WARNING` has no UI-driven way to clear other than deleting and re-onboarding the service | Low | Deliberate MVP scope — a "Retry Discovery" action is a natural, low-effort extension (Future Migration Path), not built because the task's own Task 5 UI list didn't ask for it. |
| `AutoDiscoveryWorker`/`HealthPollingService`'s actual HTTP-calling code (the `WebClient` probes) has no dedicated mocked-`WebClient` unit test | Low | Consistent with this codebase's established precedent (`KeycloakIdpAdapter`, `McpBackendClient` — never unit-tested with mocked HTTP) — proven instead by `InventoryRegistryIT` against a real WireMock target. The one pure, extractable piece of decision logic (`HealthPollingService.statusTransition`) does have a direct unit test, same as `KeycloakIdpAdapter#isSystemClient`'s precedent. |
| A service `name` collision between the inventory registry and `RequestTargetResolver`'s path-segment extraction is required for passive `last_successful_call` tracking to work at all — an inventory entry named anything other than the exact path segment (`"service-a"`, not `"Service A"` or `"svc-a"`) silently never receives telemetry | Medium | Named explicitly, not hidden — `HealthMetricRepository.upsertSuccessfulCallByServiceName`'s Javadoc states the no-op-on-mismatch behavior. No validation enforces the naming convention at onboarding time; an operator registering `service-a` under a different display name gets a registry entry that's otherwise fully functional (discovery, health polling) but never shows passive traffic data. |
| Like every other `idp_identities`/sync-based cache in this codebase, `inventory_services`/`health_metrics` accumulate no reconciliation — a deleted service is only removed by an explicit `DELETE`, never automatically | Low | Consistent with this repo's established posture (identity sync has the same property, named in its own ADR) — not a new gap. |
| `AutoDiscoveryWorker`/`HealthPollingService` use a plain `WebClient` with no ZTE mTLS client certificate — registering `service-a`/`service-b` by their mTLS API port (8081/8082) always discovers/polls as unreachable | Low | Found live testing this feature against the real running stack — not a bug, `MtlsHttpClientConfig`'s client cert is deliberately scoped to `GatewayRouteConfig`'s own routing `HttpClient`, not reused here. Documented in the README: register those two services via their plain-HTTP management port (9081/9082) instead, the same port `docker-compose.yml`'s own healthcheck already uses. |

---

## Consequences

- **Positive:** Operators get one place to see every registered REST
  service / MCP agent, whether it passed its initial connectivity check,
  and whether real traffic has actually reached it recently.
- **Positive:** Onboarding a broken or unreachable service is now visible
  immediately (`WARNING`) rather than silently failing the first time a
  real request tries to route to it.
- **Positive:** The health-poll job's `ACTIVE`↔`DOWN` self-healing means an
  operator doesn't need to manually flip a service back to `ACTIVE` after
  a transient outage recovers.
- **Positive:** `HealthTelemetryService` is a second, independent proof
  that the `Sinks.Many`+`boundedElastic` fire-and-forget pattern
  (`RequestLogAuditService`, ADR-013) generalizes cleanly to a new async
  write concern.
- **Negative:** The MCP discovery probe's stateless-call assumption is
  unverified against a real stateful-only MCP agent (see Self-Critique) —
  a real risk if `hubspot-mcp` (or a future agent) is ever onboarded
  through this flow and turns out to require the session handshake.
- **Negative:** Passive telemetry silently depends on exact name matching
  between the registry and `RequestTargetResolver`'s path-derived service
  name — an easy, undetected misconfiguration (see Self-Critique).

---

## Future Migration Path

- **A "Retry Discovery" Admin Console action**, to clear a stuck `WARNING`
  without deleting and re-onboarding the service.
- **A history table for `health_metrics`** (ping latency over time, not
  just the latest value), if operators need trend visibility rather than
  just current state.
- **Validate the MCP stateless-discovery assumption** against a real
  session-only agent, and fall back to a full `GET /sse` handshake for
  discovery if needed.
- **Reconciliation for stale inventory rows**, mirroring the same backlog
  item already named for `idp_identities`/`idp_identity_relations`.
- **Enforce (or at least warn on) name mismatches** between a registered
  service and any `GatewayRouteConfig` route it's meant to represent, so
  the passive-telemetry naming constraint (see Self-Critique) isn't a
  silent trap.
