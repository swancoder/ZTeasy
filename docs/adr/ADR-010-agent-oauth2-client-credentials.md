# ADR-010 — Agent Authentication via OAuth2 Client Credentials, and a Deliberate Dead-End Stub

**Status:** Accepted
**Date:** 2026-07-31

---

## Context

Stage 9's goal is to prove that AI agents (Agent A, Agent B — the `hubspot-mcp`
sibling project) authenticate to the ZTeasy gateway as themselves, and that the
gateway validates that authentication, *before* wiring agent traffic into the
MCP proxy's policy engine and backend forwarding built in Stage 8 (ADR-009).

Two things needed deciding: which OAuth2 grant fits a service authenticating as
itself, and how to test that in isolation without also standing up a real
per-agent authorization model or a real backend to forward to.

## Decision

### Client Credentials grant, not Resource Owner Password

Agent A and Agent B are services, not users acting through an agent. The
Client Credentials grant (`grant_type=client_credentials`) is the correct
OAuth2 grant for this: the client authenticates as itself with its own
`client_id`/`client_secret`, no username/password involved. This mirrors the
`zte-gateway` client's existing `serviceAccountsEnabled: true` setting from
ADR-002 — service accounts weren't new to this realm, just not yet used by
anything.

Two new confidential clients were added to the **existing** `zte-realm`
(`keycloak/realm-export.json`) — not a second realm, which would fragment
identity configuration for no benefit:

| Client | Flows | Secret (dev-only) |
|---|---|---|
| `agent-a` | Client Credentials only (`serviceAccountsEnabled: true`, `standardFlowEnabled: false`, `directAccessGrantsEnabled: false`) | generated locally (ADR-037) |
| `agent-b` | same | generated locally (ADR-037) |

A Keycloak service-account token's `azp` (authorized party) claim is set to
the client_id automatically — no protocol mapper needed, and it's the same
claim `RequestAuditFilter` already reads elsewhere in this gateway. `McpProxyHandler`'s
`currentAgentId` now prefers `azp`, falling back to `sub` for tokens that lack it
(e.g. the existing user-flow tokens from `zte-gateway`).

### No new SecurityConfiguration — the existing one already covers this

`auth-library`'s `SecurityConfig` (`anyExchange().authenticated()`, JWT via
OAuth2 Resource Server, auto-configured into every service via
`ZteSecurityAutoConfiguration`) already gates `/sse` and `/message` — this was
already true and already tested as of ADR-009; it required no changes to
extend to agent-a/agent-b tokens, since it validates *any* valid JWT from the
configured issuer, regardless of which client requested it. Adding a
gateway-local `SecurityConfiguration`/`SecurityWebFilterChain` would have
created a second, redundant chain competing with the existing global one for
no benefit — Spring Security WebFlux supports multiple chains via
`securityMatcher`-scoped `@Order`ing, but there is no scenario here that needs
a second one.

`spring-boot-starter-oauth2-resource-server` was likewise already a
`gateway-service` dependency (Stage 2), and `issuer-uri`/`jwk-set-uri` already
correctly pointed at `zte-realm` on `localhost:8180` — no config changes there
either.

### Header-based bearer auth for `GET /sse`, not query-param

`/sse` stays authenticated via a normal `Authorization: Bearer` header, same
as `/message` and every other route. Query-param token extraction
(`?access_token=...`) exists in the wild specifically for browser `EventSource`
clients, which can't set custom headers — it is *strictly less secure*
(tokens end up in server logs, proxies, browser history) and unneeded here,
since Agent A/B are plain HTTP clients (Python `requests`) with full header
control. Header auth is "the easiest secure option," not a step to build
toward.

### Dead-end stub: what changed vs. what didn't

`POST /message` **no longer calls `McpPolicyEngine` or `McpBackendClient`**.
Instead, `McpProxyHandler.process` logs the extracted `clientId`, records a
`"STUBBED"` audit event, and injects a stub JSON-RPC success response (naming
the client) into the caller's SSE session via `JsonRpcResponse.stubbed(...)`.

