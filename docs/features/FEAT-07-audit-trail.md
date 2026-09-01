# FEAT-07 — Unified Audit Trail

**Maturity:** Production-shaped
**Depends on:** FEAT-01, FEAT-04, FEAT-06 (all produce events)
**Feeds:** FEAT-10, FEAT-11, FEAT-13
**Detail:** [SPECS §5.5, §6](../SPECS.md) · [ADR-013](../adr/ADR-013-postgres-audit-logging.md), [ADR-017](../adr/ADR-017-dynamic-routing-and-audit.md)

## What it does

Records every zero-trust-relevant event — REST calls and agent tool calls
alike — in one table, with the caller, the target, the decision, a correlation
id and (for agent traffic) the tool and its arguments. Writes never block the
request: events go through an async sink, and a database outage degrades to a
log line instead of failing or delaying the call it describes.

## Why it matters

A governance control that cannot be proven afterwards is a claim, not a
control. One table for both traffic types is what makes "show me everything
this agent did last month" a single query rather than a correlation exercise.
The correlation id is what makes a chain of service calls readable as one
story.

## Behaviour

**Given** any proxied request, **when** it completes, **then** a row is written
asynchronously after the response, carrying the correlation id, client
address, target, method, status and derived decision.

**Given** a request arriving without a correlation id, **when** it is
processed, **then** the gate mints one and forwards it to every downstream
service, so the whole chain shares it.

**Given** an agent tool call, **when** it is decided, **then** the row also
carries the agent id, tool name, session and the call's arguments — an
allow, a deny, a hold, and a later human decision are all separate rows.

**Given** the audit database is unavailable, **when** events occur, **then**
requests continue to be served and the events are logged with a warning
rather than lost silently or retried into the request path.

**Given** console traffic and health checks, **when** they occur, **then**
they are excluded — otherwise reading the audit trail would generate audit
trail. Correlation-id handling still applies to them.

**Given** a request with no token at all, **when** it is rejected, **then**
**no row is written** — the security layer refuses it before the audit filter
runs. This is a known blind spot for true `401`s.

## Limits

- The decision recorded is derived from the final status code, so it does not
  distinguish "the gate refused this" from "the downstream service errored".
- The client address trusts a forwarding header at face value — fine behind
  the current single-hop deployment, not behind an untrusted proxy.
- The write buffer is unbounded; a long database outage grows it.
- Retention is unbounded: no archival or pruning exists.
