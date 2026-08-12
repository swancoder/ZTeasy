# ADR-017: Dynamic Inventory-Driven Routing, Unified Audit Logging, and Strict S2S Rules

## Status
Accepted

## Context

Two hardcoded Gateway routes (`GatewayRouteConfig`, `/api/v1/service-a/**` →
`service-a.uri`, `/api/v1/service-b/**` → `service-b.uri`) had coexisted
with the fully DB-driven APIM inventory registry (`inventory_services`,
ADR-016) since Stage 16 — the registry could track and health-poll any
number of services, but only these original two could ever actually
receive traffic, since routing itself never consulted the registry at
all. Separately, `request_logs` (ADR-013) audited REST gateway traffic to
Postgres, while MCP tool-call traffic had its own, separate, log-only
audit path (`LoggingMcpAuditService`, ADR-009) — a unification ADR-009
explicitly flagged as future work, never done. This ADR closes both gaps:
routing becomes 100% `inventory_services`-driven, and MCP audit events
join REST traffic in the same `request_logs` table. It also adds a
concrete `service2service` policy scenario (an `ALLOW`/no-rule pair
against two `service-b` endpoints) to exercise `ServiceToServiceAuthorizationFilter`,
which had no live-tested scenario before this.

## Decision

### Investigated and corrected before writing any code

The task that prompted this work assumed several things that turned out
to be wrong once checked against the actual codebase — each investigated
directly (bytecode/source reading, decompiling where relevant), not
assumed either way:

1. **"Reading the request body in a GlobalFilter is dangerous, use
   `ServerWebExchangeUtils.cacheRequestBody`"** — moot. The MCP proxy
   (`POST /message`) isn't a Spring Cloud Gateway route/`GlobalFilter` at
   all; it's a plain WebFlux `RouterFunction` handler
   (`McpProxyHandler.handleMessage`) that parses the body exactly once via
   `request.bodyToMono(JsonRpcRequest.class)` and never needs to replay
   it — it builds a *new* outbound request (`McpBackendClient.forward`),
   it doesn't proxy the original byte stream. `agentId`/`toolName` were
   already extracted here, just never persisted anywhere durable.
2. **"Create migration `V[N]__create_request_logs.sql`"** — `request_logs`
   already exists (V4, ADR-013), with `agent_id`/`tool_name` columns
   already present and explicitly commented "reserved for a future
   MCP-audit unification, always null for REST traffic today." A literal
   `CREATE TABLE request_logs` would have failed outright. `V12` `ALTER`s
   the existing table instead, and the MCP unification (below) populates
   those two already-reserved columns rather than adding new ones for the
   same concept.