**What did not change:** the Stage 8 HTTP/SSE transport contract. `GET /sse`
still returns the `endpoint` handshake and keeps the connection open; `POST
/message` still requires a `sessionId` from that handshake (400 if unknown),
still always returns `202 Accepted`, and the actual payload still only ever
arrives via the SSE stream. Only *what gets computed* changed, not *how it's
delivered* — this stage is about proving the auth layer, not renegotiating
the transport ADR-009 already settled.

`policyEngine`, `backendClient`, and `dataMaskingFilter` stay wired into
`McpProxyHandler`'s constructor, unused for now, with a code comment pointing
here — re-enabling per-agent policy + real forwarding is a one-method change
to `process`, not a rewire, once that's ready to test end-to-end.

### TLS / security boundary

- **Agent ↔ Gateway:** plain HTTP + bearer JWT in this dev setup (matching
  every other client of this gateway — see ADR-004's own dev-vs-prod TLS
  posture). No mTLS between agents and the gateway; mTLS in this project is
  reserved for the gateway's *own* outbound calls to service-a/b (ADR-004).
  Production would put TLS in front of the gateway's public listener
  regardless of internal mTLS.
- **Gateway ↔ Keycloak:** JWT signature validation via JWKS
  (`jwk-set-uri`), same mechanism every other client of this realm already
  uses — no new trust boundary introduced.
- **Gateway ↔ Backend MCP server:** out of scope for this stage by design
  (dead-end). When forwarding is re-enabled, `McpBackendClient`'s existing gap
  (no auth toward the backend, flagged in ADR-009/`docs/SPECS.md` §8.5) still
  needs addressing — this stage doesn't make that gap worse or better, it's
  simply not reached.

## Consequences

**Positive:**
- Two independent clients (`agent-a`, `agent-b`) with distinct identities,
  provable in isolation from any of the Stage 8 tool-call logic — a clean
  layer boundary for testing.
- Zero new Spring Security configuration surface — one fewer place for the
  "gateway becomes a God Service" risk (ADR-001) to grow.
- `azp`-based client identity is consistent with the one claim this gateway
  already uses for the same purpose elsewhere (`RequestAuditFilter`).

**Negative / Risks:**
- **Test churn**: two of Stage 8's three `McpProxyIT` scenarios (deny-vs-allow
  differentiation, "backend called once") no longer hold, since every
  authenticated call now gets the same stub outcome. Updated in place rather
  than left contradicting the new, intentional behavior.
- **Dev-only client secrets**, at the time committed to the repo (**removed by ADR-037**)
  hardcoded in `realm-export.json` — same accepted-for-MVP risk ADR-002
  already flagged for the gateway client; must be env/secret-manager-injected
  before staging.
- **No real authorization yet**: any client with a valid token from this
  realm gets the same stub, regardless of which client it is — `DummyMcpPolicyEngine`
  isn't consulted at all in this stage, so there is currently no per-agent
  *authorization* distinction, only per-agent *identification* (which is
  logged/audited but not enforced against).
- **hubspot_server.py has no HTTP transport.** Stdio-only. Forwarding can't be
  turned back on without either adding HTTP/SSE transport there, or some other
  bridging mechanism — a prerequisite for whatever stage re-enables
  `McpBackendClient`, not something this stage needed to solve.

## Future Migration Path

- Re-enable `policyEngine.evaluate(...)` + `backendClient.forward(...)` in
  `McpProxyHandler.process`, now keyed on the real `agentId` (client) rather
  than a placeholder — this is the existing `docs/SPECS.md` §9.3 backlog item
  ("Per-agent authorization in `McpPolicyEngine`"), now with real
  distinguishable identities to authorize against.
- Add an HTTP (or HTTP+SSE) transport to `hubspot_server.py`, or another
  bridging mechanism, before `McpBackendClient` can forward anywhere real.
- Move the agent client secrets to environment
  injection before any non-local environment.
