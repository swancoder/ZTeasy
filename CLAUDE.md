# CLAUDE.md � ZTE Lightweight Project Guide

## Project Overview
**Product:** Lightweight Zero Trust Environment (ZTE) MVP.
**Goal:** Demonstrate AI-driven development (Gemini as Architect, Claude as Engineer).

## Execution Protocols (Mandatory)
1. **Chain of Thought (CoT):** Always output a `### THOUGHTS` block before any implementation.
2. **Self-Criticism:** Always output a `### CRITIQUE` block after a proposal to identify risks.
3. **ADR Requirement:** Every structural or architectural decision must be documented in `./docs/adr/ADR-XXX-name.md`.
4. **Prompt History:** Save every major task prompt into `./prompts-hist/XXX_name.txt`.
5. **SUMMARY**  Update README.md  after each completed task.
6. **Git Workflow:** Each completed task must end with a successful test run and a commit to `main`.


## Build & Development Commands
- **Build Project:** `./gradlew build` (requires `ANTHROPIC_API_KEY` env var for zt-agents, settable via `.env` — see ADR-008; also requires Node.js/npm, which builds the Admin Console — see ADR-012)
- **Build (skip zt-agents):** `./gradlew build -x :zt-agents:compileKotlin` (no API key needed)
- **Build (skip Admin Console):** `./gradlew build -x :gateway-service:buildAdminUi` (no Node/npm needed)
- **Run Unit Tests:** `./gradlew test`
- **Run Integration Tests:** `./gradlew integrationTest` (requires Docker; starts Postgres + Keycloak via Testcontainers)
- **Run All Tests:** `./gradlew test integrationTest`
- **Generate Dev Certs:** `chmod +x certs/generate-certs.sh && ./certs/generate-certs.sh`
- **Infrastructure:** `docker compose up -d` / `docker compose down`
- **Clean DB:** `./gradlew flywayClean` (use with caution)
- **Check Ports:** `netstat -an | grep -E "8080|8081|8082|8083|5432|8180"` (Gateway, Service-A, Service-B, ZT-Agents, DB, Keycloak)

## Code Style & Standards
- **Language:** Java 21 (Modern features only: Records, Pattern Matching).
- **Architecture:** API Gateway Pattern.
- **Naming:** kebab-case for URLs and configs.
- **Security:** Zero Trust principles � no implicit trust, mTLS for all inter-service traffic.
- **Auth:** OIDC/OAuth2 via Keycloak.

## Custom Skills & Tools
- `project-health-check`: Custom skill to verify Docker health and Gradle build status.
- `pre-commit-docs`: Slash command (`/pre-commit-docs`) — reads the staged diff and updates README.md, CLAUDE.md, docs/adr/, and prompts-hist/ before each commit. Definition: `.claude/commands/pre-commit-docs.md`.
- `generate-adr`: (Planned) Helper to scaffold a new ADR file with required CoT/Critique sections.

## Key Directories
- `./gateway-service`: The ZTE entry point (port 8080 HTTP).
- `./auth-library`: Shared security logic — `SecurityConfig`, `ZteAuditLogger`, `ReloadableSslContextFactory`, `UserContextTokenService`.
- `./service-a`: First protected downstream service (port 8081 HTTPS/mTLS, 9081 management).
- `./service-b`: Second protected downstream service — validates OBO token (port 8082 HTTPS/mTLS, 9082 management).
- `./zt-agents`: AI security copilot (Kotlin Spring Boot WebFlux, port 8083) — Policy Auditor Agent (Anthropic Claude).
- `./zt-admin-ui`: React Admin Console (Vite/TypeScript/MUI) — plain npm project, built by `gateway-service`'s Gradle build and served at `/admin/` (not run standalone).
- `./certs`: Dev certificate scripts (`generate-certs.sh`) and generated PKCS12 files (gitignored).
- `./prompts-hist`: Log of all Gemini-generated instructions.
- `./docs/adr`: Architectural Decision Records.
- `./docs/SPECS.md`: Consolidated technical spec — architecture, component specs, data model, API reference, risk register, progress-flagged roadmap. Single reference tying together README, CLAUDE.md, and the ADRs.

---

## Stage Progress

### Stage 1 — Infrastructure Bootstrap `COMPLETE` (commit `ddd0fbd`)
- [x] Gradle 8.12 multi-project build (Kotlin DSL, version catalog)
- [x] Docker Compose: PostgreSQL 16, Keycloak 24.0.4
- [x] `gateway-service` Spring Boot 3.4 skeleton with Spring Cloud Gateway
- [x] `auth-library` placeholder module
- ADR: ADR-001-architecture-pattern-gateway-vs-sidecar.md

### Stage 2 — Identity Provider `COMPLETE` (commits `5ddac01`, `b05a6b3`)
- [x] `keycloak/realm-export.json` — `zte-realm` with client `zte-gateway`, roles `ADMIN`/`USER`, users `zte-admin` + `zte-test-user`
- [x] Docker Compose `--import-realm` flag + directory-level bind mount (WSL2 inode fix)
- [x] `scripts/set-keycloak-password.sh` — post-start password via `kcadm.sh`
- [x] `gateway-service/application.yml` — Spring Security OAuth2 resource server pointing to Keycloak JWKS
- ADR: ADR-002-identity-provider-configuration-strategy.md

