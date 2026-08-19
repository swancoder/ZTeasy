# ZTeasy — Technical Specification & Implementation Roadmap

**Product:** Lightweight Zero Trust Environment (ZTE) MVP — a Zero Trust Data
Gateway fronting both plain REST traffic and AI agent (MCP) traffic under one
policy engine and one audit trail.

This document describes the system **as it currently behaves** — what
exists, how it fits together, what's configurable, what's tested, and what's
left. It deliberately does not re-narrate *how* each decision was reached or
*how* each bug was found — that's what the ADRs and `prompts-hist/` are for
(§11 links every one). If you're extending this system, read §8
("Conventions for Future Development") first — it distills the patterns this
codebase has converged on, so new work matches the existing shape instead of
inventing a parallel one.

---

## 1. Scope & Non-Goals

**In scope:** a runnable, all-Java(/Kotlin) Zero Trust stack demonstrating
four explicit trust checks end-to-end (user identity, authorization, service
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

| # | Stage | ADR |
|---|---|---|
| 1 | Infrastructure bootstrap (Gradle, Docker Compose, gateway skeleton) | [001](adr/ADR-001-architecture-pattern-gateway-vs-sidecar.md) |
| 2 | Identity provider (Keycloak realm auto-import) | [002](adr/ADR-002-identity-provider-configuration-strategy.md) |
| 3 | DB-based policy enforcement (R2DBC + cache) — later fully replaced by YAML, see Stage 11 | [003](adr/ADR-003-reactive-policy-engine.md) |
| 4 | mTLS + On-Behalf-Of delegation | [004](adr/ADR-004-mtls-implementation.md) |
| 5 | Unit tests (filters, auth-library) | — |
| 6 | E2E integration tests (Testcontainers + WireMock) | [005](adr/ADR-005-integration-testing-strategy.md) |
| — | Pre-commit documentation automation | [006](adr/ADR-006-pre-commit-documentation-automation.md) |
| 7 | AI Security Copilot (`zt-agents`, Policy Auditor) | [007](adr/ADR-007-policy-auditor-agent.md) |
| — | `.env`-based config for `zt-agents` | [008](adr/ADR-008-dotenv-configuration-management.md) |
| 8 | MCP Proxy & Interception Layer | [009](adr/ADR-009-mcp-proxy-interception-layer.md) |
| 9 | Agent OAuth2 Client Credentials Auth (dead-end stub) — superseded, see Stage 10 | [010](adr/ADR-010-agent-oauth2-client-credentials.md) |
| 10 | YAML Policy Engine (users2service / service2service / agentMcpToolCalls), no-downtime reload, real MCP enforcement | [011](adr/ADR-011-yaml-policy-engine.md) |
| 11 | Full YAML migration (DB policy store retired) + React Admin Console | [012](adr/ADR-012-full-yaml-migration-and-admin-console.md) |
| 12 | R2DBC `request_logs` audit trail, `X-Request-Id` tracing, Admin Console "Audit Trail" tab | [013](adr/ADR-013-postgres-audit-logging.md) |
| 13 | IdP identity sync, URN-based `users2service` sources, Admin Console "Identities" tab | [014](adr/ADR-014-idp-identity-sync.md) |
| 14 | Machine identities (OIDC clients), URN unification across all three policy categories | [015](adr/ADR-015-machine-identities-and-urn-unification.md) |
| 15 | Identities UI refactor (Actors/Access Containers), relations caching | [Identities UI + Relations](adr/identities-ui-actors-containers-and-relations-caching.md) |
| 16 | APIM inventory registry, auto-discovery, health polling, Admin Console "Registry" tab | [016](adr/ADR-016-inventory-and-health-registry.md) |
| 17 | Dynamic inventory-driven routing, REST/MCP audit unification, strict `service2service` scenario | [017](adr/ADR-017-dynamic-routing-and-audit.md) |
| 18 | Smart mTLS enforcement — gateway HTTPS, `client-auth: want`, `MtlsEnforcementWebFilter` | [018](adr/ADR-018-smart-mtls-enforcement.md) |
| — | Audit row enrichment (closes ADR-017's own duplicate-row gap): `SSE_OPENED` events, shared `ClientIpResolver`, readable display identity, named MCP target, captured call arguments | ADR-017 amendment, see §5.5 |
| 19 | HOLD decision outcome + durable approval queue (ACAP/DIGI-KAI governance demo, Stage 1 of 6) — Admin Console "Approvals" tab, new `crm-account-health-emea-01` demo agent | [019](adr/ADR-019-hold-decision-and-approval-queue.md) |
| — | Honest-deny/hold verification (ACAP demo Stage 2 of 6): confirmed deny/hold reasons are always specific, reconciled with MCP's always-202 transport, fixed two Admin Console chip-coloring gaps that painted a held (202) call green | ADR-019 amendment, see §5.4 |
| 20 | ACAP scope profiles (ACAP demo Stage 3 of 6): per-agent argument/field-level policy tightening — territory, data-minimization fields, read-only write-deny, bulk/export deny; new `crm-account-health-emea-01` demo profile | [020](adr/ADR-020-acap-scope-profiles.md) |
| 21 | Governance dashboard (ACAP demo Stage 4 of 6): per-agent ALLOW/HOLD/DENY activity + out-of-policy-attempts feed + JSON export, Admin Console "Governance" tab — read-only reporting over the existing `request_logs` audit trail, no new table | [021](adr/ADR-021-governance-dashboard.md) |
| 22 | CRM tool-surface alignment (ACAP demo Stage 5 of 6, in the sibling `hubspot-mcp` repo, not this one — no ADR here): `read_contacts`/`read_deals`/`read_activities`/`update_deal`/`export_contacts`/`send_email`/`draft_followup`/`escalate`, matching `demo-case-A-crm-hubspot.pdf`'s exact tool names/arguments, added alongside (not replacing) agent-a/b's existing tools; `agent_simulator.py` extended to run the full 🟢/🔴/🟡 script for `crm-account-health-emea-01` | see `hubspot-mcp/README.md` |
| 23 | ACAP agent metadata + usage thresholds (ACAP demo Stage 6 of 6, final stage): `AcapProfile.agent`/`risk` (display-only, Admin Console "Governance" tab's new ACAP Profiles section), `thresholds` + `AcapThresholdTracker` (in-memory, daily-reset per-agent-per-metric counter that can escalate ALLOW to HOLD) | [022](adr/ADR-022-acap-agent-metadata-and-thresholds.md) |
| 24 | `mcpTarget` — scopes `agentMcpToolCalls`/`agentMcpToolHolds` rules to a specific MCP backend (matched against `mcp-backend.name`), so a rule authored against one backend's tool semantics can't silently keep matching if the gateway is repointed at a different one | [023](adr/ADR-023-policy-rule-mcp-target.md) |
| 25+ | Backlog (rate limiting, ABAC…) | see §9 |

**Testing:** `./gradlew test` (unit — every package below has direct
coverage for its pure decision logic; I/O-calling code that has no
dedicated mocked-HTTP unit test is proven by the IT suite instead, a
deliberate and repeated choice, not a gap — see §8) and `./gradlew
integrationTest` (Testcontainers Postgres + Keycloak, WireMock standing in
for downstream/backend targets) are both green. See individual ADRs for how
specific regressions were found and fixed during development.

---

## 3. System Architecture

### 3.1 Component topology

```
                              ┌───────────────────────────────────────────┐
                              │              gateway-service               │
                              │      port 8080 (HTTPS, client-auth: want)  │
  User ──[Keycloak JWT]──────►│  SecurityConfig (auth-library): JWT req'd  │
                              │                                             │
                              │  ⓪ MtlsEnforcementWebFilter (ADR-018):     │
                              │     client cert required on /sse, /message,│
                              │     /api/v1/** — except /admin, /internal  │
                              │                                             │
                              │  REST path (ADR-011/ADR-012/ADR-017):      │
                              │   ① ZteAuthorizationFilter (users2service) │
                              │   ① a ServiceToServiceAuthorizationFilter  │
                              │      (service2service)                    │
                              │   ② UserContextPropagationFilter (OBO)     │
                              │   ③ InventoryRouteDefinitionLocator        │
                              │      → any registered REST service        │
                              │                                             │
                              │  policy/def: PolicyDefinitionStore         │
                              │   AtomicRef<PolicyDocument>, loaded from   │
                              │   zte-policies.yaml, hot-swappable via     │
                              │   POST /api/v1/internal/policies/reload    │
                              │                                             │
                              │  MCP path (ADR-009/010/011/017 amendment): │
                              │   GET /sse ──► McpSessionManager           │
                              │     + SSE_OPENED audit event               │
                              │   POST /message ──► McpProxyHandler        │
                              │     → YamlMcpPolicyEngine.evaluate()       │
                              │       deny via SSE, allow forwards to      │
                              │       McpBackendClient                     │
                              │                                             │
                              │  Admin path (ADR-012/013):                 │
                              │   GET/POST /api/v1/admin/**                │
                              │     → AdminAuthorizationFilter (WebFilter) │
                              │   GET /admin/** (static) ──► zt-admin-ui   │
                              │                                             │
                              │  Audit path (ADR-013, enriched ADR-017):   │
                              │   RequestAuditFilter (WebFilter) → doFinally│
                              │     → skip if excluded → else              │
                              │     RequestLogAuditService (async sink)    │
                              │     → request_logs (R2DBC) — one unified   │
                              │       table for REST and MCP traffic       │
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

Infrastructure: PostgreSQL 16 (`5432`, JDBC/Flyway for migrations + R2DBC for
the async `request_logs` write path — `request_logs` is the only table on
the runtime query/write path), Keycloak 24.0.4 (`8180`, realm `zte-realm`,
native `--import-realm`). See `docker-compose.yml`.

### 3.2 Module map

| Module | Language | Responsibility | Port(s) |
|---|---|---|---|
| `auth-library` | Java 21 | Shared security: `SecurityConfig`, `ZteAuditLogger`, `ReloadableSslContextFactory`, `UserContextTokenService` | — (library) |
| `gateway-service` | Java 21 | ZT entry point: JWT validation, YAML policy enforcement, OBO issuance, mTLS (inbound + outbound), MCP proxy, Admin Console API + static hosting, unified async audit trail | 8080 |
| `service-a` | Java 21 (WebFlux) | First protected downstream; calls service-b | 8081 (mTLS), 9081 (mgmt) |
| `service-b` | Java 21 (WebFlux) | Deepest downstream; validates OBO token | 8082 (mTLS), 9082 (mgmt) |
| `zt-agents` | Kotlin (WebFlux) | AI security copilot — Policy Auditor Agent | 8083 |
| `zt-admin-ui` | TypeScript (Vite/React) | Admin Console SPA — built and packaged by `gateway-service`'s Gradle build, not run standalone | — (served by 8080) |
| `certs` | shell | Dev cert generation (ZTE-CA, PKCS12 + PEM) | — |
| `scripts` | shell | Keycloak password bootstrap, git hook install | — |
| `docs/adr` | Markdown | Architectural Decision Records | — |

---

## 4. Zero Trust Model

Every request answers a subset of these questions, depending on caller type:

| # | Question | Mechanism | Where enforced |
|---|---|---|---|
| 1 | Who is the user? | Keycloak JWT (RS256) | `SecurityConfig` (`auth-library`), all services |
| 2 | Is the user allowed? | YAML `users2service` rules | `ZteAuthorizationFilter` (routed paths); `AdminAuthorizationFilter` (`/api/v1/admin/**`) |
| 3 | Who is the internal caller? | mTLS client cert (ZTE-CA) | `MtlsHttpClientConfig` + service HTTPS listeners (outbound, always); `MtlsEnforcementWebFilter` (inbound to the gateway itself, `/sse`/`/message`/`/api/v1/**` minus `/admin`/`/internal` — presence only, at the app layer, since `server.ssl.client-auth: want`) |
| 4 | On whose behalf? | Signed OBO token (`X-ZTE-User-Context`, HMAC-SHA256, 30s TTL) | `UserContextPropagationFilter` → `UserContextTokenService` |
| 5 | Is this MCP agent who it claims, and what may it do? | OAuth2 Client Credentials + `agentMcpToolCalls` YAML rules | `McpProxyHandler` + `YamlMcpPolicyEngine` |
| 6 | May this service call that service? | JWT `azp` + `service2service` YAML rules | `ServiceToServiceAuthorizationFilter` |
| 7 | May this admin see/change the policy set? | Keycloak JWT + `users2service` `ADMIN` rule | `AdminAuthorizationFilter` |

For the full request-by-request trust narrative (User → Gateway → Service A
→ Service B) see `README.md`'s "Chain of Trust" section.

---

## 5. Component Reference

### 5.1 `auth-library`

Reusable security config imported by every service.

- **`SecurityConfig`** — `anyExchange().authenticated()` except
  `/actuator/health/**`; JWT via `oauth2ResourceServer`. Deny-by-default.
- **`ReloadableSslContextFactory`** — client-side `SslContext`, hot-reloaded
  on keystore file mtime change (§8's async pattern doesn't apply here — see
  its own note on why server-side certs can't hot-reload). See ADR-004.
- **`UserContextTokenService`** — creates/validates the OBO token
  (HMAC-SHA256, `sub`+`roles`, `iss: zte-gateway`, 30s TTL).
- **`ZteAuditLogger`** — static structured `[ZTE-AUDIT]` log lines
  (grep/tail-grade only — contrast with the async, DB-backed audit trail in
  §5.5).

### 5.2 `gateway-service` — REST/Admin path (filter order)

1. **`MtlsEnforcementWebFilter`** (`filter`, `HIGHEST_PRECEDENCE+50`, ADR-018)
   — `401` on a missing/empty peer-cert array for `/sse`, `/message`,
   `/api/v1/**` except `/api/v1/admin/`/`/api/v1/internal/`. Runs before
   Spring Security's own JWT check. Gated by `zte.mtls.enabled`.
2. Spring Security's `WebFilterChainProxy` (JWT validation, from
   `auth-library`).
3. **`ZteAuthorizationFilter`** (`GlobalFilter`, `HIGHEST_PRECEDENCE+100`) —
   `users2service` enforcement for interactive-user traffic; a service/agent
   `azp` passes through untouched to the next filter. **Only fires for
   Gateway-routed requests** — see §8's `GlobalFilter` caveat.
4. **`ServiceToServiceAuthorizationFilter`** (`HIGHEST_PRECEDENCE+150`) —
   `service2service` enforcement for the machine-credential traffic the
   previous filter passed through.
5. **`UserContextPropagationFilter`** (`HIGHEST_PRECEDENCE+200`) — strips
   client-supplied `X-ZTE-User-Context`/`X-User-Id`, mints the OBO token from
   the raw JWT `sub` (always the raw UUID — see §5.5 for the separate,
   display-only identity shown in the audit trail).
6. **`InventoryRouteDefinitionLocator`** — routes `/api/v1/{name}/**` to any
   `REST`-type, `ACTIVE`/`WARNING` entry in the APIM registry (§5.7); no
   hardcoded routes. `InventoryBootstrapSeeder` seeds `service-a`/`service-b`
   at startup so a fresh `docker compose up` still routes them.
7. **`RequestAuditFilter`** (plain `WebFilter`, `LOWEST_PRECEDENCE-100`) —
   resolves/forwards `X-Request-Id`, strips/injects trusted `X-User-Id`, and
   — via `doFinally`, for paths outside `zte.audit.excluded-path-prefixes`
   — writes the unified audit row (§5.5). Also feeds `HealthTelemetryService`
   on any 2xx routed response, unconditionally of the exclusion list.

Admin-facing controllers/config (all `admin` package, ADR-012, JWT + `ADMIN`
YAML rule via `AdminAuthorizationFilter`, since they're local
`@RestController`s a `GlobalFilter` never reaches):

- **`AdminPolicyController`** — `GET`/`POST /api/v1/admin/policies[/reload]`.
- **`AdminAuditLogController`** — `GET /api/v1/admin/audit-logs` (latest 100).
- **`AdminIdentitySyncController`** / **`AdminIdentitySearchController`** /
  **`AdminIdentityRelationsController`** — manual sync, search, relations.
- **`AdminInventoryController`** — CRUD + schema fetch for the APIM registry.
- **`AdminUiConfig`** — permits `/admin/**` (the static SPA bundle, a
  different prefix from the API above) and serves it from
  `classpath:/static/admin/`.

Internal (network-perimeter-only, no JWT, `InternalSecurityConfig`):
**`InternalPolicyController`** (`GET /api/v1/internal/policies`, feeds
`zt-agents`) and **`PolicyReloadController`** (`POST
/api/v1/internal/policies/reload`).

### 5.3 YAML Policy Engine

One file (`zte-policies.yaml`, path via `zte.policy.file`) is the sole
runtime source of truth for all four rule categories. Full schema,
precedence, and validation reference: `docs/policy-schema.md`; worked
example: `docs/examples/zte-policies-example.yaml` (kept honest by
`DocumentationExampleConformanceTest`). A per-agent `AcapProfile` (Stage 3,
ADR-020 — see §5.4) is a separate, additive enrichment on top of this file,
not a fifth category within it.

| Category | Governs | Enforced by |
|---|---|---|
| `users2service` | User (realm role) → gateway REST service | `ZteAuthorizationFilter`, `AdminAuthorizationFilter` |
| `service2service` | Calling service/agent (`azp`) → gateway REST service | `ServiceToServiceAuthorizationFilter` |
| `agentMcpToolCalls` | MCP agent (`azp`) → MCP tool name | `YamlMcpPolicyEngine` |
| `agentMcpToolHolds` (ADR-019) | MCP tool calls routed to a human even when `agentMcpToolCalls` would ALLOW them | `YamlMcpPolicyEngine`, matched via `PolicyMatcher.matchAny` (not `evaluate` — see ADR-019 for why this is a separate list rather than a third `RuleEffect`) |

One `PolicyRule` shape (`id`/`effect`/`source`/`target`/`pathPattern`/
`methods`/`priority`/`mcpTarget`) is reused across all four — `pathPattern`/
`methods` are simply unused by `agentMcpToolCalls`/`agentMcpToolHolds`,
`effect` is unused (conventionally `ALLOW`) by `agentMcpToolHolds`, and
conversely `mcpTarget` (ADR-023) is unused by `users2service`/
`service2service` — when set on an MCP-category rule, it must match the
configured `mcp-backend.name` (exact string, not an Ant pattern) or the rule
doesn't apply; unset matches any backend, same "unscoped means universal"
convention as `pathPattern`/`methods`. `PolicyDefinitionStore` holds an
`AtomicReference<PolicyDocument>`, hot-swappable via
`POST /api/v1/internal/policies/reload` (or the ADMIN-gated counterpart);
`PolicyMatcher` — deny always overrides allow, `AntPathMatcher`-based, no
match falls to `zte.policy.default-effect` (default `DENY`).

**Identity URNs** — every source additionally accepts `user:`/`group:`/
`role:` (`users2service`) or `client:` (`service2service`/
`agentMcpToolCalls`) prefixed forms, parsed by `IdentityUrn.parse(source,
defaultType)`; an unprefixed source means the category's implicit default
type (`ROLE` for `users2service`, `CLIENT` for the other two — fully
backward compatible with pre-URN rules). `IdentitySources.enrich`/
`enrichClient` build the enriched source list every filter above passes to
`PolicyMatcher`.

### 5.4 MCP Proxy

A plain WebFlux `RouterFunction` (`McpRouterConfig`), not a Gateway route —
Spring Cloud Gateway's model proxies one request to one response, but MCP's
HTTP+SSE transport needs `POST /message` to inject its result into an
*already open*, separate `GET /sse` connection.

1. `GET /sse` → `McpProxyHandler.handleSse` — identity from JWT `azp`
   (falling back to `sub`), generates a `sessionId`, registers it with
   `McpSessionManager`, pushes the `endpoint` handshake event, records an
   `SSE_OPENED` audit event (§5.5).
2. `POST /message?sessionId=<id>` → `handleMessage` — parses the JSON-RPC
   `tools/call` body, calls `YamlMcpPolicyEngine.evaluate(agentId, toolName,
   arguments)` (synchronous, zero I/O — reads `PolicyDefinitionStore`'s
   snapshot).
3. **Deny** → a JSON-RPC success envelope with `result.isError = true`
   (matches MCP's own convention), injected via SSE; `McpBackendClient` never
   called.
4. **Hold** (ADR-019) → `PendingApprovalService.hold(...)` persists a
   `pending_approvals` row (R2DBC — durable, since review may happen well
   after the session closes) and a `status: "held"` envelope is injected via
   SSE; `McpBackendClient` never called yet. A human decides later via
   `POST /api/v1/admin/approvals/{id}/approve|reject` (`AdminApprovalsController`,
   Admin Console "Approvals" tab) — approve reconstructs and forwards the
   original call through the same `McpForwardService` the Allow path uses,
   reject synthesizes an honest denial; either way the decision is audited,
   and pushed back into the originating SSE session only if it's still open.
5. **Allow** → `McpForwardService.execute(rpc)` (wraps
   `McpBackendClient.forward` + `DataMaskingFilter`, currently a pass-through
   stub) — shared with the Hold-then-approve path above so masking can't
   drift between the two.
5a. **ACAP tightening (ADR-020, Stage 3)**: before returning any non-DENY
   decision from step 2, `YamlMcpPolicyEngine` checks whether the calling
   agent has an `AcapProfile` (`AcapProfileStore.find`); if so,
   `AcapScopeEvaluator.tighten` may further downgrade ALLOW/HOLD to DENY
   based on the call's *arguments* — territory, requested fields, write/bulk
   shape — something step 2's tool-name-only matching can't express.
   Additive and opt-in: an agent with no profile is unaffected; a DENY from
   step 2 is never reconsidered. See `docs/policy-schema.md`'s "ACAP scope
   profiles" section for the full check list. If tightening didn't already
   deny the call, `AcapScopeEvaluator.checkThresholds` (Stage 6, ADR-022)
   gets one more chance: a per-agent-per-metric usage limit (e.g.
   `followup_drafts_per_day`, tracked in-memory by `AcapThresholdTracker`
   with a daily reset) can escalate an ALLOW to HOLD once exceeded — never
   touches an existing HOLD/DENY, never invents an ALLOW.
6. Every path records an audit event (§5.5) — `HELD`/`APPROVED`/`REJECTED`
   join `ALLOWED`/`DENIED`/`SSE_OPENED` as of ADR-019, mapped to
   `decisionEffect` `HOLD`/`ALLOW`/`DENY` respectively. `POST /message`
   always returns `202 Accepted` — the real answer only ever arrives over SSE.

**"Honest deny," reconciled with MCP's always-202 transport (verified, Stage 2):**
the `202` on `POST /message` is MCP's own HTTP+SSE transport contract, not
something this gateway can (or should) change without breaking protocol
compliance — the real result always travels over SSE, by design, for every
caller. "Honest" doesn't mean "the raw HTTP status is 403"; it means a caller
inspecting the actual result can never mistake a deny/hold for a success.
Verified true at every layer that matters:
- The SSE envelope itself: `denied()` sets `result.isError = true` with a
  specific reason (§5.4 point 3) — an MCP client checking `isError` (the
  protocol's own convention for a failed tool call) sees a real failure, not
  a disguised success. `held()` (point 4) is a third, distinct shape
  (`status: "held"`) — not `isError`, since a hold isn't a policy failure
  either.
- Every deny reason is concrete, never generic filler: `YamlMcpPolicyEngine`
  always names the matched rule id or the specific missing grant (see that
  class) — checked directly, not assumed, while auditing this stage.
- The audit trail (§5.5, `request_logs.decision_effect`/`status_code`) gives
  the ACAP-style "as if it had an HTTP status" view (`403`/`202`/`200` for
  DENY/HOLD/ALLOW) for compliance reporting, entirely independent of what the
  real transport-level HTTP response code was — this is where a `403`
  actually shows up, deliberately not on the wire.
- Admin Console honesty gap found and fixed this stage: `AuditTrail.tsx`'s
  Status chip previously painted every `< 400` status green, including a
  held call's `202` — indistinguishable from a real success at a glance.
  Now `HOLD` gets its own (amber) color, independent of the raw status code
  threshold; the Decision chip's `HOLD`/`ERROR` mapping was likewise made
  explicit instead of falling into one shared "warning" bucket. Similarly,
  `PolicyDashboard.tsx`'s `agentMcpToolHolds` rows previously displayed their
  (unused-by-convention, always-`ALLOW`) `effect` field literally — reading
  as "this rule ALLOWs" for a category that exists to hold, not allow; now
  overridden to display `HOLD`.

**Gaps:** `McpBackendClient` assumes one JSON response per call (no
incremental relay for a streaming backend); it has no auth toward the
backend at all (separate from, and unaffected by, ADR-018's *inbound*
enforcement); `McpSessionManager`'s session map is in-memory,
single-instance; `LoggingMcpAuditService`'s buffer is unbounded. A held
call's decision is never pushed back to the agent if its SSE session has
since closed — see ADR-019's Self-Criticism.

### 5.5 Unified Audit Trail

One table, `request_logs` (§6), fed by two independent write paths that
converge on the same async sink pattern (§8):

- **REST** — `RequestAuditFilter` → `RequestLogAuditService` →
  `RequestLog.forRest(...)`.
- **MCP** — `McpProxyHandler` records an `McpAuditEvent`
  (`SSE_OPENED`/`ALLOWED`/`DENIED`) → `LoggingMcpAuditService` →
  `RequestLog.forMcp(...)`.

Both paths extract the same HTTP context (`X-Request-Id`, client IP via the
shared **`ClientIpResolver`** — `X-Forwarded-For` first hop, else the raw
connection address with loopback normalized to `127.0.0.1` regardless of
which IP family a given connection happened to use — and `User-Agent`) from
whichever request actually triggered the event; for MCP that's `GET /sse`
for an `SSE_OPENED` row and `POST /message` for a tool-call row — different
connections, never reused for each other's row. `/sse` and `/message` are
in `zte.audit.excluded-path-prefixes` (alongside `/admin/`,
`/api/v1/admin/`, `/api/v1/internal/`, `/actuator/`) since the MCP-specific
row now carries everything a generic transport row would have —
`RequestAuditFilter`'s own trace-id generation/forwarding still runs
unconditionally regardless of this list, only the DB write is gated.

`originalUserObo` prefers the JWT's `preferred_username` claim over the raw
`sub` UUID, on both paths — display-only: `X-User-Id` and the actual OBO
token both still use the raw `sub`, read independently. `targetService` for
MCP rows is the operator-set `mcp-backend.name` (default `hubspot-mcp`) — a
config label, since `McpBackendClient` forwards to one fixed backend, not a
per-call registry lookup the way REST routing is. The MCP session id and the
`tools/call` request's own arguments (JSON, not masked — `DataMaskingFilter`
only applies to the backend's response) are folded into `message`
(`"Session: <id>[. <deny reason>][. Args: <json>]"`) rather than dedicated
columns; the Admin Console shows this as a tooltip on the Audit Trail's
Tool-name cell.

**Governance dashboard (Stage 4, ADR-021)** reads this same table, not a
separate one: `GovernanceService` aggregates MCP rows (`agent_id IS NOT
NULL`) in memory (no SQL `GROUP BY`, matching `InventoryService`'s own
join-in-Java precedent) into per-agent ALLOW/HOLD/DENY counts
(`GET /api/v1/admin/governance/agent-activity?hours=`) and a live
out-of-policy feed — `decisionEffect = "DENY"` rows, which already includes
a human's post-hold `REJECTED` decision for free since `LoggingMcpAuditService`
maps that to `DENY` too (ADR-019) — (`GET /api/v1/admin/governance/out-of-policy`).
`GET /api/v1/admin/governance/report` bundles both, unmodified, as a plain
JSON export (Admin Console's "Governance" tab, "Export Report" button).

### 5.6 IdP Identity Sync

`gateway-service/.../identity` — a local cache (`idp_identities`, §6) of
Keycloak users/groups/roles/clients, refreshed on a fixed interval
(`zte.idp.sync-interval-ms`, default 15 min) or on demand
(`POST /api/v1/admin/identities/sync`). Used by: `OrphanedRuleChecker`
(flags a policy rule whose `source` doesn't resolve to any cached identity —
warns only, never blocks), the Admin Console's Identities tab, and the
Policies tab's orphan cross-reference.

- **`KeycloakIdpAdapter`** (`IdpClient`'s only implementation) — reuses
  `zte-gateway`'s own service account (granted `realm-management`'s
  `view-users`/`view-realm`/`view-clients` roles in
  `keycloak/realm-export.json`). Excludes Keycloak's realm-builtin system
  clients (`account`, `broker`, `realm-management`, `admin-cli`,
  `security-admin-console` + satellites) from the synced client list.
- **`IdpIdentityRelation`** — many-to-many `MEMBER_OF`/`HAS_ROLE`
  relationships, resolved during the same sync cycle that upserts the
  identities they reference (no extra lookup query per relation), read
  locally by `GET /api/v1/admin/identities/{id}/relations` — no live
  Keycloak dependency on that read path, ever.

### 5.7 APIM Inventory Registry

`gateway-service/.../inventory` — a central registry (`inventory_services`/
`health_metrics`, §6) of REST services and MCP agents this gateway fronts,
onboarded via the Admin Console's "Registry" tab, auto-discovered, and
health-monitored.

- **`AutoDiscoveryWorker`** — on onboard/edit, probes `{base_url}/v3/api-docs`
  (REST, or a custom `docs_url` when set) or a stateless `tools/list`
  JSON-RPC call (MCP); captures the response body into
  `discovered_schema JSONB`, viewable in the Admin Console (Swagger UI for
  REST, a plain tool list for MCP) or synchronously re-triggered via
  `POST .../schema/fetch`. Success → `ACTIVE`; failure → `WARNING`, never a
  thrown exception. Uses the gateway's default `WebClient.Builder`, so it
  automatically presents the gateway's mTLS client cert to any `https://`
  target.
- **`HealthPollingService`** — pings every `ACTIVE`/`WARNING`/`DOWN` entry's
  `/actuator/health` (via `managementUrl` if set, else `baseUrl`) every
  `zte.inventory.health-poll-interval-ms` (default 60s). A successful ping
  flips `DOWN`→`ACTIVE`, a failed one flips `ACTIVE`→`DOWN`; `WARNING` is
  never touched here (a raw health ping doesn't confirm the service's actual
  API/tool contract). **This poll assumes a Spring Actuator-shaped response**
  (`{"status": "..."}` JSON at that exact path) — see §9's known risk for
  what that means for non-Spring backends.
- Routing is 100% driven by this registry (§5.2 step 6) — onboarding a REST
  service makes it immediately routable, no redeploy.

### 5.8 `service-a` / `service-b`

`service-a/HelloController` (`GET /api/v1/service-a/hello`) calls service-b
via mTLS, forwards `X-ZTE-User-Context` unchanged (delegation, not
re-issuance). `service-b/UserContextController` validates the OBO token's
HMAC signature + expiry. Both run WebFlux/Netty for the mTLS listener;
management endpoints are plain HTTP on separate ports (9081/9082) so Docker
health checks don't need a client cert.

### 5.9 `zt-agents` — Policy Auditor Agent

Kotlin/WebFlux, port 8083. `PolicyAuditorService`: fetch policies (via the
gateway's internal endpoint) → format → send to Claude (`AnthropicClient`) →
return a Markdown compliance report via `POST /api/v1/agents/auditor/run`.
Config loadable from `.env` (`spring-dotenv`), env vars take precedence.

### 5.10 `zt-admin-ui` — Admin Console

Vite + React + TypeScript SPA (Material UI), `react-oidc-context` for the
Keycloak login (client `zte-admin-ui`, authorization code + PKCE). Built by
`gateway-service`'s own Gradle build, packaged into that jar's
`static/admin/`; no client-side routing.

- **`PolicyDashboard.tsx`** — each of the three rule categories as its own
  `Accordion` (a rules table inside); flags rows whose `source` isn't in the
  synced identity cache. "Reload Policies" button.
- **`Identities.tsx`** — "Actors" (`USER`/`CLIENT`) and "Access Containers"
  (`GROUP`/`ROLE`) as separate `Accordion` `Stack`s; "Sync Now"; quick
  search; an "info" button per Actor row opens a `Drawer` with its cached
  Roles/Groups.
- **`Inventory.tsx`** ("Registry" tab) — "Services" (`REST`) / "MCPs"
  (`MCP`) as two `Accordion`s; "Onboard Service" dialog; per-row
  Edit/Fetch-schema/View-schema/Delete; delete routes through a shared
  **`ConfirmDialog.tsx`** (reusable for future destructive actions); a
  "Refresh" button (plain refetch — lighter than Identities' "Sync Now"
  since there's no external IdP to pull from here, just async
  discovery/health-poll status changes to pick up).
- **`AuditTrail.tsx`** — "Agent ID" (MCP-only; REST/interactive-user
  identity is the adjacent "Initiator / OBO User" column) and "Refresh".
  The Tool-name cell shows a `Tooltip` with `message` (§5.5) — session,
  deny reason, and call arguments — on hover.

### 5.11 Infrastructure

- **PostgreSQL 16-alpine** — JDBC/Flyway migrations + R2DBC for the async
  `request_logs` write path.
- **Keycloak 24.0.4** — realm `zte-realm`, auto-imported from
  `keycloak/realm-export.json` (clients `zte-gateway`, `agent-a`/`agent-b`,
  `service-a`, `zte-admin-ui`; roles `ADMIN`/`USER`; users `zte-admin`/
  `zte-test-user`). **`--import-realm` only imports on a realm's first
  creation** — a config change to an already-imported realm (a new client
  role grant, a redirect URI) needs either a full `docker compose down &&
  up` (destroys Keycloak's dev-mode state) or a live Admin REST API update;
  this has bitten real deployments of this repo (a role grant silently
  present in the file but not the running realm).
- **Certs** — `certs/generate-certs.sh` builds a one-off ZTE-CA and issues
  `client.p12`/`.pem` (shared internal client cert — `.pem` for non-JVM
  clients, e.g. this repo's `hubspot-mcp` sibling), `service-a.p12`/
  `service-b.p12`/`gateway.p12` (server certs), `truststore.p12` (CA-only
  trust anchor).

---

## 6. Data Model

`zte-policies.yaml` (file, not a DB table): one `PolicyDocument` —
`schemaVersion` (must be `1`) + four rule lists (§5.3's `PolicyRule` shape,
`docs/policy-schema.md` for the full reference).

`acap-profiles/*.yaml` (files, not a DB table, ADR-020): zero or more
`AcapProfile` documents — `agentId`/`territory`/`scope.read[].{resource,fields}`/
`scope.writeAllowed` (`docs/policy-schema.md`'s "ACAP scope profiles" section
for the full reference). Best-effort loaded (§5.4), unlike `zte-policies.yaml`.

**`request_logs`** (Flyway `V4`+`V12`) — the unified REST+MCP audit trail:

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK, `DEFAULT gen_random_uuid()` |
| `timestamp` | `TIMESTAMPTZ` | `DEFAULT NOW()`, indexed descending |
| `trace_id` | `VARCHAR(64)` | The request's `X-Request-Id` (caller-supplied or minted) — REST and MCP alike. Indexed |
| `client_ip` | `VARCHAR(64)`, nullable | Via `ClientIpResolver` (§5.5) — REST and MCP alike |
| `user_agent` | `TEXT`, nullable | REST and MCP alike |
| `process_id` | `VARCHAR(32)`, nullable | OS PID of the gateway JVM instance |
| `agent_id` | `VARCHAR(128)`, nullable | MCP only — populated for every MCP row including `SSE_OPENED`; always `null` for REST |
| `tool_name` | `VARCHAR(128)`, nullable | MCP only — `null` for `SSE_OPENED` (no tool call yet); always `null` for REST |
| `path` | `TEXT` | REST: the request path. MCP: `/sse` or `/message` |
| `status_code` | `INTEGER`, nullable | |
| `message` | `TEXT`, nullable | MCP only — `"Session: <id>[. <deny reason>][. Args: <json>]"` (§5.5); unused by REST |
| `initiator_client` | `VARCHAR(128)`, nullable | JWT `azp` (REST) / agent id (MCP) — `null` for a plain interactive user |
| `original_user_obo` | `VARCHAR(128)`, nullable | JWT `preferred_username` (falling back to `sub`), REST and MCP alike — display-only, see §5.5 |
| `target_service` | `VARCHAR(255)`, nullable | REST: `RequestTargetResolver`-derived name. MCP: `mcp-backend.name` |
| `http_method` | `VARCHAR(10)`, nullable | REST: the actual verb. MCP: `GET` (`SSE_OPENED`) or `POST` (tool call) |
| `decision_effect` | `VARCHAR(10)`, nullable | `ALLOW`/`DENY`/`HOLD`/`ERROR`, derived from `status_code` — a coarse, post-hoc signal, not per-filter provenance of which layer decided. `HOLD` (ADR-019) is a held MCP call; a later `APPROVED`/`REJECTED` decision is its own row, mapped to `ALLOW`/`DENY` respectively |

Written by `RequestLogAuditService` (§5.5/§8's async pattern), read via `GET
/api/v1/admin/audit-logs` (`findTop100ByOrderByTimestampDesc()`).

**`idp_identities`** (Flyway `V5`+`V6`) — the local IdP cache, no
secrets/credentials ever:

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK |
| `type` | `VARCHAR(10)` + `CHECK` | `USER`/`GROUP`/`ROLE`/`CLIENT` |
| `external_id` | `VARCHAR(255)` | The IdP's own identifier; `UNIQUE (type, external_id)` |
| `name` | `VARCHAR(255)`, indexed with `type` | What `IdentityUrn`/policy sources match against |
| `display_name` | `VARCHAR(255)`, nullable | Human-readable label |
| `last_synced` | `TIMESTAMPTZ` | |

**`idp_identity_relations`** (Flyway `V7`) — many-to-many, `subject_id`/
`target_id` reference `idp_identities(id) ON DELETE CASCADE`,
`relation_type` `CHECK IN ('MEMBER_OF','HAS_ROLE')`, `UNIQUE (subject_id,
target_id, relation_type)`.

**`inventory_services`** (Flyway `V8`–`V11`) — the APIM registry:

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK |
| `name` | `VARCHAR(255)` | `UNIQUE` — must match `RequestTargetResolver`'s path-segment extraction for passive telemetry to find this row |
| `target_type` | `VARCHAR(10)` + `CHECK` | `REST`/`MCP` |
| `base_url` | `VARCHAR(512)` | |
| `docs_url` | `VARCHAR(512)`, nullable | Full absolute URL, probed instead of `{base_url}/v3/api-docs`; `REST` only |
| `management_url` | `VARCHAR(512)`, nullable | Health-poll target instead of `base_url`; `NULL` falls back to `base_url` |
| `status` | `VARCHAR(10)` + `CHECK`, `DEFAULT 'PENDING'` | `PENDING`/`ACTIVE`/`WARNING`/`DOWN` |
| `discovered_schema` | `JSONB`, nullable | Raw last-successful-probe body; excluded from `findAll()`/list view by construction |
| `created_at` | `TIMESTAMPTZ` | Never touched by an update |

**`health_metrics`** (same migration) — one row per service:
`service_id` (`UNIQUE REFERENCES inventory_services(id) ON DELETE CASCADE`),
`last_ping_ms`, `actuator_status`, `last_successful_call`, `updated_at`.

**`pending_approvals`** (Flyway `V13`, ADR-019) — the 🟡 HOLD outcome's durable queue:

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK |
| `session_id` | `VARCHAR(64)` | The originating MCP session — may have since closed, see ADR-019 |
| `agent_id` | `VARCHAR(128)` | |
| `tool_name` | `VARCHAR(255)` | |
| `rpc_id_json` / `arguments_json` | `VARCHAR(255)` / `TEXT`, nullable | The original `tools/call` request's `id`/`arguments`, compact-JSON — reconstructed verbatim on approval |
| `route_to` | `VARCHAR(128)`, nullable | Stored, not yet enforced/routed anywhere — see ADR-019 Self-Criticism |
| `reason` | `TEXT`, nullable | Why it was held (the matched `agentMcpToolHolds` rule) |
| `status` | `VARCHAR(10)` + `CHECK`, `DEFAULT 'PENDING'` | `PENDING`/`APPROVED`/`REJECTED` |
| `requested_at` / `decided_at` | `TIMESTAMPTZ` | |
| `decided_by` | `VARCHAR(128)`, nullable | Deciding admin's JWT `preferred_username`/`sub` |
| `trace_id` / `client_ip` / `user_agent` / `display_identity` | — | Same audit-context fields as `request_logs`, carried through to the eventual `APPROVED`/`REJECTED` audit row |

---

## 7. API Reference

| Endpoint | Method | Auth | Service | Purpose |
|---|---|---|---|---|
| `/api/v1/{name}/**` | any | JWT + YAML policy | gateway → any `REST` registry entry | Dynamically routed (§5.2/§5.7) |
| `/api/v1/service-b/restricted` | GET | JWT + `service2service` policy | gateway → service-b | Deliberately has no rule, exercises default-deny |
| `/api/v1/internal/policies[/reload]` | GET/POST | network perimeter only | gateway | Feeds `zt-agents`; no-downtime reload |
| `/api/v1/admin/policies[/reload]` | GET/POST | JWT + `ADMIN` | gateway | Full policy document; ADMIN-gated reload |
| `/api/v1/admin/audit-logs` | GET | JWT + `ADMIN` | gateway | Latest 100 `request_logs` rows |
| `/api/v1/admin/identities/sync` | POST | JWT + `ADMIN` | gateway | Manual IdP sync trigger |
| `/api/v1/admin/identities/search` | GET | JWT + `ADMIN` | gateway | `?type=&q=` |
| `/api/v1/admin/identities/{id}/relations` | GET | JWT + `ADMIN` | gateway | Local-cache-only |
| `/api/v1/admin/inventory[/{id}]` | GET/POST/PUT/DELETE | JWT + `ADMIN` | gateway | APIM registry CRUD |
| `/api/v1/admin/inventory/{id}/schema[/fetch]` | GET/POST | JWT + `ADMIN` | gateway | Captured discovery payload; synchronous re-probe |
| `/api/v1/admin/approvals` | GET | JWT + `ADMIN` | gateway | Pending 🟡 HOLD queue (ADR-019) |
| `/api/v1/admin/approvals/{id}/approve` | POST | JWT + `ADMIN` | gateway | Forwards the original held call; audits `APPROVED` |
| `/api/v1/admin/approvals/{id}/reject` | POST | JWT + `ADMIN` | gateway | Honest denial; audits `REJECTED` |
| `/api/v1/admin/acap-profiles` | GET | JWT + `ADMIN` | gateway | Loaded ACAP profiles + current threshold usage (`AcapProfileView`, ADR-020/ADR-022) — Admin Console "Governance" tab's ACAP Profiles section |
| `/api/v1/admin/acap-profiles/reload` | POST | JWT + `ADMIN` | gateway | Re-reads `zte.acap.profiles-location`; always 200 (best-effort, see ADR-020) |
| `/api/v1/admin/governance/agent-activity` | GET | JWT + `ADMIN` | gateway | Per-agent ALLOW/HOLD/DENY counts (`?hours=`, default 24) (ADR-021) |
| `/api/v1/admin/governance/out-of-policy` | GET | JWT + `ADMIN` | gateway | Latest 50 MCP-agent denials, newest first (ADR-021) |
| `/api/v1/admin/governance/report` | GET | JWT + `ADMIN` | gateway | Combined JSON export of the above two (`?hours=`) (ADR-021) |
| `/admin/**` | GET | none (SPA handles its own login) | gateway | Admin Console static bundle |
| `/sse` | GET | JWT + client cert | gateway (MCP proxy) | Opens an MCP session; SSE stream |
| `/message?sessionId=<id>` | POST | JWT + client cert | gateway (MCP proxy) | JSON-RPC `tools/call`; result via SSE |
| `/api/v1/service-a/hello` | GET | JWT + YAML policy + client cert | service-a | Demo endpoint, calls service-b |
| `/api/v1/agents/auditor/run` | POST | none (local demo) | zt-agents | Runs the Policy Auditor |
| `/actuator/health/**` | GET | public | all services | Liveness/readiness |
| `/realms/zte-realm/protocol/openid-connect/token` | POST | client creds | Keycloak | Token issuance |

---

## 8. Conventions for Future Development

Patterns this codebase has converged on, distilled from what's otherwise
scattered as repeated asides across the ADRs. Follow these before inventing
a new mechanism for something a new feature needs.

**mTLS is outbound-default, inbound-selective.** Any `WebClient.Builder`
injected anywhere in `gateway-service` automatically carries the gateway's
mTLS client cert — Spring's `ClientHttpConnectorAutoConfiguration` applies
`MtlsHttpClientConfig`'s one `ReactorClientHttpConnector` bean to every such
builder, confirmed by bytecode inspection, not assumed (see
`AutoDiscoveryWorker`'s Javadoc). A new outbound call gets mTLS for free by
just injecting `WebClient.Builder` — don't build a second connector.
Inbound, the gateway's own listener requires a client cert only on specific
paths (`MtlsEnforcementWebFilter`'s hardcoded prefix list) — a new protected
surface gets added to that list; a second HTTPS listener was explicitly
considered and rejected (ADR-018) in favor of this.

**`GlobalFilter` only sees Gateway-routed traffic.** Spring Cloud Gateway's
`GlobalFilter`s are invoked by `FilteringWebHandler`, which only runs for
requests `RoutePredicateHandlerMapping` matches to a registered route. A
local `@RestController` with no route entry — and any request denied by an
earlier filter, which short-circuits before `chain.filter()` — never reaches
one. Found the hard way twice (`AdminAuthorizationFilter`, `ADR-012`;
`RequestAuditFilter`, `ADR-013`). A new non-routed authenticated endpoint
needs a plain `WebFilter`, not a `GlobalFilter`.

**Async fire-and-forget writes: one shared shape.**
`Sinks.Many.tryEmitNext` (non-blocking on the caller's thread) → one
`Schedulers.boundedElastic()` subscriber draining into the actual write →
an SLF4J warning on failure instead of propagating or silently dropping.
`RequestLogAuditService`, `LoggingMcpAuditService`, and
`HealthTelemetryService` all use this exact shape — reuse it for the next
one rather than inventing a variant.

**R2DBC records: `id` left `null`, DB generates it.** Every entity record
leaves `id` `null` on construction; Spring Data's "null id → new entity"
heuristic plus Postgres `DEFAULT gen_random_uuid()` handles inserts with no
custom `Persistable` implementation needed.

**Enum-shaped columns are `VARCHAR` + `CHECK`, not native Postgres enums** —
avoids registering an R2DBC enum codec. See `idp_identities.type`,
`inventory_services.target_type`/`status`.

**Extend an existing free-text field before adding a migration.** The MCP
session id, deny reasons, and call arguments all live in
`request_logs.message` rather than three dedicated columns — reach for this
when the data doesn't need to be filtered/joined/indexed on directly.

**Extract shared logic once two call sites need the identical rule, not
before.** `ClientIpResolver` was pulled out of `RequestAuditFilter` and
`McpProxyHandler` only once both needed the same header-resolution logic
(§5.5) — it didn't exist speculatively ahead of that.

**A static config label is fine until a real per-call lookup is needed.**
`mcp-backend.name` is an operator-set string, not a registry lookup, because
`McpBackendClient` only ever forwards to one configured backend — contrast
`InventoryRouteDefinitionLocator`, a genuine per-call lookup, which exists
because REST traffic already has multiple real targets. Don't build the
dynamic version speculatively; build the static one and upgrade it when a
second backend actually shows up.

**Pure decision logic gets a direct unit test; I/O-calling code around it
doesn't need one if an IT test already exercises it.**
`HealthPollingService.statusTransition`/`.healthCheckUrl`,
`KeycloakIdpAdapter.isSystemClient` — all package-visible + `static`
specifically so they're unit-testable without mocking a `WebClient`. The
surrounding HTTP-calling code is proven by Testcontainers/WireMock IT tests
instead — a deliberate, repeated choice in this codebase, not a coverage gap
to reflexively close.

**A property that gates transport-layer behavior is independent of one that
gates application-layer behavior — check both.** `server.ssl.enabled`
(listener-level) and `zte.mtls.enabled` (gates `MtlsHttpClientConfig`'s
outbound config, and `MtlsEnforcementWebFilter`'s inbound check) look
related but are set independently; `application-it.yml` needs its own
`server.ssl.enabled: false` regardless of what `zte.mtls.enabled` is set to.

**Identity URNs need a declared default type.** Any new policy category
using `IdentityUrn.parse` must specify what a bare, unprefixed source means
for that category (`ROLE` for `users2service`, `CLIENT` for the
service/agent-facing two) — there's no global default.

**Verify the literal ask against the actual current code before
implementing it.** Repeatedly, a task's own framing turned out not to match
reality once checked — a claimed existing config value that wasn't there
(ADR-018's `server.ssl.client-auth: need → want` framing, when no
`server.ssl` block existed at all), a claimed gap that was already closed
(ADR-016's "bypasses mTLS" framing, when the connector was already global).
Check first; it's cheaper than reworking after the fact, and every ADR that
did this documents exactly what was investigated and found.

---

## 9. Roadmap / Backlog

**MCP proxy hardening:**
- Authenticate `McpBackendClient` → backend (bearer token or mTLS) — the one
  hop ADR-018's inbound enforcement explicitly didn't touch.
- Bounded buffer + overflow policy for `LoggingMcpAuditService` and
  `RequestLogAuditService` (both currently unbounded).
- Incremental relay for a backend that streams multiple SSE events per tool
  call, instead of buffering to one `Mono<JsonRpcResponse>`.
- Shared/sticky session store for `McpSessionManager` if the gateway is ever
  run with >1 replica.
- Real `DataMaskingFilter` (PII rules undefined today — pass-through stub).
- Real TSDB writer behind `McpAuditService`, replacing the log line in
  `LoggingMcpAuditService.persist()`.
- **`HealthPollingService`'s `/actuator/health` + Spring-shaped-JSON
  assumption forces every non-Spring backend the registry fronts to fake
  being one** (found live via the `hubspot-mcp` MCP bridge, a plain Python
  script that added a matching endpoint purely to pass this poll) — make the
  check protocol-agnostic (any 2xx, or a per-entry-configurable
  path/shape) instead.
- Validate `AutoDiscoveryWorker`'s MCP stateless-discovery assumption
  against a real session-only agent.

**Audit/observability:**
- Per-filter `decision_effect` provenance (currently derived from the final
  status code alone, can't distinguish a ZTE-layer `DENY` from a downstream
  service's own error status).
- True-`401` (no token at all) requests aren't captured — Spring Security's
  own filter rejects before `RequestAuditFilter` runs.
- `client_ip` trusts `X-Forwarded-For` at face value — fine for this MVP's
  single-hop Docker-network deployment, would need edge-level
  stripping/validation behind a real LB.
- Distributed tracing: Micrometer Tracing + Zipkin (`X-Request-Id` is
  already the prerequisite primitive).

**Identity/policy:**
- A second `IdpClient` implementation (Azure Entra ID / AWS IAM) — the
  concrete reason the adapter interface exists.
- UUID-based user URNs (today `user:<name>` only matches
  `preferred_username`); a demo Keycloak group, to close the integration
  test gap for `group:`-scoped rules.
- Filesystem-watch/webhook-driven identity sync and policy reload, replacing
  both fixed polling intervals.
- Per-category `zte.policy.*.default-effect` overrides.
- ABAC extension: a `condition` field on `PolicyRule` (SpEL against JWT
  claims).
- Reduce `fetchRelations()`'s per-user/per-client HTTP call count at larger
  realm scale — no known Keycloak Admin API batch endpoint today.

**Registry:**
- A `health_metrics` history table (ping latency over time).
- Reconciliation for stale `inventory_services`/`health_metrics` rows (only
  removed by explicit `DELETE`).
- Warn on a name mismatch between a registered service and passive
  telemetry's exact-name-match requirement.
- Code-split `zt-admin-ui`'s bundle (`swagger-ui-react` roughly tripled it).

**Production-path items** (deferred by design at MVP scope, see the ADR
cited): RS256 OBO tokens instead of shared-secret HMAC (ADR-004); per-agent
mTLS client certs (SPIFFE/SVID) instead of one shared cert (ADR-004);
Keycloak Config CLI/Terraform for multi-environment identity config
(ADR-002); service mesh evaluation once service count exceeds ~5 or
Kubernetes is adopted (ADR-001); rate limiting (`RequestRateLimiter`,
Redis-backed); a full mTLS Testcontainers system test (WireMock has no TLS,
so handshake rejection is untested); a generic mechanism so a future
gateway-local `@RestController` inherits `users2service` enforcement
automatically instead of needing its own `WebFilter` (ADR-012).

---

## 10. Known Risks (consolidated from ADR self-critique sections)

| Severity | Risk | Mitigation status |
|---|---|---|
| High | Gateway could become a "God Service" if business logic creeps in | Convention only — no enforcement mechanism |
| High (prod) | Keycloak client secret hardcoded in `realm-export.json` | Dev-only; must be env/secret-manager-injected before staging |
| High | `GlobalFilter`s silently skip non-routed/pre-denied requests (§8) | No generic guard against a third instance — real gap |
| Medium | Shared HMAC secret for OBO tokens | `ZTE_OBO_SECRET` env var; RS256 upgrade deferred (§9) |
| Medium | Server-side TLS cert rotation requires a restart (no hot-reload API) | 1-year dev certs; production needs cert-manager + rolling restart |
| Medium | mTLS transport-layer enforcement untested in the IT suite (WireMock has no TLS) | Backlog (§9) |
| Medium | MCP session state in-memory, single-instance | Needs sticky routing or a shared store before scaling out |
| Medium | True-`401` requests aren't captured in `request_logs` | Named; doesn't affect any existing test |
| Medium | `idp_identities`/`idp_identity_relations` can be stale up to the sync interval (15 min default) | Deliberate tradeoff to keep policy evaluation zero-I/O; manual sync gives an immediate override |
| Medium | `fetchRelations()` is N+1-shaped (2 HTTP calls per user/client) | No known Keycloak Admin API batch alternative; backlog (§9) |
| Medium | `AutoDiscoveryWorker`'s MCP discovery assumes a stateless `tools/list` call | Unverified against a real session-only agent; backlog (§9) |
| Medium | Passive `last_successful_call` telemetry depends on an exact name match with no validation at onboarding time | Documented; backlog (§9) |
| Low | `decision_effect` is status-code-derived, not per-filter provenance | Named; backlog (§9) |
| Low | `PolicyMatcher` is a full linear scan per category per request | Fine at `<100`-rule MVP scale |
| Low | `POST /api/v1/internal/policies/reload` has network-perimeter-only auth | Acceptable for MVP (Docker-bridge only); ADR-012 adds an ADMIN-gated counterpart |
| Low | `LoggingMcpAuditService`/`RequestLogAuditService` buffers are both unbounded | Backlog (§9) |
| Low | `OrphanedRuleChecker`'s startup check can race `IdentitySyncService`'s first sync, a transient false-positive | Self-corrects within one sync interval; observational only |
| Low | No IT test exercises `group:`-scoped `users2service` matching end-to-end | `zte-realm` has no groups defined yet; backlog (§9) |
| Low | Several `WebClient`-calling classes (`KeycloakIdpAdapter`, `AutoDiscoveryWorker`, `HealthPollingService`, `McpBackendClient`) have no dedicated mocked-HTTP unit test | Deliberate, repeated choice — proven by IT tests instead (§8) |
| Low | `inventory_services`/`health_metrics` have no reconciliation for stale rows | Same posture as `idp_identities`; backlog (§9) |
| Low | `WARNING` inventory status has no UI action to clear other than delete+re-onboard | The "Fetch" (🔄) button already recovers it once the target's reachable again — no dedicated action needed |
| Low | `HealthPollingService`'s `/actuator/health` + JSON-shape assumption forces non-Spring backends to fake being one | Backlog (§9) — found live via the `hubspot-mcp` MCP bridge |
| — | **Keycloak `--import-realm` only imports on first creation** — a `realm-export.json` change (new role grant, redirect URI) silently doesn't apply to an already-running realm | Real, has bitten a real deployment of this repo; needs `docker compose down && up` (destroys dev state) or a live Admin API update — no code fix, an operational gotcha worth remembering |

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
| [012](adr/ADR-012-full-yaml-migration-and-admin-console.md) | Full YAML Policy Migration and React Admin Console |
| [013](adr/ADR-013-postgres-audit-logging.md) | R2DBC-Backed Request Audit Logging with Distributed Tracing |
| [014](adr/ADR-014-idp-identity-sync.md) | IdP Identity Sync and URN-Based Policy Matching |
| [015](adr/ADR-015-machine-identities-and-urn-unification.md) | Machine Identities (OIDC Clients) and URN Unification |
| [Identities UI + Relations](adr/identities-ui-actors-containers-and-relations-caching.md) | Identities UI Refactor (Actors vs. Access Containers) and Relational Caching — deliberately unnumbered filename, see the ADR's own note |
| [016](adr/ADR-016-inventory-and-health-registry.md) | APIM Inventory Registry — Auto-Discovery and Health Telemetry |
| [017](adr/ADR-017-dynamic-routing-and-audit.md) | Dynamic Inventory-Driven Routing, Unified Audit Logging, and Strict S2S Rules |
| [018](adr/ADR-018-smart-mtls-enforcement.md) | Smart mTLS Enforcement (client-auth: want + Application-Layer WebFilter) |
| [019](adr/ADR-019-hold-decision-and-approval-queue.md) | HOLD as a Third MCP Decision Outcome, and a Durable Approval Queue |
| [020](adr/ADR-020-acap-scope-profiles.md) | ACAP Scope Profiles — Argument/Field-Level Policy Tightening |
| [021](adr/ADR-021-governance-dashboard.md) | Governance Dashboard — Per-Agent Activity and Out-of-Policy Feed |
| [022](adr/ADR-022-acap-agent-metadata-and-thresholds.md) | ACAP Agent Metadata and Usage Thresholds |
| [023](adr/ADR-023-policy-rule-mcp-target.md) | `mcpTarget` — Scoping agentMcpToolCalls/agentMcpToolHolds Rules to a Specific MCP Backend |

---

*This document reflects repo state through commit `8383def` (the audit-row
enrichment follow-up — `SSE_OPENED`, `ClientIpResolver`, readable display
identity, named MCP target, captured call arguments — §5.5/§8) and
`304269d` (Admin Console polish). Restructured from a chronological
per-stage narrative into a current-state reference plus §8's conventions —
the per-stage investigation narrative this replaced still lives in each
linked ADR and `prompts-hist/`; nothing was deleted, only moved to where it
belongs. Keep this in sync the same way as README/CLAUDE.md.*
