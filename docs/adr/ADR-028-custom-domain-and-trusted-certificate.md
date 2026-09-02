# ADR-028: Custom Domain with a Publicly-Trusted Certificate — a Second, Browser-Facing Ingress

**Status:** Accepted, then **superseded by [ADR-040](ADR-040-one-front-door.md)** (2026-09-02): Azure's HTTP ingress can request and forward a client certificate (`clientCertificateMode`), which this ADR did not use — so the two-app split it introduced is no longer necessary and has been merged back into one.

**Original status:** Accepted
**Date:** 2026-08-25
**Stage:** 28

## Context

The Azure deployment (ADR-027) is reachable at
`https://gateway.<env>.northeurope.azurecontainerapps.io:8080`, serving the
dev ZTE-CA certificate — a browser interstitial on every visit and a port in
the URL. The demo needs `https://demo.zteasy.tech` with a certificate from a
real issuer. `zteasy.tech` already exists (Namecheap DNS, apex on GitHub
Pages); `demo` is free.

### THOUGHTS

- ADR-027 chose **TCP passthrough** ingress precisely so a client
  certificate survives to the gateway — that's what makes
  `MtlsEnforcementWebFilter` (ADR-018) meaningful in the cloud at all.
- Azure refuses a custom domain on that ingress:
  `ContainerAppInvalidIngressCustomDomainForTcpApp` — "custom domains can
  only be set for http transport". Verified by attempting it, not read off a
  doc page.
- Bringing our own Let's Encrypt certificate to the TCP listener does work
  (nothing in the path inspects SNI), but the environment already owns port
  443 for its HTTP ingress — `exposedPort: 443` fails with "already in
  use" — so the URL keeps a port, and the cert needs a 90-day manual DNS-01
  renewal.
- The two audiences have different needs: **browsers** (Admin Console,
  Approval Center, the `/auth` login redirect) need a trusted certificate and
  a clean URL, and never present a client certificate — those paths are in
  `MtlsEnforcementWebFilter`'s excluded list already. **Agents** need
  transport-level mTLS and don't care about the hostname or the issuer; in
  this deployment they live inside the perimeter and reach the gateway by its
  internal name.

## Decision

Run a **second Container App, `gateway-web`, from the same image**, with HTTP
ingress, the custom domain `demo.zteasy.tech`, and an Azure **managed
certificate** (free, auto-renewed). The existing `gateway` app keeps its TCP
passthrough ingress for agent/mTLS traffic, unchanged.

- `gateway-web` runs with `SERVER_SSL_ENABLED=false` (Azure terminates TLS in
  front of it) but keeps `zte.mtls.enabled=true`, so its *outbound* calls to
  service-a/service-b still carry the gateway's client certificate — the
  Admin Console's schema fetches and health polling keep working. Its inbound
  mTLS enforcement is untouched: `/sse` and `/message` simply refuse there,
  which is correct, since agents don't use this ingress.
- Both apps share Postgres, Keycloak, the MCP bridge and the certs volume, so
  approvals decided on one are visible on the other — one system, two front
  doors.
- Keycloak's `KC_HOSTNAME_URL` becomes `https://demo.zteasy.tech/auth`, so
  every token's issuer is the custom domain regardless of which app or which
  network path requested it; both gateways validate that issuer and fetch
  JWKS internally. `make-cloud-realm.py` now accepts several origins so the
  SPA clients' redirect URIs cover the custom domain *and* the Azure FQDN.
- DNS (in the domain's own registrar, by the domain owner): `CNAME demo →
  gateway-web.<env>.northeurope.azurecontainerapps.io` and `TXT asuid.demo →
  <customDomainVerificationId>`.

## Alternatives considered

- **Move everything to HTTP ingress.** One app, one certificate — but Azure
  then terminates TLS for agent traffic too, so the client certificate
  survives only as an `X-Forwarded-Client-Cert` header. That turns the
  demo's central claim ("mTLS all the way to the gate") into a header the
  proxy asserts, and needs `MtlsEnforcementWebFilter` plus every agent
  reconfigured. Rejected: it weakens the thing being demonstrated.
- **Let's Encrypt on the TCP listener.** Keeps mTLS intact on a single
  address and was the initially chosen option — until `exposedPort: 443`
  turned out to be taken by the environment, leaving `:8080` in the URL,
  plus a manual 90-day renewal. Rejected once the port limit was measured.
- **Azure Front Door / Application Gateway in front.** Also terminates TLS
  (same objection), and adds a paid component to a demo.

### CRITIQUE

- Two revisions of the gateway image run instead of one: roughly double the
  gateway's compute, and both execute the same background jobs (health
  polling, IdP sync, route refresh) against shared state. Harmless
  duplication here, wasteful at real scale — a leader-election or a
  jobs-disabled flag on the web copy would be the clean fix.
- MCP session state is in-memory per instance (a known ADR-009 gap); with two
  apps it is now also *split* between them. Fine only because agents never
  touch `gateway-web`.
- The custom domain covers the browser path only. An external agent (outside
  the perimeter) still faces the dev CA on `:8080` — acceptable while agents
  are in-perimeter, and the reason the Let's Encrypt option is documented
  above rather than discarded.
- A managed certificate ties renewal to Azure's DNS validation staying happy;
  if the CNAME is ever repointed, the certificate stops renewing silently.

## Deployment notes (from the live run)

- Binding is scripted: `deploy/azure/bind-custom-domain.sh` with no arguments
  prints the two DNS records to add; with the domain, it verifies both
  records resolve as expected, binds the hostname, issues the managed
  certificate, regenerates the realm for both origins and repoints Keycloak
  and both gateways.
- **A Container App can wedge in `provisioningState: Failed`, and every later
  update then fails with the *original* error rather than a current one.**
  The earlier `exposedPort: 443` attempt (rejected because the environment
  owns 443) left the `gateway` app failed, and subsequent env updates kept
  reporting the stale 443 conflict; the real blocker by then was a duplicate
  `revisionSuffix` in the stored template. Recovery is a full ARM `PUT` with
  a fresh suffix — re-supplying secret values, since a `GET` returns them as
  `null` and a `PUT` would otherwise store the nulls.
- Verified after the switch: the certificate is issued by DigiCert/GeoTrust
  for `CN=demo.zteasy.tech` (valid to 2027-02-25), both SPAs and
  `/ui-config.js` answer 200 over normal TLS validation, tokens carry
  `iss: https://demo.zteasy.tech/auth/realms/zte-realm`, the approver and
  admin APIs answer 200, the security fixes from ADR-027 still hold on the
  new origin (internal endpoints 403 without the key, Keycloak admin 404),
  and the in-perimeter agent job still runs the full script.

## Consequences

- `https://demo.zteasy.tech/admin/index.html` and `/approver/index.html` are
  the demo URLs, with a publicly-trusted, auto-renewing certificate and no
  port.
- The agent-facing ingress, and with it the end-to-end mTLS story, is
  untouched.
- Adding a browser-facing front door is now a scripted operation:
  `create-app-with-certs.sh` takes a transport argument, and
  `make-cloud-realm.py` takes multiple origins.
