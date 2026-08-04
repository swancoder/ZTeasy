# ZTeasy — Technical Specification & Implementation Roadmap

**Product:** Lightweight Zero Trust Environment (ZTE) MVP — a Zero Trust Data Gateway,
now extended toward fronting AI agent (MCP) traffic, not just plain REST.
**Status as of:** 2026-08-04 · Stage 10 of 10 implemented stages complete.

This document is the single technical reference for the system as built. It
consolidates what's spread across `README.md` (quick start, chain-of-trust
summary), `CLAUDE.md` (terse per-stage changelog for AI-assisted sessions), and
eleven ADRs (individual decisions) into one place: what exists, how it fits
together, what's configurable, what's tested, and what's left. It does not
replace the ADRs — every decision below links to the ADR that argues it.

---

## 1. Scope & Non-Goals

**In scope:** a runnable, all-Java(/Kotlin) Zero Trust stack demonstrating four
explicit trust checks end-to-end (user identity, authorization, service
identity, on-behalf-of delegation), plus two AI-facing surfaces: an internal
Policy Auditor agent, and an MCP proxy that applies the same zero-trust
posture to Model Context Protocol tool calls.

**Non-goals (by design, see ADR-001 / ADR-004):** this is not a service mesh,
not Kubernetes-native, and not production-hardened. Every mechanism is
implemented explicitly in application code specifically so it's visible and
auditable — the opposite of a mesh's "hide it in the sidecar" approach. See
§10 for where this trades off against production-readiness.

---

## 2. Status Summary

| # | Stage | Status | Commit | ADR |
|---|---|---|---|---|
| 1 | Infrastructure bootstrap (Gradle, Docker Compose, gateway skeleton) | ✅ Complete | `ddd0fbd` | [001](adr/ADR-001-architecture-pattern-gateway-vs-sidecar.md) |
| 2 | Identity provider (Keycloak realm auto-import) | ✅ Complete | `5ddac01`, `b05a6b3` | [002](adr/ADR-002-identity-provider-configuration-strategy.md) |
| 3 | DB-based policy enforcement (R2DBC + cache) | ✅ Complete | `bf873a5` | [003](adr/ADR-003-reactive-policy-engine.md) |
| 4 | mTLS + On-Behalf-Of delegation | ✅ Complete | `e917be9` | [004](adr/ADR-004-mtls-implementation.md) |
| 5 | Unit tests (filters, auth-library) | ✅ Complete | `07382bf` | — |
| 6 | E2E integration tests (Testcontainers + WireMock) | ✅ Complete | `c28fe21` | [005](adr/ADR-005-integration-testing-strategy.md) |
| — | Pre-commit documentation automation | ✅ Complete | — | [006](adr/ADR-006-pre-commit-documentation-automation.md) |
| 7 | AI Security Copilot (`zt-agents`, Policy Auditor) | ✅ Complete | `c85e77f` | [007](adr/ADR-007-policy-auditor-agent.md) |
| — | `.env`-based config for `zt-agents` | ✅ Complete | `d721915` | [008](adr/ADR-008-dotenv-configuration-management.md) |
| 8 | MCP Proxy & Interception Layer | ✅ Complete | `cb5da35` | [009](adr/ADR-009-mcp-proxy-interception-layer.md) |
| 9 | Agent OAuth2 Client Credentials Auth (dead-end stub) | ✅ Complete | `e79994e` | [010](adr/ADR-010-agent-oauth2-client-credentials.md) |
| 10 | YAML Policy Engine (users2service / service2service / agent@mcp), no-downtime reload, unified audit, real MCP enforcement | ✅ Complete | `d76c709` | [011](adr/ADR-011-yaml-policy-engine.md) |
| 11+ | Backlog (audit persistence, tracing, rate limiting, ABAC, users2service→YAML-only…) | ⬜ Planned | — | see §9.2 |

