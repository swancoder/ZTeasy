# ADR-011: YAML-Defined Access Policies (users2service / service2service / agent@mcp)

**Status:** Accepted
**Date:** 2026-08-04
**Deciders:** ZTE-Lightweight Architects

---

## Context

GitHub issue #1 asks for a single mechanism to define allow/deny access policies
across three relationship categories — **users2service**, **service2service**, and
**agent@mcp/tool-calls** — loadable from a YAML file, applicable to both the API
gateway proxy and the MCP proxy.

The issue's AI-generated technical specification is internally inconsistent: its
"YAML schema" and "YAML policy file loader" tasks describe a generic, versioned
YAML document and loader, but its three enforcement tasks (users2service,
service2service, agent@mcp/tool-call) each wire the decision to a *new,
parallel DB table* (`access_policies.effect`, a new `service_access_policies`
table, a new `mcp_tool_policies` table) — none of which actually consult the
loaded YAML document at request time. Building all three literally as specified
would produce a YAML loader nothing reads from at runtime, which defeats the
original human-written ask ("we need to have a possibility to load YAML file
with definition of who can connect where").

This ADR resolves that inconsistency in favor of the original ask.

---

## Decision

**One YAML file is the runtime source of truth for all three policy
categories**, loaded once at startup into an immutable, versioned
`PolicyDocument` and evaluated in-memory on every request.

- `gateway-service/src/main/java/com/zte/gateway/policy/def` — the new package:
  - `PolicyDocument` (schemaVersion, `users2service[]`, `service2service[]`, `agentMcpToolCalls[]`)
  - `PolicyRule` — **one shared rule shape** for all three categories (`id`, `effect`,
    `source`, `target`, `pathPattern`, `methods`, `priority`) rather than three
    parallel rule subclasses. Java records don't support inheritance, and the
    shape genuinely is identical across categories — `pathPattern`/`methods` are
    simply unused (`null`) by `agentMcpToolCalls` rules, whose `target` is a tool
    name rather than a service.
  - `YamlPolicyFileLoader` — parses a Spring `Resource` (classpath or filesystem)
    via Jackson's `YAMLMapper`, rejecting unknown top-level keys.
  - `PolicyValidator` — collects every violation in one pass: missing required
    fields, unknown schema version, duplicate rule ids (errors, load-blocking),
    and same-tuple ALLOW/DENY conflicts (warnings — non-blocking, since
    deny-overrides-allow resolves them deterministically).
  - `PolicyDefinitionStore` — loads+validates at construction (throws
    `PolicyLoadException`, failing Spring's `ApplicationContext` refresh on
    invalid content — fail-fast); holds the active document behind an
    `AtomicReference`, mirroring `auth-library`'s `ReloadableSslContextFactory`
    hot-swap pattern (ADR-004). `current()` is a zero-I/O synchronous read.
  - `PolicyMatcher` — pure, in-memory rule matching (`AntPathMatcher`, the same
    matcher `PolicyService` already uses): among all rules matching a request
    tuple, an explicit DENY always wins over an explicit ALLOW regardless of
    priority or declaration order; no match yields `NO_MATCH`, left for the
    caller to resolve.
- **users2service** (`ZteAuthorizationFilter`): YAML is consulted first. An
  explicit YAML ALLOW/DENY short-circuits the decision. `NO_MATCH` falls back,
  **unchanged**, to the existing DB-backed `PolicyService` (ADR-003) — this
  keeps every pre-existing DB-driven flow and test working exactly as before;
  YAML is additive. A JWT with no `realm_access.roles` and an `azp` other than
  the interactive user client (`zte.policy.user-client-id`, default
  `zte-gateway`) is now recognized as service2service traffic and passed
  through untouched, instead of being blanket-denied by the (empty) role check.
- **service2service** (new `ServiceToServiceAuthorizationFilter`, order
  `HIGHEST_PRECEDENCE + 150` — after `ZteAuthorizationFilter`'s `+100`, before
  `UserContextPropagationFilter`'s `+200`): governs exactly the traffic
  `ZteAuthorizationFilter` now passes through. YAML is the *sole* source of
  truth here (there is no prior DB-backed service2service policy to preserve
  compatibility with) — `NO_MATCH` resolves to `zte.policy.default-effect`
  (default `DENY`).
- **agent@mcp/tool-call** (`YamlMcpPolicyEngine`, replacing the
  `DummyMcpPolicyEngine` placeholder): same YAML-only, default-deny model.
  `McpProxyHandler.process()` is restored to its pre-Stage-9 shape (evaluate →
  deny via SSE, or forward to `McpBackendClient` + mask + emit), superseding
  Stage 9/ADR-010's deliberate dead-end stub — this *is* the Stage 10+ backlog
  item "Re-enable McpPolicyEngine/McpBackendClient", now driven by real
  per-agent YAML grants instead of a fixed deny-list. `evaluate()` stays
  synchronous/zero-I/O per ADR-009 §8.2 — it reads `PolicyDefinitionStore`'s
  `AtomicReference` snapshot, never I/O inline.
- **Runtime reload without downtime**: `POST /api/v1/internal/policies/reload`
  (`PolicyReloadController`, reusing the existing permitAll internal chain —
  see `InternalSecurityConfig`) re-reads and re-validates the file off
  `Schedulers.boundedElastic()`, then atomically swaps the reference. Reload
  triggers are serialized with a `synchronized` compute-then-swap. A validation
  failure keeps the previous document active and reports the errors — never a
  partial application. **Chosen instead of a filesystem watch service**: an
  explicit admin endpoint meets the "reload without restart, in-flight requests
  unaffected" requirement with far less moving-parts risk (no background
  watch thread, no debounce/coalescing logic) for MVP scale.
- **Unified audit logging**: one new `ZteAuditLogger.policyDecision(category,
  subject, target, matchedRuleId, outcome)` method, called identically from
  `ZteAuthorizationFilter`, `ServiceToServiceAuthorizationFilter`, and
  `McpProxyHandler`/`YamlMcpPolicyEngine` — the two proxies emit the same log
  shape *by construction*, not by convention. Plain synchronous SLF4J via
  `ZteAuditLogger`, matching every other audit call site in this codebase — no
  new `Sinks.Many`/bounded-buffer machinery for MVP scale.

---

## Alternatives Considered

### Option A: Build exactly what the AI spec's per-task class lists describe (rejected)
New Flyway-migrated tables (`access_policies.effect`, `service_access_policies`,
`mcp_tool_policies`), each with its own R2DBC repository and `Mono.cache`
policy service, mirroring ADR-003 three times over.

- **Pros:** Consistent with ADR-003's existing DB-backed pattern; the YAML
  loader could still exist as a separate import/seed tool.
- **Cons:** The YAML loader and validator (issue Tasks 1–2) would have no
  runtime consumer — contradicts the literal, human-written feature request.
  Three near-identical DB-table-plus-cache pipelines is three times ADR-003's
  known risks (5-minute stale window, DB dependency for a decision that could
  be pure in-memory) for no compatibility benefit, since two of the three
  categories (service2service, MCP) have no existing DB table to be compatible
  with in the first place.
- **Verdict:** Rejected — it does not build what was asked for.

### Option B: YAML replaces the DB entirely for users2service too (rejected)
Drop `PolicyService`/`access_policies` and make YAML the only users2service
source.

- **Pros:** One less code path; single source of truth story is cleaner.
- **Cons:** Breaks ADR-003's established, tested DB-backed flow and every test
  built on it (`ZteAuthorizationFilterTest`, `HappyPathIT`, `ZeroTrustBreachIT`)
  for no requirement in the issue that demands it — users2service already had a
  working enforcement mechanism; only service2service and MCP were genuinely
  new.
- **Verdict:** Rejected for this iteration. `PolicyService` remains the
  users2service fallback; full migration to YAML-only (if ever wanted) is a
  separate, deliberate future step — see Future Migration Path.

### Option C: YAML as the additive front layer, DB as fallback (Selected)
Described in Decision above.

- **Pros:** Zero behavior change for existing DB-driven users2service flows
  (default shipped YAML has an empty `users2service` list); adds the new
  capability without a risky big-bang migration; service2service and MCP get a
  real, coherent single source of truth since neither had a prior DB table to
  preserve.
- **Cons:** Two sources of truth for users2service is genuinely more to reason
  about — mitigated by YAML only ever *short-circuiting* on an explicit match
  (silence = old behavior) and every decision being audit-logged with its
  source category.
- **Verdict:** Accepted.

### Option D: Filesystem watch service for reload (rejected for now)
A background `WatchService` polling the policy file, auto-reloading on change.

- **Pros:** No explicit trigger needed; closer to some GitOps-style workflows.
- **Cons:** Needs debounce/coalescing (a file save can fire multiple events),
  a dedicated thread, and error-handling for watch-service failures — real
  complexity for a capability the explicit admin endpoint already delivers
  (reload without restart, in-flight requests unaffected).
- **Verdict:** Rejected for MVP; noted as a future migration path.

---

## Chain of Thought (CoT)

1. **The spec's per-task class lists optimize locally, not globally.** Each
   enforcement task's spec is internally coherent (DB table + R2DBC repo +
   cache, mirroring ADR-003) but three of them together contradict the
   loader/schema tasks and the original issue text. Reconciling in favor of the
   literal, human-authored ask (not the AI-elaborated spec) is the right call
   when the two disagree.
