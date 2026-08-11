# CLAUDE.md — ZTE Lightweight Project Guide

## Project Overview
**Product:** Lightweight Zero Trust Environment (ZTE) MVP.
**Goal:** Demonstrate AI-driven development (Gemini as Architect, Claude as Engineer).

## Where Things Live (read this before re-deriving anything)
This file is a short orientation index, not the record of what was built or why.
Detail lives in exactly one place each — don't duplicate it back into this file:
- **What's implemented, per stage, with commit hashes:** `docs/SPECS.md` §2 (Status Summary).
- **Why a decision was made, alternatives considered, self-critique, consequences:** the ADR for that stage — `docs/adr/ADR-XXX-name.md` (index: `docs/SPECS.md` §11, and `README.md`'s ADR Index).
- **The literal prompt a stage was built from, and how the implementation deviated from it:** `prompts-hist/`.
- **Full architecture, component specs, data model, API reference, known risks, roadmap:** `docs/SPECS.md` (all of it — this is the consolidated technical reference).
- **Quick start, chain-of-trust walkthrough, feature how-tos:** `README.md`.

If you're about to add a paragraph here summarizing a stage's implementation, stop —
that paragraph belongs in the stage's ADR (or `docs/SPECS.md` if it's cross-cutting).
This file should stay small enough to read in one pass.

## Execution Protocols (Mandatory)
1. **Chain of Thought (CoT):** Always output a `### THOUGHTS` block before any implementation.
2. **Self-Criticism:** Always output a `### CRITIQUE` block after a proposal to identify risks.
3. **ADR Requirement:** Every structural or architectural decision must be documented in `./docs/adr/ADR-XXX-name.md`. This is where implementation detail, alternatives, and self-critique live — not in this file.
4. **Prompt History:** Save every major task prompt into `./prompts-hist/NNN_name.txt` (sequential number continuing from the last file in that directory).
5. **Doc Sync:** After each completed task, update `README.md` (user-facing: quick start, feature how-tos) and `docs/SPECS.md` (technical reference: status table §2, component spec, data model, API reference, roadmap, known risks — whichever sections the change actually touches). Add one line to `CLAUDE.md`'s Stage Index below. Do not copy ADR content into any of these — link to it.
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
- **Security:** Zero Trust principles — no implicit trust, mTLS for all inter-service traffic.
- **Auth:** OIDC/OAuth2 via Keycloak.

## Custom Skills & Tools
- `project-health-check`: Custom skill to verify Docker health and Gradle build status.
- `pre-commit-docs`: Slash command (`/pre-commit-docs`) — reads the staged diff and updates README.md, CLAUDE.md, docs/adr/, and prompts-hist/ before each commit. Definition: `.claude/commands/pre-commit-docs.md`. Keeps CLAUDE.md to a one-line Stage Index entry per stage — see that file's own rules.
- `generate-adr`: (Planned) Helper to scaffold a new ADR file with required CoT/Critique sections.

## Key Directories
- `./gateway-service`: The ZTE entry point (port 8080 HTTP).
- `./auth-library`: Shared security logic — `SecurityConfig`, `ZteAuditLogger`, `ReloadableSslContextFactory`, `UserContextTokenService`.
- `./service-a`: First protected downstream service (port 8081 HTTPS/mTLS, 9081 management).
- `./service-b`: Second protected downstream service — validates OBO token (port 8082 HTTPS/mTLS, 9082 management).
- `./zt-agents`: AI security copilot (Kotlin Spring Boot WebFlux, port 8083) — Policy Auditor Agent (Anthropic Claude).
- `./zt-admin-ui`: React Admin Console (Vite/TypeScript/MUI) — plain npm project, built by `gateway-service`'s Gradle build and served at `/admin/` (not run standalone).
- `./certs`: Dev certificate scripts (`generate-certs.sh`) and generated PKCS12 files (gitignored).
- `./prompts-hist`: Log of every major task prompt and how the implementation deviated from it.
- `./docs/adr`: Architectural Decision Records — one per structural/architectural decision.
- `./docs/SPECS.md`: Consolidated technical spec — architecture, component specs, data model, API reference, risk register, progress-flagged roadmap. The authoritative reference tying together README, CLAUDE.md, and the ADRs.

---

## Stage Index

One line per stage: what it is, and where the detail lives. Full table with commit
hashes: `docs/SPECS.md` §2. Reasoning/alternatives/self-critique: the linked ADR.

| Stage | Title | ADR |
|---|---|---|
| 1 | Infrastructure Bootstrap | [ADR-001](docs/adr/ADR-001-architecture-pattern-gateway-vs-sidecar.md) |
| 2 | Identity Provider (Keycloak) | [ADR-002](docs/adr/ADR-002-identity-provider-configuration-strategy.md) |
| 3 | DB-Based Policy Enforcement | [ADR-003](docs/adr/ADR-003-reactive-policy-engine.md) |
| 4 | mTLS & On-Behalf-Of Delegation | [ADR-004](docs/adr/ADR-004-mtls-implementation.md) |
| 5 | Unit Tests | — |
| 6 | E2E Integration Tests | [ADR-005](docs/adr/ADR-005-integration-testing-strategy.md) |
| — | Pre-Commit Documentation Automation | [ADR-006](docs/adr/ADR-006-pre-commit-documentation-automation.md) |
| 7 | AI Security Copilot (`zt-agents`) | [ADR-007](docs/adr/ADR-007-policy-auditor-agent.md) |
| — | `.env` Config for `zt-agents` | [ADR-008](docs/adr/ADR-008-dotenv-configuration-management.md) |
| 8 | MCP Proxy & Interception Layer | [ADR-009](docs/adr/ADR-009-mcp-proxy-interception-layer.md) |
| 9 | Agent OAuth2 Client Credentials Auth | [ADR-010](docs/adr/ADR-010-agent-oauth2-client-credentials.md) |
| 10 | YAML Policy Engine (users2service / service2service / agent@mcp) | [ADR-011](docs/adr/ADR-011-yaml-policy-engine.md) |
| 11 | Full YAML Migration + React Admin Console | [ADR-012](docs/adr/ADR-012-full-yaml-migration-and-admin-console.md) |
| 12 | R2DBC Audit Logging + Distributed Tracing | [ADR-013](docs/adr/ADR-013-postgres-audit-logging.md) |
| 13 | IdP Identity Sync + URN-Based Policy Matching | [ADR-014](docs/adr/ADR-014-idp-identity-sync.md) |
| 14 | Machine Identities (OIDC Clients) + URN Unification | [ADR-015](docs/adr/ADR-015-machine-identities-and-urn-unification.md) |
| 15 | Identities UI Refactor (Actors vs. Access Containers) + Relational Caching | [Identities UI + Relations](docs/adr/identities-ui-actors-containers-and-relations-caching.md) |
| 16 | APIM Inventory Registry — Auto-Discovery + Health Telemetry | [ADR-016](docs/adr/ADR-016-inventory-and-health-registry.md) |

**Backlog:** tracked in `docs/SPECS.md` §9 (Roadmap) — not duplicated here.