**Test status:** all unit tests green (`./gradlew test`), including the new
`policy.def` package (`PolicyValidatorTest`, `PolicyMatcherTest`,
`YamlPolicyFileLoaderTest`, `PolicyDefinitionStoreTest`,
`DocumentationExampleConformanceTest`), `YamlMcpPolicyEngineTest`, and
`ServiceToServiceAuthorizationFilterTest` — 53 tests in `gateway-service`
alone. `McpProxyIT` and the rest of the E2E integration suite were updated for
Stage 10's real MCP enforcement (allow → forwarded to backend, deny → no
backend call, both via the default `zte-policies.yaml`) but **could not be
re-run in the environment this stage was implemented in** (no Docker daemon
available) — verify with `./gradlew integrationTest` before merging.

---

## 3. System Architecture

### 3.1 Component topology

```
                              ┌───────────────────────────────────────────┐
                              │              gateway-service               │
                              │              port 8080 (HTTP)              │
  User ──[Keycloak JWT]──────►│  SecurityConfig (auth-library): JWT req'd  │
                              │                                             │
                              │  REST path (ADR-011):                      │
                              │   ① ZteAuthorizationFilter                 │
                              │      (users2service: YAML-first, DB fallback)│
                              │   ① a ServiceToServiceAuthorizationFilter  │
                              │      (service2service: YAML-only)          │
                              │   ② UserContextPropagationFilter (OBO)     │
                              │   ③ GatewayRouteConfig → service-a/b       │
                              │                                             │
                              │  policy/def (ADR-011): PolicyDefinitionStore│
                              │   AtomicRef<PolicyDocument>, loaded from   │
                              │   zte-policies.yaml, hot-swappable via     │
                              │   POST /api/v1/internal/policies/reload    │
                              │                                             │
                              │  MCP path (ADR-009, ADR-010, ADR-011):     │
                              │   GET /sse ──► McpSessionManager           │
                              │   POST /message ──► McpProxyHandler        │
                              │     → YamlMcpPolicyEngine.evaluate()       │
                              │       (agentMcpToolCalls rules); deny via  │
                              │       SSE, allow forwards to               │
                              │       McpBackendClient (supersedes Stage 9)│
                              │                                             │
                              │  Agent data path (ADR-007):                │
                              │   GET /api/v1/internal/policies            │
                              │     (Docker-network-only, no JWT)          │
                              └───────┬──────────────────┬──────────────────┘
                                      │ mTLS                │ HTTP (internal)
                                      ▼                      ▼
                          ┌───────────────────┐   ┌─────────────────────────┐
                          │     service-a      │   │       zt-agents          │
                          │  8081 (mTLS API)   │   │  port 8083 (Kotlin/WebFlux)│
                          │  9081 (mgmt)       │   │  PolicyAuditorService     │
                          │  → forwards OBO    │   │  → AnthropicClient        │
                          └─────────┬──────────┘   │    (Claude Messages API)  │
                                    │ mTLS          └───────────────────────────┘
                                    ▼
                          ┌───────────────────┐
                          │     service-b      │
                          │  8082 (mTLS API)   │
                          │  9082 (mgmt)       │
                          │  validates OBO     │
                          └───────────────────┘

  mcp-backend.uri (MCP_BACKEND_URI, default :9090) — any fronted MCP server,
  e.g. this repo's hubspot-mcp sibling project. Not part of this repo.
```

Infrastructure: PostgreSQL 16 (`5432`, `access_policies` table via Flyway +
R2DBC), Keycloak 24.0.4 (`8180`, realm `zte-realm`, native `--import-realm`).
See `docker-compose.yml`.

### 3.2 Module map

| Module | Language | Responsibility | Port(s) |
|---|---|---|---|
| `auth-library` | Java 21 | Shared security: `SecurityConfig`, `ZteAuditLogger`, `ReloadableSslContextFactory`, `UserContextTokenService` | — (library) |
| `gateway-service` | Java 21 | ZT entry point: JWT validation, DB policy enforcement, OBO issuance, mTLS client, MCP proxy, internal agent data endpoint | 8080 |
| `service-a` | Java 21 (WebFlux) | First protected downstream; calls service-b | 8081 (mTLS), 9081 (mgmt) |
| `service-b` | Java 21 (WebFlux) | Deepest downstream; validates OBO token | 8082 (mTLS), 9082 (mgmt) |
| `zt-agents` | Kotlin (WebFlux) | AI security copilot — Policy Auditor Agent | 8083 |
| `certs` | shell | Dev cert generation (ZTE-CA, PKCS12 stores) | — |
| `scripts` | shell | Keycloak password bootstrap, git hook install | — |
| `docs/adr` | Markdown | Architectural Decision Records | — |