2. **A single `PolicyRule` shape, not three subclasses.** Records can't extend
   records. A sealed interface + three record implementations would work but
   adds real ceremony (three types, three deserialization configs, three
   matchers) for fields that are 90% identical. One record with two
   category-specific fields left `null` where unused is simpler and the
   simplification is called out explicitly (in this ADR and in
   `PolicyRule`'s Javadoc) so it reads as a decision, not an oversight.
3. **Deny-overrides-allow, computed once, in one place.** `PolicyMatcher` is
   the only place precedence is decided — every one of the three enforcement
   points calls the same method, so a future precedence-rule change (e.g.
   priority tie-break logic) only needs to change in one file.
4. **`PolicyDefinitionStore.current()` must stay synchronous.** ADR-009 §8.2
   already mandates `McpPolicyEngine.evaluate()` be zero-I/O — reusing the same
   `AtomicReference` snapshot pattern `ReloadableSslContextFactory` already
   uses for `SslContext` (ADR-004) satisfies that without inventing a new
   idiom.
5. **Re-enabling MCP enforcement is explicitly in scope**, not an unplanned
   side effect — issue #1's "Enforce agent@mcp/tool-call" task requires exactly
   what Stage 10+'s backlog already named: "Re-enable McpPolicyEngine/
   McpBackendClient in McpProxyHandler.process, keyed on the real per-agent
   clientId." This ADR is that backlog item's implementation, not a scope
   overreach.
6. **An admin endpoint, not a file watcher, for reload.** The acceptance
   criteria ask for "reload without restart" and "in-flight requests complete
   on the pre-reload policy set" — both are satisfied by the atomic-swap store
   regardless of what triggers `reload()`. A watcher only changes *what*
   triggers it, at the cost of a background thread and debounce logic this
   MVP doesn't need yet.

---

## Self-Critique

| Risk | Severity | Mitigation |
|---|---|---|
| Two sources of truth for users2service (YAML + DB) | Medium | YAML only short-circuits on an *explicit* match; the shipped default YAML ships with an empty `users2service` list, so today's behavior is unchanged until an operator deliberately adds YAML rules. Every decision is audit-logged with `category=users2service` and which source resolved it (implicitly, via whether a `matchedRule` is present). |
| Re-enabling MCP enforcement breaks Stage 9/10's "dead-end stub" test assertions | Medium | `McpProxyIT` and `McpProxySecurityWebFluxTest` are rewritten in this same change to assert the new allow/deny reality (not left red) — see their updated Javadoc for what changed and why. |
| `PolicyRule` reuse across categories leaves `pathPattern`/`methods` always-null on MCP rules | Low | Documented in `PolicyRule`'s Javadoc and this ADR; the alternative (three subclasses) was assessed and rejected above, not simply not considered. |
| No per-category default effect (`zte.policy.default-effect` applies to all three) | Low | YAGNI until a concrete need for e.g. allow-by-default users2service + deny-by-default MCP appears in the same deployment — noted below as a future migration path. |
| Runtime reload via admin endpoint, not automatic file-watch | Low | Meets the stated acceptance criteria (no restart, in-flight requests unaffected); an operator/CI job must explicitly call the endpoint after editing the file — acceptable given `InternalPolicyController` already establishes this "internal endpoint, network-perimeter-protected" pattern for adjacent concerns. |
| `PolicyMatcher` is a full linear scan per category per request | Low (MVP) | Same `<100 rules` MVP scale ceiling ADR-003 already documents for `access_policies`; O(n) is negligible at that scale. |

---

## Consequences

- **Positive:** Service2service and MCP tool-call access are now governed by an
  explicit, versioned, validated policy document instead of (respectively)
  nothing and a fixed deny-list — closing the two gaps issue #1 was filed to
  close.
- **Positive:** users2service gains YAML-expressible deny rules (e.g. an
  emergency lockout) without touching the DB, while every existing DB-driven
  flow is provably unchanged (same tests, same assertions, still green).
- **Positive:** One audit-log shape across both proxies, enforced by sharing
  the logging call site rather than by two teams independently agreeing on a
  format.
- **Negative:** `zte-policies.yaml` is a second policy artifact alongside the
  `access_policies` DB table — operators must know both exist and how they
  interact (documented in `docs/policy-schema.md` and this ADR).
- **Negative:** No filesystem auto-reload — a config change requires either a
  gateway restart or an explicit `POST /api/v1/internal/policies/reload` call.

---

## Future Migration Path

- **Full users2service migration to YAML-only**, retiring `access_policies`
  and `PolicyService`, once YAML coverage is proven out in production — a
  deliberate follow-up ADR, not a silent default.
- **Per-category default effect** (`zte.policy.users2service.default-effect`,
  `...service2service...`, `...mcp...`) if a real deployment needs them to
  differ.
- **Filesystem watch-based auto-reload**, layered on top of the existing
  `PolicyDefinitionStore.reload()` — the atomic-swap mechanism this ADR ships
  is already the hard part; a watcher is purely a new trigger.
- **ABAC extension**: a `condition` column/field (SpEL evaluated against JWT
  claims) on `PolicyRule`, mirroring ADR-003's own noted future path for
  `access_policies`.
