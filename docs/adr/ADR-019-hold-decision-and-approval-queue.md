# ADR-019: HOLD as a Third MCP Decision Outcome, and a Durable Approval Queue

## Status
Accepted

## Context

`examples-from-vlad/` (a colleague's ACAP/DIGI-KAI demo materials — an
AI-agent governance product built conceptually on top of a "Gate" that maps
closely to this repo's gateway + MCP proxy + `YamlMcpPolicyEngine`) lists,
in the demo owner's own priority order, what's missing to make a live CRM-agent
governance demo work. Priority #1: a third decision outcome — **HOLD** — that
routes a tool call to a human for approval instead of forwarding or denying
it outright. Today `PolicyDecision` is strictly boolean (`allowed`/`reason`)
and `McpProxyHandler.process()` has exactly two branches. This ADR adds the
third.

This is Stage 1 of a six-stage plan (see the approved plan file) toward the
demo's full "three lights" model (🟢 allow / 🟡 hold / 🔴 deny); later stages
add argument/field-level policy evaluation (territory, data minimization —
the demo's other major ask) and a governance dashboard. Neither is touched
here.

## Decision

### HOLD is a separate `agentMcpToolHolds` rule list, not a third `RuleEffect`

The obvious-looking design — add `HOLD` to `RuleEffect` alongside
`ALLOW`/`DENY`, since `PolicyRule`/`PolicyMatcher`/`PolicyEvaluation` are
already shared across `users2service`/`service2service`/`agentMcpToolCalls`
— was rejected after checking, not assumed either way: `PolicyEvaluation.Outcome`
is consumed by three **exhaustive** `switch` expressions outside MCP code
entirely — `ZteAuthorizationFilter`, `ServiceToServiceAuthorizationFilter`,
`AdminAuthorizationFilter` — none of which have any meaningful notion of
"hold a REST call for a human." Adding `HOLD` there would force every one of
those switches to grow a case that can never legitimately fire, purely to
satisfy the compiler — exactly the kind of blast-radius a change should not
have.

Instead: `PolicyDocument` gained a fourth list, `agentMcpToolHolds`, reusing
`PolicyRule`'s shape (`id`/`source`/`target`/`priority`) for YAML-authoring
convenience only — `effect` is unused (kept `ALLOW` by convention for
readability) since matching is via a new `PolicyMatcher.matchAny(rules,
sources, target)` (highest-priority source/target match, any effect), not
`evaluate()`'s ALLOW/DENY precedence. `PolicyValidator` validates it like any
other category (required fields, duplicate ids across the *whole* document);
`OrphanedRuleChecker` checks its sources too. `PolicyMatcher`/`RuleEffect`/
`PolicyEvaluation` themselves are completely unchanged — zero risk to REST
traffic authorization.

`YamlMcpPolicyEngine.evaluate()`: the existing coarse `agentMcpToolCalls`
check runs first, exactly as before. A `DENY` there is final. An `ALLOW` (or
a `NO_MATCH` that resolves to allow via `zte.policy.default-effect`) is then
checked against `agentMcpToolHolds` via `matchAny` — a match downgrades the
decision to `HOLD`; no match leaves it `ALLOW`. A hold rule can never
override a `DENY` — it only ever tightens an otherwise-allowed call, never
loosens one.

`PolicyDecision` becomes `record PolicyDecision(Outcome outcome, String
reason)` with `Outcome { ALLOW, DENY, HOLD }` and a `hold(reason)` factory.
`allowed()` is kept as a derived convenience (`outcome == ALLOW`) so the ~12
pre-existing tests asserting on it didn't need touching — new code
(`McpProxyHandler`) switches on `outcome()` directly.

### `McpProxyHandler` gets a third branch, and a shared forward helper

`process()` is now a three-way `switch` on `decision.outcome()`. The
`ALLOW` branch's "forward to backend, mask the response" logic was extracted
into a new `McpForwardService` (`backendClient.forward(rpc).map(dataMaskingFilter::mask)`)
so the new approve-after-hold path (below) can reuse the exact same masking
guarantee rather than risking it being reimplemented slightly differently in
two places.

The `HOLD` branch calls `PendingApprovalService.hold(...)`, audits a `HELD`
event, and emits a new `JsonRpcResponse.held(id, approvalId, reason)` — a
non-error envelope (`status: "held"`, `approvalId`) distinct from
`denied()`'s `isError: true`, since a hold is not a policy failure and a
naive `isError`-checking client shouldn't treat it as one.

### `pending_approvals` — durable, not in-memory

A held call may be reviewed well after it was raised (the demo's own "held
items reviewed daily" framing) — possibly after the originating `GET /sse`
session (`McpSessionManager`, in-memory, per-instance) has already closed.
So `PendingApproval` is a new R2DBC-backed table (`V13__create_pending_approvals.sql`),
not an in-memory queue: `id`, `sessionId`, `agentId`, `toolName`, the
original call's `id`/`arguments` (compact-JSON, so the exact call can be
reconstructed and forwarded unchanged), `status` (`PENDING`/`APPROVED`/`REJECTED`),
and the usual audit-context fields (`traceId`/`clientIp`/`userAgent`/`displayIdentity`).

`PendingApprovalService.approve`/`reject` (behind
`POST /api/v1/admin/approvals/{id}/approve|reject`, `AdminApprovalsController`
— same `u2s-admin-console-api`/`AdminAuthorizationFilter` gate as every other
admin endpoint) reconstruct the original `JsonRpcRequest`, forward it via
`McpForwardService` on approve (or synthesize an honest denial on reject),
audit the decision (`APPROVED`/`REJECTED` — `LoggingMcpAuditService` maps
these to `decisionEffect` `ALLOW`/`DENY` respectively, since a rejection
*is* a deny, just a human's rather than the policy engine's), and — only if
`McpSessionManager.exists(sessionId)` still holds — push the result into
that session. If the session has closed, the decision still executes and is
fully audited; only the "push it back to that live connection" part is
skipped (logged, not an error).

### Admin Console

New "Approvals" tab (`Approvals.tsx`, wired into `App.tsx`'s existing
`View`-union + `Tab` + conditional-render pattern) — a table of pending
calls with Approve/Reject buttons (reject behind `ConfirmDialog`, matching
Registry's delete-confirmation convention). `PolicyDashboard.tsx`'s
data-driven `CATEGORIES` array gained one entry for `agentMcpToolHolds`, so
the active hold rules are visible in the existing Policies tab for free.

### New demo agent identity

`crm-account-health-emea-01` (the ACAP example's own agent id) is a new
Keycloak client-credentials client (`keycloak/realm-export.json`), kept
fully separate from `agent-a`/`agent-b`'s existing grants — `zte-policies.yaml`
gives it read grants (`read_contacts`/`read_deals`/`read_activities`) plus
`send_email`/`draft_followup` (granted in `agentMcpToolCalls`, then held in
`agentMcpToolHolds` — both rules are needed: a hold rule alone doesn't grant
anything, it only tightens an existing grant). These tool names don't exist
in `hubspot-mcp` yet — that's Stage 5, tracked separately; this stage's own
IT coverage stubs the backend via WireMock like every other MCP proxy test.

## Self-Criticism

- **Session-closed-by-decision-time is the common case for a slow reviewer,
  not an edge case** — for a demo where hold-and-approve happens within one
  live session it's invisible, but a real "reviewed daily" workflow will hit
  it on every single approval. The result still gets audited and (for
  approve) actually executed, which is the important half — but the agent
  itself never learns the outcome under the current MCP session model. A
  real fix needs either a re-connectable session or a poll/webhook the agent
  itself supports; out of scope here.
- **`route_to` is stored but unused.** ACAP's `hold[].route_to` (e.g. "deal
  owner") isn't wired to any real routing/notification — every hold lands in
  one flat Admin Console queue regardless of who should see it. Fine for a
  single-operator demo; a real deployment needs it.
- **`decidedBy` trusts the caller's JWT `preferred_username`/`sub` at face
  value** — there's no additional check that the approving admin is the
  *specific* human named in the ACAP profile's `hold[].route_to`/`approves`
  field (which doesn't exist as an enforced concept yet — see above).
- **No rate limiting on the approval endpoints themselves** — an admin
  session could spam-approve; acceptable at MVP/demo scale, not for
  production.
- **`agentMcpToolHolds` duplicates a little structure with `agentMcpToolCalls`**
  (both list agent → tool grants) rather than a single richer rule shape
  with an optional "and then hold" flag. Chosen anyway: it keeps the
  existing, well-tested `PolicyMatcher.evaluate()` precedence completely
  unchanged, and mirrors ACAP's own `hold` list being separate from `scope`.

## Consequences

- `PolicyDocument`/`PolicyDecision`/`McpProxyHandler`/`LoggingMcpAuditService`
  all changed, but in additive, backward-compatible ways: existing
  `agent-a`/`agent-b` grants, existing tests' 4-arg `PolicyDocument`
  constructor calls, and existing `.allowed()` call sites all still work
  unchanged.
- The path is now open for Stage 3 (argument/field-level ACAP evaluation) to
  layer richer, argument-aware hold/deny logic on top of this same
  `PolicyDecision.Outcome`/`pending_approvals` machinery without further
  structural change.
