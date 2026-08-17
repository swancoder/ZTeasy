# ZTeasy — Technical Specification & Implementation Roadmap

**Product:** Lightweight Zero Trust Environment (ZTE) MVP — a Zero Trust Data Gateway,
now extended toward fronting AI agent (MCP) traffic, not just plain REST.
**Status as of:** 2026-08-10 · Stage 12 of 12 implemented stages complete.

This document is the single technical reference for the system as built. It
consolidates what's spread across `README.md` (quick start, chain-of-trust
summary), `CLAUDE.md` (terse per-stage changelog for AI-assisted sessions), and
thirteen ADRs (individual decisions) into one place: what exists, how it fits
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
| 11 | Full YAML migration (retired `access_policies`/`PolicyService`) + React Admin Console (`zt-admin-ui`), ADMIN-JWT-gated admin API, `AdminAuthorizationFilter` | ✅ Complete | `00edf91` | [012](adr/ADR-012-full-yaml-migration-and-admin-console.md) |
| 12 | R2DBC-backed `request_logs` audit trail, `X-Request-Id` distributed tracing, `GET /api/v1/admin/audit-logs`, Admin Console "Audit Trail" tab; `RequestAuditFilter` rewritten as a `WebFilter` | ✅ Complete | `e5e1c65` | [013](adr/ADR-013-postgres-audit-logging.md) |
| 13 | IdP identity sync (`idp_identities` cache, `KeycloakIdpAdapter`), URN-based `users2service` sources (`user:`/`group:`/`role:`), orphaned-rule detection, Admin Console "Identities" tab | ✅ Complete | `dd8a13f` | [014](adr/ADR-014-idp-identity-sync.md) |
| 14 | Machine identities — OIDC clients synced as `CLIENT` type, `client:<clientId>` URN unification for `service2service`/`agentMcpToolCalls`, orphaned-rule detection extended to all three categories | ✅ Complete | `f5a30b8` | [015](adr/ADR-015-machine-identities-and-urn-unification.md) |
| 15 | Identities UI refactor (Actors vs. Access Containers accordions, quick search, relations Drawer), `idp_identity_relations` caching, Keycloak system-client filtering | ✅ Complete | `1198921` | [Identities UI + Relations](adr/identities-ui-actors-containers-and-relations-caching.md) |
| 16 | APIM inventory registry (`inventory_services`/`health_metrics`), auto-discovery on onboarding, periodic health polling, passive `last_successful_call` telemetry, Admin Console "Registry" tab | ✅ Complete | `c3fd7de` | [016](adr/ADR-016-inventory-and-health-registry.md) |
| 17 | Dynamic inventory-driven routing (`InventoryRouteDefinitionLocator`, replacing hardcoded routes), REST/MCP audit unification into `request_logs`, strict `service2service` policy scenario (`service-a`→`service-b`) | ✅ Complete | `87d9976` | [017](adr/ADR-017-dynamic-routing-and-audit.md) |
| 18 | Smart mTLS enforcement — gateway HTTPS (`gateway.p12`), `server.ssl.client-auth: want`, `MtlsEnforcementWebFilter` gating `/sse`/`/message`/`/api/v1/**` (minus `/admin`/`/internal`) | ✅ Complete | `<commit>` | [018](adr/ADR-018-smart-mtls-enforcement.md) |
| 19+ | Backlog (rate limiting, ABAC…) | ⬜ Planned | — | see §9.2 |