### Stage 3 — DB-Based Policy Enforcement `COMPLETE` (commit `bf873a5`)
- [x] V2 Flyway migration: `access_policies` table (role_name, path_pattern, methods, enabled)
- [x] Seed rows: ADMIN → `/api/v1/service-a/**` and `/api/v1/service-b/**` (GET, POST)
- [x] `AccessPolicy` record, `AccessPolicyRepository` (R2DBC reactive), `PolicyService` (Mono.cache 5 min, fail-closed)
- [x] `ZteAuthorizationFilter` GlobalFilter — extracts `realm_access.roles`, enforces DB policy, 403 JSON on deny
      - Order: `HIGHEST_PRECEDENCE + 100`; uses `GATEWAY_ALREADY_ROUTED_ATTR` to block NettyRoutingFilter
- [x] `service-a` sub-module: Spring Boot WebFlux, `GET /api/v1/service-a/hello`, port 8081
- [x] Gateway routes: `/api/v1/service-a/**` and `/api/v1/service-b/**`
- [x] Verification: ADMIN → 200 ✅ | no token → 401 ✅ | USER → 403 ✅
- ADR: ADR-003-reactive-policy-engine.md

### Stage 4 — mTLS & On-Behalf-Of Delegation `COMPLETE` (commit `e917be9`)
- [x] `certs/generate-certs.sh` — generates ZTE-CA, `client.p12`, `service-a.p12`, `service-b.p12`, `truststore.p12`
- [x] `auth-library/ReloadableSslContextFactory` — `AtomicReference<SslContext>` with per-connection lambda hot-swap (Reactor Netty pattern)
- [x] `auth-library/UserContextTokenService` — HMAC-SHA256 OBO JWT (30s TTL): create + validate
- [x] `auth-library/ZteAuditLogger` — structured `[ZTE-AUDIT]` log events (static utility)
- [x] `gateway-service/MtlsHttpClientConfig` — Netty HttpClient with `client.p12`; `@ConditionalOnProperty(zte.mtls.enabled)`
- [x] `gateway-service/UserContextPropagationFilter` — strips injected headers, creates OBO token (order `HIGHEST_PRECEDENCE + 200`)
- [x] `gateway-service/RequestAuditFilter` — logs `sub`, `azp`, path; injects trusted `X-User-Id` header (order `LOWEST_PRECEDENCE - 10`)
- [x] `service-b` module — port 8082 HTTPS/mTLS, 9082 management; `UserContextController` validates OBO token
- [x] `service-a/HelloController` — calls service-b via mTLS WebClient, forwards `X-ZTE-User-Context` unchanged
- [x] Docker Compose: service-a + service-b with cert volume mounts and management port exposure
- ADR: ADR-004-mtls-implementation.md

### Stage 5 — Unit Tests `COMPLETE` (commit `07382bf`)
- [x] `ZteAuthorizationFilterTest` — mocked `PolicyService`; StepVerifier with `ReactiveSecurityContextHolder.withAuthentication`
- [x] `UserContextPropagationFilterTest` — verifies OBO header generated, incoming headers stripped
- [x] `auth-library/UserContextTokenServiceTest` — token TTL expiry + HMAC signature validation
- [x] Bug fix: `switchIfEmpty` double-invocation in `ZteAuthorizationFilter` (Mono cold/hot evaluation)

### Stage 6 — E2E Integration Tests `COMPLETE` (commit `c28fe21`)
- [x] `src/it` source set + `integrationTest` Gradle task (separate from unit tests)
- [x] `BaseZteIntegrationTest` — singleton Testcontainers (PostgreSQL 16 + Keycloak 24.0.4) + in-process WireMock
- [x] `HappyPathIT` — gets JWT from Keycloak, calls gateway, verifies 200 + OBO token forwarded to WireMock stub
- [x] `ZeroTrustBreachIT` — no token → 401, expired token → 401, USER role → 403, spoofed OBO header → stripped
- [x] WSL2 Docker fix: `api.version=1.45` system property + `testcontainers.properties` strategy pin
- [x] 7/7 scenarios green
- ADR: ADR-005-integration-testing-strategy.md

### Stage 7 — AI Security Copilot (`zt-agents`) `COMPLETE` (commit `c85e77f`)
- [x] `zt-agents` — Kotlin Spring Boot WebFlux module (port 8083)
- [x] `AnthropicClient` — WebClient wrapper: model `claude-sonnet-4-6`, 120s timeout, `x-api-key` / `anthropic-version` headers
- [x] `GatewayClient` — fetches `GET /api/v1/internal/policies` from gateway
- [x] `PolicyAuditorService` — orchestrates: fetch → format → LLM → Markdown report
- [x] `PolicyAuditorController` — `POST /api/v1/agents/auditor/run` returns `{"report": "..."}`
- [x] `gateway-service/InternalPolicyController` — `GET /api/v1/internal/policies` (live DB, bypasses cache)
- [x] `gateway-service/InternalSecurityConfig` — `@Order(-100)` permitAll for `/api/v1/internal/**`
- [x] `ANTHROPIC_API_KEY` env var; model/timeout/max-tokens configurable via properties
- [x] `spring-dotenv` — loads `.env` (from `.env.example` template) into Spring `Environment`, env vars still take precedence
- ADR: ADR-007-policy-auditor-agent.md, ADR-008-dotenv-configuration-management.md

