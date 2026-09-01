# FEAT-09 — Service Registry & Dynamic Routing

**Maturity:** Working (health check assumes a Spring-shaped endpoint)
**Depends on:** FEAT-03 (outbound certificates for probing)
**Feeds:** FEAT-01 (routes), FEAT-11 (CTO panel), FEAT-13 (Registry tab)
**Detail:** [SPECS §5.7](../SPECS.md) · [ADR-016](../adr/ADR-016-inventory-and-health-registry.md), [ADR-017](../adr/ADR-017-dynamic-routing-and-audit.md)

## What it does

A registry of everything the gate fronts — REST services and MCP servers.
Onboarding an entry makes it routable immediately, without a redeploy. The
system then probes it for an API description, keeps polling its health, and
records when real traffic last succeeded through it.

## Why it matters

Two audiences, one mechanism. Operationally it removes the "add a service,
redeploy the gateway" step. For governance it answers "what is actually
exposed through the gate right now?" — a question that is embarrassing to
answer from configuration files, and which the CTO panel and the console
answer from live state.

## Behaviour

**Given** a newly onboarded service, **when** the entry is saved, **then** the
call returns immediately and discovery runs in the background — onboarding is
never blocked by a slow or unreachable target.

**Given** a target whose API description is reachable, **when** discovery
completes, **then** the entry becomes active and the captured description is
browsable in the console.

**Given** a target that is reachable but whose description is not, **when**
discovery completes, **then** the entry is marked degraded rather than failed
— reachable enough to route, not confirmed enough to trust — and the health
poller deliberately never clears that state on its own.

**Given** an active service that stops responding, **when** the health poll
runs, **then** it is marked down; when it responds again, it recovers
automatically.

**Given** an entry that is down, **when** routes are rebuilt, **then** it is
removed from routing — which is why a health check pointed at the wrong port
silently breaks a working service (this happened in the cloud deployment; see
FEAT-15).

**Given** an operator clicking "fetch schema", **when** the target is
unreachable, **then** they get an explicit failure — stricter than the
background probe, because a person is waiting for a yes-or-no answer.

## Limits

- Health polling expects an Actuator-shaped endpoint and JSON body, so
  non-Spring backends must impersonate one (the MCP bridge does exactly that).
- Passive telemetry matches entries by exact name; a mismatch is a silent
  no-op rather than a warning.
- Stale entries are only removed by explicit deletion — unlike identities
  (FEAT-08), the registry has no reconciliation.
- MCP discovery assumes a stateless tool-list call, unverified against a
  session-only server.