**Test status:** all unit tests green (`./gradlew test`), including the
`policy.def` package (`PolicyValidatorTest`, `PolicyMatcherTest`,
`YamlPolicyFileLoaderTest`, `PolicyDefinitionStoreTest`,
`DocumentationExampleConformanceTest`), `YamlMcpPolicyEngineTest`,
`ServiceToServiceAuthorizationFilterTest`, `AdminAuthorizationFilterTest`, and
the new `RequestAuditFilterTest`/`RequestLogAuditServiceTest` (Stage 12 —
`RequestAuditFilter`'s `GlobalFilter`→`WebFilter` rewrite and its
`switchIfEmpty`→`doFinally` fix; see §5.2/§10). `./gradlew integrationTest`
(Testcontainers: Postgres + Keycloak) green — `McpProxyIT`'s
real-MCP-enforcement coverage, `HappyPathIT`, `ZeroTrustBreachIT`, and the new
`RequestAuditIT` (proves both allowed and denied requests produce a
`request_logs` row with matching `trace_id`/non-null `client_ip`, polled via
Awaitility since the write is async). Stage 12 also verified by hand against
a real running stack: curl allow/deny requests, `docker exec zte-postgres
psql` to confirm rows land, Admin Console "Audit Trail" tab renders them.
Stage 13 adds `IdentityUrnTest`/`IdentitySourcesTest`/`OrphanedRuleCheckerTest`/
`IdentitySyncServiceTest` (all green); `ZteAuthorizationFilterTest`/
`AdminAuthorizationFilterTest`/`PolicyMatcherTest` pass **unmodified**
(confirms bare-role-name backward compatibility) plus one new `role:`-prefixed
test each; `PolicyDefinitionStoreTest` updated for the new
`ApplicationEventPublisher` constructor param. New IT `IdentitySyncIT`
exercises the real Testcontainers Keycloak Admin REST API round trip — the
actual proof the `realm-export.json` service-account role grant works.
Stage 14 extends the same tests for `client:`-prefixed sources and
`IdentityType.CLIENT` (`ServiceToServiceAuthorizationFilterTest`/
`YamlMcpPolicyEngineTest` pass **unmodified** plus one new test each,
confirming bare-clientId backward compatibility); `IdentitySyncIT`'s
`manualSync_populatesClients` proves the new `view-clients` role grant
works against the real Testcontainers Keycloak. Stage 15 adds
`KeycloakIdpAdapterTest` (new — `isSystemClient` unit coverage) and extends
`IdentitySyncServiceTest` (3-mock setup covering resolvable/unresolvable
relations); `IdentitySyncIT`'s `manualSync_excludesSystemClients`/
`manualSync_thenRelationsEndpoint_reflectsRoleAssignment` prove both the
system-client filter and the relations endpoint against the real
Testcontainers Keycloak. Stage 16 adds `InventoryServiceTest` (mocked
repositories/worker — create/update/delete/list join logic) and
`HealthPollingServiceTest` (direct unit test of the pure
`statusTransition` decision logic only — the actual `WebClient`-calling
code has no dedicated mocked-HTTP unit test, consistent with the
`KeycloakIdpAdapter`/`McpBackendClient` precedent); `RequestAuditFilterTest`
extended for the new health-telemetry hook. New IT `InventoryRegistryIT`
(6 scenarios) proves REST/MCP discovery (success and failure, via
WireMock), full CRUD, duplicate-name rejection, and — the literal task
verification — that real routed traffic through `/api/v1/service-a/hello`
asynchronously updates `last_successful_call` (polled via Awaitility, same
pattern as `RequestAuditIT`). Stage 17 adds `LoggingMcpAuditServiceTest`
(new — verifies MCP allow/deny events now also persist a `request_logs`
row via a mocked `RequestLogAuditService`); extends
`RequestLogAuditServiceTest`/`RequestAuditFilterTest` for the five new
`request_logs` columns; adds `ZteAuthorizationFilterTest`'s
`servicePrincipalToken_withDefaultKeycloakRoles_stillPassesThrough`
regression guard (the roles-emptiness bug, see §10); extends
`InventoryServiceTest` for the new `ApplicationEventPublisher`/
`RefreshRoutesEvent` publication on create/update/delete. New IT
`ServiceToServiceIT` (2 scenarios) is the literal task verification:
`service-a` (new Keycloak machine client) calling service-b's `/context`
succeeds (200), routes via the dynamic locator, and produces a
`request_logs` row with `decisionEffect=ALLOW` and a non-null
`originalUserObo`; calling `/restricted` is denied (403) before ever
reaching the downstream WireMock stub (`verify(0, ...)`), with a
`decisionEffect=DENY` row. `InventoryRegistryIT`/`RequestAuditIT` updated
for the base class's new inventory-seeding `@BeforeEach` and ADR-017
audit fields respectively. Full suite (`./gradlew test integrationTest`)
confirmed green after two real, previously-latent bugs this stage was the
first to exercise were found and fixed — see §10. Stage 18 adds
`MtlsEnforcementWebFilterTest` (8 cases: valid/absent/empty peer-cert array
on a protected path, three unprotected-path exemptions, `zte.mtls.enabled=false`
bypass) and a `zte.mtls.enabled=false` `@TestPropertySource` override on
`McpProxySecurityWebFluxTest` (a `@WebFluxTest` slice with no real TLS
handshake, which auto-detects `MtlsEnforcementWebFilter` the same way it
already auto-detects `AdminAuthorizationFilter`/`RequestAuditFilter` — see
that test class's own Javadoc). `application-it.yml` gains a new, separate
`server.ssl.enabled: false` (independent of the existing `zte.mtls.enabled:
false`) — without it the entire IT suite fails at the TCP/TLS layer once
`server.ssl.enabled: true` lands in the main config. Full suite confirmed
green after both fixes.

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
                              │  REST path (ADR-011/ADR-012):              │
                              │   ① ZteAuthorizationFilter                 │
                              │      (users2service: YAML-only, deny)      │
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
                              │  Admin path (ADR-012, ADR-013):            │
                              │   GET/POST /api/v1/admin/**                │
                              │     → AdminAuthorizationFilter (WebFilter, │
                              │       not GlobalFilter — see ADR-012) →    │
                              │       AdminPolicyController /              │
                              │       AdminAuditLogController              │
                              │   GET /admin/** (static) ──► zt-admin-ui   │
                              │     bundle, packaged into this jar         │
                              │                                             │
                              │  Audit path (ADR-013):                     │
                              │   RequestAuditFilter (WebFilter, not       │
                              │     GlobalFilter — sees denied + admin/    │
                              │     internal traffic too) → X-Request-Id   │
                              │     resolve/forward on every request →     │
                              │     doFinally → skip if excluded (see      │
                              │     AuditExclusionProperties) → else       │
                              │     RequestLogAuditService (async sink)    │
                              │     → request_logs (R2DBC)                 │
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

Infrastructure: PostgreSQL 16 (`5432` — JDBC/Flyway for migrations, and R2DBC
again as of ADR-013 for the `request_logs` async write path; `access_policies`
(ADR-003/removed ADR-012) and `gateway_audit_log` (V1, removed ADR-013) are
both gone, `request_logs` is the only table on the runtime query/write path
today), Keycloak 24.0.4 (`8180`, realm `zte-realm`, native `--import-realm`).
See `docker-compose.yml`.

### 3.2 Module map

| Module | Language | Responsibility | Port(s) |
|---|---|---|---|
| `auth-library` | Java 21 | Shared security: `SecurityConfig`, `ZteAuditLogger`, `ReloadableSslContextFactory`, `UserContextTokenService` | — (library) |
| `gateway-service` | Java 21 | ZT entry point: JWT validation, YAML policy enforcement, OBO issuance, mTLS client, MCP proxy, internal agent data endpoint, Admin Console API + static hosting, async R2DBC request audit trail | 8080 |
| `service-a` | Java 21 (WebFlux) | First protected downstream; calls service-b | 8081 (mTLS), 9081 (mgmt) |
| `service-b` | Java 21 (WebFlux) | Deepest downstream; validates OBO token | 8082 (mTLS), 9082 (mgmt) |
| `zt-agents` | Kotlin (WebFlux) | AI security copilot — Policy Auditor Agent | 8083 |
| `zt-admin-ui` | TypeScript (Vite/React) | Admin Console SPA — plain npm project, built and packaged by `gateway-service`'s Gradle build (ADR-012), not run standalone | — (served by 8080) |
| `certs` | shell | Dev cert generation (ZTE-CA, PKCS12 stores, plus `client.pem` for non-JVM clients) | — |
| `scripts` | shell | Keycloak password bootstrap, git hook install | — |
| `docs/adr` | Markdown | Architectural Decision Records | — |

---

## 4. Zero Trust Model

Every request must answer four questions (README's framing, unchanged since
Stage 4):

| # | Question | Mechanism | Where enforced |
|---|---|---|---|
| 1 | Who is the user? | Keycloak JWT (RS256) | `SecurityConfig` (`auth-library`), all services |
| 2 | Is the user allowed? | YAML `users2service` rules (`zte-policies.yaml`) — sole source of truth as of ADR-012 | `ZteAuthorizationFilter` (gateway-routed paths); `AdminAuthorizationFilter` (`/api/v1/admin/**`) |
| 3 | Who is the internal caller? | mTLS client cert (ZTE-CA) | `MtlsHttpClientConfig` + service HTTPS listeners (outbound); as of ADR-018, cert *presence* is also required inbound to the gateway itself on `/sse`, `/message`, and `/api/v1/**` (minus `/admin`/`/internal`) via `MtlsEnforcementWebFilter` — `server.ssl.client-auth: want`, enforced at the application layer, not the TLS layer |
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

Seventh question, human-operator-facing (ADR-012): **may this admin see or
change the policy set?** — the Admin Console SPA authenticates the operator
via Keycloak (client `zte-admin-ui`, PKCE), and `AdminAuthorizationFilter`
matches the resulting JWT's roles against the same `users2service` YAML
rules `ZteAuthorizationFilter` uses for everything else — same policy
document, same audit-log shape, different filter because `/api/v1/admin/**`
has no `GatewayRouteConfig` route for `ZteAuthorizationFilter`'s
`GlobalFilter` to attach to (see §5.2's note on this).

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

- **`MtlsEnforcementWebFilter`** (`filter` package, `WebFilter`, order
  `HIGHEST_PRECEDENCE+50`, new in ADR-018) — runs before every other filter
  below, including Spring Security's own JWT check. `server.ssl.client-auth`
  is `want` (TLS handshake succeeds with or without a client cert), so this
  filter is what actually requires one, and only on specific paths: `/sse`,
  `/message` (always), and `/api/v1/**` except `/api/v1/admin/` and
  `/api/v1/internal/` (the same two prefixes `AuditExclusionProperties`
  already treats as gateway-local). No `SslInfo`/empty peer-cert array on a
  protected path → `401` before any JWT/policy work happens. `/admin/**`,
  `/actuator/**`, and the two excluded prefixes stay reachable over plain
  browser HTTPS. Gated by `zte.mtls.enabled` (default `true`), the same
  property `MtlsHttpClientConfig` uses for the outbound side.
- **`InventoryRouteDefinitionLocator`** (`inventory` package, ADR-017,
  replacing `GatewayRouteConfig`) — a `RouteDefinitionLocator` bean that
  queries `InventoryRepository.findAll()`, filtered to `target_type=REST`
  and `status IN (ACTIVE, WARNING)` (§5.2d's registry), and emits one
  `RouteDefinition` per entry: `Path` predicate `/api/v1/{name}/**` →
  `base_url`, `AddRequestHeader X-Gateway-Source=zte-gateway`. Routing is
  now 100% `inventory_services`-driven — onboarding a service via the
  Admin Console makes it immediately routable, no code change or redeploy.
  Spring Cloud Gateway's `CachingRouteLocator` only re-fetches on a
  `RefreshRoutesEvent`: `InventoryService.create`/`update`/`delete` each
  publish one immediately (§5.2d); `InventoryRouteRefreshScheduler`
  (`@Scheduled(fixedDelayString = "${zte.routing.refresh-interval-ms:30000}")`)
  is the periodic catch-all for status changes written directly by
  `AutoDiscoveryWorker`/`HealthPollingService` (which don't go through
  `InventoryService` and so publish no event of their own).
  **`InventoryBootstrapSeeder`** (`ApplicationRunner`) seeds `service-a`/
  `service-b` into the registry at startup from the `service-a.uri`/
  `service-b.uri` properties (repurposed from ADR-004's original static
  routes), only if not already registered — preserves zero-config
  `docker compose up` onboarding now that routing has no hardcoded
  fallback. See ADR-017.
- **`ZteAuthorizationFilter`** (`GlobalFilter`, order `HIGHEST_PRECEDENCE+100`)
  — users2service enforcement: a JWT whose `azp` is not the interactive
  user client (`zte.policy.user-client-id`) is service2service traffic and
  passes through untouched regardless of `realm_access.roles` — see next
  bullet (ADR-017: this used to also require `roles.isEmpty()`, but every
  Keycloak client is granted default composite/scope roles automatically,
  so that condition was never actually true for a real client-credentials
  token — a previously-latent bug, unexercised until this stage's `service-a`
  machine client became the first service credential to ever reach this
  Gateway-routed filter; see §10). Otherwise extracts `realm_access.roles`,
  builds an enriched sources list via `IdentitySources.enrich(roles, jwtAuth)`
  (bare role names plus `role:`/`user:`/`group:` URN forms, ADR-014 — see
  §5.2c), consults the YAML `users2service` rules against that enriched list
  (explicit ALLOW/DENY short-circuits; no match →
  deny as of ADR-012, the DB-backed fallback was retired); 403 +
  `GATEWAY_ALREADY_ROUTED_ATTR` on deny (blocks `NettyRoutingFilter`).
  **Only runs for Gateway-routed requests** — `GlobalFilter` is invoked by
  `FilteringWebHandler`, which only handles requests
  `RoutePredicateHandlerMapping` matches to an
  `InventoryRouteDefinitionLocator`-sourced route; a local
  `@RestController` with no route entry never reaches it (see
  `AdminAuthorizationFilter` below, and ADR-012's Self-Critique for how this
  was found — empirically, via a USER-role JWT getting `200` from the new
  admin API before the fix).
- **`ServiceToServiceAuthorizationFilter`** (order `HIGHEST_PRECEDENCE+150`,
  new in ADR-011) — service2service enforcement for the traffic
  `ZteAuthorizationFilter` passes through: matches an `IdentitySources.enrichClient(azp)`-enriched
  list (the bare `azp` plus its `client:<azp>` URN form, ADR-015 — see §5.2c)
  against YAML `service2service` rules; no DB fallback, `NO_MATCH` resolves
  to `zte.policy.default-effect` (default `DENY`).
- **`policy/def` package** (ADR-011) — `PolicyDefinitionStore` (loads +
  validates `zte-policies.yaml` at startup, fails `ApplicationContext` refresh
  on invalid content; `AtomicReference<PolicyDocument>` hot-swap, mirroring
  `ReloadableSslContextFactory`'s pattern), `PolicyMatcher` (deny always
  overrides allow; `AntPathMatcher`-based), `PolicyValidator`,
  `YamlPolicyFileLoader`. See §5.2a.
- **`AdminPolicyController`** (`admin` package, ADR-012) — `GET
  /api/v1/admin/policies` returns the full active `PolicyDocument` (all three
  categories — the operator dashboard view); `POST
  /api/v1/admin/policies/reload` does what `PolicyReloadController` does,
  both sharing one `PolicyReloadResult.toResponseEntity()` implementation.
  Stays behind the default JWT-required chain — no permitAll override.
- **`AdminAuthorizationFilter`** (`admin` package, plain `WebFilter`, ADR-012)
  — enforces the YAML `users2service` rule for `/api/v1/admin/**`
  (`u2s-admin-console-api`: `ADMIN` → target `admin`), reusing the same
  `PolicyMatcher`/`PolicyDefinitionStore`/`IdentitySources`-enriched/audit-log
  path `ZteAuthorizationFilter` uses. Exists specifically because
  `ZteAuthorizationFilter`'s `GlobalFilter` type doesn't run for this
  non-routed controller (see above). No explicit `@Order` — defaults to
  lowest precedence, i.e. runs after Spring Security's `WebFilterChainProxy`
  (which populates the reactive security context this filter reads).
- **`AdminUiConfig`** (`admin` package, ADR-012) — `@Order(-90)`
  `SecurityWebFilterChain` permitAll for `/admin/**` only (the static SPA
  bundle — a *different* path prefix from `/api/v1/admin/**`, so this never
  weakens the API's auth), plus a `WebFluxConfigurer` resource handler
  serving `classpath:/static/admin/` at that path.
- **`UserContextPropagationFilter`** (order `HIGHEST_PRECEDENCE+200`) — strips
  client-supplied `X-ZTE-User-Context`/`X-User-Id`, issues the OBO token.
- **`RequestAuditFilter`** (plain `WebFilter`, not `GlobalFilter`, as of
  ADR-013 — order `LOWEST_PRECEDENCE-100`) — resolves/forwards `X-Request-Id`
  (caller's value or a new UUID), strips/injects trusted `X-User-Id`
  (unconditionally now, not just on the JWT branch — a strengthening, see
  ADR-013 Self-Critique), logs `sub`/`azp`, and — from a `doFinally` callback
  so it fires regardless of outcome, **for paths not matching
  `zte.audit.excluded-path-prefixes`** (`AuditExclusionProperties`, ADR-013
  amendment — §5.2b) — fires the async `request_logs` write via
  `RequestLogAuditService`. Converted from `GlobalFilter` specifically so it
  sees denied requests and `/api/v1/admin/**`/`/api/v1/internal/**` traffic
  too (see §5.2b and ADR-013); the exclusion list then deliberately scopes
  that visibility back down to zero-trust-relevant traffic only, sparing the
  Admin Console's own housekeeping calls and health checks. Also, since
  ADR-016 (§5.2d) — from the same `doFinally`, on any 2xx status regardless
  of the exclusion list (a separate concern with its own no-op-if-unregistered
  safety net) — fires `HealthTelemetryService.recordSuccessfulCall(...)`
  with the `RequestTargetResolver`-derived target name. Since ADR-017, the
  persisted `RequestLog` (via `RequestLog.forRest(...)`) also carries
  `initiatorClient` (JWT `azp`), `originalUserObo` (JWT `sub`),
  `targetService` (the same `RequestTargetResolver` name used for health
  telemetry), `httpMethod`, and `decisionEffect` (`ALLOW`/`DENY`/`ERROR`,
  derived from the final status code — a coarse signal, not per-filter
  provenance; see §10).
- **`InternalPolicyController`** — `GET /api/v1/internal/policies`, permitAll
  via `InternalSecurityConfig` (`@Order(-100)`), Docker-network-only exposure.
  Returns `PolicyDefinitionStore.current().users2service()` (YAML-backed as
  of ADR-012, was a DB query before). Feeds `zt-agents`. See
  [ADR-007](adr/ADR-007-policy-auditor-agent.md) for why this is
  unauthenticated by design (network perimeter, not app-layer) and its
  production upgrade path.
- **`PolicyReloadController`** (ADR-011) — `POST
  /api/v1/internal/policies/reload`, same permitAll/network-perimeter posture
  as `InternalPolicyController`; re-validates and atomically swaps the active
  `PolicyDocument`, keeping the previous one on validation failure. ADR-012
  adds an ADMIN-JWT-gated counterpart at `AdminPolicyController` for the
  human operator; both share the same reload/response logic.
- **`AdminAuditLogController`** (`admin` package, ADR-013) — `GET
  /api/v1/admin/audit-logs`, same ADMIN-JWT posture as `AdminPolicyController`
  (no new security wiring — `AdminAuthorizationFilter`'s path check already
  covers all of `/api/v1/admin/**`). Returns
  `RequestLogRepository.findTop100ByOrderByTimestampDesc()` — capped at the
  SQL `LIMIT` level.
- **`AdminIdentitySyncController`** (`admin` package, ADR-014) — `POST
  /api/v1/admin/identities/sync`, same ADMIN-JWT posture as
  `AdminPolicyController` (no new security wiring). Triggers
  `IdentitySyncService.syncNow()` and returns the upserted count.
- **`AdminIdentitySearchController`** (`admin` package, ADR-014) — `GET
  /api/v1/admin/identities/search?type=&q=`, same posture. Backs the Admin
  Console's Identities tab and the Policies tab's orphan cross-reference. An
  unrecognized `type` value returns an empty list rather than an error.
- **`AdminIdentityRelationsController`** (`admin` package, Stage 15) — `GET
  /api/v1/admin/identities/{id}/relations`, same posture. Reads only
  `IdpIdentityRelationRepository`/`IdpIdentityRepository` (local Postgres) —
  **no** `WebClient`/Keycloak dependency anywhere in the class, the same
  Zero Trust reliability posture every other `/api/v1/admin/**` read
  endpoint already has. Backs the Identities tab's "info" Drawer.
- **`AdminInventoryController`** (`admin` package, ADR-016) — `GET`/`POST`/`PUT`/`DELETE
  /api/v1/admin/inventory[/{id}]`, same posture. `POST`/`PUT` return `409`
  (not a raw constraint-violation error) on a duplicate `name`. Backs the
  Admin Console's "Registry" tab.

### 5.2a YAML Policy Engine (ADR-011, ADR-012)

One YAML file (`zte-policies.yaml`, path via `zte.policy.file`) is the
**sole** runtime source of truth for all three categories — `users2service`
included, as of ADR-012 (the DB-backed fallback was retired). Full schema,
precedence rules, and validation semantics: `docs/policy-schema.md`. Full
three-category worked example: `docs/examples/zte-policies-example.yaml`
(kept honest by `DocumentationExampleConformanceTest`, which loads and
validates it against the real schema).

A single `PolicyRule` shape (`id`, `effect`, `source`, `target`,
`pathPattern`, `methods`, `priority`) is reused across all three categories —
`pathPattern`/`methods` are simply unused by `agentMcpToolCalls` rules. See
ADR-011 for why this was chosen over three parallel rule subclasses.

### 5.2b Request Audit Trail (ADR-013)

`gateway-service/.../audit` package — the async, R2DBC-backed write path
`RequestAuditFilter` (§5.2) feeds:

- **`RequestLog`** — R2DBC record (`@Table("request_logs")`); `id` left
  `null` on construction, DB-generated (`gen_random_uuid()`, built into
  Postgres core since v13). Mirrors the pre-ADR-012 `AccessPolicy` record's
  DB-generated-PK convention. Two factories as of ADR-017:
  `forRest(...)` (REST traffic, `agentId`/`toolName` always `null`) and
  `forMcp(...)` (MCP traffic — `traceId` is the MCP session id, `httpMethod`
  hardcoded `POST`, `clientIp`/`userAgent`/`originalUserObo` always `null`).
- **`RequestLogRepository`** — `ReactiveCrudRepository<RequestLog, UUID>`,
  one derived query, `findTop100ByOrderByTimestampDesc()`.
- **`RequestLogAuditService`** — directly mirrors
  `LoggingMcpAuditService`'s architecture (`Sinks.Many` + one
  `Schedulers.boundedElastic()` subscriber draining into `repository.save(...)`);
  a DB write failure is caught and degrades to an SLF4J warning line instead
  of propagating or being lost — the literal "keep SLF4J as fallback"
  requirement.

**Audit unification (ADR-017).** `agentId`/`toolName` were reserved but
always `null` from this path prior to ADR-017 — REST and MCP traffic had
two disconnected audit mechanisms (this one, and MCP's own in-memory-only
`McpAuditEvent`/`LoggingMcpAuditService`, §8.3), a gap ADR-009 itself
flagged as future work. As of ADR-017, `LoggingMcpAuditService.persist(...)`
also calls `RequestLogAuditService.record(RequestLog.forMcp(...))` — one
unified `request_logs` table for both traffic types, reusing the same
async, non-blocking write path rather than adding a second one.
`request_logs` also gained five columns in `V12__extend_request_logs.sql`:
`initiator_client`, `original_user_obo`, `target_service`, `http_method`,
`decision_effect` (see §5.2 `RequestAuditFilter` and §6). `decisionEffect`
is derived from the final HTTP status code (2xx→`ALLOW`, 401/403→`DENY`,
else→`ERROR`) — a coarse, post-hoc signal, not per-filter provenance of
*which* policy layer decided (§10).

**`AuditExclusionProperties`** (`filter` package, `@ConfigurationProperties(prefix
= "zte.audit")`, same shape as `PolicyDefaultsProperties`) — `zte.audit.excluded-path-prefixes`
in `application.yml`, deliberately not `zte-policies.yaml` (a flat exclusion
list doesn't need that document's schema/validation/hot-reload machinery)
and not hardcoded (operator-editable without a rebuild). Default excludes
`/admin/`, `/api/v1/admin/`, `/api/v1/internal/`, `/actuator/` — an
ADR-013 amendment made the same day, after the original "audit every
request" version was observed live: 30 of the first 34 `request_logs` rows
were the Admin Console's own traffic, not zero-trust enforcement points.
Gates only `RequestAuditFilter`'s audit *output*; trace ID/`X-User-Id`
handling stay universal.

### 5.2c IdP Identity Sync (ADR-014; machine identities ADR-015; UI + relations Stage 15)

`gateway-service/.../identity` package:

- **`IdentityType`** — enum `USER`/`GROUP`/`ROLE`/`CLIENT` (`CLIENT` added by ADR-015).
- **`IdpIdentity`** — R2DBC record (`@Table("idp_identities")`); `id`/`lastSynced`
  left `null` on construction for freshly fetched (not-yet-persisted)
  identities, same DB-generated-PK convention as `RequestLog`.
- **`IdpIdentityRepository`** — `upsert(...)` is a real `@Query` native
  `INSERT ... ON CONFLICT (type, external_id) DO UPDATE ... RETURNING id`
  (`Mono<UUID>`, not `Mono<Void>` — Stage 15 change, so
  `IdentitySyncService` can resolve relations' subject/target ids without a
  second lookup query per identity; deliberately **not** `@Modifying`,
  which would switch result handling to row-count semantics, wrong for a
  `RETURNING` clause), not `save()` (which would violate the unique
  constraint on the second sync cycle for the same identity);
  `existsByTypeAndName`/`searchByTypeAndName` take a plain `String type`
  rather than `IdentityType`, sidestepping any question about derived-query
  *parameter* enum binding (entity *field* mapping — reading `type` back
  out — is the well-established direction and needed no such care).
- **`IdpClient`** — adapter interface (`fetchUsers()`/`fetchGroups()`/`fetchRoles()`/`fetchClients()`/`fetchRelations()`,
  identities as `Flux<IdpIdentity>`, relations as `Flux<IdpRelation>` — Stage
  15 adds the last one). **`KeycloakIdpAdapter`** is the only implementation
  today (`@ConditionalOnProperty(zte.idp.provider=keycloak,
  matchIfMissing=true)`) — a future Azure Entra ID/AWS IAM adapter needs no
  changes anywhere else in this package. Constructor-injects
  `WebClient.Builder` (mirrors `McpBackendClient`'s pattern); obtains a
  fresh client-credentials token per `fetchX()` call, reusing `zte-gateway`'s
  existing service account (granted `realm-management`'s
  `view-users`/`view-realm`/`view-clients` roles in `keycloak/realm-export.json`)
  rather than a new dedicated client. `fetchClients()` excludes Keycloak's
  every-realm builtin clients (`account`, `broker`, `realm-management`,
  `admin-cli`, `security-admin-console`, plus `account-`/`broker-`-prefixed
  satellite clients — `isSystemClient(...)`, package-visible + `static`
  specifically for a direct unit test, Stage 15) — still fetches every
  *other* client regardless of `serviceAccountsEnabled` (ADR-015's original
  MVP simplification, unchanged); `external_id`=the client's internal UUID,
  `name`=`clientId`, `displayName`=`name`→`description`→`clientId` fallback.
  `fetchRelations()` (Stage 15) — user group-memberships + realm-role
  mappings directly; non-system clients' realm-role mappings via their
  service-account user (`GET /clients/{id}/service-account-user` → role
  mappings on *that* user, reported against the client's own external id —
  Keycloak stores client role assignments there, and `idp_identities` never
  caches service-account users as their own `USER` row); a client with no
  service account 404s there, caught per-client via `onErrorResume` rather
  than failing the whole fetch.
- **`RelationType`** — enum `MEMBER_OF`/`HAS_ROLE` (Stage 15).
- **`IdpIdentityRelation`** — R2DBC record (`@Table("idp_identity_relations")`);
  `subjectId`/`targetId` are `idp_identities.id` internal PKs.
- **`IdpIdentityRelationRepository`** — `upsert(...)` (native `ON CONFLICT`,
  same rationale as `IdpIdentityRepository`'s), `findBySubjectId(UUID)` (the
  `GET .../relations` read path).
- **`IdpRelation`** — adapter-layer DTO, subject/target keyed by *Keycloak
  external id* (pre-resolution) — what `IdpClient.fetchRelations()` returns,
  before `IdentitySyncService` maps it to internal PKs.
- **`IdentitySyncService`** — `@Scheduled(fixedDelayString =
  "${zte.idp.sync-interval-ms:900000}")` (`refresh()`), driven by Spring's
  own `TaskScheduler` thread; `syncNow(): Mono<Integer>` (Stage 15: two
  passes) first upserts all four identity kinds via `syncIdentities()`,
  collecting a `Map<externalId, internalId>` from the new `RETURNING id`
  clause, then `syncRelations(map)` fetches+resolves+upserts every relation
  against that map — zero extra DB round trips to resolve ids, since every
  relation names an entity the same cycle's identity fetch already named;
  an unresolvable relation is logged and skipped, not a sync failure.
  Never calls `.block()` anywhere, so it never touches the Netty event loop
  by construction.
- **`IdentityUrn`** — `parse(String source, IdentityType defaultType):
  Optional<IdentityUrn>` (the one-arg `parse(source)` is a thin delegate to
  `parse(source, IdentityType.ROLE)`, unchanged for `users2service` callers).
  No prefix → implicit `defaultType` (category-supplied: `ROLE` for
  `users2service`, `CLIENT` for `service2service`/`agentMcpToolCalls` —
  ADR-015, since every rule in those two categories predates URN sources and
  was already a bare client id); unrecognized prefix → literal name of
  `defaultType` (not silently ignored); any `*`/`?` → `Optional.empty()`
  (not checkable against a fixed identity list); an explicit prefix
  (`role:`/`user:`/`group:`/`client:`) always overrides the default.
- **`IdentitySources`** — `enrich(List<String> realmRoles,
  JwtAuthenticationToken): List<String>`, used by
  `ZteAuthorizationFilter`/`AdminAuthorizationFilter` — bare role names
  (unchanged) plus `role:<r>`/`user:<preferred_username>`/`group:<g>` URNs.
  `enrichClient(String clientId): List<String>` (ADR-015), used by
  `ServiceToServiceAuthorizationFilter`/`YamlMcpPolicyEngine` — the bare
  client id (unchanged) plus its `client:<clientId>` URN form. Both build
  the enriched sources list passed to `PolicyMatcher.evaluate(...)` (§5.2) —
  `PolicyMatcher` itself required **zero** code changes for either ADR; it
  already does generic string-list matching over whatever `sources` it's
  given.
- **`OrphanedRuleChecker`** — `@PostConstruct` startup check +
  `@EventListener(PolicyDocumentReloadedEvent.class)` for reloads. Checks
  all three categories (`service2service`/`agentMcpToolCalls` added by
  ADR-015, `Flux.merge`d alongside `users2service`, each with its own
  default type): `IdentityUrn.parse(rule.source(), defaultType)` then
  `repository.existsByTypeAndName(...)`; logs SLF4J `WARN`
  `"ORPHANED RULE: ..."` when no match — never rejects or deletes.
  Deliberately decoupled from `PolicyValidator`/`PolicyMatcher` (which stay
  synchronous/zero-I/O per ADR-009 §8.2) via a new
  `PolicyDocumentReloadedEvent`, published by `PolicyDefinitionStore.doReload()`
  only on success (not from the constructor's initial load). Named,
  accepted race: this startup check can run before `IdentitySyncService`'s
  own first `@Scheduled` sync populates `idp_identities`, producing a
  transient false-positive that self-corrects within one sync interval, or
  immediately after a manual sync/reload. Each per-rule check has its own
  `onErrorResume` (found live during the ADR-014 session — a Flyway/R2DBC
  startup-ordering race caused a query failure that `flatMap`'s single-error
  propagation silently dropped for a second concurrent rule) — this
  per-rule resilience is what makes `Flux.merge`-ing three category streams
  safe without reintroducing that cross-category dropped-error risk.

**Schema**: `V5__create_idp_identities.sql` — `idp_identities` (`type`
`VARCHAR(10)`+`CHECK`, not a native Postgres enum — same reasoning as
`RuleEffect`; `UNIQUE (type, external_id)`). Only `id`/`type`/`external_id`/
`name`/`display_name`/`last_synced` — no IdP secrets or credentials are ever
cached. `V6__add_client_identity_type.sql` (ADR-015) widens the `type`
`CHECK` constraint to add `'CLIENT'` — no new column or table, keeping
machine identities in the same unified cache as users/groups/roles.
`V7__create_idp_identity_relations.sql` (Stage 15) — `idp_identity_relations`
(`subject_id`/`target_id` reference `idp_identities.id`, `ON DELETE
CASCADE`; `relation_type` `VARCHAR(20)`+`CHECK IN ('MEMBER_OF','HAS_ROLE')`,
same non-native-enum reasoning; `UNIQUE (subject_id, target_id,
relation_type)`).

### 5.2d APIM Inventory Registry (ADR-016)

`gateway-service/.../inventory` package — a central registry of REST
services and MCP agents this gateway fronts, onboarded via the Admin
Console, auto-discovered, and health-monitored:

- **`TargetType`** — enum `REST`/`MCP`. **`InventoryStatus`** — enum
  `PENDING`/`ACTIVE`/`WARNING`/`DOWN`; `PENDING`→`ACTIVE`/`WARNING` is set
  once by `AutoDiscoveryWorker` right after registration, `ACTIVE`↔`DOWN`
  is toggled repeatedly by `HealthPollingService`'s ping — `WARNING` is
  never touched by the ping job (a successful raw health ping doesn't
  confirm the service's actual API/tool contract, so it must not silently
  clear a discovery failure).
- **`InventoryEntry`** — R2DBC record (`@Table("inventory_services")`);
  `managementUrl` (nullable, `management_url` column) is the optional
  target `HealthPollingService` pings instead of `baseUrl` (ADR-016
  amendment, 2026-08-11 — see below).
  **`HealthMetric`** — R2DBC record (`@Table("health_metrics")`), one row
  per service (`UNIQUE (service_id)`, not a history log), upserted in
  place. **`InventoryView`** — `InventoryEntry` left-joined with its
  `HealthMetric`, built in application code (`InventoryService.list()`
  zips both repositories' `findAll()`s into a `Map`), not via a native
  projected query — this project has no reliable precedent for
  unannotated-DTO R2DBC projection, and getting it wrong silently is
  exactly the class of subtle R2DBC failure this codebase has hit before.
- **`InventoryRepository`** — `updateStatus`/`updateFields` are both scoped
  `@Query` updates, not `save()`: constructing a full replacement
  `InventoryEntry` for an update either forces a read-then-write to
  preserve `created_at`, or nulls it and hits the column's `NOT NULL`
  constraint on a plain `UPDATE` (which never applies a column `DEFAULT`)
  — found live running `InventoryRegistryIT`, fixed with the scoped query.
  **`HealthMetricRepository`** — `upsertPingResult` (native `ON CONFLICT`,
  written by the poll job) and `upsertSuccessfulCallByServiceName` (same
  upsert shape, but resolves `service_id` via a `SELECT` subquery on the
  request's target *name* — one round trip, and a name with no matching
  row is a harmless no-op).
- **`InventoryService`** — `create()` persists a `PENDING` row and returns
  immediately; `AutoDiscoveryWorker`'s probe is fired as an isolated
  `.subscribe()`, not part of the returned `Mono` chain, so onboarding an
  unreachable/slow service never delays the API response. `update()`
  always resets to `PENDING` and re-triggers discovery (simpler than
  conditional re-discovery; can never leave a stale `ACTIVE` pointing at a
  changed URL). `delete()` cascades to `health_metrics` via `ON DELETE
  CASCADE`.
- **`AutoDiscoveryWorker`** — Java + Project Reactor (not Kotlin, despite
  the task's own framing — `gateway-service` has been a pure Java 21
  module since Stage 1; `zt-agents` is this repo's only Kotlin module, a
  deliberate, narrow choice). Builds a fresh `WebClient` per call (unlike
  `KeycloakIdpAdapter`/`McpBackendClient`'s one-fixed-target pattern —
  every inventory entry has a different `base_url`). `REST`: `GET
  {base_url}/v3/api-docs`. `MCP`: a stateless `POST {base_url}/message`
  JSON-RPC `tools/list` call — an explicit, named assumption (this
  gateway's own MCP proxy requires a `GET /sse` session handshake before
  any `POST /message`; discovery assumes that's not required for a
  one-shot schema probe, unverified against a real session-only agent).
  Any failure (timeout, non-2xx, connection error) → `WARNING`, never a
  thrown exception.
- **`HealthPollingService`** — `@Scheduled(fixedDelayString =
  "${zte.inventory.health-poll-interval-ms:60000}")` (`poll()`), `pollNow()`
  is the directly-callable core (mirrors `IdentitySyncService`'s
  `refresh()`/`syncNow()` split). Pings every `ACTIVE`/`WARNING`/`DOWN`
  service's `/actuator/health` (not `PENDING` — its `base_url` hasn't
  passed discovery yet, so a ping is premature noise). `statusTransition(...)`
  (the `ACTIVE`↔`DOWN` decision) is package-visible + `static` specifically
  for a direct unit test — same precedent `KeycloakIdpAdapter#isSystemClient`
  established; the `WebClient`-calling code itself has no dedicated
  unit test, proven only by `InventoryRegistryIT`.
- **`HealthTelemetryService`** — directly mirrors `RequestLogAuditService`'s
  architecture (`Sinks.Many` + one `Schedulers.boundedElastic()`
  subscriber) — the async, non-blocking fire-and-forget write ADR-016's
  own Self-Criticism instruction demanded. Fed by `RequestAuditFilter`
  (§5.2) on every 2xx routed response.

**Schema**: `V8__create_inventory_and_health.sql` — `inventory_services`
(`target_type`/`status` both `VARCHAR`+`CHECK`, same non-native-enum
reasoning as `idp_identities.type`; `name` `UNIQUE`), `health_metrics`
(`service_id` `UNIQUE REFERENCES inventory_services(id) ON DELETE CASCADE`).

**ADR-016 amendment, same day** — a follow-up task, framed as "`AutoDiscoveryWorker`/
`HealthPollingService` use a plain `WebClient`, bypassing mTLS," was
investigated and found factually incorrect before any code changed: both
classes inject the application's one autoconfigured default `WebClient.Builder`,
which already carries the gateway's mTLS client certificate whenever
`zte.mtls.enabled=true` — Spring Boot's own `ClientHttpConnectorAutoConfiguration`
applies `MtlsHttpClientConfig`'s single `ReactorClientHttpConnector` bean to
every such builder automatically, confirmed by bytecode inspection and by a
live `curl`-with-no-client-cert failing the TLS handshake against the exact
same URL these workers successfully probe. No `WebClient` wiring changed;
both classes' Javadoc now states the inheritance and how it was verified.
The amendment's real fix: `service-a`/`service-b` had no `/v3/api-docs`
endpoint at all (`springdoc-openapi-starter-webflux-ui:2.7.0`, added to both
modules — no `SecurityConfig` change needed, `ServiceSecurityConfig`
already `permitAll()`s everything on both services, mTLS being their whole
trust perimeter). Verified live: a freshly re-registered `service-a` at
`https://localhost:8081` now reaches `ACTIVE` via `AutoDiscoveryWorker`'s
`/v3/api-docs` probe, not `WARNING`. Also newly observed live, out of this
amendment's scope: `HealthPollingService.pingOne` immediately flips that
same entry to `DOWN` on its next poll cycle, because it pings `{base_url}/actuator/health`
on the mTLS port (8081) — but `service-a`/`service-b` only expose
`/actuator/health` on their separate plain management port (9081, see
§5.3), so this ping always 404s for both services regardless of the
OpenAPI fix. Pre-existing since Stage 16, not a regression from this
amendment — tracked in §9 roadmap rather than fixed here.

**ADR-016 amendment, 2026-08-11 — `management_url`.** Fixed the gap named
above: `inventory_services` gained an optional `management_url` column
(`V9__add_inventory_management_url.sql`, nullable — existing rows and
every `InventoryRegistryIT` WireMock target unaffected).
`HealthPollingService.healthCheckUrl(entry)` — package-visible, `static`,
directly unit-tested (same precedent as `statusTransition`) — pings
`managementUrl` when set, else falls back to `baseUrl` exactly as before.
Investigated, not assumed, before picking this over forcing mTLS onto the
management port itself: `service-a`/`service-b`'s management port is
plain HTTP by deliberate pre-existing design (`application.yml`'s own
comment; `docker-compose.yml`'s container `healthcheck` already depends on
it being unauthenticated). `managementUrl` doesn't hardcode a scheme — the
gateway's default `WebClient.Builder` carries the mTLS connector
regardless of target, so an operator whose own service protects its
management port with mTLS can still set `managementUrl` to an `https://`
address and get that. `InventoryEntry`/`InventoryView`/`AdminInventoryController.InventoryRequest`
all gained the matching `managementUrl` field (nullable throughout); the
Admin Console's onboarding dialog gained an optional "Management URL"
field and the registry table an extra column. Verified live: `service-a`
registered with `management_url=http://localhost:9081` stayed `ACTIVE`
through a full health-poll cycle instead of flipping to `DOWN`.

**ADR-016 amendment, 2026-08-12 — captured discovery payloads (API
Catalog).** `AutoDiscoveryWorker` previously discarded the `/v3/api-docs`/
`tools/list` response body after checking reachability; it's now captured
into a new `inventory_services.discovered_schema JSONB` column
(`V10__add_discovered_schema.sql`) and served on demand via
`GET /api/v1/admin/inventory/{id}/schema`, rendered in the Admin Console
(`SchemaDrawer.tsx`) as Swagger UI (`REST`) or a plain tool list (`MCP`).
R2DBC↔`jsonb` mapping was investigated by decompiling
`r2dbc-postgresql:1.0.7.RELEASE` (confirmed a built-in `JsonStringCodec`
handles `jsonb -> String` reads with no custom converter); writes use an
explicit `CAST(:schema AS jsonb)` in a native `@Query` since the driver's
default `String` bind is `VARCHAR`, which Postgres won't implicitly
coerce. `discovered_schema` deliberately never touches `InventoryEntry`/
`InventoryView`/`findAll()` — two new, independent `InventoryRepository`
queries handle it instead — so the registry list view's payload size is
unaffected by construction, not by convention. Switching the probes from
`.toBodilessEntity()` to `.retrieve().bodyToMono(String.class)` (needed to
read the body) introduced a real regression, caught before merging: an
empty response body now completes the `Mono` with **no element**
(`toBodilessEntity()` always emitted exactly one), which would have broken
the existing "empty-body 2xx is still `ACTIVE`" case — fixed with
`.defaultIfEmpty("")`. A `ObjectMapper#readTree` validity check guards the
`jsonb` cast against a non-JSON response body (Postgres would otherwise
reject the write and that failure would ride along with the chained
`status` update). Adding `swagger-ui-react` roughly tripled the Admin
Console's built bundle (589 KB → 1.88 MB raw, 538 KB gzipped) — a known,
accepted cost of the specified library, not mitigated (no code-splitting)
since that wasn't asked for.

**ADR-016 amendment, 2026-08-12 (second) — custom `docs_url` + synchronous
fetch.** Adds `inventory_services.docs_url` (`V11__add_docs_url.sql`,
nullable, `VARCHAR(512)`) — a full absolute URL `AutoDiscoveryWorker`
probes instead of `{base_url}/v3/api-docs` for `REST` targets when set —
and `POST /api/v1/admin/inventory/{id}/schema/fetch`, a synchronous,
UI-triggered discovery trigger. The task's own suggested simplification —
gate the Admin Console's "View Schema" button on `status === 'ACTIVE'` —
was investigated and found incorrect before implementing: `ACTIVE` is set
on any 2xx regardless of body validity, but `discovered_schema` is only
written when the body is valid JSON (a prior, deliberate decision), so a
2xx-with-empty-or-invalid-body reaches `ACTIVE` while capturing nothing —
already exercised by the pre-existing `crud_updateAndDelete` IT test's
empty-body stub, and made more likely by this amendment's own `docs_url`
(an operator typo can easily land on a non-JSON page). Implemented the
task's own named alternative instead: `InventoryView.hasSchema`, backed
by a new, cheap `InventoryRepository.findIdsWithDiscoveredSchema()` query
(`id`-only, never the payload) joined in memory the same way
`HealthMetric` already is. The extracted fetch logic is intentionally
*not* identical between the background and synchronous paths: the
background `discoverAndUpdateStatus` stays lenient (2xx is `ACTIVE`
regardless of body), while the new `fetchSchemaNow` is stricter — an
empty/non-JSON 2xx is a failure (`SchemaFetchException`, mapped to `502
Bad Gateway`, not the task's literal "400/500" — `502` is the correct
code for "this gateway couldn't get a valid response from an upstream it
proxies to," which is exactly what happened). `404` (`ServiceNotFoundException`)
for an unknown `id`. Frontend: optional "Docs URL" field, a "Fetch" (🔄)
button per row (Snackbar feedback, table refresh on success), "View
Schema" now disabled via `hasSchema` rather than `status`.

**ADR-016 amendment, 2026-08-12 (third) — inline Edit + confirmed
refetch-overwrite.** Frontend-only: an "Edit" (✏️) row action opens the
onboarding `Dialog` pre-filled and submits `PUT` instead of `POST`
(`editingService` state); a shared `closeDialog()` now resets form state
on every close path (Cancel, backdrop, and post-submit), not just after a
successful submit as before — closes a real gap the previous single-mode
dialog never surfaced. Backend needed no changes: `InventoryService.update`/
`updateFields` already threaded `docsUrl`/`managementUrl` (prior
amendment), and `updateDiscoveredSchema` is an unconditional `UPDATE` with
no conflict path — verified live by fetching an already-captured schema
twice and confirming both calls genuinely re-probe and return `200`.
Named, not fixed: `InventoryService.update` still has no duplicate-name
check the way `create()` does (§9.2).

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
`GatewayClient`'s `PolicyDto` mirrors the gateway's `PolicyRule` shape
(`id`/`effect`/`source`/`target`/`pathPattern`/`methods`/`priority`) since
ADR-012 — it used to mirror the DB row shape before `/api/v1/internal/policies`
became YAML-backed; `PolicyAuditorService` itself needed no change, since it
only calls `.toAuditLine()`/`.isEmpty()`/`.size` on the returned list.
Configuration (`ANTHROPIC_API_KEY`, model/timeout/max-tokens, gateway URI) is
loadable from `.env` via `spring-dotenv` since ADR-008 — env vars still take
precedence over `.env` values. See [ADR-007](adr/ADR-007-policy-auditor-agent.md)
and [ADR-008](adr/ADR-008-dotenv-configuration-management.md).

### 5.4a `zt-admin-ui` — Admin Console (ADR-012)

Vite + React + TypeScript SPA, Material UI, `react-oidc-context` for the
Keycloak login (client `zte-admin-ui`, authorization code + PKCE). `base:
'/admin/'`; not a Gradle subproject (see ADR-012's Decision 3 for why) — built
by `gateway-service`'s own Gradle build via the `com.github.node-gradle.node`
plugin (`buildAdminUi` `NpmTask`, feeding `processResources`), and packaged
into that jar's `static/admin/`. Two source files carry the logic: `App.tsx`
(login gate + shell) and `PolicyDashboard.tsx` (fetches `GET
/api/v1/admin/policies` with the OIDC access token, renders all three rule
categories as tables, "Reload Policies" button posts to `POST
/api/v1/admin/policies/reload`). No client-side routing — the OIDC
`redirect_uri` points at the exact served file, `/admin/index.html`.
`AuditTrail.tsx` (ADR-013) and `Identities.tsx` (ADR-014, `CLIENT` type
added by ADR-015) are added as further `Tabs` entries in `App.tsx`.
`PolicyDashboard.tsx` independently fetches the identity search endpoint to
flag rows in **all three** categories (`service2service`/`agentMcpToolCalls`
added by ADR-015, via a per-category `defaultSourceType` field driving the
same `IdentityUrn.parse`-mirroring logic) whose `source` isn't in the synced
cache (a small, intentionally duplicated TypeScript port, not a shared-state
lift — keeps the tabs self-contained).

`Identities.tsx` was rewritten in Stage 15: fetches `GET
/api/v1/admin/identities/search`, has a "Sync Now" button (`POST
/api/v1/admin/identities/sync`), and a "Quick search" `TextField` filtering
the fetched list by name (client-side, before per-type grouping). The list
is split into two `Stack`s — "Actors" (`USER`/`CLIENT` types) and "Access
Containers" (`GROUP`/`ROLE` types) — each type rendered as its own MUI
`Accordion` (`defaultExpanded` if non-empty). `USER`/`CLIENT` rows get an
"info" `IconButton` (plain emoji glyph, matching `PolicyDashboard`'s
existing icon convention, not `@mui/icons-material`) that opens an MUI
`Drawer` fetching `GET /api/v1/admin/identities/{id}/relations` and
rendering that identity's cached Roles/Groups in two `List`s.

`Inventory.tsx` (Stage 16, `App.tsx`'s "Registry" tab) — same plain MUI
`Table` pattern (not `@mui/x-data-grid`, the same repeatedly-reaffirmed
dependency call), columns Name/Type/Base URL/Status (a colored `Chip` —
green `ACTIVE`, amber `WARNING`, red `DOWN`, grey `PENDING`)/Ping (ms)/Last
Successful Call, plus a delete action per row. "Onboard Service" header
button opens an MUI `Dialog` form (name `TextField`, `target_type`
dropdown via a `select` `TextField`, `base_url` `TextField`) posting to
`POST /api/v1/admin/inventory` — this repo's first `Dialog`/`Select` usage,
no new dependency (both ship in `@mui/material` core).

### 5.5 Infrastructure

- **PostgreSQL 16-alpine** — JDBC/Flyway for migrations, plus R2DBC again as
  of ADR-013 (reintroduced after ADR-012 removed it — a different runtime
  purpose, the async `request_logs` write path, not the old policy-lookup
  one). `access_policies` (`V2`, dropped by `V3`/ADR-012) and
  `gateway_audit_log` (`V1`, dropped by `V4`/ADR-013) are both gone;
  `request_logs` (`V4`) is the only table on the runtime path today.
- **Keycloak 24.0.4** — realm `zte-realm` auto-imported from
  `keycloak/realm-export.json`; clients `zte-gateway` (confidential, UI +
  service-to-service), `agent-a`/`agent-b` (confidential, client credentials
  only), `zte-admin-ui` (public, authorization code + PKCE, ADR-012); roles
  `ADMIN`/`USER`; users `zte-admin`/`zte-test-user`. Password set post-start
  via `scripts/set-keycloak-password.sh` (import can't carry plaintext
  credentials). See [ADR-002](adr/ADR-002-identity-provider-configuration-strategy.md).
- **Certs** — `certs/generate-certs.sh` builds a one-off ZTE-CA and issues
  `client.p12` (shared by gateway + service-a as outbound client cert),
  `service-a.p12`/`service-b.p12` (server certs), `truststore.p12` (CA-only
  trust anchor). See [ADR-004](adr/ADR-004-mtls-implementation.md).

---

## 6. Data Model

`access_policies` (PostgreSQL, `role_name`/`path_pattern`/`methods`/`enabled`
columns) was the users2service data model through Stage 10 — introduced by
Flyway `V2__access_policies.sql` (ADR-003), dropped by `V3__drop_access_policies.sql`
once ADR-012 retired its only reader (`PolicyService`). No replacement table;
`zte-policies.yaml` below is the full replacement, not a migration of the
table into a new one.

`zte-policies.yaml` (file, not a DB table — ADR-011/ADR-012): one `PolicyDocument`
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

`request_logs` (PostgreSQL, Flyway `V4__create_request_logs_table.sql` +
`V12__extend_request_logs.sql`, ADR-013 + ADR-017) — the async, unified
REST+MCP request audit trail. Replaces `gateway_audit_log` (`V1`, dropped
in the same `V4` migration — never read or written by any code since
Stage 1):

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK, `DEFAULT gen_random_uuid()` |
| `timestamp` | `TIMESTAMPTZ` | `DEFAULT NOW()`, indexed descending for the "latest 100" query |
| `trace_id` | `VARCHAR(64)` | REST: the request's `X-Request-Id` (caller-supplied or gateway-generated). MCP: the session id. Indexed |
| `client_ip` | `VARCHAR(64)`, nullable | `X-Forwarded-For` first hop, else the raw connection address; always `null` for MCP rows |
| `user_agent` | `TEXT`, nullable | Always `null` for MCP rows |
| `process_id` | `VARCHAR(32)`, nullable | OS PID of the gateway JVM instance that handled the request — distinct from `trace_id`, which travels across services |
| `agent_id` | `VARCHAR(128)`, nullable | MCP only (ADR-017) — the calling agent's id; always `null` for REST rows |
| `tool_name` | `VARCHAR(128)`, nullable | MCP only (ADR-017) — the requested JSON-RPC tool name; always `null` for REST rows |
| `path` | `TEXT` | REST: the request path. MCP: always `/message` |
| `status_code` | `INTEGER`, nullable | |
| `message` | `TEXT`, nullable | MCP only (ADR-017) — the deny reason on a `DENIED` policy decision; unused by the REST write path |
| `initiator_client` | `VARCHAR(128)`, nullable | ADR-017. JWT `azp` (REST) / agent id (MCP) — the calling service/agent identity, `null` for a plain interactive user |
| `original_user_obo` | `VARCHAR(128)`, nullable | ADR-017, REST only. JWT `sub` — the identity the gateway's OBO token was minted for |
| `target_service` | `VARCHAR(255)`, nullable | ADR-017. `RequestTargetResolver`-derived service name, same convention as `health_metrics` |
| `http_method` | `VARCHAR(10)`, nullable | ADR-017. REST: the actual verb. MCP: hardcoded `POST` |
| `decision_effect` | `VARCHAR(10)`, nullable | ADR-017. `ALLOW`/`DENY`/`ERROR`, derived from `status_code` (2xx/401,403/else) — a coarse, post-hoc signal, not per-filter provenance |

Written by `RequestLogAuditService` (§5.2b, both `RequestAuditFilter` REST
rows and `LoggingMcpAuditService` MCP rows as of ADR-017), read via `GET
/api/v1/admin/audit-logs` (§7) — `findTop100ByOrderByTimestampDesc()`.

`idp_identities` (PostgreSQL, Flyway `V5__create_idp_identities.sql` +
`V6__add_client_identity_type.sql`, ADR-014/ADR-015) — the local IdP
identity cache. No secrets/credentials, ever:

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK, `DEFAULT gen_random_uuid()` |
| `type` | `VARCHAR(10)` + `CHECK (type IN ('USER','GROUP','ROLE','CLIENT'))` | Not a native Postgres enum — same reasoning as `RuleEffect`, avoids an R2DBC enum codec registrar; `CLIENT` added by `V6`/ADR-015 |
| `external_id` | `VARCHAR(255)` | The IdP's own identifier (Keycloak internal UUID); `UNIQUE (type, external_id)` |
| `name` | `VARCHAR(255)`, indexed with `type` | Username / group name / role name / OIDC `clientId` — what `IdentityUrn`/policy sources match against |
| `display_name` | `VARCHAR(255)`, nullable | firstName+lastName (USER, falling back to username), group name (GROUP), role description falling back to name (ROLE), client `name`→`description`→`clientId` fallback (CLIENT) |
| `last_synced` | `TIMESTAMPTZ` | `DEFAULT NOW()`, updated on every upsert |

Written by `IdentitySyncService` via `IdpIdentityRepository.upsert(...)` (a
real `INSERT ... ON CONFLICT (type, external_id) DO UPDATE ... RETURNING
id`, §5.2c), read by `OrphanedRuleChecker` and `GET
/api/v1/admin/identities/search` (§7).

`idp_identity_relations` (PostgreSQL, Flyway
`V7__create_idp_identity_relations.sql`, Stage 15) — many-to-many
relationships between `idp_identities` rows:

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK, `DEFAULT gen_random_uuid()` |
| `subject_id` | `UUID` | `REFERENCES idp_identities(id) ON DELETE CASCADE` |
| `target_id` | `UUID` | `REFERENCES idp_identities(id) ON DELETE CASCADE` |
| `relation_type` | `VARCHAR(20)` + `CHECK (relation_type IN ('MEMBER_OF','HAS_ROLE'))` | Not a native Postgres enum — same reasoning as `idp_identities.type`; `UNIQUE (subject_id, target_id, relation_type)` |
| `last_synced` | `TIMESTAMPTZ` | `DEFAULT NOW()`, updated on every upsert |

Written by `IdentitySyncService` via `IdpIdentityRelationRepository.upsert(...)`
(same `ON CONFLICT` shape, resolved against the same sync cycle's identity
upserts — no extra lookup query per relation), read by `GET
/api/v1/admin/identities/{id}/relations` (§7) — the local-cache-only Actor
detail view.

`inventory_services` (PostgreSQL, Flyway `V8__create_inventory_and_health.sql`,
`V9__add_inventory_management_url.sql`, `V10__add_discovered_schema.sql`,
`V11__add_docs_url.sql`, ADR-016 + amendments) — the APIM registry:

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK, `DEFAULT gen_random_uuid()` |
| `name` | `VARCHAR(255)` | `UNIQUE` — also the name `RequestTargetResolver`'s path-segment extraction must match for passive telemetry (§5.2d) to find this row |
| `target_type` | `VARCHAR(10)` + `CHECK (target_type IN ('REST','MCP'))` | Not a native Postgres enum — same reasoning as `idp_identities.type` |
| `base_url` | `VARCHAR(512)` | |
| `docs_url` | `VARCHAR(512)`, nullable | `V11` amendment — full absolute URL `AutoDiscoveryWorker` probes instead of `{base_url}/v3/api-docs` for `REST` targets when set; ignored for `MCP` (§5.2d) |
| `management_url` | `VARCHAR(512)`, nullable | `V9` amendment — `HealthPollingService` pings this instead of `base_url` when set; `NULL` falls back to `base_url` unchanged (§5.2d) |
| `status` | `VARCHAR(10)` + `CHECK (status IN ('ACTIVE','WARNING','DOWN','PENDING'))`, `DEFAULT 'PENDING'` | See `InventoryStatus`'s transition rules, §5.2d |
| `discovered_schema` | `JSONB`, nullable | `V10` amendment — raw response body from the last successful `AutoDiscoveryWorker` probe; deliberately excluded from `findAll()`/the list view (§5.2d) |
| `created_at` | `TIMESTAMPTZ` | `DEFAULT NOW()`, never touched by an update (see §5.2d's live-tested `updateFields` fix) |

`health_metrics` (same migration) — current health snapshot, one row per service:

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK, `DEFAULT gen_random_uuid()` |
| `service_id` | `UUID` | `UNIQUE REFERENCES inventory_services(id) ON DELETE CASCADE` |
| `last_ping_ms` | `INTEGER`, nullable | Written by `HealthPollingService`'s periodic ping |
| `actuator_status` | `VARCHAR(64)`, nullable | The polled service's own `/actuator/health` `status` field, or `"DOWN"` on ping failure/timeout |
| `last_successful_call` | `TIMESTAMPTZ`, nullable | Written passively by `HealthTelemetryService` on real 2xx routed traffic (§5.2) |
| `updated_at` | `TIMESTAMPTZ` | `DEFAULT NOW()`, updated on every upsert (either write path) |

Written/read entirely by the `inventory` package (§5.2d) and `GET
/api/v1/admin/inventory` (§7, joined with `inventory_services` in
application code, not a native query).

---

## 7. API Reference

| Endpoint | Method | Auth | Service | Purpose |
|---|---|---|---|---|
| `/api/v1/{name}/**` | any | JWT + YAML policy | gateway → any `REST`-type `inventory_services` entry | Dynamically routed by `InventoryRouteDefinitionLocator` (ADR-017) — `service-a`/`service-b` below are the two entries `InventoryBootstrapSeeder` seeds by default, not hardcoded routes |
| `/api/v1/service-b/restricted` | GET | JWT + YAML `service2service` policy | gateway → service-b | ADR-017 — deliberately has no `service2service` rule, exercises default-deny |
| `/api/v1/internal/policies` | GET | none (network perimeter only) | gateway | Feeds `zt-agents` (ADR-007), YAML-backed |
| `/api/v1/internal/policies/reload` | POST | none (network perimeter only) | gateway | No-downtime YAML policy reload (ADR-011) |
| `/api/v1/admin/policies` | GET | JWT + `ADMIN` YAML rule | gateway | Full policy document for the Admin Console (ADR-012) |
| `/api/v1/admin/policies/reload` | POST | JWT + `ADMIN` YAML rule | gateway | No-downtime reload, ADMIN-gated counterpart (ADR-012) |
| `/api/v1/admin/audit-logs` | GET | JWT + `ADMIN` YAML rule | gateway | Latest 100 `request_logs` rows for the Admin Console (ADR-013) |
| `/api/v1/admin/identities/sync` | POST | JWT + `ADMIN` YAML rule | gateway | Manual IdP identity sync trigger (ADR-014) |
| `/api/v1/admin/identities/search` | GET | JWT + `ADMIN` YAML rule | gateway | Search/list the `idp_identities` cache, `?type=&q=` (ADR-014) |
| `/api/v1/admin/identities/{id}/relations` | GET | JWT + `ADMIN` YAML rule | gateway | Roles/groups related to an Actor identity, local-cache-only (Stage 15) |
| `/api/v1/admin/inventory` | GET, POST | JWT + `ADMIN` YAML rule | gateway | List / onboard APIM registry entries (ADR-016) |
| `/api/v1/admin/inventory/{id}` | PUT, DELETE | JWT + `ADMIN` YAML rule | gateway | Update / remove a registry entry; `PUT` returns `409` on a name collision with another entry, `404` if `id` doesn't exist (ADR-016 + amendment) |
| `/api/v1/admin/inventory/{id}/schema` | GET | JWT + `ADMIN` YAML rule | gateway | Fetch the last successfully captured discovery payload, raw; `404` if none (ADR-016 amendment) |
| `/api/v1/admin/inventory/{id}/schema/fetch` | POST | JWT + `ADMIN` YAML rule | gateway | Synchronous, UI-triggered discovery; `200` on success, `404` unknown `id`, `502` unreachable/timed out/invalid JSON (ADR-016 amendment) |
| `/admin/**` | GET | none (SPA handles its own login) | gateway | Admin Console static bundle (ADR-012) |
| `/sse` | GET | JWT | gateway (MCP proxy) | Opens an MCP session; SSE stream |
| `/message?sessionId=<id>` | POST | JWT | gateway (MCP proxy) | JSON-RPC `tools/call`; result via SSE, not the response body |
| `/api/v1/service-a/hello` | GET | JWT + YAML policy | service-a | Demo endpoint, calls service-b |
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
   (ADR-011), the same call used by the REST-path filters. As of ADR-017,
   the same `persist()` call also writes a `RequestLog.forMcp(...)` row into
   `request_logs` via `RequestLogAuditService` — REST and MCP traffic share
   one audit table (§5.2b).
6. `POST /message` always returns `202 Accepted` — the real answer only ever
   arrives over SSE.

### 8.3 Current policy logic

`YamlMcpPolicyEngine` (ADR-011, replacing the Stage 8 `DummyMcpPolicyEngine`
placeholder) — real per-agent authorization: matches
`(IdentitySources.enrichClient(agentId), toolName)` (the bare `agentId` plus
its `client:<agentId>` URN form, ADR-015 — see §5.2c) against the
`agentMcpToolCalls` rules in `zte-policies.yaml` via the shared
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
  mTLS) — inconsistent with the mTLS-secured service-a/b calls. **Unaffected
  by ADR-018**: that ADR enforces client-cert presence *inbound* to the
  gateway (agent → gateway); this backend-facing hop (gateway → MCP backend,
  `mcp-backend.uri`) is a separate, still-open gap.
- `LoggingMcpAuditService`'s buffer is unbounded — a stuck downstream writer
  grows memory without limit.

---

## 9. Roadmap

### 9.1 Completed (see §2 for commits/ADRs)

Stages 1–16, plus the two undated additions (pre-commit doc automation,
`.env` config) — all ✅. Stage 11 (ADR-012) closed the "Full users2service
migration to YAML-only" item that used to be listed below; Stage 12
(ADR-013) closed the "DB-based request audit log" item that used to be
listed below too; Stage 13 (ADR-014) adds IdP identity sync and URN-based
`users2service` sources, Stage 14 (ADR-015) extends that to machine
identities (OIDC clients) and unifies URN sources across all three policy
categories, Stage 15 (Identities UI + Relations ADR) closes the
`fetchClients()`-noise-filtering backlog item ADR-015 itself named, adds
`idp_identity_relations` caching, and redesigns the Identities tab, and
Stage 16 (ADR-016) adds the APIM inventory registry, auto-discovery, and
health telemetry — none of these four are closed backlog items on their
own, all are new capabilities.

### 9.2 Backlog — general (from `CLAUDE.md` Stage 17+)

- [x] ~~A "Retry Discovery" Admin Console action, to clear a stuck
      `WARNING` inventory entry without deleting and re-onboarding it~~ —
      already covered: the Registry table's "Fetch" (🔄) button (ADR-016
      amendment, 2026-08-12 second) isn't `status`-gated, and
      `AutoDiscoveryWorker.fetchSchemaNow`'s success path unconditionally
      calls `updateStatus`, so clicking it on a `WARNING` entry whose
      target has since become reachable correctly recovers it to `ACTIVE`
      — no dedicated "Retry" action was needed. Verified live and via a
      new IT test (`fetchSchemaNow_onWarningService_recoversToActiveOnceReachable`,
      ADR-016 amendment, 2026-08-12 fourth).
- [ ] A `health_metrics` history table (ping latency over time), if
      operators need trend visibility rather than just current state (ADR-016).
- [ ] Validate `AutoDiscoveryWorker`'s MCP stateless-discovery assumption
      against a real session-only agent; fall back to a full `GET /sse`
      handshake for discovery if needed (ADR-016 Self-Critique).
- [ ] Reconciliation for stale `inventory_services`/`health_metrics` rows
      (a deleted/renamed service is only removed by an explicit `DELETE`)
      — the same backlog item already named for `idp_identities` (ADR-016).
- [ ] Warn on a name mismatch between a registered inventory service and
      any `GatewayRouteConfig` route it's meant to represent, so passive
      `last_successful_call` telemetry's exact-name-match requirement isn't
      a silent trap (ADR-016 Self-Critique).
- [x] ~~`HealthPollingService.pingOne` pings `{base_url}/actuator/health`
      on the target's main mTLS port, but `service-a`/`service-b` only
      expose `/actuator/health` on a separate plain management port~~ —
      fixed via the optional `inventory_services.management_url` column
      (ADR-016 amendment, 2026-08-11, §5.2d) — `HealthPollingService` pings
      it instead of `base_url` when set.
- [ ] `service-a`/`service-b`'s management port (9081/9082) is plain HTTP
      by deliberate design (their own Docker `healthcheck` depends on it
      needing no client cert) — genuinely enforcing mTLS end-to-end would
      mean adding `management.ssl.enabled=true` + `client-auth: need` to
      both services *and* reworking their Docker healthchecks to present a
      client certificate; `management_url` (above) supports pointing at an
      `https://` management endpoint today, but neither example service is
      configured that way (ADR-016 amendment, 2026-08-11).
- [ ] No size limit on a captured `discovered_schema` payload — bounded
      only by `zte.inventory.discovery-timeout-ms`, not response size; a
      lower-severity gap than on a public endpoint since onboarding is an
      `ADMIN`-only action against an operator-supplied URL (ADR-016
      amendment, 2026-08-12, Self-Criticism).
- [ ] Code-split `zt-admin-ui`'s bundle — `swagger-ui-react` roughly
      tripled it (589 KB → 1.88 MB raw); a natural candidate for a dynamic
      `import()` behind the "View Schema" action if bundle size becomes a
      real problem (ADR-016 amendment, 2026-08-12, Self-Criticism).
- [ ] A "Retry Discovery"-adjacent affordance distinguishing *why*
      `hasSchema` is `false` (never attempted / unreachable / reached but
      invalid) — today it's a single boolean by design, sufficient only
      for gating "View Schema" (ADR-016 amendment, 2026-08-12 second,
      Self-Criticism).
- [ ] `docs_url` has no validation that it points at the same
      host/service being registered — same operator-trusted-input
      posture as `base_url`/`management_url`, not a new trust boundary,
      but worth tightening if inventory onboarding is ever opened to a
      less-trusted role than `ADMIN` (ADR-016 amendment, 2026-08-12
      second, Self-Criticism).
- [x] ~~`InventoryService.update` has no duplicate-name check the way
      `create()` does~~ — fixed with `InventoryRepository.existsByNameAndIdNot`
      (excludes the row being edited, so a no-rename update never
      false-positives against itself), wired through `update()` and the
      `PUT` controller endpoint's error mapping the same way `create()`
      already handles `DuplicateServiceNameException`. Fixing this also
      surfaced and closed a second latent gap in the same method: `PUT`
      against an unknown `id` previously returned a bare `200` with an
      empty body (`updateFields`/`findById` both silently no-op/empty on
      a nonexistent row) instead of `404` — now raises
      `ServiceNotFoundException`, mapped consistently with every other
      inventory endpoint (ADR-016 amendment, 2026-08-12 fourth).
- [ ] Reduce `fetchRelations()`'s per-user/per-client HTTP call count if
      sync duration becomes a problem at larger realm scale — no known
      Keycloak Admin API batch endpoint for this today (Stage 15 ADR
      Self-Critique).
- [ ] `findByTargetId` + a reverse "info" affordance on Group/Role rows
      ("which Actors have this Group/Role") — the natural complement to
      today's Actor→Container-only direction (Stage 15 ADR).
- [ ] Filter `fetchClients()` further, to `serviceAccountsEnabled` clients
      only — Stage 15 already excludes Keycloak's realm-builtin clients;
      this would be a stricter, separate tightening for any remaining
      non-agent client with no service account.
- [ ] A visual distinction in the Identities tab between "actor" clients
      (referenced by ≥1 policy rule) and unused/built-in ones (ADR-015).
- [ ] UUID-based user URNs — today `user:<name>` only matches by
      `preferred_username` (ADR-014).
- [ ] Filesystem-watch or webhook-driven identity sync, replacing the fixed
      15-min `IdentitySyncService` polling interval (ADR-014).
- [ ] A demo Keycloak group in `zte-realm`, to close the integration-level
      test gap for `group:`-scoped `users2service` rules (ADR-014 Self-Critique).
- [ ] A second `IdpClient` implementation (Azure Entra ID or AWS IAM) — the
      concrete reason the adapter interface exists (ADR-014).
- [ ] Per-category `zte.policy.*.default-effect` overrides (today one
      `default-effect` applies to service2service and agentMcpToolCalls
      alike).
- [ ] Filesystem watch-based auto-reload, layered on
      `PolicyDefinitionStore.reload()` (today: explicit `POST
      /api/v1/internal/policies/reload`).
- [ ] Distributed tracing: Micrometer Tracing + Zipkin in Docker Compose —
      `X-Request-Id` (ADR-013) is a prerequisite primitive for this, not a
      replacement for it.
- [ ] Rate limiting: Spring Cloud Gateway `RequestRateLimiter` (Redis-backed).
- [ ] Docker Compose production profile: resource limits, health-check
      restart policies.
- [ ] ABAC extension: `condition` field on `PolicyRule` (SpEL against JWT
      claims).
- [ ] Full mTLS system test: service-a + service-b as real Testcontainers
      (covers TLS handshake rejection — current gap per ADR-005 §"mTLS
      Testing Gap").
- [ ] A generic mechanism so a future gateway-local `@RestController`
      inherits users2service enforcement automatically, instead of needing
      its own `AdminAuthorizationFilter`-style `WebFilter` — see ADR-012
      Self-Critique/Future Migration Path.
- [ ] Environment-configurable Keycloak/gateway URLs in `zt-admin-ui`
      (currently hardcoded in `main.tsx`, consistent with the rest of this
      MVP's `localhost` defaults — see ADR-012).
- [ ] True-`401` (no token at all) coverage in `request_logs` — currently
      invisible since Spring Security's own filter rejects before
      `RequestAuditFilter` runs (see ADR-013 Self-Critique).
- [x] MCP-audit unification: `LoggingMcpAuditService` now also writes into
      `request_logs`, populating `agent_id`/`tool_name` — ADR-017.
- [ ] Bounded buffer + overflow policy for `RequestLogAuditService` — same
      known gap `LoggingMcpAuditService` already has (§9.3).
- [ ] Per-filter `decision_effect` provenance — currently derived from the
      final HTTP status code alone, so it can't distinguish a ZTE-layer
      `DENY` from a downstream service's own error status (ADR-017
      Self-Criticism).

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
| High | `GlobalFilter`s (Spring Cloud Gateway's type) silently don't run for any gateway-local `@RestController` (no `InventoryRouteDefinitionLocator`-sourced route) or for requests denied before reaching them — found empirically twice now: `AdminAuthorizationFilter` (ADR-012, a USER-role JWT got `200` from the admin API) and `RequestAuditFilter` (ADR-013, denied/admin/internal requests weren't being logged) | ADR-012, ADR-013 | Both fixed by converting to plain `WebFilter`s; documented in both classes' Javadoc and both ADRs. Still no *generic* guard against a third instance of this mistake — real gap, backlog item §9.2 |
| ~~Medium~~ Resolved | ~~`@EnableScheduling` declared on `MtlsHttpClientConfig`, a `@ConditionalOnProperty`-gated config class that never activates when `zte.mtls.enabled=false` (true for every integration test) — no `@Scheduled` method anywhere in the app, including the pre-existing `HealthPollingService`, had ever actually fired during an IT test~~ | ADR-017 | Resolved — `@EnableScheduling` moved to `GatewayApplication` (always active, unconditional). Found live while debugging `InventoryRouteRefreshScheduler` never firing in integration tests |
| ~~Medium~~ Resolved | ~~`ZteAuthorizationFilter`'s service-principal detection required `realm_access.roles` to be completely empty (`roles.isEmpty() && isServicePrincipal(...)`), which is never true for a real Keycloak client-credentials token (every client gets default composite/scope roles automatically) — silently unexercised because MCP agents, the only prior service-credential callers, never reach this Gateway `GlobalFilter`~~ | ADR-017 | Resolved — dropped the `roles.isEmpty()` requirement; `isServicePrincipal(jwtAuth)` alone is sufficient. Found live via the new `service-a` machine client, the first service credential to ever exercise this exact Gateway-routed filter |
| Low | `decision_effect` in `request_logs` is derived purely from the final HTTP status code, not from which policy layer actually made the decision — can't distinguish a ZTE-layer `DENY` from a downstream service's own error status | ADR-017 | Named explicitly, not hidden; a per-filter `exchange` attribute would fix this properly but isn't needed at this MVP's scale — backlog item §9.2 |
| Low | MCP traffic produces two `request_logs` rows per interaction — `RequestAuditFilter`'s own generic REST audit of the raw `/sse`/`/message` HTTP requests, plus `LoggingMcpAuditService`'s MCP-specific row (agent/tool populated). Found live, not by any IT test | ADR-017 | Arguably correct (transport-level vs. semantic audit, same two-layer structure ADR-013 already established for REST); not silently discovered — named explicitly, no fix applied since suppressing it would also lose `X-Request-Id` visibility for that traffic |
| Medium | `switchIfEmpty` on a `Mono<Void>`-typed reactive chain can't distinguish "upstream had a value" from "upstream was empty" (a `Mono<Void>` never emits either way) — double-invokes the fallback. Found and fixed twice: `AdminAuthorizationFilter` (ADR-012) and (pre-existing, found empirically before this stage's rewrite) `RequestAuditFilter` (ADR-013) | ADR-012, ADR-013 | Both use `doFinally`/`defaultIfEmpty`+`instanceof` instead now. No static-analysis rule exists to catch a third occurrence automatically. |
| Medium | Shared HMAC secret for OBO tokens | ADR-004 | `ZTE_OBO_SECRET` env var; RS256 upgrade deferred (§9.4) |
| Medium | Server-side TLS cert rotation requires a restart (no hot-reload API) | ADR-004 | 1-year dev certs; production needs cert-manager + rolling restart |
| Medium | mTLS transport-layer enforcement untested in the integration suite (WireMock has no TLS) | ADR-005 | Full mTLS Testcontainers system test is backlog (§9.2) |
| Medium | MCP session state in-memory, single-instance | ADR-009 / §8.5 | Documented; needs sticky routing or shared store before scaling out |
| Medium | True-`401` (no token) requests aren't captured in `request_logs` — Spring Security's own filter rejects before `RequestAuditFilter` runs | ADR-013 | Named, not silently accepted; doesn't affect any existing test (all use present-but-wrong-role JWTs); backlog item §9.2 |
| Low | `client_ip` trusts `X-Forwarded-For` at face value, no validation the immediate hop is a trusted proxy | ADR-013 | Acceptable for this MVP's single-hop Docker-network deployment; a real LB-fronted deployment would need edge-level header stripping/validation |
| Low | `POST /api/v1/internal/policies/reload` has no auth beyond network-perimeter isolation, same posture as `InternalPolicyController` | ADR-011 | Acceptable for MVP (Docker-bridge only, not proxied externally); ADR-012 adds an ADMIN-JWT-gated counterpart for the human operator without removing this one |
| Low | `LoggingMcpAuditService` and `RequestLogAuditService` buffers are both unbounded | §8.5, ADR-013 | Backlog item §9.2/§9.3 |
| ~~Low~~ Resolved | ~~`agent_id`/`tool_name` in `request_logs` are always `null` from the REST path~~ | ADR-013 | Resolved by ADR-017 — `LoggingMcpAuditService` now writes MCP rows into the same table, populating both columns; always `null` for REST rows by design (not applicable to that traffic) |
| ~~Medium~~ Resolved | ~~5-minute policy cache window / two sources of truth for users2service~~ | ADR-003 / ADR-011 | Resolved by ADR-012 — `PolicyService`'s DB cache is deleted entirely; YAML is the sole source, no staleness window |
| Low | `PolicyMatcher` is a full linear scan per category per request | ADR-011 | Same `<100 rules` MVP scale ceiling as `access_policies`; negligible at that scale |
| Medium | `idp_identities` can be stale for up to `zte.idp.sync-interval-ms` (15 min default) — a Keycloak identity created/renamed after the last sync isn't URN-addressable until the next sync | ADR-014 | Deliberate tradeoff to keep `PolicyMatcher.evaluate()` zero-I/O (ADR-009 §8.2); `POST /api/v1/admin/identities/sync` gives an immediate manual override |
| Low | `OrphanedRuleChecker`'s `@PostConstruct` startup check and `IdentitySyncService`'s first `@Scheduled` run have no guaranteed ordering — a cold start can produce a transient false-positive "orphaned" warning | ADR-014 | Named, not silently accepted; self-corrects within one sync interval or after a manual sync/reload; purely observational (SLF4J only), never affects request handling |
| Low | No integration-level test exercises `group:`-scoped `users2service` matching end-to-end — `zte-realm` has no groups defined yet | ADR-014 | `groups-mapper` protocol mapper and `IdentitySources`'s group-claim handling are unit-tested in isolation (`IdentitySourcesTest`); backlog item §9.2 |
| ~~Low~~ Resolved | ~~`idp_identities` includes every Keycloak built-in client, not just the realm's real actors~~ | ADR-015 | Resolved by Stage 15 — `fetchClients()` now excludes `account`/`broker`/`realm-management`/`admin-cli`/`security-admin-console` and their satellite clients entirely (`isSystemClient(...)`); a stricter `serviceAccountsEnabled`-only filter remains a separate, still-open backlog item (§9.2) |
| Low | `KeycloakIdpAdapter.fetchClients()`/`fetchRelations()` have no dedicated mocked-`WebClient` unit test, same as `fetchUsers`/`fetchGroups`/`fetchRoles` | ADR-015, Stage 15 | Consistent with ADR-014 precedent — correctness proven by `IdentitySyncIT` (`manualSync_populatesClients`, `manualSync_excludesSystemClients`, `manualSync_thenRelationsEndpoint_reflectsRoleAssignment`) against a real Testcontainers Keycloak, not mocked HTTP |
| Medium | `fetchRelations()` makes 2 HTTP calls per user and 2 per non-system client (service-account lookup + role-mappings) — an N+1-shaped call pattern with no known Keycloak Admin API batch alternative | Stage 15 ADR | Accepted for this realm's current scale; named explicitly as a real backlog item (§9.2), not silently absorbed |
| Low | `idp_identity_relations` can be as stale as `idp_identities` itself — up to `zte.idp.sync-interval-ms` (15 min default) | Stage 15 ADR | Same accepted tradeoff `idp_identities` already has (ADR-014); manual sync gives an immediate override |
| Medium | `AutoDiscoveryWorker`'s MCP `tools/list` probe assumes a stateless `POST {base_url}/message` call — an agent that strictly requires the `GET /sse` session handshake even for discovery always lands in `WARNING`, not because it's actually broken | ADR-016 | Named explicitly as an assumption, not a spec fact; unverified against a real stateful-only MCP agent — backlog item §9.2 |
| Medium | Passive `last_successful_call` telemetry depends on an exact name match between a registered inventory entry and `RequestTargetResolver`'s path-derived service name — a mismatch silently means the entry never receives telemetry, with no warning | ADR-016 | Documented in `HealthMetricRepository.upsertSuccessfulCallByServiceName`'s Javadoc; no validation enforces the naming convention at onboarding time — backlog item §9.2 |
| Low | `WARNING` inventory status has no UI-driven way to clear other than deleting and re-onboarding the service | ADR-016 | Deliberate MVP scope; a "Retry Discovery" action is a natural low-effort extension — backlog item §9.2 |
| Low | `AutoDiscoveryWorker`/`HealthPollingService`'s actual `WebClient`-calling code has no dedicated mocked-HTTP unit test | ADR-016 | Consistent with the `KeycloakIdpAdapter`/`McpBackendClient` precedent — proven by `InventoryRegistryIT` against real WireMock targets instead; the one pure decision-logic piece (`HealthPollingService.statusTransition`) does have a direct unit test |
| Low | `inventory_services`/`health_metrics` accumulate no reconciliation — a deleted/renamed service is only removed by an explicit `DELETE`, never automatically | ADR-016 | Consistent with this repo's established posture (`idp_identities` has the same property) — not a new gap; backlog item §9.2 |

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

---

*This document reflects repo state at commit `8a85f75` (Stage 16, APIM Inventory
Registry, plus the mTLS/OpenAPI, management-URL health-polling, discovered-schema API
Catalog, custom-docs-URL/synchronous-fetch, inline-edit/refetch, and known-issues/
test-coverage amendments) plus Stage 17 (dynamic routing, audit unification, strict S2S
rules — `87d9976`, see §2) plus Stage 18 (smart mTLS enforcement — `<commit>`, see §2).
Keep it in sync the same way as README/CLAUDE.md — per CLAUDE.md's mandatory workflow,
update it alongside any task that completes a stage or changes the roadmap.*
