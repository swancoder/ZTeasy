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
- **Build Project:** `./gradlew build` (requires `ANTHROPIC_API_KEY` env var for zt-agents, settable via `.env` — see ADR-008)
- **Build (skip zt-agents):** `./gradlew build -x :zt-agents:compileKotlin` (no API key needed)
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

---

## Stage 11+ Backlog (Not Yet Implemented)

- [ ] Full users2service migration to YAML-only, retiring `access_policies`/`PolicyService` — deliberate future ADR, not attempted in Stage 10 (see ADR-011 Future Migration Path)
- [ ] Per-category `zte.policy.*.default-effect` overrides (today one `default-effect` applies to service2service and agentMcpToolCalls alike)
- [ ] Filesystem watch-based auto-reload, layered on `PolicyDefinitionStore.reload()` (today: explicit `POST /api/v1/internal/policies/reload`)
- [ ] HTTP (or HTTP+SSE) transport for `hubspot_server.py` — prerequisite for any real forwarding; it's stdio-only today
- [ ] Move `agent-a-secret-dev-only`/`agent-b-secret-dev-only` to environment injection before any non-local environment
- [ ] DB-based request audit log (`request_logs` table, V3 Flyway migration) — currently log-only via `RequestAuditFilter`
- [ ] Distributed tracing: Micrometer Tracing + Zipkin in Docker Compose
- [ ] Rate limiting: Spring Cloud Gateway `RequestRateLimiter` (Redis-backed)
- [ ] Docker Compose production profile: resource limits, health-check restart policies
- [ ] ABAC extension: `condition` field on `PolicyRule` / `access_policies` (SpEL evaluated against JWT claims)
- [ ] Full mTLS system test: service-a + service-b as real Testcontainers (covers TLS handshake rejection)