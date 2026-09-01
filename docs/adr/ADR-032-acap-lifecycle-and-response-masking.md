# ADR-032: ACAP Lifecycle Management and Response Masking

**Status:** Accepted — **amends [ADR-022](ADR-022-acap-agent-metadata-and-thresholds.md)**
**Date:** 2026-09-01
**Stage:** 32

## Context

ACAP profiles (ADR-020/ADR-022) governed what an agent may *ask for*, but the
product could not move an agent through its life: profiles were baked into
the jar, "re-authorization due" was a badge with no consequence, suspending
an agent meant deleting a file and redeploying, threshold counters vanished
on restart, and — the largest hole — the field scope applied only to
requests, so a backend returning more than it was asked for leaked past the
gate untouched (`DataMaskingFilter` had been a pass-through stub since
ADR-009).

### THOUGHTS

- The lifecycle is: create → activate → observe → re-authorize → suspend →
  retire. The middle existed; the ends did not.
- Storage is the load-bearing decision. Profile CONTENT (scope, fields,
  limits) is what a data owner signs — it must not mutate at a button click,
  and it belongs in a reviewable file. Profile STATE (operational? when is
  the next review?) is exactly what an operator must change without a
  release. Hence the same split stage 31 used for policy activation: files
  for content, a DB overlay for state.
- If overdue re-authorization is to mean anything, it needs teeth — but
  blocking a business process because a review date slipped is how controls
  get switched off entirely. Escalating every ALLOW to HOLD keeps work
  moving under supervision and puts real pressure on the review. That
  changes ADR-022's "display-only" posture, so it is recorded as an
  amendment rather than a footnote.
- Masking is the first thing in this codebase that rewrites *data* rather
  than deciding about it. The dangerous failure is silently corrupting a
  legitimate response, so the rule must be: mask only inside structures we
  actually understand; anything else passes through untouched and says so.

## Decision

**1. Lifecycle overlay.** `acap_profile_lifecycle` (V17: status ACTIVE /
SUSPENDED / RETIRED, re-authorization due override, who/when) plus
`acap_reauthorizations` (V18's sibling: append-only history — the compliance
answer to "when was this agent last reviewed, by whom"). Mirrored in memory
(`AcapLifecycleStore`) so evaluation stays zero-I/O. **No row = ACTIVE with
the file's own date**, so nothing changes until an operator acts.

**2. Enforcement** in `YamlMcpPolicyEngine`, before any rule work for the
lifecycle gate and after ACAP tightening for the overdue check:
SUSPENDED/RETIRED → every call DENIED with the state named; ACTIVE but
overdue → every ALLOW escalated to HOLD naming the date. A DENY is never
loosened by either.

**3. Response masking.** `AcapDataMaskingFilter` replaces the stub: for
`read_*` results of agents with a profile, values of properties not in
`scope.read[].fields` become `███ masked by ZTeasy`, counted and logged.
Structure-aware by design — it masks inside `properties` objects (the shape
this deployment actually fronts) and passes unknown/unparseable payloads
through unchanged, logging that it did. Marker over deletion (the chosen
option): agent and auditor both see that something was withheld.

**4. Persistent thresholds.** `acap_threshold_usage` (V18) as write-behind
for the in-memory counter, restored at startup — a restart no longer resets
an agent's daily usage, which previously made any limit bypassable by
bouncing the process.

**5. Coverage and demo hygiene.** Profiles for `agent-a` (read-only) and
`agent-b` (`writeAllowed: true`, deliberately — the example of an owned
write decision), so the governed count reflects the whole fleet. The demo
profile's `reauthDue` moved into the future: with escalation now real, a
permanently stale baked date would hold every call in every demo; the
overdue scenario is driven through the re-authorize API instead.

## Alternatives considered

- **Full profile CRUD in the database** — a true in-app lifecycle, rejected
  for the same reason ADR-012 keeps policy in YAML: the signed document
  would stop being the source of truth.
- **Files only, reload button** — no new state, but then the lifecycle is
  not *in* the application at all: statuses and review history would live in
  commits and people's heads.
- **Overdue = hard DENY** (with a grace period) — the strictest discipline,
  rejected: an overdue review is an administrative failure, and stopping the
  business outright teaches people to disable the check.
- **Dropping disallowed fields instead of masking them** — cleaner against
  metadata inference, but invisible on a demo and liable to break agents
  that expect a key to exist.

### CRITIQUE

- **The overlay compounds stage 31's epistemic cost**: runtime behaviour is
  now file minus two DB overlays. A copied profile directory no longer tells
  the whole story. Every change is attributed and logged, which is
  mitigation, not a cure.
- **Masking is only as good as its structure assumption.** A backend that
  nests data differently is passed through unmasked — safe, but silently
  less protected than an operator might assume; the log line is the only
  signal. A per-backend response schema would be the real answer.
- Masking rewrites the JSON payload, so byte-for-byte equality with the
  backend's response is lost (irrelevant here, would matter for signatures).
- **Thresholds are still not distributed**: two gateway instances each count
  in memory and merge only at startup, so a limit can be exceeded by up to
  N-1 instances' worth. Honest improvement, not a solution.
- Suspension and stage 31's rule toggle can produce the same refusal by
  different means; an operator seeing DENY must now check two places. The UI
  names the source in the reason, which is thin.
- `RETIRED` behaves identically to `SUSPENDED` — it is a label of intent,
  not a distinct mechanism. Kept because the vocabulary matters to the
  audience, but it promises more finality than it delivers.
- Found while testing, fixed here: all three in-memory overlays (this
  stage's two plus stage 31's) loaded in their constructors, racing Flyway —
  the read failed on the very run that created the table. Loading now
  happens on `ApplicationReadyEvent`.

## Amendment (same day): cross-instance overlay staleness

Deploying the lifecycle UI exposed a defect the single-instance local stack
could never show. The cloud runs **two** gateway instances (ADR-028's
browser-facing and agent-facing front doors). Suspending an agent through
the browser app updated that instance's in-memory mirror and the database —
and the agent-facing instance kept allowing the agent's calls, because an
in-memory mirror only learns about changes made through itself. Verified
live: the job ran with 6 allows against a profile the API reported as
SUSPENDED.

Both overlays (this stage's lifecycle and stage 31's rule activation) now
re-read their tables on a fixed interval (`zte.acap.lifecycle-refresh-ms`,
`zte.policy.overlay-refresh-ms`, 20s default), bounding divergence to one
interval instead of "until restart". Re-verified in the cloud: after a
suspension, every call from the agent-facing gateway was refused with
"Agent … is suspended (ACAP lifecycle)", and after resuming, traffic flowed
again with masking applied (27 field values masked in one real HubSpot
response).

This is polling, not invalidation: an operator's action can take up to the
interval to take effect everywhere, which is acceptable for lifecycle
decisions and would not be for anything latency-sensitive. A push channel
(or moving the read onto the request path with a short-lived cache) is the
proper fix if these overlays ever govern something time-critical.

## Consequences

- An agent can be suspended, resumed, re-authorized and retired from the
  console, with an append-only record of every decision.
- An overdue review has a real, proportionate consequence for the first
  time.
- The gate now controls what comes *back*, not only what is asked for.
- Daily limits survive restarts, so they are a control rather than a hint.
- Lifecycle actions are available from the Governance tab: status chip,
  Suspend (behind a confirmation naming the consequence), Resume,
  Re-authorize (date + note) and the last re-authorizations inline.
- Cloud profiles live on the mounted certs share
  (`ZTE_ACAP_PROFILES_LOCATION=file:…`), so changing an agent's scope no
  longer requires rebuilding the gateway image — reload picks it up.
