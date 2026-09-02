# FEAT-04 — MCP Tool-Call Gate

**Maturity:** Production-shaped. Since ADR-038 the backend itself refuses anyone but the gateway, so the gate is enforced by the destination rather than assumed by the network.
**Depends on:** FEAT-02 (rules), FEAT-03 (transport), FEAT-05 (argument limits), FEAT-08 (agent identities)
**Feeds:** FEAT-06 (holds), FEAT-07 (audit), FEAT-10/11 (reporting)
**Detail:** [SPECS §5.4](../SPECS.md) · [ADR-009](../adr/ADR-009-mcp-proxy-interception-layer.md), [ADR-011](../adr/ADR-011-yaml-policy-engine.md), [ADR-019](../adr/ADR-019-hold-decision-and-approval-queue.md)

## What it does

Sits in front of a Model Context Protocol server and intercepts every tool
call an agent makes. The agent believes it is talking to the MCP server; in
fact each call is stopped, identified, evaluated, and only then forwarded —
or refused, or parked for a human. Three outcomes, not two: **allow**,
**deny**, **hold**.

## Why it matters

This is the product. Everything else in ZTeasy exists so that this decision
can be made well and proven afterwards. An organisation adopting AI agents
has no natural place to answer "what is this agent allowed to actually *do*
in our systems?" — the model provider cannot answer it, and the tool server
was not built to. The gate is that place, and it works without modifying
either the agent or the MCP server.

## Behaviour

**Given** an agent with a valid token and certificate, **when** it opens a
session, **then** it receives a session id over the event stream, and the
session opening is itself recorded.

**Given** an allowed tool call, **when** it is evaluated, **then** it is
forwarded to the backend and the result is injected into the agent's open
stream.

**Given** a tool call with no grant, **when** it is evaluated, **then** the
backend is never contacted and the agent receives an error-shaped result
naming the reason — not a silent empty response.

**Given** a tool whose name matches a destructive-shape safety rule
(`delete*`, `drop*`, bulk export), **when** any agent calls it, **then** it is
denied regardless of that agent's grants.

**Given** an allowed call that also matches a hold rule, **when** it is
evaluated, **then** it is persisted for human review (FEAT-06) and the agent
receives a distinct "held" status — not an error, and not a success.

**Given** a held call whose agent has since disconnected, **when** a human
approves it, **then** the call still executes and is audited; only pushing
the result back into that closed stream is best-effort.

**Given** the transport requires a `202 Accepted` on every message,
**when** a call is denied, **then** the HTTP status stays `202` while the
*result* carries the refusal — the audit trail records it as a `403`-shaped
decision. Honesty lives in the payload and the trail, not in a status code
the protocol does not allow us to change.

## Limits

- The hop from gate to MCP backend has **no authentication of its own** —
  anything inside the network can call the backend directly, bypassing every
  decision above. Closing this is the first item on the agreed backlog.
- Session state is in-memory: one gateway instance, no shared store.
- Data masking is real as of stage 32 (values outside an agent's ACAP field
  scope are replaced with a marker), but only for response shapes it
  understands — see FEAT-05's limits.
- One configured backend at a time; the registry (FEAT-09) knows about MCP
  targets but routing is not registry-driven for MCP.
