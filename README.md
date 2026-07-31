# ZTE-Lightweight — Project Summary

**Lightweight Zero Trust Environment (MVP)**
Demonstrating AI-driven development: Gemini as Architect, Claude as Engineer.

For the full technical specification (architecture, component specs, data
model, API reference, risk register, and the progress-flagged roadmap), see
[docs/SPEC.md](docs/SPEC.md). This README stays a quick-start summary.

---

## What This Project Is

A minimal, runnable Zero Trust microservices stack built entirely in Java, showing how
every trust decision is made explicit in code — no service mesh, no "implicit trust once
inside the network." Every request must prove:

1. **Who is the user?** — Keycloak JWT (RS256, validated at the gateway)
2. **Is the user allowed?** — DB-backed access policy (`access_policies` table)
3. **Who is the internal caller?** — mTLS client certificate (signed by ZTE CA)
4. **On whose behalf?** — Signed OBO token (`X-ZTE-User-Context`, HMAC-SHA256, 30s TTL)

---

## Chain of Trust: Keycloak → Service B

```
┌─────────┐  Keycloak JWT (RS256)   ┌──────────────────────────────────────────┐
│  User   │ ──────────────────────► │              ZTE Gateway                 │
└─────────┘                         │  ① Spring Security validates JWT sig     │
                                    │  ② ZteAuthorizationFilter: DB policy     │
                                    │     role ∈ access_policies? else 403     │
                                    │  ③ UserContextPropagationFilter:         │
                                    │     creates X-ZTE-User-Context (OBO JWT) │
                                    └──────────────┬───────────────────────────┘
                                                   │ HTTPS + mTLS
                                                   │ client.p12 (ZTE CA)
                                                   │ X-ZTE-User-Context: <OBO>
                                                   ▼
                                    ┌──────────────────────────────────────────┐
                                    │              Service A                   │
                                    │  ④ TLS handshake: client cert verified   │
                                    │     against ZTE CA (or reject)           │
                                    │  ⑤ Forwards X-ZTE-User-Context unchanged │
                                    │     (delegation — not re-issuance)       │
                                    └──────────────┬───────────────────────────┘
                                                   │ HTTPS + mTLS
                                                   │ client.p12 (ZTE CA)
                                                   │ X-ZTE-User-Context: <same OBO>
                                                   ▼
                                    ┌──────────────────────────────────────────┐
                                    │              Service B                   │
                                    │  ⑥ TLS handshake: client cert verified   │
                                    │  ⑦ UserContextController validates HMAC  │
                                    │     signature + expiry of OBO token      │
                                    │  ⑧ Returns: sub, roles, trustBasis       │
                                    └──────────────────────────────────────────┘
```

**Trust at each hop:**

| Hop | Mechanism | What it proves |
|---|---|---|
| User → Gateway | Keycloak JWT (RS256) | User identity + realm roles |
| Gateway policy | `access_policies` table (R2DBC) | User is authorised for this path |
| OBO token | HMAC-SHA256 JWT, 30s TTL | Gateway delegated on behalf of this user |
| Gateway → Service A | mTLS (`client.p12` / ZTE CA) | Caller is an authorised ZTE service |
| Service A → Service B | mTLS (`client.p12` / ZTE CA) | Caller is an authorised ZTE service |
| OBO at Service B | HMAC signature + expiry check | Token was issued by the gateway, not forged |

---

## Module Map

```
zte-lightweight/
├── auth-library/          Shared security utilities (no main class)
│   ├── SecurityConfig     Default WebFlux security (JWT, deny-by-default)
│   ├── ZteAuditLogger     Structured [ZTE-AUDIT] log events (static utility)
│   ├── ReloadableSslContextFactory   Netty client SslContext with AtomicRef hot-swap
│   └── UserContextTokenService       HMAC-SHA256 OBO token create/validate
│
├── gateway-service/       ZTE entry point — port 8080 (HTTP)
│   ├── ZteAuthorizationFilter        DB policy enforcement (HIGHEST_PRECEDENCE+100)
│   ├── UserContextPropagationFilter  OBO token injection (HIGHEST_PRECEDENCE+200)
│   ├── MtlsHttpClientConfig         Netty HttpClient with client.p12
│   ├── PolicyService                 R2DBC policy cache (Mono.cache, 5-min TTL)
│   ├── GatewayRouteConfig            Routes: /api/v1/service-a/**, /api/v1/service-b/**
│   ├── InternalPolicyController      GET /api/v1/internal/policies (agent data provider)
│   └── mcp/                          MCP proxy — GET /sse, POST /message (see ADR-009)
│       ├── McpProxyHandler           Policy check, deny-via-SSE, backend forward
│       ├── McpSessionManager         sessionId → SSE sink (cross-request injection)
│       ├── McpBackendClient          WebClient forward to mcp-backend.uri
│       ├── policy/DummyMcpPolicyEngine   In-memory deny-list (synchronous check)
│       ├── audit/LoggingMcpAuditService  Non-blocking audit sink (TSDB-ready stub)
│       └── mask/NoOpDataMaskingFilter    PII masking stub
│
├── service-a/             Protected downstream — port 8081 (HTTPS/mTLS), 9081 (mgmt)
│   ├── HelloController    Calls service-b, returns combined response
│   └── ServiceBClientConfig          mTLS WebClient for outbound calls
│
├── service-b/             Deep downstream — port 8082 (HTTPS/mTLS), 9082 (mgmt)
│   └── UserContextController         Validates OBO token, returns user context
│
├── zt-agents/             AI security copilot — port 8083 (Kotlin Spring Boot WebFlux)
│   ├── PolicyAuditorService          Fetches policies → prompts Claude → returns Markdown
│   ├── AnthropicClient               WebClient wrapper for Anthropic Messages API
│   └── GatewayClient                 Fetches /api/v1/internal/policies from gateway
│
├── certs/
│   └── generate-certs.sh  Generates ZTE-CA, client.p12, service-a.p12, service-b.p12
│
├── scripts/
│   └── install-hooks.sh   Installs .githooks/pre-commit into local .git/hooks/
│
├── .claude/commands/
│   └── pre-commit-docs.md /pre-commit-docs slash command — docs guardian pre-commit check
│
└── docs/adr/              Architectural Decision Records
```

