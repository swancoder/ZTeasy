# FEAT-05 — ACAP Scope Profiles

**Maturity:** Working (subset of the ACAP schema)
**Depends on:** FEAT-04 (applies on top of its decision)
**Feeds:** FEAT-04 (tightening), FEAT-06 (threshold escalations), FEAT-11 (DPO/risk panels)
**Detail:** [SPECS §5.4](../SPECS.md), [policy-schema.md](../policy-schema.md) · [ADR-020](../adr/ADR-020-acap-scope-profiles.md), [ADR-022](../adr/ADR-022-acap-agent-metadata-and-thresholds.md)

## What it does

Per-agent profiles that constrain calls by their **arguments**, not just by
tool name: which territory an agent may read, which fields it may request,
whether it may write at all, and how many times a day it may do a given
thing. Each profile also carries governance metadata — owner, deployment
date, re-authorisation due date, EU AI Act risk class.

The layer is additive: it can only turn a coarse allow into a refusal or a
hold. It can never grant something the policy engine did not.

## Why it matters

Tool-name permissions are too blunt for real data governance. "May read
contacts" is not a policy a DPO can sign off; "may read name and company for
EMEA contacts, may not write, may not export" is. This is what turns the gate
from an access control into a data-minimisation control, and it is the layer
that maps onto external governance frameworks.

## Behaviour

**Given** `read_contacts(territory=EMEA)` from an EMEA-scoped agent, **when**
evaluated, **then** the coarse decision stands and the call proceeds.

**Given** the *same tool* called with `territory=NA`, **when** evaluated,
**then** it is refused — identical permission, different arguments, different
outcome.

**Given** a request for a field outside the profile's list (say an id
number), **when** evaluated, **then** it is refused for data minimisation
even though the resource itself is readable.

**Given** a write tool called by a read-only profile, **when** evaluated,
**then** it is refused — even if the policy engine granted that tool coarsely
(the demo does exactly this on purpose, to prove the two layers interact).

**Given** an agent with no profile at all, **when** it calls anything, **then**
nothing changes: this layer is opt-in per agent.

**Given** a daily usage threshold, **when** the limit is exceeded, **then**
the next otherwise-allowed call is escalated to a hold rather than refused —
volume is a reason to involve a human, not to fail.

**Given** a profile whose re-authorisation date has passed, **when** the
dashboards render, **then** it is flagged overdue — but calls are **not**
blocked: re-authorisation is a human process, deliberately not a technical
gate.

## Limits

- Threshold counters are in-memory and reset daily; a restart forgets usage.
- The implemented schema is a subset of the source ACAP format — enough for
  territory, fields, write and volume, not the full vocabulary.
- Metadata (owner, risk class) is display-only by design; nothing enforces it.
- Field checks apply to what the agent *requests*, not to what the backend
  returns — a backend ignoring the projection would not be caught here
  (response filtering belongs to the masking stub in FEAT-04).