---

## 4. Zero Trust Model

Every request must answer four questions (README's framing, unchanged since
Stage 4):

| # | Question | Mechanism | Where enforced |
|---|---|---|---|
| 1 | Who is the user? | Keycloak JWT (RS256) | `SecurityConfig` (`auth-library`), all services |
| 2 | Is the user allowed? | DB-backed `access_policies` (R2DBC) | `ZteAuthorizationFilter` (gateway) |
| 3 | Who is the internal caller? | mTLS client cert (ZTE-CA) | `MtlsHttpClientConfig` + service HTTPS listeners |
| 4 | On whose behalf? | Signed OBO token (`X-ZTE-User-Context`, HMAC-SHA256, 30s TTL) | `UserContextPropagationFilter` → `UserContextTokenService` |

The MCP proxy (§8) adds a fifth question for AI agents specifically: **is this
caller who it claims to be, and what may it do?** — Agent A/B authenticate via
OAuth2 Client Credentials (ADR-010), `McpProxyHandler` extracts the client
identity (`azp`), and as of Stage 10 (ADR-011) that identity **is** authorized:
`YamlMcpPolicyEngine` matches it against the `agentMcpToolCalls` rules in
`zte-policies.yaml` before any tool call reaches the backend — see §8.2/§8.3.

Sixth question, gateway-internal: **may this service call that service?**
(`service2service` in ADR-011) — a calling service/agent's `azp`, matched
against YAML `service2service` rules by `ServiceToServiceAuthorizationFilter`,
governs REST calls made with a service/agent credential rather than an
interactive user's.

For the full request-by-request trust narrative (User → Gateway → Service A →
Service B) see `README.md` §"Chain of Trust" — reproduced there with a
sequence diagram; not duplicated here to avoid drift between two copies.

---

## 5. Component Specifications

### 5.1 `auth-library`

Reusable security config imported by every service (`ZteSecurityAutoConfiguration`).

- **`SecurityConfig`** — `anyExchange().authenticated()` except
  `/actuator/health/**`; JWT via `oauth2ResourceServer`. Deny-by-default.
- **`ReloadableSslContextFactory`** — `AtomicReference<SslContext>`, refreshed
  on keystore file mtime change; Reactor Netty evaluates the `secure()` lambda
  per-connection, so new connections transparently pick up rotated certs
  without a restart. See [ADR-004](adr/ADR-004-mtls-implementation.md).
- **`UserContextTokenService`** — creates/validates the OBO token: HMAC-SHA256,
  claims `sub` + `roles`, `iss: zte-gateway`, 30s TTL (`ZTE_OBO_EXPIRY_SECONDS`).
- **`ZteAuditLogger`** — static structured `[ZTE-AUDIT]` log lines
  (`MTLS_ACCEPTED`, `OBO_VALIDATED`, `OBO_REJECTED`, `POLICY_ALLOW`,
  `POLICY_DENY`). No persistence, no async queue — grep/tail-grade only
  (contrast with `LoggingMcpAuditService` in §8, which *is* async and
  TSDB-directed).

### 5.2 `gateway-service` — REST path

- **`GatewayRouteConfig`** — declarative routes: `/api/v1/service-a/**`,
  `/api/v1/service-b/**` → `https://localhost:8081` / `:8082`.
- **`ZteAuthorizationFilter`** (`GlobalFilter`, order `HIGHEST_PRECEDENCE+100`)
  — users2service enforcement: extracts `realm_access.roles`, consults the
  YAML `users2service` rules first (explicit ALLOW/DENY short-circuits),
  falling back to `PolicyService.isAllowed(roles, path, method)` (DB-backed,
  unchanged since ADR-003) when no YAML rule matches; 403 +
  `GATEWAY_ALREADY_ROUTED_ATTR` on deny (blocks `NettyRoutingFilter`). A JWT
  with no realm roles and an `azp` other than the interactive user client
  (`zte.policy.user-client-id`) is service2service traffic and passes through
  untouched — see next bullet.