---

## ADR Index

| ADR | Title | Status |
|---|---|---|
| [ADR-001](docs/adr/ADR-001-architecture-pattern-gateway-vs-sidecar.md) | API Gateway as ZT Entry Point | Accepted |
| [ADR-002](docs/adr/ADR-002-identity-provider-configuration-strategy.md) | Identity Provider Configuration Strategy (Keycloak native import) | Accepted |
| [ADR-003](docs/adr/ADR-003-reactive-policy-engine.md) | Reactive Policy Engine — R2DBC + In-Process Cache | Accepted |
| [ADR-004](docs/adr/ADR-004-mtls-implementation.md) | mTLS Implementation and On-Behalf-Of User Context Delegation | Accepted |
| [ADR-005](docs/adr/ADR-005-integration-testing-strategy.md) | Integration Testing Strategy — Testcontainers + WireMock | Accepted |
| [ADR-006](docs/adr/ADR-006-pre-commit-documentation-automation.md) | Pre-Commit Documentation Automation via Claude Code Slash Command | Accepted |
| [ADR-007](docs/adr/ADR-007-policy-auditor-agent.md) | Policy Auditor Agent — Internal Endpoint + WebClient Anthropic Integration | Accepted |
| [ADR-008](docs/adr/ADR-008-dotenv-configuration-management.md) | `.env`-Based Configuration Management for `zt-agents` | Accepted |
| [ADR-009](docs/adr/ADR-009-mcp-proxy-interception-layer.md) | MCP Proxy & Interception Layer — WebFlux Router + Session Manager | Accepted |
| [ADR-010](docs/adr/ADR-010-agent-oauth2-client-credentials.md) | Agent Auth via OAuth2 Client Credentials, and a Deliberate Dead-End Stub | Accepted |

---

## MCP Proxy

`gateway-service` fronts Model Context Protocol (MCP) traffic over HTTP+SSE, intercepting
every tool call for a policy decision before it reaches the backend MCP server. See
[ADR-009](docs/adr/ADR-009-mcp-proxy-interception-layer.md) for why this is a plain WebFlux
router rather than a Gateway route.

**Endpoints:** `GET /sse` (open a session), `POST /message?sessionId=<id>` (send a JSON-RPC
`tools/call`). Both require a JWT, same as every other route.

**Flow:**
1. Client opens `GET /sse`. The gateway generates a `sessionId` and immediately pushes an
   `endpoint` event: `data: /message?sessionId=<id>` (standard MCP HTTP+SSE handshake).
2. Client sends a JSON-RPC call to `POST /message?sessionId=<id>`. `clientId` comes from the
   JWT `azp` claim (falling back to `sub`); the tool name and arguments come from
   `params.name` / `params.arguments`.
3. **As of Stage 9 (ADR-010), the gateway is a deliberate dead-end**: it does not call
   `DummyMcpPolicyEngine` or forward to any backend. It logs the authenticated `clientId` and
   injects a stub JSON-RPC success response naming that client into the SSE stream. The POST
   still always returns `202 Accepted` — the transport contract from Stage 8 is unchanged,
   only what gets computed. See ADR-010 for what re-enabling policy + forwarding looks like.
4. Every call is recorded asynchronously via `LoggingMcpAuditService` (non-blocking sink,
   logs today, TSDB-ready) with status `"STUBBED"`.

**Tested by:** `McpProxyIT` (`gateway-service/src/it`) — full `GET /sse` → `POST /message` →
SSE-injection round trip against a real running gateway (Testcontainers + WireMock), using
Agent A/Agent B's actual client-credentials tokens; asserts the backend is never called, plus
401-without-token and unknown-`sessionId` 400. Also `McpProxySecurityWebFluxTest` (`src/test`)
— a fast `@WebFluxTest` slice covering the same auth boundary without Docker. Run with
`./gradlew test integrationTest`.

---

## Agent Authentication (Stage 9)

