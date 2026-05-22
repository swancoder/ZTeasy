# CLAUDE.md � ZTE Lightweight Project Guide

## Project Overview
**Product:** Lightweight Zero Trust Environment (ZTE) MVP.
**Goal:** Demonstrate AI-driven development (Gemini as Architect, Claude as Engineer).
**Tech Stack:** Java 21, Gradle (Kotlin DSL), Spring Boot 3.4+, PostgreSQL, Keycloak, Docker.

## Execution Protocols (Mandatory)
1. **Chain of Thought (CoT):** Always output a `### THOUGHTS` block before any implementation.
2. **Self-Criticism:** Always output a `### CRITIQUE` block after a proposal to identify risks.
3. **ADR Requirement:** Every structural or architectural decision must be documented in `./docs/adr/ADR-XXX-name.md`.
4. **Prompt History:** Save every major task prompt into `./prompts-hist/XXX_name.txt`.
5. **SUMMARY**  Update README.md  after each completed task.
6. **Git Workflow:** Each completed task must end with a successful test run and a commit to `main`.


## Build & Development Commands
- **Build Project:** `./gradlew build`
- **Run Unit Tests:** `./gradlew test`
- **Run Integration Tests:** `./gradlew integrationTest` (requires Docker; starts Postgres + Keycloak via Testcontainers)
- **Run All Tests:** `./gradlew test integrationTest`
- **Generate Dev Certs:** `chmod +x certs/generate-certs.sh && ./certs/generate-certs.sh`
- **Infrastructure:** `docker compose up -d` / `docker compose down`
- **Clean DB:** `./gradlew flywayClean` (use with caution)
- **Check Ports:** `netstat -an | grep -E "8080|8081|8082|5432|8180"` (Gateway, Service-A, Service-B, DB, Keycloak)

## Code Style & Standards
- **Language:** Java 21 (Modern features only: Records, Pattern Matching).
- **Architecture:** API Gateway Pattern.
- **Naming:** CamelCase for classes/methods, kebab-case for URLs and configs.
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
- `./certs`: Dev certificate scripts (`generate-certs.sh`) and generated PKCS12 files (gitignored).
- `./prompts-hist`: Log of all Gemini-generated instructions.
- `./docs/adr`: Architectural Decision Records.

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

---

## Stage 7+ Backlog (Not Yet Implemented)

- [ ] DB-based request audit log (`request_logs` table, V3 Flyway migration) — currently log-only via `RequestAuditFilter`
- [ ] Distributed tracing: Micrometer Tracing + Zipkin in Docker Compose
- [ ] Rate limiting: Spring Cloud Gateway `RequestRateLimiter` (Redis-backed)
- [ ] `/admin/policies/refresh` actuator endpoint: force `PolicyService` cache invalidation without restart
- [ ] Docker Compose production profile: resource limits, health-check restart policies
- [ ] ABAC extension: `condition` column on `access_policies` (SpEL evaluated against JWT claims)
- [ ] Full mTLS system test: service-a + service-b as real Testcontainers (covers TLS handshake rejection)