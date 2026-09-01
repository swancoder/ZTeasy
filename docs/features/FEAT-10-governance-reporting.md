# FEAT-10 — Governance Reporting

**Maturity:** Working
**Depends on:** FEAT-07 (its only data source)
**Feeds:** FEAT-11, FEAT-13
**Detail:** [SPECS §5.5](../SPECS.md) · [ADR-021](../adr/ADR-021-governance-dashboard.md)

## What it does

Turns the audit trail into per-agent history: how many calls each agent had
allowed, held and denied over a chosen window, when it was last active, and a
live feed of the most recent refusals with their reasons. Exportable as a
single JSON snapshot.

Deliberately read-only, and deliberately built on the existing trail — no
second store, no separate counters that could disagree with the audit record.

## Why it matters

The approvals queue answers "what needs me now"; this answers "what has been
happening". It is the artefact for a monthly review or an auditor's question,
and the out-of-policy feed is the one place where attempted overreach by an
agent becomes visible as a pattern rather than a single log line.

## Behaviour

**Given** a reporting window, **when** activity is requested, **then** every
agent with at least one call in that window appears with its allow, hold and
deny counts and its last-activity timestamp.

**Given** a human who denied a held call, **when** the out-of-policy feed is
read, **then** that denial appears alongside machine refusals — a refusal is
a refusal regardless of who made it.

**Given** an agent that has done nothing in the window, **when** the report is
built, **then** it is absent rather than shown as zero — the report describes
observed activity, not configured agents.

**Given** an export request, **when** it completes, **then** the file contains
exactly what the screens show for that window, with no additional
aggregation.

## Limits

- Aggregation happens in memory over the window's rows — appropriate at demo
  volume, not for months of high-traffic history.
- The refusal feed is capped at the most recent entries; it is a live feed,
  not a search interface.
- Counts are only as complete as the trail, which omits requests rejected
  before authentication (FEAT-07).