Agent A and Agent B (the `hubspot-mcp` sibling project) authenticate to this gateway via
OAuth2 **Client Credentials** — a service authenticating as itself, not on behalf of a user.
See [ADR-010](docs/adr/ADR-010-agent-oauth2-client-credentials.md).

Two confidential clients live in the existing `zte-realm` (`keycloak/realm-export.json`):
`agent-a` and `agent-b`, both Client-Credentials-only (no interactive login). Fetch a token
and call the MCP proxy:

```bash
TOKEN=$(curl -s -X POST http://localhost:8180/realms/zte-realm/protocol/openid-connect/token \
  -d "grant_type=client_credentials&client_id=agent-a&client_secret=agent-a-secret-dev-only" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

curl -s -N -H "Authorization: Bearer $TOKEN" http://localhost:8080/sse
# → data: /message?sessionId=<id>  (open a second terminal to POST while this stays open)
```

Or run the full round trip via `hubspot-mcp/run_agents.sh` (see that repo's README) — it
does the token fetch, `GET /sse` handshake, and `POST /message` for both agents automatically.

---

## AI Security Copilot — `zt-agents`

The `zt-agents` module adds an AI-native security copilot to the ZTE stack.

### Agent 1: Policy Auditor

Fetches all access policies from the gateway and sends them to Claude for a zero-trust
compliance analysis. Returns a structured Markdown security report.

**Endpoint:** `POST /api/v1/agents/auditor/run` (port 8083)

**Prerequisites:** Set `ANTHROPIC_API_KEY` before starting `zt-agents` (see [ADR-008](docs/adr/ADR-008-dotenv-configuration-management.md)).

```bash
# 1. Copy the .env template and fill in your Anthropic API key
cp .env.example .env
# edit .env and set ANTHROPIC_API_KEY=sk-ant-...

# 2. Start the gateway (must be running — zt-agents fetches policies from it)
./gradlew :gateway-service:bootRun

# 3. Start zt-agents
./gradlew :zt-agents:bootRun

# 4. Run the policy audit (expect 10–60 s while Claude analyses the policies)
curl -s --max-time 150 -X POST http://localhost:8083/api/v1/agents/auditor/run | python3 -m json.tool
```

**Expected response shape:**
```json
{
    "report": "## Executive Summary\n...\n## Risk Findings\n..."
}
```

**Security notes:**
- The gateway's internal endpoint (`GET /api/v1/internal/policies`) is network-restricted
  (Docker bridge) and requires no JWT for MVP. See ADR-007 for the production upgrade path.
- Never commit `ANTHROPIC_API_KEY`. Use `.env` files (gitignored) or Vault in production.

---

## Quick Start

```bash
# 1. Prerequisites: Java 21, Docker Desktop, openssl, keytool

# 2. Generate development certificates
chmod +x certs/generate-certs.sh && ./certs/generate-certs.sh

# 3. Start infrastructure (PostgreSQL + Keycloak)
docker compose up -d

# 4. Set Keycloak password (first time only)
./scripts/set-keycloak-password.sh

# 5. Start services (each in a separate terminal)
./gradlew :gateway-service:bootRun
./gradlew :service-a:bootRun
./gradlew :service-b:bootRun

# 6. Get an ADMIN token
TOKEN=$(curl -s -X POST http://localhost:8180/realms/zte-realm/protocol/openid-connect/token \
  -d "grant_type=password&client_id=zte-gateway&client_secret=zte-gateway-secret" \
  -d "username=zte-admin&password=Admin@123!" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# 7. Call the full chain: User → Gateway → Service A → Service B
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/service-a/hello | python3 -m json.tool
```

**Expected response:**
```json
{
    "service": "service-a",
    "caller": "<keycloak-user-uuid>",
    "message": "Hello from Protected Service A",
    "service-b": "{\"service\":\"service-b\",\"sub\":\"...\",\"roles\":[\"ADMIN\"],\"trustBasis\":\"mTLS (ZTE-CA) + HMAC-SHA256 OBO token\"}"
}
```

---

## Implemented Stage Progress

| Stage | Feature | Commit |
|---|---|---|
| 1 | Gradle multi-project scaffold, Docker Compose, gateway skeleton | `c3a9aa7` |
| 2 | Keycloak realm auto-import, JWT resource server, scripts | `f8d044d` |
| 3 | DB policy engine (R2DBC + Mono.cache), ZteAuthorizationFilter, service-a | `5ce757e` |
| 4 | mTLS (ReloadableSslContextFactory), OBO delegation, service-b, ZteAuditLogger | `fce58a9` |
| 5 | Unit tests for filters + auth-library; fix switchIfEmpty double-invocation bug | `22dbe1b` |
| 6 | E2E integration test suite: Testcontainers (Postgres + Keycloak) + WireMock; 7/7 passing | `c28fe21` |
| 7 | `zt-agents` AI copilot module (Kotlin): Policy Auditor Agent + gateway internal endpoint | `c85e77f` |
| 8 | MCP proxy: GET /sse + POST /message, session manager, policy/audit/masking stubs | `cb5da35` |
| 9 | Agent OAuth2 Client Credentials auth (agent-a/agent-b), dead-end stub response | `e79994e` |