### Stage 8 — MCP Proxy & Interception Layer `COMPLETE`
- [x] `gateway-service/mcp` — WebFlux `RouterFunction`s (not Gateway routes — see ADR-009): `GET /sse`, `POST /message`
- [x] `McpSessionManager` — `sessionId → Sinks.Many<ServerSentEvent<String>>`, bridges POST result injection into the open SSE connection
- [x] `McpProxyHandler` — MCP HTTP+SSE handshake (`endpoint` event), JWT `sub` → `agent_id`, parses `tools/call` params
- [x] `DummyMcpPolicyEngine` — synchronous in-memory deny-list (`export_all_data`, `delete_all`, `drop_table`, and `delete`/`drop` substrings)
- [x] Deny path: `JsonRpcResponse.denied(...)` — successful JSON-RPC envelope with `result.isError=true`, injected via SSE; backend never called
- [x] Allow path: `McpBackendClient` forwards to `mcp-backend.uri` (`MCP_BACKEND_URI`), result passed through `DataMaskingFilter` stub (`NoOpDataMaskingFilter`)
- [x] `LoggingMcpAuditService` — non-blocking `Sinks.Many` + `Schedulers.boundedElastic()` subscriber, TSDB-ready stub
- [x] Unit tests: `McpSessionManagerTest`, `DummyMcpPolicyEngineTest`
- [x] `McpProxyIT` — full `GET /sse` → `POST /message` → SSE-injection round trip (deny path, allow path via WireMock, unknown-sessionId 400)
- ADR: ADR-009-mcp-proxy-interception-layer.md

### Stage 9 — Agent OAuth2 Client Credentials Auth (dead-end stub) `COMPLETE`
- [x] `keycloak/realm-export.json` — new confidential clients `agent-a`/`agent-b` in the existing `zte-realm` (Client Credentials only: `serviceAccountsEnabled=true`, `standardFlowEnabled=false`, `directAccessGrantsEnabled=false`)
- [x] No new SecurityConfiguration — existing `auth-library.SecurityConfig` (`anyExchange().authenticated()`) already covered `/sse` and `/message`
- [x] `McpProxyHandler.currentAgentId` — now prefers the `azp` claim (client_id) over `sub`, matching `RequestAuditFilter`'s existing convention
- [x] `McpProxyHandler.process` — dead-end stub: no longer calls `McpPolicyEngine`/`McpBackendClient`; logs `clientId`, audits `"STUBBED"`, emits `JsonRpcResponse.stubbed(id, clientId)` via SSE. Stage 8's 202/SSE-session transport contract unchanged — `policyEngine`/`backendClient`/`dataMaskingFilter` stay wired for a one-method re-enable later
- [x] `McpProxySecurityWebFluxTest` — `@WebFluxTest` slice: 401 without token (`/sse`, `/message`), 200/202 with a mocked JWT, unknown-session 400, verifies policy engine + backend are never touched
- [x] `McpProxyIT` updated — agent-a/agent-b client-credentials scenarios (stub names the authenticated client, backend never called), 401-without-token, unknown-sessionId 400
- [x] `hubspot-mcp/auth.py` — OAuth2 Client Credentials helper (`token_for_agent`)
- [x] `hubspot-mcp/agent_simulator.py` — rewritten: real MCP HTTP+SSE client (`GatewaySession`) against the gateway, replacing the old stdio-direct simulation
- ADR: ADR-010-agent-oauth2-client-credentials.md

