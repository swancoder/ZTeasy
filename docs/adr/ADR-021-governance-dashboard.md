# ADR-021: Governance Dashboard — Per-Agent Activity and Out-of-Policy Feed

## Status
Accepted

## Context

Stage 4 of the ACAP/DIGI-KAI governance-demo plan (see ADR-019's Context for
the full background): the demo owner's priority #4 — "a log + simple
dashboard: trace, agent, action, decision, reason." Stage 1's "Approvals"
tab already covers the live, actionable half of governance (what's pending
right now); this stage is the historical/reporting half — what has an agent
actually been doing, and what has it been denied.

## Decision

### Read-only reporting over the existing audit trail — no new table

`request_logs` (ADR-013, unified with MCP traffic by ADR-017) already
carries everything this dashboard needs: `agent_id`, `tool_name`,
`decision_effect`, `timestamp`, `message` (the deny/hold reason). Every
prior stage (ADR-011, ADR-019, ADR-020) already writes a row for every
ALLOW/DENY/HOLD/APPROVED/REJECTED decision. This stage adds no migration,
no new write path — purely two new read queries and the UI to show them.

### Aggregate in Java, not SQL — matches this codebase's own precedent

`GovernanceService.agentActivity(hours)` fetches raw MCP rows since a cutoff
(`RequestLogRepository.findByAgentIdIsNotNullAndTimestampAfterOrderByTimestampDesc`)
and groups/counts them in memory, rather than a SQL `GROUP BY` + a
constructor-projected DTO. Chosen deliberately: `InventoryService#list`
already established "join/aggregate in Java at this demo's scale" over a
native-query projection — the latter is more fragile with Spring Data R2DBC
(column-alias-to-record-component binding for arbitrary projections isn't a
pattern already proven anywhere in this codebase) for no real benefit at the
row counts a demo produces.

### Out-of-policy feed is MCP-only, deliberately narrower than the Audit Trail tab

`findTop50ByAgentIdIsNotNullAndDecisionEffectOrderByTimestampDesc("DENY")`
filters to `agent_id IS NOT NULL` — REST-traffic denials (a `USER` hitting a
`users2service`/`service2service` 403) are excluded. This is specifically an
*agent* governance view (mirrors ACAP's own `evidence.board_view.out_of_policy_attempts`,
which is about the governed agent, not human REST callers) — the existing
Audit Trail tab already covers the REST+MCP-wide view; duplicating that here
would blur what each tab is for.

`decisionEffect = "DENY"` alone (no separate `REJECTED` filter) is correct
and sufficient: `LoggingMcpAuditService` already maps a human's post-hold
`REJECTED` decision to `decisionEffect = "DENY"` (ADR-019) — a rejected hold
is exactly as much an "out-of-policy attempt" as an original policy denial,
so it belongs in this feed for free, without new logic.

### Export is a plain JSON snapshot, not a formatted compliance document

`GET /api/v1/admin/governance/report` returns exactly the two views the
dashboard already renders (`agentActivity` + `outOfPolicyAttempts`), nothing
recomputed or reformatted. A real ACAP `evidence.report: monthly_compliance`
would need its own aggregation period, retention, and probably a PDF/CSV
format aimed at a compliance audience rather than an operator — building
that now, for a single demo, would be speculative generality with no real
consumer yet. The Admin Console's "Export Report" button downloads this
JSON via a client-side `Blob`, no backend formatting work.

## Self-Criticism

- **No pagination or row cap on `agentActivity`'s underlying query** beyond
  the time window — at real-world MCP call volumes this would need one; at
  this demo's scale it's a non-issue, matching `findTop100ByOrderByTimestampDesc`'s
  own precedent of a hardcoded, un-paginated cap elsewhere in this codebase.
- **The dashboard doesn't auto-refresh** — same manual "Refresh" button
  pattern every other Admin Console tab already uses (Approvals, Registry,
  Audit Trail); a live-updating view would need SSE/WebSocket push, out of
  scope here.
- **No test coverage for `AdminGovernanceController`'s controller layer**
  beyond compiling against `GovernanceService`'s already-tested methods —
  matches this codebase's established precedent for thin admin controllers
  (`AdminPolicyController`, `AdminAcapProfileController` have none either;
  the IT suite is where thin controller wiring gets exercised).
- **`windowHours` has no upper bound validation** — a caller passing an
  absurdly large value just queries further back, no error. Acceptable:
  worst case is a slower query, not an incorrect or unsafe one.

## Consequences

- The demo's full "log + simple dashboard" ask (priority #4) is now
  implemented: an operator can see what an agent has done, what it's been
  denied, and export a snapshot — on top of Stage 1's live Approvals queue.
- All four of the demo owner's priorities from `examples-from-vlad/` are now
  addressed at the policy/backend/dashboard layer (Stages 1–4). Only ACAP
  agent metadata/thresholds (Stage 6) remains — the `hubspot-mcp` tool
  surface (Stage 5) this whole plan has been built against is done too, in
  that sibling repo.