3. **"Remove the static service-a/service-b routes from
   `application.yml`"** — there was nothing there to remove; the routes
   were Java config (`GatewayRouteConfig`'s `RouteLocator` bean), not
   YAML. `application.yml` did (and still does) have `service-a.uri`/
   `service-b.uri` *values*, repurposed below rather than deleted.
4. **"Update `ZeroTrustBreachIT` (or a new S2S IT) — service-a calling
   service-b"** — `service-a`/`service-b` are not, and structurally should
   not be, OAuth2 clients that authenticate *to the gateway*: their real
   inter-service call is a direct mTLS hop (ADR-004), off-gateway
   entirely, using the OBO token the gateway already minted for the
   *user's* request — there is no "service-a calls the gateway as itself"
   flow in the existing architecture. Building the requested S2S test
   scenario needed a new Keycloak machine client (`service-a`, mirroring
   `agent-a`/`agent-b`'s shape) representing a distinct, additional
   identity: something authenticating *to the gateway* claiming to be
   service-a, for exercising `ServiceToServiceAuthorizationFilter`
   specifically — not a stand-in for the real service's own internal call.
   Documented explicitly in the client's own description and the policy
   YAML comments, so this distinction doesn't get lost later.

### Dynamic routing — `InventoryRouteDefinitionLocator`

A new `RouteDefinitionLocator` bean (`com.zte.gateway.inventory`) queries
`InventoryRepository.findAll()`, filters to `target_type = REST` and
`status IN (ACTIVE, WARNING)` (matching `InventoryStatus`'s own "WARNING
is reachable enough to route" semantics, already established in ADR-016),
and maps each row to a `RouteDefinition`: `Path` predicate
`/api/v1/{name}/**`, `uri = base_url`, `AddRequestHeader
X-Gateway-Source=zte-gateway` — the same shape `GatewayRouteConfig`'s two
hardcoded routes used, so `RequestTargetResolver`'s `/api/v1/{name}/...`
→ `name` convention (used by every authorization filter) stays exactly
aligned with what's actually routable, by construction.

**Route freshness.** Spring Cloud Gateway's default
`cachedCompositeRouteLocator` wraps every `RouteLocator` (including the
`RouteDefinitionRouteLocator` built from this locator) in a
`CachingRouteLocator` — confirmed by decompiling
`GatewayAutoConfiguration`/`CachingRouteLocator`, not assumed — which only
re-fetches routes on a `RefreshRoutesEvent`. `InventoryService.create`/
`update`/`delete` each publish one immediately, so an operator's own
onboard/edit/remove actions route right away. `AutoDiscoveryWorker`/
`HealthPollingService`, though, write `status` directly via
`InventoryRepository` — not through `InventoryService` — so they publish
no event of their own; `InventoryRouteRefreshScheduler`
(`@Scheduled(fixedDelayString = "${zte.routing.refresh-interval-ms:30000}")`)
is the periodic catch-all for that path (same order-of-magnitude as
`HealthPollingService`'s own poll interval — routing doesn't need to be
fresher than the health signal driving it).

**`InventoryBootstrapSeeder`** — routing being 100% DB-driven means an
empty `inventory_services` table routes nowhere at all; without
mitigation, a fresh `docker compose up` would need an operator to
manually onboard `service-a`/`service-b` via the Admin Console before
either was reachable — a real regression from the old zero-config
experience. This `ApplicationRunner` seeds both, once, at startup, only
if not already registered (never overwrites an operator's own edits),
reusing the exact same `service-a.uri`/`service-b.uri` properties
`GatewayRouteConfig` used to hardcode directly — same property names,
repurposed from "the route" to "the bootstrap seed value."

### Two real, previously-latent bugs found and fixed along the way

Neither is new code introduced by this ADR — both are pre-existing
defects this work was the first to actually exercise, found by
methodically debugging why a genuinely-correct-looking implementation
kept 404ing/403ing in integration tests, not assumed or guessed at.

1. **`@EnableScheduling` was declared on `MtlsHttpClientConfig`**, which
   is `@ConditionalOnProperty(zte.mtls.enabled, matchIfMissing=true)` —
   and the `it` test profile sets `zte.mtls.enabled=false` explicitly (no
   certs needed in CI). That means the whole class, `@EnableScheduling`
   included, was never registered as a bean in *any* integration test's
   Spring context — so no `@Scheduled` method anywhere in this
   application, including `HealthPollingService`'s pre-existing one, has
   ever actually fired during a test, in this project's entire history.
   It simply never mattered before: every prior IT test relied on
   explicit synchronous/async triggers (`AutoDiscoveryWorker` fired
   directly from `InventoryService.create`, or the synchronous
   `fetchSchemaNow` endpoint), never on a periodic job actually running.
   `InventoryRouteRefreshScheduler` is the first thing to genuinely
   depend on it. Fixed by moving `@EnableScheduling` to
   `GatewayApplication` (always present, unconditional) — found live by
   adding a temporary `/actuator/gateway/routes` + DEBUG-level
   `RouteDefinitionRouteLocator` logging probe to an IT test and
   observing that a seeded, `WARNING`-status, correctly-configured
   `service-b` entry never once appeared in the live route cache no
   matter how long the test waited.
2. **`ZteAuthorizationFilter`'s "is this a service principal" check
   required `realm_access.roles` to be completely empty** (`roles.isEmpty()
   && isServicePrincipal(jwtAuth)`) before handing a request off to
   `ServiceToServiceAuthorizationFilter`. Every Keycloak client — service
   or interactive — is granted default composite/scope roles
   automatically (`offline_access`, `uma_authorization`,
   `default-roles-<realm>`), so `roles.isEmpty()` is essentially never
   true for a real token. This was never caught before because the only
   pre-existing service-credential callers (`agent-a`/`agent-b`) never
   reach this filter at all — MCP traffic goes through `McpProxyHandler`'s
   separate WebFlux router, which this Gateway `GlobalFilter` doesn't
   apply to. The new `service-a` client, calling a genuinely
   Gateway-routed `/api/v1/service-b/**` path, is the first service
   credential ever to hit this exact code path — and was immediately,
   incorrectly, `users2service`-DENIED with "no yaml rule" before
   `ServiceToServiceAuthorizationFilter` ever got a chance to evaluate
   its actual `service2service` `ALLOW` rule. Fixed by dropping the
   `roles.isEmpty()` requirement — `isServicePrincipal(jwtAuth)` (the
   `azp` check `ServiceToServiceAuthorizationFilter` itself already uses
   as its sole identity signal) is sufficient and correct on its own;
   interactive users are unaffected since they authenticate via the
   `zte-gateway` client, which `isServicePrincipal` always excludes
   regardless of roles.

Both were found and root-caused with live evidence (decompiled
autoconfiguration classes, `RouteDefinitionRouteLocator` DEBUG logs,
`ZT-DENY` WARN log lines captured from a failing test run) before any fix
was written — not guessed at or worked around.

### Audit logging unification

`request_logs` (`V12__extend_request_logs.sql`) gains five columns:
`initiator_client` (JWT `azp` — the calling service/agent identity for a
machine-to-machine request, `NULL` for a plain interactive user),
`original_user_obo` (the JWT subject that reached the gateway — the same
identity it embeds into the OBO token it mints for downstream
propagation), `target_service` (`RequestTargetResolver`-derived, already
computed for health telemetry, now reused), `http_method`, and
`decision_effect` (`ALLOW`/`DENY`/`ERROR`, derived from the final status
code: 2xx → `ALLOW`, 401/403 → `DENY`, else → `ERROR`). Deliberately
*not* new `target_path`/`mcp_tools` columns — `path`/`tool_name` already
exist and mean the same thing; duplicating them would fragment the same
data across two column names for no benefit.

`RequestAuditFilter` (REST path) computes and persists all five via a new
`RequestLog.forRest(...)` factory. `LoggingMcpAuditService` (MCP path) now
also calls `RequestLogAuditService.record(...)` — reusing the exact same
async, non-blocking `Sinks.Many`/`boundedElastic` writer REST traffic
already used, rather than building a second persistence mechanism — via a
new `RequestLog.forMcp(...)` factory: `trace_id` is the MCP session id
(the closest thing MCP has to a per-flow correlation identifier, since
tool calls don't carry `X-Request-Id`), `agent_id`/`tool_name` (now
finally populated, no longer permanently `null`), `initiator_client` set
to the same agent id (so "who initiated this row" never has to branch on
target type), `http_method` hardcoded `POST` (the only method `/message`
ever uses), `status_code`/`decision_effect` derived from the MCP policy
decision (`ALLOWED`→200/`ALLOW`, `DENIED`→403/`DENY`), and `message` the
deny reason for a `DENIED` event (`PolicyDecision#reason()`, newly
threaded through `McpAuditEvent`).

**`decision_effect` is a coarse, status-code-derived signal, not
per-policy-rule provenance** — `RequestAuditFilter` observes the final
response after the *entire* filter chain (and possibly the downstream
service itself) has run, so it can't distinguish "ZTE's own policy layer
denied this" from "the downstream service returned this status on its
own." Named explicitly as a known limitation (Self-Criticism), not
silently glossed over.

### `service2service` policy scenario

`service-b` gains a second endpoint, `GET /api/v1/service-b/restricted`
(`UserContextController`) — identical mTLS+OBO validation logic to the
existing `/context`, added purely as a second target with different
policy exposure, not because the method itself implements any access
restriction. `zte-policies.yaml`'s `service2service` list (previously
empty) gains one `ALLOW` rule: `client:service-a` → `service-b`'s
`/context`, `GET` only. `/restricted` has **no** rule — deliberately
omitted rather than an explicit `DENY`, so it falls through to
`zte.policy.default-effect` (`DENY` by default) — proves the "no rule
means no access" posture, not just an explicit-deny-rule posture.

**Keycloak realm import gotcha, found live:** the new `service-a`
client's first `description` field (a full paragraph explaining the
service-a-identity-vs-real-service-a distinction above) was 433
characters — Keycloak's `CLIENT.DESCRIPTION` column is `VARCHAR(255)`.
`--import-realm` doesn't validate this ahead of time; it fails deep
inside a Liquibase-driven `INSERT`/`UPDATE` with a raw Hibernate
`DataException`, which Testcontainers surfaces only as an opaque
"Container startup failed for image quay.io/keycloak/keycloak:24.0.4" —
no indication *what* about the realm export was wrong. Found by running
the exact same image manually (`docker run ... start-dev --import-realm`)
outside Testcontainers, where the real Hibernate SQL error was visible in
plain container logs. Fixed by shortening the description to under 200
characters; worth remembering for any future realm-export.json edit.

## Findings from live testing

- Confirmed via a throwaway inventory entry (`docs_url`-style manual test,
  base_url pointed at a non-`/api/v1/{name}` target) that the dynamic
  locator's `PredicateDefinition.addArg("patterns", ...)` binding is
  correct — `patterns` (plural), not `pattern`, matching
  `PathRoutePredicateFactory.Config`'s actual field name, confirmed via
  `javap` before trusting it. A `403` ("no policy grants access"), not a
  generic `404`, is what confirmed the route matched but policy denied it
  — the reverse (a bare `404`) is what later exposed the `@EnableScheduling`
  bug, once a *second*, never-yet-refreshed entry was involved.
- Full integration suite (`./gradlew integrationTest`) run repeatedly
  through each stage of this change — static routes removed, `service-b`
  seeded, `ServiceToServiceIT` added — to catch regressions incrementally
  rather than only at the end; both bugs above were caught this way, not
  in a final all-at-once run.
- `service-a`/`service-b` Keycloak-machine-identity distinction verified
  live: `getAgentToken("service-a", ...)`'s JWT correctly carries
  `azp=service-a`, is correctly treated as a non-interactive caller by
  both `ZteAuthorizationFilter` (post-fix) and
  `ServiceToServiceAuthorizationFilter`, and produces exactly the
  `ALLOW`/`DENY` outcomes the policy rules specify.

---

## Alternatives Considered

### A parallel "routes" table, separate from `inventory_services` (rejected)

- **Pros:** Clean separation between "what's monitored" and "what's
  routed," if those ever needed to diverge.
- **Cons:** `inventory_services` already has every field a route
  definition needs (`name`, `base_url`, `status`, `target_type`) —
  building a second table would duplicate that data and require keeping
  two things in sync for no benefit this MVP needs.
- **Verdict:** Rejected — one table, `InventoryRouteDefinitionLocator`
  reads it directly.

### `ServerWebExchangeUtils.cacheRequestBody` for MCP tool-name extraction (rejected)

- **Pros:** The textbook-documented pattern for safely peeking a Gateway
  `GlobalFilter`'s request body without consuming it.
- **Cons:** Doesn't apply — the MCP proxy isn't a `GlobalFilter`/routed
  path at all (see Decision, point 1); this machinery would be solving a
  problem that doesn't exist in this codebase's actual architecture.
- **Verdict:** Rejected as unnecessary, not merely unused.

### Reusing `agent-a`/`agent-b` for the S2S test instead of a new `service-a` client (rejected)

- **Pros:** No realm-export.json change, no new secret to manage.
- **Cons:** Misrepresents the scenario — the task specifically asked to
  test "service-a calling service-b," and reusing an MCP agent's identity
  for that would produce a passing test that doesn't actually prove what
  it claims to.
- **Verdict:** Rejected — added a purpose-built `service-a` machine
  client instead, with its distinct-from-the-real-service-a-app nature
  documented explicitly.

---

## Self-Criticism

| Risk | Severity | Mitigation |
|---|---|---|
| `decision_effect` is derived purely from the final HTTP status code, not from which policy layer actually made the decision — can't distinguish a ZTE-layer `DENY` from a downstream service's own `403`/`404`. | Medium | Named explicitly, not hidden. A per-filter `exchange` attribute threaded through to `RequestAuditFilter` would fix this properly but touches three filters for a distinction this MVP doesn't yet need — tracked as a backlog item. |
| MCP traffic now produces **two** `request_logs` rows per interaction, not one: `RequestAuditFilter` (a `WebFilter`, unconditionally applied) still audits the raw `GET /sse`/`POST /message` HTTP requests as generic REST traffic (`agent_id`/`tool_name` null, `target_service` = the literal path), *in addition to* the new MCP-specific row `LoggingMcpAuditService` writes per tool call (`agent_id`/`tool_name` populated). Found live, not in any IT test — `McpProxyIT` never queries `request_logs`, and `ServiceToServiceIT`/`LoggingMcpAuditServiceTest` only assert a matching row exists, never that exactly one does. | Low | Arguably correct, not a bug: one row is transport-level ("something hit this endpoint"), the other is semantic ("here's the policy decision for this specific tool call") — the same two-layers-of-audit structure ADR-013 already established for REST vs. this ADR's own MCP addition. Not silently discovered and ignored; if it proves confusing in the Admin Console UI, adding `/sse`/`/message` to `zte.audit.excluded-path-prefixes` would suppress the transport-level row — deliberately not done here since that would also suppress `X-Request-Id`/`X-User-Id` visibility for that traffic, a bigger behavior change than this ADR's scope. |
| `InventoryRouteRefreshScheduler`'s periodic interval (30s default) means a service transitioning `ACTIVE`↔`DOWN` via `HealthPollingService` can be routable-but-actually-down (or vice versa) for up to that long. | Low | Same tolerance this codebase already accepts for `HealthPollingService`'s own 60s poll interval — routing freshness doesn't need to exceed the health signal driving it. `InventoryService`'s own create/update/delete stay immediate. |
| `InventoryBootstrapSeeder` never updates an existing entry, only skips if present — if an operator manually deletes `service-a`/`service-b` from the registry, restarting the gateway silently re-seeds them at the `SERVICE_A_URI`/`SERVICE_B_URI` defaults, which might surprise an operator who deleted them on purpose. | Low | Matches this codebase's established "idempotent seed, never fight the operator's edits" bias elsewhere; an operator who wants them permanently gone would need to also unset the env vars — not documented prominently, a fair follow-up. |
| The `@EnableScheduling` and `ZteAuthorizationFilter` fixes are both broader than this ADR's own feature scope — they fix latent, previously-unexercised bugs affecting `HealthPollingService` and any future Gateway-routed service-credential caller, not just this ADR's own new code. | Low | Deliberate, not scope creep avoided — both were blocking this ADR's own correctness and would have silently affected any future feature that happened to be the first to exercise them either way; fixing them now with full evidence is better than leaving a known-bad state for the next unlucky feature to rediscover. |
| No test proves `InventoryBootstrapSeeder` itself (it's excluded from IT via blanked `service-a.uri`/`service-b.uri` properties, so tests can seed their own WireMock-pointed entries instead). | Low | Its logic (`existsByName` check + `InventoryService.create` delegation) is simple enough to be low-risk, and both of those pieces are independently well-tested; a dedicated unit test would mostly be re-testing Mockito wiring. |

---

## Consequences

- Onboarding a new REST service via the Admin Console now makes it
  immediately routable — no code change, no redeploy, matching the
  registry's own "self-service onboarding" intent from ADR-016 far more
  completely than before (previously, only the two hardcoded services
  could ever receive real traffic no matter what was registered).
- `request_logs` is now genuinely the single, unified audit trail for
  both REST and MCP traffic — an operator no longer needs to check two
  different places (Postgres vs. application logs) to see what an agent
  did.
- Fixed two real bugs (`@EnableScheduling` scope, `ZteAuthorizationFilter`
  service-principal detection) that were latent and unexercised before
  this ADR, now correct for every future caller, not just this ADR's own.
- `GatewayRouteConfig` is gone; `service-a.uri`/`service-b.uri` properties
  are repurposed (bootstrap seed values, not route definitions) rather
  than removed, preserving the existing `docker-compose.yml`/env-var
  deployment convention.

## Future Migration Path

- Per-filter `exchange` attribute for `decision_effect` provenance (which
  layer actually decided), instead of status-code inference (Self-Criticism).
- `InventoryBootstrapSeeder` could optionally support a "force re-seed"
  flag for operators who want the old zero-config values back after
  deleting them, if that gap ever proves to matter in practice.
- A `Content-Length`/streaming cap on captured MCP/REST audit payloads —
  same open item ADR-016 already tracks for `discovered_schema`.
- Distinguish `service-a` (the new machine identity) from the real
  service-a application more visibly in the Admin Console's Identities
  tab, so an operator doesn't confuse the two — currently only documented
  in the Keycloak client's own description and this ADR.
