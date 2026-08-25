# ZTeasy — Azure Deployment Plan (ADR-027)

**Goal:** lift the whole demo stack to Azure **as is** — every server in its
own container — exposing externally only the two human surfaces (Admin
Console `/admin/`, Approval Center `/approver/`). Agents live strictly
inside the perimeter; only the MCP bridge has HubSpot egress.

## Decisions (confirmed 2026-08-25)

| Decision | Choice | Why |
|---|---|---|
| Compute | **Azure Container Apps** (Consumption) | Managed, internal-by-default networking, monthly free grant (180k vCPU-s / 360k GiB-s); one env = the perimeter |
| Registry | **GHCR** (`ghcr.io/swancoder/*`), private images | Free; images carry demo credentials (realm passwords) so they stay private; ACA pulls with a PAT |
| Keycloak exposure | **Reverse-proxied under the gateway** at `/auth/**` | One external origin; browser OIDC redirects stay on the gateway's host (user-selected option) |
| External surface | **One TCP-passthrough ingress** on the gateway (`:8080` → container `8080`) | The gateway terminates its own TLS (`gateway.p12`, `client-auth: want`), so agent mTLS and browser HTTPS coexist on one port — ACA's HTTP ingress would strip client certs |

## Topology

```
                        Internet
                           │  https://<gateway-fqdn>:8080
                           ▼  (TCP passthrough — TLS ends at the gateway itself)
   ┌─ Container Apps environment (= perimeter) ─────────────────────────┐
   │  gateway  ── /admin/, /approver/, /auth/* (→ keycloak), /sse …     │
   │     │ mTLS              │ http (internal)         │ http           │
   │     ▼                   ▼                         ▼                │
   │  service-a ─mTLS─► service-b     keycloak      postgres            │
   │                                                                    │
   │  mcp-bridge ──────────────────────────► HubSpot API (only egress)  │
   │  zt-agents (internal)                                              │
   │  agent-runner (ACA Job, on demand — plays the 🟢/🔴/🟡 demo)        │
   └────────────────────────────────────────────────────────────────────┘
```

- **Only `gateway` has external ingress.** Everything that isn't `/admin/**`,
  `/approver/**`, `/auth/**`, or `/ui-config.js` still demands JWT + a
  ZTE-CA client certificate at the application layer (ADR-018), so the
  exposed port is not an open proxy.
- **Keycloak** runs with `KC_HTTP_RELATIVE_PATH=/auth` and
  `KC_HOSTNAME_URL=https://<fqdn>:8080/auth` — the issuer in every token is
  the external URL no matter where the token was requested from; the
  gateway validates that issuer while fetching JWKS over the internal
  network.
- **State is ephemeral by design** (demo scope): Postgres data and
  Keycloak's dev-file H2 live in the containers; a restart re-imports the
  realm (with baked demo passwords — `deploy/azure/make-cloud-realm.py`)
  and re-runs Flyway from scratch. Upgrading to Azure Database for
  PostgreSQL is a config change (`DB_HOST` etc.), deliberately out of
  scope today.
- **Certs**: regenerated once with the gateway FQDN in the SAN
  (`GATEWAY_EXTRA_SANS=DNS:<fqdn>`), uploaded to an Azure Files share,
  mounted read-only into gateway/service-a/service-b/agent-runner. Never
  baked into images.

## Steps

1. **Local mirror first** — `docker-compose.cloud.yml` runs the exact cloud
   wiring (single origin `https://localhost:8443` — host port 8443 to avoid
   the dev gateway on 8080 — `/auth` proxy, internal
   bridge) so every image and env var is proven before any cloud resource
   exists.
2. **Build & push images** to GHCR: `zteasy-gateway`, `zteasy-service-a`,
   `zteasy-service-b`, `zteasy-zt-agents`, `zteasy-keycloak` (stock image +
   realm import file), `hubspot-mcp-bridge`, `hubspot-mcp-agents`.
3. **Provision** (`deploy/azure/deploy.sh`): resource group → ACA
   environment → storage account + file share (certs) → the seven apps
   (gateway external TCP ingress; the rest internal or ingress-less;
   agent-runner as a manual-trigger Job). Secrets (`HUBSPOT_TOKEN`,
   `ANTHROPIC_API_KEY`, GHCR PAT, `ZTE_OBO_SECRET`) go in as ACA secrets.
4. **Two-phase config**: the gateway's FQDN exists only after the app is
   created — create it first, then regenerate certs with the FQDN SAN,
   regenerate `realm-cloud.json` against the real origin, push the final
   keycloak image, and update the gateway/keycloak apps.
5. **Verify e2e**: admin + approver pages log in via `/auth`; run the
   agent-runner Job; approve/decline the held `send_email` from the
   Approval Center; check the Governance tab counts.

## Known limitations (accepted for the demo)

- Browser shows a self-signed-CA warning (`ZTE-CA`) — no public cert on the
  TCP-passthrough path. A custom domain + real cert is a follow-up.
- Ephemeral Postgres/Keycloak state (above).
- `zt-agents` → gateway policy fetch fails TLS validation (it doesn't trust
  the ZTE-CA yet) — container ships anyway; fix is a truststore mount, in
  the backlog with the gateway→bridge auth work.
- The MCP bridge accepts unauthenticated calls from inside the perimeter —
  the #1 gap from today's analysis; network isolation (internal-only, no
  ingress from anywhere but the env) is the compensating control until
  bearer/mTLS auth lands there.
