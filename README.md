# ZTE-Lightweight — Project Summary

**Lightweight Zero Trust Environment (MVP)**
Demonstrating AI-driven development: Gemini as Architect, Claude as Engineer.

For **what the product does** — every capability, how they depend on each
other, and how mature each one is — see the
[Feature Catalogue](docs/FEATURES.md). For **how it is built** (architecture,
component specs, data model, API reference, risk register, roadmap), see
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
├── gateway-service/       ZTE entry point — port 8080 (HTTPS, client-auth: want — ADR-018)
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
├── approver/              Approval Center API + hosting (see ADR-026)
│   ├── ApproverApprovalsController  /api/v1/approver/approvals[/{id}/approve|reject]
│   │                                (USER or ADMIN role; same PendingApprovalService
│   │                                as the admin tab — one decision path, one audit trail)
│   ├── ApproverUiConfig             permitAll + static serving for /approver/**
│   └── UiConfigController           GET /ui-config.js — runtime OIDC authority for both SPAs
│
├── zt-approver-ui/        Approval Center SPA (Vite/TS/MUI) — see ADR-026
│
├── zt-admin-ui/           React Admin Console (Vite/TS/MUI) — see ADR-012
│   ├── src/App.tsx        Login gate (react-oidc-context) + dashboard shell
│   ├── src/PolicyDashboard.tsx  Fetches/renders policies, "Reload Policies" button
│   └── (plain npm project, not a Gradle subproject — built by gateway-service's
│        build.gradle.kts via the node-gradle plugin, packaged into its jar)
│
├── certs/
│   └── generate-certs.sh  Generates ZTE-CA, client.p12/.pem, service-a.p12, service-b.p12, gateway.p12
│                          (re-run safe: reuses an existing CA, reissues leaf certs;
│                           ZTE_REGENERATE_CA=1 forces a new CA, GATEWAY_EXTRA_SANS adds SANs)
│
├── scripts/
│   └── install-hooks.sh   Installs .githooks/pre-commit into local .git/hooks/
│
├── deploy/azure/          Azure Container Apps deployment (see ADR-027, ADR-028)
│   ├── deploy.sh          Two-phase provisioning (env + VNET, apps, certs share, phase-2 origin)
│   ├── push-images.sh     Builds and pushes every image to GHCR (private)
│   ├── bind-custom-domain.sh  Prints the required DNS records; binds domain + managed cert
│   ├── power.sh           stop / start / status — park the whole stack overnight
│   ├── make-cloud-realm.py    Cloud realm import (multi-origin redirect URIs)
│   └── Dockerfile.keycloak    Keycloak image with that realm baked in
│
├── docker-compose.cloud.yml   Local mirror of the cloud topology (single external origin)
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
| [ADR-006](docs/adr/ADR-006-pre-commit-documentation-automation.md) | Pre-Commit Documentation Automation via Claude Code Slash Command | Accepted (amended by ADR-024) |
| [ADR-007](docs/adr/ADR-007-policy-auditor-agent.md) | Policy Auditor Agent — Internal Endpoint + WebClient Anthropic Integration | Accepted |
| [ADR-008](docs/adr/ADR-008-dotenv-configuration-management.md) | `.env`-Based Configuration Management for `zt-agents` | Accepted |
| [ADR-009](docs/adr/ADR-009-mcp-proxy-interception-layer.md) | MCP Proxy & Interception Layer — WebFlux Router + Session Manager | Accepted |
| [ADR-010](docs/adr/ADR-010-agent-oauth2-client-credentials.md) | Agent Auth via OAuth2 Client Credentials, and a Deliberate Dead-End Stub | Accepted |
| [ADR-011](docs/adr/ADR-011-yaml-policy-engine.md) | YAML-Defined Access Policies (users2service / service2service / agent@mcp) | Accepted |
| [ADR-012](docs/adr/ADR-012-full-yaml-migration-and-admin-console.md) | Full YAML Policy Migration and React Admin Console | Accepted |
| [ADR-013](docs/adr/ADR-013-postgres-audit-logging.md) | R2DBC-Backed Request Audit Logging with Distributed Tracing | Accepted |
| [ADR-014](docs/adr/ADR-014-idp-identity-sync.md) | IdP Identity Sync and URN-Based Policy Matching | Accepted |
| [ADR-015](docs/adr/ADR-015-machine-identities-and-urn-unification.md) | Machine Identities (OIDC Clients) and URN Unification | Accepted |
| [Identities UI + Relational Caching](docs/adr/identities-ui-actors-containers-and-relations-caching.md) | Identities UI Refactor (Actors vs. Access Containers) and Relational Caching (deliberately unnumbered filename — see the ADR's own note) | Accepted |
| [ADR-016](docs/adr/ADR-016-inventory-and-health-registry.md) | APIM Inventory Registry — Auto-Discovery and Health Telemetry | Accepted |
| [ADR-017](docs/adr/ADR-017-dynamic-routing-and-audit.md) | Dynamic Inventory-Driven Routing, Unified Audit Logging, and Strict S2S Rules | Accepted |
| [ADR-018](docs/adr/ADR-018-smart-mtls-enforcement.md) | Smart mTLS Enforcement (client-auth: want + Application-Layer WebFilter) | Accepted |
| [ADR-019](docs/adr/ADR-019-hold-decision-and-approval-queue.md) | HOLD as a Third MCP Decision Outcome, and a Durable Approval Queue | Accepted |
| [ADR-020](docs/adr/ADR-020-acap-scope-profiles.md) | ACAP Scope Profiles — Argument/Field-Level Policy Tightening | Accepted |
| [ADR-021](docs/adr/ADR-021-governance-dashboard.md) | Governance Dashboard — Per-Agent Activity and Out-of-Policy Feed | Accepted |
| [ADR-022](docs/adr/ADR-022-acap-agent-metadata-and-thresholds.md) | ACAP Agent Metadata and Usage Thresholds | Accepted |
| [ADR-023](docs/adr/ADR-023-policy-rule-mcp-target.md) | `mcpTarget` — Scoping agentMcpToolCalls/agentMcpToolHolds Rules to a Specific MCP Backend | Accepted |
| [ADR-024](docs/adr/ADR-024-untrack-internal-engineering-notes.md) | Untracking Internal Engineering Notes (`CLAUDE.md`, `prompts-hist/`) from the Public Repo | Accepted |
| [ADR-025](docs/adr/ADR-025-gateway-openapi-documentation.md) | OpenAPI Documentation for the Gateway's Own API + Admin Console "Documentation" Tab | Accepted |
| [ADR-026](docs/adr/ADR-026-standalone-approver-ui.md) | Standalone Approval Center — a Second UI Surface for the HOLD Queue | Accepted |
| [ADR-027](docs/adr/ADR-027-azure-container-apps-deployment.md) | Azure Deployment — Container Apps, Single External Origin, `/auth` Reverse Proxy | Accepted |
| [ADR-028](docs/adr/ADR-028-custom-domain-and-trusted-certificate.md) | Custom Domain with a Publicly-Trusted Certificate — a Second, Browser-Facing Ingress | Accepted |
| [ADR-029](docs/adr/ADR-029-executive-dashboard.md) | Executive Dashboard — Role-Scoped Panels, Real Token Metering, Shared Visual Language | Accepted |
| [ADR-030](docs/adr/ADR-030-credential-hygiene-and-identity-reconciliation.md) | Credential Hygiene and Identity-Cache Reconciliation | Accepted |
| [ADR-031](docs/adr/ADR-031-policy-audit-surfacing-and-activation-overlay.md) | Policy-Audit Surfacing and the Activation Overlay | Accepted |

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
   `McpForwardService` (backend + masking), and injects the result into the SSE stream. The
   POST still always returns `202 Accepted` regardless — the transport contract from Stage 8
   is unchanged, only what gets computed. This supersedes Stage 9's (ADR-010) deliberate
   dead-end stub — see ADR-011 for the full reasoning.
4. **As of Stage 19 (ADR-019), a third outcome — Hold — routes a call to a human** instead of
   allowing or denying it outright: an otherwise-allowed call additionally matching an
   `agentMcpToolHolds` rule is parked in `pending_approvals` (durable — a held item may be
   reviewed well after this SSE session closes) and a `status: "held"` envelope is injected
   instead. A human then calls `POST /api/v1/admin/approvals/{id}/approve` (forwards the
   original call through the same `McpForwardService` path) or `/reject` (an honest denial) —
   see the Admin Console's **Approvals** tab, below.
4a. **As of Stage 20 (ADR-020), an agent with an ACAP profile gets a further,
   argument-aware tightening pass** on top of steps 3–4's decision: a call step 3
   would otherwise ALLOW (or hold) can still be denied based on its *arguments* —
   wrong territory, a disallowed field, a write attempt from a read-only agent, or
   a bulk/export-shaped tool — checks the coarse, tool-name-only rules above have
   no way to express. This is why `read_contacts(territory=EMEA)` and
   `read_contacts(territory=NA)` — the *same* tool, different arguments — can (and
   for the demo agent, do) produce different decisions. Additive and opt-in: an
   agent with no ACAP profile file is unaffected; a deny from step 3 is never
   reconsidered. See [YAML Policy Engine](#yaml-policy-engine-stage-10-full-migration-stage-11--adr-012)'s
   "ACAP scope profiles" note below and `docs/policy-schema.md`.
5. Every call is recorded asynchronously via `LoggingMcpAuditService` (non-blocking sink,
   logs today, TSDB-ready) with status `"ALLOWED"`/`"DENIED"`/`"HELD"`/`"APPROVED"`/`"REJECTED"`,
   and synchronously via the unified `ZteAuditLogger.policyDecision(...)` structured log line
   shared with the gateway's REST policy filters.

**Tested by:** `McpProxyIT` (`gateway-service/src/it`) — full `GET /sse` → `POST /message` →
SSE-injection round trip against a real running gateway (Testcontainers + WireMock), using
Agent A/Agent B's (and, as of ADR-019, the demo `crm-account-health-emea-01` agent's) actual
client-credentials tokens; covers an allowed call forwarded to the backend, a tool with no
grant, a destructive-shaped tool caught by the deny-list, a held call followed by an admin
approval, and (ADR-020) the same `read_contacts` tool call allowed/denied differently by
territory and by requested fields, plus a coarsely-granted-but-ACAP-denied `update_deal`,
plus 401-without-token and unknown-`sessionId` 400. Also `McpProxySecurityWebFluxTest`
(`src/test`) — a fast `@WebFluxTest` slice covering the same allow/deny/hold paths without
Docker. Run with `./gradlew test integrationTest`.

### Approvals — the 🟡 Hold Queue (ADR-019)

Admin Console **Approvals** tab: every call currently parked by an `agentMcpToolHolds` rule —
agent, tool, arguments, and why it was held — with **Approve**/**Reject** buttons. Approve
reconstructs and forwards the exact original call; Reject sends back an honest denial. Either
way the decision is durable and audited even if the agent's SSE session has since closed; only
pushing the result into that *specific* live connection is best-effort (see ADR-019's
Self-Criticism). The demo `crm-account-health-emea-01` agent (`zte-policies.yaml`) holds on
`send_email`/`draft_followup` — try it: open an SSE session as that agent, call one of those
tools, then approve or reject it from this tab.

