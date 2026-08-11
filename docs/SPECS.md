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
| 13 | IdP identity sync (`idp_identities` cache, `KeycloakIdpAdapter`), URN-based `users2service` sources (`user:`/`group:`/`role:`), orphaned-rule detection, Admin Console "Identities" tab | ✅ Complete | — | [014](adr/ADR-014-idp-identity-sync.md) |
| 14+ | Backlog (rate limiting, ABAC, MCP-audit unification…) | ⬜ Planned | — | see §9.2 |

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

---

## 3. System Architecture

### 3.1 Component topology

```
                              ┌───────────────────────────────────────────┐
                              │              gateway-service               │
                              │              port 8080 (HTTP)              │
  User ──[Keycloak JWT]──────►│  SecurityConfig (auth-library): JWT req'd  │
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
| 2 | Is the user allowed? | YAML `users2service` rules (`zte-policies.yaml`) — sole source of truth as of ADR-012 | `ZteAuthorizationFilter` (gateway-routed paths); `AdminAuthorizationFilter` (`/api/v1/admin/**`) |
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

- **`GatewayRouteConfig`** — declarative routes: `/api/v1/service-a/**`,
  `/api/v1/service-b/**` → `https://localhost:8081` / `:8082`.
- **`ZteAuthorizationFilter`** (`GlobalFilter`, order `HIGHEST_PRECEDENCE+100`)
  — users2service enforcement: extracts `realm_access.roles`, builds an
  enriched sources list via `IdentitySources.enrich(roles, jwtAuth)` (bare
  role names plus `role:`/`user:`/`group:` URN forms, ADR-014 — see §5.2c),
  consults the YAML `users2service` rules against that enriched list
  (explicit ALLOW/DENY short-circuits; no match →
  deny as of ADR-012, the DB-backed fallback was retired); 403 +
  `GATEWAY_ALREADY_ROUTED_ATTR` on deny (blocks `NettyRoutingFilter`). A JWT
  with no realm roles and an `azp` other than the interactive user client
  (`zte.policy.user-client-id`) is service2service traffic and passes through
  untouched — see next bullet. **Only runs for Gateway-routed requests** —
  `GlobalFilter` is invoked by `FilteringWebHandler`, which only handles
  requests `RoutePredicateHandlerMapping` matches to a `GatewayRouteConfig`
  route; a local `@RestController` with no route entry never reaches it (see
  `AdminAuthorizationFilter` below, and ADR-012's Self-Critique for how this
  was found — empirically, via a USER-role JWT getting `200` from the new
  admin API before the fix).
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
  Admin Console's own housekeeping calls and health checks.
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
  DB-generated-PK convention.
- **`RequestLogRepository`** — `ReactiveCrudRepository<RequestLog, UUID>`,
  one derived query, `findTop100ByOrderByTimestampDesc()`.
- **`RequestLogAuditService`** — directly mirrors
  `LoggingMcpAuditService`'s architecture (`Sinks.Many` + one
  `Schedulers.boundedElastic()` subscriber draining into `repository.save(...)`);
  a DB write failure is caught and degrades to an SLF4J warning line instead
  of propagating or being lost — the literal "keep SLF4J as fallback"
  requirement.

`agentId`/`toolName` on `RequestLog` are always `null` from this path — the
given schema has no subject/user-id column, and this integration point is
REST-gateway-only (not `LoggingMcpAuditService`/MCP); reserved for a future
unification (§9.4).

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

### 5.2c IdP Identity Sync (ADR-014)

`gateway-service/.../identity` package:

- **`IdentityType`** — enum `USER`/`GROUP`/`ROLE`.
- **`IdpIdentity`** — R2DBC record (`@Table("idp_identities")`); `id`/`lastSynced`
  left `null` on construction for freshly fetched (not-yet-persisted)
  identities, same DB-generated-PK convention as `RequestLog`.
- **`IdpIdentityRepository`** — `upsert(...)` is a real `@Modifying @Query`
  native `INSERT ... ON CONFLICT (type, external_id) DO UPDATE`, not
  `save()` (which would violate the unique constraint on the second sync
  cycle for the same identity); `existsByTypeAndName`/`searchByTypeAndName`
  take a plain `String type` rather than `IdentityType`, sidestepping any
  question about derived-query *parameter* enum binding (entity *field*
  mapping — reading `type` back out — is the well-established direction and
  needed no such care).
- **`IdpClient`** — adapter interface (`fetchUsers()`/`fetchGroups()`/`fetchRoles()`,
  each `Flux<IdpIdentity>`). **`KeycloakIdpAdapter`** is the only
  implementation today (`@ConditionalOnProperty(zte.idp.provider=keycloak,
  matchIfMissing=true)`) — a future Azure Entra ID/AWS IAM adapter needs no
  changes anywhere else in this package. Constructor-injects
  `WebClient.Builder` (mirrors `McpBackendClient`'s pattern); obtains a
  fresh client-credentials token per `fetchX()` call, reusing `zte-gateway`'s
  existing service account (granted `realm-management`'s
  `view-users`/`view-realm` roles in `keycloak/realm-export.json`) rather
  than a new dedicated client.
- **`IdentitySyncService`** — `@Scheduled(fixedDelayString =
  "${zte.idp.sync-interval-ms:900000}")` (`refresh()`), driven by Spring's
  own `TaskScheduler` thread; `syncNow(): Mono<Integer>` fetches all three
  kinds and upserts each — never calls `.block()`, so it never touches the
  Netty event loop by construction.
- **`IdentityUrn`** — `parse(String source): Optional<IdentityUrn>`. No
  prefix → implicit `ROLE` (backward compat); unrecognized prefix → literal
  `ROLE` name (not silently ignored); any `*`/`?` → `Optional.empty()` (not
  checkable against a fixed identity list).
- **`IdentitySources`** — `enrich(List<String> realmRoles,
  JwtAuthenticationToken): List<String>`. Builds the enriched sources list
  `ZteAuthorizationFilter`/`AdminAuthorizationFilter` pass to
  `PolicyMatcher.evaluate(...)` (§5.2) — bare role names (unchanged) plus
  `role:<r>`/`user:<preferred_username>`/`group:<g>` URNs.
  `PolicyMatcher` itself required **zero** code changes; it already does
  generic string-list matching over whatever `sources` it's given.
- **`OrphanedRuleChecker`** — `@PostConstruct` startup check +
  `@EventListener(PolicyDocumentReloadedEvent.class)` for reloads. For each
  `users2service` rule, `IdentityUrn.parse(rule.source())` then
  `repository.existsByTypeAndName(...)`; logs SLF4J `WARN`
  `"ORPHANED RULE: ..."` when no match — never rejects or deletes.
  Deliberately decoupled from `PolicyValidator`/`PolicyMatcher` (which stay
  synchronous/zero-I/O per ADR-009 §8.2) via a new
  `PolicyDocumentReloadedEvent`, published by `PolicyDefinitionStore.doReload()`
  only on success (not from the constructor's initial load). Named,
  accepted race: this startup check can run before `IdentitySyncService`'s
  own first `@Scheduled` sync populates `idp_identities`, producing a
  transient false-positive that self-corrects within one sync interval, or
  immediately after a manual sync/reload.

**Schema**: `V5__create_idp_identities.sql` — `idp_identities` (`type`
`VARCHAR(10)`+`CHECK`, not a native Postgres enum — same reasoning as
`RuleEffect`; `UNIQUE (type, external_id)`). Only `id`/`type`/`external_id`/
`name`/`display_name`/`last_synced` — no IdP secrets or credentials are ever
cached.

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
`AuditTrail.tsx` (ADR-013) and `Identities.tsx` (ADR-014) are the same shape,
added as further `Tabs` entries in `App.tsx`. `Identities.tsx` fetches `GET
/api/v1/admin/identities/search` and has a "Sync Now" button (`POST
/api/v1/admin/identities/sync`); `PolicyDashboard.tsx` also independently
fetches that same search endpoint to flag `users2service` rows whose
`source` isn't in the synced cache (a small, intentionally duplicated
TypeScript port of `IdentityUrn.parse`, not a shared-state lift — keeps the
tabs self-contained).

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

`request_logs` (PostgreSQL, Flyway `V4__create_request_logs_table.sql`,
ADR-013) — the async request audit trail. Replaces `gateway_audit_log` (`V1`,
dropped in the same migration — never read or written by any code since
Stage 1):

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK, `DEFAULT gen_random_uuid()` |
| `timestamp` | `TIMESTAMPTZ` | `DEFAULT NOW()`, indexed descending for the "latest 100" query |
| `trace_id` | `VARCHAR(64)` | The request's `X-Request-Id` (caller-supplied or gateway-generated); indexed |
| `client_ip` | `VARCHAR(64)`, nullable | `X-Forwarded-For` first hop, else the raw connection address |
| `user_agent` | `TEXT`, nullable | |
| `process_id` | `VARCHAR(32)`, nullable | OS PID of the gateway JVM instance that handled the request — distinct from `trace_id`, which travels across services |
| `agent_id` | `VARCHAR(128)`, nullable | Always `null` from the REST path today — reserved for a future MCP-audit unification (§9.4) |
| `tool_name` | `VARCHAR(128)`, nullable | Same as `agent_id` |
| `path` | `TEXT` | |
| `status_code` | `INTEGER`, nullable | |
| `message` | `TEXT`, nullable | Currently unused by the REST write path |

Written by `RequestLogAuditService` (§5.2b), read via `GET
/api/v1/admin/audit-logs` (§7) — `findTop100ByOrderByTimestampDesc()`.

`idp_identities` (PostgreSQL, Flyway `V5__create_idp_identities.sql`,
ADR-014) — the local IdP identity cache. No secrets/credentials, ever:

| Column | Type | Notes |
|---|---|---|
| `id` | `UUID` | PK, `DEFAULT gen_random_uuid()` |
| `type` | `VARCHAR(10)` + `CHECK (type IN ('USER','GROUP','ROLE'))` | Not a native Postgres enum — same reasoning as `RuleEffect`, avoids an R2DBC enum codec registrar |
| `external_id` | `VARCHAR(255)` | The IdP's own identifier (Keycloak internal UUID); `UNIQUE (type, external_id)` |
| `name` | `VARCHAR(255)`, indexed with `type` | Username / group name / role name — what `IdentityUrn`/`users2service` sources match against |
| `display_name` | `VARCHAR(255)`, nullable | firstName+lastName (USER, falling back to username), group name (GROUP), role description falling back to name (ROLE) |
| `last_synced` | `TIMESTAMPTZ` | `DEFAULT NOW()`, updated on every upsert |

Written by `IdentitySyncService` via `IdpIdentityRepository.upsert(...)` (a
real `INSERT ... ON CONFLICT (type, external_id) DO UPDATE`, §5.2c), read by
`OrphanedRuleChecker` and `GET /api/v1/admin/identities/search` (§7).

---

## 7. API Reference

| Endpoint | Method | Auth | Service | Purpose |
|---|---|---|---|---|
| `/api/v1/service-a/**` | any | JWT + YAML policy | gateway → service-a | Proxied REST call |
| `/api/v1/service-b/**` | any | JWT + YAML policy | gateway → service-b | Proxied REST call |
| `/api/v1/internal/policies` | GET | none (network perimeter only) | gateway | Feeds `zt-agents` (ADR-007), YAML-backed |
| `/api/v1/internal/policies/reload` | POST | none (network perimeter only) | gateway | No-downtime YAML policy reload (ADR-011) |
| `/api/v1/admin/policies` | GET | JWT + `ADMIN` YAML rule | gateway | Full policy document for the Admin Console (ADR-012) |
| `/api/v1/admin/policies/reload` | POST | JWT + `ADMIN` YAML rule | gateway | No-downtime reload, ADMIN-gated counterpart (ADR-012) |
| `/api/v1/admin/audit-logs` | GET | JWT + `ADMIN` YAML rule | gateway | Latest 100 `request_logs` rows for the Admin Console (ADR-013) |
| `/api/v1/admin/identities/sync` | POST | JWT + `ADMIN` YAML rule | gateway | Manual IdP identity sync trigger (ADR-014) |
| `/api/v1/admin/identities/search` | GET | JWT + `ADMIN` YAML rule | gateway | Search/list the `idp_identities` cache, `?type=&q=` (ADR-014) |
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

Stages 1–13, plus the two undated additions (pre-commit doc automation,
`.env` config) — all ✅. Stage 11 (ADR-012) closed the "Full users2service
migration to YAML-only" item that used to be listed below; Stage 12
(ADR-013) closed the "DB-based request audit log" item that used to be
listed below too; Stage 13 (ADR-014) adds IdP identity sync and URN-based
`users2service` sources — not a closed backlog item, a new capability.

### 9.2 Backlog — general (from `CLAUDE.md` Stage 14+)

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
- [ ] MCP-audit unification: have `LoggingMcpAuditService` write into
      `request_logs` too, populating the currently-always-null
      `agent_id`/`tool_name` columns (see ADR-013 Future Migration Path).
- [ ] Bounded buffer + overflow policy for `RequestLogAuditService` — same
      known gap `LoggingMcpAuditService` already has (§9.3).

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
| High | `GlobalFilter`s (Spring Cloud Gateway's type) silently don't run for any gateway-local `@RestController` (no `GatewayRouteConfig` route) or for requests denied before reaching them — found empirically twice now: `AdminAuthorizationFilter` (ADR-012, a USER-role JWT got `200` from the admin API) and `RequestAuditFilter` (ADR-013, denied/admin/internal requests weren't being logged) | ADR-012, ADR-013 | Both fixed by converting to plain `WebFilter`s; documented in both classes' Javadoc and both ADRs. Still no *generic* guard against a third instance of this mistake — real gap, backlog item §9.2 |
| Medium | `switchIfEmpty` on a `Mono<Void>`-typed reactive chain can't distinguish "upstream had a value" from "upstream was empty" (a `Mono<Void>` never emits either way) — double-invokes the fallback. Found and fixed twice: `AdminAuthorizationFilter` (ADR-012) and (pre-existing, found empirically before this stage's rewrite) `RequestAuditFilter` (ADR-013) | ADR-012, ADR-013 | Both use `doFinally`/`defaultIfEmpty`+`instanceof` instead now. No static-analysis rule exists to catch a third occurrence automatically. |
| Medium | Shared HMAC secret for OBO tokens | ADR-004 | `ZTE_OBO_SECRET` env var; RS256 upgrade deferred (§9.4) |
| Medium | Server-side TLS cert rotation requires a restart (no hot-reload API) | ADR-004 | 1-year dev certs; production needs cert-manager + rolling restart |
| Medium | mTLS transport-layer enforcement untested in the integration suite (WireMock has no TLS) | ADR-005 | Full mTLS Testcontainers system test is backlog (§9.2) |
| Medium | MCP session state in-memory, single-instance | ADR-009 / §8.5 | Documented; needs sticky routing or shared store before scaling out |
| Medium | True-`401` (no token) requests aren't captured in `request_logs` — Spring Security's own filter rejects before `RequestAuditFilter` runs | ADR-013 | Named, not silently accepted; doesn't affect any existing test (all use present-but-wrong-role JWTs); backlog item §9.2 |
| Low | `client_ip` trusts `X-Forwarded-For` at face value, no validation the immediate hop is a trusted proxy | ADR-013 | Acceptable for this MVP's single-hop Docker-network deployment; a real LB-fronted deployment would need edge-level header stripping/validation |
| Low | `POST /api/v1/internal/policies/reload` has no auth beyond network-perimeter isolation, same posture as `InternalPolicyController` | ADR-011 | Acceptable for MVP (Docker-bridge only, not proxied externally); ADR-012 adds an ADMIN-JWT-gated counterpart for the human operator without removing this one |
| Low | `LoggingMcpAuditService` and `RequestLogAuditService` buffers are both unbounded | §8.5, ADR-013 | Backlog item §9.2/§9.3 |
| Low | `agent_id`/`tool_name` in `request_logs` are always `null` from the REST path — the given schema has no subject/user-id column | ADR-013 | Admin Console's "Agent/User ID" column shows blank for today's REST traffic; reserved for a future MCP-audit unification (§9.2) |
| ~~Medium~~ Resolved | ~~5-minute policy cache window / two sources of truth for users2service~~ | ADR-003 / ADR-011 | Resolved by ADR-012 — `PolicyService`'s DB cache is deleted entirely; YAML is the sole source, no staleness window |
| Low | `PolicyMatcher` is a full linear scan per category per request | ADR-011 | Same `<100 rules` MVP scale ceiling as `access_policies`; negligible at that scale |
| Medium | `idp_identities` can be stale for up to `zte.idp.sync-interval-ms` (15 min default) — a Keycloak identity created/renamed after the last sync isn't URN-addressable until the next sync | ADR-014 | Deliberate tradeoff to keep `PolicyMatcher.evaluate()` zero-I/O (ADR-009 §8.2); `POST /api/v1/admin/identities/sync` gives an immediate manual override |
| Low | `OrphanedRuleChecker`'s `@PostConstruct` startup check and `IdentitySyncService`'s first `@Scheduled` run have no guaranteed ordering — a cold start can produce a transient false-positive "orphaned" warning | ADR-014 | Named, not silently accepted; self-corrects within one sync interval or after a manual sync/reload; purely observational (SLF4J only), never affects request handling |
| Low | No integration-level test exercises `group:`-scoped `users2service` matching end-to-end — `zte-realm` has no groups defined yet | ADR-014 | `groups-mapper` protocol mapper and `IdentitySources`'s group-claim handling are unit-tested in isolation (`IdentitySourcesTest`); backlog item §9.2 |

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

---

*This document reflects repo state at commit `e5e1c65` (Stage 12, R2DBC Audit Logging +
Distributed Tracing). Keep it in sync the same way as README/CLAUDE.md — per CLAUDE.md's
mandatory workflow, update it alongside any task that completes a stage or changes the
roadmap.*
