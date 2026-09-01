# ADR-034 — Approval routing, entitlement and expiry

**Status:** Accepted · 2026-09-01
**Context:** Stage 34 · extends ADR-019 (HOLD queue) and ADR-026 (Approval Center)

## Context

The held-call queue worked, in the sense that a human could approve or reject and
the tool call really was forwarded or refused. Three things were missing, and the
first was not "unenforced" so much as absent:

1. **`route_to` was a column nothing wrote.** It has been in `pending_approvals`
   since V13, but hold rules had no field to express an approver, so
   `PendingApprovalService.hold(...)` passed a literal `null` at every call site.
   Nothing in the product could have populated it.
2. **Anyone could decide anything.** Correct while every item was equivalent;
   wrong the moment some calls have a named owner. A customer-facing email
   approved by whoever happened to be logged in is not governance.
3. **A held call waited forever.** An approval with no deadline is not "waiting",
   it is lost — and a queue that only ever grows tells a reviewer nothing about
   what is urgent.

## Decision

### Routing is opt-in, on the rule that holds the call

`PolicyRule` gains `routeTo`, in the same URN vocabulary `source` already uses
(`role:APPROVER`, `user:jane`, and a bare name meaning a role, per ADR-014).
`PolicyDecision` carries it from the matching rule to the row, because by the time
the approval is written the rule that matched is long out of scope.

Absent `routeTo` keeps ADR-026's posture exactly: any interactive user may decide.
This matters — routing was added to name owners for the calls that warrant one,
not to close the queue. Today one rule uses it: `send_email` from the CRM agent,
routed to `role:APPROVER`.

The validator warns when `routeTo` appears anywhere but `agentMcpToolHolds`, since
only a held call has a decision to route and it would otherwise be silently ignored.

### Everyone sees the queue; not everyone may act on it

`GET /approvals` returns a per-viewer view: the row plus `canDecide`,
`refusalReason` and `secondsRemaining`, all computed at read time because
entitlement depends on the caller's token and the countdown on the clock.

Hiding routed items from people who cannot decide them was rejected. The queue's
purpose is to show that the system held something; a reviewer who can see an item
they may not decide learns something true, while an empty screen teaches them the
system did nothing.

Enforcement is in the service, not the UI: a plain `USER` posting to
`/approve` on a routed item gets **403** with the same sentence the greyed-out
button shows.

### A deadline, and expiry as an event

`expires_at` is stamped at hold time from `zte.approvals.ttl-minutes` (24h by
default, from ADR-019's own "held items reviewed daily" framing — not from a
requirement). `ApprovalExpirySweeper` moves overdue PENDING rows to `EXPIRED` on a
timer, and `decide(...)` checks the deadline itself, because between the deadline
and the next sweep a row is expired in fact but not yet in the database, and a
call must not execute in that window.

An expiry writes the same kind of audit record a human decision does. "Nobody
answered" is an outcome of the governance process, not an absence of one.

## Consequences

- A policy author can name who owns a decision, and the system enforces it.
- Held calls have a visible deadline and stop accumulating forever.
- Two approval surfaces (Admin Console, Approval Center) share one entitlement
  rule and one error contract — disagreement between them would be a governance
  bug, not a UI inconsistency.
- The `EXPIRED` status is terminal and the row stays in the queue view, greyed:
  evidence, not absence.

## Self-critique

- **`group:` cannot work and says so.** A realm JWT carries `realm_access.roles`
  and nothing about groups, so a group-routed approval is refused to everyone with
  an explicit message and a `WARN`. Silently treating it as "no match" would make a
  rule that can never work look exactly like one that does.
- **The sweep interval is not an SLA.** An item can sit past its deadline for up
  to one interval before the row changes; the decision-path check bounds the
  damage but the queue display can lag by that much.
- **24 hours is a demo number.** It comes from the narrative, not from anyone's
  policy, and it is the first thing a real deployment should change.
- **Approving late is still approving.** ADR-019's model executes the original
  call whenever the human gets to it — arguments and all. A deadline bounds how
  late that can be, but the underlying oddity (a 23-hour-old email sending on
  approval) is untouched.
- **Entitlement is coarse.** Roles and usernames only: no delegation, no
  four-eyes rule, no "the requester may not approve their own call" (which does
  not arise today, since agents hold no realm role and therefore never qualify).

## Postscript: the expiry that audited itself as an ALLOW

The first expiry recorded on the live deployment appeared in the audit trail as
**ALLOW**. `LoggingMcpAuditService` derived its effect as
`denied ? DENY : held ? HOLD : ALLOW`, so a status it did not recognise — the new
`EXPIRED` — landed on the permissive branch: a call that never ran, filed as one
that did.

Adding a terminal state to the enum is therefore not the whole change; the audit
mapping has a default branch and that branch says "allowed". `EXPIRED` now maps to
DENY with status 408 (a deny in effect, but "nobody answered in time" is a
different fact from "a human said no"), and a test pins it precisely so the next
state added has to make the same decision consciously.
