# ADR-007: Policy Auditor Agent — Protected Internal Endpoint and WebClient-Based Anthropic Integration

**Status:** Accepted
**Date:** 2026-05-22
**Deciders:** ZTE-Lightweight Architects

> Note: ADR-006 is reserved for pre-commit documentation automation. This ADR is numbered 007.

---

## Context

Stage 7 introduces `zt-agents`, an AI-native Kotlin Spring Boot module that acts as a security
copilot. The first agent — **Policy Auditor** — fetches the gateway's access policies and sends
them to an LLM (Anthropic Claude) for zero-trust compliance analysis.

Two integration decisions must be made:

1. **How does `zt-agents` access the policy data?**
   - Option A: Direct R2DBC connection to the PostgreSQL database.
   - Option B: A dedicated internal REST endpoint on the gateway.

2. **How does `zt-agents` call the Anthropic LLM?**
   - Option A: Official Anthropic SDK.
   - Option B: WebClient (Spring WebFlux reactive HTTP client).

---

## Decision 1: Protected Internal REST Endpoint (not Direct DB)

**`zt-agents` calls `GET /api/v1/internal/policies` on the gateway instead of connecting to PostgreSQL directly.**

### Rationale

| Concern | Direct DB | Internal Endpoint |
|---|---|---|
| Service boundary | Violated — `zt-agents` knows the DB schema | Maintained — gateway owns its schema |
| Zero Trust | Grants DB credentials to a 3rd service | Gateway is the single authorised policy reader |
| Schema evolution | Any DB change breaks `zt-agents` | Gateway evolves its schema independently |
| Policy cache bypass | Must replicate cache logic | Bypass intentional — audit needs live data |
| Reuse | Cannot reuse gateway's validation logic | Can reuse gateway's validation in future |

### Security of the Internal Endpoint (MVP)

The gateway runs on HTTP (port 8080). Transport-layer mTLS to the gateway itself would require
a second HTTPS listener — out of scope for MVP.

**MVP security model:**
- `GET /api/v1/internal/**` is covered by `InternalSecurityConfig` (`@Order(-100)`), which
  applies `permitAll()` for these paths only. All other paths still require JWT via the default
  `SecurityConfig` from `auth-library`.
- `ZteAuthorizationFilter` passes unauthenticated requests through automatically (its
  `defaultIfEmpty(new SecurityContextImpl())` path skips DB policy enforcement when no
  `JwtAuthenticationToken` is in the security context).
- Network restriction: the gateway's port 8080 is not proxied via `GatewayRouteConfig` for
  internal paths, and Docker Compose does not expose it to the public internet.

**Production upgrade path:**
1. Create a `zt-agents` Keycloak client with `client_credentials` grant.
2. Assign it an `INTERNAL` realm role.
3. Insert a DB policy row: `INTERNAL → GET /api/v1/internal/**`.
4. Remove `InternalSecurityConfig` — the standard JWT + DB policy path handles it.
5. Configure the gateway with an HTTPS server port for mTLS.

---

## Decision 2: WebClient Instead of Anthropic SDK

**`zt-agents` calls the Anthropic Messages API via Spring WebFlux `WebClient`, not a language SDK.**

### Rationale

- **No official Java/Kotlin SDK:** At the time of writing, Anthropic provides official SDKs only
  for Python and TypeScript. The Java community SDK is unofficial and not recommended for production.
- **WebClient is already a project dependency:** `spring-boot-starter-webflux` is on the classpath.
  Adding a third-party SDK would add unnecessary dependencies.
- **Native Reactor integration:** `WebClient` returns `Mono<T>`, composing naturally with the
  reactive pipeline (`GatewayClient.fetchPolicies()` → `AnthropicClient.complete()` → `Mono<AuditReport>`).
  The SDK's blocking / callback API would require wrapping in `Mono.fromCallable()`.
- **Full control:** Retry policy, timeout (`120s`), custom headers (`x-api-key`,
  `anthropic-version`), and error mapping are explicit and auditable in Java/Kotlin code.

### Synchronous-Reactive (Non-Streaming) Response

The audit endpoint returns a complete Markdown report (`Mono<AuditReport>`). Streaming
(Anthropic's `text/event-stream` SSE format) is not implemented in this stage because:
- A complete report is more useful for audit purposes (copy/paste, save to file).
- SSE parsing adds complexity (custom `BodyExtractor` or Reactor `FluxSink` wiring).
- The 120-second WebFlux timeout is acceptable for management/development tooling.
- No server thread is blocked while waiting — WebFlux suspends the reactive chain.

**Deferred:** Streaming via SSE endpoint (`text/event-stream`) for interactive audits.

---

## Chain of Thought

1. **Service boundary is non-negotiable.** Giving `zt-agents` a DB password means it can
   read/write any table. The gateway owns policy data; it must be the only DB writer.

2. **permitAll() for internal paths is a conscious trade-off.** The alternative (JWT for
   `zt-agents`) requires a Keycloak service account, realm export update, and DB migration.
   For an MVP agent tool running in the same Docker network, network isolation is sufficient.
   The ADR documents the upgrade path so it is not forgotten.

3. **WebClient timeout = acceptable user experience.** The audit endpoint is not user-facing
   (it is a management operation). A developer running `/api/v1/agents/auditor/run` expects
   to wait 10-60 seconds. Setting `--max-time 150` in curl is the only documentation needed.

4. **The `ZteAuthorizationFilter` pass-through is not a loophole.** The filter's
   `defaultIfEmpty(new SecurityContextImpl())` is designed exactly for paths that Spring
   Security has already decided to `permitAll()`. No DB policy record is consulted for
   unauthenticated requests — fail-closed applies only to authenticated requests that lack
   a matching policy.

---

## Self-Critique

| Risk | Severity | Mitigation |
|---|---|---|
| Internal endpoint accessible to any host on port 8080 | Medium | Docker network restriction; document production upgrade (Keycloak SA + JWT) |
| ANTHROPIC_API_KEY in environment variable | Medium | Dev only; production: Vault / K8s Secret; never committed |
| No authentication on `POST /api/v1/agents/auditor/run` | Low | zt-agents is an internal management tool; not exposed to end users |
| Kotlin 2.0.21 may conflict with Spring Boot BOM kotlin version | Low | BOM manages stdlib; plugin manages compiler; tested compatible |
| 120s timeout may exceed reverse-proxy or load-balancer default | Low | Document `curl --max-time 150`; add streaming in a later stage |

---

## Consequences

- **Positive:** Policy audit is fully reactive — no threads blocked during LLM wait.
- **Positive:** `InternalPolicyController` gives a stable, versioned API for any future agent.
- **Positive:** `AnthropicClient` is a thin, testable wrapper — easy to swap for SDK later.
- **Negative:** Internal endpoint has no auth for MVP — documented technical debt.
- **Negative:** Kotlin module in a Java-dominant build requires Kotlin plugin in the version catalog.