### Approval Center — standalone approver UI (ADR-026)

A second, separate UI surface over the same hold queue, for business
approvers who shouldn't get the full Admin Console:
`https://localhost:8080/approver/index.html` — its own SPA
(`zt-approver-ui/`), its own Keycloak client (`zte-approver-ui`, PKCE), its
own login screen, one card per held call with **Approve**/**Decline**.

Open to **any authenticated interactive user** (`USER` or `ADMIN` realm
role — `u2s-approver-api-user`/`-admin` rules on the new
`/api/v1/approver/**` API), deliberately *not* `source: "*"`: an agent's
client-credentials JWT carries no realm role, so an agent can never approve
its own held call. Decisions go through the exact same
`PendingApprovalService` as the Admin Console tab — one decision path, one
audit trail (`decided_by` = the approver's username).

```bash
# Try it: log in as the USER-role account (scripts/set-keycloak-password.sh
# sets the local dev passwords; cloud credentials live only in the gitignored
# deploy/azure/out/cloud-credentials.env) and decide a held send_email.
open https://localhost:8080/approver/index.html
```

Both SPAs now load their OIDC authority at runtime from `GET /ui-config.js`
(`zte.ui.oidc-authority`, env `ZTE_UI_OIDC_AUTHORITY`) — the same built
bundle works against local Keycloak (`localhost:8180`, the default) or a
reverse-proxied `/auth` deployment (ADR-027).

### Governance Dashboard (ADR-021)

Admin Console **Governance** tab — the historical/reporting half (Approvals above is the
live, actionable half): read-only over the same `request_logs` audit trail every prior stage
already writes to, no new table.

- **Agent Activity** — per-agent ALLOW/HOLD/DENY counts over a selectable window (last hour /
  24 hours / 7 days), plus each agent's most recent activity timestamp.
- **Out-of-Policy Attempts** — the latest 50 denied MCP-agent calls, newest first (agent, tool,
  reason) — deliberately narrower than the Audit Trail tab (MCP-agent traffic only, not REST);
  a human's post-hold rejection shows up here too, since it's audited as a `DENY` (ADR-019).
- **Export Report** — downloads both views as one JSON snapshot for the selected window.
- **ACAP Profiles** (Stage 22, ADR-022) — one card per loaded ACAP profile: owner, deployment
  date, a red "OVERDUE" badge once re-authorization is due, EU AI Act risk tier, and live
  `used/limit` chips for any usage thresholds — see below.

```bash
curl -sk "https://localhost:8080/api/v1/admin/governance/agent-activity?hours=24" -H "Authorization: Bearer $ADMIN_TOKEN"
curl -sk https://localhost:8080/api/v1/admin/governance/out-of-policy -H "Authorization: Bearer $ADMIN_TOKEN"
```

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

# As of ADR-018, /sse is a protected path: a JWT alone is no longer enough, a client
# certificate signed by the ZTE-CA is also required (server.ssl.client-auth: want +
# MtlsEnforcementWebFilter) — -k accepts the dev self-signed CA on the gateway's own cert.
curl -sk --cert certs/client.p12:zte-pass --cert-type P12 \
  -N -H "Authorization: Bearer $TOKEN" https://localhost:8080/sse
# → data: /message?sessionId=<id>  (open a second terminal to POST while this stays open)
```

Or run the full round trip via `hubspot-mcp/run_agents.sh` (see that repo's README) — it
does the token fetch, `GET /sse` handshake, and `POST /message` for both agents automatically.

---

## YAML Policy Engine (Stage 10, full migration Stage 11 / ADR-012)

A single YAML file (`gateway-service/src/main/resources/zte-policies.yaml`, path
configurable via `zte.policy.file`) defines allow/deny rules for four relationship
categories, loaded and validated at startup and hot-swappable at runtime, and is the
**sole** source of truth for all four (no DB fallback anywhere, as of ADR-012 — see
[ADR-011](docs/adr/ADR-011-yaml-policy-engine.md),
[ADR-012](docs/adr/ADR-012-full-yaml-migration-and-admin-console.md), and
[`docs/policy-schema.md`](docs/policy-schema.md) for the full schema and precedence rules.

| Category | Governs | Enforced by | On no match |
|---|---|---|---|
| `users2service` | User (realm role) → gateway REST service | `ZteAuthorizationFilter` (Gateway-routed paths); `AdminAuthorizationFilter` (`/api/v1/admin/**` — see ADR-012 for why a local `@RestController` needs its own filter) | Deny |
| `service2service` | Calling service/agent (JWT `azp`) → gateway REST service | `ServiceToServiceAuthorizationFilter` | `zte.policy.default-effect` (default `DENY`) |
| `agentMcpToolCalls` | MCP agent (JWT `azp`) → MCP tool name | `YamlMcpPolicyEngine` | `zte.policy.default-effect` (default `DENY`) |
| `agentMcpToolHolds` (ADR-019) | MCP tool calls routed to a human even when `agentMcpToolCalls` would ALLOW them | `YamlMcpPolicyEngine` (`PolicyMatcher.matchAny`, not `evaluate` — see ADR-019 for why) | Not held (plain ALLOW/DENY stands) |

Deny always overrides allow, regardless of priority or declaration order. Full schema
reference (all fields, precedence, validation rules): [`docs/policy-schema.md`](docs/policy-schema.md).
Full worked example: [`docs/examples/zte-policies-example.yaml`](docs/examples/zte-policies-example.yaml).

### Format

```yaml
schemaVersion: 1        # required, must be exactly 1
users2service: [ ... ]  # list of rules, may be empty/omitted
service2service: [ ... ]
agentMcpToolCalls: [ ... ]
agentMcpToolHolds: [ ... ]   # ADR-019 — route a call to a human instead of forwarding it
```

Every rule, in every category, shares one shape:

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Unique across the whole document — referenced in audit logs and validation errors |
| `effect` | yes | `ALLOW` or `DENY` |
| `source` | yes | Caller identity (Ant pattern): realm role name, or service/agent OAuth2 client id. Every category also accepts an IdP URN — `user:<name>`, `group:<name>`, `role:<name>` (`users2service`), `client:<clientId>` (`service2service`/`agentMcpToolCalls`) — see [IdP Identity Sync](#idp-identity-sync-adr-014-adr-015) below; a bare name with no prefix still means the category's implied form exactly as before (`role:` for `users2service`, `client:` for the other two — fully backward compatible) |
| `target` | yes | What's accessed (Ant pattern): service name, or MCP tool name |
| `pathPattern` | no | Request path scope (Ant pattern); unused by `agentMcpToolCalls` |
| `methods` | no | Comma-separated HTTP verbs, or `*`; unused by `agentMcpToolCalls` |
| `priority` | no | Tie-break within the same effect only (default `0`) — never breaks a DENY vs ALLOW tie |
| `mcpTarget` | no | Which MCP backend this rule applies to, matched exactly against `mcp-backend.name` (ADR-023). Unused by `users2service`/`service2service`. Omit to match any backend — a rule authored against a specific backend's tool semantics should set this so it stops applying if the gateway is ever repointed elsewhere. |

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
   curl -sk -X POST https://localhost:8080/api/v1/internal/policies/reload | python3 -m json.tool
   ```

   An invalid file (bad schema, duplicate `id`, etc.) fails validation and the
   previously active policy set stays in effect — the reload response reports the errors.
3. Check the gateway log for the `[ZTE-AUDIT]` `POLICY_ALLOW`/`POLICY_DENY` line naming
   the matched rule `id` to confirm it's taking effect.

### ACAP scope profiles (Stage 20 / ADR-020)

A separate, additive, opt-in layer on top of the four categories above — one
YAML file per agent under `gateway-service/src/main/resources/acap-profiles/`
(path via `zte.acap.profiles-location`), consulted only for agents that have
one. Where `zte-policies.yaml` only ever sees a tool *name*, an ACAP profile
lets the gate look at the call's *arguments* too:

```yaml
agentId: crm-account-health-emea-01
territory: EMEA
scope:
  read:
    - resource: contacts
      fields: [name, company, lifecycle_stage, last_activity, deal_ids]
  writeAllowed: false
```

`read_<resource>` calls are checked against `territory` and `fields`;
`update_*` calls are denied unless `writeAllowed`; `export_*` calls are
always denied. It can only tighten a coarse ALLOW/HOLD into a DENY, never
loosen one — an agent with no profile file behaves exactly as before this
stage. Full schema and the ACAP-to-ZTeasy field mapping:
[`docs/policy-schema.md`](docs/policy-schema.md#acap-scope-profiles-stage-3-adr-020).
List what's loaded (each entry includes its current threshold usage, see
below) / trigger a reload:

```bash
curl -sk https://localhost:8080/api/v1/admin/acap-profiles -H "Authorization: Bearer $ADMIN_TOKEN"
curl -sk -X POST https://localhost:8080/api/v1/admin/acap-profiles/reload -H "Authorization: Bearer $ADMIN_TOKEN"
```

### ACAP agent metadata & usage thresholds (Stage 22 / ADR-022)

Two more, purely additive, optional blocks on the same per-agent profile file:

```yaml
agent:
  name: Account-Health Assistant
  client: Nordwind Components
  owner:
    name: Sales Operations Lead
    email: sales-ops@nordwind.example
  deploymentDate: "2026-08-01"
  reauthDue: "2026-02-01"
risk:
  euAiActClass: limited
  internalTier: 2
thresholds:
  - metric: followup_drafts_per_day
    toolName: draft_followup
    limit: 30
    onExceed: hold
```

`agent`/`risk` are **display-only** — shown in the Admin Console's
**Governance** tab, new "ACAP Profiles" section (one card per agent: owner,
deployment date, a red "OVERDUE" badge once `reauthDue` is in the past, risk
tier) — no enforcement is tied to either, matching the demo's own framing
("re-authorization is a human process, not a technical gate").

`thresholds` *is* enforced, one step further than scope tightening: once a
call gets past territory/field/write/bulk checks without being denied,
`AcapThresholdTracker` (in-memory, resets daily) increments the matching
metric's counter, and — only if the count now exceeds `limit` and the call
was a plain ALLOW — escalates it to HOLD, same as an `agentMcpToolHolds`
rule would. `toolName` (not part of the source ACAP schema) says explicitly
which tool the metric counts, since a name like `followup_drafts_per_day`
doesn't mechanically imply `draft_followup` by any convention worth trusting.
Each profile card in the Governance tab shows live `used/limit` chips for
its thresholds.

---

## Admin Console (ADR-012)

A React/Vite/TypeScript SPA (`zt-admin-ui/`), built by `gateway-service`'s own Gradle
build and served statically at `https://localhost:8080/admin/index.html` — no separate
process to run. Tabs: **Policies** (the full active YAML policy set, all four categories
as of ADR-019, and a "Reload Policies" button), **Audit Trail**, **Identities**,
**Registry**, **Approvals** (ADR-019 — the 🟡 hold queue, see [MCP Proxy](#mcp-proxy)
above), **Governance** (ADR-021 — see below), and **Documentation** (ADR-025 — the
gateway's own OpenAPI reference, see below).

**No client certificate needed here:** `/admin/**` and `/api/v1/admin/**` are both in
`MtlsEnforcementWebFilter`'s excluded-path list (ADR-018) — the gateway is HTTPS-only as
of that ADR, but this is plain server-authenticated TLS (your browser will warn about the
dev self-signed ZTE-CA cert; accept it to proceed, same as any local HTTPS dev setup).

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
open https://localhost:8080/admin/index.html   # or just visit it in a browser

# 3. Log in as zte-admin (password from scripts/set-keycloak-password.sh —
#    local dev only; never reuse it for a deployment reachable from anywhere else)
```

**Building without Node/npm installed:** `./gradlew build -x :gateway-service:buildAdminUi`
skips the React build (mirrors the existing `-x :zt-agents:compileKotlin` escape hatch for
the no-API-key case) — the gateway still builds and runs, just without `/admin/**` content.

**Tabs:** "Policies", "Audit Trail", "Identities", and "Registry" (all below) — each
fetches independently on load. The Policies tab cross-references the Identities cache to
flag `users2service` rules whose `source` doesn't resolve to any synced identity (see
[IdP Identity Sync](#idp-identity-sync-adr-014-adr-015)).

### API Documentation (ADR-025)

The **Documentation** tab renders the gateway's own OpenAPI spec (`GET /v3/api-docs`,
auto-generated by `springdoc-openapi` from every `@RestController` under `com.zte.gateway`
— covers `/api/v1/admin/**` and `/api/v1/internal/**`; not the MCP proxy's `/sse`/`/message`
routes or the proxy routes to service-a/service-b) via the same `swagger-ui-react` component
`SchemaDrawer.tsx` already uses for discovered downstream schemas. Unlike every other tab,
it needs no access token — `/v3/api-docs` and `/swagger-ui/**` are `permitAll`
(`ApiDocsSecurityConfig`), since the spec describes route *shapes*, not data; every actual
`/api/v1/admin/**` call underneath stays exactly as protected as before. Also reachable
directly, outside the Admin Console, at `https://localhost:8080/swagger-ui.html`
(springdoc's own bundled standalone UI).

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
curl -sk -H "Authorization: Bearer $TOKEN" https://localhost:8080/api/v1/admin/audit-logs | python3 -m json.tool

# ...or query Postgres directly
docker exec zte-postgres psql -U zte_user -d zte_db \
  -c "SELECT trace_id, client_ip, path, status_code FROM request_logs ORDER BY timestamp DESC LIMIT 10;"
```

Also visible in the Admin Console's "Audit Trail" tab (Timestamp, Trace ID, Client IP,
Agent ID, Initiator/OBO User, Method, Path, Target, Tool, Status, Decision). Hovering a
Tool name shows a tooltip with the MCP session id, deny reason (if any), and the actual
`tools/call` arguments sent — "Agent ID" is MCP-only (`agentId`, `null` for REST rows);
REST/interactive-user identity lives in the adjacent "Initiator/OBO User" column instead.

**Unified with MCP audit (ADR-017, further enriched in a same-area follow-up).** REST
and MCP traffic share this one table — `LoggingMcpAuditService` (see [MCP
Proxy](#mcp-proxy) below) writes a row per MCP event (a session opening, or a tool
call's outcome), populating `agent_id`/`tool_name` (always blank for REST rows, and vice
versa) and carrying the same full HTTP context (`trace_id`/`client_ip`/`user_agent`) REST
rows always had — no more separate, thinner MCP-only row. Every row also carries
`initiator_client` (the calling service/agent's JWT `azp`, blank for a plain interactive
user), `original_user_obo` (the caller's `preferred_username`, falling back to JWT `sub`
— display-only; the OBO token the gateway actually mints for downstream propagation
always uses the raw `sub`, read independently and unaffected by this display choice),
`target_service`, `http_method`, and `decision_effect` (`ALLOW`/`DENY`/`ERROR`, derived
from the final status code — see [ADR-017](docs/adr/ADR-017-dynamic-routing-and-audit.md)).

---

## IdP Identity Sync (ADR-014, ADR-015, Identities UI + Relations)

Policy rules can now target a synced IdP identity instead of a bare role name or client
id — `zte-gateway`'s service account (`realm-management`'s `view-users`/`view-realm`/
`view-clients` roles, granted in `keycloak/realm-export.json`) periodically pulls
Keycloak's users, groups, roles, **and OIDC clients** (ADR-015 — machine identities like
Agent A/B and `zte-gateway` itself) into a local `idp_identities` Postgres cache, via an
`IdpClient` adapter interface (`KeycloakIdpAdapter` today; a future Azure Entra ID/AWS
IAM adapter is a drop-in). No sensitive IdP data (passwords, secrets, tokens) is ever
cached — only id/type/name. `fetchClients()` excludes Keycloak's own realm-builtin
clients (`account`, `broker`, `realm-management`, `admin-cli`,
`security-admin-console`, and their `account-`/`broker-`-prefixed satellite clients) —
only real business clients/agents land in the cache.

**URN sources**: `role:<name>`/`user:<preferred_username>`/`group:<name>` for
`users2service`, `client:<clientId>` for `service2service`/`agentMcpToolCalls` — see the
Format table above. A bare name (no prefix) still means the category's implied form
exactly as before either ADR — fully backward compatible. `PolicyMatcher` itself is
unchanged; the enriched sources list is built at the filter/engine call sites
(`ZteAuthorizationFilter`/`AdminAuthorizationFilter` via `IdentitySources.enrich`;
`ServiceToServiceAuthorizationFilter`/`YamlMcpPolicyEngine` via `IdentitySources.enrichClient`).

**Relations** (`idp_identity_relations`, synced the same cycle): a User's group
memberships and realm-role assignments, and a Client's realm-role assignments (via its
service-account user) are cached too, resolved to `idp_identities`' internal ids with
zero extra Keycloak calls beyond the sync itself. `GET
/api/v1/admin/identities/{id}/relations` reads **only** local Postgres — no live
Keycloak dependency on that request path, ever.

```bash
# Manual sync (also runs automatically every 15 min, zte.idp.sync-interval-ms)
curl -sk -X POST -H "Authorization: Bearer $TOKEN" https://localhost:8080/api/v1/admin/identities/sync

# Search the cache (used by the Admin Console's autocomplete / Identities tab)
curl -sk -H "Authorization: Bearer $TOKEN" "https://localhost:8080/api/v1/admin/identities/search?type=CLIENT" | python3 -m json.tool

# Roles/groups related to a given Actor identity (cached only, no Keycloak call)
curl -sk -H "Authorization: Bearer $TOKEN" "https://localhost:8080/api/v1/admin/identities/<id>/relations" | python3 -m json.tool
```

**Orphaned rules:** a rule in any category whose `source` doesn't resolve to any cached
identity logs an SLF4J `WARN` `"ORPHANED RULE: ..."` line (checked at startup and on every
policy reload) and is highlighted in the Admin Console's Policies tab — never rejected or
deleted. A transient false-positive is possible on a cold start, before the first sync has
run; see [ADR-014](docs/adr/ADR-014-idp-identity-sync.md)/[ADR-015](docs/adr/ADR-015-machine-identities-and-urn-unification.md) Self-Critique.

**Identities tab layout:** split into "Actors" (Users, Clients) and "Access Containers"
(Groups, Roles), each type its own MUI Accordion (expanded by default if non-empty), with
a client-side "Quick search" filter by name and an "info" button on User/Client rows
opening a Drawer with that identity's cached Roles/Groups — see
[the Identities UI ADR](docs/adr/identities-ui-actors-containers-and-relations-caching.md).

Also visible in the Admin Console's "Identities" tab (Type, Name, Display Name, Last
Synced) with a "Sync Now" button.

---

## APIM Inventory Registry (ADR-016)

A central registry (`inventory_services`/`health_metrics`) of REST services and MCP
agents this gateway fronts — onboarded manually via the Admin Console's "Registry" tab,
auto-discovered, and health-monitored, both actively (periodic ping) and passively (real
routed traffic).

**Routing is 100% driven by this registry (ADR-017).** There are no hardcoded Gateway
routes — `InventoryRouteDefinitionLocator` builds `/api/v1/{name}/**` → `base_url` for
every `ACTIVE`/`WARNING` `REST`-type entry, refreshed immediately on any onboard/edit/
delete and periodically otherwise (`zte.routing.refresh-interval-ms`, 30s default) to
pick up status changes from discovery/health-polling. Onboarding a new REST service via
the Admin Console makes it reachable at `/api/v1/<name>/**` immediately — no redeploy.
`service-a`/`service-b` are seeded into the registry automatically at gateway startup
(`InventoryBootstrapSeeder`, from the `service-a.uri`/`service-b.uri` properties) so a
fresh `docker compose up` still routes them out of the box.

**Onboarding → auto-discovery:** `POST /api/v1/admin/inventory` persists a `PENDING` row
and returns immediately — `AutoDiscoveryWorker` probes the service in the background
(never delaying the response): `GET {base_url}/v3/api-docs` for `REST` (or an explicit
`docs_url` override — a full absolute URL, for a target whose OpenAPI document doesn't
live at that conventional path; `REST`-only, ADR-016 amendment), a stateless
`POST {base_url}/message` JSON-RPC `tools/list` call for `MCP`. Success → `ACTIVE`;
failure or timeout → `WARNING` ("reachable enough to route, but its schema/tool list
couldn't be confirmed" — a degraded state that requires manual attention, not a hard
failure). Note that `status == ACTIVE` alone doesn't guarantee a schema was captured — a
2xx response with an empty or non-JSON body still reaches `ACTIVE` but captures nothing;
use `hasSchema` (below) to check.

**Health polling:** every 60s (`zte.inventory.health-poll-interval-ms`), `HealthPollingService`
pings every `ACTIVE`/`WARNING`/`DOWN` service's `/actuator/health` and records
`last_ping_ms`/`actuator_status`. A failed ping flips `ACTIVE`→`DOWN`; a successful one
flips `DOWN`→`ACTIVE` — self-healing. `WARNING` is never touched by this job: a
successful raw health ping doesn't mean the service's actual API/tool contract works,
so it must not silently clear a discovery failure.

**Registering `service-a`/`service-b` themselves:** `AutoDiscoveryWorker`/`HealthPollingService`
inject the application's default `WebClient.Builder`, which already carries the
gateway's ZTE mTLS client certificate whenever `zte.mtls.enabled=true` — the same
builder every other outbound gateway component uses, confirmed both by inspecting
Spring Boot's `ClientHttpConnectorAutoConfiguration` and live (see ADR-016's amendment
section). Register `base_url=https://localhost:8081` (or `:8082`) so discovery exercises
the real mTLS API port — both services expose `/v3/api-docs`
(`springdoc-openapi-starter-webflux-ui`), so this correctly reaches `ACTIVE`. Health
polling pings a different endpoint (`/actuator/health`), which `service-a`/`service-b`
only expose on their separate plain-HTTP **management port**, not the mTLS API port —
set the optional `management_url` field (`http://localhost:9081` / `:9082`) at onboarding
time so health polling targets the right port instead of `base_url`; leaving it blank
falls back to `base_url`, which will show `DOWN` for these two services specifically
(ADR-016 amendment, 2026-08-11).

**Passive telemetry:** `RequestAuditFilter` fires an async, non-blocking update
(mirrors `RequestLogAuditService`'s fire-and-forget architecture, ADR-013) on every 2xx
routed response, setting `health_metrics.last_successful_call` for the matching
inventory entry (matched by name — must equal the path segment
`RequestTargetResolver` derives, e.g. `service-a`). Never blocks the request thread; a
target name with no matching registry row is a harmless no-op.

**Discovered schema (API Catalog):** a successful discovery probe now captures the raw
response body — the OpenAPI document for `REST`, the JSON-RPC `tools/list` response for
`MCP` — into `inventory_services.discovered_schema`. Fetch it on demand via
`GET /api/v1/admin/inventory/{id}/schema` (`404` if nothing's been captured yet), or
click a row's "View Schema" (📄) button in the Admin Console: `REST` targets render in
an embedded Swagger UI, `MCP` targets as a plain tool name/description list. Deliberately
excluded from the main registry list/CRUD payload so viewing the registry table stays
light regardless of how large a target's schema is (ADR-016 amendment, 2026-08-12). The
Admin Console gates "View Schema" on the list response's `hasSchema` field, not `status`
— see the note above on why those two aren't equivalent.

**Synchronous fetch:** `POST /api/v1/admin/inventory/{id}/schema/fetch` (or the "Fetch"
🔄 button on a Registry row) runs discovery immediately and waits for the result, instead
of the passive background trigger onboarding uses. `200` on success; `404` if `id`
doesn't exist; `502 Bad Gateway` if the target was unreachable, timed out, or returned no
valid JSON — deliberately *stricter* here than the background worker's `ACTIVE`-on-any-2xx
tolerance, since a human just clicked "Fetch" and needs a real yes/no answer, with an
error message the UI shows directly (ADR-016 amendment, 2026-08-12, second). It's a true
*re*fetch — clicking it on a service that already has a captured schema overwrites the
old one unconditionally, no special handling needed.

**Editing a registration:** the "Edit" (✏️) button on a Registry row opens the same
onboarding dialog pre-filled with that row's data and saves via `PUT
/api/v1/admin/inventory/{id}` instead of `POST` — every onboarding field (`base_url`,
`docs_url`, `management_url`) is editable this way. Like onboarding, saving an edit
always resets `status` to `PENDING` and re-triggers discovery (ADR-016 amendment,
2026-08-12, third). `PUT` returns `409` if the new name collides with a *different*
existing entry (renaming without changing the name never false-positives against
itself), and `404` if `id` doesn't exist (ADR-016 amendment, 2026-08-12, fourth).

```bash
# Onboard a service
curl -sk -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  https://localhost:8080/api/v1/admin/inventory \
  -d '{"name":"hubspot-mcp","targetType":"MCP","baseUrl":"http://localhost:9090"}'

# Onboard a REST service whose OpenAPI docs live somewhere other than /v3/api-docs
curl -sk -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  https://localhost:8080/api/v1/admin/inventory \
  -d '{"name":"legacy-api","targetType":"REST","baseUrl":"https://legacy.example.com","docsUrl":"https://legacy.example.com/swagger.json"}'

# List the registry (includes current health snapshot)
curl -sk -H "Authorization: Bearer $TOKEN" https://localhost:8080/api/v1/admin/inventory | python3 -m json.tool

# Fetch a service's captured schema (once discovery has succeeded at least once)
curl -sk -H "Authorization: Bearer $TOKEN" https://localhost:8080/api/v1/admin/inventory/<id>/schema | python3 -m json.tool

# Trigger discovery synchronously (e.g. after fixing a docs_url typo) and wait for the result
curl -sk -X POST -H "Authorization: Bearer $TOKEN" -w "\n%{http_code}\n" \
  https://localhost:8080/api/v1/admin/inventory/<id>/schema/fetch
```

See [ADR-016](docs/adr/ADR-016-inventory-and-health-registry.md) for the full design,
including the MCP discovery assumption and the `WARNING`-is-sticky decision.

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

## Running in Azure (ADR-027, ADR-028)

The stack also runs as containers in Azure Container Apps — full plan,
topology, live-run gotchas and the security review:
[docs/azure-deployment-plan.md](docs/azure-deployment-plan.md).

| Audience | URL | TLS |
|---|---|---|
| People — Admin Console, Approval Center, login | `https://demo.zteasy.tech/admin/index.html`, `/approver/index.html` | Azure managed certificate, auto-renewing |
| Agents — MCP over mTLS | `https://gateway.<env>.northeurope.azurecontainerapps.io:8080` | dev ZTE-CA, client certificate required |

Two front doors onto one system: a browser-facing app (HTTP ingress, custom
domain) and the agent-facing one (TCP passthrough, so the client certificate
survives to the gate). Same image, same Postgres/Keycloak/MCP backend.

```bash
# Provision from scratch (needs GHCR_USER/GHCR_PAT, HUBSPOT_TOKEN; see the plan)
./deploy/azure/deploy.sh

# Park it overnight / bring it back / see what's running
./deploy/azure/power.sh stop
./deploy/azure/power.sh start
./deploy/azure/power.sh status

# Attach a custom domain + free managed certificate (prints the DNS records first)
./deploy/azure/bind-custom-domain.sh
./deploy/azure/bind-custom-domain.sh demo.zteasy.tech
```

Deployment-only settings worth knowing: `ZTE_INTERNAL_API_KEY` (shared secret
for `/api/v1/internal/**` — mandatory once the gateway has a public ingress),
`ZTE_AUTH_PROXY_ENABLED` (serve Keycloak's login under `/auth`),
`ZTE_UI_OIDC_AUTHORITY` (what both SPAs get from `/ui-config.js`), and
`MANAGEMENT_PORT` set equal to each service's API port (a Container App
publishes only one port).

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

# 4. Set Keycloak passwords (first time only). Defaults are localhost-only
#    development values; override with ZTE_LOCAL_ADMIN_PASSWORD /
#    ZTE_LOCAL_USER_PASSWORD, or pass them as arguments.
./scripts/set-keycloak-password.sh

# 5. Start services (each in a separate terminal)
./gradlew :gateway-service:bootRun
./gradlew :service-a:bootRun
./gradlew :service-b:bootRun

# 6. Get an ADMIN token
TOKEN=$(curl -s -X POST http://localhost:8180/realms/zte-realm/protocol/openid-connect/token \
  -d "grant_type=password&client_id=zte-gateway&client_secret=zte-gateway-secret" \
  -d "username=zte-admin&password=$ZTE_LOCAL_ADMIN_PASSWORD" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# 7. Call the full chain: User → Gateway → Service A → Service B
# As of ADR-018, /api/v1/** (proxied REST traffic) requires a client certificate in
# addition to the JWT — server.ssl.client-auth: want + MtlsEnforcementWebFilter. This
# applies uniformly to this general REST-proxy surface, not just agent/MCP traffic, so
# even this interactive-user demo call needs --cert now. -k accepts the dev self-signed CA.
curl -sk --cert certs/client.p12:zte-pass --cert-type P12 \
  -H "Authorization: Bearer $TOKEN" https://localhost:8080/api/v1/service-a/hello | python3 -m json.tool
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
| 14 | Machine identities (OIDC clients synced as `CLIENT` type), `client:<clientId>` URN unification for `service2service`/`agentMcpToolCalls`, orphaned-rule detection extended to all three categories (ADR-015) | `f5a30b8` |
| 15 | Identities UI refactor (Actors vs. Access Containers, MUI Accordions, quick search, relations Drawer), `idp_identity_relations` caching, Keycloak system-client filtering | `1198921` |
| 16 | APIM inventory registry (`inventory_services`/`health_metrics`), auto-discovery on onboarding, periodic health polling, passive `last_successful_call` telemetry, Admin Console "Registry" tab | `c3fd7de` |
| 17 | Dynamic inventory-driven routing (`InventoryRouteDefinitionLocator`, replacing hardcoded routes), REST/MCP audit unification into `request_logs`, strict `service2service` policy scenario (ADR-017) | `87d9976` |
| 18 | Smart mTLS enforcement: gateway HTTPS (`gateway.p12`), `server.ssl.client-auth: want`, `MtlsEnforcementWebFilter` gating `/sse`/`/message`/`/api/v1/**` minus `/admin`/`/internal` (ADR-018) | `<commit>` |
| 19 | HOLD decision outcome (🟡): `agentMcpToolHolds` policy category, `pending_approvals` table, `PendingApprovalService`, `POST /api/v1/admin/approvals/{id}/approve\|reject`, Admin Console "Approvals" tab, new `crm-account-health-emea-01` demo agent, plus a Stage 2 honest-deny/hold verification pass (specific deny reasons confirmed everywhere, MCP's always-202 transport reconciled with "honest deny," two Admin Console chip-coloring gaps fixed) — ACAP/DIGI-KAI governance demo Stages 1–2 of 6 (ADR-019) | `<commit>` |
| 20 | ACAP scope profiles (🟢/🔴 by argument, not just tool name): `AcapProfile`/`AcapProfileStore`/`AcapScopeEvaluator`, territory + data-minimization field checks + read-only write-deny + bulk/export-deny, `GET/POST /api/v1/admin/acap-profiles[/reload]`, new `acap-profiles/crm-account-health-emea-01.yaml` — ACAP/DIGI-KAI governance demo Stage 3 of 6 (ADR-020) | `<commit>` |
| 21 | Governance dashboard: `GovernanceService`, per-agent ALLOW/HOLD/DENY activity + out-of-policy-attempts feed + JSON export (`GET /api/v1/admin/governance/{agent-activity,out-of-policy,report}`), Admin Console "Governance" tab — read-only reporting over the existing `request_logs` audit trail — ACAP/DIGI-KAI governance demo Stage 4 of 6 (ADR-021) | `<commit>` |
| — | CRM tool-surface alignment: `read_contacts`/`read_deals`/`read_activities`/`update_deal`/`export_contacts`/`send_email`/`draft_followup`/`escalate` added to `hubspot-mcp` (sibling repo, no ZTeasy commit) alongside agent-a/b's existing tools; `agent_simulator.py` extended to run the demo's full 🟢/🔴/🟡 script — ACAP/DIGI-KAI governance demo Stage 5 of 6 | n/a (`hubspot-mcp` repo) |
| 22 | ACAP agent metadata + usage thresholds: `AcapProfile.agent`/`risk` (display-only), `thresholds` + `AcapThresholdTracker` (in-memory daily-reset counter escalating ALLOW to HOLD on limit exceedance), Admin Console Governance tab's new "ACAP Profiles" section (owner/deployment/reauth-overdue badge/risk tier/threshold usage chips) — ACAP/DIGI-KAI governance demo Stage 6 of 6, final stage (ADR-022) | `<commit>` |
| 23 | `PolicyRule.mcpTarget`: scopes `agentMcpToolCalls`/`agentMcpToolHolds` rules to a specific MCP backend (matched against `mcp-backend.name`) so a rule can't silently keep matching a same-named tool if the gateway is repointed at a different backend; `zte-policies.yaml`'s per-agent grants scoped to `hubspot-mcp`, its name-shape safety nets deliberately left unscoped; `PolicyValidator`'s duplicate-detection fixed to treat `mcpTarget` as part of the uniqueness tuple (ADR-023) | `<commit>` |
| 24 | Gateway OpenAPI documentation: `springdoc-openapi` added to `gateway-service` (auto-discovers every `@RestController`, `GET /v3/api-docs` + `/swagger-ui.html`, both `permitAll` via new `ApiDocsSecurityConfig`), Admin Console "Documentation" tab rendering the spec via `swagger-ui-react` (reusing `SchemaDrawer.tsx`'s existing pattern) (ADR-025) | `<commit>` |