- **`ServiceToServiceAuthorizationFilter`** (order `HIGHEST_PRECEDENCE+150`,
  new in ADR-011) — service2service enforcement for the traffic
  `ZteAuthorizationFilter` passes through: matches the caller's `azp` against
  YAML `service2service` rules; no DB fallback, `NO_MATCH` resolves to
  `zte.policy.default-effect` (default `DENY`).
- **`policy/def` package** (ADR-011) — `PolicyDefinitionStore` (loads +
  validates `zte-policies.yaml` at startup, fails `ApplicationContext` refresh
  on invalid content; `AtomicReference<PolicyDocument>` hot-swap, mirroring
  `ReloadableSslContextFactory`'s pattern), `PolicyMatcher` (deny always
  overrides allow; `AntPathMatcher`-based), `PolicyValidator`,
  `YamlPolicyFileLoader`. See §5.2a.
- **`PolicyService`** — `Mono<List<AccessPolicy>>` cached via
  `Mono.cache(Duration.ofMinutes(5))`; fail-closed on DB error (empty list,
  not cached, so the next subscriber retries). Now the users2service
  *fallback*, not the sole source — see
  [ADR-003](adr/ADR-003-reactive-policy-engine.md) and
  [ADR-011](adr/ADR-011-yaml-policy-engine.md).
- **`UserContextPropagationFilter`** (order `HIGHEST_PRECEDENCE+200`) — strips
  client-supplied `X-ZTE-User-Context`/`X-User-Id`, issues the OBO token.
- **`RequestAuditFilter`** (order `LOWEST_PRECEDENCE-10`) — logs `sub`/`azp`,
  injects trusted `X-User-Id`.
- **`InternalPolicyController`** — `GET /api/v1/internal/policies`, permitAll
  via `InternalSecurityConfig` (`@Order(-100)`), Docker-network-only exposure.
  Feeds `zt-agents`. See [ADR-007](adr/ADR-007-policy-auditor-agent.md) for
  why this is unauthenticated by design (network perimeter, not app-layer)
  and its production upgrade path.
- **`PolicyReloadController`** (ADR-011) — `POST
  /api/v1/internal/policies/reload`, same permitAll/network-perimeter posture
  as `InternalPolicyController`; re-validates and atomically swaps the active
  `PolicyDocument`, keeping the previous one on validation failure.

### 5.2a YAML Policy Engine (ADR-011)

One YAML file (`zte-policies.yaml`, path via `zte.policy.file`) is the runtime
source of truth for `service2service` and `agentMcpToolCalls`, and an
additive, deny/allow-first layer in front of the DB for `users2service`. Full
schema, precedence rules, and validation semantics: `docs/policy-schema.md`.
Full three-category worked example: `docs/examples/zte-policies-example.yaml`
(kept honest by `DocumentationExampleConformanceTest`, which loads and
validates it against the real schema).

A single `PolicyRule` shape (`id`, `effect`, `source`, `target`,
`pathPattern`, `methods`, `priority`) is reused across all three categories —
`pathPattern`/`methods` are simply unused by `agentMcpToolCalls` rules. See
ADR-011 for why this was chosen over three parallel rule subclasses.

### 5.3 `service-a` / `service-b`

- **`service-a/HelloController`** — `GET /api/v1/service-a/hello`; calls
  service-b via mTLS `WebClient`, forwards `X-ZTE-User-Context` unchanged
  (delegation, not re-issuance), returns a combined JSON response.
- **`service-b/UserContextController`** — validates the OBO token's HMAC
  signature + expiry; 401 on failure; returns `sub`, `roles`, `trustBasis`.
- Both run Spring WebFlux (Netty) specifically to support the mTLS listener
  pattern from ADR-004; management endpoints are HTTP-only on separate ports
  so Docker health checks don't need a client cert.

### 5.4 `zt-agents` — Policy Auditor Agent

