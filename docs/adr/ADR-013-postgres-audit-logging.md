# ADR-013: R2DBC-Backed Request Audit Logging with Distributed Tracing

**Status:** Accepted
**Date:** 2026-08-10
**Deciders:** ZTE-Lightweight Architects

---

## Context

Every gateway policy decision was, until now, logged only via synchronous
SLF4J (`ZteAuditLogger`) — grep/tail-grade, no queryable history, no way to
correlate a single request across service-a/service-b/downstream calls. This
adds a persistent, async, non-blocking audit trail backed by the existing
Postgres instance (via R2DBC — removed in ADR-012 once its only prior use,
`PolicyService`, was deleted; restored here for a different purpose), a
mandatory `X-Request-Id` distributed-tracing header, a new ADMIN-gated read
API, and an "Audit Trail" tab in the React Admin Console (ADR-012).

---

## Decision

### Schema

One new Flyway migration, `V4__create_request_logs_table.sql`, both creates
`request_logs` and drops `gateway_audit_log` (V1, Stage 1) — see "Consolidating
`gateway_audit_log`" below. `id` is `UUID DEFAULT gen_random_uuid()` (built
into Postgres core since v13, no extension needed); `trace_id`/`timestamp`
are indexed for the read path's "latest N" query.

### Write path

`RequestAuditFilter` is rewritten from a Spring Cloud Gateway `GlobalFilter`
to a plain `WebFilter` — see "Finding: GlobalFilter doesn't see what this
task needs" below, this is the load-bearing decision the rest of the write
path depends on. It:

1. Resolves `trace_id` from the caller's `X-Request-Id` header, or mints a
   new UUID if absent, and unconditionally sets it on the mutated request so
   it's forwarded to service-a/service-b.
2. Resolves `client_ip` from `X-Forwarded-For` (first hop) or the raw
   connection address.
3. Keeps the pre-existing `X-User-Id` trust boundary (strip client-supplied
   value, inject the JWT `sub` when present) — now applied unconditionally
   rather than only on the JWT branch, closing a minor gap the old code had
   (see Self-Critique).
4. Wraps `chain.filter(mutated)` in `.doFinally(...)`, which fires
   regardless of the eventual outcome (200 routed through, or 403/401 from
   any policy filter) and reads the now-final `exchange.getResponse().getStatusCode()`.
   `doFinally` — not `switchIfEmpty` — is what makes this correct; see
   "Finding: the switchIfEmpty pitfall, again" below.
