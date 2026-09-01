# ADR-031: Policy-Audit Surfacing and the Activation Overlay

**Status:** Accepted
**Date:** 2026-09-01
**Stage:** 31

## Context

The AI Policy Auditor (ADR-007) existed but had no surface: an internal
endpoint returning a Markdown blob, unreachable from the console and broken
in the cloud (zt-agents does not trust the gateway's dev CA — FEAT-14's
named gap). Separately, rules could only be changed by editing the YAML and
reloading; there was no way to take a rule out of effect quickly, and no
trace of what a switched-off rule *would* have done.

The brief: a Run button on the Policies screen, results as a side panel with
per-recommendation Implement/Modify actions, audit-flagged rules highlighted
orange, a Last Audit Results view that says whether findings still apply,
and a per-rule on/off toggle where an inactive rule has no effect on match
but the event is logged and marked.

### THOUGHTS

- Wiring the button to the existing flow would ship a control that fails on
  the public demo. The dependency points the wrong way: the gateway has the
  policies and can reach zt-agents over plain HTTP inside the perimeter, so
  the gateway should PUSH the document. That kills the TLS coupling instead
  of treating it.
- Markdown findings cannot drive highlighting or buttons. The model must
  return structured findings referencing rule ids — and everything it
  returns must be treated as untrusted (invalid ids shown but flagged, raw
  text always preserved, parse failure an explicit state).
- "Are findings still actual?" must be *derived*, not stored: capture a
  content hash per referenced rule at run time, compare at read time.
  A stored status is one missed update away from lying.
- The toggle cannot live in the YAML (baked into the jar in the cloud;
  UI editing deliberately removed by ADR-012). It is an activation OVERLAY:
  the file defines what rules exist, a DB table defines whether they act.
  Evaluation stays zero-I/O via an in-memory mirror.
- Disabled-rule semantics per the brief: evaluate as if the rule were
  absent, then separately match the disabled subset and record would-have
  hits — a structured log line everywhere, plus a reason annotation on MCP
  audit rows (the chosen option; no new row types).

## Decision

1. **Flow reversal.** `POST /api/v1/agents/auditor/analyze` on zt-agents
   accepts the policy document and returns strict-JSON findings
   ({severity, title, ruleIds, recommendation, suggestedAction, suggestedYaml});
   parsing is defensive and `parseError` is an honest terminal state. The
   old `/run` endpoint is untouched.
2. **Persisted runs.** `policy_audit_runs` (V16) stores findings, the raw
   report and per-referenced-rule hashes. `PolicyAuditService` computes
   freshness at read time: ADDRESSED (rules removed, or a DISABLE_RULE
   suggestion done via the toggle), RULE_CHANGED, CURRENT.
   `POST /api/v1/admin/policy-audit/run`, `GET /latest`,
   `POST /latest/findings/{id}/acknowledge` — all ADMIN-gated.
3. **Activation overlay.** `policy_rule_overrides` (V15) +
   `PolicyActivationStore` (in-memory mirror, loaded at startup, updated on
   toggle) + `ActivePolicyEvaluator` applied at all four decision points
   (users2service routed + local, service2service, agentMcpToolCalls,
   agentMcpToolHolds). `PUT /api/v1/admin/policies/{ruleId}/enabled`
   (404 for unknown ids), `GET /api/v1/admin/policies/overrides`.
   `PolicyMatcher.matching(...)` exposes the shared predicate chain so
   inactive matching cannot drift from real matching.
4. **UI.** Run/Last-Results buttons on the Policies tab; a right-side
   findings drawer with severity, affected-rule chips, freshness chips
   (with explanations), Implement (enabled only for DISABLE_RULE with an
   existing, enabled target — executes the toggle) and Modify (reveals the
   suggested YAML with copy, acknowledges the finding). Rules referenced by
   non-ADDRESSED findings are highlighted orange; per-rule Switch with
   dimmed rows and an "inactive" chip; switching a DENY off requires an
   explicit confirm spelling out the consequence.
5. **Bonus closure of FEAT-14's gap:** `zte.gateway.ca-cert` lets
   zt-agents' gateway-facing clients (metering, legacy /run) trust the
   ZTE-CA — found necessary live when the audit's own token report failed
   against the HTTPS-only gateway; with it, the audit's cost appears on the
   CFO panel (€0.038 for one run, measured).

## Alternatives considered

- **Auto-applying suggested YAML via a second overlay** — rejected with the
  user: it moves the source of truth from the file into the DB, reversing
  ADR-012 as a side effect of a UI feature.
- **DENY rules not toggleable** — rejected: part of audit recommendations
  become un-actionable; the confirm dialog is the chosen guard.
- **A separate audit row per inactive match** — rejected as noise; the log
  line + MCP reason annotation carries the same information.
- **Storing finding freshness** — rejected; see THOUGHTS.

### CRITIQUE

- The overlay makes runtime behaviour = YAML minus DB state: a copied file
  no longer tells the whole story, and a restored DB can silently disable
  rules. Mitigated by the UI showing state and every toggle being logged
  with its author — but it is a real epistemic cost.
- A disabled DENY is an open door with a confirm dialog in front. §10 now
  carries this as a named risk; a time-boxed disable (auto-re-enable) would
  be the better control.
- LLM findings vary between runs and can be wrong (one live finding claimed
  a missing default-deny that exists in config). Advisory framing and the
  "not found" flag on unknown rule ids are the guard, not a fix.
- Inactive matching doubles the match work per category; linear-scan scale
  makes this negligible today (§10's existing note applies).
- Freshness compares per-rule content only: a finding about the *absence*
  of a rule (ADD_RULE) never goes stale mechanically.
- In the cloud, audit runs and overrides live in the ephemeral Postgres —
  a restart forgets both (consistent with ADR-027's posture, still worth
  saying).

## Consequences

- The auditor is a first-class console feature that works identically on
  localhost and demo.zteasy.tech.
- Operators can take a rule out of effect in one click, with the change
  attributed, reversible, and every suppressed match traceable.
- "Did we act on the audit?" is answerable from the screen — and the answer
  is computed from the policy document, not from anyone's bookkeeping.
