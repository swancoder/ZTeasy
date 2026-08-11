# ZTE-Lightweight — Project Summary

**Lightweight Zero Trust Environment (MVP)**
Demonstrating AI-driven development: Gemini as Architect, Claude as Engineer.

For the full technical specification (architecture, component specs, data
model, API reference, risk register, and the progress-flagged roadmap), see
[docs/SPECS.md](docs/SPECS.md). This README stays a quick-start summary.

---

## What This Project Is

A minimal, runnable Zero Trust microservices stack built entirely in Java, showing how
every trust decision is made explicit in code — no service mesh, no "implicit trust once
inside the network." Every request must prove:

1. **Who is the user?** — Keycloak JWT (RS256, validated at the gateway)
2. **Is the user allowed?** — YAML-defined access policy (`zte-policies.yaml`, ADR-011/ADR-012)
3. **Who is the internal caller?** — mTLS client certificate (signed by ZTE CA)
4. **On whose behalf?** — Signed OBO token (`X-ZTE-User-Context`, HMAC-SHA256, 30s TTL)

---

## Chain of Trust: Keycloak → Service B

```
┌─────────┐  Keycloak JWT (RS256)   ┌──────────────────────────────────────────┐
│  User   │ ──────────────────────► │              ZTE Gateway                 │
└─────────┘                         │  ① Spring Security validates JWT sig     │
                                    │  ② ZteAuthorizationFilter: YAML policy   │
                                    │     role matches zte-policies.yaml?      │
                                    │     else 403 (ADR-012: YAML-only)        │
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
| Gateway policy | `zte-policies.yaml` (`users2service` rules, ADR-011/ADR-012) | User is authorised for this path |
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
│   ├── ZteAuthorizationFilter        users2service: YAML-only, deny-by-default (HIGHEST_PRECEDENCE+100, ADR-012)
│   ├── ServiceToServiceAuthorizationFilter  service2service: YAML-only, default-deny (HIGHEST_PRECEDENCE+150)
│   ├── UserContextPropagationFilter  OBO token injection (HIGHEST_PRECEDENCE+200)
│   ├── MtlsHttpClientConfig         Netty HttpClient with client.p12
│   ├── policy/def/                   YAML policy engine (see ADR-011/ADR-012): PolicyDefinitionStore,
│   │                                 PolicyMatcher, PolicyValidator, YamlPolicyFileLoader, RealmRoles
│   ├── GatewayRouteConfig            Routes: /api/v1/service-a/**, /api/v1/service-b/**
│   ├── InternalPolicyController      GET /api/v1/internal/policies (agent data provider, YAML-backed)
│   ├── PolicyReloadController        POST /api/v1/internal/policies/reload (no-downtime reload)
│   ├── admin/                        Admin Console API (see ADR-012, ADR-013)
│   │   ├── AdminPolicyController     GET /api/v1/admin/policies, POST .../reload (ADMIN JWT required)
│   │   ├── AdminAuditLogController   GET /api/v1/admin/audit-logs (latest 100, ADMIN JWT required)
│   │   ├── AdminAuthorizationFilter  Plain WebFilter enforcing users2service on /api/v1/admin/**
│   │   │                             (ZteAuthorizationFilter's GlobalFilter type doesn't run for
│   │   │                             non-Gateway-routed local controllers — see its Javadoc)
│   │   └── AdminUiConfig             permitAll + static resource serving for /admin/**
│   ├── audit/                        Async request audit trail (see ADR-013)
│   │   ├── RequestLog                R2DBC record (request_logs table)
│   │   ├── RequestLogRepository      ReactiveCrudRepository, findTop100ByOrderByTimestampDesc
│   │   └── RequestLogAuditService    Non-blocking Sinks.Many sink → boundedElastic writer,
│   │                                 SLF4J fallback on DB failure (mirrors LoggingMcpAuditService)
│   ├── RequestAuditFilter            Plain WebFilter (not GlobalFilter, ADR-013): X-Request-Id
│   │                                 resolve/forward, X-User-Id trust boundary, async audit write
│   │                                 via doFinally (LOWEST_PRECEDENCE-100)
│   └── mcp/                          MCP proxy — GET /sse, POST /message (see ADR-009)
│       ├── McpProxyHandler           Policy check, deny-via-SSE, backend forward
│       ├── McpSessionManager         sessionId → SSE sink (cross-request injection)
│       ├── McpBackendClient          WebClient forward to mcp-backend.uri
│       ├── policy/YamlMcpPolicyEngine    YAML-backed per-agent tool grants (see ADR-011)
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
├── zt-admin-ui/           React Admin Console (Vite/TS/MUI) — see ADR-012
│   ├── src/App.tsx        Login gate (react-oidc-context) + dashboard shell
│   ├── src/PolicyDashboard.tsx  Fetches/renders policies, "Reload Policies" button
│   └── (plain npm project, not a Gradle subproject — built by gateway-service's
│        build.gradle.kts via the node-gradle plugin, packaged into its jar)
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
| [ADR-011](docs/adr/ADR-011-yaml-policy-engine.md) | YAML-Defined Access Policies (users2service / service2service / agent@mcp) | Accepted |
| [ADR-012](docs/adr/ADR-012-full-yaml-migration-and-admin-console.md) | Full YAML Policy Migration and React Admin Console | Accepted |
| [ADR-013](docs/adr/ADR-013-postgres-audit-logging.md) | R2DBC-Backed Request Audit Logging with Distributed Tracing | Accepted |
| [ADR-014](docs/adr/ADR-014-idp-identity-sync.md) | IdP Identity Sync and URN-Based Policy Matching | Accepted |

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
3. **As of Stage 10 (ADR-011), the gateway enforces real per-agent policy**: `YamlMcpPolicyEngine`
   checks the `agentMcpToolCalls` rules in `zte-policies.yaml` (deny always wins, no match →
   default-deny). A denial is injected as a JSON-RPC envelope with `result.isError=true`,
   naming the matched rule — the backend is never called. An allow forwards the call to
   `McpBackendClient`, runs the result through `DataMaskingFilter`, and injects it into the
   SSE stream. The POST still always returns `202 Accepted` regardless — the transport
   contract from Stage 8 is unchanged, only what gets computed. This supersedes Stage 9's
   (ADR-010) deliberate dead-end stub — see ADR-011 for the full reasoning.
4. Every call is recorded asynchronously via `LoggingMcpAuditService` (non-blocking sink,
   logs today, TSDB-ready) with status `"ALLOWED"`/`"DENIED"`, and synchronously via the
   unified `ZteAuditLogger.policyDecision(...)` structured log line shared with the gateway's
   REST policy filters.

**Tested by:** `McpProxyIT` (`gateway-service/src/it`) — full `GET /sse` → `POST /message` →
SSE-injection round trip against a real running gateway (Testcontainers + WireMock), using
Agent A/Agent B's actual client-credentials tokens; covers an allowed call forwarded to the
backend, a tool with no grant, and a destructive-shaped tool caught by the deny-list, plus
401-without-token and unknown-`sessionId` 400. Also `McpProxySecurityWebFluxTest` (`src/test`)
— a fast `@WebFluxTest` slice covering the same allow/deny paths without Docker. Run with
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

## YAML Policy Engine (Stage 10, full migration Stage 11 / ADR-012)

A single YAML file (`gateway-service/src/main/resources/zte-policies.yaml`, path
configurable via `zte.policy.file`) defines allow/deny rules for three relationship
categories, loaded and validated at startup and hot-swappable at runtime, and is the
**sole** source of truth for all three (no DB fallback anywhere, as of ADR-012 — see
[ADR-011](docs/adr/ADR-011-yaml-policy-engine.md),
[ADR-012](docs/adr/ADR-012-full-yaml-migration-and-admin-console.md), and
[`docs/policy-schema.md`](docs/policy-schema.md) for the full schema and precedence rules.

| Category | Governs | Enforced by | On no match |
|---|---|---|---|
| `users2service` | User (realm role) → gateway REST service | `ZteAuthorizationFilter` (Gateway-routed paths); `AdminAuthorizationFilter` (`/api/v1/admin/**` — see ADR-012 for why a local `@RestController` needs its own filter) | Deny |
| `service2service` | Calling service/agent (JWT `azp`) → gateway REST service | `ServiceToServiceAuthorizationFilter` | `zte.policy.default-effect` (default `DENY`) |
| `agentMcpToolCalls` | MCP agent (JWT `azp`) → MCP tool name | `YamlMcpPolicyEngine` | `zte.policy.default-effect` (default `DENY`) |

Deny always overrides allow, regardless of priority or declaration order. Full schema
reference (all fields, precedence, validation rules): [`docs/policy-schema.md`](docs/policy-schema.md).
Full worked example: [`docs/examples/zte-policies-example.yaml`](docs/examples/zte-policies-example.yaml).

### Format

```yaml
schemaVersion: 1        # required, must be exactly 1
users2service: [ ... ]  # list of rules, may be empty/omitted
service2service: [ ... ]
agentMcpToolCalls: [ ... ]
```

Every rule, in every category, shares one shape:

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Unique across the whole document — referenced in audit logs and validation errors |
| `effect` | yes | `ALLOW` or `DENY` |
| `source` | yes | Caller identity (Ant pattern): realm role name, or service/agent OAuth2 client id. For `users2service` only, also accepts an IdP URN — `user:<name>`, `group:<name>`, `role:<name>` (see [IdP Identity Sync](#idp-identity-sync-adr-014) below); a bare name with no prefix still means `role:<name>` exactly as before (fully backward compatible) |
| `target` | yes | What's accessed (Ant pattern): service name, or MCP tool name |
| `pathPattern` | no | Request path scope (Ant pattern); unused by `agentMcpToolCalls` |
| `methods` | no | Comma-separated HTTP verbs, or `*`; unused by `agentMcpToolCalls` |
| `priority` | no | Tie-break within the same effect only (default `0`) — never breaks a DENY vs ALLOW tie |

### How to add a rule

1. Edit `gateway-service/src/main/resources/zte-policies.yaml` (or whatever file
   `zte.policy.file` points at) and add a rule to the relevant category, e.g. to let
   `USER`s `POST` to service-a:

   ```yaml
   users2service:
     - id: u2s-user-write-service-a
       effect: ALLOW
       source: USER
       target: service-a
       pathPattern: "/api/v1/service-a/**"
       methods: "POST"
   ```

2. Restart the gateway, **or** reload without downtime — either the unauthenticated
   ops/tooling endpoint, or the ADMIN-JWT-gated one the Admin Console UI uses (see
   [Admin Console](#admin-console-adr-012) below):

   ```bash
   curl -s -X POST http://localhost:8080/api/v1/internal/policies/reload | python3 -m json.tool
   ```

   An invalid file (bad schema, duplicate `id`, etc.) fails validation and the
   previously active policy set stays in effect — the reload response reports the errors.
3. Check the gateway log for the `[ZTE-AUDIT]` `POLICY_ALLOW`/`POLICY_DENY` line naming
   the matched rule `id` to confirm it's taking effect.

---

## Admin Console (ADR-012)

A React/Vite/TypeScript SPA (`zt-admin-ui/`), built by `gateway-service`'s own Gradle
build and served statically at `http://localhost:8080/admin/index.html` — no separate
process to run. Shows the full active YAML policy set (all three categories) and a
"Reload Policies" button.

**Login flow:** the SPA itself is served unauthenticated (`/admin/**`, permitAll) — it
redirects to Keycloak (`react-oidc-context`, client `zte-admin-ui`, PKCE) and only then
calls the gateway's JSON API with a bearer token. The API (`/api/v1/admin/**`) requires
a valid JWT **and** the `ADMIN` realm role (enforced by the `u2s-admin-console-api` YAML
rule in `zte-policies.yaml`) — a `USER`-role login gets `403` from the API even though
the page itself loads.

```bash
# 1. Build + start the gateway (npm install/build runs automatically as part of the Gradle build)
./gradlew :gateway-service:bootRun

# 2. Open the console
open http://localhost:8080/admin/index.html   # or just visit it in a browser

# 3. Log in as zte-admin / Admin@123!
```

**Building without Node/npm installed:** `./gradlew build -x :gateway-service:buildAdminUi`
skips the React build (mirrors the existing `-x :zt-agents:compileKotlin` escape hatch for
the no-API-key case) — the gateway still builds and runs, just without `/admin/**` content.

**Tabs:** "Policies", "Audit Trail", and "Identities" (all below) — each fetches independently
on load. The Policies tab cross-references the Identities cache to flag `users2service` rules
whose `source` doesn't resolve to any synced identity (see [IdP Identity Sync](#idp-identity-sync-adr-014)).

---

## Audit Trail & Distributed Tracing (ADR-013)

Every zero-trust-relevant gateway request — a proxied REST call or MCP tool call, allowed
or denied by policy — is written asynchronously to a `request_logs` table in the same
Postgres instance already used for Flyway migrations (via R2DBC), in addition to the
existing synchronous `[ZTE-AUDIT]` SLF4J line. The write never blocks the response:
`RequestAuditFilter` fires the DB write from a `doFinally` callback into a non-blocking
sink (mirrors `LoggingMcpAuditService`'s pattern); a DB outage degrades to an SLF4J
warning instead of losing the event or slowing requests down.

**Excluded from the audit trail** (configurable, `zte.audit.excluded-path-prefixes` in
`application.yml` — deliberately separate from `zte-policies.yaml`, not hardcoded):
the Admin Console's own traffic (`/admin/**` static assets, `/api/v1/admin/**` API —
otherwise viewing the audit trail would generate more audit trail), `/api/v1/internal/**`,
and `/actuator/**` health checks. `X-Request-Id`/`X-User-Id` handling below still applies
to *every* request regardless of this list — only the `request_logs` row and the sync
`requestLog` SLF4J line are scoped down.

**Distributed tracing:** every request gets an `X-Request-Id` — the caller's own value if
present, otherwise a new UUID minted by the gateway — which is then guaranteed to be
forwarded to service-a/service-b, so a single request can be correlated across the whole
chain. Known gap: a request with **no** `Authorization` header at all (true `401`) is
rejected by Spring Security before reaching this filter, so it isn't written to
`request_logs` — every denial scenario tested in this codebase uses a present-but-wrong-role
JWT (`403`), not a missing token, so this doesn't affect the common case. See
[ADR-013](docs/adr/ADR-013-postgres-audit-logging.md) for the full reasoning.

```bash
# Read the latest 100 rows via the admin API (ADMIN JWT required)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/admin/audit-logs | python3 -m json.tool

# ...or query Postgres directly
docker exec zte-postgres psql -U zte_user -d zte_db \
  -c "SELECT trace_id, client_ip, path, status_code FROM request_logs ORDER BY timestamp DESC LIMIT 10;"
```

Also visible in the Admin Console's "Audit Trail" tab (Timestamp, Trace ID, Client IP,
Agent/User ID, Path, Status) — `agent_id`/`tool_name` are always blank for REST traffic
today, reserved for a future MCP-audit unification (see ADR-013 Future Migration Path).

---

## IdP Identity Sync (ADR-014)

`users2service` rules can now target a synced IdP identity, not just a bare realm-role
name — `zte-gateway`'s service account (`realm-management`'s `view-users`/`view-realm`
roles, granted in `keycloak/realm-export.json`) periodically pulls Keycloak's users,
groups, and roles into a local `idp_identities` Postgres cache, via an `IdpClient` adapter
interface (`KeycloakIdpAdapter` today; a future Azure Entra ID/AWS IAM adapter is a
drop-in). No sensitive IdP data (passwords, secrets, tokens) is ever cached — only
id/type/name.

**URN sources**, `users2service` only: `role:<name>` (equivalent to the pre-existing bare
`<name>` form), `user:<preferred_username>`, `group:<name>` — see the Format table above.
`PolicyMatcher` itself is unchanged; the enriched sources list is built at the filter call
sites (`ZteAuthorizationFilter`/`AdminAuthorizationFilter`).

```bash
# Manual sync (also runs automatically every 15 min, zte.idp.sync-interval-ms)
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/admin/identities/sync

# Search the cache (used by the Admin Console's autocomplete / Identities tab)
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/admin/identities/search?type=ROLE" | python3 -m json.tool
```

**Orphaned rules:** a `users2service` rule whose `source` doesn't resolve to any cached
identity logs an SLF4J `WARN` `"ORPHANED RULE: ..."` line (checked at startup and on every
policy reload) and is highlighted in the Admin Console's Policies tab — never rejected or
deleted. A transient false-positive is possible on a cold start, before the first sync has
run; see [ADR-014](docs/adr/ADR-014-idp-identity-sync.md) Self-Critique.

Also visible in the Admin Console's "Identities" tab (Type, Name, Display Name, Last
Synced) with a "Sync Now" button.

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
# 1. Prerequisites: Java 21, Docker Desktop, openssl, keytool, Node.js + npm
#    (Node/npm build the Admin Console — see "Building without Node/npm installed" above
#    if you want to skip it)

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
| 10 | YAML policy engine: users2service/service2service/agent@mcp allow-deny rules, no-downtime reload, unified audit logging, real MCP enforcement (supersedes Stage 9's stub) | `d76c709` |
| 11 | Full YAML migration (retired `access_policies`/`PolicyService`) + React Admin Console (`zt-admin-ui`), new ADMIN-JWT-gated admin API, `AdminAuthorizationFilter` (WebFilter, not GlobalFilter — see ADR-012) | `00edf91` |
| 12 | R2DBC-backed `request_logs` audit trail, `X-Request-Id` distributed tracing, `GET /api/v1/admin/audit-logs`, Admin Console "Audit Trail" tab; `RequestAuditFilter` rewritten as a WebFilter (ADR-013) | `e5e1c65` |
| 13 | IdP identity sync (`idp_identities` cache, `KeycloakIdpAdapter`), URN-based `users2service` sources, orphaned-rule detection, Admin Console "Identities" tab (ADR-014) | `dd8a13f` |
