# FEAT-11 — Executive Dashboard

**Maturity:** Working
**Depends on:** FEAT-02 (per-panel authorisation), FEAT-07, FEAT-10, FEAT-12, FEAT-05, FEAT-09
**Feeds:** nothing — it is a read surface
**Detail:** [ADR-029](../adr/ADR-029-executive-dashboard.md)

## What it does

One page that answers "is our agent estate under control?", cut by audience:

| Audience | Sees |
|---|---|
| CEO / Board | everything: posture, spend, decisions, risk |
| CFO | LLM spend by day and by agent |
| CTO | agent activity and registry health |
| Board · Risk | risk classes, overdue re-authorisations, refusals |
| DPO | exactly which fields and territories each agent may touch |

The audiences are **real realm roles**, and each panel is a separate API path
granted to specific roles. The tabs a user sees follow their roles, but that
is a convenience: the refusal comes from the gate.

## Why it matters

Every other surface in ZTeasy is built for operators. This one is built for
the people who authorise the programme and answer for it — and it is
deliberately built so that showing it does not require granting
administrator access. A CFO can be given a login that genuinely cannot read
anything but cost.

## Behaviour

**Given** a CFO's token, **when** the spend panel is fetched, **then** it is
served; **when** the operations panel is fetched, **then** the gate refuses
it — and the UI renders that refusal as "your role isn't granted this panel"
rather than an error.

**Given** a plain user with no audience role, **when** any panel is fetched,
**then** all of them are refused.

**Given** an agent's service-account token, **when** it calls any dashboard
path, **then** it is refused — grants are role-scoped and agents hold no
realm role.

**Given** no LLM usage has ever been reported, **when** the spend tiles
render, **then** they show "not reported yet" rather than a confident zero —
a missing integration and a genuinely free month must not look identical.

**Given** an ACAP profile whose re-authorisation date has passed, **when** the
risk panel renders, **then** it is badged overdue while the agent keeps
working (see FEAT-05).

**Given** the governed-agent tile, **when** it is computed, **then** the
denominator is agents *seen in the audit window*, not agents configured — so
the number describes reality and can move as traffic changes.

## Limits

- Read-only apart from the inline approval queue.
- Windows are fixed (30 days / 24 hours) with no custom range or drill-down.
- The role→panel grants are nine near-identical policy rules; a role/path
  matrix in the policy schema would express this better.
- Figures are computed per request with no caching.
