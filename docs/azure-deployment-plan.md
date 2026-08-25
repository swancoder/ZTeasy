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

## What the first live run taught us (2026-08-25)

Every one of these is now encoded in `deploy/azure/deploy.sh`; they are the
non-obvious constraints, not generic advice:

1. **Region**: `westeurope` refused this subscription outright ("not
   accepting new customers"). `northeurope` works — hence the new default.
2. **External TCP ingress requires a custom VNET.** A Consumption
   environment without one rejects the gateway app with
   `ContainerAppTcpRequiresVnet`, and a VNET cannot be added afterwards —
   the environment must be created with `--infrastructure-subnet-resource-id`
   from the start, and that subnet must be **delegated** to
   `Microsoft.App/environments`.
3. **Intra-environment addressing uses the app's bare name**
   (`postgres:5432`, `keycloak:8080`). The `<app>.internal.<domain>` FQDN
   form times out for TCP-transport apps — this is what silently kept the
   gateway's Flyway migration hanging on startup.
4. **`az containerapp create --yaml` is unusable against this RP**: it
   builds its envelope for a preview api-version the service rejects with a
   bare `System.Boolean` validation error. Apps needing a volume mount are
   created with a raw ARM `PUT` (`az rest`, api-version `2024-03-01`).
5. **Image-before-app**: the Keycloak image (realm baked in) must be pushed
   before phase 1 creates the app, otherwise the pull fails
   `MANIFEST_UNKNOWN` — phase 2 rebuilds it against the real origin anyway.
6. **Keycloak's H2 import is strict**: a client `description` longer than
   255 chars hard-fails realm import; `make-cloud-realm.py` truncates.
7. **Cert regeneration used to break the trust chain.**
   `generate-certs.sh` minted a *new CA* on every run, so phase 2 (which
   reissues the gateway cert with the FQDN SAN) left service-a/service-b
   presenting certs from the previous CA — every mTLS call then failed
   `PKIX path validation failed … does not chain with any of the trust
   anchors` (schema fetch, health poll, proxied REST). Fixed at the source:
   the script now **reuses an existing CA** and only reissues leaf certs
   (`ZTE_REGENERATE_CA=1` forces a full PKI swap). Phase 2 still restarts
   keycloak, gateway, service-a and service-b so they load the reissued
   leaves.
8. **One published port per app**, so the services' separate plain-HTTP
   management ports (9081/9082) don't exist here. `MANAGEMENT_PORT` is set
   equal to each service's API port, putting `/actuator/health` on the mTLS
   port where the gateway's poll (which already carries the client cert)
   reaches it. Without this both services sit at `DOWN`, which silently
   removes their inventory-driven routes.
9. **The MCP bridge needs onboarding into the registry** —
   `InventoryBootstrapSeeder` seeds only service-a/service-b. `deploy.sh`
   now POSTs the `hubspot-mcp` entry (`http://mcp-bridge:9090`) at the end.
10. **Bridge fixes in the sibling repo** (`hubspot-mcp`, committed there):
   it bound to `localhost` — unreachable from another container, so no
   tool call ever reached HubSpot — and spoke HTTP/1.0, which broke the
   gateway's pooled Netty connections with "Connection prematurely closed
   BEFORE response". Now `MCP_BACKEND_HOST` (0.0.0.0 in the image),
   HTTP/1.1 keep-alive, and a threaded server.

Verified live on the deployed stack: both pages return 200, login through
the `/auth` proxy issues tokens with the external issuer, the approver API
answers 200 for a `USER` token, `/sse` without a client certificate is 401,
the `agent-runner` job replays the full 🟢/🔴/🟡 script from inside the
perimeter, and a held `send_email` approved from the Approval Center shows
up in the Governance counts (7 allow / 3 deny / 1 hold for the CRM agent).
After items 7–10 above: all three registry entries are `ACTIVE` with a
captured schema (the bridge reporting all 12 tools), and a real
`read_contacts(territory=EMEA)` round trip over `/sse` + `/message` returns
live HubSpot data (9 contacts) to the agent.

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
