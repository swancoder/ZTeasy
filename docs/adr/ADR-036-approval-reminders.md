# ADR-036 — Reminding before the deadline

**Status:** Accepted · 2026-09-02
**Context:** Stage 36 · completes ADR-034 (expiry) and ADR-035 (notification)

## Context

ADR-034 gave a held call a deadline. ADR-035 announced it once, when raised.
Together they created a failure mode with a timer on it: an item announced at
18:00 and expiring at 18:00 the next day gets exactly one chance to be seen, and
if that message arrives while its addressee is in a meeting, the system will
quietly deny an agent's action a day later on the grounds that nobody looked.

Adding the deadline is what made this urgent. Before it, an unseen item waited
forever — annoying, visible in a growing queue. Now it disappears on schedule.

## Decision

### Thresholds are fractions of the item's own lifetime

`zte.approvals.reminder-fractions` (default `0.5`) is a comma-separated list of
fractions of the elapsed lifetime, not a fixed lead time. The TTL is
configurable, and "an hour before expiry" is meaningless for a one-minute TTL
while "halfway" holds at any scale. Multiple fractions are allowed (`0.5,0.9`).
Blank disables reminders entirely.

### Claim first, send second

This is the part that is not obvious. Both gateway apps run the scheduler
(ADR-028: one image, two front doors).

Expiry survives that by accident of design: sweeping *changes the approval's
status*, so the second sweeper's query returns nothing. **A reminder changes
nothing about the approval**, so the same shape of loop would send one message
per instance, per interval, until the deadline — the item nobody answered would
become the item everybody mutes.

So the row is written **before** the message goes out, under
`UNIQUE (approval_id, stage) WHERE kind = 'REMINDER'`. The instance that loses
the race gets a duplicate-key error and stops, having sent nothing. The
alternative — query "was this already sent?", then send — is precisely the
check-then-act race that reads correct and fails under two instances, which this
codebase has already been bitten by once (ADR-032's overlay staleness).

A `CLAIMED` status exists for the window between claiming and settling. A row
left in that state is not a delivery; it is a crash between the two, and it says
so in the column comment.

The sweeper deliberately returns *every* passed threshold without checking what
was already sent — that check belongs to the index, which is the only one that
holds across instances.

### The reminder says what changed

Same payload discipline as ADR-035 — no call arguments — with the time pressure
made explicit: "Reminder — still undecided with 30 min left". Both consoles now
show whether the last contact was the original announcement or a reminder, and at
which stage; a bare timestamp cannot tell an operator whether anyone has been
nudged since the item was raised.

No second desktop notification: if the page is open the item is already on
screen, and a popup repeating what is visible is noise for symmetry's sake.

## Consequences

- A held call is announced when raised and again as its deadline approaches,
  through whatever channel is configured.
- Two gateway instances produce one reminder, verified in the cloud rather than
  reasoned about: with both sweeping the same threshold (one every 20s, one every
  60s), rows-per-stage equalled distinct-approvals-per-stage exactly.
- The console distinguishes "announced" from "reminded", so silence and
  persistence look different.

## Self-critique

- **A reminder only amplifies the channel that exists.** With no webhook
  configured it records a second `SKIPPED` — a second entry about silence, not a
  second chance to be seen. Honest, and worth saying plainly.
- **Precision is bounded by the sweep interval.** A short-lived item can pass a
  threshold and expire between two sweeps; only the expiry is then recorded. The
  live test showed exactly this on 4-minute items.
- **Still no retry** (ADR-035's gap, untouched). A reminder that fails is a
  reminder that failed; the row says so and nothing tries again.
- **No escalation.** Reminding the same audience louder is a different decision
  from calling in a different one, and the second has a different cost when it is
  wrong — it tells people about work that was never theirs. Left out on purpose.
- **`CLAIMED` rows are never reaped.** If an instance dies between claiming and
  sending, that stage is permanently consumed for that approval: no reminder will
  be sent and the row will sit in `CLAIMED` forever. A sweep that settles stale
  claims is the obvious repair and is not built — with one message per stage at
  demo scale, the failure is visible in the console rather than silent.
