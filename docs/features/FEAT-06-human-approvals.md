# FEAT-06 — Human-in-the-Loop Approvals

**Maturity:** Working (no routing, notification or expiry — see Limits)
**Depends on:** FEAT-04 (produces holds), FEAT-02 (who may decide)
**Feeds:** FEAT-07 (decisions are audited), FEAT-10/11 (queue depth, refusals)
**Detail:** [SPECS §5.4](../SPECS.md) · [ADR-019](../adr/ADR-019-hold-decision-and-approval-queue.md), [ADR-026](../adr/ADR-026-standalone-approver-ui.md)

## What it does

Turns "hold" from a refusal into a workflow. A held call is persisted with
everything a human needs to judge it — agent, tool, arguments, and why it was
held — and waits. A person approves or denies it from either the Admin
Console or a dedicated Approval Center at its own URL, with its own login,
open to any authenticated user rather than administrators only.

Approval replays the *original* call through the same path a normal allow
would take. Denial produces an honest refusal.

## Why it matters

Every serious AI-governance conversation reaches the same question: what
happens with actions that are neither clearly safe nor clearly forbidden —
sending a client email, changing a deal? Refusing them makes the agent
useless; allowing them makes it dangerous. A durable queue plus a
one-decision-at-a-time screen is the answer, and it is what lets a business
owner rather than an engineer hold the switch.

## Behaviour

**Given** a held call, **when** an approver opens the queue, **then** they see
the agent, the tool, the exact arguments and the rule that caused the hold.

**Given** an approval, **when** it is executed, **then** the original call is
reconstructed verbatim and forwarded; the decision, the decider's username
and the outcome are all recorded.

**Given** a denial, **when** it is executed, **then** the agent receives a
refusal and the event is audited as a denial — it appears in the
out-of-policy feed exactly like a machine refusal.

**Given** a call already decided, **when** a second approver acts on it,
**then** the second action is refused with a conflict rather than executing
twice.

**Given** an agent whose session has closed, **when** its held call is
decided, **then** the decision still applies and is recorded; only the push
back to that specific connection is skipped.

**Given** an agent's own service-account token, **when** it calls the
approver API, **then** it is refused: the grants are role-scoped, and agents
hold no realm role, so an agent can never approve its own call.

## Limits

- **Anyone with a login can approve anything.** There is no approver role, no
  routing to a specific person, and the `route_to` field is stored but not
  enforced.
- No notifications: an approver must be looking at the page (it polls every
  15 seconds).
- No expiry or escalation — a held call waits indefinitely.
- No second-person rule for high-risk actions.
