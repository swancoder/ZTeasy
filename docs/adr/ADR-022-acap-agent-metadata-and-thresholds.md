# ADR-022: ACAP Agent Metadata and Usage Thresholds

## Status
Accepted

## Context

Stage 6 (the last of the six-stage ACAP/DIGI-KAI governance-demo plan, see
ADR-019's Context for the full background) — lower priority than the demo
owner's four numbered asks (already done: Stages 1–4), but still on the
plan: surface an ACAP profile's descriptive metadata (owner, deployment
date, re-authorization due date, EU AI Act risk classification) in the
Admin Console, and implement `thresholds` — ACAP's `followup_drafts_per_day`
style per-agent usage limits that escalate a call to HOLD once exceeded.

## Decision

### Agent metadata and risk: purely additive fields, display-only

`AcapProfile` gains `agent: AcapAgentInfo` (`name`/`client`/`owner`/
`deploymentDate`/`reauthDue`) and `risk: AcapRisk` (`euAiActClass`/
`internalTier`) — both optional, both nullable, both consumed only by the
Admin Console's new "ACAP Profiles" section (Governance tab). Neither is
read by `AcapScopeEvaluator` at all; there is no enforcement tied to risk
tier or a past `reauthDue`, matching the plan's own framing ("a dashboard
badge only, no enforcement" — re-authorization is a human process, not a
technical gate). `agentId` stays a top-level field rather than moving under
`agent` (unlike the source ACAP JSON's nested `agent.id`) — see
`AcapProfile`'s own Javadoc for why: it's the primary key every lookup site
already indexes by, and nesting it would ripple through more code for no
behavioral benefit.

Dates (`deploymentDate`/`reauthDue`) are plain ISO-8601 strings, not
`LocalDate` — avoids registering a Jackson `JavaTimeModule` on {@code
AcapProfileFileLoader}'s standalone `YAMLMapper` (it doesn't auto-discover
modules the way Spring's shared `ObjectMapper` bean does) for a value
nothing in Java computes with. The one comparison that matters — "is
`reauthDue` in the past" — is a plain string compare against today's
ISO date, done in the Admin Console (`isPastDue` in `Governance.tsx`).

### Thresholds: `toolName` added explicitly, not derived from `metric`

ACAP's `thresholds: [{metric: "followup_drafts_per_day", limit: 30,
on_exceed: "hold"}]` names a *metric*, not a tool — nothing in that string
mechanically derives the tool it counts (`draft_followup`) via any naming
convention robust enough to trust for an arbitrary future metric name.
Rather than guess, `AcapThreshold` adds an explicit `toolName` field
alongside `metric` (kept for its human-readable label, matching ACAP's own
monitoring/evidence framing) — a deliberate ZTeasy-side schema addition, not
part of the literal source JSON.

Only `onExceed: "hold"` is implemented — the only value the real ACAP
example uses; nothing else has a defined meaning here, so nothing else is
handled (fails silently as "no escalation," not an error, matching this
codebase's already-established permissive stance for optional/informational
config).

### A new, separate evaluator method — not folded into `tighten`

`AcapScopeEvaluator.checkThresholds(profile, toolName, currentOutcome)` is
called by `YamlMcpPolicyEngine` only when `tighten()` (ADR-020's
territory/field/write/bulk checks) didn't already produce a DENY. Kept
distinct from `tighten()` rather than merged into it: `checkThresholds`
answers a different question ("has this agent used this tool too much
today," not "does this specific call violate scope"), and — unlike every
one of `tighten()`'s pure checks — has a real side effect (`AcapThresholdTracker`
increments on every matching call, regardless of whether it ends up
escalating anything). Mixing a side-effecting method into a set of
deliberately pure ones would make `tighten()` harder to reason about and
test in isolation, which is exactly what ADR-020 built it to be.

`checkThresholds` can *escalate* ALLOW to HOLD but never touches an
existing HOLD or DENY, and never invents an ALLOW — the same one-directional
contract `tighten()` already established, just in the opposite direction
(tightens toward DENY there; tightens toward HOLD here). The usage counter
still increments even when the call is already HOLD (from ADR-019's
`agentMcpToolHolds`) — accurate usage tracking is independent of what the
final decision shape turns out to be; only the *escalation* is conditioned
on `currentOutcome == ALLOW`.

### In-memory counter, daily reset, no persistence

`AcapThresholdTracker` — a `ConcurrentHashMap<agentId, ConcurrentHashMap<metric,
AtomicInteger>>`, reset whenever `LocalDate.now()` rolls over. Explicitly
not backed by a new table: Stage 6's own framing ("simple in-memory... with
daily reset") plus this codebase's established precedent for demo-scale,
single-instance, restart-losable in-memory state (`McpSessionManager`,
`LoggingMcpAuditService`'s sink) — a real deployment needing counts to
survive a restart, or to work across multiple gateway instances, would need
a shared store; not built here.

### Admin Console: folded into the existing Governance tab, not a new one

The plan named "Approvals or a new Agents tab" as options; chosen instead:
a new "ACAP Profiles" section within the existing **Governance** tab
(ADR-021) — same page as per-agent activity, same underlying "how is this
agent behaving" narrative, avoiding a whole new tab for what's a compact
card-per-agent display. `AdminAcapProfileController`'s `GET
/api/v1/admin/acap-profiles` now returns each profile wrapped with its
live threshold usage (`AcapProfileView`, a small controller-local record)
rather than the bare `AcapProfile` list Stage 3 originally returned — the
Admin Console needs both together to render a threshold chip.

## Self-Criticism

- **No enforcement tied to `reauthDue`/risk tier at all** — a demo agent
  whose re-authorization lapses keeps operating exactly as before; only a
  visual badge changes. Matches the plan's explicit framing, but is worth
  naming as a real gap for anything beyond a demo.
- **Threshold escalation has no visible effect in the current demo config**
  — `draft_followup` is already unconditionally held by ADR-019's
  `agentMcpToolHolds`, so the 30/day threshold currently never gets the
  chance to escalate an ALLOW (there isn't one to escalate). It's real,
  tested, wired-up capability — exercised directly by
  `YamlMcpPolicyEngineTest`'s `allowedByCoarseRule_thresholdExceeded_isEscalatedToHold`
  — but not something the live demo script will ever visibly trigger unless
  that coarse hold rule is later relaxed. Documented in the profile YAML's
  own comment.
- **`AcapThresholdTracker`'s `synchronized` methods are a single global
  lock across every agent/metric** — fine at demo scale (a handful of
  agents, low call volume), a real bottleneck at any meaningful throughput.
  Matches `PolicyMatcher`'s own "full linear scan, demo scale" precedent for
  not adding machinery this project doesn't need yet.
- **No test coverage for `AdminAcapProfileController`'s controller layer**
  beyond compiling against already-tested collaborators — same established
  precedent as every other thin admin controller in this codebase.

## Consequences

- All six stages of the original `examples-from-vlad/` plan are now
  implemented: HOLD (Stage 1), honest-deny verification (Stage 2),
  argument/field-level tightening (Stage 3), governance dashboard
  (Stage 4), the `hubspot-mcp` tool surface (Stage 5, sibling repo), and
  this stage's agent metadata/thresholds.
- `AcapProfile`'s shape is now close to feature-complete relative to the
  source ACAP JSON, modulo the deliberate omissions documented on the type
  itself (`platforms`/`audit`/`evidence`/`deny_response` — no consumer in
  this codebase).
