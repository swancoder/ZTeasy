# ADR-027: Azure Deployment — Container Apps, Single External Origin, `/auth` Reverse Proxy

**Status:** Accepted
**Date:** 2026-08-25
**Stage:** 27

## Context

The stack must move to Azure "as is" — each server in its own container,
images in a free registry — exposing externally only the Admin Console and
the Approval Center. Agents operate strictly inside the perimeter; the MCP
bridge is the only component with HubSpot egress. Both SPAs log in via
OIDC authorization-code redirects, which means an unauthenticated browser
must be able to reach Keycloak's login pages — in tension with "only two
pages exposed."

### THOUGHTS

- The gateway is already the natural single entry point: every sensitive
  path demands JWT + client cert at the app layer (ADR-018), so exposing
  its one port exposes exactly the two SPA surfaces plus locked doors.
- ACA's HTTP ingress terminates TLS at Envoy — that would strip agent
  client certificates and break `MtlsEnforcementWebFilter`. TCP-passthrough
  ingress hands the raw TLS stream to the gateway, preserving both browser
  HTTPS and agent mTLS on one port. This is the deciding constraint.
- Proxying Keycloak under the gateway (`/auth/**`) was chosen over exposing
  it on its own host (user decision): one external origin, no second FQDN.
  The mechanically tricky part is issuer consistency — solved by
  `KC_HTTP_RELATIVE_PATH=/auth` (no path rewriting) + `KC_HOSTNAME_URL`
  (fixed external issuer in every token) + the gateway validating that
  issuer while fetching JWKS internally (`KEYCLOAK_JWKS_URI`).
- ADR-026's `/ui-config.js` already decouples the SPAs from the authority
  URL, so no rebuild between local and cloud.

## Decision

1. **`KeycloakAuthProxyConfig`** (gateway): a `@ConditionalOnProperty`-gated
   (`zte.auth-proxy.enabled`, default **false**) static Gateway route
   `/auth/**` → `zte.auth-proxy.uri`, plus a permitAll security chain for
   that prefix. `ZteAuthorizationFilter` passes anonymous exchanges through
   by design (its step 2), so no policy rule is involved; `/auth/` joins
   the audit exclusion list. Local dev is untouched (flag off, Keycloak
   still direct on :8180).
2. **Dockerfiles** for the two missing modules: `gateway-service/Dockerfile`
   (node stage builds both SPAs, Gradle stage skips the npm tasks and
   packages the ready dists) and `zt-agents/Dockerfile`; `hubspot-mcp` gets
   `Dockerfile.bridge` (long-running backend) and `Dockerfile.agents`
   (one-shot demo job).
3. **`docker-compose.cloud.yml`**: a local mirror of the exact cloud wiring
   (single origin `https://localhost:8443`, `/auth` proxy, internal-only
   everything else) — the cloud topology is verified locally before any
   Azure resource exists.
4. **`deploy/azure/make-cloud-realm.py`**: generates the cloud realm import
   (baked demo passwords — there is no `docker exec kcadm` against ACA and
   the H2 store is ephemeral; single-origin redirect URIs).
5. **Azure shape** (see `docs/azure-deployment-plan.md` for the full plan
   and runbook): one ACA environment = the perimeter; GHCR private images;
   gateway = the only external ingress (TCP passthrough `:8443`); certs on
   an Azure Files share (never in images), regenerated with the gateway
   FQDN SAN (`GATEWAY_EXTRA_SANS` hook in `generate-certs.sh`);
   agent-runner as a manual ACA Job.

## Alternatives considered

- **Expose Keycloak on its own external host** — the standard topology and
  operationally simpler; rejected by explicit user choice for a single
  origin.
- **ROPC (password grant) login pages** — keeps Keycloak fully internal but
  abandons the OIDC redirect flow both SPAs already implement, and ROPC is
  deprecated practice.
- **ACA HTTP ingress + disabling gateway TLS** — would offload TLS but
  breaks inbound mTLS (ADR-018) entirely; rejected.
- **ACI single container group / VM + compose** — closest to "as is" but
  no free tier, no per-app scaling, and the perimeter is just one shared
  network namespace; ACA chosen (user decision).

### CRITIQUE

- TCP passthrough means the browser sees the dev ZTE-CA cert — a warning
  interstitial on first visit. Acceptable for a demo; a custom domain +
  publicly-trusted cert (or moving the two SPA paths behind a second,
  HTTP-ingress app) is the production path.
- Ephemeral Postgres/Keycloak state: every restart wipes audit history and
  Keycloak sessions. Named, deliberate demo tradeoff; `DB_HOST` env is the
  upgrade seam.
- `zt-agents` can't TLS-validate the gateway's internal endpoint (no
  ZTE-CA truststore) — its auditor call fails in this topology until a
  truststore is mounted; shipped anyway for inventory completeness.
- The `/auth/**` proxy widens the gateway's unauthenticated surface to
  include Keycloak's login pages when enabled — mitigated by Keycloak's own
  auth on everything sensitive, and by the flag defaulting off outside the
  cloud profile.

## Amendment (2026-08-25) — what the first live deployment changed

The design above survived contact with Azure; five mechanical constraints
did not appear in any planning and are now encoded in the scripts (full
list with symptoms: `docs/azure-deployment-plan.md`, "What the first live
run taught us"). The two that changed *decisions* rather than just code:

- **The environment must be VNET-backed from creation.** External TCP
  ingress — the mechanism this ADR chose specifically to preserve inbound
  mTLS — is only permitted on an environment with a custom VNET whose
  infrastructure subnet is delegated to `Microsoft.App/environments`, and a
  VNET cannot be retrofitted. So the "one ACA environment = the perimeter"
  decision now also implies a VNET + delegated subnet; `ENV_NAME` moved to
  `zteasy-env-v2` after the first, VNET-less environment had to be
  destroyed.
- **Apps address each other by bare name, not by `internal` FQDN.** The
  plan's internal URLs (`postgres.internal.<domain>`) time out for
  TCP-transport apps; every internal URI is now `postgres:5432`-shaped.
  This is invisible in a compose mirror (where bare names always worked),
  so it only surfaced in the cloud.

A second round of live findings (same day) covered the registry/mTLS side:
`generate-certs.sh` mints a new CA per run, so phase 2 must restart *every*
cert-holding app or the gateway stops trusting service-a/service-b; ACA
publishes one port per app, so `/actuator/health` moves onto each service's
mTLS port (`MANAGEMENT_PORT`) or both go `DOWN` and lose their routes; and
the MCP bridge is now onboarded into the APIM registry by the deploy script
(the bootstrap seeder only covers service-a/service-b). The bridge itself
needed two fixes in its own repo — it bound to `localhost` (unreachable
across containers, so no tool call reached HubSpot) and spoke HTTP/1.0,
breaking the gateway's pooled connections.

Deployed and verified end to end (both SPAs, `/auth` login, approver API,
cert-less `/sse` → 401, in-perimeter agent job, approve-then-Governance,
all registry entries ACTIVE with schemas, and a live `read_contacts(EMEA)`
returning real HubSpot data through the proxy).

## Consequences

- One public URL serves both human surfaces and the login flow; everything
  else is invisible from outside the environment.
- The same images run locally (compose mirror) and in ACA with only env
  changes — no rebuild between environments.
- The unauthenticated gateway→bridge hop is now compensated by real network
  isolation (internal-only app), pending the bearer/mTLS fix queued from
  today's gap analysis.
