# ADR-038 — Authenticating the gateway → MCP backend hop

**Status:** Accepted · 2026-09-02
**Context:** Stage 38 · closes the hop ADR-018 explicitly did not touch

## Context

Every inbound path into this system is authenticated: agents present a client
certificate and a JWT (ADR-010/ADR-018), users present a JWT, service-to-service
calls carry mTLS plus an on-behalf-of token (ADR-004). The one *outbound* hop —
gateway → MCP backend — was plain HTTP, and the bridge's own source said so:

> this bridge has no auth of its own — ZTeasy's gateway is the gate

That is true of the HTTP path and false of the network. Anything able to reach
port 9090 could call tools directly: no policy evaluation, no ACAP scope check,
no masking, no audit row. In the cloud deployment the things able to reach it
include **the agent containers themselves**, which mount the certs share and
exist precisely to be governed by the gateway they would have been stepping
around. The product's central claim — "an agent cannot touch the CRM except
through a policy decision" — was one `curl` away from being false.

## Decision

mTLS, with an identity issued for this hop alone.

**Why not a shared secret.** The pattern exists here already (ADR-027's internal
API key) and would have been quicker. But a header secret is a bearer token: it
leaks by being copied, and every process that can read the environment holding it
becomes the gateway. A certificate is verified against the CA and its private key
never travels.

**Why a dedicated certificate.** ADR-004 records that this PKI has one shared
client identity, `client.p12` / `CN=zte-internal-client`. Accepting "any
certificate signed by our CA" would therefore accept the agent runner, which
holds exactly that file — the check would have looked like authentication while
authenticating nothing relevant. So `generate-certs.sh` now issues
`gateway-mcp-client.p12` (`CN=zte-gateway-mcp`), given to the gateway and nothing
else, and the bridge authorises **that subject specifically**.

**What the bridge enforces.** TLS with `CERT_REQUIRED` (no certificate, no
handshake), then subject matching: `/message` requires `CN=zte-gateway-mcp`;
`/actuator/health` accepts any CA-signed peer, because the registry's health poll
(ADR-016) runs with the shared identity and the endpoint returns nothing but
`{"status":"UP"}`. Anything else is `403`, logged with the CN it refused —
"a certificate from inside the perimeter that is not the gateway" is the
interesting event, and it is an agent trying to skip the gate.

**Fail closed.** The bridge refuses to start without TLS configuration unless
`MCP_BRIDGE_INSECURE=1` is set explicitly. The previous version was insecure by
default and documented as such; documentation is not what an attacker reads
(ADR-037 made the same argument about committed defaults).

Discovery (`AutoDiscoveryWorker`, ADR-016) uses the same identity as the proxy,
since it posts to the same endpoint — otherwise onboarding an MCP backend would
fail with a 403 that reads like the backend being down.

## Consequences

- The gateway is now the only thing that can call a tool, enforced by the backend
  rather than assumed by the topology.
- Verified against the real image, locally, across five paths:

  | caller | result |
  |---|---|
  | plain HTTP | refused (no reply — TLS required) |
  | TLS, no client certificate | handshake fails |
  | shared perimeter cert (`CN=zte-internal-client`, held by the agent runner) | **403** |
  | gateway hop cert (`CN=zte-gateway-mcp`) | 200 |
  | shared cert against `/actuator/health` | 200, by design |

- In Azure the bridge reports `mTLS on; only CN='zte-gateway-mcp' may call tools`
  and serves the gateway's forwarded calls with 200s and no refusals.

## Self-critique

- **This shipped broken once, on the way in.** Adding a second
  `ReactorClientHttpConnector` bean made Spring Boot's auto-configuration
  ambiguous and the gateway failed to start — an outage caused by the fix, caught
  by reading the deployed logs rather than by any test. The default connector is
  now `@Primary`, and the reason is written where the next person will meet it.
  A test that starts the full context would have caught this; the unit suite
  deliberately does not, and the integration suite runs with `zte.mtls.enabled=false`.
- **The cloud-side refusal was proved locally**, with the same image and the same
  certificates, not by exec'ing into Azure — the container has no HTTP client and
  the exec API was rate-limited at the time. The cloud confirms the enforcement is
  on and that the gateway is accepted; the refusal path is evidenced locally.
- **A CN is a weak name.** Nothing binds `CN=zte-gateway-mcp` to *this* gateway
  beyond the CA's willingness to sign it; anyone who can run
  `generate-certs.sh` against the CA key can mint one. The CA key sits on the same
  certs share the services mount — which is the deployment's real trust boundary,
  and it is not narrowed here.
- **No revocation.** A leaked hop certificate is valid until it expires; there is
  no CRL or OCSP, matching the rest of this PKI.
- **`/actuator/health` remains open to the whole perimeter**, deliberately. It is
  also the endpoint whose Spring-shaped contract the bridge only implements to
  satisfy our poller (a pre-existing wart, noted in its own TODO).