Kotlin/WebFlux module, port 8083. `PolicyAuditorService` orchestrates:
fetch policies (`GatewayClient` → gateway's internal endpoint) → format →
send to Claude (`AnthropicClient`, model `claude-sonnet-4-6`, 120s timeout) →
return a Markdown compliance report via `POST /api/v1/agents/auditor/run`.
Configuration (`ANTHROPIC_API_KEY`, model/timeout/max-tokens, gateway URI) is
loadable from `.env` via `spring-dotenv` since ADR-008 — env vars still take
precedence over `.env` values. See [ADR-007](adr/ADR-007-policy-auditor-agent.md)
and [ADR-008](adr/ADR-008-dotenv-configuration-management.md).

### 5.5 Infrastructure

- **PostgreSQL 16-alpine** — `access_policies` table (Flyway `V1`/`V2`
  migrations, JDBC for Flyway + R2DBC for runtime queries, per ADR-003).
- **Keycloak 24.0.4** — realm `zte-realm` auto-imported from
  `keycloak/realm-export.json`; client `zte-gateway`; roles `ADMIN`/`USER`;
  users `zte-admin`/`zte-test-user`. Password set post-start via
  `scripts/set-keycloak-password.sh` (import can't carry plaintext
  credentials). See [ADR-002](adr/ADR-002-identity-provider-configuration-strategy.md).
- **Certs** — `certs/generate-certs.sh` builds a one-off ZTE-CA and issues
  `client.p12` (shared by gateway + service-a as outbound client cert),
  `service-a.p12`/`service-b.p12` (server certs), `truststore.p12` (CA-only
  trust anchor). See [ADR-004](adr/ADR-004-mtls-implementation.md).

---

## 6. Data Model

`access_policies` (PostgreSQL, Flyway `V2__access_policies.sql`):

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGSERIAL` | PK |
| `role_name` | `VARCHAR` | Matched against JWT `realm_access.roles` |
| `path_pattern` | `VARCHAR` | Ant-style path pattern |
| `methods` | `VARCHAR` | Comma-separated HTTP methods, or `*` |
| `enabled` | `BOOLEAN` | Soft-disable without deleting the row |

Seed data: `ADMIN` → `/api/v1/service-a/**` and `/api/v1/service-b/**`
(`GET`, `POST`). Evaluated by `PolicyService.isAllowed` — role AND path
(Ant-matched) AND method must all match at least one enabled row. Now the
users2service *fallback* when no YAML rule matches (ADR-011) — see below.

`zte-policies.yaml` (file, not a DB table — ADR-011): one `PolicyDocument`
with `schemaVersion` (int, must be `1`) and three rule lists —
`users2service`, `service2service`, `agentMcpToolCalls`. Every rule shares one
shape (`PolicyRule`):

| Field | Type | Notes |
|---|---|---|
| `id` | string | unique across the whole document |
| `effect` | `ALLOW` \| `DENY` | deny always overrides allow |
| `source` | string (Ant pattern) | caller identity: role name, or service/agent client id |
| `target` | string (Ant pattern) | service name, or MCP tool name |
| `pathPattern` | string (Ant pattern), nullable | unused by `agentMcpToolCalls` |
| `methods` | string, nullable | comma-separated verbs or `*`; unused by `agentMcpToolCalls` |
| `priority` | int, default `0` | tie-break within the same effect only |

Full schema/precedence/validation reference: `docs/policy-schema.md`.

---

## 7. API Reference

| Endpoint | Method | Auth | Service | Purpose |
|---|---|---|---|---|
| `/api/v1/service-a/**` | any | JWT + DB policy | gateway → service-a | Proxied REST call |
| `/api/v1/service-b/**` | any | JWT + DB policy | gateway → service-b | Proxied REST call |
| `/api/v1/internal/policies` | GET | none (network perimeter only) | gateway | Feeds `zt-agents` (ADR-007) |
| `/api/v1/internal/policies/reload` | POST | none (network perimeter only) | gateway | No-downtime YAML policy reload (ADR-011) |
| `/sse` | GET | JWT | gateway (MCP proxy) | Opens an MCP session; SSE stream |
| `/message?sessionId=<id>` | POST | JWT | gateway (MCP proxy) | JSON-RPC `tools/call`; result via SSE, not the response body |
| `/api/v1/service-a/hello` | GET | JWT + DB policy | service-a | Demo endpoint, calls service-b |
| `/api/v1/agents/auditor/run` | POST | none (local demo) | zt-agents | Runs the Policy Auditor, returns Markdown report |
| `/actuator/health/**` | GET | public | all services | Liveness/readiness |
| `/realms/zte-realm/protocol/openid-connect/token` | POST | client creds | Keycloak | Token issuance |

---

## 8. MCP Proxy — Detail

The most recently added component (Stage 8); given as its own section since
`docs/adr/ADR-009` covers the *decision* but not the operational shape.

### 8.1 Why a separate router, not a Gateway route

Spring Cloud Gateway's `GlobalFilter`/`RouteLocator` model proxies one request
to one response. MCP's HTTP+SSE transport needs a `POST /message` to inject
its result into an *already open*, separate `GET /sse` connection — no hook
in Gateway's model can reach across two independent exchanges like that. The
proxy is therefore a plain WebFlux `RouterFunction` (`McpRouterConfig`),
coexisting with Gateway routing on non-overlapping paths.

### 8.2 Request flow

1. `GET /sse` → `McpProxyHandler.handleSse`: extracts `agent_id` from JWT
   `sub`, generates a `sessionId` (UUID), registers it with
   `McpSessionManager`, pushes an `endpoint` SSE event
   (`data: /message?sessionId=<id>`) — the standard MCP HTTP+SSE handshake.
2. `POST /message?sessionId=<id>` → `handleMessage`: parses the JSON-RPC
   `tools/call` body (`JsonRpcRequest.toolName()` / `.toolArguments()`),
   calls `McpPolicyEngine.evaluate(agentId, toolName, arguments)` — a plain
   synchronous method call, no I/O.
3. **Deny:** `JsonRpcResponse.denied(id, reason)` — a JSON-RPC **success**
   envelope with `result.isError = true` (matches MCP's own `tools/call`
   error convention) — injected into the session via
   `McpSessionManager.emit`. `McpBackendClient` is never invoked.
4. **Allow:** `McpBackendClient.forward(rpc)` calls `mcp-backend.uri`
   (`MCP_BACKEND_URI`, default `http://localhost:9090`), the result passes
   through `DataMaskingFilter` (currently `NoOpDataMaskingFilter` — a stub),
   then is injected the same way.
5. Either path calls `McpAuditService.record(...)` — `LoggingMcpAuditService`
   does a non-blocking `Sinks.Many.tryEmitNext`, drained by one subscriber on
   `Schedulers.boundedElastic()`. Logs today; swapping `persist()` for an
   InfluxDB line-protocol write is the only change needed for a real TSDB.
   Also logged synchronously via `ZteAuditLogger.policyDecision(...)`
   (ADR-011), the same call used by the REST-path filters.
6. `POST /message` always returns `202 Accepted` — the real answer only ever
   arrives over SSE.

### 8.3 Current policy logic

`YamlMcpPolicyEngine` (ADR-011, replacing the Stage 8 `DummyMcpPolicyEngine`
placeholder) — real per-agent authorization: matches `(agentId, toolName)`
against the `agentMcpToolCalls` rules in `zte-policies.yaml` via the shared
`PolicyMatcher` (deny always overrides allow); no match resolves to
`zte.policy.default-effect` (default `DENY`). `evaluate()` stays synchronous
and zero-I/O per ADR-009 §8.2 — it reads `PolicyDefinitionStore`'s
`AtomicReference` snapshot, never fetches inline. The default shipped rule set
denies destructive-shaped tool names (`delete*`, `drop*`, `export_all_data`)
for every agent, plus explicit grants for Agent A (`get_deals`) and Agent B
(`update_deal_stage`) — see `zte-policies.yaml`'s header comment for why it's
this conservative by default.

### 8.4 Testing

`McpProxyIT` (`gateway-service/src/it`) exercises the full round trip against
the real running gateway (Testcontainers Postgres + Keycloak, WireMock
standing in for the MCP backend, same pattern as `HappyPathIT`): opens
`GET /sse`, extracts the `sessionId` from the `endpoint` handshake event, fires
`POST /message`, and asserts the result lands on the still-open SSE stream —
covering an allowed call (forwarded to WireMock, result relayed), a tool with
no grant, a destructive-shaped tool caught by the deny-list (both: backend
never called), no-token 401, and an unknown-`sessionId` 400.
`McpProxySecurityWebFluxTest` (`src/test`) covers the same allow/deny paths as
a fast `@WebFluxTest` slice without Docker. `McpSessionManagerTest` and
`YamlMcpPolicyEngineTest` (unit) continue to cover those two components in
isolation.

### 8.5 Gaps (carried from ADR-009 + implementation-time critique)

- `McpBackendClient` assumes one JSON response per call; a backend that
  itself streams multiple events per tool call isn't relayed incrementally.
- `McpSessionManager`'s session map is in-memory, single-instance — doesn't
  survive a restart or work across multiple gateway replicas without sticky
  routing or a shared store.
- `McpBackendClient` has no auth toward the backend (no bearer token, no
  mTLS) — inconsistent with the mTLS-secured service-a/b calls.
- `LoggingMcpAuditService`'s buffer is unbounded — a stuck downstream writer
  grows memory without limit.

---

## 9. Roadmap

### 9.1 Completed (see §2 for commits/ADRs)

Stages 1–10, plus the two undated additions (pre-commit doc automation,
`.env` config) — all ✅.

### 9.2 Backlog — general (from `CLAUDE.md` Stage 11+)

- [ ] Full users2service migration to YAML-only, retiring `access_policies`/
      `PolicyService` — deliberate future ADR, not attempted in Stage 10 (see
      [ADR-011](adr/ADR-011-yaml-policy-engine.md) Future Migration Path).
- [ ] Per-category `zte.policy.*.default-effect` overrides (today one
      `default-effect` applies to service2service and agentMcpToolCalls
      alike).
- [ ] Filesystem watch-based auto-reload, layered on
      `PolicyDefinitionStore.reload()` (today: explicit `POST
      /api/v1/internal/policies/reload`).
- [ ] DB-based request audit log (`request_logs` table, V3 Flyway migration)
      — currently log-only via `RequestAuditFilter`.
- [ ] Distributed tracing: Micrometer Tracing + Zipkin in Docker Compose.
- [ ] Rate limiting: Spring Cloud Gateway `RequestRateLimiter` (Redis-backed).
- [ ] Docker Compose production profile: resource limits, health-check
      restart policies.
- [ ] ABAC extension: `condition` field on `PolicyRule`/`access_policies`
      (SpEL against JWT claims).
- [ ] Full mTLS system test: service-a + service-b as real Testcontainers
      (covers TLS handshake rejection — current gap per ADR-005 §"mTLS
      Testing Gap").

### 9.3 Backlog — MCP proxy hardening (from §8.5)

- [x] Integration test: full `GET /sse` → `POST /message` → SSE-injection
      round trip — `McpProxyIT` (see §8.4).
- [x] Per-agent authorization in `McpPolicyEngine` — `YamlMcpPolicyEngine`
      (ADR-011, Stage 10), replacing the static deny-list.
- [ ] Bounded buffer + overflow policy for `LoggingMcpAuditService`.
- [ ] Authenticate `McpBackendClient` → backend (bearer token or mTLS,
      matching the service-a/b posture).
- [ ] Shared/sticky session store for `McpSessionManager` if the gateway is
      ever run with >1 replica.
- [ ] Incremental relay for backends that stream multiple SSE events per
      tool call, instead of buffering to one `Mono<JsonRpcResponse>`.
- [ ] Real `DataMaskingFilter` implementation (PII masking rules undefined
      today — `NoOpDataMaskingFilter` is a pass-through stub).
- [ ] Real InfluxDB (or equivalent TSDB) writer behind `McpAuditService`,
      replacing `LoggingMcpAuditService.persist()`'s log line.

### 9.4 Deferred production-path items (surfaced across ADRs, not yet backlog items)

- RS256 (asymmetric) OBO tokens instead of shared-secret HMAC — deferred in
  [ADR-004](adr/ADR-004-mtls-implementation.md).
- Per-service mTLS client certs (SPIFFE/SVID) instead of one shared
  `client.p12` — deferred in ADR-004.
- Keycloak Config CLI or Terraform for multi-environment identity config —
  deferred in [ADR-002](adr/ADR-002-identity-provider-configuration-strategy.md).
- Service mesh (Istio/Linkerd) evaluation once the service count exceeds ~5
  or Kubernetes is adopted — deferred in [ADR-001](adr/ADR-001-architecture-pattern-gateway-vs-sidecar.md).
- JWT-authenticated `/api/v1/internal/policies` (currently network-perimeter
  only) — noted in [ADR-007](adr/ADR-007-policy-auditor-agent.md).

---

## 10. Known Risks (consolidated from ADR self-critique sections)

| Severity | Risk | Source | Mitigation status |
|---|---|---|---|
| High | Gateway could become a "God Service" if business logic creeps in | ADR-001 | Convention only — no enforcement mechanism yet |
| High (prod) | Keycloak client secret (`zte-gateway-secret`) hardcoded in `realm-export.json` | ADR-002 | Dev-only; must be env/secret-manager-injected before staging |
| Medium | Shared HMAC secret for OBO tokens | ADR-004 | `ZTE_OBO_SECRET` env var; RS256 upgrade deferred (§9.4) |
| Medium | Server-side TLS cert rotation requires a restart (no hot-reload API) | ADR-004 | 1-year dev certs; production needs cert-manager + rolling restart |
| Medium | 5-minute policy cache window — a revoked-role JWT still works until refresh (users2service DB fallback only) | ADR-003 | Accepted for MVP; a YAML DENY rule takes effect immediately via reload (ADR-011), bypassing the DB cache entirely |
| Medium | Two sources of truth for users2service (YAML + DB) | ADR-011 | YAML only short-circuits on an *explicit* match; shipped default YAML has an empty `users2service` list, so today's DB-only behavior is unchanged until an operator adds YAML rules. Every decision audit-logged with its category. |
| Medium | mTLS transport-layer enforcement untested in the integration suite (WireMock has no TLS) | ADR-005 | Full mTLS Testcontainers system test is backlog (§9.2) |
| Medium | MCP session state in-memory, single-instance | ADR-009 / §8.5 | Documented; needs sticky routing or shared store before scaling out |
| Low | `POST /api/v1/internal/policies/reload` has no auth beyond network-perimeter isolation, same posture as `InternalPolicyController` | ADR-011 | Acceptable for MVP (Docker-bridge only, not proxied externally); noted alongside ADR-007's existing production-upgrade path for the internal endpoints |
| Low | `LoggingMcpAuditService` buffer is unbounded | §8.5 | Backlog item §9.3 |
| Low | All DB policies held in one in-memory `Mono` — fine at MVP scale (<100 rows) | ADR-003 | Revisit if the policy table grows large |
| Low | `PolicyMatcher` is a full linear scan per category per request | ADR-011 | Same `<100 rules` MVP scale ceiling as `access_policies`; negligible at that scale |

---

## 11. ADR Index

| ADR | Title |
|---|---|
| [001](adr/ADR-001-architecture-pattern-gateway-vs-sidecar.md) | Architecture Pattern — Gateway vs Sidecar |
| [002](adr/ADR-002-identity-provider-configuration-strategy.md) | Identity Provider Configuration Strategy |
| [003](adr/ADR-003-reactive-policy-engine.md) | Reactive Policy Engine — R2DBC + In-Process Cache |
| [004](adr/ADR-004-mtls-implementation.md) | mTLS Implementation and On-Behalf-Of User Context Delegation |
| [005](adr/ADR-005-integration-testing-strategy.md) | Integration Testing Strategy — Testcontainers + WireMock |
| [006](adr/ADR-006-pre-commit-documentation-automation.md) | Pre-Commit Documentation Automation |
| [007](adr/ADR-007-policy-auditor-agent.md) | Policy Auditor Agent |
| [008](adr/ADR-008-dotenv-configuration-management.md) | `.env`-Based Configuration Management |
| [009](adr/ADR-009-mcp-proxy-interception-layer.md) | MCP Proxy & Interception Layer |
| [010](adr/ADR-010-agent-oauth2-client-credentials.md) | Agent Auth via OAuth2 Client Credentials, and a Deliberate Dead-End Stub |
| [011](adr/ADR-011-yaml-policy-engine.md) | YAML-Defined Access Policies (users2service / service2service / agent@mcp) |

---

*This document reflects repo state at commit `d76c709` (Stage 10, YAML Policy Engine). Keep it in sync the
same way as README/CLAUDE.md — per CLAUDE.md's mandatory workflow, update it
alongside any task that completes a stage or changes the roadmap.*