### Stage 10 — YAML Policy Engine (users2service / service2service / agent@mcp) `COMPLETE`
- [x] `gateway-service/policy/def` — new package: `PolicyDocument`/`PolicyRule` (one shared rule shape across all three categories), `YamlPolicyFileLoader` (Jackson `YAMLMapper`, rejects unknown keys), `PolicyValidator` (collects all violations in one pass; duplicate ids/exact-duplicate rules are errors, ALLOW/DENY conflicts on the same tuple are warnings), `PolicyDefinitionStore` (loads+validates at startup — fails `ApplicationContext` refresh on invalid content; `AtomicReference` hot-swap, mirroring `ReloadableSslContextFactory` — ADR-004), `PolicyMatcher` (deny always overrides allow, `AntPathMatcher`-based)
- [x] `zte-policies.yaml` (`gateway-service/src/main/resources`, path configurable via `zte.policy.file`) — the single YAML file defining `users2service`/`service2service`/`agentMcpToolCalls` rules; default ships with empty users2service/service2service (DB/default-deny fallback unchanged) and populated agentMcpToolCalls (destructive-tool deny rules + per-agent grants)
- [x] `ZteAuthorizationFilter` (users2service) — YAML checked first (explicit ALLOW/DENY short-circuits), falls back unchanged to the existing DB-backed `PolicyService` (ADR-003) on no match; JWTs with no realm roles and a non-user `azp` now pass through to `ServiceToServiceAuthorizationFilter` instead of being blanket-denied
- [x] `ServiceToServiceAuthorizationFilter` (new, `HIGHEST_PRECEDENCE+150`) — service2service enforcement for JWTs identified by `azp`; YAML-only, default-deny
- [x] `YamlMcpPolicyEngine` (replaces `DummyMcpPolicyEngine`, deleted) — agent@mcp/tool-call enforcement; `McpProxyHandler.process()` restored to call the policy engine + `McpBackendClient` (supersedes Stage 9/ADR-010's dead-end stub — this **is** the former "Re-enable McpPolicyEngine/McpBackendClient" backlog item)
- [x] `POST /api/v1/internal/policies/reload` (`PolicyReloadController`, reuses the permitAll internal chain) — no-downtime reload, atomic swap, fail-closed on invalid content (keeps previous document)
- [x] `ZteAuditLogger.policyDecision(...)` — one shared structured audit method used identically by all three enforcement points (REST + MCP), so the log shape is unified by construction
- [x] Unit tests: `PolicyValidatorTest`, `PolicyMatcherTest`, `YamlPolicyFileLoaderTest`, `PolicyDefinitionStoreTest`, `YamlMcpPolicyEngineTest`, `ServiceToServiceAuthorizationFilterTest`, `DocumentationExampleConformanceTest` (loads `docs/examples/zte-policies-example.yaml` against the real schema); `ZteAuthorizationFilterTest`/`McpProxySecurityWebFluxTest`/`McpProxyIT` updated for the new behavior
- ADR: ADR-011-yaml-policy-engine.md; schema doc: `docs/policy-schema.md`; full 3-category example: `docs/examples/zte-policies-example.yaml`

### Stage 11 — Full YAML Migration + React Admin Console `COMPLETE`
- [x] Deleted `PolicyService`, `AccessPolicy`, `AccessPolicyRepository`, and the R2DBC dependencies they were the only consumers of — `users2service` is now YAML-only, same as `service2service`/`agentMcpToolCalls`; `ZteAuthorizationFilter`'s `NO_MATCH` case denies directly instead of falling back to a DB query
- [x] `V3__drop_access_policies.sql` (Flyway) — drops the now-unused table; `V1`'s `gateway_audit_log` untouched
- [x] `InternalPolicyController` (`GET /api/v1/internal/policies`) refactored to read `PolicyDefinitionStore` instead of the deleted repository — not originally scoped, but required since `zt-agents`' `GatewayClient`/`PolicyDto` depend on this endpoint; `PolicyDto` updated to the `PolicyRule` shape
- [x] `gateway-service/admin` (new package) — `AdminPolicyController` (`GET /api/v1/admin/policies` returns the full `PolicyDocument`; `POST /api/v1/admin/policies/reload`, ADMIN-JWT-gated, shares `PolicyReloadResult.toResponseEntity()` with `PolicyReloadController`), `AdminAuthorizationFilter` (plain `WebFilter`, **not** `GlobalFilter` — `ZteAuthorizationFilter`'s type only runs for `GatewayRouteConfig`-routed requests, found empirically when a USER-role JWT got `200` from the admin API before this filter existed), `AdminUiConfig` (`@Order(-90)` permitAll for `/admin/**` only + static resource serving)
- [x] `RealmRoles` (new shared utility, `policy/def` package) — JWT `realm_access.roles` extraction, used by both `ZteAuthorizationFilter` and `AdminAuthorizationFilter`
- [x] `zt-admin-ui/` — Vite + React + TypeScript + MUI + `react-oidc-context` SPA; plain npm project, **not** a Gradle subproject (root `build.gradle.kts`'s `subprojects{}` block would wrongly apply Java/Spring-BOM config to it); `base: '/admin/'`, OIDC `redirect_uri` points at the literal `/admin/index.html`
- [x] `gateway-service/build.gradle.kts` — `com.github.node-gradle.node` plugin applied directly (not via `settings.gradle.kts`), `buildAdminUi` `NpmTask` feeds `processResources`, copying `zt-admin-ui/dist` into `static/admin/`; `-x :gateway-service:buildAdminUi` skips it (mirrors the `-x :zt-agents:compileKotlin` escape hatch)
- [x] `keycloak/realm-export.json` — new public client `zte-admin-ui` (authorization code + PKCE, no service account)
- [x] Unit tests: `AdminAuthorizationFilterTest` (pins down both bugs found during implementation — see ADR-012); `ZteAuthorizationFilterTest` updated for deny-on-`NO_MATCH`; `McpProxySecurityWebFluxTest` updated (`@WebFluxTest` slices auto-detect `WebFilter` beans by type across the whole app, so `AdminAuthorizationFilter` needed mock deps added there too)
- ADR: ADR-012-full-yaml-migration-and-admin-console.md

### Stage 12 — R2DBC Audit Logging + Distributed Tracing `COMPLETE`
- [x] Found and fixed, before writing any new code: `RequestAuditFilter` had the same `Mono<Void>`+`switchIfEmpty` double-subscription bug as `AdminAuthorizationFilter` (ADR-012) — verified live (one curl call, `docker logs zte-service-a` showed the backend was hit exactly once, not twice, only because `NettyRoutingFilter` guards against re-routing an already-routed exchange)
- [x] `RequestAuditFilter` rewritten `GlobalFilter` → `WebFilter` (same fix class as `AdminAuthorizationFilter`) — needed so it sees `/api/v1/admin/**`/`/api/v1/internal/**` traffic *and* requests denied before reaching it; wraps `chain.filter()` in `.doFinally(...)` (not `switchIfEmpty`) so the final status code is observed regardless of outcome. Ordered `LOWEST_PRECEDENCE-100` (after Security, before `AdminAuthorizationFilter`). `X-User-Id` stripping is now unconditional (was JWT-branch-only before) — a strengthening. Named gap: true `401` (no token) still isn't captured, since Security's own filter rejects first — every existing "denied" test in this repo uses a present-but-wrong-role JWT, not a missing token, so this doesn't conflict with anything
- [x] `V4__create_request_logs_table.sql` (Flyway) — creates `request_logs` (`id UUID DEFAULT gen_random_uuid()`, `timestamp`/`trace_id` indexed) and **drops `gateway_audit_log`** (`V1`, Stage 1 — confirmed zero code references ever, consolidated rather than left orphaned, same call as `access_policies` in ADR-012)
- [x] R2DBC restored (`spring-r2dbc`/`r2dbc-postgresql`, `spring.r2dbc.*` in `application.yml`) — removed in ADR-012 for a different reason, back for this one. `BaseZteIntegrationTest`'s R2DBC `@DynamicPropertySource` wiring was never deleted, just dormant — needed no changes
- [x] `gateway-service/audit` (new package) — `RequestLog` (R2DBC record, `id=null` on construction → DB-generated INSERT, no `Persistable` needed), `RequestLogRepository` (`findTop100ByOrderByTimestampDesc`), `RequestLogAuditService` (directly mirrors `LoggingMcpAuditService`'s `Sinks.Many`+`boundedElastic` architecture; DB failure → SLF4J fallback, not lost/propagated)
- [x] `ZteAuditLogger.requestLog(...)` — sync SLF4J counterpart to the async DB write, same "log both" precedent as ADR-011
- [x] `AdminAuditLogController` (`GET /api/v1/admin/audit-logs`, `admin` package) — no new security wiring needed, `AdminAuthorizationFilter`'s path check already covers all of `/api/v1/admin/**`
- [x] `zt-admin-ui` — new `AuditTrail.tsx` tab (plain MUI `Table`, not `@mui/x-data-grid` — avoids an unproven dependency on the MUI v9/React 19 combo that already hit one typing quirk last stage), `Tabs` added to `App.tsx` switching Policies/Audit Trail
- [x] Unit tests: `RequestAuditFilterTest`, `RequestLogAuditServiceTest`; `McpProxySecurityWebFluxTest` needed a new `@MockBean RequestLogAuditService` (same reason as last stage's `PolicyDefinitionStore`/`PolicyMatcher` — `@WebFluxTest` auto-detects `WebFilter` beans by type)
- [x] New IT `RequestAuditIT` — proves the task's literal verification (allowed + denied requests both produce a `request_logs` row with matching `trace_id`/non-null `client_ip`), polled via Mockito-style Awaitility since the write is async; new `org.awaitility:awaitility` test dependency
- [x] **Same-day amendment**: found live that 30 of the first 34 `request_logs` rows were the Admin Console observing its own existence, not zero-trust enforcement — added `AuditExclusionProperties` (`zte.audit.excluded-path-prefixes` in `application.yml`, mirroring `PolicyDefaultsProperties`'s shape, deliberately separate from `zte-policies.yaml` and not hardcoded), default-excluding `/admin/`, `/api/v1/admin/`, `/api/v1/internal/`, `/actuator/`. Gates only `RequestAuditFilter`'s audit output (DB write + sync `requestLog` line) — `X-Request-Id`/`X-User-Id` handling stays universal
- ADR: ADR-013-postgres-audit-logging.md

### Stage 13 — IdP Identity Sync + URN-Based Policy Matching `COMPLETE`
- [x] `keycloak/realm-export.json` — `zte-gateway`'s service account granted `realm-management`'s `view-users`/`view-realm` client roles; new `oidc-group-membership-mapper` protocol mapper (claim `groups`) — implemented but currently unexercised, `zte-realm` has no groups defined yet
- [x] `V5__create_idp_identities.sql` (Flyway) — `idp_identities` table (`type` `VARCHAR(10)`+`CHECK`, not a native Postgres enum, same reasoning as `RuleEffect`; `UNIQUE (type, external_id)`); no secrets/passwords cached, only id/type/name
- [x] `gateway-service/identity` (new package) — `IdentityType`, `IdpIdentity` (R2DBC record), `IdpIdentityRepository` (native `ON CONFLICT` UPSERT via `@Modifying @Query`, not `save()` — `save()`'s null-id convention would violate the unique constraint on the second sync cycle), `IdpClient` interface (`fetchUsers`/`fetchGroups`/`fetchRoles`), `KeycloakIdpAdapter` (`@ConditionalOnProperty(zte.idp.provider=keycloak)`, fresh client-credentials token per call, reuses `zte-gateway`'s existing service account)
- [x] `IdentitySyncService` — `@Scheduled(zte.idp.sync-interval-ms:900000)`, non-blocking by construction (Spring's own `TaskScheduler` thread, no `.block()` anywhere in the chain); `AdminIdentitySyncController` (`POST /api/v1/admin/identities/sync`), `AdminIdentitySearchController` (`GET /api/v1/admin/identities/search?type=&q=`) — both covered by the existing `u2s-admin-console-api` YAML rule, no new security wiring
- [x] `IdentityUrn.parse` (`user:<name>`/`group:<name>`/`role:<name>`, no-prefix implies `ROLE`, unknown prefix treated as a literal role name, wildcard sources unparseable) + `IdentitySources.enrich(roles, jwtAuth)` — `PolicyMatcher` itself needed **zero** code changes, the enriched sources list (bare role names + URN forms) is built entirely at the `ZteAuthorizationFilter`/`AdminAuthorizationFilter` call sites
- [x] `PolicyDocumentReloadedEvent` (new `ApplicationEventPublisher`/`@EventListener` pattern in this codebase) — published by `PolicyDefinitionStore.doReload()` only on success; `OrphanedRuleChecker` (`@PostConstruct` + `@EventListener`) logs an SLF4J `WARN` `"ORPHANED RULE: ..."` for any `users2service` rule whose source doesn't resolve in `idp_identities` — purely observational, never rejects/deletes. Named, accepted cold-start race with `IdentitySyncService`'s own first `@Scheduled` run (self-corrects within one sync interval)
- [x] `zt-admin-ui` — new `Identities.tsx` tab (plain MUI `Table`, "Sync Now" button); `PolicyDashboard.tsx` independently fetches `/api/v1/admin/identities/search` and flags orphaned `users2service` rows client-side (small intentionally-duplicated TS port of `IdentityUrn.parse`, not a new backend field on the shared `PolicyRule` shape)
- [x] Unit tests: `IdentityUrnTest`, `IdentitySourcesTest`, `OrphanedRuleCheckerTest`, `IdentitySyncServiceTest`; `ZteAuthorizationFilterTest`/`AdminAuthorizationFilterTest`/`PolicyMatcherTest` pass **unmodified** (confirms bare-role backward compatibility) plus one new `role:`-prefixed-source test each; `PolicyDefinitionStoreTest` updated for the new `ApplicationEventPublisher` constructor param
- [x] New IT `IdentitySyncIT` — real Testcontainers Keycloak Admin REST API round trip; this is what actually proves the realm-export.json service-account role grant works, not just a hope
- ADR: ADR-014-idp-identity-sync.md

### Stage 14 — Machine Identities (OIDC Clients) + URN Unification `COMPLETE`
- [x] `keycloak/realm-export.json` — `zte-gateway`'s service account granted `realm-management`'s `view-clients` client role (alongside Stage 13's `view-users`/`view-realm`)
- [x] `V6__add_client_identity_type.sql` (Flyway) — widens `idp_identities`' existing `type` `CHECK` constraint (`idp_identities_type_check`) to add `CLIENT`; no new column/table — keeps machine identities in the same unified cache
- [x] `IdentityType.CLIENT` + `IdpClient.fetchClients()` + `KeycloakIdpAdapter` implementation (Keycloak `GET /admin/realms/{realm}/clients`, `external_id`=client's internal UUID, `name`=`clientId`, `displayName`=`name`→`description`→`clientId` fallback) — fetches **every** client in the realm (Keycloak built-ins included), not just `serviceAccountsEnabled` ones, an accepted MVP simplification (ADR-015 Self-Critique)
- [x] `IdentitySyncService.syncNow()` — `Flux.merge`s `fetchClients()` alongside the existing three
- [x] `IdentityUrn.parse(source, defaultType)` — new overload: a bare (no-prefix) source's implied type is now caller-supplied per category (`users2service`→`ROLE` via the original one-arg `parse(source)`, now a thin delegate; `service2service`/`agentMcpToolCalls`→`CLIENT`, since every pre-ADR-015 rule in those categories was already a bare client id) — an explicit prefix (`role:`/`user:`/`group:`/`client:`) always overrides the default regardless
- [x] `IdentitySources.enrichClient(clientId)` (mirrors `enrich(roles, jwtAuth)`) — wired into `ServiceToServiceAuthorizationFilter`/`YamlMcpPolicyEngine`'s `policyMatcher.evaluate(...)` calls, replacing the old single-bare-string `List.of(callerService)`/`List.of(agentId)`; `PolicyMatcher` itself needed **zero** further changes
- [x] `OrphanedRuleChecker.check(document)` — extended from one `Flux` (users2service) to three `Flux.merge`d streams (one per category, each with the correct default type); the per-rule `onErrorResume` resilience added live in the Stage 13 session (Flyway/R2DBC startup race fix) is what makes merging streams safe without reintroducing cross-category dropped-error risk
- [x] `zte-policies.yaml`/`docs/examples/zte-policies-example.yaml` — `service2service`/`agentMcpToolCalls` example sources migrated to the `client:<clientId>` form; verified non-breaking for any bare-form rule via `enrichClient`'s backward-compatible enrichment
- [x] `zt-admin-ui` — `Identities.tsx` needed no changes (renders whatever `type` the API returns, no hardcoded list); `PolicyDashboard.tsx`'s orphan highlighting extended from `users2service`-only to all three categories via a new per-`Category.defaultSourceType` field driving the client-side `parseUrn` port
- [x] Unit tests: `IdentityUrnTest`/`IdentitySourcesTest`/`OrphanedRuleCheckerTest` extended for `client:`/defaultType cases; `ServiceToServiceAuthorizationFilterTest`/`YamlMcpPolicyEngineTest` pass **unmodified** (confirms bare-clientId backward compatibility) plus one new `client:`-prefixed test each; `IdentitySyncServiceTest` updated for the new `fetchClients()` dependency
- [x] `IdentitySyncIT` extended (`manualSync_populatesClients`) — real Testcontainers Keycloak Admin REST API proof that `agent-a`/`agent-b`/`zte-gateway` land as `CLIENT`-type identities, and that the new `view-clients` role grant actually works
- ADR: ADR-015-machine-identities-and-urn-unification.md

### Stage 15 — Identities UI Refactor (Actors vs. Access Containers) + Relational Caching `COMPLETE`
- [x] `KeycloakIdpAdapter.fetchClients()` — now excludes Keycloak's every-realm builtin clients (`account`/`broker`/`realm-management`/`admin-cli`/`security-admin-console`, plus `account-`/`broker-`-prefixed satellite clients) via a `.filter(...)` before mapping, not a UI-side hide — closes the "actors vs. noise" gap ADR-015's own Self-Critique named as a future tightening; `isSystemClient(...)` made package-visible+`static` specifically for a direct unit test (`KeycloakIdpAdapterTest`), the one piece of this adapter that's ever had one — everything else stays proven only by `IdentitySyncIT` against a real Keycloak (ADR-014 precedent)
- [x] `V7__create_idp_identity_relations.sql` (Flyway) — `idp_identity_relations` (`subject_id`/`target_id` reference `idp_identities.id` internal PKs, not Keycloak external ids; `relation_type` `VARCHAR(20)`+`CHECK IN ('MEMBER_OF','HAS_ROLE')`, same non-native-enum reasoning as `idp_identities.type`; `UNIQUE (subject_id, target_id, relation_type)`, `ON DELETE CASCADE`)
- [x] `IdpIdentityRepository.upsert` — `@Query` gained `RETURNING id`, return type changed `Mono<Void>`→`Mono<UUID>` (dropped the now-wrong `@Modifying`, which switches Spring Data R2DBC to row-count semantics) — `IdentitySyncService` needs the resolved internal PK to populate relations without a second lookup query per identity
- [x] `RelationType` enum, `IdpIdentityRelation` (R2DBC record), `IdpIdentityRelationRepository` (native `ON CONFLICT` upsert, `findBySubjectId`); `IdpRelation` (adapter-layer DTO, subject/target keyed by Keycloak external id, pre-resolution) + `IdpClient.fetchRelations()` — `KeycloakIdpAdapter`'s implementation fetches user group-memberships/realm-role-mappings directly, and non-system clients' realm-role-mappings via their *service-account user* (a separate Keycloak entity, `GET /clients/{id}/service-account-user` — `idp_identities` never caches service-account users as their own `USER` row), reported against the client's own external id; a client without `serviceAccountsEnabled` 404s there, caught per-client via `onErrorResume` rather than failing the whole sync (mirrors `OrphanedRuleChecker`'s established per-rule resilience, ADR-014's live-tested Flyway/R2DBC race fix)
- [x] `IdentitySyncService.syncNow()` — now two passes per cycle: `syncIdentities()` upserts + collects a `Map<externalId, internalId>` from the new `RETURNING id`; `syncRelations(map)` resolves each `IdpRelation` against that map and upserts — **zero** extra DB round trips, since every relation names an entity the same cycle's identity fetch already named; an unresolvable relation is logged and skipped, not a sync failure
- [x] `AdminIdentityRelationsController` (`GET /api/v1/admin/identities/{id}/relations`) — reads only local Postgres (`IdpIdentityRelationRepository`/`IdpIdentityRepository`), **no** `WebClient`/Keycloak dependency — same Zero Trust reliability posture every other `/api/v1/admin/**` read endpoint already has; covered by the existing `u2s-admin-console-api` YAML rule, no new security wiring
- [x] `zt-admin-ui` — `Identities.tsx` rewritten: "Actors" (`USER`/`CLIENT`) and "Access Containers" (`GROUP`/`ROLE`) sections, each type an MUI `Accordion` (`defaultExpanded` if non-empty), a single client-side "Quick search" `TextField` filtering by name across all accordions at once, and an "info" `IconButton` (plain emoji glyph, matching `PolicyDashboard`'s existing icon convention — no `@mui/icons-material`) on `USER`/`CLIENT` rows opening an MUI `Drawer` that fetches the relations endpoint and lists Roles/Groups
- [x] Unit tests: `KeycloakIdpAdapterTest` (new — `isSystemClient` exact-match/prefix/business-client cases); `IdentitySyncServiceTest` extended with a 3-mock setup (`IdpClient`/`IdpIdentityRepository`/`IdpIdentityRelationRepository`) covering resolvable- and unresolvable-relation cases
- [x] `IdentitySyncIT` extended — `manualSync_excludesSystemClients` (real post-sync search confirms every builtin client id is absent), `manualSync_thenRelationsEndpoint_reflectsRoleAssignment` (real Testcontainers Keycloak round trip proving `zte-admin`'s `ADMIN` role assignment surfaces through the relations endpoint)
- ADR: `docs/adr/identities-ui-actors-containers-and-relations-caching.md` — deliberately unnumbered filename per this task's explicit instruction (a one-off deviation from the `ADR-XXX-name.md` convention, not an oversight); prompt saved to the literal path the task specified, `prompts-hist/identities_ui_and_relations_sync.txt`, not this repo's usual `NNN_name.txt` numbering

---

## Stage 16+ Backlog (Not Yet Implemented)

- [ ] Reduce `fetchRelations()`'s per-user/per-client HTTP call count if sync duration becomes a problem at larger realm scale — no known Keycloak Admin API batch endpoint for this today (see Stage 15 ADR Self-Critique)
- [ ] `findByTargetId` + a reverse "info" affordance on Group/Role rows ("which Actors have this Group/Role") — the natural complement to today's Actor→Container-only direction
- [ ] Filter `fetchClients()` further, to `serviceAccountsEnabled` clients only — Stage 15 already excludes Keycloak's realm-builtin clients; this would be a stricter, separate tightening for any remaining non-agent client with no service account
- [ ] A visual distinction in the Identities tab between "actor" clients (referenced by ≥1 policy rule) and unused/built-in ones
- [ ] UUID-based user URNs (today `user:<name>` only matches by `preferred_username`)
- [ ] Filesystem-watch or webhook-driven identity sync, replacing the fixed 15-min polling interval (see ADR-014)
- [ ] A demo Keycloak group in `zte-realm`, to close the integration-level test gap for `group:`-scoped rules (see ADR-014 Self-Critique)
- [ ] A second `IdpClient` implementation (Azure Entra ID or AWS IAM) — the concrete reason the adapter interface exists
- [ ] Per-category `zte.policy.*.default-effect` overrides (today one `default-effect` applies to service2service and agentMcpToolCalls alike)
- [ ] Filesystem watch-based auto-reload, layered on `PolicyDefinitionStore.reload()` (today: explicit `POST /api/v1/internal/policies/reload`)
- [ ] HTTP (or HTTP+SSE) transport for `hubspot_server.py` — prerequisite for any real forwarding; it's stdio-only today
- [ ] Move `agent-a-secret-dev-only`/`agent-b-secret-dev-only` to environment injection before any non-local environment
- [ ] Distributed tracing: Micrometer Tracing + Zipkin in Docker Compose — `X-Request-Id` (Stage 12) is a prerequisite primitive, not a replacement
- [ ] Rate limiting: Spring Cloud Gateway `RequestRateLimiter` (Redis-backed)
- [ ] Docker Compose production profile: resource limits, health-check restart policies
- [ ] ABAC extension: `condition` field on `PolicyRule` (SpEL evaluated against JWT claims)
- [ ] Full mTLS system test: service-a + service-b as real Testcontainers (covers TLS handshake rejection)
- [ ] Generic mechanism so a future gateway-local `@RestController` gets users2service enforcement automatically instead of needing its own `AdminAuthorizationFilter`-style `WebFilter` (see ADR-012)
- [ ] Environment-configurable Keycloak/gateway URLs in `zt-admin-ui` (currently hardcoded in `main.tsx`)
- [ ] True-`401` coverage in `request_logs` (see ADR-013 Self-Critique)
- [ ] MCP-audit unification: `LoggingMcpAuditService` writing into `request_logs` too, populating `agent_id`/`tool_name` (see ADR-013)
- [ ] Bounded buffer + overflow policy for `RequestLogAuditService` (same known gap `LoggingMcpAuditService` already has)