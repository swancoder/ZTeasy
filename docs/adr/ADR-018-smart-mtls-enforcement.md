# ADR-018: Smart mTLS Enforcement (client-auth: want + Application-Layer WebFilter)

## Status
Accepted

## Context

The gateway's own inbound listener (port 8080) has had zero TLS configuration
since it was first stood up — plain HTTP throughout. Agents (Agent A/B,
ADR-010) and general REST-proxy callers authenticate purely via OAuth2/JWT
bearer tokens; there is no transport-layer identity proof on this hop at all,
unlike the already-mTLS'd outbound chain gateway→service-a→service-b
(`server.ssl.client-auth: need`, ADR-004). `InternalSecurityConfig`'s own
Javadoc named this gap explicitly: *"the gateway runs on HTTP... making
transport-layer mTLS infeasible without a second HTTPS listener."*

This ADR closes that gap for MCP/agent traffic (`/sse`, `/message`) and the
general S2S/REST-proxy surface (`/api/v1/**`), while leaving the browser-facing
Admin Console (`/admin/**`), Admin API (`/api/v1/admin/**`), internal endpoints
(`/api/v1/internal/**`), and actuator untouched and cert-free — those must keep
working over plain browser HTTPS, since browsers don't carry a ZTE-CA-issued
client certificate.

**Explicitly out of scope:** the separate gateway → MCP-backend-bridge hop
(`McpBackendClient` → `mcp-backend.uri`, currently plain HTTP to
`localhost:9090`). That gap is tracked independently in `docs/SPECS.md`
§8.5/§9.3 and is unaffected by this change.

## Decision

**`server.ssl.client-auth: want`, not `need`, on the gateway's single existing
port 8080** — the TLS handshake succeeds whether or not the client presents a
certificate. Cert *presence* is enforced separately, at the application layer,
by a new `MtlsEnforcementWebFilter`, scoped to specific path prefixes:

- Always protected: `/sse`, `/message` (the MCP proxy, `McpRouterConfig`).
- Protected: `/api/v1/**`, **except** `/api/v1/admin/` and `/api/v1/internal/`
  — the same two prefixes `AuditExclusionProperties`/`zte.audit.excluded-path-prefixes`
  already treats as gateway-local, non-proxied traffic.
- Never protected: `/admin/**` (Admin Console static assets), `/actuator/**`,
  and the two excluded `/api/v1/` prefixes above.

A brand-new gateway server certificate (`gateway.p12`, `certs/generate-certs.sh`,
same CA, `extendedKeyUsage=serverAuth`) backs the listener; the existing shared
`truststore.p12` (CA-only) is reused to validate any presented client cert.
`MtlsEnforcementWebFilter` runs at `Ordered.HIGHEST_PRECEDENCE + 50` — before
Spring Security's `WebFilterChainProxy` (JWT check), before `AdminAuthorizationFilter`
(implicit lowest precedence), before `RequestAuditFilter` (`LOWEST_PRECEDENCE - 100`),
and before every Gateway `GlobalFilter` (`ZteAuthorizationFilter`/
`ServiceToServiceAuthorizationFilter`/`UserContextPropagationFilter`, which only
fire once `FilteringWebHandler` is reached). A missing client cert on a
protected path is rejected with `401` before any JWT/policy work happens. It
does not replace the JWT check — a protected MCP call still separately needs a
valid bearer token; the two checks are independent and both required.

`zte.mtls.enabled` (the existing property gating `MtlsHttpClientConfig`'s
outbound client config) also gates this filter, read via
`@Value("${zte.mtls.enabled:true}")` rather than `@ConditionalOnProperty`,
since a `WebFilter` bean needs to be present to make the runtime decision per
request, not conditionally registered — `application-it.yml` sets it `false`
for the IT suite, alongside a **new, separately necessary** `server.ssl.enabled:
false` (the listener itself is a distinct, transport-layer concern from this
property).

## Alternatives Considered

### A dedicated second HTTPS listener (e.g. port 8443) for MCP/agent traffic only (rejected)

- **Pros:** Genuine isolation of the agent-facing surface — a missing cert
  fails at the TLS handshake itself, not one layer up in application code;
  matches service-a/b's uniform `client-auth: need` posture exactly.
- **Cons:** Spring Boot's embedded reactive Netty server only auto-configures
  one listener from `server.ssl.*` — a second one requires a custom
  `NettyReactiveWebServerFactory`/`ReactorHttpHandlerAdapter` wiring, real Java
  server-bootstrap code, not configuration. That's a materially larger, more
  fragile surface to maintain and to eventually port when the Data Plane is
  rewritten in Go.
