# ZTE-Lightweight — Project Summary

**Lightweight Zero Trust Environment (MVP)**
Demonstrating AI-driven development: Gemini as Architect, Claude as Engineer.

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
│   └── GatewayRouteConfig            Routes: /api/v1/service-a/**, /api/v1/service-b/**
│
├── service-a/             Protected downstream — port 8081 (HTTPS/mTLS), 9081 (mgmt)
│   ├── HelloController    Calls service-b, returns combined response
│   └── ServiceBClientConfig          mTLS WebClient for outbound calls
│
├── service-b/             Deep downstream — port 8082 (HTTPS/mTLS), 9082 (mgmt)
│   └── UserContextController         Validates OBO token, returns user context
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
