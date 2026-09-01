# FEAT-14 — AI Policy Auditor

**Maturity:** Working
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

- Advisory only — and demonstrably so: a live run produced one finding that
  contradicts actual configuration. Findings drive suggestions and one
  narrow action (disable-a-rule), never automatic policy edits.
- Findings vary between runs; there is no diffing against a previous run.
- Since stage 31 the console runs it via the gateway (`/api/v1/admin/
  policy-audit/run`, ADMIN-gated) by PUSHING the document to the agent —
  the old TLS gap toward the gateway is closed by `zte.gateway.ca-cert`,
  and the audit's own token spend lands in FEAT-12's metering.
