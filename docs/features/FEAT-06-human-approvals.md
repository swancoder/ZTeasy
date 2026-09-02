# FEAT-06 — Human-in-the-Loop Approvals

**Maturity:** Working. Routing and expiry landed in ADR-034 (a hold rule may name `role:APPROVER`, enforced with a 403; items carry a deadline and expire into an audited terminal state). Notification landed in ADR-035: a per-viewer badge, an opt-in desktop notification and an outbound webhook, with every delivery attempt recorded. Reminders before the deadline landed in ADR-036. Still no retry for a failed delivery.
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

- **An unrouted call is still decidable by anyone with a login**, deliberately
  (ADR-026's posture). A hold rule opts into an owner with `routeTo:
  role:APPROVER`, which the gateway enforces with a 403 — but rules that don't
  set it stay open to every interactive user.
- **A failed delivery is never retried** (ADR-035). The attempt is recorded as
  `FAILED` and nothing tries again, so a transient outage at the chat provider
  loses that notification permanently.
- **A reminder only amplifies the channel that exists** (ADR-036): with no
  webhook configured it records a second `SKIPPED` — another entry about silence,
  not another chance to be seen. Its precision is bounded by the sweep interval,
  so a short-lived item can pass a threshold and expire between two sweeps.
- **No escalation**: a reminder goes to the same audience. Calling in a different
  one is a separate decision, with a different cost when it is wrong.
- A `CLAIMED` row left by an instance that died mid-send permanently consumes
  that reminder stage — visible in the console, not repaired automatically.
- One webhook URL for every audience: `role:FINANCE` cannot be sent somewhere
  different from `role:APPROVER`.
- **Expiry is swept on a timer**, so an item can read as PENDING for up to one
  sweep interval past its deadline. Deciding it in that window is refused by the
  decision path itself, so the display lags but the enforcement does not.
- Routing understands roles and usernames only. `group:` URNs are refused
  outright, with the reason shown, because a realm JWT carries no group claim.
- No escalation, no delegation, and no second-person rule for high-risk actions.
- **A late approval still executes the original call**, arguments and all — the
  deadline bounds how late, but ADR-019's model is unchanged (ADR-034).
