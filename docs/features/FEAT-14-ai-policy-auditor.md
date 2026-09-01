# FEAT-14 — AI Policy Auditor

**Maturity:** Demo-grade
**Depends on:** FEAT-02 (reads the active policy set), an LLM provider
**Feeds:** FEAT-12 (reports its own token usage)
**Detail:** [SPECS §5.9](../SPECS.md) · [ADR-007](../adr/ADR-007-policy-auditor-agent.md)

## What it does

An in-house agent that fetches the active policy set and asks a language
model to review it as a zero-trust auditor would: over-broad grants,
dangerous combinations, missing denials. It returns a written report.

It is also the system's own first-class example of a governed agent — it
reports its token spend (FEAT-12) and reads policy through an internal
endpoint that is itself protected (FEAT-16).

## Why it matters

Policy files rot quietly: a wildcard added during an incident stays for
years. A reviewer that reads the whole set on demand and explains what it
finds is cheap, and it demonstrates the product's own thesis — that an AI
agent is useful precisely when its access is explicit and bounded.

## Behaviour

**Given** a run request, **when** the policy set is fetched and reviewed,
**then** a structured report comes back describing findings, not a score.

**Given** the LLM provider is slow, **when** a run is in flight, **then** it
waits within a configured timeout without blocking other work; on timeout the
run fails cleanly.

**Given** a completed run, **when** the provider reports token counts,
**then** they are metered against this agent (FEAT-12).

**Given** no API key is configured, **when** the component starts, **then**
it fails fast rather than starting and failing per request.

## Limits

- Advisory only: findings are text for a human, never applied automatically.
- Single prompt over the whole document — no diffing between versions, no
  history of what a previous run said.
- The run endpoint is unauthenticated inside the perimeter.
- In the cloud deployment it cannot currently reach the gateway, because it
  does not trust the deployment's development certificate authority — a
  known, named gap rather than a silent failure.