- **Verdict:** Rejected. The operational simplicity of "one port, one cert,
  one listener" was weighted higher than TLS-layer purity for this stage.

### Blanket `client-auth: need` on the single port 8080 (rejected)

- **Pros:** Simplest possible config — mirrors service-a/b exactly, no new
  filter class needed at all.
- **Cons:** Forces every caller through mTLS, including the Admin Console's
  browser session (browsers don't carry a ZTE-CA client cert) and the
  actuator/internal endpoints — breaks the existing Quick Start's plain-HTTPS
  Admin UI flow outright.
- **Verdict:** Rejected — too coarse for a gateway that serves both a browser
  UI and machine-to-machine MCP/S2S traffic on the same port.

## Self-Criticism

| Risk | Severity | Mitigation |
|---|---|---|
| No cert-to-identity binding: the filter checks *presence* of a peer certificate, not that its CN/SAN matches the caller's claimed identity (unlike JWT `azp`, which is checked separately and is the actual identity signal used downstream). Any cert signed by the ZTE-CA passes, regardless of which client it was minted for. | Medium | Acceptable for this stage — the JWT bearer check remains the real authorization signal; the cert only proves "this caller possesses a ZTE-CA-issued credential," a coarser but still meaningful transport-layer bar. Cert-to-`azp` correlation is a natural follow-up (Future Migration Path). |
| Two-tier, path-dependent enforcement is a deliberate departure from service-a/b's uniform `client-auth: need` — the gateway's overall mTLS posture is no longer "always required," it's "required if you're calling one of these prefixes." A future contributor adding a new `/api/v1/**`-shaped endpoint that *should* be admin-only could forget to add it to the exclusion list and accidentally require a client cert from browser users, or forget to protect something that should require one. | Low | The exclusion list is a small, `private static final` set (matches `AuditExclusionProperties`'s existing prefixes) and is documented in this ADR and the filter's own Javadoc; not currently unit-tested against every existing route individually (`MtlsEnforcementWebFilterTest` covers the four representative path shapes). |
| The gateway → MCP-backend-bridge hop (`McpBackendClient` → port 9090) remains completely unauthenticated — this ADR does nothing for it. | Low (by design) | Explicitly out of scope, cross-referenced to the existing `docs/SPECS.md` §8.5/§9.3 backlog item, not silently left implicit. |
| `client-auth: want` means a misconfigured or malicious client that fails certificate validation (e.g. presents a cert not signed by the ZTE-CA) doesn't get a hard TLS-handshake failure — Reactor Netty/the JDK's TLS stack behavior for a *rejected* (not merely *absent*) client cert under `want` was not independently verified as part of this change. | Low | Worth a dedicated follow-up test against a cert from a different, non-ZTE CA — not currently covered by `MtlsEnforcementWebFilterTest` (which only exercises presence/absence, not trust-chain validity, since that's enforced by the TLS layer itself before the filter ever runs). |

## Consequences

- The gateway is now HTTPS-only on port 8080 — every prior plain-`http://`
  reference (Quick Start curl examples, the Keycloak `zte-gateway`/`zte-admin-ui`
  client `redirectUris`/`webOrigins`, README's Admin Console instructions) needs
  updating to `https://` (dev examples additionally need `-k` for the
  self-signed ZTE-CA). Handled in this same change: `keycloak/realm-export.json`,
  `README.md`.
- Agent/S2S callers now need both a valid client certificate *and* a valid JWT
  bearer token to reach `/sse`, `/message`, or a proxied `/api/v1/**` route —
  a strictly stronger bar than before (JWT alone), with zero change to the
  Admin Console/Admin API/internal/actuator surface.
- Future Go-rewritten Data Plane only needs to port a path-prefix list and a
  single "does the request carry a peer certificate" check — not any
  Netty-listener-level wiring — matching the operational-simplicity rationale
  behind rejecting the dedicated-port alternative above.

## Future Migration Path

- Cert-to-identity binding: cross-check the peer certificate's CN/SAN against
  the JWT `azp` claim, so a stolen-but-mismatched cert/token pair is
  detectable, not just "a cert was present."
- Per-agent client certificates (distinct CNs for Agent A/B, rather than the
  shared `client.p12` used internally today) — a SPIFFE/SVID-style identity
  model, already flagged as a deferred item in ADR-004 for the same reason.
- Authenticate the gateway → MCP-backend hop (`docs/SPECS.md` §8.5/§9.3) —
  `McpBackendClient` already rides the same mTLS-capable `WebClient` connector
  used for service-a/b once its target URI is `https://`; only the backend's
  own TLS listener needs to exist.
- A negative test proving `client-auth: want` correctly rejects (at the TLS
  layer) a client cert signed by an untrusted CA, not just an absent one.
