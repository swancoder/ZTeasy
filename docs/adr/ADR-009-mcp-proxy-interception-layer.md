# ADR-009 — MCP Proxy & Interception Layer

**Status:** Accepted
**Date:** 2026-07-29

---

## Context

ZTeasy's stated goal is a Zero Trust Data Gateway *for AI agents*. Stages 1–7 built
zero-trust enforcement for plain REST traffic (`gateway-service` proxying to
`service-a`/`service-b` via Spring Cloud Gateway `GlobalFilter`s, DB-backed
role/path policy in `ZteAuthorizationFilter`). None of that touches the actual
target: Model Context Protocol (MCP) traffic — the JSON-RPC tool calls an AI agent
sends to an MCP server, exactly the kind of request this repo's sibling project
(`hubspot-mcp`) serves.

MCP's HTTP+SSE transport has a shape that doesn't fit the existing filter model:
a client opens `GET /sse` and holds that connection open to receive events, then
sends JSON-RPC calls via separate `POST /message` requests. A policy decision
made while handling the POST must be delivered by writing into the *already-open*
SSE connection from the GET — two independent HTTP exchanges, correlated only by
an application-level session id. `GlobalFilter`s and Gateway's `RouteLocator`
operate on one request/response pair at a time; there's no hook for one request
to reach into another's response stream.

## Decision

Add the MCP proxy as new components inside `gateway-service` (package
`com.zte.gateway.mcp`), using plain WebFlux `RouterFunction`s instead of Gateway
routes:

- **`McpSessionManager`** — `sessionId → Sinks.Many<ServerSentEvent<String>>`,
  the bridge that makes cross-request injection possible.
- **`McpRouterConfig`** — routes `GET /sse` and `POST /message`. These paths
  don't overlap with `/api/v1/service-a|b/**`, so Gateway's existing routing is
  untouched; both inherit the same `anyExchange().authenticated()` from
  `SecurityConfig` with no changes there either.
- **`McpProxyHandler`** — on `GET /sse`, generates a session id, registers it,
  and pushes an `endpoint` SSE event with `/message?sessionId=<id>` (the
  standard MCP HTTP+SSE handshake). On `POST /message`, extracts `agent_id` from
  the JWT `sub` claim (same claim `RequestAuditFilter` already uses for
  `X-User-Id` — one identity convention across the gateway), parses the
  JSON-RPC `tools/call` params for tool name + arguments, and calls
  `McpPolicyEngine.evaluate(...)` **synchronously** (a plain method call, not
  `Mono`-wrapped — the check is in-memory logic with no I/O, unlike the
  DB-backed `PolicyService`). The POST itself always returns `202 Accepted`;
  the real answer travels over the SSE stream.
- **Deny path**: `DummyMcpPolicyEngine` returns a decision without ever calling
  `McpBackendClient`. The denial is built as `JsonRpcResponse.denied(...)` — a
  **successful** JSON-RPC envelope with `result.isError = true`, matching how
  MCP's `tools/call` reports tool-level errors. This is deliberate: a denial is
  the policy engine working correctly, not a JSON-RPC protocol failure, so it is
  never a `JsonRpcError`.
- **Allow path**: `McpBackendClient` forwards the call to `mcp-backend.uri`
  (e.g. `hubspot-mcp` or any other fronted MCP server), and the result passes
  through `DataMaskingFilter` (currently `NoOpDataMaskingFilter`, a stub for
  future PII masking) before being emitted to the session.
- **Audit**: `LoggingMcpAuditService` accepts events via a non-blocking
  `tryEmitNext` on a `Sinks.Many`, drained by one subscriber on
  `Schedulers.boundedElastic()`. The proxy thread never blocks; swapping the
  log-based `persist()` for an InfluxDB write is a one-method change.

## Consequences

**Positive:**
- Fits the actual MCP transport shape instead of forcing it through a
  request/response-pair abstraction that can't express it.
- Reuses existing conventions (JWT `sub` for identity, `SecurityConfig`
  unchanged, `Ordered`/`GlobalFilter` style left alone for what it's good at).
- Every extension point requested is a distinct, separately swappable
  component: `McpPolicyEngine`, `McpAuditService`, `DataMaskingFilter`,
  `McpBackendClient`.
- No new Gradle dependencies — WebFlux, Jackson, and `WebClient.Builder` all
  ship transitively via `spring-cloud-starter-gateway`.

**Negative / Risks:**
- `McpBackendClient` assumes the backend answers each `POST /message` with one
  JSON body relayed as a single SSE event; a backend that itself streams
  multiple SSE events per call would need incremental relaying, not buffering
  to one response.
- `McpSessionManager`'s session map is in-memory and per-instance — it does not
  survive a gateway restart or work across multiple gateway replicas. A
  production version would need sticky routing or a shared session store.
- `DummyMcpPolicyEngine` is a fixed in-memory deny-list, not per-agent
  authorization — same demo-grade caveat as `PolicyService`'s DB policies, just
  not yet backed by a table.
- `LoggingMcpAuditService`'s buffer is unbounded and in-memory; a burst of
  audit events with a slow/blocked TSDB writer would grow memory without limit
  until the writer catches up.

## References

- `com.zte.gateway.mcp.*` (gateway-service)
- Compare: ADR-003 (reactive policy engine — DB-backed, for REST traffic) and
  ADR-007 (Policy Auditor Agent — the other place this repo talks to an
  external AI-facing API surface)