5. **Only for paths not matching `zte.audit.excluded-path-prefixes`** — fires
   `RequestLogAuditService.record(...)` from that `doFinally` callback — a
   single non-blocking `Sinks.Many.tryEmitNext`, **directly mirroring
   `LoggingMcpAuditService`'s architecture** (`gateway-service/.../mcp/audit/LoggingMcpAuditService.java`):
   one dedicated subscriber on `Schedulers.boundedElastic()` drains the sink
   and does the actual `repository.save(...)`. A DB write failure is caught
   via `onErrorResume` and degrades to an SLF4J warning line instead of
   being lost or propagating — satisfies "keep SLF4J as a fallback"
   literally. Also logs a synchronous `ZteAuditLogger.requestLog(...)` line
   alongside the async write, keeping ADR-011's "log both sync-SLF4J and
   async-structured" precedent intact for this new call site too.

   **Amendment (same day, before the first commit's data was even a full
   session old):** the first version of this filter wrote a row for *every*
   request, no exclusions. Running against the live system for a few hours
   showed the actual composition: of 34 rows, 30 were the Admin Console
   observing its own existence (`/api/v1/admin/policies`,
   `/api/v1/admin/audit-logs`, `/admin/**` static assets) or
   `/actuator/health` probes — only 4 were real zero-trust enforcement
   points. `AuditExclusionProperties` (`@ConfigurationProperties(prefix =
   "zte.audit")`, mirroring `PolicyDefaultsProperties`'s exact shape) adds an
   **exclude-list** of path prefixes, configured in `application.yml` —
   deliberately *not* `zte-policies.yaml` (that document is a structured,
   hot-reloadable rule set with its own schema/validation, ADR-011; this is
   a flat list that doesn't need any of that) and *not* hardcoded in Java
   (operator-editable without a rebuild, same externalization
   `zte.policy.*`/`zte.mtls.*` already get). Shipped default excludes
   `/admin/`, `/api/v1/admin/`, `/api/v1/internal/`, `/actuator/`. Gates only
   the audit *output* (the DB write and the sync `requestLog` line) — trace
   ID resolution/forwarding and `X-User-Id` stripping stay universal on every
   request regardless of exclusion, since those are tracing/security
   concerns, not audit-scope ones.

### Read path

New `AdminAuditLogController` (`GET /api/v1/admin/audit-logs`), same `admin`
package as `AdminPolicyController`. No new security wiring — `AdminAuthorizationFilter`'s
existing `u2s-admin-console-api` YAML rule and its own path check
(`path.startsWith("/api/v1/admin/")`) already cover any sub-path. Returns
`repository.findTop100ByOrderByTimestampDesc()` — the 100-row cap the task's
Self-Criticism asked for is enforced as a SQL `LIMIT`, not by fetching
everything and slicing in application code.

### UI

New "Audit Trail" tab (`zt-admin-ui/src/AuditTrail.tsx`), added via MUI
`Tabs` in `App.tsx` alongside the existing "Policies" tab. Renders with the
same plain MUI `Table` pattern `PolicyDashboard`'s `RuleTable` already uses
— see "Rejected: `@mui/x-data-grid`" below.

---

## Findings that reshaped the literal task list

### Finding 1: `RequestAuditFilter` had the exact `Mono<Void>`+`switchIfEmpty` pitfall from ADR-012, live in production

Verified empirically before touching anything: made one curl call through
the running gateway, then checked `docker logs zte-service-a` — the backend
was hit exactly once, not twice, confirming the double-subscription
(`.flatMap(...).switchIfEmpty(chain.filter(exchange))`, where the flatMap's
result is itself a `Mono<Void>` that can never satisfy `switchIfEmpty`'s
"had a value" check, so it always re-fires) was real but happened to be
harmless — only because Spring Cloud Gateway's `NettyRoutingFilter` guards
against re-routing an already-routed exchange. Since this task requires
rewriting this exact method anyway, the fix (`doFinally` instead of
`switchIfEmpty`) is folded into the rewrite rather than treated as a
separate patch.

### Finding 2: `GlobalFilter` doesn't see what this task needs

`RequestAuditFilter` was a `GlobalFilter` — per ADR-012's own discovery,
those only run for requests `RoutePredicateHandlerMapping` matches to a
`GatewayRouteConfig` route. That means the old filter never ran for
`/api/v1/admin/**`/`/api/v1/internal/**` (no route), and never ran for a
request denied *before* reaching it (`ZteAuthorizationFilter` et al.
short-circuit without calling `chain.filter()`). But the task's own
verification demands rows for **both** denied and allowed requests.
Converting it to a plain `WebFilter` (ADR-012's established fix for this
exact class of problem) resolves both at once: a `WebFilter` wraps the
*entire* downstream decision — for a Gateway-routed path that includes the
whole `GlobalFilter` chain internally — so `doFinally` observes the final
status code regardless of which inner layer set it. Ordered at
`Ordered.LOWEST_PRECEDENCE - 100`: after Spring Security's authentication
(so the reactive security context is populated), before
`AdminAuthorizationFilter`'s implicit lowest-precedence default (so this
filter's `chain.filter()` call is what actually invokes it, and its
decision is observable in `doFinally`).

**Known, named scope boundary — not silently accepted**: a request with no
token at all is still not captured, since Spring Security's own
`AuthorizationWebFilter` rejects it before reaching any filter positioned
after it in the WebFilter chain. Every *existing* "denied" test scenario in
this codebase (`ZeroTrustBreachIT`'s "USER role — no access policy → 403",
every `AdminAuthorizationFilterTest` deny case) uses a present-but-
insufficient-role JWT, not a missing token — so this doesn't conflict with
the task's literal verification, but it's a real gap, listed in
Self-Critique rather than glossed over.

### Consolidating `gateway_audit_log`

`V1__init_schema.sql` (Stage 1) already created a `gateway_audit_log` table,
described as "immutable audit trail of authenticated requests through the
ZTE gateway" — and never referenced by any code since (`grep -rl
gateway_audit_log --include=*.java` → zero hits). Leaving it orphaned
alongside a new, actually-wired `request_logs` table would be exactly the
kind of tech-debt accumulation ADR-012 rejected for `access_policies`.
Same call, same rationale: `V4` drops it in the same migration that creates
its real replacement.

---

## Alternatives Considered

### A dedicated TSDB (InfluxDB) instead of Postgres (rejected)

`LoggingMcpAuditService`'s own Javadoc already earmarks it for a future
InfluxDB line-protocol writer, and time-series data is arguably a better
semantic fit for request logs than a relational table.

- **Pros:** Purpose-built for high-volume time-series writes/queries;
  matches the MCP audit path's stated future direction.
- **Cons:** A second piece of infrastructure to run, operate, and back up
  for an MVP explicitly framed (by this task's own Chain of Thought) as
  needing to "stay strictly Lightweight" by reusing the Postgres instance
  already there. No current requirement demands TSDB-grade write volume or
  query patterns (downsampling, retention policies) that Postgres can't
  serve at this scale.
- **Verdict:** Rejected for this task. `LoggingMcpAuditService`'s own
  backlog item (a real TSDB writer for MCP audit) is untouched and remains
  a legitimate future direction for *that* path — this ADR doesn't compete
  with it, see Future Migration Path.

### `@mui/x-data-grid` for the UI table (rejected)

The task's Task 4 literally says "DataGrid."

- **Pros:** Built-in sorting/pagination/filtering for free; matches the
  literal word used in the task.
- **Cons:** A new, fairly heavy dependency on top of an MUI v9 + React 19
  combination that already hit one peer-compatibility typing quirk in the
  previous session (a `Stack` component overload resolution issue). No
  proven-safe precedent for `@mui/x-data-grid` on this exact stack yet.
  `PolicyDashboard`'s existing plain MUI `Table` (`RuleTable`) already
  solves the identical "render N rows of structured data" problem in this
  same codebase.
- **Verdict:** Rejected. Read literally the task asks for a data *grid*
  (rows and columns), which a plain `Table` satisfies functionally, and the
  user's own stated "Lightweight" framing for this task favors the
  lower-risk, already-proven-in-this-repo choice.

---

## Self-Critique

| Risk | Severity | Mitigation |
|---|---|---|
| A request with no `Authorization` header at all (true 401) never reaches `RequestAuditFilter`, so it's never logged to `request_logs` | Medium | Named explicitly in Finding 2 above rather than silently accepted. Every existing denial test in this codebase uses a present-but-wrong-role JWT (403), so the task's literal verification isn't affected — but a genuinely unauthenticated attacker's request leaves no DB trail, only the (still-present) `[ZTE-AUDIT]` line Spring Security itself may emit. Backlog item below. |
| `agentId`/`toolName` are always `null` from this integration point — the given schema has no subject/user-id column at all, only `agent_id` (MCP-focused) | Medium | The Admin Console's "Agent/User ID" column will show blank for all of today's REST traffic. A real, named tension between the task's literal schema (Task 1) and its literal UI column ask (Task 4), resolved in favor of the schema (harder to safely change later than a UI column) rather than silently picking one or guessing at a workaround. |
| `RequestLogAuditService`'s `Sinks.Many` buffer is unbounded, no delivery guarantee across restarts | Low | The exact same known, already-documented limitation `LoggingMcpAuditService` has (ADR-009/ADR-011) — not fixed here either, deliberately consistent rather than fixing it in one sink and not the other. |
| `X-User-Id` stripping is now unconditional (every request, not just JWT-authenticated ones) — a behavior change from the pre-ADR-013 filter | Low | This is a strengthening, not a regression: the class's own Javadoc states "downstream services should NEVER trust client-supplied identity headers," and unconditional stripping matches that principle more closely than the old JWT-gated stripping did. Verified via `RequestAuditFilterTest.noJwt_spoofedXUserIdHeader_isStillStripped` and the full existing IT suite (`ZeroTrustBreachIT`'s spoofing scenarios) staying green. |
| `client_ip` trusts `X-Forwarded-For` at face value with no validation that the immediate hop is actually a trusted proxy | Low | Same posture as any reverse-proxy-unaware app; acceptable for this MVP's single-hop Docker-network deployment. A production deployment behind a real load balancer would need to validate/strip this header at the network edge before it reaches the gateway — noted, not solved here. |

---

## Consequences

- **Positive:** Every zero-trust-relevant gateway request (a proxied REST
  call or MCP tool call, allowed or denied by policy) now has a queryable,
  persistent audit row correlated by `trace_id` — closes the "no queryable
  history" gap this ADR opened with, without also drowning it in the Admin
  Console's own housekeeping traffic (see the write-path Amendment above).
- **Positive:** `X-Request-Id` is a first-class, gateway-guaranteed
  distributed-tracing primitive on *every* request (not just audited ones)
  — any downstream service can rely on it being present, whether or not the
  original caller sent one.
- **Positive:** The `GlobalFilter`-vs-`WebFilter` lesson from ADR-012 is now
  applied a second time, in a different filter, for a different reason
  (visibility into denied requests, not just non-routed local controllers)
  — reinforcing it's a general pattern in this codebase, not a one-off fix.
- **Negative:** R2DBC is back on the classpath after ADR-012 removed it —
  the "one less code path" simplification that ADR was partly about is
  undone, for a genuinely different reason (write-path persistence, not
  policy lookup). Documented, not silently reversed.
- **Negative:** `gateway_audit_log`'s Flyway history (`V1`) now describes a
  table that no longer exists as of `V4` — same pattern as `access_policies`/`V2`/`V3`
  from ADR-012, an accepted cost of not leaving dead schema artifacts around.
- **Negative:** True-401 requests and MCP-agent identity are both currently
  invisible to `request_logs` — two named, deliberate scope boundaries, not
  silent gaps (see Self-Critique and Future Migration Path).

---

## Future Migration Path

- **MCP audit unification**: `LoggingMcpAuditService` could write into this
  same `request_logs` table (populating the currently-always-null
  `agent_id`/`tool_name` columns) instead of (or in addition to) its own
  log-only `persist()` — would also finally give the Admin Console's
  "Agent/User ID" column real data for MCP traffic.
- **True-401 coverage**: a dedicated, very-early `WebFilter` (before Spring
  Security's own chain) could capture the no-token case, at the cost of not
  having subject/role information for that log line (since Security hasn't
  run yet) — a real design tradeoff, not attempted here.
- **A `subject` column**, if a genuine need for REST-caller identity in
  `request_logs` (not just `agent_id`) emerges — the given schema
  deliberately doesn't have one today (see Self-Critique).
- **Real TSDB writer** for `LoggingMcpAuditService`, unrelated to this ADR's
  choice of Postgres for REST audit — still a legitimate, separate future
  direction for the MCP path specifically (see "Alternatives Considered").
- **Bounded buffer + overflow policy** for `RequestLogAuditService`'s
  `Sinks.Many` — same backlog item `LoggingMcpAuditService` already has.
